import unittest

from datagen import gen_dataset as gen
from datagen import generate_ddl as ddl


class PublicRelationalGeneratorTest(unittest.TestCase):
    def test_p3_contract_uses_sparse_monotone_key_and_exact_diff(self):
        p = gen.PROFILES["p3"]
        self.assertEqual("jumpnum", p["key_mode"])
        self.assertEqual(["KEY_01", "KEY_02", "KEY_03"], p["pk_cols"])
        self.assertEqual(154_356_834, p["rows"])
        self.assertEqual((11, 7, 0), (p["source_only"], p["target_only"], p["modified"]))
        values = [p["jump_spacing"] * i +
                  ((i * gen.JUMP_MULT + p["jump_seed"] * gen.JUMP_SEED_STRIDE)
                   % gen.JUMP_MOD_PRIME) % p["jump_spacing"]
                  for i in range(10000)]
        self.assertEqual(len(values), len(set(values)))
        self.assertTrue(all(a < b for a, b in zip(values, values[1:])))

    def test_p3_smoke_generator_keeps_target_only_inside_dirty_range(self):
        p = dict(gen.PROFILES["p3"])
        p["oracle_table"] = "ARTIFACT_REPLAY.PROFILE_P3_SMOKE"
        p["mysql_table"] = "artifact_replay.profile_p3_smoke"
        sql = gen.emit_target("mysql", p, 256)
        self.assertIn("SELECT 100 AS i", sql)
        self.assertNotIn("SELECT 256 + s.i AS i", sql)
        self.assertIn("WHERE NOT", sql)

    def test_p3_row_contract_has_25_insert_values_and_22_value_types(self):
        p = gen.PROFILES["p3"]
        oracle = gen.row_values_of("oracle", p)
        mysql = gen.row_values_of("mysql", p)
        self.assertEqual(25, len(oracle))
        self.assertEqual(25, len(mysql))
        self.assertEqual(22, len(gen.VALUE_TYPES_P3))

    def test_contract_has_three_keys_and_31_values(self):
        self.assertEqual(34, len(gen.INSERT_COLS))
        self.assertEqual(31, len(gen.VALUE_COLS))
        self.assertEqual(31, len(gen.VALUE_TYPES))
        self.assertEqual(13, gen.VALUE_TYPES.count("decimal4"))
        self.assertEqual(2, gen.VALUE_TYPES.count("int"))
        self.assertEqual(2, gen.VALUE_TYPES.count("datetime_sec"))
        self.assertEqual(14, gen.VALUE_TYPES.count("string"))

    def test_both_dialects_populate_every_common_column(self):
        profile = gen.PROFILES["p2"]
        oracle = gen.row_columns("oracle", profile)
        mysql = gen.row_columns("mysql", profile)
        self.assertEqual(len(gen.INSERT_COLS), len(oracle))
        self.assertEqual(len(gen.INSERT_COLS), len(mysql))
        self.assertIn("DATE '2026-08-01'", oracle[gen.INSERT_COLS.index("VALUE_25")])
        self.assertIn("INTERVAL", mysql[gen.INSERT_COLS.index("VALUE_25")])
        for column in ("VALUE_02", "VALUE_11", "VALUE_13",
                       "VALUE_19", "VALUE_29", "VALUE_30"):
            index = gen.INSERT_COLS.index(column)
            self.assertIn("NULL", oracle[index], column)
            self.assertIn("NULL", mysql[index], column)

    def test_small_smoke_truth_is_exact(self):
        profile = dict(gen.PROFILES["p2"], modified=4, source_only=2, target_only=1)
        modified, source_only, mpb, spb, dirty_blocks = gen.dirty_predicate(profile, 256)
        self.assertIn("< 4", modified)
        self.assertIn(">= 4", source_only)
        self.assertEqual((4, 2, 1), (mpb, spb, dirty_blocks))
        truth = gen.emit_groundtruth(profile, 256)
        self.assertIn("modified=4  source_only=2  target_only=1", truth)
        self.assertIn("d_row=7  d_ms=11", truth)

    def test_target_modification_uses_decimal4_base(self):
        profile = dict(gen.PROFILES["p2"], modified=4, source_only=2, target_only=0)
        sql = gen.emit_target("mysql", profile, 256)
        self.assertIn("MOD(i, 100000) / 10000", sql)
        self.assertNotIn("MOD(i,1000)/100", sql)
        self.assertEqual(34, sql.split("INSERT INTO", 1)[1].split(")", 1)[0].count("`") // 2)

    def test_oracle_stage_load_uses_direct_path_after_truncate(self):
        sql = gen.emit_source("oracle", gen.PROFILES["p2"], 256)
        self.assertIn("TRUNCATE TABLE ARTIFACT_REPLAY.PROFILE_P2;", sql)
        self.assertIn("INSERT /*+ APPEND */ INTO ARTIFACT_REPLAY.PROFILE_P2", sql)

    def test_p1_worst_case_adds_disjoint_oracle_only_rows(self):
        profile = dict(gen.PROFILES["p1"], modified=0, source_only=0,
                       target_only=0, single_sided_dirty_every=10)
        oracle = gen.emit_source("oracle", profile, 256)
        mysql = gen.emit_target("mysql", profile, 256)
        truth = gen.emit_groundtruth(profile, 256)
        self.assertIn("key_collision_candidates", oracle)
        self.assertIn("Oracle-only dirty", oracle)
        self.assertEqual(26, gen.extra_source_only_count(profile, 256))
        self.assertIn("Oracle source rows = 256 + 26", truth)
        self.assertIn("source_only total (incl. worst-case extra rows)=26", truth)
        self.assertNotIn("Oracle-only dirty", mysql)
        rollback = gen.emit_extra_source_only_delete("oracle", profile, 256)
        self.assertIn('DELETE FROM ARTIFACT_REPLAY.PROFILE_P1', rollback)
        self.assertIn('WHERE "KEY_01" IN', rollback)
        append = gen.emit_extra_source_only_append("oracle", profile, 256)
        self.assertIn("INSERT /*+ APPEND */ INTO ARTIFACT_REPLAY.PROFILE_P1", append)
        self.assertNotIn("TRUNCATE TABLE", append)

    def test_p1_extra_is_named_profile_with_10k_default(self):
        profile = gen.PROFILES["p1-extra"]
        self.assertEqual(10000, profile["single_sided_dirty_every"])
        self.assertEqual(1, gen.extra_source_only_count(profile, 256))
        self.assertEqual(gen.PROFILES["p1"]["pk_cols"], profile["pk_cols"])

    def test_worst_case_requires_p1_jumpnum(self):
        with self.assertRaises(ValueError):
            gen.emit_source("oracle", dict(gen.PROFILES["p2"],
                                            single_sided_dirty_every=10), 256)

    def test_public_ddl_is_generic_and_matches_all_profiles(self):
        oracle = ddl.render("oracle")
        mysql = ddl.render("mysql")
        for text in (oracle, mysql):
            self.assertIn("PROFILE_P1" if text is oracle else "profile_p1", text)
            self.assertIn("KEY_01" if text is oracle else "key_01", text)
            self.assertIn("VALUE_43" if text is oracle else "value_43", text)
        self.assertNotIn("VALUE_44", oracle)
        self.assertNotIn("value_44", mysql)


if __name__ == "__main__":
    unittest.main()
