import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.1.0-1.0.29"
    kotlin("plugin.serialization") version "2.1.0"
}

// Load API key from local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

android {
    namespace = "com.example.lamforgallery"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.lamforgallery"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        buildConfigField("String", "GOOGLE_API_KEY", "\"${localProperties.getProperty("GOOGLE_API_KEY") ?: ""}\"")
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
    
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }

//    fun AaptOptions.() {
//        noCompress += "onnx"
//        noCompress += "ort"
//    }
    androidResources {
        noCompress += "onnx"
        noCompress += "ort"
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended-android:1.6.8")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation(libs.androidx.foundation) // Or any recent stable version
    testImplementation(libs.junit)
    
    // Koog AI Agent Framework
    implementation("ai.koog:koog-agents:0.5.3")
    
    // Ktor Client (required by Koog)
    implementation("io.ktor:ktor-client-cio:2.3.5")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.5")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.5")
    
    // JSON Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("com.google.code.gson:gson:2.10.1")
    
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    val room_version = "2.6.1" // Using a recent stable version of Room
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    ksp("androidx.room:room-compiler:$room_version")
    implementation("androidx.exifinterface:exifinterface:1.3.6")
    implementation("com.google.mlkit:face-detection:16.1.6")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}