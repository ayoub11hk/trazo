-- Tests for 20261015000000_live_profiles.sql. Runs after 10_access_rules.sql (reuses its helpers).
\set QUIET on

insert into auth.users (email, raw_user_meta_data) values
  ('eva@test', '{"display_name":"Eva"}'), ('fran@test', '{"display_name":"Fran"}'), ('gus@test', '{"display_name":"Gus"}');

-- ---------------------------------------------------------------- user names and bio
select tests.login('eva@test');
update public.profiles set username = 'eva.r', bio = 'Conduzco por Madrid' where id = auth.uid();
select tests.ok((select username from public.profiles where id = auth.uid()) = 'eva.r', 'can set own user name');
select tests.throws($$ update public.profiles set username = 'Eva!' where id = auth.uid() $$, 'invalid user name rejected');
select tests.throws($$ update public.profiles set bio = repeat('x', 141) where id = auth.uid() $$, 'bio longer than 140 rejected');
reset role;

select tests.login('fran@test');
select tests.throws($$ update public.profiles set username = 'eva.r' where id = auth.uid() $$, 'user names are unique');
update public.profiles set username = 'fran_m' where id = auth.uid();
select tests.ok((select count(*) from public.find_users('ev')) = 0, 'search needs at least 3 characters');
select tests.ok((select count(*) from public.find_users('fran_m')) = 0, 'search never returns yourself');
select tests.ok((select relation from public.find_users('@Eva')) = 'none', 'search by user name, case and @ ignored');
select tests.ok((select count(*) from public.find_users('fra%')) = 0, 'wildcards are not accepted');
select tests.ok((select count(*) from public.live_locations) = 0, 'search reveals no location');
select tests.ok((select status from public.send_friend_request_to((select id from public.find_users('eva.r')))) = 'pending',
  'friend request to a found user');
select tests.ok((select relation from public.find_users('eva.r')) = 'pending_out', 'search shows the pending request');
select tests.throws($$ select public.send_friend_request_to(auth.uid()) $$, 'cannot befriend yourself');
select tests.throws($$ select public.send_friend_request_to(gen_random_uuid()) $$, 'unknown user rejected');
reset role;

select tests.login('eva@test');
select tests.ok((select relation from public.find_users('fran_m')) = 'pending_in', 'receiver sees incoming request in search');
select public.respond_friend_request((select id from public.pending_requests() where incoming), true);
select tests.ok(public.are_friends(auth.uid(), tests.uid('fran@test')), 'request accepted');

-- Eva drives: a trip in progress with uploaded points and her live position.
insert into public.trips (id, owner_id, status, started_at) values ('11111111-1111-1111-1111-111111111111', auth.uid(), 'recording', now() - interval '10 minutes');
insert into public.trip_points (trip_id, seq, t, elapsed_ms, lat, lng, accuracy_m, flag)
  select '11111111-1111-1111-1111-111111111111', g, now() - interval '10 minutes' + g * interval '1 second', g * 1000,
         40.41 + g * 0.0001, -3.70, 5, case when g = 7 then 2 else 0 end
  from generate_series(0, 49) g;
insert into public.live_locations (user_id, lat, lng, accuracy_m, state, trip_id, recorded_at)
  values (auth.uid(), 40.415, -3.70, 5, 'driving', '11111111-1111-1111-1111-111111111111', now());
reset role;

select tests.login('fran@test');
select tests.ok((select count(*) from public.live_trail(tests.uid('eva@test'))) = 49, 'friend gets the live trail (rejected GPS points excluded)');
select tests.ok((select bool_and(a.t <= b.t) from (select t, lead(t) over (order by t) as nt from public.live_trail(tests.uid('eva@test'))) a
  join lateral (select a.nt as t) b on true where a.nt is not null), 'trail is in time order');
select tests.ok((select username from public.profiles where id = tests.uid('eva@test')) = 'eva.r', 'friend sees user name');
reset role;

select tests.login('gus@test');
select tests.ok((select count(*) from public.live_trail(tests.uid('eva@test'))) = 0, 'stranger gets no live trail');
select tests.ok((select count(*) from public.find_users('eva.r')) = 1, 'stranger can find the name to send a request');
select tests.ok((select count(*) from public.profiles where id = tests.uid('eva@test')) = 0, 'stranger still cannot read the profile');
reset role;

-- Eva stops sharing with Fran only.
select tests.login('eva@test');
update public.friendships set share_location = false where user_id = auth.uid() and friend_id = tests.uid('fran@test');
reset role;
select tests.login('fran@test');
select tests.ok((select count(*) from public.live_trail(tests.uid('eva@test'))) = 0, 'no trail once she stops sharing with me');
reset role;
select tests.login('eva@test');
update public.friendships set share_location = true where user_id = auth.uid() and friend_id = tests.uid('fran@test');
update public.user_settings set ghost_mode = true where user_id = auth.uid();
reset role;
select tests.login('fran@test');
select tests.ok((select count(*) from public.live_trail(tests.uid('eva@test'))) = 0, 'no trail in ghost mode');
reset role;
select tests.login('eva@test');
update public.user_settings set ghost_mode = false where user_id = auth.uid();
update public.live_locations set sharing = true, lat = 40.415, lng = -3.70, trip_id = '11111111-1111-1111-1111-111111111111' where user_id = auth.uid();
update public.trips set status = 'completed', ended_at = now() where id = '11111111-1111-1111-1111-111111111111';
reset role;
select tests.login('fran@test');
select tests.ok((select count(*) from public.live_trail(tests.uid('eva@test'))) = 0, 'finished trips are history, not live');
reset role;

set role anon;
select tests.throws($$ select * from public.find_users('eva.r') $$, 'anon cannot search users');
select tests.throws($$ select * from public.live_trail(gen_random_uuid()) $$, 'anon cannot read trails');
reset role;

\echo 'ALL LIVE-PROFILE TESTS PASSED'
