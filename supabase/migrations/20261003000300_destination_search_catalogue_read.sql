begin;

-- The authenticated destination-search Edge Function uses its server-only
-- service credential to resolve canonical catalogue context. Clients retain
-- no direct catalogue access; Journey snapshots and constraints are unchanged.
grant select on public.admin_catalogue_versions, public.admin_regions,
    public.admin_local_units to service_role;

commit;
