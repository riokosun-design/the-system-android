-- 038 — STRICT CPA-ONLY VC ECONOMY (master directive §1)
-- Every free VC mint is EXECUTED. Positive grants through credit_vc are now
-- whitelisted to CPA postbacks (+ their 2% convoy cut), tournament refunds &
-- prizes, admin adjustments and the offline TOPUP rail. Quests, training,
-- battles, sync and referrals pay XP ONLY. History is never rewritten:
-- archived ledger rows stay exactly as they happened.

-- ── 1. AWAKENING: initial_vc = 0 (signup bonus executed) ────────────────────
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
declare total int;
begin
  -- serialize concurrent first signups so exactly three admins ever get seeded
  perform pg_advisory_xact_lock(77001);
  insert into public.users (id, username, display_name, avatar_url)
  values (
    new.id,
    'hunter_' || lower(substr(replace(new.id::text,'-',''), 1, 10)),
    coalesce(new.raw_user_meta_data ->> 'full_name', new.raw_user_meta_data ->> 'name'),
    new.raw_user_meta_data ->> 'avatar_url'
  );
  select count(*) into total from public.users;
  if total <= 3 then
    update public.users set role = 'SUPER_ADMIN' where id = new.id;
  end if;
  -- 038 §1: the +100 'SIGNUP_BONUS' mint is dead. VC is EARNED (CPA postbacks)
  -- or WON (tournament stakes) — never gifted at awakening.
  return new;
end $$;

-- ── 2. BATTLES: victory pays XP, not minted VC ──────────────────────────────
create or replace function public.finish_battle(p_battle_id uuid, p_score_a int, p_score_b int) returns void
language plpgsql security definer set search_path = public as $$
declare b public.battles%rowtype; w uuid;
begin
  select * into b from public.battles where id = p_battle_id for update;
  if not found then raise exception 'no_battle'; end if;
  if auth.uid() not in (b.player_a, b.player_b) and not public.is_admin() then raise exception 'forbidden'; end if;
  if b.status = 'FINISHED' then raise exception 'already_finished'; end if;
  if b.status not in ('LIVE','SCHEDULED','LOBBY') then raise exception 'not_live'; end if;
  w := case when p_score_a >= p_score_b then b.player_a else b.player_b end;
  update public.battles
     set status = 'FINISHED', score_a = p_score_a, score_b = p_score_b, winner = w, finished_at = now()
   where id = b.id;
  perform public.apply_xp(b.player_a, case when b.player_a = w then 150 else 20 end, null);
  perform public.apply_xp(b.player_b, case when b.player_b = w then 150 else 20 end, null);
  -- 038 §1: +50 'QUEST_REWARD' battle-win VC and the free 2-VC convoy mint are
  -- executed. Tournament-stake settlements continue through their own rail.
end $$;

-- ── 3. BODY × MIND SYNC: +25 XP only, VC mint executed ──────────────────────
CREATE OR REPLACE FUNCTION public.claim_synergy()
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_user uuid := auth.uid();
    v_body boolean;
    v_mind boolean;
BEGIN
    IF v_user IS NULL THEN RAISE EXCEPTION 'auth required'; END IF;
    SELECT EXISTS (SELECT 1 FROM public.daily_quests
                   WHERE user_id = v_user AND quest_date = current_date AND completed) INTO v_body;
    SELECT EXISTS (SELECT 1 FROM public.mental_quest_claims
                   WHERE user_id = v_user AND quest_date = current_date) INTO v_mind;
    IF NOT v_body THEN RETURN 'BODY PENDING — finish a physical quest first'; END IF;
    IF NOT v_mind THEN RETURN 'MIND PENDING — finish today''s mental quest first'; END IF;
    INSERT INTO public.synergy_claims (user_id) VALUES (v_user) ON CONFLICT DO NOTHING;
    IF NOT FOUND THEN RETURN 'SYNC ALREADY CLAIMED TODAY'; END IF;
    PERFORM public.apply_xp(v_user, 25, null);
    -- 038 §1: +5 'SYNERGY_BONUS' executed. XP only.
    RETURN 'SYNC COMPLETE — +25 XP · VC IS BOUNTY-EARNED ONLY';
END $$;

-- ── 4. REFERRALS: XP blessings, VC blessings executed ───────────────────────
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
  -- 038 §1: 250 'TOPUP' + 100 'REFERRAL_CUT' free VC executed → XP blessings.
  perform public.apply_xp(me, 150, null);
  perform public.apply_xp(ref, 100, null);
end $$;

-- ── 5. CPA POSTBACK: convoy cut gets its own whitelisted reason ─────────────
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
                             greatest(floor(p_vc * 0.02), 0), 'CPA_REFERRAL_CUT', 'cpa:' || p_external_ref);
  end if;
end $$;

drop index if exists public.vc_tx_referral_ref_uq;
create unique index if not exists vc_tx_cpa_referral_ref_uq
  on public.vc_transactions (reference)
  where reason = 'CPA_REFERRAL_CUT' and reference is not null;

-- ── 6. THE WHITELIST WALL: credit_vc grants are CPA/tournament-family ONLY ──
CREATE OR REPLACE FUNCTION public.credit_vc(p_user uuid, p_amount bigint, p_reason text, p_ref text DEFAULT NULL::text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare v_inserted bigint;
begin
  -- 038 §1 GRANT LAW: a POSITIVE credit must come from the bounty/tournament
  -- family. Everything else raises — quests/social/sync never mint VC again.
  if p_amount > 0 and p_reason not in
     ('CPA_POSTBACK','CPA_REFERRAL_CUT','ENTRY_REFUND','TOURNAMENT_PRIZE','TOPUP','ADMIN_ADJUST') then
     raise exception 'vc_grant_forbidden[%]', p_reason;
  end if;
  -- ledger-row FIRST with ON CONFLICT: the tx_id uniqueness wall makes replayed
  -- postbacks exactly-once. Any exception aborts the tx, ledger row included.
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

update public.engine_config
   set value = coalesce(value, '{}'::jsonb)
       || '{"vc_economy": "CPA_ONLY", "free_vc": "EXECUTED_038", "vc_utility": "TOURNAMENT_STAKES_AND_PRODUCT_DISCOUNTS"}'::jsonb
 where key = 'monetization';
