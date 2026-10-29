import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

// Signing is never committed: keystore.properties next to settings.gradle.kts (storeFile, storePassword, keyAlias,
// keyPassword) or the DF_* environment variables. Without them assembleRelease gives an unsigned APK.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = signingProps.getProperty(key) ?: System.getenv(env)
val releaseStoreFile = signingValue("storeFile", "DF_STORE_FILE")

// The Daftari server (accounts, OTP, chat, backup). -PdaftariServerUrl=https://... or gradle.properties.
val serverUrl: String = (findProperty("daftariServerUrl") as String?)?.trim().orEmpty()
// Optional Google web client id fallback; normally the server's /api/v1/info gives it.
// Google OAuth web client id (optional; Drive backup only needs the Android client registered in his Cloud project).
// -PdaftariGoogleClientId=… or `daftari.googleClientId=…` in local.properties (never committed).
val localProps = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
// One command to rebuild with his id: DF_GOOGLE_WEB_CLIENT_ID=…apps.googleusercontent.com in local.properties, the environment or -P.
val googleClientId: String = ((findProperty("DF_GOOGLE_WEB_CLIENT_ID") as String?) ?: System.getenv("DF_GOOGLE_WEB_CLIENT_ID")
    ?: localProps.getProperty("DF_GOOGLE_WEB_CLIENT_ID") ?: (findProperty("daftariGoogleClientId") as String?) ?: localProps.getProperty("daftari.googleClientId"))?.trim().orEmpty()

android {
    namespace = "com.daftari.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.daftari.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "SERVER_URL", "\"" + serverUrl.replace("\"", "") + "\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"" + googleClientId.replace("\"", "") + "\"")
        buildConfigField("String", "DF_GOOGLE_WEB_CLIENT_ID", "\"" + googleClientId.replace("\"", "") + "\"")
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("storePassword", "DF_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "DF_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "DF_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "1536m"
            }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*") }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)
    implementation(libs.work.runtime.ktx)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
