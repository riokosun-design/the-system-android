-- ═══════════════════════════════════════════════════════════════════════════
-- MIGRATION 043 · v1.0-RELEASE MASTER (directive §1/§2/§4/§7/§8)
--
--   §1  VC/CPA PURGE — the coin economy dies. History tables (vc_transactions,
--       vc_orders) survive READ-ONLY as financial evidence; every credit /
--       redeem / order / postback function is dropped, the offerwall config is
--       purged, and the bounty rail is unbound. Workouts, quests and chess
--       reward XP, rank progress and prestige — no coin clutter.
--   §4  GEO IDENTITY — users carry city/district/state (user-declared, same
--       privacy law as country in mig 025) for the LOCAL leaderboard tier.
--   §2  MEDAL TRIALS + DUNGEON PROTOCOLS + BLACK ROOM 2-PAYMENT RAIL —
--       Razorpay-ready: every purchase first creates a PENDING_PAYMENT order
--       with a server-minted reference; fulfillment lands only when the live
--       gateway webhook settles it. No client-side price math, ever.
--   §7  SPECIAL QUESTS — admin-broadcast quests with GLOBAL / INDIA / LOCAL
--       (city·district) targeting, read-side scoped by the hunter's own geo.
--   §8  BROTHERHOOD — extend_a_hand rally rail (rate-limited, notice-stamped).
-- ═══════════════════════════════════════════════════════════════════════════

-- ── §1 · VC / CPA PURGE ─────────────────────────────────────────────────────
drop function if exists public.cpa_postback(uuid, bigint, text) cascade;
drop function if exists public.redeem_vc_discount(uuid, text) cascade;
drop function if exists public.get_offerwall_config() cascade;
drop function if exists public.create_vc_order(text) cascade;
drop function if exists public.submit_vc_order_proof(uuid, text, text, jsonb) cascade;
drop function if exists public.expire_stale_vc_orders() cascade;
drop function if exists public.admin_adjust_vc(uuid, bigint, text) cascade;
drop function if exists public.credit_vc(uuid, bigint, text, text) cascade;
drop function if exists public.credit_vc(uuid, bigint, text) cascade;

do $$
begin
  if to_regclass('public.engine_config') is not null then
    update public.engine_config set value = value - 'cpa_offerwall' where key = 'monetization';
  end if;
  if to_regclass('public.config') is not null then
    delete from public.config where key = 'offerwall_url';
  end if;
end $$;

-- create_clan (mig 002) deducted 500 VC through the now-dropped credit_vc —
-- redefine it level-gated only, so guild founding survives the purge.
create or replace function public.create_clan(p_name text, p_tag text) returns uuid
language plpgsql security definer set search_path = public as $$
declare u public.users%rowtype; clan_id uuid;
begin
  select * into u from public.users where id = auth.uid();
  if u.level < 30 then raise exception 'level_req'; end if;
  if exists(select 1 from public.clan_members where user_id = u.id) then raise exception 'already_in_clan'; end if;
  insert into public.clans (name, tag, guild_master) values (p_name, p_tag, u.id) returning id into clan_id;
  insert into public.clan_members (clan_id, user_id, role) values (clan_id, u.id, 'GUILD_MASTER');
  return clan_id;
end $$;

-- ── §4 · GEO IDENTITY (user-declared — privacy law of mig 025 holds) ────────
alter table public.users add column if not exists city text;
alter table public.users add column if not exists district text;
alter table public.users add column if not exists state text;

-- ── §2 · MEDAL TRIALS (the Conqueror model — physical dog-tag at 100%) ──────
create table if not exists public.medal_trials (
  id            text primary key,
  title         text not null,
  days          int  not null check (days in (30, 60)),
  target_reps   int  not null default 0,   -- e.g. 3000 push-ups
  target_km     numeric not null default 0, -- e.g. 60
  price_inr     int  not null check (price_inr between 399 and 499),
  medal_name    text not null,
  active        boolean not null default true,
  created_at    timestamptz not null default now()
);

insert into public.medal_trials (id, title, days, target_reps, target_km, price_inr, medal_name) values
  ('SHADOW_MONARCH_30', 'SHADOW MONARCH TRIAL', 30, 3000, 60,  499, 'S-RANK SHADOW MONARCH DOG-TAG'),
  ('IRON_VESSEL_30',    'IRON VESSEL TRIAL',    30, 2000, 30,  399, 'A-RANK IRON VESSEL DOG-TAG'),
  ('MONARCH_ASCENT_60', 'MONARCH ASCENT TRIAL', 60, 6000, 120, 499, 'S-RANK MONARCH ASCENT MEDAL'),
  ('DEMON_RIDGE_60',    'DEMON RIDGE TRIAL',    60, 4500, 90,  449, 'A-RANK DEMON RIDGE MEDAL')
on conflict (id) do nothing;

create table if not exists public.medal_trial_enrollments (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references public.users(id) on delete cascade,
  trial_id     text not null references public.medal_trials(id),
  status       text not null default 'PENDING_PAYMENT'
               check (status in ('PENDING_PAYMENT','ACTIVE','COMPLETED','EXPIRED')),
  order_ref    text not null unique,
  reps_done    int not null default 0,
  km_done      numeric not null default 0,
  started_at   timestamptz,
  deadline_at  timestamptz,
  completed_at timestamptz,
  shipping     jsonb,   -- address form unlocks ONLY at 100% (server-verified)
  created_at   timestamptz not null default now()
);
create index if not exists medal_enroll_user_idx on public.medal_trial_enrollments (user_id, created_at desc);

alter table public.medal_trial_enrollments enable row level security;
drop policy if exists medal_enroll_select on public.medal_trial_enrollments;
create policy medal_enroll_select on public.medal_trial_enrollments
  for select using (auth.uid() = user_id or public.is_admin());

alter table public.medal_trials enable row level security;
drop policy if exists medal_trials_select on public.medal_trials;
create policy medal_trials_select on public.medal_trials for select using (true);

-- Razorpay-ready registration: mints the PENDING order; the gateway webhook
-- (when wired) is the ONLY path to ACTIVE. Client money math: zero.
create or replace function public.medal_trial_enroll(p_trial text)
returns jsonb
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
  t public.medal_trials%rowtype;
  v_ref text;
  v_existing public.medal_trial_enrollments%rowtype;
begin
  if auth.uid() is null then return jsonb_build_object('ok', false, 'err', 'AUTH'); end if;
  select * into t from public.medal_trials where id = p_trial and active;
  if not found then return jsonb_build_object('ok', false, 'err', 'TRIAL_NOT_FOUND'); end if;

  -- one living enrollment per trial per hunter (idempotent retry returns the same order)
  select * into v_existing from public.medal_trial_enrollments
   where user_id = auth.uid() and trial_id = p_trial and status in ('PENDING_PAYMENT','ACTIVE')
   order by created_at desc limit 1;
  if found then
    return jsonb_build_object('ok', true, 'order_ref', v_existing.order_ref,
      'amount_inr', t.price_inr, 'status', v_existing.status, 'idempotent', true);
  end if;

  v_ref := 'MT-' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 12));
  insert into public.medal_trial_enrollments (user_id, trial_id, order_ref)
  values (auth.uid(), p_trial, v_ref);
  return jsonb_build_object('ok', true, 'order_ref', v_ref, 'amount_inr', t.price_inr, 'status', 'PENDING_PAYMENT');
end;
$function$;
grant execute on function public.medal_trial_enroll(text) to authenticated;

-- 100% completion unlocks the shipping form — server is the only judge.
create or replace function public.medal_trial_claim_completion(p_enrollment uuid, p_shipping jsonb)
returns jsonb
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
  e public.medal_trial_enrollments%rowtype;
  t public.medal_trials%rowtype;
begin
  select * into e from public.medal_trial_enrollments where id = p_enrollment and user_id = auth.uid();
  if not found then return jsonb_build_object('ok', false, 'err', 'NOT_FOUND'); end if;
  if e.status <> 'ACTIVE' then return jsonb_build_object('ok', false, 'err', 'NOT_ACTIVE'); end if;
  select * into t from public.medal_trials where id = e.trial_id;
  if e.reps_done < t.target_reps or e.km_done < t.target_km then
    return jsonb_build_object('ok', false, 'err', 'TRIAL_INCOMPLETE',
      'reps_left', t.target_reps - e.reps_done, 'km_left', t.target_km - e.km_done);
  end if;
  update public.medal_trial_enrollments
     set status = 'COMPLETED', completed_at = now(), shipping = p_shipping
   where id = e.id;
  return jsonb_build_object('ok', true, 'medal', t.medal_name);
end;
$function$;
grant execute on function public.medal_trial_claim_completion(uuid, jsonb) to authenticated;

-- ── §2 · DUNGEON PROTOCOLS (goal-based DLC blueprints, one-time unlock) ─────
create table if not exists public.dungeon_protocols (
  id         text primary key,
  title      text not null,
  days       int  not null,
  focus      text not null,
  price_inr  int  not null check (price_inr between 149 and 299),
  active     boolean not null default true,
  created_at timestamptz not null default now()
);

insert into public.dungeon_protocols (id, title, days, focus, price_inr) values
  ('FOOTBALL_SPEED_60',  'EXPLOSIVE FOOTBALL SPEED & STAMINA', 60, 'SPORT · SPEED',  299),
  ('EXAM_FOCUS_30',      'EXTREME EXAM FOCUS & DOPAMINE RESET', 30, 'MIND · FOCUS',   199),
  ('CALI_AESTHETIC_90',  'CALISTHENICS AESTHETIC HOME BLUEPRINT', 90, 'BODY · FORM',  249),
  ('DAWN_DISCIPLINE_21', '21-DAY DAWN DISCIPLINE FORGE', 21, 'MIND · HABIT',        149)
on conflict (id) do nothing;

create table if not exists public.dungeon_unlocks (
  user_id     uuid not null references public.users(id) on delete cascade,
  protocol_id text not null references public.dungeon_protocols(id),
  status      text not null default 'PENDING_PAYMENT' check (status in ('PENDING_PAYMENT','UNLOCKED')),
  order_ref   text not null unique,
  created_at  timestamptz not null default now(),
  unlocked_at timestamptz,
  primary key (user_id, protocol_id)
);

alter table public.dungeon_unlocks enable row level security;
drop policy if exists dungeon_unlocks_select on public.dungeon_unlocks;
create policy dungeon_unlocks_select on public.dungeon_unlocks
  for select using (auth.uid() = user_id or public.is_admin());

alter table public.dungeon_protocols enable row level security;
drop policy if exists dungeon_protocols_select on public.dungeon_protocols;
create policy dungeon_protocols_select on public.dungeon_protocols for select using (true);

create or replace function public.dungeon_unlock_request(p_protocol text)
returns jsonb
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
  p public.dungeon_protocols%rowtype;
  v_ref text;
  v_existing public.dungeon_unlocks%rowtype;
begin
  if auth.uid() is null then return jsonb_build_object('ok', false, 'err', 'AUTH'); end if;
  select * into p from public.dungeon_protocols where id = p_protocol and active;
  if not found then return jsonb_build_object('ok', false, 'err', 'NOT_FOUND'); end if;
  select * into v_existing from public.dungeon_unlocks where user_id = auth.uid() and protocol_id = p_protocol;
  if found then
    return jsonb_build_object('ok', true, 'order_ref', v_existing.order_ref,
      'amount_inr', p.price_inr, 'status', v_existing.status, 'idempotent', true);
  end if;
  v_ref := 'DP-' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 12));
  insert into public.dungeon_unlocks (user_id, protocol_id, order_ref) values (auth.uid(), p_protocol, v_ref);
  return jsonb_build_object('ok', true, 'order_ref', v_ref, 'amount_inr', p.price_inr, 'status', 'PENDING_PAYMENT');
end;
$function$;
grant execute on function public.dungeon_unlock_request(text) to authenticated;

-- ── §2 · BLACK ROOM — 3-YEAR ARC, 2-PAYMENT RAIL ────────────────────────────
-- Pricing stays in black_room_eligibility (mig 024: dynamic, consistency-seeded).
-- This rail records the two payments of the arc: UNLOCK, then RENEWAL at month
-- 6 (the remaining 2.5 years unlock forever). PENDING until the gateway settles.
create table if not exists public.black_room_orders (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references public.users(id) on delete cascade,
  stage      text not null check (stage in ('UNLOCK','RENEWAL')),
  order_ref  text not null unique,
  amount_inr int not null,
  status     text not null default 'PENDING_PAYMENT' check (status in ('PENDING_PAYMENT','SETTLED')),
  created_at timestamptz not null default now(),
  settled_at timestamptz
);
create index if not exists black_room_orders_user_idx on public.black_room_orders (user_id, created_at desc);

alter table public.black_room_orders enable row level security;
drop policy if exists black_room_orders_select on public.black_room_orders;
create policy black_room_orders_select on public.black_room_orders
  for select using (auth.uid() = user_id or public.is_admin());

-- Mints the correct stage's order (server reads the hunter's own arc state).
create or replace function public.black_room_order_request()
returns jsonb
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
  v_price jsonb;
  v_amount int;
  v_unlocked boolean;
  v_renewal_due boolean;
  v_pending public.black_room_orders%rowtype;
  v_ref text;
  v_stage text;
begin
  if auth.uid() is null then return jsonb_build_object('ok', false, 'err', 'AUTH'); end if;
  -- the 024 gate owns pricing (dynamic: consistency + personal seed, INR banded)
  v_price := public.black_room_eligibility('IN');
  v_amount := greatest(149, least(699, coalesce((v_price ->> 'upi')::int, 499)));

  select * into v_pending from public.black_room_orders
   where user_id = auth.uid() and status = 'PENDING_PAYMENT'
   order by created_at desc limit 1;
  if found then
    return jsonb_build_object('ok', true, 'order_ref', v_pending.order_ref,
      'amount_inr', v_pending.amount_inr, 'stage', v_pending.stage,
      'status', v_pending.status, 'idempotent', true);
  end if;

  select exists(select 1 from public.black_room_orders where user_id = auth.uid() and stage = 'UNLOCK' and status = 'SETTLED')
    into v_unlocked;
  v_stage := case when v_unlocked then 'RENEWAL' else 'UNLOCK' end;
  -- RENEWAL is quotable only 6 months after the UNLOCK settled (directive §2.1)
  if v_unlocked then
    select (settled_at + interval '6 months') <= now() into v_renewal_due
      from public.black_room_orders
     where user_id = auth.uid() and stage = 'UNLOCK' and status = 'SETTLED'
     order by settled_at desc limit 1;
    if not coalesce(v_renewal_due, false) then
      return jsonb_build_object('ok', false, 'err', 'RENEWAL_NOT_DUE');
    end if;
  end if;

  v_ref := 'BR-' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 12));
  insert into public.black_room_orders (user_id, stage, order_ref, amount_inr)
  values (auth.uid(), v_stage, v_ref, v_amount);
  return jsonb_build_object('ok', true, 'order_ref', v_ref, 'amount_inr', v_amount, 'stage', v_stage, 'status', 'PENDING_PAYMENT');
end;
$function$;
grant execute on function public.black_room_order_request() to authenticated;

-- ── §7 · SPECIAL QUESTS (admin broadcast + AI local shared quests) ──────────
create table if not exists public.special_quests (
  id         uuid primary key default gen_random_uuid(),
  title      text not null,
  detail     text not null default '',
  xp_reward  int  not null default 150 check (xp_reward between 25 and 2000),
  scope      text not null check (scope in ('GLOBAL','INDIA','LOCAL')),
  city       text,
  district   text,
  state      text,
  source     text not null default 'ADMIN' check (source in ('ADMIN','AI')),
  created_by uuid,
  expires_at timestamptz not null default now() + interval '48 hours',
  created_at timestamptz not null default now()
);
create index if not exists special_quests_scope_idx on public.special_quests (scope, city, district, expires_at desc);

alter table public.special_quests enable row level security;
-- Geo law: a hunter sees GLOBAL, INDIA (when they declared IN), and LOCAL rows
-- matching their own declared city/district. Nothing else leaks.
drop policy if exists special_quests_select on public.special_quests;
create policy special_quests_select on public.special_quests for select using (
  scope = 'GLOBAL'
  or (scope = 'INDIA' and exists(select 1 from public.users u where u.id = auth.uid() and u.country = 'IN'))
  or (scope = 'LOCAL' and exists(select 1 from public.users u where u.id = auth.uid()
        and u.city is not null and u.city = special_quests.city
        and coalesce(u.district, '') = coalesce(special_quests.district, '')))
);

create or replace function public.broadcast_special_quest(
  p_title text, p_detail text, p_xp int, p_scope text,
  p_city text default null, p_district text default null, p_state text default null,
  p_hours int default 48
) returns uuid
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
  v_id uuid;
begin
  if not public.is_admin() then raise exception 'ADMIN_ONLY'; end if;
  if p_scope not in ('GLOBAL','INDIA','LOCAL') then raise exception 'BAD_SCOPE'; end if;
  if p_scope = 'LOCAL' and p_city is null then raise exception 'LOCAL_NEEDS_CITY'; end if;
  insert into public.special_quests (title, detail, xp_reward, scope, city, district, state, source, created_by, expires_at)
  values (p_title, coalesce(p_detail, ''), p_xp, p_scope, p_city, p_district, p_state, 'ADMIN', auth.uid(),
          now() + make_interval(hours => greatest(1, least(p_hours, 168))))
  returning id into v_id;
  return v_id;
end;
$function$;
grant execute on function public.broadcast_special_quest(text, text, int, text, text, text, text, int) to authenticated;

-- AI shared-local-quest writer: the scheduler (generate-system-plan) calls this
-- per active region so every hunter in the SAME locale gets the SAME quest.
create or replace function public.issue_local_shared_quest(
  p_city text, p_district text, p_state text, p_title text, p_detail text, p_xp int default 200
) returns uuid
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
  v_id uuid;
begin
  -- one live AI local quest per city — re-issue replaces, never stacks
  delete from public.special_quests
   where scope = 'LOCAL' and source = 'AI' and city = p_city
     and coalesce(district, '') = coalesce(p_district, '');
  insert into public.special_quests (title, detail, xp_reward, scope, city, district, state, source, expires_at)
  values (p_title, coalesce(p_detail, ''), p_xp, 'LOCAL', p_city, p_district, p_state, 'AI', now() + interval '48 hours')
  returning id into v_id;
  return v_id;
end;
$function$;
revoke all on function public.issue_local_shared_quest(text, text, text, text, text, int) from public, authenticated, anon;
grant execute on function public.issue_local_shared_quest(text, text, text, text, text, int) to service_role;

-- ── §8 · BROTHERHOOD RALLIES (extend a hand — 1/pair/24h, notice-stamped) ───
create table if not exists public.brotherhood_rallies (
  id         uuid primary key default gen_random_uuid(),
  from_user  uuid not null references public.users(id) on delete cascade,
  to_user    uuid not null references public.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  check (from_user <> to_user)
);
create index if not exists rallies_pair_idx on public.brotherhood_rallies (from_user, to_user, created_at desc);

alter table public.brotherhood_rallies enable row level security;
drop policy if exists rallies_select on public.brotherhood_rallies;
create policy rallies_select on public.brotherhood_rallies
  for select using (auth.uid() = from_user or auth.uid() = to_user);

create or replace function public.extend_a_hand(p_to uuid)
returns jsonb
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
  v_me text;
  v_recent int;
begin
  if auth.uid() is null then return jsonb_build_object('ok', false, 'err', 'AUTH'); end if;
  if p_to = auth.uid() then return jsonb_build_object('ok', false, 'err', 'SELF'); end if;
  if not exists(select 1 from public.users where id = p_to) then
    return jsonb_build_object('ok', false, 'err', 'NO_TARGET');
  end if;
  select count(*) into v_recent from public.brotherhood_rallies
   where from_user = auth.uid() and to_user = p_to and created_at > now() - interval '24 hours';
  if v_recent > 0 then return jsonb_build_object('ok', false, 'err', 'ALREADY_EXTENDED'); end if;

  select coalesce(username, 'A hunter') into v_me from public.users where id = auth.uid();
  insert into public.brotherhood_rallies (from_user, to_user) values (auth.uid(), p_to);
  insert into public.user_notifications (user_id, kind, title, body)
  values (p_to, 'BROTHERHOOD_RALLY', 'BROTHERHOOD SIGNAL',
    '[ Hunter ' || v_me || ' extended a hand to you. Get back up — we rise together. ]');
  return jsonb_build_object('ok', true);
end;
$function$;
grant execute on function public.extend_a_hand(uuid) to authenticated;

-- rally count feeds the local leaderboard's brotherhood glow (read-only)
create or replace function public.rallies_received_today(p_user uuid)
returns int language sql stable security definer set search_path to 'public' as $$
  select count(*)::int from public.brotherhood_rallies where to_user = p_user and created_at > now() - interval '24 hours';
$$;
grant execute on function public.rallies_received_today(uuid) to authenticated;

-- ── §1 · DEEP PURGE — every surviving VC caller is executed or reborn ──────
-- The manual payment rail + VC order/withdrawal review tooling die completely.
drop function if exists public.review_payment(uuid, boolean, text) cascade;
drop function if exists public.admin_review_vc_order(uuid, boolean, text) cascade;
drop function if exists public.admin_review_withdrawal(uuid, text, text, text) cascade;
drop function if exists public.request_withdrawal(bigint, text, jsonb) cascade;

-- TOURNAMENTS: entry is free; the bracket crown pays XP glory, not a coin pool.
create or replace function public.join_tournament(p_tournament_id uuid, p_clan_id uuid default null) returns void
language plpgsql security definer set search_path = public as $$
declare
  t public.tournaments%rowtype;
  me uuid := auth.uid();
begin
  if me is null then raise exception 'not_authenticated'; end if;
  select * into t from public.tournaments where id = p_tournament_id for update;
  if not found then raise exception 'no_tournament'; end if;
  if t.status not in ('OPEN') then raise exception 'registration_closed'; end if;
  if (select count(*) from public.tournament_participants p where p.tournament_id = t.id) >= t.max_participants
     then raise exception 'bracket_full'; end if;

  if t.type = 'CLAN' then
    if p_clan_id is null then raise exception 'clan_required'; end if;
    if not exists(select 1 from public.clan_members m
                   where m.clan_id = p_clan_id and m.user_id = me
                     and m.role in ('GUILD_MASTER','VICE_CAPTAIN')) then
      raise exception 'not_member_or_officer';
    end if;
    insert into public.tournament_participants (tournament_id, clan_id, entry_fee_paid)
      values (t.id, p_clan_id, 0);
  else
    insert into public.tournament_participants (tournament_id, user_id, entry_fee_paid)
      values (t.id, me, 0);
  end if;
exception when unique_violation then
  raise exception 'already_joined';
end $$;

create or replace function public.advance_bracket(p_tournament_id uuid)
returns integer
language plpgsql security definer set search_path = public as $$
declare
    t         record;
    v_round   int;
    v_pending int;
    v_users   uuid[];
    v_clans   uuid[];
    n         int;
    i         int := 1;
    v_created int := 0;
    champ     uuid;
begin
    if not (public.is_super_admin() or public.is_admin()) then
        raise exception 'admin_only';
    end if;

    select * into t from public.tournaments where id = p_tournament_id for update;
    if not found then raise exception 'tournament_not_found'; end if;
    if t.status::text <> 'IN_PROGRESS' then raise exception 'tournament_not_in_progress'; end if;

    select max(round) into v_round from public.battles where tournament_id = p_tournament_id;
    if v_round is null then raise exception 'no_bracket_generated'; end if;

    select count(*) into v_pending
    from public.battles
    where tournament_id = p_tournament_id and round = v_round and status <> 'FINISHED';
    if v_pending > 0 then raise exception 'round_not_finished'; end if;

    -- winners advance in bracket order, carrying their clan banner with them
    select array_agg(b.winner order by b.created_at),
           array_agg((case when b.winner = b.player_a then b.clan_a else b.clan_b end) order by b.created_at)
      into v_users, v_clans
      from public.battles b
      where b.tournament_id = p_tournament_id and b.round = v_round;

    n := coalesce(array_length(v_users, 1), 0);

    -- final battle decided → crown the champion in XP glory (v1.0 §1: the coin
    -- pool is dead; the champion's blessing is XP + the title)
    if n = 1 then
        champ := v_users[1];
        update public.tournaments set status = 'COMPLETED', ends_at = now() where id = p_tournament_id;
        update public.tournament_participants set placement = 1
        where tournament_id = p_tournament_id and user_id = champ;
        if champ is not null then
            perform public.apply_xp(champ, 500, null);
        end if;
        insert into public.admin_audit_log (actor, action, payload)
        values (auth.uid(), 'TOURNAMENT_CROWNED', jsonb_build_object('tournament', p_tournament_id, 'champion', champ, 'prize_xp', 500));
        return 0;
    end if;

    while i + 1 <= n loop
        insert into public.battles (tournament_id, player_a, player_b, clan_a, clan_b, status, round)
        values (p_tournament_id, v_users[i], v_users[i + 1], v_clans[i], v_clans[i + 1], 'LOBBY', v_round + 1);
        v_created := v_created + 1;
        i := i + 2;
    end loop;

    insert into public.admin_audit_log (actor, action, payload)
    values (auth.uid(), 'ADVANCE_BRACKET', jsonb_build_object('tournament', p_tournament_id, 'round', v_round + 1, 'battles', v_created));
    return v_created;
end $$;

-- BATTLE CHALLENGES: XP only (the 038 whitelist already made the VC mint raise).
create or replace function public.claim_battle_challenge(p_kind text default 'PUSH')
returns void language plpgsql security definer set search_path = public as $$
declare c jsonb; v_required int; v_wins int; v_ex text;
begin
  if p_kind not in ('PUSH','SQUAT') then raise exception 'bad_kind'; end if;
  select x into c from unnest(public.daily_battle_challenges()) x
   where x->>'id' = p_kind;
  if c is null then raise exception 'no_challenge'; end if;
  v_required := (c->>'required')::int;
  v_wins := (c->>'wins')::int;
  if (c->>'claimed')::boolean then raise exception 'already_claimed'; end if;
  if v_wins < v_required then raise exception 'challenge_incomplete'; end if;
  insert into public.workouts (user_id, kind, reps, duration_sec, xp_earned)
  values (auth.uid(), 'BATTLE_CHALLENGE_' || p_kind, v_required, 0, 120);
  perform public.apply_xp(auth.uid(), 120, null);
end $$;

-- SQUAD STATUS: the treasury key leaves the payload (client never reads it).
create or replace function public.squad_status()
returns jsonb
language plpgsql stable security definer set search_path to 'public' as $function$
declare
  c public.clans%rowtype;
  v_score jsonb;
begin
  select cl.* into c from public.clans cl
    join public.clan_members m on m.clan_id = cl.id and m.user_id = auth.uid()
  limit 1;
  if not found then return jsonb_build_object('in_squad', false); end if;
  v_score := public.squad_score(c.id);
  return jsonb_build_object(
    'in_squad', true, 'clan_id', c.id, 'name', c.name, 'tag', c.tag,
    'stored_level', c.level,
    'guild_master', c.guild_master) || v_score;
end $function$;
