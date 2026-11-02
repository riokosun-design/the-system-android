// ═══════════════════════════════════════════════════════════════════════════
// THE SYSTEM — Edge Function: GENERATE SYSTEM PLAN (vC49 §3)
// Server-side Sol leveling RPG daily-plan generator, GroqCloud-powered.
//
//  • Auth: hunter JWT REQUIRED (verify_jwt=true). The plan is keyed to the
//    caller's auth.uid — no user can forge or read another hunter's plan.
//  • BMR / TDEE are computed SERVER-SIDE (Mifflin-St Jeor per directive):
//      M: 10w + 6.25h − 5a + 5     F: 10w + 6.25h − 5a − 161
//    (users.gender is nullable — when unset the M formula applies; deviation
//     acknowledged: onboarding never captured sex — captured for the future.)
//  • Activity multiplier is rank-derived (F→1.30 … SS→1.75) with a +0.05
//    nudge when activity_level='HIGH'. Deterministic, auditable, in SQL-match
//    tier thresholds (F1/E5/D12/C22/B35/A50/S70/SS90).
//  • GroqCloud (model = GROQ_MODEL, default qwen/qwen3-32b) drafts the RPG
//    directives as STRICT JSON. If GROQ_API_KEY is absent (dependency flagged
//    PENDING) or Groq call fails, a deterministic server-side template plan
//    ships instead, honestly stamped ai_generated=false. Fake AI claims are
//    never emitted.
//  • Result upserts user_system_plans (RLS: hunter reads own; only this
//    function's service role writes). 20h freshness cache unless forced.
// ═══════════════════════════════════════════════════════════════════════════
import { createClient } from "jsr:@supabase/supabase-js@2";

const MODEL = Deno.env.get("GROQ_MODEL") ?? "qwen/qwen3-32b";
const GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
const FRESH_MS = 20 * 3600 * 1000;

function tierFor(level: number): string {
  if (level >= 90) return "SS";
  if (level >= 70) return "S";
  if (level >= 50) return "A";
  if (level >= 35) return "B";
  if (level >= 22) return "C";
  if (level >= 12) return "D";
  if (level >= 5) return "E";
  return "F";
}
const MULT: Record<string, number> = { F: 1.30, E: 1.35, D: 1.45, C: 1.50, B: 1.55, A: 1.60, S: 1.65, SS: 1.75 };

function bmrFor(sex: string | null, w: number, h: number, age: number): number {
  const base = 10 * w + 6.25 * h - 5 * age;
  return Math.round(sex === "F" ? base - 161 : base + 5); // Mifflin-St Jeor, spec §3
}

function macrosFor(cal: number, w: number) {
  const protein = Math.round(2.0 * w);                       // g/kg — hunter doctrine
  const fat = Math.round(0.9 * w);
  const carbs = Math.max(0, Math.round((cal - protein * 4 - fat * 9) / 4));
  return { protein_g: protein, carbs_g: carbs, fat_g: fat };
}

interface Directive { time: string; directive: string; detail: string }

function fallbackRoutine(tier: string, cal: number): Directive[] {
  const hard = ["S", "SS", "A"].includes(tier);
  return [
    { time: "05:30", directive: "AWAKENING PROTOCOL", detail: "No screen before feet touch the floor. Water first." },
    { time: "06:00", directive: `DAILY QUEST — ${hard ? "100" : "50"} PUSH-UPS / ${hard ? "100" : "50"} SQUATS`, detail: "Verified by the camera. Reps the System can SEE count; the rest are lies." },
    { time: "07:00", directive: "FUEL GATE 1", detail: `Protein-anchored meal inside the ${cal} kcal budget. Weigh it once — honesty compounds.` },
    { time: "13:00", directive: "SHADOW WALK", detail: "20 minutes minimum. Steps feed the recovery ledger." },
    { time: "18:00", directive: "DISCIPLINE BLOCK", detail: "One deep-work or study session, phone face-down. The System watches consistency, not promises." },
    { time: "21:30", directive: "FUEL GATE 2 + LOG", detail: "Final meal logged. Tomorrow's target acknowledged before sleep." },
    { time: "22:30", directive: "SYSTEM SHUTDOWN", detail: "Sleep is a stat. Guard it like one." },
  ];
}

Deno.serve(async (req: Request) => {
  // ── AUTH GATE — the hunter's own JWT or nothing ──────────────────────────
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

  // ── cache wall: fresh plan returns as-is unless force_refresh ────────────
  const { data: existing } = await admin.from("user_system_plans").select("*").eq("user_id", user.id).maybeSingle();
  if (!force && existing && Date.now() - new Date(existing.updated_at).getTime() < FRESH_MS) {
    return Response.json({ plan: { ...existing, cached: true } });
  }

  const { data: p } = await admin.from("users")
    .select("weight_kg,height_cm,age,gender,level,activity_level,username,display_name")
    .eq("id", user.id).maybeSingle();
  if (!p?.weight_kg || !p?.height_cm || !p?.age) {
    return Response.json({ error: "biometrics_incomplete", detail: "Finish IDENTIFY YOUR VESSEL first." }, { status: 422 });
  }

  const w = Number(p.weight_kg), h = Number(p.height_cm), age = Number(p.age);
  const tier = tierFor(Number(p.level ?? 1));
  let mult = MULT[tier] ?? 1.3;
  if (String(p.activity_level ?? "").toUpperCase() === "HIGH") mult += 0.05;
  const bmr = bmrFor(p.gender ?? null, w, h, Math.min(Math.max(age, 10), 100));
  const tdee = Math.round(bmr * mult);
  const calTarget = tdee; // recomposition baseline; directive: target = TDEE
  const macros = macrosFor(calTarget, w);

  // ── GroqCloud draft — strict JSON, RPG register, no medical claims ──────
  let routine: Directive[] = [];
  let aiGenerated = false;
  let modelUsed: string | null = null;
  const groqKey = Deno.env.get("GROQ_API_KEY") ?? "";
  if (groqKey) {
    const prompt = {
      role: "user",
      content: `Hunter ${p.display_name ?? p.username} | rank ${tier} | level ${p.level} | ${w}kg | ${h}cm | ${age}yo | TDEE ${tdee} kcal.
Emit STRICT JSON only: {"headline": string, "daily_routine": [{"time":"HH:MM","directive":string,"detail":string}...(7-10 entries ascending)],
"discipline_quests": [string...3]}.
Register: Solo Leveling system-window tone — cold, precise, militaristic. No medical claims, no supplement sales, no fantasy violence. Directives must be executable in a normal Indian household without equipment.`,
    };
    try {
      const res = await fetch(GROQ_URL, {
        method: "POST",
        headers: { Authorization: `Bearer ${groqKey}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          model: MODEL, temperature: 0.7, max_tokens: 1400,
          response_format: { type: "json_object" },
          messages: [
            { role: "system", content: "You are THE SYSTEM — a deterministic RPG progression engine. Reply with parseable JSON only." },
            prompt,
          ],
        }),
      });
      if (res.ok) {
        const j = await res.json();
        const parsed = JSON.parse(String(j?.choices?.[0]?.message?.content ?? ""));
        if (Array.isArray(parsed?.daily_routine) && parsed.daily_routine.length >= 5) {
          routine = parsed.daily_routine
            .filter((d: Directive) => d && typeof d.directive === "string")
            .slice(0, 12)
            .map((d: Directive) => ({
              time: String(d.time ?? "--:--").slice(0, 5),
              directive: String(d.directive).toUpperCase().slice(0, 48),
              detail: String(d.detail ?? "").slice(0, 220),
            }));
          aiGenerated = true; modelUsed = MODEL;
        }
      }
    } catch { /* fall through to the deterministic floor — never a crash */ }
  }
  if (!aiGenerated) routine = fallbackRoutine(tier, calTarget);

  const row = {
    user_id: user.id, bmr, tdee, calories_target: calTarget,
    protein_g: macros.protein_g, carbs_g: macros.carbs_g, fat_g: macros.fat_g,
    daily_routine: routine, rank_tier: tier, multiplier: mult,
    ai_generated: aiGenerated, model: modelUsed, updated_at: new Date().toISOString(),
  };
  const { error: upErr } = await admin.from("user_system_plans")
    .upsert(row, { onConflict: "user_id" });
  if (upErr) return Response.json({ error: upErr.message.slice(0, 140) }, { status: 500 });

  return Response.json({ plan: { ...row, cached: false } });
});
