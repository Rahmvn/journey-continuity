package com.journeycontinuity.app.domain

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Collections
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class AdminRegionType { STATE, FCT }
enum class AdminLocalUnitType { LGA, AREA_COUNCIL }

@Serializable
data class AdminCatalogueSource(
    val id: String,
    val authority: String,
    val title: String,
    val url: String,
    val retrievedAt: String,
)

@Serializable
data class AdminRegion(
    val id: String,
    val canonicalName: String,
    val type: AdminRegionType,
    val displayOrder: Int,
    val sourceId: String,
)

@Serializable
data class AdminLocalUnit(
    val id: String,
    val parentRegionId: String,
    val canonicalName: String,
    val type: AdminLocalUnitType,
    val displayOrder: Int,
    val sourceId: String? = null,
    val aliases: List<String> = emptyList(),
)

/** Each committed asset preserves an immutable Alabarin catalogue snapshot. */
@Serializable
data class AdminCatalogueDocument(
    val datasetId: String,
    val version: String,
    val snapshotDate: String,
    val hashRule: String,
    val regionIndexSourceId: String,
    val sources: List<AdminCatalogueSource>,
    val regions: List<AdminRegion>,
    val localUnits: List<AdminLocalUnit>,
)

data class AdminCatalogueMetadata(
    val datasetId: String,
    val version: String,
    val snapshotDate: String,
    /** SHA-256 of the exact committed UTF-8 asset bytes, including whitespace and trailing newline. */
    val sha256: String,
    val sources: List<AdminCatalogueSource>,
)

/** Pure validation shared by the bundled reader and catalogue tests. */
object AdminCatalogueValidator {
    const val HASH_RULE = "SHA-256 of exact committed UTF-8 JSON bytes"
    private val idPattern = Regex("ng-[a-z0-9]+(?:-[a-z0-9]+)*")
    private val sourceIdPattern = Regex("source-[0-9]{2}")
    private val whitespace = Regex("\\s+")
    private val fctNames = setOf(
        "Abaji", "Abuja Municipal Area Council", "Bwari", "Gwagwalada", "Kuje", "Kwali",
    )

    fun normalizedName(name: String): String = name.trim().lowercase(Locale.ROOT)
        .replace(whitespace, " ")

    fun validate(document: AdminCatalogueDocument) {
        require(document.datasetId == "NG_ADMIN") { "Unexpected admin dataset ID." }
        require(document.version.startsWith("alabarin-") && document.version.isNotBlank()) {
            "Catalogue version must identify an Alabarin snapshot."
        }
        require(document.hashRule == HASH_RULE) { "Unsupported catalogue hash rule." }
        LocalDate.parse(document.snapshotDate)
        require(document.sources.isNotEmpty()) { "Catalogue provenance is missing." }
        val sourceIds = document.sources.map { source ->
            require(sourceIdPattern.matches(source.id)) { "Invalid source ID: ${source.id}" }
            require(source.authority.isMeaningful() && source.title.isMeaningful()) {
                "Incomplete source metadata: ${source.id}"
            }
            val uri = URI.create(source.url)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
                "Source URL must be HTTPS: ${source.id}"
            }
            LocalDate.parse(source.retrievedAt)
            source.id
        }.toSet()
        require(sourceIds.size == document.sources.size) { "Duplicate catalogue source IDs." }
        require(document.regionIndexSourceId in sourceIds) { "Region index source is unknown." }
        val referencedSourceIds = mutableSetOf(document.regionIndexSourceId)

        require(document.regions.size == 37) { "Expected 37 administrative regions." }
        require(document.regions.count { it.type == AdminRegionType.STATE } == 36)
        require(document.regions.count { it.type == AdminRegionType.FCT } == 1)
        val regionIds = document.regions.map { region ->
            require(idPattern.matches(region.id) && region.canonicalName.isMeaningful()) {
                "Invalid region identity: ${region.id}"
            }
            require(region.sourceId in sourceIds) { "Unknown region source: ${region.id}" }
            referencedSourceIds.add(region.sourceId)
            require((region.id == "ng-fct") == (region.type == AdminRegionType.FCT)) {
                "Only ng-fct may be FCT."
            }
            region.id
        }
        require(regionIds.toSet().size == regionIds.size) { "Duplicate region IDs." }
        require(document.regions.map { it.displayOrder }.sorted() == (0..36).toList()) {
            "Region display order must be deterministic."
        }
        val regionById = document.regions.associateBy { it.id }
        require(regionById["ng-fct"]?.canonicalName == "Federal Capital Territory") {
            "FCT region is missing or incorrectly named."
        }

        require(document.localUnits.size == 774) { "Expected 774 local units." }
        require(document.localUnits.count { it.type == AdminLocalUnitType.LGA } == 768)
        require(document.localUnits.count { it.type == AdminLocalUnitType.AREA_COUNCIL } == 6)
        val unitIds = HashSet<String>()
        val siblingNames = HashSet<Pair<String, String>>()
        document.localUnits.forEach { unit ->
            val parent = requireNotNull(regionById[unit.parentRegionId]) {
                "Unknown local unit parent: ${unit.id}"
            }
            require(idPattern.matches(unit.id) && unit.id.startsWith("${parent.id}-") &&
                unit.id.length > parent.id.length + 1 && unit.canonicalName.isMeaningful()) {
                "Invalid local unit identity: ${unit.id}"
            }
            require(unitIds.add(unit.id)) { "Duplicate local unit ID: ${unit.id}" }
            require(unit.sourceId == null || unit.sourceId in sourceIds) {
                "Unknown local unit source: ${unit.id}"
            }
            unit.sourceId?.let(referencedSourceIds::add)
            require((parent.type == AdminRegionType.FCT && unit.type == AdminLocalUnitType.AREA_COUNCIL) ||
                (parent.type == AdminRegionType.STATE && unit.type == AdminLocalUnitType.LGA)) {
                "Local unit type does not match parent: ${unit.id}"
            }
            (listOf(unit.canonicalName) + unit.aliases).forEach { name ->
                require(name.isMeaningful()) { "Blank local unit name or alias: ${unit.id}" }
                require(siblingNames.add(parent.id to normalizedName(name))) {
                    "Duplicate sibling name or alias: ${parent.id} / $name"
                }
            }
        }
        document.sources.firstOrNull { it.id !in referencedSourceIds }?.let {
            throw IllegalArgumentException("Unused catalogue source: ${it.id}")
        }
        document.regions.forEach { region ->
            val orders = document.localUnits.filter { it.parentRegionId == region.id }
                .map { it.displayOrder }.sorted()
            require(orders == (0 until orders.size).toList()) {
                "Local unit display order is invalid for ${region.id}."
            }
        }
        val fct = document.localUnits.filter { it.parentRegionId == "ng-fct" }
        require(fct.size == 6 && fct.map { it.canonicalName }.toSet() == fctNames) {
            "FCT must have exactly the six Area Councils."
        }
        require(fct.single { it.canonicalName == "Abuja Municipal Area Council" }.let {
            it.id == "ng-fct-abuja-municipal" && "AMAC" in it.aliases
        }) { "AMAC must be an alias of Abuja Municipal Area Council." }
        require(document.localUnits.count { it.parentRegionId == "ng-kwara" } == 16) {
            "Kwara must have 16 LGAs."
        }
    }

    private fun String.isMeaningful(): Boolean = isNotBlank() && this == trim() && '\uFFFD' !in this
}

/** Validated, immutable in-process view of the versioned asset. */
class AdminCatalogue private constructor(
    val metadata: AdminCatalogueMetadata,
    val regions: List<AdminRegion>,
    val localUnits: List<AdminLocalUnit>,
) {
    private val regionById = regions.associateBy { it.id }
    private val unitById = localUnits.associateBy { it.id }
    private val unitsByParent = localUnits.groupBy { it.parentRegionId }
        .mapValues { (_, units) -> Collections.unmodifiableList(units) }

    fun region(id: String): AdminRegion? = regionById[id]
    fun localUnit(id: String): AdminLocalUnit? = unitById[id]
    fun localUnitsFor(parentRegionId: String): List<AdminLocalUnit> =
        unitsByParent[parentRegionId] ?: emptyList()

    fun localUnitByName(parentRegionId: String, nameOrAlias: String): AdminLocalUnit? {
        val query = AdminCatalogueValidator.normalizedName(nameOrAlias)
        return localUnitsFor(parentRegionId).firstOrNull { unit ->
            AdminCatalogueValidator.normalizedName(unit.canonicalName) == query ||
                unit.aliases.any { AdminCatalogueValidator.normalizedName(it) == query }
        }
    }

    companion object {
        fun parse(bytes: ByteArray): AdminCatalogue {
            val text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            val document = Json.decodeFromString<AdminCatalogueDocument>(text)
            AdminCatalogueValidator.validate(document)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val sources = Collections.unmodifiableList(document.sources.toList())
            val regions = Collections.unmodifiableList(document.regions.sortedBy { it.displayOrder })
            val units = Collections.unmodifiableList(document.localUnits
                .sortedWith(compareBy({ it.parentRegionId }, { it.displayOrder }))
                .map { it.copy(aliases = Collections.unmodifiableList(it.aliases.toList())) })
            return AdminCatalogue(
                AdminCatalogueMetadata(document.datasetId, document.version,
                    document.snapshotDate, hash, sources),
                regions,
                units,
            )
        }
    }
}
