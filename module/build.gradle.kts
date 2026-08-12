plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.miui.superwallpapernoaod"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.miui.superwallpapernoaod"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3.0"
    }

    buildFeatures {
        compose = true
    }

    sourceSets["main"].java.srcDirs("src")
    sourceSets["main"].res.srcDirs("res")
    sourceSets["main"].manifest.srcFile("AndroidManifest.xml")

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.compose.foundation:foundation:1.9.4")
    implementation("androidx.compose.runtime:runtime:1.9.4")
    implementation("androidx.compose.ui:ui:1.9.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.3")
    compileOnly(files("libs/xposed-api-82.jar"))
}
