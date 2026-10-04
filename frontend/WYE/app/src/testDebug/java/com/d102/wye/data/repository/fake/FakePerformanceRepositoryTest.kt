package com.d102.wye.data.repository.fake

import com.d102.wye.domain.common.BaseResult
import com.d102.wye.domain.model.EtfFilter
import com.d102.wye.domain.model.EtfCountItem
import com.d102.wye.domain.model.SavePortfolioParams
import com.d102.wye.domain.state.InvestmentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakePerformanceRepositoryTest {

    @Test
    fun `ETF 목록은 서버 없이 고정 fixture를 반환한다`() = runBlocking {
        val result = FakeEtfRepository().getEtfList(EtfFilter(), page = 0)

        assertTrue(result is BaseResult.Success)
        val page = (result as BaseResult.Success).data
        assertTrue(page.items.size >= 6)
        assertTrue(page.items.any { it.ticker == "069500" })
        assertTrue(page.isLast)
    }

    @Test
    fun `가격 이력은 캐시에 저장되어 백테스트에서 다시 조회된다`() = runBlocking {
        val repository = FakeSimulationRepository()
        val result = repository.getEtfPriceHistories(
            tickers = listOf("069500", "360750"),
            startDate = "2024-01-01",
            endDate = "2024-12-31"
        )

        assertTrue(result is BaseResult.Success)
        val histories = (result as BaseResult.Success).data
        repository.savePriceHistories(histories)
        val cached = repository.getCachedPriceHistories(histories.keys.toList())

        assertEquals(histories.keys, cached.keys)
        assertTrue(cached.values.all { it.content.size > 200 })
        assertEquals(
            cached.getValue("069500").content.map { it.date },
            cached.getValue("360750").content.map { it.date }
        )
    }

    @Test
    fun `저장한 포트폴리오는 메모리 목록에서 조회된다`() = runBlocking {
        val repository = FakePortfolioRepository()
        val params = SavePortfolioParams(
            portfolioName = "성능 테스트",
            investType = InvestmentType.LUMP_SUM,
            investAmount = 10_000_000L,
            investPeriod = 12,
            etfs = listOf(EtfCountItem(ticker = "069500", counts = 10.0))
        )

        assertTrue(repository.savePortfolio(params) is BaseResult.Success)
        val result = repository.getPortfolioList()

        assertTrue(result is BaseResult.Success)
        assertEquals("성능 테스트", (result as BaseResult.Success).data.single().title)
    }
}