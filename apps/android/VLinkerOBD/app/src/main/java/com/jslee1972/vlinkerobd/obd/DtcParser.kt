package com.jslee1972.vlinkerobd.obd

/**
 * Decodes Diagnostic Trouble Codes from Mode 03 (current), 07 (pending), or 0A (permanent)
 * responses per SAE J2012 / ISO 15031-6: each DTC is 2 bytes, the top 2 bits of the first byte
 * select the P/C/B/U category, and the remaining 14 bits render as 4 hex digits. A "00 00" pair
 * is padding, not a code. Returns an empty list (not null) when the ECU reports zero codes;
 * returns null when the response is NO DATA, a 7F negative response, or otherwise unparsable.
 */
object DtcParser {

    private val responseByteForMode = mapOf("03" to 0x43, "07" to 0x47, "0A" to 0x4A)

    fun parse(raw: String, requestMode: String): List<String>? {
        val responseByte = responseByteForMode[requestMode] ?: return null
        val data = ObdResponseParser.classify(raw) as? ObdResponseStatus.Data ?: return null

        val markerIndex = data.bytes.indexOf(responseByte)
        if (markerIndex < 0) return null
        val payload = data.bytes.drop(markerIndex + 1)

        val codes = mutableListOf<String>()
        var i = 0
        while (i + 1 < payload.size) {
            val high = payload[i]
            val low = payload[i + 1]
            i += 2
            if (high == 0 && low == 0) continue
            codes += decode(high, low)
        }
        return codes
    }

    private fun decode(high: Int, low: Int): String {
        val category = when ((high shr 6) and 0x3) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }
        val firstDigit = (high shr 4) and 0x3
        val secondDigit = high and 0xF
        val thirdDigit = (low shr 4) and 0xF
        val fourthDigit = low and 0xF
        return buildString {
            append(category)
            append(firstDigit.toString(16).uppercase())
            append(secondDigit.toString(16).uppercase())
            append(thirdDigit.toString(16).uppercase())
            append(fourthDigit.toString(16).uppercase())
        }
    }
}
