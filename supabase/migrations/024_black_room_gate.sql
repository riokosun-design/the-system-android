-- ═══════════════════════════════════════════════════════════════════════════
-- MIGRATION 024 · BLACK ROOM GATE v2 (STEP 9)
--
-- Same gate, three upgrades per spec:
--  1. BAND LAW — pricing bands widen to spec: India ₹149–₹699, Intl $10–$30.
--  2. SEED LAW — the price is deterministic + EXPLAINABLE + stable per user:
--     60% measured consistency (streak/wars/win-rate, exactly as before) +
--     40% a stable personal seed derived from md5(user_id). Same metrics →
--     same price for the same user, every time; no RNG anywhere.
--  3. REASON LAW — the response carries a human-readable reasons array: every
--     gate with its measured value, and both pricing factors. The client only
--     renders it; the server owns every number.
-- Eligibility criteria are UNCHANGED from the live function (preserve law).
-- ═══════════════════════════════════════════════════════════════════════════

create or replace function public.black_room_eligibility(p_country text default 'IN'::text)
returns jsonb
language plpgsql
stable
security definer
set search_path to 'public'
as $function$
declare
  u public.users%rowtype;
  v_forms int; v_muscle_pct numeric; v_specials int;
  v_upi numeric; v_usd numeric; v_upi_idx int; v_consistency numeric;
  v_win_rate int; v_wars int; stats jsonb;
  v_seed numeric;
  c_forms boolean; c_level boolean; c_missed boolean; c_muscle boolean; c_special boolean;
  v_reasons jsonb := '[]'::jsonb;
begin
  select * into u from public.users where id = auth.uid();
  if not found then return '{}'::jsonb; end if;

  select count(*) into v_forms from public.user_forms where user_id = u.id;
  select coalesce(max(uc.progress_percent),0) into v_muscle_pct
    from public.user_courses uc join public.courses c on c.id = uc.course_id
   where uc.user_id = u.id and c.type = 'MUSCLE';
  select count(*) into v_specials from public.user_courses uc
    join public.courses c on c.id = uc.course_id
   where uc.user_id = u.id and c.type = 'SPECIAL' and uc.status = 'COMPLETED';

  c_forms   := v_forms >= 1;
  c_level   := u.level >= 50;
  c_missed  := u.missed_days >= 5;  -- history/depth gate (unchanged law)
  c_muscle  := v_muscle_pct >= 50;
  c_special := v_specials >= 3;

  stats := public.hunter_battle_stats(u.id);
  v_win_rate := coalesce((stats->>'win_rate')::int, 0);
  v_wars := coalesce((stats->>'total')::int, 0);
  v_consistency := least(1.0, u.streak_days / 30.0) * 0.6
                 + least(1.0, v_wars / 20.0) * 0.2
                 + (v_win_rate / 100.0) * 0.2;

  -- SEED LAW: stable personal seed from the user id (md5 → unsigned 32-bit
  -- → [0,1)). Deterministic across sessions, engines and decades.
  v_seed := ((('x' || substr(md5(u.id::text), 1, 8))::bit(32)::bigint) % 1000) / 1000.0;

  v_upi_idx := greatest(0, least(4, round((v_consistency * 0.6 + v_seed * 0.4) * 4)::int));
  v_upi := (array[149,299,449,549,699])[v_upi_idx + 1];
  v_usd := (array[10,15,20,25,30])[v_upi_idx + 1];

  -- REASON LAW
  v_reasons := v_reasons || jsonb_build_array(
    case when c_forms then 'FORM UNLOCKED — MET' else 'FORM UNLOCKED — MISSING (unlock at least 1)' end,
    case when c_level then 'LEVEL 50 — MET (' || u.level || ')' else 'LEVEL 50 — MISSING (' || u.level || ' / 50)' end,
    case when c_missed then 'DEPTH LEDGER — MET (' || u.missed_days || ' missed days)' else 'DEPTH LEDGER — MISSING (' || u.missed_days || ' / 5 historical events)' end,
    case when c_muscle then 'PRIMARY COURSE ≥50% — MET (' || v_muscle_pct || '%)' else 'PRIMARY COURSE ≥50% — MISSING (' || v_muscle_pct || '% / 50%)' end,
    case when c_special then 'THREE TRACKS COMPLETED — MET (' || v_specials || ')' else 'THREE TRACKS COMPLETED — MISSING (' || v_specials || ' / 3)' end,
    'PRICE FACTOR · consistency ' || round(v_consistency, 2) || ' ×60%',
    'PRICE FACTOR · personal seed ' || round(v_seed, 2) || ' ×40% → index ' || v_upi_idx || '/4'
  );

  return jsonb_build_object(
    'eligible', c_forms and c_level and c_missed and c_muscle and c_special,
    'checks', jsonb_build_object(
      'form_unlocked', c_forms, 'level_50', c_level,
      'penalty_events_min5', c_missed, 'muscle_progress_ok', c_muscle,
      'specials_completed_ok', c_special,
      'forms', v_forms, 'muscle_percent', v_muscle_pct, 'specials_completed', v_specials,
      'missed_days', u.missed_days),
    'upi', case when upper(coalesce(p_country,'IN')) in ('IN','PK','BD','NP','LK') then v_upi end,
    'usd', v_usd,
    'potential_index', v_upi_idx,
    'reasons', v_reasons);
end $function$;
