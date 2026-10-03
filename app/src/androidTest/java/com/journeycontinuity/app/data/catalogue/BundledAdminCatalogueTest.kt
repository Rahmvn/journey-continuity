package com.journeycontinuity.app.data.catalogue

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeycontinuity.app.domain.AdminCatalogue
import com.journeycontinuity.app.domain.AdminLocalUnitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledAdminCatalogueTest {
    @Test fun packagedAppContainsTheCompleteValidatedCatalogueAsset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = context.assets.open(BundledAdminCatalogue.ASSET_PATH).use { it.readBytes() }
        val parsed = AdminCatalogue.parse(bytes)
        val reader = BundledAdminCatalogue(context)
        assertEquals(parsed.metadata.sha256, reader.metadata.sha256)
        assertEquals("e5a75f4d7f906ab3bd2d4db806bbbfa1b4c7ac2ff04f6cca3e52dd39e6b24aa4",
            reader.metadata.sha256)
        assertEquals(37, reader.regions.size)
        assertEquals(16, reader.localUnitsFor("ng-kwara").size)
        assertEquals(6, reader.localUnitsFor("ng-fct").size)
        assertEquals(AdminLocalUnitType.AREA_COUNCIL,
            reader.localUnit("ng-fct-abuja-municipal")?.type)
        assertEquals("ng-fct-abuja-municipal", reader.localUnitByName("ng-fct", "AMAC")?.id)
        assertNotNull(reader.localUnit("ng-delta-isoko-north"))
        assertNotNull(reader.localUnit("ng-gombe-yamaltu-deba"))
    }
}
