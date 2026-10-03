import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { validate } from './generate_nigeria_admin_sql.mjs';

const asset = fileURLToPath(new URL('../app/src/main/assets/catalogue/ng_admin_v1.json', import.meta.url));

test('canonical sources are all referenced and an unused source is rejected', () => {
  const document = JSON.parse(readFileSync(asset, 'utf8'));
  assert.doesNotThrow(() => validate(document));
  const referenced = new Set([
    document.regionIndexSourceId,
    ...document.regions.map(region => region.sourceId),
    ...document.localUnits.flatMap(unit => unit.sourceId == null ? [] : [unit.sourceId]),
  ]);
  assert.equal(document.sources.length, 46);
  assert.deepEqual(new Set(document.sources.map(source => source.id)), referenced);
  assert.ok(!referenced.has('source-18'));
  const unused = { ...document.sources[0], id: 'source-18' };
  assert.throws(() => validate({ ...document, sources: [...document.sources, unused] }),
    /Unused catalogue source: source-18/);
});
