create table public.daily_fortunes (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles (id) on delete cascade,
    fortune_date date not null,
    headline text not null,
    total_comment text not null,
    total_score smallint not null,
    love_score smallint not null,
    money_score smallint not null,
    career_score smallint not null,
    study_score smallint not null,
    health_score smallint not null,
    lucky_number smallint not null,
    lucky_color text not null,
    today_action text not null,
    created_at timestamp with time zone not null default now(),

    constraint daily_fortunes_user_date_key unique (user_id, fortune_date),
    constraint daily_fortunes_headline_check check (char_length(btrim(headline)) > 0),
    constraint daily_fortunes_total_comment_check check (char_length(btrim(total_comment)) > 0),
    constraint daily_fortunes_total_score_check check (total_score between 0 and 100),
    constraint daily_fortunes_love_score_check check (love_score between 0 and 100),
    constraint daily_fortunes_money_score_check check (money_score between 0 and 100),
    constraint daily_fortunes_career_score_check check (career_score between 0 and 100),
    constraint daily_fortunes_study_score_check check (study_score between 0 and 100),
    constraint daily_fortunes_health_score_check check (health_score between 0 and 100),
    constraint daily_fortunes_lucky_number_check check (lucky_number between 0 and 10),
    constraint daily_fortunes_lucky_color_check check (char_length(btrim(lucky_color)) > 0),
    constraint daily_fortunes_today_action_check check (char_length(btrim(today_action)) > 0)
);

comment on table public.daily_fortunes is
    'One generated fortune per authenticated user and Korea calendar date.';

alter table public.daily_fortunes enable row level security;

revoke all on table public.daily_fortunes from anon;
grant select, insert, update on table public.daily_fortunes to authenticated;

create policy "Users can view their own daily fortunes"
    on public.daily_fortunes
    for select
    to authenticated
    using ((select auth.uid()) = user_id);

create policy "Users can insert their own daily fortunes"
    on public.daily_fortunes
    for insert
    to authenticated
    with check ((select auth.uid()) = user_id);

create policy "Users can update their own daily fortunes"
    on public.daily_fortunes
    for update
    to authenticated
    using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);
