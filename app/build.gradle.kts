plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.daycalculator.dynamic.app"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "com.daycalculator.dynamic.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 74
        versionName = "3.43.2"
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "GITHUB_DESCRIPTION", "\"For more information, check GitHub.\"")
            buildConfigField("boolean", "SHOW_CHANGELOG", "false")
            buildConfigField("String", "PRIVACY_POLICY_URL", "\"https://sites.google.com/view/daycalcy-privacy-policy-\"")
            buildConfigField("boolean", "EXACT_WIDGET_REFRESH", "false")
        }
        create("direct") {
            dimension = "distribution"
            buildConfigField("String", "GITHUB_DESCRIPTION", "\"For updates, check GitHub.\"")
            buildConfigField("boolean", "SHOW_CHANGELOG", "true")
            buildConfigField("String", "PRIVACY_POLICY_URL", "\"\"")
            buildConfigField("boolean", "EXACT_WIDGET_REFRESH", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")
}
