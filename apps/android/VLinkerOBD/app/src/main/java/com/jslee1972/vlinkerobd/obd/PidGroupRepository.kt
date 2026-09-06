package com.jslee1972.vlinkerobd.obd

data class PidDefinition(
    val request: String,
    val field: String,
    val unit: String,
    val formula: String? = null,
    val bitField: BitFieldSpec? = null,
    val ecuHeader: String? = null,
    val ecuReceiveFilter: String? = null,
    val verified: String? = null,
)

data class PidModel(
    val modelId: String,
    val displayNameZh: String,
    val ecuHeader: String?,
    val pids: List<PidDefinition>,
)

data class VehicleProfile(
    val profileId: String,
    val brand: String? = null,
    val displayNameZh: String? = null,
    val pids: List<PidDefinition> = emptyList(),
    val models: List<PidModel> = emptyList(),
)

/**
 * Loads vehicle profile JSON files synced from shared/vehicle-profiles into
 * app/src/main/assets/vehicle-profiles. The universal profile is always active; a brand profile
 * is optionally layered on top by the user (see shared/vehicle-profiles/README.md). [readAsset]
 * is injected so this stays a pure, unit-testable parser — production code supplies
 * `context.assets.open(name).bufferedReader().readText()`.
 */
class PidGroupRepository(private val readAsset: (String) -> String) {

    fun loadUniversal(): VehicleProfile = loadProfile("vehicle-profiles/universal-obd2.json")

    fun loadBrand(assetFileName: String): VehicleProfile = loadProfile("vehicle-profiles/$assetFileName")

    fun loadProfile(assetPath: String): VehicleProfile = parseProfile(readAsset(assetPath))

    companion object {
        fun parseProfile(json: String): VehicleProfile {
            val root = MiniJson.parse(json).asObject()
            val profileId = (root["profileId"] as JsonValue.JsonString).value
            val brand = (root["brand"] as? JsonValue.JsonString)?.value
            val displayNameZh = (root["displayNameZh"] as? JsonValue.JsonString)?.value
            val pids = (root["pids"] as? JsonValue.JsonArray)?.items.orEmpty().map { parsePid(it) }
            val models = (root["models"] as? JsonValue.JsonArray)?.items.orEmpty().map { parseModel(it) }
            return VehicleProfile(profileId, brand, displayNameZh, pids, models)
        }

        private fun parseModel(value: JsonValue): PidModel {
            val obj = value.asObject()
            return PidModel(
                modelId = (obj["modelId"] as JsonValue.JsonString).value,
                displayNameZh = (obj["displayNameZh"] as JsonValue.JsonString).value,
                ecuHeader = (obj["ecuHeader"] as? JsonValue.JsonString)?.value,
                pids = (obj["pids"] as? JsonValue.JsonArray)?.items.orEmpty().map { parsePid(it) },
            )
        }

        private fun parsePid(value: JsonValue): PidDefinition {
            val obj = value.asObject()
            return PidDefinition(
                request = (obj["request"] as JsonValue.JsonString).value,
                field = (obj["field"] as JsonValue.JsonString).value,
                unit = (obj["unit"] as JsonValue.JsonString).value,
                formula = (obj["formula"] as? JsonValue.JsonString)?.value,
                bitField = (obj["bitField"] as? JsonValue.JsonObject)?.let { parseBitField(it) },
                ecuHeader = (obj["ecuHeader"] as? JsonValue.JsonString)?.value,
                ecuReceiveFilter = (obj["ecuReceiveFilter"] as? JsonValue.JsonString)?.value,
                verified = (obj["verified"] as? JsonValue.JsonString)?.value,
            )
        }

        private fun parseBitField(obj: JsonValue.JsonObject): BitFieldSpec {
            val fields = obj.fields
            return BitFieldSpec(
                bitIndex = (fields.getValue("bitIndex") as JsonValue.JsonNumber).value.toInt(),
                bitLength = (fields.getValue("bitLength") as JsonValue.JsonNumber).value.toInt(),
                multiplier = (fields["multiplier"] as? JsonValue.JsonNumber)?.value ?: 1.0,
                divisor = (fields["divisor"] as? JsonValue.JsonNumber)?.value ?: 1.0,
                offset = (fields["offset"] as? JsonValue.JsonNumber)?.value ?: 0.0,
                signed = (fields["signed"] as? JsonValue.JsonBoolean)?.value ?: false,
                min = (fields["min"] as? JsonValue.JsonNumber)?.value,
                max = (fields["max"] as? JsonValue.JsonNumber)?.value,
            )
        }
    }
}
