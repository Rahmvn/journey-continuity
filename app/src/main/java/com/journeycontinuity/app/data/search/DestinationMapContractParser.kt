package com.journeycontinuity.app.data.search

import com.journeycontinuity.app.ui.destination.*
import java.net.URI
import kotlinx.serialization.json.*

/** Strict transport consistency checks. Android never performs polygon reasoning. */
internal object DestinationMapContractParser {
    private fun invalid(): Nothing = throw DestinationMapException(DestinationMapFailure.INVALID_RESPONSE)
    private fun objectBody(body: String): JsonObject {
        if (body.toByteArray().size > 65_536) invalid()
        return Json.parseToJsonElement(body) as? JsonObject ?: invalid()
    }
    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: invalid()
    private fun JsonObject.optionalText(key: String): String? = when (val value = get(key)) {
        JsonNull -> null
        is JsonPrimitive -> value.takeIf { it.isString }?.content ?: invalid()
        else -> invalid()
    }
    private fun String.canonical(maximum: Int): String {
        if (isBlank() || this != trim() || codePointCount(0, length) > maximum ||
            any { Character.isISOControl(it) }) invalid()
        return this
    }
    private fun JsonObject.number(key: String): Double {
        val primitive = get(key) as? JsonPrimitive ?: invalid()
        if (primitive.isString) invalid()
        return primitive.doubleOrNull?.takeIf { it.isFinite() } ?: invalid()
    }
    private fun JsonObject.boundary(outcomeKey: String, versionKey: String, datasetKey: String?): CoordinateVerification {
        val outcome = enumValueOf<BoundaryOutcome>(text(outcomeKey))
        val version = optionalText(versionKey)?.canonical(120)
        val dataset = if (datasetKey != null) optionalText(datasetKey)
            else if (version != null) OPERATIONAL_BOUNDARY_DATASET else null
        if (outcome in setOf(BoundaryOutcome.INVALID_CONTEXT, BoundaryOutcome.UNAVAILABLE)) {
            if (version != null || dataset != null) invalid()
        } else if (dataset != OPERATIONAL_BOUNDARY_DATASET || version == null) invalid()
        return CoordinateVerification(outcome, dataset, version)
    }
    fun verification(body: String): CoordinateVerification = guarded {
        objectBody(body).boundary("outcome", "boundary_dataset_version", "boundary_dataset_id")
    }
    fun viewport(body: String): LocalUnitViewport = guarded {
        val obj = objectBody(body)
        when (obj.text("status")) {
            "INVALID_CONTEXT" -> LocalUnitViewport.InvalidContext
            "UNAVAILABLE" -> LocalUnitViewport.Unavailable
            "AVAILABLE" -> LocalUnitViewport.Available(obj.number("west"), obj.number("south"),
                obj.number("east"), obj.number("north"), obj.text("boundary_dataset_id"),
                obj.text("boundary_dataset_version").canonical(120))
            else -> invalid()
        }
    }
    fun label(body: String): MapLabelResponse = guarded {
        val obj = objectBody(body)
        val status = obj.text("status")
        if (status == "VERIFICATION_FAILED") {
            val verification = obj.boundary("boundaryOutcome", "boundaryDatasetVersion", null)
            if (verification.outcome == BoundaryOutcome.INSIDE) invalid()
            return@guarded MapLabelResponse.VerificationFailed(verification)
        }
        val quality = enumValueOf<MapLabelStatus>(status)
        if (obj.text("boundaryOutcome") != "INSIDE") invalid()
        val version = obj.text("boundaryDatasetVersion").canonical(120)
        val unitName = obj.text("canonicalLocalUnitName").canonical(160)
        val type = enumValueOf<MapLabelResultType>(obj.text("resultType"))
        val label = obj.optionalText("label")?.canonical(160)
        val secondary = obj.optionalText("secondaryLabel")?.canonical(300)
        val provider = obj.optionalText("provider")
        val placeId = obj.optionalText("providerPlaceId")?.canonical(512)
        val distance = when (val value = obj["distanceMeters"]) {
            JsonNull -> null
            is JsonPrimitive -> if (value.isString) invalid() else value.doubleOrNull
                ?.takeIf { it.isFinite() && it in 0.0..20_100_000.0 } ?: invalid()
            else -> invalid()
        }
        val attribution = when (val value = obj["attribution"]) {
            JsonNull -> null
            is JsonObject -> {
                val url = value.text("sourceUrl").canonical(300)
                val uri = URI(url)
                if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) invalid()
                ProviderAttribution(value.text("sourceName").canonical(80),
                    value.text("attribution").canonical(160), value.text("license").canonical(80), url)
            }
            else -> invalid()
        }
        val evidence = if (quality == MapLabelStatus.SPECIFIC_LABEL) {
            if (label == null || provider != "GEOAPIFY" || attribution == null ||
                type !in setOf(MapLabelResultType.PLACE, MapLabelResultType.ROAD)) invalid()
            MapLabelEvidence(label, secondary, type, provider, placeId, distance, attribution)
        } else {
            if (listOf(label, secondary, provider, placeId, distance, attribution).any { it != null }) invalid()
            null
        }
        MapLabelResponse.Verified(quality, version, unitName, evidence)
    }
    private inline fun <T> guarded(block: () -> T): T = try { block() }
        catch (known: DestinationMapException) { throw known }
        catch (_: Exception) { invalid() }
}
