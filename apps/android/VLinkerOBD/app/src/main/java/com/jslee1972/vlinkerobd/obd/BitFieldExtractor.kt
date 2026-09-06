package com.jslee1972.vlinkerobd.obd

/**
 * Specifies a signal packed at the bit level within a (possibly long, multi-byte) UDS payload —
 * the representation manufacturer telemetry like battery cell voltages or tire pressure PIDs
 * needs, versus the plain byte-arithmetic [PidFormula] used by simple standard PIDs.
 *
 * Bit 0 is the most-significant bit of the first payload byte (the byte right after the
 * mode+PID echo), and bit numbering proceeds MSB-first across successive bytes — the
 * "big-endian"/Motorola bit order used throughout the OBDb community signalsets
 * (https://github.com/OBDb) this schema is modeled on. [signed] applies two's-complement
 * sign extension. Final value = raw * [multiplier] / [divisor] + [offset], clamped to
 * [min]/[max] when given.
 */
data class BitFieldSpec(
    val bitIndex: Int,
    val bitLength: Int,
    val multiplier: Double = 1.0,
    val divisor: Double = 1.0,
    val offset: Double = 0.0,
    val signed: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
)

object BitFieldExtractor {

    fun extractRaw(bytes: List<Int>, bitIndex: Int, bitLength: Int, signed: Boolean): Long? {
        if (bitLength <= 0 || bitLength > 63 || bitIndex < 0) return null
        val totalBits = bytes.size * 8
        if (bitIndex + bitLength > totalBits) return null

        var raw = 0L
        for (offset in 0 until bitLength) {
            val bitPos = bitIndex + offset
            val byteValue = bytes[bitPos / 8]
            val bitInByte = bitPos % 8
            val bit = (byteValue shr (7 - bitInByte)) and 1
            raw = (raw shl 1) or bit.toLong()
        }

        if (signed) {
            val signBit = 1L shl (bitLength - 1)
            if (raw and signBit != 0L) {
                raw -= (1L shl bitLength)
            }
        }
        return raw
    }

    fun evaluate(bytes: List<Int>, spec: BitFieldSpec): Double? {
        val raw = extractRaw(bytes, spec.bitIndex, spec.bitLength, spec.signed) ?: return null
        var value = raw.toDouble() * spec.multiplier / spec.divisor + spec.offset
        spec.min?.let { value = maxOf(value, it) }
        spec.max?.let { value = minOf(value, it) }
        return value
    }
}
