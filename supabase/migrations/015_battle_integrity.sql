-- ═══════════════════════════════════════════════════════════════════════════
-- 015 · BATTLE INTEGRITY + DATA ENGINE + ENGINE REMOTE CONFIG
-- CV-BATTLE-ARCHITECTURE §8 (signed events, server plausibility, hash chain),
-- §11 (feature corpus for the TCN), §13 (tunable bias knobs, no app release).
-- ═══════════════════════════════════════════════════════════════════════════

-- ── ENGINE REMOTE CONFIG — behavior knobs the server can flip without an app release
create table if not exists public.engine_config (
  key        text primary key,
  value      jsonb not null,
  updated_at timestamptz not null default now()
);
insert into public.engine_config (key, value) values
  ('engine', '{
     "tcn_decide": false,
     "min_ranked_engine": "0.8.0",
     "flash_liveness_required": false,
     "flash_min_contrast": 1.35,
     "depth_floor_strict": 0.28,
     "depth_floor_standard": 0.24,
     "harvest_enabled": true
   }'::jsonb)
on conflict (key) do nothing;

create or replace function public.get_engine_config() returns jsonb
language sql stable security definer set search_path = public as $$
  select value from public.engine_config where key = 'engine';
$$;
grant execute on function public.get_engine_config() to authenticated;

-- ── BATTLE NONCES — one device-session key base per player per battle (§8)
create table if not exists public.battle_nonces (
  battle_id  uuid not null references public.battles(id) on delete cascade,
  user_id    uuid not null references auth.users(id) on delete cascade,
  nonce      text not null,
  created_at timestamptz not null default now(),
  primary key (battle_id, user_id)
);
alter table public.battle_nonces enable row level security;
create policy battle_nonces_read_own on public.battle_nonces
  for select to authenticated using (auth.uid() = user_id);
-- no direct insert/update: minted through the RPC only

create or replace function public.mint_battle_nonce(p_battle uuid) returns text
language plpgsql security definer set search_path = public as $$
declare b public.battles%rowtype; n text;
begin
  select * into b from public.battles where id = p_battle;
  if not found then raise exception 'no_battle'; end if;
  if auth.uid() not in (b.player_a, b.player_b) then raise exception 'forbidden'; end if;
  if b.status not in ('LOBBY','LIVE') then raise exception 'battle_closed'; end if;
  insert into public.battle_nonces (battle_id, user_id, nonce)
  values (b.id, auth.uid(), encode(gen_random_bytes(24), 'hex'))
  on conflict (battle_id, user_id) do nothing;
  select nonce into n from public.battle_nonces where battle_id = b.id and user_id = auth.uid();
  return n;
end $$;
grant execute on function public.mint_battle_nonce(uuid) to authenticated;

-- ── SIGNED REP EVENTS (§8) — no video ever; the rep trajectory IS the evidence
create table if not exists public.battle_rep_events (
  id          bigint generated always as identity primary key,
  battle_id   uuid not null references public.battles(id) on delete cascade,
  user_id     uuid not null references auth.users(id) on delete cascade,
  seq_no      int  not null,
  t_device_ms bigint not null,
  quality     jsonb not null,    -- {theta_min, line_dev, shoulder_drop, tempo_ms}
  confidence  real not null,
  cheat_score real not null,
  engine_version text not null,
  prev_hash   text not null,
  hash        text not null,
  sig         text not null,
  flags       text[] not null default '{}',
  created_at  timestamptz not null default now(),
  unique (battle_id, user_id, seq_no)
);
alter table public.battle_rep_events enable row level security;
create policy battle_rep_events_read_participants on public.battle_rep_events
  for select to authenticated using (
    public.is_admin() or exists (
      select 1 from public.battles b
      where b.id = battle_id and auth.uid() in (b.player_a, b.player_b)
    )
  );
-- inserts happen exclusively through submit_rep_events (verifying RPC)

-- canonical(u, e) = battle|user|seq|t|theta|line|drop|tempo|conf|cheat|engine|prev_hash
-- key             = sha256(nonce || '|' || user_id)  — shared secret client⇄server
create or replace function public.submit_rep_events(p_battle uuid, p_events jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  b public.battles%rowtype;
  n_row public.battle_nonces%rowtype;
  e jsonb;
  canon text;
  key_b bytea;
  expected_hash text;
  expected_sig text;
  fl text[];
  accepted int := 0;
  flagged int := 0;
  prev_t bigint := null;
  prev_hash_db text := null;
begin
  select * into b from public.battles where id = p_battle;
  if not found then raise exception 'no_battle'; end if;
  if auth.uid() not in (b.player_a, b.player_b) then raise exception 'forbidden'; end if;
  select * into n_row from public.battle_nonces where battle_id = b.id and user_id = auth.uid();
  if not found then raise exception 'no_nonce — call mint_battle_nonce first'; end if;
  key_b := digest(n_row.nonce || '|' || auth.uid()::text, 'sha256');

  select e.t_device_ms, e.hash into prev_t, prev_hash_db
    from public.battle_rep_events e
   where e.battle_id = b.id and e.user_id = auth.uid()
   order by e.seq_no desc limit 1;

  for e in select * from jsonb_array_elements(p_events) loop
    fl := '{}';
    canon := b.id::text || '|' || auth.uid()::text || '|'
      || (e->>'seq_no') || '|' || (e->>'t_device_ms') || '|'
      || (e->'quality'->>'theta_min') || '|' || (e->'quality'->>'line_dev') || '|'
      || (e->'quality'->>'shoulder_drop') || '|' || (e->'quality'->>'tempo_ms') || '|'
      || (e->>'confidence') || '|' || (e->>'cheat_score') || '|'
      || (e->>'engine_version') || '|' || (e->>'prev_hash');
    expected_hash := encode(digest(canon, 'sha256'), 'hex');
    expected_sig  := encode(hmac(expected_hash, key_b, 'sha256'), 'hex');

    if e->>'hash' is distinct from expected_hash then fl := fl || 'bad_hash'; end if;
    if e->>'sig'  is distinct from expected_sig  then fl := fl || 'bad_sig';  end if;
    if prev_hash_db is not null and e->>'prev_hash' is distinct from prev_hash_db
      then fl := fl || 'chain_break'; end if;
    if prev_t is not null and (e->>'t_device_ms')::bigint - prev_t < 500
      then fl := fl || 'physics_gap'; end if;
    if ((e->'quality'->>'tempo_ms')::int) < 500
      then fl := fl || 'tempo_short'; end if;

    insert into public.battle_rep_events
      (battle_id, user_id, seq_no, t_device_ms, quality, confidence, cheat_score,
       engine_version, prev_hash, hash, sig, flags)
    values
      (b.id, auth.uid(), (e->>'seq_no')::int, (e->>'t_device_ms')::bigint,
       e->'quality', (e->>'confidence')::real, (e->>'cheat_score')::real,
       e->>'engine_version', e->>'prev_hash', e->>'hash', e->>'sig', fl)
    on conflict (battle_id, user_id, seq_no) do nothing;

    if found then
      prev_t := (e->>'t_device_ms')::bigint;
      prev_hash_db := e->>'hash';
      if array_length(fl, 1) is null then accepted := accepted + 1; else flagged := flagged + 1; end if;
    end if;
  end loop;

  return jsonb_build_object('accepted', accepted, 'flagged', flagged);
end $$;
grant execute on function public.submit_rep_events(uuid, jsonb) to authenticated;

-- ── SERVER PLAUSIBILITY v2 (§8 referee): physics + chain + distribution verdicts
create or replace function public.battle_plausibility(p_battle uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  b public.battles%rowtype;
  r record;
  out jsonb := '{}'::jsonb;
  per jsonb;
  hard_flags int;
  soft_flags int;
  total int;
  avg_tempo real;
  span_s real;
  max_rate real;
  verdict text;
begin
  select * into b from public.battles where id = p_battle;
  if not found then raise exception 'no_battle'; end if;
  if auth.uid() not in (b.player_a, b.player_b) and not public.is_admin() then
    raise exception 'forbidden';
  end if;

  for r in select player_id from (values (b.player_a), (b.player_b)) as v(player_id) loop
    select count(*),
           count(*) filter (where flags && array['bad_sig','bad_hash','chain_break']),
           count(*) filter (where flags && array['physics_gap','tempo_short']),
           avg((quality->>'tempo_ms')::real),
           (max(t_device_ms) - min(t_device_ms)) / 1000.0
      into total, hard_flags, soft_flags, avg_tempo, span_s
      from public.battle_rep_events
     where battle_id = b.id and user_id = r.player_id;

    max_rate := case when coalesce(span_s, 0) > 0 then total / span_s else 0 end;
    verdict := case
      when total = 0 then 'NO_EVIDENCE'
      when hard_flags > 0 then 'INVALID'
      when max_rate > 2.0 then 'SUSPICIOUS_RATE'
      when soft_flags::real / greatest(total, 1) > 0.25 then 'SUSPICIOUS_TEMPO'
      else 'CLEAN' end;

    per := jsonb_build_object(
      'events', total, 'hard_flags', hard_flags, 'soft_flags', soft_flags,
      'avg_tempo_ms', round(coalesce(avg_tempo, 0)), 'rep_rate_per_s', round(max_rate::numeric, 2),
      'verdict', verdict);
    out := out || jsonb_build_object(r.player_id::text, per);
  end loop;

  update public.battles set plausibility = out where id = b.id;
  return out;
end $$;
grant execute on function public.battle_plausibility(uuid) to authenticated;

alter table public.battles add column if not exists plausibility jsonb;

-- ═══════════════════════════════════════════════════════════════════════════
-- PHASE 2 · FEATURE CORPUS (§11) — 12-dim windows + verdicts, NEVER pixels.
-- Hard negatives harvested at parity with commits (fraud over-sampled §11).
-- ═══════════════════════════════════════════════════════════════════════════
alter table public.users add column if not exists harvest_consent boolean not null default false;

create or replace function public.set_harvest_consent(p_consent boolean) returns void
language sql security definer set search_path = public as $$
  update public.users set harvest_consent = p_consent where id = auth.uid();
$$;
grant execute on function public.set_harvest_consent(boolean) to authenticated;

create or replace function public.harvest_consent() returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce((select harvest_consent from public.users where id = auth.uid()), false);
$$;
grant execute on function public.harvest_consent() to authenticated;

create table if not exists public.rep_feature_sequences (
  id             bigint generated always as identity primary key,
  user_id        uuid not null references auth.users(id) on delete cascade,
  kind           text not null,            -- QUEST_PUSH / BATTLE_PUSH / QUEST_SQUAT
  engine_version text not null,
  verdict        text not null,            -- COMMIT / REJECT_DEPTH / REJECT_FORM / ...
  features       jsonb not null,           -- 48 × 12 float window (4dp rounded)
  meta           jsonb not null default '{}',
  label          text,                     -- VALID / INVALID_DEPTH / INVALID_FORM / CHEAT / UNKNOWN
  label_source   text,                     -- HUMAN_A / HUMAN_B / ARBITER / AUTO_ACCEPTED
  labeled_by     uuid,
  labeled_at     timestamptz,
  created_at     timestamptz not null default now()
);
create index if not exists rep_feature_sequences_label_idx on public.rep_feature_sequences (label) where label is null;
alter table public.rep_feature_sequences enable row level security;
create policy rep_feature_sequences_insert_own on public.rep_feature_sequences
  for insert to authenticated with check (auth.uid() = user_id);
create policy rep_feature_sequences_read on public.rep_feature_sequences
  for select to authenticated using (auth.uid() = user_id or public.is_admin());

create or replace function public.label_rep_sequence(p_id bigint, p_label text, p_source text)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  if p_label not in ('VALID','INVALID_DEPTH','INVALID_FORM','PARTIAL_REP','CHEAT_MOVEMENT','CAMERA_MOVEMENT','UNKNOWN')
    then raise exception 'bad_label'; end if;
  update public.rep_feature_sequences
     set label = p_label, label_source = p_source, labeled_by = auth.uid(), labeled_at = now()
   where id = p_id;
end $$;
grant execute on function public.label_rep_sequence(bigint, text, text) to authenticated;
