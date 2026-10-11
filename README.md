# Askurious diary app (Android)

The app for diary surveys of [Askurious](https://askurious.de) (German: Tagebuchstudie; experience sampling).
Participants join a study with its QR code or join code, answer the entry part once, and then get the prompts of the study's modules as notifications, or start a report themselves.
Concept: `concepts/experiencesampling.md` in the Askurious repository; server side: `app/diary.py`, `app/diary_api.py`.

## How it works

- **No account.**
  Joining returns a random secret per study; the app keeps it in its private storage (no backup) and sends it as a bearer token.
  The server keeps only its hash, and the app sends no device id, so two studies on one phone cannot be linked.
  Nothing of the app goes into cloud backups or to a new phone (`allowBackup="false"`, `res/xml/data_extraction_rules.xml`).
- **No shared cookies.**
  Each entry starts with empty WebView cookies, web storage and cache (`EntryActivity`), so the session cookie of one study never travels with the entry of another.
- **Lock screen.**
  Notifications are private: the lock screen shows only "Askurious – A short question is waiting", never the title of the study or the researchers' notification text.
- **Thin shell.**
  The questionnaire pages are the participant pages of Askurious in a WebView (`EntryActivity`), opened with a one-time address per entry.
  The app itself does the study list, joining, the schedule, the notifications and the sync.
- **Schedule on the phone.**
  `Schedule.kt` computes the prompts of the fixed modules in local time; the server computes the same times for the export (`diary.prompts`).
  One exact alarm at a time (`Alarms.kt`), set again after a reboot, an update, or a change of time or time zone.
  No push service: `SyncWorker` fetches each study every 6 hours.
- **Reminder.**
  A fixed module with `reminder` > 0 gets a second notification that many minutes after the prompt, from the same one alarm (`Schedule.nextMoment` takes the earlier of the next prompt and the next reminder).
  It is posted only if the prompt is still open and was neither opened nor answered without a connection (`PromptLog`, a list of the prompts of the last three days on the phone), under the prompt's notification id (it replaces the first one) and with the same neutral lock-screen version ("Reminder: a short question is still waiting").
  Tapping it sends `"reminded": true` with the entry, and the time of the first notification as `notified`.
- **Answering without a connection.**
  For a module with `offline: true` each sync of a running study fetches its page (`POST /offline/<module>`, at most every 15 minutes) and the `/static/` and `/media/` files it names, also those named by its style sheets (`OfflinePages`, in the app's files directory, at most 10 MB per file).
  When an entry finds no network, a prompt still open or a self-started module under its daily limit opens the kept page in the WebView; the page's own files come from the phone, any other request fails.
  `participant.js` hands the filled form to `AskuriousApp.offlineSubmit()`, and the app queues it (`AnswerQueue`) and says it will be sent.
  The next sync, and a one-time WorkManager job that waits for a connection, sends it with `POST /offline`; the study card shows how many answers are waiting.
  An answer leaves the queue when the server stored it or refused it for good (answered already, questionnaire changed, expired, unknown, secret unknown); without a network, at 429 or 5xx it stays.
  The entry part is always answered online.
- **Server answers.**
  A 401 (`unauthorised`: the server does not know the secret, study deleted) ends a study only if it keeps coming for more than three days (`Study.unauthorised`); any answer of the server clears it.
  429 (`limit`) and 5xx never change a study.
  Leaving without a connection marks the study as left at once and sends `/leave` at the next sync (`leavePending`).
- **Deleting answers.**
  "Delete my answers" on every study card calls `POST /api/diary/v1/erase`: the server deletes all answers of this person in this study and the person; the app then removes the study.
  Deleting or removing a study from the list also deletes its queued answers, its kept pages and the log of its prompts.
- **What stays on the phone.**
  The studies with their secrets; the log of the prompts of the last three days (notified, opened); the kept pages of offline modules while the study runs (dropped when it ends, is left, or the module is no longer offline); answers given without a connection until they are sent, at most 7 days after the page was opened (the server takes them no later), then they are dropped unsent.
  All of it in the app's private storage, in no backup.
- **Languages.**
  The survey languages of Askurious: English, German, Spanish, French, Italian, Dutch, Polish, European Portuguese, Russian, Turkish, Simplified Chinese and Arabic (right to left), formal address where the language has it.
  `res/xml/locales_config.xml` lists them for the per-app language setting (Android 13+).
- **About the app.**
  The (i) in the top bar: what the app stores, the privacy statement (https://askurious.de/privacy#tagebuch), the copyright and the licences of the libraries.

## Build and run

Open the folder in Android Studio and press Run, or from a terminal (the Gradle of this project needs Android Studio's JDK):

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest assembleDebug
```

Toolchain: Gradle 9.5.1, AGP 9.3.3 (with the compatibility flags of the upgrade assistant in gradle.properties, still `android.newDsl=false`), Kotlin 2.2.20, compileSdk and targetSdk 36, minSdk 26.
A release build is shrunk with R8 (`proguard-rules.pro` keeps the WebView bridge `EntryActivity.Bridge`).
Checks: `./gradlew testDebugUnitTest lintDebug`.

A **debug build** talks to `http://localhost:5060`, the Flask server on the computer (`.venv/bin/python run.py` in the Askurious repository).
Run `adb reverse tcp:5060 tcp:5060` once per start of the emulator, so that its `localhost:5060` is the computer's: the server answers only under `localhost` while it runs with the development key.
A **release build** talks to `https://ask.askurious.de`, the participants' address.

## Trying it on the emulator

1. In Askurious: My surveys › + Diary survey; check the tab "Schedule"; start data collection.
2. In the app: Join study › type the join code from the tab "Schedule".
3. Answer the entry part; the study shows "Starts on …" (or "Day 1 of 14" with the start "on the day of the entry part").
4. To see a prompt at once, set a module's time to a few minutes from now and bring the app to the front again (it syncs whenever it opens).

## App Links

The QR code holds `https://ask.askurious.de/j/<code>`; only the participants' address `ask.askurious.de` is an App Link host (it serves `/j/` and `/.well-known/assetlinks.json`), not `askurious.de`.
Test join codes have one hyphen (`k7qm2x-t3f9a`); the app keeps it.
For the phone to open the app directly, the server must answer `/.well-known/assetlinks.json` with the SHA-256 fingerprint of the signing key: set `DIARY_APP_FINGERPRINTS` on the server (`keytool -list -v -keystore <keystore>`).
Without it the link opens the join page in the browser, which shows the join code.

## Not yet

Random prompts within time windows, an exit part, the entry part without a connection, a release signing configuration, iOS (concept, section 10).

## Licence

© 2026 Philipp Brauner, RWTH Aachen University.
No open-source licence has been chosen for the code yet; until then all rights are reserved (`LICENSE`).
The libraries and their licences: `THIRD_PARTY_NOTICES.md`.
