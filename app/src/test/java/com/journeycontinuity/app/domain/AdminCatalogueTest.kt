package com.journeycontinuity.app.domain

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdminCatalogueTest {
    private val reviewedAssetSha256 = "e5a75f4d7f906ab3bd2d4db806bbbfa1b4c7ac2ff04f6cca3e52dd39e6b24aa4"
    private val assetFile: File by lazy {
        listOf(
            File("src/main/assets/catalogue/ng_admin_v1.json"),
            File("app/src/main/assets/catalogue/ng_admin_v1.json"),
        ).first { it.isFile }
    }
    private val bytes: ByteArray by lazy { assetFile.readBytes() }
    private val catalogue: AdminCatalogue by lazy { AdminCatalogue.parse(bytes) }

    @Test fun correctedVersionRetainsHistoricalSnapshotAndAllStableIds() {
        val currentFile = File(assetFile.parentFile, "ng_admin_v2.json")
        val current = AdminCatalogue.parse(currentFile.readBytes())
        assertEquals("alabarin-2026-10-05-v2", current.metadata.version)
        assertEquals("694a02cb25ad723429477da364f8c3bb482f3b5cb6595e1c0571d8c7e27ee911",
            current.metadata.sha256)
        assertEquals("Qua\u2019an Pan", catalogue.localUnit("ng-plateau-quaan-pan")?.canonicalName)
        assertEquals("Qua'an Pan", current.localUnit("ng-plateau-quaan-pan")?.canonicalName)
        assertEquals(catalogue.regions, current.regions)
        assertEquals(catalogue.localUnits.map { it.id }, current.localUnits.map { it.id })
        assertEquals(catalogue.localUnits.filter { it.id != "ng-plateau-quaan-pan" },
            current.localUnits.filter { it.id != "ng-plateau-quaan-pan" })
        assertEquals(774, current.localUnits.size)
        assertEquals("ng-plateau-quaan-pan",
            current.localUnitByName("ng-plateau", "Qua'an Pan")?.id)
    }

    @Test fun canonicalAssetHasTheCompleteVersionedNigeriaHierarchy() {
        assertEquals("NG_ADMIN", catalogue.metadata.datasetId)
        assertEquals("alabarin-2026-10-03-v1", catalogue.metadata.version)
        assertEquals("2026-10-03", catalogue.metadata.snapshotDate)
        assertEquals(37, catalogue.regions.size)
        assertEquals(36, catalogue.regions.count { it.type == AdminRegionType.STATE })
        assertEquals(1, catalogue.regions.count { it.type == AdminRegionType.FCT })
        assertEquals(774, catalogue.localUnits.size)
        assertEquals(768, catalogue.localUnits.count { it.type == AdminLocalUnitType.LGA })
        assertEquals(6, catalogue.localUnits.count { it.type == AdminLocalUnitType.AREA_COUNCIL })
        assertEquals(37, catalogue.regions.map { it.id }.toSet().size)
        assertEquals(774, catalogue.localUnits.map { it.id }.toSet().size)
        assertTrue(catalogue.metadata.sources.all { it.url.startsWith("https://") })
    }

    @Test fun everySourceIsUsedAndUnusedSourceFailsClosed() {
        val document = Json.decodeFromString<AdminCatalogueDocument>(bytes.decodeToString())
        val referenced = setOf(document.regionIndexSourceId) +
            document.regions.map { it.sourceId } + document.localUnits.mapNotNull { it.sourceId }
        assertEquals(46, document.sources.size)
        assertEquals(document.sources.map { it.id }.toSet(), referenced)
        assertFalse(document.sources.any { it.id == "source-18" })

        val unused = document.sources.first().copy(id = "source-18")
        val failure = runCatching {
            AdminCatalogueValidator.validate(document.copy(sources = document.sources + unused))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.contains("source-18") == true)
    }

    @Test fun lookupsAreOfflineTypedAndDeterministicallyOrdered() {
        assertEquals(AdminRegionType.FCT, catalogue.region("ng-fct")?.type)
        assertEquals("Offa", catalogue.localUnit("ng-kwara-offa")?.canonicalName)
        assertEquals(16, catalogue.localUnitsFor("ng-kwara").size)
        assertEquals((0..15).toList(), catalogue.localUnitsFor("ng-kwara").map { it.displayOrder })
        assertEquals(0, catalogue.regions.first().displayOrder)
        assertEquals(36, catalogue.regions.last().displayOrder)
        assertNull(catalogue.region("ng-unknown"))
        assertNull(catalogue.localUnit("ng-unknown-unit"))
        assertTrue(catalogue.localUnitsFor("ng-unknown").isEmpty())
        assertEquals("ng-fct-abuja-municipal", catalogue.localUnitByName("ng-fct", " amac ")?.id)
        assertEquals("Abuja Municipal Area Council",
            catalogue.localUnit("ng-fct-abuja-municipal")?.canonicalName)
        assertEquals("ng-gombe-yamaltu-deba", catalogue.localUnit("ng-gombe-yamaltu-deba")?.id)
        assertEquals("Shongom", catalogue.localUnit("ng-gombe-shongom")?.canonicalName)
        assertEquals(25, catalogue.localUnitsFor("ng-delta").size)
        assertNotNull(catalogue.localUnit("ng-delta-isoko-north"))
        assertNotNull(catalogue.localUnit("ng-delta-okpe"))
        assertEquals("Aiyekire", catalogue.localUnit("ng-ekiti-aiyekire")?.canonicalName)
        assertEquals("ng-ekiti", catalogue.region("ng-ekiti")?.id)
        assertNull(catalogue.localUnit("ng-ekiti-gbonyin"))
        assertEquals("Ifako-Ijaiye", catalogue.localUnit("ng-lagos-ifako-ijaiye")?.canonicalName)
    }

    @Test fun actualAssetHashIsStableAndUsesExactBytes() {
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(expected, catalogue.metadata.sha256)
        assertEquals(reviewedAssetSha256, expected)
        assertEquals(expected, AdminCatalogue.parse(bytes.copyOf()).metadata.sha256)
        assertFalse(expected == AdminCatalogue.parse(bytes + ' '.code.toByte()).metadata.sha256)
    }

    @Test fun malformedCatalogueFailsClosed() {
        val document = Json.decodeFromString<AdminCatalogueDocument>(bytes.decodeToString())
        val first = document.localUnits.first()
        rejects { AdminCatalogueValidator.validate(document.copy(
            localUnits = listOf(first.copy(parentRegionId = "ng-missing")) + document.localUnits.drop(1),
        )) }
        rejects { AdminCatalogueValidator.validate(document.copy(
            localUnits = listOf(first.copy(type = AdminLocalUnitType.AREA_COUNCIL)) +
                document.localUnits.drop(1),
        )) }
        rejects { AdminCatalogueValidator.validate(document.copy(
            localUnits = listOf(first.copy(aliases = listOf(first.canonicalName))) +
                document.localUnits.drop(1),
        )) }
        rejects { AdminCatalogue.parse(bytes.copyOfRange(0, bytes.size / 2)) }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected malformed catalogue to be rejected") }
        catch (_: Exception) { /* invalid source data must not load */ }
    }
}
