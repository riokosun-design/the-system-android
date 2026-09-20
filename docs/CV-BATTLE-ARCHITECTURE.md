# THE SYSTEM — COMPETITIVE PUSH-UP VERIFICATION ARCHITECTURE
### Sensor-Fusion + Temporal-AI Battle Engine · v1.0 · 2026-09
**Authored as:** Senior CV / Sports-Motion Engineer review for THE SYSTEM
**Status:** Design target — ≥99.9% valid-rep precision *inside a defined, enforced capture envelope*. This document separates what is theoretically possible from what real smartphones actually deliver, and it starts with the uncomfortable truth.

---

## 0 · THE UNCOMFORTABLE TRUTH FIRST (challenge to your assumptions)

> **No AI on Earth can certify a push-up it cannot see.**

Your field failures (pose lost at the bottom, counter stuck, "UP but never counts") were not state-machine bugs and not model weakness — they were **viewpoint starvation**. With the phone lying flat on the floor pointing up at the hunter's face:

1. **The elbow flexion angle — the single most informative joint for a push-up — is geometrically unobservable.** Forearm and upper arm project onto each other from the front/below; the 2D angle collapses to near-constant. Every "front-span width change" trick we shipped (v2/v3 engines) was measuring *distance-to-lens parallax*, not depth — it works, but it is one noisy dimension standing in for five.
2. At the deepest point of the rep the head is 10–20 cm from the lens at a top-down angle. **No public pose dataset contains meaningful coverage of that viewpoint**, so every off-the-shelf estimator (ML Kit, BlazePose, MoveNet) degrades exactly when depth must be judged.
3. The proximity-sensor detour failed for the same physics reason: a binary ~5 cm gate only fires if the chin/chest actually crosses it — real push-up depth varies by body proportions, so 5 cm is the wrong threshold for most bodies.

**Therefore the first requirement of a competitive-grade system is not a better model. It is an enforced capture envelope:**

| Factor | Envelope we will ENFORCE (calibration gate refuses battle start otherwise) |
|---|---|
| Viewpoint | **Side view, ~90° to the body**, 30° tolerance three-quarter fallback (reduced confidence) |
| Camera height | 30–60 cm above floor (chair/desk/stack of books) |
| Subject distance | full body + 15% margin in frame |
| Stability | device physically static (IMU variance gate) |
| Lighting | mean luma 40–220, no strobe, no heavy backlight |

Inside this envelope, ≥99.9% valid-rep precision is a *real engineering target*. Outside it, no architecture gets you there — anyone who claims otherwise is selling you a demo, not a system. The rest of this document is the honest machine that lives inside that envelope.

---

## 1 · COMPLETE SYSTEM ARCHITECTURE

```
┌──────────────────────────────────────────────────────────────────────┐
│ SENSOR LAYER (Android / CameraX / SensorManager)                     │
│  · Camera stream 1280×720 YUV @ 15–30 fps (adaptive)                 │
│  · Accelerometer + Gyroscope @ 100 Hz  → camera-stability witness     │
│  · (proximity / magnetometer: REJECTED as evidence — see §2)          │
├──────────────────────────────────────────────────────────────────────┤
│ PERCEPTION LAYER (per frame, on-device)                              │
│  · Pose estimator → 17–33 keypoints + per-point confidence            │
│  · Person-ROI locker (bounding-box EMA, multi-person detector)        │
│  · Optical-flow shoulder tracker (fill-in when pose drops ≤ 0.4 s)    │
│  · Frame-quality probes: luma mean/std, blur (Laplacian var), moiré   │
├──────────────────────────────────────────────────────────────────────┤
│ FEATURE LAYER (per frame, normalized)                                │
│  · 12-dim vector: θ_elbowL/R, shoulder-Y_n, hip-Y_n, body-line dev,   │
│    wrist-fixation L/R, visibility agg, dt, torso scale, view code     │
├──────────────────────────────────────────────────────────────────────┤
│ TEMPORAL LAYER (windows, on-device)                                  │
│  · Deterministic state machine: READY→POSITION→DOWN→BOTTOM→UP→TOP     │
│  · Temporal CNN (48-frame windows) → 7-class sequence classifier      │
├──────────────────────────────────────────────────────────────────────┤
│ DECISION LAYER                                                       │
│  · Hard vetoes (camera moved / depth not proven / pose lost)          │
│  · Soft fusion score (§5) · CHEAT_SCORE (§6) · REP REVIEW buffer      │
├──────────────────────────────────────────────────────────────────────┤
│ SYNC LAYER (battles only)                                            │
│  · Signed rep events (seq, timestamp, quality, cheat)                 │
│  · Server = authority: plausibility + verdict commit                  │
└──────────────────────────────────────────────────────────────────────┘
```

Two principles drive everything below:

1. **Single frames decide nothing.** Only validated *sequences* produce a rep.
2. **False negative > false positive, always.** A real rep occasionally held for review is a UX cost; a fake rep awarded in a money/XP battle is a product death.

---

## 2 · SENSOR FUSION — WHAT EACH SENSOR ACTUALLY BUYS YOU

| Sensor | Verdict | Role |
|---|---|---|
| **Camera** | PRIMARY | Only sensor that observes the body. Everything else supports it. |
| **Accelerometer** | SUPPORT (cheap, high value) | Phone is *static* during legit capture → any sustained variance ⇒ the phone (or floor) is moving ⇒ **camera-movement veto**. Also detects deliberate device bumping near rep time. |
| **Gyroscope** | SUPPORT | Rotation-rate norm; catches tilt/pan repositioning between reps that accel alone misses. |
| **Proximity** | **REJECT for counting** | Binary ~5 cm gate; fired unreliably across devices/ROMs and punishes body proportions (your field test proved it). MAY stay as an optional liveness *hint* in the idle lobby; never as rep evidence. |
| **Magnetometer** | REJECT | No signal about the body; metal in floors confuses it. |
| **Mic** | REJECT | Privacy-creepy for near-zero signal. Don't. |

**Stability witness (the IMU's real job):**
```
accel_var  = variance(|a| - 9.81) over 500 ms window
gyro_norm  = max(|ω|) over window
CAMERA_STABLE ⇔ accel_var < 0.35 m²/s⁴ AND gyro_norm < 0.6 rad/s
```
Cost: ~0 code, negligible battery, massive anti-cheat value. When it trips mid-set: freeze the state machine, show "CAMERA MOVED — RE-STABILIZING", re-run a 1.5 s mini-calibration.

---

## 3 · COMPUTER-VISION PIPELINE

```
CameraX ImageAnalysis (YUV_420_888, 720p, STRATEGY_KEEP_ONLY_LATEST)
        │  rotation-corrected InputImage
        ▼
POSE ESTIMATOR (per frame) ──► 33 (or 17) keypoints + confidences
        │
        ├─► PERSON ROI LOCKER
        │     bbox EMA (α=0.2), aspect sanity, area sanity
        │     → MULTI-PERSON ALERT if ≥2 confident torsos persist ≥ 300 ms
        │
        ├─► OPTICAL-FLOW PATCH TRACKER (Lucas–Kanade on 2 shoulder patches)
        │     bridges pose dropouts ≤ 0.4 s; longer dropout = sequence ABORT
        │     (never "imagine" a bottom we did not observe)
        │
        ├─► FRAME-QUALITY PROBES
        │     luma mean/σ · Laplacian variance (blur) · high-frequency
        │     energy (moiré — points at a *screen*, anti replay)
        │
        ▼
FEATURE BUILDER → 12-dim normalized vector / frame (next section)
```

**Normalization (this is what makes thresholds body/camera independent):**
- `torso_len = dist(shoulder_mid, hip_mid)` at calibration top position.
- All verticals expressed as `(y - y_hip_mid_calib) / torso_len` → **unit = "torsos"**, invariant to distance, height, resolution.
- View classification at calibration from `shoulder_span_px / torso_len_px`: near-1 ⇒ side view (full confidence); near-0 ⇒ front view (**battle start REFUSED** for push-ups).

---

## 4 · TEMPORAL AI — THE SEQUENCE CLASSIFIER

**Architecture comparison for 30 fps on-device:**

| Model | Streaming? | Latency | Size | Verdict |
|---|---|---|---|---|
| LSTM/GRU | yes, but stateful drift, slow RNN step | ~25–60 ms | 0.5–2 MB | fallback only |
| **TCN (causal dilated 1D convs)** | **native streaming, parallel, stable** | **~5–12 ms** | **<0.5 MB int8** | **CHOSEN** |
| Transformer encoder | needs re-windowing, KV cache complexity | 20–80 ms | 1–5 MB | overkill |
| TinyML micro | MCU-targeted, unnecessary | — | — | wrong platform |

**Chosen: causal TCN**
```
INPUT: 48 frames × 12 features  (1.6 s @ 30 fps), stride 8 (every ~267 ms)
CausalConv1d( 64, k=3, d=1) → GELU → CausalConv1d( 64, k=3, d=2) → GELU →
CausalConv1d(128, k=3, d=4) → GELU → CausalConv1d(128, k=3, d=8) → GELU →
GlobalAvgPool → Dense(7, softmax)
Params ≈ 120 k · int8 PTQ ≈ 0.4 MB · receptive field 1 + 2·(1+2+4+8)·(3−1) = 61 frames ✓ covers a full rep
```
**Classes:** `VALID_PUSHUP · INVALID_DEPTH · INVALID_FORM · PARTIAL_REP · CHEAT_MOVEMENT · CAMERA_MOVEMENT · UNKNOWN`

Why this design: the state machine (§5) already encodes *domain truth* deterministically; the TCN is **not** asked to count reps — it scores *sequence plausibility* (the smooth strictness/grace that hand-tuned thresholds can't express, e.g. "the descent looked real but oddly ballistic"). That division keeps the system debuggable: when a rep is rejected, we can name the exact veto — critical for a competitive product and for support tickets.

---

## 5 · MATHEMATICAL VALIDATION RULES (deterministic core)

Per-frame (side view, normalized units; `θ` in degrees):

```
θ_elbow      = angle(shoulder, elbow, wrist)            // both arms, keep min-sane one
line_dev     = perp_dist(hip_mid, line(shoulder_mid→ankle_mid)) / torso_len
shoulder_h   = (y_shoulder_calibTop − y_shoulder) / torso_len   // grows on descent
wrist_fix    = max over recent 300 ms of |wrist_n − wrist_ref| / torso_len
```

**State machine — a rep exists only as a completed trajectory:**

```
READY → POSITION_VALIDATION: pose stable ≥ 500 ms, θ ≥ 150°, line_dev ≤ 0.10,
        wrist_FIX ≤ 0.06 (arms planted), CAMERA_STABLE
  ↓
DOWN_PHASE: shoulder_h rising ≥ 120 ms (min 4 frames)
  ↓
BOTTOM_VALIDATION: witnessed min θ over the dip satisfies
        θ_min ≤ θ_depth  (adaptive: 75° − 8°·viewQuality + ROM_prior adjustment)
        AND shoulder_h_max ≥ 0.28 torsos   (real chest drop — kills head-nods,
        knee-rocking, hip bounces: none of those move the shoulder 0.28 torsos)
        AND line_dev ≤ 0.15 at the deepest frame (no sag, no pike)
  ↓
UP_PHASE: θ recovering ≥ 120 ms
  ↓
TOP_VALIDATION: θ ≥ 150°, held ≥ 200 ms, return to baseline ±0.08 torsos
  ↓
ANTI-CHEAT VALIDATION (§6 — must pass, else CHEAT_SCORE grows)
  ↓
VALID_REP += 1        (one count, once per descent arm; 450 ms global re-arm)
```

**Adaptive thresholds — where calibration earns its keep:**
- `θ_top`/`θ_depth` initialized from the hunter's own calibration lockout: `θ_top = Q10(θ during 2 s calibration hold) − 10°`; `θ_depth = θ_top − clamp(0.55·ROM_prior, 70°, 95°)`. First validated rep then updates ROM estimate (only from *witnessed-valid* bottoms — contamination can never raise the bar, a lesson our v3 engine already learned).
- Camera three-quarter views widen `θ_depth` by +8° and *reduce confidence*; front views are refused at the gate, not "tolerated".

---

## 6 · ANTI-CHEAT ALGORITHM

Every defensive rule emits a **violation event**; the **CHEAT_SCORE** is a decaying accumulator (×0.95 per second) — isolated anomalies annoy, patterns convict.

| Attack / failure | Detector | Action |
|---|---|---|
| Half reps (top-half only) | `shoulder_h_max < 0.28` ⇒ bottom validation fails | NO COUNT, coaching line |
| Bottom-half bouncing | no return to baseline ±0.08 before next descent | NO COUNT |
| Head-only nods | `shoulder_h` static while nose plunges (head–shoulder divergence > 0.15) | NO COUNT + FORM flag |
| Hip-only dips | hip-Y moves, shoulder-Y doesn't | NO COUNT + FORM flag |
| Knee push-ups | line_dev pattern: shoulder→hip→ankle colinear at side view only with straight body; knee contact geometry ⇒ knee-ankle hinge visible; detected as persistent line_dev > 0.15 in "safe-dip" shape | NO COUNT (battle), allow in training quests w/ label |
| Phone bumped / repositioned | IMU witness (§2) OR landmark baseline slew > 0.15 torsos in one frame | FREEZE → mini-recalib (1.5 s) |
| Camera pointed elsewhere mid-set | pose loss > 0.4 s (LK bridge limit) | sequence ABORT, no inferred reps |
| Two people / swap | multi-torso alert; ROI aspect flip; face-embedding optional (v2) | INVALIDATE session |
| Mimicking video (phone aimed at a screen) | moiré/aliasing energy + LCD flicker band 24–60 Hz in ROI; synthetic luma modulation; (v2) random on-screen flash challenge: we strobe the screen in a pseudo-random pattern and require the *captured* luma to correlate — a pointed-at screen cannot replay a pattern it hasn't seen | SESSION INVALID + high cheat weight |
| Pre-recorded deepfake/replay of *their own* past set | flash challenge (above) is the only hard defense; without it this class is **declared residual risk** (see §15 honesty) | v2 challenge mode for ranked |
| Robotically fast "reps" | physics gate: rep < 500 ms total or bottom dwell < 100 ms impossible for strict form | NO COUNT |
| Body teleports between reps | baseline slew guard (already proven in v3) | FREEZE + recalib |

```
CHEAT_SCOREΔ at each finalized rep attempt:
  FORM_violation        +0.15
  depth_shortfall       +0.10
  sequence_abort        +0.20
  camera_moved          +0.25   (also hard-vetoes pending reps)
  multi_person          +0.60   (also session flag)
  screen_signature      +1.00   (session invalid)
VALID_REP requires CHEAT_SCORE before-decay < 0.50 at finalize time.
SCORE ≥ 1.00 ⇒ session enters DISPUTED: battle pauses, server notified.
```

---

## 7 · CAMERA CALIBRATION (5–8 s, the battle gate)

```
T+0.0–0.5 s  STABILITY  : IMU variance quiet?                     else "SET THE PHONE DOWN"
T+0.5–2.0 s  PERSON     : pose present, full-body bbox +15% margin else "SHOW YOUR FULL BODY"
T+2.0–3.0 s  VIEW       : classify side / three-quarter / front     push-up battle ⇒ front = RED
T+3.0–4.0 s  LIGHT/QUAL : luma mean∈[40,220], σ≥12, blur var ≥ thr  else "MORE LIGHT / WIPED LENS"
T+4.0–6.0 s  TOP LOCK   : hold plank top 1.5 s: learn θ_top, torso_len,
                          floor line, ROM prior                      "HOLD THE TOP POSITION"
⇒ PROFILE saved → battle unlocked
```

**HUD:** one traffic dot — GREEN ready / YELLOW adjust (named reason) / RED invalid (battle button disabled). Quests may start YELLOW; **ranked battles require GREEN.**

---

## 8 · TWO-PLAYER SYNCHRONIZATION ARCHITECTURE

```
Both phones ──► signed REP EVENTS ──► Arena server (Supabase realtime)
rep_event = {
  battle_id, seq_no, t_device_ms, rep_quality{θ_min, line_dev, shoulder_h,
  tempo_ms}, confidence, cheat_score, engine_version
}
signature = HMAC_SHA256(device_session_key, canonical(event))
```

- **No raw video ever leaves the device** (privacy contract, §9).
- **Device session key** minted per battle via server handshake (`battle_nonce`); events can't be replayed into a different battle.
- **Server is the referee**, not the counter: it never re-derives reps (no video), but it *polices* them:
  - monotonic `seq`, plausible inter-rep intervals (physics gate server-side: <500 ms gaps rejected regardless of client),
  - rep-rate outliers vs. `rep_quality` tempo distribution (client claiming 60 reps with reported 2 s tempos convicts itself),
  - both players' engines must be `engine_version ≥ floor` for ranked,
  - final hash-chain of the event log committed at battle end → tamper-evident record for disputes.
- Trust anchor (v2): **Play Integrity API** verdict per battle; rooted/tampered clients battle unranked only. A determined attacker with a patched APK on a rooted phone can still forge events — see §15; the mitigation is server-side implausibility + social dispute flow with optional consented 2 s proof-snippet upload (§9).

---

## 9 · ON-DEVICE PRIVACY

```
Camera frames → [device RAM only] → keypoints/features → [device] decision
                        │
                        ├── NEVER: raw video to server (no code path exists)
                        └── OPT-IN ONLY: 2 s dispute snippet, explicit consent
                            dialog, badge visible while uploading, then purge
```
Detection + validation + calibration + counting run **fully offline** (airplane-mode quest proofs still work; they sync when back on the grid). Only *battles* need the network — competition is inherently online.

---

## 10 · LOW-END ANDROID — TWO RUNTIME PERSONALITIES

| | PERFORMANCE MODE | ACCURACY MODE (ranked default if capable) |
|---|---|---|
| Resolution | 640×480 | 1280×720 |
| Pose rate | 12–15 fps (process every 2nd frame) | 24–30 fps |
| TCN cadence | stride 16 (~500 ms) | stride 8 |
| ML delegates | XNNPACK ×4 | GPU/NNAPI if stable on device |
| LK bridge | off | on |
| Thermal response | auto-step-down | auto-step-down |
**Runtime adaptation:** rolling inference-time EMA; >40 ms/frame sustained ⇒ step down a tier; <18 ms for 10 s ⇒ step up. Battery-saver OS hint forces PERFORMANCE.

---

## 11 · DATASET STRATEGY (v1)

**Scale for a serious first model:** ~**12,000–15,000 rep sequences** ≈ 500–800 users × 20 reps × varied setups (with the deterministic engine harvesting weak labels from *accepted* reps on real devices, human-verified on a sample).

| Axis | Coverage targets |
|---|---|
| Viewpoint | side 70% · 3/4 20% · front 10% (front used ONLY as negative/refusal training) |
| Body | height 145–200 cm, weight 45–120 kg, sex representation, limb-length variety |
| Hard negatives | half reps, knee push-ups, head nods, hip dips, bounce reps, hand-wave, pet crossing frame, two people, video-on-screen, camera bump mid-set — **≥30% of corpus** (fraud is rarer than form errors in reality, so we over-sample it) |
| Conditions | dark/warm/fluorescent/blown-backlight light; floor types; 6+ phone tiers (OIS or not, autofocus hunting) |
| Speed | 0.6 s–4 s per rep |
**Labeling protocol:** 2-pass human labels on sequence level + one arbitration pass on disagreements; every auto-accepted real rep is eligible for active-learning sampling (manually label the 5% highest-entropy windows). No synthetic-renderer data in v1 (domain gap on hands/floor contact is brutal); augmentation = crop/photometric/temporal-jitter/small angle warps only.

---

## 12 · ACCURACY BENCHMARK — PROOF PROTOCOL

**Test corpus:** 10,000+ manually adjudicated rep sequences from ≥150 withheld users (never in training), stratified across the axes above. Repeated per engine build, on-device (not desktop re-implementation).

| Metric | Formula | Target (envelope) |
|---|---|---|
| Valid-rep precision | TP/(TP+FP) | **≥ 99.9%** (≤1 false award / 1000) |
| Valid-rep recall | TP/(TP+FN) | ≥ 95% controlled · ≥ 90% edge conditions |
| F1 | harmonic mean | ≥ 0.97 controlled |
| Per-session count error | |predicted−truth|/truth ≥1 rep | ≤ 2% of sessions |
| Cheat-detection precision | caught-cheat / flagged | ≥ 95% (few innocent flags) |
| REP-REVIEW rate | held reps / total | ≤ 5% (fail-safe bounded) |
Report per-angle, per-light, per-device-tier slices — never a single headline number. Gate: any slice with valid-rep precision < 99% ships with that condition moved from YELLOW to RED at the calibration gate. **This is how we "optimize toward 99.9%": we don't claim it — we fence the envelope until it's true.**

---

## 13 · FAIL-SAFE BEHAVIOR

```
finalized candidate fails a SOFT check but passes all HARD checks
        ↓
REP REVIEW (≤ 1.5 s): machine holds, HUD: "HOLD POSITION — VERIFYING REP"
  · evidence completes (top re-witnessed, stability returns) → VALID_REP
  · else → REJECT silently (count does not move), coaching line shown
HARD check failure at any point → instant reject, no review offered.
```
Design bias knobs live server-side (remote config), so precision/recall bias is tunable without an app release.

---

## 14 · TECHNOLOGY STACK — WHAT'S CUSTOM, WHAT'S BORROWED

| Component | Choice | Why |
|---|---|---|
| Camera | CameraX (Kotlin) | vendor quirks handled, zero NDK pain |
| **Pose v1** | **MoveNet Thunder via TFLite directly** (open weights, 17 kpt) — NOT "ML Kit as the product" | we own normalization, ROI, and the failure surface; ML Kit kept as device-compat fallback only |
| Pose v2 (optional) | BlazePose TFLite standalone (33 kpt, world coords) | depth-ish signal for sag/pike refinement |
| Temporal model | **Custom-trained causal TCN** (§4), TFLite int8, XNNPACK | our classes, our envelope, our data |
| Patch tracker | OpenCV-Android LK optical flow | tiny, proven, no training |
| IMU fusion | Rule-based (Kotlin) | physics doesn't need a network |
| Anti-replay | v1: moiré/flicker DSP probes; v2: **random flash-liveness challenge** | simple, brutal effectiveness |
| Server | existing Supabase: events table + plausibility RPC + hash-chain | no new infra class |
| NDK/C++ | **only if profiling proves it** (LK hot loop) | premature native code is a maintenance tax |

---

## 15 · THREAT MODEL (honest residual risk)

| Threat | Defense | Residual risk |
|---|---|---|
| Fake reps via movement tricks | envelope + state machine + TCN + physics gates | Low |
| Phone moved / angle gamed | IMU veto + slew guard + recalib | Low |
| Second person swap | multi-torso + ROI/alarm | Medium (identical twins at 3/4 view… v2 face check) |
| Video of a screen | moiré + flicker probes | Medium→Low with flash-liveness (v2) |
| Rooted client, patched APK, forged events | Play Integrity + server plausibility | **Medium — the honest ceiling.** No consumer CV system defeats a fully controlled client. Mitigation: reputation stats, dispute flow, consented snippet, ranked rewards throttling. |
| Server/MITM tampering | TLS + signed events + hash-chained log | Low |

---

## 16 · ENGINE PSEUDOCODE (complete rep-counting loop)

```
fun onFrame(f):
    probes = qualityProbes(f)                       // luma, blur, moiré
    imu = stabilityWitness.sample()                 // §2
    if !imu.stable: freeze("CAMERA MOVED"); goto miniRecalib; return

    pose = poseEstimator.run(f) or lkBridge.step()  // ≤0.4 s bridge only
    multiPersonGuard(pose)                          // session flag if tripped
    if pose == null: dropout += dt; if dropout > 0.4s: abortSequence(); return
    dropout = 0
    x = featureBuilder.normalize(pose)              // 12-dim, torso units
    window.push(x)

    switch state:
      READY:        if holds(x, θ≥150 & line_dev≤.10 & wristFix & 500ms): arm
      ARMED:        trend = shoulder_rising(x, ≥120ms, ≥4 frames)
                    if trend: state=DESCENDING; trough=∞; descentStart=t
      DESCENDING:   trough = min(trough, θ)
                    track shoulder_h_max, worst line_dev at deepest frame
                    if θ rising ≥120ms: state=ASCENDING
      ASCENDING:    if θ≥150 sustained ≥200ms AND |baseline−start|≤0.08:
                       candidate = buildCandidate(trough, shoulder_h_max, ...)
                       state = REVIEW else: state=ARMED (bounce guard)
      REVIEW:       ok_depth  = trough ≤ θ_depth AND shoulder_h_max ≥ 0.28
                    ok_form   = line_dev ≤ 0.15
                    ok_tempo  = 500ms ≤ (t−descentStart) ≤ 4000ms
                    tcn       = temporalNet.score(window)      // §4
                    if !ok_depth or !ok_tempo: reject("DEPTH/TEMPO"); cheat(+0.10)
                    else if !ok_form: reject("FORM"); cheat(+0.15); state=ARMED
                    else if tcn.VALID ≥ 0.85 AND cheatScore < 0.5:
                             commitRep(candidate); cheat(−0.05 floor 0); state=READY
                    else: holdRepReview(1.5s)                 // §13

fun commitRep(c):
    reps += 1; haptics.rep(); emitSignedEvent(c)    // battles (§8)

fun abortSequence(): state=READY; cheat(+0.20); coach("STAY IN FRAME")
```

---

## 17 · RECOMMENDED MODEL ARCHITECTURES (summary card)

- **Pose (v1):** MoveNet **Thunder** int8 (TFLite, 17 kpt) @ 15–30 fps; ROI-cropped second pass optional. *Fallback:* ML Kit Pose STREAM where TFLite delegates crash (vendor fragmentation).
- **Temporal:** causal TCN 48×12 → 7 classes (§4), ~120 k params, 0.4 MB.
- **Liveness (v2):** flash-challenge correlator (pure DSP, no model) + moiré spectral probe.
- **Nothing else learned** in v1 — the envelope + deterministic rules carry precision.

## 18 · EXPECTED COMPUTATIONAL COST (per frame, mid-tier 2023+ / low-tier 2019)

| Stage | Mid-tier (P60-class) | Low-tier (SD665-class) |
|---|---|---|
| YUV→InputImage + probes | 2–3 ms | 3–5 ms |
| MoveNet Thunder 720p-int8 | 14–22 ms | 25–38 ms (perf mode: 480p) |
| LK bridge (2 patches) | 1–2 ms | 2–4 ms |
| Features + state machine | <0.5 ms | <1 ms |
| TCN (stride 8, amortized) | ~1 ms/frame | ~2 ms/frame |
| **Total** | **~20–28 ms ⇒ 30 fps OK** | **~33–48 ms ⇒ perf mode 15 fps** |
Battery: ~8–12%/10 min battle (screen on) mid-tier — acceptable; thermal step-downs handled by §10.

## 19 · FULLY ON-DEVICE (yes/no)

Detection, calibration, validation, counting, anti-cheat v1, offline quests: **fully on-device.** Required network: battle sync + plausibility verdict + (v2) integrity verdict. Nothing else.

## 20 · DEVELOPMENT ROADMAP

| Phase | Scope | Exit criterion |
|---|---|---|
| **0 — Envelope + Deterministic v4** *(I can build this immediately, no dataset needed)* | Calibration gate (§7) into quest/battle; side-view enforcement w/ coaching overlays; IMU stability veto; normalized-torso feature layer; state machine §5; REP REVIEW; quest proofs migrate to tuned deterministic engine | Field test: 25/25 clean reps side-view; 0 counts on nod/half-rep manual abuse pass |
| **1 — Pose ownership** | MoveNet-Thunder TFLite in-app (ML Kit relegated to fallback), ROI locker, LK bridge | parity vs ML Kit across 5 devices; dropout ≤ 2% |
| **2 — Data engine** | Recording harness (consented, envelope-guided), 500+ hunters × 20 reps via phased rollout; labeling pipeline | 10k labeled sequences |
| **3 — TCN online** | Train/quantize/ship; shadow-mode first 2 weeks (scores logged, rules decide), then promote | shadow: TCN agrees ≥97% with accepted-rule reps; disagreement audit clean |
| **4 — Anti-cheat v2** | flash liveness, integrity verdict, server plausibility v2 | red-team pass on replay/swap matrix |
| **5 — Benchmark + certification** | §12 protocol on 10k withheld | slice-wise report ≥ targets; envelope fences tuned |
| **6 — Modes + polish** | adaptive tiers, perf mode ship, battle UX polish | low-tier device grid passes |

**What I need from you for Phase 1–2:** crowd field clips — 30 side-view push-up videos from different hunters/phones (I'll give you an upload pipeline), and your agreement that **ranked battles require the side-view calibration GREEN**. That's the price of a fair war.

---

### APPENDIX · Why your quest proofs should migrate to Phase 0
The same envelope fixes the daily quest pain you've been hitting: side view makes elbow angle observable, which makes the deterministic engine's job (your v3 lineage) straightforward. The artifacts in this doc are sequenced so **every piece of Phase 0 directly serves battles later** — no throwaway work.
