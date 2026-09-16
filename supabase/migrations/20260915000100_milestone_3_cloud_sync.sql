begin;

create table public.journeys (
    id uuid primary key,
    owner_id uuid not null references auth.users(id) on delete restrict,
    destination text not null check (length(btrim(destination)) > 0),
    expected_arrival_at timestamptz not null,
    started_at timestamptz not null,
    status text not null check (status in ('ACTIVE', 'COMPLETED')),
    completed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint journeys_completion_consistent check (
        (status = 'ACTIVE' and completed_at is null)
        or (status = 'COMPLETED' and completed_at is not null)
    )
);

create table public.telemetry_observations (
    journey_id uuid not null references public.journeys(id) on delete restrict,
    sequence bigint not null check (sequence > 0),
    event_time timestamptz not null,
    latitude double precision not null check (latitude between -90 and 90),
    longitude double precision not null check (longitude between -180 and 180),
    accuracy_meters double precision not null check (accuracy_meters >= 0),
    battery_percent integer check (battery_percent between 0 and 100),
    charging boolean,
    connectivity_state text not null check (
        connectivity_state in ('NONE', 'CELLULAR', 'WIFI', 'OTHER', 'UNKNOWN')
    ),
    received_at timestamptz not null default now(),
    primary key (journey_id, sequence)
);

create or replace function public.set_journey_updated_at()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

create trigger journeys_set_updated_at
before update on public.journeys
for each row execute function public.set_journey_updated_at();

alter table public.journeys enable row level security;
alter table public.telemetry_observations enable row level security;

revoke all on table public.journeys from public, anon, authenticated;
revoke all on table public.telemetry_observations from public, anon, authenticated;
grant select, insert, update on table public.journeys to authenticated;
grant select, insert, update on table public.telemetry_observations to authenticated;

create policy journeys_select_own
on public.journeys
for select
to authenticated
using (owner_id = (select auth.uid()));

create policy journeys_insert_own
on public.journeys
for insert
to authenticated
with check (owner_id = (select auth.uid()));

create policy journeys_update_own
on public.journeys
for update
to authenticated
using (owner_id = (select auth.uid()))
with check (owner_id = (select auth.uid()));

create policy telemetry_select_through_owned_journey
on public.telemetry_observations
for select
to authenticated
using (
    exists (
        select 1
        from public.journeys
        where journeys.id = telemetry_observations.journey_id
          and journeys.owner_id = (select auth.uid())
    )
);

create policy telemetry_insert_through_owned_journey
on public.telemetry_observations
for insert
to authenticated
with check (
    exists (
        select 1
        from public.journeys
        where journeys.id = telemetry_observations.journey_id
          and journeys.owner_id = (select auth.uid())
    )
);

create policy telemetry_update_through_owned_journey
on public.telemetry_observations
for update
to authenticated
using (
    exists (
        select 1
        from public.journeys
        where journeys.id = telemetry_observations.journey_id
          and journeys.owner_id = (select auth.uid())
    )
)
with check (
    exists (
        select 1
        from public.journeys
        where journeys.id = telemetry_observations.journey_id
          and journeys.owner_id = (select auth.uid())
    )
);

revoke all on function public.set_journey_updated_at() from public, anon, authenticated;

commit;
