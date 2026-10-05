package com.d102.wye.domain.usecase.simulation

import com.d102.wye.domain.model.EtfPriceHistory
import com.d102.wye.domain.model.EtfPricePoint
import com.d102.wye.domain.model.Portfolio
import com.d102.wye.domain.state.InvestmentType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CalculateBacktestUseCaseTest {

    private val useCase = CalculateBacktestUseCase()

    @Test
    fun `주입된 기준일을 기준으로 백테스트 기간을 계산한다`() {
        // given
        val portfolio = listOf(
            Portfolio(
                ticker = "TEST",
                name = "테스트 ETF",
                weightPercent = 100
            )
        )

        val priceHistory = EtfPriceHistory(
            ticker = "TEST",
            content = listOf(
                EtfPricePoint(
                    date = "2023-10-01",
                    stockPrice = 9_900L,
                    dailyReturn = 0.0
                ),
                EtfPricePoint(
                    date = "2023-10-02",
                    stockPrice = 10_000L,
                    dailyReturn = 1.0
                ),
                EtfPricePoint(
                    date = "2026-10-02",
                    stockPrice = 20_000L,
                    dailyReturn = 1.0
                )
            ),
            totalElements = 3,
            totalPages = 1,
            last = true
        )

        // when
        val result = useCase(
            portfolios = portfolio,
            priceHistories = mapOf("TEST" to priceHistory),
            investmentAmount = 10_000_000L,
            investmentType = InvestmentType.LUMP_SUM,
            periodMonths = 36,
            referenceDate = LocalDate.of(2026, 10, 2)
        )

        // then
        assertEquals("2023-10-02", result.points.first().date)
    }

    @Test
    fun `생성자에 고정한 기준일을 기본 계산에 사용한다`() {
        // given
        val fixedUseCase = CalculateBacktestUseCase.withReferenceDate(LocalDate.of(2026, 10, 2))
        val portfolio = listOf(Portfolio("TEST", "테스트 ETF", 100))
        val priceHistory = EtfPriceHistory(
            ticker = "TEST",
            content = listOf(
                EtfPricePoint("2023-10-01", 9_900L, 0.0),
                EtfPricePoint("2023-10-02", 10_000L, 1.0),
                EtfPricePoint("2026-10-02", 20_000L, 1.0)
            ),
            totalElements = 3,
            totalPages = 1,
            last = true
        )

        // when
        val result = fixedUseCase(
            portfolios = portfolio,
            priceHistories = mapOf("TEST" to priceHistory),
            investmentAmount = 10_000_000L,
            investmentType = InvestmentType.LUMP_SUM,
            periodMonths = 36
        )

        // then
        assertEquals("2023-10-02", result.points.first().date)
    }
}
