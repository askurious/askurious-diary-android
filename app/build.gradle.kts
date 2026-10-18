import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Instant
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // the routes of the screens as classes (Navigation Compose, type-safe routes)
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * The app's versionCode (audit 2026-10-18, AND-08): no longer the number of Git commits, which a shallow clone or a
 * source archive gave as 1 (a build the phone refuses as a downgrade and the server as outdated). It is the minutes
 * since 2026-01-01 00:00 UTC at the build, so every later build is higher, with or without Git, and far above the
 * codes published before (the commit counts, up to [lastPublishedVersionCode]); a CI may set it with
 * -PversionCode=<n>. Minutes reach the Play Store's limit (2 100 000 000) only in about 4000 years.
 */
val lastPublishedVersionCode: Int = (findProperty("askurious.lastPublishedVersionCode") as String?)?.toInt() ?: 0
val appVersionCode: Int = (findProperty("versionCode") as String?)?.toInt()
    ?: ((System.currentTimeMillis() - Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()) / 60_000L).toInt()
check(appVersionCode > lastPublishedVersionCode) {
    "versionCode $appVersionCode is not above the last published one ($lastPublishedVersionCode, gradle.properties)"
}

/** The servers of Askurious (origins) that join links may name; see KNOWN_SERVERS below. */
val knownServers = listOf(
    "https://askurious.de", "https://ask.askurious.de", "https://polltergeist.de", "https://ask.polltergeist.de",
).joinToString(",")
val debugServers = listOf("http://localhost:5080", "http://127.0.0.1:5080", "http://10.0.2.2:5080").joinToString(",")

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
        testInstrumentationRunner = "de.askurious.app.test.UiInstrumentation"
        minSdk = 26
        targetSdk = 36
        // a newer build always has a higher versionCode (the Play Store and the server's DIARY_MIN_APP_VERSION
        // compare it), also when built without Git (appVersionCode above)
        versionCode = appVersionCode
        versionName = "0.1.$appVersionCode"
        // the server of a join code typed by hand (a QR code names its own server): the main address of Askurious, where
        // the diary studies run for now (server: config DIARY_ON_MAIN_ADDRESS, 2026-10-12); a debug build talks to
        // the Flask server on the computer through `adb reverse tcp:5080 tcp:5060` (the server answers only under
        // localhost while it runs with the development key)
        buildConfigField("String", "BASE_URL", "\"https://askurious.de\"")
        // the servers of Askurious a join link (QR code, App Link) may name (audit 2026-10-13, AN-1): the domains of
        // Askurious and of its brands (server: BRANDS, app/brand.py) and their participants' addresses; any other
        // server is refused with a message. A new brand or instance of Askurious is added here.
        buildConfigField("String", "KNOWN_SERVERS", "\"$knownServers\"")
        // when this APK was built (epoch milliseconds), shown in the footer of the app: which version runs on a phone
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }

    buildTypes {
        debug {
            buildConfigField("String", "BASE_URL", "\"http://127.0.0.1:5080\"")
            // and the Flask server on the computer (adb reverse; 10.0.2.2 is the computer seen from the emulator)
            buildConfigField("String", "KNOWN_SERVERS", "\"$knownServers,$debugServers\"")
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
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
    // the screens of MainActivity on a back stack with deep links, transitions and the predictive back gesture (UX
    // audit 2026-10-16, structure 1); 2.9 needs no newer Activity or Lifecycle than the app has
    implementation("androidx.navigation:navigation-compose:2.9.8")

    implementation("androidx.work:work-runtime-ktx:2.10.5")
    // the bridge of an entry as a message channel of the server's main frame only (audit 2026-10-13, AN-12)
    implementation("androidx.webkit:webkit:1.17.1")
    // QR codes without Google Play services
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // ZXing Android Embedded is in maintenance and brings core 3.4.1 (2020); the decoder itself in its last release
    // (audit 2026-10-13, AN-10)
    implementation("com.google.zxing:core:3.5.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    // the real org.json for the unit tests (android.jar has only stubs): the test vectors of the random prompts
    testImplementation("org.json:json:20260814")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
