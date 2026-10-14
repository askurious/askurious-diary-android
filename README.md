# Askurious diary app (Android)

The app for diary surveys of [Askurious](https://askurious.de) (German: Tagebuchstudie; experience sampling).
Participants join a study with its QR code or join code, answer the entry part once, and then get the prompts of the study's modules as notifications, or start a report themselves.
Concept: `concepts/experiencesampling.md` in the Askurious repository; server side: `app/diary.py`, `app/diary_api.py`.

## How it works

- **No account.**
  Joining returns a random secret per study; the app keeps it in its private storage (no backup) and sends it as a bearer token.
  The server keeps only its hash, and the app sends no device id, so two studies on one phone cannot be linked.
  Nothing of the app goes into cloud backups or to a new phone (`allowBackup="false"`, `res/xml/data_extraction_rules.xml`).
- **Only servers of Askurious.**
  A join link (QR code, App Link, a link another app hands over) names its server, but the app asks only a server of Askurious or of one of its brands: `askurious.de`, `ask.askurious.de`, `polltergeist.de`, `ask.polltergeist.de` (`KNOWN_SERVERS` in app/build.gradle.kts, `Api.isKnown`; a debug build also the Flask server on the computer).
  The link of any other server is refused with a message naming it, before that server is asked anything; a new brand or instance is added to the list in the build.
- **No shared cookies.**
  Each entry starts with empty WebView cookies, web storage and cache (`EntryActivity`), so the session cookie of one study never travels with the entry of another.
  They are emptied again when the entry ends (end page, answer saved without a connection) and when its screen goes, and the WebView is destroyed with it, so the session cookie does not stay on the phone after the entry.
- **Permissions.**
  Without notifications no prompt arrives, and without "Alarms & reminders" it may come late: the start page shows a notice for each right below its heading, read again whenever the app comes back to the front (from the system settings, the dialog, an entry).
  Once the system no longer shows its dialog (denied twice), or the permission is there but the app's notifications or the channel of the prompts are switched off, the notice's button is "Allow in settings" and opens the app's notification settings (`notifyNotice`).
  Both notices stand in one block in the error colours above the list, gone while all is well (`Readiness`). After the first join (once per phone), a step "So that questions arrive on time" explains both permissions and asks for one after the other.
  While notifications are off, a running study's card says "Notifications off – you will not get any questions" instead of the time of the next question; while "Alarms & reminders" is off, it says "about" before that time (Android may ring a few minutes late).
  The camera (QR code) is asked for by the app itself before the scanner opens; refused, the join screen points to the join code, and once the system shows no dialog any more the button opens the app's settings.
- **Lock screen.**
  Notifications are private: the lock screen shows only "Askurious – A short question is waiting", never the title of the study or the researchers' notification text.
- **Thin shell.**
  The questionnaire pages are the participant pages of Askurious in a WebView (`EntryActivity`), opened with an address the server makes for each entry (the server accepts it for a few hours, `ENTRY_SECONDS`).
  A release build opens only addresses of its own server (`Api.absolute`); the bridge `AskuriousApp` counts only while the page is the server's (or the kept page under its address); the WebView reaches no files or content providers of the phone, autofill stays out, and the entry screen allows no screenshots (`FLAG_SECURE`).
  On the server's pages the bridge is a message channel that only the main frame of the study's server reaches (`WebViewCompat.addWebMessageListener` with that origin; a script at the start of each page offers participant.js the same `window.AskuriousApp.done()` and `.offlineSubmit(json)`); a WebView too old for it, and the kept page (which loads nothing from any other origin), use `addJavascriptInterface` as before.
  The start page, a study's page and the join screen show titles of studies: from Android 13 they leave no picture in "Recents" (`setRecentsScreenshotEnabled(false)`, screenshots stay possible), before that `FLAG_SECURE`; "About" and the licence texts keep theirs.
  "Join" and the questions before leaving, removing or deleting take a touch only while no window of another app lies over them (`filterTouchesWhenObscured`, `GuardAgainstOverlays`; Android 12 and later block such overlays themselves).
  An open entry is listed in "Recents" as its own task, so that it survives a switch to another app, but without a picture of the page (`setRecentsScreenshotEnabled(false)`, `FLAG_SECURE`).
  Links to other sites open in another app (web, mail and phone links only); turning the phone keeps the page and what was typed.
  The back gesture goes one page back in the questionnaire; on its first page, and with the X at the left of the top bar, the app asks "Cancel this entry?" first.
  A bar under the top bar shows a page loading; a page that does not load (no connection on the way) shows the app's own message with "Try again" instead of the browser's error page, and the first page of a module that can be answered offline falls back to the kept page.
  The notification of a prompt stays until its entry has loaded (it is not cleared by the tap), so an entry that failed for want of a connection can be opened from it again; an error that may pass ("No connection", a fault of the server) offers "Try again".
  The app itself does the study list, joining, the schedule, the notifications and the sync.
- **Look of the study.**
  The server sends the colours of a study's participant pages with the study and on the join screen (`"look": {"colors", "colors_dark", "radius"}`, server `diary.look_json`).
  The study card, the bar above an entry (and the screen before its page has loaded) and the join screen, once the study is found, take them (`StudyTheme`): page colour, text, accent, border, corners.
  A look that follows the device has a dark palette (`colors_dark`), shown while the phone is dark; a fixed look stays the same, as its pages do.
  The status bar takes light or dark icons for the look's background. Without a look (an older server) the app shows its own look; the rest of the app (start page, notices, about) always does.
  The app's own look is the standard look of the participant pages, light and dark (`Look.DEFAULT`, the values the server's `look_json` gives for "askurious" and "askurious-dark"); every colour role of Material 3 is set from a look's colours (`StudyTheme`), so no purple default shows, and the window and the splash screen take the page colour (`res/values*/colors.xml`, `themes.xml`).
- **Screens.**
  The start page lists the studies that still ask something (running, not yet begun, paused, an open exit questionnaire) and below them, under "Ended", the others; each card shows the title, the state with an icon, the next question ("today, 09:40", "tomorrow, 08:15" or the date, in the phone's 12 or 24 h format; "about" without exact alarms) and what to do now.
  A day after the study period while the server still says "active" shows "Study period over – updating …", never "Day 17 of 14"; a finished study with an open exit questionnaire asks for it instead of thanking (`StudyStatus.kt`).
  Tapping a card opens the study's page: study period, what is asked, quiet hours, waiting answers, and apart from the rest leaving or removing and deleting the answers, each with a question first and a short message afterwards.
  The screens (start page, study page, joining, the permission step, about, licence texts) lie on a back stack saved with the activity (`MainActivity.App`): the back gesture goes one screen back, and turning the phone or a change of language or dark mode keeps the screen and what was typed.
  "Join study" is the floating button of the start page; every other screen has a back arrow at the left of its top bar.
- **Schedule on the phone.**
  `Schedule.kt` computes the prompts of the fixed and random modules in local time; the server computes the same times for the export and to check that a prompt was due (`diary.prompts`).
  One exact alarm at a time (`Alarms.kt`), set again after a reboot, an update, or a change of time or time zone.
  No push service: `SyncWorker` fetches each study every 6 hours.
- **Random prompts.**
  A module with the trigger `{"t": "random", "per_day", "windows", "min_gap", "weekdays"}` is prompted like a fixed one (notification, expiry, reminder, answering without a connection), but its times are drawn per person and day (`RandomTimes.kt`): SplitMix64 over a state from the person's `seed` (sent by `POST /study`), the module key (FNV-1a 64) and the day, in unsigned 64-bit arithmetic, bit for bit the server's `diary.random_times`.
  The free minutes of the day (the windows without the quiet hours) are cut into `per_day` equal slots, each draws one minute at least `min_gap`/2 from its edges.
  All times are local times of that calendar day: a window 20:00–02:00 gives early-morning times of the same day.
  `app/src/test/resources/diary_random_vectors.json` is the server's `tests/diary_random_vectors.json`, unchanged; `RandomTimesTest` checks every case.
- **The same prompts as the server.**
  The server checks with its own list (`diary.prompts`) that a prompt was due, so the app's `Schedule.prompts` must give exactly that list.
  `app/src/test/resources/diary_schedule_vectors.json` is the server's `tests/diary_schedule_vectors.json`, unchanged: studies as `POST /study` sends them (fixed and random modules, weekdays, days of a module, quiet hours changing during the study, pauses, both changes of summer time, a zone far from Berlin) with the prompts the server computes for them; `ScheduleVectorsTest` checks every case.
  When the server's rule changes, it regenerates the file (`python tests/test_diary_app_contract.py --update`), and the copy here and `Schedule.kt` change with it; the server's test compares both copies when this repository lies next to it.
- **Update request.**
  `POST /study` sends `"min_app_version"` (the server's `DIARY_MIN_APP_VERSION`, 0 = every version).
  When a study asks for a newer version than the running `versionCode`, the start page asks the participant to update the app (button to its Play Store page); the app goes on working meanwhile.
  `versionCode` is the number of Git commits up to the build (`git rev-list --count HEAD`), `versionName` "0.1.<that number>", so every later build on main is newer.
- **Summary, study page.**
  The card shows below the title the researchers' short summary (`"summary"` of `POST /study`), the day, the next question and the buttons to answer (first questionnaire, reports, exit questionnaire).
  Tapping a card opens the study's page: the summary, the study period (start and end, or "open, until the researchers end the study"), what is asked, the next question, the quiet hours, the notification sound, a home-screen shortcut and leaving or deleting.
  Leaving asks first; once left, the app asks whether the answers should be deleted too, says kindly that keeping them still helps the research, and offers "Keep my answers" as the first choice.
- **Open-ended studies.**
  `"open_end": true` with `"last_day": null`: prompts go on until the researchers end the study (`Schedule.prompts`); the card shows "Day x" instead of "Day x of n", the join screen "Duration: open-ended" (`public_json` sends `"open_end": true`, `"period_days": 0`).
- **Reporting at any time.**
  A self module (`"t": "self"`) and a fixed or random module with `"self": {"label", "max_per_day"}` (prompted and also started by the participant) get a button on the card while their days run (`Module.selfTrigger`, `Study.selfModules`); a self-started entry goes without `due`, and the daily limit counts only self-started answers.
- **Home-screen shortcut.**
  For a running study with a module the participant starts, the study's page offers "Add to home screen" (`Shortcuts.kt`, a pinned shortcut).
  The home screen is seen by others: the label is the report's label by default, never the study's title, and the participant can change it before adding it.
  The shortcut opens the app with `de.askurious.app.action.REPORT`; the app reads the study from its sealed store off the main thread, and the entry starts only while the study runs and the module may be started today (at the study's own server, as any entry), otherwise the app opens on the study's page.
  When a study is left, ended, finished, removed or its answers deleted, its shortcuts are disabled (the app cannot take a pinned shortcut off the home screen).
- **Notification sound.**
  On by default, switched off per study on the study's page; kept only on the phone (`Study.sound`, never sent, kept over a sync by `Sync.merged`).
  The sound of a notification channel cannot change once it exists, so the choice picks the channel: `prompts` (the phone's notification sound) or `prompts_silent`.
- **Quiet hours.**
  A study with a random module shows its quiet hours on its page; "Change quiet hours" opens a dialog with the switch "Use quiet hours", from and to, saved with "Save". They keep the random prompts out of that time, never the fixed ones.
  `POST /study` sends a change (`"quiet": ["22:00", "07:00"]` or `null`), the server keeps it from the person's next day on and answers with the whole history (`"quiet": [{"from", "q"}]`); the app uses exactly that history, the entry of the last `from` on or before a day (`QuietFrom.on`, the server's `quiet_on`).
  Without a connection the change waits as `quietPending` (valid from the day after it was set, by the server's rule) and goes with the next sync.
- **Reminder.**
  A fixed or random module with `reminder` > 0 gets a second notification that many minutes after the prompt, from the same one alarm (`Schedule.nextMoment` takes the earlier of the next prompt and the next reminder).
  It is posted only if the prompt is still open and was neither opened nor answered without a connection (`PromptLog`, a list of the prompts of the last three days on the phone), under the prompt's notification id (it replaces the first one) and with the same neutral lock-screen version ("Reminder: a short question is still waiting"); unlocked, it shows the researchers' text after "Reminder:" (or that neutral text when they wrote none).
  Tapping it sends `"reminded": true` with the entry, and the time of the first notification as `notified`.
- **Answering without a connection.**
  For a module with `offline: true` each sync of a running study fetches its page (`POST /offline/<module>`, at most every 15 minutes) and the `/static/` and `/media/` files it names, also those named by its style sheets (`OfflinePages`, in the app's files directory, at most 10 MB per file).
  When an entry finds no network, a prompt still open or a self-started module under its daily limit opens the kept page in the WebView; the page's own files come from the phone, any other request fails.
  `participant.js` hands the filled form to `AskuriousApp.offlineSubmit()`, and the app queues it (`AnswerQueue`) and says it will be sent.
  The next sync, and a one-time WorkManager job that waits for a connection, sends it with `POST /offline`; the study card shows how many answers are waiting.
  Each answer goes with its `"uuid"` (made when it was queued): the server stores an answer that arrives twice (a timeout after it was stored, two syncs) only once and answers as the first time.
  Only one sync runs at a time in the app (`Sync`, one lock for the sync on opening, the periodic one, the one after an answer and the one after an entry).
  An answer leaves the queue only after the server confirms `stored` or `review`, or the participant explicitly removes it. Refusals and expired answers stay encrypted on the phone for clarification; transient failures are retried.
  A `daily_limit` refusal retains the original for clarification and the study page says so.
  A 401 or 409 `paused` retains the answer indefinitely and waits for the next regular sync (`AnswerQueue.afterRefusal`).
  If the server refuses an answer while its clock and the phone's differ by more than five minutes (from the `Date` header of its answers), the answer stays and the app asks to set the phone's clock right.
  Offline queue changes use checked synchronous disk commits on an I/O dispatcher before showing “Saved”. A failed write keeps the filled questionnaire open; unreadable queues cannot be overwritten by later answers. A submission identifier survives activity recreation to make a repeated local save idempotent.
  The entry part is always answered online.
- **Paused by the researchers.**
  `POST /study` may answer `"status": "paused"`: the card says "Paused by the researchers", there are no prompts or reminders, and the self-started modules are hidden; leaving stays possible.
  `"pauses": [["2026-10-25", "2026-10-27"], ["2026-11-02", null]]` lists the paused days (local dates, both included; `null` = still paused): no prompt, fixed or random, on those days, also after the person is active again (`Study.pausedOn`, `Schedule.prompts`).
  `"ended"` can also mean the researchers ended this person's study early; the card says the same "The researchers have ended this study".
- **Exit questionnaire.**
  `"exit": {"name", "open", "until"}` (or `null`): the server sets `open` after the person's study period is over (finished or ended), until `until`, and only while it is not answered.
  While it is open the card shows a button with its name (default "Final survey", German "Abschlussbefragung"), which opens the entry with `POST /entry {"module": "exit"}` (no `due`; always online, never from a kept page).
  Its notification names the last day it can be answered when the server sends `until`.
  One notification at 10:00 local time on the day after `last_day` (`Schedule.exitAt`), or at the next sync if that moment has passed and it is still open; `PromptLog` remembers that it was notified (or opened from the card), so it comes only once.
  A finished or ended study with an exit questionnaire is synced until it is answered or `until` has passed (`Sync.wanted`); once answered the server sends `open: false` and the button goes.
- **Fast-forward test persons.**
  `"clock": {"t0", "factor"}` (or `null`) marks a test person for whom one study day passes in 24 h / `factor` of real time (×144: 10 minutes).
  Everything the app computes for such a study is in the virtual time v = t0 + (real − t0) · factor (`Clock.virtual`, `Study.now`, `Study.today`): dates, first and last day, day index, fixed and random prompt times, quiet hours, weekdays, expiry and reminder minutes, the exit notification, and "Next question" and "Day x of n" on the card; the zone stays the phone's.
  The one alarm rings at the real moment t0 + (v − t0) / factor, rounded up (`Clock.real`, `Prompt.ring`).
  Every time sent to the server (`due`, `notified`, offline `started` and `completed`) is virtual epoch milliseconds.
  The card shows a small neutral "Fast-forward ×144" badge (TalkBack: "Fast-forward, 144 times"). Studies without a clock are unchanged.
- **Server answers.**
  A 401 (`unauthorised`: the server does not know the secret, study deleted) ends a study only if it keeps coming for more than three days (`Study.unauthorised`); any answer of the server clears it.
  429 (`limit`, the rate limit) and 5xx never change a study; 409 `daily_limit` is today's limit of a self-started report.
  Leaving marks the study as left at once, also while a sync runs (a sync that ends afterwards keeps it, `Sync.merged`), and sends `/leave` then or at the next sync (`leavePending`).
  An answer of the server the app cannot read (a date or a module of a newer kind) costs only that part, never the app; damaged storage is skipped, not crashed on.
  Bringing the app to the front syncs at most every five minutes and never while a sync runs (`Sync.allIfDue`).
- **Joining again.**
  Joining a study that is still on the phone (after leaving it, or with a test or invitation code) asks first: a new participation gets a new secret, and the earlier answers can then no longer be deleted in the app.
  If the participant goes on, the earlier participation is closed with its own secret (its waiting answers are sent, a running one is left) and then removed from the phone (`Sync.rejoin`): nothing of it goes to the new person.
  A personal code someone already joined with is different: the server answers `"rejoin": true` and connects this phone to that same participation with a new secret (the phone used so far loses access); the app asks nothing, and opens the entry questionnaire only while it is not done.
  Whether such a code connects at any time or only after the researchers released it is set on the server; otherwise it answers `"used"`, and the app says to ask the research team.
- **Deleting answers.**
  "Delete my answers" on the page of every study (beside "Leave study" or "Remove from list") calls `POST /api/diary/v1/erase`: the server deletes all answers of this person in this study and the person; the app then removes the study.
  Deleting or removing a study from the list also deletes its queued answers, its kept pages and the log of its prompts.
  "Remove from list" asks first: with the study its secret goes, so its answers can no longer be deleted through the app; the question says so, and says when the exit questionnaire is still open.
- **What stays on the phone.**
  The studies with their secrets, their look (colours), the seed of the random prompts, the quiet hours, the pauses, the exit questionnaire, a fast-forward clock and whether its notifications ring with a sound; the labels of home-screen shortcuts are kept by the launcher; the log of the prompts of the last three days (notified, opened) and whether the exit questionnaire was notified; the kept pages of offline modules while the study runs (dropped when it ends, is left, or the module is no longer offline); answers given without a connection until the server confirms storage or the participant explicitly removes them; expiry and refusal never delete the original.
  Besides, whether the app asked for the notification and the camera permission once (to tell a first request from a refusal for good), and whether the step for the permissions was shown.
  All of it in the app's private storage, in no backup.
  The list of studies (with titles, schedules and secrets), the answers waiting to be sent and the log of the prompts are stored sealed (AES-GCM, `SealedText`, each with its own purpose as associated data) under a key that lives in the Android Keystore and never leaves it (`KeystoreSealer`); a copy of the app's files alone shows neither secrets nor answers nor titles. Each is opened once per process and kept in memory, so the Keystore is not asked at every read; reading them at the start, for an alarm or a boot runs off the main thread.
  What an earlier version stored (the list with each secret sealed on its own, the queue and the log in plain text) is sealed the first time it is read. An entry whose secret cannot be opened (the Keystore lost its key) is kept as it is, not overwritten. The kept questionnaire pages and their files stay as files: they hold the questions, not the answers.
- **Languages.**
  The survey languages of Askurious: English, German, Spanish, French, Italian, Dutch, Polish, European Portuguese, Russian, Turkish, Simplified Chinese and Arabic (right to left), formal address where the language has it, except German: the German texts address nobody directly (neither "Sie" nor "du"), because the start page shows studies whose pages may use either.
  `res/xml/locales_config.xml` lists them for the per-app language setting (Android 13+).
- **About the app.**
  The (i) in the top bar: what the app stores, the privacy statement (https://askurious.de/privacy#tagebuch), the provider as named in the imprint of askurious.de with a link to it (https://askurious.de/imprint), the copyright, the libraries, and their licence texts on a page of their own (without the hard line breaks of the text files).

## Build and run

Open the folder in Android Studio and press Run, or from a terminal (the Gradle of this project needs Android Studio's JDK):

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest assembleDebug
```

Toolchain: Gradle 9.6.0, AGP 9.4.1 (with the compatibility flags of the upgrade assistant in gradle.properties, still `android.newDsl=false`), Kotlin 2.2.20, compileSdk and targetSdk 36, minSdk 26.
The wrapper checks the SHA-256 of the Gradle download (`distributionSha256Sum`). Compose BOM 2026.06.01 is the newest that builds with compileSdk 36 (Compose UI 1.12 from BOM 2026.08.00 on needs compileSdk 37).
Gradle checks the SHA-256 of every dependency against `gradle/verification-metadata.xml`. After changing a dependency, write it again with the tasks of a release:
`./gradlew --write-verification-metadata sha256 help lintDebug lintRelease testDebugUnitTest assembleDebug assembleRelease bundleRelease`, and look at what changed before committing it.
The jars of aapt2 for Linux and macOS are trusted without a checksum (the file was written on Windows), as are sources and javadoc for the IDE.
The QR scanner is ZXing Android Embedded 4.3.0 (in maintenance, no release since 2021) with the decoder `com.google.zxing:core` raised to 3.5.4, its last release; a scanner on CameraX with ML Kit would need Google's ML Kit terms and a new screen, so it stays for now.
Not yet: a check of the transitive versions against a vulnerability database (`./gradlew :app:dependencies`, OSV) before the store upload.
A release build is shrunk with R8 (`proguard-rules.pro` keeps the WebView bridge `EntryActivity.Bridge`).
Checks: `./gradlew testDebugUnitTest lintDebug`.

A **debug build** talks to `http://127.0.0.1:5080`, the Flask server on the computer (`.venv/bin/python run.py` in the Askurious repository).
Run `adb reverse tcp:5080 tcp:5060` once per start of the emulator, so that its `127.0.0.1:5080` is the computer's: the server answers only under `localhost` while it runs with the development key.
A **release build** talks to `https://askurious.de` for a typed join code (the main address, where the diary studies run for now; `BASE_URL` in app/build.gradle.kts), and to the server a QR code names.

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

## Release build

A signed release needs the upload key in `keystore.properties` next to this README (never in Git; `.gitignore` keeps it and `*.jks` out). Copy `keystore.properties.example`, make the key once (Android Studio: Build › Generate Signed Bundle, or the `keytool` line in the example) and keep the key file and its passwords safe outside the repository.
Then `./gradlew bundleRelease` gives `app/build/outputs/bundle/release/app-release.aab` for the Play Console. Use Play App Signing: Google keeps the key the app is signed with, the upload key only uploads and can be replaced if it is lost.
Without `keystore.properties` a release build stays unsigned. The SHA-256 fingerprint for `DIARY_APP_FINGERPRINTS` on the server is the one of the app signing key in the Play Console (Setup › App signing), not the upload key's.
`versionCode` comes from Git (see "Update request"), so build releases from a clone with its history, not from a source archive.

## Not yet

The entry part without a connection, iOS (concept, section 10), an open-source licence (a decision of the copyright holder).

## Licence

© 2026 Philipp Brauner, RWTH Aachen University.
No open-source licence has been chosen for the code yet; until then all rights are reserved (`LICENSE`).
The libraries and their licences: `THIRD_PARTY_NOTICES.md`.


## Android UI audit: open questionnaires and interruptions

The home screen offers still-open scheduled questionnaires independently of notification permissions; opened
notifications are not counted as completed answers. Confirmed prompt keys and study contact are provided by the
diary API. Study details retain the purpose, contact, period and participation controls.

Removal warns about unsent answers as well as loss of access, and offers transferring answers first. A transfer
view shows individual outstanding answers, last attempts and the last twenty server confirmations per study.
Confirmation metadata is encrypted with the same Keystore mechanism as the existing local study/answer stores.
Headers grow with system text size, dialogs scroll and actions wrap. Native palette roles enforce readable text
and controls; the study's existing fixed/automatic palette choice and shape are retained.

A retained application-context WebView preserves live input across Activity recreation. Ordinary form fields
also use a bounded Android saved-state draft; unsubmitted files, recordings and drawings cannot reliably be
recovered after process termination. Closing changed input asks first. The secure origin/main-frame message
channel remains in use for drafts and completion. Microphone/location rights are requested only by questions
from the configured known server, with no background recording/location permission. About/privacy texts explain
local drafts and transfer metadata in all twelve languages.

Debug uses loopback port 5080 because WebView blocks SIP port 5060. Forward `adb reverse tcp:5080 tcp:5060` to
Flask's unchanged server port. `UiInstrumentation` checks dirty close, Activity recreation and saved-state
recovery using synthetic local fixtures; no participant data or external service is needed.
