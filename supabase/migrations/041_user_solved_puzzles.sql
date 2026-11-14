-- 041 — vC55 §A4: LIFETIME NEVER-REPEAT PUZZLE VAULT (durable rail)
-- Every puzzle the hunter SOLVES is stamped here once (primary key kill on
-- replays). The app blacklists these ids forever — a conquered puzzle can
-- never be served again, on any device, after any reinstall. The local
-- DataStore vault mirrors it for offline-first reads; THIS table is the
-- cross-device source of truth. Pure ledger: no XP, no economy — chess
-- economy still lives exclusively in mig-018/019 RPCs.

create table if not exists public.user_solved_puzzles (
  user_id    uuid                     not null references auth.users (id) on delete cascade,
  puzzle_id  text                     not null check (char_length(puzzle_id) between 1 and 40),
  solved_at  timestamptz              not null default now(),
  solve_ms   integer                  not null default 0 check (solve_ms between 0 and 3600000),
  primary key (user_id, puzzle_id)
);

alter table public.user_solved_puzzles enable row level security;

-- own rows only, both directions (mirrors the chess_profile covenant)
drop policy if exists user_solved_puzzles_rw_own on public.user_solved_puzzles;
create policy user_solved_puzzles_rw_own on public.user_solved_puzzles
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create index if not exists user_solved_puzzles_user_idx
  on public.user_solved_puzzles (user_id);
