-- A signed access JWT can outlive logout. Check its session before serving protected APIs.
create or replace function public.is_current_auth_session_active()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from auth.sessions as session
        where session.id = nullif(auth.jwt() ->> 'session_id', '')::uuid
          and session.user_id = auth.uid()
    );
$$;

revoke all on function public.is_current_auth_session_active() from public, anon;
grant execute on function public.is_current_auth_session_active() to authenticated;
