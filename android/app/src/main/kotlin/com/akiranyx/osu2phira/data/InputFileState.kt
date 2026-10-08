package com.akiranyx.osu2phira.data

import com.akiranyx.osu2phira.domain.ConversionFailure
import com.akiranyx.osu2phira.domain.ConversionFailureCode
import com.akiranyx.osu2phira.domain.InputFileErrorCode
import com.akiranyx.osu2phira.domain.FailureRecoveryAction
import com.akiranyx.osu2phira.domain.recoveryAction

sealed interface InputFileState {
    data object Empty : InputFileState
    data object Loading : InputFileState
    data class Ready(val inspection: InputFileInspection) : InputFileState
    data class Error(val failure: ConversionFailure) : InputFileState {
        constructor(code: InputFileErrorCode, message: String) : this(
            ConversionFailure(
                code = code,
                message = message,
                section = "输入",
            )
        )

        val code: ConversionFailureCode
            get() = failure.code
        val message: String
            get() = failure.message
        val recoveryAction: FailureRecoveryAction
            get() = failure.recoveryAction()
    }
}
