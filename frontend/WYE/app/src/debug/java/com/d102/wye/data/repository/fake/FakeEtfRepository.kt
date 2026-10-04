package com.d102.wye.data.repository.fake

import com.d102.wye.domain.common.BaseResult
import com.d102.wye.domain.model.EtfClusterData
import com.d102.wye.domain.model.EtfDetail
import com.d102.wye.domain.model.EtfFilter
import com.d102.wye.domain.model.EtfLikeData
import com.d102.wye.domain.model.EtfMarketData
import com.d102.wye.domain.model.EtfPage
import com.d102.wye.domain.model.EtfPriceData
import com.d102.wye.domain.model.TopVolumeEtf
import com.d102.wye.domain.model.TopVolumeEtfSnapshot
import com.d102.wye.domain.repository.EtfRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeEtfRepository @Inject constructor() : EtfRepository {

    private val likedTickers = MutableStateFlow<Set<String>>(emptySet())

    override suspend fun getTopVolumeEtfs(): BaseResult<TopVolumeEtfSnapshot> =
        BaseResult.Success(
            TopVolumeEtfSnapshot(
                items = FakePerformanceData.etfs.take(5).map {
                    TopVolumeEtf(it.ticker, it.name, it.changeRate, 1_000_000L + it.etfId * 100_000L)
                },
                timestamp = "성능 테스트 fixture"
            )
        )

    override suspend fun getEtfList(filter: EtfFilter, page: Int): BaseResult<EtfPage> {
        if (page > 0) return BaseResult.Success(EtfPage(emptyList(), isLast = true))

        val liked = likedTickers.value
        val query = filter.searchName?.trim().orEmpty()
        val items = FakePerformanceData.etfs
            .asSequence()
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.ticker.contains(query) }
            .filter { filter.riskType == null || it.riskType == filter.riskType }
            .filter { filter.isFavorite != true || it.ticker in liked }
            .map { it.copy(isFavorite = it.ticker in liked) }
            .toList()
        return BaseResult.Success(EtfPage(items = items, isLast = true))
    }

    override fun getLikedEtfList(): Flow<List<EtfLikeData>> = likedTickers.map { liked ->
        FakePerformanceData.etfs
            .filter { it.ticker in liked }
            .map {
                EtfLikeData(
                    ticker = it.ticker,
                    name = it.name,
                    currentPrice = it.currentPrice,
                    changeRate = it.changeRate,
                    changeAmount = it.changeAmount,
                    riskType = it.riskType
                )
            }
    }

    override suspend fun toggleLike(data: EtfLikeData): BaseResult<Boolean> {
        val isLiked = data.ticker !in likedTickers.value
        likedTickers.value = if (isLiked) {
            likedTickers.value + data.ticker
        } else {
            likedTickers.value - data.ticker
        }
        return BaseResult.Success(isLiked)
    }

    override suspend fun getEtfDetail(ticker: String): BaseResult<EtfDetail> =
        BaseResult.Success(FakePerformanceData.detail(ticker))

    override suspend fun getEtfCluster(ticker: String): BaseResult<EtfClusterData> =
        BaseResult.Success(FakePerformanceData.cluster(ticker))

    override suspend fun getEtfPriceHistory(
        ticker: String,
        startDate: String,
        endDate: String,
        size: Int,
    ): BaseResult<List<EtfPriceData>> {
        val items = FakePerformanceData.priceHistory(ticker, startDate, endDate)
            .content
            .takeLast(size)
            .map { EtfPriceData(it.date, it.stockPrice, it.dailyReturn, it.stockPrice.toDouble()) }
        return BaseResult.Success(items)
    }

    override suspend fun getMarketData(ticker: String): BaseResult<EtfMarketData> {
        val detail = FakePerformanceData.detail(ticker)
        return BaseResult.Success(
            EtfMarketData(
                ticker = detail.ticker,
                currentPrice = detail.currentPrice,
                dailyReturn = detail.dailyFluctuationRatio,
                volume = detail.volume
            )
        )
    }
}