param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(0, 5000)]
    [int]$InputIntervalMillis,

    [ValidateRange(1, 6)]
    [int[]]$EtfCounts = @(2, 4, 6),

    [ValidateRange(1, 36)]
    [int[]]$PeriodMonthsValues = @(12, 24, 36),

    [ValidateRange(0, 1000)]
    [int]$WarmupCount = 5,

    [ValidateRange(1, 1000)]
    [int]$MeasurementCount = 20,

    [string]$DeviceSerial = "",

    [string]$OutputDirectory = "",

    [switch]$AllowDirty
)

$ErrorActionPreference = "Stop"

function Get-StageMedian {
    param(
        [object]$Summary,
        [string]$StageName
    )

    $property = $Summary.metrics.stages.PSObject.Properties[$StageName]
    if ($null -eq $property -or $null -eq $property.Value) {
        return $null
    }
    return $property.Value.medianMs
}

$wyeRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$baselineScriptPath = Join-Path $PSScriptRoot "run-simulation-baseline.ps1"
if (-not (Test-Path -LiteralPath $baselineScriptPath)) {
    throw "Baseline script를 찾을 수 없습니다: $baselineScriptPath"
}

$capturedAt = Get-Date -Format "yyyyMMdd-HHmmss"
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $wyeRoot "performance-results\scaling-$capturedAt"
}
$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $resolvedOutputDirectory -Force | Out-Null

$rows = New-Object System.Collections.Generic.List[object]

foreach ($periodMonths in $PeriodMonthsValues) {
    foreach ($etfCount in $EtfCounts) {
        $scenarioName = "etf-$etfCount-period-$periodMonths"
        $scenarioDirectory = Join-Path $resolvedOutputDirectory $scenarioName
        Write-Host "Scaling scenario 시작: ETF ${etfCount}개, 기간 ${periodMonths}개월"

        $baselineParameters = @{
            InputIntervalMillis = $InputIntervalMillis
            WarmupCount = $WarmupCount
            MeasurementCount = $MeasurementCount
            EtfCount = $etfCount
            PeriodMonths = $periodMonths
            OutputDirectory = $scenarioDirectory
        }
        if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
            $baselineParameters.DeviceSerial = $DeviceSerial
        }
        if ($AllowDirty) {
            $baselineParameters.AllowDirty = $true
        }

        & $baselineScriptPath @baselineParameters

        $summaryPath = Join-Path $scenarioDirectory "summary.json"
        if (-not (Test-Path -LiteralPath $summaryPath)) {
            throw "Scenario summary를 찾을 수 없습니다: $summaryPath"
        }
        $summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
        if (-not $summary.validationPassed) {
            throw "Scenario 검증이 실패했습니다: $scenarioName"
        }

        $rows.Add([pscustomobject][ordered]@{
            scenario = $scenarioName
            etfCount = $etfCount
            periodMonths = $periodMonths
            scenarioMedianMs = $summary.metrics.scenarioDuration.medianMs
            inputToSuccessMedianMs = $summary.metrics.inputToSuccess.medianMs
            calculationMedianMs = $summary.metrics.calculation.medianMs
            cacheReadMedianMs = Get-StageMedian -Summary $summary -StageName "cache_read"
            prepareInputsMedianMs = Get-StageMedian -Summary $summary -StageName "prepare_inputs"
            domainCalculationMedianMs = Get-StageMedian -Summary $summary -StageName "domain_calculation"
            backtestMedianMs = Get-StageMedian -Summary $summary -StageName "backtest"
            dateIntersectionMedianMs = Get-StageMedian -Summary $summary -StageName "date_intersection"
            filterAndPriceMapMedianMs = Get-StageMedian -Summary $summary -StageName "filter_and_price_map"
            calculateMedianMs = Get-StageMedian -Summary $summary -StageName "calculate"
        })
    }
}

$csvPath = Join-Path $resolvedOutputDirectory "scaling-summary.csv"
$jsonPath = Join-Path $resolvedOutputDirectory "scaling-summary.json"
$markdownPath = Join-Path $resolvedOutputDirectory "scaling-summary.md"

$rows | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding utf8
$rows | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $jsonPath -Encoding utf8

$markdown = New-Object System.Collections.Generic.List[string]
$markdown.Add("# Simulation Scaling Analysis")
$markdown.Add("")
$markdown.Add("- 입력 간격: ${InputIntervalMillis}ms")
$markdown.Add("- warm-up: ${WarmupCount}회")
$markdown.Add("- 본 측정: ${MeasurementCount}회")
$markdown.Add("- ETF 개수: $($EtfCounts -join ', ')")
$markdown.Add("- 기간: $($PeriodMonthsValues -join ', ')개월")
$markdown.Add("")
$markdown.Add("| ETF | 기간(개월) | 계산 전체(ms) | 도메인 계산(ms) | 백테스트(ms) | 날짜 교집합(ms) | 가격 Map(ms) | 계산 루프(ms) |")
$markdown.Add("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
foreach ($row in $rows) {
    $markdown.Add("| $($row.etfCount) | $($row.periodMonths) | $($row.calculationMedianMs) | $($row.domainCalculationMedianMs) | $($row.backtestMedianMs) | $($row.dateIntersectionMedianMs) | $($row.filterAndPriceMapMedianMs) | $($row.calculateMedianMs) |")
}
$markdown | Set-Content -LiteralPath $markdownPath -Encoding utf8

Write-Host "규모별 측정 완료: $resolvedOutputDirectory"
Write-Host "집계 CSV:  $csvPath"
Write-Host "집계 JSON: $jsonPath"
Write-Host "집계 문서: $markdownPath"
