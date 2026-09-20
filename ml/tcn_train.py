#!/usr/bin/env python3
"""
TCN TRAIN — CV-BATTLE-ARCHITECTURE §4 Phase 3 (bootstrap-synthetic v0).

Generates 48×12 feature windows from the same kinematic physics the battle-sim
uses, trains the causal TCN (12→64→64→128→128, k=3, d=1/2/4/8, tanh-GELU,
GAP, dense-7), reports per-class precision/recall + SHADOW-VS-RULES agreement,
and exports raw-f32 weights for the zero-dep in-app forward pass (v0 only).
When the Phase-2 corpus lands ≥10k labeled real windows, this same script is
re-run on the export for the earned v3.0 model (§20 promotion gate).
"""
import json, math, os, random, struct, sys, time

random.seed(47)
import numpy as np
np.random.seed(47)

CLASSES = ["VALID_PUSHUP", "INVALID_DEPTH", "INVALID_FORM", "PARTIAL_REP",
           "CHEAT_MOVEMENT", "CAMERA_MOVEMENT", "UNKNOWN"]
T, F = 48, 12

# ── kinematics → 12-dim features (mirrors engine onFrame12 ordering) ─────────
def ema(xs, a=0.5):
    out = np.empty_like(xs); out[0] = xs[0]
    for i in range(1, len(xs)): out[i] = out[i-1] + a * (xs[i] - out[i-1])
    return out

def ease(i, n):
    x = min(1.0, i / max(1, n)); return 0.5 - 0.5 * math.cos(math.pi * x)

def base_window(view_q=1.0, top_deg=None, noise=1.0):
    top = top_deg if top_deg else random.uniform(164, 176)
    w = np.zeros((T, F), np.float32)
    w[:, 0] = top / 180.0; w[:, 1] = top / 180.0
    w[:, 2] = 0.0; w[:, 3] = 0.015
    w[:, 4] = np.clip(np.random.normal(0.07, 0.02, T), 0.03, 0.14)
    w[:, 5] = np.clip(np.abs(np.random.normal(0.02, 0.015, T)), 0, 0.1)
    w[:, 6] = w[:, 5] * np.random.uniform(0.8, 1.2)
    w[:, 7] = np.clip(np.random.normal(0.88, 0.06, T), 0.42, 0.99)
    w[:, 8] = np.clip(np.random.normal(0.33, 0.03, T), 0.2, 0.8)
    w[:, 9] = np.clip(1.0 + np.random.normal(0, 0.03, T), 0.85, 1.15)
    w[:, 10] = view_q
    w[:, 11] = np.clip(np.random.normal(0.02, 0.02, T), 0, 0.1)
    # far-arm observation model
    if view_q < 1.0:
        w[:, 0] = np.clip(w[:, 0] + np.random.normal(0.0, 0.06, T), 0, 1)
    return w, top

def inject_rep(w, top, theta_lo, drop_lo, frames, start, line_lo=None, dwell_frac=0.14, line_at_bottom=None):
    """writes one rep into the window (cosine descent/dwell/ascent + EMA finish)"""
    dwell = max(1, int(frames * dwell_frac))
    down = int((frames - dwell) * 0.5); up = frames - dwell - down
    prof = []
    for i in range(down): prof.append(ease(i, down))
    prof += [1.0] * dwell
    for i in range(up): prof.append(1.0 - ease(i, up))
    th = np.empty(T); dy = np.empty(T)
    for t in range(T):
        if t < start or t >= start + frames:
            prof_t = 0.0; th[t] = top; dy[t] = 0.0
        else:
            p = prof[t - start]; prof_t = p
            th[t] = top - (top - theta_lo) * p
            dy[t] = drop_lo * p
    th += np.random.normal(0, 1.3, T); dy += np.random.normal(0, 0.008, T)
    w[:, 0] = w[:, 1] = ema(th, 0.5) / 180.0
    w[:, 2] = ema(dy, 0.5)
    w[:, 3] = ema(dy * np.random.uniform(0.96, 1.04) + 0.015, 0.5)
    if line_at_bottom is not None:
        w[:, 4] = w[:, 4] + (line_at_bottom - w[:, 4]) * np.clip(ema(dy / max(drop_lo, 1e-3), 0.5) / max(drop_lo, 1e-3), 0, 1)
        w[:, 4] = np.clip(w[:, 4], 0, 0.9)
    if line_lo is not None:
        w[:, 4] = np.clip(np.random.normal(line_lo, 0.02, T), 0, 0.9)
    if random.random() < 0.3:  # near-arm occlusion on some frames
        gap = random.randint(2, 6); s = random.randint(0, T - gap - 1)
        w[s:s+gap, 1] = -2.0 / 180.0 * 0 + (-0.0111)
    return w

def gen(cls):
    vq = 1.0 if random.random() < 0.7 else 0.7
    w, top = base_window(vq)
    fr = random.randint(13, 48)
    st = random.randint(-6, T - 6)              # rep may run past window edges
    if cls == 0:   # VALID
        w = inject_rep(w, top, random.uniform(58, 92), random.uniform(0.55, 1.05), fr, max(0, st),
                       line_at_bottom=random.uniform(0.06, 0.15))
    elif cls == 1: # INVALID_DEPTH — half dip OR elbow-deep shoulder-static dip
        if random.random() < 0.5:
            w = inject_rep(w, top, random.uniform(110, 145), random.uniform(0.03, 0.18), fr, max(0, st))
        else:
            w = inject_rep(w, top, random.uniform(75, 90), random.uniform(0.04, 0.19), fr, max(0, st))
    elif cls == 2: # INVALID_FORM — full rep but the body line folds at the bottom
        w = inject_rep(w, top, random.uniform(62, 90), random.uniform(0.6, 1.0), fr, max(0, st),
                       line_at_bottom=random.uniform(0.19, 0.42))
    elif cls == 3: # PARTIAL — descent never returns inside the window
        w = inject_rep(w, top, random.uniform(62, 92), random.uniform(0.55, 1.0),
                       frames=fr * 2, start=max(0, T - int(fr * 0.6)))
        w[T - random.randint(0, 4):, 2] = np.random.uniform(0.5, 0.9)
    elif cls == 4: # CHEAT — bounce spam / nods / shoulder waves
        mode = random.random()
        if mode < 0.4:
            for k in range(3):
                w = inject_rep(w, top, random.uniform(95, 125), random.uniform(0.2, 0.5),
                               frames=random.randint(7, 12), start=min(T - 8, k * 13))
        elif mode < 0.7:
            w = inject_rep(w, top, top - random.uniform(4, 12), random.uniform(0.01, 0.06),
                           frames=random.randint(8, 14), start=T // 3)
        else:
            t = np.arange(T)
            w[:, 2] = 0.3 + 0.25 * np.sin(t * 0.5) + np.random.normal(0, 0.01, T)
            w[:, 0] = w[:, 1] = top / 180.0
    elif cls == 5: # CAMERA_MOVEMENT — baseline jumps, freezes, noise bursts
        j = random.randint(8, T - 8)
        w = inject_rep(w, top, random.uniform(62, 92), random.uniform(0.55, 0.95), fr, max(0, st))
        w[j:j+3, 2] += np.random.uniform(-0.5, 0.8, (3,))
        w[j:j+2, 8] = 2.0
        b = random.randint(0, T - 4); w[b:b+4] = w[b]            # frozen frames
        w[:, 7] = np.clip(w[:, 7] - np.random.uniform(0.1, 0.3), 0.2, 1)
    else:          # UNKNOWN — stand / walk / no-pose / drift
        mode = random.random()
        if mode < 0.35:   # standing
            w[:, 4] = np.random.uniform(0.25, 0.6)
            w[:, 0] = w[:, 1] = random.uniform(168, 178) / 180.0
            w[:, 2] = np.random.normal(-0.1, 0.06, T)
        elif mode < 0.6:  # no pose — sentinel channels
            w[:] = 0
            w[:, 0] = w[:, 1] = -0.0111
            w[:, 7] = np.clip(np.random.normal(0.2, 0.1, T), 0, 0.4)
            w[:, 10] = vq
        else:             # drift
            w[:, 2] = np.cumsum(np.random.normal(0, 0.02, T))
    # channel-2 occasionally unwitnessed (hidden far arm in side view)
    if random.random() < 0.25:
        w[:, 1] = -0.0111
    return w, cls

def build(n):
    xs = np.zeros((n, T, F), np.float32); ys = np.zeros(n, np.int64)
    weights = [0.22, 0.13, 0.13, 0.13, 0.13, 0.13, 0.13]
    for i in range(n):
        c = np.random.choice(7, p=weights)
        xs[i], ys[i] = gen(c)
    return xs, ys

# ── model (causal TCN — left-pad only) ───────────────────────────────────────
import torch
import torch.nn as nn
torch.manual_seed(47)
torch.set_num_threads(max(1, os.cpu_count() // 2))

class CausalConv(nn.Conv1d):
    def __init__(self, cin, cout, k, dil):
        super().__init__(cin, cout, k, dilation=dil, padding=0)
        self.left = (k - 1) * dil
    def forward(self, x):
        return super().forward(nn.functional.pad(x, (self.left, 0)))

class TCN(nn.Module):
    def __init__(self):
        super().__init__()
        self.net = nn.Sequential(
            CausalConv(F, 64, 3, 1), nn.GELU(approximate="tanh"),
            CausalConv(64, 64, 3, 2), nn.GELU(approximate="tanh"),
            CausalConv(64, 128, 3, 4), nn.GELU(approximate="tanh"),
            CausalConv(128, 128, 3, 8), nn.GELU(approximate="tanh"),
        )
        self.head = nn.Linear(128, 7)
    def forward(self, x):                    # x: [B, T, F]
        h = self.net(x.transpose(1, 2))      # [B, C, T]
        return self.head(h.mean(dim=2))      # GAP over time → classes

def main():
    t0 = time.time()
    n_train, n_val = 26000, 4000
    print(f"generating {n_train} train / {n_val} val windows…")
    xt, yt = build(n_train); xv, yv = build(n_val)
    model = TCN()
    nparams = sum(p.numel() for p in model.parameters())
    print(f"params: {nparams} (~{nparams*4/1e6:.2f} MB f32)")

    opt = torch.optim.AdamW(model.parameters(), lr=8e-4, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=6)
    lossf = nn.CrossEntropyLoss()
    dev = "cpu"
    model.to(dev)
    X = torch.tensor(xt); Y = torch.tensor(yt)
    bs = 512
    for ep in range(6):
        model.train(); perm = torch.randperm(n_train); tot = cor = 0; ls = 0.0
        for i in range(0, n_train, bs):
            idx = perm[i:i+bs]
            opt.zero_grad()
            out = model(X[idx])
            loss = lossf(out, Y[idx])
            loss.backward(); opt.step()
            ls += loss.item() * len(idx)
            cor += (out.argmax(1) == Y[idx]).sum().item(); tot += len(idx)
        sched.step()
        model.eval()
        with torch.no_grad():
            pv = model(torch.tensor(xv)).argmax(1).numpy()
        va = (pv == yv).mean()
        print(f"epoch {ep+1}/6  loss {ls/tot:.4f}  train_acc {cor/tot:.3f}  val_acc {va:.3f}")

    # ── metrics: per-class + shadow-vs-rules agreement (doc promotion metric) ──
    model.eval()
    with torch.no_grad():
        pv = model(torch.tensor(xv)).argmax(1).numpy()
    per = {}
    for c, name in enumerate(CLASSES):
        tp = int(((pv == c) & (yv == c)).sum())
        fp = int(((pv == c) & (yv != c)).sum())
        fn = int(((pv != c) & (yv == c)).sum())
        per[name] = dict(
            precision=round(tp / max(1, tp + fp), 4),
            recall=round(tp / max(1, tp + fn), 4),
        )
    agree_valid = per["VALID_PUSHUP"]["recall"]
    agree_reject = np.mean([per[c]["recall"] for c in CLASSES if c != "VALID_PUSHUP"])
    agree = float((agree_valid + agree_reject) / 2)
    print(f"per-class: {json.dumps(per, indent=1)}")
    print(f"shadow-vs-rules agreement (synthetic): {agree*100:.1f}%  (real-data gate: ≥97%)")

    # ── export raw-f32 weights (in-app forward layout) ────────────────────────
    out_dir = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "ml")
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, "pushup_tcn_synth_v0.weights.bin")
    convs = [m for m in model.net if isinstance(m, CausalConv)]
    with open(path, "wb") as fh:
        fh.write(b"TCN1")
        for c in convs:
            w = c.weight.detach().numpy()          # [outC, inC, k]
            wkio = np.transpose(w, (2, 1, 0)).astype("<f4")   # → [k, inC, outC]
            fh.write(wkio.tobytes())
            fh.write(c.bias.detach().numpy().astype("<f4").tobytes())
        fh.write(model.head.weight.detach().numpy().astype("<f4").tobytes())  # [7, 128]
        fh.write(model.head.bias.detach().numpy().astype("<f4").tobytes())
    print(f"exported {path} ({os.path.getsize(path)} bytes)")

    rep = dict(
        model="pushup_tcn_synth_v0", params=nparams, val_acc=round(float(va), 4),
        per_class=per, shadow_rules_agreement_synthetic=round(agree, 4),
        note="BOOTSTRAP-SYNTHETIC v0 — promotion requires real-corpus retrain + ≥97% agreement (§20)",
        train_seconds=round(time.time() - t0, 1),
    )
    with open(os.path.join(os.path.dirname(__file__), "v0_report.json"), "w") as fh:
        json.dump(rep, fh, indent=2)
    print("report → ml/v0_report.json")

if __name__ == "__main__":
    main()
