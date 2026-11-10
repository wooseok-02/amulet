-- Additive migration: existing profiles and fortunes are not changed.
begin;

create table public.amulets (
    id uuid primary key,
    creator_id uuid not null references auth.users(id),
    image_path text not null unique,
    fortune_summary text not null check (char_length(btrim(fortune_summary)) > 0),
    record text not null check (char_length(btrim(record)) between 1 and 200),
    day_message text not null check (char_length(btrim(day_message)) > 0),
    action_advice text not null check (char_length(btrim(action_advice)) > 0),
    lucky_color text not null check (char_length(btrim(lucky_color)) > 0),
    created_date date not null,
    created_at timestamptz not null default now()
);

create table public.amulet_library (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users(id),
    amulet_id uuid not null references public.amulets(id),
    created_at timestamptz not null default now(),
    unique (owner_id, amulet_id)
);

create table public.amulet_share_links (
    id uuid primary key default gen_random_uuid(),
    amulet_id uuid not null unique references public.amulets(id),
    sender_id uuid not null references auth.users(id),
    token_hash text not null unique check (token_hash ~ '^[0-9a-f]{64}$'),
    recipient_id uuid references auth.users(id),
    created_at timestamptz not null default now()
);

create index amulets_created_date_idx on public.amulets(created_date, id);
create index amulet_library_amulet_id_idx on public.amulet_library(amulet_id);
create index amulet_share_links_sender_idx on public.amulet_share_links(sender_id);
create index amulet_share_links_recipient_idx on public.amulet_share_links(recipient_id);

alter table public.amulets enable row level security;
alter table public.amulet_library enable row level security;
alter table public.amulet_share_links enable row level security;

revoke all on public.amulets, public.amulet_library, public.amulet_share_links
    from anon, authenticated;
grant select on public.amulets, public.amulet_library to authenticated;

create policy "Read own amulet library" on public.amulet_library
    for select to authenticated using (owner_id = (select auth.uid()));
create policy "Read amulets in own library" on public.amulets
    for select to authenticated using (
        exists (select 1 from public.amulet_library l
                where l.amulet_id = amulets.id and l.owner_id = (select auth.uid()))
    );

-- Definer is required for atomic writes without granting direct table writes.
-- The identity always comes from auth.uid(), never a caller-supplied owner ID.
create function public.save_amulet(
    p_id uuid, p_image_path text, p_content jsonb, p_token_hash text default null
) returns uuid language plpgsql security definer set search_path = '' as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then
        raise exception 'AUTH_REQUIRED' using errcode = '28000';
    end if;
    if p_image_path is distinct from (v_user::text || '/' || p_id::text || '.png')
       or not exists (
           select 1 from storage.objects o
           where o.bucket_id = 'amulet-images' and o.name = p_image_path
       ) then
        raise exception 'INVALID_IMAGE_PATH' using errcode = '22023';
    end if;

    insert into public.amulets (
        id, creator_id, image_path, fortune_summary, record,
        day_message, action_advice, lucky_color, created_date
    ) values (
        p_id, v_user, p_image_path, p_content->>'fortune_summary', p_content->>'record',
        p_content->>'day_message', p_content->>'action_advice',
        p_content->>'lucky_color', (p_content->>'created_date')::date
    );
    if p_token_hash is null then
        insert into public.amulet_library(owner_id, amulet_id) values (v_user, p_id);
    else
        insert into public.amulet_share_links(amulet_id, sender_id, token_hash)
        values (p_id, v_user, p_token_hash);
    end if;
    return p_id;
end;
$$;

create function public.claim_amulet(p_token_hash text)
returns uuid language plpgsql security definer set search_path = '' as $$
declare
    v_user uuid := auth.uid();
    v_link public.amulet_share_links%rowtype;
begin
    if v_user is null then
        raise exception 'AUTH_REQUIRED' using errcode = '28000';
    end if;
    select * into v_link from public.amulet_share_links
        where token_hash = p_token_hash for update;
    if not found then
        raise exception 'AMULET_LINK_NOT_FOUND' using errcode = 'P0002';
    end if;
    if v_link.recipient_id is not null and v_link.recipient_id <> v_user then
        raise exception 'AMULET_ALREADY_CLAIMED' using errcode = 'P0001';
    end if;
    update public.amulet_share_links set recipient_id = v_user where id = v_link.id;
    insert into public.amulet_library(owner_id, amulet_id)
        values (v_user, v_link.amulet_id)
        on conflict (owner_id, amulet_id) do nothing;
    return v_link.amulet_id;
end;
$$;

revoke all on function public.save_amulet(uuid, text, jsonb, text) from public, anon, authenticated;
revoke all on function public.claim_amulet(text) from public, anon, authenticated;
grant execute on function public.save_amulet(uuid, text, jsonb, text) to authenticated;
grant execute on function public.claim_amulet(text) to authenticated;

-- Storage helpers must bypass amulets RLS to distinguish orphan uploads from
-- committed friend gifts that the sender cannot read through their library.
create schema if not exists amulet_private;
revoke all on schema amulet_private from public, anon;
grant usage on schema amulet_private to authenticated;

create function amulet_private.can_read_image(p_path text)
returns boolean language sql stable security definer set search_path = '' as $$
    select auth.uid() is not null and (
        exists (select 1 from public.amulets a join public.amulet_library l
                on l.amulet_id = a.id
                where a.image_path = p_path and l.owner_id = auth.uid())
        or (
            split_part(p_path, '/', 1) = auth.uid()::text
            and not exists (select 1 from public.amulets a where a.image_path = p_path)
        )
    );
$$;

create function amulet_private.can_delete_orphan(p_path text)
returns boolean language sql stable security definer set search_path = '' as $$
    select auth.uid() is not null
        and split_part(p_path, '/', 1) = auth.uid()::text
        and not exists (select 1 from public.amulets a where a.image_path = p_path);
$$;

revoke all on function amulet_private.can_read_image(text) from public, anon, authenticated;
revoke all on function amulet_private.can_delete_orphan(text) from public, anon, authenticated;
grant execute on function amulet_private.can_read_image(text) to authenticated;
grant execute on function amulet_private.can_delete_orphan(text) to authenticated;

insert into storage.buckets(id, name, public, file_size_limit, allowed_mime_types)
values ('amulet-images', 'amulet-images', false, 5242880, array['image/png']);

create policy "Upload own amulet PNG" on storage.objects
    for insert to authenticated with check (
        bucket_id = 'amulet-images'
        and (storage.foldername(name))[1] = (select auth.uid())::text
        and name ~ '^[0-9a-f-]{36}/[0-9a-f-]{36}\.png$'
    );
create policy "Read owned amulet images" on storage.objects
    for select to authenticated using (
        bucket_id = 'amulet-images' and amulet_private.can_read_image(name)
    );
create policy "Clean up own failed amulet uploads" on storage.objects
    for delete to authenticated using (
        bucket_id = 'amulet-images' and amulet_private.can_delete_orphan(name)
    );

notify pgrst, 'reload schema';
commit;
