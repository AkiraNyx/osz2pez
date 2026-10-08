package com.akiranyx.osu2phira.domain

import java.math.BigDecimal
import java.math.MathContext
import java.util.LinkedHashMap

/** Small JSON value tree used to keep the generated chart schema explicit. */
internal sealed interface JsonValue {
    data class Object(val fields: LinkedHashMap<String, JsonValue>) : JsonValue
    data class Array(val items: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val text: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

internal fun jsonObject(vararg fields: Pair<String, JsonValue>): JsonValue.Object =
    JsonValue.Object(linkedMapOf(*fields))

internal fun jsonArray(items: Iterable<JsonValue>): JsonValue.Array =
    JsonValue.Array(items.toList())

internal fun jsonString(value: String): JsonValue.StringValue = JsonValue.StringValue(value)

internal fun jsonNumber(value: BigDecimal): JsonValue.NumberValue =
    JsonValue.NumberValue(decimalText(value))

internal fun jsonNumber(value: Long): JsonValue.NumberValue =
    JsonValue.NumberValue(value.toString())

internal fun jsonNumber(value: Int): JsonValue.NumberValue =
    JsonValue.NumberValue(value.toString())

internal fun decimalText(value: BigDecimal): String {
    if (value.compareTo(BigDecimal.ZERO) == 0) return "0"
    return value.stripTrailingZeros().toPlainString()
}

/** Deterministic, UTF-8 friendly JSON writer with no third-party dependency. */
internal object JsonCodec {
    fun encode(value: JsonValue): String = buildString {
        appendValue(value, this, 0)
        append('\n')
    }

    private fun appendValue(value: JsonValue, out: StringBuilder, indent: Int) {
        when (value) {
            is JsonValue.Object -> appendObject(value, out, indent)
            is JsonValue.Array -> appendArray(value, out, indent)
            is JsonValue.StringValue -> appendString(value.value, out)
            is JsonValue.NumberValue -> out.append(value.text)
            is JsonValue.BooleanValue -> out.append(if (value.value) "true" else "false")
            JsonValue.NullValue -> out.append("null")
        }
    }

    private fun appendObject(value: JsonValue.Object, out: StringBuilder, indent: Int) {
        if (value.fields.isEmpty()) {
            out.append("{}")
            return
        }
        out.append("{\n")
        value.fields.entries.forEachIndexed { index, (key, child) ->
            appendIndent(out, indent + 1)
            appendString(key, out)
            out.append(": ")
            appendValue(child, out, indent + 1)
            if (index != value.fields.size - 1) out.append(',')
            out.append('\n')
        }
        appendIndent(out, indent)
        out.append('}')
    }

    private fun appendArray(value: JsonValue.Array, out: StringBuilder, indent: Int) {
        if (value.items.isEmpty()) {
            out.append("[]")
            return
        }
        out.append("[\n")
        value.items.forEachIndexed { index, child ->
            appendIndent(out, indent + 1)
            appendValue(child, out, indent + 1)
            if (index != value.items.size - 1) out.append(',')
            out.append('\n')
        }
        appendIndent(out, indent)
        out.append(']')
    }

    private fun appendString(value: String, out: StringBuilder) {
        out.append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        val hex = "0123456789abcdef"
                        val code = character.code
                        out.append("\\u")
                        out.append(hex[(code ushr 12) and 0xF])
                        out.append(hex[(code ushr 8) and 0xF])
                        out.append(hex[(code ushr 4) and 0xF])
                        out.append(hex[code and 0xF])
                    } else {
                        out.append(character)
                    }
                }
            }
        }
        out.append('"')
    }

    private fun appendIndent(out: StringBuilder, indent: Int) {
        repeat(indent) { out.append("  ") }
    }
}

/** Strict JSON reader used only to validate the file after the ZIP is written. */
internal object MiniJson {
    fun parse(text: String): Any? = Parser(text).parse()

    private class Parser(private val text: String) {
        private var position = 0

        fun parse(): Any? {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (position != text.length) fail("多余内容")
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (position >= text.length) fail("缺少值")
            return when (text[position]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> fail("无法解析 JSON 值")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val result = LinkedHashMap<String, Any?>()
            if (consume('}')) return result
            while (true) {
                skipWhitespace()
                if (position >= text.length || text[position] != '"') fail("对象键必须是字符串")
                val key = parseString()
                if (result.containsKey(key)) fail("对象包含重复键：$key")
                skipWhitespace()
                expect(':')
                val value = parseValue()
                result[key] = value
                skipWhitespace()
                if (consume('}')) return result
                expect(',')
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            skipWhitespace()
            val result = ArrayList<Any?>()
            if (consume(']')) return result
            while (true) {
                result += parseValue()
                skipWhitespace()
                if (consume(']')) return result
                expect(',')
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (position < text.length) {
                val character = text[position++]
                when (character) {
                    '"' -> return result.toString()
                    '\\' -> {
                        if (position >= text.length) fail("字符串转义不完整")
                        when (val escaped = text[position++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> result.append(parseUnicodeEscape())
                            else -> fail("未知字符串转义")
                        }
                    }
                    else -> {
                        if (character.code < 0x20) fail("字符串包含控制字符")
                        result.append(character)
                    }
                }
            }
            fail("字符串未结束")
        }

        private fun parseUnicodeEscape(): Char {
            if (position + 4 > text.length) fail("Unicode 转义不完整")
            var value = 0
            repeat(4) {
                val digit = text[position++].digitToIntOrNull(16) ?: fail("Unicode 转义无效")
                value = (value shl 4) or digit
            }
            return value.toChar()
        }

        private fun parseNumber(): BigDecimal {
            val start = position
            consume('-')
            if (consume('0')) {
                if (position < text.length && text[position].isDigit()) fail("数字前导零无效")
            } else {
                requireDigits("数字整数部分为空")
            }
            if (consume('.')) requireDigits("数字小数部分为空")
            if (position < text.length && (text[position] == 'e' || text[position] == 'E')) {
                position++
                if (position < text.length && (text[position] == '+' || text[position] == '-')) position++
                requireDigits("数字指数部分为空")
            }
            val token = text.substring(start, position)
            return try {
                BigDecimal(token)
            } catch (error: NumberFormatException) {
                fail("数字无效", error)
            }
        }

        private fun parseLiteral(expected: String, value: Any?): Any? {
            if (!text.startsWith(expected, position)) fail("字面量无效")
            position += expected.length
            return value
        }

        private fun requireDigits(reason: String) {
            val start = position
            while (position < text.length && text[position].isDigit()) position++
            if (start == position) fail(reason)
        }

        private fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        private fun expect(expected: Char) {
            if (!consume(expected)) fail("缺少 $expected")
        }

        private fun consume(expected: Char): Boolean {
            if (position < text.length && text[position] == expected) {
                position++
                return true
            }
            return false
        }

        private fun fail(reason: String, cause: Throwable? = null): Nothing =
            throw IllegalArgumentException("JSON 第 ${position + 1} 个字符：$reason", cause)
    }
}

internal fun rpeTimeJson(value: RpeTime): JsonValue = jsonArray(
    listOf(
        jsonNumber(value.integer),
        jsonNumber(value.numerator),
        jsonNumber(value.denominator),
    )
)

private fun standardEvent(
    start: BigDecimal,
    end: BigDecimal,
    startTime: RpeTime,
    endTime: RpeTime,
): JsonValue = jsonObject(
    "bezier" to jsonNumber(0),
    "bezierPoints" to jsonArray(listOf(jsonNumber(BigDecimal.ZERO), jsonNumber(BigDecimal.ZERO), jsonNumber(BigDecimal.ZERO), jsonNumber(BigDecimal.ZERO))),
    "easingLeft" to jsonNumber(BigDecimal.ZERO),
    "easingRight" to jsonNumber(BigDecimal.ONE),
    "easingType" to jsonNumber(1),
    "end" to jsonNumber(end),
    "endTime" to rpeTimeJson(endTime),
    "linkgroup" to jsonNumber(0),
    "start" to jsonNumber(start),
    "startTime" to rpeTimeJson(startTime),
)

private fun standardEvent(
    start: Int,
    end: Int,
    startTime: RpeTime,
    endTime: RpeTime,
): JsonValue = standardEvent(
    BigDecimal.valueOf(start.toLong()),
    BigDecimal.valueOf(end.toLong()),
    startTime,
    endTime,
)

internal fun renderChartJson(
    chart: ConvertedManiaChart,
    audioTarget: String,
    backgroundTarget: String,
): String {
    val bpmList = chart.bpmList.map { event ->
        jsonObject(
            "bpm" to jsonNumber(event.bpm),
            "startTime" to rpeTimeJson(event.startTime),
        )
    }
    val notes = chart.notes.map { note ->
        val fields = linkedMapOf<String, JsonValue>(
            "above" to jsonNumber(note.above),
            "alpha" to jsonNumber(note.alpha),
            "endTime" to rpeTimeJson(note.endTime),
            "isFake" to jsonNumber(note.isFake),
            "positionX" to jsonNumber(note.positionX),
            "size" to jsonNumber(note.size),
            "speed" to jsonNumber(note.speed),
            "startTime" to rpeTimeJson(note.startTime),
            "type" to jsonNumber(note.type),
            "visibleTime" to jsonNumber(note.visibleTime),
            "yOffset" to jsonNumber(note.yOffset),
        )
        if (note.type != 2) note.hitsound?.let { fields["hitsound"] = jsonString(it) }
        JsonValue.Object(fields)
    }
    val moveY = standardEvent(
        chart.moveYEvent.start,
        chart.moveYEvent.end,
        chart.moveYEvent.startTime,
        chart.moveYEvent.endTime,
    )
    val alpha = standardEvent(
        chart.alphaEvent.start,
        chart.alphaEvent.end,
        chart.alphaEvent.startTime,
        chart.alphaEvent.endTime,
    )
    val speedEvents = chart.speedEvents.map { event ->
        jsonObject(
            "end" to jsonNumber(event.end),
            "endTime" to rpeTimeJson(event.endTime),
            "linkgroup" to jsonNumber(0),
            "start" to jsonNumber(event.start),
            "startTime" to rpeTimeJson(event.startTime),
        )
    }
    val line = jsonObject(
        "Name" to jsonString(chart.judgeLineName),
        "Texture" to jsonString("line.png"),
        "father" to jsonNumber(-1),
        "isCover" to jsonNumber(1),
        "bpmfactor" to jsonNumber(BigDecimal.ONE),
        "notes" to jsonArray(notes),
        "numOfNotes" to jsonNumber(chart.tapCount),
        "eventLayers" to jsonArray(
            listOf(
                jsonObject(
                    "alphaEvents" to jsonArray(listOf(alpha)),
                    "moveYEvents" to jsonArray(listOf(moveY)),
                    "speedEvents" to jsonArray(speedEvents),
                )
            )
        ),
    )
    val root = jsonObject(
        "BPMList" to jsonArray(bpmList),
        "META" to jsonObject(
            "RPEVersion" to jsonNumber(ConversionDefaults.rpeVersion),
            "background" to jsonString(backgroundTarget),
            "song" to jsonString(audioTarget),
            "name" to jsonString(chart.meta.name),
            "level" to jsonString(chart.meta.level),
            "composer" to jsonString(chart.meta.composer),
            "charter" to jsonString(chart.meta.charter),
            "offset" to jsonNumber(chart.meta.offsetMs),
        ),
        "judgeLineList" to jsonArray(listOf(line)),
    )
    return JsonCodec.encode(root)
}

internal fun renderInfoYaml(
    chart: ConvertedManiaChart,
    audioTarget: String,
    backgroundTarget: String,
): String {
    val previewStart = chart.source.previewTimeMs.max(BigDecimal.ZERO)
        .divide(BigDecimal("1000"), MathContext.DECIMAL128)
    val fields = linkedMapOf<String, JsonValue>(
        "name" to jsonString(chart.meta.name),
        "difficulty" to jsonNumber(chart.source.overallDifficulty),
        "level" to jsonString(chart.meta.level),
        "charter" to jsonString(chart.meta.charter),
        "composer" to jsonString(chart.meta.composer),
        "illustrator" to jsonString(""),
        "chart" to jsonString("chart.json"),
        "music" to jsonString(audioTarget),
        "illustration" to jsonString(backgroundTarget),
        "previewStart" to jsonNumber(previewStart),
        "previewEnd" to jsonNumber(previewStart.add(BigDecimal("15"))),
        "aspectRatio" to jsonNumber(BigDecimal("1.7777778")),
        "backgroundDim" to jsonNumber(BigDecimal("0.6")),
        "lineLength" to jsonNumber(BigDecimal("6.0")),
        "offset" to jsonNumber(BigDecimal.ZERO),
        "tags" to jsonArray(emptyList()),
        "intro" to jsonString(""),
        "holdPartialCover" to JsonValue.BooleanValue(false),
    )
    return buildString {
        fields.forEach { (key, value) ->
            append(key).append(": ")
            append(JsonCodec.encode(value).trimEnd('\n'))
            append('\n')
        }
    }
}
