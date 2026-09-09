import XCTest
@testable import VLinkerOBD

final class GaugeMathTests: XCTestCase {
    func testMinValueMapsToStartAngle() {
        XCTAssertEqual(GaugeMath.valueToAngleDegrees(value: 0, minValue: 0, maxValue: 220, startAngleDegrees: 150, sweepAngleDegrees: 240), 150)
    }

    func testMaxValueMapsToEndAngle() {
        XCTAssertEqual(GaugeMath.valueToAngleDegrees(value: 220, minValue: 0, maxValue: 220, startAngleDegrees: 150, sweepAngleDegrees: 240), 390)
    }

    func testMidpointMapsToMidAngle() {
        XCTAssertEqual(GaugeMath.valueToAngleDegrees(value: 110, minValue: 0, maxValue: 220, startAngleDegrees: 150, sweepAngleDegrees: 240), 270)
    }

    func testClampsOutOfRangeValues() {
        XCTAssertEqual(GaugeMath.valueToAngleDegrees(value: -50, minValue: 0, maxValue: 220, startAngleDegrees: 150, sweepAngleDegrees: 240), 150)
        XCTAssertEqual(GaugeMath.valueToAngleDegrees(value: 999, minValue: 0, maxValue: 220, startAngleDegrees: 150, sweepAngleDegrees: 240), 390)
    }

    func testZeroRangeDoesNotDivideByZero() {
        XCTAssertEqual(GaugeMath.valueToAngleDegrees(value: 5, minValue: 5, maxValue: 5, startAngleDegrees: 150, sweepAngleDegrees: 240), 150)
    }
}

final class TrendChartMathTests: XCTestCase {
    func testBoundsMapToZeroAndOne() {
        XCTAssertEqual(TrendChartMath.yFraction(value: 0, min: 0, max: 100), 0)
        XCTAssertEqual(TrendChartMath.yFraction(value: 100, min: 0, max: 100), 1)
    }

    func testFlatRangeReturnsHalf() {
        XCTAssertEqual(TrendChartMath.yFraction(value: 5, min: 5, max: 5), 0.5)
    }

    func testClampsOutOfRange() {
        XCTAssertEqual(TrendChartMath.yFraction(value: -10, min: 0, max: 100), 0)
        XCTAssertEqual(TrendChartMath.yFraction(value: 200, min: 0, max: 100), 1)
    }
}

final class VehicleBrandDetectorTests: XCTestCase {
    func testDetectsBrandFromFullWmi() {
        XCTAssertEqual(VehicleBrandDetector.FALLBACK.detectBrand(vin: "JM1NC2540P0123456"), "Mazda")
    }

    func testDetectsCitroenFromSpainPlantWmi() {
        XCTAssertEqual(VehicleBrandDetector.FALLBACK.detectBrand(vin: "VR7ECYHZRNJ613202"), "Citroen")
    }

    func testUnrecognizedWmiReturnsNil() {
        XCTAssertNil(VehicleBrandDetector.FALLBACK.detectBrand(vin: "ZZZ00000000000000"))
    }

    func testTooShortVinReturnsNil() {
        XCTAssertNil(VehicleBrandDetector.FALLBACK.detectBrand(vin: "AB"))
    }

    func testLoadsComprehensiveDatabaseFromBundle() {
        let detector = VehicleBrandDetector.loadFromBundle()
        XCTAssertEqual(detector.detectBrand(vin: "VR7ECYHZRNJ613202"), "Citroen")
    }
}
