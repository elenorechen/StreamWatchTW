plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 簽章設定：GitHub Secrets 有設定時用正式金鑰（之後的版本才能直接覆蓋更新），
// 沒設定時退回 debug 金鑰（還是能安裝，但每次打包金鑰不同，更新時要先解除安裝）。
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")
val keystorePassword: String? = System.getenv("SIGNING_PASSWORD")
val hasReleaseKey = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank() &&
    file(keystorePath!!).exists()

android {
    namespace = "tw.plli.streamwatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "tw.plli.streamwatch"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = "plli"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
