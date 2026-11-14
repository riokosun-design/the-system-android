// ═══════════════════════════════════════════════════════════════════════════
// THE SYSTEM — Edge Function: GENERATE SYSTEM PLAN (vC55 §A6+§B7)
//
//  §B7 — MULTI-PROVIDER AI ROUTER. Seven key slots, three vendors:
//     GROQ       GROQ_API_KEY / GROQ_API_KEY_2 / GROQ_API_KEY_3
//                → https://api.groq.com/openai/v1/chat/completions
//                model = GROQ_MODEL (default qwen/qwen3.8-27b)
//     TOKENHARBOR TOKENHARBOR_API_KEY_1 → TOKENHARBOR_MIMO_MODEL   (MiMo FREE slug)
//                TOKENHARBOR_API_KEY_2 → TOKENHARBOR_DEEPSEEK_MODEL (DeepSeek FREE slug)
//                → https://tokenharbor.ai/v1/chat/completions
//                FREE-tier models ONLY: a slot stays DEAD unless BOTH its key
//                AND its model env are set — we never guess paid slugs.
//     GOOGLE     GOOGLE_AI_STUDIO_KEY_1 / GOOGLE_AI_STUDIO_KEY_2
//                → https://generativelanguage.googleapis.com/v1beta/models/{m}:generateContent
//                model = GOOGLE_AI_MODEL (default gemini-2.5-flash)
//  Round-robin load balance (module counter rotates the starting slot per
//  call) + INSTANT failover: unset key/model, non-200 (429/5xx/…), timeout
//  (12s), or schema-broken JSON ⇒ next slot ⇒ … ⇒ deterministic fallback
//  plan stamped ai_generated=false. Winning slot is logged + persisted in
//  user_system_plans.ai_provider_used (mig 042).
//
//  §A6 — SMOOTH MONOTONIC QUEST SCALING. The server computes deterministic
//  anchors from hunter level + streak: sub-linear √ level growth, streak
//  bonus capped at 45d, L1–10 comfortable, human-realistic ceilings under
//  L80, genuinely impossible workloads ONLY at L80+. The MODEL personalizes
//  color (titles, directives, routine blocks) but its quest numbers are
//  CLAMPED into a ±10% band around the anchor — the band rides a monotonic
//  anchor, so no day-to-day yo-yo easy/hard swings. Personalization inputs:
//  primary muscle course + special sub-courses (user_courses ⨝ courses),
//  BMR/TDEE + height/weight/age/gender, sport + practice environment,
//  streak/consistency days, level 1–100.
//
//  • Auth: hunter JWT REQUIRED. BMR/TDEE computed server-side (Mifflin-St
//    Jeor) — server owns every progression number.
//  • EVERYTHING persists to user_system_plans (normalized + plan_data blob).
// ═══════════════════════════════════════════════════════════════════════════
import { createClient } from "jsr:@supabase/supabase-js@2";

const FRESH_MS = 20 * 3600 * 1000;
const GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
const TH_URL = "https://tokenharbor.ai/v1/chat/completions";
const GOOGLE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

function tierFor(level: number): string {
  if (level >= 90) return "SS"; if (level >= 70) return "S";
  if (level >= 50) return "A"; if (level >= 35) return "B";
  if (level >= 22) return "C"; if (level >= 12) return "D";
  if (level >= 5) return "E"; return "F";
}
const MULT: Record<string, number> = { F: 1.30, E: 1.35, D: 1.45, C: 1.50, B: 1.55, A: 1.60, S: 1.65, SS: 1.75 };

function bmrFor(sex: string | null, w: number, h: number, age: number): number {
  const base = 10 * w + 6.25 * h - 5 * age;
  return Math.round(sex === "F" ? base - 161 : base + 5);
}
function macrosFor(cal: number, w: number) {
  const protein = Math.round(2.0 * w);
  const fat = Math.round(0.9 * w);
  const carbs = Math.max(0, Math.round((cal - protein * 4 - fat * 9) / 4));
  return { protein_g: protein, carbs_g: carbs, fats_g: fat };
}

// ── §A6 SMOOTH MONOTONIC ANCHORS ───────────────────────────────────────────
// Monotonic in BOTH level and streak by construction (√ / linear terms only);
// zero dependence on model mood → the floor can never yo-yo.
interface Anchors { pushup: number; squat: number; km: number }
function questAnchors(level: number, streak: number): Anchors {
  const lvl = Math.min(Math.max(Math.round(level), 1), 100);
  const s = Math.min(Math.max(Math.round(streak), 0), 365);
  const beast = Math.max(0, lvl - 79);              // impossible tier ONLY at 80+
  const human = lvl < 80;
  const round5 = (v: number) => Math.round(v / 5) * 5;
  const push = 15 + 13 * Math.sqrt(lvl) + Math.min(s, 45) * 0.5 + beast * 15;
  const squat = 15 + 14 * Math.sqrt(lvl) + Math.min(s, 45) * 0.55 + beast * 16;
  const km = 1.5 + lvl * 0.07 + Math.min(s, 45) * 0.04 + beast * 0.9;
  return {
    pushup: round5(Math.min(push, human ? 170 : 520)),
    squat: round5(Math.min(squat, human ? 185 : 560)),
    km: Math.round(Math.min(km, human ? 12 : 42) * 2) / 2,
  };
}
/** ±10% band around the monotonic anchor + absolute safety ceilings. */
function bandClamp(v: number, anchor: number, lo: number, hi: number): number {
  const n = Number(v);
  if (!isFinite(n) || n <= 0) return anchor;
  return Math.min(Math.max(Math.round(n), Math.max(lo, Math.round(anchor * 0.90))), Math.min(hi, Math.round(anchor * 1.10)));
}

interface Directive { time: string; directive: string; detail: string }
interface DailyQuest { title: string; pushup_target: number; squat_target: number; cardio_target_km: number; penalty_warning: string }

function fallbackQuest(tier: string, a: Anchors): DailyQuest {
  const hard = ["S", "SS", "A"].includes(tier);
  return {
    title: `DAILY QUEST — ${hard ? "APEX" : "STANDARD"} PROTOCOL`,
    pushup_target: a.pushup, squat_target: a.squat, cardio_target_km: a.km,
    penalty_warning: "Unfinished quests arm the XP-decay protocol. The System does not negotiate.",
  };
}
function fallbackRoutine(cal: number): Directive[] {
  return [
    { time: "05:30", directive: "AWAKENING PROTOCOL", detail: "No screen before feet touch the floor. Water first." },
    { time: "06:00", directive: "DAILY QUEST EXECUTION", detail: "Reps the camera verifies count. The rest are lies." },
    { time: "07:00", directive: "FUEL GATE 1", detail: `Protein-anchored meal inside the ${cal} kcal budget.` },
    { time: "13:00", directive: "SHADOW WALK", detail: "20 minutes minimum. Steps feed recovery." },
    { time: "18:00", directive: "DISCIPLINE BLOCK", detail: "One deep-work session, phone face-down." },
    { time: "21:30", directive: "FUEL GATE 2 + LOG", detail: "Final meal logged. Tomorrow acknowledged." },
    { time: "22:30", directive: "SYSTEM SHUTDOWN", detail: "Sleep is a stat. Guard it like one." },
  ];
}

// ── §B7 PROVIDER ROUTER ────────────────────────────────────────────────────
interface Slot {
  label: string; kind: "groq" | "tokenharbor" | "google";
  key: string; model: string;
}

function liveSlots(): Slot[] {
  const env = (k: string) => Deno.env.get(k) ?? "";
  const groqModel = env("GROQ_MODEL") || "qwen/qwen3.8-27b";
  const googleModel = env("GOOGLE_AI_MODEL") || "gemini-2.5-flash";
  const all: Slot[] = [
    { label: "groq-1", kind: "groq", key: env("GROQ_API_KEY"), model: groqModel },
    { label: "groq-2", kind: "groq", key: env("GROQ_API_KEY_2"), model: groqModel },
    { label: "groq-3", kind: "groq", key: env("GROQ_API_KEY_3"), model: groqModel },
    // FREE-tier only: a TokenHarbor slot lives ONLY when key AND model are both set
    { label: "tokenharbor-mimo-1", kind: "tokenharbor", key: env("TOKENHARBOR_API_KEY_1"), model: env("TOKENHARBOR_MIMO_MODEL") },
    { label: "tokenharbor-deepseek-2", kind: "tokenharbor", key: env("TOKENHARBOR_API_KEY_2"), model: env("TOKENHARBOR_DEEPSEEK_MODEL") },
    { label: "google-1", kind: "google", key: env("GOOGLE_AI_STUDIO_KEY_1"), model: googleModel },
    { label: "google-2", kind: "google", key: env("GOOGLE_AI_STUDIO_KEY_2"), model: googleModel },
  ];
  return all.filter((s) => s.key.length > 0 && s.model.length > 0);
}

let rrCursor = 0;   // module counter: round-robin start rotates per call

const SYS_PROMPT = "You are THE SYSTEM — a deterministic RPG progression engine. Emit parseable JSON only.";

async function callSlot(slot: Slot, userPrompt: string): Promise<string> {
  if (slot.kind === "google") {
    const res = await fetch(`${GOOGLE_URL}${slot.model}:generateContent`, {
      method: "POST",
      headers: { "x-goog-api-key": slot.key, "Content-Type": "application/json" },
      signal: AbortSignal.timeout(12_000),
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: SYS_PROMPT }] },
        contents: [{ role: "user", parts: [{ text: userPrompt }] }],
        generationConfig: { temperature: 0.7, maxOutputTokens: 1500, responseMimeType: "application/json" },
      }),
    });
    if (!res.ok) throw new Error(`${slot.label} http ${res.status}`);
    const j = await res.json();
    const text = String(j?.candidates?.[0]?.content?.parts?.[0]?.text ?? "");
    if (!text) throw new Error(`${slot.label} empty`);
    return text;
  }
  // OpenAI-compatible rail (GroqCloud + TokenHarbor share the shape)
  const res = await fetch(slot.kind === "groq" ? GROQ_URL : TH_URL, {
    method: "POST",
    headers: { Authorization: `Bearer ${slot.key}`, "Content-Type": "application/json" },
    signal: AbortSignal.timeout(12_000),
    body: JSON.stringify({
      model: slot.model, temperature: 0.7, max_tokens: 1500,
      response_format: { type: "json_object" },
      messages: [
        { role: "system", content: SYS_PROMPT },
        { role: "user", content: userPrompt },
      ],
    }),
  });
  if (!res.ok) throw new Error(`${slot.label} http ${res.status}`);
  const j = await res.json();
  const text = String(j?.choices?.[0]?.message?.content ?? "");
  if (!text) throw new Error(`${slot.label} empty`);
  return text;
}

/** Strict schema rail: one bad field = broken response = failover. */
function parsePlan(text: string): Record<string, unknown> | null {
  try {
    const parsed = JSON.parse(text);
    const q = parsed?.daily_quest;
    if (!q || typeof q.title !== "string" || !(Number(q.pushup_target) > 0)) return null;
    if (typeof parsed?.system_directive !== "string") return null;
    if (!Array.isArray(parsed?.daily_routine)) return null;
    return parsed;
  } catch { return null; }
}

interface RouteWin { parsed: Record<string, unknown>; provider: string; model: string }

async function routeAI(userPrompt: string): Promise<RouteWin | null> {
  const slots = liveSlots();
  if (slots.length === 0) return null;
  for (let i = 0; i < slots.length; i++) {
    const slot = slots[(rrCursor + i) % slots.length];
    try {
      const text = await callSlot(slot, userPrompt);
      const parsed = parsePlan(text);
      if (parsed) {
        rrCursor = (rrCursor + i + 1) % slots.length;   // next call starts AFTER the winner
        console.log(`ai_provider_used=${slot.label}:${slot.model}`);
        return { parsed, provider: slot.label, model: slot.model };
      }
      console.warn(`slot ${slot.label} schema-broken — failover`);
    } catch (e) {
      console.warn(`slot ${slot.label} down — failover (${String(e).slice(0, 90)})`);
    }
  }
  return null;
}

// ── MAIN ───────────────────────────────────────────────────────────────────
Deno.serve(async (req: Request) => {
  const authHeader = req.headers.get("Authorization") ?? "";
  if (!authHeader.startsWith("Bearer ")) return Response.json({ error: "auth_required" }, { status: 401 });

  const anonClient = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: authHeader } },
  });
  const { data: { user }, error: authErr } = await anonClient.auth.getUser();
  if (authErr || !user) return Response.json({ error: "auth_invalid" }, { status: 401 });

  let force = false;
  try { force = Boolean((await req.json())?.force_refresh); } catch { /* empty body = fine */ }

  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

  const { data: existing } = await admin.from("user_system_plans").select("*").eq("user_id", user.id).maybeSingle();
  if (!force && existing && Date.now() - new Date(existing.updated_at).getTime() < FRESH_MS) {
    return Response.json({ plan: { ...existing, cached: true } });
  }

  const { data: p } = await admin.from("users")
    .select("weight_kg,height_cm,age,gender,level,activity_level,username,display_name,sport,sport_experience_level,training_location,streak_days")
    .eq("id", user.id).maybeSingle();
  if (!p?.weight_kg || !p?.height_cm || !p?.age) {
    return Response.json({ error: "biometrics_incomplete", detail: "Finish IDENTIFY YOUR VESSEL first." }, { status: 422 });
  }

  // §A6 — course rails: primary muscle course + special sub-courses
  const { data: enrolls } = await admin.from("user_courses")
    .select("progress_percent,status,courses(title,type,difficulty)")
    .eq("user_id", user.id);
  const muscle = (enrolls ?? [])
    .filter((e) => (e as { courses?: { type?: string } }).courses?.type === "MUSCLE")
    .sort((a, b) => Number((b as { progress_percent?: number }).progress_percent ?? 0) - Number((a as { progress_percent?: number }).progress_percent ?? 0))[0] as
    { progress_percent?: number; courses?: { title?: string; difficulty?: string } } | undefined;
  const specials = (enrolls ?? [])
    .filter((e) => (e as { courses?: { type?: string } }).courses?.type === "SPECIAL")
    .map((e) => String((e as { courses?: { title?: string } }).courses?.title ?? ""))
    .filter((t) => t.length > 0);

  const w = Number(p.weight_kg), h = Number(p.height_cm), age = Number(p.age);
  const level = Number(p.level ?? 1);
  const streak = Number(p.streak_days ?? 0);
  const tier = tierFor(level);
  let mult = MULT[tier] ?? 1.3;
  if (String(p.activity_level ?? "").toUpperCase() === "HIGH") mult += 0.05;
  const bmr = bmrFor(p.gender ?? null, w, h, Math.min(Math.max(age, 10), 100));  // OUR math is truth
  const tdee = Math.round(bmr * mult);
  const calTarget = tdee;
  const macros = macrosFor(calTarget, w);

  // §A6 — deterministic monotonic anchor: the quest floor the AI may flavor
  // but never override. Below L80 the ceilings are human-realistic; only
  // L80+ unlocks impossible-tier workloads.
  const anchors = questAnchors(level, streak);

  let quest: DailyQuest = fallbackQuest(tier, anchors);
  let directive = "Consistency is the only cheat code the System respects.";
  let routine: Directive[] = fallbackRoutine(calTarget);
  let aiGenerated = false;
  let modelUsed: string | null = null;
  let providerUsed: string | null = null;

  // vC52 sport context + vC55 course context ride into the brain honestly
  const sportLine = p.sport
    ? ` | athlete: ${p.sport} at ${p.sport_experience_level ?? "BEGINNER"} level, trains ${p.training_location === "FIELD" ? "on FIELD only" : p.training_location === "HOME" ? "at HOME only (zero equipment variants)" : "BOTH home & field"}`
    : ` | no sport path`;
  const courseLine = muscle?.courses?.title
    ? ` | primary course: ${muscle.courses.title} (${Math.round(Number(muscle.progress_percent ?? 0))}% cleared${muscle.courses.difficulty ? `, ${muscle.courses.difficulty}` : ""})`
    : ` | no course path`;
  const specialLine = specials.length > 0 ? ` | special sub-courses: ${specials.join(", ").slice(0, 120)}` : "";

  const userPrompt = `Hunter ${p.display_name ?? p.username} | rank ${tier} | level ${level}/100 | ${w}kg | ${h}cm | ${age}yo | sex ${p.gender ?? "M"} | TDEE ${tdee} kcal${sportLine}${courseLine}${specialLine} | streak ${streak}d.
Act as the authoritative Solo Leveling System. Output ONLY valid JSON:
{"bmr":number,"tdee":number,"daily_quest":{"title":string,"pushup_target":number,"squat_target":number,"cardio_target_km":number,"penalty_warning":string},"nutrition_target":{"calories":number,"protein_g":number,"carbs_g":number,"fats_g":number},"system_directive":string,"daily_routine":[{"time":"HH:MM","directive":string,"detail":string} x7-9 ascending]}.
Quest target anchors (STAY WITHIN ±10% — monotonic scaling law): pushups ${anchors.pushup}, squats ${anchors.squat}, cardio ${anchors.km}km. Difficulty must match level ${level}: levels 1–10 comfortable onboarding, mid-levels realistic progressive overload, workloads above human limits ONLY beyond level 80.
If a primary course exists, exactly one daily_routine block MUST drill its craft; if special sub-courses exist, one block serves the first special. If an athlete line exists, one block MUST be a sport-specific drill executable in the stated environment. Register: cold, militaristic system-window. No medical claims, no supplements, no fantasy violence. Household-executable, zero equipment.`;

  const win = await routeAI(userPrompt);
  if (win) {
    const q = win.parsed.daily_quest as Record<string, unknown>;
    // §A6 band law: model color rides the monotonic anchor — model numbers
    // are clamped INSIDE ±10%, and nothing below L80 crosses human ceilings.
    quest = {
      title: String(q.title).toUpperCase().slice(0, 60),
      pushup_target: bandClamp(Number(q.pushup_target), anchors.pushup, 10, level < 80 ? 185 : 520),
      squat_target: bandClamp(Number(q.squat_target), anchors.squat, 10, level < 80 ? 200 : 560),
      cardio_target_km: Math.min(Math.max(Number(q.cardio_target_km) > 0
        ? Math.min(Math.max(Number(q.cardio_target_km), Math.max(1, anchors.km * 0.9)), Math.min(level < 80 ? 13 : 42, anchors.km * 1.1))
        : anchors.km, 1), 42),
      penalty_warning: String(q.penalty_warning ?? quest.penalty_warning).slice(0, 160),
    };
    directive = String(win.parsed.system_directive).slice(0, 220);
    const dr = win.parsed.daily_routine as Directive[];
    if (dr.length >= 5) {
      routine = dr.slice(0, 12).map((d: Directive) => ({
        time: String(d?.time ?? "--:--").slice(0, 5),
        directive: String(d?.directive ?? "").toUpperCase().slice(0, 48),
        detail: String(d?.detail ?? "").slice(0, 220),
      })).filter((d: Directive) => d.directive.length > 0);
      if (routine.length < 5) routine = fallbackRoutine(calTarget);
    }
    aiGenerated = true;
    modelUsed = win.model;
    providerUsed = `${win.provider}:${win.model}`;
  }

  const planData = {
    headline: quest.title,
    daily_quest: quest,
    nutrition_target: { calories: calTarget, protein_g: macros.protein_g, carbs_g: macros.carbs_g, fats_g: macros.fats_g },
    system_directive: directive,
    daily_routine: routine,
    ai_provider_used: providerUsed,
    anchors,   // honest audit rail: the deterministic floor the quest rode
  };
  const row = {
    user_id: user.id, bmr, tdee, calories_target: calTarget,
    protein_g: macros.protein_g, carbs_g: macros.carbs_g, fat_g: macros.fats_g,
    daily_routine: routine, plan_data: planData,
    rank_tier: tier, multiplier: mult,
    ai_generated: aiGenerated, model: modelUsed, ai_provider_used: providerUsed,
    updated_at: new Date().toISOString(),
  };
  const { error: upErr } = await admin.from("user_system_plans").upsert(row, { onConflict: "user_id" });
  if (upErr) return Response.json({ error: upErr.message.slice(0, 140) }, { status: 500 });

  return Response.json({ plan: { ...row, cached: false } });
});
