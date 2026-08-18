-- ═══════════════════════════════════════════════════════════════════════════
-- THE SYSTEM — 001: CORE SCHEMA
-- Tables, enums, views, indexes, storage buckets.
-- Apply order: 001_schema → 002_functions → 003_rls → 004_cron → 005_seed
-- ═══════════════════════════════════════════════════════════════════════════

create extension if not exists pgcrypto;
create extension if not exists citext;

-- ── Enums ────────────────────────────────────────────────────────────────────
do $$ begin
  create type user_role as enum ('SUPER_ADMIN','ADMIN','MODERATOR','USER');
exception when duplicate_object then null; end $$;
do $$ begin
  create type penalty_state as enum ('CLEAR','WARNING','DECAY','GARBAGE','LOSER');
exception when duplicate_object then null; end $$;
do $$ begin
  create type clan_role as enum ('GUILD_MASTER','VICE_CAPTAIN','ELITE_HUNTER','MEMBER');
exception when duplicate_object then null; end $$;
do $$ begin
  create type tournament_type as enum ('SOLO','CLAN');
exception when duplicate_object then null; end $$;
do $$ begin
  create type tournament_status as enum ('DRAFT','OPEN','LOCKED','IN_PROGRESS','COMPLETED','CANCELLED');
exception when duplicate_object then null; end $$;
do $$ begin
  create type pool_status as enum ('OPEN','LOCKED','SETTLED','REFUNDED');
exception when duplicate_object then null; end $$;
do $$ begin
  create type payment_status as enum ('PENDING','APPROVED','REJECTED');
exception when duplicate_object then null; end $$;
do $$ begin
  create type message_kind as enum ('CLAN','DM');
exception when duplicate_object then null; end $$;
do $$ begin
  create type asset_type as enum ('CHARACTER_WALLPAPER','AMBIENT_BACKGROUND','UI_OVERLAY','SPLASH_ART');
exception when duplicate_object then null; end $$;

-- ── USERS (profile mirror of auth.users; roles assigned by trigger) ──────────
create table if not exists public.users (
  id                uuid primary key references auth.users(id) on delete cascade,
  username          citext not null unique,
  display_name      text,
  avatar_url        text,
  role              user_role not null default 'USER',
  level             int  not null default 1 check (level between 1 and 100),
  xp                bigint not null default 0 check (xp >= 0),
  vc_balance        bigint not null default 0 check (vc_balance >= 0),
  penalty_state     penalty_state not null default 'CLEAR',
  missed_days       int not null default 0,
  streak_days       int not null default 0,
  last_activity_date date,
  goal              text,
  age               int check (age is null or age between 13 and 100),
  height_cm         numeric(5,1),
  weight_kg         numeric(5,1),
  referral_code     text not null unique default upper(substr(replace(gen_random_uuid()::text,'-',''),1,8)),
  referred_by       uuid references public.users(id),
  onboarding_completed boolean not null default false,
  black_room_until  timestamptz,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);

-- ── DYNAMIC THEME ASSET ENGINE ───────────────────────────────────────────────
create table if not exists public.dynamic_assets (
  id            uuid primary key default gen_random_uuid(),
  key           text not null,                  -- e.g. DASHBOARD_HERO, ARENA_BG
  type          asset_type not null,
  title         text,
  storage_path  text not null,                  -- path inside bucket 'system_assets'
  fade_opacity  real not null default 0.25 check (fade_opacity between 0 and 1),
  enabled       boolean not null default true,
  sort_order    int not null default 0,
  updated_by    uuid references public.users(id),
  updated_at    timestamptz not null default now(),
  unique (key, type)
);

-- ── DYNAMIC LEGAL DOCUMENTS ──────────────────────────────────────────────────
create table if not exists public.legal_documents (
  id               uuid primary key default gen_random_uuid(),
  doc_type         text not null unique check (doc_type in ('PRIVACY_POLICY','TERMS_OF_SERVICE')),
  title            text not null,
  content_markdown text not null,
  version          int not null default 1,
  updated_by       uuid references public.users(id),
  updated_at       timestamptz not null default now()
);

-- ── SYSTEM CONFIG (admin-editable runtime knobs) ─────────────────────────────
create table if not exists public.system_config (
  key   text primary key,
  value text not null
);

-- ── E-COMMERCE (level-gated merch + affiliate supplements) ───────────────────
create table if not exists public.ecommerce_products (
  id                       uuid primary key default gen_random_uuid(),
  name                     text not null,
  description              text,
  image_url                text,
  price_inr                numeric(10,2) not null default 0 check (price_inr >= 0),
  category                 text not null default 'MERCH' check (category in ('MERCH','SUPPLEMENT','DIGITAL')),
  outbound_url             text not null,
  affiliate_commission_pct numeric(5,2) not null default 0,
  min_rank_required        text not null default 'AVERAGE',
  active                   boolean not null default true,
  stock_note               text,
  created_by               uuid references public.users(id),
  created_at               timestamptz not null default now(),
  updated_at               timestamptz not null default now()
);

-- ── DAILY QUESTS (Leguna S.1 AI) ─────────────────────────────────────────────
create table if not exists public.daily_quests (
  id           bigint generated always as identity primary key,
  user_id      uuid not null references public.users(id) on delete cascade,
  quest_date   date not null default current_date,
  title        text not null,
  target_value int not null,
  progress     int not null default 0,
  xp_reward    int not null default 50,
  completed    boolean not null default false,
  source       text not null default 'LEGUNA_S1_AI',
  unique (user_id, quest_date, title)
);

-- ── TRAINING ARCS (4-month anime courses) ────────────────────────────────────
create table if not exists public.training_arcs (
  id               text primary key,            -- SAITAMA, GOKU, JIN_WOO, GAROU, ...
  hero             text not null,
  title            text not null,
  duration_months  int not null default 4,
  unlock_req_arc   text references public.training_arcs(id),
  min_level        int not null default 1,
  description      text,
  disclaimer       text not null,
  routine          jsonb not null default '[]'::jsonb, -- day templates, editable by admins
  sort             int not null default 0
);

create table if not exists public.user_arc_progress (
  user_id            uuid not null references public.users(id) on delete cascade,
  arc_id             text not null references public.training_arcs(id) on delete cascade,
  started_at         date not null default current_date,
  days_completed     int not null default 0,
  penalty_extra_days int not null default 0,
  completed          boolean not null default false,
  primary key (user_id, arc_id)
);

-- ── FORM EVOLUTION (Mystery Power, max 5) ────────────────────────────────────
create table if not exists public.user_forms (
  user_id              uuid not null references public.users(id) on delete cascade,
  form_index           int not null check (form_index between 1 and 5),
  name                 text not null,
  combat_style         text not null default 'BALANCED',
  base_power           numeric(10,1) not null,
  hard_work_multiplier numeric(4,2) not null default 1.0,
  computed_power       numeric(12,1) not null,
  unlocked_at          timestamptz not null default now(),
  primary key (user_id, form_index)
);

-- ── CLANS (Shadow Guilds) ────────────────────────────────────────────────────
create table if not exists public.clans (
  id           uuid primary key default gen_random_uuid(),
  name         text not null unique check (char_length(name) between 3 and 32),
  tag          citext not null unique check (char_length(tag::text) between 2 and 5),
  description  text not null default '',
  crest_path   text,
  guild_master uuid not null references public.users(id),
  level        int not null default 1,
  treasury_vc  bigint not null default 0 check (treasury_vc >= 0),
  created_at   timestamptz not null default now()
);

create table if not exists public.clan_members (
  clan_id   uuid not null references public.clans(id) on delete cascade,
  user_id   uuid not null references public.users(id) on delete cascade,
  role      clan_role not null default 'MEMBER',
  joined_at timestamptz not null default now(),
  primary key (clan_id, user_id),
  unique (user_id) -- one guild per hunter
);

create or replace view public.clan_overview with (security_invoker = true) as
select c.*, (select count(*) from public.clan_members m where m.clan_id = c.id) as member_count
from public.clans c;

create or replace view public.clan_members_with_names with (security_invoker = true) as
select m.clan_id, m.user_id, m.role::text as role, m.joined_at, u.username
from public.clan_members m join public.users u on u.id = m.user_id;

-- ── TERRITORY (1KM geofenced zones) ──────────────────────────────────────────
create table if not exists public.zone_captures (
  id         bigint generated always as identity primary key,
  user_id    uuid not null references public.users(id) on delete cascade,
  clan_id    uuid references public.clans(id) on delete set null,
  zone       text not null check (char_length(zone) = 6), -- geohash precision 6 ≈ 1.2km cell
  reps       int not null default 0,
  duration_sec int not null default 0,
  created_at timestamptz not null default now()
);
create index if not exists zone_captures_zone_idx on public.zone_captures (zone, created_at desc);
create index if not exists zone_captures_user_idx on public.zone_captures (user_id, created_at desc);

create table if not exists public.clan_territories (
  zone          text primary key check (char_length(zone) = 6),
  clan_id       uuid not null references public.clans(id) on delete cascade,
  shield_active boolean not null default true,
  tax_bps       int not null default 500, -- 5.00% tribute from non-members in zone
  claimed_by    uuid references public.users(id),
  captured_at   timestamptz not null default now()
);

-- area leadership = top capturer per zone over the trailing 30 days
create or replace view public.zone_leaderboard with (security_invoker = true) as
select zc.zone,
       zc.user_id,
       u.username,
       count(*)::bigint as captures,
       m.clan_id,
       c.tag::text as clan_tag
from public.zone_captures zc
join public.users u on u.id = zc.user_id
left join public.clan_members m on m.user_id = zc.user_id
left join public.clans c on c.id = m.clan_id
where zc.created_at > now() - interval '30 days'
group by zc.zone, zc.user_id, u.username, m.clan_id, c.tag;

create or replace view public.clan_territories_with_tag with (security_invoker = true) as
select t.zone, t.clan_id, t.shield_active, t.tax_bps, t.captured_at, c.tag::text as clan_tag
from public.clan_territories t join public.clans c on c.id = t.clan_id;

-- ── WORKOUTS + 10-MIN-PURGED VERIFICATION MEDIA ──────────────────────────────
create table if not exists public.workouts (
  id         bigint generated always as identity primary key,
  user_id    uuid not null references public.users(id) on delete cascade,
  kind       text not null,
  reps       int not null default 0,
  duration_sec int not null default 0,
  xp_earned  int not null default 0,
  zone       text,
  created_at timestamptz not null default now()
);

create table if not exists public.workout_media (
  id           bigint generated always as identity primary key,
  workout_id   bigint not null references public.workouts(id) on delete cascade,
  user_id      uuid not null references public.users(id) on delete cascade,
  storage_path text not null,
  created_at   timestamptz not null default now()
);
create index if not exists workout_media_purge_idx on public.workout_media (created_at);

-- ── VC WALLET LEDGER ─────────────────────────────────────────────────────────
create table if not exists public.vc_transactions (
  id         bigint generated always as identity primary key,
  user_id    uuid not null references public.users(id) on delete cascade,
  amount     bigint not null, -- signed: +credit / −debit
  reason     text not null,   -- ENTRY_FEE | BET | BET_PAYOUT | BET_REFUND | CLAN_CREATE | QUEST_REWARD
             --               -- GUILD_TAX | TOPUP | PURCHASE | ADMIN_ADJUST | REFERRAL_CUT | CPA_POSTBACK | TOURNAMENT_PRIZE
  reference  text,
  created_at timestamptz not null default now()
);
create index if not exists vc_txn_user_idx on public.vc_transactions (user_id, created_at desc);

-- ── TOURNAMENTS (admin-controlled entry fees) ────────────────────────────────
create table if not exists public.tournaments (
  id               uuid primary key default gen_random_uuid(),
  title            text not null,
  type             tournament_type not null default 'SOLO',
  status           tournament_status not null default 'DRAFT',
  entry_fee_vc     bigint not null default 0 check (entry_fee_vc >= 0),
  prize_pool_vc    bigint not null default 0,
  max_participants int not null default 64,
  starts_at        timestamptz,
  ends_at          timestamptz,
  created_by       uuid references public.users(id),
  created_at       timestamptz not null default now()
);

create table if not exists public.tournament_participants (
  id             uuid primary key default gen_random_uuid(),
  tournament_id  uuid not null references public.tournaments(id) on delete cascade,
  user_id        uuid references public.users(id) on delete cascade,
  clan_id        uuid references public.clans(id) on delete cascade,
  entry_fee_paid bigint not null default 0,
  placement      int,
  joined_at      timestamptz not null default now(),
  check ((user_id is null) <> (clan_id is null))
);
-- partial uniques: one registration per hunter per solo tournament / per guild per clan war
create unique index if not exists tp_solo_uniq on public.tournament_participants (tournament_id, user_id) where user_id is not null;
create unique index if not exists tp_clan_uniq on public.tournament_participants (tournament_id, clan_id) where clan_id is not null;

-- ── BATTLES (realtime push-up wars; solo battles + clan-vs-clan fixtures) ────
create table if not exists public.battles (
  id            uuid primary key default gen_random_uuid(),
  tournament_id uuid references public.tournaments(id) on delete set null,
  player_a      uuid not null references public.users(id) on delete cascade,
  player_b      uuid not null references public.users(id) on delete cascade,
  clan_a        uuid references public.clans(id),
  clan_b        uuid references public.clans(id),
  score_a       int not null default 0,
  score_b       int not null default 0,
  status        text not null default 'LOBBY' check (status in ('LOBBY','LIVE','FINISHED','CANCELLED')),
  winner        uuid references public.users(id),
  created_at    timestamptz not null default now(),
  started_at    timestamptz,
  finished_at   timestamptz
);
create or replace view public.battles_with_names with (security_invoker = true) as
select b.*, a.username as player_a_name, b2.username as player_b_name
from public.battles b
join public.users a on a.id = b.player_a
join public.users b2 on b2.id = b.player_b;

-- ── PREDICTION ENGINE (works for 1v1 AND clan battles via battle_id) ─────────
create table if not exists public.prediction_pools (
  id               uuid primary key default gen_random_uuid(),
  battle_id        uuid not null unique references public.battles(id) on delete cascade,
  status           pool_status not null default 'OPEN',
  platform_cut_bps int not null default 1500, -- 15% house cut
  total_pool_vc    bigint not null default 0,
  total_a_vc       bigint not null default 0,
  total_b_vc       bigint not null default 0,
  winning_side     text check (winning_side in ('A','B')),
  settled_at       timestamptz,
  created_at       timestamptz not null default now()
);

create table if not exists public.prediction_bets (
  id         bigint generated always as identity primary key,
  pool_id    uuid not null references public.prediction_pools(id) on delete cascade,
  user_id    uuid not null references public.users(id) on delete cascade,
  side       text not null check (side in ('A','B')),
  amount_vc  bigint not null check (amount_vc > 0),
  payout_vc  bigint,
  status     text not null default 'OPEN' check (status in ('OPEN','WON','LOST','REFUNDED')),
  created_at timestamptz not null default now(),
  unique (pool_id, user_id) -- one conviction per hunter per pool
);

-- ── MESSAGES (clan chat + global DMs, one table, realtime) ───────────────────
create table if not exists public.messages (
  id           bigint generated always as identity primary key,
  sender_id    uuid not null references public.users(id) on delete cascade,
  clan_id      uuid references public.clans(id) on delete cascade,
  recipient_id uuid references public.users(id) on delete cascade,
  kind         message_kind not null,
  body         text not null check (char_length(body) between 1 and 1000),
  created_at   timestamptz not null default now(),
  check ((clan_id is not null)::int + (recipient_id is not null)::int = 1),
  check ((kind = 'CLAN' and clan_id is not null) or (kind = 'DM' and recipient_id is not null))
);
create index if not exists messages_clan_idx on public.messages (clan_id, created_at desc);
create index if not exists messages_dm_idx on public.messages (recipient_id, created_at desc);
create index if not exists messages_sender_idx on public.messages (sender_id, created_at desc);

create or replace view public.messages_with_sender with (security_invoker = true) as
select m.*, u.username as sender_username
from public.messages m join public.users u on u.id = m.sender_id;

-- ── MANUAL PAYMENTS (UPI QR → UTR → screenshot → admin approval) ─────────────
create table if not exists public.manual_payments (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references public.users(id) on delete cascade,
  item_type       text not null check (item_type in ('BLACK_ROOM_PASS','MERCH','VC_TOPUP')),
  item_ref        text,
  amount_inr      numeric(10,2) not null check (amount_inr > 0),
  upi_utr         text not null unique,
  screenshot_path text,
  status          payment_status not null default 'PENDING',
  reviewed_by     uuid references public.users(id),
  reviewed_at     timestamptz,
  review_note     text,
  created_at      timestamptz not null default now()
);
create or replace view public.manual_payments_with_user with (security_invoker = true) as
select p.*, u.username from public.manual_payments p join public.users u on u.id = p.user_id;

-- ── REFERRALS ────────────────────────────────────────────────────────────────
create table if not exists public.referrals (
  referrer   uuid not null references public.users(id) on delete cascade,
  referee    uuid not null references public.users(id) on delete cascade unique,
  created_at timestamptz not null default now(),
  primary key (referrer, referee)
);
create or replace view public.referral_hall with (security_invoker = true) as
select u.username, count(r.referee)::bigint as referral_count
from public.referrals r join public.users u on u.id = r.referrer
group by u.username
order by referral_count desc;

-- ── ADMIN AUDIT ──────────────────────────────────────────────────────────────
create table if not exists public.admin_audit_log (
  id         bigint generated always as identity primary key,
  actor      uuid references public.users(id),
  action     text not null,
  payload    jsonb,
  created_at timestamptz not null default now()
);

-- ── STORAGE BUCKETS ──────────────────────────────────────────────────────────
insert into storage.buckets (id, name, public)
values
  ('system_assets',        'system_assets',        true),
  ('product-images',       'product-images',       true),
  ('avatars',              'avatars',              true),
  ('workout-verification', 'workout-verification', false), -- auto-purged after 10 minutes
  ('payment-proofs',       'payment-proofs',       false)
on conflict (id) do nothing;
