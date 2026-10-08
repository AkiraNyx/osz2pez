package com.akiranyx.osu2phira.data

import com.akiranyx.osu2phira.domain.ConversionFailure
import com.akiranyx.osu2phira.domain.ConversionFailureCode
import com.akiranyx.osu2phira.domain.FailureRecoveryAction
import com.akiranyx.osu2phira.domain.recoveryAction

sealed interface ExportState {
    data object Idle : ExportState
    data object SelectingOutput : ExportState
    data object Exporting : ExportState
    data object Cancelling : ExportState
    data object Cancelled : ExportState
    data class Success(
        val fileName: String,
        val entryCount: Int,
        val warningCount: Int,
    ) : ExportState

    data class Error(val failure: ConversionFailure) : ExportState {
        constructor(code: ConversionFailureCode, message: String) : this(
            ConversionFailure(
                code = code,
                message = message,
                section = "输出",
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
