// THE SYSTEM — Edge Function: 10-minute workout verification media auto-purge.
// Hard-deletes storage objects + DB rows EXACTLY 10 minutes after created_at.
// Invoke via pg_cron/net (see 004_cron.sql) or any scheduler. Idempotent.
import { createClient } from "jsr:@supabase/supabase-js@2";

const CUTOFF_MS = 10 * 60 * 1000;

Deno.serve(async () => {
  const url = Deno.env.get("SUPABASE_URL")!;
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const supabase = createClient(url, serviceKey);

  const cutoff = new Date(Date.now() - CUTOFF_MS).toISOString();

  // 1. Find expired rows
  const { data: rows, error: selErr } = await supabase
    .from("workout_media")
    .select("id, storage_path")
    .lt("created_at", cutoff);
  if (selErr) return Response.json({ error: selErr.message }, { status: 500 });

  const ids = (rows ?? []).map((r) => r.id);
  const paths = (rows ?? []).map((r) => r.storage_path);
  if (ids.length === 0) return Response.json({ purged: 0, cutoff });

  // 2. Delete storage objects first (so a crash never orphans rows pointing to nothing)
  const { error: stErr } = await supabase.storage.from("workout-verification").remove(paths);
  if (stErr) return Response.json({ error: stErr.message, paths }, { status: 500 });

  // 3. Then the DB rows
  const { error: delErr } = await supabase.from("workout_media").delete().in("id", ids);
  if (delErr) return Response.json({ error: delErr.message, ids }, { status: 500 });

  return Response.json({ purged: ids.length, cutoff, paths });
});
