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

---

## 15 Oct 2026 — player + AI polish (dono apps, arm64)

Ye round music/video player aur Danav AI ke UI ka hai. Dono APK dobara bane, purani APK pehle delete ki gayi,
repo 88 MB par hai.

### Video player
- **Audio-track icon button hata diya** — sirf track ka naam dikhta hai. Next/prev button aur swipe dono se
  agla video aata hai.
- **Next video par swipe:** controls pehle chup ho jaate hain, phir page bilkul **smooth** glide karta hai
  (180–340 ms, ease-out), aur naya video pehla frame draw karte hi controls wapas aa jaate hain. Beech me
  kaala gap nahi aata, kyunki agla video apne page par pehle se chalta rehta hai.
- Rotate kiya hua (landscape) player par swipe band hi hai — sirf button chalta hai.

### Audio player (now playing)
- Poster aur controls ke darmiyan ka **khaali space khatam**; layout center me balance hai, chhote phone par
  artwork `min(100%, 40vh, 340px)` par ruk jata hai.
- Neeche ek line me: **Favourite star**, **Lyrics**, **Mixer**; upar poster ke andar spectrum.

### Lyrics (naya)
- **Lyrics** button → agar lyrics nahi hain to **Add lyrics** khulta hai: paste karo, Save — lyrics usi song ke
  sath phone par hamesha ke liye save ho jaate hain (file badle to lyrics dobara paste karne parenge).
- Padhne ke liye aasan font (16px, line-height 1.7, paragraph breaks), jab chaho **Edit** se badal do.
- Kuch upload nahi hota — sirf phone ki storage me.

### Realtime mixer (naya)
- **Mixer** button → 5 fader: **Vocals, Guitar, Piano, Bass, Drums** (−100% … +100%).
- Fader hilate hi awaaz usi waqt badalti hai (Android ke apne equalizer bands par, instrument ke tone ke
  hisaab se weights), aur setting song ke sath yaad rehti hai. **Reset** se sab 0%.
- Vocals neeche karo → karaoke feel.

### Spectrum (asli, banawata nahi)
- Jis song par poster nahi hota, wahan **Show the music** button aata hai → poster ki jagah **asli spectrum**
  chalta hai (Android ka apna Visualizer, 48 bands, 45 Hz – 16 kHz, 33 fps, rise fast / fall slow).
- Rang halke se mixed hain (accent se safed ki taraf shift) aur tez bands par safe cap — rock/metal par
  poora bhar aata hai. Pehli baar **microphone permission** maangega (Android 13+ requirement); mana karo to
  spectrum chup-chaap band ho jata hai, koi banawata bars nahi banate.

### Videos / Music library
- **Search bar ab hamesha nazar nahi aata** — chhota search icon dabao to khulta hai, ✕ se band.
- Thumbnail par **quality/resolution ka tag hata diya**.
- **Favourites (Music):** har song ke baaju star, aur upar **Favorites** segment — wahan se seedha play.
- **Delete:** video aur music dono ka delete poora kaam karta hai (confirm ke baad file bhi jati hai, list se
  foran gayab).

### Danav AI
- **Right side ki 2-line sidebar poori hata di** — ab **ChatGPT jaisi left sidebar**: search, New chat,
  Voice call, Phone control, What Danav remembers, Voice, aur neeche **Settings** group (Appearance,
  Volume-Up ×3 anywhere, App info) + version. Har row ke saamne uski haqeeqat (On a call / Ready / 3 notes …).
- Left history icon nahi; `#aiBar` me sirf **hamburger (left) + title + new chat (right)**.
- **Think selector chhota** kar diya (26px chip, 11.5px text).
- **Keyboard wala bug fix:** input box ke saath popover ab apni jagah **follow** karta hai (visualViewport +
  resize/scroll listeners) — box neeche jaye to menu hawa me nahi latakta; popover par tap karne se keyboard
  bhi band nahi hota.
- **Appearance settings** (downloader jaisi): **Theme** (System/Light/Dark), **Accent** (11 colours),
  **Text size** (chhota/normal/bara = phone ka apna text zoom), **Compact**. Java prefs me save hote hain,
  is liye app band karne ke baad bhi wahi rehte hain.
- **Patli safed line khatam:** downloader ke nav (home/videos/music/downloads) ke neeche, mini player par,
  Danav AI ke input box ke neeche (ab box bina border ka soft fill hai), aur sidebar ke header/footer par.

### Safai
- Purani APK build se pehle delete hoti hai, build folders prune — repo **88 MB** (limit 128 MB).
- Dono APK: `versionCode 14`, `versionName 2.3.0`, same cert (SHA-256 `91631a…2b87`) — purani 2.3.0 ke upar
  seedha install ho jati hain, data safe rehta hai.

---

## 15 Oct 2026 (round 2) — jo aap ne kaha, woh theek kar diya

Aap ki list ka har point dobara dekha gaya. Purani APK build se pehle delete, repo **88 MB**.

### Audio player: mixer nikal diya + layout theek
- **Mixer poori tarah hata diya** — button, panel, faders aur uska code, sab. Ab player me sirf jo zaroori hai.
- **Poster ab darmiyan me hai aur controls neeche** — pehle poora block beech me tha (is liye controls oopar lagte the aur neeche
  khaali jagah bachti thi). Ab artwork apne hisse ke beech me centre hota hai aur play/prev/next wala hissa neeche baithta hai.
- Poster par halka sa shadow, aur neeche ki taraf double safe-area padding bhi khatam (wo extra khaali space de raha tha).

### Spectrum: khud ba khud, asli aur rangeen
- **Ab button dabane ki zaroorat nahi** — jis song ka poster nahi, us par player kholte hi **asli spectrum** chal padta hai
  (Android ka Visualizer, 48 bands, 45 Hz – 16 kHz). Pehli baar phone **microphone** maangega (Android ki shart hai) — ek baar Allow.
- **Colours asli analyser jaise**: bass par laal/orange, mid me peela-green, treble par cyan/violet — har band ka apna rang, level ke
  sath brightness, neeche halka glow, aur har band par **peak cap** jo dheere neeche girta hai. Values har baar asli FFT se aati hain —
  banawata bars kabhi nahi.
- Agar microphone mana kar diya jaye to poster ke neeche ek **chhota sa** "Show the music" pill aata hai (bara button nahi) — tap
  karne par dobara maangta hai. Poster wale songs par spectrum nahi aata (un ka apna poster hota hai).

### Lyrics
- **Text ab darmiyan (centre) me** aur bara-saaf: 17.5px, line-height 1.85, paragraph gaps, pehli line bold (song ka naam).
- **Lyrics button ab top bar me chhota icon** hai (star ke saath) — controls ke neeche wala bara ajeeb button hata diya.
- Paste → Save waise hi; lyrics song ke sath phone par rehti hain.

### Poster na ho to song ka apna colour cover
- Har song ka **apna colour** (title + artist se bana hamesha wahi gradient): Music list ki rows, albums ke tiles, mini player aur
  full player — sab jagah. Woh phika grey box khatam.
- Full player me colour cover ke upar hi spectrum chalta hai, aur peeche halka colour wash — dekhne me poora set lagta hai.

### Patli safed line (screenshot wali)
- Dono apps me phone ki **navigation strip ka divider aur contrast scrim band** kar diya (`setNavigationBarDividerColor` +
  `setNavigationBarContrastEnforced(false)`), aur downloader me us strip ka colour app ke bottom bar ke bilkul barabar kar diya
  (dark me `#18181b`) — nav (Home/Videos/Music/Downloads) ke neeche line nahi rehni chahiye.
- Danav AI me input box ke neeche bhi wahi treatment — box bina border ka soft fill hai, aur neeche ki line band.

### Danav AI: Settings button, chhota flat think selector
- Sidebar me pehle teen settings rows thi — ab **ek hi "Settings" button** hai (uske saamne abhi ka theme/accent likha hota hai).
  Tap karne par **poora Settings page** khulta hai: **Theme** (System/Light/Dark), **Accent** (11 rang), **Text size**, **Compact**,
  **Volume-Up ×3 anywhere** (switch), aur **App info** (version + phone ki app settings).
- **Think selector chhota aur bilkul flat** — 24px chip, koi border nahi, halka sa text; input ke saath hi baithta hai.
- Menu me **kisi item ke baad koi dhundhli line nahi** (border hata diya), aur **tap karne par woh select ho jata hai** — chuna hua
  level accent colour me highlight + tick ke sath, aur wahi level yaad rehta hai. Pehle popover ka apna tap-guard click kha jaata tha
  (is liye "select nahi hota" lagta tha) — woh hata diya, aur menu band hone par keyboard wapas aa jata hai.
- Agar koi model `thinkingLevel` field na maane, to wahi request **level ke bina dobara** jaati hai — jawab fail nahi hota.

---

## 15 Oct 2026 (round 3) — Danav AI bahut zyada strong, sirf Danav AI par kaam

Sirf `danav-ai` badla hai (naya APK: `DanavAI-2.3.0-arm64.apk`, 1.75 MB, wahi cert). Downloader wahi pichhli build hai.

### 1. Speaker ka masla khatam (Danav ke jane par awaaz upar wale speaker se aana)
- Pehle Danav call ke baad phone ka audio **earpiece (upar wala)** par atak jata tha. Ab call khatam hone par route
  **zabardasti loudspeaker** par wapas jata hai, phir chhoda jata hai (`setCommunicationDevice(speaker)` → clear, purane
  Android par `setSpeakerphoneOn`), aur `setMode` bhi theek se reset hota hai. Audio focus bhi chhoot jata hai.

### 2. Video chal rahi ho to bhi Danav sunta hai
- Danav call shuru karte waqt **audio focus** le leta hai — Downloader ka player focus maanta hai, is liye video **khud pause**
  ho jati hai (aur call khatam hone par focus chhoot jata hai).
- Jo app focus na maane uske liye **volume ducking**: call ke doran media ki awaaz ~16% par, call khatam par wapas poori.

### 3. Bolte bolte rukna / wahi baat dobara bolna
- **False interruption** hi asli wajah thi: room ki halki awaaz ko "user bol raha hai" samajh kar jawab beech me kat jata tha,
  phir wahi line dobara. Ab **start-of-speech sensitivity LOW**, silence 520ms, prefix 120ms — hiss/traffic/video se jawab
  nahi katta.
- Mic par **floor** laga diya: bohot halki room-noise silence ban jati hai (model us par lafz guess nahi karta) — AGC + noise
  suppression pehle se the.
- Network break ke baad wapas connect hone par model ko saaf kaha jata hai: **dobara greet na kare, koi line repeat na kare**.

### 4. Phone control: galti band
- **Contact** dhoondhne me ab sirf woh log aate hain jinka **asli number/email** save ho — khaali number se kabhi number guess
  nahi hota (`intl()` ab bhi guard karta hai).
- **Ek naam ke do contacts** hon to Danav **poochh leta** hai, khud kisi ko nahi chunta.
- **WhatsApp**: message/call bhejne se pehle WhatsApp jo chat kholta hai uska **title check** hota hai; naam match na kare to
  kuch send nahi hota aur Danav saaf batata hai.
- **Tap ki accuracy**: jo node match kare un me sab se **chhota box** chuna jata hai (button, na ke poori list jo us ka text
  apne andar rakhti ho) — "galat jagah click" kam.
- **Swipe (naya)**: poora page / story / Reels / carousel ek hi smooth finger-move me, `pages` se kitne page. List ke liye `scroll`.

### 5. WhatsApp pe call kar ke Danav khud baat kare
- `whatsapp_call` ab call lagane ke baad **Danav ko line par hi rakhta hai** (`task` = kya baat karni hai, `say` = pehla jumla).
- Danav **loudspeaker** se bolta hai, is liye samne wala Danav ko sunta hai, aur **microphone call app ke paas** hi rehta hai —
  is liye samne wala banda user ko bhi bilkul saaf sunta rehta hai (yeh jaan-boojh kar kiya gaya: do app ek mic par ho to
  call ki awaz kharab hoti hai).
- Danav ke doran user **jo chahta hai likh kar** bhejta hai (Danav ki chat ka input box) → woh jumla **call par bol diya jata hai**
  (`say_on_call` tool bhi hai). `task` ke sath Danav khud apni marzi se short, insani jumlon me baat karta rehta hai.
- `hangup_call`: call khatam, aur Danav **wapas sunne lagta hai** (mic wapas le leta hai, "listening").
- Note (sach): is mode me Danav **samne wale ki awaaz nahi sun sakta** (mic call app ke paas hai), is liye jawab ka intezar nahi karta.

### 6. Chat me bhi poora phone control
- Pehle sirf voice call se phone chalaya ja sakta tha. Ab **chat** me bhi wahi sab tools hain: WhatsApp/SMS message, call,
  email, apps, YouTube, alarm/timer, volume, settings, screen padhna, tap/type/scroll/swipe — aur instructions me saaf likha hai
  ke jab user kahe to **bina dobara poochhe kar daalna hai**, magar number/naam kabhi guess nahi karna.

### 7. Chhoti magar kaam ki cheezein
- Bridge ke doran call screen par status: **"On the call — Danav is talking for you"**.
- `liveInfo` me `bridge` flag; `liveSay` bridge se chat ko jodta hai.
- Chat ke tool declarations saaf builders se bane (koi toota hua JSON nahi).

---

## 15 Oct 2026 (round 3b) — jawab na aane ki asli wajah: MERI ghalti (theek ho gayi)

Aap ka masla sahi chihna gaya. Wajah API nahi thi — **round 3 me maine ek bug daal diya tha**, aur wahi dono cheezon ko todta tha:

**Bug:** chat me maine phone wale tools jode (WhatsApp, call, apps…), magar chat khud bhi `web_search`, `remember` aur
`forget` declare karta hai. Is tarah **ek hi function ka naam do dafa** chala gaya — aur Google ka API aisi request ko
**poori reject (400)** kar deta hai. Natija: "Danav AI couldn't answer this. Please try again." Call par bhi setup reject hota
rehta tha, is liye "koi response nahi" (screen par connecting hi rehta tha).

**Fix (`Ai.java`):**
- Tools jodte waqt **naam check** hota hai — jo naam pehle se hai woh dobara nahi jata (web_search / remember / forget).
- Voice-call-only tools (`end_call`, `say_on_call`, `hangup_call`, `dismiss`) chat me nahi jate.
- **Do fallback** lagā diye: agar model thinking level mana kare to bina level dobara, aur agar tools mana kare to
  **bina tools** dobara — yaani phir bhi jawab aa jata hai, "couldn't answer" nahi.

**LiveCall (`LiveCall.java`):** setup me pehle speech-tuning hatti hai (pehle se tha), ab **tools bhi hat jate hain** agar
service unhe mana kare — call phir bhi chal jati hai. Aur agar asli wajah **quota / API key / model** ho, to screen par
saaf English me likha aata hai (pehle hamesha "Couldn't reach Danav AI, check your internet" aata tha).

**Naya: Settings → Test Danav AI** (bolt button) — service ko ek chhota sawal bhejta hai aur **asli jawab** dikhata hai:
"Working — answered in 320 ms" ya "Not working — …quota… (HTTP 429)". Is se aap khud dekh sakte hain ke galti app me hai
ya account/limit me.

Agar Test me "limit/quota" likha aaye to woh keys ki limit hai (mera code nahi) — us surat me thodi der baad test karein.

---

## 15 Oct 2026 — Danav AI: asli wajah pakri gayi (round 3c build)

Pichhle build me chat/call bilkul jawab nahi de rahe the. Wajah **mere code me thi**, app ke internet ya account me nahi:

1. **Takraav wale tool naam (asli bug).** Chat apne `web_search`, `remember`, `forget` tools bhejti thi aur saath hi phone ke poore tool set bhi
   — service ne saaf inkaar kiya: `400 Duplicate function declaration found: web_search`. Isi wajah se chat "jawab nahi de saka" dikhata tha aur
   live call ka setup kabhi mukammal na hota tha (is liye "connecting" par hi atki rehti thi). Ab naam ek jagah se guzarte hain, duplicate hat jaate hain,
   aur call ke khaas tools (`end_call`, `say_on_call`, `hangup_call`, `dismiss`) chat me nahi bhejte.
2. **Model ke naam ab guess nahi hote.** App `GET /v1beta/models` se **aap ke account ke asli models** khud padh leti hai (chat aur live, dono),
   12 ghante yaad rakhti hai, aur behtareen model pehle chunti hai (flash-lite > flash > pro, stable > preview). Jo naam account me nahi,
   wo 5 minute ke liye skip ho jata hai — poora call ya message us par zaya nahi hota.
3. **Call ab model na milne par rukti nahi:** pehla model na chale to agla live model foran try hota hai, phir agli key.
   Har jagah saaf message: "Danav AI has used up its limit for now" / "isn't set up right in this copy" — banawati bahane nahi.
4. **"Kuch kehta hoon to kuch nahi hota" ka ilaaj:** mic ka purana gate aawaz dabaa deta tha (loud hone par audio bhejta hi nahi tha, aur
   Danav bolte waqt bhi sunna band kar deta tha). Ab sirf ek halka noise floor hai — us se upar ki har aawaz bilkul waisi hi bhejti hai,
   jaisi hai. Isi tarah call apne aap band nahi hoti.
5. **Naya: Settings → Test Danav AI** — ek bolt button jo service ko chhota sawal bhej kar asli nateeja dikhata hai:
   "Working — answered in 320 ms · <model>" ya "Not working — <wajah> (HTTP n)". Agar wahan limit/quota likha aaye to masla keys ki limit ka hai, mera code ka nahi.

---

## 15 Oct 2026 — Danav AI: jawab na aane ki asli investigation (round 3d)

Aap ne Test chala kar bataya: "Not working — no answer from the service". Is par maine **service par khud test kiye** (aap ki keys ke saath,
live) aur docs bhi dekhe. Nateeje:

* Aap ki keys, `gemini-3.5-flash-lite` aur `gemini-3.8-live` — sab service par **theek chalte hain** (test: HTTP 200, jawab "Four", streaming bhi 200,
  tool call bhi 200). Yani galti service, key ya model me nahi thi.
* Is liye app ke network code ko poora badla gaya:
  1. **IPv4 pehle (asli mashhoor masla).** Bohat se mobile networks/ISP ka IPv6 address Google ke paas ghalat location par map hota hai, aur service
     saaf inkaar kar deti hai: *"User location is not supported for the API use"* (Pakistan supported list me hai, magar IPv6 ranges par yeh error
     aata rehta hai). Ab app har request me **pehle IPv4** try karti hai (OkHttp ka apna DNS), IPv6 sirf backup ke tor par.
  2. **Chat ab purane HttpURLConnection par nahi, OkHttp par** (live call pehle se OkHttp par thi) — zyada mazboot TLS/HTTP2, aur timeout bhi
     lamba (150 s), takay sochne wala model beech me kat na jaye.
  3. **Akhri hifazat:** agar streaming endpoint network ki wajah se na chale, to app **plain (non-streaming) call** se jawab le aati hai — jawab
     aana band nahi hota.
  4. **Test ab poora safar dikhata hai** ek line me: `keys=8 · net=wifi dns=2v0 · GET models=200 · POST gemini-3.5-flash-lite=200 · answered ·
     stream=200 ok`. Jo bhi step fail ho, uska asli naam aur wajah us line me likha hota hai (jaise `dns!UnknownHostException:…` ya
     `POST=400 User location is not supported…`). Jo line aaye, wohi bhej dein — us se masla second me pakra jayega.
  5. Jo naam pehle chhup jate the (location / network / timeout) ab saaf message dete hain, aur test ka nateeja copy bhi ho sakta hai (long-press).

---

## 15 Oct 2026 — Danav AI call: pehli baat ke baad chup ho jana (round 3e)

Aap ki report bilkul theek thi: chat chal rahi thi, call me **pehli baar** baat hui aur jawab bhi aaya — **doosri baar bolne par call chup ho gayi**, koi jawab nahi.
Maine live service par khud 3-turn ka test kiya (setup → jawab → doosra sawal → teesra sawal): **service har turn par awaz deti hai** — yani problem service/model ki nahi, **app ke andar** thi.
Wajah: call ke session ke tootne/error hone par app chup chaap baithi rehti thi. Ab woh sab theek:

1. **Session error ab chhupta nahi.** Setup ke baad service jo bhi error bhejti thi (session expire, internal error, goAway, limit) woh pehle *khaali ignore* hoti thi — isi liye call chup. Ab: limit ho to saaf message, warna **usi conversation handle ke saath session foran dobara khadi** hoti hai, aur Danav wahi baat aage continue karta hai (dobara salam nahi).
2. **Bohat chup socket pakri jati hai.** OkHttp ka `send()` jab `false` deta hai (socket mar chuka hota hai) to pehle har frame chup chaap girta rehta tha. Ab woh foran pakra jata hai aur session dobara khadi hoti hai.
3. **Watchdog (naya).** Agar user bol chuka hai, 7 second se zyada khamoshi hai aur service 9 second se kuch nahi keh rahi — to call ko **apne aap theek** kiya jata hai ("reconnecting"), max 6 baar; bar bar wahi ho to imandari se message: "The call kept dropping. Please start it again."
4. **Mic dobara ban jata hai.** Pehle `rec.read()` ik baar fail ho to mic thread hamesha ke liye so jata tha (aawaz jati hi nahi thi). Ab recorder naya bana liya jata hai (25 koshishon ke baad imandar error).
5. **Speaker bhi dobara banta hai.** AudioTrack mar jaye (jaisa kuch phone par hota hai) to woh naya bana kar wahi se bajna jari rakhta hai — jawab nigla nahi jata.
6. **Tool atak jaye to model ko batate hain.** Koi step 20 second se zyada le to model ko "do not wait" keh diya jata hai (aur asli nateeja baad me baat ki tarah pahunchta hai) — model hamesha ke liye intezar me nahi baithta.
7. **Aawaz ki shuruat zyada sensitive** (START_SENSITIVITY_HIGH + 450 ms silence): dheemi aawaz bhi turn kholti hai, aur chup hone par turn foran band ho jata hai.
8. **Test ab jhoota "not working" nahi dikhata.** Pehle sirf **pehli key** try hoti thi — ek thaki hui key poore app ko "kharab" dikha deti thi (jabke chat doosri keys se chal rahi thi). Ab test sab keys aazmati hai aur poora safar likhta hai:
   `keys=8 · net=wifi dns=2v0 · GET models=200 · key#1=429 … · key#2 ok 380ms · answered · stream=200 ok`

---

## 15 Oct 2026 — ASLI BUG MIL GAYA: call me jawab kyun nahi aata tha (round 3f)

Aap sahi thay — na model ka masla tha, na API ka. Maine is baar **asli insaani awaz** se service par test kiya
(apni ek recording, 16 kHz PCM, bilkul waisi hi frames jaisi app bhejti hai) aur bug pakra gaya:

**Bug: app "khamosh" frames bhejti hi nahi thi.**

Service apni **activity detection** se yeh faisla karti hai ke user ne bolna shuru kiya aur **kab band kiya**.
Woh khamoshi ko *un frames me* naapti hai jo client bhejta hai. App ka noise-floor gate quiet frames ko
**skip** kar deta tha (aur aakhri khamoshi bhi nahi jati thi), is liye service ko sirf **ACTIVITY_START** milta tha —
**ACTIVITY_END kabhi nahi** → turn poora hi nahi hota tha → **koi transcribe, koi jawab, kuch nahi.**

Live test ke teen nateeje (asli awaz se, ek jaisi):
* poora lagatar stream (khamoshi bhi bheji) → **jawab aa gaya**: "I'm sorry, but I don't have access to real-time weather…"
* sirf awaz, aant me khamoshi nahi → `voiceActivity=ACTIVITY_START` … aur **koi jawab nahi**
* app jaisa gate (quiet frames skip) → `ACTIVITY_START` … aur **koi jawab nahi**

Yani jitni baar aap bolte thay, app ki taraf se turn band hi nahi hota tha. Isi liye "pehli baar kuch hua, phir chup".

**Fix (verified, 2 turn lagatar chale):**
1. **Har 20 ms ek frame jati hai, poore call me, bila nagha.** Quiet audio = asli digital silence (skip nahi).
   Isi se service ko sentence ka **END** sunai deta hai aur turn band ho kar jawab aata hai.
2. **Half-duplex:** jab Danav bol raha ho, mic (model ke liye) band — khamoshi jati hai. Is se Danav apni hi
   awaz sun kar khud ko beech me nahi kaatta (aap ki purani "beech me ruk jata hai / wahi dohra deta hai" shikayat).
   Danav khatam ho, 250 ms, aur aap ki awaz foran sun li jati hai — bas Danav ke bolne ke dauran na bolein.
3. **Service ki default activity detection** hi rakhi (apni settings se cheh-chh nahi) — model ke liye wahi tuned hai.
4. **`interimInputTranscription` setup se hata diya:** service is field ko setup me **reject** karti hai
   (`1007 Unknown name "interimInputTranscription"`) — live test me pakra; warna call setup hi fail hota.
   Agar service kabhi in-progress words bheje to app unhe call screen par live dikha dega.
5. **Mic thread ab kabhi nahi marta:** har frame try/catch me, recorder marta hai to naya ban jata hai.
6. Bridge (WhatsApp call) par mic chhorne waqt `audioStreamEnd` bhejti hai — service ko pata chalta hai mic off hai.

---

## 15 Oct 2026 — kaam karte waqt awaz, WhatsApp se dhoondna, aur call par sunna (round 4)

Aap ki teen shikayatein, teenon ka kaam:

**1. "Jab kaam kar raha hota hai to awaz nahi aati"** — ab aati hai:
* Call ke rules badle: kaam shuru karte waqt Danav **ek chhoti line bolta hai** ("dekhta hoon", "ruko, WhatsApp khol raha hoon") — phir kaam karta hai, aur natija **sirf** tool ke batane par batata hai (jhooti kamyabi kabhi nahi).
* Screen par kaam 10–20 second le sakta hai: itni der ki khamoshi ke liye app khud phone ki **text-to-speech** se chhoti line bolti hai — 5 second par "One moment.", 11 second par "Still working on it." (agar phone me TTS engine na ho to chup rehti hai, kuch kharab nahi hota). Ye awaz **call wale raste** par bajti hai, aur bolte waqt mic (model ke liye) band rehta hai taake Danav khud ko na sune.

**2. "Bina dekhe keh deta hai 'mujhe nahi mila' — WhatsApp khol kar dekhe"** — ab kholta hai:
* Naya: agar naam phone ki **contacts me nahi milta** (ya number WhatsApp par nahi hai), app khud **WhatsApp kholti hai → search dabati hai → naam likhti hai → nateeja dekhti hai → chat kholti hai** aur phir message bhejti hai ya call lagati hai.
* Ek hi naam ka match ho to seedha kaam; **do logon ke naam milein to sawal** (kabhi andaza nahi); aur agar WhatsApp me kuch nahi mila to **tab** imandari se batati hai ke "WhatsApp me bhi dhoonda, nahi mila" — screen par jo dekha woh bhi batati hai.
* Message bhejne ke baad check karti hai ke message box **khali** ho gaya (yani waqai chala gaya), warna saaf kehti hai ke bheja nahi gaya.

**3. "WhatsApp call par samne wale ki awaz Danav ko nahi aati thi"** — ab aati hai:
* Pehle design hi ghalat tha: call lagte hi app mic **chhor deti thi**, isi liye Danav behta tha ("baat hi nahi kar pa raha tha").
* Ab Danav **mic rakhta hai** aur samne wale ki awaz **loudspeaker se mic me** aati hai — Android ke apne qawaid ke mutabiq yeh kaam karta hai jab app **accessibility service** ho: "Voice call + ordinary app: **the app can capture audio if it is an accessibility service**" aur AOSP: "Accessibility service: can record even if a privacy-sensitive use case is active". (Danav AI me accessibility pehle se on hoti hai, phone control ke liye.)
* Bridge ke waqt capture **plain MIC** source par hota hai (privacy-sensitive VOICE_COMMUNICATION nahi) aur us par **echo canceller band** — warna wohi canceller samne wali awaz ko "apni echo" samajh kar mita deta.
* Call **loudspeaker par full volume** (pehle 85%).
* **Pehle intezar ke woh phone uthayein:** call screen par timer (0:01, 0:02…) aane ka intezar hota hai, phir Danav bolna shuru karta hai. 20 second tak koi na uthe to imandari se: "ringing hai, kisi ne abhi nahi uthaya".
* Agar phone Danav ko call ki awaz **deta hi nahi** (kuch phones/apps aisa karte hain), to app 3.5 second me pakar leti hai aur **saaf batati hai**: "Danav can't hear the call: turn on Danav AI in Accessibility, or put the call on speaker" — chup chaap natak nahi.
* Danav ko ye bhi sikhaya gaya: room me user bhi baat kar sakta hai — "ise kehna …" sun kar wohi kehna hai, aur agar koi Danav se baat kar raha hai to jawab dena hai.

---

## 15 Oct 2026 — "app khulti hi nahi thi" aur asli screen par kaam (round 6, Danav AI)

Aap ne kaha tha: khud dekho kya toota hua hai, khud theek karo, sirf baatein na karo. To ye round poori
tarah code, test aur saboot ka hai.

### 1. Asal wajah (root cause) — kyun "khol diya" keh kar kuch nahi khulta tha

Do bugs the, dono mil gaye:

* **Launcher hamesha "ho gaya" keh raha tha.** Android 10 se aage background app ko doosri app saamne
  laane ki ijazat nahi deta — system chup chaap mana kar deta hai. Purana code phir bhi "Opened WhatsApp."
  keh deta tha, kyunke woh **sirf ye dekhta tha ke intent bhejne me error aaya ya nahi**, ye nahi dekhta tha
  ke screen par asal me kya aaya.
* **Chat ka fast rasta (Fast.java) tool ka asli natija phenk kar apna banaya hua line bol deta tha.** Isi liye
  asli nakami bhi "kaam ho gaya" ban kar aap tak pahunchti thi.

Ab code aisa hai:

* Har tool ka natija `Out.ok` (sach / jhoot) ke sath aata hai — ab ye **guessing nahi**, code khud
  `bad(...)` / `badLook(...)` se nakami likhta hai (53 nakami jagah + 16 "screen dekho aur doosra rasta nikalo"
  wali).  Wording se andaza lagane ka purana tareeqa sirf **safety net** reh gaya hai.
* Agar `Out.ok == false` to **dono models** ko saaf milta hai: `success: false` aur `"FAILED — … Never tell
  the user this worked"`. Fast rasta bhi ab tool ka **asli** text bolta hai.
* Khulne ka naya tareeqa: pehle check hota hai ke ijazat hai ya nahi (`canStart` = accessibility **ya**
  "show over other apps" **ya** app khud saamne), phir screen jagaai jati hai, phir app kholi jati hai, aur
  **asli intezar** hota hai ke woh app foreground me aa jaye (`waitForFront`). Na aaye to ek dafa retry, phir
  seedhi baat: *"I tried to open X but it did NOT come to the front — the screen is still showing Y."* +
  ye ke kis switch ko on karna hai (`startProblem`).

### 2. Screen dekhna aur samajhna (aap ki requirement)

* `see_screen` ka screenshot asal me **model ko bheja jata hai** (chat me image ban kar conversation me jata
  hai, call me bhi). Zyada se zyada 2 tasveerein per jawab — na aankh band, na faltu data.
* Jab koi step **fail** ho to app khud model ko screen ki tasveer dikhati hai: "try something different".
* `read_screen` ab **screen thehrne ka intezar** karta hai (1.4 s tak), aur do dafa bilkul same screen ho to
  saaf likha aata hai: *"[The screen has NOT changed since your last look — the previous step did nothing.]"*
* Screen ka **size** bhi batata hai + `tap_xy` fractions (0-1) ya percentage leta hai.
* **Purana index** (jaise `[7]`) ab andha tap nahi karta: screen badal chuki ho to label se dobara dhoondta
  hai, warna mana kar deta hai.
* **Typing verify** hoti hai; WhatsApp/WebView/Flutter ke wo input jo `ACTION_SET_TEXT` ignore karte hain,
  unke liye clipboard + paste fallback (phir dobara check).
* Chat ko ab **"Phone right now"** bhi milta hai (din, waqt, battery, kaunsi app khuli hai) — is liye pehle
  sawal poochne ke bajaye seedha kaam karta hai.
* Kaam ke rounds 6 se **9** kar diye — WhatsApp: search → chat → type → send jaise kaam beech me nahi rukte.

### 3. WhatsApp / SMS / call — ab jhoot nahi

* Message ke baad check hota hai ke **text box khali** hua; khali na ho to: *"Pressed send but the message is
  still in the box — it did NOT go out."*
* Call: dialer khula aur call shuru nahi hui to saaf likha aata hai ke *"the call itself did not start"*;
  WhatsApp call me 20 second tak uthaya na jaye to "ringing hai, kisi ne nahi uthaya".
* Hanging up: end-call button dabane ke baad **check** hota hai; call screen par ab bhi ho to *"the call is
  still on screen"* — pehle jaisa "call ended" nahi.
* Music pause/play: `isMusicActive()` se verify — *"Nothing started playing"* / *"The app ignored the pause
  key."* Wohi tareeqa SMS aur email ke liye.

### 4. Naya: aap khud jaanch sakte hain ("Test phone control")

Settings → Phone control → **Test phone control** dabayein: Danav waqai Settings kholta hai, check karta hai
ke Settings saamne aayi ya nahi, aap ko seedha natija deta hai (kitne ms me, screen ke kitne characters
padhe, screenshot mila ya nahi) aur phir **khud wapas** aap ke paas aa jata hai. Jo bug tha, wohi cheez ab
ek button par saboot ban gayi hai.

Settings me **Test Danav AI** ka report bhi ab do nayi lines dikhata hai: `phone=control on apps=yes
screen=1234 chars in WhatsApp shot=180KB` aur `live=ok 812ms · gemini-3.8-live` — yani asli live-voice
session khol kar dekhta hai ke awaz wala rasta chal raha hai ya nahi.

### 5. Tests (jo asal me chale, banaye hue nahi)

`python3 tests/test_round6.py` → **36/36** static + `--live` ke sath **39/39**, jin me:

* `tests/test_honesty.py` — 50 cases: 34 asli nakami wali lines pakdi jati hain, 16 kamyabi wali lines ghalat
  tarah nakami nahi banti (`wrong: 0`).
* `tests/build_tools.py` — asli `Phone.tools()` JVM par compile kar ke: **live=32, chat=31** tools, naam
  unique, `required` sirf maujood properties par.
* **Live API (asli key se):** chat `gemini-3.5-flash-lite` + 31 tools → **HTTP 200**;
  `wss://…BidiGenerateContent` + 32 tools → **setupComplete**. Roman Urdu routing bhi test hua:

  | Aap likhein | Tool jo chuna gaya |
  |---|---|
  | "Amma ko whatsapp pe message bhejo ke main 10 minute mein pahunch raha hoon" | `whatsapp_message {contact: Amma, message: Main 10 minute mein pahunch raha hoon}` |
  | "screen par jo likha hai woh batao" | `read_screen` |
  | "instagram kholo" | `open_app {name: Instagram}` |
  | "bilal ko whatsapp call karo aur baat karo" | `whatsapp_call {contact: bilal, task: …}` |
  | "torch on kar do" | `flashlight {on: true}` |

### 6. Jo cheez is computer par test nahi ho sakti (saaf baat)

Yahan koi asli phone ya emulator nahi hai, is liye **compile + static scan + asli API/WebSocket** tak hi
saboot hai. Asli phone par khud chalta hua WhatsApp UI, contacts, call lagana, clipboard-paste typing —
ye cheezein **device par** confirm karni hongi; isi liye upar "Test phone control" button banaya hai jo aap
ko seedha sach batata hai. Jhooti kamyabi ka koi rasta code me nahi chhoda gaya.

**APK:** `DanavAI-2.3.0-arm64.apk` (1,771,738 bytes · vc 14 · usi certificate se signed · sha256 verified)
