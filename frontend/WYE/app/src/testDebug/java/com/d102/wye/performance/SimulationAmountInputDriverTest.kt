package com.d102.wye.performance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulationAmountInputDriverTest {

    @Test
    fun `입력값과 간격을 동일하게 재생한다`() = runTest {
        val driver = SimulationAmountInputDriver()
        val dispatched = mutableListOf<Pair<Long, String>>()

        driver.replay(intervalMillis = 80L) { value ->
            dispatched += currentTime to value
        }

        assertEquals(
            listOf(
                0L to "1",
                80L to "10",
                160L to "100",
                240L to "1000",
                320L to "10000",
            ),
            dispatched
        )
    }

    @Test
    fun `같은 설정은 매번 같은 입력 순서를 만든다`() {
        assertEquals(
            SimulationAmountInputDriver().inputs,
            SimulationAmountInputDriver().inputs
        )
    }
}
