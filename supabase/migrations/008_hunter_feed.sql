-- ═══════════════════════════════════════════════════════════════════════════
-- THE SYSTEM — 008: THE HUNTER FEED (X-style social layer)
-- Dispatches (posts), threads (parent), re-dispatches (quotes), interactions
-- (mana_boost / transmit / challenge), realtime replication, RLS.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── Interaction types ────────────────────────────────────────────────────────
do $$ begin
  create type public.hunter_interaction_type as enum ('mana_boost', 'transmit', 'challenge');
exception when duplicate_object then null; end $$;

-- ── DISPATCHES ───────────────────────────────────────────────────────────────
create table if not exists public.hunter_posts (
  id                   uuid primary key default gen_random_uuid(),
  author_id            uuid not null references public.users(id) on delete cascade,
  content              text not null check (char_length(content) between 1 and 2000),
  media_url            text,
  quest_verification_id bigint,                    -- soft link to a verified quest/workout row
  parent_id            uuid references public.hunter_posts(id) on delete cascade,   -- reply → thread
  quoted_post_id       uuid references public.hunter_posts(id) on delete set null,  -- re-dispatch → quote
  created_at           timestamptz not null default now()
);
create index if not exists hunter_posts_timeline on public.hunter_posts (created_at desc) where parent_id is null;
create index if not exists hunter_posts_thread   on public.hunter_posts (parent_id) where parent_id is not null;

create table if not exists public.hunter_interactions (
  post_id    uuid not null references public.hunter_posts(id) on delete cascade,
  user_id    uuid not null references public.users(id) on delete cascade,
  type       public.hunter_interaction_type not null,
  created_at timestamptz not null default now(),
  primary key (post_id, user_id, type)
);

-- ── Feed projection: author plate + counts + quote preview ───────────────────
-- security_invoker keeps RLS semantics identical to base tables for every reader.
create or replace view public.hunter_posts_feed with (security_invoker = true) as
select
  p.id,
  p.author_id,
  u.username,
  u.display_name,
  u.avatar_url,
  u.level,
  u.missed_days,
  (select m.clan_id from public.clan_members m where m.user_id = p.author_id) as author_clan_id,
  p.content,
  p.media_url,
  p.quest_verification_id,
  p.parent_id,
  p.quoted_post_id,
  qu.username          as quoted_username,
  left(q.content,136)  as quoted_excerpt,
  (select count(*) from public.hunter_interactions i where i.post_id = p.id and i.type = 'mana_boost') as mana_count,
  (select count(*) from public.hunter_interactions i where i.post_id = p.id and i.type = 'transmit')   as transmit_count,
  (select count(*) from public.hunter_posts r where r.parent_id = p.id)                               as reply_count,
  p.created_at
from public.hunter_posts p
join public.users u on u.id = p.author_id
left join public.hunter_posts q on q.id = p.quoted_post_id
left join public.users qu on qu.id = q.author_id;

-- ── RLS: public read, authenticated write-own ────────────────────────────────
alter table public.hunter_posts        enable row level security;
alter table public.hunter_interactions enable row level security;

drop policy if exists feed_posts_read   on public.hunter_posts;
drop policy if exists feed_posts_insert on public.hunter_posts;
drop policy if exists feed_posts_delete on public.hunter_posts;
create policy feed_posts_read   on public.hunter_posts for select using (true);
create policy feed_posts_insert on public.hunter_posts for insert with check (auth.uid() = author_id);
create policy feed_posts_delete on public.hunter_posts for delete using (auth.uid() = author_id);

drop policy if exists feed_inter_read   on public.hunter_interactions;
drop policy if exists feed_inter_insert on public.hunter_interactions;
drop policy if exists feed_inter_delete on public.hunter_interactions;
create policy feed_inter_read   on public.hunter_interactions for select using (true);
create policy feed_inter_insert on public.hunter_interactions for insert with check (auth.uid() = user_id);
create policy feed_inter_delete on public.hunter_interactions for delete using (auth.uid() = user_id);

-- ── REALTIME: zero-latency timeline ──────────────────────────────────────────
do $$ begin
  alter publication supabase_realtime add table public.hunter_posts;
exception when duplicate_object then null; end $$;
do $$ begin
  alter publication supabase_realtime add table public.hunter_interactions;
exception when duplicate_object then null; end $$;
