plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本规则：versionName 为应用自己的 semver（major.minor.patch），与 Tailscale
// 版本无关；versionCode 不再手写，按 major*10000 + minor*100 + patch 推导。
// 例：2.0.0 -> 20000、2.0.1 -> 20001、2.1.0 -> 20100。发版只改上面这一行。
val appVersionName = "2.0.0"

val appVersionCode: Int = run {
    val parts = appVersionName.split(".")
    require(parts.size == 3) {
        "versionName 必须是 major.minor.patch 格式，当前为 \"$appVersionName\""
    }
    val major = parts[0].toIntOrNull()
        ?: error("versionName 只能包含数字：\"$appVersionName\"")
    val minor = parts[1].toIntOrNull()
        ?: error("versionName 只能包含数字：\"$appVersionName\"")
    val patch = parts[2].toIntOrNull()
        ?: error("versionName 只能包含数字：\"$appVersionName\"")
    require(minor in 0..99 && patch in 0..99) {
        "minor/patch 必须小于 100，才能保证 versionCode 唯一：\"$appVersionName\""
    }
    major * 10000 + minor * 100 + patch
}

android {
    namespace = "com.tsproxy.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tsproxy.android"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("ci") {
            storeFile = rootProject.file("signing/keystore.p12")
            storePassword = "tailscale-socks5"
            keyAlias = "key"
            keyPassword = "tailscale-socks5"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val ciKeystore = rootProject.file("signing/keystore.p12")
            signingConfig = if (ciKeystore.exists()) {
                signingConfigs.getByName("ci")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            val ciKeystore = rootProject.file("signing/keystore.p12")
            signingConfig = if (ciKeystore.exists()) {
                signingConfigs.getByName("ci")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    implementation("androidx.datastore:datastore-preferences:1.0.0")

    implementation("androidx.core:core-ktx:1.12.0")
}
