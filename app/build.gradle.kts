plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.cayana"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cayana"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        val supabaseUrl = (project.findProperty("CAYANA_SUPABASE_URL") as? String)
            ?: System.getenv("CAYANA_SUPABASE_URL")
            ?: ""
        val supabasePublishableKey = (project.findProperty("CAYANA_SUPABASE_PUBLISHABLE_KEY") as? String)
            ?: System.getenv("CAYANA_SUPABASE_PUBLISHABLE_KEY")
            ?: ""

        buildConfigField("String", "CAYANA_SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "CAYANA_SUPABASE_PUBLISHABLE_KEY", "\"$supabasePublishableKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    sourceSets {
        getByName("test").assets.srcDirs("$projectDir/schemas")
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
        getByName("debug").assets.srcDirs("$projectDir/schemas")
        getByName("release").assets.srcDirs("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Koin
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // WorkManager
    implementation(libs.androidx.work.runtime)

    // ML Kit OCR
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.kotlinx.coroutines.play.services)

    // Google Play Services Auth (AuthorizationClient for Drive appDataFolder)
    implementation(libs.google.play.services.auth)

    // On-device ASR (sherpa-onnx)
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))

    // Archive extraction (supports official tar.bz2 and zip)
    implementation("org.apache.commons:commons-compress:1.26.1")

    // Supabase
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.okhttp)

    // Testing
    testImplementation(libs.junit)
    testImplementation("org.postgresql:postgresql:42.7.4")
    testImplementation(libs.embedded.postgres)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.koin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

tasks.withType<Test> {
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.matching { it.name == "testReleaseUnitTest" }.configureEach {
    (this as? Test)?.exclude("**/HomeSearchUiTest*")
}

