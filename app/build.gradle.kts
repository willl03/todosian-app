plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

import java.io.FileInputStream
import java.util.Properties

android {
    namespace = "com.isotjs.todosian"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.isotjs.todosian"
        minSdk = 30
        targetSdk = 37
        // Fork versioning: versionCode = <upstream code> * 1000 + <fork build>;
        // versionName = "<upstream version>+fork.<build>".
        versionCode = 10003
        versionName = "2.2.0+fork.3"
    }

    val keystoreProperties = Properties().apply {
        val propsFile = rootProject.file("keystore.properties")
        if (propsFile.exists()) {
            FileInputStream(propsFile).use { load(it) }
        }
    }
    fun signingValue(key: String): String? = System.getenv(key) ?: keystoreProperties.getProperty(key)

    signingConfigs {
        val storeFilePath = signingValue("TODOSIAN_RELEASE_STORE_FILE")
        val storePassword = signingValue("TODOSIAN_RELEASE_STORE_PASSWORD")
        val keyAlias = signingValue("TODOSIAN_RELEASE_KEY_ALIAS")
        val keyPassword = signingValue("TODOSIAN_RELEASE_KEY_PASSWORD")

        val storeFile = storeFilePath?.let { rootProject.file(it) }
        if (storeFile != null && storeFile.exists() && storePassword != null && keyAlias != null && keyPassword != null) {
            create("release") {
                this.storeFile = storeFile
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword

                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

android.sourceSets {
    getByName("debug") {
        res.directories += "src/debug/res"
    }
}

dependencies {
    implementation(libs.google.material)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.glance.appwidget)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.squareup.leakcanary.android)
}

tasks.register("updateChangelogJson") {
    val vCode = android.defaultConfig.versionCode
    val vName = android.defaultConfig.versionName
    doLast {
        val jsonFile = file("src/main/assets/changelog.json")
        if (jsonFile.exists() && vCode != null && vName != null) {
            val content = jsonFile.readText()
            // Only the newest entry (first in the file) is synced; older entries keep their
            // own version metadata.
            val updated = content
                .replaceFirst(Regex("\"versionCode\":\\s*\\d+"), "\"versionCode\": $vCode")
                .replaceFirst(Regex("\"versionName\":\\s*\"[^\"]*\""), "\"versionName\": \"$vName\"")
            if (content != updated) {
                jsonFile.writeText(updated)
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn("updateChangelogJson")
}
