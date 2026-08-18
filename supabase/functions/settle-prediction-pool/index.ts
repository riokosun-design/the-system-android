// THE SYSTEM — Edge Function: settle a prediction pool (solo AND clan battles).
// Body: { "poolId": "<uuid>", "winner": "A" | "B" }
// Payout = (Bet / WinningPool) × (TotalPool × 0.85), 15% platform cut kept.
// The heavy lifting is SQL-side (settle_prediction_pool) so the whole settlement
// is a single atomic transaction — double-invocation settles only once.
import { createClient } from "jsr:@supabase/supabase-js@2";

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return new Response("POST only", { status: 405 });

  let poolId = "";
  let winner = "";
  try {
    const body = await req.json();
    poolId = String(body.poolId ?? "");
    winner = String(body.winner ?? "");
  } catch {
    return Response.json({ error: "bad_json" }, { status: 400 });
  }
  if (!poolId || !["A", "B"].includes(winner)) {
    return Response.json({ error: "poolId + winner(A|B) required" }, { status: 400 });
  }

  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );

  const { data, error } = await supabase.rpc("settle_prediction_pool", {
    p_pool_id: poolId,
    p_winner: winner,
  });

  if (error) return Response.json({ error: error.message }, { status: 409 });
  return Response.json({ ok: true, result: data });
});
