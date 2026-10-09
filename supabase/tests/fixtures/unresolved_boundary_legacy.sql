-- Apply ONLY to a disposable local database after the base Journey migration
-- and before the 10A migration. Models an accepted historical Journey whose
-- original ETA exceeds the new v1 maximum. Never apply to hosted data.
insert into auth.users(id,instance_id,aud,role,email,created_at,updated_at,is_anonymous)
values ('10000000-0000-4000-8000-000000000040',
    '00000000-0000-0000-0000-000000000000','authenticated','authenticated',
    'historical-boundary@example.invalid',now(),now(),false);
insert into public.journeys(id,owner_id,destination,started_at,expected_arrival_at,status)
values ('20000000-0000-4000-8000-000000000040','10000000-0000-4000-8000-000000000040',
    'Historical fixture','2026-01-01 UTC','2030-01-01 UTC','ACTIVE');
