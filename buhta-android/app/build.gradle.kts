plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.buhta.app"
    compileSdk = 35

    // Постоянный ключ подписи: обновления APK ставятся поверх старой версии без удаления.
    signingConfigs {
        getByName("debug") {
            storeFile = file("buhta-release.jks")
            storePassword = "buhta-sign"
            keyAlias = "buhta"
            keyPassword = "buhta-sign"
        }
    }

    defaultConfig {
        applicationId = "ru.buhta.app"
        minSdk = 26
        targetSdk = 34
        val build = (System.getenv("BUHTA_BUILD") ?: "1").toInt()
        versionCode = build
        versionName = "0.1.$build"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // libbuhta.so — это не библиотека, а Go-бинарник (joiner в режиме client).
    // Его кладёт в jniLibs CI; legacy-упаковка нужна, чтобы файл распаковался
    // в nativeLibraryDir и его можно было запустить как процесс.
    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
        }
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.hierynomus:sshj:0.38.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
        exclude(group = "org.bouncycastle", module = "bcpkix-jdk18on")
        exclude(group = "org.bouncycastle", module = "bcutil-jdk18on")
    }
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcutil-jdk18on:1.78.1")
}
