import Foundation

enum OBDParser {
    static func normalized(_ input: String) -> String {
        input
            .uppercased()
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: ">", with: " ")
            .replacingOccurrences(of: "SEARCHING...", with: " ")
            .replacingOccurrences(of: "SEARCHING", with: " ")
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
    }

    static func parseSpeedKPH(_ response: String) -> Int? {
        let clean = normalized(response)
        let tokens = clean.split(separator: " ").map(String.init)

        // 正常：41 0D XX
        for i in 0..<(max(0, tokens.count - 2)) {
            if tokens[i] == "41", tokens[i + 1] == "0D",
               let speed = Int(tokens[i + 2], radix: 16) {
                return speed
            }
        }

        // 部分 adapter 可能回傳無空格：410D3C
        let compact = clean.replacingOccurrences(of: " ", with: "")
        if let range = compact.range(of: "410D") {
            let start = range.upperBound
            guard compact.distance(from: start, to: compact.endIndex) >= 2 else { return nil }
            let end = compact.index(start, offsetBy: 2)
            return Int(String(compact[start..<end]), radix: 16)
        }

        return nil
    }
}
