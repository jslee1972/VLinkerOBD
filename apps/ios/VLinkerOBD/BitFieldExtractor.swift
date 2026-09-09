import Foundation

/// Decodes a ``BitFieldSpec`` against response payload bytes. Faithful port of Android's
/// `BitFieldExtractor.kt` — bit 0 is the MSB of the first payload byte, MSB-first across
/// successive bytes (the OBDb community "Motorola" bit order), two's-complement for `signed`.
enum BitFieldExtractor {

    static func extractRaw(bytes: [Int], bitIndex: Int, bitLength: Int, signed: Bool) -> Int64? {
        guard bitLength > 0, bitLength <= 63, bitIndex >= 0 else { return nil }
        let totalBits = bytes.count * 8
        guard bitIndex + bitLength <= totalBits else { return nil }

        var raw: Int64 = 0
        for offset in 0..<bitLength {
            let bitPos = bitIndex + offset
            let byteValue = bytes[bitPos / 8]
            let bitInByte = bitPos % 8
            let bit = (byteValue >> (7 - bitInByte)) & 1
            raw = (raw << 1) | Int64(bit)
        }

        if signed {
            let signBit: Int64 = 1 << (bitLength - 1)
            if raw & signBit != 0 {
                raw -= (Int64(1) << bitLength)
            }
        }
        return raw
    }

    static func evaluate(bytes: [Int], spec: BitFieldSpec) -> Double? {
        guard let raw = extractRaw(bytes: bytes, bitIndex: spec.bitIndex, bitLength: spec.bitLength, signed: spec.signed) else {
            return nil
        }
        var value = Double(raw) * spec.multiplier / spec.divisor + spec.offset
        if let minValue = spec.min { value = max(value, minValue) }
        if let maxValue = spec.max { value = min(value, maxValue) }
        return value
    }
}
