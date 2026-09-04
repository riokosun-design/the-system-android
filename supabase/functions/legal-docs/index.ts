// THE SYSTEM — public legal documents (Play Console compliance)
// Serves the LATEST legal_documents rows as a clean HTML page.
// Deploy with JWT verification DISABLED (Play reviewers open these URLs cold).
//   GET /functions/v1/legal-docs?doc=privacy   (default)
//   GET /functions/v1/legal-docs?doc=terms
import { createClient } from "npm:@supabase/supabase-js@2";

const PAGE = (title: string, body: string, meta: string) => `<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>${title} — THE SYSTEM</title>
<style>
  body{margin:0;background:#0A0B0E;color:#E2E8F0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;line-height:1.65}
  main{max-width:720px;margin:0 auto;padding:48px 20px 96px}
  h1{color:#F1F5F9;font-size:26px;letter-spacing:.02em;border-bottom:1px solid rgba(255,255,255,.08);padding-bottom:16px}
  .meta{color:#64748B;font-size:13px;margin:12px 0 32px}
  h2,h3{color:#38BDF8;margin-top:32px}
  strong{color:#F1F5F9}
  a{color:#38BDF8}
  p,li{color:#CBD5E1}
</style></head><body><main>
<h1>${title}</h1>
<div class="meta">${meta}</div>
${body}
</main></body></html>`;

/** Minimal, safe markdown: escape HTML, then headings / bold / italics / lists / paragraphs. */
function render(md: string): string {
  const esc = md
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  const lines = esc.split(/\r?\n/);
  const out: string[] = [];
  let inList = false;
  const inline = (t: string) =>
    t.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
     .replace(/\*([^*]+)\*/g, "<em>$1</em>");
  for (const raw of lines) {
    const line = raw.trimEnd();
    if (/^\s*[-•]\s+/.test(line)) {
      if (!inList) { out.push("<ul>"); inList = true; }
      out.push(`<li>${inline(line.replace(/^\s*[-•]\s+/, ""))}</li>`);
      continue;
    }
    if (inList) { out.push("</ul>"); inList = false; }
    if (!line.trim()) continue;
    if (line.startsWith("### ")) out.push(`<h3>${inline(line.slice(4))}</h3>`);
    else if (line.startsWith("## ")) out.push(`<h2>${inline(line.slice(3))}</h2>`);
    else if (line.startsWith("# ")) out.push(`<h2>${inline(line.slice(2))}</h2>`);
    else out.push(`<p>${inline(line)}</p>`);
  }
  if (inList) out.push("</ul>");
  return out.join("\n");
}

Deno.serve(async (req) => {
  const url = new URL(req.url);
  const want = (url.searchParams.get("doc") ?? "privacy").toLowerCase();
  const docType = want.startsWith("term") ? "TERMS_OF_SERVICE" : "PRIVACY_POLICY";

  const supa = createClient(
    Deno.env.get("SUPABASE_URL") ?? "",
    Deno.env.get("SUPABASE_ANON_KEY") ?? "",
  );

  const { data, error } = await supa
    .from("legal_documents")
    .select("title, content_markdown, version, updated_at")
    .eq("doc_type", docType)
    .order("version", { ascending: false })
    .limit(1)
    .maybeSingle();

  if (error || !data) {
    return new Response(PAGE("Document unavailable", `<p>${error ? "Server error." : "Document not published yet."}</p>`, "THE SYSTEM"), {
      status: 404, headers: { "content-type": "text/html; charset=utf-8" },
    });
  }

  const meta = `Version ${data.version}${data.updated_at ? " · Updated " + new Date(data.updated_at).toDateString() : ""} · THE SYSTEM`;
  return new Response(PAGE(data.title, render(data.content_markdown), meta), {
    headers: { "content-type": "text/html; charset=utf-8", "cache-control": "public, max-age=300" },
  });
});
