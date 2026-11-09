<div align="center">

# ⚔️ THE SYSTEM

### *ARISE. Turn your body into the controller — and discipline into the game.*

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-0496FF?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![Supabase](https://img.shields.io/badge/Backend-Supabase-3ECF8E?style=for-the-badge&logo=supabase&logoColor=white)](https://supabase.com)
[![ML Kit Vision](https://img.shields.io/badge/Edge%20AI-ML%20Kit%20Pose-4285F4?style=for-the-badge&logo=google&logoColor=white)](https://developers.google.com/ml-kit)
[![Build](https://img.shields.io/github/actions/workflow/status/riokosun-design/the-system-android/android.yml?branch=main&style=for-the-badge&label=CI)](https://github.com/riokosun-design/the-system-android/actions/workflows/android.yml)
[![PRs Welcome](https://img.shields.io/badge/PRs-Welcome-brightgreen?style=for-the-badge)](#-contributing)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge)](LICENSE)

**A camera-verified, Solo Leveling–inspired fitness RPG for Android:**
real workouts → quest proofs → EXP → ranks → 1v1 arena wars.
No rep is counted by a tap. Every point is witnessed by on-device vision AI.

</div>

---

## 🧭 Overview

**The System** is a production-grade, privacy-first gamified health engine that asks one brutal question:

> *"What if your life had a progress bar — and the only way to fill it was to actually do the work?"*

It answers with a full interactive-health SaaS blueprint, open source end to end:

- Your **push-ups and squats are counted by on-device pose estimation** — ML Kit / TensorFlow Lite run on the phone itself. A camera frame never leaves the device; only cryptographic rep signatures do.
- Every completed quest feeds a **server-authoritative RPG economy** (EXP, levels, ranks, stat points, decay for missed days) where **all progression math is SQL-side** — the client never mints a single point.
- Hunters duel in **real-time 1v1 Arena battles** over Supabase Realtime, with a server referee validating rep plausibility before any verdict lands.
- A **discipline layer** (accessibility sentinel + 24h watchdog) enforces your own rules: circadian quest windows, app blockers, and a crimson penalty gauntlet when you abandon the grind.
- A full **chess cognition module** (engine, 50-position rated puzzle pool, 64-lesson interactive curriculum) keeps the MIND stat honest, not just the body.

For indie builders, The System is a reference implementation of how far **edge computer vision + Supabase + strict Clean Architecture** goes on a $0 infrastructure budget — tuned to run smooth on 2 GB RAM devices.

---

## ✨ Key Features

### 👁️ Edge Vision AI — `ui/training/`, `ai/`
- Real-time **push-up / squat repetition detection** with the adaptive *Rep Pulse* engine (MoveNet primary, ML Kit failover), live skeleton overlay, and per-rep quality metrics (depth %, tempo, form deviation).
- **Anti-cheat by construction**: reps are kinematically modeled and re-plausibility-checked server-side; an IMU witness corroborates motion. No "I tapped the button" fraud — ever.
- **100% on-device inference.** Works fully offline; no video is buffered, stored, or uploaded.

### 🎮 Gamification Engine — `core/SystemMath.kt` + SQL mirror
- Daily **habit quests** generated per-hunter (circadian-scheduled windows), sequential protocol blocks, and mandatory 15–30 min recovery breaks.
- **EXP curve `100 × N^1.8`**, levels 1–100, rank ladder, forms, XP decay and rank demotion for abandoned days — every rule implemented **twice**: Kotlin client mirror + `security definer` SQL truth.
- Stat allocation, verified personal bests, achievement wall, and an AI-stamped system plan (`ai_generated` honesty flag — content is never faked).

### ⚔️ Real-Time 1v1 Arena — `ui/arena/`
- Live workout duels (push-up / squat) synchronized over **Supabase Realtime channels** with tug-of-war state, ready-up protocol, and countdown sync.
- **Signed rep-event evidence** streams to the server referee; verdicts ship only after a plausibility audit — the low-latency feel of a game, the integrity of a ledger.
- Tournaments, prediction pools, war invites and squad (clan) coordination on top.

### 🏛️ Supabase Backend Architecture — `supabase/`
- **PostgreSQL** with **Row Level Security on every table**, server-owned RPC economy (quests, XP, VC, marketplace, penalties), scheduled jobs via `pg_cron`, and Storage for curated system assets.
- **Edge Functions (Deno/TypeScript)** for the things clients must never touch: daily penalty adjudication, presence sync, media auto-purge, AI quest generation.
- The app ships with the **public anon key only** — the entire trust model is enforced by RLS + RPCs, which is the whole point.

### ♟️ Cognition & Discipline — `chess/`, `service/`
- Deterministic 0x88 chess engine (self-contained, dependency-free), rated MENTAL WAR games, adaptive puzzle director (1–10 rail), and a 64-lesson engine-verified curriculum.
- **Discipline engine**: accessibility-sentinel app wards (Instagram / YouTube & Shorts kill-switch / adult-content shield), circadian quest-window seals, and a 24h **watchdog** (WorkManager + AlarmManager) whose breaches trigger a crimson redemption gauntlet.

---

## 🛠️ Tech Stack

| Category | Tools |
|---|---|
| **Mobile** | Kotlin 2.0 · Jetpack Compose (Material3, BOM 2024.10) · Coroutines & StateFlow · Navigation-Compose · CameraX · WorkManager 2.9 · DataStore · Hilt (DI) · Coil 3 |
| **AI / Vision** | ML Kit Pose Detection 17 · TensorFlow Lite 2.17 (MoveNet) · MediaPipe (on-device LLM tooling, optional shadow lane) · custom temporal rep-classifiers (`RepPulseEngineV5`, TCN shadow scorer) |
| **Backend** | Supabase (`supabase-kt` 3.0.2 — Auth, PostgREST, **Realtime**, Storage, Functions) · PostgreSQL w/ RLS + `security definer` RPCs · `pg_cron` + `pg_net` · Edge Functions (Deno / TypeScript) |
| **Architecture** | Clean Architecture / MVVM · repository-per-domain (`data/repo/`) · server-authoritative economy (client = renderer, never judge) · offline-first caches · `minSdk 26`, low-end ROM verified |

---

## 🏗️ Project Architecture

```text
the-system-android/
├── app/src/main/java/com/thesystem/app/
│   ├── TheSystemApplication.kt        # Channels, watchdog arm, anti-tamper gate
│   ├── MainActivity.kt                # Splash gate → onboarding → MainTabs + NavHost
│   │
│   ├── core/                          # Pure-domain layer (no Android UI)
│   │   ├── SystemMath.kt              # XP/rank/decay/forms math — mirrored in SQL
│   │   ├── di/                        # Hilt modules (SupabaseClient singleton)
│   │   ├── discipline/                # Adult blocklist, ward prefs, typing guard
│   │   ├── security/                  # Anti-tamper perimeter (release builds)
│   │   ├── theme/ · ui/               # Design system: InkBlack/SkyBlue HUD kit
│   │   └── sensors/ · routine/ · sport/
│   │
│   ├── ai/                            # Edge intelligence
│   │   ├── DeterministicAI.kt         # Cold-path planner
│   │   ├── ContextEngine.kt           # Local context assembly (no PII upload)
│   │   └── LocalInferenceRuntime.kt   # On-device LLM runtime lane
│   │
│   ├── chess/                         # 0x88 engine · puzzles · curriculum · director
│   │
│   ├── data/
│   │   ├── model/                     # @Serializable DTOs (QuestDto, BattleDto, …)
│   │   └── repo/                      # System · Auth · Social · Arena · Training repos
│   │
│   ├── service/                       # The sentinels (all verdicts local, offline-safe)
│   │   ├── SystemDisciplineService.kt # Accessibility ward: app locks, shorts killer
│   │   ├── SystemWatchdog.kt          # 24h WorkManager + AlarmManager watchdog
│   │   ├── QuestRhythm.kt             # Circadian quest-window war clock
│   │   ├── PenaltyLockdown.kt         # Crimson redemption gauntlet ledger
│   │   └── StepCounterService.kt      # Foreground HEALTH run/walk tracking
│   │
│   └── ui/
│       ├── tabs/                      # MainTabs scaffold + lockdown banner
│       ├── dashboard/                 # Status: level ring, protocol blocks, buffs
│       ├── training/                  # Vision proof sessions (rep engines, timers)
│       ├── arena/                     # Lobby → battle room (Realtime) → verdict
│       ├── chess/                     # Hub, match, puzzles, lessons, settings
│       ├── discipline/ · penalty/     # Ward control room · server penalty gate
│       ├── profile/ · rank/ · squad/ · market/ · wallet/ · admin/ · …
│
├── supabase/
│   ├── migrations/                    # 001→033: schema, RPC economy, RLS, cron
│   └── functions/                     # Edge Functions: penalties, media purge, …
│
├── docs/                              # Architecture deep-dives (CV battles, AI engine…)
├── ml/                                # Rep-classifier training harness (TCN)
└── .github/workflows/                 # android.yml CI (build + ship on every push)
```

---

## 🚀 Getting Started

### Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or newer
- **JDK 17**, Android SDK 35 (`minSdk 26`)
- A free **[Supabase](https://supabase.com)** project + the [Supabase CLI](https://supabase.com/docs/guides/cli)
- Any Android 8.0+ device or emulator (low-end hardware is a *supported* target)

### 1 · Clone

```bash
git clone https://github.com/riokosun-design/the-system-android.git
cd the-system-android
```

### 2 · Provision the backend (≈10 min)

```bash
supabase login --token <YOUR_ACCESS_TOKEN>
supabase link --project-ref <YOUR_PROJECT_REF>
supabase db push                                   # applies migrations 001→033 (schema, RPCs, RLS, cron)
supabase functions deploy apply-daily-penalties purge-verification-media legal-docs
```

Then in the Supabase dashboard:

1. **Database → Extensions** — enable `pg_cron` and `pg_net` (the scheduler migration wires itself).
2. **Auth → Providers → Google** — create an Android OAuth client (your SHA-1) **and** a Web client ID.
3. Confirm **Realtime** publication is on for the battle tables (covered by the RLS migration).

### 3 · Configure the app

```bash
cp gradle.properties.template gradle.properties
```

Fill in your keys (read at build time into `BuildConfig`):

```properties
SUPABASE_URL=https://<YOUR_PROJECT_REF>.supabase.co
SUPABASE_ANON_KEY=<YOUR_PUBLIC_ANON_KEY>          # anon/public key only — service_role NEVER ships
GOOGLE_SERVER_CLIENT_ID=<YOUR_WEB_OAUTH_CLIENT_ID>.apps.googleusercontent.com
```

### 4 · Build & run

```bash
./gradlew :app:assembleDebug          # or open the folder in Android Studio and press ▶
```

CI (`android.yml`) does the same on every push, so a green badge means a reproducible build.

---

## 🔐 Security & Privacy

| Principle | How it is enforced |
|---|---|
| **Video never leaves the phone** | Pose inference (ML Kit / TFLite) runs fully on-device; only rep counts + signed kinematic event digests are transmitted. |
| **Client is never trusted** | Every XP/VC/economy mutation happens inside `security definer` RPCs; the client holds only the **anon key** and cannot grant itself a point. |
| **RLS on everything** | All tables are behind Row Level Security policies (migrations `003`+); Storage buckets are policy-gated. |
| **service_role stays server-side** | Edge Functions and `pg_cron` jobs are the only holders of elevated keys. |
| **Windows are proof** | Quest completion requires camera/steps/timer *evidence*; server referee replays plausibility (tempo, depth, cadence) before stamping XP. |
| **Media hygiene** | Any verification artifact is auto-purged on a fixed 10-minute cron — retention is an accident this project refuses to have. |
| **Binary integrity** | Release builds run an anti-tamper sweep at process start; debug builds note-and-allow for honest development. |

---

## 🗺️ Roadmap

- [ ] **Wear OS companion** — quest windows on the wrist, HR-corroborated effort scoring
- [ ] **Multi-exercise vision models** — pull-ups, sit-ups, lunges beyond the push-up/squat pair
- [ ] **AI personalized coaching routines** — deeper groove for the server quest engine + on-device plan inference
- [ ] **Online chess league (Neural Link)** — the honest-locked ladder modes, opened
- [ ] **Territory control** — geohash cell conquest using the step counter rails
- [ ] **Squad raids** — cooperative boss battles over arena infrastructure

---

## 🤝 Contributing

Pull requests are genuinely welcome — this codebase loves aggressive ideas executed cleanly.

1. **Fork** the repo and cut a branch: `feature/your-idea`
2. Follow the existing shape: Compose + MVVM + Hilt, server-authoritative math, zero new dependencies without a fight
3. Keep the honesty laws: no fake server state, no silent deletions, low-end-device budget respected
4. Run the battery before pushing: `./gradlew :app:assembleDebug` and `app`'s unit tests (`SystemMathTest`)
5. Open the PR with a clear "what / why / proof" — screenshots or a screen recording go a long way

For large changes (new exercise models, economy rules, Realtime flows), open an issue first so architecture stays coherent.

---

## 📜 License

Distributed under the **MIT License** — see [LICENSE](LICENSE) for the full text.

> *The System is a fan-inspired homage to the Solo Leveling manhwa aesthetic (hunters, ranks, gates, "ARISE").
> It is not affiliated with, endorsed by, or connected to the Solo Leveling IP holders.
> All names, art and code in this repository are original work.*

<div align="center">

**Maintained with obsessive discipline by [Santonu Safi](https://github.com/riokosun-design) — solo independent developer.**

*"Stand up. The System is watching."* ⚔️

</div>
