# Nowmark 1.0.0 release notes for Play Console

## 1. Status of v1

| Area | Implemented | Verified how |
| --- | --- | --- |
| Mark range math, union, ad frequency, WAV header, length tolerance, ZIP name safety | Yes | Unit tests (`app/src/test`), run in CI |
| Build, lint (incl. all 10 translations complete), release uses real ad IDs only | Yes | CI |
| Foreground recording, pause/resume, marks, notes, notification actions, widget, shortcut | Yes | Compiled; **needs device testing** |
| Chunked PCM + journal, crash recovery | Yes | Compiled; **test by force-stopping mid-recording** |
| Save to M4A/WAV, length check vs. captured length | Yes | Compiled; **needs device testing** |
| Input interruption (API 29+) and device-change logging | Yes | Compiled; **needs device testing** (e.g. start a call, or another app grabbing the mic) |
| Library search, folders, tags, trash, rename | Yes | Compiled |
| Review (highlights) playback, speed, skip silence, background playback | Yes | Compiled |
| Trim as copy, share clip, export/import ZIP backup | Yes | Compiled |
| UMP consent, banner, interstitial frequency | Yes | Compiled; **test with debug build (test ads)** |
| Remove-ads purchase, pending, restore | Yes | Compiled; **needs license tester on internal track** |
| Photos | Not in v1 (no buttons shown) | – |

"Compiled" means it builds and lints in CI but has not been run on a phone yet.

## 2. Play Console setup checklist

1. Create app *Nowmark*, default language English (US), app, free.
2. Upload the signed AAB to **Internal testing** first. Opt in to Play App Signing.
3. Monetize → In-app products → one-time product `nowmark_remove_ads`, purchase option
   `lifetime`, US$1.99, activate.
4. Add yourself under Settings → License testing to buy without being charged.
5. App content: privacy policy URL `https://pockivo-studio.web.app/nowmark/privacy/`,
   Ads = **Yes**, target audience 18+ (or 13+; not designed for children), content rating
   questionnaire, Data safety (section 4), Foreground service declaration (section 3).
6. Store listing: support email `justin.cho@plexsor.ai`, website `https://pockivo-studio.web.app/`.
7. Host `app-ads.txt` at `https://pockivo-studio.web.app/app-ads.txt`.

## 3. Foreground service declaration

**microphone (FOREGROUND_SERVICE_MICROPHONE)**
> Nowmark is a voice recorder. When the user taps Record, a foreground service captures audio
> from the microphone so recording continues while the screen is off or the user switches apps.
> A persistent notification shows the elapsed time and Pause, Mark, and Stop actions. The service
> starts only from a user action and stops when the user stops recording.

**mediaPlayback (FOREGROUND_SERVICE_MEDIA_PLAYBACK)**
> Plays the user's own recordings, including marked highlights, with media controls in the
> notification and lock screen so playback continues in the background.

Demo video: record → lock screen → notification Mark → stop → saved screen.

## 4. Data safety draft

* **Data collected by the developer:** none. Recordings, marks, notes and settings stay on the
  device; there is no server.
* **Third-party SDKs:** Google Mobile Ads collects device or other IDs, app interactions,
  diagnostics and approximate location (from IP) for advertising, analytics and fraud prevention.
  Declare: *Device or other IDs* – Collected, shared, for Advertising/Analytics/Fraud prevention.
  Play Billing processes purchase history (*Purchase history* – collected, App functionality).
* Data encrypted in transit: Yes (SDK traffic). Users can request deletion: data is on-device;
  uninstalling removes app data (audio in Recordings/Music stays until the user deletes it).
* Ads-free users: the ad SDK is not initialised.

## 5. Privacy policy points (for the web page)

* What the app records and where it is stored (on device only).
* Microphone permission used only while recording, which the user starts.
* Notifications permission used for the recording/playback status notification.
* Google AdMob and UMP: link Google's policy (`https://policies.google.com/technologies/ads`),
  consent choices available in Settings → Privacy options.
* Google Play Billing for the remove-ads purchase.
* No account, no analytics of our own, no cloud backup. Contact: justin.cho@plexsor.ai.

## 6. Next updates (from the product plan)

**2nd update**
* Photo attachments and an extended photo + note timeline (data model already leaves room).
* Optional re-listen / to-do local reminders; per-project collections.
* User-started local Wi-Fi transfer to a PC.
* MP3 export; silence or mask selected ranges in shared copies.
* Floating bubble, Wear OS, earphone-button control, after platform and device verification.

**3rd update (after validation)**
* Optional downloadable on-device multilingual transcription.
* Jump from transcript sentences to the original audio; search across recordings.
* Local summaries and automatic briefings on supported devices.
* Optional standby mode that keeps the 1–2 minutes before recording starts.
