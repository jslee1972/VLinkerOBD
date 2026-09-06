package com.jslee1972.vlinkerobd.obd

/**
 * Minimal JSON parser covering exactly what vehicle profile files need (objects, arrays,
 * strings, numbers, booleans, null). Avoids depending on org.json (stubbed out in plain JVM
 * unit tests without Robolectric) or pulling in a full JSON library for a handful of fields.
 */
sealed interface JsonValue {
    data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue
    data class JsonArray(val items: List<JsonValue>) : JsonValue
    data class JsonString(val value: String) : JsonValue
    data class JsonNumber(val value: Double) : JsonValue
    data class JsonBoolean(val value: Boolean) : JsonValue
    data object JsonNull : JsonValue
}

object MiniJson {

    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        require(parser.isAtEnd()) { "Unexpected trailing content in JSON" }
        return value
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun isAtEnd() = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            return when (val c = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.JsonString(parseStringLiteral())
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> if (c == '-' || c.isDigit()) parseNumber() else error("Unexpected character '$c' at $pos")
            }
        }

        private fun parseObject(): JsonValue.JsonObject {
            expect('{')
            val fields = mutableMapOf<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return JsonValue.JsonObject(fields)
            }
            while (true) {
                skipWhitespace()
                val key = parseStringLiteral()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                fields[key] = value
                skipWhitespace()
                when (peek()) {
                    ',' -> { pos++ }
                    '}' -> { pos++; return JsonValue.JsonObject(fields) }
                    else -> error("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): JsonValue.JsonArray {
            expect('[')
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return JsonValue.JsonArray(items)
            }
            while (true) {
                items += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> { pos++ }
                    ']' -> { pos++; return JsonValue.JsonArray(items) }
                    else -> error("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseStringLiteral(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = text[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val escaped = text[pos++]
                        sb.append(
                            when (escaped) {
                                '"' -> '"'
                                '\\' -> '\\'
                                '/' -> '/'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'u' -> {
                                    val code = text.substring(pos, pos + 4).toInt(16)
                                    pos += 4
                                    code.toChar()
                                }
                                else -> error("Unsupported escape '\\$escaped'")
                            }
                        )
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): JsonValue.JsonNumber {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.' || text[pos] == 'e' || text[pos] == 'E' || text[pos] == '+' || text[pos] == '-')) pos++
            return JsonValue.JsonNumber(text.substring(start, pos).toDouble())
        }

        private fun parseBoolean(): JsonValue.JsonBoolean {
            return if (text.startsWith("true", pos)) {
                pos += 4
                JsonValue.JsonBoolean(true)
            } else {
                require(text.startsWith("false", pos)) { "Invalid literal at $pos" }
                pos += 5
                JsonValue.JsonBoolean(false)
            }
        }

        private fun parseNull(): JsonValue.JsonNull {
            require(text.startsWith("null", pos)) { "Invalid literal at $pos" }
            pos += 4
            return JsonValue.JsonNull
        }

        private fun peek(): Char {
            skipWhitespace()
            return text[pos]
        }

        private fun expect(c: Char) {
            skipWhitespace()
            require(pos < text.length && text[pos] == c) { "Expected '$c' at $pos" }
            pos++
        }
    }
}

fun JsonValue.asObject(): Map<String, JsonValue> = (this as JsonValue.JsonObject).fields
fun JsonValue.asArray(): List<JsonValue> = (this as JsonValue.JsonArray).items
fun JsonValue.asString(): String = (this as JsonValue.JsonString).value
