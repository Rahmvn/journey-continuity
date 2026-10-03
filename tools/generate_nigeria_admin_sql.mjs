#!/usr/bin/env node
// Slice 2A validates the canonical asset. Slice 2B can generate SQL from this same file.
// Identity: SHA-256 of the exact committed UTF-8 JSON bytes, including whitespace/newline.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
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

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  if (process.argv.length !== 3 || process.argv[2] !== '--validate') {
    console.error('Usage: node tools/generate_nigeria_admin_sql.mjs --validate');
    process.exitCode = 2;
  } else {
    try {
      const bytes = readFileSync(asset);
      const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
      const document = JSON.parse(text);
      validate(document);
      const hash = createHash('sha256').update(bytes).digest('hex');
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
