// DION Mayak: separate app, VPN over several DION call rooms at once.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun signingValue(name: String): String? =
    (providers.gradleProperty(name).orNull ?: providers.environmentVariable(name).orNull ?: localProperties.getProperty(name))
        ?.takeIf { it.isNotBlank() }

val keystorePath = signingValue("KEYSTORE_FILE")
val dionBuildNumber: Int? = providers.environmentVariable("DION_BUILD_NUMBER").orNull?.toIntOrNull()

android {
    namespace = "app.mayak.dion"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.mayak.dion"
        minSdk = 26
        targetSdk = 37
        versionCode = dionBuildNumber?.let { 1000 + it } ?: 1
        versionName = dionBuildNumber?.let { "1.0.$it" } ?: "1.0-dev"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = signingValue("KEYSTORE_PASSWORD")
                keyAlias = signingValue("KEY_ALIAS")
                keyPassword = signingValue("KEY_PASSWORD")
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        jniLibs {
            // The relay and the balancer are started as processes, so they must be real files.
            useLegacyPackaging = true
            keepDebugSymbols += "**/libwbrelay.so"
            keepDebugSymbols += "**/libdionmux.so"
            keepDebugSymbols += "**/libgojni.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/NOTICE.md"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("com.hierynomus:sshj:0.38.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
        exclude(group = "org.bouncycastle", module = "bcpkix-jdk18on")
        exclude(group = "org.bouncycastle", module = "bcutil-jdk18on")
    }
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
    implementation("org.bouncycastle:bcutil-jdk18on:1.85")
    // whitelist-bypass tun2socks (gomobile), built by CI.
    implementation(files("libs/wbmobile.aar"))
}
