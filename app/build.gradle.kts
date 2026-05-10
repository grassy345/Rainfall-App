import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// ── Load MQTT credentials from local.properties ──────────────────────
val localProps = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        load(localPropsFile.inputStream())
    }
}

android {
    namespace = "com.sreer.rainfallmonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sreer.rainfallmonitor"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inject MQTT credentials into BuildConfig at compile time
        buildConfigField("String", "MQTT_BROKER_HOST", "\"${localProps.getProperty("mqtt.broker.host", "")}\"")
        buildConfigField("int",    "MQTT_BROKER_PORT", localProps.getProperty("mqtt.broker.port", "8883"))
        buildConfigField("String", "MQTT_USERNAME",    "\"${localProps.getProperty("mqtt.username", "")}\"")
        buildConfigField("String", "MQTT_PASSWORD",    "\"${localProps.getProperty("mqtt.password", "")}\"")
        buildConfigField("String", "MQTT_TOPIC_STATUS","\"${localProps.getProperty("mqtt.topic.status", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }
}

dependencies {
    // ── Android Core & UI ──────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")

    // ── Lifecycle (ViewModel + LiveData) ───────────────────────────────
    val lifecycleVersion = "2.9.0"
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-service:$lifecycleVersion")

    // ── Room Database ─────────────────────────────────────────────────
    val roomVersion = "2.7.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // ── Coroutines ────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // ── MQTT (HiveMQ Client v5) ───────────────────────────────────────
    implementation("com.hivemq:hivemq-mqtt-client:1.3.7")

    // ── Charting (MPAndroidChart) ─────────────────────────────────────
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // ── WorkManager (for periodic background tasks if needed) ─────────
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // ── Testing ───────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
