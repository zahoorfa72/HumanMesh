plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val supabaseUrl = providers.gradleProperty("SUPABASE_URL")
    .orElse(providers.environmentVariable("SUPABASE_URL"))
    .orElse("")
    .get()
val supabaseAnonKey = providers.gradleProperty("SUPABASE_ANON_KEY")
    .orElse(providers.environmentVariable("SUPABASE_ANON_KEY"))
    .orElse("")
    .get()
val escapedSupabaseUrl = supabaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")
val escapedSupabaseAnonKey = supabaseAnonKey.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.humanmesh.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.humanmesh.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
        buildConfigField("String", "SUPABASE_URL", "\"$escapedSupabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$escapedSupabaseAnonKey\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    signingConfigs {
        // Explicitly use the same persisted keystore that CI restores before Gradle runs.
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
