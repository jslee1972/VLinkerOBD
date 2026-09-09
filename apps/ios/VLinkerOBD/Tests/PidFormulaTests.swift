import XCTest
@testable import VLinkerOBD

final class PidFormulaTests: XCTestCase {
    func testEvaluatesSingleByte() {
        XCTAssertEqual(PidFormula.evaluate("A", bytes: [100]), 100.0)
    }

    func testEvaluatesTwoByteRpmFormula() {
        // 41 0C 1A F8 -> rpm = ((0x1A*256)+0xF8)/4 = 1726.0
        XCTAssertEqual(PidFormula.evaluate("((A*256)+B)/4", bytes: [0x1A, 0xF8]), 1726.0)
    }

    func testEvaluatesOffsetFormula() {
        XCTAssertEqual(PidFormula.evaluate("A-40", bytes: [90]), 50.0)
    }

    func testEvaluatesMazdaTirePressureConstant() {
        let result = PidFormula.evaluate("((A*1373)/1000)*0.145037738", bytes: [200])
        XCTAssertEqual(result ?? 0, 39.8274, accuracy: 0.001)
    }

    func testReturnsNilWhenNotEnoughBytes() {
        XCTAssertNil(PidFormula.evaluate("((A*256)+B)/4", bytes: [13]))
    }

    func testReturnsNilForDivisionByZero() {
        XCTAssertNil(PidFormula.evaluate("A/0", bytes: [10]))
    }

    func testSupportsVariablesPastD() {
        // SAE J1979 multi-sensor PID (e.g. $78) packs sensor 2/3 data past byte D.
        XCTAssertEqual(PidFormula.evaluate("(F*256)+G", bytes: [0, 0, 0, 0, 0, 1, 44]), 300.0)
    }

    func testUnsupportedSyntaxReturnsNil() {
        XCTAssertNil(PidFormula.evaluate("Signed(A)", bytes: [10]))
    }

    func testNegativeNumbersAndParentheses() {
        XCTAssertEqual(PidFormula.evaluate("-(A-125)", bytes: [100]), 25.0)
    }
}

final class BitFieldExtractorTests: XCTestCase {
    func testExtractsWholeByte() {
        XCTAssertEqual(BitFieldExtractor.extractRaw(bytes: [0xAB], bitIndex: 0, bitLength: 8, signed: false), 0xAB)
    }

    func testExtractsAcrossByteBoundary() {
        XCTAssertEqual(BitFieldExtractor.extractRaw(bytes: [0x0F, 0xF0], bitIndex: 4, bitLength: 8, signed: false), 0xFF)
    }

    func testExtractsSingleBit() {
        // 0x20 = 0b00100000 — MSB-first bit index 2 is the set bit.
        XCTAssertEqual(BitFieldExtractor.extractRaw(bytes: [0x20], bitIndex: 2, bitLength: 1, signed: false), 1)
    }

    func testSignedEightBit() {
        XCTAssertEqual(BitFieldExtractor.extractRaw(bytes: [0xFF], bitIndex: 0, bitLength: 8, signed: true), -1)
        XCTAssertEqual(BitFieldExtractor.extractRaw(bytes: [0xCE], bitIndex: 0, bitLength: 8, signed: true), -50)
    }

    func testSigned16BitEct() {
        let raw = BitFieldExtractor.extractRaw(bytes: [0xFF, 0x9C], bitIndex: 0, bitLength: 16, signed: true)
        XCTAssertEqual(raw, -100)
        let spec = BitFieldSpec(bitIndex: 0, bitLength: 16, divisor: 10, signed: true)
        XCTAssertEqual(BitFieldExtractor.evaluate(bytes: [0xFF, 0x9C], spec: spec), -10.0)
    }

    func testClampsToMin() {
        let spec = BitFieldSpec(bitIndex: 0, bitLength: 8, signed: true, min: -40.0)
        XCTAssertEqual(BitFieldExtractor.evaluate(bytes: [0xCE], spec: spec), -40.0)
    }

    func testOutOfRangeReturnsNil() {
        XCTAssertNil(BitFieldExtractor.extractRaw(bytes: [0x01], bitIndex: 0, bitLength: 16, signed: false))
    }
}
