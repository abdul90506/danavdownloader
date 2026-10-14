# Vidra for Android — video downloader

*Developed by **Dilhan**.* This is the Android version of the Vidra extension's downloader, with the same design and the same rules: exact sizes only, real progress, audio and video merged, and clear errors.

## What it does
- **Paste a link, or Share → Vidra from any app.** It works with YouTube, Instagram, Facebook, TikTok, X, Vimeo, Reddit, Dailymotion and 1,000+ sites supported by yt-dlp, plus any page that has a plain HTML5 video, HLS (m3u8) or DASH (mpd) stream.
- **Video card:** shows the thumbnail in its real aspect ratio (never cropped), the title, the site and the duration.
- **Quality menu:** one entry per resolution, plus **Audio only (M4A)**.
  - The size is shown only when it's exact.
  - **No audio** is flagged when the site has no sound track for that quality.
  - With *Prefer MP4 (H.264)*, the version that plays on every phone is chosen.
- **Audio + video merge:** separate video and audio streams (YouTube, DASH sites) are downloaded and merged with FFmpeg into MP4. VP9 is merged into WebM, and MKV is used only as a last resort.
- **Real progress:** shows the percent, downloaded bytes against the exact total, and speed. Progress is combined across both streams, then shows "Merging…" and "Saving…". For HLS, the percent comes from the fragment count, and no estimated total is shown.
- **Pause / resume / cancel / retry:** pausing stops yt-dlp but keeps the partial files, so resume continues from the same byte.
- **Background downloads:** a foreground service shows a progress notification, and a "Download complete" notification opens the file.
- **Saved in public storage** at `Download/Vidra/<title> (<quality>).<ext>`, so files show up in Files and in the gallery. The folder can be changed in Settings.
- **Downloads tab:** in-progress items and a history list, with a real thumbnail taken from the saved file. Each item can be opened, shared, have its page link copied, be removed from the list, or have its file deleted.
- **Browser (globe icon):** for sites yt-dlp can't read. Open the page in Vidra's browser and press play. Like the extension, Vidra watches the network requests and the `<video>` elements, then analyses the detected streams (manifests first) with the page as referer.
- **Accounts:** sign in to a site inside Vidra so that private, age-restricted or login-only videos work. Cookies are exported to yt-dlp locally only.
- **Engine updates:** yt-dlp updates itself once a day, and there is an **Update** button in Settings.
- **Clear errors** for these cases: login needed, private, age-restricted, DRM, geo-blocked, live stream, removed, rate-limited, network problems and unsupported links. Each comes with a next step (Open in browser, Sign in, Try again).

## How it works
| Part | File |
|---|---|
| UI (HTML/CSS/JS in a WebView, same design as the extension) | `app/src/main/assets/app/` |
| Quality building, exact sizes, error messages | `assets/app/formats.js` |
| Native bridge, share intent, clipboard | `MainActivity.java` |
| yt-dlp init / analyse (`-J`) / update | `Engine.java` |
| Download queue: `--load-info-json` + `-f video+audio` + progress template, save | `Jobs.java` |
| Copy into MediaStore `Download/<folder>`, thumbnail, open/share/delete | `Saver.java` |
| Foreground service + notifications | `DownloadService.java`, `Notify.java` |
| In-app browser with stream detection / sign-in | `BrowserActivity.java` |
| Cookie export for yt-dlp | `Cookies.java` |
| Settings + history | `Store.java` |

The engine is [youtubedl-android](https://github.com/yausername/youtubedl-android) 0.18.1, which bundles Python, yt-dlp, FFmpeg and QuickJS. It is GPL-3.0, so this app is GPL-3.0 too.

## Build
Requires JDK 17 and the Android SDK (platform 34, build-tools 34).
```
./build.sh          # → app/build/outputs/apk/release/app-arm64-v8a-release.apk (+ armeabi-v7a)
```
Releases are signed with `app/vidra-release.jks` (alias `vidra`, password `vidra-dilhan`). Keep this file: future updates must be signed with the same key to install over the old version.

## Limits
- DRM-protected streams (Netflix, Prime and similar) can't be downloaded.
- Live streams can be downloaded only after they end.
- Some sites need a sign-in (Settings → Accounts).
- The first launch takes a few seconds while the engine is unpacked.
- The APK is about 55 MB because it contains Python and FFmpeg.
