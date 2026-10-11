# Supabase migrations

## Applied migrations

- `migrations/202610110001_create_profiles.sql` (`create_profiles`)
- `migrations/20261012001826_lock_down_profile_trigger_functions.sql`
  (`lock_down_profile_trigger_functions`)
- `migrations/20261012002000_restrict_profiles_table_privileges.sql`
  (`restrict_profiles_table_privileges`)

These migrations were applied through Supabase MCP. They create
`public.profiles`, its RLS policies, the
`updated_at` trigger, and the `auth.users` trigger that creates a profile from
signup metadata. They also revoke direct access to the trigger functions and
limit the `authenticated` table grants to `SELECT` and `UPDATE`.

## Preflight

Run these read-only queries against the target project before applying the
migration:

```sql
select
    to_regclass('public.profiles') as profiles_table,
    to_regprocedure('public.set_profile_updated_at()') as updated_at_function,
    to_regprocedure('public.handle_new_user()') as new_user_function;

select count(*) as existing_auth_users
from auth.users;

select version, name
from supabase_migrations.schema_migrations
order by version desc;
```

Expected result:

- All three objects in the first query are `NULL`.
- No migration with the same version or name exists.
- `existing_auth_users` is `0`. If users already exist, decide on a separate
  backfill migration before applying this migration; the new trigger only runs
  for users created afterward.

## Applying to another environment

Apply the migration files in filename order with the Supabase MCP
`apply_migration` tool. Use each filename suffix as the migration name and the
complete file contents as the query.

The MCP connection must have `read_only=false`; `read_only=true` disables
`apply_migration`. Do not use `execute_sql` for this DDL migration.

## Postflight

After applying, verify the objects, RLS, policies, triggers, and grants with
read-only SQL:

```sql
select
    c.relname as table_name,
    c.relrowsecurity as rls_enabled
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'public'
  and c.relname = 'profiles';

select policyname, cmd, roles, qual, with_check
from pg_policies
where schemaname = 'public'
  and tablename = 'profiles'
order by policyname;

select
    event_object_schema,
    event_object_table,
    trigger_name,
    action_timing,
    event_manipulation
from information_schema.triggers
where trigger_name in ('set_profile_updated_at', 'on_auth_user_created')
order by trigger_name;

select grantee, privilege_type
from information_schema.role_table_grants
where table_schema = 'public'
  and table_name = 'profiles'
  and grantee in ('anon', 'authenticated')
order by grantee, privilege_type;
```

Expected result:

- `public.profiles` exists with RLS enabled.
- The two owner-only policies exist for `SELECT` and `UPDATE`.
- Both triggers exist.
- `authenticated` has only `SELECT` and `UPDATE`; `anon` has no table grant.
