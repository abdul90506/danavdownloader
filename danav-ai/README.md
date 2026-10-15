# Danav AI for Android

*Developed by **Dilhan**.* The voice assistant on its own: chat, a live voice call the phone can talk back to,
and hands-free control of the whole phone. The downloader is a **separate app** (`../danav-downloader`).

## What it does
- **Chat** — ask anything; web search, pages read for you, photos and files attached, long answers with code and
  sources that open in the phone's browser.
- **Live voice call** — talk naturally, interrupt it, it answers out loud. Camera (video) and screen sharing
  are part of the call screen, and a small floating bubble keeps the call alive while you use other apps.
- **Volume-Up ×3 anywhere** — Danav's circle comes up while another app is in front. Three presses again send it
  away. **It never closes by itself** — no 15-second timer, no silence timeout. (Needs the microphone permission
  and, for the key press while other apps are open, the accessibility service.)
- **Phone control** — WhatsApp messages and calls, phone calls, SMS, email, YouTube, opening any app, settings,
  alarms and timers, volume, flashlight, typing, scrolling, dragging sliders, screenshots. Everything it does is
  real: taps are brought into view first, checks the new screen after each step, and reports honestly.
- **Fast local answers** — time, date, battery, torch, volume, timer, alarm, screenshot, back / home, WhatsApp
  message or call, "open <app>" are answered **on the phone, instantly**, without the internet.
- **Memory** — "What Danav remembers": name, city, language, people, routines, likes and how you want answers.
  Danav saves these itself while you talk and can be told to forget any of them.
- **3-line sidebar** (top right) — tools and widgets: voice call, phone control, memory, voice, chats, the
  Volume-Up ×3 shortcut, battery limits, and widgets for the time, battery, saved notes and current voice.

## How it works
| Part | File |
|---|---|
| UI (chat, call, phone panel, sidebar) | `app/src/main/assets/app/` — `ai.js`, `ai.css`, `core.js`, `index.html`, `icons.js` |
| Bridge to the phone (`window.Vidra.*`) | `app/src/main/java/com/dilhan/danavai/Bridge.java` |
| WebView host, permissions, back button | `MainActivity.java` |
| Live voice call (WebSocket, audio in / out) | `LiveCall.java` |
| Chat + web search + attachments + memory tools | `Ai.java`, `AiKeys.java` |
| Instant on-phone answers | `Fast.java` |
| The tools the model can call on the phone | `Phone.java` |
| Accessibility service: read screen, tap, type, drag, sliders | `DanavAccess.java` |
| Floating call bubble | `CallBubble.java`, `CallService.java` |
| Screen sharing during a call | `ScreenShare.java` |
| Volume-Up ×3 + the quiet microphone notification | `WakeService.java` |
| "What Danav remembers" storage | `Memory.java` |

There is **no voice model and no wake word** any more: nothing is listened to in the background, and no 79 MB
Hindi model is downloaded. The microphone is opened only when the circle is up (or you start a call).

## Build
Requires JDK 17 and the Android SDK (platform 34, build-tools 34).
```
./build.sh          # → app/build/outputs/apk/release/app-arm64-v8a-release.apk (+ armeabi-v7a)
```
Signed with `app/danav-release.jks` (alias `vidra`, password `vidra-dilhan`) — the same certificate as the
downloader and as 2.1.0, so updates install straight over the old app.

## First run
1. Allow the **microphone** (needed for calls and the circle).
2. **Phone control**: 3-line button → Phone control → *Phone control* row → turn on "Danav AI phone control" in
   accessibility settings. On Android 13+ the row may be greyed out — App info › ⋮ › *Allow restricted settings*.
3. Optional: allow **Show over other apps** (for the floating bubble) and **No battery limits**.
