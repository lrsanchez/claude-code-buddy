plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.claude.buddy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.claude.buddy"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    debugImplementation(libs.compose.ui.tooling)
}

// AGP registers `test` as a lifecycle task across all variants, and lifecycle
// tasks reject Gradle's `--tests` filter. Replace it with the real unit-test
// task (created after the variants are registered) so that
// `./gradlew :app:test --tests Foo` works like in any JVM project.
afterEvaluate {
    val unitTest = tasks.named<Test>("testDebugUnitTest")
    val allUnitTests = tasks.replace("test", Test::class.java)
    allUnitTests.group           = "verification"
    allUnitTests.description     = "Run unit tests for all variants."
    allUnitTests.testClassesDirs = files(unitTest.map { it.testClassesDirs })
    allUnitTests.classpath       = files(unitTest.map { it.classpath })
}
