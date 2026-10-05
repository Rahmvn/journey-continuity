package com.journeycontinuity.app.data.catalogue

import android.content.Context
import com.journeycontinuity.app.domain.AdminCatalogue
import com.journeycontinuity.app.domain.AdminCatalogueMetadata
import com.journeycontinuity.app.domain.AdminLocalUnit
import com.journeycontinuity.app.domain.AdminRegion

/** Offline catalogue reader. The validated asset is parsed once per app process. */
class BundledAdminCatalogue(context: Context) {
    private val applicationContext = context.applicationContext

    val metadata: AdminCatalogueMetadata get() = catalogue().metadata
    val regions: List<AdminRegion> get() = catalogue().regions

    fun region(id: String): AdminRegion? = catalogue().region(id)
    fun localUnit(id: String): AdminLocalUnit? = catalogue().localUnit(id)
    fun localUnitsFor(parentRegionId: String): List<AdminLocalUnit> =
        catalogue().localUnitsFor(parentRegionId)
    fun localUnitByName(parentRegionId: String, nameOrAlias: String): AdminLocalUnit? =
        catalogue().localUnitByName(parentRegionId, nameOrAlias)

    private fun catalogue(): AdminCatalogue {
        cached?.let { return it }
        return synchronized(BundledAdminCatalogue) {
            cached ?: applicationContext.assets.open(ASSET_PATH).use { input ->
                AdminCatalogue.parse(input.readBytes()).also { cached = it }
            }
        }
    }

    companion object {
        const val ASSET_PATH = "catalogue/ng_admin_v2.json"
        @Volatile private var cached: AdminCatalogue? = null
    }
}
