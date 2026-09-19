-- ═══════════════════════════════════════════════════════════════════════════
-- 014 · REFINEMENT — real-world course titles + verified performance bests
-- UI task 0.6.0: AI-style course names replaced with short real-world training
-- names (slugs untouched → bundled art, quests and schedules never break), and
-- the new RANK screen reads verified bests from immutable workout proofs.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── MUSCLE course titles (slugs / ids / quest links unchanged) ─────────────
update public.courses set title = 'Athletic Bodyweight'        where slug = 'adaptive-fighter-physique';
update public.courses set title = 'Calisthenics Development'   where slug = 'calisthenics-foundation';
update public.courses set title = 'Athletic Strength'          where slug = 'athletic-strength-build';
update public.courses set title = 'Lean Athletic Build'        where slug = 'lean-muscle-development';
update public.courses set title = 'Home Athlete'               where slug = 'home-strength-system';
update public.courses set title = 'Functional Strength'        where slug = 'functional-muscle-build';
update public.courses set title = 'Full Body Strength'         where slug = 'full-body-strength-cycle';
update public.courses set title = 'Strength Foundation'        where slug = 'beginner-strength-foundation';

-- ── VERIFIED BESTS — the RANK screen's honest performance feed ─────────────
-- Every number comes from immutable `workouts` proof rows (camera / radar /
-- step-sensor verified) plus finished battles. Nothing is client-computed.
create or replace function public.verified_bests()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid      uuid := auth.uid();
  v_push     int;
  v_squat    int;
  v_run      int;
  v_sessions int;
  v_wins     int;
begin
  if v_uid is null then
    raise exception 'not_authenticated';
  end if;

  select coalesce(max(reps), 0)  into v_push   from workouts where user_id = v_uid and kind = 'QUEST_PUSH';
  select coalesce(max(reps), 0)  into v_squat  from workouts where user_id = v_uid and kind = 'QUEST_SQUAT';
  select coalesce(max(reps), 0)  into v_run    from workouts where user_id = v_uid and kind = 'QUEST_RUN';
  select count(*)                into v_sessions from workouts where user_id = v_uid;
  select count(*)                into v_wins   from battles  where winner = v_uid and status = 'FINISHED';

  return jsonb_build_object(
    'push_reps',   v_push,
    'squat_reps',  v_squat,
    'run_meters',  v_run,
    'sessions',    v_sessions,
    'battle_wins', v_wins
  );
end;
$$;

grant execute on function public.verified_bests() to authenticated;
