package com.d102.wye.domain.usecase.simulation

import com.d102.wye.domain.common.ApiError
import com.d102.wye.domain.common.BaseResult
import com.d102.wye.domain.model.EtfFundamentals
import com.d102.wye.domain.model.EtfPriceHistory
import com.d102.wye.domain.model.Portfolio
import com.d102.wye.domain.model.SimulationResult
import com.d102.wye.domain.state.InvestmentType
import javax.inject.Inject
import timber.log.Timber

class RunSimulationUseCase @Inject constructor(
    private val calculateBacktest: CalculateBacktestUseCase,
    private val calculateWeightedFundamentals: CalculateWeightedFundamentalsUseCase
) {

    data class Params(
        val portfolios: List<Portfolio>,
        val investmentAmount: Long,
        val investmentType: InvestmentType,
        val periodMonths: Int,
        val priceHistories: Map<String, EtfPriceHistory>,
        val fundamentalsMap: Map<String, EtfFundamentals> = emptyMap(),
        val startDate: String? = null,
        val endDate: String? = null
    )

    suspend operator fun invoke(params: Params): BaseResult<SimulationResult> {
        val startedAtNanos = System.nanoTime()
        if (params.priceHistories.isEmpty()) {
            Timber.tag(SIMULATION_PERF_TAG).d(
                "use_case_finished | status=empty_price_history | durationMs=%.3f | thread=%s",
                elapsedMillis(startedAtNanos),
                Thread.currentThread().name
            )
            return BaseResult.Error(
                ApiError(code = -1, message = "가격 이력 데이터가 없습니다")
            )
        }

        var stageStartedAtNanos = System.nanoTime()
        val backtestResult = calculateBacktest(
            portfolios = params.portfolios,
            priceHistories = params.priceHistories,
            investmentAmount = params.investmentAmount,
            investmentType = params.investmentType,
            periodMonths = params.periodMonths
        )
        Timber.tag(SIMULATION_PERF_TAG).d(
            "use_case_stage_finished | stage=backtest | durationMs=%.3f | outputPointCount=%d | thread=%s",
            elapsedMillis(stageStartedAtNanos),
            backtestResult.points.size,
            Thread.currentThread().name
        )

        // per/pbr/roe 가중평균 계산
        stageStartedAtNanos = System.nanoTime()
        val fundamentals = calculateWeightedFundamentals(
            portfolios = params.portfolios,
            fundamentalsMap = params.fundamentalsMap
        )
        Timber.tag(SIMULATION_PERF_TAG).d(
            "use_case_stage_finished | stage=weighted_fundamentals | durationMs=%.3f | thread=%s",
            elapsedMillis(stageStartedAtNanos),
            Thread.currentThread().name
        )

        val result = BaseResult.Success(
            SimulationResult(
                backtestPoints = backtestResult.points,
                fundamentals = fundamentals,
                expectedAnnualDividend = 0L,   // TODO: 배당 API 연동 후 채우기
                expectedMonthlyDividend = 0L,
                estimatedFinalValue = backtestResult.estimatedFinalValue,
                totalReturn = backtestResult.totalReturn,
                totalInvestment = backtestResult.totalInvestment
            )
        )
        Timber.tag(SIMULATION_PERF_TAG).d(
            "use_case_finished | status=success | durationMs=%.3f | thread=%s",
            elapsedMillis(startedAtNanos),
            Thread.currentThread().name
        )
        return result
    }

    private fun elapsedMillis(startedAtNanos: Long): Double =
        (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND

    private companion object {
        const val SIMULATION_PERF_TAG = "SimulationPerf"
        const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}
