# AI ENGINE IMPLEMENTATION LEDGER — spec §1–§28

> Incremental layer on TOP of THE SYSTEM. Nothing rebuilt, nothing replaced,
> nothing removed. This file is the honest record of state per spec section.

## Architecture (§1)
`ai/` package: `AIOrchestrator` (single doorway) → `AIRemoteConfig` (flags+catalog)
→ `AIModelRouter` duty = `DeviceCapabilityManager` + `AIRemoteConfig.modelFor`
→ `LocalInferenceRuntime` (interface) / `MediaPipeRuntime` adapter / `NoopRuntime`
→ `DeterministicAI` (fallback brains) → `AIValidator` → existing services.
UI talks ONLY to ViewModels → orchestrator. No model type leaks upward.

## LIVE today (deterministic brains — production personalization)
- **§8 Quest AI** — Dashboard "AI PROPOSE": adaptive proposal from VERIFIED bests
  → validator (whitelist, clamps, dup guard, ≤2× progression) → hunter taps ADOPT
  → `adopt_ai_quest` RPC re-enforces everything SERVER-SIDE (1/day, kinds the proof
  machinery can witness: PUSH/SQUAT/RUN/WALK/PLANK, deterministic capped XP) →
  lands in `daily_quests` and the EXISTING verified proof flow owns it.
- **§9/§10 Nutrition** — Market "DAILY FUEL": 3-template macro-honest plans scaled
  to goal (never below floor, NEVER for minors); market matches resolved purely
  from `ecommerce_products` (real ids/prices, ≤2 refs, ≤30% budget); clean empty
  state when nothing matches; CONFIRM is acknowledgment — no auto-purchase, ever.
- **§14 Routine** — new module: draft anchored now+30m from today's quests/course/
  recovery; accept→sync (`daily_routines`), edit (toggle/remove → demotes DRAFT),
  reject, regenerate (armed double-tap — CONFIRMED never silently overwritten).
- **§12/§13 Assistant** — HUD terminal, 4 personalities (tone-only), intents over
  a sealed context snapshot; chat in-memory ONLY; §15 memory = explicit
  SAVE MEMORY pins → `ai_notes` (RLS own rows).
- **§20 Security** — anon key + RLS everywhere; zero new privilege.
- **§25 Observability** — local counters only (no prompts/content): request/fallback/
  fail counts, EMA latency, RAM peaks, tier selections.
- **§22 Benchmark** — admin-only screen (Profile → AI BENCHMARK): device probes,
  flags, catalog states, counters. Not reachable from normal navigation.
- **§24 Flags** — `engine_config.ai` (016): quest/nutrition/assistant/routine ON,
  `local_ai_enabled` **OFF** until converted artifacts pass on-device validation.
- **§4/§19 Low-end laws** — no startup load, no background LLM, on-demand
  load→infer→unload, idle-unload, QuestProof sheds LLM (`visionActiveHint`).

## SHADOW / NOT-YET-LIVE (honest)
- **Local LLM runtime** — `MediaPipeRuntime` (tasks-genai 0.10.35) is fully wired:
  capability tiering (RAM+pressure+thermal+ABI+storage), versioned downloads with
  SHA-256 pins, single-model discipline. The **catalog ships with `url:null`**
  artifacts — converted .task bundles (SmolLM2-135M/360M INT8, Gemma-3-1B INT8)
  must be produced, hosted, pinned (sha256) and device-validated BEFORE
  `local_ai_enabled` flips true. Until then tier router returns NONE and every
  request serves the deterministic brain — by design, not by accident.
- **§5 quantization bakeoff** — INT8 chosen as the single default until the
  benchmark screen has real numbers from 2GB/4GB/8GB fleet data.
- **§21 fleet testing** — benchmark plumbing ships; numbers pending physical fleet.
- **§28 acceptance** — items 15–18 (forced failures → fallback) are structural
  (every LLM rung degrades to deterministic); on-device confirmation pending.

## Deferred explicitly
- Model artifact conversion/hosting pipeline (needs converted .task bundles + CDN).
- Voice input for assistant. Routine calendar reminders (needs alarm policy review).
