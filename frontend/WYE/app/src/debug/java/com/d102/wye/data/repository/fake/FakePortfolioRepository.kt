package com.d102.wye.data.repository.fake

import com.d102.wye.domain.common.ApiError
import com.d102.wye.domain.common.BaseResult
import com.d102.wye.domain.model.PortfolioCount
import com.d102.wye.domain.model.PortfolioDetail
import com.d102.wye.domain.model.PortfolioEtf
import com.d102.wye.domain.model.PortfolioIssue
import com.d102.wye.domain.model.PortfolioListItem
import com.d102.wye.domain.model.SavePortfolioParams
import com.d102.wye.domain.repository.PortfolioRepository
import java.time.LocalDate
import javax.inject.Inject

class FakePortfolioRepository @Inject constructor() : PortfolioRepository {

    private val details = linkedMapOf<Long, PortfolioDetail>()
    private val listItems = linkedMapOf<Long, PortfolioListItem>()
    private var nextId = 1L

    override suspend fun savePortfolio(params: SavePortfolioParams): BaseResult<Unit> {
        val id = nextId++
        val createdAt = LocalDate.now().toString()
        val etfs = params.etfs.map { item ->
            val detail = FakePerformanceData.detail(item.ticker)
            PortfolioEtf(ticker = item.ticker, name = detail.name)
        }
        details[id] = PortfolioDetail(
            portfolioId = id,
            portfolioName = params.portfolioName,
            counts = params.etfs.map { item ->
                PortfolioCount(
                    ticker = item.ticker,
                    counts = item.counts,
                    etfName = FakePerformanceData.detail(item.ticker).name
                )
            },
            investAmount = params.investAmount,
            createdAt = createdAt,
            portfolioType = params.investType
        )
        listItems[id] = PortfolioListItem(
            portfolioId = id,
            title = params.portfolioName,
            createdAt = createdAt,
            etfList = etfs,
            totalReturn = 8.4,
            isMyData = false
        )
        return BaseResult.Success(Unit)
    }

    override suspend fun getPortfolioList(): BaseResult<List<PortfolioListItem>> =
        BaseResult.Success(listItems.values.toList())

    override suspend fun getPortfolioDetail(portfolioId: Long): BaseResult<PortfolioDetail> =
        details[portfolioId]?.let { BaseResult.Success(it) }
            ?: BaseResult.Error(ApiError.unknownError("저장된 테스트 포트폴리오가 없습니다."))

    override suspend fun deletePortfolio(portfolioId: Long): BaseResult<Unit> {
        details.remove(portfolioId)
        listItems.remove(portfolioId)
        return BaseResult.Success(Unit)
    }

    override suspend fun updatePortfolio(portfolioId: Long, name: String): BaseResult<Unit> {
        val detail = details[portfolioId]
            ?: return BaseResult.Error(ApiError.unknownError("저장된 테스트 포트폴리오가 없습니다."))
        details[portfolioId] = detail.copy(portfolioName = name)
        listItems[portfolioId] = listItems.getValue(portfolioId).copy(title = name)
        return BaseResult.Success(Unit)
    }

    override suspend fun getPortfolioIssues(portfolioId: Long): BaseResult<List<PortfolioIssue>> =
        BaseResult.Success(
            listOf(
                PortfolioIssue(
                    localDate = LocalDate.now().toString(),
                    title = "성능 테스트 데이터",
                    description = "서버 연결 없이 동일한 포트폴리오 이슈를 반환합니다."
                )
            )
        )
}