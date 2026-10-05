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
    throw "adb was not found: $adbPath"
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

Write-Host "Capturing SimulationPerf logs for $DurationSeconds seconds."
Start-Sleep -Seconds $DurationSeconds

$logcatArgs = @($deviceArgs + @("logcat", "-d", "-v", "epoch", "SimulationPerf:D", "*:S"))
& $adbPath @logcatArgs | Set-Content -LiteralPath $rawPath -Encoding utf8

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

Write-Host "Raw log: $rawPath"
Write-Host "CSV log: $csvPath"
