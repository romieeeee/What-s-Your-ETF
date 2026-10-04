package com.d102.wye.data.repository.fake

import com.d102.wye.domain.common.BaseResult
import com.d102.wye.domain.model.AiReviewResult
import com.d102.wye.domain.model.EtfBundle
import com.d102.wye.domain.model.EtfBundleDetail
import com.d102.wye.domain.model.EtfDividendHistory
import com.d102.wye.domain.model.EtfMonthlyDividend
import com.d102.wye.domain.model.EtfPriceHistory
import com.d102.wye.domain.model.Portfolio
import com.d102.wye.domain.repository.SimulationRepository
import com.d102.wye.domain.state.InvestmentType
import java.time.YearMonth
import javax.inject.Inject

class FakeSimulationRepository @Inject constructor() : SimulationRepository {

    private val priceHistoryCache = mutableMapOf<String, EtfPriceHistory>()
    private val lastSuccessfulSync = mutableMapOf<String, Long>()
    private val lastCacheAccess = mutableMapOf<String, Long>()

    override suspend fun getEtfPriceHistories(
        tickers: List<String>,
        startDate: String?,
        endDate: String?,
        page: Int,
    ): BaseResult<Map<String, EtfPriceHistory>> = BaseResult.Success(
        tickers.distinct().associateWith { ticker ->
            FakePerformanceData.priceHistory(ticker, startDate, endDate)
        }
    )

    override suspend fun getEtfDividendHistories(
        tickers: List<String>,
        startDate: String?,
        endDate: String?,
    ): BaseResult<Map<String, EtfDividendHistory>> {
        val currentMonth = YearMonth.now()
        val result = tickers.distinct().associateWith { ticker ->
            val detail = FakePerformanceData.detail(ticker)
            EtfDividendHistory(
                etfId = FakePerformanceData.etfs.firstOrNull { it.ticker == ticker }?.etfId ?: 0L,
                etfName = detail.name,
                ticker = ticker,
                dividends = (11 downTo 0).map { offset ->
                    EtfMonthlyDividend(
                        month = currentMonth.minusMonths(offset.toLong()).toString(),
                        dividend = 35L + (offset % 4) * 5L
                    )
                }
            )
        }
        return BaseResult.Success(result)
    }

    override suspend fun getAiPortfolioReview(
        totalAmount: Long,
        investmentType: InvestmentType,
        portfolios: List<Portfolio>,
    ): BaseResult<AiReviewResult> = BaseResult.Success(
        AiReviewResult(
            mainTitle = "균형 잡힌 테스트 포트폴리오",
            subTitle = "서버 없이 재현 가능한 성능 측정 결과",
            tags = listOf("#FakeData", "#성능테스트", "#재현가능"),
            feedback = "고정된 로컬 데이터를 사용하고 있어 동일한 조건에서 렌더링 성능을 비교할 수 있습니다."
        )
    )

    override suspend fun savePriceHistories(histories: Map<String, EtfPriceHistory>) {
        histories.forEach { (ticker, incoming) ->
            val merged = (priceHistoryCache[ticker]?.content.orEmpty() + incoming.content)
                .associateBy { it.date }
                .values
                .sortedBy { it.date }
            priceHistoryCache[ticker] = incoming.copy(
                content = merged,
                totalElements = merged.size,
                totalPages = 1,
                last = true
            )
        }
    }

    override suspend fun getCachedPriceHistories(tickers: List<String>): Map<String, EtfPriceHistory> =
        tickers.distinct().mapNotNull { ticker ->
            priceHistoryCache[ticker]?.let { ticker to it }
        }.toMap()

    override suspend fun deleteCachedPriceHistory(ticker: String) {
        priceHistoryCache.remove(ticker)
        lastSuccessfulSync.remove(ticker)
        lastCacheAccess.remove(ticker)
    }

    override suspend fun getLastCachedDate(ticker: String): String? =
        priceHistoryCache[ticker]?.content?.maxOfOrNull { it.date }

    override suspend fun getLastSuccessfulPriceHistorySync(ticker: String): Long? =
        lastSuccessfulSync[ticker]

    override suspend fun markPriceHistorySyncSuccessful(ticker: String, syncedAtEpochMillis: Long) {
        lastSuccessfulSync[ticker] = syncedAtEpochMillis
    }

    override suspend fun markPriceHistoryCacheAccessed(
        tickers: List<String>,
        accessedAtEpochMillis: Long,
    ) {
        tickers.distinct().forEach { lastCacheAccess[it] = accessedAtEpochMillis }
    }

    override suspend fun deleteUnusedPriceHistoryCache(cutoffEpochMillis: Long) {
        val unusedTickers = lastCacheAccess
            .filterValues { it < cutoffEpochMillis }
            .keys
            .toList()
        unusedTickers.forEach { deleteCachedPriceHistory(it) }
    }

    override suspend fun getPresetList(): BaseResult<List<EtfBundle>> =
        BaseResult.Success(FakePerformanceData.bundles)

    override suspend fun getPresetDetail(presetId: Int): BaseResult<EtfBundleDetail> =
        BaseResult.Success(FakePerformanceData.bundleDetail(presetId))
}