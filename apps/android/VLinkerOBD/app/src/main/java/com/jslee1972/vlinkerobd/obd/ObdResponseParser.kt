package com.jslee1972.vlinkerobd.obd

/**
 * Classified meaning of a raw ELM/STN response, distinguishing normal data from the two
 * failure shapes an adapter can legitimately return: "NO DATA" (PID not supported / ECU didn't
 * answer in time) and a UDS/OBD negative response "7F <service> <nrc>" (service explicitly
 * refused, e.g. sub-function/PID not supported).
 */
sealed interface ObdResponseStatus {
    data class Data(val bytes: List<Int>) : ObdResponseStatus
    data object NoData : ObdResponseStatus
    data class NegativeResponse(val service: String, val nrc: String, val messageZh: String) : ObdResponseStatus
    data object Unrecognized : ObdResponseStatus
}

object ObdResponseParser {

    private val negativeResponseCodes = mapOf(
        "10" to "一般拒絕",
        "11" to "服務不支援",
        "12" to "不支援此 PID",
        "22" to "條件不符",
        "31" to "請求超出範圍",
        "33" to "安全存取被拒",
        "78" to "處理中，請稍候",
    )

    /** Uppercases, strips echo/prompt/"SEARCHING" noise, and collapses whitespace to single spaces. */
    fun normalize(raw: String): String {
        return raw
            .uppercase()
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(">", " ")
            .replace("SEARCHING...", " ")
            .replace("SEARCHING", " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    fun classify(raw: String): ObdResponseStatus {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return ObdResponseStatus.Unrecognized
        if (normalized.contains("NO DATA")) return ObdResponseStatus.NoData

        val compact = normalized.replace(" ", "")

        Regex("7F([0-9A-F]{2})([0-9A-F]{2})").find(compact)?.let { match ->
            val service = match.groupValues[1]
            val nrc = match.groupValues[2]
            val messageZh = negativeResponseCodes[nrc] ?: "未知錯誤代碼 $nrc"
            return ObdResponseStatus.NegativeResponse(service, nrc, messageZh)
        }

        if (compact.length % 2 != 0 || !compact.matches(Regex("[0-9A-F]+"))) {
            return ObdResponseStatus.Unrecognized
        }
        val bytes = compact.chunked(2).map { it.toInt(16) }
        return ObdResponseStatus.Data(bytes)
    }

    /**
     * Parses a positive response to [request] (mode + PID as hex, e.g. "010D" or "22C901") and
     * evaluates [formula] against the payload bytes. Tolerates command echo, split lines, and
     * compact (unspaced) responses by locating the "<mode+0x40> <pid>" marker anywhere in the
     * decoded byte stream.
     */
    fun parsePid(raw: String, request: String, formula: String): Double? {
        val payload = payloadBytes(raw, request) ?: return null
        return PidFormula.evaluate(formula, payload)
    }

    /**
     * Parses a positive response the same way as [parsePid], but decodes the payload with a
     * [BitFieldSpec] instead of an arithmetic formula string — needed for manufacturer PIDs that
     * pack many signals (bit flags, multi-byte battery telemetry) into one long UDS response.
     */
    fun parsePidBitField(raw: String, request: String, spec: BitFieldSpec): Double? {
        val payload = payloadBytes(raw, request) ?: return null
        return BitFieldExtractor.evaluate(payload, spec)
    }

    /**
     * Locates the "<mode+0x40> <pid>" echo marker anywhere in the decoded byte stream (tolerating
     * command echo, split lines, and compact/unspaced responses) and returns the bytes after it.
     */
    private fun payloadBytes(raw: String, request: String): List<Int>? {
        val data = classify(raw) as? ObdResponseStatus.Data ?: return null
        if (request.length < 4 || request.length % 2 != 0) return null

        val modeValue = request.substring(0, 2).toIntOrNull(16) ?: return null
        val pidBytes = request.substring(2).chunked(2).map { it.toIntOrNull(16) ?: return null }
        val marker = listOf(modeValue + 0x40) + pidBytes

        val markerIndex = indexOfSubList(data.bytes, marker)
        if (markerIndex < 0) return null
        val payload = data.bytes.drop(markerIndex + marker.size)
        return payload.ifEmpty { null }
    }

    private fun indexOfSubList(haystack: List<Int>, needle: List<Int>): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (start in 0..(haystack.size - needle.size)) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }
}
