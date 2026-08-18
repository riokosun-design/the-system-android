# THE SYSTEM — ARISE

Anime-inspired, high-intensity gamified fitness + lifestyle + e-commerce + social ecosystem.
Native Android (Kotlin + Jetpack Compose, MVVM, Hilt) · Supabase (PostgreSQL, RLS, Realtime, Storage, Edge Functions, pg_cron).

```
TheSystem/
├── app/                                   # ANDROID APP
│   ├── src/main/java/com/thesystem/app/
│   │   ├── TheSystemApplication.kt        # Hilt app + osmdroid config
│   │   ├── MainActivity.kt                # Root gate: splash → onboarding → tabs + NavHost
│   │   ├── core/
│   │   │   ├── SystemMath.kt              # XP=100·N^1.8, ranks, decay, forms, prediction math
│   │   │   ├── Geohash.kt                 # 1KM territory cells (precision 6)
│   │   │   ├── theme/SystemTheme.kt       # #0B0E14 / #00F0FF / #9D00FF / #FF0055
│   │   │   ├── ui/Components.kt           # GlowCard, NeonButton, XpRing, RankBadge…
│   │   │   └── di/AppModule.kt            # SupabaseClient (Hilt singleton)
│   │   ├── data/
│   │   │   ├── model/Dtos.kt              # @Serializable DTOs for every table/view
│   │   │   └── repo/                      # Auth · System · Social · Arena · Commerce · Admin
│   │   └── ui/
│   │       ├── splash/                    # 11-variant Canvas splash engine + Crossfade
│   │       ├── onboarding/                # wizard: legal (server-fetched) → metrics → Google → @handle
│   │       ├── dashboard/                 # Tab 1: level/XP/rank/quests/buffs/decay
│   │       ├── territory/                 # Tab 2: osmdroid dark map + 1KM zones + geofences
│   │       ├── arena/                     # Tab 3: battles, tournaments, prediction, ranks + war room
│   │       ├── protocol/                  # Tab 4: Black/White Rooms, arcs, guilds
│   │       ├── profile/                   # Tab 5: stats, forms, wallet, referrals, CPA wall, store
│   │       ├── chat/                      # Clan room + global DM (Realtime)
│   │       └── admin/                     # CMS: legal, store, tournaments, payments, pools, assets
│   └── src/test/…/SystemMathTest.kt       # rule-engine drift guard
└── supabase/
    ├── migrations/
    │   ├── 001_schema.sql                 # 20+ tables, views, indexes, buckets
    │   ├── 002_functions.sql              # RBAC trigger, XP/penalty engine, all RPCs, settle, purge
    │   ├── 003_rls.sql                    # RLS on everything + storage policies + realtime publication
    │   ├── 004_cron.sql                   # 1-min media purge + daily penalty engine
    │   └── 005_seed.sql                   # legal docs, arcs, store, config, asset slots
    └── functions/
        ├── purge-verification-media/      # Edge Function: 10-minute auto-purge
        ├── settle-prediction-pool/        # Edge Function: settle solo & clan pools (0.85 formula)
        ├── apply-daily-penalties/         # Edge Function: decay/GARBAGE/LOSER runner
        └── cpa-postback/                  # Offerwall server→server VC crediting (2% referral cut)
```

## 1 — Backend setup (≈10 min)

```bash
# with a Supabase personal access token
supabase login --token <YOUR_ACCESS_TOKEN>
supabase link --project-ref <YOUR_PROJECT_REF>
supabase db push                 # applies 001→005
supabase functions deploy purge-verification-media settle-prediction-pool apply-daily-penalties cpa-postback
supabase secrets set CPA_POSTBACK_SECRET=<random-64>
```

Then in the dashboard:
1. **Auth → Providers → Google**: create OAuth clients (Android with your SHA-1 + **Web** client).
2. **Database → Extensions**: enable `pg_cron` and `pg_net` (004_cron.sql schedules itself).
3. **Authentication → Realtime**: already covered by publication in 003_rls.sql.
4. Upload your anime art to the `system_assets` bucket via **Admin → ASSETS** in the app.

### Security model
- App ships with the **anon (public) key only**; every table is RLS-locked; all money/XP moves go through
  `security definer` RPCs (`join_tournament`, `place_bet`, `settle_prediction_pool`, `create_clan`…).
- The **service_role key is used only** by Edge Functions/cron. Never put it in the app.
- The first 3 registered users become `SUPER_ADMIN` automatically (trigger, race-safe advisory lock).

## 2 — App setup

In `gradle.properties` (or env vars):
```properties
SUPABASE_URL=https://<YOUR_PROJECT_REF>.supabase.co
SUPABASE_ANON_KEY=<YOUR_PUBLIC_ANON_KEY>
GOOGLE_SERVER_CLIENT_ID=<WEB OAuth client id>.apps.googleusercontent.com
OFFERWALL_URL=https://your-cpalead-offerwall-url
```
Then: `./gradlew :app:assembleDebug` (or open in Android Studio). minSdk 26 — tuned for low-end devices.

## 3 — Rulebook implemented (client + SQL mirror)

| Rule | Location |
|---|---|
| XP = 100 × N^1.8 → level 1–100 | `SystemMath.kt` / `xp_required_for_level()` |
| Ranks AVERAGE→ELITE→S-RANK→MASTERPIECE; 3d skip→GARBAGE, 5d→LOSER | `rankFor()` / `apply_daily_penalties()` |
| Decay −3%×missed-day XP daily; arcs extend by penalty days | same |
| Forms 1–5 @ levels 5/20/40/60/85; Power=(base+XP)×style×hardWork(0.5–3) | `maybe_unlock_forms()` |
| Payout = (Bet/WinningPool)×(Total×0.85), 15% house cut | `settle_prediction_pool()` |
| Clan gate LV30 + 500 VC; shield tax 5% to treasury | `create_clan()` / `apply_xp()` |
| Battle +150/−? XP → +150 winner, +20 loser | `finish_battle()` |
| Referral cuts 10% merch/black-room · 5% winnings · 2% CPA | `review_payment`, `cpa_postback`, settle |
| Media purge exactly +10:00 | `purge_workout_media()` (cron minutely) |
| First 3 users → SUPER_ADMIN | `handle_new_user()` trigger |

## 4 — MVP honesty notes
- Tournament **bracket auto-generation** (pairing rounds) is a follow-up: fixtures/prices/fees/joins are done; seed rounds via Admin.
- Offerwall ships as an external URL + postback endpoint — CPALead/Tapjoy native SDK can replace it later.
- Black Room premium routines currently unlock via `black_room_until`; content lives in `training_arcs.routine` JSONB (editable in DB or extended Admin UI).
