// THE SYSTEM — Edge Function: CPA offerwall postback receiver.
// Configure this URL in CPALead/Tapjoy as the postback:
//   https://<ref>.supabase.co/functions/v1/cpa-postback?subid={userId}&vc={amount}&ref={txn}&secret=YOUR_SECRET
// Credits VC idempotently and routes the 2% lifetime referral cut.
import { createClient } from "jsr:@supabase/supabase-js@2";

Deno.serve(async (req: Request) => {
  const url = new URL(req.url);
  if (url.searchParams.get("secret") !== Deno.env.get("CPA_POSTBACK_SECRET")) {
    return Response.json({ error: "bad_secret" }, { status: 401 });
  }
  const userId = url.searchParams.get("subid") ?? "";
  const vc = Number(url.searchParams.get("vc") ?? "0");
  const ref = url.searchParams.get("ref") ?? "";
  if (!userId || !ref || vc <= 0 || vc > 100000) {
    return Response.json({ error: "bad_params" }, { status: 400 });
  }

  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );
  const { error } = await supabase.rpc("cpa_postback", {
    p_user: userId,
    p_vc: Math.floor(vc),
    p_external_ref: ref,
  });
  if (error) return Response.json({ error: error.message }, { status: 500 });
  return Response.json({ ok: true });
});
