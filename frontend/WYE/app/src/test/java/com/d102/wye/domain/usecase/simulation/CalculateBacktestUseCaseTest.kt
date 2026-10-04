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
}