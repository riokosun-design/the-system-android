-- ═══════════════════════════════════════════════════════════════════════════
-- THE SYSTEM — 002: FUNCTIONS, TRIGGERS & TRANSACTIONAL RPCs
-- Every formula mirrors app/src/main/java/com/thesystem/app/core/SystemMath.kt.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── Role helpers (security definer → no RLS recursion) ───────────────────────
create or replace function public.is_admin() returns boolean
language sql stable security definer set search_path = public as $$
  select exists(select 1 from public.users where id = auth.uid() and role in ('SUPER_ADMIN','ADMIN'))
     or (auth.jwt() ->> 'role') = 'service_role'
$$;

create or replace function public.is_super_admin() returns boolean
language sql stable security definer set search_path = public as $$
  select exists(select 1 from public.users where id = auth.uid() and role = 'SUPER_ADMIN')
     or (auth.jwt() ->> 'role') = 'service_role'
$$;

-- ── Level engine: XP = 100 × N^1.8 (cumulative requirement curve) ────────────
create or replace function public.xp_required_for_level(l int) returns bigint
language sql immutable as $$
  select round(100 * power(greatest(l - 1, 0)::numeric, 1.8))::bigint
$$;

create or replace function public.level_for_xp(total_xp bigint) returns int
language plpgsql immutable as $$
declare l int := 1;
begin
  while l < 100 and total_xp >= public.xp_required_for_level(l + 1) loop
    l := l + 1;
  end loop;
  return l;
end $$;

create or replace function public.rank_title(p_level int, p_penalty penalty_state) returns text
language sql immutable as $$
  select case
    when p_penalty = 'LOSER' then 'LOSER'
    when p_penalty = 'GARBAGE' then 'GARBAGE'
    when p_level >= 80 then 'MASTERPIECE'
    when p_level >= 50 then 'S-RANK'
    when p_level >= 25 then 'ELITE'
    else 'AVERAGE' end
$$;

-- ── NEW USER: create profile; FIRST THREE registrations become SUPER_ADMIN ───
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
declare total int;
begin
  -- serialize concurrent first signups so exactly three admins ever get seeded
  perform pg_advisory_xact_lock(77001);

  insert into public.users (id, username, display_name, avatar_url)
  values (
    new.id,
    'hunter_' || lower(substr(replace(new.id::text,'-',''), 1, 10)), -- temp handle; onboarding forces a unique claim
    coalesce(new.raw_user_meta_data ->> 'full_name', new.raw_user_meta_data ->> 'name'),
    new.raw_user_meta_data ->> 'avatar_url'
  );

  select count(*) into total from public.users;
  if total <= 3 then
    update public.users set role = 'SUPER_ADMIN' where id = new.id;
  end if;

  insert into public.vc_transactions (user_id, amount, reason, reference)
  values (new.id, 100, 'SIGNUP_BONUS', 'welcome');
  update public.users set vc_balance = vc_balance + 100 where id = new.id;

  return new;
end $$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- ── Anti-privilege-escalation guard on users ─────────────────────────────────
-- Players may edit their profile, never role/level/xp/vc/penalty — those move
-- only through security-definer functions or an admin.
create or replace function public.guard_user_columns() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if (auth.jwt() ->> 'role') = 'service_role' then return new; end if;
  if not public.is_admin() then
    if new.role is distinct from old.role
       or new.level is distinct from old.level
       or new.xp is distinct from old.xp
       or new.vc_balance is distinct from old.vc_balance
       or new.penalty_state is distinct from old.penalty_state
       or new.black_room_until is distinct from old.black_room_until then
      raise exception 'forbidden_columns';
    end if;
  end if;
  -- even admins cannot self-promote to SUPER_ADMIN or demote another SUPER_ADMIN
  if new.role is distinct from old.role and not public.is_super_admin() then
    raise exception 'forbidden_columns';
  end if;
  new.updated_at := now();
  return new;
end $$;

drop trigger if exists users_guard on public.users;
create trigger users_guard before update on public.users
  for each row execute function public.guard_user_columns();

-- ── UNIQUE USERNAME CLAIM (@handle) ──────────────────────────────────────────
create or replace function public.claim_username(p_handle text) returns void
language plpgsql security definer set search_path = public as $$
declare h text := lower(trim(p_handle));
begin
  if h !~ '^[a-z0-9_]{3,20}$' then raise exception 'bad_handle'; end if;
  update public.users set username = h where id = auth.uid();
  if not found then raise exception 'no_profile'; end if;
exception when unique_violation then
  raise exception 'handle_taken';
end $$;

-- ── VC ledger primitive ──────────────────────────────────────────────────────
create or replace function public.credit_vc(p_user uuid, p_amount bigint, p_reason text, p_ref text default null) returns void
language plpgsql security definer set search_path = public as $$
begin
  if p_amount < 0 then
    update public.users set vc_balance = vc_balance + p_amount
      where id = p_user and vc_balance >= -p_amount;
    if not found then raise exception 'insufficient_vc'; end if;
  else
    update public.users set vc_balance = vc_balance + p_amount where id = p_user;
  end if;
  insert into public.vc_transactions (user_id, amount, reason, reference) values (p_user, p_amount, p_reason, p_ref);
end $$;

-- ── XP engine: award_xp (guild tax hooks), touch activity, penalties ─────────
create or replace function public.apply_xp(p_user uuid, p_amount bigint, p_zone text default null) returns bigint
language plpgsql security definer set search_path = public as $$
declare new_xp bigint; new_level int; tax bigint; guild uuid;
begin
  update public.users
     set xp = greatest(xp + p_amount, 0),
         last_activity_date = current_date,
         missed_days = 0,
         penalty_state = 'CLEAR',
         streak_days = case when last_activity_date = current_date - 1 then streak_days + 1
                            when last_activity_date = current_date then streak_days else 1 end
     where id = p_user
  returning xp, level into new_xp, new_level;

  new_level := public.level_for_xp(new_xp);
  if new_level <> (select level from public.users where id = p_user) then
    update public.users set level = new_level where id = p_user;
  end if;

  -- Territory guild tax: shielded zones route 5% of XP-equivalent VC to the guild treasury
  if p_zone is not null and p_amount > 0 then
    select t.clan_id into guild
      from public.clan_territories t
     where t.zone = p_zone and t.shield_active
       and not exists(select 1 from public.clan_members m where m.clan_id = t.clan_id and m.user_id = p_user);
    if guild is not null then
      tax := round(p_amount * 0.05);
      if tax > 0 then
        update public.clans set treasury_vc = treasury_vc + tax where id = guild;
        insert into public.vc_transactions (user_id, amount, reason, reference)
          values (p_user, 0, 'GUILD_TAX', format('zone %s taxed %s VC to guild', p_zone, tax));
      end if;
    end if;
  end if;

  return new_xp;
end $$;

-- Self-serve XP (verified client flows: quests, white room, capture). Hard-capped
-- per call; heavy rewards (battles/tournaments) are granted server-side only.
create or replace function public.award_xp(p_amount int, p_zone text default null) returns void
language plpgsql security definer set search_path = public as $$
begin
  if p_amount <= 0 or p_amount > 200 then raise exception 'xp_cap'; end if;
  perform public.apply_xp(auth.uid(), p_amount, p_zone);
end $$;

-- ── Penalty engine (daily cron): XP decay + degradation to GARBAGE/LOSER ─────
create or replace function public.apply_daily_penalties() returns int
language plpgsql security definer set search_path = public as $$
declare r record; decayed bigint; affected int := 0;
begin
  for r in
    select id, xp, missed_days, streak_days from public.users
     where last_activity_date is not null and last_activity_date < current_date
  loop
    decayed := floor(r.xp * (0.03 * (r.missed_days + 1)))::bigint;
    update public.users
       set xp = xp - decayed,
           level = public.level_for_xp(xp - decayed),
           missed_days = missed_days + 1,
           streak_days = 0,
           penalty_state = case
             when missed_days + 1 = 1 then 'WARNING'
             when missed_days + 1 = 2 then 'DECAY'
             when missed_days + 1 in (3,4) then 'GARBAGE'
             else 'LOSER' end
     where id = r.id;
    -- guild arcs absorb penalty as timeline extension too
    update public.user_arc_progress set penalty_extra_days = penalty_extra_days + 1
      where user_id = r.id and completed = false;
    affected := affected + 1;
  end loop;
  return affected;
end $$;

-- ── DAILY QUESTS — Leguna S.1 AI ─────────────────────────────────────────────
create or replace function public.ensure_daily_quests() returns void
language plpgsql security definer set search_path = public as $$
declare u public.users%rowtype; seed int;
begin
  select * into u from public.users where id = auth.uid();
  if not found then return; end if;
  if exists(select 1 from public.daily_quests where user_id = u.id and quest_date = current_date) then return; end if;

  seed := (extract(doy from current_date)::int + u.level) % 4;
  insert into public.daily_quests (user_id, quest_date, title, target_value, xp_reward) values
    (u.id, current_date, (array['100 PUSH-UPS — SAITAMA PROTOCOL','60 PUSH-UPS — SHADOW WARMUP','45 DIAMOND PUSH-UPS','80 KNEE-TO-ELBOW PLANKS'])[seed+1], 100, 60 + u.level),
    (u.id, current_date, (array['10KM SHADOW RUN','5KM DAWN RUN','200 SKIPS','90 BURPEES'])[seed+1], seed + 2, 80 + u.level),
    (u.id, current_date, (array['100 SQUATS — GOKU LEGS','50 LUNGES','3-MIN WALL SIT ×3','40 PISTOL PROGRESSIONS'])[seed+1], 100, 60 + u.level)
  on conflict do nothing;
end $$;

create or replace function public.complete_quest(p_quest_id bigint) returns void
language plpgsql security definer set search_path = public as $$
declare q public.daily_quests%rowtype; bonus int := 0;
begin
  select * into q from public.daily_quests where id = p_quest_id and user_id = auth.uid() for update;
  if not found then raise exception 'no_quest'; end if;
  if q.completed then raise exception 'already_done'; end if;

  update public.daily_quests set completed = true, progress = q.target_value where id = q.id;
  perform public.apply_xp(auth.uid(), q.xp_reward, null);
  bonus := round(q.xp_reward * 0.25)::int;
  perform public.credit_vc(auth.uid(), bonus, 'QUEST_REWARD', 'quest:' || q.id);
end $$;

create or replace function public.add_quest_progress(p_quest_id bigint, p_amount int) returns void
language plpgsql security definer set search_path = public as $$
begin
  update public.daily_quests set progress = least(progress + p_amount, target_value)
    where id = p_quest_id and user_id = auth.uid() and completed = false;
end $$;

-- ── FORM EVOLUTION (Mystery Power): unlock at levels 5/20/40/60/85 ──────────
-- Power = (FormBase×100 + XP/250) × StyleMult × HardWork. HardWork ∈ [0.5, 3.0].
create or replace function public.maybe_unlock_forms() returns int
language plpgsql security definer set search_path = public as $$
declare
  u public.users%rowtype;
  unlock_levels int[] := array[5, 20, 40, 60, 85];
  form_base numeric[] := array[1.0, 1.75, 2.6, 3.8, 5.5];
  form_names text[] := array['SHADOW WAKE','MONARCH''S STEP','BERSERK FANG','MONARCH ECLIPSE','ARISE'];
  weekly int; hw numeric; i int; unlocked int := 0; pow numeric;
begin
  select * into u from public.users where id = auth.uid();
  if not found then return 0; end if;

  select count(*) into weekly from public.workouts
    where user_id = u.id and created_at > now() - interval '7 days';
  hw := greatest(0.5, least(3.0, 1.0 + 0.05 * least(u.streak_days, 20) + 0.08 * least(weekly, 10) - 0.10 * u.missed_days));

  for i in 1..5 loop
    if u.level >= unlock_levels[i] then
      pow := round((form_base[i] * 100 + u.xp / 250.0) * hw, 1);
      insert into public.user_forms (user_id, form_index, name, combat_style, base_power, hard_work_multiplier, computed_power)
      values (u.id, i, form_names[i], 'BALANCED', form_base[i] * 100, hw, pow)
      on conflict (user_id, form_index) do update
        set hard_work_multiplier = excluded.hard_work_multiplier,
            computed_power = excluded.computed_power;
      if not found then unlocked := unlocked + 1; end if;
    end if;
  end loop;
  return unlocked;
end $$;

-- ── TRAINING ARCS: progression-locked, penalties extend the timeline ─────────
create or replace function public.start_arc(p_arc_id text) returns void
language plpgsql security definer set search_path = public as $$
declare a public.training_arcs%rowtype; u public.users%rowtype;
begin
  select * into a from public.training_arcs where id = p_arc_id;
  if not found then raise exception 'no_arc'; end if;
  select * into u from public.users where id = auth.uid();
  if u.level < a.min_level then raise exception 'level_req'; end if;
  if a.unlock_req_arc is not null and not exists(
    select 1 from public.user_arc_progress p where p.user_id = u.id and p.arc_id = a.unlock_req_arc and p.completed
  ) then raise exception 'lock_base'; end if;
  insert into public.user_arc_progress (user_id, arc_id) values (u.id, a.id)
  on conflict do nothing;
  if not found then raise exception 'already_started'; end if;
end $$;

-- ── CLANS: creation gate (level 30+, 500 VC) + join ─────────────────────────
create or replace function public.create_clan(p_name text, p_tag text) returns uuid
language plpgsql security definer set search_path = public as $$
declare u public.users%rowtype; clan_id uuid;
begin
  select * into u from public.users where id = auth.uid();
  if u.level < 30 then raise exception 'level_req'; end if;
  if exists(select 1 from public.clan_members where user_id = u.id) then raise exception 'already_in_clan'; end if;

  perform public.credit_vc(u.id, -500, 'CLAN_CREATE', null); -- throws insufficient_vc

  insert into public.clans (name, tag, guild_master) values (p_name, p_tag, u.id) returning id into clan_id;
  insert into public.clan_members (clan_id, user_id, role) values (clan_id, u.id, 'GUILD_MASTER');
  return clan_id;
end $$;

create or replace function public.join_clan(p_clan_id uuid) returns void
language plpgsql security definer set search_path = public as $$
begin
  insert into public.clan_members (clan_id, user_id, role) values (p_clan_id, auth.uid(), 'MEMBER');
exception when unique_violation then raise exception 'already_in_clan'; end $$;

-- ── TERRITORY: capture + guild claim ─────────────────────────────────────────
create or replace function public.capture_zone(p_zone text, p_reps int default 0, p_duration_sec int default 0) returns void
language plpgsql security definer set search_path = public as $$
declare my_clan uuid; wid bigint;
begin
  -- anti-spoof: max 60 captures/hour/device-user keeps GPS churn honest
  if (select count(*) from public.zone_captures
       where user_id = auth.uid() and created_at > now() - interval '1 hour') >= 60 then
    raise exception 'rate_limited';
  end if;
  select clan_id into my_clan from public.clan_members where user_id = auth.uid();
  insert into public.zone_captures (user_id, clan_id, zone, reps, duration_sec)
    values (auth.uid(), my_clan, p_zone, p_reps, p_duration_sec);
  insert into public.workouts (user_id, kind, reps, duration_sec, xp_earned, zone)
    values (auth.uid(), 'ZONE_CAPTURE', p_reps, p_duration_sec, 10, p_zone) returning id into wid;
  perform public.apply_xp(auth.uid(), 10, p_zone); -- guild tax applies via apply_xp
end $$;

create or replace function public.claim_territory(p_zone text) returns void
language plpgsql security definer set search_path = public as $$
declare my record;
begin
  select m.clan_id, m.role into my from public.clan_members m where m.user_id = auth.uid();
  if my is null then raise exception 'not_member'; end if;
  -- domination requirement: your guild logged ≥5 captures here in 14 days
  if (select count(*) from public.zone_captures z
       where z.zone = p_zone and z.clan_id = my.clan_id and z.created_at > now() - interval '14 days') < 5 then
    raise exception 'domination_req';
  end if;
  insert into public.clan_territories (zone, clan_id, shield_active, claimed_by)
    values (p_zone, my.clan_id, true, auth.uid())
  on conflict (zone) do update
    set clan_id = excluded.clan_id, shield_active = true, claimed_by = excluded.claimed_by, captured_at = now();
end $$;

-- ── TOURNAMENTS: entry fee gate (solo + clan) ────────────────────────────────
-- SOLO   → fee debited from the hunter's personal wallet.
-- CLAN   → fee debited from the GUILD TREASURY (officer-gated), because clan wars
--          are a guild expense — merged from the reference codebase after audit.
create or replace function public.join_tournament(p_tournament_id uuid, p_clan_id uuid default null) returns void
language plpgsql security definer set search_path = public as $$
declare
  t public.tournaments%rowtype;
  me uuid := auth.uid();
  v_treasury bigint;
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

    select treasury_vc into v_treasury from public.clans where id = p_clan_id for update;
    if not found then raise exception 'no_clan'; end if;
    if v_treasury < t.entry_fee_vc then raise exception 'treasury_insufficient'; end if;
    update public.clans set treasury_vc = treasury_vc - t.entry_fee_vc where id = p_clan_id;

    update public.tournaments set prize_pool_vc = prize_pool_vc + t.entry_fee_vc where id = t.id;
    insert into public.tournament_participants (tournament_id, clan_id, entry_fee_paid)
      values (t.id, p_clan_id, t.entry_fee_vc);
  else
    perform public.credit_vc(me, -t.entry_fee_vc, 'ENTRY_FEE', 'tournament:' || t.id); -- throws insufficient_vc
    update public.tournaments set prize_pool_vc = prize_pool_vc + t.entry_fee_vc where id = t.id;
    insert into public.tournament_participants (tournament_id, user_id, entry_fee_paid)
      values (t.id, me, t.entry_fee_vc);
  end if;
exception when unique_violation then
  -- refund on duplicate race conditions, then surface the real error
  if t.type = 'CLAN' then
    update public.clans set treasury_vc = treasury_vc + t.entry_fee_vc where id = p_clan_id;
  else
    perform public.credit_vc(me, t.entry_fee_vc, 'ENTRY_REFUND', 'tournament:' || t.id);
  end if;
  update public.tournaments set prize_pool_vc = greatest(prize_pool_vc - t.entry_fee_vc, 0) where id = t.id;
  raise exception 'already_joined';
end $$;

-- ── BATTLES: create + auto prediction pool + finish/rewards ─────────────────
create or replace function public.create_battle(p_opponent uuid) returns uuid
language plpgsql security definer set search_path = public as $$
declare bid uuid; me uuid := auth.uid();
begin
  if p_opponent = me then raise exception 'no_self_battle'; end if;
  insert into public.battles (player_a, player_b) values (me, p_opponent) returning id into bid;
  insert into public.prediction_pools (battle_id) values (bid); -- pool spawns with the battle
  return bid;
end $$;

-- +150 XP winner / +20 XP loser; pool is settled separately by admins (or edge fn)
create or replace function public.finish_battle(p_battle_id uuid, p_score_a int, p_score_b int) returns void
language plpgsql security definer set search_path = public as $$
declare b public.battles%rowtype; w uuid;
begin
  select * into b from public.battles where id = p_battle_id for update;
  if not found then raise exception 'no_battle'; end if;
  if auth.uid() not in (b.player_a, b.player_b) and not public.is_admin() then raise exception 'forbidden'; end if;
  if b.status = 'FINISHED' then raise exception 'already_finished'; end if;

  w := case when p_score_a >= p_score_b then b.player_a else b.player_b end;
  update public.battles
     set status = 'FINISHED', score_a = p_score_a, score_b = p_score_b,
         winner = w, finished_at = now()
   where id = b.id;

  perform public.apply_xp(b.player_a, case when b.player_a = w then 150 else 20 end, null);
  perform public.apply_xp(b.player_b, case when b.player_b = w then 150 else 20 end, null);
  perform public.credit_vc(case when b.player_a = w then b.player_a else b.player_b end, 50, 'QUEST_REWARD', 'battle_win:' || b.id);
  -- referrer takes a cut of the winner's spoils (5% tier ≈ 2 VC here)
  if (select referred_by from public.users where id = w) is not null then
    perform public.credit_vc((select referred_by from public.users where id = w), 2, 'REFERRAL_CUT', 'battle:' || b.id);
  end if;
end $$;

-- ── PREDICTION ENGINE: place + settle ────────────────────────────────────────
-- Payout = (Bet / WinningSidePool) × (TotalPool × 0.85). 15% house cut.
create or replace function public.place_bet(p_pool_id uuid, p_side text, p_amount bigint) returns void
language plpgsql security definer set search_path = public as $$
declare p public.prediction_pools%rowtype; me uuid := auth.uid();
begin
  if p_side not in ('A','B') then raise exception 'bad_side'; end if;
  if p_amount <= 0 then raise exception 'bad_amount'; end if;
  select * into p from public.prediction_pools where id = p_pool_id for update;
  if not found then raise exception 'no_pool'; end if;
  if p.status <> 'OPEN' then raise exception 'pool_locked'; end if;
  if exists(select 1 from public.battles b where b.id = p.battle_id and me in (b.player_a, b.player_b)) then
    raise exception 'no_self_bet'; -- combatants may not bet on their own war
  end if;

  perform public.credit_vc(me, -p_amount, 'BET', 'pool:' || p.id); -- throws insufficient_vc

  insert into public.prediction_bets (pool_id, user_id, side, amount_vc) values (p.id, me, p_side, p_amount)
  on conflict (pool_id, user_id) do nothing;
  if not found then
    perform public.credit_vc(me, p_amount, 'BET_REFUND', 'pool:' || p.id);
    raise exception 'already_bet';
  end if;

  update public.prediction_pools
     set total_pool_vc = total_pool_vc + p_amount,
         total_a_vc = total_a_vc + case when p_side = 'A' then p_amount else 0 end,
         total_b_vc = total_b_vc + case when p_side = 'B' then p_amount else 0 end
   where id = p.id;
end $$;

create or replace function public.settle_prediction_pool(p_pool_id uuid, p_winner text)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  pool public.prediction_pools%rowtype;
  r record;
  win_total bigint;
  distributable numeric;
  pay bigint;
  winners int := 0; losers int := 0;
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  if p_winner not in ('A','B') then raise exception 'bad_side'; end if;

  select * into pool from public.prediction_pools where id = p_pool_id for update;
  if not found then raise exception 'no_pool'; end if;
  if pool.status in ('SETTLED','REFUNDED') then raise exception 'already_settled'; end if;

  distributable := floor(pool.total_pool_vc * (10000 - pool.platform_cut_bps) / 10000.0);
  win_total := case when p_winner = 'A' then pool.total_a_vc else pool.total_b_vc end;

  if win_total = 0 or pool.total_pool_vc = 0 then
    -- nobody backed the winner (or empty pool): refund everyone
    for r in select * from public.prediction_bets where pool_id = p_pool_id and status = 'OPEN' loop
      perform public.credit_vc(r.user_id, r.amount_vc, 'BET_REFUND', 'pool:' || p_pool_id);
      update public.prediction_bets set status = 'REFUNDED', payout_vc = amount_vc where id = r.id;
    end loop;
    update public.prediction_pools set status = 'REFUNDED', settled_at = now() where id = p_pool_id;
    return jsonb_build_object('mode', 'refund');
  end if;

  for r in select * from public.prediction_bets where pool_id = p_pool_id and status = 'OPEN' loop
    if r.side = p_winner then
      pay := floor((r.amount_vc::numeric / win_total) * distributable);
      perform public.credit_vc(r.user_id, pay, 'BET_PAYOUT', 'pool:' || p_pool_id);
      update public.prediction_bets set status = 'WON', payout_vc = pay where id = r.id;
      -- 5% referral cut of winnings goes to the winner's referrer
      if (select referred_by from public.users where id = r.user_id) is not null then
        perform public.credit_vc((select referred_by from public.users where id = r.user_id),
                                 greatest(floor(pay * 0.05), 0), 'REFERRAL_CUT', 'pool:' || p_pool_id);
      end if;
      winners := winners + 1;
    else
      update public.prediction_bets set status = 'LOST', payout_vc = 0 where id = r.id;
      losers := losers + 1;
    end if;
  end loop;

  update public.prediction_pools
     set status = 'SETTLED', winning_side = p_winner, settled_at = now()
   where id = p_pool_id;

  return jsonb_build_object('mode', 'settled', 'winners', winners, 'losers', losers,
                            'distributable', distributable, 'winning_pool', win_total,
                            'house_cut', pool.total_pool_vc - distributable);
end $$;

-- ── REFERRALS ────────────────────────────────────────────────────────────────
create or replace function public.apply_referral(p_code text) returns void
language plpgsql security definer set search_path = public as $$
declare ref uuid; me uuid := auth.uid();
begin
  if exists(select 1 from public.users where id = me and referred_by is not null) then
    raise exception 'already_referred';
  end if;
  select id into ref from public.users where referral_code = upper(trim(p_code));
  if ref is null then raise exception 'bad_code'; end if;
  if ref = me then raise exception 'no_self_referral'; end if;
  update public.users set referred_by = ref where id = me;
  insert into public.referrals (referrer, referee) values (ref, me);
  perform public.credit_vc(me, 250, 'TOPUP', 'referral_bonus');           -- referee blessing
  perform public.credit_vc(ref, 100, 'REFERRAL_CUT', 'new_hunter:' || me::text);
end $$;

-- ── MANUAL PAYMENTS: review → apply effect ───────────────────────────────────
create or replace function public.review_payment(p_payment_id uuid, p_approve boolean, p_note text default '') returns void
language plpgsql security definer set search_path = public as $$
declare p public.manual_payments%rowtype; ref_cut bigint;
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  select * into p from public.manual_payments where id = p_payment_id for update;
  if not found then raise exception 'no_payment'; end if;
  if p.status <> 'PENDING' then raise exception 'already_reviewed'; end if;

  update public.manual_payments
     set status = case when p_approve then 'APPROVED' else 'REJECTED' end,
         reviewed_by = auth.uid(), reviewed_at = now(), review_note = p_note
   where id = p.id;

  if p_approve then
    case p.item_type
      when 'BLACK_ROOM_PASS' then
        update public.users set black_room_until = now() + interval '30 days' where id = p.user_id;
        -- 10% lifetime cut to referrer on Black Room unlocks
        ref_cut := greatest(floor(p.amount_inr * 0.10), 0);
      when 'VC_TOPUP' then
        perform public.credit_vc(p.user_id, floor(p.amount_inr * 10)::bigint, 'TOPUP', 'utr:' || p.upi_utr);
        ref_cut := greatest(floor(p.amount_inr * 0.10), 0);
      else
        ref_cut := greatest(floor(p.amount_inr * 0.10), 0); -- merch 5-10 band: 10 default
    end case;
    if (select referred_by from public.users where id = p.user_id) is not null and ref_cut > 0 then
      perform public.credit_vc((select referred_by from public.users where id = p.user_id),
                               ref_cut, 'REFERRAL_CUT', 'payment:' || p.id);
    end if;
  end if;
end $$;

-- ── ADMIN utilities ──────────────────────────────────────────────────────────
create or replace function public.admin_adjust_vc(p_target uuid, p_amount bigint, p_note text) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  perform public.credit_vc(p_target, p_amount, 'ADMIN_ADJUST', p_note);
  insert into public.admin_audit_log (actor, action, payload)
    values (auth.uid(), 'ADJUST_VC', jsonb_build_object('target', p_target, 'amount', p_amount, 'note', p_note));
end $$;

create or replace function public.admin_overview() returns jsonb
language sql stable security definer set search_path = public as $$
  select case when public.is_admin() then jsonb_build_object(
    'users', (select count(*) from public.users),
    'clans', (select count(*) from public.clans),
    'pending_payments', (select count(*) from public.manual_payments where status = 'PENDING'),
    'open_tournaments', (select count(*) from public.tournaments where status = 'OPEN'),
    'pool_vc', (select coalesce(sum(total_pool_vc),0) from public.prediction_pools where status <> 'SETTLED')
  ) else '{}'::jsonb end
$$;

-- ── 10-MINUTE WORKOUT MEDIA AUTO-PURGE ───────────────────────────────────────
-- Hard-deletes DB rows AND their storage objects EXACTLY 10 min after created_at.
create or replace function public.purge_workout_media() returns int
language plpgsql security definer set search_path = public, storage as $$
declare n int;
begin
  with dead as (
    delete from public.workout_media
     where created_at < now() - interval '10 minutes'
    returning storage_path
  )
  delete from storage.objects o
   using dead d
   where o.bucket_id = 'workout-verification'
     and o.name = d.storage_path;
  get diagnostics n = row_count;
  return n;
end $$;

-- ── CPA postback endpoint helper (called by offerwall postback via service) ──
create or replace function public.cpa_postback(p_user uuid, p_vc bigint, p_external_ref text) returns void
language plpgsql security definer set search_path = public as $$
begin
  if (auth.jwt() ->> 'role') <> 'service_role' then raise exception 'forbidden'; end if;
  if exists(select 1 from public.vc_transactions where reason = 'CPA_POSTBACK' and reference = p_external_ref) then
    return; -- idempotent: no double-crediting on postback retries
  end if;
  perform public.credit_vc(p_user, p_vc, 'CPA_POSTBACK', p_external_ref);
  if (select referred_by from public.users where id = p_user) is not null then
    perform public.credit_vc((select referred_by from public.users where id = p_user),
                             greatest(floor(p_vc * 0.02), 0), 'REFERRAL_CUT', 'cpa:' || p_external_ref);
  end if;
end $$;
