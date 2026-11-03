-- 037 — vC50 §2: full structured directive payload rides with the plan.
alter table public.user_system_plans
  add column if not exists plan_data jsonb not null default '{}'::jsonb;
