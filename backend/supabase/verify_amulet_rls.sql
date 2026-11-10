-- Run in the dashboard as postgres. All fixture writes roll back.
-- Uses only existing account IDs; no existing rows are modified.
begin;
create temporary table amulet_verification_result(result text) on commit drop;
do $$
declare
    actors uuid[];
    own_id uuid := gen_random_uuid();
    gift_id uuid := gen_random_uuid();
    gift_path text;
begin
    select array_agg(id) into actors from (select id from auth.users order by id limit 2) u;
    if coalesce(array_length(actors, 1), 0) < 2 then
        raise exception 'Two existing test accounts are required';
    end if;
    gift_path := actors[1]::text || '/' || gift_id::text || '.png';
    insert into public.amulets(id, creator_id, image_path, fortune_summary, record,
                              day_message, action_advice, lucky_color, created_date)
    values (own_id, actors[1], actors[1]::text || '/' || own_id::text || '.png',
            'Test fortune', 'Test record', 'Test interpretation', 'Test advice', 'green', current_date),
           (gift_id, actors[1], gift_path,
            'Test gift', 'Test record', 'Test interpretation', 'Test advice', 'green', current_date);
    insert into public.amulet_library(owner_id, amulet_id) values (actors[1], own_id);
    insert into public.amulet_share_links(amulet_id, sender_id, token_hash)
        values (gift_id, actors[1], repeat('a', 64));

    perform set_config('request.jwt.claim.sub', actors[1]::text, true);
    set local role authenticated;
    if (select count(*) from public.amulets where id in (own_id, gift_id)) <> 1 then
        raise exception 'Creator library RLS failed';
    end if;
    if amulet_private.can_read_image(gift_path) then
        raise exception 'Unclaimed gift image must not be readable by sender';
    end if;
    if amulet_private.can_delete_orphan(gift_path) then
        raise exception 'Committed gift must not be deletable as orphan';
    end if;

    perform set_config('request.jwt.claim.sub', actors[2]::text, true);
    if (select count(*) from public.amulets where id in (own_id, gift_id)) <> 0 then
        raise exception 'Unclaimed gift RLS failed';
    end if;
    if public.claim_amulet(repeat('a', 64)) <> gift_id then
        raise exception 'Claim result failed';
    end if;
    if public.claim_amulet(repeat('a', 64)) <> gift_id then
        raise exception 'Idempotent claim failed';
    end if;
    if (select count(*) from public.amulet_library where amulet_id=gift_id) <> 1 then
        raise exception 'Duplicate library entry';
    end if;
    if not amulet_private.can_read_image(gift_path) then
        raise exception 'Recipient cannot read gift image';
    end if;
    if (select count(*) from public.amulets where id in (own_id, gift_id)) <> 1 then
        raise exception 'Recipient can see another account own amulet';
    end if;

    perform set_config('request.jwt.claim.sub', actors[1]::text, true);
    begin
        perform public.claim_amulet(repeat('a', 64));
        raise exception 'Second account was allowed to claim' using errcode='XX000';
    exception when sqlstate 'P0001' then
        if sqlerrm <> 'AMULET_ALREADY_CLAIMED' then raise; end if;
    end;
    reset role;
    insert into amulet_verification_result values ('PASS: owner RLS, gift isolation, recipient access, repeat claim, second-account rejection, committed-image deletion denied');
end;
$$;
select result from amulet_verification_result;
rollback;
