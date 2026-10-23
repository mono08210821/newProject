# Nowmark

Android voice recorder by **Pockivo Studio** (`com.pockivo.nowmark`).
Fully local: no account, no server, no cloud upload. Every feature is free with ads;
a one-time purchase (`nowmark_remove_ads`) removes ads only.

## Build

| Item | Version |
| --- | --- |
| JDK | 17 |
| Android SDK | platform 36, build-tools 36.0.0 |
| minSdk / targetSdk | 26 / 36 |
| AGP / Gradle / Kotlin | 8.12.0 / 8.14.3 / 2.2.0 |

```bash
cd nowmark
./gradlew testDebugUnitTest lintDebug   # unit tests + lint (missing translations are errors)
./gradlew assembleDebug                 # test APK, uses Google test ad units, id com.pockivo.nowmark.debug
./gradlew bundleRelease                 # signed AAB for Play (needs signing, below)
```

CI: `.github/workflows/nowmark-android.yml` runs all of the above on every push to `nowmark/**`
and uploads the APKs, AAB, lint and test reports as the `nowmark-outputs` artifact. It also fails
the build if the release dex contains a Google test ad unit, or lacks the real banner unit.

### Translations

Strings live in `tools/i18n/<lang>.py` (en, ko, ja, zh-CN, es, pt-BR, de, fr, hi, ar).
Edit there, then run `python3 tools/i18n/generate.py`. The script refuses to write if any language
is missing a key or has a mismatched placeholder. CI fails if the generated XML is out of date.
Arabic runs RTL; waveform and range drawing mirror automatically.

## Release signing

The upload key is **not** in git. Either:

* **Local:** put `keystore.properties` next to `settings.gradle.kts`:
  ```properties
  storeFile=nowmark-upload.jks        # relative to nowmark/, or absolute
  storePassword=...
  keyAlias=nowmark-upload
  keyPassword=...
  ```
* **CI secrets:** `NOWMARK_KEYSTORE_BASE64` (base64 of the .jks), `NOWMARK_KEYSTORE_PASSWORD`,
  `NOWMARK_KEY_ALIAS`, `NOWMARK_KEY_PASSWORD`.

Without either, release outputs are unsigned. Enroll in **Play App Signing** at first upload; the
key above is then only the upload key and can be reset through Play support if lost.

Optional: `NOWMARK_PLAY_LICENSE_KEY` (Play Console → Monetize → Monetization setup → licensing
key) enables local signature checks on purchases.

## AdMob and consent

| | Debug | Release |
| --- | --- | --- |
| App ID | Google test | `ca-app-pub-9374713455776239~7250027872` |
| Banner | Google test | `ca-app-pub-9374713455776239/4308791882` |
| Interstitial | Google test | `ca-app-pub-9374713455776239/3966011109` |

Rules enforced in code (`ads/AdsManager.kt`, `core/AdFrequency.kt`):

* UMP consent is gathered on the library screen, never on record or playback screens. Privacy
  options are available in Settings when UMP requires them.
* No ads while recording, playing, or saving, and none on the record or playback screens.
  No app-open ads. Ad-free users never initialise the ad SDK.
* Banner: library only, adaptive, takes no space until loaded.
* Interstitial: only on "Saved → Library", at most once per 3 successful saves and at least
  5 minutes apart. Ad state is re-checked immediately before load and show.

Setup:
1. In AdMob, set the app's store listing once it is live, and complete the GDPR and US-state
   messages under **Privacy & messaging** (UMP reads these).
2. Host `app-ads.txt` at the **root** of the developer website listed in Play
   (`https://pockivo-studio.web.app/app-ads.txt`):
   ```
   google.com, pub-9374713455776239, DIRECT, f08c47fec0942fa0
   ```

## Remove-ads purchase

Play Console → Monetize → In-app products → **One-time product**:

* Product ID `nowmark_remove_ads`, purchase option ID `lifetime` (Buy, non-consumable).
* Price US$1.99; let Play generate local prices.

App behaviour (`billing/BillingManager.kt`, Play Billing 8):

* The button shows the price string Play returns. If Play does not return one, the screen shows
  a retry button instead of any number.
* `PENDING` purchases grant nothing until they become `PURCHASED`.
* Purchases are acknowledged (retried on failure), never consumed.
* Restore = re-query owned purchases (also runs on every resume). A network error keeps the last
  known state instead of removing the entitlement.
* Limit of having no server: entitlement is verified on-device only (optionally with the licensing
  key). Refunds are picked up the next time Play stops returning the purchase.

## Storage and recovery

* While recording, PCM is written to `files/sessions/<uuid>/chunk_NNNNN.pcm` (new chunk every
  5 minutes, fsync about every second) with `journal.json` (atomic replace) and `peaks.bin`.
* Saving encodes the chunks to M4A (AAC) or WAV in `Recordings/Nowmark` (Android 12+),
  `Music/Nowmark` (10–11), or app storage (8–9), then measures the finished file with
  MediaExtractor. The saved screen shows *captured length* and *file length* side by side
  and only says "match" within codec tolerance. It never claims nothing was lost: input
  interruptions reported by Android (`isClientSilenced`, API 29+) and device changes are listed
  separately, without guessing the cause.
* If the app or phone dies mid-recording, the next launch offers recovery for what is on disk,
  reporting the recovered length from the bytes actually found.
* Originals are never modified. Trims create a new linked recording.

### Metadata (Room, `nowmark.db`)

`recordings` (uuid, title, uri, display path, format, preset, captured/file length, verify status,
folder, tags, trashed, parent for trims), `marks` (time, range start/end, category, memo),
`notes` (time, text), `health_events` (type, start/end, wall time). Mark ranges default to
t−30 s … t+10 s, clamped to the file; overlapping ranges are unioned for playback.

### What survives an uninstall

Audio in `Recordings/Nowmark` or `Music/Nowmark` stays on the device. The database (titles, marks,
notes) and anything in app storage is removed. Use **Settings → Export backup** (ZIP with audio
and `manifest.json`) before uninstalling; **Import** restores it and skips recordings already
present. Cloud backup is disabled; device-to-device transfer includes the database.

## Project layout

```
app/src/main/java/com/pockivo/nowmark/
  core/      pure rules (mark ranges, ad frequency, WAV, length check, zip safety) – unit tested
  recorder/  foreground service, capture engine, journal, finalizer/recovery
  audio/     encoders, decoder, MediaStore, waveform cache, trim/share
  player/    ExoPlayer + MediaSession
  data/      Room entities/DAO, DataStore settings
  ads/ billing/ backup/ widget/ ui/
tools/i18n/  translation sources and generator
docs/        release notes, store and policy drafts
```
