-- ═══════════════════════════════════════════════════════════════════════════
-- 016 · AI ENGINE — feature flags, model catalog, routine store, assistant
-- notes, and the server-verified AI quest adoption path.
--
-- LAWS honored here:
--  · AI proposes, the SERVER validates and applies (Source of Truth stays SQL)
--  · one AI bonus block per day; only proof-verifiable kinds; clamped volumes
--  · routines & notes are the ONLY AI writes — and they are user-owned rows
--  · local LLM defaults OFF (local_ai_enabled:false) until converted artifacts
--    are integrity-verified on-device; deterministic brains run everything today
-- ═══════════════════════════════════════════════════════════════════════════

-- ── A. FEATURE FLAGS + MODEL CATALOG (remote-tunable, no app release) ────────
update public.engine_config set value = value || '{
  "ai": {
    "local_ai_enabled": false,
    "quest_ai_enabled": true,
    "nutrition_ai_enabled": true,
    "assistant_enabled": true,
    "routine_ai_enabled": true,
    "max_context_tokens": 512,
    "max_output_tokens": 220,
    "inference_timeout_ms": 12000,
    "unload_after_ms": 30000,
    "min_free_storage_mb": 400,
    "nutrition_daily_calorie_floor": 1600,
    "models": [
      {"id":"smollm2-135m-instruct","params_m":135,"quant":"INT8","size_mb":140,"ram_mb":350,"ctx":1024,"tier":"TINY","backend":"MEDIAPIPE_TASK","version":"0","url":null,"sha256":null},
      {"id":"smollm2-360m-instruct","params_m":360,"quant":"INT8","size_mb":370,"ram_mb":700,"ctx":1536,"tier":"MID","backend":"MEDIAPIPE_TASK","version":"0","url":null,"sha256":null},
      {"id":"gemma-3-1b-it","params_m":1000,"quant":"INT8","size_mb":1300,"ram_mb":2100,"ctx":2048,"tier":"PLUS","backend":"MEDIAPIPE_TASK","version":"0","url":null,"sha256":null}
    ]
  }
}'::jsonb where key = 'engine';

-- ── B. DAILY ROUTINE — one draft/confirmed routine per hunter per day ────────
create table if not exists public.daily_routines (
  user_id      uuid not null references auth.users(id) on delete cascade,
  routine_date date not null default current_date,
  items        jsonb not null default '[]'::jsonb,
  status       text not null default 'DRAFT',        -- DRAFT | CONFIRMED
  source       text not null default 'AI',           -- AI | MANUAL
  updated_at   timestamptz not null default now(),
  primary key (user_id, routine_date)
);
alter table public.daily_routines enable row level security;
create policy routines_rw_own on public.daily_routines
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create or replace function public.touch_routine_updated_at() returns trigger
language plpgsql as $$
begin new.updated_at := now(); return new; end $$;
drop trigger if exists trg_routines_touch on public.daily_routines;
create trigger trg_routines_touch before update on public.daily_routines
  for each row execute function public.touch_routine_updated_at();

-- ── C. ASSISTANT MEMORY — ONLY explicitly-saved notes (never raw chat logs) ──
create table if not exists public.ai_notes (
  id         bigint generated always as identity primary key,
  user_id    uuid not null references auth.users(id) on delete cascade,
  note       text not null check (char_length(note) between 2 and 500),
  created_at timestamptz not null default now()
);
alter table public.ai_notes enable row level security;
create policy ai_notes_rw_own on public.ai_notes
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- ── D. ADOPT AI QUEST — the only path by which an AI proposal becomes real ───
-- Client-side validation is UX. THIS function is the law: whitelist of kinds
-- the verified proof machinery (CAMERA v4 / STEPS service / TIMER clock) can
-- actually witness, clamped volumes, one AI block per day, no same-kind dupes.
create or replace function public.adopt_ai_quest(
  p_title text,
  p_exercise_kind text,
  p_target_value int,
  p_target_unit text,
  p_est_duration_sec int,
  p_difficulty text,
  p_verification text
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_kind  text := upper(trim(p_exercise_kind));
  v_verif text;
  v_unit  text;
  v_target int;
  v_xp    int;
  v_seq   int;
  v_dur   int;
  v_diff  text := upper(trim(coalesce(p_difficulty, 'EASY')));
  v_title text;
  q public.daily_quests%rowtype;
begin
  if auth.uid() is null then raise exception 'auth'; end if;

  -- contract: exactly one AI-adopted bonus block per day
  if exists (select 1 from public.daily_quests
              where user_id = auth.uid() and quest_date = current_date
                and source = 'AI_ASSIST') then
    raise exception 'ai_quest_limit';
  end if;

  -- whitelist: ONLY kinds the proof machinery can physically verify
  v_verif := case v_kind
               when 'PUSH'  then 'CAMERA'
               when 'SQUAT' then 'CAMERA'
               when 'RUN'   then 'STEPS'
               when 'WALK'  then 'STEPS'
               when 'PLANK' then 'TIMER'
               else null end;
  if v_verif is null then raise exception 'ai_quest_kind'; end if;

  v_unit := case v_kind
              when 'RUN' then 'METERS'
              when 'WALK' then 'METERS'
              when 'PLANK' then 'SECONDS'
              else 'REPS' end;

  -- clamps: 5..300 reps · 100..5000 m · 15..600 s · 60..2400 s duration
  v_target := case v_unit
                when 'METERS'  then least(greatest(coalesce(p_target_value, 1000), 100), 5000)
                when 'SECONDS' then least(greatest(coalesce(p_target_value, 60), 15), 600)
                else least(greatest(coalesce(p_target_value, 20), 5), 300) end;
  v_dur := least(greatest(coalesce(p_est_duration_sec, 300), 60), 2400);
  if v_diff not in ('EASY', 'MEDIUM', 'HARD') then v_diff := 'EASY'; end if;

  -- no duplicate active kind today (any source) — variation over repetition
  if exists (select 1 from public.daily_quests
              where user_id = auth.uid() and quest_date = current_date
                and exercise_kind = v_kind
                and status not in ('MISSED', 'PENALIZED')) then
    raise exception 'ai_quest_dup';
  end if;

  -- deterministic XP — bounded, never AI-authored
  v_xp := least(60, 10 + v_target / (case v_unit when 'METERS' then 100 when 'SECONDS' then 10 else 2 end));

  select coalesce(max(seq), 0) + 1 into v_seq
    from public.daily_quests
   where user_id = auth.uid() and quest_date = current_date;

  v_title := left('AI // ' || trim(coalesce(p_title, '')), 60);
  if v_title = 'AI //' or v_title = 'AI // ' then v_title := 'AI // ' || v_kind || ' PROTOCOL'; end if;

  insert into public.daily_quests
    (user_id, quest_date, title, target_value, xp_reward, source, seq,
     exercise_kind, target_unit, est_duration_sec, rest_sec, difficulty, verification, status)
  values (auth.uid(), current_date, v_title, v_target, v_xp, 'AI_ASSIST', v_seq,
          v_kind, v_unit, v_dur, 90, v_diff, v_verif, 'READY')
  returning * into q;

  return to_jsonb(q);
end $$;
grant execute on function public.adopt_ai_quest(text, text, int, text, int, text, text) to authenticated;
