import XCTest
@testable import VLinkerOBD

final class PidGroupRepositoryTests: XCTestCase {
    func testLoadsUniversalProfileWithSpeedAndRpm() throws {
        let profile = try PidGroupRepository.loadUniversal()
        XCTAssertEqual(profile.profileId, "universal-obd2")
        XCTAssertNil(profile.brand)

        let speed = try XCTUnwrap(profile.pids.first { $0.field == "speedKPH" })
        XCTAssertEqual(speed.request, "010D")
        XCTAssertEqual(speed.formula, "A")

        let rpm = try XCTUnwrap(profile.pids.first { $0.field == "rpm" })
        XCTAssertEqual(rpm.request, "010C")
    }

    func testUniversalPidsCarryV5Metadata() throws {
        let profile = try PidGroupRepository.loadUniversal()
        let coolant = try XCTUnwrap(profile.pids.first { $0.field == "coolantTempC" })
        XCTAssertEqual(coolant.displayNameZh, "水溫")
        XCTAssertEqual(coolant.group, "溫度")
        XCTAssertNotNil(coolant.descriptionZh)
    }

    func testLoadsMazdaBrandProfileWithPerModelHeaders() throws {
        let profile = try PidGroupRepository.loadBrand("mazda")
        XCTAssertEqual(profile.brand, "Mazda")
        XCTAssertTrue(profile.pids.isEmpty)
        XCTAssertEqual(profile.models.count, 2)

        let miata = try XCTUnwrap(profile.models.first { $0.modelId == "miata-nc" })
        XCTAssertEqual(miata.ecuHeader, "720")
        let tire1 = try XCTUnwrap(miata.pids.first { $0.field == "tire1PressurePSI" })
        XCTAssertEqual(tire1.request, "22C901")
        XCTAssertEqual(tire1.verified, "forum-partial")
        XCTAssertEqual(tire1.group, "廠牌專屬")
    }

    func testLoadsFordBrandProfileWithBitFieldPids() throws {
        let profile = try PidGroupRepository.loadBrand("ford")
        XCTAssertEqual(profile.brand, "Ford")
        XCTAssertTrue(profile.models.isEmpty)

        let odometer = try XCTUnwrap(profile.pids.first { $0.field == "odometerKM" })
        XCTAssertEqual(odometer.request, "22404C")
        XCTAssertEqual(odometer.ecuHeader, "720")
        XCTAssertEqual(odometer.ecuReceiveFilter, "728")
        XCTAssertNil(odometer.formula)
        XCTAssertEqual(odometer.bitField?.bitLength, 24)
        XCTAssertEqual(odometer.bitField?.divisor, 10.0)
    }

    func testLoadsHondaBrandProfileWithSignedBitFieldPid() throws {
        let profile = try PidGroupRepository.loadBrand("honda")
        XCTAssertEqual(profile.brand, "Honda")
        let current = try XCTUnwrap(profile.pids.first { $0.field == "batteryCurrent" })
        XCTAssertEqual(current.ecuHeader, "DA01")
        XCTAssertEqual(current.ecuReceiveFilter, "01")
        XCTAssertEqual(current.bitField?.signed, true)
        XCTAssertEqual(current.bitField?.divisor, 50.0)
        XCTAssertEqual(current.bitField?.min, -100.0)
    }

    func testCitroenAndCitroenEvMergeIntoOnePsaProfile() throws {
        let ice = try PidGroupRepository.loadBrand("citroen")
        let ev = try PidGroupRepository.loadBrand("citroen-ev")
        var merged = ice
        merged.pids += ev.pids

        XCTAssertTrue(merged.pids.contains { $0.field == "turboPressureBar" })
        XCTAssertTrue(merged.pids.contains { $0.field == "evBatteryVoltageV" })
    }

    func testParsesMinimalInlineProfile() throws {
        let json = """
        {"schemaVersion": 5, "profileId": "test-profile", "pids": [
          {"request": "0105", "field": "coolantTempC", "unit": "度", "formula": "A-40"}
        ]}
        """
        let profile = try PidGroupRepository.parseProfile(data: Data(json.utf8))
        XCTAssertEqual(profile.profileId, "test-profile")
        XCTAssertEqual(profile.pids.count, 1)
        XCTAssertEqual(profile.pids.first?.formula, "A-40")
    }
}

final class DtcDescriptionsTests: XCTestCase {
    func testCuratedChineseTranslationTakesPriority() {
        let descriptions = DtcDescriptions.load()
        XCTAssertEqual(descriptions.describe("P0133"), "氧感應器反應遲緩（Bank 1 Sensor 1）")
    }

    func testFallbackMessageForUnknownCode() {
        let descriptions = DtcDescriptions.withoutEnglishData()
        XCTAssertEqual(descriptions.describe("P9999"), "此故障碼尚無內建說明，建議查詢車廠維修手冊或委由專業技師診斷。")
    }

    func testCategoryNameFromLeadingLetter() {
        XCTAssertEqual(DtcDescriptions.categoryName("P0100"), "動力系統")
        XCTAssertEqual(DtcDescriptions.categoryName("C0035"), "底盤")
        XCTAssertEqual(DtcDescriptions.categoryName("B0001"), "車身")
        XCTAssertEqual(DtcDescriptions.categoryName("U0100"), "網路通訊")
    }

    func testBrandSpecificEnglishFallback() {
        let descriptions = DtcDescriptions.load()
        // Any BMW P1xxx code not in the curated Chinese set should come back tagged as untranslated English.
        let text = descriptions.describe("P1123", brand: "BMW")
        XCTAssertTrue(text.hasSuffix("（英文原文，尚無中文翻譯）") || text == "此故障碼尚無內建說明，建議查詢車廠維修手冊或委由專業技師診斷。")
    }
}

final class ParameterMetadataTests: XCTestCase {
    func testDisplayNameFallsBackToFieldKey() {
        let metadata = ParameterMetadata(profiles: [])
        XCTAssertEqual(metadata.displayName("unknownField"), "unknownField")
    }

    func testTripComputerFieldsHaveHardcodedMetadata() {
        let metadata = ParameterMetadata(profiles: [])
        XCTAssertEqual(metadata.displayName("instantFuelConsumption"), "瞬時油耗")
        XCTAssertEqual(metadata.groupFor("tripDistance"), ParameterGroups.tripComputer)
    }

    func testReadsMetadataFromLoadedProfile() throws {
        let universal = try PidGroupRepository.loadUniversal()
        let metadata = ParameterMetadata(profiles: [universal])
        XCTAssertEqual(metadata.displayName("coolantTempC"), "水溫")
        XCTAssertEqual(metadata.groupFor("coolantTempC"), "溫度")
    }

    func testUnknownBrandFieldFallsBackToBrandSpecificGroup() {
        let metadata = ParameterMetadata(profiles: [])
        XCTAssertEqual(metadata.groupFor("someBrandOnlyField"), ParameterGroups.brandSpecific)
    }
}
