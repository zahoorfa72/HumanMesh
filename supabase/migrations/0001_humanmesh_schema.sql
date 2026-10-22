-- HumanMesh initial schema. Apply to a fresh Supabase project.
-- Do not disable RLS and never put a service-role key in the Android app.
create extension if not exists pgcrypto;

create schema if not exists private;
revoke all on schema private from public;
grant usage on schema private to authenticated;

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text not null check (char_length(display_name) between 1 and 60),
  username text not null unique check (username ~ '^[a-z0-9_]{3,30}$'),
  bio text not null default '' check (char_length(bio) <= 400),
  skills text[] not null default '{}',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.goals (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  title text not null check (char_length(title) between 3 and 100),
  description text not null default '' check (char_length(description) <= 2000),
  category text not null default 'General' check (char_length(category) <= 50),
  required_skills text[] not null default '{}',
  status text not null default 'active' check (status in ('active', 'paused', 'completed')),
  visibility text not null default 'public' check (visibility in ('public', 'private')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists goals_public_recent_idx on public.goals (created_at desc) where visibility = 'public' and status = 'active';
create index if not exists goals_owner_idx on public.goals (owner_id, created_at desc);

create table if not exists public.match_requests (
  id uuid primary key default gen_random_uuid(),
  sender_id uuid not null references auth.users(id) on delete cascade,
  receiver_id uuid not null references auth.users(id) on delete cascade,
  goal_id uuid not null references public.goals(id) on delete cascade,
  message text not null default '' check (char_length(message) <= 1000),
  status text not null default 'pending' check (status in ('pending', 'accepted', 'rejected', 'cancelled')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (sender_id <> receiver_id),
  unique (sender_id, receiver_id, goal_id)
);
create index if not exists match_receiver_idx on public.match_requests (receiver_id, status, created_at desc);
create index if not exists match_sender_idx on public.match_requests (sender_id, created_at desc);

create table if not exists public.opportunity_rooms (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  goal_id uuid references public.goals(id) on delete set null,
  title text not null check (char_length(title) between 3 and 100),
  description text not null default '' check (char_length(description) <= 1500),
  topic text not null default 'Collaboration' check (char_length(topic) <= 50),
  is_public boolean not null default true,
  created_at timestamptz not null default now()
);
create index if not exists rooms_public_recent_idx on public.opportunity_rooms (created_at desc) where is_public = true;

create table if not exists public.room_members (
  room_id uuid not null references public.opportunity_rooms(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null default 'member' check (role in ('owner', 'member')),
  joined_at timestamptz not null default now(),
  primary key (room_id, user_id)
);
create index if not exists room_members_user_idx on public.room_members (user_id);

create table if not exists public.messages (
  id uuid primary key default gen_random_uuid(),
  room_id uuid not null references public.opportunity_rooms(id) on delete cascade,
  sender_id uuid not null references auth.users(id) on delete cascade,
  body text not null check (char_length(body) between 1 and 2000),
  created_at timestamptz not null default now()
);
create index if not exists messages_room_recent_idx on public.messages (room_id, created_at desc);

create table if not exists public.user_blocks (
  blocker_id uuid not null references auth.users(id) on delete cascade,
  blocked_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id, blocked_id),
  check (blocker_id <> blocked_id)
);

create table if not exists public.user_reports (
  id uuid primary key default gen_random_uuid(),
  reporter_id uuid references auth.users(id) on delete set null,
  reported_id uuid references auth.users(id) on delete set null,
  reason text not null check (char_length(reason) between 3 and 1000),
  status text not null default 'open' check (status in ('open', 'reviewing', 'resolved', 'dismissed')),
  created_at timestamptz not null default now(),
  check (reporter_id is null or reported_id is null or reporter_id <> reported_id)
);
create index if not exists reports_status_recent_idx on public.user_reports (status, created_at desc);

-- The function is placed in a schema that must not be exposed through PostgREST.
-- It returns only whether either direction of a block exists for the current user/target.
create or replace function private.users_have_blocked(a uuid, b uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.user_blocks ub
    where (ub.blocker_id = a and ub.blocked_id = b)
       or (ub.blocker_id = b and ub.blocked_id = a)
  );
$$;
revoke all on function private.users_have_blocked(uuid, uuid) from public, anon;
grant execute on function private.users_have_blocked(uuid, uuid) to authenticated;

create or replace function private.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists profiles_set_updated_at on public.profiles;
create trigger profiles_set_updated_at before update on public.profiles
for each row execute function private.set_updated_at();
drop trigger if exists goals_set_updated_at on public.goals;
create trigger goals_set_updated_at before update on public.goals
for each row execute function private.set_updated_at();
drop trigger if exists match_requests_set_updated_at on public.match_requests;
create trigger match_requests_set_updated_at before update on public.match_requests
for each row execute function private.set_updated_at();

create or replace function private.guard_match_request_update()
returns trigger
language plpgsql
set search_path = ''
as $guard$
begin
  if auth.uid() is distinct from old.receiver_id then
    raise exception 'Only the request recipient may respond to a connection request';
  end if;
  if new.sender_id is distinct from old.sender_id
     or new.receiver_id is distinct from old.receiver_id
     or new.goal_id is distinct from old.goal_id
     or new.message is distinct from old.message
     or new.created_at is distinct from old.created_at then
    raise exception 'Connection request details cannot be changed';
  end if;
  if old.status <> 'pending' or new.status not in ('accepted', 'rejected') then
    raise exception 'Only pending requests may be accepted or rejected';
  end if;
  return new;
end;
$guard$;
revoke all on function private.guard_match_request_update() from public, anon, authenticated;

drop trigger if exists match_requests_guard_update on public.match_requests;
create trigger match_requests_guard_update before update on public.match_requests
for each row execute function private.guard_match_request_update();

create or replace function private.add_room_owner_member()
returns trigger
language plpgsql
security definer
set search_path = ''
as $owner$
begin
  insert into public.room_members (room_id, user_id, role)
  values (new.id, new.owner_id, 'owner')
  on conflict (room_id, user_id) do nothing;
  return new;
end;
$owner$;
revoke all on function private.add_room_owner_member() from public, anon, authenticated;

drop trigger if exists rooms_add_owner_member on public.opportunity_rooms;
create trigger rooms_add_owner_member after insert on public.opportunity_rooms
for each row execute function private.add_room_owner_member();

alter table public.profiles enable row level security;
alter table public.goals enable row level security;
alter table public.match_requests enable row level security;
alter table public.opportunity_rooms enable row level security;
alter table public.room_members enable row level security;
alter table public.messages enable row level security;
alter table public.user_blocks enable row level security;
alter table public.user_reports enable row level security;

-- Profiles: authenticated users can see profiles only when neither side has blocked the other.
drop policy if exists profiles_select_visible on public.profiles;
create policy profiles_select_visible on public.profiles
for select to authenticated
using (id = auth.uid() or not private.users_have_blocked(auth.uid(), id));
drop policy if exists profiles_insert_own on public.profiles;
create policy profiles_insert_own on public.profiles
for insert to authenticated with check (id = auth.uid());
drop policy if exists profiles_update_own on public.profiles;
create policy profiles_update_own on public.profiles
for update to authenticated using (id = auth.uid()) with check (id = auth.uid());

-- Goals: public active goals visible to authenticated users unless blocked; owners can see/edit all their goals.
drop policy if exists goals_select_visible on public.goals;
create policy goals_select_visible on public.goals
for select to authenticated
using (
  owner_id = auth.uid()
  or (visibility = 'public' and status = 'active' and not private.users_have_blocked(auth.uid(), owner_id))
);
drop policy if exists goals_insert_own on public.goals;
create policy goals_insert_own on public.goals
for insert to authenticated with check (owner_id = auth.uid());
drop policy if exists goals_update_own on public.goals;
create policy goals_update_own on public.goals
for update to authenticated using (owner_id = auth.uid()) with check (owner_id = auth.uid());
drop policy if exists goals_delete_own on public.goals;
create policy goals_delete_own on public.goals
for delete to authenticated using (owner_id = auth.uid());

-- Connection requests: participants only; the request must target the owner of the referenced public goal.
drop policy if exists matches_select_participants on public.match_requests;
create policy matches_select_participants on public.match_requests
for select to authenticated
using (
  (sender_id = auth.uid() or receiver_id = auth.uid())
  and not private.users_have_blocked(auth.uid(), case when sender_id = auth.uid() then receiver_id else sender_id end)
);
drop policy if exists matches_insert_sender on public.match_requests;
create policy matches_insert_sender on public.match_requests
for insert to authenticated with check (
  sender_id = auth.uid()
  and sender_id <> receiver_id
  and status = 'pending'
  and not private.users_have_blocked(sender_id, receiver_id)
  and exists (
    select 1 from public.goals g
    where g.id = goal_id and g.owner_id = receiver_id
      and g.visibility = 'public' and g.status = 'active'
  )
);
drop policy if exists matches_update_receiver on public.match_requests;
create policy matches_update_receiver on public.match_requests
for update to authenticated
using (receiver_id = auth.uid() and status = 'pending')
with check (receiver_id = auth.uid() and status in ('accepted', 'rejected'));

-- Rooms: public rooms are discoverable; private rooms are only visible to members and their owner.
drop policy if exists rooms_select_visible on public.opportunity_rooms;
create policy rooms_select_visible on public.opportunity_rooms
for select to authenticated
using (
  owner_id = auth.uid()
  or (
    not private.users_have_blocked(auth.uid(), owner_id)
    and (
      is_public
      or exists (select 1 from public.room_members rm where rm.room_id = opportunity_rooms.id and rm.user_id = auth.uid())
    )
  )
);
drop policy if exists rooms_insert_owner on public.opportunity_rooms;
create policy rooms_insert_owner on public.opportunity_rooms
for insert to authenticated with check (owner_id = auth.uid());
drop policy if exists rooms_update_owner on public.opportunity_rooms;
create policy rooms_update_owner on public.opportunity_rooms
for update to authenticated using (owner_id = auth.uid()) with check (owner_id = auth.uid());
drop policy if exists rooms_delete_owner on public.opportunity_rooms;
create policy rooms_delete_owner on public.opportunity_rooms
for delete to authenticated using (owner_id = auth.uid());

-- A user can only join as themselves, and only to a public room (or their own room).
drop policy if exists room_members_select_self on public.room_members;
create policy room_members_select_self on public.room_members
for select to authenticated using (user_id = auth.uid());
drop policy if exists room_members_insert_self on public.room_members;
create policy room_members_insert_self on public.room_members
for insert to authenticated with check (
  user_id = auth.uid()
  and role = 'member'
  and exists (
    select 1 from public.opportunity_rooms r
    where r.id = room_members.room_id and (r.is_public or r.owner_id = auth.uid())
      and not private.users_have_blocked(auth.uid(), r.owner_id)
  )
);
drop policy if exists room_members_delete_self on public.room_members;
create policy room_members_delete_self on public.room_members
for delete to authenticated using (user_id = auth.uid());

-- Messages are available only to current members. The sender must be the authenticated user.
drop policy if exists messages_select_member on public.messages;
create policy messages_select_member on public.messages
for select to authenticated using (
  exists (select 1 from public.room_members rm where rm.room_id = messages.room_id and rm.user_id = auth.uid())
  and not exists (
    select 1 from public.opportunity_rooms r
    where r.id = messages.room_id and private.users_have_blocked(auth.uid(), r.owner_id)
  )
);
drop policy if exists messages_insert_member on public.messages;
create policy messages_insert_member on public.messages
for insert to authenticated with check (
  sender_id = auth.uid()
  and exists (select 1 from public.room_members rm where rm.room_id = messages.room_id and rm.user_id = auth.uid())
  and not exists (
    select 1 from public.opportunity_rooms r
    where r.id = messages.room_id and private.users_have_blocked(auth.uid(), r.owner_id)
  )
);

-- Blocking is private to the blocker. A block is insertable/removable only by the blocker.
drop policy if exists blocks_select_own on public.user_blocks;
create policy blocks_select_own on public.user_blocks
for select to authenticated using (blocker_id = auth.uid());
drop policy if exists blocks_insert_own on public.user_blocks;
create policy blocks_insert_own on public.user_blocks
for insert to authenticated with check (blocker_id = auth.uid() and blocker_id <> blocked_id);
drop policy if exists blocks_delete_own on public.user_blocks;
create policy blocks_delete_own on public.user_blocks
for delete to authenticated using (blocker_id = auth.uid());

-- Reports can be submitted by a signed-in user and only read by the reporter.
-- Moderators must review reports through a trusted server/admin process.
drop policy if exists reports_insert_own on public.user_reports;
create policy reports_insert_own on public.user_reports
for insert to authenticated with check (
  reporter_id = auth.uid()
  and reported_id is not null
  and reported_id <> auth.uid()
);
drop policy if exists reports_select_own on public.user_reports;
create policy reports_select_own on public.user_reports
for select to authenticated using (reporter_id = auth.uid());

-- Grant only the operations the app needs; RLS remains the deciding access layer.
grant select, insert, update on public.profiles to authenticated;
grant select, insert, update, delete on public.goals to authenticated;
grant select, insert, update on public.match_requests to authenticated;
grant select, insert, update, delete on public.opportunity_rooms to authenticated;
grant select, insert, delete on public.room_members to authenticated;
grant select, insert on public.messages to authenticated;
grant select, insert, delete on public.user_blocks to authenticated;
grant select, insert on public.user_reports to authenticated;

-- Avoid accidentally exposing these tables to anonymous API calls.
revoke all on public.profiles, public.goals, public.match_requests,
  public.opportunity_rooms, public.room_members, public.messages,
  public.user_blocks, public.user_reports from anon;
