plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.buhta.app"
    compileSdk = 34

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
}
