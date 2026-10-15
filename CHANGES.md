# Danav 2.3.0 — do alag apps (15 Oct 2026)

Ab **do alag apps** hain, alag alag source code aur alag APK ke sath:

| App | APK | Size | Kya hai |
|-----|-----|------|---------|
| **Danav Downloader** | `DanavDownloader-2.3.0-arm64.apk` | 82 MB | Sirf downloader: sab sites se video/audio, apna browser + ad blocker, video player, music player, Private folder. **AI ka kuch nahi.** |
| **Danav AI** | `DanavAI-2.3.0-arm64.apk` | 1.7 MB | Sirf Danav AI: chat, live voice call, Volume-Up ×3 circle, poora phone control, memory. |

Dono same certificate se signed hain, is liye 2.2.0 ke upar seedha update hote hain. Package name bhi alag:
downloader `com.dilhan.vidra`, Danav AI `com.dilhan.danavai` — dono saath install ho sakti hain.

## 1. Source code aur folders alag
- `danav-downloader/` = pooray downloader ka source (16 java files + apna UI). Isme se AI ka sara code,
  AI ki web files (`ai.js`, `ai.css`), accessibility service, wake service, call service, mic/overlay
  permissions aur wake/phone/memory bridge — sab **hata diya**.
- `danav-ai/` = pooray Danav AI ka source (16 java files + `ai.js`/`ai.css`/`core.js`/`index.html`).
  Isme downloader/player/vault ka kuch nahi.
- Dono app ka apna `build.sh`; root me ek `build.sh` jo **dono** APK banata hai (aur pehle purani APK +
  build folders delete karta hai).
- Downloader 86 MB → **82 MB** (Vosk voice model lib, JNA aur okhttp nikal gaye).

## 2. Downloader se 3-line button hata diya
- Danav Downloader ke top-right se hamburger **hata diya**; uski sidebar (tools + widgets) ab **Danav AI app**
  me hai. Downloader me AI ka koi button, koi switch, koi mention nahi — sirf downloader.
- Launcher par naam bhi **"Danav Downloader"** ho gaya (AI app ka "Danav AI").

## 3. Danav AI app: 3-line sidebar (right side) + widgets
- Danav AI ke top-right me 3-line button hai → right side se sidebar khulti hai:
  **Tools** (Voice call, Phone control, What Danav remembers, Voice, Chats) +
  **Widgets** (time, battery %, saved notes, current voice) +
  **Phone** (Volume-Up ×3 shortcut on/off, No battery limits, App info).
- Left wale button se purani chat list khulti hai (search + purani chats), jaise pehle tha.

## 4. Activation: Volume-Up ×3 (Danav AI me)
- "Hey Danav" wake word ki ab **koi zarurat nahi** — na voice model, na 79 MB download, na background listening.
  Jo bhi Vosk ka code tha wo Danav AI se nikal diya, is liye app 1.7 MB ki reh gayi.
- Volume-Up **3 baar ek saath** → circle aa jata hai; **dobara 3 baar** → chala jata hai. Circle **khud band
  nahi hota** — 15 second baad bhi nahi, kabhi nahi. Sirf aapka "bye / bas / band karo" ya dobara triple press.
- Mic service hamesha "Ready · Volume-Up ×3 to wake" rehti hai (ek chhoti, silent notification).

## 5. Phone control (AI)
- Slide ke baad tap ghalat jagah (neeche wale button par) lagta tha — ab har tap se pehle element screen ke andar
  laaya jata hai, clamped centre par tap, aur 2 koshish hoti hain; slide ke baad 250 ms settle.
- Naye tools: `tap_xy` (exact x,y — double tap bhi), `drag`, `set_slider` (slider % tak smooth khinchna),
  aur `tap` ab ScrollView/RecyclerView ke andar bhi dhoond leta hai.

## 6. Video player (downloader)
- Rotate karte ho → **swipe se agla video band**; portrait me swipe par agla video **inline** aata hai — pager
  glid karta hai, agla player pehle se muted pre-loaded hota hai, is liye **kaala gap nahi** aata aur player bhi
  agle video ke sath chalta hai (300 ms smooth glide, chup-chaap release).

## 7. Fast local replies (AI)
- Time, date, battery, torch, volume, timer, alarm, screenshot, back/home, WhatsApp message/call, phone call,
  "X app kholo" — sab phone hi me foran hal hote hain (internet ke bina). Live call me AI ko phone ka live
  context milta hai: "abhi <din> <waqt>, battery N%, user <app> me hai".

## 8. Jaga (128 MB rule)
- Build se pehle purani APK aur build folders delete; bekaar `testenv/`, `uploads/` bhi delete.
- Ab repo ~84 MB ka hai (dono APK mila kar) — 128 MB ke andar.

---

# Danav 2.3.0 — kya badla (13 Oct 2026)

APK: **Danav-2.3.0-arm64.apk** (86 MB, v2 signed, wahi certificate jo 2.2.0/2.1.0 ka tha — seedha update install hota hai).

## 1. Right side hamburger + sidebar (naya)
- App ke top-right (brand ke bagal me, AI switch se pehle) **3-line button** aa gaya hai.
- Usse **side bar** khulti hai: Home, Downloads, Private, Settings + Tools group — Danav circle, Voice call, Phone control, What Danav remembers, Browser, Site accounts, Ad blocker, Download engine, Clear download list.
- Sidebar me **widgets ke liye jagah** rakhi gayi hai (foot me version ke sath widget slot) — naye tools/widgets wahin add hote rahenge.
- Back button dabane par pehle sidebar band hota hai, phir screen badalti hai.

## 2. "Hey Danav" hataya — ab **Volume-Up ×3**
- Wake word ("Hey Danav") UI se hata diya. Ab **Volume-Up 3 baar ek saath** dabao → Danav circle aa jata hai. Dobara Volume-Up ×3 → band.
- Circle **khud band nahi hota** — 15 second baad bhi nahi, kabhi nahi. Sirf do cheezein band karti hain: Volume-Up ×3, ya user ke "bye / bas / band karo".
- Pura "silent" safety net 10 minute kar diya (sirf tab jab bilkul koi baat na ho).
- Mic service (chhoti si "Danav" notification, importance minimum) **hamesha ready** rehti hai — voice model ki ab zarurat nahi, is liye wake ab har phone pe bina download chalega. Notification me likha hota hai: "Ready · Volume-Up ×3 to wake".
- Teen dafa dabane ka window: 2.2 second ke andar teen press, aur do press ke beech 0.9 second se zyada gap nahi.

## 3. Danav ab phone theek chalata hai (tap/slide ki accuracy)
- **Slide ke baad tap ghuma hua tha** — ab har tap se pehle element apne aap **screen ke andar** laaya jata hai (`ACTION_SHOW_ON_SCREEN`), phir clamp kiya hua centre point par tap hota hai, aur 2 koshish hoti hain (pehla attempt miss ho to dobara).
- `scroll`/`swipe` ke baad 250 ms **settle** — animation rukne ke baad hi agla tap.
- Naye tools: **`tap_xy`** (screen ke exact x,y par tap — double tap bhi), **`drag`** (x1,y1 → x2,y2), **`set_slider`** (slider ko `frac 0–100%` tak khinchna — brightness/volume/seek wale sliders ke liye, 10 chhote steps me smooth drag).
- `tap` ab scroll ke andar wale item ke liye `findScroll` use karta hai (ScrollView/RecyclerView khud scroll kar ke element tak jata hai).
- Ye sab Danav ke system instructions me bhi bata diya gaya hai, is liye AI khud sahi tool chunta hai.

## 4. Video player — rotate par swipe band, warna agla video **inline**
- Phone **rotate** karte ho → swipe se agla/pehla video **band** (VLC/landscape me bas player chalta hai). Wapas portrait → swipe phir kaam karta hai.
- Portrait me swipe karo → agla video **usi jagah, bina kaale gap** ke aa jata hai: pager (pvPrev / pv / pvNext) pura ek sath glid karta hai, agla player pehle se **tayyar** (muted, pre-loaded) hota hai — is liye screen kaali nahi hoti aur **player bhi agle video ke sath chalta** hai.
- Smoothness: `PathInterpolator(0.3, 0, 0.6, 1)` 300 ms glide, agla page tayyar na ho to +160 ms, phir 240 ms hold aur pichla player chup-chaap release (koi awaaz ka overlap nahi — purane player ka "audio becoming noisy" band hai).
- Rotate karne par purane pager pages release ho jate hain (memory aur awaaz dono saaf).

## 5. Fast local replies (naya) — bina internet, foran
- Awaaz/fast commands ab **phone hi me** hal hote hain (koi server round-trip nahi): time, date, battery, torch on/off, volume up/down/mute/set, ring vs media, timer, alarm, screenshot, back, home, WhatsApp message/call, phone call, "X app kholo".
- Circle wale live mode me AI ko phone ka live context milta hai: **"abhi <din> <waqt>, battery N%, user <app> me hai"** — is liye ye poochne ki zarurat nahi.

## 6. Jaga (128 MB rule)
- Naya build karne se pehle purani APK aur build outputs hata diye jate hain; repo root me sirf **ek** APK rehti hai.

## 7. Wahi cheezein jo qaim hain
- Chhote kaam ka "kar diya" nahi batata, jab tak poocha na jaye chup; pehle sunta hai, phir karta hai.
- Memory (What Danav remembers) waisi hi — save/delete/clear.
- Koi alag Danav AI notification nahi (sirf chhoti si wake/mic service).

---

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
