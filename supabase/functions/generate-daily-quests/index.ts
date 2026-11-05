// ═══════════════════════════════════════════════════════════════════════════
// THE SYSTEM — Edge Function: GENERATE DAILY QUESTS (master directive §4)
// The TODAY QUEST ledger is no longer static templates: qwen/qwen3.8-27b reads
// the hunter's FULL context (level/rank/streak/history, biometrics + gender-true
// BMR, active MUSCLE/SPECIAL courses, sport path + experience + practice
// environment) and drafts TODAY'S personalized quest blocks as strict JSON.
//
// HARD LAWS:
//  • §4.3 PERIODIZATION (isodow): Mon LIGHT · Tue HEAVY · Wed SUPER LIGHT ·
//    Thu LIGHT · Fri HEAVY · Sat MODERATE/SPORT DRILLS · Sun REST.
//  • XP math is SERVER-side (difficulty base × intensity multiplier) — the
//    model never prices progression. VC is never granted here (mig 038).
//  • Rows land in daily_quests with source='AI_BRAIN' + ai_generated=true +
//    a circadian scheduled_window (§6): morning mobility 06:00–07:30, main
//    strength/field block 16:30–18:30 (peak body temperature), evening
//    mental/skill 20:00–21:00.
//  • Sequential integrity preserved: first block READY, the rest LOCKED.
//  • GROQ absent / bad JSON / any throw ⇒ the deterministic template floor
//    (ensure_daily_quests, itself periodization-scaled by mig 039) issues
//    today's set instead — honestly stamped ai_generated=false.
//  • Once today has any non-trial quest rows this fn is a no-op (idempotent).
// ═══════════════════════════════════════════════════════════════════════════
import { createClient } from "jsr:@supabase/supabase-js@2";

const MODEL = Deno.env.get("GROQ_MODEL") ?? "qwen/qwen3.8-27b";
const GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

// directive §4.3 — the weekly intensity cycle (isodow 1=Mon … 7=Sun)
const INTENSITY: Record<number, string> = {
  1: "LIGHT", 2: "HEAVY", 3: "SUPER LIGHT", 4: "LIGHT", 5: "HEAVY", 6: "MODERATE", 7: "REST",
};
const XP_BASE: Record<string, number> = { EASY: 20, MEDIUM: 35, HARD: 50 };
const XP_MULT: Record<string, number> = { "SUPER LIGHT": 0.5, LIGHT: 0.8, MODERATE: 1.0, HEAVY: 1.3, REST: 0.4 };
const KINDS = new Set(["PUSHUP","SQUAT","SITUP","LUNGE","PLANK","JACKS","BURPEE","RUN","WALK","SPRINT",
  "SKILL","CORE","CIRCUIT","STRENGTH","PLYO","STRETCH","DRIBBLE","PASSING","REST"]);

function tierFor(level: number): string {
  if (level >= 90) return "SS"; if (level >= 70) return "S";
  if (level >= 50) return "A"; if (level >= 35) return "B";
  if (level >= 22) return "C"; if (level >= 12) return "D";
  if (level >= 5) return "E"; return "F";
}
function bmrFor(sex: string | null, w: number, h: number, age: number): number {
  const base = 10 * w + 6.25 * h - 5 * age;
  return Math.round(sex === "F" ? base - 161 : base + 5);
}
// server decides HOW a quest is proven — never the model
function verificationFor(kind: string): string {
  if (["PUSHUP", "SQUAT", "SITUP", "LUNGE"].includes(kind)) return "CAMERA";
  if (["RUN", "WALK", "SPRINT"].includes(kind)) return "STEPS";
  if (["REST", "STRETCH"].includes(kind)) return "MANUAL";
  return "TIMER";
}
function unitFor(kind: string): string {
  if (["RUN", "WALK", "SPRINT"].includes(kind)) return "METERS";
  if (["PUSHUP", "SQUAT", "SITUP", "LUNGE", "JACKS", "BURPEE"].includes(kind)) return "REPS";
  return "SEC";
}
const WINDOW_RX = /^([01]\d|2[0-3]):[0-5]\d$/;
const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), hi);

Deno.serve(async (req: Request) => {
  const authHeader = req.headers.get("Authorization") ?? "";
  if (!authHeader.startsWith("Bearer ")) return Response.json({ error: "auth_required" }, { status: 401 });

  const userClient = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: authHeader } },
  });
  const { data: { user }, error: authErr } = await userClient.auth.getUser();
  if (authErr || !user) return Response.json({ error: "auth_invalid" }, { status: 401 });

  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const today = new Date().toISOString().slice(0, 10);
  const isoDow = ((new Date().getUTCDay() + 6) % 7) + 1;         // UTC calendar = quest_date calendar
  const intensity = INTENSITY[isoDow];

  // §4 law: idempotent per day — trial rows never satisfy the generator
  const { data: existing } = await admin.from("daily_quests").select("id")
    .eq("user_id", user.id).eq("quest_date", today).neq("source", "PENALTY_TRIAL").limit(1);
  if (existing && existing.length > 0) {
    return Response.json({ status: "EXISTS", intensity, date: today });
  }

  // ── FULL CONTEXT §4.2 ─────────────────────────────────────────────────────
  const { data: p } = await admin.from("users")
    .select("username,display_name,level,xp,streak_days,age,height_cm,weight_kg,gender,activity_level,athletic_experience,sport,sport_experience_level,training_location")
    .eq("id", user.id).maybeSingle();
  if (!p) return Response.json({ error: "no_profile" }, { status: 422 });

  const { data: mine } = await admin.from("user_courses").select("course_id")
    .eq("user_id", user.id).eq("status", "ACTIVE");
  let courses: { title: string; type: string; difficulty: string | null; equipment: string | null }[] = [];
  if (mine && mine.length > 0) {
    const { data: cs } = await admin.from("courses").select("title,type,difficulty,equipment")
      .in("id", mine.map((m: { course_id: string }) => m.course_id));
    courses = (cs ?? []).filter((c: { type: string }) => ["MUSCLE", "SPECIAL"].includes(String(c.type)));
  }

  const yday = new Date(Date.now() - 86400000).toISOString().slice(0, 10);
  const { data: yq } = await admin.from("daily_quests").select("completed")
    .eq("user_id", user.id).eq("quest_date", yday).neq("source", "PENALTY_TRIAL");
  const doneRate = yq && yq.length > 0 ? Math.round(100 * yq.filter((r: { completed: boolean }) => r.completed).length / yq.length) : null;

  const w = Number(p.weight_kg ?? 65), h = Number(p.height_cm ?? 170);
  const age = clamp(Number(p.age ?? 18), 10, 100);
  const tier = tierFor(Number(p.level ?? 1));
  const bmr = bmrFor(p.gender ?? null, w, h, age);

  const floor = async (note: string) => {
    await userClient.rpc("ensure_daily_quests"); // template floor, periodization-scaled (039)
    const { data: n } = await admin.from("daily_quests").select("id")
      .eq("user_id", user.id).eq("quest_date", today).limit(10);
    return Response.json({ status: "FLOOR", intensity, date: today, issued: n?.length ?? 0, note, ai_generated: false });
  };

  const groqKey = Deno.env.get("GROQ_API_KEY") ?? "";
  if (!groqKey) return await floor("no_groq_key");

  const courseLine = courses.length
    ? `Active training: ${courses.map((c) => `${c.title} [${c.type}${c.difficulty ? " · " + c.difficulty : ""}]`).join("; ")}.`
    : "No active training courses.";
  const sportLine = p.sport
    ? `Athlete path: ${p.sport}, experience ${p.sport_experience_level ?? "BEGINNER"}, practice environment ${p.training_location ?? "BOTH"}. Include sport-specific drills executable in that exact environment (HOME = zero equipment, FIELD = ground drills).`
    : "No sport path declared.";
  const restLaw = intensity === "REST"
    ? "TODAY IS REST DAY: quests are stretching / mobility / hydration / breathing blocks only. No strength volume, no sprints."
    : "";

  const userPrompt = `You are THE SYSTEM issuing TODAY QUEST directives for a hunter.
Profile: ${p.display_name ?? p.username} | rank ${tier} | level ${p.level} | streak ${p.streak_days ?? 0}d | yesterday completion ${doneRate === null ? "no history" : doneRate + "%"}.
Biometrics: ${w}kg | ${h}cm | ${age}yo | sex ${p.gender ?? "M"} | BMR ${bmr} kcal | activity ${p.activity_level ?? "STEADY"}.
${courseLine} ${sportLine}
MANDATORY PERIODIZATION LAW — today's intensity is ${intensity} (Mon LIGHT, Tue HEAVY, Wed SUPER LIGHT, Thu LIGHT, Fri HEAVY, Sat MODERATE + sport drills, Sun REST). ${restLaw}
Difficulty must scale to the rank and yesterday's completion rate (low completion ⇒ gentler targets, never zero).
CIRCADIAN SCIENCE LAW: morning mobility/sun-exposure work sits at 06:00–07:30; the heaviest strength/speed/field block sits at 16:30–18:30 (peak body temperature window); mental/skill polish sits at 20:00–21:00. Assign each quest "window" accordingly ("HH:MM", 24h).
Output ONLY valid JSON:
{"quests":[{"title":string,"exercise_kind":"PUSHUP|SQUAT|SITUP|LUNGE|PLANK|JACKS|BURPEE|RUN|WALK|SPRINT|SKILL|CORE|CIRCUIT|STRENGTH|PLYO|STRETCH|REST","target_value":number,"difficulty":"EASY|MEDIUM|HARD","window":"HH:MM"} x3-6 ordered],"coach_note":string}
Cold, militaristic system-window register. Household-executable, zero equipment unless the sport/environment line allows more. No medical claims, no fantasy violence, titles under 64 chars.`;

  let parsed: { quests?: unknown[]; coach_note?: string } | null = null;
  try {
    const res = await fetch(GROQ_URL, {
      method: "POST",
      headers: { Authorization: `Bearer ${groqKey}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        model: MODEL, temperature: 0.7, max_tokens: 1200,
        response_format: { type: "json_object" },
        messages: [
          { role: "system", content: "You are THE SYSTEM quest issuer. Emit parseable JSON only." },
          { role: "user", content: userPrompt },
        ],
      }),
    });
    if (res.ok) parsed = JSON.parse(String((await res.json())?.choices?.[0]?.message?.content ?? ""));
  } catch { /* floor stands below */ }

  const raw = Array.isArray(parsed?.quests) ? parsed!.quests!.slice(0, 6) : [];
  if (raw.length < 3) return await floor("ai_output_rejected");

  // ── server-side validation + clamps (the model advises, the server decides) ──
  const xpMult = XP_MULT[intensity] ?? 1;
  const rows = [];
  for (const [i, qq] of raw.entries()) {
    const o = qq as Record<string, unknown>;
    const kind = String(o.exercise_kind ?? "").toUpperCase();
    const kindSafe = KINDS.has(kind) ? kind : "SKILL";
    const diff = ["EASY", "MEDIUM", "HARD"].includes(String(o.difficulty).toUpperCase())
      ? String(o.difficulty).toUpperCase() : "MEDIUM";
    const unit = unitFor(kindSafe);
    const bounds = unit === "REPS" ? [5, 300] : unit === "METERS" ? [200, 10000] : [60, 3600];
    const target = clamp(Math.round(Number(o.target_value) || bounds[0]), bounds[0], bounds[1]);
    const window = WINDOW_RX.test(String(o.window ?? "")) ? String(o.window)
      : (i === 0 ? "06:30" : i === raw.length - 1 ? "20:30" : "17:00");
    rows.push({
      user_id: user.id,
      quest_date: today,
      seq: i + 1,
      title: `${intensity} · ${String(o.title ?? "DIRECTIVE").toUpperCase().slice(0, 64)}`,
      target_value: target,
      xp_reward: clamp(Math.round((XP_BASE[diff] ?? 35) * xpMult), 10, 90),
      exercise_kind: kindSafe,
      target_unit: unit,
      est_duration_sec: clamp(Math.round(Number(o.est_duration_sec) || 600), 120, 3600),
      rest_sec: 90,
      difficulty: diff,
      verification: verificationFor(kindSafe),
      status: i === 0 ? "READY" : "LOCKED",
      source: "AI_BRAIN",
      ai_generated: true,
      scheduled_window: window,
    });
  }

  const { error: insErr } = await admin.from("daily_quests").insert(rows);
  if (insErr) return await floor("insert_failed:" + insErr.message.slice(0, 80));

  return Response.json({
    status: "ISSUED", intensity, date: today, issued: rows.length,
    ai_generated: true, model: MODEL, coach_note: String(parsed?.coach_note ?? "").slice(0, 220),
  });
});
