# Third-party notices

The Askurious diary app for Android contains the following libraries and one font; the licence stands with each entry.
The full texts ship with the app and are shown under "About the app" › licence texts: the Apache License 2.0 (`app/src/main/res/raw/apache_license_2_0.txt`), the SIL Open Font License 1.1 of PT Serif (`app/src/main/res/raw/pt_serif_licence.txt`), and the NOTICE files of ZXing, Kotlin, kotlinx.coroutines and kotlinx.serialization (each at the version shipped) with the BSD 3-Clause licence of ThreeTen-bp (`app/src/main/res/raw/notices.txt`).

| Library | Artifacts | Version | Copyright | Licence |
|---|---|---|---|---|
| AndroidX Core | `androidx.core:core-ktx` | 1.17.0 | The Android Open Source Project | Apache-2.0 |
| AndroidX Activity | `androidx.activity:activity-compose` | 1.11.0 | The Android Open Source Project | Apache-2.0 |
| AndroidX Lifecycle | `androidx.lifecycle:lifecycle-runtime-ktx` | 2.9.4 | The Android Open Source Project | Apache-2.0 |
| AndroidX Navigation (Compose) | `androidx.navigation:navigation-compose` (with `navigation-common` and `navigation-runtime`) | 2.9.8 | The Android Open Source Project | Apache-2.0 |
| Jetpack Compose (UI, Material 3, Material Icons) | `androidx.compose:compose-bom` and its artifacts: `androidx.compose.ui:ui` (with foundation, animation, runtime), `androidx.compose.material3:material3`, `androidx.compose.material:material-icons-core` | BOM 2026.06.01: ui 1.11.4, material3 1.4.0, material-icons-core 1.7.8 | The Android Open Source Project | Apache-2.0 |
| Material Components for Android | `com.google.android.material:material` | 1.13.0 | The Android Open Source Project | Apache-2.0 |
| WorkManager | `androidx.work:work-runtime-ktx` | 2.10.5 | The Android Open Source Project | Apache-2.0 |
| AndroidX WebKit | `androidx.webkit:webkit` | 1.17.1 | The Android Open Source Project | Apache-2.0 |
| ZXing Android Embedded | `com.journeyapps:zxing-android-embedded` | 4.3.0 | ZXing authors, Journey Mobile | Apache-2.0 |
| ZXing ("Zebra Crossing") | `com.google.zxing:core` (used by ZXing Android Embedded) | 3.5.4 | ZXing authors | Apache-2.0 (NOTICE in `notices.txt`) |
| Kotlin standard library | `org.jetbrains.kotlin:kotlin-stdlib` | 2.2.20 | JetBrains s.r.o. and Kotlin contributors | Apache-2.0 (NOTICE in `notices.txt`) |
| ThreeTen-bp, part of the Kotlin standard library (`kotlin.time`) | in `org.jetbrains.kotlin:kotlin-stdlib` | 2.2.20 | © 2007-present Stephen Colebourne & Michael Nascimento Santos | BSD-3-Clause (text in `notices.txt`) |
| kotlinx.coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-android`, `-core` (transitive) | 1.9.0 | JetBrains s.r.o. and contributors | Apache-2.0 (NOTICE in `notices.txt`) |
| kotlinx.serialization | `org.jetbrains.kotlinx:kotlinx-serialization-core` (transitive; the routes of the screens use it) | 1.7.3 | JetBrains s.r.o. and contributors | Apache-2.0 (NOTICE in `notices.txt`) |
| JetBrains Java Annotations | `org.jetbrains:annotations` (transitive) | 23.0.0 | JetBrains s.r.o. | Apache-2.0 |
| JSpecify | `org.jspecify:jspecify` (transitive) | 1.0.0 | The JSpecify Authors | Apache-2.0 |
| Error Prone annotations | `com.google.errorprone:error_prone_annotations` (transitive) | 2.15.0 | The Error Prone Authors | Apache-2.0 |
| Guava ListenableFuture | `com.google.guava:listenablefuture` (transitive) | 1.0 | The Guava Authors | Apache-2.0 |

| Font | File | Copyright | Licence |
|---|---|---|---|
| PT Serif Bold (logo and app icon) | `app/src/main/res/font/pt_serif_bold.ttf` (unmodified `PT_Serif-Web-Bold.ttf` of [google/fonts](https://github.com/google/fonts/tree/main/ofl/ptserif)) | © 2010 ParaType Ltd., Reserved Font Names "PT Sans", "PT Serif" and "ParaType" | SIL Open Font License 1.1 |

Further AndroidX and Jetpack libraries come in as transitive dependencies of the ones above (for example Room, SQLite, AppCompat, Fragment, emoji2, graphics-path; © The Android Open Source Project); they are under the Apache License 2.0 as well, and the app says so below its list of libraries.

According to `license/README.md` of Kotlin 2.2.20, the Kotlin standard library also contains code derived from other projects: `kotlin.time` from ThreeTen-bp (BSD 3-Clause, listed above, text in `notices.txt`) and, in `kotlin/util/MathJVM.kt`, code derived from the Boost special math functions (Copyright Eric Ford & Hubert Holin 2001, Boost Software License 1.0, which asks for no notice in compiled form).

Only for the tests (not in the APK):

| Library | Artifacts | Version | Licence |
|---|---|---|---|
| JUnit 4 (with Hamcrest Core 1.3) | `junit:junit` | 4.13.2 | Eclipse Public License 1.0 (Hamcrest: BSD-3-Clause) |
| Robolectric | `org.robolectric:robolectric` | 4.17 | MIT |
| JSON in Java | `org.json:json` | 20260814 | Public domain |

The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) is © Gradle, Inc. and contributors, Apache License 2.0; it builds the app and is not in the APK.
The same holds for the Kotlin serialization compiler plugin (`org.jetbrains.kotlin.plugin.serialization` 2.2.20, JetBrains s.r.o. and Kotlin contributors, Apache License 2.0), which writes the serializers of the routes when the app is built.
