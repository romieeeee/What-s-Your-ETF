param(
    [Parameter(Mandatory = $true)]
    [string]$RawLogPath,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,

    [ValidateRange(0, 1000)]
    [int]$ExpectedWarmupCount = 5,

    [ValidateRange(1, 1000)]
    [int]$ExpectedMeasurementCount = 20
)

$ErrorActionPreference = "Stop"

function Get-Fields {
    param([string]$Message)

    $fields = @{}
    $segments = $Message -split '\s*\|\s*'
    foreach ($segment in $segments | Select-Object -Skip 1) {
        if ($segment -match '^(?<key>[^=]+)=(?<value>.*)$') {
            $fields[$Matches.key.Trim()] = $Matches.value.Trim()
        }
    }
    return $fields
}

function Get-DoubleValue {
    param(
        [hashtable]$Fields,
        [string]$Key
    )

    if (-not $Fields.ContainsKey($Key) -or $Fields[$Key] -eq "none") {
        return $null
    }

    $value = 0.0
    if ([double]::TryParse(
        $Fields[$Key],
        [System.Globalization.NumberStyles]::Float,
        [System.Globalization.CultureInfo]::InvariantCulture,
        [ref]$value
    )) {
        return $value
    }
    return $null
}

function Get-Statistics {
    param([double[]]$Values)

    if ($null -eq $Values -or $Values.Count -eq 0) {
        return $null
    }

    $sorted = @($Values | Sort-Object)
    $middle = [math]::Floor($sorted.Count / 2)
    $median = if ($sorted.Count % 2 -eq 0) {
        ($sorted[$middle - 1] + $sorted[$middle]) / 2.0
    } else {
        $sorted[$middle]
    }

    return [ordered]@{
        count = $sorted.Count
        medianMs = [math]::Round($median, 3)
        minMs = [math]::Round($sorted[0], 3)
        maxMs = [math]::Round($sorted[-1], 3)
        rangeMs = [math]::Round($sorted[-1] - $sorted[0], 3)
    }
}

function Format-Statistics {
    param($Statistics)

    if ($null -eq $Statistics) {
        return "측정값 없음"
    }
    return "median=$($Statistics.medianMs)ms, min=$($Statistics.minMs)ms, max=$($Statistics.maxMs)ms, range=$($Statistics.rangeMs)ms, n=$($Statistics.count)"
}

$resolvedRawLogPath = (Resolve-Path -LiteralPath $RawLogPath).Path
$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $resolvedOutputDirectory -Force | Out-Null

$records = New-Object System.Collections.Generic.List[object]
$currentBatch = $null
$currentIteration = $null

foreach ($line in Get-Content -LiteralPath $resolvedRawLogPath) {
    if ($line -notmatch '^(?<timestamp>\d+\.\d+)\s+(?<pid>\d+)\s+(?<tid>\d+)\s+(?<level>[A-Z])\s+(?<tag>[^:]+):\s*(?<message>.*)$') {
        continue
    }

    $message = $Matches.message
    $event = ($message -split '\s*\|\s*', 2)[0].Trim()
    $fields = Get-Fields -Message $message

    if ($event -eq "scenario_iteration" -and $fields.phase -eq "started") {
        $currentBatch = $fields.batch
        $currentIteration = $fields.iteration
    }

    $recordBatch = $currentBatch
    $recordIteration = $currentIteration
    $records.Add([pscustomobject]@{
        timestamp = $Matches.timestamp
        pid = $Matches.pid
        tid = $Matches.tid
        level = $Matches.level
        tag = $Matches.tag.Trim()
        event = $event
        runId = $fields.runId
        batch = $recordBatch
        iteration = $recordIteration
        calculationId = $fields.calculationId
        inputEventId = $fields.inputEventId
        phase = $fields.phase
        stage = $fields.stage
        status = $fields.status
        durationMs = $fields.durationMs
        calculationMs = $fields.calculationMs
        triggerToSuccessMs = $fields.triggerToSuccessMs
        inputToSuccessMs = $fields.inputToSuccessMs
        elapsedMs = $fields.elapsedMs
        thread = $fields.thread
        message = $message
        fields = $fields
    })

    if ($event -eq "scenario_iteration" -and $fields.phase -eq "finished") {
        $currentBatch = $null
        $currentIteration = $null
    }
}

$csvPath = Join-Path $resolvedOutputDirectory "events.csv"
$records |
    Select-Object timestamp, pid, tid, level, tag, event, runId, batch, iteration,
        calculationId, inputEventId, phase, stage, status, durationMs, calculationMs,
        triggerToSuccessMs, inputToSuccessMs, elapsedMs, thread, message |
    Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding utf8

$warmupFinished = @($records | Where-Object {
    $_.event -eq "scenario_iteration" -and $_.phase -eq "finished" -and $_.batch -eq "warmup"
})
$measurementFinished = @($records | Where-Object {
    $_.event -eq "scenario_iteration" -and $_.phase -eq "finished" -and $_.batch -eq "measurement"
})
$warmupSuccess = @($records | Where-Object {
    $_.batch -eq "warmup" -and $_.event -eq "finished" -and $_.status -eq "success"
})
$measurementSuccess = @($records | Where-Object {
    $_.batch -eq "measurement" -and $_.event -eq "finished" -and $_.status -eq "success"
})
$measurementCancelled = @($records | Where-Object {
    $_.batch -eq "measurement" -and $_.event -eq "cancelled"
})
$measurementCancelRequested = @($records | Where-Object {
    $_.batch -eq "measurement" -and $_.event -eq "cancel_requested"
})

$iterationDurations = @($measurementFinished | ForEach-Object {
    Get-DoubleValue -Fields $_.fields -Key "durationMs"
} | Where-Object { $null -ne $_ })
$inputToSuccess = @($measurementSuccess | ForEach-Object {
    Get-DoubleValue -Fields $_.fields -Key "inputToSuccessMs"
} | Where-Object { $null -ne $_ })
$calculationDurations = @($measurementSuccess | ForEach-Object {
    Get-DoubleValue -Fields $_.fields -Key "calculationMs"
} | Where-Object { $null -ne $_ })

$stageStatistics = [ordered]@{}
$stageGroups = $records | Where-Object {
    $_.batch -eq "measurement" -and $_.event -match '(^|_)stage_finished$'
} | Group-Object stage
foreach ($group in $stageGroups) {
    $values = @($group.Group | ForEach-Object {
        Get-DoubleValue -Fields $_.fields -Key "durationMs"
    } | Where-Object { $null -ne $_ })
    $stageStatistics[$group.Name] = Get-Statistics -Values $values
}

$cancelRequestedById = @{}
foreach ($record in $measurementCancelRequested) {
    if (-not [string]::IsNullOrWhiteSpace($record.calculationId)) {
        $cancelRequestedById[$record.calculationId] = [double]::Parse(
            $record.timestamp,
            [System.Globalization.CultureInfo]::InvariantCulture
        )
    }
}
$cancelDelays = @($measurementCancelled | ForEach-Object {
    if ($cancelRequestedById.ContainsKey($_.calculationId)) {
        $cancelledAt = [double]::Parse(
            $_.timestamp,
            [System.Globalization.CultureInfo]::InvariantCulture
        )
        ($cancelledAt - $cancelRequestedById[$_.calculationId]) * 1000.0
    }
})

$validationErrors = New-Object System.Collections.Generic.List[string]
if ($warmupFinished.Count -ne $ExpectedWarmupCount) {
    $validationErrors.Add("warm-up 완료 횟수: 기대 $ExpectedWarmupCount, 실제 $($warmupFinished.Count)")
}
if ($warmupSuccess.Count -ne $ExpectedWarmupCount) {
    $validationErrors.Add("warm-up 성공 결과 수: 기대 $ExpectedWarmupCount, 실제 $($warmupSuccess.Count)")
}
if ($measurementFinished.Count -ne $ExpectedMeasurementCount) {
    $validationErrors.Add("본 측정 완료 횟수: 기대 $ExpectedMeasurementCount, 실제 $($measurementFinished.Count)")
}
if ($measurementSuccess.Count -ne $ExpectedMeasurementCount) {
    $validationErrors.Add("본 측정 성공 결과 수: 기대 $ExpectedMeasurementCount, 실제 $($measurementSuccess.Count)")
}
if ($inputToSuccess.Count -ne $ExpectedMeasurementCount) {
    $validationErrors.Add("본 측정 inputToSuccessMs 수: 기대 $ExpectedMeasurementCount, 실제 $($inputToSuccess.Count)")
}
foreach ($iteration in 1..$ExpectedMeasurementCount) {
    $successCount = @($measurementSuccess | Where-Object { [int]$_.iteration -eq $iteration }).Count
    if ($successCount -ne 1) {
        $validationErrors.Add("본 측정 $iteration 회차 성공 결과 수: 기대 1, 실제 $successCount")
    }
}

$summary = [ordered]@{
    validationPassed = $validationErrors.Count -eq 0
    validationErrors = @($validationErrors)
    counts = [ordered]@{
        warmupIterations = $warmupFinished.Count
        warmupSuccesses = $warmupSuccess.Count
        measurementIterations = $measurementFinished.Count
        measurementSuccesses = $measurementSuccess.Count
        measurementCancelRequests = $measurementCancelRequested.Count
        measurementCancellations = $measurementCancelled.Count
    }
    metrics = [ordered]@{
        scenarioDuration = Get-Statistics -Values $iterationDurations
        inputToSuccess = Get-Statistics -Values $inputToSuccess
        calculation = Get-Statistics -Values $calculationDurations
        cancellationDelay = Get-Statistics -Values $cancelDelays
        stages = $stageStatistics
    }
    threads = @($records | Where-Object {
        $_.batch -eq "measurement" -and -not [string]::IsNullOrWhiteSpace($_.thread)
    } | Select-Object -ExpandProperty thread -Unique)
}

$summaryJsonPath = Join-Path $resolvedOutputDirectory "summary.json"
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryJsonPath -Encoding utf8

$summaryMarkdownPath = Join-Path $resolvedOutputDirectory "summary.md"
$markdown = New-Object System.Collections.Generic.List[string]
$markdown.Add("# Simulation Baseline Summary")
$markdown.Add("")
$markdown.Add("- 검증: $(if ($summary.validationPassed) { '통과' } else { '실패' })")
$markdown.Add("- warm-up 완료/성공: $($summary.counts.warmupIterations)/$($summary.counts.warmupSuccesses)회")
$markdown.Add("- 본 측정: $($summary.counts.measurementIterations)회")
$markdown.Add("- 본 측정 성공 결과: $($summary.counts.measurementSuccesses)회")
$markdown.Add("- 취소 요청/종료: $($summary.counts.measurementCancelRequests)/$($summary.counts.measurementCancellations)회")
$markdown.Add("- 관찰 thread: $($summary.threads -join ', ')")
$markdown.Add("")
$markdown.Add("## 핵심 지표")
$markdown.Add("")
$markdown.Add("- 시나리오 전체: $(Format-Statistics $summary.metrics.scenarioDuration)")
$markdown.Add("- 마지막 입력 → 상태 갱신: $(Format-Statistics $summary.metrics.inputToSuccess)")
$markdown.Add("- 계산: $(Format-Statistics $summary.metrics.calculation)")
$markdown.Add("- 취소 요청 → 종료: $(Format-Statistics $summary.metrics.cancellationDelay)")
$markdown.Add("")
$markdown.Add("## 단계별 비용")
$markdown.Add("")
foreach ($stage in $summary.metrics.stages.Keys) {
    $markdown.Add("- ${stage}: $(Format-Statistics $summary.metrics.stages[$stage])")
}
if ($validationErrors.Count -gt 0) {
    $markdown.Add("")
    $markdown.Add("## 검증 오류")
    $markdown.Add("")
    foreach ($validationError in $validationErrors) {
        $markdown.Add("- $validationError")
    }
}
$markdown | Set-Content -LiteralPath $summaryMarkdownPath -Encoding utf8

Write-Host "이벤트 CSV: $csvPath"
Write-Host "요약 JSON:   $summaryJsonPath"
Write-Host "요약 문서:   $summaryMarkdownPath"

if (-not $summary.validationPassed) {
    throw "측정 원자료 검증이 실패했습니다: $($validationErrors -join '; ')"
}
