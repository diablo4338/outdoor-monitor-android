import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun envValue(name: String): String? {
    return (project.findProperty(name) as String?)
        ?: localProperties.getProperty(name)
        ?: System.getenv(name)
}

fun requiredEnvValue(name: String): String {
    return envValue(name)
        ?: error("Missing $name. Set it in MyApplication/local.properties, environment, or pass -P$name=...")
}

fun buildConfigString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

fun requiredEnvInt(name: String): Int {
    val value = requiredEnvValue(name)
    return value.toIntOrNull()
        ?: error("$name must be an integer, got '$value'")
}

android {
    namespace = "com.example.metrics"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.metrics"
        minSdk = 26
        targetSdk = 34
        versionCode = envValue("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = envValue("VERSION_NAME") ?: "1.1-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    buildTypes {
        debug {
            buildConfigField(
                "String",
                "API_BASE_URL",
                buildConfigString(requiredEnvValue("DEBUG_API_BASE_URL"))
            )
            buildConfigField(
                "String",
                "API_FALLBACK_BASE_URL",
                buildConfigString(envValue("DEBUG_API_FALLBACK_BASE_URL") ?: "")
            )
            buildConfigField(
                "String",
                "GOOGLE_WEB_CLIENT_ID",
                buildConfigString(requiredEnvValue("GOOGLE_WEB_CLIENT_ID"))
            )
            buildConfigField(
                "int",
                "POLL_INTERVAL_SECONDS",
                requiredEnvInt("POLL_INTERVAL_SECONDS").toString()
            )
        }

        release {
            val signingStoreFile = envValue("SIGNING_STORE_FILE")
            val signingStorePassword = envValue("SIGNING_STORE_PASSWORD")
            val signingKeyAlias = envValue("SIGNING_KEY_ALIAS")
            val signingKeyPassword = envValue("SIGNING_KEY_PASSWORD")
            if (
                signingStoreFile != null && signingStorePassword != null &&
                signingKeyAlias != null && signingKeyPassword != null
            ) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(signingStoreFile)
                    storePassword = signingStorePassword
                    keyAlias = signingKeyAlias
                    keyPassword = signingKeyPassword
                }
            }
            buildConfigField(
                "String",
                "API_BASE_URL",
                buildConfigString(requiredEnvValue("RELEASE_API_BASE_URL"))
            )
            buildConfigField(
                "String",
                "API_FALLBACK_BASE_URL",
                buildConfigString(envValue("RELEASE_API_FALLBACK_BASE_URL") ?: "")
            )
            buildConfigField(
                "String",
                "GOOGLE_WEB_CLIENT_ID",
                buildConfigString(requiredEnvValue("GOOGLE_WEB_CLIENT_ID"))
            )
            buildConfigField(
                "int",
                "POLL_INTERVAL_SECONDS",
                requiredEnvInt("POLL_INTERVAL_SECONDS").toString()
            )
            isMinifyEnabled = true       // хочешь обфускацию — скажи
            isShrinkResources = true
            proguardFiles(

                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    packaging {
        jniLibs {
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // compose BOM — сам управляет версиями UI/M3
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Compose BOM – управляет версиями
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))

    // Базовые артефакты Compose
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")      // <-- НУЖНО ДЛЯ background
    implementation("androidx.compose.ui:ui-tooling-preview")

    // (опционально, но полезно)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // Debug tooling
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Твоя сеть
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
}
