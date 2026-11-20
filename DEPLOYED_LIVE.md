# THE SYSTEM — LIVE BACKEND STATUS (deployed 2026-08-09)

## Connected project
- **Project ref:** `qpxxthjndnqtgjlrwteq` (region ap-southeast-1, Postgres 17.6)
- **Project URL:** https://qpxxthjndnqtgjlrwteq.supabase.co
- **App wiring:** `gradle.properties` already contains the live URL + anon key.

## What is live (verified against production)
| Component | Status | Proof |
|---|---|---|
| Schema: 25 tables, 8 views, indexes | ✅ | `supabase/migrations/001–005` pushed via Management API |
| RLS on 100% of tables | ✅ | anonymous write to `legal_documents` → `42501` blocked |
| First-3 → SUPER_ADMIN trigger | ✅ | users #1–3 became SUPER_ADMIN, #4 stayed USER |
| `users_guard` anti-escalation | ✅ | regular user XP mint attempt → `forbidden_columns`, XP unchanged |
| Leguna quest engine | ✅ | 3 quests generated; complete → +61 XP, +15 VC, streak 1 |
| VC ledger | ✅ | SIGNUP_BONUS 100 → QUEST_REWARD 15 trail intact |
| Tournament entry-fee gate | ✅ | join @100 VC vs 250 fee → `insufficient_vc`, balance untouched |
| pg_cron jobs | ✅ | `purge-workout-media` (every min) + `daily-penalty-engine` (00:05) both active |
| Storage buckets | ✅ | 5 buckets, public/private as designed |
| Realtime publication | ✅ | `messages`, `battles`, `prediction_pools`, `users` |
| Edge Functions ×4 | ✅ | deployed & smoke-tested (see below) |
| Test data | ✅ cleaned | 0 users remain — **YOUR first registration becomes SUPER_ADMIN** |

### Edge Function endpoints (all live, no-JWT-verify per config)
- `POST /functions/v1/purge-verification-media` → `{"purged":0}` ✓
- `POST /functions/v1/apply-daily-penalties` → `{"hunters_penalized":0}` ✓
- `POST /functions/v1/settle-prediction-pool` `{poolId, winner}` → validation ✓
- `GET  /functions/v1/cpa-postback?subid=…&vc=…&ref=…&secret=…` → 401 without secret ✓

### CPA postback secret (configure in your CPALead/Tapjoy dashboard)
```
sys_29d18618aece4cf2a1110027d51a4269efe16ffdca71c631
```
Postback URL template:
```
https://qpxxthjndnqtgjlrwteq.supabase.co/functions/v1/cpa-postback?subid={aff_sub}&vc={virtual_currency}&ref={transaction_id}&secret=sys_29d18618aece4cf2a1110027d51a4269efe16ffdca71c631
```
(Set your offerwall base URL later via `system_config.offerwall_url` from the Admin Panel — no app update needed.)

## Remaining checklist (needs your accounts, can't be done with a token)
1. **Google sign-in:** Google Cloud Console → OAuth clients → create **Web** client + **Android** client (SHA-1 of your keystore). Put the Web client ID into the Google provider page (Supabase → Auth → Providers → Google) and into `gradle.properties` as `GOOGLE_SERVER_CLIENT_ID`.
2. **Theme art:** build the app, register (you'll be SUPER_ADMIN #1), then upload the character wallpapers in **Admin → ASSETS** (`system_assets` bucket) — the whole app re-themes instantly, no release needed.
3. **UPI ID:** set your real receiving UPI id via `system_config.upi_id` (or Admin config) so Black Room payments route to you.
4. **Build:** `cd TheSystem && ./gradlew :app:assembleDebug`.

## Operational notes
- Rotate the personal access token you shared once you're comfortable: Dashboard → Account → Access Tokens.
- Never place the `service_role` key in the Android app; it exists only in Edge Function env vars.
- Daily penalty engine runs 00:05 UTC (db timezone). Media purge runs every minute, expiring at exactly +10:00.

## Reference-code merge (2026-08-09)
An external reference codebase was audited against this implementation. **Adopted (and pushed live):**
- `join_tournament`: CLAN wars now debit the guild **treasury** (officer-gated, row-locked; new error `treasury_insufficient` → user toast mapped in `ArenaViewModel`). SOLO still charges the hunter's wallet.
**Rejected after audit:** its schema/RLS (user self-mint of role/XP via PATCH, victim-UUID wallet drain in `join_tournament`, unguarded `settle_prediction_pool`), pre-auth legal fetch blocked by RLS, hardcoded quests, static placeholder screens, non-compiling chat realtime, broken OAuth flow, and admin-write-only territories (no capture log/leadership/claim path). Details are in the audit reply in chat.

# ── KEYSTORES (generated 2026-08-20, sandbox) ──
# Debug:   keystore/debug.keystore (COMMITTED on purpose — identical SHA-1 everywhere)
#   SHA1:   DE:4A:41:E6:95:2E:9D:AA:10:E6:B7:3E:69:DB:DD:47:F0:3B:39:88
# Release: keystore/thesystem-release.keystore (GITIGNORED — never commit!)
#   SHA1:   C9:9C:AA:C2:56:49:35:48:A0:54:5B:67:D9:BE:6F:D1:61:C3:42:DA
#   SHA256: 5A:40:A5:F6:65:92:99:A9:A7:D9:3E:7E:4B:55:E6:5B:83:33:CC:F8:68:8B:7E:F3:92:2D:C0:50:D7:B1:0F:7D
#   creds in gitignored gradle.properties (RELEASE_STORE_PASSWORD / RELEASE_KEY_PASSWORD)
#   base64 for GitHub secret: keystore/release.keystore.base64.txt (gitignored)
# Package: com.thesystem.app | Play App Signing SHA-1 add later from Play Console.

# ── CI BUILDS (2026-08-21) ──
# Run history: fail(deps) → fail(92 compile errors) → fix round 1 → fail(gradlew exec bit) → ✅ SUCCESS
# Final: 46.6MB debug APK, signer SHA-1 VERIFIED = DE:4A:41:E6:95:2E:9D:AA:10:E6:B7:3E:69:DB:DD:47:F0:3B:39:88 (matches committed debug.keystore)
# Deps fixed: coil-network-okhttp (was okhttp3), mlkit pose 17.0.0 (18.x = beta-only)
# Pending user action: Google OAuth clients + GitHub secrets per GOOGLE_OAUTH_PHONE_GUIDE.md

# ── DESIGN 2.0 "QUIET POWER" (2026-08-21) ──
# User rejected neon/glow design 1.0: "too much text, unprofessional colors, want neat & clean"
# DONE: SystemTheme.kt tokens 2.0 (one-accent sky #38BDF8, neutral #0A0B0E, semantic-only
#   color roles, sans typography) — pushed, CI ✅ green, new-palette APK in artifacts
# DONE: TheSystem-UI-Walkthrough.html rebuilt in new language (6 screens) — USER APPROVED "way more better than I expect"
# NEXT (Round 7, user returns tomorrow to "add spice"): implement mockup layouts in Compose —
#   Components.kt rebuild (GlowCard → hairline rows), Status, Arena, Map, Vault, Gate;
#   halve text, kill bounce-spam, keep smooth springs. push.sh helper at /home/user/push.sh

# ── CINEMATIC LAUNCH FLOW (Round 7, 2026-08-22) — CI GREEN ✅ ──
# SplashScreens.kt (flow): SplashUiState sealed machine — VesselIntake(pre-splash) →
#   Awakening(glitch+stat projection) → ContractGate(fingerprint hold) → Evaluation(wheels+archetype) → AuthGate(S-Rank key)
# OnboardingGate.kt (primitives): neonGlow(drawWithCache), SystemBackdrop(26 motes, #07090E),
#   GlitchRevealText, TerminalLine, FingerprintHold(1.5s+haptics@120ms+flash), WheelRow(snap),
#   ArchetypeCard(Monarch=STRENGTH/WindWalker=SHRED/Titan=BULK), GateKeyButton, HoloStatusPanel, LegalFooter(ClickableText)
# Persistence/auth via OnboardingViewModel (username live-check, legal docs, Google sign-in, finish)
# MainActivity: NeedsOnboarding → LaunchFlowScreen; signed-in → DynamicSplash only

# ── GITLAB MIRROR (2026-09-02) ──
# https://gitlab.com/reactify54/the-system-android (PRIVATE, id 86052797)
# remote name: gitlab | full history pushed incl. CI, supabase, keystores(debug)
# git config not persisted across sessions → re-add remote URL per push (token in chat history)
# GitHub (origin) remains primary CI; GitLab = mirror/backup

# ── GITLAB (2026-09-03 update) ──
# CANONICAL: https://gitlab.com/striker-group3/the-system-android (id 86053901, private)
#   full mirror of origin/main, head sha verified identical
#   remote name: gitlab → points here now
# GitLab Duo: ENABLED on striker-group3 (group-level toggle ON)
# User is OWNER of striker-group3 (id 141213552)
# Transfer API silently no-op'd (200 but no move) → recreated in-group instead
# Old personal repo reactify54/the-system-android (id 86052797) left as-is (duplicate);
#   gitlab.com API blocked delete/archive/transfer on it → UI delete: Settings > Advanced > Delete

# ── DESIGN 2.5 "MONARCH EDGE" (2026-09-03) — CI GREEN ✅ ──
# core/ui/Hud.kt: chamferShape(Shape+createOutline), hudFrame(1dp+ticks+corner glow, drawWithCache), HudFrameCard, HudTag
# Quest windows → [ DAILY QUEST ] + [COMPLETE]/[IN PROGRESS]/[PENALTY RISK] (hour>=21 or missedDays>0)
# Arena live battles → HudFrameCard; War Room → PoseMeshOverlay (landmarks from PosePushUpCounter, kinetic rings, scanline, chest rep-burst), in-frame rep readout
# FingerprintHold → seal shockwave rings @100%
# NOTE: GitLab PAT lost git-HTTP access (API OK, ls-remote 401) — mirror sync pending; GitHub canonical

## 2026-09-04 — PLAY READINESS SWEEP (commit 6990d1f, CI GREEN)
- App icon: monarch hex-crown-eye adaptive set (5 densities, round, themed monochrome), legacy rounded tiles, store 512 → icon-work/out/store/
- delete_account() RPC (007_compliance.sql — cascades verified) + Vault DANGER ZONE UI → user must run 007 in SQL editor (PAT dead: 401 on mgmt API)
- legal-docs edge fn written (JWT off, HTML pages) → user deploys via dashboard paste
- Play assets: feature_graphic.png 1024x500 + play_store_512.png in workspace icon-work/out/store/
- Launch guide: /home/user/PLAY_STORE_LAUNCH_GUIDE.md (4 user steps + listing copy + bg-location justification)

## 2026-09-04 (2) — REBRAND: S-eye logo (commit bd23eb7, CI GREEN)
- User-supplied S-eye sigil (uploads/IMG-20260904-WA7091.jpg) = official launcher icon
- Pipeline: border-seeded BFS corner cleanup (squircle artifact fix) + MaxFilter dilate → adaptive 82% safe-zone + monochrome + legacy round + store 512
- Feature graphic recomposed: fg_bg.png cinematic hunter + real S-eye logo composite → icon-work/out/store/feature_graphic.png


## 2026-09-06 — UI ENGINEERING PASS 2.6 (commit 20e3d66, CI GREEN)
- SystemTheme: unified typography (ExBold -0.02em headings, 14sp/20lh body), Grid object (4dp baseline, Margin=20dp), FloatingSurface 0xCC0E121B + Hairline 0x1AFFFFFF tokens
- Components: SystemBackground vignette + aura clamp 0.12-0.16f; new FloatingIconButton (44dp contrast shell), SystemChip, BoxScope.StickyActionBar
- Dashboard: Grid margins/spacing; quest cards use SystemChip "+N XP"; sticky NEXT QUEST bar w/ LazyListState scroll-targeting first open quest
- Territory: rounded+bordered map container; floating zoom/recenter rail (contrast-safe); SystemMapView now returns MapView
- All tab screens: 16dp -> Grid.Margin sweep; HudFrameCard padding gridded
- Fixes in-flight: Dashboard when-closer dup; FloatingIconButton onClick -> last param (trailing lambda rule)


## 2026-09-06 (2) — THE HUNTER FEED (commit 92edc53, CI GREEN)
- 008_hunter_feed.sql: hunter_posts (parent threads + quote), hunter_interactions (mana_boost/transmit/challenge), feed view, RLS public-read/write-own, realtime publication
- SocialRepository: hunterFeed/myFeedInteractions/hunterFeedFlow (2 chans)/createPost/toggleMana/retransmit/markChallenge/deletePost
- ui/social/: HunterFeedViewModel (scope lenses + optimistic mana + debounced realtime resync) + HunterFeedScreen (twitter rows, rank rings per spec, mana pulse, quote card, glyph action bar, FAB 56dp, composer+thread dialogs, markdown-lite, relTime)
- Tabs 2.7: Status/Feed/Arena/Map/Vault; Protocol → Routes.PROTOCOL via Status header lock; challenge → direct battle room via Routes.battle
- ⚠️ edit_file lesson: parallel same-file edits RACE — serialize same-file edits
- USER: must run 007+008 in Supabase SQL editor


## 2026-09-10 — THE AWAKENING: 27-page cinematic onboarding (commit 9f89d11, CI GREEN)
- Replaces LaunchFlow as the NeedsOnboarding route (MainActivity one-line swap; SplashScreens.kt retained — Archetype enum still sourced from it)
- AwakeningFlow.kt (1,549 lines): 5 phases, EXACTLY 27 pages — P01-06 intro / P07-11 intake / P12-18 analysis / P19-24 transformation / P25-27 contract
- Animation kit: per-page 1150ms master clock + seg() windowing; appear/popIn graphicsLayer-only modifiers; AnimatedContent transition (fade+slide/4+scale 0.965 in, slide -1/5 + scale 1.03 out, togetherWith)
- P07 name: mono vessel-tag field, scanning underline, LIVE username availability (400ms debounce) — LOCK TAG gated on usernameAvailable==true
- P08-10: age 13–30 wheel + ruler/gauge canvases + snap wheel pickers feeding OnboardingViewModel setters
- P11: Archetype ×3 + baseline Activity (SEDENTARY 0.6 / STEADY 1.0 / RELENTLESS 1.4) → setGoal maps to STRENGTH/SHRED/BULK
- P13-16: stat charge meters with personalities (STR 1900ms crimson / VIT 1600 green / AGI 850 blue / PER 1300 purple), auto-advance
- P17: POWER equation chips + GRAPH 2 radial power composition + rolling counter (power = SystemMath shape: FORM_BASE[1]×100 × style × hardWork)
- P18: dark pause → INITIAL RANK = SystemMath.rankFor(1,0) = AVERAGE (honest) + GRAPH 1 segmented stat blocks (12/row)
- P19-21: 90-day arc — timeline marker, 30-cell streak grid, 2.6× projection + honesty line ("projection, not promise")
- P24: rewards honesty — NO guaranteed-income claims ("EARN THROUGH YOUR GRIND / UNLOCK REWARD OPPORTUNITIES")
- P25: consequences — XP decay bar 100→73, rank degradation flicker (CrimsonRed)
- P26: FingerprintHold 1.5s seal + FlashRipple + haptic slam → "SYSTEM CONTRACT ACCEPTED"
- P27: VESSEL VERIFIED + HoloStatusPanel + GateKeyButton CONTINUE WITH GOOGLE (loading on signingIn||busy) + LegalFooter + doc dialog; signedInProfile watcher → vm.finish(onDone) (claimUsername → completeOnboarding → referral)
- 7 ORIGINAL noir art plates (generated, desaturated 0.72, brightness 0.90, 720×1280, 35–77KB) in drawable-nodpi: nomer/proof/territory/transform/conquer/rewards/gate — ~10–16% prominence, vignette, slow zoom (graphicsLayer)
- Perf law: graphicsLayer-only motion, ≤1 extra Canvas/page, 60fps budget target; zero backend changes; OnboardingViewModel untouched
- Build fix in-flight: lerp → androidx.compose.ui.util.lerp

## 2026-09-10 (2) — ERROR SWEEP (commit a669a9e, CI GREEN — build log verified: 0 errors / 0 warnings)
- Compiler warnings 6→0: LocalLifecycleOwner x2 (BattleRoom, MapEngine) -> androidx.lifecycle.compose; osmdroid setBuiltInZoomControls -> zoomController.setVisibility(NEVER) [custom rail owns zoom]; Icons.Default.Send -> AutoMirrored x2 (HunterFeedScreen, MainTabs FEED slot); LegalFooter ClickableText -> Text + LinkAnnotation.Clickable + LinkInteractionListener (compose 1.7 API)
- Logic: P17 power counter zero-start target (AnimatedCounter remembers FIRST target — roll only fires on change; spec "rolling number" now honored)
- Logic: BackHandler in Awakening host — system back rewinds page N->N-1 instead of ejecting from the ritual (key(p) state reboots per page entry, auto-advance pages simply replay)
- Verified clean: 0 TODO/FIXME/XXX in tree; no readers of dead ui.page outside unused legacy screens — OnboardingViewModel contract deliberately UNTOUCHED
- Gotcha: .git/config excluded from workspace snapshots -> re-add token remote EVERY session (push failed w/o it)

## 2026-09-10 (3) — BACKEND TRANSPLANT: new project from scratch (commit d046d92, user-supplied PAT)
- NEW project: bacwuvdomvvfavbwgvid "The system" (ap-northeast-2, org "Astha's Org") — created by user 2026-09-10; OLD qpxxthjndnqtgjlrwteq abandoned (different account — no agent access; delete/pause from old account's dashboard if desired, else free tier auto-pauses)
- Full from-scratch replay via Mgmt API /database/query: 001→008 ALL OK. Verified: 36 tables, RLS 27/27, 80 functions, cron jobs (purge-workout-media 1min + daily-penalty-engine 00:05 UTC), supabase_realtime 6 tables, extensions pg_cron/pg_net/pgcrypto/uuid-ossp
- 5 edge functions deployed via POST /functions (raw source): legal-docs ACTIVE verify_jwt=FALSE (smoke: ?doc=privacy 200 HTML), cpa-postback verify_jwt=FALSE (own ?secret= auth), apply-daily-penalties / purge-verification-media / settle-prediction-pool verify_jwt=TRUE
- CPA_POSTBACK_SECRET generated + set via /secrets (value in ~/.backend-build/cpa_secret.txt — configure at offerwall w/ new URL https://bacwuvdomvvfavbwgvid.supabase.co/functions/v1/cpa-postback)
- Auth: Google provider ENABLED + skip-nonce TRUE (mobile ID-token flow, no secret needed) — CLIENT-ID ALLOWLIST EMPTY: pending user's Web Client ID (Google Console → Credentials → "The System Web"; one PATCH /config/auth closes it). Gotcha: Mgmt API /config/auth accepts SNAKE-case only; SCREAMING_CASE fields no-op with 200.
- App rewire: local gradle.properties + workflow CI fallback + supabase/config.toml → new ref+anon; GitHub secrets SUPABASE_URL/SUPABASE_ANON_KEY set via sealed-box API (201)
- Smoke green: auth v2.196.0 health, anon REST legal_documents (2 docs), hunter_posts_feed view
- Vault: ~/.backend-build/keys.json (anon+service_role), cpa_secret.txt — user's admin backup, NOT committed anywhere
- STILL USER-SIDE: paste Web Client ID (closes Google sign-in on NEW backend) → also confirm GitHub secret GOOGLE_SERVER_CLIENT_ID holds the REAL web id (else sign-in builds w/ placeholder)

## 2026-09-10 (4) — GOOGLE GATE CLOSED (user pasted Web Client ID 893566462152-…l3ff)
- PATCH /config/auth: external_google_client_id = 893566462152-6mpsgsolui5loqafoqdga7kf0fr0l3ff.apps.googleusercontent.com (verified via GET + /auth/v1/settings external.google=true)
- GitHub secret GOOGLE_SERVER_CLIENT_ID sealed (201) → BuildConfig → setServerClientId (BootGate + OnboardingViewModel)
- Instant rebuild dispatched (workflow_dispatch, run 34563595308) — GREEN
- USER-SIDE STILL: consent-screen Test users must include own Gmail (Testing mode) + Android OAuth clients w/ package com.thesystem.app + SHA-1 debug/release must exist in the Google project (guide Step 1) — else Credential Manager throws 10: developer error

## 2026-09-10 (5) — FEED GATE (commit 475a527, CI GREEN — user decision: social deferred, no backend budget)
- FEED tab now renders HunterFeedGateScreen (ui/social/FeedGateScreen.kt): CHANNEL 09 sigil — counter-rotating arc rings + breathing core, staggered entrance, 3 LOCKED rows (GLOBAL DISPATCHES / MANA BOOSTS / SUMMON THE RIVAL), "GRIND IN SILENCE" whisper. Zero network, zero realtime, zero ViewModel.
- Full social layer preserved dormant: HunterFeedScreen + HunterFeedViewModel + 008 tables/RLS/functions untouched — reopen = one-line swap MainTabs.kt FEED branch -> HunterFeedScreen(nav = nav)
- Idle hunter_posts tables cost ~0 on free tier (no channels, no writes)

## 2026-09-10 (6) — AWAKENING 2.0 user-verdict polish (commit 0a376d2, CI GREEN)
- BUTTON BUG ROOT-CAUSED: ContinueBar gated on enabled = clock-seg >= 1f (~943ms dead zone) + appear() moving hit target 26dp mid-mash -> fixed: enabled at 0.3, dy 10dp. GateKeyButton legibility: 16sp ExtraBold + drop shadow, sans subtext 0.9 static alpha, darker metal plate
- TYPOGRAPHY: 58 FontFamily.Monospace call-sites -> Default sans (NO MERCY DNA per user); TerminalLine intentionally mono (P01/P12 boot-log cinema); tap hint 11sp SemiBold
- P09 height: CM | FT·IN toggle chips; FT mode = twin wheels (4..7 ft, 0..11 in) + live 2.54 conversion + "= N CM" caption; cm stays single source of truth
- ART DIRECTOR: 10 NEW original plates generated+processed (gen-limit 10/turn hit — per/power/foundation/discipline/hudcity/decay deferred via mirrored far-apart reuse); pageArtFor() map covers all 20 previously-artless pages; CinematicArt gained mirror param; 17 plates / 1.3MB in drawable-nodpi
- Stat pages inherit sans counters + art layer + chrome sweep

## 2026-09-10 (7) — V2 CUT (commit 1eb702f, CI GREEN)
- versionCode 2 / versionName 0.2.0 — proper upgrade path over any installed V1 APK
- Bundles: new backend (bacwuvdomvvfavbwgvid) + Google gate armed + Awakening 2.0 (art director 17 plates, pro sans type, mash-proof buttons, FT·IN height) + Feed coming-soon gate + zero-warning sweep

## 2026-09-11 — HOTFIX 0.2.1 (commit 09d5e41, CI GREEN) — screenshot-reported dead button
- ROOT CAUSE: P06 GateKeyButton enabled = seg(clock,0.82f) >= 1f, but default span 0.22 caps seg at (1.0-0.82)/0.22 = 0.818 — gate mathematically unreachable -> button rendered lit (popIn uses tighter 0.18 span = completes) yet permanently disabled. Fixed: >= 0.3f wake. Audited all other seg>=1f sites: only visuals (check glyphs, flicker lights)
- PRO IMMERSIVE MODE (user spec): WindowInsetsControllerCompat BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE — nav bar hidden at launch, edge-swipe reveals, custom insets listener re-hides after exact 10s hold (removeCallbacks+postDelayed 10_000)
- versionCode 3 / 0.2.1
- LESSON: any "enabled = seg() >= 1f" with at+span > 1.0 is a compile-clean runtime trap — audit enable gates against clock cap 1.0

## 2026-09-11 (2) — FIELD REPAIR PACK 0.2.2 (commit 8ae7e04, CI GREEN) — user device bug sweep
- MAP BLANK root causes (double): CI builds carried YOUR_MAPTILER_KEY placeholder (GitHub secret was never set — workflow falls back silently) -> secret MAPTILER_API_KEY sealed via API (201) from local gradle.properties; tile style ch-swisstopo-lbm-dark = Swisstopo basemap, NO coverage outside Switzerland -> switched BOTH sites to global streets-v2-dark
- LOCATION: getCurrentLocation one-shot BALANCED -> null-race after GPS wake; now 3x PRIORITY_HIGH_ACCURACY attempts (1.4s backoff) + requestLocationUpdates HIGH 7s/4s-min/8m while Map tab alive; applyFix() shared loader; boards re-fetch only on geohash cell change; tracking stopped in onCleared
- PUSH-UP v2 (PosePushUpCounter): EMA alpha .45 on elbow angle, DOWN<115/UP>145 hysteresis (old: <105 down, >155 full-lockout -> missed reps), 2-frame confirm both transitions, deadband resets, 500ms cooldown, inFrameLikelihood 0.5->0.35 (low light), occluded frames keep state
- DENIED-FOREVER UX: OPEN APP SETTINGS button (ACTION_APPLICATION_DETAILS_SETTINGS) on GPS + camera denied cards
- versionCode 4 / 0.2.2

## 2026-09-12 — USER-MANUAL PHASES AUDIT + REP-COUNT REPAIR (commit f29d01c, CI GREEN)
- User manually shipped MONOCHROME 3.0 + PHASE2/3.2 (v0.3.0→v0.4.1): adaptive quests, predictions, black room, market, training catalog, step service, territory REMOVED, 4-tab menu. 9 commits, 2 mid-refactor RED CIs (self-resolved by 765512a), latest GREEN
- Wire-side verify (PAT was rotated/dead 401, fell back to anon probes): migrations 009+012 ALL APPLIED — 4 new tables 200, ensure_daily_quests 204, black_room_program/claim_prediction_allowance 400 (exist), settle_prediction_pool 22P02 (exists); backend NOT at fault
- PUSH-UP ZERO-COUNT root trio: (a) angle engine vs floor+front camera mismatch — 2D elbow angle constant from below, TOP unreachable; (b) hip-line BAD_ANGLE gate fired every frame in foreshortened view, hard-returned evaluate(); (c) imgW/H rotation-space bug (thresholds+mesh math on wrong axes)
- FIX: front-span push-up cycle (shoulder-width apparent size, self-calibrating lo/hi window, full-cycle discipline kept); visibility shoulders+nose; hip gate removed for PUSHUP; rotation swap 90/270; BattleRoom unified to training/PoseRepCounter keyed by exercise_type; legacy PosePushUpCounter.kt deleted
- versionCode 9 / 0.4.2
- NEW GitHub token in use (user-issued). Supabase PAT sbp_3210… DEAD — user rotated; fresh PAT needed for future DB surgery
- MICRO (deferred): PoseRepCounter.reset() doesn't clear framesWithoutPose/lastStatus (brief stale status ribbon on immediate re-run)

## 0.4.3 / versionCode 10 — `22130dd` (CI GREEN, run 35438979877)
**REP ENGINE v3 — push-up counter ground-up rebuild after field report
"sticks at 1, shows UP but never counts".**
- ROOT PROVEN by exact sim of shipped 0.4.2: session-wide ratchet min/max
  span window + fixed 66%/34% bands → one contaminated extreme (setup
  walking captures spanLo: 0/10 sim; mid-set lean-in captures spanHi:
  3-then-dead sim) makes a band unreachable for the rest of the session.
  Failing layer = rep validation/state transition (4/5), NOT camera/pose/UI.
- v3 = asymmetric-baseline prominence machine: leaky baseline (0.35 top /
  0.10 depth), frozen per-rep trough+peak, slew guard (>19px/frame =
  relocation + 500ms settle), depth window [max(16px,55% ROM)..170% ROM],
  descent >=4f/132ms, >=3 deep frames (spike armour), count on 62% climb-back
  of own depth, 450ms cooldown/min-rep, stale-trough recovery valve (350ms
  climb stall at baseline), ROM learned ONLY from validated reps, pose-loss
  freeze + 1.5s gap re-anchor keeping count, reset() fully clears state.
- VALIDATION: 22/22 scenario matrix PASS (was 4/10 on v2): 15 consecutive
  reps x3 seeds exact, dropouts, drift, ±40% speed, tiny-frame 23px ROM,
  slow 2.6s grinds, fast 0.9s reps, setup-walk + lean-in + combined all
  10/10; nods/11px micro-dips/interleaved half-reps/camera jolts rejected.
- SQUAT path untouched. Sim harness archived at /home/user/rep-sim/.

## 0.5.0 / versionCode 11 — `59ce83f` (CI GREEN, run 35441611516)
**Arena 5-section expansion + detail-sheet architecture + market purge.**
- Migration 013 APPLIED live (new PAT): arena_queue + random_queue_enter /
  random_queue_status / random_queue_leave + arena_queue_gc. Atomic pairing
  (FOR UPDATE SKIP LOCKED), 120s heartbeat expiry, anon → not_authenticated ✓.
- ArenaScreen restructured: 1 MATCHMAKING · 2 RANDOM MATCHMAKING (queue UI,
  SEARCHING pulse → OPPONENT FOUND → ENTER WAR ROOM / DECLINE / CANCEL)
  · 3 PREDICTION/SPECTATOR · 4 LIVE CHALLENGES (real arena_live rows + state
  chips, honest empty) · 5 SCHEDULED CHALLENGES (accept/decline/war room).
  My-open-wars + daily battle challenges kept as supporting tail.
- Shared SystemBottomSheet (scrim + slide/fade + CLOSE): TODAY QUEST opens
  QUEST INFO sheet (inline expansion deleted), CourseInfoPanel + hunter
  record migrated to it. content → sheet → bottom-nav layering fixed.
- Training/market cards: split layout (art block top, solid info panel
  below) — text never touches artwork; 2-line title/desc wraps.
- MARKET PURGE: deleted all 7 005_seed placeholder products from live DB
  (placeholder URLs, never operator-configured; verified none admin-added).
  DIGITAL/AFFILIATE sections now show truthful empty shelves.

## 0.5.1 SHIPPED (218d61f, CI run 35446921919 GREEN) — FLOOR RADAR rep channel
- NEW ui/training/FloorRadar.kt: ProximityRadarCounter (event-driven NEAR→FAR full-cycle machine,
  MIN_DWELL 120ms bounce armor, MIN_REP_INTERVAL 500ms, validated 10/10 in radar_sim.py),
  RadarModeToggle (AI CAMERA / FLOOR RADAR), FloorRadarPanel (sonar HUD, lock tick + rep ACK
  tone, internal rep flash, no-sensor honest state).
- QuestProof: push-up proofs default to FLOOR RADAR (field report: ML Kit loses pose at the
  bottom of floor-level reps = POSE NOT DETECTED flicker + counter stuck). Camera path extracted
  into BoxScope.CameraBox; camera released (unbindAll) on stage exit; radar needs NO permission;
  FLAG_KEEP_SCREEN_ON while proving; quality line switches to SENSOR-VERIFIED + tempo.
- BattleRoom: same channel switch for push-up wars (locked once LIVE), analyzer unbound when
  radar active (no double counting), camera never requested on radar channel.
- Camera mode preserved everywhere (squats + manual toggle). Quest sync pipeline untouched.

## 0.6.0 SHIPPED (5569e2c, CI run 35450276195 GREEN) — REFINEMENT: primary course, RANK ladder, ARENA tabs
- DB mig 014 live: 8 MUSCLE titles renamed to real-world short names (slugs safe);
  verified_bests() RPC (workouts proofs + finished wars).
- SystemMath: HunterTier F..SS ladder (tierFor/nextTier/tierProgress).
- NEW ui/rank/RankScreen.kt + tier emblem + ladder strip + verified performance; RANK is tab #2.
- Dashboard: TRAINING shows ONE primary course panel (art top, compact data, CONTINUE);
  no-enrollment -> CTA to catalog; specials rail = enrolled only; tier badge replaces legacy
  rank title when not penalized; headers 26sp->22sp.
- CourseCard: description stripped (detail lives in COURSE INFO).
- Arena: 5 sections behind SystemTabBar (MATCH/RANDOM/PREDICT/LIVE/PLANNED), no giant page;
  intros trimmed; open wars -> MATCH tail; daily battle challenges -> LIVE tail.

## 0.6.1 SHIPPED (c0cb1ec, CI run 35451969446 GREEN) — FINAL UI CORRECTION
- RANK: original AI-gen cinematic backdrop (drawable-nodpi/rank_bg.jpg, 100% original — no
  copyrighted footage), 26s Ken Burns drift, staged reveal timeline (art -> motes -> emblem
  forms -> ladder -> record), ParticleField (26 seeded motes), radial glow inside emblem.
- Course art: FIXED aspect ratio + ContentScale.Fit on pure black (CourseCard 1.25:1,
  primary panel 16:10) — no more decapitated artwork; card footer: ACTIVE · x% / COMPLETED /
  TAP FOR INFO.
- LOGIC FIX: Special Training law restored — full specials catalog ALWAYS renders with
  ACTIVE x/5 counter (0.6.0 wrongly hid unselected specials; muscle-only rule untouched).

## 0.7.0 SHIPPED (ebde822, CI run 35454767000 GREEN) — STATUS command-center rearrangement
- New hierarchy: HEADER (row only) -> PLAYER SNAPSHOT (LV/RANK/XP bar + streak/next/missed
  micro-stats, giant XP ring removed for compactness) -> TODAY QUEST (title, block/difficulty/
  verification line, charge bar, progress+XP row, QUEST INFO ghost + START/RESUME direct action
  -> proof screen; COMPLETED disabled state) -> PRIMARY TRAINING (unchanged one-course rule)
  -> PERFORMANCE DEVELOPMENT (ACTIVE x/5 counter + art-less compact mini-cards of ACTIVE tracks
  only; empty = NO ACTIVE TRACKS + BROWSE; full catalog behind VIEW ALL) -> PROGRESS strip
  (QUESTS TODAY / TRACKS ACTIVE / VERIFIED SESSIONS from verified_bests — nothing invented)
  -> FormsStrip/BuffsCard tail + 96dp bottom spacer (nav never clipped).
- DashboardViewModel: bests (verified_bests RPC) loaded in refresh.

## 0.7.1 SHIPPED (bbf7b61, CI run 35495221642 GREEN) — RADAR REVOKED (user field report: proximity channel miscounts)
- FLOOR RADAR experiment ENDED by user order: toggle, FloorRadarPanel, ProximityRadarCounter,
  RadarModeToggle, radar state — all deleted. Quest proof + war room are camera-only again.
- Kept: KEEP_SCREEN_ON during proof, camera unbind-on-exit fix, provider-tracked cleanup.

## 0.8.0 GATE PROTOCOL (code 17) — 2026-09-20 · commit d100049 · PHASE 0 of CV-BATTLE-ARCHITECTURE
- NEW core/sensors/ImuStabilityWitness.kt — §2 IMU veto (accel_var<0.35, gyro<0.6 rad/s, 500ms windows)
- NEW ui/training/CalibrationGate.kt — §7 five-stage gate (STABILITY→PERSON→VIEW→LIGHT→TOP_LOCK), CalibProfile(GREEN/YELLOW), shared monochrome GateOverlay HUD; front-view refused outright
- NEW ui/training/PushupEngineV4.kt — §5/§16 deterministic side-view machine: SEARCH→ARMED→DESCENDING→ASCENDING→REVIEW; double depth gate (θ_elbow ≤ θ_depth AND shoulder drop ≥ 0.28 torsos STRICT/0.24 STANDARD); decaying cheat score; REP REVIEW 1.5s; slew guard 0.35 torsos; ROM self-learning from committed reps only; bounce re-entry; walk-away excursions silent
- QuestProofScreen — push-ups: gate→engine v4 front-camera stage, engine-status ribbon (12 statuses), envelope note; squats untouched
- QuestProofViewModel — calibNote + engineStatus channels
- BattleRoomScreen — ranked law: GREEN gate required to arm READY in push-up wars; always-on analyzer routes lobby→gate, live→engine STRICT; squat wars unchanged
- SIM PROOF (rep-sim/v4_sim.py, exact port): 12/12 — 30/30 clean, 25/25 STRICT, 15/15 ¾-view, 0 counts on half reps/nods/hip dips/380ms bounces/hip sags/knee shapes(STRICT); camera bump costs ~1 rep then recovers; pose gap aborts honestly; walk-away silent
- ROADMAP EXIT (§20 phase 0) MET: 25/25 clean side-view + 0 on nod/half abuse — exceeded (sim)

CI: GREEN — run 35498418309 (build fix commit 4d54db6: EMA_Y Float math)

---

## 0.9.0 "PROOF ENGINE" (versionCode 18) — 2026-09-20 — CI GREEN

Phases 1–4 of CV-BATTLE-ARCHITECTURE §20 shipped in one drop (docs/PHASE-1-4-IMPLEMENTATION.md is the honest ledger).

- Commits: 221a6a8 (main drop) → f5952a1 (chore: gitignore pycache) → 1291f77 (TFLite 2.17.1→2.17.0; 2.17.1 never existed) → 4104a3e (TOML # comment) → 4f787c3 (bestElbow nullable frame) — GREEN run 35509701022.
- Phase 1: estimation layer — PoseEstimator iface, MoveNet Thunder int8 (7.1MB asset) w/ EMA ROI lock, ML Kit behind same iface, router w/ fail-streak demotion + parity probe. Gate+engine estimator-agnostic.
- Phase 2: signed evidence — RepEventSigner (sha256 chain + HMAC over server nonce), migration 015 verifier live, 3s flush + final drain, plausibility verdict on finished card. Canonical string byte-identical client⇄server (audited).
- Phase 3: shadow TCN — 89,607-param causal TCN, pure-Kotlin f32 forward, PARITY-VERIFIED vs torch (max |Δp| 5.1e-07). v0 = synthetic bootstrap prior (74.2% agreement); rules ALWAYS decide; tcn_decide=false.
- Phase 4: corpus + liveness — FeatureHarvester (consent-gated, features only), harvest consent card, FlashLiveness 7-pulse strobe (flag-gated), engine_config knobs live.
- Squat untouched. LK bridge deferred (Phase 1.5); Play Integrity scaffolded via flags (needs owner's Play Console).
- Verify on device: gate insists side view; MoveNet vs MLKit parity in harvest meta; battle integrity line after match.

## 0.9.1 "AI ENGINE" — 2026-09-20 — CI GREEN (run 35520212627)

Adaptive AI layer per 28-section spec — incremental, nothing rebuilt.
- Commits: d7821e4 (drop) → 2e5089b (fixes: mediapipe llminference pkg, Quad components, JsonArray.size, horizontalScroll import) — GREEN.
- ai/ pkg: Orchestrator · CapabilityManager (downgrade-only tiers) · RemoteConfig (engine_config.ai, cached) · Runtime iface + MediaPipe 0.10.35 adapter + Noop · ModelManager (sha256 pins, on-demand only) · ContextEngine (sealed snapshots) · AIValidator · DeterministicAI (production brains today) · Observability (technical counters).
- LIVE: AI quest propose→adopt (server RPC law), nutrition vs real inventory (never buys), SYSTEM ROUTINE module, SYSTEM ASSISTANT (4 personas, opt-in memory), admin AI BENCHMARK, vision-sheds-LLM hint.
- Backend 016 verified live: engine_config.ai (local_ai=false pending artifacts), daily_routines, ai_notes, adopt_ai_quest (whitelist/clamps/1-day/dup-guard).
- HONEST: models in catalog have url:null — converted .task bundles + hosting + on-device validation owed before local_ai_enabled flips. APK grows ~44MB (mediapipe native x4 ABI).
- Next owed: SmolLM2/Gemma .task conversion + sha256 pins → catalog URLs → device fleet bench (§21) → flip local_ai_enabled. Voice + routine alarms deferred.

## 0.9.2 "POLISH PASS 1" (versionCode 19) — 2026-09-20

User-reported: crashes, missing animations, text overlap, splash re-arrange, haptics.
- SPLASH: rotation re-arranged per product truth — RANK / BLACK ROOM / DAILY PROTOCOL / ARENA (QuestDonut + BattleSplit canvases promoted from the dead pool; Territory rotation OUT — dead gameplay never advertises the first frame). Onboarding IntroPage synced. RootState.Error now shows TAP TO RE-SYNC retry instead of silent brand loop.
- STABILITY/WEIGHT: abiFilters arm64-v8a+armeabi-v7a — strips x86/x86_64 native blobs (MediaPipe+MLKit ≈60MB unpacked) for 2GB install pressure relief.
- MOTION: enterAnim stagger on Routine items + Assistant header/personality rail; notice path now fires haptics.success + floater (AI ADOPT routes through global notice).
- HAPTICS: AI PROPOSE tick, ADOPT slam, DISMISS tick, benchmark refresh select, routine error/notice confirm family.
- Crash hardening: every 0.9.1 AI callpath re-audited — all suspend calls runCatching-bounded; no AI work at init/startup (verified: orchestrator only on explicit taps; proof screen only sheds LLM).
- Owed next: user device-error screenshots if anything persists (no logcat from cloud).

## 0.10.0 "TACTICAL OS" (versionCode 20) — 2026-09-24 — CI GREEN (run 35971853990, commit 457070d)

25-section UI/UX + feature restructure. Same laws, sharper surface.
- SYSTEM NOTICE (core/ui/SystemNotice.kt): dark rounded panel, hairline border, inner inset box + spinner giveway, single CTA. Three producers live: daily SYSTEM MESSAGE (day-seeded rotating copy, once/day via prefs), missed-day SYSTEM ALERT, BattleRoom arena-loss banner ("STAND BACK UP"). All overlay the frozen page; dismissed by the CTA.
- DAILY PROTOCOL (migration 017 user_commitments LIVE): hunter marks fixed life blocks — RoutineScreen setup sheet (quick-add School/College/Tuition/Work/Football/Martial Arts/Training/Study/Sleep + HH:MM + DAILY/MON-FRI/WEEKEND presets), owner-RLS table, ContextEngine feeds them to the routine engine; DeterministicAI anchors commitments first, quests fill ≥40m gaps, training hunts 17:00+, meals only when clear.
- ROUTINE ALARMS: per-block bell → AlarmEditSheet (time, days preset, ON/OFF, DELETE). Exact alarms where permitted (else inexact fallback), reboot-resilient via RoutineBootReceiver, POST_NOTIFICATIONS requested only when arming. Never forced.
- Dashboard quest card law enforced: BLOCK/DIFF/VERIF line + AI-PROPOSE strip removed from the main card (bonus lives inside QUEST INFO sheet).
- QuestProof camera: 8s init watchdog — slow lens admits CAMERA INITIALIZATION DELAYED + RETRY instead of an infinite black box; giant NN/TT charge counter.
- Assistant: opening line = "What are we working on?" + 5 suggestion tracks (TRAINING/ROUTINE/NUTRITION/PROGRESS/MINDSET).
- Vault rebuild: PERFORMANCE (verified bests w/ honest empty state) + ACHIEVEMENTS + ACCOUNT compact rows; wallet/bounty/referral big cards retired from the stack.
- Market: TRAINING PROGRAMS catalog strip gone per spec (Black Room tile preserved).
- Typography letterSpacing cooled (1.4→0.5sp family).
- Deferred per honesty law: custom alarm SOUND (channel default for now); local-LLM artifacts still pending (local_ai_enabled=false).
- CI: artifact storage quota hit (1.86GB purged via API); APK delivery moved to rolling GitHub Release `nightly-debug` (releases bypass artifact quota; artifacts kept 3d, non-blocking).
- APK: https://github.com/riokosun-design/the-system-android/releases/download/nightly-debug/the-system-debug.apk (121.8MB)

## 0.11.0 "MENTAL ASCENSION" (versionCode 21) — 2026-09-24 — CI GREEN (run 35979556996, commit d4854ec)

Second pillar per 22-section spec: BODY × MIND × DISCIPLINE.
- Mig 018 LIVE: chess_profile (rating F→SSS, mental_xp, 7-stat jsonb), chess_sessions, mental_quest_claims, synergy_claims. Economy 100% server RPC: log_chess_session (clamps + bounded EMA stats), complete_mental_quest (once/day), claim_synergy (+25 XP +5 VC after BOTH body+mind proof, once/day). RLS SELECT-only; no wagering anywhere.
- chess/ pkg: pure-Kotlin 0x88 engine (FEN, full legality: EP/castle/promo/pins) — PERFT VERIFIED (8902/2039/2812). Negamax + PST eval (3 difficulty depths). 12-puzzle pack: EVERY line played through the engine (mates verified mate, tactics verified material).
- CHESS tab (6th): MENTAL RANK card (rank letter, LV from mental xp, record), MENTAL POWER bars (evolve from play only), DAILY CHALLENGE (date-seeded), 12 local modes — AI TRAINING (level gate), MENTAL WAR (rated + SYSTEM ANALYSIS: accuracy/blunders/best-worst/time → stat deltas), BLITZ 5+0, RAPID 15+10, CLASSICAL, TIME PRESSURE 2+0, ENDGAME (3 conversion FENs), OPENING (Italian/Ruy), TACTICAL/BLUNDER→puzzle pool. Online modes (QUICK/RANKED/RANDOM/FRIEND) honest-locked "NEURAL LINK — SOON".
- STATUS: TODAY — MIND module (day-seeded mental quest, real progress from chess_sessions, CLAIM), BODY × MIND SYNC claim once both sides clear.
- Puzzle engine: adaptive tier (3-streak ↑, miss ↓), persisted; daily one-shot guard.
- Assistant knows mind (Need.CHESS): rank/rating/record/weakest stat; routine engine slots CHESS TRAINING 20:30 when free; quick-pick Chess block; login notice bank +2 mind lines.
- Market: 200 VC = ₹100 value line (VC/XP/PredictionPoints law — predictions remain analytic-only, no staking built).
- Owed honestly: neural-link online play (Supabase realtime match channel), bigger puzzle corpus, SPACED opening trainer, desktop perft CI gate.

## 0.12.0 "AWAKENING" (versionCode 22) — 2026-09-26 — CI GREEN (run 36231333583, commit b872e2b)

Full launch-experience + chess-interface redesign per AWAKENING spec.
- Launch sequence REBORN: every generic loader erased — DynamicSplash rotation (4-segment ring / geometric line splits / rank bars / black-room scan), old SystemBootGate ritual, LaunchFlowScreen, legacy OnboardingScreen, SplashCanvases — all DELETED. No spinner, no clock, no circle anywhere in the boot path.
- SYSTEM "S" EMBLEM: sharp angular ribbon-S vector (ui/intro/SystemEmblem) — white primary form, faint SYSTEM-blue energy bleed, zero circular frame. Glitch → blue pulse → stabilize. Now the launch identity; reused on the boot-sync gate.
- ACTIVATION INTRO (~6.2s full / 2.6s fast): S + SYSTEM INITIALIZING → BODY·MIND·DISCIPLINE stack → push-up silhouette (rim-lit dark figure, rep counter, floor pulse ring per rep, PHYSICAL PROTOCOL → VERIFIED) → chess knight hard-cut entrance (motion-ghost trail, glitch slices, white-flash cuts, MENTAL PROTOCOL → ENGAGE) → THE SYSTEM / TRAIN YOUR BODY. TRAIN YOUR MIND. SKIP arms after 1.3s. FAST mode auto-selects on isLowRamDevice / ≤4GB RAM. Pure Canvas, no video/assets/particles.
- Boot-sync gate: small emblem + SIGNAL LOCK hairline status (SystemProcessing) while the session resolves; offline shows SIGNAL LOST + tap-to-resync.
- Loading law: CircularProgressIndicator extinct project-wide. New SystemProcessing (mono status line + traveling blue hairline) serves every functional wait — SystemNotice WATCHING line, boot sync, chess engine thinking, war-analysis compiling. Material LinearProgress bar in Protocol arcs swapped to a SYSTEM hairline fill.
- CHESS UI REBUILT: SystemChessSet — one coherent hand-vectored piece family (P N B R Q K, 40-space silhouettes, cached paths): white = near-white bodies+ink edges, black = near-black bodies+paper edges. Board = single Canvas: real 8×8, clearly separated light/dark squares, coord etching, square-tap detection, selected frame, legal dots, capture rings, last-move wash, pulsing royal frame on checked king. Moves SLIDE (190ms ease), captures fade under the attacker, promotions crossfade at landing — zero teleports.
- Game screen: captured-piece strips + material readout on both tags, YOUR MOVE / SYSTEM TO MOVE indicator, CHECK banners, auto-scrolling move log (castle/capture/promo/check/mate-aware notation), controls row: ↶ UNDO (training modes only — AI TRAINING/CLASSICAL/ENDGAME/OPENING; pops engine+player plies, restores captures/think times) · NEW · RESIGN · BACK. AI_TRAINING gets live ENGINE LEVEL chips; SYSTEM COMPUTING indicator while the engine thinks. Bottom nav/back untouched; nav routes unchanged; ratings/XP economy still 100% server RPC (mig 018 path preserved).
- Puzzle screen: scripted replies now slide too; retry/next clears anim state.
- Archetype enum relocated onto OnboardingGate (splash package deletion); onboarding IA (AwakeningFlow) untouched per spec.
- Owed honestly: SAN disambiguation for same-piece moves stays compact (no Nbd2 forms yet); online neural-link play still incubating.

## 0.13.0 "IRON PROOF" (versionCode 23) — 2026-09-26 — CI GREEN (run 36238186271, commit e1c040b)

Device-reported fixes + audit-first mega-spec compliance (§0–§14).
- PUSH-UP COUNTING unblocked: hunter device report — camera proof stuck in ENVELOPE CALIBRATION forever. Root causes fixed: (1) LIGHT stage hard-failed bright rooms (>235 luma = "glare prison") — now glare caps strictness to YELLOW and passes; (2) every stage machine is now escapable: 15s stuck on any stage → "USE RELAXED ENVELOPE" release valve locks a standard YELLOW profile (engine v4 still counts every rep; ranked surfaces keep the strict gate); LOW_LIGHT window widened 40..220 → 30..235.
- CAMERA UI hazed: bright preview washed out the white HUD — full-coverage FeedScrim gradient (top/bottom heavy) now sits between feed and overlays in both proof stages; HUD readable in any light.
- CHESS board per hunter reference: classic tournament palette (tan F0D9B5 / chestnut B58863), same SYSTEM vector piece set (classic silhouettes).
- PLAY vs AI config sheet (spec §6/§8/§9): DIFFICULTY = BEGINNER/MEDIUM/HARD/EXPERT (real search depths 1/2/3/4 — EXPERT=4 is new), YOUR SIDE = WHITE/RANDOM/BLACK (random re-rolls each rematch), ALLOW UNDO + SHOW MOVE HINTS toggles (undo honors preference; hints = engine MEDIUM whisper arrow on board), PRACTICE ELO line. Honesty card: "AI ENGINE — SYSTEM ENGINE α-β v1 · ON-DEVICE SEARCH · NO CLOUD MODEL / COACH — SYSTEM CORE RULES · NO LLM CONNECTED" (spec §7 law: no fake AI names).
- ELO (mig 019 LIVE): practice_elo column; standard K=32 ELO proposition computed client-side vs difficulty anchors (400/900/1400/1900), server clamps ±48 + plies-gates (<8 moves = 4 XP floor, zero ELO) anti-farm; MENTAL WAR stays the competitive track (server flat steps). Result panel distinguishes PRACTICE ELO (±d) vs COMPETITIVE RATING. Hub card shows PRACTICE ELO + record.
- GLOBAL XP BRIDGE (§11/§12): chess XP now feeds the single SYSTEM rank via apply_xp, deterministic daily ceiling 150 XP; LogResult surfaces global_xp ("RANK CREDITED" line in results).
- BOOT re-timed (spec §2): everyday cold open = 1.3s SHORT mark (S emblem → glow settle → THE SYSTEM → blue hairline). The 6.2s BODY/MIND cinematic + low-end FAST cut now play ONCE per install (intro_prefs.cinematic_seen), SKIP preserved. No circular graphics anywhere, ever.
- Owed honestly: neural-link online matchmaking still incubating (tiles stay honest-locked); local LLM coach not connected and not advertised.

## 0.13.1 FIELD REPAIR (versionCode 24) — user device report, same-day fix
- run: 36443229673 GREEN · commit 5ab4bd0 · APK updated 2026-09-28T15:29Z
- CHESS CLICK BUG (user: "glitch hocche, click hocche na") ROOT CAUSE:
  ChessBoard pointerInput(myColor) stale-captured the first-frame onSquare
  lambda; after ply 1, taps were judged against the INITIAL board/legal list —
  pieces never selected again. Fixed with rememberUpdatedState(onSquare).
  Tournament brown palette (F0D9B5/B58863) re-etched + inverse coord labels.
- BLACK ROOM PHOTO (user: "photo generate hoy ni"): art shipped as
  the-black-room.webp but server row + client fallback pointed at
  black-room.webp. Fallback fixed; migration 020 applied LIVE — Black Room row
  repaired + 13 NULL-convert catalog covers mapped to bundled art; zero broken
  rows remain (verified via API).
- BOOT/ONBOARDING ("abar ager moto"): activation intro layer (added 0.12.0)
  DELETED per user verdict. Restored pre-0.12.0 boot: shuffled HONEST splash
  rotation for Booting, SIGNAL LOST tap-to-resync for Error. AwakeningFlow was
  already identical to the old flow — untouched.

## 0.14.0 FITNESS INTELLIGENCE (versionCode 25) — AI master prompt executed audit-first
- run 36581758408 GREEN · commit 42603e7 · APK updated
- AUDIT: ai/ package held REAL MediaPipe adapter+manager+ladder+orchestrator+validator+fallback;
  catalog was honest-dead (all bundle urls NULL — SmolLM2 never published, Gemma gated).
  NOTHING deleted (§15); preserved rows carry reasons in 'source'.
- ACTIVATION (mig 021 LIVE, verified): local_ai_enabled=true w/ REAL Qwen2.5 LiteRT bundles
  (0.5B MID 521MB sha e608953f.. / 1.5B PLUS 1.5GB sha 8d867a7c..), byte+sha pinned.
- Hardening: inference never silently downloads (explicit GET on AI status page, wi-fi
  honored, storage pre-flight); MODEL BAY UI (progress bar/DELETE/ACTIVE BRAIN line).
- Rules brain v2: FitnessMath (BMI/BMR-est/TDEE/calories-clamped/protein/water) — app
  math, AI reads only (§2); FitnessIntelligence (band, comebackFactor, overload+6%/1.5x
  wall, richQuest rounds, muscle sessions, honest special hints); ContextEngine v2 body
  facts into prompt block; assistant SAFETY WALL (§4) + 20 intents; quest brain gets
  TRAINING context. Economy 100% server-RPC untouched (§2).

## 0.15.0 REP PULSE (versionCode 26) — 2026-09-30
- Commit: 6b5c6e5 · CI run: 36742315752 (GREEN) · APK: nightly-debug the-system-debug.apk 122,003,902 B @2026-09-30T16:16:22Z
- THE floor rep-tracking fix (4th recurrence, permanent): RepPulseCore v5 — geometry-agnostic adaptive multi-channel rep engine (pure Kotlin, android-free core)
  - Sim-gated: /rep-sim/v5 harness, 12 field scenarios × 3 noise seeds = ALL GREEN (reproduced the user's zero-count bug in sim first, then killed it)
  - Channels: elbow/knee angle, shoulder span, head-depth, shoulder/hip travel — learns the hunter's own range, no calibration ritual
  - Autopsy-hardened: prevSig self-poisoning fix, glitch-confirmed relocation (no SETTLE cascades), channel staleness law, coherence tax vs bimodal windows, bandLo-anchored gates, apprenticeship band rebuilds
  - RepPulseEngineV5 android shell: rotation-correct, router (MoveNet primary / ML Kit failover), IMU witness; maps core → EngineStatus/PoseStatus/RepQuality contracts
  - QuestProofScreen rewritten on v5 both engines (push-up front cam, squat back cam); live honest guidance (sees/needs + learning meter); gate+v4 stay for battle room (deferred by design)
  - Harvest corpus + shadow TCN keep streaming under engine tag v5.0-{movenet,mlkit}

## 0.15.1 CHESS REBUILD (code 27) — 2026-10-02
- commit: 0c0e6d0b (hotfix on a674d418 — LocalContext compose rule)
- CI run: 37026712545 GREEN (first attempt 37025902877 red on compose-rule, hotfixed)
- APK: the-system-debug.apk 122,036,670 B @ 2026-10-02T15:29:35Z (nightly-debug)
- ships: live-board race killed (clone-search + commit gate, sim-proven 0 corrupt reads),
  PuzzleDirector (anti-repeat quarantine + adaptive tiers, 12→22 verified pack),
  FRIEND_MATCH pass-and-play, mid-game power chips removed, AI ELO labels removed,
  EASY label fix, hub 6-door command deck, real persisted default engine level.
- gates: chess sim ALL GREEN (22 puzzles / race / 3971-ply integrity / director law),
  brace scan clean, kotlinc noise-only (4 known Hilt/KSP artifacts).

## 0.16.0 SPORT PATHS (code 28) — 2026-10-02
- commit: 525e359d · CI run: 37043924851 GREEN (first attempt, zero hotfix)
- APK: the-system-debug.apk 122,036,674 B @ 2026-10-02T18:00:37Z (nightly-debug)
- server: migration 022 LIVE (users.sport CHECK-guarded, 32 templates,
  ensure_daily_quests v2) · engine flag sport_paths = ON (post-green flip)
- ships: onboarding page 26 'Will you chase a sport?' (4 paths + explicit
  opt-out), one CONDITIONING + one TECHNICAL daily quest rotated
  deterministically, proof law = STEPS/TIMER only (verified modes).
- gates: sport sim 26/26, server rotation dry-run exactly-one-per-day (8 days),
  brace scan clean, kotlinc noise-only.

## ── parallel-session ships (verified against git log + CI API on 2026-10-03) ──

## 0.17.0 ROUTINE ARC (code 29) — 2026-10-02/03
- commit: b1c9950 · CI run: 37100247561 GREEN (recorded 4829adb)
- STEP 7: adaptive week engine — 4-law arc + migration 023 verdicts + week-aware drafts.

## 0.17.1 SUPPLY DOOR (code 30)
- commit: af3b410 (hotfix of 5c522e2 compose annotation) · CI run: 37101145814 GREEN
- STEP 8: Marketplace moved to Profile → AI Benchmark → SYSTEM SUPPLY card.

## 0.17.2 BLACK ROOM GATE v2 (code 31)
- commit: 1ef71c1 · CI run: 37101705155 GREEN
- STEP 9: eligibility engine + deterministic band·seed·reason pricing (mig 024).

## 0.17.3 COUNTRY RAIL (code 32)
- commit: 4c984e7 (hotfix 2 after c7ddf22/e99aab6 annotation+import reds) · CI run: 37103010444 GREEN
- STEP 10: privacy-first user-selected country, zero IP stored (mig 025).

## 0.18.0 SQUAD CORE (code 33)
- commit: 4f29bcc · CI run: 37104495289 GREEN
- STEPS 11-14: squad tab (replaces market slot), 41/9/50 measured war score,
  weekly rotating task (8 tasks live), hunter search + DM, leaderboard; wars sealed-honest.

## 0.18.1 USERNAME IDENTITY (code 34) — 2026-10-03
- commit: f82b47f · CI run: 37105164997 GREEN
- APK: the-system-debug.apk 122,134,974 B @ 2026-10-03T07:17:29Z (nightly-debug)
- STEP 16: typed case preserved vs normalized uniqueness, 25 reserved handles,
  instant RPC availability. STEP 15 audited (no ship needed). STEPS 17-19 swept.
- final state verified 2026-10-03: 102 kt files, kotlinc noise-only, brace scan clean,
  squad RPCs x4 + username RPCs live, sport flag ON, users.sport+country live.
- ★ MASTER PLAN 19/19 CLOSED ★

## 0.19.0 BOARD & BEACON (code 35) — 2026-10-05
- commit: 11c52621 (hotfix of b1dfcd79 limit-Int) · CI run: 37293623619 GREEN
  (first attempt 37292930167 red on postgrest limit Long, hotfixed same window)
- ships: cburnett Wikipedia piece set (12 bundled 120px PNGs, CC BY-SA 3.0,
  NOTICE.md + hub attribution) replacing custom vector set; board optically
  centered (weight-zone, min(w,h) ≤ 400dp); selection glow #38bdf8 @40%;
  SQUAD tab → WhatsApp-style messenger rail (chips, guild banner, real
  threads, measured unread, FAB DM/create-guild), squad command preserved
  at route squadCommand.
- gates: kotlinc noise-only, brace scan clean.

## 0.20.0 CHESS COMMAND (versionCode 36) — 2026-10-09
- commit `cf0fb28` · CI run `37900614083` → GREEN (first attempt)
- Chess home = exactly 7 doors (Play Online sealed-honest / vs AI / Friends / Puzzles / Lessons / Rankings / Settings); Mental Rank + Mental Power panels, stat chart, ELO/W-L-D summaries, training-mode grid, neural-link tiles, daily banner REMOVED from home (data + RPCs untouched).
- NEW screens: ChessLessons (2 openings + 3 endgames as playable lessons via `?lesson=` FEN pin + drills), ChessRankings (own server standing + recent sessions ≥ no fake names), ChessSettings (default engine level pref / engine+piece identity / local puzzle-memory reset).
- Timed combat (BLITZ/RAPID/CLASSICAL/TIME_PRESSURE/MENTAL_WAR) relocated INTO Play-vs-AI config sheet; daily↔training switch chip inside puzzle screen. Engine/puzzle/matchmaking logic: untouched.
- APK: https://github.com/riokosun-design/the-system-android/releases/download/nightly-debug/the-system-debug.apk

## 0.21.0 SHADOW WAR (versionCode 37) — 2026-10-10 · six-phase overhaul
- commit `707da67` + hotfix `1e019ef` (CalibProfile contract restore) · CI runs `38043052255` (red) → `38043555502` GREEN
- P1 chess stealth AI: "SYSTEM COMPUTING" strip deleted → zero CLS; engine already on Dispatchers.Default.
- P2 subtext cull: dashboard decorative captions removed ("What are we working on?", "today, ordered", "TOTAL", "BY aiQuestBrain", proposal reasons).
- P3 unified rep tracking: NEW RepCounterService (RepPulseEngineV5 doorway) runs 1v1 battles STANDARD row (sim v5: 12 scenarios × 3 seeds GREEN — scenario K proof STRICT refuses floor posture); v4 CalibrationGate + FlashLiveness DELETED; CalibProfile/CalibLevel retained (CalibTypes.kt) for PushupEngineV4's ribbon vocabulary.
- P4 match_challenges: mig 028 LIVE (table+RLS+3 RPCs) · bell+badge in global MainTabs header · WarInviteHub modal (accept→server spawns battle→both drop into war room; challenger watches, ~3s cadence).
- P5 WhatsApp chat: tinted right/left bubbles, hh:mm a, real read receipts (mig 029 messages.read_at + markDmRead + ✓/✓✓), optimistic send with reconciliation + failure snackbar, 5-line composer.
- P6 identity: users.bio (160) + avatars — 4 Canvas presets (system://monolith|aegis|glitch|berserk) + custom photo → storage bucket `avatars` (mig 029, owner-write RLS); Coil3 render.
- APK: https://github.com/riokosun-design/the-system-android/releases/download/nightly-debug/the-system-debug.apk

## 0.22.0 ADMIN COMMAND + MONETIZATION — 2026-10-13
- versionCode 38 / versionName 0.22.0 · commit 4244fbb (+175a5bc base) · CI run 38446980362 GREEN (18:32Z)
- APK nightly-debug the-system-debug.apk 142,232,861 B @2026-10-13T18:32:22Z (ML Kit text-recognition native libs added ≈ +20 MB debug)
- SERVER (mig 030, applied+verified earlier this task): admin_perms, admin_audit_log, vc_packages (4 seeds @₹0.50/VC), payment_orders (status machine + unique UTR idx), users.vc_held, withdrawal_requests, user_notifications, support_conversations+messages (all RLS) · RPCs: has_admin_perm/require_admin_perm, admin_grant/revoke_perm, admin_set/get_config, monetization_public, create_vc_order, submit_vc_order_proof, admin_review_vc_order (exactly-once credit), request_withdrawal (atomic hold), admin_review_withdrawal (approve/process/paid/reject/fail), support_open/user_reply/admin_reply/admin_status/mark_read, admin_ops_metrics · +HOTFIX mark_notifications_read() (applied live 18:2xZ, appended to mig 030 §I) · engine_config 'monetization' seeded upi_id=7003283024@fam · storage bucket payment-proofs PRIVATE + owner/FINANCE policies
- CLIENT: WalletScreen (packages→exact-amount UPI QR (ZXing), UTR+proof upload, ML Kit OCR hint (untrusted), status poll, withdraw panel, history) · AdminOpsScreens 6 tabs OPS/ORDERS/WITHDRAWALS/SUPPORT/CONFIG/ROLES bolted to Admin rail · SupportScreen DM-style ticket thread (+6s poll, receipts) · TournamentsScreen (list→detail→fixtures→battle room) + Arena MATCHMAKING door · bell badge = war invites + unread system notices, NOTICES section w/ mark-all-read · Profile WalletCard + SUPPORT door · Routes wallet/support/tournaments
- TESTS: /home/user/rep-sim/fintech/Checks.kt — 61/61 PASS (UTR/UPI predicates, exactly-once credit, hold math, transition machine, OCR matcher, QR payload, 200VC=₹100 rate) · whole-repo kotlinc scan: only known no-classpath noise · brace scan clean (DeterministicAI.kt known FP)
- KNOWN: legacy AdminRepository.paymentProofUrl uses publicUrl on now-private bucket → legacy manual-payment screenshots won't render (bucket never existed before, no regression) · online_now=null (no heartbeat rail) · verification = server checks + OCR hint + manual FINANCE review, no payment gateway · payouts = controlled manual UPI · CPA GRIP strictly NOT integrated

## 0.23.0 IRON PERIMETER — 2026-10-16 (7-section master overhaul)
- versionCode 39 / 0.23.0 · commit 1d2daa3 (+2f295db) · CI run 38932816566 GREEN (16:3xZ)
- §1 bars/nav/bell: immersive auto-hide DELETED (system bar permanent) · MainTabs tab = rememberSaveable (chess/puzzle back now lands on parent hub, not STATUS) · 48dp padded command header (brand left, bell right, hairline under)
- §2 UI: SystemNotice → Solo Leveling window (CutCornerShape, cyan gradient frame + corner brackets + scanlines, "[ ! ] ALARM" header, cold directives, all motivational quotes purged) · chess jargon box + undo/hints subtexts purged · MonoLabel 10→11.5sp Medium, FaintGray #555→#6E6E6E · Assistant REMOVED (screen/VM/route/door) — Routine now dedicated card · arena/profile/market filler culled
- §3 chess: engine v2 (lazy replay-mapped opening book e4/d4/Nf3 systems; eval2 center+development+castling; quiescence on MEDIUM+; EXPERT iterative 4-ply w/ 5-ply sparse-endgame boost + 1.2M node ceiling) · harness laws: opening-natural all 4 levels, no knight ping-pong, deterministic, HARD mid 112ms / EXPERT mid 1299ms JVM, mate-in-1 all levels · puzzles 1-10 random tier rail (bands→tier I-III), instant-fail law confirmed pre-existing · check frame RED #FF1744 · PCM-synth SFX (move/capture/check/mate/victory/fail, zero assets) + vibrate on check/mate/victory + VIBRATE permission
- §4 chat: header = display name + Online/Last-seen (users.last_seen_at; touch_presence heartbeat every 50s via bell VM) · peer poll 15s · date pills · bubbles show display names (messages_with_sender + sender_display_name, mig 032) · PACTS→GUILDS
- §5 arena: hero 1v1 random matchmaking (pulsing border) ALWAYS top + ACTIVE TOURNAMENTS live section (countdown ticker, JOIN/REGISTERED) + tabs trimmed to MATCH/PREDICT/LIVE/PLANNED (RANDOM promoted into hero)
- §6 PENALTY RESURRECTED: apply_daily_penalties cast bug fixed (::penalty_state) + role='USER' only (admin exempt) · NEW sync_inactivity() client-startup catch-up (idempotent via penalty_synced_at, 14d/75% bounded compound, bell notice) · purged cron salvage (jobid 1 storage errors swallowed)
- §7 ANTI-MOD: guard v3 (+vc_held, +prediction_points, +penalty_synced_at; internal_write GUC for definer writes) · AntiTamper.kt cold-start (debugger/root/emulator/frida/xposed/luckypatcher/signature-SHA256-pinned) in Application.onCreate · R8 repackage 'sys' + BuildConfig keeps
- TESTS RUN: chess Checks.jar (6 law groups) ALL GREEN · fintech 61/61 GREEN · whole-repo kotlinc noise-only · brace clean · CI green (1 hotfix: $-escape + FontWeight import)
- KNOWN: nightly debug APK skips signature check (EXPECTED_CERT_SHA256="") — release lane pins it when the keystore ships · emulator test-keys+goldfish and test-keys ROMs blocked by design · lp/root heuristics conservative · CPA GRIP STILL NOT INTEGRATED

## 0.24.0 PHASE 2 — 2026-10-20 (8-section master directive)
- versionCode 40 / 0.24.0 · commit c672a36 (45 files, +3167/−1890) + hotfix f2fee33 (TextToolbarStatus.Hidden — classpath-less scan blind spot, CI caught) · CI 39479659051 RED → 39480579864 GREEN (19:11Z)
- APK: nightly-debug the-system-debug.apk 122,494,952 B @2026-10-20T19:11:11Z (−19.74 MB from 142,232,877 — mlkit-text+zxing death confirmed)
- §1 SYSTEM BARS: explicit WindowCompat.setDecorFitsSystemWindows(false) + transparent nav bar + contrast-enforcement OFF (API29+) + light-nav-bars false · navigationBarsPadding() stays ONLY on outermost bottom bar · white-bar touch-intercept dead
- §2 HEADER: single unified 56dp statusBarsPadding Row in MainTabs (brand 15sp bold left, bell right, hairline under) · duplicate statusBarsPadding() purged from Dashboard(×2)/Rank/Profile(×2)/SquadChats/ChessHub(×2) (pushed screens keep own, no double-space)
- §3 ARENA REWRITE: ArenaScreen = ONE top-level LazyColumn (header/hero/tournaments/tabbar/sections) — nested-scroll trap dead · MatchmakingHeroCard: pulsing cyan tactical frame + Canvas RadarBackdrop (rotating sweepGradient wedge + rings + emitter) + BATTLE MODE chips + STAKES OF TIME 30/60/120 + FIND MATCH · tournaments → horizontal LazyRow 250dp cards (30s countdown ticker, REGISTERED/JOIN→) · username-pick inner LazyRows · ArenaViewModel +queueDuration passed to random_queue_enter
- §4 UPI RAIL DEATH (client): QrBitmap.kt + PaymentOcr.kt DELETED · WalletScreen rewrite (balance/held + OFFLINE_NOTICE + VcTxn ledger) · NEW data/gateway/PaymentGatewayService.kt (interface + CheckoutRequest + GatewayResult sealed + NoopPaymentGateway + PaymentGateways.current — Razorpay seam, placeholder ONLY) · CommerceRepository/AdminRepository/AdminViewModel/AdminOpsScreens/AdminTrainingScreens/ProtocolScreen/BlackRoomScreen stripped of order/proof/withdrawal/config rail (Black Room eligibility + program preserved; apply gone) · PaymentsTab + Pending-₹ tile + Orders/Withdrawals/Config tabs + BLACK ROOM tab DELETED · admin sections → 10-item when (OVERVIEW..ROLES) · Dtos culled (PaymentDto/VcPackageDto/PaymentOrderDto/WithdrawalDto/BlackRoomApplicationDto + upi/usd fields) · mlkit-text-recognition + zxing DELETED (mlkit-POSE kept — camera rep engine) · codebase grep: ZERO upi refs
- §4 UPI RAIL DEATH (server, LIVE-verified has_function_privilege): create_vc_order / submit_vc_order_proof / request_withdrawal → revoked from public+anon+authenticated, GRANT service_role only (execute chain NOT enough — PUBLIC default grant had to die first) · tables+audit rows+admin review fns PRESERVED (financial history law) · engine_config 'monetization' = {"gateway":"OFFLINE_RAZORPAY_PENDING","manual_rail_removed":"0.24.0"}
- §5/§6/§7 DISCIPLINE ENGINE (all-new, opt-in by Android law): core/discipline/ DisciplinePrefs (default-deny quest verdict cache + uninstall-release + social/porn/dns toggles) · AdultBlocklist (TLDs .xxx/.adult/.porn/.sex, ~100 domains, ~60 boundary-matched tokens, ~35 phrases, percent-decode, extractHost) · TypingGuard pure state machine (CONFESSION verbatim, 15s idle reset, 60s amnesty) · service/ SystemDisciplineService (accessibility: 5 social pkgs + lite variants overlay-lock w/ cyan bracket frame + HOME+self-launch CTA; 8 browsers URL-scan throttled 700ms + full-text fallback porn shield w/ red intervention overlay; settings/packageinstaller danger-scan "the system"+danger terms → AntiUninstallActivity throttled 4s; TYPE_ACCESSIBILITY_OVERLAY attach/remove lifecycle) · SystemLockOverlay (programmatic views, zero deps) · SystemDnsGuardService (VpnService routing ONLY 1.1.1.3+1.0.0.3 /32 UDP/53 splice, protected DatagramSocket, IP checksum recompute, UDP checksum 0, TTL 64 — opt-in, cannot stop app-private DoH, drops TCP/53→resolvers retry UDP) · SystemDeviceAdminReceiver + device_admin_policies.xml (force-lock) · ui/discipline/ AntiUninstallActivity (BackHandler blocked, EmptyTextToolbar, KeyboardOptions Password+no-autocorrect+ImeAction.None, single-char-delta anti-paste via commonAffix, 15s idle watchdog, ON_STOP reset, cyan/red/gray judged spans, 60s amnesty on 100%, escape → MainActivity CLEAR_TOP) · DisciplineScreen (accessibility status audit, device-admin + VPN prepare launchers, toggles, ON_RESUME re-audit) at Routes.DISCIPLINE via Profile DisciplineCard
- §8 PENALTY CLIENT: ui/penalty/PenaltyGate.kt — PenaltyVerdict parse, crimson #FF1744 full-app gate (lockdown/t3/t1 bands), TrialRow EXECUTE→QUEST_PROOF, auto-close loop → PenaltyClearedLayout, LifecycleResumeEffect refresh+throttle · SystemRepository +penaltyStatus() + stampQuestLock (today && source!=PENALTY_TRIAL && !isDone → DisciplinePrefs) · DashboardViewModel stamps verdict after every quest load · MainActivity RootState.Ready(+gated): resolve() runs penaltyStatus, 120s resync throttle, EXTRA_OPEN routing, PenaltyGateHost NavHost (penaltyZone + questProof) swaps AppNavHost when gated
- §8 PENALTY SERVER (mig 033, LIVE + FIRE-TESTED): users +penalty_trial_active/penalty_lockdown/penalty_xp_lost_total · enum +LOCKDOWN · guard v4 (forbidden +3 trial cols) · ensure_penalty_trial (CAMERA 0XP seq 90/91 READY rows, source PENALTY_TRIAL, on-conflict-nothing, internal-only) · penalize_user_row worker (EXEMPT role<>'USER' · idle<1 CLEAR · same-day ALREADY_SYNCED + trial re-provision · tiered decay 1d=3%/2-3d=6%/4-6d=8%/7d+=10% capped 75% over ≤14d · tier=1|3|7 · tier7 floors xp to xp_required_for_level(level_for_xp) → rank demotion inherent · trial 50/100/150 reps · tier-branded notification) · sync_inactivity wrapper (grants preserved) · penalty_status() NEW (auth-only, full jsonb incl remaining reps + trial rows) · apply_daily_penalties v3 cron · complete_quest v2 (trial ledger: remaining==0 → clear ALL flags + CLEAR + sync date + notice; else re-assert WARNING/LOCKDOWN after apply_xp wipes display) · ensure_daily_quests ignores PENALTY_TRIAL rows (full body reconstituted verbatim 3187 chars)
- LIVE FIRE TEST (throwaway users, cascade-deleted, 0 residue): tier7 8d/100000xp/lvl20 → {tier:7, LOCKDOWN, missed:8, decayed:59739} → xp 40261, level 29, exactly 2×150-rep CAMERA trials, ALREADY_SYNCED idempotent re-run, no dup rows, "[ ! ] SYSTEM LOCKDOWN" notice · tier1 1d/5000xp → {tier:1, WARNING, 3%=150}, 50/50 trial · ADMIN → EXEMPT missed:0 · bogus uuid → no_profile
- TESTS RUN (not claimed): discipline harness /home/user/rep-sim/discipline/Checks.kt 32/32 GREEN (TypingGuard + AdultBlocklist, kotlinc-compiled /tmp/discipline.jar) · chunked whole-repo kotlinc scan (123 files, 2×70 chunks, −Xmx1200m) 18,488 raw errors → noise filter → 0 residuals · new-file errors = pure androidx/compose classpath noise · brace scan clean (DeterministicAI.kt known string-literal FP)
- KNOWN: ACCESSIBILITY/VPN/device-admin all require explicit user opt-in (Android platform law — cannot self-grant) · DNS guard can't stop app-private DoH · anti-uninstall limited to known settings/installer pkg set (OEM variants may slip) · EXPECTED_CERT_SHA256="" → AntiTamper signature pin still sleeps until release keystore · Razorpay NOT integrated (Noop placeholder only, per directive) · CPA GRIP STRICTLY NOT INTEGRATED · dead ai/ fns remain inert

## 0.25.0 MASTER INTRO — MOTION GRAPHICS — 2026-10-22 (5-section master prompt 2)
- versionCode 41 / 0.25.0 · commit 0ff785b · CI run 39716524610 GREEN first try (03:29Z)
- APK: nightly-debug the-system-debug.apk 121,492,357 B @2026-10-22T03:29Z (−1.0 MB from 122,494,952 — 11 dead art assets + dead code out, engine in)
- NEW ui/motion/MotionGraphicsEngine.kt (~1,700 lines): SystemPalette · SystemRenderCache (pre-allocated Paths + NativePaints, zero hot-path alloc) · CameraRigState + apply3DCameraRig graphicsLayer 3D rig · project3DPoint FOV projection · drawGlowingPath/Line/Circle 4-pass BlendMode.Plus neon bloom · drawHudText native-canvas mono telemetry
- §1 SYSTEM DETECTED EXECUTED: AwakeningFlow P01 circular radar + "DETECTING UNREGISTERED VESSEL…SIGNAL DETECTED" boot log DELETED; never renders again anywhere
- §2 FIRST-LAUNCH GATE: MainActivity wraps RootState — LaunchPrefs("system_launch").masterIntroPending → SystemMasterIntroCinematics (3 scenes: biometric wireframe push-up + scan lasers+HUD, chain-lock shattering EXCUSES/DOPAMINE_TRAP/SLOTH/WEAKNESS, 3D F→SS pillar matrix; 4.5s/scene auto-advance, tap-to-skip, ENTER THE SYSTEM CTA, scene progress bars, status/nav inset pads) → marks seen once → normal splash→gates chain
- §3 ONBOARDING DIET 28→12 pages: KEPT intake 1-5 (name/age/height/mass/class+activity) · P06 biometric auto-scan · P07 NEW DisciplinaryTrajectoryGraph panel (COMMIT TO THE SYSTEM unlocks on onComplete; CAM TRACK/WIDE + REPLAY live) · P08 NEW Ranks showcase (Crossfade visible-rank screen bg + 8 LazyRow cards each with own RankDisciplinaryMotionBackground + scrim + flavor/minLevel/YOUR START chip) · P09 NEW Black Room showcase (SS eclipse aura bg, 5-lock server-truth gate copy, "entry is not bought") · sport path/contract hold/final Google gate preserved · phases 5→3 · VesselStats/computeVesselStats/StatChargePage/RadialPowerChart/StatBlockRow/TapNextLayer DELETED (dead after slide cull)
- §4 RANKSCREEN: static rank_bg art REPLACED by current-rank RankDisciplinaryMotionBackground (Crossfade 700ms on tier change, scrim 0.72/0.62/0.92) · LadderStrip: every F→SS cell renders its own motion signature (matchParentSize + 0.74 scrim, letter always wins)
- DEAD LEGACY CULL: SplashScreens.kt (LaunchFlowScreen + duplicate Archetype/Experience/BioFlag enums) DELETED (0 external refs) · SplashCanvases: 6 dead graphs killed (TerminalGlitch/LevelPulse/SpiderRadar/ShadowHeartbeat/CombatSpline/LimitBreaker), 5 live kept · 11 dead drawables removed (−896KB): onb_grind/transform/nomer/territory/proof/str/vit/agi/conquer/rewards + rank_bg
- TESTS RUN: brace scan clean (DeterministicAI FP only) · chunked whole-repo kotlinc 124 files/2 chunks −Xmx1200m: 17,564 raw → 0 residuals after noise filter · new-file errors pure androidx/compose classpath noise · CI gradle assembleDebug GREEN (the classpath truth)
- KNOWN: kinematics unverifiable on-device (no emulator here) — visual QA deferred to hunter device · master intro replays for existing users exactly once after upgrade (flag never existed before 0.25.0) · ladder cells = 8 mini animated canvases + 1 full-screen (bounded loops, pre-allocated caches; 2GB law preserved) · CPA GRIP STRICTLY NOT INTEGRATED · Razorpay still placeholder-only

## 0.25.1 AUTO-BACK HOTFIX — 2026-10-22 (user report: app dies 3-4s after launch, onboarding unreachable)
- versionCode 42 / 0.25.1 · commits 4748513 → 7977de2 · CI 39726134864/39726524328 RED (clip/background/border extension imports — classpath-less scan blind spot, CI caught both) → 39726912776 GREEN
- Forensics-first law: AntiTamper = instant frame-0 kill (ruled out by timing) · penaltyStatus = runCatching-safe (ruled out) · REAL UNGUARDED VECTOR: RootViewModel.resolve() — myProfile/network throw in viewModelScope = uncaught-coroutine process death ~2-4s post-launch (timing match) → now wrapped, degrades to retryable "SIGNAL LOST" screen
- NEW core/CrashTrace.kt: uncaught-exception → synchronous commit to prefs → next launch shows LAST FATAL stack card (bottom, crimson, first 1400 chars, CLEAR & CONTINUE) — debug-build gated only; installed AFTER AntiTamper (denials stay silent by design) · NO MORE BLIND SURGERY: if the crash persists, the device itself will name the killer
- Motion engine hardened: zero/transient-size guards at all 3 canvas draw entries (intro/rank-bg/graph) — NaN-modulo + infinite-while crash class dead
- User directive honored: 2GB restraint RETIRED — zero visual downgrades; full engine intensity preserved
- TESTS RUN: brace clean · chunked kotlinc 17,850 raw → 0 residuals · CI green after 2 import hotfixes
- CPA GRIP STRICTLY NOT INTEGRATED · Razorpay placeholder-only

## 0.25.2 LAUNCH RESURRECTION (vC43) — commit 418931a · CI run 39731366728 GREEN (first try)
- §1 CLOCK EXECUTED: splash_mark.xml (watch-dial) deleted; native splash = ic_system_s.xml S-mark, 400ms handoff into live bloom SystemSEmblem in DynamicSplash — zero black gap
- §2.1 AntiTamper: CrashTrace installs FIRST; DEBUG(nightly)=sweep+note only (self-kill impossible), RELEASE=enforces — budget-ROM test-keys false positives can no longer execute hunters
- §2.2 API-28 NoSuchMethodError FIXED: isNavigationBarContrastEnforced now under VERSION_CODES.Q guard (prime auto-back suspect on Android 8/9)
- §2.3 nav law: 13/13 navigate() consts registered, dead ARENA_ADD purged; §2.4 manifest @xml/@string/classes all resolve
- §2.5 no BlurMaskFilter anywhere; S-emblem bloom = clamped 3-pass alpha strokes, zero-size canvas skips
- §2.6 resume penalty-audit runCatching armored; §2.7 startup_audit.py smoke test — 40/40 laws green
- Proofs: brace scan OK · kotlinc 19,277 raw → 0 residuals · XML well-formed 5/5
- CPA Grip: NOT integrated (deferred) · Razorpay: placeholder seam only · no fake transactions

## 0.26.0 CLEAN CANVAS (vC44) — commit b1b97b5 · CI run 39899730239 GREEN (first try)
- §1 S-mark now OWNS only the opening 2.6s (AnimatedVisibility fade → UNMOUNT); never floats over RANKS/BLACK ROOM slides again
- §2 quest-donut + arena battle-split slides EXECUTED from enum/canvases/rotation → exactly RANKS + BLACK ROOM + GROWTH (new GrowthCurveGraph, old clean style)
- §3 heavy lag plates purged: RankScreen = restored classic rank_bg.jpg art + 26s Ken Burns drift + scrim (pre-0.25.0 smooth version, git-resurrected); onboarding rank cards → tier gradients; Black Room → static gold aura; graph page → CleanTrajectoryGraph pure-Canvas single fill anim; engine −1042 lines (RankDisciplinaryMotionBackground/DisciplinaryTrajectoryGraph + 8 private drawer executed; master intro intact)
- §4 OnboardingVideoBackdrop seam wired into splash + SystemBackdrop (framework VideoView, loop, muted 0f, lifecycle pause/resume/stopPlayback, alpha 0.28-0.30 + vignette) — res/raw/onboarding_bg_video.mp4 NOT SHIPPED: catbox link returned 404/zero-byte, no fake footage substituted; drop the file in to activate
- §4.3 typography brightened: splash title #F0F4F8, labels #E0FBFC silver-cyan
- Proofs: startup_audit.py 47/47 laws green · brace scan OK · kotlinc 19,209 raw → 0 residuals
- CPA Grip: NOT integrated (deferred) · Razorpay: placeholder seam only · zero fake assets/analytics

## 0.26.1 THE LOOP ARRIVES (vC45) — commit 4582599 · CI run 39907442891 GREEN (first try)
- Hunter-uploaded mp4 found at repo root (spam filename from Klickpin — content FRAME-VERIFIED via cv2: dark monochrome anime hunter, bright white glowing eye, lightning aura; 720x1280, 12s, 30fps, 2,789,367 B)
- git mv → app/src/main/res/raw/onboarding_bg_video.mp4 (root spam name purged)
- §4 backdrop now LIVE behind splash + onboarding: muted VideoView loop, lifecycle pause/resume/stopPlayback, alpha 0.28-0.30 + dark vignette
- Proofs: startup audit 47/47 (VIDEO_ASSET flipped green) · no code changes this turn
- CPA Grip: NOT integrated (deferred) · Razorpay: placeholder seam only

## 0.23.0-LIVE PROOF (vC46) — commit eb5d91b · CI run 40098142368 GREEN
- §1 S-mark ZERO-OVERLAP law: flashDone gate = first 1.8s S alone (video+scanlines only), emblem REMOVED from composition tree the instant slides start — S and RESTRICTED can never share a frame
- §2 rotation fixed deterministic BLACK ROOM→RANKS→GROWTH (shuffle killed in MainActivity); RankScreen heavy glows were already executed in 0.26.0 (verified still clean)
- §3 watermark: awakening HUD = "BUILD 0.23-LIVE • PAGE NN", versionName 0.23.0-LIVE / vC46
- §4 proof: commit 2026-10-24T13:25:52Z < APK asset updated_at (verified newer) — see chat report
- startup audit 50/50 · brace scan OK · kotlinc 19,200 raw → 0 residuals
- Diagnosis of the field failure: recorded symptoms (S stuck 10s+, emblem always-on, no video, stock PAGE header) match v0.25.2 exactly — device ran the pre-0.26.0 binary; active-file hypothesis disproven by grep (single render path)
- CPA Grip: NOT integrated (deferred) · Razorpay: placeholder seam only

## 0.23.0-LIVE vC47 CRITICAL OVERHAUL — commit 9ae62dc · CI 40512937609 GREEN
- §1 SYS_SEQ intro + ENTER btn + LaunchPrefs + ui/motion pkg EXECUTED; palette rehomed; splash = S flash 1.8s → BLACK ROOM → RANKS → GROWTH with faded anime loop behind all
- §2 Google gate = PAGE 01; existing hunters skip onboarding intact; handles A-Za-z0-9_-// 3-24 (client + mig 034 live-verified)
- §3 navigationBarsPadding on 24 inner screens; chess bottom row +8dp
- §4 Chess 5s law: wall-clock SearchCtx + ID from depth1 + withTimeoutOrNull wall + legal fallback
- Proofs: audit 54/54 · brace OK · kotlinc 18,944 raw → 0 residuals · APK asset timestamp > commit timestamp
- CPA Grip NOT integrated · Razorpay placeholder seam only

---

## 0.23.0-LIVE · vC48 FIELD REPAIR — 2026-10-28T15:35:50Z ✅ LIVE
- commit `9fff314` → CI run GREEN → nightly-debug APK 124,265,746 B (asset 15:35:50Z > commit 15:29:56Z)
- §A MINIMUM-DWELL LAW: fast session resolve was vaulting past Booting — slides looked "deleted". Full ritual (S flash 1.8s → BLACK ROOM → RANKS → GROWTH) now ALWAYS completes once per process before any gate.
- §C RENDER LAW: graphicsLayer alpha on a SurfaceView is void on budget ROMs → fade moved to in-window Compose scrims only (veil 0.30 + vignette / rank gradient 0.25→0.60). Video now actually visible.
- §3 Main RankScreen: static rank_bg Ken Burns plate EXECUTED (asset + dead imports purged) → faded anime loop IS the rank chamber.
- §4 2GB-RAM law: framework VideoView, one decoder at a time, pause/resume/stopPlayback lifecycle, codec-error self-unmount to scrim.
- Proofs run: startup audit 56/56 · brace scan OK (DeterministicAI string-FP only) · kotlinc 18,936 raw → 0 residuals.
- CPA Grip: NOT integrated (deferred). Razorpay: placeholder seam only.

---

## 0.23.0-LIVE · vC49 TRI-SYSTEM DIRECTIVE — 2026-11-02 · commit eb725cc · CI GREEN
- §1 PREDICTION/BETTING EXECUTED (server+client): pools/bets/allowance → archived_* (history preserved); prediction_points snapshotted→dropped; 11 live fns rebuilt pool-free; 5 RPCs dropped; settle-prediction-pool edge fn deleted LIVE; client PREDICT tab/cards/PoolsTab/repo/DTOs/payout-math excised.
- §2 CPA RAIL LIVE-PROVEN: cpa-postback v4 (HMAC x-signature + query-secret, clamps); credit_vc ledger-first ON CONFLICT exactly-once + unique indexes; synthetic battery 7/7 (bad sig 401, credit ✓, replay absorbed, bomb 400, HMAC ✓, forged 401, ghost 400); +3 VC SELFTEST on sae (honest audit rows kept); offerwall=AWAITING_PROVIDER (CPA Grip SDK NOT integrated — deferred); Wallet PARTNER OPS section + resume-refresh.
- §3 GROQ SYSTEM PLAN: generate-system-plan DEPLOYED (JWT gate, Mifflin-St Jeor BMR, rank multipliers, qwen/qwen3-32b default via GROQ_MODEL, fallback floor ai_generated=false, upsert user_system_plans RLS); client fetchOrGenerateSystemPlan + offline mirror + Routine SYSTEM PLAN panel. GROQ_API_KEY secret PENDING — dependency marked.
- users.gender nullable col added (BMR sex key; onboarding untouched — deviation documented).
- audit 60/60 · brace scan OK · kotlinc 18,911 raw → 0 residuals. Razorpay: placeholder seam only.

---

## 0.23.0-LIVE · vC50 VIDEO LEGIBILITY + GROQ BRAIN — 2026-11-03 · 0f4fab2 · CI GREEN
- §1 LEGIBILITY LAW: fixed scrim stack everywhere the hunter's loop plays (0xFF030508@72% veil + Black 85→0→90 gradient; L3 floor weight on rank); Contrast.kt primitives (PanelInk 78% + cyan hairline + VideoTextShadow hard drop); splash branding pure white + shadow; canvas native glyphs shadow-layered; all 7 awakening hero headlines white+shadow; HUD 0.9; rank hero/header shadows; LadderStrip future cells ink-plated.
- §2 GROQ BRAIN ARMED: user-supplied GROQ_API_KEY set as Supabase secret; live probe proved ONLY qwen/qwen3.8-27b on the account → GROQ_MODEL pinned to it (user's original slug validated); generate-system-plan v5 redeployed with schema-b (daily_quest + nutrition_target + system_directive + daily_routine retained); plan_data jsonb via mig 037 LIVE; deterministic floor honesty preserved.
- Client: requestAiBrainPlan() + Dashboard SYSTEM BRAIN advisory card + Routine plan panel quest/directive rows.
- audit 63/63 · brace scan OK · kotlinc 19,064 raw → 0 residuals. CPA Grip NOT integrated. Razorpay placeholder-only.

═══════════════════════════════════════════════════════════════════════════════
vC51 — VESSEL SEX VERIFICATION GATE + LEGACY CALIBRATION — 2026-11-04 (UTC)
═══════════════════════════════════════════════════════════════════════════════
Commit:  4b35723 + fix 1163619  →  CI: SUCCESS  (Android Build 14:41:24Z)
APK:     nightly-debug / the-system-debug.apk · 124,265,746 B @ 14:41:14Z
         (asset 14:41:14Z > fix commit 14:34:44Z — live APK IS vC51)
URL:     https://github.com/riokosun-design/the-system-android/releases/download/nightly-debug/the-system-debug.apk

§1 VESSEL SEX VERIFICATION — new onboarding PAGE 03 (13-page flow):
     • Explicit M/F declaration — MALE (ElectricBlue) / FEMALE (NeonPurple)
       ArchetypeCards; cards stamp the honest BMR constants (+5 / −161).
     • Gated continue: "SEAL VESSEL SEX ▸" arms only after declaration
       (same law as the sport-path opt-out); biometric scan page prints SEX.
     • finish() → AuthRepository.completeOnboarding(+gender) → users.gender
       (mig 036 column; guard_user_columns untouched — gender not protected).
     • generate-system-plan's bmrFor ALREADY reads users.gender → the Groq
       brain's BMR/TDEE/macros now bend to the REAL body. Zero backend change.
§2 LEGACY CALIBRATION — awakened hunters (gender NULL = old accounts):
     • Dashboard card over brain panel: "[ VESSEL SEX UNVERIFIED ]" + one-tap
       MALE VESSEL / FEMALE VESSEL (GhostButtons, positional onClick — CI
       caught the trailing-lambda mistake, fixed in 1163619).
     • SystemRepository.setGender (M|F validated) → profile patch → notice
       "SEX CALIBRATED … FORMULA ACTIVE" → silent brain plan re-think
       (force-refresh, correct-sex BMR instantly).
§3 UserDto.gender decoded; audit 64/64 (+GENDER_CAPTURE); brace scan clean;
   kotlinc chunked 19,161 raw classpath noise → 0 residuals (compiler was
   jar-wiped by snapshot — re-armed 1.9.25, chmod, honest sweep, no false green).
   CPA Grip: NOT integrated (deferred) · Razorpay: OFFLINE razorpay-placeholder
   seam only · versionName locked "0.23.0-LIVE" (vC51, watermark truthful).
   AWAITING: user field verification — new-account flow shows sex gate page 03;
   existing account shows dashboard calibration card until declared.

═══════════════════════════════════════════════════════════════════════════════
vC52 — MASTER OVERHAUL SHIP 1/2: CPA-ONLY VC · GROQ QUESTS · SPORTS · MARKET
═══════════════════════════════════════════════════════════════════════════════
Commits: 234159f + fixes a08df47/c8562ef  →  CI: SUCCESS (15:29:30Z IST-UTC)
APK:     nightly-debug/the-system-debug.apk · 124,298,514 B @ 2026-11-05T15:29:21Z
         (asset 15:29:21Z > commit 15:23:09Z — live APK IS vC52, vC52 bundled)

§1 CPA-ONLY VC (mig 038, server-verified live):
  handle_new_user −SIGNUP_BONUS(+100) · finish_battle −QUEST_REWARD(+50 VC,
  −free convoy) · claim_synergy −SYNERGY_BONUS(+5, XP remains +25) ·
  apply_referral −TOPUP250/−REFERRAL100 free mints → XP blessings 150/100 ·
  credit_vc WHITELIST WALL: positive grants raise vc_grant_forbidden unless
  reason ∈ {CPA_POSTBACK, CPA_REFERRAL_CUT, ENTRY_REFUND, TOURNAMENT_PRIZE,
  TOPUP, ADMIN_ADJUST} — live-fired probe PROVED raise + zero residue ·
  convoy renamed CPA_REFERRAL_CUT (index rotated) · client copy purged.

§4 GROQ DAILY QUESTS (mig 039 + NEW edge fn generate-daily-quests v1 jwt):
  brain-first dailyQuests() → fn feeds qwen/qwen3.8-27b FULL context (rank,
  level, streak, yesterday completion, gender-true BMR, active MUSCLE/SPECIAL
  courses, sport + experience + environment) → rows written into daily_quests
  (source=AI_BRAIN, ai_generated=true, scheduled_window circadian slots)
  → TODAY QUEST card reflects the exact AI-built set; periodization law
  server-enforced Mon LIGHT…Sun REST; XP priced server-side only; template
  floor rebuilt under identical intensity scaling (honest ai_generated=false).

§5 SPORTS 4→11 (Auth: ATHLETICS/BOXING_MMA/CALISTHENICS/SWIMMING/TENNIS/
  VOLLEYBALL/POWERLIFTING; 56 template seeds; rotation ordinals == Sport.kt);
  onboarding page 13 ATHLETE CALIBRATION gated for declared athletes;
  users.sport_experience_level + training_location persisted; BODY STATS
  editable strip. §2 MARKETPLACE: 3 admin ratio cols + atomic redeem_vc_discount
  RPC + shelf "Redeem X VC for Y% OFF" rail. §3 Black Room price purged.

Audit 68/68 (+CPA_ONLY_ECONOMY/AI_DAILY_QUESTS/SPORTS_CALIBRATION/MARKET_VC_DISCOUNT)
· brace clean · kotlinc 19,359 raw→0 residuals · 2 CI-caught fixes honestly
logged (misplaced vals, missing import) · CPA Grip SDK: NOT integrated ·
Razorpay: placeholder seam only.
AWAITING: field check — fresh STATUS load should show AI-authored quests with
◷ window chips; VAULT→BODY STATS shows athlete strip when sport declared.
NEXT: vC53 ship 2/2 — §6 quest alarms + execution/recovery timers,
§7 granular blockers (Adult/Instagram/YouTube+Shorts), §8 24h watchdog +
HARDCORE lockdown gauntlet (quests 100% + 1 chess win + 4 arena wins).

═══ vC53 · 2026-11-06 15:58Z · commit 9e62b75 (CI green 15:56) ═══
MASTER CHESS OVERHAUL — 7/7 sections shipped.
 §1 CURRICULUM: 64 lessons / 9 gates, 388 steps — DSL: Talk·Tap·Place·
   MoveTask·Choice(MCQ)·Auto·Test. UI law: instructor card, progress dots,
   < > steppers, tap feedback green/red + "Not this square. Try again." +
   Show me, "Place this piece:" bar, LESSON TEST w/ Moves: X/3.
   TRUTH: validate_lessons.kt vs shipped engine = ALL GREEN (every FEN,
   every move, every Choice truth claim).
 §2 PUZZLES: pack 22→50 (ratings 1–10, setup-move replays on 4 entries).
   TRUTH: python-chess 50/50 GREEN + in-app engine parity 50/50 GREEN.
   Director honest 1–10 rail + neighbor-widened pools; setup anim →
   "Your move!" + live clock + Hint; LINE BROKEN DELETED →
   "There is a better move!" (↻ Repeat / Next →) + "Good moves!" (green ✓,
   +1 float, Elo rail).
 §3 AUDIO: warm thud/crack/chime/CRUSH bank; sfx_muted covenant via
   ChessSfx.attach in Application; toggle chip on Hub·Match·Puzzle·Lessons·
   Settings.
 §4 OBSERVATIONAL: pseudo-legal match play, dual burning-king frames
   (#FF1744), KING_CRUSHED engine status → direct crown-kill instant win.
 §5 DUAL INPUT: tap + drag-ghost on match AND puzzle boards.
 §6 MOTION: 190ms slide universal; capture = 10-shard angular burst.
 §7 FINISHER: unskippable 2.5s KingCrushFinisher (60 radial speed-lines,
   lunge, slash, king split, banner) before ResultPanel.
 APK: the-system-debug.apk 124,429,586 B updated 15:57:53Z > commit 15:51:47Z.
 audit 72/72 · brace clean (DeterministicAI.kt = known FP) · kotlinc sweep
 20,237 raw classpath-noise → 0 residuals · ONE CI iteration (Long literal
 overflow, 1-line fix 9e62b75).
 CPA Grip SDK: NOT integrated · Razorpay: placeholder seam only.
 NEXT: vC54 — circadian alarms, quest execution + recovery-break timers,
 granular discipline toggles, quest-window auto-lock, 24h watchdog,
 HARDCORE PENALTY gauntlet.

---

## vC54 — CIRCADIAN ALARMS, GRANULAR BLOCKERS & 24H WATCHDOG PENALTY GAUNTLET — SHIPPED LIVE

Commit: `35e4d68` (2026-11-07T14:50:07Z) · CI: GREEN (completed success 14:55:06Z, one run, zero fix iterations)
APK: `the-system-debug.apk` = 124,808,710 bytes refreshed 14:54:57Z (> commit) at nightly-debug.
versionCode 54 · versionName "0.23.0-LIVE" (page watermark truthful) · audit 78/78 (+4 vC54 laws) · brace scan clean (known FP only) · kotlinc sweep 63,516 raw → 0 residuals.

### §1 — Circadian Timers & Alarms
- NEW `service/QuestRhythm.kt`: device-local mirror of server-stamped `scheduled_window` per daily quest (fallback slots 06:00 / 16:30 / 20:00 for unstamped engine rows); exact `setExactAndAllowWhileIdle` pings (with idle-fallback chains) fire WINDOW_OPEN and RECOVERY_END through `QuestWindowReceiver`.
- NEW channel `CHANNEL_CIRCADIAN` (IMPORTANCE_HIGH + TYPE_ALARM sound + vibration + public lockscreen) — every scheduled war opens LOUD; `QuestNotifier.circadian()` posts PRIORITY_MAX / CATEGORY_ALARM / colorized crimson.
- In-app QUEST EXECUTION TIMER: live `EXECUTION mm:ss` chip rides the header of every proof screen; entering the proof screen stamps `markExecutionStarted`.
- Mandatory post-quest recovery band LAW: `RecoveryTracker` clamped to 15–30 min (MIN_BREAK_SEC 900 / MAX_BREAK_SEC 1800; server rest_sec is honored inside the band, floor = 15). Recovery-end exact alarm via `armRecovery → QuestRhythm.onQuestCompleted`.
- Live quest-window auto-lock: sentinel hard-locks armed wards while any unfinished window (window = stamped start + est duration clamped 15–60 min) or the recovery break is active.

### §2 — Granular Discipline Blockers (Profile / Vault)
- New `GranularBlockersCard` on Profile: "Block Adult Contents" (UI copy law enforced — the word "Porn" is dead across DisciplineScreen/ProfileScreen/overlay copy), "Instagram Blocker", "YouTube & Shorts Blocker" — independent prefs, defaults ON.
- DisciplineScreen ward relabelled "BLOCK ADULT CONTENTS — ALWAYS ON".
- Sentinel verdict tree (strictest wins): LOCKDOWN (IG/YT/Snapchat — toggles don't matter, it's a sentence) > WINDOW (IG/YT hard-lock while a window/recovery is live) > SOCIAL (§5 quest lock + IG granular ward while quests open).
- YouTube & Shorts Blocker: accessibility scan for `reel_watch_fragment_root` (900 ms throttle) → `GLOBAL_ACTION_BACK` + auto-dissolving "SHORTS REEL KILLED" flash overlay.

### §3 — 24H Watchdog & Ruthless Penalty Gauntlet
- NEW `service/SystemWatchdog.kt`: dual rail — `WatchdogWorker` (WorkManager unique KEEP, 15-min periodic, survives app-kill) + `WatchdogAlarmReceiver` (exact boundary alarm pinned to next window start/deadline/+1h grace; re-armed every audit; +3h coarse fallback). Boot receiver re-arms both rails + today's windows.
- Verdicts: app silent ≥24h (presence clock punched on launch/sync/completion) OR window breached >1h grace → `PenaltyLockdown.engage()`; breach <1h → `shiftRemainingToNow()` re-arms remaining windows from +10 min with 15-min spacing + one-shot daily notice `[ SYSTEM NOTICE: Schedule adjusted. Remaining quest windows re-armed from +10 min. ... ]`.
- NEW `service/PenaltyLockdown.kt`: crimson sentence in prefs; social trio sealed until redemption gauntlet = 100% pending daily quests (server-confirmed sync stamps via `syncDeviceRhythm`) + 1 rated MENTAL WAR chess win (`recordChessWin` at `finalizeFinish`) + 4 server-judged arena wins (`recordArenaWin` at `onFinished`). Counters only advance through real in-app events never local claims; banner ledger rides EVERY tab: `Quests: X/Y • Chess Wins: 0/1 • Arena Wins: 0/4`; crimson lockdown overlay shows the live ledger inside the sealed apps.
- Money/progression math untouched — lockdown is a device discipline sentence; XP/decay/trials remain server-owned (sync_inactivity / penalty_status intact).

CPA Grip SDK: **NOT integrated** · Razorpay: **placeholder seam only** (`OFFLINE_RAZORPAY_PENDING`) · no fake transactions/content.

**NEXT:** hunter verification on-device (install vC54 APK → arm System Guardian → confirm circadian alarm sound, window seal, shorts kill, watchdog reschedule/lockdown drills).

---

## vC55 — 0.23.0-LIVE · OBSERVATIONAL CHESS CORE + 530-PUZZLE MEGA-PACK + ENGINE v3 + WOODEN FOLEY + PLAN ROUTER (DEPLOYED 2026-11-14T06:19:59Z)

**Commit `2a3f774` pushed → CI run 43565303202 SUCCESS → rolling APK refreshed:**
`https://github.com/riokosun-design/the-system-android/releases/download/nightly-debug/the-system-debug.apk`
APK stamp: **124,989,591 bytes · 2026-11-14T06:19:59Z** (vC54 was 124,808,710 B @11-07 · +176 KB = puzzle mega-pack ×530 + wooden WAVs ×7 + code).
versionCode **55** · versionName "0.23.0-LIVE" · audit **83/83 (+5 vC55 laws)** · brace clean (Known FP only) · kotlinc sweep **0 residuals** across 132 files.

### §A1 — Observational Chess Rules (the System no longer babysits your king)
- 100% pseudo-legal moves everywhere: self-check exposure is LEGAL; kings under attack burn with the thick glowing **#FF1744** twin-frame red border+square flash (`board.kingsInCheck()` — BOTH sides, every moment).
- No early mate terminations: classical "mate" now triggers `Engine.crushFinisher()` — the attacker's crown piece physically SLIDES onto the victim king's square next beat; verdict **KING CRUSHED** (KING_CRUSHED status), sanLog `Qx♔#crush`. Stalemate never spawns a phantom strike (harness-proven).
- AI crush instinct at root + horizon: an exposed enemy king is captured immediately, always.

### §A2 — Zero-Teleport Glide
- `AnimMove.moveId` monotonic seq + `AnimMove.of()` factory; `LaunchedEffect(moveId)` tween **200 ms** FastOutSlowInEasing from exact (fromCol,fromRow)→(toCol,toRow); landing-square static piece hidden until slide lands. Tap, drag-drop, AI reply, puzzle setup/setup-replay, lesson demos — every move glides; undo snaps intentionally.

### §A3 — Wooden Foley (oscillator EXECUTED)
- Zero AudioTrack/sin synthesis left. 7 procedural acoustic WAVs authored offline and measured (warm low-pass character): `chess_move` thud · `chess_capture` knock+clatter · `chess_check` chime-knock · `chess_crush` deep 52 Hz thump · `chess_mate` falling triple · `chess_victory` rising marimba · `chess_defeat` low descent (PUZZLE_FAIL shares defeat). SoundPool USAGE_GAME, `GAIN = 0.50f`, persisted 🔊/🔇 covenant untouched on every chess surface.

### §A4 — Lifetime Never-Repeat Puzzle Arena
- `assets/chess_puzzles.json` forged offline: **530 positions, 100% multi-move** (min 3 plies): 340 ALL-defences-proven forced mate-in-2/3 + 190 deck-checked fork/pin/skewer strikes, rated across 1200–2500 ELO. Double-verified: python-chess legal-tree ground truth + in-app 0x88 replay **580/580 GREEN** (incl. builtin 50).
- Pure-Kotlin zero-dep `PuzzlePack.decode` + `merged` (id + board-twin dedup; corrupt asset ⇒ 49-seed, never crash). Daily seed walks past solved ids deterministically.
- **Dual-rail blacklist:** DataStore `solved_puzzle_vault` (instant) + Supabase `user_solved_puzzles` (mig 041, RLS own-row, PK-kill replays; server-heals reinstalls via merge). Director: solved = dead forever; drained tier widens to nearest unbeaten rail; full pack conquered → honest `ARENA CLEARED` wall. "There is a better move!" + ↻ Repeat / Next → untouched.

### §A5 — ChessAI v3 (the 100–400 ELO leak is dead)
- 56 legal-verified classical mainlines (Ruy/Italian/Scotch/Petrov/KG/Sicilian×10/French×4/Caro×4/Scandinavian/QGD/Slav/KID…) — engine-replayed into position keys; 1.e4 answers ride classical family only.
- SplitMix64 Zobrist (incremental, ep/castle/promo-safe, unmake bit-perfect) + 64K TT; MVV-LVA captures → promotions → killers → history ordering; capture/promo quiescence; **HORIZON CRUSH GATE** (`crushVision`) — mate is visible at EVERY leaf even at depth 2/q0, so EASY never sleepwalks past mate-in-1.
- Tapered PeSTO mg/eg PSTs + bishop-pair + doubled/isolated pawns + open-file rooks. Ladder: **EASY ~900** (book+depth2+130 jitter) · **MEDIUM ~1400** (d4+quiescence) · **HARD ~1850** (d6+12q) · **EXPERT 2300+** (d8 ID+16q TT) within the 4.5 s wall (EXPERT sharp-middlegame measured ≤4.7 s). `debugRootScores` diagnostics rail · `ChessV55Test` JUnit mirror (21/21 harness-proven).
- Book debt honestly settled: Pirc/Modern/Alekhine slots evicted for Rossolimo/Alapin/Panov/Taimanov/Rubinstein (directive families only).

### §A6+§B7 — generate-system-plan v7 (DEPLOYED 2026-11-14)
- §A6 deterministic monotonic quest anchors (server): `15+13√lvl+streak bonus` style curves, round-5 reps / 0.5 km steps; L1–10 comfortable (~30 reps), human-realistic ceilings under L80 (≤170 reps/≤12 km), genuinely impossible tier ONLY at L80+ (L100 ≈ 460+reps/29 km). The AI's numbers are clamped into a ±10% band around the anchor → personalization WITHOUT day-to-day yo-yo. Personalization adds primary MUSCLE course (user_courses⨝courses max progress), SPECIAL sub-courses, sport+environment, streak; fallback quest = exact anchor, ai_generated=false.
- §B7 7-slot router: GROQ_API_KEY(+_2/_3)→qwen/qwen3.8-27b · TOKENHARBOR_API_KEY_1/2→MiMo/DeepSeek FREE slugs (slot dead unless key AND model env set) · GOOGLE_AI_STUDIO_KEY_1/2→gemini-2.5-flash generateContent adapter. Module round-robin cursor + instant failover on unset/429/5xx/12 s timeout/schema-break; winner stamped to `user_system_plans.ai_provider_used` (mig 042) + console. Router test suite over the tsc-transpiled REAL file: 13/13 GREEN (failover chains, rotation, clamp proof, monotonic sweep L1–100).
- Live probes: auth gate 401 ✓ · secret digests confirm GROQ_API_KEY+GROQ_MODEL alive; TOKENHARBOR/GOOGLE/GROQ_2/3 slots await boss's keys (dormant-by-design, zero fake content).

CPA Grip SDK: **NOT integrated** · Razorpay: **placeholder seam only** (`OFFLINE_RAZORPAY_PENDING`) · no fake transactions/content.

**NEXT:** hunter on-device verification (install vC55 APK → drag/tap glide, red burn on attacked kings, KING CRUSH finisher, wooden taps at half gain → Puzzle arena serves forged pack, solved souls never return → MENTAL WAR ladder feel: EASY human-loose, EXPERT merciless).

## vC56 — 0.23.0-LIVE · versionCode 56 · 2026-11-14T14:37:11Z
- Commit 4479058 pushed 14:31Z → CI run 43605268206 SUCCESS → nightly-debug APK 125,006,019 B (was 124,989,591 @ vC55).
- §1 MANDATORY PERMISSION REDIRECT: PermissionLair oracle (real overlay/accessibility/device-admin reads) + SystemPermissionSetupDialog (3 exact GRANT doors + Installed-Services guide); DisciplineScreen + ProfileScreen gate every ward switch; ON_RESUME re-syncs + completes pending arming; anti-uninstall toggle (uninstall_guard) gated behind all 3 powers.
- §2 REAL-TIME INTERCEPTION: Instagram Reels (clips_viewer_view_pager / reel_viewer_root / selected 'Reels') → BACK + exact SYSTEM INTERVENTION copy; YouTube Shorts (reel_watch_fragment_root / reel_player_page_container / selected 'Shorts') → BACK + SYSTEM WARNING; DMs/Profile/long-form free unless locked; adult → crimson SYSTEM VIOLATION.
- §3 BRUTAL ANTI-UNINSTALL: SYSTEM_ALERT_WINDOW declared; Abandonment Protocol = un-dismissible TYPE_APPLICATION_OVERLAY (Activity ritual fallback) over AOSP/Play/Samsung/MIUI/ColorOS/Realme surgery screens; interception anchored to pref && device-admin (first-run activation false-trigger killed); verbatim confession + cyan oath + 60s amnesty intact.
- §4 NIGHT QUIET HOURS 22:30–06:30: QuietHours law; canPostNow gates in QuestNotifier (reminder+circadian); defer()→06:30 in QuestRhythm/SystemWatchdog/RoutineAlarmScheduler; enforcement 24/7, noise sleeps.
- Proof: validate_quiet harness 25/25 · audit 88/88 (+5 vC56 laws) · brace clean · sweep 0 residuals ×3 chunks.
- CPA Grip SDK: NOT integrated · Razorpay: placeholder seam only (OFFLINE_RAZORPAY_PENDING).

## vC57 — 0.23.0-LIVE · versionCode 57 · 2026-11-15 (field fix-forward)
- Field report: anti-uninstall went free (hunter uninstalled easily). Root causes: §7 watch anchored to device-admin state (admin off = zero interception), Play Store (com.android.vending) never watched, and device_guard_desc/EXTRA_ADD_EXPLANATION contained sentinel danger substrings ("anti-uninstall" → "uninstall") that would self-trip the ritual during legit activation once un-anchored.
- Commit 1d305ac pushed 04:05Z → CI run 43675231464 SUCCESS → nightly-debug APK rebuilt.
- Fix stack: watch answers to the hunter's toggle alone; admin state only classifies "device admin" screens (activation = free pass, armed = hostile); vending watched; desc/explanation sanitized; GUARD UNARMED nag — one quiet-hours-gated reminder/day (notif 7301) while toggle ON and any of the 3 powers missing. Android law stated honestly in-app copy: DEVICE ADMIN is the only true uninstall wall.
- Proof: audit 89/89 (+VC57_UNINSTALL_WALL, version pin 57) · brace clean · sweep 0 residuals ×4 runs.
- CPA Grip SDK: NOT integrated · Razorpay: placeholder seam only (OFFLINE_RAZORPAY_PENDING).

## vC58 — RTMPOSE OFFLINE BACKBONE + TRACK-HOLD LAW (2026-11-17)
- commit `853262f` · CI run 44046631010 SUCCESS (1 iteration) · APK 126,575,912 B @2026-11-17T08:31:59Z
- APK contents verified: librtmpose_jni.so (arm64 5.60MB / armv7 2.88MB) + libc++_shared, JNI exports present; assets/ml/rtmpose_{m,s}.ncnn.{param,bin} exact bytes; movenet + ML Kit gone
- offline gates GREEN before integration: pipeline_validate (official SimCC contract: argmax/2 + peak-product score, NO softmax — flat-score autopsy closed) · ncnn_parity (fp16 payload vs ONNX fp32: M/S maxPosDelta 0.00px, maxScoreDelta ≤0.002)
- TRACK-HOLD sim: 14 scenarios × 3 seeds GREEN — 700ms mid-rep blackout HELD+counted; 3000ms blackout clears only the abandoned attempt; rest blackout zero collateral
- versionCode 58 · versionName frozen 0.23.0-LIVE · audit 96/96 · brace clean
- CPA Grip SDK: NOT integrated · Razorpay: placeholder seam only (OFFLINE_RAZORPAY_PENDING).

## v1.0.0-RELEASE — versionCode 59 · 2026-08-09 (FINAL RELEASE MASTER DIRECTIVE)
- §1 ECONOMY EXECUTED: VC/CPA dead end-to-end — server drops credit_vc, cpa_postback, redeem_vc_discount, get_offerwall_config, create/submit/expire vc-order fns, admin_adjust_vc, review_payment, admin_review_vc_order, admin_review_withdrawal, request_withdrawal (mig 043); join_tournament redefined fee-free, advance_bracket crowns with 500 XP (no coin pool), claim_battle_challenge XP-only, squad_status drops treasury_vc, create_clan level-gated only; cpa-postback edge function + config entry deleted. Client: WalletScreen → THE GATE status panel, ProfileScreen wallet/bounty-board/VcChip removed, market VC-redeem removed, tournament fees gone, admin VC tooling removed, SystemMath VC constants executed. Rewards = XP · Rank · Prestige.
- §1 DEVICE ADMIN REMOVED (sideload auto-back law): SystemDeviceAdminReceiver + BIND_DEVICE_ADMIN manifest block + device_admin_policies.xml + PermissionLair.DEVICE_ADMIN executed. Anti-uninstall = pure AccessibilityService sentinel + SYSTEM_ALERT_WINDOW Abandonment Protocol; settings-danger (App Info / uninstall / accessibility / device-admin screens mentioning THE SYSTEM) → performGlobalAction(HOME) THEN the un-dismissible overlay.
- §2 RAZORPAY-READY RAILS (PENDING_PAYMENT orders minted server-side; only the live gateway webhook settles): Black Room 3-year arc = exactly 2 payments (UNLOCK → RENEWAL at month 6 → forever) with dynamic consistency-seeded pricing (mig 024 bands ₹149–₹699); MEDAL TRIALS 30/60-day (₹399–₹499, server-judged 100% → shipping form → physical dog-tag); DUNGEON PROTOCOLS one-time DLC ₹149–₹299; Marketplace = existing shelf through the gate.
- §3 HAMBURGER COMMAND DRAWER: #0B0E14 glass, #00F0FF cyan edge blade, 40ms staggered slide-in; Leaderboards · Medal Trials · Dungeon Protocols · Black Room · Marketplace · Brotherhood Hub · Settings & Discipline · Admin Panel (admins only).
- §4 LIVE LEADERBOARDS: OVERALL HUNTER (level/XP/streak) × MEDAL TRIALS (progress/status) × GLOBAL/INDIA/LOCAL geo tiers; city/district/state declared in Profile → Edit Identity (mig 043 columns, 025 privacy law); mig 044 public-safe medal board RPCs; 20s live re-sync.
- §5 VERBOSE MICRO-COPY EXECUTED on Home/Profile/Squad/Training — bold headlines, stats, clean buttons survive.
- §6 EMAIL+PASSWORD GATE on Page 01 (signUpWith/signInWith Email) beside Google; inline errors; same signedInProfile contract.
- §7 SPECIAL QUESTS: admin Broadcast console (GLOBAL/INDIA/LOCAL city·district, 1–168h); generate-system-plan opportunistically mints AI SHARED LOCAL QUESTS — same city+district = same quest, replace-never-stack.
- §8 BROTHERHOOD & SISTERHOOD: Code of the Brotherhood on the contract page + squad hub; EXTEND A HAND on at-risk hunters (local leaderboard + squad find feed) → extend_a_hand RPC (1/pair/24h, notice-stamped into user_notifications); daily uplift nudge + all rally noise respect QuietHours 22:30–06:30.
- §9 PROOF: startup_audit 100/100 (7 laws rewritten to v1.0 + 8 new) · brace sweep balance-neutral on every touched file · pglast parse 73+4 statements clean.
- CPA Grip SDK: NEVER integrated (rail deleted) · Razorpay: OFFLINE_RAZORPAY_PENDING seam until live credentials ship.
