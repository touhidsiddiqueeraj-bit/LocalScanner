plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Release version can be overridden from CI (tag name, e.g. -PappVersion=1.2.0)
val appVersionName = (project.findProperty("appVersion") as String?) ?: "1.0.0"
val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

android {
    namespace = "com.localscanner.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.localscanner.app"
        minSdk = 24
        targetSdk = 34
        versionCode = ciRun ?: 1
        versionName = appVersionName
        ndk {
            // Slimmer APK: skip the legacy 32-bit x86 emulator ABI
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        val ksPath = System.getenv("KEYSTORE_PATH")
        val ksPass = System.getenv("KEYSTORE_PASSWORD")
        val alias = System.getenv("KEY_ALIAS") ?: "localscanner"
        val keyPass = System.getenv("KEY_PASSWORD") ?: ksPass
        if (ksPath != null && ksPass != null && File(ksPath).exists()) {
            create("ciRelease") {
                storeFile = File(ksPath)
                storePassword = ksPass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("ciRelease") ?: signingConfigs.getByName("debug")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST"
            )
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    testOptions {
        unitTests {
            // Robolectric needs the merged resources/manifest to render real UI.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Core / lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Room database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Settings storage
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // CameraX
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Computer vision (edge detection, perspective warp, document filters)
    implementation("org.opencv:opencv:4.10.0")

    // OCR - English: ML Kit bundled on-device model
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // OCR - Bengali + English fallback: Tesseract4Android (on-device, no network)
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.8.0")

    // Searchable PDF export (invisible OCR text layer)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Thumbnails
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // Screenshot tests: Robolectric renders the real Compose UI to PNG on the JVM,
    // no emulator or device required (used to generate docs/screenshots).
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.26.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.26.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<Test>().configureEach {
    // Robolectric + Compose rendering needs more than the default 512m heap.
    maxHeapSize = "1792m"
    // captureRoboImage() writes PNGs when recording is enabled (the Roborazzi
    // Gradle plugin normally toggles this; we run without the plugin).
    systemProperty("roborazzi.record.enable", "true")
    // Allow a pre-warmed android-all jar cache (set ROBOLECTRIC_CACHE_DIR locally).
    System.getenv("ROBOLECTRIC_CACHE_DIR")?.let {
        systemProperty("robolectric.dependency.cache.dir", it)
    }
}
