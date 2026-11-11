plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.gamecontrols.link"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.gamecontrols.link"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
    }
    buildFeatures {
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.all {
            it.systemProperty("android.manifest", file("src/main/AndroidManifest.xml").absolutePath)
            it.systemProperty("android.resources", file("src/main/res").absolutePath)
        }
    }
    lint {
        abortOnError = true
        checkDependencies = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    constraints {
        implementation(libs.serialization.core) {
            because("AndroidX SavedState's transitive core uses the cached, checksum-verified 1.9.0 version.")
        }
    }
    implementation(project(":protocol"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle)
    implementation(libs.coroutines.android)
    implementation(libs.material)
    testImplementation(libs.junit)
}
