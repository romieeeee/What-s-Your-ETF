param(
    [ValidateRange(1, 3600)]
    [int]$DurationSeconds = 60,

    [string]$DeviceSerial = "",

    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"

$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
$adbPath = if ($adbCommand) {
    $adbCommand.Source
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
}

if (-not (Test-Path -LiteralPath $adbPath)) {
    throw "adb를 찾을 수 없습니다: $adbPath"
}

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot "..\performance-results"
}

$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $resolvedOutputDirectory -Force | Out-Null

$capturedAt = Get-Date -Format "yyyyMMdd-HHmmss"
$rawPath = Join-Path $resolvedOutputDirectory "simulation-$capturedAt.log"
$csvPath = Join-Path $resolvedOutputDirectory "simulation-$capturedAt.csv"
$deviceArgs = if ([string]::IsNullOrWhiteSpace($DeviceSerial)) { @() } else { @("-s", $DeviceSerial) }

& $adbPath @deviceArgs logcat -c

$logcatArgs = @($deviceArgs + @("logcat", "-v", "epoch", "SimulationPerf:D", "*:S"))
$logcatProcess = Start-Process `
    -FilePath $adbPath `
    -ArgumentList $logcatArgs `
    -RedirectStandardOutput $rawPath `
    -NoNewWindow `
    -PassThru

try {
    Write-Host "SimulationPerf 로그를 $DurationSeconds 초 동안 수집합니다."
    Start-Sleep -Seconds $DurationSeconds
} finally {
    if (-not $logcatProcess.HasExited) {
        Stop-Process -Id $logcatProcess.Id
        $logcatProcess.WaitForExit()
    }
}

$records = Get-Content -LiteralPath $rawPath | ForEach-Object {
    if ($_ -match '^(?<timestamp>\d+\.\d+)\s+(?<pid>\d+)\s+(?<tid>\d+)\s+(?<level>[A-Z])\s+(?<tag>[^:]+):\s*(?<message>.*)$') {
        $message = $Matches.message
        [pscustomobject]@{
            timestamp = $Matches.timestamp
            pid = $Matches.pid
            tid = $Matches.tid
            level = $Matches.level
            tag = $Matches.tag.Trim()
            event = ($message -split '\|', 2)[0].Trim()
            message = $message
        }
    }
}

$records | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding utf8

Write-Host "원본 로그: $rawPath"
Write-Host "CSV 로그:  $csvPath"
