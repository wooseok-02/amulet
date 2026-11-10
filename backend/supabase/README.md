# Supabase migrations

## Applied migrations

- `migrations/202610110001_create_profiles.sql` (`create_profiles`)
- `migrations/20261012001826_lock_down_profile_trigger_functions.sql`
  (`lock_down_profile_trigger_functions`)
- `migrations/20261012002000_restrict_profiles_table_privileges.sql`
  (`restrict_profiles_table_privileges`)
- `migrations/202610120001_create_daily_fortunes.sql`
  (`create_daily_fortunes`)
- `migrations/202610120002_restrict_daily_fortunes_privileges.sql`
  (`restrict_daily_fortunes_privileges`)
- `migrations/202610120003_make_daily_fortunes_immutable.sql`
  (`make_daily_fortunes_immutable`)
- `migrations/202610120004_drop_daily_fortunes_update_policy.sql`
  (`drop_daily_fortunes_update_policy`)

These migrations were applied through Supabase MCP. They create
`public.profiles`, its RLS policies, the
`updated_at` trigger, and the `auth.users` trigger that creates a profile from
signup metadata. They also revoke direct access to the trigger functions and
limit the `authenticated` table grants to `SELECT` and `UPDATE`.

The daily fortune migrations create `public.daily_fortunes` with one row per
user and Korea calendar date. RLS allows authenticated users to select and
insert only their own rows. Existing rows cannot be updated or deleted through
the authenticated role.

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

## 부적 저장·공유 — 2026-11-10 적용

`migrations/202611100001_create_amulet_storage_and_sharing.sql` 구성을 실제 대시보드
SQL Editor에서 적용하고 이력 `202611100001`로 기록했다. 기존 사용자·운세 테이블은 변경하지 않았다.
새 테이블은 `amulets`, `amulet_library`, `amulet_share_links`이며 비공개
Storage 버킷 `amulet-images`를 사용한다. 모든 테이블에 RLS가 적용된다.

로그인한 사용자의 JWT와 기존 Publishable key로 Storage·RPC를 호출한다.
관리자 비밀키는 추가하지 않는다. `save_amulet`은 저장·보관함 추가 또는 선물 링크 생성을,
`claim_amulet`은 수령 계정 확정·보관함 추가를 한 트랜잭션으로 처리한다.
테이블에 대한 authenticated 직접 INSERT/UPDATE/DELETE는 허용하지 않는다.
함수의 검색 경로는 고정하고 anon/PUBLIC 실행 권한을 제거했다.

`verify_amulet_rls.sql`은 기존 계정 두 개의 ID로 검증하며 가상 부적 데이터는
마지막에 모두 롤백한다. 소유자 조회·선물 격리·수령 반복·다른 계정 차단을 실제 DB에서 확인했다.

이미지 저장 실패 시 아직 부적 원본에 연결되지 않은 본인 파일만 정리할 수 있다.
이미 저장된 부적 이미지는 삭제할 수 없다. 수령 링크 토큰 해시와 수령 계정은 직접 조회할 수 없다.

근거: https://supabase.com/docs/guides/database/functions
https://supabase.com/docs/guides/storage/security/access-control
