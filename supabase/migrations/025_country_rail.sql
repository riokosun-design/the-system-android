-- ═══════════════════════════════════════════════════════════════════════════
-- MIGRATION 025 · COUNTRY RAIL (STEP 10)
--
-- Privacy law: the country is USER-SELECTED, full stop. Nothing is derived
-- from IP, GPS, SIM or locale — those never touch this column (and no IP is
-- stored anywhere in the product for this feature). The user's own choice
-- wins; unset (NULL) honestly falls back to the India rail (₹), matching the
-- India-first launch audience.
--
-- Two-letter free-form codes (CHECK ^[A-Z]{2}$) — the client ships a modest
-- picker; the server stays representation-agnostic. Currency derivation is a
-- server-side display rule (UPI rail vs USD rail), not stored state.
-- ═══════════════════════════════════════════════════════════════════════════

alter table public.users add column if not exists country text;
do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'users_country_check') then
    alter table public.users add constraint users_country_check
      check (country is null or country ~ '^[A-Z]{2}$');
  end if;
end $$;

-- eligibility now honors: explicit arg > user's chosen country > India fallback
create or replace function public.black_room_eligibility(p_country text default null)
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
  v_seed numeric; v_country text;
  c_forms boolean; c_level boolean; c_missed boolean; c_muscle boolean; c_special boolean;
  v_reasons jsonb := '[]'::jsonb;
begin
  select * into u from public.users where id = auth.uid();
  if not found then return '{}'::jsonb; end if;

  v_country := upper(coalesce(p_country, u.country, 'IN'));

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

  -- SEED LAW (mig 024): stable personal seed from the user id
  v_seed := ((('x' || substr(md5(u.id::text), 1, 8))::bit(32)::bigint) % 1000) / 1000.0;

  v_upi_idx := greatest(0, least(4, round((v_consistency * 0.6 + v_seed * 0.4) * 4)::int));
  v_upi := (array[149,299,449,549,699])[v_upi_idx + 1];
  v_usd := (array[10,15,20,25,30])[v_upi_idx + 1];

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
    'upi', case when v_country in ('IN','PK','BD','NP','LK') then v_upi end,
    'usd', v_usd,
    'potential_index', v_upi_idx,
    'country', v_country,
    'reasons', v_reasons);
end $function$;
