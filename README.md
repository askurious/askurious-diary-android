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
- **Permissions.**
  Without notifications no prompt arrives, and without "Alarms & reminders" it may come late: the start page shows a notice for each right below its heading, read again whenever the app comes back to the front (from the system settings, the dialog, an entry).
  Once the system no longer shows its dialog (denied twice), or the permission is there but the app's notifications or the channel of the prompts are switched off, the notice's button is "Allow in settings" and opens the app's notification settings (`notifyNotice`).
  While notifications are off, a running study's card says "Notifications off – you will not get any questions" instead of the time of the next question.
- **Lock screen.**
  Notifications are private: the lock screen shows only "Askurious – A short question is waiting", never the title of the study or the researchers' notification text.
- **Thin shell.**
  The questionnaire pages are the participant pages of Askurious in a WebView (`EntryActivity`), opened with a one-time address per entry.
  A release build opens only addresses of its own server (`Api.absolute`); the bridge `AskuriousApp` counts only while the page is the server's (or the kept page under its address); the WebView reaches no files or content providers of the phone, autofill stays out, and the entry screen allows no screenshots (`FLAG_SECURE`).
  An open entry is listed in "Recents" as its own task, so that it survives a switch to another app, but without a picture of the page (`setRecentsScreenshotEnabled(false)`, `FLAG_SECURE`).
  Links to other sites open in another app (web, mail and phone links only); turning the phone keeps the page and what was typed.
  The app itself does the study list, joining, the schedule, the notifications and the sync.
- **Schedule on the phone.**
  `Schedule.kt` computes the prompts of the fixed and random modules in local time; the server computes the same times for the export and to check that a prompt was due (`diary.prompts`).
  One exact alarm at a time (`Alarms.kt`), set again after a reboot, an update, or a change of time or time zone.
  No push service: `SyncWorker` fetches each study every 6 hours.
- **Random prompts.**
  A module with the trigger `{"t": "random", "per_day", "windows", "min_gap", "weekdays"}` is prompted like a fixed one (notification, expiry, reminder, answering without a connection), but its times are drawn per person and day (`RandomTimes.kt`): SplitMix64 over a state from the person's `seed` (sent by `POST /study`), the module key (FNV-1a 64) and the day, in unsigned 64-bit arithmetic, bit for bit the server's `diary.random_times`.
  The free minutes of the day (the windows without the quiet hours) are cut into `per_day` equal slots, each draws one minute at least `min_gap`/2 from its edges.
  All times are local times of that calendar day: a window 20:00–02:00 gives early-morning times of the same day.
  `app/src/test/resources/diary_random_vectors.json` is the server's `tests/diary_random_vectors.json`, unchanged; `RandomTimesTest` checks every case.
- **Quiet hours.**
  A running study with a random module shows its quiet hours on the study card ("Quiet hours", from/to or none); they keep the random prompts out of that time, never the fixed ones.
  `POST /study` sends a change (`"quiet": ["22:00", "07:00"]` or `null`), the server keeps it from the person's next day on and answers with the whole history (`"quiet": [{"from", "q"}]`); the app uses exactly that history, the entry of the last `from` on or before a day (`QuietFrom.on`, the server's `quiet_on`).
  Without a connection the change waits as `quietPending` (valid from the day after it was set, by the server's rule) and goes with the next sync.
- **Reminder.**
  A fixed or random module with `reminder` > 0 gets a second notification that many minutes after the prompt, from the same one alarm (`Schedule.nextMoment` takes the earlier of the next prompt and the next reminder).
  It is posted only if the prompt is still open and was neither opened nor answered without a connection (`PromptLog`, a list of the prompts of the last three days on the phone), under the prompt's notification id (it replaces the first one) and with the same neutral lock-screen version ("Reminder: a short question is still waiting").
  Tapping it sends `"reminded": true` with the entry, and the time of the first notification as `notified`.
- **Answering without a connection.**
  For a module with `offline: true` each sync of a running study fetches its page (`POST /offline/<module>`, at most every 15 minutes) and the `/static/` and `/media/` files it names, also those named by its style sheets (`OfflinePages`, in the app's files directory, at most 10 MB per file).
  When an entry finds no network, a prompt still open or a self-started module under its daily limit opens the kept page in the WebView; the page's own files come from the phone, any other request fails.
  `participant.js` hands the filled form to `AskuriousApp.offlineSubmit()`, and the app queues it (`AnswerQueue`) and says it will be sent.
  The next sync, and a one-time WorkManager job that waits for a connection, sends it with `POST /offline`; the study card shows how many answers are waiting.
  Each answer goes with its `"uuid"` (made when it was queued): the server stores an answer that arrives twice (a timeout after it was stored, two syncs) only once and answers as the first time.
  Only one sync runs at a time in the app (`Sync`, one lock for the sync on opening, the periodic one, the one after an answer and the one after an entry).
  An answer leaves the queue when the server stored it or refused it for good (answered already, questionnaire changed, expired, unknown); without a network, at 429 or 5xx it stays.
  409 `daily_limit` (today's limit of a self-started report was reached, also by answers sent online the phone does not know of) is final: the answer goes and the study card says so once.
  A 401 or 409 `paused` does not drop it: it waits for the next regular sync, with the same three days of grace as the study itself (`AnswerQueue.afterRefusal`).
  If the server refuses an answer while its clock and the phone's differ by more than five minutes (from the `Date` header of its answers), the answer stays and the app asks to set the phone's clock right.
  The entry part is always answered online.
- **Paused by the researchers.**
  `POST /study` may answer `"status": "paused"`: the card says "Paused by the researchers", there are no prompts or reminders, and the self-started modules are hidden; leaving stays possible.
  `"pauses": [["2026-10-25", "2026-10-27"], ["2026-11-02", null]]` lists the paused days (local dates, both included; `null` = still paused): no prompt, fixed or random, on those days, also after the person is active again (`Study.pausedOn`, `Schedule.prompts`).
  `"ended"` can also mean the researchers ended this person's study early; the card says the same "The researchers have ended this study".
- **Exit questionnaire.**
  `"exit": {"name", "open", "until"}` (or `null`): the server sets `open` after the person's study period is over (finished or ended), until `until`, and only while it is not answered.
  While it is open the card shows a button with its name (default "Final survey", German "Abschlussbefragung"), which opens the entry with `POST /entry {"module": "exit"}` (no `due`; always online, never from a kept page).
  One notification at 10:00 local time on the day after `last_day` (`Schedule.exitAt`), or at the next sync if that moment has passed and it is still open; `PromptLog` remembers that it was notified (or opened from the card), so it comes only once.
  A finished or ended study with an exit questionnaire is synced until it is answered or `until` has passed (`Sync.wanted`); once answered the server sends `open: false` and the button goes.
- **Fast-forward test persons.**
  `"clock": {"t0", "factor"}` (or `null`) marks a test person for whom one study day passes in 24 h / `factor` of real time (×144: 10 minutes).
  Everything the app computes for such a study is in the virtual time v = t0 + (real − t0) · factor (`Clock.virtual`, `Study.now`, `Study.today`): dates, first and last day, day index, fixed and random prompt times, quiet hours, weekdays, expiry and reminder minutes, the exit notification, and "Next question" and "Day x of n" on the card; the zone stays the phone's.
  The one alarm rings at the real moment t0 + (v − t0) / factor, rounded up (`Clock.real`, `Prompt.ring`).
  Every time sent to the server (`due`, `notified`, offline `started` and `completed`) is virtual epoch milliseconds.
  The card shows a small "Fast-forward ×144" badge. Studies without a clock are unchanged.
- **Server answers.**
  A 401 (`unauthorised`: the server does not know the secret, study deleted) ends a study only if it keeps coming for more than three days (`Study.unauthorised`); any answer of the server clears it.
  429 (`limit`, the rate limit) and 5xx never change a study; 409 `daily_limit` is today's limit of a self-started report.
  Leaving marks the study as left at once, also while a sync runs (a sync that ends afterwards keeps it, `Sync.merged`), and sends `/leave` then or at the next sync (`leavePending`).
  An answer of the server the app cannot read (a date or a module of a newer kind) costs only that part, never the app; damaged storage is skipped, not crashed on.
  Bringing the app to the front syncs at most every five minutes and never while a sync runs (`Sync.allIfDue`).
- **Joining again.**
  Joining a study that is still on the phone (after leaving it, or with a test or invitation code) asks first: a new participation gets a new secret, and the earlier answers can then no longer be deleted in the app.
  If the participant goes on, the earlier participation is closed with its own secret (its waiting answers are sent, a running one is left) and then removed from the phone (`Sync.rejoin`): nothing of it goes to the new person.
- **Deleting answers.**
  "Delete my answers" on every study card calls `POST /api/diary/v1/erase`: the server deletes all answers of this person in this study and the person; the app then removes the study.
  Deleting or removing a study from the list also deletes its queued answers, its kept pages and the log of its prompts.
  "Remove from list" asks first: with the study its secret goes, so its answers can no longer be deleted through the app; the question says so, and says when the exit questionnaire is still open.
- **What stays on the phone.**
  The studies with their secrets, the seed of the random prompts, the quiet hours, the pauses, the exit questionnaire and a fast-forward clock; the log of the prompts of the last three days (notified, opened) and whether the exit questionnaire was notified; the kept pages of offline modules while the study runs (dropped when it ends, is left, or the module is no longer offline); answers given without a connection until they are sent, at most 7 days after the page was opened (the server takes them no later), then they are dropped unsent.
  Besides, whether the app asked for the notification permission once (to tell a first request from a refusal for good).
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
The wrapper checks the SHA-256 of the Gradle download (`distributionSha256Sum`). Compose BOM 2026.06.01 is the newest that builds with compileSdk 36 (Compose UI 1.12 from BOM 2026.08.00 on needs compileSdk 37).
Not yet: `gradle/verification-metadata.xml` (`./gradlew --write-verification-metadata sha256 help` on a machine with Gradle), and a check of the transitive versions (`./gradlew :app:dependencies`) before the store upload.
A release build is shrunk with R8 (`proguard-rules.pro` keeps the WebView bridge `EntryActivity.Bridge`).
Checks: `./gradlew testDebugUnitTest lintDebug`.

A **debug build** talks to `http://localhost:5060`, the Flask server on the computer (`.venv/bin/python run.py` in the Askurious repository).
Run `adb reverse tcp:5060 tcp:5060` once per start of the emulator, so that its `localhost:5060` is the computer's: the server answers only under `localhost` while it runs with the development key.
A **release build** talks to `https://ask.askurious.de`, the participants' address.

## Trying it on the emulator

1. In Askurious: My surveys › + Diary survey; check the tab "Schedule"; start data collection.
2. In the app: Join study › type the join code from the tab "Schedule".
3. Answer the entry part; the study shows "Starts on …" (or "Day 1 of 14" with the start "on the day of the entry part").
4. To see a prompt at once, set a module's time to a few minutes from now and bring the app to the front again (it syncs when it opens, at most every five minutes).

## App Links

The QR code holds `https://ask.askurious.de/j/<code>`; only the participants' address `ask.askurious.de` is an App Link host (it serves `/j/` and `/.well-known/assetlinks.json`), not `askurious.de`.
Test join codes (`k7qm2x-t3f9a`, five characters after the hyphen) and personal invitation codes (`k7qm2x-a8c3d9ef`, eight lowercase letters or digits) have one hyphen; the app keeps it.
For the phone to open the app directly, the server must answer `/.well-known/assetlinks.json` with the SHA-256 fingerprint of the signing key: set `DIARY_APP_FINGERPRINTS` on the server (`keytool -list -v -keystore <keystore>`).
Without it the link opens the join page in the browser, which shows the join code.

## Not yet

The entry part without a connection, a release signing configuration, iOS (concept, section 10).

## Licence

© 2026 Philipp Brauner, RWTH Aachen University.
No open-source licence has been chosen for the code yet; until then all rights are reserved (`LICENSE`).
The libraries and their licences: `THIRD_PARTY_NOTICES.md`.
