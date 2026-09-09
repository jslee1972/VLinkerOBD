import Foundation

/// Decodes Diagnostic Trouble Codes from Mode 03 (current), 07 (pending), or 0A (permanent)
/// responses per SAE J2012 / ISO 15031-6. Faithful port of Android's `DtcParser.kt`: each DTC is
/// 2 bytes, the top 2 bits of the first byte select the P/C/B/U category, the remaining 14 bits
/// render as 4 hex digits. A "00 00" pair is padding, not a code. Returns an empty array (not
/// nil) when the ECU reports zero codes; returns nil when the response is NO DATA, a 7F negative
/// response, or otherwise unparsable.
enum DtcParser {

    private static let responseByteForMode: [String: Int] = ["03": 0x43, "07": 0x47, "0A": 0x4A]

    static func parse(_ raw: String, requestMode: String) -> [String]? {
        guard let responseByte = responseByteForMode[requestMode] else { return nil }
        guard case let .data(bytes) = ObdResponseParser.classify(raw) else { return nil }
        guard let markerIndex = bytes.firstIndex(of: responseByte) else { return nil }
        let payload = Array(bytes.dropFirst(markerIndex + 1))

        var codes: [String] = []
        var i = 0
        while i + 1 < payload.count {
            let high = payload[i]
            let low = payload[i + 1]
            i += 2
            if high == 0 && low == 0 { continue }
            codes.append(decode(high: high, low: low))
        }
        return codes
    }

    private static func decode(high: Int, low: Int) -> String {
        let category: Character
        switch (high >> 6) & 0x3 {
        case 0: category = "P"
        case 1: category = "C"
        case 2: category = "B"
        default: category = "U"
        }
        let firstDigit = (high >> 4) & 0x3
        let secondDigit = high & 0xF
        let thirdDigit = (low >> 4) & 0xF
        let fourthDigit = low & 0xF
        return "\(category)\(String(firstDigit, radix: 16).uppercased())\(String(secondDigit, radix: 16).uppercased())\(String(thirdDigit, radix: 16).uppercased())\(String(fourthDigit, radix: 16).uppercased())"
    }
}
