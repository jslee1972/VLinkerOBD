import XCTest
@testable import VLinkerOBD

final class GroupPagingTests: XCTestCase {
    func testMergesSmallAdjacentGroups() {
        let pages = GroupPaging.mergedPages(
            order: ["A", "B", "C"],
            fieldCounts: ["A": 1, "B": 2, "C": 1],
            maxFieldsPerPage: 6
        )
        XCTAssertEqual(pages, [["A", "B", "C"]])
    }

    func testStartsNewPageWhenOverCapacity() {
        let pages = GroupPaging.mergedPages(
            order: ["A", "B", "C"],
            fieldCounts: ["A": 4, "B": 4, "C": 1],
            maxFieldsPerPage: 6
        )
        XCTAssertEqual(pages, [["A"], ["B", "C"]])
    }

    func testLargeGroupAlwaysGetsItsOwnPageEvenAlone() {
        let pages = GroupPaging.mergedPages(
            order: ["A"],
            fieldCounts: ["A": 12],
            maxFieldsPerPage: 6
        )
        XCTAssertEqual(pages, [["A"]])
    }

    func testSkipsGroupsWithNoFields() {
        let pages = GroupPaging.mergedPages(
            order: ["A", "B", "C"],
            fieldCounts: ["A": 2, "C": 2],
            maxFieldsPerPage: 6
        )
        XCTAssertEqual(pages, [["A", "C"]])
    }

    func testPreservesDisplayOrderNotSizeOrder() {
        let pages = GroupPaging.mergedPages(
            order: ["big", "small"],
            fieldCounts: ["big": 5, "small": 1],
            maxFieldsPerPage: 6
        )
        XCTAssertEqual(pages, [["big", "small"]])
    }
}
