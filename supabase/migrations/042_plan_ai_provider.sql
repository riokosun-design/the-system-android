-- 042 — vC55 §B7: which provider slot actually drafted the plan (audit rail).
-- Multi-provider router stamps e.g. 'groq-2:qwen/qwen3.8-27b',
-- 'tokenharbor-mimo-1:<slug>', 'google-1:gemini-2.5-flash'; deterministic
-- fallback stamps NULL (ai_generated=false already covers honesty).
alter table public.user_system_plans
  add column if not exists ai_provider_used text;
