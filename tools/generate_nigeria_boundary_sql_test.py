"""Determinism and source-of-truth checks for the boundary SQL generator."""
import hashlib
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_nigeria_boundary_sql import MIGRATION, SNAPSHOT, generate, literal  # noqa: E402


class BoundarySqlGeneratorTest(unittest.TestCase):
    def test_committed_migration_is_exact_generator_output(self):
        before = {name: hashlib.sha256((SNAPSHOT / name).read_bytes()).hexdigest()
                  for name in ("grid3_lga.geojson", "grid3_state.geojson", "mapping.json")}
        generated = generate().encode("utf-8")
        self.assertEqual(MIGRATION.read_bytes(), generated)
        self.assertEqual(generated, generate().encode("utf-8"))
        self.assertEqual(before, {name: hashlib.sha256((SNAPSHOT / name).read_bytes()).hexdigest()
                                  for name in before})

    def test_expected_import_identity(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("784cc510bbbdbca2848bb02fb1a4a1a681885d464b9a09f29529ddb3a10f10a3", sql)
        self.assertIn("92700100062315eefd485629567ee17aa5c77b5fc42a41520b21fb21a85f329b", sql)
        self.assertEqual(sql.count("gis.ST_Multi(gis.ST_GeomFromGeoJSON("), 811)
        self.assertIn("alabarin-2026-10-03-v1", sql)
        self.assertNotIn("ST_MakeValid", sql.replace("-- No geometry simplification, buffer, assignment, or ST_MakeValid.", ""))

    def test_sql_literal_escapes_untrusted_source_text(self):
        self.assertEqual(literal("Qua'an Pan"), "'Qua''an Pan'")
        self.assertEqual(literal("Îles-Ètè"), "'Îles-Ètè'")


if __name__ == "__main__":
    unittest.main()
