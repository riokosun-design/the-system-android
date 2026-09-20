# PHASE 1–4 IMPLEMENTATION LEDGER — 0.9.0 "PROOF ENGINE"

> Companion to `CV-BATTLE-ARCHITECTURE.md` §20 (roadmap). This file is the
> honest record of what Phase 1–4 delivered in **0.9.0 (versionCode 18)**,
> what is **LIVE**, what is **SHADOW**, and what is **deferred** — with the
> exact promotion gates owed before anything shadow becomes a decision-maker.

Shipped: two commits on `main` (client + backend migration 015).
Verification: rep-sim 12/12 (0.8.0 logic preserved), TCN parity test
PASS (max |Δp| 5.1e-07 over 200 random windows), migration 015 verified
live on project `bacwuvdomvvfavbwgvid`.

---

## PHASE 1 — ESTIMATION LAYER (perception hardening) ✅

| Deliverable | Status | Notes |
|---|---|---|
| `estimate/PoseEstimator.kt` — canonical 33-slot `LandmarkFrame` | LIVE | One interface for every pose source; ML Kit numbering is canonical. COCO-17 is mapped in; joints ML Kit has no analog for (eyes/ears/feet detail) are approximated from parents so geometry code never branches per-source. |
| `MoveNetEstimator` (Thunder int8, 7.1 MB asset) | LIVE | f32 ROI lock with ±1.35 pad and EMA smoothing; auto-unlocks to full-frame after 8 consecutive misses. YUV→RGB 256² sampler (BT.601) handles the upright→buffer rotation transform once, centrally. |
| `MlKitEstimator` | LIVE | Refactored behind the same interface (Tasks.await on the analyzer executor). Squat path unchanged. |
| `PoseEstimatorRouter` | LIVE | MoveNet is prime; a fail-streak of 8 demotes to ML Kit; periodic parity probe (every 30 frames when enabled) reports mean landmark distance + active source to the UI/harvest meta. |
| CalibrationGate consumes estimator (not ML Kit directly) | LIVE | `CalibProfile.estimatorSource` recorded; the 5-stage gate (STABILITY→PERSON→VIEW→LIGHT→TOP_LOCK) is unchanged in behavior. |

**Deferred to Phase 1.5:** the LK optical-flow bridge (sub-pixel landmark
stabilization between detector frames) — pending on-device profiling of the
Thunder path at 15/30 fps. The EMA-smoothed ROI lock is the interim answer.

## PHASE 2 — SIGNED EVIDENCE CHAIN (battle integrity) ✅

| Deliverable | Status | Notes |
|---|---|---|
| Migration 015: `battle_nonces`, `battle_rep_events`, `mint_battle_nonce`, `submit_rep_events`, `battle_plausibility`, `battles.plausibility` | LIVE (DB) | Nonces are idempotent, participants-only, LOBBY/LIVE-gated. `submit_rep_events` recomputes the canonical string server-side; flags: `bad_hash`, `bad_sig`, `chain_break`, `physics_gap` (<500 ms), `tempo_short` (<500 ms). |
| `RepEventSigner` (client) | LIVE | `prev_hash` chain from `"GENESIS"`; canonical line `battle\|user\|seq\|t\|theta\|line\|drop\|tempo\|conf\|cheat\|engine\|prev_hash`; floats `%.3f` US-locale; quality/conf/cheat sent as JSON strings (server casts `::real`). Drains ≤8 events per flush, final drain ≤64 at battle end. |
| BattleRoom wiring | LIVE | ViewModel mints nonce for non-spectators at join; 3 s flush loop; finish chains final drain + `battle_plausibility`; finished card shows `INTEGRITY: YOU x · RIVAL y` (downgraded to LabelGray on SUSPICIOUS/INVALID). |
| Plausibility verdicts | LIVE (DB) | CLEAN / SUSPICIOUS_RATE (>2.0 rps) / SUSPICIOUS_TEMPO (>25 % soft events) / INVALID (hash/sig/chain failures) / NO_EVIDENCE. |

**Canonical-string contract is byte-for-byte load-bearing.** Any client-side
change to field order, float formatting, or delimiters desyncs verification
(every event → `bad_hash`). Treat the string as a protocol version; bump
`engine` field on any change.

## PHASE 3 — TEMPORAL MODEL (TCN, SHADOW-ONLY) ✅ as shadow

| Deliverable | Status | Notes |
|---|---|---|
| `PushupEngineV4` emits 12-dim frames + decision labels | LIVE | Per frame: [θL/180, θR/180 (sentinel −2), shYn, hipYn, line, wFixL, wFixR, vis, dt/100, torsoScale, viewQ, cheat]. Decisions: COMMIT, REVIEW_COMMIT, REJECT_*, ABORT_IMU/POSE/SLEW/LATERAL, EXCURSION, REVIEW_TIMEOUT. Rules ALWAYS decide — the TCN only observes. |
| `ml/tcn_train.py` + `pushup_tcn_synth_v0.weights.bin` (358 KB, 89 607 f32) | SHIPPED (shadow) | 26 k/4 k synthetic windows, 6 epochs; per-class P/R in `ml/v0_report.json`; synthetic shadow-rules agreement **74.2 %**. |
| `tcn/TcnShadowScorer.kt` — zero-dep native f32 forward | LIVE (shadow) | 4 causal convs (k=3, d=1/2/4/8) → GAP → dense-7, tanh-GELU. **Parity-verified** against the torch reference (max |Δp| 5.1e-07). Ring 48×12, scores every 8 frames; `snapshot()` feeds the corpus. |

**v0 TCN is a BOOTSTRAP-SYNTHETIC PRIOR — deliberately not promotable.**
74.2 % synthetic agreement < the §20 gate of 97 % on real data. Promotion
protocol (unchanged from §20): collect ≥10 k labeled real windows via the
harvest → retrain → shadow ≥97 % agreement for 2 consecutive releases → only
then may `engine_config.tcn_decide` flip. Server default remains
`"tcn_decide": false`.

## PHASE 4 — CORPUS HARVEST + LIVENESS ✅

| Deliverable | Status | Notes |
|---|---|---|
| Migration 015: `users.harvest_consent`, `set_harvest_consent`/`harvest_consent` RPCs, `rep_feature_sequences` + `label_rep_sequence` (admin) | LIVE (DB) | Label vocabulary: VALID / INVALID_DEPTH / INVALID_FORM / PARTIAL_REP / CHEAT_MOVEMENT / CAMERA_MOVEMENT / UNKNOWN. |
| `tcn/FeatureHarvester.kt` | LIVE | Consent-gated (never uploads without opt-in; NO VIDEO, ever — only 48×12 float windows, rounded to 4 dp, + meta: model/sdk/shadow class+conf/parity/view_q). Batches of 10, requeues on failure. |
| QuestProof consent card | LIVE | HarvestConsentCard asks once (persisted decline in `harvest_prefs`); decision labels from the engine become corpus labels — self-labeling by construction. |
| `FlashLiveness.kt` — torch-strobe challenge | LIVE (flag-gated) | 7×380 ms PRNG pulses (≥2 on/≥2 off); verdict requires sign-agreement ≥6/7 AND contrast ≥1.35. Server flag `flash_liveness_required` defaults **false** — battle lobby gate enforces it only when flipped. |
| `engine_config` table + `get_engine_config()` | LIVE (DB) | Seed: tcn_decide false, min_ranked_engine 0.8.0, flash off, depth floors 0.28/0.24, harvest on. |

## Explicitly NOT in this drop

- **LK optical-flow bridge** → Phase 1.5 (needs on-device profiling first).
- **Play Integrity API** → scaffolded via the `flash_liveness_required` /
  `engine_config` flags; activation requires the Play Console listing, which
  only the account owner can create. Flash liveness is the interim VPS.
- **TCN decision authority** → gated by §20 promotion protocol above.
- **Squat engine** → untouched (v3-mlkit lineage, by design).

## Ops quick reference

- Flip a server flag: `UPDATE engine_config SET value = value || '{"flash_liveness_required": true}' WHERE key = 'global';`
- Pull corpus for retrain: `SELECT * FROM rep_feature_sequences ORDER BY created_at DESC` (service_role / admin).
- Label a window: `SELECT label_rep_sequence('<id>', 'INVALID_DEPTH');`
- Retrain: `python3 ml/tcn_train.py --corpus <export>.json` (script currently synthetic-only; corpus loader is the next ml/ change when volume justifies it).
