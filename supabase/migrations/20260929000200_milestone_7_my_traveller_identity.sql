begin;

-- An authenticated account is a Traveller only when its own verified Auth
-- identity has an established Traveller profile. No caller-supplied identity.
create function public.get_my_traveller_identity_v1()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
    select jsonb_build_object('full_name', p.full_name, 'handle', p.handle)
    from public.traveller_profiles as p
    join auth.users as u on u.id = p.owner_id
    where p.owner_id = (select auth.uid())
      and u.is_anonymous is false
      and u.email_confirmed_at is not null
      and u.email is not null and btrim(u.email) <> ''
$$;

revoke all on function public.get_my_traveller_identity_v1()
    from public, anon, authenticated, service_role;
grant execute on function public.get_my_traveller_identity_v1() to authenticated;

commit;
