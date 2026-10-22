-- HumanMesh collaboration workflow: goal clarity, tasks, milestones, and evidence-based outcomes.
-- Apply after 0001_humanmesh_schema.sql. RLS stays enabled on all new tables.
alter table public.goals
  add column if not exists can_offer text not null default '' check (char_length(can_offer) <= 1000),
  add column if not exists target_timeline text not null default '' check (char_length(target_timeline) <= 100);

create table if not exists public.project_items (
  id uuid primary key default gen_random_uuid(),
  room_id uuid not null references public.opportunity_rooms(id) on delete cascade,
  created_by uuid not null references auth.users(id) on delete cascade,
  title text not null check (char_length(title) between 3 and 120),
  description text not null default '' check (char_length(description) <= 1000),
  item_type text not null check (item_type in ('task', 'milestone')),
  status text not null default 'open' check (status in ('open', 'completed')),
  due_date date,
  created_at timestamptz not null default now(),
  completed_at timestamptz
);
create index if not exists project_items_room_idx on public.project_items(room_id, item_type, status, created_at);

create table if not exists public.goal_outcomes (
  id uuid primary key default gen_random_uuid(),
  room_id uuid not null references public.opportunity_rooms(id) on delete cascade,
  author_id uuid not null references auth.users(id) on delete cascade,
  title text not null check (char_length(title) between 3 and 120),
  summary text not null check (char_length(summary) between 10 and 1500),
  created_at timestamptz not null default now()
);
create index if not exists goal_outcomes_room_idx on public.goal_outcomes(room_id, created_at desc);

create table if not exists public.outcome_confirmations (
  outcome_id uuid not null references public.goal_outcomes(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (outcome_id, user_id)
);

create or replace function private.guard_project_item_update()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.room_id is distinct from old.room_id
     or new.created_by is distinct from old.created_by
     or new.item_type is distinct from old.item_type
     or new.title is distinct from old.title
     or new.description is distinct from old.description
     or new.due_date is distinct from old.due_date
     or new.created_at is distinct from old.created_at then
    raise exception 'Project item details cannot be changed through status updates';
  end if;
  if new.status = 'completed' then
    new.completed_at = coalesce(old.completed_at, now());
  else
    new.completed_at = null;
  end if;
  return new;
end;
$$;
revoke all on function private.guard_project_item_update() from public, anon, authenticated;
drop trigger if exists project_items_guard_update on public.project_items;
create trigger project_items_guard_update before update on public.project_items
for each row execute function private.guard_project_item_update();

alter table public.project_items enable row level security;
alter table public.goal_outcomes enable row level security;
alter table public.outcome_confirmations enable row level security;

drop policy if exists project_items_select_members on public.project_items;
create policy project_items_select_members on public.project_items
for select to authenticated using (
  exists (select 1 from public.room_members rm where rm.room_id = project_items.room_id and rm.user_id = auth.uid())
);
drop policy if exists project_items_insert_members on public.project_items;
create policy project_items_insert_members on public.project_items
for insert to authenticated with check (
  created_by = auth.uid()
  and exists (select 1 from public.room_members rm where rm.room_id = project_items.room_id and rm.user_id = auth.uid())
);
drop policy if exists project_items_update_members on public.project_items;
create policy project_items_update_members on public.project_items
for update to authenticated
using (exists (select 1 from public.room_members rm where rm.room_id = project_items.room_id and rm.user_id = auth.uid()))
with check (exists (select 1 from public.room_members rm where rm.room_id = project_items.room_id and rm.user_id = auth.uid()));
drop policy if exists project_items_delete_creator on public.project_items;
create policy project_items_delete_creator on public.project_items
for delete to authenticated using (
  created_by = auth.uid()
  and exists (select 1 from public.room_members rm where rm.room_id = project_items.room_id and rm.user_id = auth.uid())
);

drop policy if exists goal_outcomes_select_members on public.goal_outcomes;
create policy goal_outcomes_select_members on public.goal_outcomes
for select to authenticated using (
  exists (select 1 from public.room_members rm where rm.room_id = goal_outcomes.room_id and rm.user_id = auth.uid())
);
drop policy if exists goal_outcomes_insert_members on public.goal_outcomes;
create policy goal_outcomes_insert_members on public.goal_outcomes
for insert to authenticated with check (
  author_id = auth.uid()
  and exists (select 1 from public.room_members rm where rm.room_id = goal_outcomes.room_id and rm.user_id = auth.uid())
);
drop policy if exists goal_outcomes_update_author on public.goal_outcomes;
create policy goal_outcomes_update_author on public.goal_outcomes
for update to authenticated using (author_id = auth.uid()) with check (author_id = auth.uid());
drop policy if exists goal_outcomes_delete_author on public.goal_outcomes;
create policy goal_outcomes_delete_author on public.goal_outcomes
for delete to authenticated using (author_id = auth.uid());

drop policy if exists outcome_confirmations_select_members on public.outcome_confirmations;
create policy outcome_confirmations_select_members on public.outcome_confirmations
for select to authenticated using (
  exists (
    select 1 from public.goal_outcomes go
    join public.room_members rm on rm.room_id = go.room_id
    where go.id = outcome_confirmations.outcome_id and rm.user_id = auth.uid()
  )
);
drop policy if exists outcome_confirmations_insert_members on public.outcome_confirmations;
create policy outcome_confirmations_insert_members on public.outcome_confirmations
for insert to authenticated with check (
  user_id = auth.uid()
  and exists (
    select 1 from public.goal_outcomes go
    join public.room_members rm on rm.room_id = go.room_id
    where go.id = outcome_confirmations.outcome_id
      and rm.user_id = auth.uid()
      and go.author_id <> auth.uid()
  )
);
-- Confirmations are immutable; duplicate confirmations are prevented by the primary key.

grant select, insert, update, delete on public.project_items to authenticated;
grant select, insert, update, delete on public.goal_outcomes to authenticated;
grant select, insert on public.outcome_confirmations to authenticated;
revoke all on public.project_items, public.goal_outcomes, public.outcome_confirmations from anon;
