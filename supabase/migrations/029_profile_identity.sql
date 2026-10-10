-- 029 PROFILE IDENTITY + CHAT RECEIPTS
-- bio (≤160, client-trimmed) · avatars (presets ride avatar_url as system://KEY,
-- customs ride it as a storage public URL) · DM read receipts · avatars bucket.

alter table public.users add column if not exists bio text;

-- ── DM read receipts — recipient stamps read_at, sender sees ✓✓ ─────────────
alter table public.messages add column if not exists read_at timestamptz;
create index if not exists messages_unread_idx
  on public.messages (recipient_id, sender_id) where read_at is null;

drop policy if exists messages_mark_read on public.messages;
create policy messages_mark_read on public.messages for update
  using (auth.uid() = recipient_id) with check (auth.uid() = recipient_id);

-- the sender-side view must carry the receipt stamp too
-- (drop+create: CREATE OR REPLACE cannot reorder — read_at lands before sender_username)
drop view if exists public.messages_with_sender;
create view public.messages_with_sender with (security_invoker = true) as
select m.*, u.username as sender_username
from public.messages m join public.users u on u.id = m.sender_id;

-- ── custom avatar storage — public bucket, authenticated write, owner update ─
insert into storage.buckets (id, name, public)
values ('avatars', 'avatars', true)
on conflict (id) do nothing;

drop policy if exists avatars_public_read on storage.objects;
create policy avatars_public_read on storage.objects for select
  using (bucket_id = 'avatars');

drop policy if exists avatars_auth_insert on storage.objects;
create policy avatars_auth_insert on storage.objects for insert
  with check (bucket_id = 'avatars' and auth.role() = 'authenticated');

drop policy if exists avatars_owner_update on storage.objects;
create policy avatars_owner_update on storage.objects for update
  using (bucket_id = 'avatars' and owner = auth.uid());

drop policy if exists avatars_owner_delete on storage.objects;
create policy avatars_owner_delete on storage.objects for delete
  using (bucket_id = 'avatars' and owner = auth.uid());
