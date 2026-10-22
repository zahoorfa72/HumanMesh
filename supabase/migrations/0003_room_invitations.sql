-- Room invitations allow room members to invite specific collaborators.
-- Apply after 0001_humanmesh_schema.sql and 0002_collaboration_progress.sql.
create table if not exists public.room_invitations (
  id uuid primary key default gen_random_uuid(),
  room_id uuid not null references public.opportunity_rooms(id) on delete cascade,
  inviter_id uuid not null references auth.users(id) on delete cascade,
  invitee_id uuid not null references auth.users(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending', 'accepted', 'rejected')),
  created_at timestamptz not null default now(),
  responded_at timestamptz,
  check (inviter_id <> invitee_id)
);
create unique index if not exists room_invitations_pending_unique
  on public.room_invitations(room_id, invitee_id) where status = 'pending';
create index if not exists room_invitations_invitee_idx
  on public.room_invitations(invitee_id, status, created_at desc);
create index if not exists room_invitations_inviter_idx
  on public.room_invitations(inviter_id, created_at desc);

create or replace function private.guard_room_invitation_update()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if auth.uid() is distinct from old.invitee_id then
    raise exception 'Only the invited member can respond';
  end if;
  if new.room_id is distinct from old.room_id
     or new.inviter_id is distinct from old.inviter_id
     or new.invitee_id is distinct from old.invitee_id
     or new.created_at is distinct from old.created_at then
    raise exception 'Invitation details cannot be changed';
  end if;
  if old.status <> 'pending' or new.status not in ('accepted', 'rejected') then
    raise exception 'Only pending invitations can be accepted or rejected';
  end if;
  new.responded_at = now();
  return new;
end;
$$;
revoke all on function private.guard_room_invitation_update() from public, anon, authenticated;

create or replace function private.add_invited_room_member()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.status = 'accepted' and old.status = 'pending' then
    insert into public.room_members(room_id, user_id, role)
    values (new.room_id, new.invitee_id, 'member')
    on conflict (room_id, user_id) do nothing;
  end if;
  return new;
end;
$$;
revoke all on function private.add_invited_room_member() from public, anon, authenticated;

drop trigger if exists room_invitations_guard_update on public.room_invitations;
create trigger room_invitations_guard_update before update on public.room_invitations
for each row execute function private.guard_room_invitation_update();
drop trigger if exists room_invitations_add_member on public.room_invitations;
create trigger room_invitations_add_member after update on public.room_invitations
for each row execute function private.add_invited_room_member();

alter table public.room_invitations enable row level security;
drop policy if exists room_invitations_select_participants on public.room_invitations;
create policy room_invitations_select_participants on public.room_invitations
for select to authenticated using (inviter_id = auth.uid() or invitee_id = auth.uid());
drop policy if exists room_invitations_insert_room_members on public.room_invitations;
create policy room_invitations_insert_room_members on public.room_invitations
for insert to authenticated with check (
  inviter_id = auth.uid()
  and status = 'pending'
  and exists (
    select 1 from public.room_members rm
    where rm.room_id = room_invitations.room_id and rm.user_id = auth.uid()
  )
  and not private.users_have_blocked(auth.uid(), invitee_id)
  and exists (
    select 1 from public.opportunity_rooms r
    where r.id = room_invitations.room_id
  )
);
drop policy if exists room_invitations_update_invitee on public.room_invitations;
create policy room_invitations_update_invitee on public.room_invitations
for update to authenticated
using (invitee_id = auth.uid() and status = 'pending')
with check (invitee_id = auth.uid() and status in ('accepted', 'rejected'));

grant select, insert, update on public.room_invitations to authenticated;
revoke all on public.room_invitations from anon;
