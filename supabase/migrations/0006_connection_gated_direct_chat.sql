-- Connection-gated one-to-one text chat. Apply after migrations 0001-0005.
-- A conversation is keyed to an accepted match request; clients cannot create arbitrary chats.
create table if not exists public.direct_messages (
  id uuid primary key default gen_random_uuid(),
  match_request_id uuid not null references public.match_requests(id) on delete cascade,
  sender_id uuid not null references auth.users(id) on delete cascade,
  body text not null check (char_length(body) between 1 and 2000),
  created_at timestamptz not null default now()
);
create index if not exists direct_messages_conversation_recent_idx
  on public.direct_messages (match_request_id, created_at asc);

alter table public.direct_messages enable row level security;
drop policy if exists direct_messages_select_accepted_connection on public.direct_messages;
create policy direct_messages_select_accepted_connection on public.direct_messages
for select to authenticated using (
  exists (
    select 1 from public.match_requests mr
    where mr.id = direct_messages.match_request_id
      and mr.status = 'accepted'
      and (mr.sender_id = auth.uid() or mr.receiver_id = auth.uid())
      and not private.users_have_blocked(auth.uid(),
        case when mr.sender_id = auth.uid() then mr.receiver_id else mr.sender_id end)
  )
);
drop policy if exists direct_messages_insert_accepted_connection on public.direct_messages;
create policy direct_messages_insert_accepted_connection on public.direct_messages
for insert to authenticated with check (
  sender_id = auth.uid()
  and exists (
    select 1 from public.match_requests mr
    where mr.id = direct_messages.match_request_id
      and mr.status = 'accepted'
      and (mr.sender_id = auth.uid() or mr.receiver_id = auth.uid())
      and not private.users_have_blocked(auth.uid(),
        case when mr.sender_id = auth.uid() then mr.receiver_id else mr.sender_id end)
  )
);
grant select, insert on public.direct_messages to authenticated;
revoke all on public.direct_messages from anon;
