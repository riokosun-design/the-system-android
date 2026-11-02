-- 036 — CPA exactly-once rail + Groq system plans (directive §2/§3)

create unique index if not exists vc_tx_cpa_ref_uq on public.vc_transactions (reference) where reason = 'CPA_POSTBACK' and reference is not null;

create unique index if not exists vc_tx_referral_ref_uq on public.vc_transactions (reference) where reason = 'REFERRAL_CUT' and reference is not null;

CREATE OR REPLACE FUNCTION public.credit_vc(p_user uuid, p_amount bigint, p_reason text, p_ref text DEFAULT NULL::text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare v_inserted bigint;
begin
  -- vC48 LAWS: ledger-row FIRST with ON CONFLICT → the tx_id uniqueness wall
  -- makes replayed postbacks exactly-once. Any exception below aborts the
  -- whole statement transaction, rolling the ledger row back with it.
  insert into public.vc_transactions (user_id, amount, reason, reference)
  values (p_user, p_amount, p_reason, p_ref)
  on conflict do nothing
  returning id into v_inserted;
  if p_ref is not null and v_inserted is null then
    return; -- replay of an already-stamped reference: never double-credit
  end if;
  if p_amount < 0 then
    update public.users set vc_balance = vc_balance + p_amount
      where id = p_user and vc_balance >= -p_amount;
    if not found then raise exception 'insufficient_vc'; end if;
  else
    update public.users set vc_balance = vc_balance + p_amount where id = p_user;
    if not found then raise exception 'no_such_user'; end if;
  end if;
end 
$function$;

alter table public.users add column if not exists gender text check (gender in ('M','F'));

create table if not exists public.user_system_plans (
  user_id uuid primary key references public.users(id) on delete cascade,
  bmr integer not null, tdee integer not null, calories_target integer not null,
  protein_g integer not null, carbs_g integer not null, fat_g integer not null,
  daily_routine jsonb not null default '[]'::jsonb,
  rank_tier text, multiplier numeric, ai_generated boolean not null default false,
  model text, created_at timestamptz not null default now(), updated_at timestamptz not null default now());

alter table public.user_system_plans enable row level security;

drop policy if exists plans_read_own on public.user_system_plans;

create policy plans_read_own on public.user_system_plans for select using (auth.uid() = user_id);

revoke insert, update, delete on public.user_system_plans from anon, authenticated;

grant select on public.user_system_plans to authenticated;

create or replace function public.get_offerwall_config() returns jsonb language sql stable security definer set search_path = public as $$
  select coalesce((select value -> 'cpa_offerwall' from public.engine_config where key = 'monetization'), '{}'::jsonb)
$$;

grant execute on function public.get_offerwall_config() to authenticated;

update public.engine_config set value = value || jsonb_build_object('cpa_offerwall', jsonb_build_object(
     'status', 'AWAITING_PROVIDER',
     'note', 'rail armed: cpa-postback edge fn + idempotent credit_vc live; provider wall URL pending',
     'wall_url', null,
     'postback', 'https://bacwuvdomvvfavbwgvid.supabase.co/functions/v1/cpa-postback'))
 where key = 'monetization';
