// THE SYSTEM — Edge Function: run the daily penalty engine on demand/cron.
// XP decay (3% × missed day), degradation to GARBAGE (3-4d) / LOSER (5d+),
// arc timeline extensions. Atomic, idempotent per day via last_activity_date.
import { createClient } from "jsr:@supabase/supabase-js@2";

Deno.serve(async () => {
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );
  const { data, error } = await supabase.rpc("apply_daily_penalties");
  if (error) return Response.json({ error: error.message }, { status: 500 });
  return Response.json({ hunters_penalized: data });
});
