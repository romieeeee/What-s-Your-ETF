package com.d102.wye.domain.usecase.simulation

import com.d102.wye.domain.model.BacktestPoint
import com.d102.wye.domain.model.EtfPriceHistory
import com.d102.wye.domain.model.Portfolio
import com.d102.wye.domain.state.InvestmentType
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToLong
import timber.log.Timber

class CalculateBacktestUseCase @Inject constructor() {

    data class Result(
        val points: List<BacktestPoint>,
        val estimatedFinalValue: Long,
        val totalReturn: Double,
        val totalInvestment: Long
    )

    operator fun invoke(
        portfolios: List<Portfolio>,
        priceHistories: Map<String, EtfPriceHistory>,
        investmentAmount: Long,
        investmentType: InvestmentType,
        periodMonths: Int,
        referenceDate: LocalDate = LocalDate.now()
    ): Result {
        val startedAtNanos = System.nanoTime()
        Timber.tag(SIMULATION_PERF_TAG).d(
            "backtest_started | etfCount=%d | inputPointCount=%d | periodMonths=%d | type=%s | thread=%s",
            priceHistories.size,
            priceHistories.values.sumOf { it.content.size },
            periodMonths,
            investmentType,
            Thread.currentThread().name
        )

        var stageStartedAtNanos = System.nanoTime()
        val allCommonDates: List<String> = priceHistories.values
            .map { it.content.map { p -> p.date }.toSet() }
            .reduceOrNull { acc, set -> acc.intersect(set) }
            ?.sorted()
            ?: return emptyResult("missing_history", startedAtNanos)
        Timber.tag(SIMULATION_PERF_TAG).d(
            "backtest_stage_finished | stage=date_intersection | durationMs=%.3f | commonDateCount=%d | thread=%s",
            elapsedMillis(stageStartedAtNanos),
            allCommonDates.size,
            Thread.currentThread().name
        )

        if (allCommonDates.isEmpty()) return emptyResult("no_common_dates", startedAtNanos)

        stageStartedAtNanos = System.nanoTime()
        val startDate = referenceDate
            .minusMonths(periodMonths.toLong())
            .toString()
        val commonDates = allCommonDates.filter { it >= startDate }

        if (commonDates.isEmpty()) return emptyResult("no_dates_in_period", startedAtNanos)

        val priceMap: Map<String, Map<String, Double>> =
            priceHistories.mapValues { (_, history) ->
                history.content.associate { it.date to it.stockPrice.toDouble() }
            }
        Timber.tag(SIMULATION_PERF_TAG).d(
            "backtest_stage_finished | stage=filter_and_price_map | durationMs=%.3f | filteredDateCount=%d | thread=%s",
            elapsedMillis(stageStartedAtNanos),
            commonDates.size,
            Thread.currentThread().name
        )

        stageStartedAtNanos = System.nanoTime()
        val result = when (investmentType) {
            InvestmentType.REGULAR_SAVING -> calcInstallment(
                portfolios, commonDates, priceMap, investmentAmount, periodMonths
            )
            InvestmentType.LUMP_SUM -> calcLumpSum(
                portfolios, commonDates, priceMap, investmentAmount
            )
        }
        Timber.tag(SIMULATION_PERF_TAG).d(
            "backtest_stage_finished | stage=calculate | durationMs=%.3f | rawPointCount=%d | thread=%s",
            elapsedMillis(stageStartedAtNanos),
            result.points.size,
            Thread.currentThread().name
        )

        stageStartedAtNanos = System.nanoTime()
        val downsampledResult = result.copy(points = downsample(result.points, MAX_CHART_POINT_COUNT))
        Timber.tag(SIMULATION_PERF_TAG).d(
            "backtest_stage_finished | stage=downsample | durationMs=%.3f | outputPointCount=%d | thread=%s",
            elapsedMillis(stageStartedAtNanos),
            downsampledResult.points.size,
            Thread.currentThread().name
        )
        Timber.tag(SIMULATION_PERF_TAG).d(
            "backtest_finished | status=success | durationMs=%.3f | thread=%s",
            elapsedMillis(startedAtNanos),
            Thread.currentThread().name
        )
        return downsampledResult
    }

    private fun emptyResult(reason: String, startedAtNanos: Long): Result {
        Timber.tag(SIMULATION_PERF_TAG).d(
            "backtest_finished | status=empty | reason=%s | durationMs=%.3f | thread=%s",
            reason,
            elapsedMillis(startedAtNanos),
            Thread.currentThread().name
        )
        return Result(emptyList(), 0L, 0.0, 0L)
    }

    private fun elapsedMillis(startedAtNanos: Long): Double =
        (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND

    /**
     * 적립형
     * y축 = 수익률 (%)
     *
     * 매입 방식:
     *   매월 거래일 평균 단가로 해당 월 투자금 / 평균단가 = 수량 누적
     *
     * 포인트 방식:
     *   매일 보유 수량 × 당일 주가 = 일별 평가금액
     *   수익률 = (일별 평가금액 - 누적 납입금) / 누적 납입금 × 100
     *   → 1개월도 약 20포인트 → 차트 표시 가능
     */
    private fun calcInstallment(
        portfolios: List<Portfolio>,
        dates: List<String>,
        priceMap: Map<String, Map<String, Double>>,
        monthlyAmount: Long,
        periodMonths: Int
    ): Result {
        val accumulatedShares = mutableMapOf<String, Double>()
        portfolios.forEach { accumulatedShares[it.ticker] = 0.0 }

        // 월별 그룹핑 (periodMonths만큼만)
        val datesByMonth = dates.groupBy { it.take(7) }
            .entries
            .sortedBy { it.key }
            .takeLast(periodMonths)

        // 월별 누적 납입금 맵 (날짜 → 해당 시점까지 납입금)
        // 각 월의 첫 거래일부터 그 달 납입금이 적용됨
        val cumulativeInvestByDate = mutableMapOf<String, Long>()
        var monthCount = 0

        datesByMonth.forEach { (_, monthDates) ->
            monthCount++
            val cumulativeInvest = monthlyAmount * monthCount

            // 이 달의 매입 처리
            portfolios.forEach { portfolio ->
                val ticker = portfolio.ticker
                val weight = portfolio.weightPercent / 100.0
                val monthlyInvestPerEtf = monthlyAmount * weight
                val monthlyPrices = monthDates.mapNotNull { priceMap[ticker]?.get(it) }
                if (monthlyPrices.isEmpty()) return@forEach
                val avgPrice = monthlyPrices.average()
                if (avgPrice <= 0) return@forEach
                accumulatedShares[ticker] = (accumulatedShares[ticker] ?: 0.0) + (monthlyInvestPerEtf / avgPrice)
            }

            // 이 달의 모든 날짜에 누적 납입금 기록
            monthDates.forEach { date ->
                cumulativeInvestByDate[date] = cumulativeInvest
            }
        }

        // 일별 포인트 생성 (보유 수량 × 당일 주가 → 수익률)
        val allDatesInPeriod = datesByMonth.flatMap { it.value }
        val points = mutableListOf<BacktestPoint>()

        // 월별로 순회하며 각 달의 매입 후 일별 평가금액 계산
        val runningShares = mutableMapOf<String, Double>()
        portfolios.forEach { runningShares[it.ticker] = 0.0 }
        var runningMonthCount = 0

        datesByMonth.forEach { (_, monthDates) ->
            runningMonthCount++

            // 이 달 매입 처리
            portfolios.forEach { portfolio ->
                val ticker = portfolio.ticker
                val weight = portfolio.weightPercent / 100.0
                val monthlyInvestPerEtf = monthlyAmount * weight
                val monthlyPrices = monthDates.mapNotNull { priceMap[ticker]?.get(it) }
                if (monthlyPrices.isEmpty()) return@forEach
                val avgPrice = monthlyPrices.average()
                if (avgPrice <= 0) return@forEach
                runningShares[ticker] = (runningShares[ticker] ?: 0.0) + (monthlyInvestPerEtf / avgPrice)
            }

            val cumulativeInvest = monthlyAmount * runningMonthCount

            // 이 달의 일별 포인트
            monthDates.forEach { date ->
                val portfolioValue = portfolios.sumOf { portfolio ->
                    val shares = runningShares[portfolio.ticker] ?: 0.0
                    val price = priceMap[portfolio.ticker]?.get(date) ?: 0.0
                    shares * price
                }
                val returnRate = if (cumulativeInvest > 0)
                    (portfolioValue - cumulativeInvest) / cumulativeInvest * 100.0
                else 0.0
                points.add(BacktestPoint(date = date, value = returnRate))
            }
        }

        val actualTotalInvestment = monthlyAmount * runningMonthCount
        val finalEndValue = if (points.isNotEmpty()) {
            // 마지막 날 절대 평가금액 역산
            val lastReturn = points.last().value
            (actualTotalInvestment * (1.0 + lastReturn / 100.0)).roundToLong()
        } else 0L
        val totalReturn = points.lastOrNull()?.value ?: 0.0

        return Result(
            points = points,
            estimatedFinalValue = finalEndValue,
            totalReturn = totalReturn,
            totalInvestment = actualTotalInvestment
        )
    }

    /**
     * 거치형
     * y축 = 수익률 (%)
     *   초기 투자 후 일별 수익률 = (일별 평가금액 - 초기 투자금) / 초기 투자금 × 100
     */
    private fun calcLumpSum(
        portfolios: List<Portfolio>,
        dates: List<String>,
        priceMap: Map<String, Map<String, Double>>,
        initialAmount: Long
    ): Result {
        val firstDate = dates.first()
        val shares = mutableMapOf<String, Double>()

        portfolios.forEach { portfolio ->
            val ticker = portfolio.ticker
            val weight = portfolio.weightPercent / 100.0
            val investPerEtf = initialAmount * weight
            val firstPrice = priceMap[ticker]?.get(firstDate) ?: return@forEach
            if (firstPrice > 0) shares[ticker] = investPerEtf / firstPrice
        }

        val points = dates.map { date ->
            val portfolioValue = portfolios.sumOf { portfolio ->
                val s = shares[portfolio.ticker] ?: 0.0
                val price = priceMap[portfolio.ticker]?.get(date) ?: 0.0
                s * price
            }
            val returnRate = if (initialAmount > 0)
                (portfolioValue - initialAmount) / initialAmount * 100.0
            else 0.0
            BacktestPoint(date = date, value = returnRate)
        }

        val finalReturn = points.lastOrNull()?.value ?: 0.0
        val estimatedFinalValue = (initialAmount * (1.0 + finalReturn / 100.0)).roundToLong()

        return Result(
            points = points,
            estimatedFinalValue = estimatedFinalValue,
            totalReturn = finalReturn,
            totalInvestment = initialAmount
        )
    }

    private companion object {
        const val SIMULATION_PERF_TAG = "SimulationPerf"
        const val MAX_CHART_POINT_COUNT = 120
        const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}

private fun downsample(points: List<BacktestPoint>, maxCount: Int): List<BacktestPoint> {
    if (points.size <= maxCount) return points
    val step = points.size.toFloat() / maxCount
    return (0 until maxCount).map { i ->
        points[(i * step).toInt().coerceAtMost(points.size - 1)]
    } + listOf(points.last())
}
