// ═══════════════════════════════════════════════════════════════════════════
// THE SYSTEM — Edge Function: GENERATE SYSTEM PLAN (vC50 §2, schema-b)
// GroqCloud-powered Solo Leveling brain: personalized daily quest + nutrition
// + RPG directives from the hunter's REAL biometrics.
//
//  • Auth: hunter JWT REQUIRED (verify_jwt=true). Plans key to auth.uid.
//  • BMR / TDEE computed SERVER-SIDE (Mifflin-St Jeor):
//      M: 10w + 6.25h − 5a + 5     F: 10w + 6.25h − 5a − 161
//    The MODEL'S math is advisory color; the returned bmr/tdee/calories are
//    always OUR deterministic numbers (server is the economy/truth owner).
//  • Rank multiplier from hunter level (F→1.30 … SS→1.75), +0.05 when
//    activity_level='HIGH'. Thresholds mirror client SystemMath exactly.
//  • GroqCloud (GROQ_MODEL, live-verified qwen/qwen3.8-27b) drafts, as STRICT
//    JSON: bmr/tdee advisory + daily_quest{title,pushup_target,squat_target,
//    cardio_target_km,penalty_warning} + nutrition_target{calories,protein_g,
//    carbs_g,fats_g} + system_directive + daily_routine[] (kept: the Routine
//    panel renders it — never break working UI).
//  • EVERYTHING persists to user_system_plans: normalized columns (plan is
//    UI-hot) + plan_data jsonb blob (the full directive payload) — RLS: hunter
//    reads own, service writes only.
//  • GROQ_API_KEY absent or Groq down ⇒ deterministic server floor stamped
//    ai_generated=false. Never a crash, never a fake claim, never blank UI.
// ═══════════════════════════════════════════════════════════════════════════
import { createClient } from "jsr:@supabase/supabase-js@2";

const MODEL = Deno.env.get("GROQ_MODEL") ?? "qwen/qwen3.8-27b";
const GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
const FRESH_MS = 20 * 3600 * 1000;

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

interface Directive { time: string; directive: string; detail: string }
interface DailyQuest { title: string; pushup_target: number; squat_target: number; cardio_target_km: number; penalty_warning: string }

function fallbackQuest(tier: string): DailyQuest {
  const hard = ["S", "SS", "A"].includes(tier);
  return {
    title: `DAILY QUEST — ${hard ? "APEX" : "STANDARD"} PROTOCOL`,
    pushup_target: hard ? 100 : 50, squat_target: hard ? 100 : 50, cardio_target_km: hard ? 5 : 2,
    penalty_warning: "Unfinished quests arm the XP-decay protocol. The System does not negotiate.",
  };
}
function fallbackRoutine(tier: string, cal: number): Directive[] {
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
    .select("weight_kg,height_cm,age,gender,level,activity_level,username,display_name")
    .eq("id", user.id).maybeSingle();
  if (!p?.weight_kg || !p?.height_cm || !p?.age) {
    return Response.json({ error: "biometrics_incomplete", detail: "Finish IDENTIFY YOUR VESSEL first." }, { status: 422 });
  }

  const w = Number(p.weight_kg), h = Number(p.height_cm), age = Number(p.age);
  const tier = tierFor(Number(p.level ?? 1));
  let mult = MULT[tier] ?? 1.3;
  if (String(p.activity_level ?? "").toUpperCase() === "HIGH") mult += 0.05;
  const bmr = bmrFor(p.gender ?? null, w, h, Math.min(Math.max(age, 10), 100));  // OUR math is truth
  const tdee = Math.round(bmr * mult);
  const calTarget = tdee;
  const macros = macrosFor(calTarget, w);

  let quest: DailyQuest = fallbackQuest(tier);
  let directive = "Consistency is the only cheat code the System respects.";
  let routine: Directive[] = fallbackRoutine(tier, calTarget);
  let aiGenerated = false;
  let modelUsed: string | null = null;

  const groqKey = Deno.env.get("GROQ_API_KEY") ?? "";
  if (groqKey) {
    const userPrompt = `Hunter ${p.display_name ?? p.username} | rank ${tier} | level ${p.level} | ${w}kg | ${h}cm | ${age}yo | sex ${p.gender ?? "M"} | TDEE ${tdee} kcal.
Act as the authoritative Solo Leveling System. Output ONLY valid JSON:
{"bmr":number,"tdee":number,"daily_quest":{"title":string,"pushup_target":number,"squat_target":number,"cardio_target_km":number,"penalty_warning":string},"nutrition_target":{"calories":number,"protein_g":number,"carbs_g":number,"fats_g":number},"system_directive":string,"daily_routine":[{"time":"HH:MM","directive":string,"detail":string} x7-9 ascending]}.
Targets scale to the rank above. Register: cold, militaristic system-window. No medical claims, no supplements, no fantasy violence. Household-executable, zero equipment.`;
    try {
      const res = await fetch(GROQ_URL, {
        method: "POST",
        headers: { Authorization: `Bearer ${groqKey}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          model: MODEL, temperature: 0.7, max_tokens: 1500,
          response_format: { type: "json_object" },
          messages: [
            { role: "system", content: "You are THE SYSTEM — a deterministic RPG progression engine. Emit parseable JSON only." },
            { role: "user", content: userPrompt },
          ],
        }),
      });
      if (res.ok) {
        const j = await res.json();
        const parsed = JSON.parse(String(j?.choices?.[0]?.message?.content ?? ""));
        const q = parsed?.daily_quest;
        if (q && typeof q.title === "string" && Number(q.pushup_target) > 0) {
          quest = {
            title: String(q.title).toUpperCase().slice(0, 60),
            pushup_target: Math.min(Math.max(Math.round(Number(q.pushup_target)), 10), 300),
            squat_target: Math.min(Math.max(Math.round(Number(q.squat_target) || 0), 10), 300),
            cardio_target_km: Math.min(Math.max(Number(q.cardio_target_km) || 0, 0), 21),
            penalty_warning: String(q.penalty_warning ?? quest.penalty_warning).slice(0, 160),
          };
          if (typeof parsed.system_directive === "string") directive = parsed.system_directive.slice(0, 220);
          if (Array.isArray(parsed.daily_routine) && parsed.daily_routine.length >= 5) {
            routine = parsed.daily_routine.slice(0, 12).map((d: Directive) => ({
              time: String(d?.time ?? "--:--").slice(0, 5),
              directive: String(d?.directive ?? "").toUpperCase().slice(0, 48),
              detail: String(d?.detail ?? "").slice(0, 220),
            })).filter((d: Directive) => d.directive.length > 0);
            if (routine.length < 5) routine = fallbackRoutine(tier, calTarget);
          }
          aiGenerated = true; modelUsed = MODEL;
        }
      }
    } catch { /* deterministic floor stands — never a crash */ }
  }

  const planData = {
    headline: quest.title,
    daily_quest: quest,
    nutrition_target: { calories: calTarget, protein_g: macros.protein_g, carbs_g: macros.carbs_g, fats_g: macros.fats_g },
    system_directive: directive,
    daily_routine: routine,
  };
  const row = {
    user_id: user.id, bmr, tdee, calories_target: calTarget,
    protein_g: macros.protein_g, carbs_g: macros.carbs_g, fat_g: macros.fats_g,
    daily_routine: routine, plan_data: planData,
    rank_tier: tier, multiplier: mult,
    ai_generated: aiGenerated, model: modelUsed, updated_at: new Date().toISOString(),
  };
  const { error: upErr } = await admin.from("user_system_plans").upsert(row, { onConflict: "user_id" });
  if (upErr) return Response.json({ error: upErr.message.slice(0, 140) }, { status: 500 });

  return Response.json({ plan: { ...row, cached: false } });
});
