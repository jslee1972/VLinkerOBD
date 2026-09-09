import XCTest
@testable import VLinkerOBD

final class ObdResponseParserTests: XCTestCase {
    func testParsesSpeedFromCleanResponse() {
        let value = ObdResponseParser.parsePid("41 0D 64\r>", request: "010D", formula: "A")
        XCTAssertEqual(value, 100.0)
    }

    func testTolerantOfEchoedCommandPrefix() {
        let value = ObdResponseParser.parsePid("010D\r41 0D 64\r>", request: "010D", formula: "A")
        XCTAssertEqual(value, 100.0)
    }

    func testTolerantOfCompactUnspacedResponse() {
        let value = ObdResponseParser.parsePid("410D64\r>", request: "010D", formula: "A")
        XCTAssertEqual(value, 100.0)
    }

    func testClassifiesNoData() {
        XCTAssertEqual(ObdResponseParser.classify("NO DATA\r>"), .noData)
    }

    func testClassifiesNegativeResponse() {
        guard case let .negativeResponse(service, nrc, messageZh) = ObdResponseParser.classify("7F 22 31\r>") else {
            return XCTFail("expected negative response")
        }
        XCTAssertEqual(service, "22")
        XCTAssertEqual(nrc, "31")
        XCTAssertEqual(messageZh, "請求超出範圍")
    }

    func testUnrecognizedForOddLengthGarbage() {
        XCTAssertEqual(ObdResponseParser.classify("ABC\r>"), .unrecognized)
    }

    func testParsesVinFilteringPrintableAscii() {
        // 49 02 01 <17 ASCII VIN bytes> — data-item-count byte (01) is stripped by the printable
        // ASCII filter along with any padding, without needing to special-case its position.
        let vin = "VF7ABCDEFGH123456"
        let asciiHex = vin.unicodeScalars.map { String(format: "%02X", $0.value) }.joined(separator: " ")
        let raw = "49 02 01 \(asciiHex)\r>"
        XCTAssertEqual(ObdResponseParser.parseVin(raw), vin)
    }

    func testParsesVinFromLengthPrefixedIndexedMultiFrameResponse() {
        // Real adapters sometimes show a standalone hex length line, then "<n>:"-prefixed frames.
        let vin = "VF7ABCDEFGH123456"
        var bytes: [UInt8] = [0x49, 0x02, 0x01] + Array(vin.utf8)
        var frames: [String] = []
        var frameIndex = 0
        while !bytes.isEmpty {
            let chunk = Array(bytes.prefix(7))
            bytes.removeFirst(chunk.count)
            let hex = chunk.map { String(format: "%02X", $0) }.joined(separator: " ")
            frames.append("\(frameIndex):\(hex)")
            frameIndex += 1
        }
        let totalLength = String(format: "%03X", 3 + vin.utf8.count)
        let raw = ([totalLength] + frames).joined(separator: "\r") + "\r>"
        XCTAssertEqual(ObdResponseParser.parseVin(raw), vin)
    }

    func testPayloadBytesReturnsNilWithoutMarker() {
        XCTAssertNil(ObdResponseParser.payloadBytes("41 0C 1A F8\r>", request: "010D"))
    }
}

final class DtcParserTests: XCTestCase {
    func testDecodesCategoriesFromTopBits() {
        XCTAssertEqual(DtcParser.parse("43 00 00\r>", requestMode: "03"), [])
        XCTAssertEqual(DtcParser.parse("43 01 33\r>", requestMode: "03"), ["P0133"])
        XCTAssertEqual(DtcParser.parse("43 41 33\r>", requestMode: "03"), ["C0133"])
        XCTAssertEqual(DtcParser.parse("43 81 33\r>", requestMode: "03"), ["B0133"])
        XCTAssertEqual(DtcParser.parse("43 C1 33\r>", requestMode: "03"), ["U0133"])
    }

    func testSkipsPaddingPairs() {
        XCTAssertEqual(DtcParser.parse("43 01 33 00 00\r>", requestMode: "03"), ["P0133"])
    }

    func testReturnsNilForNoData() {
        XCTAssertNil(DtcParser.parse("NO DATA\r>", requestMode: "03"))
    }

    func testReturnsNilForUnknownMode() {
        XCTAssertNil(DtcParser.parse("43 01 33\r>", requestMode: "99"))
    }
}
