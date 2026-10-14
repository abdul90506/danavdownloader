# Danav 2.2.0 — kya badla (13 Oct 2026)

APK: **Danav-2.2.0-arm64.apk** (86 MB, v2 signed, wahi certificate jo 2.1.0 ka tha — seedha update install hota hai).

## 1. "Hey Danav" wake word — sirf naam pe active
- Pehle "Dana / Daane / Dan / Danish" jaise milte-julte shabd bhi trigger kar dete the. Ab 3 level check:
  - `हे दानव` (do saaf shabd) → 0.62 confidence kaafi.
  - Sirf akela `दानव` → 0.78 chahiye (asli naam saaf bola ho).
  - Music/video speaker pe chal raha ho → 0.88 — tab koi ghalat trigger nahi.
- Ghalat trigger ho gaya aur AI ne `dismiss` kiya → **90 second cooldown**, usi audio ki tail dobara wake nahi kar sakti.
- Wake word background me sirf *sun raha* hota hai — AI call **sirf** "Hey Danav" sun-ne par khulta hai. Jab tak call na ho, koi AI chalti nahi.

## 2. Call khud band hona band (25 second wala masla)
- Wake call pehle 25 second silence par khud band ho jati thi → ab **5 minute** silence ka safety net, warna call chalti rehti hai.
- Bolne par (awaaz detect hone par) timer khud reset ho jata hai.
- Call nahi lag rahi? Pehle 8 keys × 2 = jaldi haar maan leti thi → ab ~2.5 minute tak keys badal-badal kar reconnect karti hai (`reconnecting` state ke sath).
- AI ko rule: **khud call end mat karo** — sirf jab user "bye / bas / band karo" kahe.

## 3. Zyada tez + zyada intelligent assistant
- Mic chunks 40 ms → **20 ms**, speaker buffer 200 ms → **125 ms** = jawab pehle shuru.
- Speech tuning (`realtimeInputConfig`: start/end sensitivity high, `silenceDurationMs 450`) — agar model reject kare to **automatically** plain setup pe wapas (call kabhi break nahi hoti).
- Naye rules: clear cheez par confirmation nahi, har qadam khud, aur **chhote kaam ka "kar diya" nahi batana** — message bhej diya, app khol diya, timer laga diya → bas chup-chaap ho jata hai. Sirf tab bolta hai jab user pooche, kuch fail ho, ya decision chahiye ho.

## 4. Memory / personalization (naya)
- `Memory.java`: user ki baatein (naam, shehar, zubaan, log, routine, pasand/napasand, kaise jawab chahiye) phone me hi (files/danav_memory.json) save hoti hain.
- AI khud `remember` tool se save karta hai aur `forget` se hatata hai — chat aur call dono me note inject hote hain, is liye har baar batana nahi parta.
- UI: **Danav AI → Phone control → "What Danav remembers"** — har note delete, ya "Forget everything".

## 5. Notification
- "Hey Danav" se call start hone par **alag Danav AI notification nahi** aata (wake call Hey Danav service ke andar chalti hai).
- Sirf ek chhoti, silent mic-service notification rehti hai — yeh Android 11+ ki **lazmi shart** hai jab app background me mic use kare. Iske bagair OS mic band kar deta hai; ise min-importance + no-badge rakha gaya hai.

## 6. Downloader (yt-dlp — 1000+ sites, Vimeo, YouTube, Insta, TikTok, FB, X…)
- `--extractor-retries 3`, `--fragment-retries 20`, `--concurrent-fragments 8` (HLS/DASH tez).
- YouTube throttling pakarne par `--throttled-rate 150K` → dobara extract kar ke poori speed.
- Har quality formats.js se aati hai; jis quality me audio nahi, uska best audio sath merge hota hai (mp4/webm/mkv), aur agar site par audio hi na ho to video-only banti hai (note ke sath).

## 7. Play Protect — sach baat
App ko **isi purane certificate** se sign kiya gaya hai (`vidra-release.jks`), is liye:
- 2.1.0 ke upar seedha **update** ki tarah install hota hai (Play Protect update par naya "block" nahi karta).
- Danav AI ki keys bhi isi certificate se khulti hain — agar naya key se sign hota to AI chalti hi nahi.
- Pehli dafa install (ya uninstall ke baad) Play Protect kuch bhi sideload app ko "unsafe" keh sakta hai — yeh uski default policy hai. Home screen (Play Store) → profile → Play Protect → ⚙️ → **"Scan apps with Play Protect" off**, phir install. Install ke baad wapas on kar sakte ho, app chalti rehti hai.

## 8. Jaga / cleanup
- `Danav-2.1.0.apk` (86 MB) delete → naya 2.2.0 build.
- 86 MB `.git` history delete (usme purani APK thi) → naya chhota repo (2.6 MB) + 1 commit. `git remote add origin` kar ke push kar sakte ho.
- `testenv/`, `uploads/` rakhe hain (chhote hain); `.gradle/` cache hata diya.

## Build
```bash
cd vidra-android
bash tools/setup.sh     # JDK 17 + gradle 8.7 + Android SDK (ek dafa)
bash build.sh           # → app/build/outputs/apk/release/app-*-release.apk
```
armv7 (32-bit) APK bhi banta hai (`app-armeabi-v7a-release.apk`, 79 MB) — sirf arm64 workspace me rakha gaya hai.
