import Foundation

/// Classified meaning of a raw ELM/STN response, distinguishing normal data from the two failure
/// shapes an adapter can legitimately return: "NO DATA" (PID not supported / ECU didn't answer in
/// time) and a UDS/OBD negative response "7F <service> <nrc>" (service explicitly refused).
enum ObdResponseStatus: Equatable {
    case data([Int])
    case noData
    case negativeResponse(service: String, nrc: String, messageZh: String)
    case unrecognized
}

/// Faithful port of Android's `ObdResponseParser.kt`: normalizes raw ELM/STN text (including the
/// two multi-frame display quirks seen in the field — a standalone length-header line, and
/// "<n>:" frame-index prefixes), classifies it, and extracts PID/VIN payload bytes by locating the
/// "<mode+0x40> <pid>" echo marker anywhere in the decoded byte stream.
enum ObdResponseParser {

    private static let negativeResponseCodes: [String: String] = [
        "10": "一般拒絕",
        "11": "服務不支援",
        "12": "不支援此 PID",
        "22": "條件不符",
        "31": "請求超出範圍",
        "33": "安全存取被拒",
        "78": "處理中，請稍候",
    ]

    /// Uppercases, strips echo/prompt/"SEARCHING" noise, and collapses whitespace to single
    /// spaces. Also unwraps two ELM/STN multi-frame display quirks seen on real vehicles with
    /// responses too long for one CAN frame (e.g. a 20-byte Mode 09 VIN reply): a standalone
    /// total-length byte on its own line ahead of the data, and a "<n>:" index prefix on each
    /// following line. Both are stripped line-by-line before whitespace flattening.
    static func normalize(_ raw: String) -> String {
        let cleaned = raw
            .uppercased()
            .replacingOccurrences(of: ">", with: "")
            .replacingOccurrences(of: "SEARCHING...", with: "")
            .replacingOccurrences(of: "SEARCHING", with: "")

        let lines = cleaned
            .components(separatedBy: CharacterSet(charactersIn: "\r\n"))
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }

        var tokens: [String] = []
        let lengthHeaderRegex = try! NSRegularExpression(pattern: "^[0-9A-F]{1,4}$")
        let frameIndexRegex = try! NSRegularExpression(pattern: "^\\d{1,2}:")

        for (index, line) in lines.enumerated() {
            if index == 0, lines.count > 1,
               lengthHeaderRegex.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) != nil {
                continue
            }
            let range = NSRange(line.startIndex..., in: line)
            let withoutFrameIndex = frameIndexRegex.stringByReplacingMatches(in: line, range: range, withTemplate: "")
            let parts = withoutFrameIndex.split(whereSeparator: { $0.isWhitespace }).map(String.init)
            tokens.append(contentsOf: parts)
        }
        return tokens.joined(separator: " ")
    }

    static func classify(_ raw: String) -> ObdResponseStatus {
        let normalized = normalize(raw)
        if normalized.isEmpty { return .unrecognized }
        if normalized.contains("NO DATA") { return .noData }

        let compact = normalized.replacingOccurrences(of: " ", with: "")

        if let regex = try? NSRegularExpression(pattern: "7F([0-9A-F]{2})([0-9A-F]{2})"),
           let match = regex.firstMatch(in: compact, range: NSRange(compact.startIndex..., in: compact)),
           let serviceRange = Range(match.range(at: 1), in: compact),
           let nrcRange = Range(match.range(at: 2), in: compact) {
            let service = String(compact[serviceRange])
            let nrc = String(compact[nrcRange])
            let messageZh = negativeResponseCodes[nrc] ?? "未知錯誤代碼 \(nrc)"
            return .negativeResponse(service: service, nrc: nrc, messageZh: messageZh)
        }

        guard compact.count % 2 == 0, compact.allSatisfy({ $0.isHexDigit }) else {
            return .unrecognized
        }
        var bytes: [Int] = []
        var index = compact.startIndex
        while index < compact.endIndex {
            let next = compact.index(index, offsetBy: 2)
            guard let value = Int(compact[index..<next], radix: 16) else { return .unrecognized }
            bytes.append(value)
            index = next
        }
        return .data(bytes)
    }

    /// Parses a positive response to `request` (mode + PID as hex, e.g. "010D" or "22C901") and
    /// evaluates `formula` against the payload bytes.
    static func parsePid(_ raw: String, request: String, formula: String) -> Double? {
        guard let payload = payloadBytes(raw, request: request) else { return nil }
        return PidFormula.evaluate(formula, bytes: payload)
    }

    /// Same as `parsePid`, but decodes the payload with a ``BitFieldSpec`` instead of an
    /// arithmetic formula string.
    static func parsePidBitField(_ raw: String, request: String, spec: BitFieldSpec) -> Double? {
        guard let payload = payloadBytes(raw, request: request) else { return nil }
        return BitFieldExtractor.evaluate(bytes: payload, spec: spec)
    }

    /// Parses a Mode 09 PID 02 (VIN) response. The payload after the "49 02" echo starts with a
    /// one-byte data-item count (not part of the VIN) followed by the 17-character VIN as ASCII
    /// bytes; filtering to printable ASCII (0x20–0x7E) drops that count byte and any padding
    /// without needing to special-case its position.
    static func parseVin(_ raw: String) -> String? {
        guard let payload = payloadBytes(raw, request: "0902") else { return nil }
        let vin = String(payload.filter { $0 >= 0x20 && $0 <= 0x7E }.map { Character(UnicodeScalar($0)!) })
        return vin.isEmpty ? nil : vin
    }

    /// Locates the "<mode+0x40> <pid>" echo marker anywhere in the decoded byte stream (tolerating
    /// command echo, split lines, and compact/unspaced responses) and returns the bytes after it.
    static func payloadBytes(_ raw: String, request: String) -> [Int]? {
        guard case let .data(bytes) = classify(raw) else { return nil }
        guard request.count >= 4, request.count % 2 == 0 else { return nil }

        let modeText = String(request.prefix(2))
        guard let modeValue = Int(modeText, radix: 16) else { return nil }

        var pidBytes: [Int] = []
        var idx = request.index(request.startIndex, offsetBy: 2)
        while idx < request.endIndex {
            let next = request.index(idx, offsetBy: 2)
            guard let b = Int(request[idx..<next], radix: 16) else { return nil }
            pidBytes.append(b)
            idx = next
        }

        let marker = [modeValue + 0x40] + pidBytes
        guard let markerIndex = indexOfSubList(haystack: bytes, needle: marker) else { return nil }
        let payload = Array(bytes.dropFirst(markerIndex + marker.count))
        return payload.isEmpty ? nil : payload
    }

    private static func indexOfSubList(haystack: [Int], needle: [Int]) -> Int? {
        guard !needle.isEmpty, needle.count <= haystack.count else { return nil }
        outer: for start in 0...(haystack.count - needle.count) {
            for offset in needle.indices {
                if haystack[start + offset] != needle[offset] { continue outer }
            }
            return start
        }
        return nil
    }
}
