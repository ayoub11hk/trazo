-- Access-rule tests. Run against a fresh database after 00_supabase_shim.sql and the migrations:
--   psql -v ON_ERROR_STOP=1 -f tests/00_supabase_shim.sql -f migrations/*.sql -f tests/10_access_rules.sql
-- Any failed expectation raises an exception and stops the run.
\set QUIET on
create schema tests;
grant usage on schema tests to authenticated, anon;

create function tests.ok(cond boolean, msg text) returns void language plpgsql as $$
begin
  if cond is distinct from true then raise exception 'FAILED: %', msg; end if;
  raise notice 'ok - %', msg;
end $$;

create function tests.throws(stmt text, msg text) returns void language plpgsql as $$
begin
  begin
    execute stmt;
  exception when others then
    raise notice 'ok - % (%)', msg, sqlerrm;
    return;
  end;
  raise exception 'FAILED (no error): %', msg;
end $$;

create function tests.login(email text) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claim.sub', (select id::text from auth.users u where u.email = login.email), false);
  perform set_config('role', 'authenticated', false);
end $$;

create function tests.uid(email text) returns uuid language sql as $$ select id from auth.users u where u.email = uid.email $$;
grant execute on all functions in schema tests to authenticated, anon;
grant select on auth.users to authenticated; -- test helper lookups only

-- Users: Ana, Bruno, Carla (stranger), Dani (group mate of Ana)
insert into auth.users (email, raw_user_meta_data) values
  ('ana@test', '{"display_name":"Ana"}'), ('bruno@test', '{"display_name":"Bruno"}'),
  ('carla@test', '{}'), ('dani@test', '{"display_name":"Dani"}');

select tests.ok((select count(*) from public.profiles) = 4, 'profiles created on sign-up');
select tests.ok((select display_name from public.profiles where id = tests.uid('carla@test')) = 'carla', 'fallback display name from email');
select tests.ok((select count(distinct invite_code) from public.user_settings) = 4, 'unique invite codes');

-- ---------------------------------------------------------------- invitations
select tests.login('ana@test');
select tests.ok(public.my_invite_code() ~ '^[0-9A-HJKMNP-TV-Z]{8}$', 'invite code uses Crockford alphabet');
select tests.ok((select count(*) from public.profiles) = 1, 'stranger profiles are invisible before friendship');
select tests.throws($$ insert into public.friendships (user_id, friend_id) values (auth.uid(), tests.uid('bruno@test')) $$,
  'cannot create a friendship directly');
select tests.throws($$ update public.user_settings set invite_code = 'AAAAAAAA' $$, 'cannot choose own invite code');
reset role;

select tests.login('bruno@test');
create temp table bruno_code as select public.my_invite_code() as c;
reset role;
grant select on bruno_code to authenticated;

select tests.login('ana@test');
select tests.throws($$ select public.send_friend_request('ZZZZZZZZ') $$, 'invalid code rejected');
select tests.throws($$ select public.send_friend_request(public.my_invite_code()) $$, 'own code rejected');
select tests.ok((select status from public.send_friend_request(lower((select c from bruno_code)))) = 'pending', 'request by (lower-case) code');
select tests.ok((select count(*) from public.live_locations) = 0, 'a pending request reveals no location');
select tests.ok((select count(*) from public.profiles) = 1, 'a pending request reveals no profile');
reset role;

-- Bruno shares a live position before accepting; Ana must not see it yet.
select tests.login('bruno@test');
insert into public.live_locations (user_id, lat, lng, accuracy_m, speed_mps, state, recorded_at)
  values (auth.uid(), 40.41, -3.70, 6, 13.5, 'driving', now());
reset role;
select tests.login('ana@test');
select tests.ok((select count(*) from public.live_locations) = 0, 'no location before acceptance');
reset role;

select tests.login('carla@test');
select tests.throws($$ select public.respond_friend_request((select id from public.friend_requests limit 1), true) $$,
  'third party cannot accept someone else''s request');
select tests.ok((select count(*) from public.friend_requests) = 0, 'third party cannot see requests');
reset role;

select tests.login('bruno@test');
select public.respond_friend_request((select id from public.friend_requests where status = 'pending'), true);
select tests.ok((select count(*) from public.friendships) = 2, 'friendship rows in both directions');
reset role;

select tests.login('ana@test');
select tests.ok((select count(*) from public.profiles) = 2, 'friend profile visible after acceptance');
select tests.ok((select count(*) from public.live_locations where user_id = tests.uid('bruno@test')) = 1, 'friend location visible');
select tests.ok((select speed_mps from public.live_locations where user_id = tests.uid('bruno@test')) = 13.5, 'speed visible when shared');
select tests.ok((select count(*) from public.user_settings) = 1, 'only own settings visible');
-- Ana cannot write Bruno's live location or change what Bruno shares with her.
update public.live_locations set lat = 0 where user_id = tests.uid('bruno@test');
update public.friendships set share_location = true where user_id = tests.uid('bruno@test');
reset role;
select tests.ok((select lat from public.live_locations where user_id = tests.uid('bruno@test')) = 40.41, 'cannot modify a friend''s position');

-- ---------------------------------------------------------------- privacy controls
select tests.login('bruno@test');
update public.user_settings set share_speed = false;
reset role;
select tests.login('ana@test');
select tests.ok((select speed_mps from public.live_locations where user_id = tests.uid('bruno@test')) is null, 'speed hidden when not shared');
reset role;

select tests.login('bruno@test');
update public.friendships set share_location = false where friend_id = tests.uid('ana@test');
reset role;
select tests.login('ana@test');
select tests.ok((select count(*) from public.live_locations where user_id = tests.uid('bruno@test')) = 0, 'per-friend location revoke');
reset role;
select tests.login('bruno@test');
update public.friendships set share_location = true where friend_id = tests.uid('ana@test');
update public.live_locations set sharing = false, lat = 41.0, lng = 2.0 where user_id = auth.uid();
reset role;
select tests.login('ana@test');
select tests.ok((select sharing from public.live_locations where user_id = tests.uid('bruno@test')) = false, 'paused state visible');
select tests.ok((select lat from public.live_locations where user_id = tests.uid('bruno@test')) is null, 'paused: no coordinates stored');
reset role;

select tests.login('bruno@test');
update public.live_locations set sharing = true, lat = 41.0, lng = 2.0, recorded_at = now() + interval '1 day' where user_id = auth.uid();
select tests.ok((select recorded_at < now() + interval '3 minutes' from public.live_locations), 'future timestamps clamped');
update public.user_settings set ghost_mode = true;
select tests.ok((select lat from public.live_locations where user_id = auth.uid()) is null, 'ghost mode wipes stored position');
update public.live_locations set lat = 42.0, lng = 2.1, sharing = true where user_id = auth.uid();
reset role;
select tests.login('ana@test');
select tests.ok((select count(*) from public.live_locations where user_id = tests.uid('bruno@test')) = 0, 'ghost mode hides user entirely');
reset role;
select tests.login('bruno@test');
update public.user_settings set ghost_mode = false, share_speed = true;
update public.live_locations set lat = 42.0, lng = 2.1, sharing = true, speed_mps = 10 where user_id = auth.uid();
reset role;

-- ---------------------------------------------------------------- vehicles
select tests.login('bruno@test');
insert into public.vehicles (owner_id, name, make, model, year, color, body_type)
  values (auth.uid(), 'El Rayo', 'Seat', 'León', 2019, '#FF3355', 'hatchback');
insert into public.vehicles (owner_id, name) select auth.uid(), 'Coche ' || g from generate_series(1, 12) g;
select tests.ok((select count(*) from public.vehicles) = 13, 'no limit on number of vehicles');
update public.profiles set active_vehicle_id = (select id from public.vehicles where name = 'El Rayo') where id = auth.uid();
reset role;
select tests.login('ana@test');
select tests.ok((select v.name from public.profiles p join public.vehicles v on v.id = p.active_vehicle_id
                 where p.id = tests.uid('bruno@test')) = 'El Rayo', 'friend sees selected vehicle');
select tests.throws($$ update public.profiles set active_vehicle_id = (select id from public.vehicles where name = 'El Rayo') where id = auth.uid() $$,
  'cannot select someone else''s vehicle');
select tests.throws($$ insert into public.vehicles (owner_id, name) values (tests.uid('bruno@test'), 'x') $$, 'cannot add vehicle for others');
reset role;
select tests.login('carla@test');
select tests.ok((select count(*) from public.vehicles) = 0, 'stranger sees no vehicles');
reset role;

-- ---------------------------------------------------------------- trips
select tests.login('bruno@test');
insert into public.trips (id, owner_id, status, visibility, started_at, ended_at, distance_m, avg_speed_mps, max_speed_mps)
  values ('11111111-1111-1111-1111-111111111111', auth.uid(), 'completed', 'friends', now() - interval '2 hours', now() - interval '1 hour', 25000, 12, 30),
         ('22222222-2222-2222-2222-222222222222', auth.uid(), 'completed', 'private', now() - interval '5 hours', now() - interval '4 hours', 5000, 10, 20);
insert into public.trip_points (trip_id, seq, t, elapsed_ms, lat, lng, accuracy_m, speed_mps)
  select '11111111-1111-1111-1111-111111111111', g, now() - interval '2 hours' + g * interval '1 second', g * 1000, 40 + g * 0.0001, -3.7, 5, 12
  from generate_series(0, 99) g;
-- idempotent re-upload after a lost connection
insert into public.trip_points (trip_id, seq, t, elapsed_ms, lat, lng)
  select '11111111-1111-1111-1111-111111111111', g, now(), g * 1000, 0, 0 from generate_series(0, 9) g
  on conflict (trip_id, seq) do nothing;
select tests.ok((select count(*) from public.trip_points) = 100, 'retrying an upload creates no duplicates');
reset role;

select tests.login('ana@test');
select tests.ok((select count(*) from public.trips) = 0, 'friends cannot read the trips table directly');
select tests.ok((select count(*) from public.visible_trips(tests.uid('bruno@test'))) = 1, 'friend sees shared trip, not private one');
select tests.ok((select count(*) from public.trip_points_for('11111111-1111-1111-1111-111111111111')) = 100, 'friend can replay shared trip');
select tests.throws($$ select * from public.trip_points_for('22222222-2222-2222-2222-222222222222') $$, 'private trip points refused');
select tests.throws($$ insert into public.trip_points (trip_id, seq, t, elapsed_ms, lat, lng)
  values ('11111111-1111-1111-1111-111111111111', 500, now(), 0, 0, 0) $$, 'cannot add points to a friend''s trip');
reset role;

select tests.login('bruno@test');
update public.user_settings set share_speed = false;
reset role;
select tests.login('ana@test');
select tests.ok((select max_speed_mps from public.visible_trips(tests.uid('bruno@test'))) = 0, 'trip speed masked when not shared');
select tests.ok((select count(speed_mps) from public.trip_points_for('11111111-1111-1111-1111-111111111111')) = 0, 'point speed masked');
reset role;
select tests.login('bruno@test');
update public.user_settings set share_speed = true;
update public.friendships set share_trips = false where friend_id = tests.uid('ana@test');
reset role;
select tests.login('ana@test');
select tests.ok((select count(*) from public.visible_trips(tests.uid('bruno@test'))) = 0, 'trip access revocable per friend');
reset role;
select tests.login('bruno@test');
update public.friendships set share_trips = true where friend_id = tests.uid('ana@test');
reset role;

select tests.login('carla@test');
select tests.ok((select count(*) from public.visible_trips(tests.uid('bruno@test'))) = 0, 'stranger sees no trips');
select tests.throws($$ select * from public.trip_points_for('11111111-1111-1111-1111-111111111111') $$, 'stranger cannot replay');
reset role;

-- ---------------------------------------------------------------- groups
select tests.login('ana@test');
create temp table g as select * from public.create_group('Ruta del domingo');
reset role;
grant select on g to authenticated;
select tests.login('dani@test');
select tests.ok((select kind from public.resolve_invite_code((select invite_code from g))) = 'group', 'code resolves to group');
select tests.ok((select count(*) from public.live_locations) = 0, 'resolving a code reveals nothing');
select public.join_group((select invite_code from g));
insert into public.live_locations (user_id, lat, lng, state, recorded_at) values (auth.uid(), 40.5, -3.6, 'idle', now());
select tests.ok((select count(*) from public.group_members) = 2, 'member sees group members');
select tests.ok((select count(*) from public.profiles) = 2, 'group mates see each other''s profile');
select tests.ok((select count(*) from public.live_locations where user_id = tests.uid('bruno@test')) = 0, 'not visible: Bruno is not in the group');
reset role;
select tests.login('ana@test');
select tests.ok((select count(*) from public.live_locations where user_id = tests.uid('dani@test')) = 1, 'group mate location visible');
reset role;
select tests.login('dani@test');
update public.group_members set share_location = false where user_id = auth.uid();
select public.leave_group((select group_id from g));
reset role;
select tests.login('ana@test');
select tests.ok((select count(*) from public.live_locations where user_id = tests.uid('dani@test')) = 0, 'left group: location gone');
select public.leave_group((select group_id from g));
reset role;
select tests.ok((select count(*) from public.groups) = 0, 'empty group deleted');

-- ---------------------------------------------------------------- convoys
select tests.login('ana@test');
create temp table cv as select * from public.create_convoy('Escapada');
insert into public.live_locations (user_id, lat, lng, state, recorded_at) values (auth.uid(), 40.45, -3.65, 'driving', now());
select public.invite_to_convoy((select convoy_id from cv), tests.uid('bruno@test'));
select tests.throws($$ select public.invite_to_convoy((select convoy_id from cv), tests.uid('carla@test')) $$, 'only friends can be invited');
reset role;
grant select on cv to authenticated;
select tests.login('bruno@test');
select tests.ok((select count(*) from public.convoys) = 1, 'invitee can see the convoy');
select public.respond_convoy_invite((select id from public.convoy_invites), true);
insert into public.trips (id, owner_id, convoy_id, status, started_at) values
  ('33333333-3333-3333-3333-333333333333', auth.uid(), (select convoy_id from cv), 'completed', now());
update public.trips set visibility = 'private' where id = '33333333-3333-3333-3333-333333333333';
reset role;
select tests.login('carla@test');
select tests.throws($$ select public.join_convoy('ZZZZ-ZZZZ') $$, 'invalid convoy code');
select public.join_convoy((select invite_code from cv));
insert into public.live_locations (user_id, lat, lng, state, recorded_at) values (auth.uid(), 40.6, -3.5, 'driving', now());
select tests.ok((select count(*) from public.live_locations) = 3, 'convoy members see each other (even non-friends)');
select tests.ok((select count(*) from public.convoy_trips((select convoy_id from cv))) = 1, 'convoy trip visible to members even if private');
reset role;
select tests.login('ana@test');
select public.end_convoy((select convoy_id from cv));
reset role;
select tests.login('carla@test');
select tests.ok((select count(*) from public.live_locations where user_id <> auth.uid()) = 0, 'ended convoy: live sharing stops');
select tests.ok((select count(*) from public.convoy_trips((select convoy_id from cv))) = 1, 'ended convoy: trip still reviewable');
select tests.throws($$ select public.join_convoy((select invite_code from cv)) $$, 'ended convoy cannot be joined');
reset role;
select tests.login('bruno@test');
update public.convoy_members set share_trip = false where user_id = auth.uid();
reset role;
select tests.login('carla@test');
select tests.ok((select count(*) from public.convoy_trips((select convoy_id from cv))) = 0, 'convoy trip sharing revocable');
reset role;

-- ---------------------------------------------------------------- leaderboard
select tests.login('ana@test');
select tests.ok((select count(*) from public.leaderboard(now() - interval '1 day')) = 2, 'leaderboard: me + friends');
select tests.ok((select distance_m from public.leaderboard(now() - interval '1 day') where user_id = tests.uid('bruno@test')) = 30000, 'leaderboard distance from real trips');
reset role;
select tests.login('bruno@test');
update public.user_settings set stats_visible = false;
reset role;
select tests.login('ana@test');
select tests.ok((select count(*) from public.leaderboard(now() - interval '1 day')) = 1, 'leaderboard opt-out respected');
reset role;

-- ---------------------------------------------------------------- remove friend, export, delete
select tests.login('ana@test');
select public.remove_friend(tests.uid('bruno@test'));
select tests.ok((select count(*) from public.live_locations where user_id = tests.uid('bruno@test')) = 0, 'unfriend revokes location');
select tests.ok((select count(*) from public.visible_trips(tests.uid('bruno@test'))) = 0, 'unfriend revokes trips');
reset role;

select tests.login('bruno@test');
select tests.ok((select jsonb_array_length(public.export_my_data() -> 'trips')) = 3, 'export contains all own trips');
select tests.ok(not (public.export_my_data() -> 'settings' ? 'invite_code'), 'export omits secrets');
select tests.ok(public.delete_my_trips(now() - interval '3 hours') = 1, 'delete history before a date');
select public.delete_my_account();
reset role;
select tests.ok((select count(*) from auth.users where email = 'bruno@test') = 0, 'account deleted');
select tests.ok((select count(*) from public.trips where owner_id is null or owner_id not in (select id from auth.users)) = 0, 'trips cascade-deleted');
select tests.ok((select count(*) from public.trip_points) = 0, 'points cascade-deleted');
select tests.ok((select count(*) from public.invite_codes ic where ic.kind = 'friend' and ic.target_id not in (select id from auth.users)) = 0, 'invite code removed');

-- ---------------------------------------------------------------- no premium anywhere
select tests.ok((select count(*) from information_schema.columns where table_schema = 'public'
  and column_name ~* '(premium|subscription|plan|entitlement|is_pro|paid|tier|billing)') = 0, 'no premium/subscription columns exist');
select tests.ok((select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public' and p.proname ~* '(premium|subscription|entitlement|billing|is_pro)') = 0, 'no premium/subscription functions exist');

-- ---------------------------------------------------------------- anonymous access
set role anon;
select tests.throws($$ select * from public.profiles $$, 'anon cannot read profiles');
select tests.throws($$ select * from public.live_locations $$, 'anon cannot read locations');
reset role;

\echo 'ALL ACCESS-RULE TESTS PASSED'
