package com.d102.wye.presentation.simulation.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d102.wye.domain.common.BaseResult
import com.d102.wye.domain.model.AiReviewResult
import com.d102.wye.domain.model.BacktestPoint
import com.d102.wye.domain.model.EtfCountItem
import com.d102.wye.domain.model.EtfFundamentals
import com.d102.wye.domain.model.SavePortfolioParams
import com.d102.wye.domain.repository.EtfRepository
import com.d102.wye.domain.repository.PortfolioRepository
import com.d102.wye.domain.repository.SimulationRepository
import com.d102.wye.domain.state.InvestmentType
import com.d102.wye.domain.usecase.portfolio.CalculatePortfolioChartUseCase
import com.d102.wye.domain.usecase.simulation.RunSimulationUseCase
import com.d102.wye.domain.usecase.simulation.RefreshPriceHistoryCacheUseCase
import com.d102.wye.presentation.model.UiState
import com.d102.wye.presentation.simulation.model.SimulationUiModel
import com.d102.wye.presentation.simulation.model.toUiModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class SimulationViewModel @Inject constructor(
    private val simulationRepository: SimulationRepository,
    private val portfolioRepository: PortfolioRepository,
    private val etfRepository: EtfRepository,
    private val runSimulation: RunSimulationUseCase,
    private val calculatePortfolioChart: CalculatePortfolioChartUseCase,
    private val refreshPriceHistoryCache: RefreshPriceHistoryCacheUseCase
) : ViewModel() {

    private val _formState = MutableStateFlow(SimulationFormState())
    val formState: StateFlow<SimulationFormState> = _formState.asStateFlow()

    private val _simulationState = MutableStateFlow<UiState<SimulationUiModel>>(UiState.Idle)
    val simulationState: StateFlow<UiState<SimulationUiModel>> = _simulationState.asStateFlow()

    private val _overlayPoints = MutableStateFlow<List<BacktestPoint>?>(null)
    val overlayPoints: StateFlow<List<BacktestPoint>?> = _overlayPoints.asStateFlow()

    private val _showAiDialog = MutableStateFlow(false)
    val showAiDialog: StateFlow<Boolean> = _showAiDialog.asStateFlow()

    private val _aiReviewState = MutableStateFlow<UiState<AiReviewResult>>(UiState.Idle)
    val aiReviewState: StateFlow<UiState<AiReviewResult>> = _aiReviewState.asStateFlow()

    private val _showSaveDialog = MutableStateFlow(false)
    val showSaveDialog: StateFlow<Boolean> = _showSaveDialog.asStateFlow()

    private val _savePortfolioState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val savePortfolioState: StateFlow<UiState<Unit>> = _savePortfolioState.asStateFlow()

    private var lastAiReviewKey: AiReviewKey? = null

    private var calcJob: Job? = null
    private var activeCalculationId: Long? = null
    private var calculationSequence = 0L
    private var amountInputSequence = 0L

    // ─────────────────────────────────────────────────────────────────────────
    // 포트폴리오 CRUD
    // ─────────────────────────────────────────────────────────────────────────

    fun addPortfolioItems(tickers: List<String>) {
        viewModelScope.launch {
            val currentTickers = _formState.value.portfolioItems.map { it.ticker }
            val newTickers = tickers.filter { it !in currentTickers }

            if (newTickers.isNotEmpty()) {
                _simulationState.update { UiState.Loading }
                _formState.update { it.copy(isFetchingEtfInfo = true) }

                // 1. 공통 캐시 정책에 따라 필요한 가격 이력만 갱신
                when (val result = refreshPriceHistoryCache(newTickers)) {
                    is BaseResult.Success -> Unit
                    is BaseResult.Error -> {
                        _simulationState.update { UiState.Error(result.error.message) }
                        _formState.update { it.copy(isFetchingEtfInfo = false) }
                        return@launch
                    }
                }

                // 2. ETF 상세 조회 (name, per, pbr, roe, currentPrice)
                val etfDetails = newTickers.associate { ticker ->
                    ticker to when (val result = etfRepository.getEtfDetail(ticker)) {
                        is BaseResult.Success -> {
                            Timber.d("[API] ETF 상세 조회 성공 | ticker=$ticker | name=${result.data.name}")
                            result.data
                        }

                        is BaseResult.Error -> {
                            Timber.e("[API] ETF 상세 조회 실패 | ticker=$ticker | ${result.error.message}")
                            null
                        }
                    }
                }

                // 3. ETF 클러스터 조회 (섹터 비중)
                val etfClusters = newTickers.associate { ticker ->
                    ticker to when (val result = etfRepository.getEtfCluster(ticker)) {
                        is BaseResult.Success -> {
                            Timber.d("[API] ETF 클러스터 조회 성공 | ticker=$ticker | sectors=${result.data.sectors.map { it.name }}")
                            result.data.sectors
                        }

                        is BaseResult.Error -> {
                            Timber.e("[API] ETF 클러스터 조회 실패 | ticker=$ticker | ${result.error.message}")
                            emptyList()
                        }
                    }
                }

                // 4. formState 업데이트
                _formState.update { current ->
                    val items = current.portfolioItems.toMutableList()
                    tickers.forEach { ticker ->
                        if (items.none { it.ticker == ticker }) {
                            val detail = etfDetails[ticker]
                            items.add(
                                PortfolioItem(
                                    ticker = ticker,
                                    name = detail?.name ?: ticker,
                                    weight = 0,
                                    per = detail?.per ?: 0.0,
                                    pbr = detail?.pbr ?: 0.0,
                                    roe = detail?.roe ?: 0.0,
                                    currentPrice = detail?.currentPrice ?: 0L,
                                    sectors = etfClusters[ticker] ?: emptyList()
                                )
                            )
                        }
                    }
                    current.copy(portfolioItems = items, isFetchingEtfInfo = false)
                }
            } else {
                // 신규 ticker 없을 때도 formState 동기화
                _formState.update { current ->
                    val items = current.portfolioItems.toMutableList()
                    tickers.forEach { ticker ->
                        if (items.none { it.ticker == ticker }) {
                            items.add(PortfolioItem(ticker = ticker, name = ticker, weight = 0))
                        }
                    }
                    current.copy(portfolioItems = items)
                }
            }

            triggerCalculation(trigger = "portfolio_add")
        }
    }

    fun onPortfolioItemRemoved(ticker: String) {
        Timber.d("[Portfolio] ETF 제거 | ticker=$ticker")
        _formState.update { current ->
            current.copy(
                portfolioItems = current.portfolioItems.filter { it.ticker != ticker }
            )
        }
        triggerCalculation(trigger = "portfolio_remove")
    }

    fun updateItemWeight(ticker: String, newWeight: Int) {
        _formState.update { current ->
            current.copy(
                portfolioItems = current.portfolioItems.map { item ->
                    if (item.ticker == ticker) item.copy(weight = newWeight.coerceIn(0, 100))
                    else item
                }
            )
        }
        val totalWeight = _formState.value.portfolioItems.sumOf { it.weight }
        Timber.d("[Weight] ticker=$ticker | 새 비중=${newWeight}% | 전체 합계=${totalWeight}%")
        triggerCalculation(trigger = "weight")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 섹터 가중평균 계산
    // ─────────────────────────────────────────────────────────────────────────

    private fun calcWeightedSectors(items: List<PortfolioItem>): List<SectorWeightUiModel> {
        val sectorMap = mutableMapOf<String, Double>()

        items.forEach { item ->
            val weight = item.weight / 100.0
            item.sectors.forEach { sector ->
                sectorMap[sector.name] =
                    (sectorMap[sector.name] ?: 0.0) + sector.percentage * weight
            }
        }

        if (sectorMap.isEmpty()) return emptyList()

        // 상위 4개 + 나머지 "기타"로 합산
        val sorted = sectorMap.entries.sortedByDescending { it.value }
        val top4 = sorted.take(4).map { SectorWeightUiModel(name = it.key, ratio = it.value.toFloat()) }
        val othersRatio = sorted.drop(4).sumOf { it.value }.toFloat()
        return if (othersRatio > 0f) top4 + SectorWeightUiModel(name = "기타", ratio = othersRatio)
        else top4
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI 이벤트
    // ─────────────────────────────────────────────────────────────────────────

    val idleGuideMessage: StateFlow<String> = _formState.map { form ->
        when {
            form.investmentAmount.isBlank() || form.investmentPeriod.isBlank() ->
                "투자 금액과 기간을 입력하면\n수익률 그래프가 나타납니다."

            else -> "ETF를 추가하고 자산의 미래를 확인해보세요"
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "분석할 ETF를 먼저 추가해주세요."
    )

    fun onTabSelected(index: Int) =
        _formState.update { it.copy(selectedTabIndex = index) }

    fun onOverlayToggled(enabled: Boolean) {
        _formState.update { it.copy(isOverlayEnabled = enabled) }

        // ✨ 토글이 켜지면 마이데이터 계산, 꺼지면 초기화
        if (enabled) {
            val period = _formState.value.investmentPeriod.toIntOrNull() ?: 0
            if (period > 0) fetchMyDataOverlay(period)
        } else {
            _overlayPoints.value = null
        }
    }
    fun onInvestmentTypeSelected(type: InvestmentType) {
        Timber.d("[Form] 투자 방식 변경 | type=$type")
        _formState.update { it.copy(investmentType = type) }
        triggerCalculation(trigger = "investment_type")
    }

    fun onAmountChanged(amount: String) {
        val inputEventId = ++amountInputSequence
        val inputAtNanos = System.nanoTime()
        Timber.tag(SIMULATION_PERF_TAG).d(
            "amount_input | inputEventId=%d | length=%d | thread=%s",
            inputEventId,
            amount.length,
            Thread.currentThread().name
        )
        _formState.update { it.copy(investmentAmount = amount) }
        triggerCalculation(
            trigger = "amount",
            inputEventId = inputEventId,
            inputAtNanos = inputAtNanos
        )
    }

    fun onPeriodChanged(period: String) {
        _formState.update { it.copy(investmentPeriod = period) }
        triggerCalculation(trigger = "period")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AI 진단
    // ─────────────────────────────────────────────────────────────────────────

    fun onAiReviewClick() {
        _showAiDialog.value = true
        fetchAiReview()
    }

    fun onAiDialogDismiss() {
        _showAiDialog.value = false
    }

    private fun fetchAiReview() {
        val form = _formState.value
        val currentKey = createAiReviewKey(form)

        if (currentKey == lastAiReviewKey) return

        if (_aiReviewState.value is UiState.Loading) return

        val amount = (form.investmentAmount.toLongOrNull() ?: 0L) * 10_000L

        viewModelScope.launch {
            _aiReviewState.update { UiState.Loading }

            when (val result = simulationRepository.getAiPortfolioReview(
                totalAmount = amount,
                investmentType = form.investmentType,
                portfolios = form.portfolioItems.toDomain()
            )) {
                is BaseResult.Success -> {
                    lastAiReviewKey = currentKey
                    _aiReviewState.update { UiState.Success(result.data) }
                }

                is BaseResult.Error -> {
                    _aiReviewState.update { UiState.Error(result.error.message) }
                }
            }
        }
    }

    private fun createAiReviewKey(form: SimulationFormState): AiReviewKey {
        return AiReviewKey(
            investmentType = form.investmentType,
            items = form.portfolioItems
                .sortedBy { it.ticker }
                .map { it.ticker to it.weight }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 저장
    // ─────────────────────────────────────────────────────────────────────────

    fun onSaveIconClick() {
        _showSaveDialog.value = true
    }

    fun onSaveDialogDismiss() {
        _showSaveDialog.value = false
        _savePortfolioState.value = UiState.Idle
    }

    fun savePortfolio(portfolioName: String) {
        if (_savePortfolioState.value is UiState.Loading) return

        val form = _formState.value
        val amount = (form.investmentAmount.toLongOrNull() ?: 0L) * 10_000L
        val period = form.investmentPeriod.toIntOrNull() ?: 0
        val defaultName = "포트폴리오 ${LocalDate.now()}"

        Timber.d("[Save] 포트폴리오 저장 요청 | name=$portfolioName")

        viewModelScope.launch {
            _savePortfolioState.update { UiState.Loading }

            val etfs = form.portfolioItems.map { item ->
                val counts = if (item.currentPrice > 0) {
                    val raw = (amount * item.weight / 100).toDouble() / item.currentPrice
                    (raw * 10).toLong() / 10.0  // 소수점 1자리
                } else 0.0
                EtfCountItem(ticker = item.ticker, counts = counts)
            }

            when (val result = portfolioRepository.savePortfolio(
                SavePortfolioParams(
                    portfolioName = portfolioName.ifBlank { defaultName },
                    investType = form.investmentType,
                    investAmount = amount,
                    investPeriod = period,
                    etfs = etfs
                )
            )) {
                is BaseResult.Success -> {
                    Timber.d("[Save] 포트폴리오 저장 완료 | name=$portfolioName")
                    _savePortfolioState.update { UiState.Success(Unit) }
                    _showSaveDialog.value = false
                }

                is BaseResult.Error -> {
                    Timber.e("[Save] 포트폴리오 저장 실패 | ${result.error.message}")
                    _savePortfolioState.update { UiState.Error(result.error.message) }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ✨ 마이데이터 오버레이 계산 로직
    // ─────────────────────────────────────────────────────────────────────────
    private fun fetchMyDataOverlay(periodMonths: Int) {
        viewModelScope.launch {
            // 1. 내 자산(isMyData == true) 포트폴리오 ID 찾기
            val listResult = portfolioRepository.getPortfolioList()
            if (listResult !is BaseResult.Success) return@launch
            val myDataPortfolio = listResult.data.find { it.isMyData == true }

            if (myDataPortfolio == null) {
                _overlayPoints.value = emptyList() // 마이데이터가 없을 경우
                return@launch
            }

            // 2. 상세(counts) 조회
            val detailResult = portfolioRepository.getPortfolioDetail(myDataPortfolio.portfolioId)
            if (detailResult !is BaseResult.Success) return@launch
            val detail = detailResult.data

            // 3. 공통 캐시 정책에 따라 가격 이력 갱신
            val tickers = detail.counts.map { it.ticker }
            val today = LocalDate.now()
            val startDate = today.minusMonths(periodMonths.toLong()).toString()

            when (val result = refreshPriceHistoryCache(tickers)) {
                is BaseResult.Success -> Unit
                is BaseResult.Error -> {
                    Timber.e("[Overlay] 가격 이력 갱신 실패: ${result.error.message}")
                }
            }

            val priceHistories = simulationRepository.getCachedPriceHistories(tickers)

            // 4. 차트 수익률 계산
            val chartResult = calculatePortfolioChart(
                counts = detail.counts,
                priceHistories = priceHistories,
                createdAt = startDate // ✨ 투자 기간(N개월 전)을 기준으로 시작점 맞춤
            )

            _overlayPoints.value = chartResult.recentPoints
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 계산 트리거
    // ─────────────────────────────────────────────────────────────────────────

    private fun triggerCalculation(
        trigger: String,
        inputEventId: Long? = null,
        inputAtNanos: Long? = null
    ) {
        val calculationId = ++calculationSequence
        val scheduledAtNanos = System.nanoTime()
        val previousCalculationId = activeCalculationId
        calcJob?.takeIf { it.isActive }?.let { previousJob ->
            Timber.tag(SIMULATION_PERF_TAG).d(
                "cancel_requested | calculationId=%s | nextCalculationId=%d | nextTrigger=%s | thread=%s",
                previousCalculationId?.toString() ?: "none",
                calculationId,
                trigger,
                Thread.currentThread().name
            )
            previousJob.cancel()
        }

        Timber.tag(SIMULATION_PERF_TAG).d(
            "scheduled | calculationId=%d | trigger=%s | inputEventId=%s | thread=%s",
            calculationId,
            trigger,
            inputEventId?.toString() ?: "none",
            Thread.currentThread().name
        )
        activeCalculationId = calculationId
        calcJob = viewModelScope.launch {
            var phase = "debounce"
            try {
                delay(CALCULATION_DEBOUNCE_MILLIS)

                val form = _formState.value
                val totalWeight = form.portfolioItems.sumOf { it.weight }
                val amount = (form.investmentAmount.toLongOrNull() ?: 0L) * 10_000L
                val periodMonths = form.investmentPeriod.toIntOrNull() ?: 0

                if (form.portfolioItems.isEmpty() || amount <= 0L || periodMonths <= 0) {
                    Timber.tag(SIMULATION_PERF_TAG).d(
                        "skipped | calculationId=%d | reason=incomplete_input | elapsedMs=%.3f | thread=%s",
                        calculationId,
                        elapsedMillis(scheduledAtNanos),
                        Thread.currentThread().name
                    )
                    Timber.d("[Calc] 입력 미완성 → Idle")
                    _simulationState.update { UiState.Idle }
                    return@launch
                }

                if (totalWeight != 100) {
                    Timber.tag(SIMULATION_PERF_TAG).d(
                        "skipped | calculationId=%d | reason=invalid_weight | totalWeight=%d | elapsedMs=%.3f | thread=%s",
                        calculationId,
                        totalWeight,
                        elapsedMillis(scheduledAtNanos),
                        Thread.currentThread().name
                    )
                    Timber.d("[Calc] 비중 합계 미달 → Loading | totalWeight=$totalWeight%")
                    _simulationState.update { UiState.Loading }
                    return@launch
                }

                val calculationStartedAtNanos = System.nanoTime()
                Timber.tag(SIMULATION_PERF_TAG).d(
                    "started | calculationId=%d | trigger=%s | inputEventId=%s | debounceMs=%.3f | etfCount=%d | periodMonths=%d | type=%s | thread=%s",
                    calculationId,
                    trigger,
                    inputEventId?.toString() ?: "none",
                    elapsedMillis(scheduledAtNanos, calculationStartedAtNanos),
                    form.portfolioItems.size,
                    periodMonths,
                    form.investmentType,
                    Thread.currentThread().name
                )
                Timber.d("[Calc] 계산 시작 | portfolios=${form.portfolioItems.map { "${it.ticker}(${it.weight}%)" }} | amount=$amount | period=${periodMonths}개월 | type=${form.investmentType}")
                _simulationState.update { UiState.Loading }

                phase = "cache_read"
                var stageStartedAtNanos = System.nanoTime()
                val tickers = form.portfolioItems.map { it.ticker }
                val cachedHistories = simulationRepository.getCachedPriceHistories(tickers)
                Timber.tag(SIMULATION_PERF_TAG).d(
                    "stage_finished | calculationId=%d | stage=cache_read | durationMs=%.3f | pricePointCount=%d | thread=%s",
                    calculationId,
                    elapsedMillis(stageStartedAtNanos),
                    cachedHistories.values.sumOf { it.content.size },
                    Thread.currentThread().name
                )
                Timber.d("[DB] 캐시 조회 완료 | 데이터 건수=${cachedHistories.mapValues { it.value.content.size }}")

                phase = "prepare_inputs"
                stageStartedAtNanos = System.nanoTime()
                val fundamentalsMap = form.portfolioItems.associate { item ->
                    item.ticker to EtfFundamentals(
                        ticker = item.ticker,
                        per = item.per,
                        pbr = item.pbr,
                        roe = item.roe,
                        annualDividendYield = 0.0
                    )
                }
                val sectorWeights = calcWeightedSectors(form.portfolioItems)
                val portfolios = form.portfolioItems.toDomain()
                Timber.tag(SIMULATION_PERF_TAG).d(
                    "stage_finished | calculationId=%d | stage=prepare_inputs | durationMs=%.3f | thread=%s",
                    calculationId,
                    elapsedMillis(stageStartedAtNanos),
                    Thread.currentThread().name
                )

                phase = "domain_calculation"
                stageStartedAtNanos = System.nanoTime()
                when (val result = runSimulation(
                    RunSimulationUseCase.Params(
                        portfolios = portfolios,
                        investmentAmount = amount,
                        investmentType = form.investmentType,
                        periodMonths = periodMonths,
                        priceHistories = cachedHistories,
                        fundamentalsMap = fundamentalsMap
                    )
                )) {
                    is BaseResult.Success -> {
                        Timber.tag(SIMULATION_PERF_TAG).d(
                            "stage_finished | calculationId=%d | stage=domain_calculation | durationMs=%.3f | outputPointCount=%d | thread=%s",
                            calculationId,
                            elapsedMillis(stageStartedAtNanos),
                            result.data.backtestPoints.size,
                            Thread.currentThread().name
                        )
                        Timber.d("[Calc] 계산 성공 | estimatedFinalValue=${result.data.estimatedFinalValue} | totalReturn=${result.data.totalReturn}%")

                        phase = "ui_mapping"
                        stageStartedAtNanos = System.nanoTime()
                        val uiModel = result.data.toUiModel(form.investmentType, sectorWeights)
                        val uiMappingFinishedAtNanos = System.nanoTime()
                        Timber.tag(SIMULATION_PERF_TAG).d(
                            "stage_finished | calculationId=%d | stage=ui_mapping | durationMs=%.3f | thread=%s",
                            calculationId,
                            elapsedMillis(stageStartedAtNanos, uiMappingFinishedAtNanos),
                            Thread.currentThread().name
                        )

                        phase = "state_update"
                        _simulationState.update { UiState.Success(uiModel) }
                        val finishedAtNanos = System.nanoTime()
                        Timber.tag(SIMULATION_PERF_TAG).d(
                            "finished | calculationId=%d | status=success | calculationMs=%.3f | triggerToSuccessMs=%.3f | inputToSuccessMs=%s | thread=%s",
                            calculationId,
                            elapsedMillis(calculationStartedAtNanos, finishedAtNanos),
                            elapsedMillis(scheduledAtNanos, finishedAtNanos),
                            inputAtNanos?.let { "%.3f".format(elapsedMillis(it, finishedAtNanos)) } ?: "none",
                            Thread.currentThread().name
                        )

                        if (form.isOverlayEnabled) {
                            fetchMyDataOverlay(periodMonths)
                        }
                    }

                    is BaseResult.Error -> {
                        val finishedAtNanos = System.nanoTime()
                        Timber.tag(SIMULATION_PERF_TAG).d(
                            "finished | calculationId=%d | status=error | phase=%s | calculationMs=%.3f | triggerToFinishMs=%.3f | thread=%s",
                            calculationId,
                            phase,
                            elapsedMillis(calculationStartedAtNanos, finishedAtNanos),
                            elapsedMillis(scheduledAtNanos, finishedAtNanos),
                            Thread.currentThread().name
                        )
                        Timber.e("[Calc] 계산 실패 | ${result.error.message}")
                        _simulationState.update { UiState.Error(result.error.message) }
                    }
                }
            } catch (e: CancellationException) {
                Timber.tag(SIMULATION_PERF_TAG).d(
                    "cancelled | calculationId=%d | phase=%s | elapsedMs=%.3f | thread=%s",
                    calculationId,
                    phase,
                    elapsedMillis(scheduledAtNanos),
                    Thread.currentThread().name
                )
                throw e
            }
        }
    }

    private fun elapsedMillis(
        startedAtNanos: Long,
        finishedAtNanos: Long = System.nanoTime()
    ): Double = (finishedAtNanos - startedAtNanos) / NANOS_PER_MILLISECOND

    private companion object {
        const val SIMULATION_PERF_TAG = "SimulationPerf"
        const val CALCULATION_DEBOUNCE_MILLIS = 300L
        const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}

data class SimulationFormState(
    val selectedTabIndex: Int = 0,
    val isOverlayEnabled: Boolean = false,
    val investmentType: InvestmentType = InvestmentType.REGULAR_SAVING,
    val investmentAmount: String = "",
    val investmentPeriod: String = "",
    val portfolioItems: List<PortfolioItem> = emptyList(),
    val isFetchingEtfInfo: Boolean = false
)

data class SectorWeightUiModel(
    val name: String,
    val ratio: Float
)

data class AiReviewKey(
    val investmentType: InvestmentType,
    val items: List<Pair<String, Int>> // ticker + weight
)
