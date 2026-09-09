import Foundation

/// Shared by both dashboard layouts: greedily combines adjacent small groups (by field count,
/// following `ParameterGroups.displayOrder`) into one shared page, so a group with only one or two
/// live fields doesn't get an entire screen to itself, while a large group still gets its own.
/// "Adjacent" is defined purely by that display order — groups are never reordered to make a
/// better fit, only chunked along the sequence they'd otherwise render in.
enum GroupPaging {
    static func mergedPages(order: [String], fieldCounts: [String: Int], maxFieldsPerPage: Int = 6) -> [[String]] {
        var pages: [[String]] = []
        var currentPage: [String] = []
        var currentCount = 0
        for group in order {
            guard let count = fieldCounts[group], count > 0 else { continue }
            if !currentPage.isEmpty && currentCount + count > maxFieldsPerPage {
                pages.append(currentPage)
                currentPage = []
                currentCount = 0
            }
            currentPage.append(group)
            currentCount += count
        }
        if !currentPage.isEmpty { pages.append(currentPage) }
        return pages
    }
}
