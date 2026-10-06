param(
    [ValidateRange(0, 5000)]
    [int]$InputIntervalMillis = 227,

    [ValidateRange(0, 1000)]
    [int]$WarmupCount = 5,

    [ValidateRange(1, 20)]
    [int]$MeasurementCount = 3,

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
    throw "Gradle wrapper was not found: $gradlePath"
}
if (-not (Test-Path -LiteralPath $summaryScriptPath)) {
    throw "Summary script was not found: $summaryScriptPath"
}
if (-not (Test-Path -LiteralPath $adbPath)) {
    throw "adb was not found: $adbPath"
}

$gitPrefix = @("-c", "safe.directory=$repositoryRoot")
$commitSha = (& git @gitPrefix rev-parse HEAD).Trim()
$branchName = (& git @gitPrefix branch --show-current).Trim()
$dirtyFiles = @(& git @gitPrefix status --porcelain)
if ($dirtyFiles.Count -gt 0 -and -not $AllowDirty) {
    throw "Uncommitted changes exist. Commit them first or use -AllowDirty for a diagnostic run."
}

$deviceLines = @(& $adbPath devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' })
if ($deviceLines.Count -ne 1) {
    throw "Exactly one Android device must be connected. Connected: $($deviceLines.Count)"
}
$connectedSerial = ($deviceLines[0] -split '\s+')[0]
if (-not [string]::IsNullOrWhiteSpace($DeviceSerial) -and $DeviceSerial -ne $connectedSerial) {
    throw "Requested device '$DeviceSerial' does not match connected device '$connectedSerial'."
}
$DeviceSerial = $connectedSerial
$deviceArgs = @("-s", $DeviceSerial)

$capturedAt = Get-Date -Format "yyyyMMdd-HHmmss"
$runId = "trace-$capturedAt"
$traceFileName = "$runId.trace"
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $wyeRoot "performance-results\$runId"
}
$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $resolvedOutputDirectory -Force | Out-Null

$rawLogPath = Join-Path $resolvedOutputDirectory "logcat.log"
$logcatErrorPath = Join-Path $resolvedOutputDirectory "logcat-error.log"
$gradleLogPath = Join-Path $resolvedOutputDirectory "gradle.log"
$metadataPath = Join-Path $resolvedOutputDirectory "metadata.json"
$localTracePath = Join-Path $resolvedOutputDirectory $traceFileName
$remoteTracePath = "/data/local/tmp/$traceFileName"

$metadata = [ordered]@{
    runId = $runId
    capturedAt = (Get-Date).ToString("o")
    commitSha = $commitSha
    branch = $branchName
    workingTreeDirty = $dirtyFiles.Count -gt 0
    diagnosticOnly = $true
    timingWarning = "Method sampling adds profiler overhead. Do not compare these timings with the official baseline."
    buildVariant = "debug"
    javaHome = $resolvedJavaHome
    javaVersion = $javaVersion
    inputIntervalMillis = $InputIntervalMillis
    warmupCount = $WarmupCount
    measurementCount = $MeasurementCount
    traceSampleIntervalMicros = 1000
    traceFileName = $traceFileName
    traceBytes = 0
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
$gradleExitCode = -1
try {
    $gradleArguments = @(
        "connectedDebugAndroidTest",
        "-Pandroid.testInstrumentationRunnerArguments.class=com.d102.wye.performance.SimulationPerformanceInstrumentedTest",
        "-Pandroid.testInstrumentationRunnerArguments.runSimulationPerformance=true",
        "-Pandroid.testInstrumentationRunnerArguments.inputIntervalMillis=$InputIntervalMillis",
        "-Pandroid.testInstrumentationRunnerArguments.warmupCount=$WarmupCount",
        "-Pandroid.testInstrumentationRunnerArguments.measurementCount=$MeasurementCount",
        "-Pandroid.testInstrumentationRunnerArguments.runId=$runId",
        "-Pandroid.testInstrumentationRunnerArguments.captureMethodTrace=true",
        "-Pandroid.testInstrumentationRunnerArguments.traceFileName=$traceFileName"
    )

    Push-Location $wyeRoot
    try {
        & $gradlePath @gradleArguments 2>&1 | Tee-Object -FilePath $gradleLogPath
        $gradleExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    & $adbPath @deviceArgs logcat -d -v epoch "SimulationPerf:D" "*:S" 2> $logcatErrorPath |
        Set-Content -LiteralPath $rawLogPath -Encoding utf8

    $metadata.gradleExitCode = $gradleExitCode
    $metadata.status = if ($gradleExitCode -eq 0) { "test_passed" } else { "test_failed" }
    $metadata.completedAt = (Get-Date).ToString("o")
    $metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $metadataPath -Encoding utf8
}

if ($gradleExitCode -ne 0) {
    throw "Instrumentation trace capture failed. Gradle log: $gradleLogPath"
}

& $adbPath @deviceArgs pull $remoteTracePath $localTracePath | Out-Host
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $localTracePath)) {
    throw "Trace pull failed: $remoteTracePath"
}
$traceItem = Get-Item -LiteralPath $localTracePath
if ($traceItem.Length -le 0) {
    throw "Trace file is empty: $localTracePath"
}

& $summaryScriptPath `
    -RawLogPath $rawLogPath `
    -OutputDirectory $resolvedOutputDirectory `
    -ExpectedWarmupCount $WarmupCount `
    -ExpectedMeasurementCount $MeasurementCount

$metadata.traceBytes = $traceItem.Length
$metadata.status = "completed"
$metadata.completedAt = (Get-Date).ToString("o")
$metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $metadataPath -Encoding utf8

Write-Host "Diagnostic method trace completed: $resolvedOutputDirectory"
Write-Host "Trace file: $localTracePath"
Write-Host "Do not use trace-run timings as official Before/After measurements."
