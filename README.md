# Askurious diary app (Android)

The app for diary surveys of [Askurious](https://askurious.de) (German: Tagebuchstudie; experience sampling).
Participants join a study with its QR code or join code, answer the entry part once, and then get the prompts of the study's modules as notifications, or start a report themselves.
Concept: `concepts/experiencesampling.md` in the Askurious repository; server side: `app/diary.py`, `app/diary_api.py`.

## How it works

- **No account.**
  Joining returns a random secret per study; the app keeps it in its private storage (no backup) and sends it as a bearer token.
  The server keeps only its hash, and the app sends no device id, so two studies on one phone cannot be linked.
- **Thin shell.**
  The questionnaire pages are the participant pages of Askurious in a WebView (`EntryActivity`), opened with a one-time address per entry.
  The app itself does the study list, joining, the schedule, the notifications and the sync.
- **Schedule on the phone.**
  `Schedule.kt` computes the prompts of the fixed modules in local time; the server computes the same times for the export (`diary.prompts`).
  One exact alarm at a time (`Alarms.kt`), set again after a reboot, an update, or a change of time or time zone.
  No push service: `SyncWorker` fetches each study every 6 hours.

## Build and run

Open the folder in Android Studio and press Run, or from a terminal (the Gradle of this project needs Android Studio's JDK):

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest assembleDebug
```

Toolchain as in the ExperienceSampling project next to this one: Gradle 9.5.1, AGP 8.13.2, Kotlin 2.2.20, compileSdk 34.

A **debug build** talks to `http://localhost:5060`, the Flask server on the computer (`.venv/bin/python run.py` in the Askurious repository).
Run `adb reverse tcp:5060 tcp:5060` once per start of the emulator, so that its `localhost:5060` is the computer's: the server answers only under `localhost` while it runs with the development key.
A **release build** talks to `https://ask.askurious.de`, the participants' address.

## Trying it on the emulator

1. In Askurious: My surveys › + Diary survey; check the tab "Schedule"; start data collection.
2. In the app: Join study › type the join code from the tab "Schedule".
3. Answer the entry part; the study shows "Starts on …" (or "Day 1 of 14" with the start "on the day of the entry part").
4. To see a prompt at once, set a module's time to a few minutes from now and bring the app to the front again (it syncs whenever it opens).

## App Links

The QR code holds `https://ask.askurious.de/j/<code>`.
For the phone to open the app directly, the server must answer `/.well-known/assetlinks.json` with the SHA-256 fingerprint of the signing key: set `DIARY_APP_FINGERPRINTS` on the server (`keytool -list -v -keystore <keystore>`).
Without it the link opens the join page in the browser, which shows the join code.

## Not yet

Random prompts within time windows, reminders, an exit part, offline answers, iOS (concept, section 10).
