import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** The number of commits up to HEAD, the app's versionCode; 1 when Git is not there. */
val gitCommitCount: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

/**
 * The upload key for the Play Store (Play App Signing keeps the app signing key): keystore.properties in the project
 * folder, never in Git (storeFile, storePassword, keyAlias, keyPassword; see keystore.properties.example). Without it
 * a release build stays unsigned, as before.
 */
val upload: Properties? = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "de.askurious.app"
    compileSdk = 36

    signingConfigs {
        if (upload != null) create("upload") {
            storeFile = rootProject.file(upload.getProperty("storeFile"))
            storePassword = upload.getProperty("storePassword")
            keyAlias = upload.getProperty("keyAlias")
            keyPassword = upload.getProperty("keyPassword")
        }
    }

    defaultConfig {
        applicationId = "de.askurious.app"
        minSdk = 26
        targetSdk = 36
        // the version from Git: every commit on main counts up, so a newer build always has a higher versionCode (the
        // Play Store and the server's DIARY_MIN_APP_VERSION compare it); 1 without Git (a source archive)
        versionCode = gitCommitCount
        versionName = "0.1.$gitCommitCount"
        // the server of a join code typed by hand (a QR code names its own server): the main address of Askurious, where
        // the diary studies run for now (server: config DIARY_ON_MAIN_ADDRESS, 2026-10-12); a debug build talks to
        // the Flask server on the computer through `adb reverse tcp:5060 tcp:5060` (the server answers only under
        // localhost while it runs with the development key)
        buildConfigField("String", "BASE_URL", "\"https://askurious.de\"")
        // when this APK was built (epoch milliseconds), shown in the footer of the app: which version runs on a phone
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }

    buildTypes {
        debug {
            buildConfigField("String", "BASE_URL", "\"http://localhost:5060\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (upload != null) signingConfig = signingConfigs.getByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.activity:activity-compose:1.11.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.google.android.material:material:1.13.0")

    implementation("androidx.work:work-runtime-ktx:2.10.5")
    // QR codes without Google Play services
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    // the real org.json for the unit tests (android.jar has only stubs): the test vectors of the random prompts
    testImplementation("org.json:json:20260814")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
