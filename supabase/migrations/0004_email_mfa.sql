-- HumanMesh email-code second factor.
-- Apply after 0001_humanmesh_schema.sql, 0002_collaboration_progress.sql and 0003_room_invitations.sql.
-- Keep enforcement off while deploying the Edge Function and enabling the Auth hook.
-- Set enforce_email_mfa = true only after those steps work in a test session.

create table if not exists public.email_mfa_settings (
    singleton boolean primary key default true check (singleton = true),
    enforce_email_mfa boolean not null default false,
    updated_at timestamptz not null default now()
);
insert into public.email_mfa_settings(singleton, enforce_email_mfa)
values (true, false)
on conflict (singleton) do nothing;

create table if not exists public.email_mfa_challenges (
    session_id uuid primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    code_hash text not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    attempts integer not null default 0 check (attempts between 0 and 5),
    verified_at timestamptz
);
create index if not exists email_mfa_challenges_user_idx on public.email_mfa_challenges(user_id, created_at desc);
alter table public.email_mfa_settings enable row level security;
alter table public.email_mfa_challenges enable row level security;
revoke all on public.email_mfa_settings from public, anon, authenticated;
revoke all on public.email_mfa_challenges from public, anon, authenticated;
grant all on public.email_mfa_settings to service_role;
grant all on public.email_mfa_challenges to service_role;

create or replace function private.humanmesh_email_mfa_ok()
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
    select
        not coalesce(
            (select s.enforce_email_mfa from public.email_mfa_settings as s where s.singleton = true),
            false
        )
        or coalesce((auth.jwt() ->> 'humanmesh_email_mfa_verified')::boolean, false);
$function$;
revoke all on function private.humanmesh_email_mfa_ok() from public;
grant execute on function private.humanmesh_email_mfa_ok() to anon, authenticated;

create or replace function public.humanmesh_custom_access_token_hook(event jsonb)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
    claims jsonb := coalesce(event -> 'claims', '{}'::jsonb);
    raw_session_id text := nullif(event -> 'claims' ->> 'session_id', '');
    raw_user_id text := nullif(event ->> 'user_id', '');
    is_verified boolean := false;
begin
    if raw_session_id is not null and raw_user_id is not null then
        select exists (
            select 1 from public.email_mfa_challenges as c
            where c.session_id = raw_session_id::uuid
              and c.user_id = raw_user_id::uuid
              and c.verified_at is not null
        ) into is_verified;
    end if;
    claims := jsonb_set(claims, '{humanmesh_email_mfa_verified}', to_jsonb(is_verified), true);
    return jsonb_build_object('claims', claims);
end;
$function$;
revoke all on function public.humanmesh_custom_access_token_hook(jsonb) from public, anon, authenticated;
grant usage on schema public to supabase_auth_admin;
grant execute on function public.humanmesh_custom_access_token_hook(jsonb) to supabase_auth_admin;

do $block$
declare p record; roles_sql text; create_sql text;
begin
    for p in
        select schemaname, tablename, policyname, permissive, roles, cmd, qual, with_check
        from pg_policies
        where schemaname = 'public'
          and tablename not in ('email_mfa_settings', 'email_mfa_challenges')
    loop
        select string_agg(case when role_name = 'public' then 'PUBLIC' else quote_ident(role_name) end, ', ')
        into roles_sql
        from unnest(p.roles) as rr(role_name);
        execute format('drop policy %I on %I.%I', p.policyname, p.schemaname, p.tablename);
        create_sql := format('create policy %I on %I.%I as %s for %s to %s',
            p.policyname, p.schemaname, p.tablename, p.permissive, p.cmd, coalesce(roles_sql, 'PUBLIC'));
        if p.qual is not null then
            create_sql := create_sql || format(' using (private.humanmesh_email_mfa_ok() and (%s))', p.qual);
        end if;
        if p.with_check is not null then
            create_sql := create_sql || format(' with check (private.humanmesh_email_mfa_ok() and (%s))', p.with_check);
        end if;
        execute create_sql;
    end loop;
end;
$block$;

comment on table public.email_mfa_challenges is
    'Session-bound email verification challenges; only the server-side Edge Function can access this table.';
