begin;
select no_plan();

insert into auth.users (id, instance_id, aud, role, email, created_at, updated_at)
values
 ('10000000-0000-4000-8000-000000000091', '00000000-0000-0000-0000-000000000000',
  'authenticated', 'authenticated', 'destination-owner@example.invalid', now(), now()),
 ('10000000-0000-4000-8000-000000000092', '00000000-0000-0000-0000-000000000000',
  'authenticated', 'authenticated', 'destination-contact@example.invalid', now(), now()),
 ('10000000-0000-4000-8000-000000000093', '00000000-0000-0000-0000-000000000000',
  'authenticated', 'authenticated', 'destination-stranger@example.invalid', now(), now());

insert into public.trusted_contact_relationships
 (id, traveller_user_id, contact_user_id, display_name, contact_email_normalized)
values ('30000000-0000-4000-8000-000000000091',
 '10000000-0000-4000-8000-000000000091', '10000000-0000-4000-8000-000000000092',
 'Contact', 'destination-contact@example.invalid');

insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status)
values ('20000000-0000-4000-8000-000000000091', '10000000-0000-4000-8000-000000000091',
 '  Ilorin  ', now() + interval '1 hour', now(), 'ACTIVE');
select is((select destination from public.journeys where id = '20000000-0000-4000-8000-000000000091'),
 '  Ilorin  ', 'historical label is preserved exactly');
select ok((select destination_kind is null and destination_latitude is null and
 destination_state_id is null from public.journeys where id = '20000000-0000-4000-8000-000000000091'),
 'historical row has unknown structured facts');

insert into public.journeys (
 id, owner_id, destination, expected_arrival_at, started_at, status,
 destination_country_code, destination_state_id, destination_state_name,
 destination_lga_id, destination_lga_name, destination_kind,
 destination_latitude, destination_longitude, destination_selection_source,
 destination_custom_place_name)
values ('20000000-0000-4000-8000-000000000092', '10000000-0000-4000-8000-000000000091',
 'Grandma''s house', now() + interval '1 hour', now(), 'ACTIVE',
 'NG', 'NG-KW', 'Kwara', 'NG-KW-OFFA', 'Offa', 'POINT', 8.49, 4.56, 'MAP_PIN', 'Grandma''s house');
select is((select destination_latitude from public.journeys
 where id = '20000000-0000-4000-8000-000000000092'), 8.49::double precision,
 'point coordinates persist');

insert into public.journeys (
 id, owner_id, destination, expected_arrival_at, started_at, status,
 destination_country_code, destination_state_id, destination_state_name,
 destination_lga_id, destination_lga_name, destination_kind, destination_selection_source)
values ('20000000-0000-4000-8000-000000000093', '10000000-0000-4000-8000-000000000091',
 'Offa', now() + interval '1 hour', now(), 'ACTIVE',
 'NG', 'NG-KW', 'Kwara', 'NG-KW-OFFA', 'Offa', 'LOCALITY', 'LOCALITY_SELECTION');
select ok((select destination_kind = 'LOCALITY' and destination_latitude is null and
 destination_longitude is null from public.journeys where id = '20000000-0000-4000-8000-000000000093'),
 'locality remains valid without a fabricated point');

insert into public.journeys (
 id, owner_id, destination, expected_arrival_at, started_at, status,
 destination_country_code, destination_state_id, destination_state_name,
 destination_lga_id, destination_lga_name, destination_kind,
 destination_latitude, destination_longitude, destination_selection_source)
values ('20000000-0000-4000-8000-000000000096', '10000000-0000-4000-8000-000000000091',
 'Summit University, Offa', now() + interval '1 hour', now(), 'ACTIVE',
 'NG', 'NG-KW', 'Kwara', 'NG-KW-OFFA', 'Offa', 'POINT', 8.50, 4.57, 'PLACE_SEARCH');
select ok((select destination_selection_source = 'PLACE_SEARCH' from public.journeys
 where id = '20000000-0000-4000-8000-000000000096'), 'place-search point is valid');

select throws_ok($$insert into public.journeys (
 id, owner_id, destination, expected_arrival_at, started_at, status,
 destination_country_code, destination_state_id, destination_state_name,
 destination_lga_id, destination_lga_name, destination_kind,
 destination_latitude, destination_selection_source)
 values ('20000000-0000-4000-8000-000000000094', '10000000-0000-4000-8000-000000000091',
 'Bad point', now() + interval '1 hour', now(), 'ACTIVE',
 'NG', 'NG-KW', 'Kwara', 'NG-KW-OFFA', 'Offa', 'POINT', 8.49, 'MAP_PIN')$$,
 '23514', null,
 'half coordinate rejected');
select throws_ok($$insert into public.journeys (
 id, owner_id, destination, expected_arrival_at, started_at, status,
 destination_country_code, destination_state_id, destination_state_name,
 destination_lga_id, destination_lga_name, destination_kind,
 destination_latitude, destination_longitude, destination_selection_source)
 values ('20000000-0000-4000-8000-000000000095', '10000000-0000-4000-8000-000000000091',
 'Bad latitude', now() + interval '1 hour', now(), 'ACTIVE',
 'NG', 'NG-KW', 'Kwara', 'NG-KW-OFFA', 'Offa', 'POINT', 91, 4.56, 'MAP_PIN')$$,
 '23514', null,
 'invalid latitude rejected');
select throws_ok($$insert into public.journeys (
 id, owner_id, destination, expected_arrival_at, started_at, status,
 destination_country_code, destination_state_id, destination_state_name,
 destination_lga_id, destination_lga_name, destination_kind,
 destination_latitude, destination_longitude, destination_selection_source)
 values ('20000000-0000-4000-8000-000000000097', '10000000-0000-4000-8000-000000000091',
 'Imprecise locality', now() + interval '1 hour', now(), 'ACTIVE',
 'NG', 'NG-KW', 'Kwara', 'NG-KW-OFFA', 'Offa', 'LOCALITY', 8.49, 4.56, 'LOCALITY_SELECTION')$$,
 '23514', null, 'locality cannot carry a coordinate');
select throws_ok($$insert into public.journeys (
 id, owner_id, destination, expected_arrival_at, started_at, status,
 destination_country_code, destination_state_id, destination_state_name,
 destination_lga_id, destination_lga_name, destination_kind,
 destination_latitude, destination_longitude, destination_selection_source)
 values ('20000000-0000-4000-8000-000000000098', '10000000-0000-4000-8000-000000000091',
 'Wrong source', now() + interval '1 hour', now(), 'ACTIVE',
 'NG', 'NG-KW', 'Kwara', 'NG-KW-OFFA', 'Offa', 'POINT', 8.49, 4.56, 'LOCALITY_SELECTION')$$,
 '23514', null, 'point cannot use locality selection');
select throws_ok($$insert into public.journeys (
 id, owner_id, destination, expected_arrival_at, started_at, status, destination_state_id)
 values ('20000000-0000-4000-8000-000000000099', '10000000-0000-4000-8000-000000000091',
 'Partial', now() + interval '1 hour', now(), 'ACTIVE', 'NG-KW')$$,
 '23514', null, 'partial structured destination is rejected');

-- A current client can repeat exactly the same facts without changing destination evidence.
insert into public.journeys (
 id, owner_id, destination, expected_arrival_at, started_at, status,
 destination_country_code, destination_state_id, destination_state_name,
 destination_lga_id, destination_lga_name, destination_kind,
 destination_latitude, destination_longitude, destination_selection_source,
 destination_custom_place_name)
select id, owner_id, destination, expected_arrival_at, started_at, status,
 destination_country_code, destination_state_id, destination_state_name,
 destination_lga_id, destination_lga_name, destination_kind,
 destination_latitude, destination_longitude, destination_selection_source,
 destination_custom_place_name
from public.journeys where id = '20000000-0000-4000-8000-000000000092'
on conflict (id) do update set destination = excluded.destination,
 destination_kind = excluded.destination_kind,
 destination_latitude = excluded.destination_latitude,
 destination_longitude = excluded.destination_longitude,
 destination_selection_source = excluded.destination_selection_source;
select ok((select destination_kind = 'POINT' and destination_latitude = 8.49 from public.journeys
 where id = '20000000-0000-4000-8000-000000000092'), 'same-value current upsert succeeds');

-- Equivalent conflict shape to an old PostgREST merge upsert with omitted new columns.
insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status)
select id, owner_id, destination, expected_arrival_at, started_at, status
from public.journeys where id = '20000000-0000-4000-8000-000000000092'
on conflict (id) do update set destination_kind = excluded.destination_kind,
 destination_latitude = excluded.destination_latitude,
 destination_longitude = excluded.destination_longitude;
select ok((select destination_kind = 'POINT' and destination_latitude = 8.49 and
 destination_longitude = 4.56 from public.journeys
 where id = '20000000-0000-4000-8000-000000000092'),
 'old-client text-only conflict cannot erase a structured point');
update public.journeys set status = status where id = '20000000-0000-4000-8000-000000000092';
select ok((select destination_kind = 'POINT' and destination_custom_place_name = 'Grandma''s house'
 from public.journeys where id = '20000000-0000-4000-8000-000000000092'),
 'omitted destination facts survive an ordinary lifecycle update');
select throws_ok($$update public.journeys set destination_latitude = 8.50
 where id = '20000000-0000-4000-8000-000000000092'$$,
 'JD001', 'Journey destination is immutable', 'active destination is immutable');
update public.journeys set status = 'COMPLETED', completed_at = now()
where id = '20000000-0000-4000-8000-000000000092';
select throws_ok($$update public.journeys set destination_lga_name = 'Other'
 where id = '20000000-0000-4000-8000-000000000092'$$,
 'JD001', 'Journey destination is immutable', 'terminal destination is immutable');

insert into public.journey_trusted_contact_access
 (journey_id, relationship_id, traveller_user_id, contact_user_id)
values ('20000000-0000-4000-8000-000000000092',
 '30000000-0000-4000-8000-000000000091',
 '10000000-0000-4000-8000-000000000091',
 '10000000-0000-4000-8000-000000000092')
on conflict do nothing;

set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000093';
select is((select count(*) from public.journeys
 where id = '20000000-0000-4000-8000-000000000092'), 0::bigint,
 'non-owner cannot read the structured Journey row');
select is((select count(*) from public.list_trusted_journey_statuses_v2()), 0::bigint,
 'stranger cannot read the trusted projection');
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000092';
select ok((select destination = 'Grandma''s house' from public.list_trusted_journey_statuses_v2()
 where journey_id = '20000000-0000-4000-8000-000000000092'),
 'trusted projection retains the human label');
select ok(not (public.get_trusted_journey_status_v2('20000000-0000-4000-8000-000000000092')
 ?| array['destination_latitude', 'destination_longitude', 'destination_provider',
           'destination_provider_place_id', 'destination_admin_dataset_version',
           'destination_custom_place_name', 'destination_selection_source',
           'destination_state_id', 'destination_lga_id']),
 'trusted status excludes structured facts');
select ok(not (public.get_trusted_journey_snapshot('20000000-0000-4000-8000-000000000092')
 ?| array['destination_latitude', 'destination_longitude', 'destination_provider',
           'destination_provider_place_id', 'destination_admin_dataset_version',
           'destination_custom_place_name', 'destination_selection_source',
           'destination_state_id', 'destination_lga_id']),
 'trusted snapshot excludes structured facts');
reset role;

select * from finish();
rollback;
