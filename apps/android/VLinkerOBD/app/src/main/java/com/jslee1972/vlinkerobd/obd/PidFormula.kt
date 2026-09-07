package com.jslee1972.vlinkerobd.obd

/**
 * Evaluates the simple arithmetic formulas used by vehicle profile PID definitions
 * (e.g. "A-40", "((A*256)+B)/4", "((A*1373)/1000)*0.145037738").
 *
 * Supports variables A-Z bound to the response bytes that follow the PID echo (needed for
 * multi-sensor SAE J1979 PIDs like $78/$83, whose later sensors live past byte D), decimal
 * literals, +, -, *, /, and parentheses. Anything else (Signed()/bit-field syntax used by some
 * EV profiles) is unsupported and returns null.
 */
object PidFormula {

    fun evaluate(formula: String, bytes: List<Int>): Double? {
        val tokens = tokenize(formula) ?: return null
        val parser = Parser(tokens, bytes)
        val result = parser.parseExpression() ?: return null
        if (!parser.isAtEnd()) return null
        return result
    }

    private sealed class Token {
        data class Number(val value: Double) : Token()
        data class Variable(val letter: Char) : Token()
        data class Op(val symbol: Char) : Token()
    }

    private fun tokenize(formula: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < formula.length) {
            val c = formula[i]
            when {
                c.isWhitespace() -> i++
                c in "+-*/()" -> {
                    tokens += Token.Op(c)
                    i++
                }
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < formula.length && (formula[i].isDigit() || formula[i] == '.')) i++
                    val numberText = formula.substring(start, i)
                    val value = numberText.toDoubleOrNull() ?: return null
                    tokens += Token.Number(value)
                }
                c.uppercaseChar() in 'A'..'Z' -> {
                    tokens += Token.Variable(c.uppercaseChar())
                    i++
                }
                else -> return null
            }
        }
        return tokens
    }

    private class Parser(private val tokens: List<Token>, private val bytes: List<Int>) {
        private var pos = 0

        fun isAtEnd() = pos >= tokens.size

        fun parseExpression(): Double? {
            var left = parseTerm() ?: return null
            while (!isAtEnd()) {
                val op = (tokens[pos] as? Token.Op)?.symbol
                if (op != '+' && op != '-') break
                pos++
                val right = parseTerm() ?: return null
                left = if (op == '+') left + right else left - right
            }
            return left
        }

        private fun parseTerm(): Double? {
            var left = parseFactor() ?: return null
            while (!isAtEnd()) {
                val op = (tokens[pos] as? Token.Op)?.symbol
                if (op != '*' && op != '/') break
                pos++
                val right = parseFactor() ?: return null
                if (op == '/' && right == 0.0) return null
                left = if (op == '*') left * right else left / right
            }
            return left
        }

        private fun parseFactor(): Double? {
            if (isAtEnd()) return null
            when (val token = tokens[pos]) {
                is Token.Op -> {
                    if (token.symbol == '-') {
                        pos++
                        val value = parseFactor() ?: return null
                        return -value
                    }
                    if (token.symbol == '(') {
                        pos++
                        val value = parseExpression() ?: return null
                        val close = tokens.getOrNull(pos) as? Token.Op
                        if (close?.symbol != ')') return null
                        pos++
                        return value
                    }
                    return null
                }
                is Token.Number -> {
                    pos++
                    return token.value
                }
                is Token.Variable -> {
                    pos++
                    val index = token.letter - 'A'
                    return bytes.getOrNull(index)?.toDouble()
                }
            }
        }
    }
}
