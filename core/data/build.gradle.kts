plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.swaran.airbridge.core.data"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get()))
    }
}

dependencies {
    api(project(":domain"))
    implementation(project(":core:common"))
    implementation(project(":core:storage"))
    implementation(project(":core:network"))
    implementation(project(":core:service"))
    
    // Hilt
    ksp(libs.hilt.ksp)
    implementation(libs.hilt.android)
    
    // Coroutines & Serialization
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    
    // Logging
    implementation(libs.timber)
    
    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
