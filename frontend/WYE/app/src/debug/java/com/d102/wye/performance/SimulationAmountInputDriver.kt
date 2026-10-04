package com.d102.wye.performance

import kotlinx.coroutines.delay

/** 동일한 투자 금액 입력을 같은 순서와 간격으로 재생하는 Debug 전용 driver. */
class SimulationAmountInputDriver(
    private val targetAmount: String = DEFAULT_TARGET_AMOUNT,
) {
    init {
        require(targetAmount.isNotBlank() && targetAmount.all(Char::isDigit)) {
            "targetAmount는 숫자로만 구성되어야 합니다."
        }
    }

    val inputs: List<String> = targetAmount.indices.map { index ->
        targetAmount.substring(0, index + 1)
    }

    suspend fun replay(
        intervalMillis: Long,
        onInput: suspend (String) -> Unit,
    ) {
        require(intervalMillis >= 0L) { "intervalMillis는 0 이상이어야 합니다." }

        inputs.forEachIndexed { index, value ->
            onInput(value)
            if (index < inputs.lastIndex) delay(intervalMillis)
        }
    }

    companion object {
        /** UI 입력 단위는 만원이며 10,000은 1억원이다. */
        const val DEFAULT_TARGET_AMOUNT = "10000"
    }
}
