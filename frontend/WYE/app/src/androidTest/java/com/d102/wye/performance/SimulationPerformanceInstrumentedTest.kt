package com.d102.wye.performance

import android.os.Debug
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.d102.wye.data.repository.fake.FakePerformanceData
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
import java.io.File

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
        val runId = arguments.getString(ARG_RUN_ID) ?: "manual"
        val captureMethodTrace = arguments.getString(ARG_CAPTURE_METHOD_TRACE) == "true"
        val traceFileName = arguments.getString(ARG_TRACE_FILE_NAME) ?: "$runId.trace"
        val viewModel = createConfiguredViewModel()
        val driver = SimulationAmountInputDriver()

        Timber.tag(PERFORMANCE_TAG).d(
            "scenario_run | phase=started | runId=%s | inputIntervalMs=%d | warmupCount=%d | measurementCount=%d",
            runId,
            inputIntervalMillis,
            warmupCount,
            measurementCount
        )

        runBatch(
            batch = "warmup",
            count = warmupCount,
            runId = runId,
            inputIntervalMillis = inputIntervalMillis,
            viewModel = viewModel,
            driver = driver
        )
        if (captureMethodTrace) {
            captureMeasurementTrace(
                traceFileName = traceFileName,
                block = {
                    runBatch(
                        batch = "measurement",
                        count = measurementCount,
                        runId = runId,
                        inputIntervalMillis = inputIntervalMillis,
                        viewModel = viewModel,
                        driver = driver
                    )
                }
            )
        } else {
            runBatch(
                batch = "measurement",
                count = measurementCount,
                runId = runId,
                inputIntervalMillis = inputIntervalMillis,
                viewModel = viewModel,
                driver = driver
            )
        }

        Timber.tag(PERFORMANCE_TAG).d(
            "scenario_run | phase=finished | runId=%s",
            runId
        )

        assertTrue(viewModel.simulationState.value is UiState.Success)
    }

    private suspend fun captureMeasurementTrace(
        traceFileName: String,
        block: suspend () -> Unit,
    ) {
        require(File(traceFileName).name == traceFileName) {
            "$ARG_TRACE_FILE_NAME must be a file name without directories."
        }
        require(traceFileName.matches(TRACE_FILE_NAME_PATTERN)) {
            "$ARG_TRACE_FILE_NAME contains unsupported characters."
        }
        require(traceFileName.endsWith(TRACE_FILE_EXTENSION)) {
            "$ARG_TRACE_FILE_NAME must end with $TRACE_FILE_EXTENSION."
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val traceDirectory = context.getExternalFilesDir(null)
            ?: error("External files directory is unavailable.")
        val traceFile = File(traceDirectory, traceFileName)

        Timber.tag(PERFORMANCE_TAG).d(
            "method_trace | phase=started | file=%s | intervalUs=%d",
            traceFile.absolutePath,
            TRACE_SAMPLE_INTERVAL_MICROS
        )
        Debug.startMethodTracingSampling(
            traceFile.absolutePath,
            TRACE_BUFFER_SIZE_BYTES,
            TRACE_SAMPLE_INTERVAL_MICROS
        )
        try {
            block()
        } finally {
            Debug.stopMethodTracing()
        }
        val exportedTracePath = "$TRACE_EXPORT_DIRECTORY/$traceFileName"
        val exportedTraceBytes = exportTraceForHost(traceFile, exportedTracePath)
        Timber.tag(PERFORMANCE_TAG).d(
            "method_trace | phase=finished | file=%s | bytes=%d | exportedFile=%s | exportedBytes=%d",
            traceFile.absolutePath,
            traceFile.length(),
            exportedTracePath,
            exportedTraceBytes
        )
    }

    private fun exportTraceForHost(traceFile: File, exportedTracePath: String): Long {
        val command = "cp ${traceFile.absolutePath} $exportedTracePath && stat -c %s $exportedTracePath"
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                    .bufferedReader()
                    .use { it.readText() }
            }
            .trim()
        val exportedBytes = output.lineSequence().lastOrNull()?.toLongOrNull()
            ?: error("Failed to export method trace: $output")
        check(exportedBytes == traceFile.length()) {
            "Exported trace size mismatch: source=${traceFile.length()}, exported=$exportedBytes"
        }
        return exportedBytes
    }

    private suspend fun runBatch(
        batch: String,
        count: Int,
        runId: String,
        inputIntervalMillis: Long,
        viewModel: SimulationViewModel,
        driver: SimulationAmountInputDriver,
    ) {
        repeat(count) { index ->
            val iteration = index + 1
            Timber.tag(PERFORMANCE_TAG).d(
                "scenario_iteration | phase=started | runId=%s | batch=%s | iteration=%d | inputIntervalMs=%d",
                runId,
                batch,
                iteration,
                inputIntervalMillis
            )
            val startedAtNanos = System.nanoTime()
            replayAndAwait(viewModel, driver, inputIntervalMillis)
            Timber.tag(PERFORMANCE_TAG).d(
                "scenario_iteration | phase=finished | runId=%s | batch=%s | iteration=%d | inputIntervalMs=%d | durationMs=%.3f",
                runId,
                batch,
                iteration,
                inputIntervalMillis,
                elapsedMillis(startedAtNanos)
            )
        }
    }

    private suspend fun createConfiguredViewModel(): SimulationViewModel {
        val simulationRepository = FakeSimulationRepository()
        val viewModel = SimulationViewModel(
            simulationRepository = simulationRepository,
            portfolioRepository = FakePortfolioRepository(),
            etfRepository = FakeEtfRepository(),
            runSimulation = RunSimulationUseCase(
                calculateBacktest = CalculateBacktestUseCase.withReferenceDate(
                    FakePerformanceData.referenceDate
                ),
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

    private fun elapsedMillis(startedAtNanos: Long): Double =
        (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND

    private companion object {
        const val PERFORMANCE_TAG = "SimulationPerf"
        const val ARG_ENABLED = "runSimulationPerformance"
        const val ARG_INPUT_INTERVAL_MILLIS = "inputIntervalMillis"
        const val ARG_WARMUP_COUNT = "warmupCount"
        const val ARG_MEASUREMENT_COUNT = "measurementCount"
        const val ARG_RUN_ID = "runId"
        const val ARG_CAPTURE_METHOD_TRACE = "captureMethodTrace"
        const val ARG_TRACE_FILE_NAME = "traceFileName"
        const val DEBOUNCE_SETTLE_MILLIS = 350L
        const val RESULT_TIMEOUT_MILLIS = 10_000L
        const val NANOS_PER_MILLISECOND = 1_000_000.0
        const val TRACE_BUFFER_SIZE_BYTES = 32 * 1024 * 1024
        const val TRACE_SAMPLE_INTERVAL_MICROS = 1_000
        const val TRACE_FILE_EXTENSION = ".trace"
        const val TRACE_EXPORT_DIRECTORY = "/data/local/tmp"
        val TRACE_FILE_NAME_PATTERN = Regex("[A-Za-z0-9._-]+\\.trace")
    }
}
