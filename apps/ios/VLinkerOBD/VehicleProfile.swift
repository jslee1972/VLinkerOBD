import Foundation

/// Bit-packed signal spec (schema v3's `bitField`) — the representation manufacturer telemetry
/// like battery cell voltages or tire pressure PIDs needs, versus the plain byte-arithmetic
/// ``PidFormula`` used by simple standard PIDs.
///
/// Bit 0 is the most-significant bit of the first payload byte (the byte right after the
/// mode+PID echo), and bit numbering proceeds MSB-first across successive bytes — the
/// "big-endian"/Motorola bit order used throughout the OBDb community signalsets
/// (https://github.com/OBDb) this schema is modeled on. `signed` applies two's-complement sign
/// extension. Final value = raw * multiplier / divisor + offset, clamped to min/max when given.
struct BitFieldSpec: Decodable, Equatable {
    var bitIndex: Int
    var bitLength: Int
    var multiplier: Double = 1.0
    var divisor: Double = 1.0
    var offset: Double = 0.0
    var signed: Bool = false
    var min: Double?
    var max: Double?

    // Swift's synthesized Decodable does NOT fall back to a property's default value when a key
    // is missing from the JSON (unlike Kotlin's data class defaults) — every optional-with-a-
    // default field here needs an explicit `decodeIfPresent ?? default`, or a profile that
    // omits e.g. `multiplier`/`fastPoll` (the common case — most PIDs don't set them) fails to
    // decode entirely instead of falling back.
    enum CodingKeys: String, CodingKey { case bitIndex, bitLength, multiplier, divisor, offset, signed, min, max }

    init(bitIndex: Int, bitLength: Int, multiplier: Double = 1.0, divisor: Double = 1.0, offset: Double = 0.0, signed: Bool = false, min: Double? = nil, max: Double? = nil) {
        self.bitIndex = bitIndex
        self.bitLength = bitLength
        self.multiplier = multiplier
        self.divisor = divisor
        self.offset = offset
        self.signed = signed
        self.min = min
        self.max = max
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        bitIndex = try container.decode(Int.self, forKey: .bitIndex)
        bitLength = try container.decode(Int.self, forKey: .bitLength)
        multiplier = try container.decodeIfPresent(Double.self, forKey: .multiplier) ?? 1.0
        divisor = try container.decodeIfPresent(Double.self, forKey: .divisor) ?? 1.0
        offset = try container.decodeIfPresent(Double.self, forKey: .offset) ?? 0.0
        signed = try container.decodeIfPresent(Bool.self, forKey: .signed) ?? false
        min = try container.decodeIfPresent(Double.self, forKey: .min)
        max = try container.decodeIfPresent(Double.self, forKey: .max)
    }
}

/// One PID definition from a vehicle profile JSON (shared/vehicle-profiles, schema v5).
///
/// `formula` and `bitField` are mutually exclusive alternatives — a PID is decoded with one or
/// the other, never both. `displayNameZh`/`descriptionZh`/`group` (v5) are the per-PID Chinese
/// display name / long description / UI group, replacing what used to be separate hand-maintained
/// lookup tables on each platform (see ``ParameterMetadata``).
struct PidDefinition: Decodable, Equatable {
    var request: String
    var field: String
    var unit: String
    var formula: String?
    var bitField: BitFieldSpec?
    var ecuHeader: String?
    var ecuReceiveFilter: String?
    var verified: String?
    /// True for brand PIDs that change quickly enough (e.g. gear position) to need their own
    /// short-interval ticker instead of sharing the slow round-robin with the rest of the
    /// profile's PIDs — see `DashboardController.restartBrandPolling`.
    var fastPoll: Bool = false
    var displayNameZh: String?
    var descriptionZh: String?
    var group: String?

    enum CodingKeys: String, CodingKey {
        case request, field, unit, formula, bitField, ecuHeader, ecuReceiveFilter, verified, fastPoll, displayNameZh, descriptionZh, group
    }

    init(request: String, field: String, unit: String, formula: String? = nil, bitField: BitFieldSpec? = nil, ecuHeader: String? = nil, ecuReceiveFilter: String? = nil, verified: String? = nil, fastPoll: Bool = false, displayNameZh: String? = nil, descriptionZh: String? = nil, group: String? = nil) {
        self.request = request
        self.field = field
        self.unit = unit
        self.formula = formula
        self.bitField = bitField
        self.ecuHeader = ecuHeader
        self.ecuReceiveFilter = ecuReceiveFilter
        self.verified = verified
        self.fastPoll = fastPoll
        self.displayNameZh = displayNameZh
        self.descriptionZh = descriptionZh
        self.group = group
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        request = try container.decode(String.self, forKey: .request)
        field = try container.decode(String.self, forKey: .field)
        unit = try container.decode(String.self, forKey: .unit)
        formula = try container.decodeIfPresent(String.self, forKey: .formula)
        bitField = try container.decodeIfPresent(BitFieldSpec.self, forKey: .bitField)
        ecuHeader = try container.decodeIfPresent(String.self, forKey: .ecuHeader)
        ecuReceiveFilter = try container.decodeIfPresent(String.self, forKey: .ecuReceiveFilter)
        verified = try container.decodeIfPresent(String.self, forKey: .verified)
        fastPoll = try container.decodeIfPresent(Bool.self, forKey: .fastPoll) ?? false
        displayNameZh = try container.decodeIfPresent(String.self, forKey: .displayNameZh)
        descriptionZh = try container.decodeIfPresent(String.self, forKey: .descriptionZh)
        group = try container.decodeIfPresent(String.self, forKey: .group)
    }
}

struct PidModel: Decodable, Equatable {
    var modelId: String
    var displayNameZh: String
    var ecuHeader: String?
    var pids: [PidDefinition] = []

    enum CodingKeys: String, CodingKey { case modelId, displayNameZh, ecuHeader, pids }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        modelId = try container.decode(String.self, forKey: .modelId)
        displayNameZh = try container.decode(String.self, forKey: .displayNameZh)
        ecuHeader = try container.decodeIfPresent(String.self, forKey: .ecuHeader)
        pids = try container.decodeIfPresent([PidDefinition].self, forKey: .pids) ?? []
    }
}

struct VehicleProfile: Decodable, Equatable {
    var profileId: String
    var brand: String?
    var displayNameZh: String?
    var pids: [PidDefinition] = []
    var models: [PidModel] = []

    enum CodingKeys: String, CodingKey { case profileId, brand, displayNameZh, pids, models }

    init(profileId: String, brand: String? = nil, displayNameZh: String? = nil, pids: [PidDefinition] = [], models: [PidModel] = []) {
        self.profileId = profileId
        self.brand = brand
        self.displayNameZh = displayNameZh
        self.pids = pids
        self.models = models
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        profileId = try container.decode(String.self, forKey: .profileId)
        brand = try container.decodeIfPresent(String.self, forKey: .brand)
        displayNameZh = try container.decodeIfPresent(String.self, forKey: .displayNameZh)
        pids = try container.decodeIfPresent([PidDefinition].self, forKey: .pids) ?? []
        models = try container.decodeIfPresent([PidModel].self, forKey: .models) ?? []
    }
}

/// The two fields that get dedicated gauge treatment — every other PID reading (universal-extra
/// and brand-specific alike) flows through the loosely-typed readings dictionaries in
/// ``DashboardState`` instead of being added here (matches Android's `model/VehicleData.kt`).
struct VehicleData: Equatable {
    var speedKph: Int?
    var rpm: Int?
}

/// Loads vehicle profile JSON files bundled from shared/vehicle-profiles into the app's
/// Resources/vehicle-profiles folder. The universal profile is always active; a brand profile is
/// optionally layered on top by the user (see shared/vehicle-profiles/README.md).
enum PidGroupRepository {
    static func loadUniversal() throws -> VehicleProfile {
        try loadProfile(resourceName: "universal-obd2", subdirectory: "vehicle-profiles")
    }

    static func loadBrand(_ fileNameWithoutExtension: String) throws -> VehicleProfile {
        try loadProfile(resourceName: fileNameWithoutExtension, subdirectory: "vehicle-profiles")
    }

    static func loadProfile(resourceName: String, subdirectory: String) throws -> VehicleProfile {
        guard let url = Bundle.main.url(forResource: resourceName, withExtension: "json", subdirectory: subdirectory) else {
            throw ProfileLoadError.resourceNotFound("\(subdirectory)/\(resourceName).json")
        }
        let data = try Data(contentsOf: url)
        return try parseProfile(data: data)
    }

    static func parseProfile(data: Data) throws -> VehicleProfile {
        try JSONDecoder().decode(VehicleProfile.self, from: data)
    }

    enum ProfileLoadError: Error {
        case resourceNotFound(String)
    }
}
