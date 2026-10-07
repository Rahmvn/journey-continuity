begin;
select no_plan();

insert into auth.users (id, email, email_confirmed_at, created_at, updated_at)
values ('10000000-0000-4000-8000-0000000000e1', 'evidence-owner@example.invalid', now(), now(), now());

-- Only committed canonical IDs and geographic fixtures; no live geocoder calls.
create function pg_temp.insert_evidence_journey(p_number integer, p_patch jsonb default '{}'::jsonb)
returns void language plpgsql as $$
declare v public.journeys;
begin
 v := jsonb_populate_record(null::public.journeys, '{
 "destination_country_code":"NG", "destination_state_id":"ng-kwara", "destination_state_name":"Kwara",
 "destination_lga_id":"ng-kwara-offa", "destination_lga_name":"Offa", "destination_kind":"POINT",
 "destination_selection_source":"MAP_PIN", "destination_latitude":8.1560008, "destination_longitude":4.7227435,
 "destination_admin_dataset_version":"alabarin-2026-10-05-v2", "destination_local_unit_type":"LGA",
 "destination_boundary_dataset_id":"NG_OPERATIONAL_BOUNDARIES",
 "destination_boundary_dataset_version":"alabarin-2026-10-03-v1"}'::jsonb || p_patch);
 insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status,
 destination_country_code, destination_state_id, destination_state_name, destination_lga_id, destination_lga_name, destination_kind, destination_latitude, destination_longitude, destination_selection_source, destination_provider, destination_provider_place_id, destination_admin_dataset_version, destination_custom_place_name, destination_local_unit_type, destination_boundary_dataset_id, destination_boundary_dataset_version, destination_provider_attribution)
 values (('42000000-0000-4000-8000-' || lpad(p_number::text, 12, '0'))::uuid,
 '10000000-0000-4000-8000-0000000000e1', 'Offa, Kwara', now() + interval '1 hour', now(), 'ACTIVE',
 v.destination_country_code, v.destination_state_id, v.destination_state_name, v.destination_lga_id, v.destination_lga_name, v.destination_kind, v.destination_latitude, v.destination_longitude, v.destination_selection_source, v.destination_provider, v.destination_provider_place_id, v.destination_admin_dataset_version, v.destination_custom_place_name, v.destination_local_unit_type, v.destination_boundary_dataset_id, v.destination_boundary_dataset_version, v.destination_provider_attribution);
end;
$$;

-- Valid locality has no point claim. All changes here occur in this rolled-back fixture transaction.
create function pg_temp.locality_patch() returns jsonb language sql as $$ select '{
 "destination_kind":"LOCALITY", "destination_selection_source":"LOCALITY_SELECTION",
 "destination_latitude":null, "destination_longitude":null,
 "destination_boundary_dataset_id":null, "destination_boundary_dataset_version":null}'::jsonb $$;
create function pg_temp.provider_patch() returns jsonb language sql as $$ select '{
 "destination_provider":"GEOAPIFY", "destination_provider_attribution":{
 "sourceName":"OpenStreetMap", "attribution":"? OpenStreetMap contributors", "license":"ODbL",
 "sourceUrl":"https://www.openstreetmap.org/copyright"}}'::jsonb $$;

select lives_ok($$insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status)
 values ('42000000-0000-4000-8000-000000000099', '10000000-0000-4000-8000-0000000000e1',
 'Legacy display text', now() + interval '1 hour', now(), 'ACTIVE')$$, 'legacy INSERT accepted');
select lives_ok($$update public.journeys set status = 'CANCELLED', ended_at = now()
 where id = '42000000-0000-4000-8000-000000000099'$$, 'legacy lifecycle UPDATE accepted');
select ok((select destination_kind is null and destination_local_unit_type is null and
 destination_boundary_dataset_id is null and destination_boundary_dataset_version is null and
 destination_provider_attribution is null from public.journeys
 where id = '42000000-0000-4000-8000-000000000099'), 'legacy new evidence remains entirely null');
select lives_ok($$insert into public.journeys (id, owner_id, destination, expected_arrival_at, started_at, status, ended_at)
 select id, owner_id, destination, expected_arrival_at, started_at, status, ended_at from public.journeys
 where id = '42000000-0000-4000-8000-000000000099'
 on conflict (id) do update set status = excluded.status, ended_at = excluded.ended_at$$, 'legacy retry upsert accepted');
select throws_ok($$update public.journeys set destination_local_unit_type = 'LGA'
 where id = '42000000-0000-4000-8000-000000000099'$$,
 'JD001', 'Journey destination is immutable', 'legacy row cannot be retrofitted with partial structured evidence');

select lives_ok($$select pg_temp.insert_evidence_journey(1)$$, 'MAP_PIN Offa interior INSIDE without provider accepted');
select lives_ok($$select pg_temp.insert_evidence_journey(2, pg_temp.provider_patch())$$,
 'MAP_PIN genuine reverse-label shape without provider place ID accepted');
select lives_ok($$select pg_temp.insert_evidence_journey(3, pg_temp.provider_patch() || '{"destination_provider_place_id":"reverse-1"}')$$,
 'MAP_PIN reverse-label shape with optional provider place ID accepted');
select lives_ok($$select pg_temp.insert_evidence_journey(4, pg_temp.provider_patch() ||
 '{"destination_selection_source":"PLACE_SEARCH", "destination_provider_place_id":"place-1"}')$$,
 'PLACE_SEARCH INSIDE with full provenance accepted');
select lives_ok($$select pg_temp.insert_evidence_journey(5, pg_temp.locality_patch())$$, 'LOCALITY LGA accepted');
select lives_ok($$select pg_temp.insert_evidence_journey(6, pg_temp.locality_patch() || '{
 "destination_state_id":"ng-fct", "destination_state_name":"Federal Capital Territory",
 "destination_lga_id":"ng-fct-abuja-municipal", "destination_lga_name":"Abuja Municipal Area Council",
 "destination_local_unit_type":"AREA_COUNCIL"}')$$, 'LOCALITY Area Council accepted');
select lives_ok($$select pg_temp.insert_evidence_journey(7, '{"destination_admin_dataset_version":"alabarin-2026-10-03-v1"}')$$,
 'retained catalogue version remains admissible');

select throws_ok($$select pg_temp.insert_evidence_journey(101, '{"destination_admin_dataset_version":"unknown"}'::jsonb)$$, '22023', null, 'invalid catalogue version rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(102, '{"destination_state_id":"ng-lagos","destination_state_name":"Lagos"}'::jsonb)$$, '22023', null, 'wrong region and local-unit relationship rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(103, '{"destination_local_unit_type":"AREA_COUNCIL"}'::jsonb)$$, '22023', null, 'wrong local-unit type rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(104, '{"destination_local_unit_type":null}'::jsonb)$$, '22023', null, 'missing local-unit type rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(105, '{"destination_local_unit_type":"DISTRICT"}'::jsonb)$$, '22023', null, 'unsupported local-unit type rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(106, '{"destination_state_name":"Wrong"}'::jsonb)$$, '22023', null, 'canonical region name cannot be fabricated');

select throws_ok($$select pg_temp.insert_evidence_journey(107, '{"destination_lga_name":"Wrong"}'::jsonb)$$, '22023', null, 'canonical local-unit name cannot be fabricated');

select throws_ok($$select pg_temp.insert_evidence_journey(108, '{"destination_country_code":"GH"}'::jsonb)$$, '22023', null, 'unsupported country rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(109, '{"destination_latitude":8.1998492,"destination_longitude":4.6316354}'::jsonb)$$, '22023', null, 'Oyun coordinate selected as Offa rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(110, '{"destination_latitude":8.14435408227513,"destination_longitude":4.65057447773}'::jsonb)$$, '22023', null, 'Offa Oyun shared boundary rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(111, '{"destination_state_id":"ng-lagos","destination_state_name":"Lagos","destination_lga_id":"ng-lagos-eti-osa","destination_lga_name":"Eti-Osa","destination_latitude":6.463625,"destination_longitude":3.396625}'::jsonb)$$, '22023', null, 'Lagos audited gap Eti-Osa rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(112, '{"destination_boundary_dataset_id":null}'::jsonb)$$, '23514', null, 'missing boundary ID rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(113, '{"destination_boundary_dataset_version":null}'::jsonb)$$, '23514', null, 'missing boundary version never uses latest');

select throws_ok($$select pg_temp.insert_evidence_journey(114, '{"destination_boundary_dataset_id":"OTHER"}'::jsonb)$$, '23514', null, 'unrelated boundary dataset rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(115, '{"destination_boundary_dataset_version":"unknown"}'::jsonb)$$, '22023', null, 'UNAVAILABLE submitted boundary version rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(116, '{"destination_latitude":null}'::jsonb)$$, '23514', null, 'POINT missing coordinate rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(117, '{"destination_longitude":null}'::jsonb)$$, '23514', null, 'POINT missing longitude rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(118, '{"destination_latitude":91}'::jsonb)$$, '23514', null, 'out of range coordinate rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(119, '{"destination_latitude":"NaN"}'::jsonb)$$, '23514', null, 'nonfinite coordinate rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(120, '{"destination_selection_source":"LOCALITY_SELECTION"}'::jsonb)$$, '23514', null, 'contradictory POINT selection source rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(121, '{"destination_provider":"GEOAPIFY"}'::jsonb)$$, '23514', null, 'MAP_PIN provider without attribution rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(122, '{"destination_provider_place_id":"fabricated"}'::jsonb)$$, '23514', null, 'MAP_PIN place ID without provider rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(123, pg_temp.provider_patch() || '{"destination_provider":null}'::jsonb)$$, '23514', null, 'MAP_PIN attribution without provider rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(124, pg_temp.provider_patch() || '{"destination_provider":"OTHER"}'::jsonb)$$, '23514', null, 'MAP_PIN incompatible reverse provider rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(125, pg_temp.provider_patch() || '{"destination_selection_source":"PLACE_SEARCH","destination_provider_place_id":"place-1"}'::jsonb || '{"destination_provider":null}'::jsonb)$$, '23514', null, 'PLACE_SEARCH missing provider rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(126, pg_temp.provider_patch() || '{"destination_selection_source":"PLACE_SEARCH","destination_provider_place_id":"place-1"}'::jsonb || '{"destination_provider_place_id":null}'::jsonb)$$, '23514', null, 'PLACE_SEARCH missing provider_place_id rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(127, pg_temp.provider_patch() || '{"destination_selection_source":"PLACE_SEARCH","destination_provider_place_id":"place-1"}'::jsonb || '{"destination_provider_attribution":null}'::jsonb)$$, '23514', null, 'PLACE_SEARCH missing provider_attribution rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(128, pg_temp.provider_patch() || '{"destination_selection_source":"PLACE_SEARCH","destination_provider_place_id":"place-1"}'::jsonb || '{"destination_boundary_dataset_version":null}'::jsonb)$$, '23514', null, 'PLACE_SEARCH missing boundary evidence rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(129, pg_temp.locality_patch() || '{"destination_latitude":8.1}'::jsonb)$$, '23514', null, 'LOCALITY latitude rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(130, pg_temp.locality_patch() || '{"destination_longitude":4.7}'::jsonb)$$, '23514', null, 'LOCALITY longitude rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(131, pg_temp.locality_patch() || '{"destination_boundary_dataset_id":"NG_OPERATIONAL_BOUNDARIES"}'::jsonb)$$, '23514', null, 'LOCALITY boundary_dataset_id rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(132, pg_temp.locality_patch() || '{"destination_boundary_dataset_version":"v1"}'::jsonb)$$, '23514', null, 'LOCALITY boundary_dataset_version rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(133, pg_temp.locality_patch() || '{"destination_provider":"GEOAPIFY"}'::jsonb)$$, '23514', null, 'LOCALITY provider rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(134, pg_temp.locality_patch() || '{"destination_provider_place_id":"id"}'::jsonb)$$, '23514', null, 'LOCALITY provider_place_id rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(135, pg_temp.locality_patch() || '{"destination_provider_attribution":{"sourceName":"OpenStreetMap","attribution":"? OSM","license":"ODbL","sourceUrl":"https://osm.org"}}'::jsonb)$$, '23514', null, 'LOCALITY provider_attribution rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(136, pg_temp.locality_patch() || '{"destination_custom_place_name":"Home"}'::jsonb)$$, '23514', null, 'LOCALITY custom_place_name rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(137, pg_temp.provider_patch() || '{"destination_provider_place_id":" "}'::jsonb)$$, '23514', null, 'blank optional destination_provider_place_id rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(138, pg_temp.provider_patch() || '{"destination_custom_place_name":" "}'::jsonb)$$, '23514', null, 'blank optional destination_custom_place_name rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(139, pg_temp.provider_patch() || '{"destination_provider_attribution":{}}'::jsonb)$$, '23514', null, 'empty attribution rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(140, pg_temp.provider_patch() || '{"destination_provider_attribution":{"sourceName":"OpenStreetMap","attribution":"? OSM","license":"ODbL","sourceUrl":"https://osm.org","raw":{}}}'::jsonb)$$, '23514', null, 'raw provider envelope rejected');

select throws_ok($$select pg_temp.insert_evidence_journey(141, pg_temp.provider_patch() || '{"destination_provider_attribution":{"sourceName":"OpenStreetMap","attribution":"? OSM","license":"ODbL","sourceUrl":"https://user:pass@osm.org"}}'::jsonb)$$, '23514', null, 'credential URL provenance rejected');

-- Pinned boundary version wins even if a newer unrelated/unready dataset exists.
insert into private.boundary_dataset_versions
select dataset_id, 'future-unavailable-fixture', date '2099-01-01', source_attribution,
 source_license, source_license_url, source_lga_sha256, source_state_sha256, mapping_sha256,
 expected_region_count, expected_local_unit_count from private.boundary_dataset_versions
where version = 'alabarin-2026-10-03-v1';
select lives_ok($$select pg_temp.insert_evidence_journey(8)$$, 'explicit frozen version unaffected by latest unready boundaries');
select throws_ok($$select pg_temp.insert_evidence_journey(9, '{"destination_boundary_dataset_version":"future-unavailable-fixture"}')$$,
 '22023', null, 'unready claimed version cannot borrow older geometry');
select lives_ok($$select pg_temp.insert_evidence_journey(10, pg_temp.locality_patch())$$,
 'LOCALITY does not depend on point classifier readiness');

select throws_ok(format('update public.journeys set %I = %L where id = %L', field, value,
 '42000000-0000-4000-8000-000000000004'), 'JD001', 'Journey destination is immutable', label)
from (values
 ('destination_local_unit_type', 'AREA_COUNCIL', 'local-unit type is immutable'),
 ('destination_boundary_dataset_id', 'OTHER', 'boundary ID is immutable'),
 ('destination_boundary_dataset_version', 'OTHER', 'boundary version is immutable'),
 ('destination_provider_attribution', '{}', 'provider attribution is immutable')
) as mutations(field, value, label);
select throws_ok($$update public.journeys set destination_provider = 'GEOAPIFY',
 destination_provider_attribution = pg_temp.provider_patch()->'destination_provider_attribution'
 where id = '42000000-0000-4000-8000-000000000001'$$,
 'JD001', 'Journey destination is immutable', 'provider-free MAP_PIN cannot be enriched later');
select lives_ok($$update public.journeys set destination_local_unit_type = null,
 destination_boundary_dataset_id = null, destination_boundary_dataset_version = null,
 destination_provider_attribution = null, status = 'COMPLETED', completed_at = now()
 where id = '42000000-0000-4000-8000-000000000004'$$, 'null compatibility preserves evidence during lifecycle update');
select ok((select destination_local_unit_type = 'LGA' and destination_boundary_dataset_id = 'NG_OPERATIONAL_BOUNDARIES'
 and destination_boundary_dataset_version = 'alabarin-2026-10-03-v1' and destination_provider_attribution is not null
 and status = 'COMPLETED' from public.journeys where id = '42000000-0000-4000-8000-000000000004'),
 'all evidence survives lifecycle update');
select lives_ok($$insert into public.journeys select * from public.journeys
 where id = '42000000-0000-4000-8000-000000000001'
 on conflict (id) do update set status = excluded.status,
 destination_local_unit_type = excluded.destination_local_unit_type,
 destination_boundary_dataset_id = excluded.destination_boundary_dataset_id,
 destination_boundary_dataset_version = excluded.destination_boundary_dataset_version,
 destination_provider_attribution = excluded.destination_provider_attribution$$, 'UUID replay retains exact frozen evidence');

-- Once admitted, lifecycle updates never reclassify frozen destination facts.
update private.boundary_dataset_versions set expected_local_unit_count = expected_local_unit_count + 1
where dataset_id = 'NG_OPERATIONAL_BOUNDARIES' and version = 'alabarin-2026-10-03-v1';
select throws_ok($$select pg_temp.insert_evidence_journey(13)$$, '22023', null,
 'POINT INSERT fails closed when submitted boundary snapshot is incomplete');
select lives_ok($$update public.journeys set status = 'COMPLETED', completed_at = now()
 where id = '42000000-0000-4000-8000-000000000001'$$,
 'ordinary existing structured lifecycle update does not need available geography');
select lives_ok($$update public.journeys set status = status
 where id = '42000000-0000-4000-8000-000000000099'$$,
 'legacy lifecycle update remains independent of geographic availability');
update private.boundary_dataset_versions set expected_local_unit_count = expected_local_unit_count - 1
where dataset_id = 'NG_OPERATIONAL_BOUNDARIES' and version = 'alabarin-2026-10-03-v1';

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-0000000000e1';
select lives_ok($$select pg_temp.insert_evidence_journey(11, pg_temp.provider_patch())$$,
 'authenticated owner passes shape and geography without private schema access');
select lives_ok($$select pg_temp.insert_evidence_journey(12, pg_temp.provider_patch() ||
 '{"destination_selection_source":"PLACE_SEARCH", "destination_provider_place_id":"place-auth"}')$$,
 'authenticated owner PLACE_SEARCH retained provenance accepted');
select throws_ok($$select private.classify_destination_coordinate_v1('ng-kwara','ng-kwara-offa',8.1560008,4.7227435,'alabarin-2026-10-03-v1')$$,
 '42501', null, 'owner cannot call private classifier directly');
select throws_ok($$select * from private.boundary_local_units$$, '42501', null, 'owner cannot read private geography');
reset role;
select * from finish();
rollback;
