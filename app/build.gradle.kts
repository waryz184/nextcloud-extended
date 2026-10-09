import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

// Load signing config from keystore.properties (gitignored). Absent on CI/fresh clones.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

android {
    namespace = "xyz.luna.nextcloudextended"
    compileSdk = 35

    defaultConfig {
        applicationId = "xyz.luna.nextcloudextended"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.3.0"
        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.containsKey("storeFile")) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Only attach the signing config if the keystore is present
            if (keystoreProperties.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
    kapt {
        arguments {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/module-info.class"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
        }
    }
}

dependencies {
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.biometric:biometric:1.1.0")
    // biometric 1.1.0 drags in fragment 1.2.5, whose FragmentActivity rejects the request codes the modern
    // Activity Result API generates ("Can only use lower 16 bits for requestCode"): every registry-based
    // launcher (camera, document scan, permissions) crashed on Android 16. Force a version that supports it.
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    
    // OkHttp for CalDAV
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // In-app QR scanner for the Nextcloud login QR: CameraX preview + ZXing decoder (no Google Play services)
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.zxing:core:3.5.3")

    // Apache POI for Office document viewing (XLSX, XLS, DOCX, PPTX)
    implementation("org.apache.poi:poi-ooxml:5.2.5")
    // Android ships NO javax.xml.stream (StAX) API — POI/XMLBeans needs it. Provide API + impl:
    //   stax-api 1.0-2 = the JSR-173 API (javax.xml.stream.* only, no conflict with Android)
    //   aalto-xml      = a lightweight StAX implementation
    implementation("javax.xml.stream:stax-api:1.0-2")
    implementation("com.fasterxml:aalto-xml:1.3.2")
    
    testImplementation("junit:junit:4.13.2")
    // Real HTTP exchanges for the network layer (redirects, retries, chunked upload, resume…).
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    // android.jar only ships stubs for org.json on the JVM unit-test classpath.
    testImplementation("org.json:json:20231013")
    // Renders Compose screens to PNG on the JVM (no emulator available): see UiSnapshotTest.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
