# Building & internals

Developer notes for Ergon. For installing and using the app, see the [README](README.md).

## Build

The Gradle wrapper is included, so you don't need Gradle installed.
Requirements: JDK 17, Android SDK (platform 35 + build-tools), and `ANDROID_HOME` set
(or a `local.properties` containing `sdk.dir=/path/to/sdk`).

    .\gradlew.bat assembleDebug        (Windows)   |   ./gradlew assembleDebug   (Linux/macOS)
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Updates: the app checks this repo's latest GitHub release. Push a tag like `v1.0.1` and the workflow builds a
signed APK and publishes it as a release; the in-app **Update** button downloads and installs it.

Release signing: create a private key once and keep a backup (losing it means users must reinstall):

    keytool -genkeypair -v -keystore ergon-release.jks -alias ergon -keyalg RSA -keysize 2048 -validity 36500

Then add these repository secrets (Settings > Secrets and variables > Actions): `ERGON_KEYSTORE_BASE64`
(the .jks file, base64-encoded), `ERGON_KEYSTORE_PASSWORD`, `ERGON_KEY_ALIAS`, `ERGON_KEY_PASSWORD`.
Never commit the key. Local builds without these variables use the debug key.

Code style: `./gradlew ktlintCheck` to lint, `./gradlew ktlintFormat` to fix (rules in `.editorconfig`).

No local Gradle/SDK? Push this folder to a GitHub repo and run the **Build APK** workflow
(Actions tab). It uploads `ergon.apk` as an artifact (not zipped), and tagged builds attach it to a release.

## How it works

- **Login:** an in-app WebView signs you in to HoYoLAB (step 1) and the redeem page (step 2). The app
  reads `ltoken_v2`, `ltuid_v2` and `cookie_token_v2` from the WebView cookie jar. Tokens are stored in
  EncryptedSharedPreferences and are only ever sent to HoYoverse endpoints. Popup windows are supported
  (needed for "Sign in with Google"). A one-time disclaimer (Proceed / Exit) is shown before the first sign-in.
- **Check-in:** fixed schedule, daily at 00:30 UTC+8 (30 min after HoYoLAB's reset). A game already
  claimed today (UTC+8 day) is never sent to the server again, including from "Check in now".
- **Gift codes:** checked hourly. A page is only acted on if it changed (If-None-Match /
  If-Modified-Since when Game8 supports it, otherwise a hash of the parsed code list). Every 6 hours
  (or via "Check codes now") a full pass runs. Separately, at least once every ~20 hours one redeem request goes out
  for any game (a real code counts, otherwise a permanent code is used) so `cookie_token_v2` does not expire.
- **Machine Workshop:** the Genshin web event on HoYoLAB. Per the event rules, community missions refresh at
  00:00 UTC+8 and game missions at 04:00 server time (weekly: Monday). The app reads the current event
  version from the event's `index` call, then on every run, per task: status 3 (claimed) is never touched
  again; completion is judged by progress, so progress == total without status 3 is claimed (other statuses, e.g. 2, are not interpreted); anything else gets one `finish_task` attempt per
  period. If the server refuses it, it is marked "needs you" and only its progress is tracked. Runs happen
  30 min after each reset (00:30 and 04:30 UTC+8), as a mandatory claim 15 min before each reset (23:45 and
  03:45 UTC+8), and every 6 hours. "Collect now" also retries tasks marked "needs you". The prize draw and
  the exchange are left to you.
- **Requests:** headers come from this device's real WebView/Chrome (user agent, client hints, Sec-Fetch).
  Cookies, including Cloudflare's `cf_clearance`, are shared between the hidden WebView and OkHttp, so
  once a challenge is solved plain requests usually pass. OkHttp's TLS fingerprint is not Chrome's, so
  the WebView stays as the fallback.
- **Notifications:** sent after a check-in claim or newly redeemed codes, plus an alert when a session
  expires.

## Notes

- Runs started from the app continue only while the app is alive; progress is saved per code. The Home
  screen shows live per-game and per-code progress, and the Activity tab keeps a history of what was claimed.
- Redemption waits 10 s between codes (same as the original script).
- Release builds are unminified. Published releases are signed with your private release key (see above).
