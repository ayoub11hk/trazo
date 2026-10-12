-- Trazo: schema, privacy rules and API for the social drive tracker.
-- Every feature is free: there are no plans, entitlements, quotas or premium checks.
-- All access control is enforced here (row-level security + checked RPCs), never only in the app.

create extension if not exists pgcrypto with schema extensions;

-- ---------------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------------

-- Crockford base-32 (no I, L, O, U); must match app.trazo.core.social.InviteCode.
create or replace function public.random_code(len int default 8)
returns text language plpgsql volatile set search_path = '' as $$
declare
  alphabet constant text := '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
  bytes bytea := extensions.gen_random_bytes(len);
  result text := '';
begin
  for i in 0 .. len - 1 loop
    result := result || substr(alphabet, (get_byte(bytes, i) % 32) + 1, 1);
  end loop;
  return result;
end $$;

create or replace function public.normalize_code(input text)
returns text language sql immutable set search_path = '' as $$
  select translate(upper(regexp_replace(coalesce(input, ''), '[^A-Za-z0-9]', '', 'g')), 'OILU', '011V')
$$;

create or replace function public.touch_updated_at()
returns trigger language plpgsql set search_path = '' as $$
begin
  new.updated_at := now();
  return new;
end $$;

-- ---------------------------------------------------------------------------
-- Profiles (visible to connections) and private settings (owner only)
-- ---------------------------------------------------------------------------

create table public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  display_name text not null check (char_length(display_name) between 1 and 40),
  avatar_url text,
  active_vehicle_id uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.user_settings (
  user_id uuid primary key references auth.users (id) on delete cascade,
  invite_code text not null unique,
  ghost_mode boolean not null default false,
  share_speed boolean not null default true,
  default_trip_visibility text not null default 'friends' check (default_trip_visibility in ('private', 'friends')),
  stats_visible boolean not null default true,
  -- null = keep forever; otherwise trips older than this are purged.
  retention_days int check (retention_days is null or retention_days between 7 and 3650),
  updated_at timestamptz not null default now()
);

-- Codes are globally unique across friends, groups and convoys so one input box can resolve any.
create table public.invite_codes (
  code text primary key,
  kind text not null check (kind in ('friend', 'group', 'convoy')),
  target_id uuid not null,
  created_at timestamptz not null default now(),
  unique (kind, target_id)
);

create or replace function public.issue_code(p_kind text, p_target uuid)
returns text language plpgsql volatile security definer set search_path = '' as $$
declare c text;
begin
  delete from public.invite_codes where kind = p_kind and target_id = p_target;
  loop
    c := public.random_code(8);
    begin
      insert into public.invite_codes (code, kind, target_id) values (c, p_kind, p_target);
      return c;
    exception when unique_violation then
      -- astronomically unlikely collision: try another
    end;
  end loop;
end $$;

create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  name text := coalesce(nullif(trim(new.raw_user_meta_data ->> 'display_name'), ''), split_part(coalesce(new.email, 'Conductor'), '@', 1));
begin
  insert into public.profiles (id, display_name) values (new.id, left(name, 40));
  insert into public.user_settings (user_id, invite_code) values (new.id, public.issue_code('friend', new.id));
  return new;
end $$;

create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

create trigger profiles_touch before update on public.profiles for each row execute function public.touch_updated_at();
create trigger settings_touch before update on public.user_settings for each row execute function public.touch_updated_at();

-- Settings rows may not change their invite code directly (use regenerate_invite_code()).
create or replace function public.guard_settings()
returns trigger language plpgsql set search_path = '' as $$
begin
  -- Only the API role is restricted; regenerate_invite_code() runs as the owner.
  if current_user in ('authenticated', 'anon') and
     (new.invite_code is distinct from old.invite_code or new.user_id is distinct from old.user_id) then
    raise exception 'invite_code and user_id are read-only' using errcode = '42501';
  end if;
  return new;
end $$;
create trigger settings_guard before update on public.user_settings for each row execute function public.guard_settings();

-- ---------------------------------------------------------------------------
-- Friends
-- ---------------------------------------------------------------------------

create table public.friend_requests (
  id uuid primary key default gen_random_uuid(),
  from_id uuid not null references auth.users (id) on delete cascade,
  to_id uuid not null references auth.users (id) on delete cascade,
  status text not null default 'pending' check (status in ('pending', 'accepted', 'rejected', 'cancelled')),
  created_at timestamptz not null default now(),
  responded_at timestamptz,
  check (from_id <> to_id)
);
create unique index friend_requests_one_pending on public.friend_requests (least(from_id, to_id), greatest(from_id, to_id)) where status = 'pending';
create index friend_requests_to on public.friend_requests (to_id) where status = 'pending';

-- One row per direction: what user_id shares with friend_id. Each side controls its own row.
create table public.friendships (
  user_id uuid not null references auth.users (id) on delete cascade,
  friend_id uuid not null references auth.users (id) on delete cascade,
  share_location boolean not null default true,
  share_trips boolean not null default true,
  created_at timestamptz not null default now(),
  primary key (user_id, friend_id),
  check (user_id <> friend_id)
);
create index friendships_friend on public.friendships (friend_id);

-- ---------------------------------------------------------------------------
-- Groups
-- ---------------------------------------------------------------------------

create table public.groups (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(name) between 1 and 50),
  owner_id uuid not null references auth.users (id) on delete cascade,
  created_at timestamptz not null default now()
);

create table public.group_members (
  group_id uuid not null references public.groups (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  role text not null default 'member' check (role in ('owner', 'member')),
  share_location boolean not null default true,
  joined_at timestamptz not null default now(),
  primary key (group_id, user_id)
);
create index group_members_user on public.group_members (user_id);

-- ---------------------------------------------------------------------------
-- Convoys (shared drives)
-- ---------------------------------------------------------------------------

create table public.convoys (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(name) between 1 and 50),
  owner_id uuid not null references auth.users (id) on delete cascade,
  status text not null default 'active' check (status in ('active', 'ended')),
  created_at timestamptz not null default now(),
  ended_at timestamptz
);

create table public.convoy_members (
  convoy_id uuid not null references public.convoys (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  joined_at timestamptz not null default now(),
  left_at timestamptz,
  -- Whether this member's convoy trip stays viewable by the other members afterwards.
  share_trip boolean not null default true,
  primary key (convoy_id, user_id)
);
create index convoy_members_user on public.convoy_members (user_id);

create table public.convoy_invites (
  id uuid primary key default gen_random_uuid(),
  convoy_id uuid not null references public.convoys (id) on delete cascade,
  from_id uuid not null references auth.users (id) on delete cascade,
  to_id uuid not null references auth.users (id) on delete cascade,
  status text not null default 'pending' check (status in ('pending', 'accepted', 'declined')),
  created_at timestamptz not null default now(),
  unique (convoy_id, to_id)
);

-- ---------------------------------------------------------------------------
-- Vehicles (garage) — unlimited
-- ---------------------------------------------------------------------------

create table public.vehicles (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users (id) on delete cascade,
  name text not null check (char_length(name) between 1 and 40),
  make text check (char_length(make) <= 40),
  model text check (char_length(model) <= 60),
  year int check (year between 1886 and 2100),
  color text not null default '#C8D1DC' check (color ~ '^#[0-9A-Fa-f]{6}$'),
  body_type text not null default 'sedan',
  photo_url text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index vehicles_owner on public.vehicles (owner_id);
create trigger vehicles_touch before update on public.vehicles for each row execute function public.touch_updated_at();

alter table public.profiles add constraint profiles_active_vehicle_fk
  foreign key (active_vehicle_id) references public.vehicles (id) on delete set null;

create or replace function public.guard_active_vehicle()
returns trigger language plpgsql set search_path = '' as $$
begin
  if new.active_vehicle_id is not null and not exists (
    select 1 from public.vehicles v where v.id = new.active_vehicle_id and v.owner_id = new.id
  ) then
    raise exception 'active vehicle must belong to the user' using errcode = '42501';
  end if;
  return new;
end $$;
create trigger profiles_vehicle_guard before insert or update of active_vehicle_id on public.profiles
  for each row execute function public.guard_active_vehicle();

-- ---------------------------------------------------------------------------
-- Trips and their points
-- ---------------------------------------------------------------------------

create table public.trips (
  id uuid primary key,                       -- generated on the phone so offline trips sync idempotently
  owner_id uuid not null references auth.users (id) on delete cascade,
  vehicle_id uuid references public.vehicles (id) on delete set null,
  convoy_id uuid references public.convoys (id) on delete set null,
  status text not null default 'recording' check (status in ('recording', 'completed')),
  visibility text not null default 'friends' check (visibility in ('private', 'friends')),
  started_at timestamptz not null,
  ended_at timestamptz,
  distance_m double precision not null default 0 check (distance_m >= 0),
  measured_distance_m double precision not null default 0,
  gap_distance_m double precision not null default 0,
  duration_ms bigint not null default 0,
  moving_ms bigint not null default 0,
  avg_speed_mps double precision not null default 0,
  max_speed_mps double precision not null default 0,
  point_count int not null default 0,
  quality int check (quality between 0 and 100),
  incidents jsonb not null default '[]'::jsonb,
  simulated boolean not null default false,
  manual boolean not null default false,
  start_lat double precision, start_lng double precision,
  end_lat double precision, end_lng double precision,
  preview_polyline text,
  title text check (char_length(title) <= 80),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index trips_owner_started on public.trips (owner_id, started_at desc);
create index trips_convoy on public.trips (convoy_id) where convoy_id is not null;
create trigger trips_touch before update on public.trips for each row execute function public.touch_updated_at();

create table public.trip_points (
  trip_id uuid not null references public.trips (id) on delete cascade,
  seq int not null check (seq >= 0),
  t timestamptz not null,
  elapsed_ms bigint not null,
  lat double precision not null check (lat between -90 and 90),
  lng double precision not null check (lng between -180 and 180),
  accuracy_m real,
  speed_mps real,
  speed_accuracy_mps real,
  bearing_deg real,
  bearing_accuracy_deg real,
  altitude_m double precision,
  flag smallint not null default 0,          -- PointFlag code: 0 accepted, 1 low accuracy, 2 jump, 3 duplicate, 4 stationary jitter
  segment_start boolean not null default false,
  mock boolean not null default false,
  primary key (trip_id, seq)
);

-- ---------------------------------------------------------------------------
-- Live location (one row per user; the realtime feed)
-- ---------------------------------------------------------------------------

create table public.live_locations (
  user_id uuid primary key references auth.users (id) on delete cascade,
  sharing boolean not null default true,     -- false = paused: friends see "paused", never coordinates
  lat double precision check (lat between -90 and 90),
  lng double precision check (lng between -180 and 180),
  accuracy_m real,
  speed_mps real,
  bearing_deg real,
  state text not null default 'idle' check (state in ('driving', 'stopped', 'idle')),
  vehicle_id uuid references public.vehicles (id) on delete set null,
  trip_id uuid,
  battery_pct smallint check (battery_pct between 0 and 100),
  recorded_at timestamptz,                   -- fix time from the device
  updated_at timestamptz not null default now()  -- server receive time, cannot be forged
);

create or replace function public.sanitize_live_location()
returns trigger language plpgsql security definer set search_path = '' as $$
declare s public.user_settings;
begin
  select * into s from public.user_settings where user_id = new.user_id;
  new.updated_at := now();
  if new.recorded_at is not null and new.recorded_at > now() + interval '2 minutes' then
    new.recorded_at := now();
  end if;
  if s.ghost_mode or not new.sharing then
    new.sharing := false;
    new.lat := null; new.lng := null; new.accuracy_m := null;
    new.speed_mps := null; new.bearing_deg := null; new.trip_id := null; new.battery_pct := null;
  elsif not s.share_speed then
    new.speed_mps := null;
  end if;
  return new;
end $$;
create trigger live_locations_sanitize before insert or update on public.live_locations
  for each row execute function public.sanitize_live_location();

-- Entering ghost mode immediately wipes the stored live position.
create or replace function public.apply_ghost_mode()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if new.ghost_mode and not old.ghost_mode then
    update public.live_locations set sharing = false where user_id = new.user_id;
  end if;
  if not new.share_speed and old.share_speed then
    update public.live_locations set speed_mps = null where user_id = new.user_id;
  end if;
  return new;
end $$;
create trigger settings_ghost after update on public.user_settings for each row execute function public.apply_ghost_mode();

-- ---------------------------------------------------------------------------
-- Authorisation predicates (security definer so they can read across RLS)
-- ---------------------------------------------------------------------------

create or replace function public.is_ghost(p_user uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select coalesce((select ghost_mode from public.user_settings where user_id = p_user), false)
$$;

create or replace function public.are_friends(a uuid, b uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.friendships where user_id = a and friend_id = b)
$$;

create or replace function public.share_group(a uuid, b uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.group_members x join public.group_members y on x.group_id = y.group_id
    where x.user_id = a and y.user_id = b
  )
$$;

create or replace function public.share_active_convoy(a uuid, b uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.convoy_members x
    join public.convoy_members y on x.convoy_id = y.convoy_id
    join public.convoys c on c.id = x.convoy_id
    where x.user_id = a and y.user_id = b and c.status = 'active'
      and x.left_at is null and y.left_at is null
  )
$$;

create or replace function public.is_group_member(p_group uuid, p_user uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.group_members where group_id = p_group and user_id = p_user)
$$;

create or replace function public.is_convoy_member(p_convoy uuid, p_user uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.convoy_members where convoy_id = p_convoy and user_id = p_user)
$$;

-- Can viewer see target's profile and vehicles?
create or replace function public.is_connected(viewer uuid, target uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select viewer = target
      or public.are_friends(target, viewer)
      or public.are_friends(viewer, target)
      or public.share_group(viewer, target)
      or public.share_active_convoy(viewer, target)
$$;

-- Can viewer see target's live position? Target's consent is required on every path.
create or replace function public.can_see_location(viewer uuid, target uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select viewer = target or (
    not public.is_ghost(target) and (
      exists (select 1 from public.friendships f where f.user_id = target and f.friend_id = viewer and f.share_location)
      or exists (
        select 1 from public.group_members t join public.group_members v on v.group_id = t.group_id
        where t.user_id = target and t.share_location and v.user_id = viewer
      )
      or public.share_active_convoy(viewer, target)
    )
  )
$$;

create or replace function public.can_see_trip(viewer uuid, p_trip uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.trips t where t.id = p_trip and (
      t.owner_id = viewer
      or (t.visibility = 'friends' and t.status = 'completed' and exists (
            select 1 from public.friendships f where f.user_id = t.owner_id and f.friend_id = viewer and f.share_trips))
      or (t.convoy_id is not null and exists (
            select 1 from public.convoy_members me join public.convoy_members owner_m
              on owner_m.convoy_id = me.convoy_id and owner_m.user_id = t.owner_id
            where me.convoy_id = t.convoy_id and me.user_id = viewer and owner_m.share_trip))
    )
  )
$$;

-- ---------------------------------------------------------------------------
-- Row level security
-- ---------------------------------------------------------------------------

alter table public.profiles enable row level security;
alter table public.user_settings enable row level security;
alter table public.invite_codes enable row level security;
alter table public.friend_requests enable row level security;
alter table public.friendships enable row level security;
alter table public.groups enable row level security;
alter table public.group_members enable row level security;
alter table public.convoys enable row level security;
alter table public.convoy_members enable row level security;
alter table public.convoy_invites enable row level security;
alter table public.vehicles enable row level security;
alter table public.trips enable row level security;
alter table public.trip_points enable row level security;
alter table public.live_locations enable row level security;

-- Anonymous visitors get nothing at all.
revoke all on all tables in schema public from anon;
revoke execute on all functions in schema public from anon, public;

create policy profiles_select on public.profiles for select to authenticated
  using (public.is_connected((select auth.uid()), id));
create policy profiles_update on public.profiles for update to authenticated
  using (id = (select auth.uid())) with check (id = (select auth.uid()));

create policy settings_own on public.user_settings for select to authenticated using (user_id = (select auth.uid()));
create policy settings_update on public.user_settings for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
-- invite_codes: no policies → only reachable through the RPCs below.

create policy fr_select on public.friend_requests for select to authenticated
  using ((select auth.uid()) in (from_id, to_id));
-- Requests are created/answered only through RPCs.

create policy friendships_select on public.friendships for select to authenticated
  using ((select auth.uid()) in (user_id, friend_id));
-- A user may change only what *they* share with a friend.
create policy friendships_update on public.friendships for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

create policy groups_select on public.groups for select to authenticated
  using (public.is_group_member(id, (select auth.uid())));
create policy groups_update on public.groups for update to authenticated
  using (owner_id = (select auth.uid())) with check (owner_id = (select auth.uid()));
create policy gm_select on public.group_members for select to authenticated
  using (public.is_group_member(group_id, (select auth.uid())));
create policy gm_update_self on public.group_members for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

create policy convoys_select on public.convoys for select to authenticated
  using (public.is_convoy_member(convoys.id, (select auth.uid()))
      or exists (select 1 from public.convoy_invites i where i.convoy_id = convoys.id and i.to_id = (select auth.uid()) and i.status = 'pending'));
create policy cm_select on public.convoy_members for select to authenticated
  using (public.is_convoy_member(convoy_id, (select auth.uid())));
create policy cm_update_self on public.convoy_members for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy ci_select on public.convoy_invites for select to authenticated
  using ((select auth.uid()) in (from_id, to_id));

create policy vehicles_select on public.vehicles for select to authenticated
  using (public.is_connected((select auth.uid()), owner_id));
create policy vehicles_insert on public.vehicles for insert to authenticated with check (owner_id = (select auth.uid()));
create policy vehicles_update on public.vehicles for update to authenticated
  using (owner_id = (select auth.uid())) with check (owner_id = (select auth.uid()));
create policy vehicles_delete on public.vehicles for delete to authenticated using (owner_id = (select auth.uid()));

-- Trips: direct table access is owner-only; others read through checked RPCs that also
-- apply the owner's speed-sharing preference.
create policy trips_owner on public.trips for all to authenticated
  using (owner_id = (select auth.uid())) with check (owner_id = (select auth.uid()));
create policy points_owner_select on public.trip_points for select to authenticated
  using (exists (select 1 from public.trips t where t.id = trip_id and t.owner_id = (select auth.uid())));
create policy points_owner_insert on public.trip_points for insert to authenticated
  with check (exists (select 1 from public.trips t where t.id = trip_id and t.owner_id = (select auth.uid())));
create policy points_owner_update on public.trip_points for update to authenticated
  using (exists (select 1 from public.trips t where t.id = trip_id and t.owner_id = (select auth.uid())));
create policy points_owner_delete on public.trip_points for delete to authenticated
  using (exists (select 1 from public.trips t where t.id = trip_id and t.owner_id = (select auth.uid())));

create policy live_select on public.live_locations for select to authenticated
  using (public.can_see_location((select auth.uid()), user_id));
create policy live_insert on public.live_locations for insert to authenticated with check (user_id = (select auth.uid()));
create policy live_update on public.live_locations for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy live_delete on public.live_locations for delete to authenticated using (user_id = (select auth.uid()));

-- Clients can never insert profiles/settings/friendships/groups/convoys directly.
revoke insert, delete on public.profiles, public.user_settings, public.friendships, public.friend_requests,
  public.groups, public.group_members, public.convoys, public.convoy_members, public.convoy_invites,
  public.invite_codes from authenticated;
revoke update on public.friend_requests, public.convoys, public.convoy_invites, public.invite_codes from authenticated;

-- ---------------------------------------------------------------------------
-- RPC: invitations and friendships
-- ---------------------------------------------------------------------------

create or replace function public.require_user()
returns uuid language plpgsql stable set search_path = '' as $$
declare u uuid := auth.uid();
begin
  if u is null then raise exception 'not authenticated' using errcode = '28000'; end if;
  return u;
end $$;

create or replace function public.my_invite_code()
returns text language sql stable security definer set search_path = '' as $$
  select invite_code from public.user_settings where user_id = public.require_user()
$$;

create or replace function public.regenerate_invite_code()
returns text language plpgsql security definer set search_path = '' as $$
declare u uuid := public.require_user(); c text;
begin
  c := public.issue_code('friend', u);
  update public.user_settings set invite_code = c where user_id = u;
  return c;
end $$;

-- What a code points at, revealing only a name. Never a location.
create or replace function public.resolve_invite_code(p_code text)
returns table (kind text, target_id uuid, name text)
language plpgsql stable security definer set search_path = '' as $$
declare r public.invite_codes;
begin
  perform public.require_user();
  select * into r from public.invite_codes where code = public.normalize_code(p_code);
  if not found then return; end if;
  kind := r.kind; target_id := r.target_id;
  name := case r.kind
    when 'friend' then (select p.display_name from public.profiles p where p.id = r.target_id)
    when 'group' then (select g.name from public.groups g where g.id = r.target_id)
    when 'convoy' then (select c.name from public.convoys c where c.id = r.target_id and c.status = 'active')
  end;
  if name is null then return; end if;
  return next;
end $$;

create or replace function public.send_friend_request(p_code text)
returns table (request_id uuid, status text, display_name text)
language plpgsql security definer set search_path = '' as $$
declare
  me uuid := public.require_user();
  target uuid;
  existing public.friend_requests;
begin
  if (select count(*) from public.friend_requests where from_id = me and created_at > now() - interval '1 hour') >= 30 then
    raise exception 'too many requests, try later' using errcode = '54000';
  end if;
  select ic.target_id into target from public.invite_codes ic
    where ic.code = public.normalize_code(p_code) and ic.kind = 'friend';
  if target is null then raise exception 'invalid code' using errcode = 'P0002'; end if;
  if target = me then raise exception 'that is your own code' using errcode = '22023'; end if;
  display_name := (select p.display_name from public.profiles p where p.id = target);
  if public.are_friends(me, target) then
    request_id := null; status := 'already_friends'; return next; return;
  end if;
  select * into existing from public.friend_requests fr
    where fr.status = 'pending' and least(fr.from_id, fr.to_id) = least(me, target) and greatest(fr.from_id, fr.to_id) = greatest(me, target);
  if found then
    if existing.to_id = me then
      -- They already asked us: sending our own request means yes.
      perform public.respond_friend_request(existing.id, true);
      request_id := existing.id; status := 'accepted'; return next; return;
    end if;
    request_id := existing.id; status := 'pending'; return next; return;
  end if;
  insert into public.friend_requests (from_id, to_id) values (me, target) returning id into request_id;
  status := 'pending';
  return next;
end $$;

create or replace function public.respond_friend_request(p_request uuid, p_accept boolean)
returns void language plpgsql security definer set search_path = '' as $$
declare me uuid := public.require_user(); r public.friend_requests;
begin
  select * into r from public.friend_requests where id = p_request for update;
  if not found or r.to_id <> me or r.status <> 'pending' then
    raise exception 'request not found' using errcode = 'P0002';
  end if;
  update public.friend_requests set status = case when p_accept then 'accepted' else 'rejected' end, responded_at = now()
    where id = p_request;
  if p_accept then
    insert into public.friendships (user_id, friend_id) values (r.from_id, r.to_id), (r.to_id, r.from_id)
      on conflict do nothing;
  end if;
end $$;

create or replace function public.cancel_friend_request(p_request uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare me uuid := public.require_user();
begin
  update public.friend_requests set status = 'cancelled', responded_at = now()
    where id = p_request and from_id = me and status = 'pending';
  if not found then raise exception 'request not found' using errcode = 'P0002'; end if;
end $$;

-- Pending requests with the other person's name (their profile is not otherwise visible yet).
create or replace function public.pending_requests()
returns table (id uuid, incoming boolean, other_id uuid, other_name text, other_avatar text, created_at timestamptz)
language sql stable security definer set search_path = '' as $$
  select r.id, r.to_id = public.require_user(),
         case when r.to_id = public.require_user() then r.from_id else r.to_id end,
         p.display_name, p.avatar_url, r.created_at
  from public.friend_requests r
  join public.profiles p on p.id = case when r.to_id = public.require_user() then r.from_id else r.to_id end
  where r.status = 'pending' and public.require_user() in (r.from_id, r.to_id)
  order by r.created_at desc
$$;

-- Removing a friend revokes every permission in both directions at once.
create or replace function public.remove_friend(p_friend uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare me uuid := public.require_user();
begin
  delete from public.friendships where (user_id = me and friend_id = p_friend) or (user_id = p_friend and friend_id = me);
  update public.friend_requests set status = 'cancelled', responded_at = now()
    where status = 'pending' and ((from_id = me and to_id = p_friend) or (from_id = p_friend and to_id = me));
end $$;

-- ---------------------------------------------------------------------------
-- RPC: groups
-- ---------------------------------------------------------------------------

create or replace function public.create_group(p_name text)
returns table (group_id uuid, invite_code text)
language plpgsql security definer set search_path = '' as $$
declare me uuid := public.require_user();
begin
  insert into public.groups (name, owner_id) values (trim(p_name), me) returning id into group_id;
  insert into public.group_members (group_id, user_id, role) values (group_id, me, 'owner');
  invite_code := public.issue_code('group', group_id);
  return next;
end $$;

create or replace function public.group_invite_code(p_group uuid)
returns text language plpgsql stable security definer set search_path = '' as $$
declare me uuid := public.require_user();
begin
  if not exists (select 1 from public.group_members where group_id = p_group and user_id = me) then
    raise exception 'not a member' using errcode = '42501';
  end if;
  return (select code from public.invite_codes where kind = 'group' and target_id = p_group);
end $$;

create or replace function public.regenerate_group_code(p_group uuid)
returns text language plpgsql security definer set search_path = '' as $$
declare me uuid := public.require_user();
begin
  if not exists (select 1 from public.groups where id = p_group and owner_id = me) then
    raise exception 'only the owner can do this' using errcode = '42501';
  end if;
  return public.issue_code('group', p_group);
end $$;

create or replace function public.join_group(p_code text)
returns uuid language plpgsql security definer set search_path = '' as $$
declare me uuid := public.require_user(); g uuid;
begin
  select target_id into g from public.invite_codes where code = public.normalize_code(p_code) and kind = 'group';
  if g is null then raise exception 'invalid code' using errcode = 'P0002'; end if;
  insert into public.group_members (group_id, user_id) values (g, me) on conflict do nothing;
  return g;
end $$;

create or replace function public.leave_group(p_group uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare me uuid := public.require_user(); next_owner uuid;
begin
  delete from public.group_members where group_id = p_group and user_id = me;
  if exists (select 1 from public.groups where id = p_group and owner_id = me) then
    select user_id into next_owner from public.group_members where group_id = p_group order by joined_at limit 1;
    if next_owner is null then
      delete from public.invite_codes where kind = 'group' and target_id = p_group;
      delete from public.groups where id = p_group;
    else
      update public.groups set owner_id = next_owner where id = p_group;
      update public.group_members set role = 'owner' where group_id = p_group and user_id = next_owner;
    end if;
  end if;
end $$;

create or replace function public.remove_group_member(p_group uuid, p_user uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare me uuid := public.require_user();
begin
  if not exists (select 1 from public.groups where id = p_group and owner_id = me) or p_user = me then
    raise exception 'only the owner can remove members' using errcode = '42501';
  end if;
  delete from public.group_members where group_id = p_group and user_id = p_user;
end $$;

-- ---------------------------------------------------------------------------
-- RPC: convoys
-- ---------------------------------------------------------------------------

create or replace function public.create_convoy(p_name text)
returns table (convoy_id uuid, invite_code text)
language plpgsql security definer set search_path = '' as $$
declare me uuid := public.require_user();
begin
  insert into public.convoys (name, owner_id) values (trim(p_name), me) returning id into convoy_id;
  insert into public.convoy_members (convoy_id, user_id) values (convoy_id, me);
  invite_code := public.issue_code('convoy', convoy_id);
  return next;
end $$;

create or replace function public.convoy_invite_code(p_convoy uuid)
returns text language plpgsql stable security definer set search_path = '' as $$
declare me uuid := public.require_user();
begin
  if not exists (select 1 from public.convoy_members where convoy_id = p_convoy and user_id = me and left_at is null) then
    raise exception 'not a member' using errcode = '42501';
  end if;
  return (select code from public.invite_codes where kind = 'convoy' and target_id = p_convoy);
end $$;

create or replace function public.join_convoy(p_code text)
returns uuid language plpgsql security definer set search_path = '' as $$
declare me uuid := public.require_user(); c uuid;
begin
  select ic.target_id into c from public.invite_codes ic join public.convoys cv on cv.id = ic.target_id
    where ic.code = public.normalize_code(p_code) and ic.kind = 'convoy' and cv.status = 'active';
  if c is null then raise exception 'invalid or finished convoy' using errcode = 'P0002'; end if;
  insert into public.convoy_members (convoy_id, user_id) values (c, me)
    on conflict (convoy_id, user_id) do update set left_at = null;
  update public.convoy_invites set status = 'accepted' where convoy_id = c and to_id = me;
  return c;
end $$;

create or replace function public.invite_to_convoy(p_convoy uuid, p_friend uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare me uuid := public.require_user();
begin
  if not exists (select 1 from public.convoy_members m join public.convoys c on c.id = m.convoy_id
                 where m.convoy_id = p_convoy and m.user_id = me and m.left_at is null and c.status = 'active') then
    raise exception 'not an active member' using errcode = '42501';
  end if;
  if not public.are_friends(me, p_friend) then raise exception 'you can only invite friends' using errcode = '42501'; end if;
  insert into public.convoy_invites (convoy_id, from_id, to_id) values (p_convoy, me, p_friend)
    on conflict (convoy_id, to_id) do update set status = 'pending', from_id = excluded.from_id, created_at = now();
end $$;

create or replace function public.respond_convoy_invite(p_invite uuid, p_accept boolean)
returns uuid language plpgsql security definer set search_path = '' as $$
declare me uuid := public.require_user(); i public.convoy_invites;
begin
  select * into i from public.convoy_invites where id = p_invite and to_id = me and status = 'pending';
  if not found then raise exception 'invite not found' using errcode = 'P0002'; end if;
  update public.convoy_invites set status = case when p_accept then 'accepted' else 'declined' end where id = p_invite;
  if p_accept then
    if not exists (select 1 from public.convoys where id = i.convoy_id and status = 'active') then
      raise exception 'convoy finished' using errcode = 'P0002';
    end if;
    insert into public.convoy_members (convoy_id, user_id) values (i.convoy_id, me)
      on conflict (convoy_id, user_id) do update set left_at = null;
    return i.convoy_id;
  end if;
  return null;
end $$;

create or replace function public.leave_convoy(p_convoy uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare me uuid := public.require_user();
begin
  update public.convoy_members set left_at = now() where convoy_id = p_convoy and user_id = me and left_at is null;
end $$;

create or replace function public.end_convoy(p_convoy uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare me uuid := public.require_user();
begin
  update public.convoys set status = 'ended', ended_at = now() where id = p_convoy and owner_id = me and status = 'active';
  if not found then raise exception 'only the organiser can end the convoy' using errcode = '42501'; end if;
  delete from public.invite_codes where kind = 'convoy' and target_id = p_convoy;
  update public.convoy_invites set status = 'declined' where convoy_id = p_convoy and status = 'pending';
end $$;

-- ---------------------------------------------------------------------------
-- RPC: reading other people's trips (permission-checked, speed masked when not shared)
-- ---------------------------------------------------------------------------

create or replace function public.visible_trips(p_owner uuid, p_before timestamptz default null, p_limit int default 50)
returns setof public.trips language plpgsql stable security definer set search_path = '' as $$
declare me uuid := public.require_user(); hide_speed boolean;
begin
  hide_speed := me <> p_owner and not coalesce((select share_speed from public.user_settings where user_id = p_owner), true);
  return query
    select t.id, t.owner_id, t.vehicle_id, t.convoy_id, t.status, t.visibility, t.started_at, t.ended_at,
           t.distance_m, t.measured_distance_m, t.gap_distance_m, t.duration_ms, t.moving_ms,
           case when hide_speed then 0 else t.avg_speed_mps end,
           case when hide_speed then 0 else t.max_speed_mps end,
           t.point_count, t.quality, t.incidents, t.simulated, t.manual,
           t.start_lat, t.start_lng, t.end_lat, t.end_lng, t.preview_polyline, t.title, t.created_at, t.updated_at
    from public.trips t
    where t.owner_id = p_owner and (p_before is null or t.started_at < p_before)
      and public.can_see_trip(me, t.id)
    order by t.started_at desc
    limit least(greatest(p_limit, 1), 200);
end $$;

create or replace function public.trip_points_for(p_trip uuid)
returns setof public.trip_points language plpgsql stable security definer set search_path = '' as $$
declare me uuid := public.require_user(); owner uuid; hide_speed boolean;
begin
  if not public.can_see_trip(me, p_trip) then raise exception 'trip not found' using errcode = 'P0002'; end if;
  select owner_id into owner from public.trips where id = p_trip;
  hide_speed := me <> owner and not coalesce((select share_speed from public.user_settings where user_id = owner), true);
  return query
    select p.trip_id, p.seq, p.t, p.elapsed_ms, p.lat, p.lng, p.accuracy_m,
           case when hide_speed then null else p.speed_mps end,
           case when hide_speed then null else p.speed_accuracy_mps end,
           p.bearing_deg, p.bearing_accuracy_deg, p.altitude_m, p.flag, p.segment_start, p.mock
    from public.trip_points p where p.trip_id = p_trip order by p.seq;
end $$;

-- Every member's trip recorded during a convoy, for the shared map afterwards.
create or replace function public.convoy_trips(p_convoy uuid)
returns setof public.trips language plpgsql stable security definer set search_path = '' as $$
declare me uuid := public.require_user();
begin
  if not exists (select 1 from public.convoy_members where convoy_id = p_convoy and user_id = me) then
    raise exception 'not a member' using errcode = '42501';
  end if;
  return query select t.* from public.trips t where t.convoy_id = p_convoy and public.can_see_trip(me, t.id)
    order by t.started_at;
end $$;

-- ---------------------------------------------------------------------------
-- RPC: social statistics (opt-in, speed is never a ranking metric)
-- ---------------------------------------------------------------------------

create or replace function public.leaderboard(p_since timestamptz, p_until timestamptz default now())
returns table (user_id uuid, display_name text, avatar_url text, trips bigint, distance_m double precision, moving_ms bigint)
language plpgsql stable security definer set search_path = '' as $$
declare me uuid := public.require_user();
begin
  return query
    with people as (
      select me as uid
      union
      select f.user_id from public.friendships f
        join public.user_settings s on s.user_id = f.user_id
        where f.friend_id = me and f.share_trips and s.stats_visible
    )
    select p.id, p.display_name, p.avatar_url,
           count(t.id), coalesce(sum(t.distance_m), 0)::double precision, coalesce(sum(t.moving_ms), 0)::bigint
    from people x
    join public.profiles p on p.id = x.uid
    left join public.trips t on t.owner_id = x.uid and t.status = 'completed' and not t.simulated
         and t.started_at >= p_since and t.started_at < p_until
    group by p.id, p.display_name, p.avatar_url;
end $$;

-- ---------------------------------------------------------------------------
-- RPC: privacy — export, history deletion, account deletion, retention
-- ---------------------------------------------------------------------------

create or replace function public.export_my_data()
returns jsonb language sql stable security definer set search_path = '' as $$
  with me as (select public.require_user() as id)
  select jsonb_build_object(
    'exported_at', now(),
    'profile', (select to_jsonb(p) from public.profiles p, me where p.id = me.id),
    'settings', (select to_jsonb(s) - 'invite_code' from public.user_settings s, me where s.user_id = me.id),
    'vehicles', coalesce((select jsonb_agg(to_jsonb(v)) from public.vehicles v, me where v.owner_id = me.id), '[]'),
    'friends', coalesce((select jsonb_agg(jsonb_build_object('friend_id', f.friend_id, 'share_location', f.share_location,
                  'share_trips', f.share_trips, 'since', f.created_at)) from public.friendships f, me where f.user_id = me.id), '[]'),
    'groups', coalesce((select jsonb_agg(jsonb_build_object('group_id', g.id, 'name', g.name, 'joined_at', m.joined_at))
                  from public.group_members m join public.groups g on g.id = m.group_id, me where m.user_id = me.id), '[]'),
    'trips', coalesce((select jsonb_agg(to_jsonb(t) order by t.started_at) from public.trips t, me where t.owner_id = me.id), '[]'),
    'live_location', (select to_jsonb(l) from public.live_locations l, me where l.user_id = me.id)
  )
$$;

create or replace function public.delete_my_trips(p_before timestamptz default null)
returns int language plpgsql security definer set search_path = '' as $$
declare me uuid := public.require_user(); n int;
begin
  delete from public.trips where owner_id = me and (p_before is null or started_at < p_before);
  get diagnostics n = row_count;
  return n;
end $$;

-- Deletes the auth user; every table cascades. The app removes the avatar from Storage first.
create or replace function public.delete_my_account()
returns void language plpgsql security definer set search_path = '' as $$
declare me uuid := public.require_user();
begin
  delete from public.invite_codes where target_id = me
     or (kind = 'group' and target_id in (select id from public.groups where owner_id = me))
     or (kind = 'convoy' and target_id in (select id from public.convoys where owner_id = me));
  delete from auth.users where id = me;
end $$;

-- Called daily by pg_cron (see docs/BACKEND.md). Applies each user's retention setting.
create or replace function public.purge_expired_data()
returns int language plpgsql security definer set search_path = '' as $$
declare n int;
begin
  delete from public.trips t using public.user_settings s
    where s.user_id = t.owner_id and s.retention_days is not null
      and t.started_at < now() - make_interval(days => s.retention_days);
  get diagnostics n = row_count;
  -- Live positions not refreshed for 30 days are dropped entirely.
  delete from public.live_locations where updated_at < now() - interval '30 days';
  return n;
end $$;
revoke execute on function public.purge_expired_data() from authenticated;

-- Internal helpers are not part of the API.
revoke execute on function public.issue_code(text, uuid), public.handle_new_user(), public.random_code(int)
  from authenticated;

grant execute on function
  public.my_invite_code(), public.regenerate_invite_code(), public.resolve_invite_code(text),
  public.send_friend_request(text), public.respond_friend_request(uuid, boolean), public.cancel_friend_request(uuid),
  public.remove_friend(uuid), public.pending_requests(), public.create_group(text), public.group_invite_code(uuid), public.regenerate_group_code(uuid),
  public.join_group(text), public.leave_group(uuid), public.remove_group_member(uuid, uuid),
  public.create_convoy(text), public.convoy_invite_code(uuid), public.join_convoy(text), public.invite_to_convoy(uuid, uuid),
  public.respond_convoy_invite(uuid, boolean), public.leave_convoy(uuid), public.end_convoy(uuid),
  public.visible_trips(uuid, timestamptz, int), public.trip_points_for(uuid), public.convoy_trips(uuid),
  public.leaderboard(timestamptz, timestamptz), public.export_my_data(), public.delete_my_trips(timestamptz),
  public.delete_my_account(), public.normalize_code(text), public.require_user(),
  public.is_ghost(uuid), public.are_friends(uuid, uuid), public.is_group_member(uuid, uuid), public.is_convoy_member(uuid, uuid), public.share_group(uuid, uuid),
  public.share_active_convoy(uuid, uuid), public.is_connected(uuid, uuid), public.can_see_location(uuid, uuid),
  public.can_see_trip(uuid, uuid)
to authenticated;

-- ---------------------------------------------------------------------------
-- Realtime and storage
-- ---------------------------------------------------------------------------

alter publication supabase_realtime add table public.live_locations, public.friend_requests,
  public.friendships, public.convoy_invites, public.convoy_members;
alter table public.live_locations replica identity full;

insert into storage.buckets (id, name, public) values ('avatars', 'avatars', true) on conflict (id) do nothing;

create policy avatars_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'avatars' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy avatars_update on storage.objects for update to authenticated
  using (bucket_id = 'avatars' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy avatars_delete on storage.objects for delete to authenticated
  using (bucket_id = 'avatars' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy avatars_read on storage.objects for select to authenticated
  using (bucket_id = 'avatars');
