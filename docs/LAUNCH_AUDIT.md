# THE SYSTEM — LAUNCH AUDIT (T-28 days)

> Master prompt: 19-step implementation order. This map is the living ledger.
> Update after every shipped step. Nothing in here is aspirational — the
> "STATUS" column only moves when code lands GREEN on CI.

## Implementation map (what already exists — preserved, never deleted lightly)

| Subsystem | Where | State |
|---|---|---|
| Auth + username | `data/repo/AuthRepository`, `ui/onboarding/AwakeningFlow`, `OnboardingGate` | Auth-first flow exists; username-creation step position to verify (Phase 7) |
| Splash/onboarding | `ui/splash/*` (restored classic 0.13.1) | WORKING |
| XP / rank / VC economy | server RPCs (`SystemRepository`, `SystemMath`) | SERVER-AUTHORITATIVE, WORKING |
| Quests + fitness | `ui/protocol`, `ui/training/*`, `QuestProofScreen` | REP PULSE v5 (0.15.0) — geometry-agnostic, sim-green |
| Camera/pose | `ui/training/estimate` (MoveNet primary / MLKit failover), `RepPulseEngineV5` | WORKING |
| Local AI | `ai/*` — AIOrchestrator, LocalModelManager (Qwen 0.5B/1.5B pinned bundles), DeviceCapabilityManager, DeterministicAI, FitnessIntelligence | ACTIVE (0.14.0). No model removed. |
| Chess | `chess/Engine, ChessAI, Puzzles`; `ui/chess/*` | WORKING but had: live-board AI mutation race, mid-game power chips, AI ELO labels, puzzle repetition |
| Marketplace | `ui/market/MarketScreen` (standalone tab slot) | EXISTS — Phase 3 moves it under Profile→AI Benchmark |
| Squad/social | `ui/social/HunterFeed*`, `FeedGateScreen`, `ui/chat/Chat*` (DM infra exists) | PARTIAL — Phase 6 builds full Squad |
| Black Room | `ui/training/BlackRoomScreen` + course row | EXISTS, always-visible — Phase 4 gates it |
| Notifications/services | `service/QuestNotifier, StepCounterService, RecoveryTracker, RoutineAlarmScheduler` | WORKING |
| Admin | `ui/admin/*` | WORKING |

## Step ledger

| # | Step | Status | Build |
|---|---|---|---|
| 1 | Audit + plan | ✅ THIS DOCUMENT | — |
| 2 | Crash-prone arch fixes | ✅ chess live-board race killed (sim-proven: 98% corrupt reads → ZERO) | 0.15.1 · run 37026712545 |
| 3–5 | Chess state arch + UI + difficulty + puzzles | ✅ clone-search pipeline · director puzzles (22 pack) · pass-and-play · honest labels | 0.15.1 · run 37026712545 |
| 6 | Sports-path onboarding | ✅ migration 022 + onboarding p26 + explicit opt-out | 0.16.0 · run 37043924851 |
| 7 | Adaptive routine engine | ✅ 4-law arc + mig 023 verdicts + week-aware drafts | 0.17.0 · run 37100247561 |
| 8 | Marketplace → Profile | ✅ pushed route + SYSTEM SUPPLY card | 0.17.1 · run 37101145814 |
| 9 | Black Room eligibility/pricing | ⬜ | |
| 10 | Country/currency | ⬜ | |
| 11–14 | Squad system + chat + progression + wars | ⬜ | |
| 15 | Prediction safety | ⬜ (VC display-only law holds) | |
| 16 | Auth → username order | ⬜ | |
| 17–19 | Regression / perf / polish | ⬜ | |
