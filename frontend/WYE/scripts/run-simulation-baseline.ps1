param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(0, 5000)]
    [int]$InputIntervalMillis,

    [ValidateRange(0, 1000)]
    [int]$WarmupCount = 5,

    [ValidateRange(1, 1000)]
    [int]$MeasurementCount = 20,

    [ValidateRange(1, 6)]
    [int]$EtfCount = 6,

    [ValidateRange(1, 36)]
    [int]$PeriodMonths = 36,

    [string]$DeviceSerial = "",

    [string]$OutputDirectory = "",

    [switch]$AllowDirty
)

$ErrorActionPreference = "Stop"

$javaHomeCandidates = @(
    $env:JAVA_HOME
    $env:ANDROID_STUDIO_JDK
    (Join-Path ${env:ProgramFiles} "Android\Android Studio\jbr")
    (Join-Path $env:LOCALAPPDATA "Programs\Android Studio\jbr")
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique
$resolvedJavaHome = $javaHomeCandidates |
    Where-Object { Test-Path -LiteralPath (Join-Path $_ "bin\java.exe") } |
    Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($resolvedJavaHome)) {
    throw "A valid JDK was not found. Set JAVA_HOME or install Android Studio with its bundled JBR."
}
if ($env:JAVA_HOME -ne $resolvedJavaHome) {
    Write-Host "Using Java from Android Studio JBR: $resolvedJavaHome"
}
$env:JAVA_HOME = $resolvedJavaHome
$javaExecutable = Join-Path $resolvedJavaHome "bin\java.exe"
$javaVersion = [System.Diagnostics.FileVersionInfo]::GetVersionInfo($javaExecutable).ProductVersion

$wyeRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
$gradlePath = Join-Path $wyeRoot "gradlew.bat"
$summaryScriptPath = Join-Path $PSScriptRoot "summarize-simulation-performance.ps1"
$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
$adbPath = if ($adbCommand) {
    $adbCommand.Source
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
}

if (-not (Test-Path -LiteralPath $gradlePath)) {
    throw "Gradle wrapper를 찾을 수 없습니다: $gradlePath"
}
if (-not (Test-Path -LiteralPath $adbPath)) {
    throw "adb를 찾을 수 없습니다: $adbPath"
}

$gitPrefix = @("-c", "safe.directory=$repositoryRoot")
$commitSha = (& git @gitPrefix rev-parse HEAD).Trim()
$branchName = (& git @gitPrefix branch --show-current).Trim()
$dirtyFiles = @(& git @gitPrefix status --porcelain)
if ($dirtyFiles.Count -gt 0 -and -not $AllowDirty) {
    throw "커밋되지 않은 변경 사항이 있습니다. 먼저 커밋하거나 -AllowDirty를 명시하세요."
}

$deviceLines = @(& $adbPath devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' })
if ($deviceLines.Count -ne 1) {
    throw "정확히 1개의 Android 기기가 연결되어야 합니다. 현재: $($deviceLines.Count)개"
}
$connectedSerial = ($deviceLines[0] -split '\s+')[0]
if (-not [string]::IsNullOrWhiteSpace($DeviceSerial) -and $DeviceSerial -ne $connectedSerial) {
    throw "요청한 기기($DeviceSerial)와 연결된 기기($connectedSerial)가 다릅니다."
}
$DeviceSerial = $connectedSerial
$deviceArgs = @("-s", $DeviceSerial)

$capturedAt = Get-Date -Format "yyyyMMdd-HHmmss"
$runId = "baseline-$capturedAt"
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $wyeRoot "performance-results\$runId"
}
$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $resolvedOutputDirectory -Force | Out-Null

$rawLogPath = Join-Path $resolvedOutputDirectory "logcat.log"
$logcatErrorPath = Join-Path $resolvedOutputDirectory "logcat-error.log"
$gradleLogPath = Join-Path $resolvedOutputDirectory "gradle.log"
$metadataPath = Join-Path $resolvedOutputDirectory "metadata.json"

$metadata = [ordered]@{
    runId = $runId
    capturedAt = (Get-Date).ToString("o")
    commitSha = $commitSha
    branch = $branchName
    workingTreeDirty = $dirtyFiles.Count -gt 0
    buildVariant = "debug"
    javaHome = $resolvedJavaHome
    javaVersion = $javaVersion
    inputIntervalMillis = $InputIntervalMillis
    warmupCount = $WarmupCount
    measurementCount = $MeasurementCount
    etfCount = $EtfCount
    periodMonths = $PeriodMonths
    device = [ordered]@{
        serial = $DeviceSerial
        model = (& $adbPath @deviceArgs shell getprop ro.product.model).Trim()
        apiLevel = (& $adbPath @deviceArgs shell getprop ro.build.version.sdk).Trim()
        androidVersion = (& $adbPath @deviceArgs shell getprop ro.build.version.release).Trim()
        fingerprint = (& $adbPath @deviceArgs shell getprop ro.build.fingerprint).Trim()
    }
    status = "running"
    gradleExitCode = $null
    completedAt = $null
}
$metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $metadataPath -Encoding utf8

& $adbPath @deviceArgs logcat -c
$logcatArguments = @($deviceArgs + @("logcat", "-d", "-v", "epoch", "SimulationPerf:D", "*:S"))

$gradleExitCode = -1
try {
    $gradleArguments = @(
        "connectedDebugAndroidTest",
        "-Pandroid.testInstrumentationRunnerArguments.class=com.d102.wye.performance.SimulationPerformanceInstrumentedTest",
        "-Pandroid.testInstrumentationRunnerArguments.runSimulationPerformance=true",
        "-Pandroid.testInstrumentationRunnerArguments.inputIntervalMillis=$InputIntervalMillis",
        "-Pandroid.testInstrumentationRunnerArguments.warmupCount=$WarmupCount",
        "-Pandroid.testInstrumentationRunnerArguments.measurementCount=$MeasurementCount",
        "-Pandroid.testInstrumentationRunnerArguments.etfCount=$EtfCount",
        "-Pandroid.testInstrumentationRunnerArguments.periodMonths=$PeriodMonths",
        "-Pandroid.testInstrumentationRunnerArguments.runId=$runId"
    )

    Push-Location $wyeRoot
    try {
        & $gradlePath @gradleArguments 2>&1 | Tee-Object -FilePath $gradleLogPath
        $gradleExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    & $adbPath @logcatArguments 2> $logcatErrorPath |
        Set-Content -LiteralPath $rawLogPath -Encoding utf8

    $metadata.gradleExitCode = $gradleExitCode
    $metadata.status = if ($gradleExitCode -eq 0) { "test_passed" } else { "test_failed" }
    $metadata.completedAt = (Get-Date).ToString("o")
    $metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $metadataPath -Encoding utf8
}

if ($gradleExitCode -ne 0) {
    throw "instrumentation 측정이 실패했습니다. Gradle 로그: $gradleLogPath"
}

try {
    & $summaryScriptPath `
        -RawLogPath $rawLogPath `
        -OutputDirectory $resolvedOutputDirectory `
        -ExpectedWarmupCount $WarmupCount `
        -ExpectedMeasurementCount $MeasurementCount
    $metadata.status = "completed"
} catch {
    $metadata.status = "validation_failed"
    throw
} finally {
    $metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $metadataPath -Encoding utf8
}

Write-Host "Baseline 측정 완료: $resolvedOutputDirectory"
