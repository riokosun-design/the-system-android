// ═══════════════════════════════════════════════════════════════════════════
// THE SYSTEM — Edge Function: CPA OFFERWALL POSTBACK RECEIVER (v2, vC49 §2)
// Secure server-side webhook that verifies partner reward callbacks and
// atomically credits VC. Exactly-once is enforced THREE layers deep:
//   1. signature gate (HMAC-SHA256 x-signature header, constant-time compare)
//      or the legacy query-secret for GET-only networks (CPALead style)
//   2. RPC pre-check (reported as credited:false for replays — observability)
//   3. vc_tx_cpa_ref_uq unique index + credit_vc ON CONFLICT — a race between
//      two concurrent retries can still only land ONE ledger row, and the
//      balance move aborts with the whole statement on conflict
// Postback contract (bind in the provider dashboard when the wall goes live):
//   GET  .../cpa-postback?subid={userId}&vc={amount}&ref={txn}&secret=…
//   POST .../cpa-postback  {"user_id":…,"amount":…,"tx_id":…,"source":…}
//        + header  x-signature: hex(HMAC_SHA256(secret, rawBody))
// CPA Grip provider SDK: NOT integrated (strictly deferred) — this is the
// provider-agnostic rail only. No fake offers are ever generated.
// ═══════════════════════════════════════════════════════════════════════════
import { createClient } from "jsr:@supabase/supabase-js@2";

const enc = new TextEncoder();

async function hmacHex(secret: string, msg: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", key, enc.encode(msg));
  return Array.from(new Uint8Array(sig)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Constant-time-ish compare (length check first — no oracle on shape). */
function safeEq(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const TX_RE = /^[A-Za-z0-9_:\-]{4,128}$/;

Deno.serve(async (req: Request) => {
  const secret = Deno.env.get("CPA_POSTBACK_SECRET") ?? "";
  if (!secret) return Response.json({ error: "rail_unconfigured" }, { status: 503 });

  let userId = "", txId = "", source = "CPA_WALL", amount = 0, signedPayload = "", querySecret = "";

  if (req.method === "GET") {
    const u = new URL(req.url);
    userId = (u.searchParams.get("subid") ?? u.searchParams.get("user_id")) ?? "";
    amount = Number(u.searchParams.get("vc") ?? u.searchParams.get("amount") ?? "0");
    txId = (u.searchParams.get("ref") ?? u.searchParams.get("tx_id")) ?? "";
    source = u.searchParams.get("source") ?? "CPA_WALL";
    querySecret = u.searchParams.get("secret") ?? "";
    signedPayload = `${userId}:${amount}:${txId}`;
  } else if (req.method === "POST") {
    const raw = await req.text();
    signedPayload = raw;
    try {
      const body = JSON.parse(raw);
      userId = String(body.user_id ?? body.subid ?? "");
      amount = Number(body.amount ?? body.vc ?? 0);
      txId = String(body.tx_id ?? body.ref ?? "");
      source = String(body.source ?? "CPA_WALL");
    } catch {
      return Response.json({ error: "bad_json" }, { status: 400 });
    }
  } else {
    return new Response("GET/POST only", { status: 405 });
  }

  // ── LAYER 0: signature gate ──────────────────────────────────────────────
  const headerSig = (req.headers.get("x-signature") ?? "").toLowerCase();
  const sigOk = headerSig.length === 64 && safeEq(headerSig, await hmacHex(secret, signedPayload));
  const secretOk = querySecret.length > 0 && safeEq(querySecret, secret);
  if (!sigOk && !secretOk) return Response.json({ error: "bad_signature" }, { status: 401 });

  // ── parameter law: real user, sane amount, well-formed unique tx ─────────
  const vc = Math.floor(amount);
  if (!UUID_RE.test(userId)) return Response.json({ error: "bad_user" }, { status: 400 });
  if (!TX_RE.test(txId)) return Response.json({ error: "bad_tx" }, { status: 400 });
  if (!Number.isFinite(vc) || vc <= 0 || vc > 50_000) return Response.json({ error: "bad_amount" }, { status: 400 });

  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );

  // ── LAYER 1: replay observability (the index below is the true wall) ─────
  const { data: seen } = await supabase
    .from("vc_transactions").select("id")
    .eq("reason", "CPA_POSTBACK").eq("reference", txId).limit(1);
  if (seen && seen.length > 0) return Response.json({ ok: true, credited: false, replay: true });

  // ── LAYERS 2+3: atomic exactly-once credit via cpa_postback → credit_vc ──
  const { error } = await supabase.rpc("cpa_postback", {
    p_user: userId, p_vc: vc, p_external_ref: source === "CPA_WALL" ? txId : `${source}:${txId}`,
  });
  if (error) {
    const msg = String(error.message ?? "");
    if (msg.includes("no_such_user") || msg.includes("foreign key")) {
      return Response.json({ error: "unknown_user" }, { status: 400 });
    }
    return Response.json({ error: msg.slice(0, 120) }, { status: 500 });
  }
  return Response.json({ ok: true, credited: true, vc });
});
