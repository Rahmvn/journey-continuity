begin;
select no_plan();

select ok(
    has_table_privilege('service_role', 'public.admin_catalogue_versions', 'SELECT') and
    has_table_privilege('service_role', 'public.admin_regions', 'SELECT') and
    has_table_privilege('service_role', 'public.admin_local_units', 'SELECT'),
    'server-only destination search can read all catalogue tables'
);
select ok(
    not has_table_privilege('service_role', 'public.admin_catalogue_versions', 'INSERT') and
    not has_table_privilege('service_role', 'public.admin_regions', 'UPDATE') and
    not has_table_privilege('service_role', 'public.admin_local_units', 'DELETE'),
    'search grant did not add catalogue writes'
);
select ok(
    not has_table_privilege('anon', 'public.admin_regions', 'SELECT') and
    not has_table_privilege('authenticated', 'public.admin_regions', 'SELECT') and
    not has_table_privilege('anon', 'public.admin_local_units', 'SELECT') and
    not has_table_privilege('authenticated', 'public.admin_catalogue_versions', 'SELECT'),
    'client roles still cannot read catalogue tables'
);
select ok((select rolbypassrls from pg_roles where rolname = 'service_role'),
    'service role can pass catalogue RLS after the narrow SELECT grant');

set local role service_role;
select is((select count(*) from public.admin_catalogue_versions where dataset_id = 'NG_ADMIN'),
    2::bigint, 'search service sees both retained NG_ADMIN versions');
select is((select count(*) from public.admin_regions where dataset_id = 'NG_ADMIN' and version = 'alabarin-2026-10-05-v2'),
    37::bigint, 'search service sees 37 regions');
select is((select count(*) from public.admin_local_units where dataset_id = 'NG_ADMIN' and version = 'alabarin-2026-10-05-v2'),
    774::bigint, 'search service sees 774 local units');
reset role;

select * from finish();
rollback;
