revoke all on table public.daily_fortunes from anon;
revoke all on table public.daily_fortunes from authenticated;

grant select, insert, update on table public.daily_fortunes to authenticated;
