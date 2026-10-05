#!/usr/bin/env node
// Generates the initial catalogue schema or a retained-version seed from a canonical asset.
// Identity: SHA-256 of the exact committed UTF-8 JSON bytes, including whitespace/newline.
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const asset = fileURLToPath(new URL('../app/src/main/assets/catalogue/ng_admin_v1.json', import.meta.url));
const expectedHashRule = 'SHA-256 of exact committed UTF-8 JSON bytes';
const idPattern = /^ng-[a-z0-9]+(?:-[a-z0-9]+)*$/;
const sourcePattern = /^source-[0-9]{2}$/;
const fctNames = new Set(['Abaji', 'Abuja Municipal Area Council', 'Bwari', 'Gwagwalada', 'Kuje', 'Kwali']);

function check(condition, message) {
  if (!condition) throw new Error(message);
}

function meaningful(value) {
  return typeof value === 'string' && value.length > 0 && value.trim() === value &&
    value.trim().length > 0 && !value.includes('\uFFFD');
}

function date(value) {
  check(typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value), `Invalid date: ${value}`);
  const parsed = new Date(`${value}T00:00:00Z`);
  check(!Number.isNaN(parsed.valueOf()) && parsed.toISOString().startsWith(value), `Invalid date: ${value}`);
}

function normalizedName(value) {
  return value.trim().toLowerCase().replace(/\s+/gu, ' ');
}

function ordersAreContiguous(items) {
  return items.map(item => item.displayOrder).sort((a, b) => a - b)
    .every((order, index) => order === index);
}

export function validate(document) {
  check(document.datasetId === 'NG_ADMIN', 'Unexpected dataset ID');
  check(meaningful(document.version) && document.version.startsWith('alabarin-'),
    'Version must identify an Alabarin snapshot');
  date(document.snapshotDate);
  check(document.hashRule === expectedHashRule, 'Unsupported hash rule');
  check(Array.isArray(document.sources) && document.sources.length > 0, 'Missing provenance');
  const sourceIds = new Set();
  for (const source of document.sources) {
    check(sourcePattern.test(source.id) && !sourceIds.has(source.id), `Invalid source ID: ${source.id}`);
    check(meaningful(source.authority) && meaningful(source.title), `Incomplete source: ${source.id}`);
    check(meaningful(source.url) && new URL(source.url).protocol === 'https:', `Invalid source URL: ${source.id}`);
    date(source.retrievedAt);
    sourceIds.add(source.id);
  }
  check(sourceIds.has(document.regionIndexSourceId), 'Unknown region index source');
  const referencedSourceIds = new Set([document.regionIndexSourceId]);
  check(Array.isArray(document.regions) && document.regions.length === 37, 'Expected 37 regions');
  check(document.regions.filter(region => region.type === 'STATE').length === 36, 'Expected 36 states');
  check(document.regions.filter(region => region.type === 'FCT').length === 1, 'Expected one FCT');
  check(ordersAreContiguous(document.regions), 'Region display order is not contiguous');
  const regions = new Map();
  for (const region of document.regions) {
    check(idPattern.test(region.id) && meaningful(region.canonicalName) && !regions.has(region.id),
      `Invalid region: ${region.id}`);
    check(sourceIds.has(region.sourceId), `Unknown region source: ${region.id}`);
    referencedSourceIds.add(region.sourceId);
    check((region.id === 'ng-fct') === (region.type === 'FCT'), `Invalid FCT identity: ${region.id}`);
    regions.set(region.id, region);
  }
  check(regions.get('ng-fct')?.canonicalName === 'Federal Capital Territory', 'FCT missing');
  check(Array.isArray(document.localUnits) && document.localUnits.length === 774, 'Expected 774 local units');
  check(document.localUnits.filter(unit => unit.type === 'LGA').length === 768, 'Expected 768 LGAs');
  check(document.localUnits.filter(unit => unit.type === 'AREA_COUNCIL').length === 6, 'Expected six Area Councils');
  const unitIds = new Set();
  const siblingNames = new Set();
  const byParent = new Map();
  for (const unit of document.localUnits) {
    const parent = regions.get(unit.parentRegionId);
    check(parent && idPattern.test(unit.id) && unit.id.startsWith(`${parent.id}-`) &&
      unit.id.length > parent.id.length + 1 && !unitIds.has(unit.id) && meaningful(unit.canonicalName),
    `Invalid local unit: ${unit.id}`);
    check(unit.sourceId == null || sourceIds.has(unit.sourceId), `Unknown unit source: ${unit.id}`);
    if (unit.sourceId != null) referencedSourceIds.add(unit.sourceId);
    check((parent.type === 'FCT' && unit.type === 'AREA_COUNCIL') ||
      (parent.type === 'STATE' && unit.type === 'LGA'), `Wrong local unit type: ${unit.id}`);
    check(unit.aliases == null || Array.isArray(unit.aliases), `Invalid aliases: ${unit.id}`);
    for (const name of [unit.canonicalName, ...(unit.aliases ?? [])]) {
      const key = `${parent.id}\u0000${normalizedName(name)}`;
      check(meaningful(name) && !siblingNames.has(key), `Sibling name collision: ${parent.id} / ${name}`);
      siblingNames.add(key);
    }
    unitIds.add(unit.id);
    if (!byParent.has(parent.id)) byParent.set(parent.id, []);
    byParent.get(parent.id).push(unit);
  }
  for (const source of document.sources) {
    check(referencedSourceIds.has(source.id), `Unused catalogue source: ${source.id}`);
  }
  for (const region of document.regions) {
    check(ordersAreContiguous(byParent.get(region.id) ?? []), `Invalid child order: ${region.id}`);
  }
  const fct = byParent.get('ng-fct') ?? [];
  check(fct.length === 6 && fct.every(unit => fctNames.has(unit.canonicalName)), 'Invalid FCT Area Councils');
  const amac = fct.find(unit => unit.canonicalName === 'Abuja Municipal Area Council');
  check(amac?.id === 'ng-fct-abuja-municipal' && amac.aliases?.includes('AMAC'), 'AMAC alias missing');
  check(byParent.get('ng-kwara')?.length === 16, 'Kwara must have 16 LGAs');
}

const sql = value => `'${String(value).replaceAll("'", "''")}'`;

// Shared seed emitter: retained versions add rows without changing schema or triggers.
function snapshotInserts(document, hash) {
  const dataset = sql(document.datasetId);
  const version = sql(document.version);
  const regionRows = document.regions.map(region =>
    `(${dataset}, ${version}, ${sql(region.id)}, ${sql(region.canonicalName)}, ${sql(region.type)}, ${region.displayOrder})`);
  const regionsById = new Map(document.regions.map(region => [region.id, region]));
  const unitRows = document.localUnits.map(unit =>
    `(${dataset}, ${version}, ${sql(unit.id)}, ${sql(unit.parentRegionId)}, ${sql(regionsById.get(unit.parentRegionId).type)}, ${sql(unit.canonicalName)}, ${sql(unit.type)}, ${unit.displayOrder})`);
  return `insert into public.admin_catalogue_versions
    (dataset_id, version, snapshot_date, sha256, region_count, local_unit_count)
values (${dataset}, ${version}, ${sql(document.snapshotDate)}, ${sql(hash)}, 37, 774);
insert into public.admin_regions
    (dataset_id, version, id, canonical_name, region_type, display_order)
values
    ${regionRows.join(',\n    ')};
insert into public.admin_local_units
    (dataset_id, version, id, parent_region_id, parent_region_type, canonical_name, local_unit_type, display_order)
values
    ${unitRows.join(',\n    ')};`;
}

export function generateVersionSql(document, hash, assetFileName) {
  validate(document);
  check(/^[a-f0-9]{64}$/.test(hash), 'Invalid catalogue SHA-256');
  check(/^ng_admin_v[0-9]+\.json$/.test(assetFileName), 'Invalid catalogue asset filename');
  return `-- GENERATED from app/src/main/assets/catalogue/${assetFileName}.
-- SHA-256 of exact UTF-8 asset bytes (including final newline): ${hash}
-- Retains all earlier snapshots; no Journey/schema/trigger changes.
begin;
${snapshotInserts(document, hash)}
commit;
`;
}

export function generateSql(document, hash) {
  validate(document);
  check(/^[a-f0-9]{64}$/.test(hash), 'Invalid catalogue SHA-256');
  return `-- GENERATED from app/src/main/assets/catalogue/ng_admin_v1.json.
-- SHA-256 of exact UTF-8 asset bytes (including final newline): ${hash}
-- Regenerate with: node tools/generate_nigeria_admin_sql.mjs --output supabase/migrations/20261003000200_nigeria_admin_catalogue.sql
begin;

create table public.admin_catalogue_versions (
    dataset_id text not null,
    version text not null,
    snapshot_date date not null,
    sha256 text not null check (sha256 ~ '^[a-f0-9]{64}$'),
    region_count integer not null check (region_count > 0),
    local_unit_count integer not null check (local_unit_count > 0),
    primary key (dataset_id, version)
);
create table public.admin_regions (
    dataset_id text not null,
    version text not null,
    id text not null,
    canonical_name text not null check (length(btrim(canonical_name)) > 0),
    region_type text not null check (region_type in ('STATE', 'FCT')),
    display_order integer not null check (display_order >= 0),
    primary key (dataset_id, version, id),
    unique (dataset_id, version, id, region_type),
    unique (dataset_id, version, display_order),
    foreign key (dataset_id, version)
        references public.admin_catalogue_versions(dataset_id, version) on delete restrict
);
create table public.admin_local_units (
    dataset_id text not null,
    version text not null,
    id text not null,
    parent_region_id text not null,
    parent_region_type text not null,
    canonical_name text not null check (length(btrim(canonical_name)) > 0),
    local_unit_type text not null check (local_unit_type in ('LGA', 'AREA_COUNCIL')),
    display_order integer not null check (display_order >= 0),
    primary key (dataset_id, version, id),
    unique (dataset_id, version, parent_region_id, display_order),
    check ((parent_region_type = 'STATE' and local_unit_type = 'LGA') or
           (parent_region_type = 'FCT' and local_unit_type = 'AREA_COUNCIL')),
    foreign key (dataset_id, version, parent_region_id, parent_region_type)
        references public.admin_regions(dataset_id, version, id, region_type) on delete restrict
);

-- Reference data is migration-managed; client roles have neither reads nor writes.
alter table public.admin_catalogue_versions enable row level security;
alter table public.admin_regions enable row level security;
alter table public.admin_local_units enable row level security;
revoke all on public.admin_catalogue_versions, public.admin_regions, public.admin_local_units
    from public, anon, authenticated, service_role;

${snapshotInserts(document, hash)}

-- INSERT only: old-client ON CONFLICT UPDATE and historical lifecycle updates
-- continue through the existing destination-preservation/terminal triggers.
create function public.validate_journey_catalogue_destination_insert()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    if new.destination_kind is null then
        return new;
    end if;
    if new.destination_admin_dataset_version is null or not exists (
        select 1 from public.admin_catalogue_versions v
        where v.dataset_id = 'NG_ADMIN' and v.version = new.destination_admin_dataset_version
    ) then
        raise exception 'Unknown or missing destination catalogue version' using errcode = '22023';
    end if;
    if new.destination_country_code is distinct from 'NG' or not exists (
        select 1 from public.admin_regions r
        join public.admin_local_units u
          on u.dataset_id = r.dataset_id and u.version = r.version
         and u.parent_region_id = r.id and u.parent_region_type = r.region_type
        where r.dataset_id = 'NG_ADMIN'
          and r.version = new.destination_admin_dataset_version
          and r.id = new.destination_state_id
          and r.canonical_name = new.destination_state_name
          and u.id = new.destination_lga_id
          and u.canonical_name = new.destination_lga_name
    ) then
        raise exception 'Journey destination does not match catalogue snapshot' using errcode = '22023';
    end if;
    return new;
end;
$$;
create trigger journeys_catalogue_destination_validate_insert
before insert on public.journeys
for each row execute function public.validate_journey_catalogue_destination_insert();
revoke all on function public.validate_journey_catalogue_destination_insert()
    from public, anon, authenticated, service_role;
commit;
`;
}

export function loadCanonical(assetPath = asset) {
  const bytes = readFileSync(assetPath);
  const document = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  validate(document);
  return { bytes, document, hash: createHash('sha256').update(bytes).digest('hex') };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  const [mode, path, versionOutput] = process.argv.slice(2);
  const versionMode = ['--version-output', '--version-check', '--version-validate'].includes(mode);
  if (versionMode) {
    try {
      check(path && (mode === '--version-validate' ? !versionOutput : versionOutput) && process.argv.length <= 5,
        'Usage: --version-output|--version-check <asset> <migration>, or --version-validate <asset>');
      const { document, hash } = loadCanonical(path);
      const filename = path.replaceAll('\\', '/').split('/').at(-1);
      const generated = generateVersionSql(document, hash, filename);
      if (mode === '--version-output') writeFileSync(versionOutput, generated, 'utf8');
      if (mode === '--version-check') check(readFileSync(versionOutput, 'utf8') === generated,
        `Generated SQL differs: ${versionOutput}`);
      console.log(`${document.version}: 37 regions, 774 local units; SHA-256 exact bytes: ${hash}`);
    } catch (error) {
      console.error(`Catalogue validation failed: ${error.message}`);
      process.exitCode = 1;
    }
  } else {
    if (!['--validate', '--output', '--check'].includes(mode) ||
        (mode === '--validate' ? path !== undefined : !path) || process.argv.length > (mode === '--validate' ? 3 : 4)) {
      console.error('Usage: node tools/generate_nigeria_admin_sql.mjs --validate | --output <path> | --check <path>');
      process.exitCode = 2;
    } else {
      try {
        const { document, hash } = loadCanonical();
        if (mode === '--output') writeFileSync(path, generateSql(document, hash), 'utf8');
        if (mode === '--check') check(readFileSync(path, 'utf8') === generateSql(document, hash),
          `Generated SQL differs: ${path}`);
        console.log(`NG_ADMIN ${document.version}: 37 regions (36 STATE, 1 FCT), ` +
          '774 local units (768 LGA, 6 AREA_COUNCIL)');
        console.log(`SHA-256 exact UTF-8 asset bytes: ${hash}`);
        console.log(`Sources: ${document.sources.length}; snapshot: ${document.snapshotDate}`);
      } catch (error) {
        console.error(`Catalogue validation failed: ${error.message}`);
        process.exitCode = 1;
      }
    }
  }
}
