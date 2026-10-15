-- Trazo redesign (15/10/2026): user names, profile bio, live trails and friend search.
-- Additive only: no table, column or row is dropped or rewritten, so it is safe to run on a
-- database that already has users and trips. It can also be run more than once.
-- How to apply: Supabase dashboard → SQL Editor → paste this whole file → Run.

-- ---------------------------------------------------------------------------
-- Profiles: optional unique user name and a short public bio
-- ---------------------------------------------------------------------------

alter table public.profiles add column if not exists username text;
alter table public.profiles add column if not exists bio text;

do $$ begin
  if not exists (select 1 from pg_constraint where conname = 'profiles_username_format') then
    alter table public.profiles add constraint profiles_username_format
      check (username is null or username ~ '^[a-z0-9_.]{3,20}$');
  end if;
  if not exists (select 1 from pg_constraint where conname = 'profiles_bio_length') then
    alter table public.profiles add constraint profiles_bio_length check (bio is null or char_length(bio) <= 140);
  end if;
end $$;

create unique index if not exists profiles_username_unique on public.profiles (lower(username)) where username is not null;

-- Live positions older than the retention window are purged by updated_at.
create index if not exists live_locations_updated on public.live_locations (updated_at);

-- ---------------------------------------------------------------------------
-- Friend search by user name. Reveals only name, user name and photo, never a location,
-- and only on an exact name or a prefix of at least 3 characters.
-- ---------------------------------------------------------------------------

create or replace function public.find_users(p_query text)
returns table (id uuid, display_name text, username text, avatar_url text, relation text)
language plpgsql stable security definer set search_path = '' as $$
declare
  me uuid := public.require_user();
  q text := lower(trim(leading '@' from trim(coalesce(p_query, ''))));
begin
  if char_length(q) < 3 or q !~ '^[a-z0-9_.]+$' then return; end if;
  return query
    select p.id, p.display_name, p.username, p.avatar_url,
      case
        when public.are_friends(me, p.id) then 'friend'
        when exists (select 1 from public.friend_requests r where r.status = 'pending' and r.from_id = me and r.to_id = p.id) then 'pending_out'
        when exists (select 1 from public.friend_requests r where r.status = 'pending' and r.from_id = p.id and r.to_id = me) then 'pending_in'
        else 'none'
      end
    from public.profiles p
    where p.username is not null and p.id <> me
      and (p.username = q or p.username like replace(q, '_', '\_') || '%')
    order by (p.username = q) desc, p.username
    limit 10;
end $$;

-- Same rules as send_friend_request(code), for someone found by user name.
create or replace function public.send_friend_request_to(p_user uuid)
returns table (request_id uuid, status text, display_name text)
language plpgsql security definer set search_path = '' as $$
declare
  me uuid := public.require_user();
  existing public.friend_requests;
begin
  if (select count(*) from public.friend_requests where from_id = me and created_at > now() - interval '1 hour') >= 30 then
    raise exception 'too many requests, try later' using errcode = '54000';
  end if;
  if p_user is null or not exists (select 1 from public.profiles p where p.id = p_user) then
    raise exception 'user not found' using errcode = 'P0002';
  end if;
  if p_user = me then raise exception 'that is you' using errcode = '22023'; end if;
  display_name := (select p.display_name from public.profiles p where p.id = p_user);
  if public.are_friends(me, p_user) then
    request_id := null; status := 'already_friends'; return next; return;
  end if;
  select * into existing from public.friend_requests fr
    where fr.status = 'pending' and least(fr.from_id, fr.to_id) = least(me, p_user) and greatest(fr.from_id, fr.to_id) = greatest(me, p_user);
  if found then
    if existing.to_id = me then
      perform public.respond_friend_request(existing.id, true);
      request_id := existing.id; status := 'accepted'; return next; return;
    end if;
    request_id := existing.id; status := 'pending'; return next; return;
  end if;
  insert into public.friend_requests (from_id, to_id) values (me, p_user) returning id into request_id;
  status := 'pending';
  return next;
end $$;

-- ---------------------------------------------------------------------------
-- Live trail: the measured points of the trip a person is driving right now, so a friend
-- who opens the map sees the line drawn so far and not only what arrives after.
-- Allowed only while the owner shares their live location with the viewer (same rule as
-- the position itself), is not in ghost mode or paused, and only for the trip in progress.
-- ---------------------------------------------------------------------------

create or replace function public.live_trail(p_user uuid)
returns table (t timestamptz, lat double precision, lng double precision, accuracy_m real)
language plpgsql stable security definer set search_path = '' as $$
declare
  me uuid := public.require_user();
  trip uuid;
begin
  if not public.can_see_location(me, p_user) then return; end if;
  select l.trip_id into trip from public.live_locations l
    join public.trips tr on tr.id = l.trip_id and tr.owner_id = l.user_id and tr.status = 'recording'
    where l.user_id = p_user and l.sharing and l.trip_id is not null;
  if trip is null then return; end if;
  return query
    select x.t, x.lat, x.lng, x.accuracy_m from (
      select p.seq, p.t, p.lat, p.lng, p.accuracy_m from public.trip_points p
      where p.trip_id = trip and p.flag = 0 and not p.mock and p.t > now() - interval '12 hours'
      order by p.seq desc limit 5000
    ) x order by x.seq;
end $$;

revoke execute on function public.find_users(text), public.send_friend_request_to(uuid), public.live_trail(uuid) from public, anon;
grant execute on function public.find_users(text), public.send_friend_request_to(uuid), public.live_trail(uuid) to authenticated;

-- ---------------------------------------------------------------------------
-- Profile photos: small images only (the app sends ~512 px JPEGs of ~60 KB).
-- ---------------------------------------------------------------------------

do $$ begin
  if exists (select 1 from information_schema.columns where table_schema = 'storage' and table_name = 'buckets' and column_name = 'file_size_limit') then
    update storage.buckets set file_size_limit = 2097152, allowed_mime_types = array['image/jpeg', 'image/png', 'image/webp']
      where id = 'avatars';
  end if;
end $$;
