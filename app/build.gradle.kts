import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val journeyLocalProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use(::load)
}

// The former property changed defaultConfig and could also rename release APKs.
// Acceptance now has its own debug-derived build type and package/UID.
require(!providers.gradleProperty("m6IsolatedAcceptance").isPresent) {
    "m6IsolatedAcceptance is retired. Use the m6Acceptance build type; release cannot use acceptance mode."
}

fun configuredString(name: String): String {
    val value = journeyLocalProperties.getProperty(name) ?: System.getenv(name).orEmpty()
    return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

android {
    namespace = "com.journeycontinuity.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.journeycontinuity.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SUPABASE_URL", configuredString("SUPABASE_URL"))
        // Public client credential: dedicated tile project/key, never the server geocoding secret.
        buildConfigField("String", "GEOAPIFY_MAP_TILE_KEY", configuredString("GEOAPIFY_MAP_TILE_KEY"))
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            configuredString("SUPABASE_PUBLISHABLE_KEY"),
        )
        buildConfigField(
            "String",
            "TRUSTED_VIEWER_BASE_URL",
            configuredString("TRUSTED_VIEWER_BASE_URL"),
        )

        testInstrumentationRunner = "com.journeycontinuity.app.IsolatedTestRunner"
    }

    buildTypes {
        create("m6Acceptance") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".m6isolation"
            matchingFallbacks += listOf("debug")
        }
        create("travellerUiAcceptance") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".travelleruiacceptance"
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"\"")
        }
        create("boundaryAcceptance") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".boundaryacceptance"
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"\"")
        }
        create("localAcceptance") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".localacceptance"
            matchingFallbacks += listOf("debug")
            val localKey = journeyLocalProperties.getProperty("LOCAL_ACCEPTANCE_PUBLISHABLE_KEY")
                ?: System.getenv("LOCAL_ACCEPTANCE_PUBLISHABLE_KEY").orEmpty()
            require(localKey.isBlank() || localKey.startsWith("sb_publishable_")) {
                "Local acceptance APK requires a publishable key, never a privileged key."
            }
            buildConfigField("String", "SUPABASE_URL", "\"http://127.0.0.1:57421\"")
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", configuredString("LOCAL_ACCEPTANCE_PUBLISHABLE_KEY"))
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("m6Acceptance")) {
        it.enableAndroidTest = true
    }
    beforeVariants(selector().withBuildType("travellerUiAcceptance")) {
        it.enableAndroidTest = true
    }
    beforeVariants(selector().withBuildType("boundaryAcceptance")) {
        it.enableAndroidTest = true
    }
    beforeVariants(selector().withBuildType("localAcceptance")) {
        it.enableAndroidTest = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.maplibre.opengl)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.play.services.location)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.android)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    add("m6AcceptanceImplementation", libs.androidx.compose.ui.test.manifest)
    add("travellerUiAcceptanceImplementation", libs.androidx.compose.ui.test.manifest)
    add("localAcceptanceImplementation", libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
