package com.d102.wye.data.repository.fake

import com.d102.wye.domain.model.BundleEtfItem
import com.d102.wye.domain.model.BundleType
import com.d102.wye.domain.model.Etf
import com.d102.wye.domain.model.EtfBundle
import com.d102.wye.domain.model.EtfBundleDetail
import com.d102.wye.domain.model.EtfCluster
import com.d102.wye.domain.model.EtfClusterData
import com.d102.wye.domain.model.EtfClusterStock
import com.d102.wye.domain.model.EtfDetail
import com.d102.wye.domain.model.EtfPriceHistory
import com.d102.wye.domain.model.EtfPricePoint
import com.d102.wye.domain.model.InfluentialStock
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.sin

/** 성능 측정에서 매번 동일한 결과를 제공하는 Debug 전용 fixture. */
internal object FakePerformanceData {

    val referenceDate: LocalDate = LocalDate.of(2026, 10, 2)

    val etfs = listOf(
        Etf(1, "069500", "KODEX 200", 38_450, 0.82, 315, "MODERATE", false),
        Etf(2, "360750", "TIGER 미국S&P500", 22_130, 0.46, 101, "STABLE", false),
        Etf(3, "133690", "TIGER 미국나스닥100", 139_700, 1.17, 1_620, "ACTIVE", false),
        Etf(4, "229200", "KODEX 코스닥150", 12_820, -0.34, -44, "AGGRESSIVE", false),
        Etf(5, "305720", "KODEX 2차전지산업", 15_430, 1.42, 216, "AGGRESSIVE", false),
        Etf(6, "091160", "KODEX 반도체", 52_100, 0.95, 490, "ACTIVE", false),
    )

    val bundles = listOf(
        EtfBundle(
            id = 1,
            name = "안정 성장형",
            summary = "시장 대표 지수 중심의 균형 포트폴리오",
            bundleType = BundleType.BALANCED,
            tags = listOf("#분산투자", "#장기투자")
        ),
        EtfBundle(
            id = 2,
            name = "기술 성장형",
            summary = "미국 기술주와 국내 반도체 중심 포트폴리오",
            bundleType = BundleType.HIGH_GROWTH,
            tags = listOf("#기술주", "#성장형")
        )
    )

    fun detail(ticker: String): EtfDetail {
        val etf = etfs.firstOrNull { it.ticker == ticker } ?: etfs.first()
        return EtfDetail(
            ticker = etf.ticker,
            name = etf.name,
            currentPrice = etf.currentPrice,
            dailyFluctuation = etf.changeAmount,
            dailyFluctuationRatio = etf.changeRate,
            volume = 1_250_000L + etf.etfId * 100_000L,
            company = if (etf.name.startsWith("TIGER")) "미래에셋자산운용" else "삼성자산운용",
            riskGrade = when (etf.riskType) {
                "STABLE" -> 2
                "MODERATE" -> 3
                "ACTIVE" -> 4
                else -> 5
            },
            riskType = etf.riskType,
            expenseRatio = 0.15 + etf.etfId * 0.01,
            per = 12.0 + etf.etfId,
            pbr = 1.1 + etf.etfId * 0.1,
            roe = 8.0 + etf.etfId,
            aum = 1_000_000_000_000L + etf.etfId * 100_000_000_000L,
            listingDate = "2020-01-02",
            inav = etf.currentPrice - 15,
            inavChangeAmount = etf.changeAmount,
            inavChangeRate = etf.changeRate,
        )
    }

    fun cluster(ticker: String): EtfClusterData {
        val detail = detail(ticker)
        val technology = EtfCluster(
            name = "정보기술",
            percentage = 55.0,
            stocks = listOf(
                EtfClusterStock("005930", "삼성전자", 30.0),
                EtfClusterStock("000660", "SK하이닉스", 25.0),
            ),
            aiAnalysis = "성장주 비중이 높은 테스트 데이터입니다.",
            assetType = "EQUITY"
        )
        val finance = EtfCluster(
            name = "금융",
            percentage = 45.0,
            stocks = listOf(EtfClusterStock("105560", "KB금융", 45.0)),
            aiAnalysis = "변동성을 완화하는 테스트 데이터입니다.",
            assetType = "EQUITY"
        )
        return EtfClusterData(
            englishName = detail.name,
            sectors = listOf(technology, finance),
            influentialStocks = listOf(
                InfluentialStock("005930", "삼성전자", 30.0, 81_000, 0.8),
                InfluentialStock("000660", "SK하이닉스", 25.0, 192_000, 1.2),
            )
        )
    }

    fun priceHistory(
        ticker: String,
        startDate: String? = null,
        endDate: String? = null,
    ): EtfPriceHistory {
        val end = endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: referenceDate
        val start = startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: end.minusYears(3)
        val dates = if (start > end) {
            emptyList()
        } else {
            generateSequence(start) { current -> current.plusDays(1).takeIf { it <= end } }
                .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
                .toList()
        }
        val currentPrice = detail(ticker).currentPrice.toDouble()
        val initialPrice = currentPrice * 0.72
        var previousPrice = initialPrice
        val points = dates.mapIndexed { index, date ->
            val progress = if (dates.size <= 1) 0.0 else index.toDouble() / (dates.size - 1)
            val seasonal = 1.0 + sin(index / 13.0) * 0.025
            val price = (initialPrice * (1.0 + progress * 0.38) * seasonal)
                .toLong()
                .coerceAtLeast(1L)
            val dailyReturn = if (previousPrice == 0.0) 0.0 else (price - previousPrice) / previousPrice * 100.0
            previousPrice = price.toDouble()
            EtfPricePoint(date.toString(), price, dailyReturn)
        }
        return EtfPriceHistory(
            ticker = ticker,
            content = points,
            totalElements = points.size,
            totalPages = 1,
            last = true
        )
    }

    fun bundleDetail(presetId: Int): EtfBundleDetail {
        val bundle = bundles.firstOrNull { it.id == presetId } ?: bundles.first()
        val items = when (bundle.id) {
            2 -> listOf(etfs[2], etfs[5])
            else -> listOf(etfs[0], etfs[1])
        }
        return EtfBundleDetail(
            id = bundle.id,
            name = bundle.name,
            description = bundle.summary,
            bundleType = bundle.bundleType,
            etfItems = items.map { BundleEtfItem(it.ticker, it.name) }
        )
    }
}
