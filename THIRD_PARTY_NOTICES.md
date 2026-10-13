# Third-party notices

The Askurious diary app for Android contains the following libraries.
All of them are under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0); the full text ships with the app (`app/src/main/res/raw/apache_license_2_0.txt`) and is shown under "About the app".

| Library | Artifacts | Version | Copyright | Licence |
|---|---|---|---|---|
| AndroidX Core | `androidx.core:core-ktx` | 1.17.0 | The Android Open Source Project | Apache-2.0 |
| AndroidX Activity | `androidx.activity:activity-compose` | 1.11.0 | The Android Open Source Project | Apache-2.0 |
| AndroidX Lifecycle | `androidx.lifecycle:lifecycle-runtime-ktx` | 2.9.4 | The Android Open Source Project | Apache-2.0 |
| Jetpack Compose (UI, Material 3, Material Icons) | `androidx.compose:compose-bom` and its artifacts | BOM 2026.06.01 | The Android Open Source Project | Apache-2.0 |
| Material Components for Android | `com.google.android.material:material` | 1.13.0 | The Android Open Source Project | Apache-2.0 |
| WorkManager | `androidx.work:work-runtime-ktx` | 2.10.5 | The Android Open Source Project | Apache-2.0 |
| AndroidX WebKit | `androidx.webkit:webkit` | 1.17.1 | The Android Open Source Project | Apache-2.0 |
| ZXing Android Embedded | `com.journeyapps:zxing-android-embedded` | 4.3.0 | JourneyApps | Apache-2.0 |
| ZXing ("Zebra Crossing") | `com.google.zxing:core` (used by ZXing Android Embedded) | 3.5.4 | ZXing authors | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines | `org.jetbrains.kotlin:kotlin-stdlib`, `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 2.2.20, 1.8.1 | JetBrains s.r.o. and Kotlin contributors | Apache-2.0 |

Further AndroidX libraries come in as transitive dependencies of the ones above; they are under the same licence.
Only for the tests (not in the app): JUnit 4.13.2, Eclipse Public License 1.0.
