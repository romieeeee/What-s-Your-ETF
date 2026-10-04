package com.d102.wye.performance

import com.d102.wye.data.repository.fake.FakeEtfRepository
import com.d102.wye.data.repository.fake.FakePortfolioRepository
import com.d102.wye.data.repository.fake.FakeSimulationRepository
import com.d102.wye.domain.state.InvestmentType
import com.d102.wye.domain.usecase.portfolio.CalculatePortfolioChartUseCase
import com.d102.wye.domain.usecase.simulation.CalculateBacktestUseCase
import com.d102.wye.domain.usecase.simulation.CalculateWeightedFundamentalsUseCase
import com.d102.wye.domain.usecase.simulation.PriceHistoryCachePolicy
import com.d102.wye.domain.usecase.simulation.RefreshPriceHistoryCacheUseCase
import com.d102.wye.domain.usecase.simulation.RunSimulationUseCase
import com.d102.wye.presentation.model.UiState
import com.d102.wye.presentation.simulation.model.SimulationUiModel
import com.d102.wye.presentation.simulation.progress.SimulationViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulationPerformanceScenarioTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `같은 fixture와 입력은 매번 같은 시뮬레이션 결과를 만든다`() = runTest {
        val first = runScenario(SimulationAmountInputDriver())
        val second = runScenario(SimulationAmountInputDriver())

        assertEquals(first, second)
    }

    @Test
    fun `연속 입력은 마지막 금액의 결과만 최종 상태에 반영한다`() = runTest {
        val replayed = runScenario(SimulationAmountInputDriver(targetAmount = "10000"))
        val finalOnly = runScenario(SimulationAmountInputDriver(targetAmount = "10000"), replayAll = false)
        val differentAmount = runScenario(SimulationAmountInputDriver(targetAmount = "20000"))

        assertEquals(finalOnly, replayed)
        assertNotEquals(replayed.estimatedFinalAsset, differentAmount.estimatedFinalAsset)
    }

    private suspend fun TestScope.runScenario(
        driver: SimulationAmountInputDriver,
        replayAll: Boolean = true,
    ): SimulationUiModel {
        val simulationRepository = FakeSimulationRepository()
        val viewModel = SimulationViewModel(
            simulationRepository = simulationRepository,
            portfolioRepository = FakePortfolioRepository(),
            etfRepository = FakeEtfRepository(),
            runSimulation = RunSimulationUseCase(
                calculateBacktest = CalculateBacktestUseCase(),
                calculateWeightedFundamentals = CalculateWeightedFundamentalsUseCase()
            ),
            calculatePortfolioChart = CalculatePortfolioChartUseCase(),
            refreshPriceHistoryCache = RefreshPriceHistoryCacheUseCase(
                simulationRepository = simulationRepository,
                cachePolicy = PriceHistoryCachePolicy()
            )
        )

        val tickers = listOf("069500", "360750", "133690", "229200", "305720", "091160")
        val weights = listOf(17, 17, 17, 17, 16, 16)

        viewModel.addPortfolioItems(tickers)
        advanceUntilIdle()
        tickers.zip(weights).forEach { (ticker, weight) ->
            viewModel.updateItemWeight(ticker, weight)
        }
        viewModel.onPeriodChanged("36")
        viewModel.onInvestmentTypeSelected(InvestmentType.LUMP_SUM)
        advanceUntilIdle()

        if (replayAll) {
            driver.replay(intervalMillis = 80L, onInput = viewModel::onAmountChanged)
        } else {
            viewModel.onAmountChanged(driver.inputs.last())
        }
        advanceUntilIdle()

        val state = viewModel.simulationState.value
        assertTrue(state is UiState.Success)
        return (state as UiState.Success).data
    }
}
