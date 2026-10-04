package com.d102.wye.performance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import com.d102.wye.presentation.simulation.progress.SimulationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber

/** 실제 Android runtime에서 동일한 ViewModel 입력을 반복하는 측정용 harness. */
@RunWith(AndroidJUnit4::class)
class SimulationPerformanceInstrumentedTest {

    @Test
    fun replayConfiguredAmountInput() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_ENABLED) == "true")

        val inputIntervalMillis = arguments.getString(ARG_INPUT_INTERVAL_MILLIS)
            ?.toLongOrNull()
            ?: error("$ARG_INPUT_INTERVAL_MILLIS instrumentation argument가 필요합니다.")
        require(inputIntervalMillis >= 0L)

        val warmupCount = arguments.getString(ARG_WARMUP_COUNT)?.toIntOrNull() ?: 5
        val measurementCount = arguments.getString(ARG_MEASUREMENT_COUNT)?.toIntOrNull() ?: 20
        val viewModel = createConfiguredViewModel()
        val driver = SimulationAmountInputDriver()

        repeat(warmupCount) { index ->
            replayAndAwait(viewModel, driver, inputIntervalMillis)
            Timber.tag(PERFORMANCE_TAG).d(
                "scenario_iteration | batch=warmup | iteration=%d | inputIntervalMs=%d",
                index + 1,
                inputIntervalMillis
            )
        }

        repeat(measurementCount) { index ->
            replayAndAwait(viewModel, driver, inputIntervalMillis)
            Timber.tag(PERFORMANCE_TAG).d(
                "scenario_iteration | batch=measurement | iteration=%d | inputIntervalMs=%d",
                index + 1,
                inputIntervalMillis
            )
        }

        assertTrue(viewModel.simulationState.value is UiState.Success)
    }

    private suspend fun createConfiguredViewModel(): SimulationViewModel {
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

        withContext(Dispatchers.Main.immediate) {
            viewModel.addPortfolioItems(tickers)
        }
        awaitCondition { viewModel.formState.value.portfolioItems.size == tickers.size }
        withContext(Dispatchers.Main.immediate) {
            tickers.zip(weights).forEach { (ticker, weight) ->
                viewModel.updateItemWeight(ticker, weight)
            }
            viewModel.onPeriodChanged("36")
            viewModel.onInvestmentTypeSelected(InvestmentType.LUMP_SUM)
        }
        delay(DEBOUNCE_SETTLE_MILLIS)
        return viewModel
    }

    private suspend fun replayAndAwait(
        viewModel: SimulationViewModel,
        driver: SimulationAmountInputDriver,
        inputIntervalMillis: Long,
    ) {
        val previousState = viewModel.simulationState.value
        driver.replay(inputIntervalMillis) { value ->
            withContext(Dispatchers.Main.immediate) {
                viewModel.onAmountChanged(value)
            }
        }

        val currentState = viewModel.simulationState.value
        if (currentState is UiState.Success && currentState !== previousState) return

        withTimeout(RESULT_TIMEOUT_MILLIS) {
            viewModel.simulationState.first { state ->
                state is UiState.Success && state !== previousState
            }
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(RESULT_TIMEOUT_MILLIS) {
            while (!condition()) delay(10L)
        }
    }

    private companion object {
        const val PERFORMANCE_TAG = "SimulationPerf"
        const val ARG_ENABLED = "runSimulationPerformance"
        const val ARG_INPUT_INTERVAL_MILLIS = "inputIntervalMillis"
        const val ARG_WARMUP_COUNT = "warmupCount"
        const val ARG_MEASUREMENT_COUNT = "measurementCount"
        const val DEBOUNCE_SETTLE_MILLIS = 350L
        const val RESULT_TIMEOUT_MILLIS = 10_000L
    }
}
