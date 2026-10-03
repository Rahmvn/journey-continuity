import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmdirSync, unlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { generateSql, loadCanonical, validate } from './generate_nigeria_admin_sql.mjs';

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

test('generated migration is deterministic and matches the canonical asset', () => {
  const { document, hash, bytes } = loadCanonical();
  const before = readFileSync(asset);
  const migration = fileURLToPath(new URL('../supabase/migrations/20261003000200_nigeria_admin_catalogue.sql', import.meta.url));
  const generated = generateSql(document, hash);
  assert.equal(hash, 'e5a75f4d7f906ab3bd2d4db806bbbfa1b4c7ac2ff04f6cca3e52dd39e6b24aa4');
  assert.equal(generated, generateSql(document, hash));
  assert.equal(generated, readFileSync(migration, 'utf8'));
  assert.ok(generated.includes(hash));
  assert.equal(document.regions.length, 37);
  assert.equal(document.localUnits.length, 774);
  assert.deepEqual(readFileSync(asset), before);
  assert.deepEqual(bytes, before);
});

test('--check rejects drift from the generated SQL', () => {
  const directory = mkdtempSync(join(tmpdir(), 'alabarin-catalogue-'));
  const target = join(directory, 'migration.sql');
  try {
    const { document, hash } = loadCanonical();
    writeFileSync(target, generateSql(document, hash) + '\n');
    const script = fileURLToPath(new URL('./generate_nigeria_admin_sql.mjs', import.meta.url));
    const result = spawnSync(process.execPath, [script, '--check', target], { encoding: 'utf8' });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /Generated SQL differs/);
  } finally {
    unlinkSync(target);
    rmdirSync(directory);
  }
});
