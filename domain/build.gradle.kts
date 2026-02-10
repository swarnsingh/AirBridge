import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(libs.versions.java.get())
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation("javax.inject:javax.inject:1")
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
