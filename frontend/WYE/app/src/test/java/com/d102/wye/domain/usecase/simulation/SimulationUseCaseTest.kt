package com.d102.wye.domain.usecase.simulation

import com.d102.wye.domain.common.BaseResult
import com.d102.wye.domain.model.EtfFundamentals
import com.d102.wye.domain.model.EtfPriceHistory
import com.d102.wye.domain.model.EtfPricePoint
import com.d102.wye.domain.model.Portfolio
import com.d102.wye.domain.state.InvestmentType
import java.time.LocalDate
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulationUseCaseTest {

    @Test
    fun runSimulation_usesInjectedDispatcherAndPreservesResult() = runTest {
        val today = LocalDate.now()
        val dispatcher = RecordingDispatcher(StandardTestDispatcher(testScheduler))
        val useCase = RunSimulationUseCase(
            calculateBacktest = CalculateBacktestUseCase.withReferenceDate(today),
            calculateWeightedFundamentals = CalculateWeightedFundamentalsUseCase(),
            defaultDispatcher = dispatcher,
        )

        val result = useCase(
            RunSimulationUseCase.Params(
                portfolios = listOf(
                    Portfolio(ticker = "AAA", name = "Alpha ETF", weightPercent = 100)
                ),
                investmentAmount = 10_000L,
                investmentType = InvestmentType.LUMP_SUM,
                periodMonths = 3,
                priceHistories = mapOf(
                    "AAA" to priceHistory(
                        ticker = "AAA",
                        dates = listOf(today.minusMonths(1).toString(), today.toString()),
                        prices = listOf(100L, 200L),
                    )
                ),
            )
        )

        assertTrue(dispatcher.dispatchCount > 0)
        assertTrue(result is BaseResult.Success)
        assertEquals(20_000L, (result as BaseResult.Success).data.estimatedFinalValue)
    }

    @Test
    fun expectedDividend_calculatesWeightedAnnualAndMonthlyDividend() {
        val result = CalculateExpectedDividendUseCase().invoke(
            portfolios = listOf(
                Portfolio(ticker = "AAA", name = "Alpha ETF", weightPercent = 60),
                Portfolio(ticker = "BBB", name = "Beta ETF", weightPercent = 40),
            ),
            investmentAmount = 10_000L,
            fundamentalsMap = mapOf(
                "AAA" to fundamentals("AAA", annualDividendYield = 5.0),
                "BBB" to fundamentals("BBB", annualDividendYield = 2.5),
            )
        )

        assertEquals(400L, result.annualDividend)
        assertEquals(33L, result.monthlyDividend)
    }

    @Test
    fun weightedFundamentals_calculatesWeightedAverageRoundedToOneDecimal() {
        val result = CalculateWeightedFundamentalsUseCase().invoke(
            portfolios = listOf(
                Portfolio(ticker = "AAA", name = "Alpha ETF", weightPercent = 25),
                Portfolio(ticker = "BBB", name = "Beta ETF", weightPercent = 75),
            ),
            fundamentalsMap = mapOf(
                "AAA" to fundamentals("AAA", per = 10.04, pbr = 1.04, roe = 8.04),
                "BBB" to fundamentals("BBB", per = 20.06, pbr = 2.06, roe = 12.06),
            )
        )

        assertEquals(17.6, result.per, 0.0001)
        assertEquals(1.8, result.pbr, 0.0001)
        assertEquals(11.1, result.roe, 0.0001)
    }

    @Test
    fun lumpSumBacktest_calculatesReturnFromFirstCommonDate() {
        val today = LocalDate.now()
        val dates = listOf(
            today.minusMonths(2).toString(),
            today.minusMonths(1).toString(),
            today.toString(),
        )

        val result = CalculateBacktestUseCase().invoke(
            portfolios = listOf(Portfolio(ticker = "AAA", name = "Alpha ETF", weightPercent = 100)),
            priceHistories = mapOf("AAA" to priceHistory("AAA", dates, listOf(100L, 150L, 200L))),
            investmentAmount = 10_000L,
            investmentType = InvestmentType.LUMP_SUM,
            periodMonths = 3
        )

        assertEquals(3, result.points.size)
        assertEquals(0.0, result.points.first().value, 0.0001)
        assertEquals(100.0, result.totalReturn, 0.0001)
        assertEquals(20_000L, result.estimatedFinalValue)
        assertEquals(10_000L, result.totalInvestment)
    }

    @Test
    fun backtest_returnsEmptyResultWhenThereAreNoCommonDates() {
        val result = CalculateBacktestUseCase().invoke(
            portfolios = listOf(Portfolio(ticker = "AAA", name = "Alpha ETF", weightPercent = 100)),
            priceHistories = mapOf(
                "AAA" to priceHistory("AAA", listOf("2026-01-01"), listOf(100L)),
                "BBB" to priceHistory("BBB", listOf("2026-01-02"), listOf(100L)),
            ),
            investmentAmount = 10_000L,
            investmentType = InvestmentType.LUMP_SUM,
            periodMonths = 12
        )

        assertTrue(result.points.isEmpty())
        assertEquals(0L, result.estimatedFinalValue)
        assertEquals(0.0, result.totalReturn, 0.0001)
        assertEquals(0L, result.totalInvestment)
    }

    private fun fundamentals(
        ticker: String,
        per: Double = 0.0,
        pbr: Double = 0.0,
        roe: Double = 0.0,
        annualDividendYield: Double = 0.0,
    ) = EtfFundamentals(
        ticker = ticker,
        per = per,
        pbr = pbr,
        roe = roe,
        annualDividendYield = annualDividendYield
    )

    private fun priceHistory(
        ticker: String,
        dates: List<String>,
        prices: List<Long>,
    ) = EtfPriceHistory(
        ticker = ticker,
        content = dates.zip(prices).map { (date, price) ->
            EtfPricePoint(date = date, stockPrice = price, dailyReturn = 0.0)
        },
        totalElements = dates.size,
        totalPages = 1,
        last = true
    )

    private class RecordingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        var dispatchCount: Int = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchCount++
            delegate.dispatch(context, block)
        }
    }
}
