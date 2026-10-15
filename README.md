# Danav — ab do alag apps

Pehle ek hi app me sab kuch tha (downloader + browser + player + Danav AI). Ab **do alag apps** hain, alag
alag source code ke sath — dono phone me saath saath install ho sakti hain, aur dono ka **apna apna update**
aata hai.

| App | APK | Kya karta hai |
|-----|-----|----------------|
| **Danav Downloader** | `DanavDownloader-2.3.0-arm64.apk` (82 MB) | Har site se video/audio download, apna browser + ad blocker, video player (rotate swipe, inline next), music player, Private folder (PIN). **Isme Danav AI ka kuch nahi hai.** |
| **Danav AI** | `DanavAI-2.3.0-arm64.apk` (1.7 MB) | Danav AI chat, web search, live voice call (camera / screen share bhi), Volume-Up ×3 se circle, poora phone control (WhatsApp, call, settings, koi bhi app), memory ("What Danav remembers"). |

Dono APK repo ki bunyadi (root) folder me hain. Dono **same certificate** se signed hain, is liye purani
2.2.0 ke upar seedha update ho jata hai (data safe rehta hai).

## Kaun si app kahan

```
danavdownloader/
├── danav-downloader/      # pooray downloader app ka source code (Vidra/Danav Downloader)
│   ├── app/src/main/java/com/dilhan/vidra/…      # 16 java files
│   ├── app/src/main/assets/app/                  # UI (index.html, app.js, core.js, vault.js…)
│   ├── tools/setup.sh                            # ek baar chalane wala toolchain setup
│   └── build.sh
├── danav-ai/              # pooray Danav AI app ka source code
│   ├── app/src/main/java/com/dilhan/danavai/…    # 16 java files (Ai, LiveCall, Phone, DanavAccess…)
│   ├── app/src/main/assets/app/                  # ai.js, ai.css, core.js, index.html, icons.js
│   └── build.sh
├── build.sh               # dono apps ko build karta hai (pehle purani APK + build folder delete)
├── DanavDownloader-2.3.0-arm64.apk
├── DanavAI-2.3.0-arm64.apk
└── CHANGES.md             # kya badla (Roman Urdu)
```

Dono ka package name bhi alag hai: downloader = `com.dilhan.vidra`, Danav AI = `com.dilhan.danavai`.

## Build kaise karein

```bash
bash danav-downloader/tools/setup.sh   # sirf ek dafa: JDK 17 + Gradle 8.7 + Android SDK 34
bash build.sh                          # dono APK ban jayengi, root me
```

`build.sh` pehle purani APK aur `app/build`, `.gradle` folders **delete** karta hai (jaga bachane ke liye),
phir dono apps build karta hai, root me `*-arm64.apk` copy karta hai, aur build folders dobara delete kar
deta hai. Repo 128 MB ke andar hi rehta hai (abhi ~84 MB).

## Install

1. `DanavDownloader-2.3.0-arm64.apk` — downloader ke liye.
2. `DanavAI-2.3.0-arm64.apk` — Danav AI ke liye.

Pehli dafa Danav AI khologe to:
- **Microphone** allow karein (calls ke liye).
- **Phone control** ke liye: Danav AI → top-right 3-line button → Phone control → *Phone control* row → allow
  accessibility. (Android 13+ me "restricted setting" ho to App info › ⋮ › *Allow restricted settings*.)
- Volume-Up ×3 ka shortcut tab kaam karta hai jab mic aur accessibility dono on hon.
