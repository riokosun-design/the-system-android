-- ═══════════════════════════════════════════════════════════════════════════
-- THE SYSTEM — 003: ROW LEVEL SECURITY
-- Default posture: everything locked, then precise grants.
-- Server-side flows bypass via security-definer functions / service_role.
-- ═══════════════════════════════════════════════════════════════════════════

alter table public.users                  enable row level security;
alter table public.dynamic_assets         enable row level security;
alter table public.legal_documents        enable row level security;
alter table public.system_config          enable row level security;
alter table public.ecommerce_products     enable row level security;
alter table public.daily_quests           enable row level security;
alter table public.training_arcs          enable row level security;
alter table public.user_arc_progress      enable row level security;
alter table public.user_forms             enable row level security;
alter table public.clans                  enable row level security;
alter table public.clan_members           enable row level security;
alter table public.zone_captures          enable row level security;
alter table public.clan_territories       enable row level security;
alter table public.workouts               enable row level security;
alter table public.workout_media          enable row level security;
alter table public.vc_transactions        enable row level security;
alter table public.tournaments            enable row level security;
alter table public.tournament_participants enable row level security;
alter table public.battles                enable row level security;
alter table public.prediction_pools       enable row level security;
alter table public.prediction_bets        enable row level security;
alter table public.messages               enable row level security;
alter table public.manual_payments        enable row level security;
alter table public.referrals              enable row level security;
alter table public.admin_audit_log        enable row level security;

-- ── users ────────────────────────────────────────────────────────────────────
drop policy if exists users_select on public.users;
create policy users_select on public.users for select using (true); -- public hunter profiles
drop policy if exists users_update on public.users;
create policy users_update on public.users for update
  using (auth.uid() = id or public.is_admin())
  with check (auth.uid() = id or public.is_admin());     -- guard_user_columns() blocks role/xp/vc edits
-- no direct insert/delete: handle_new_user trigger owns lifecycle

-- ── dynamic_assets ───────────────────────────────────────────────────────────
drop policy if exists assets_select on public.dynamic_assets;
create policy assets_select on public.dynamic_assets for select using (enabled or public.is_admin());
drop policy if exists assets_write on public.dynamic_assets;
create policy assets_write on public.dynamic_assets for all
  using (public.is_admin()) with check (public.is_admin());

-- ── legal_documents ─────────────────────────────────────────────────────────
drop policy if exists legal_select on public.legal_documents;
create policy legal_select on public.legal_documents for select using (true); -- anyone may read, incl. logged-out onboarding
drop policy if exists legal_write on public.legal_documents;
create policy legal_write on public.legal_documents for all
  using (public.is_admin()) with check (public.is_admin());

-- ── system_config ────────────────────────────────────────────────────────────
drop policy if exists config_select on public.system_config;
create policy config_select on public.system_config for select using (true);
drop policy if exists config_write on public.system_config;
create policy config_write on public.system_config for all
  using (public.is_admin()) with check (public.is_admin());

-- ── ecommerce_products ──────────────────────────────────────────────────────
drop policy if exists products_select on public.ecommerce_products;
create policy products_select on public.ecommerce_products for select using (active or public.is_admin());
drop policy if exists products_write on public.ecommerce_products;
create policy products_write on public.ecommerce_products for all
  using (public.is_admin()) with check (public.is_admin());

-- ── daily_quests ─────────────────────────────────────────────────────────────
drop policy if exists quests_owner on public.daily_quests;
create policy quests_owner on public.daily_quests for select using (auth.uid() = user_id);
-- inserts/updates only via ensure_daily_quests / complete_quest / add_quest_progress (definer)

-- ── training_arcs & progress ────────────────────────────────────────────────
drop policy if exists arcs_read on public.training_arcs;
create policy arcs_read on public.training_arcs for select using (true);
drop policy if exists arcs_write on public.training_arcs;
create policy arcs_write on public.training_arcs for all
  using (public.is_admin()) with check (public.is_admin());

drop policy if exists arc_progress_owner on public.user_arc_progress;
create policy arc_progress_owner on public.user_arc_progress for select using (auth.uid() = user_id);

-- ── user_forms ───────────────────────────────────────────────────────────────
drop policy if exists forms_owner on public.user_forms;
create policy forms_owner on public.user_forms for select using (auth.uid() = user_id or public.is_admin());

-- ── clans ────────────────────────────────────────────────────────────────────
drop policy if exists clans_read on public.clans;
create policy clans_read on public.clans for select using (true);
-- create only via create_clan() RPC (gate: level 30 + 500 VC)
drop policy if exists clans_update on public.clans;
create policy clans_update on public.clans for update
  using (public.is_admin() or guild_master = auth.uid())
  with check (public.is_admin() or guild_master = auth.uid());

drop policy if exists clan_members_read on public.clan_members;
create policy clan_members_read on public.clan_members for select using (true);
-- join via join_clan() RPC; leave = delete your own row; master may kick
drop policy if exists clan_members_leave on public.clan_members;
create policy clan_members_leave on public.clan_members for delete
  using (auth.uid() = user_id
         or public.is_admin()
         or exists(select 1 from public.clans c where c.id = clan_id and c.guild_master = auth.uid()));

-- ── territory ────────────────────────────────────────────────────────────────
drop policy if exists captures_read on public.zone_captures;
create policy captures_read on public.zone_captures for select using (true);
-- writes via capture_zone() RPC (rate-limited, tax-aware)

drop policy if exists clan_territories_read on public.clan_territories;
create policy clan_territories_read on public.clan_territories for select using (true);
-- writes via claim_territory() RPC

-- ── workouts / media ────────────────────────────────────────────────────────
drop policy if exists workouts_owner on public.workouts;
create policy workouts_owner on public.workouts for select using (auth.uid() = user_id);
drop policy if exists workouts_insert on public.workouts;
create policy workouts_insert on public.workouts for insert with check (auth.uid() = user_id);

drop policy if exists media_owner on public.workout_media;
create policy media_owner on public.workout_media for select using (auth.uid() = user_id or public.is_admin());
drop policy if exists media_insert on public.workout_media;
create policy media_insert on public.workout_media for insert with check (auth.uid() = user_id);
-- no update/delete: the 10-minute purge owns deletion

-- ── vc_transactions ─────────────────────────────────────────────────────────
drop policy if exists vc_read on public.vc_transactions;
create policy vc_read on public.vc_transactions for select using (auth.uid() = user_id or public.is_admin());

-- ── tournaments ─────────────────────────────────────────────────────────────
drop policy if exists tournaments_read on public.tournaments;
create policy tournaments_read on public.tournaments for select using (true);
drop policy if exists tournaments_write on public.tournaments;
create policy tournaments_write on public.tournaments for all
  using (public.is_admin()) with check (public.is_admin());

drop policy if exists participants_read on public.tournament_participants;
create policy participants_read on public.tournament_participants for select using (true);
-- registration only via join_tournament() RPC (entry-fee gate)

-- ── battles ──────────────────────────────────────────────────────────────────
drop policy if exists battles_read on public.battles;
create policy battles_read on public.battles for select using (true);
-- create only via create_battle() RPC so every battle spawns its pool
drop policy if exists battles_live_update on public.battles;
create policy battles_live_update on public.battles for update
  using (auth.uid() in (player_a, player_b) or public.is_admin())
  with check (auth.uid() in (player_a, player_b) or public.is_admin());

-- ── predictions ─────────────────────────────────────────────────────────────
drop policy if exists pools_read on public.prediction_pools;
create policy pools_read on public.prediction_pools for select using (true);
-- writes via place_bet() / settle_prediction_pool() / create_battle()

drop policy if exists bets_read on public.prediction_bets;
create policy bets_read on public.prediction_bets for select
  using (auth.uid() = user_id or public.is_admin());

-- ── messages ────────────────────────────────────────────────────────────────
-- Clan rooms: members only. DMs: the two participants only.
drop policy if exists messages_read on public.messages;
create policy messages_read on public.messages for select using (
  (kind = 'CLAN' and exists(select 1 from public.clan_members m
                            where m.clan_id = messages.clan_id and m.user_id = auth.uid()))
  or (kind = 'DM' and auth.uid() in (sender_id, recipient_id))
  or public.is_admin()
);
drop policy if exists messages_send on public.messages;
create policy messages_send on public.messages for insert with check (
  sender_id = auth.uid() and (
    (kind = 'CLAN' and exists(select 1 from public.clan_members m
                              where m.clan_id = messages.clan_id and m.user_id = auth.uid()))
    or kind = 'DM'
  )
);
-- no edits, no deletes: the transcript is the transcript

-- ── manual_payments ─────────────────────────────────────────────────────────
drop policy if exists payments_owner_read on public.manual_payments;
create policy payments_owner_read on public.manual_payments for select
  using (auth.uid() = user_id or public.is_admin());
drop policy if exists payments_insert on public.manual_payments;
create policy payments_insert on public.manual_payments for insert
  with check (auth.uid() = user_id and status = 'PENDING');
-- review only via review_payment() RPC

-- ── referrals ───────────────────────────────────────────────────────────────
drop policy if exists referrals_read on public.referrals;
create policy referrals_read on public.referrals for select using (true); -- hall of fame is public
-- writes via apply_referral() RPC

-- ── audit log ───────────────────────────────────────────────────────────────
drop policy if exists audit_admin_read on public.admin_audit_log;
create policy audit_admin_read on public.admin_audit_log for select using (public.is_super_admin());

-- ── STORAGE object policies ──────────────────────────────────────────────────
-- system_assets / product-images: public read (public buckets), admin write
drop policy if exists sa_admin_write on storage.objects;
create policy sa_admin_write on storage.objects for all
  using (bucket_id in ('system_assets','product-images') and public.is_admin())
  with check (bucket_id in ('system_assets','product-images') and public.is_admin());

-- avatars: owner writes under their uid prefix
drop policy if exists av_owner_write on storage.objects;
create policy av_owner_write on storage.objects for all
  using (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text)
  with check (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);

-- payment-proofs: owner insert+read; admin read
drop policy if exists pp_insert on storage.objects;
create policy pp_insert on storage.objects for insert
  with check (bucket_id = 'payment-proofs' and (storage.foldername(name))[1] = auth.uid()::text);
drop policy if exists pp_read on storage.objects;
create policy pp_read on storage.objects for select
  using (bucket_id = 'payment-proofs' and ((storage.foldername(name))[1] = auth.uid()::text or public.is_admin()));

-- workout-verification: owner insert+read; NO delete policy → purge function owns expiry
drop policy if exists wv_insert on storage.objects;
create policy wv_insert on storage.objects for insert
  with check (bucket_id = 'workout-verification' and (storage.foldername(name))[1] = auth.uid()::text);
drop policy if exists wv_read on storage.objects;
create policy wv_read on storage.objects for select
  using (bucket_id = 'workout-verification' and ((storage.foldername(name))[1] = auth.uid()::text or public.is_admin()));

-- ── REALTIME publication (RLS applies to postgres_changes streams) ──────────
do $$
begin
  alter publication supabase_realtime add table public.messages;
exception when duplicate_object then null; end $$;
do $$
begin
  alter publication supabase_realtime add table public.battles;
exception when duplicate_object then null; end $$;
do $$
begin
  alter publication supabase_realtime add table public.prediction_pools;
exception when duplicate_object then null; end $$;
do $$
begin
  alter publication supabase_realtime add table public.users;
exception when duplicate_object then null; end $$;

-- identity for realtime updates/deletes
alter table public.messages replica identity full;
alter table public.battles replica identity full;
