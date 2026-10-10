begin;

-- Owner recovery of an already committed Section 09 outcome. No new window,
-- presentation, response, finalization or trusted-contact permission is created.
create function public.get_owner_probable_end_auto_end_finalization_v1(p_journey_id uuid)
returns table (
    decision text, presentation_episode_id uuid, journey_status text,
    ended_at timestamptz, not_before_at timestamptz,
    journey_id uuid, owner_id uuid, candidate_episode_id uuid,
    detector_generation bigint, policy_version text,
    history_baseline_at timestamptz, guard_version bigint,
    registered_at timestamptz, finalized_at timestamptz
)
language plpgsql stable security definer set search_path = '' as $$
declare
    v_owner uuid := (select auth.uid());
    v_facts record;
begin
    if v_owner is null or not exists (
        select 1 from auth.users u where u.id = v_owner and u.is_anonymous is false
    ) then
        raise exception 'Non-anonymous Traveller identity required' using errcode = '42501';
    end if;

    -- One statement snapshot observes Journey truth and its finalized window
    -- together. Count all finalized windows, not just ones matching ended_at:
    -- ambiguous/corrupt provenance must fail closed rather than choose a row.
    select j.id as journey_id, j.owner_id, j.status as journey_status,
           j.ended_at, j.completed_at,
           w.presentation_episode_id, w.owner_id as window_owner_id,
           w.candidate_episode_id, w.detector_generation, w.policy_version,
           w.history_baseline_at, w.guard_version,
           w.registered_at, w.not_before_at, w.finalized_at, w.finalized_count
      into v_facts
      from public.journeys j
      left join lateral (
          select cw.*, count(*) over () as finalized_count
            from private.probable_end_confirmation_windows cw
           where cw.journey_id = j.id and cw.finalized_at is not null
      ) w on true
     where j.id = p_journey_id and j.owner_id = v_owner;

    if not found then
        raise exception 'Journey is not available to this caller' using errcode = '42501';
    end if;
    if v_facts.journey_status <> 'AUTO_ENDED' then
        raise exception 'Journey has no automatic-end finalization' using errcode = '22023';
    end if;
    if v_facts.finalized_count is distinct from 1::bigint or
       v_facts.window_owner_id is distinct from v_owner or
       v_facts.completed_at is not null or
       v_facts.ended_at is distinct from v_facts.finalized_at or
       v_facts.presentation_episode_id is null or v_facts.candidate_episode_id is null or
       v_facts.detector_generation is null or v_facts.detector_generation < 0 or
       v_facts.policy_version is distinct from 'alabarin-probable-end-v1' or
       v_facts.history_baseline_at is null or
       v_facts.guard_version is null or v_facts.guard_version < 0 or
       v_facts.registered_at is null or v_facts.not_before_at is null or
       v_facts.finalized_at is null or
       not isfinite(v_facts.history_baseline_at) or
       not isfinite(v_facts.registered_at) or not isfinite(v_facts.not_before_at) or
       not isfinite(v_facts.finalized_at) or
       v_facts.not_before_at is distinct from v_facts.registered_at + interval '30 minutes' or
       v_facts.finalized_at < v_facts.not_before_at then
        raise exception 'Inconsistent automatic-end provenance' using errcode = '23514';
    end if;

    return query select 'AUTO_ENDED'::text, v_facts.presentation_episode_id,
        v_facts.journey_status, v_facts.ended_at, v_facts.not_before_at,
        v_facts.journey_id, v_facts.owner_id, v_facts.candidate_episode_id,
        v_facts.detector_generation, v_facts.policy_version,
        v_facts.history_baseline_at, v_facts.guard_version,
        v_facts.registered_at, v_facts.finalized_at;
end;
$$;

revoke all on function public.get_owner_probable_end_auto_end_finalization_v1(uuid)
    from public, anon, authenticated, service_role;
grant execute on function public.get_owner_probable_end_auto_end_finalization_v1(uuid)
    to authenticated;

commit;
