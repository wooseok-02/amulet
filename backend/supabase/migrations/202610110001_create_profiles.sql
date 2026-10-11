create table public.profiles (
    id uuid primary key references auth.users (id) on delete cascade,
    name text not null,
    birth_date date not null,
    calendar_type text not null,
    is_leap_month boolean,
    birth_time time without time zone,
    gender text not null,
    created_at timestamp with time zone not null default now(),
    updated_at timestamp with time zone not null default now(),

    constraint profiles_name_length_check
        check (char_length(btrim(name)) between 2 and 4),
    constraint profiles_birth_date_check
        check (
            extract(year from birth_date) between 1900 and extract(year from current_date)
        ),
    constraint profiles_calendar_type_check
        check (calendar_type in ('SOLAR', 'LUNAR')),
    constraint profiles_leap_month_check
        check (
            (calendar_type = 'SOLAR' and is_leap_month is null)
            or
            (calendar_type = 'LUNAR' and is_leap_month is not null)
        ),
    constraint profiles_gender_check
        check (gender in ('MALE', 'FEMALE'))
);

comment on table public.profiles is 'Service profile linked one-to-one with Supabase Auth user.';
comment on column public.profiles.birth_date is 'Original solar or lunar date selected by the user.';
comment on column public.profiles.is_leap_month is 'NULL for solar, false for regular lunar month, true for lunar leap month.';

alter table public.profiles enable row level security;

revoke all on table public.profiles from anon;
grant select, update on table public.profiles to authenticated;

create policy "Users can view their own profile"
    on public.profiles
    for select
    to authenticated
    using ((select auth.uid()) = id);

create policy "Users can update their own profile"
    on public.profiles
    for update
    to authenticated
    using ((select auth.uid()) = id)
    with check ((select auth.uid()) = id);

create function public.set_profile_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

create trigger set_profile_updated_at
    before update on public.profiles
    for each row
    execute function public.set_profile_updated_at();

create function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.profiles (
        id,
        name,
        birth_date,
        calendar_type,
        is_leap_month,
        birth_time,
        gender
    )
    values (
        new.id,
        btrim(new.raw_user_meta_data ->> 'name'),
        (new.raw_user_meta_data ->> 'birth_date')::date,
        upper(new.raw_user_meta_data ->> 'calendar_type'),
        case
            when upper(new.raw_user_meta_data ->> 'calendar_type') = 'SOLAR' then null
            else (new.raw_user_meta_data ->> 'is_leap_month')::boolean
        end,
        nullif(new.raw_user_meta_data ->> 'birth_time', '')::time,
        upper(new.raw_user_meta_data ->> 'gender')
    );

    return new;
end;
$$;

create trigger on_auth_user_created
    after insert on auth.users
    for each row
    execute function public.handle_new_user();
