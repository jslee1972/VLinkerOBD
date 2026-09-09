import Foundation

/// Evaluates the simple arithmetic formulas used by vehicle profile PID definitions
/// (e.g. "A-40", "((A*256)+B)/4", "((A*1373)/1000)*0.145037738").
///
/// Supports variables A-Z bound to the response bytes that follow the PID echo (needed for
/// multi-sensor SAE J1979 PIDs like $78/$83, whose later sensors live past byte D), decimal
/// literals, +, -, *, /, and parentheses. Anything else is unsupported and returns nil.
/// Faithful port of Android's `PidFormula.kt` recursive-descent evaluator — same grammar,
/// same null-on-divide-by-zero and null-on-out-of-range-byte semantics.
enum PidFormula {

    static func evaluate(_ formula: String, bytes: [Int]) -> Double? {
        guard let tokens = tokenize(formula) else { return nil }
        var parser = Parser(tokens: tokens, bytes: bytes)
        guard let result = parser.parseExpression(), parser.isAtEnd else { return nil }
        return result
    }

    private enum Token {
        case number(Double)
        case variable(Character)
        case op(Character)
    }

    private static func tokenize(_ formula: String) -> [Token]? {
        var tokens: [Token] = []
        let chars = Array(formula)
        var i = 0
        while i < chars.count {
            let c = chars[i]
            if c.isWhitespace {
                i += 1
            } else if "+-*/()".contains(c) {
                tokens.append(.op(c))
                i += 1
            } else if c.isNumber || c == "." {
                let start = i
                while i < chars.count && (chars[i].isNumber || chars[i] == ".") {
                    i += 1
                }
                let numberText = String(chars[start..<i])
                guard let value = Double(numberText) else { return nil }
                tokens.append(.number(value))
            } else if let upper = c.uppercased().first, upper >= "A" && upper <= "Z" {
                tokens.append(.variable(upper))
                i += 1
            } else {
                return nil
            }
        }
        return tokens
    }

    private struct Parser {
        let tokens: [Token]
        let bytes: [Int]
        var pos = 0

        var isAtEnd: Bool { pos >= tokens.count }

        mutating func parseExpression() -> Double? {
            guard var left = parseTerm() else { return nil }
            while !isAtEnd {
                guard case let .op(symbol) = tokens[pos], symbol == "+" || symbol == "-" else { break }
                pos += 1
                guard let right = parseTerm() else { return nil }
                left = symbol == "+" ? left + right : left - right
            }
            return left
        }

        private mutating func parseTerm() -> Double? {
            guard var left = parseFactor() else { return nil }
            while !isAtEnd {
                guard case let .op(symbol) = tokens[pos], symbol == "*" || symbol == "/" else { break }
                pos += 1
                guard let right = parseFactor() else { return nil }
                if symbol == "/" && right == 0.0 { return nil }
                left = symbol == "*" ? left * right : left / right
            }
            return left
        }

        private mutating func parseFactor() -> Double? {
            guard !isAtEnd else { return nil }
            switch tokens[pos] {
            case let .op(symbol):
                if symbol == "-" {
                    pos += 1
                    guard let value = parseFactor() else { return nil }
                    return -value
                }
                if symbol == "(" {
                    pos += 1
                    guard let value = parseExpression() else { return nil }
                    guard pos < tokens.count, case let .op(close) = tokens[pos], close == ")" else { return nil }
                    pos += 1
                    return value
                }
                return nil
            case let .number(value):
                pos += 1
                return value
            case let .variable(letter):
                pos += 1
                let index = Int(letter.asciiValue! - Character("A").asciiValue!)
                guard index >= 0, index < bytes.count else { return nil }
                return Double(bytes[index])
            }
        }
    }
}
