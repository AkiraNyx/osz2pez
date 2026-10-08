package com.akiranyx.osu2phira.domain

object OszInputValidator {
    fun validateDisplayName(displayName: String) {
        if (!displayName.endsWith(".osz", ignoreCase = true)) {
            throw InputFileException(
                InputFileErrorCode.UNSUPPORTED_FILE_TYPE,
                "请选择扩展名为 .osz 的 osu! 谱面包",
            )
        }
    }
}
