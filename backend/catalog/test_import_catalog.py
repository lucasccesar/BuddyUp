import subprocess
import tempfile
import time
import unittest
from pathlib import Path
from uuid import UUID

import psycopg
from openpyxl import Workbook

from catalog_format import TABLES
from import_catalog import apply_snapshot, read_snapshot

GAME = UUID("00000000-0000-4000-8000-000000000001")
INACTIVE = UUID("00000000-0000-4000-8000-000000000002")
MODE = UUID("00000000-0000-4000-8000-000000000003")
MISSING = UUID("00000000-0000-4000-8000-000000000004")
OTHER_ORIGIN = UUID("00000000-0000-4000-8000-000000000005")
PLATFORM = UUID("00000000-0000-4000-8000-000000000006")


class CatalogImportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        container = subprocess.check_output([
            "docker", "run", "--rm", "--detach", "--publish", "127.0.0.1::5432",
            "--env", "POSTGRES_USER=catalog_test", "--env", "POSTGRES_PASSWORD=catalog_test",
            "postgres:17-alpine",
        ], text=True).strip()
        cls.addClassCleanup(subprocess.run, ["docker", "stop", container], check=True, capture_output=True)
        binding = subprocess.check_output(["docker", "port", container, "5432/tcp"], text=True).strip()
        cls.dsn = f"postgresql://catalog_test:catalog_test@{binding}/catalog_test?connect_timeout=2"
        for _ in range(30):
            try:
                with psycopg.connect(cls.dsn):
                    return
            except psycopg.OperationalError:
                time.sleep(1)
        raise RuntimeError("Test PostgreSQL did not accept connections")

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.connection = psycopg.connect(self.dsn)
        self.addCleanup(self.connection.close)
        with self.connection.transaction():
            self.connection.execute("drop schema if exists buddyup cascade")
            migrations = Path(__file__).parents[1] / "src/main/resources/db/migration"
            for migration in sorted(migrations.glob("V*.sql")):
                self.connection.execute(migration.read_text(encoding="utf-8"))

    def make_workbook(self, mode_game=GAME, name="Active game", player_rows=False, formula=False):
        book = Workbook()
        book.active.title = "00_LEIA-ME"
        book.active.append(["Ignored"])
        data = {
            "games": [
                {"id": GAME, "name": name, "slug": "active", "is_active": True, "source_catalog": "Origin A"},
                {"id": GAME, "name": "Another source", "slug": "another", "is_active": True, "source_catalog": "Origin B"},
                {"id": OTHER_ORIGIN, "name": "Scoped game", "slug": "scoped", "is_active": True, "source_catalog": "Origin B"},
                {"id": INACTIVE, "name": "Inactive game", "slug": "inactive", "is_active": False, "source_catalog": "Origin A"},
            ],
            "game_modes": [
                {"id": MODE, "game_id": mode_game, "name": "Competitive", "code": "competitive",
                 "uses_roles": False, "is_ranked": True, "is_active": True, "source_catalog": "Origin A"},
                {"id": MISSING, "game_id": INACTIVE, "name": "Hidden mode", "code": "hidden",
                 "uses_roles": False, "is_ranked": False, "is_active": True, "source_catalog": "Origin A"},
            ],
        }
        if player_rows:
            data["user_game_profiles"] = [{"id": GAME}]
        if formula:
            data["games"][0]["cover_url"] = '="https://example.test"'
        mode_link = {"mode_id": MODE}
        game_entry = {"id": GAME, "game_id": GAME, "code": "fixture", "name": "Fixture", "is_active": True}
        rows = {
            "platforms": {"id": PLATFORM, "name": "PC", "slug": "pc", "abbreviation": "PC"},
            "game_platforms": {"game_id": GAME, "platform_id": PLATFORM, "is_enabled": True},
            "rank_sets": game_entry,
            "ranks": {"id": GAME, "rank_set_id": GAME, "code": "silver", "name": "Silver",
                      "is_active": True, "sort_order": 1, "min_numeric_rating": 1.5},
            "mode_party_sizes": {**mode_link, "party_size": 3},
            "game_roles": game_entry,
            "mode_roles": {**mode_link, "role_id": GAME, "min_slots_per_team": 1},
            "game_servers": game_entry,
            "mode_servers": {**mode_link, "server_id": GAME, "is_available": True},
            "mode_crossplay": {**mode_link, "platform_a_id": PLATFORM, "platform_b_id": PLATFORM,
                               "is_compatible": False},
            "mode_platforms": {**mode_link, "platform_id": PLATFORM, "is_available": False},
            "mode_team_sides": {**mode_link, "side_code": "red", "side_size": "3"},
            "mode_eligibility_rules": {
                **mode_link, "id": GAME, "rule_code": "regions", "rule_value": "Same region only",
                "source_basis": "official", "source_url": "https://example.test", "version": 1,
                "effective_from": "2026-10-01", "is_active": True,
            },
            "game_maps": game_entry,
            "mode_maps": {**mode_link, "map_id": GAME, "is_active": True, "affects_rank": True,
                          "source_url": "https://example.test", "reviewed_on": "2026-10-01"},
        }
        data.update({table: [{**row, "source_catalog": "Origin A"}] for table, row in rows.items()})
        for table, definition in TABLES.items():
            sheet = book.create_sheet(table)
            sheet.append(list(definition.columns))
            for row in data.get(table, []):
                sheet.append([str(value) if isinstance(value, UUID) else value
                              for value in (row.get(column) for column in definition.columns)])
        path = Path(self.directory.name) / "snapshot.xlsx"
        book.save(path)
        book.close()
        return path

    def test_loads_scoped_references_and_selects_active_catalog(self):
        snapshot = read_snapshot(self.make_workbook())
        self.assertEqual([], snapshot.errors)
        apply_snapshot(self.connection, snapshot)
        with self.connection.transaction():
            games = self.connection.execute(
                "select * from buddyup.catalog_active_games order by name"
            )
            self.assertNotIn("source_catalog", [column.name for column in games.description])
            self.assertNotIn("igdb_source_url", [column.name for column in games.description])
            active_games = games.fetchall()
            self.assertEqual(["Active game", "Another source", "Scoped game"], [row[1] for row in active_games])
            self.assertEqual(3, len({row[0] for row in active_games}))
            self.assertEqual(
                [("Competitive", "Active game")],
                self.connection.execute("""
                    select modes.name, games.name from buddyup.catalog_active_game_modes modes
                    join buddyup.catalog_active_games games on games.id = modes.game_id
                """).fetchall(),
            )
            self.assertEqual(4, self.connection.execute("select count(*) from buddyup.catalog_games").fetchone()[0])
            self.assertFalse(self.connection.execute("select is_compatible from buddyup.catalog_mode_crossplay").fetchone()[0])

    def test_rejects_cross_game_links_in_postgresql(self):
        apply_snapshot(self.connection, read_snapshot(self.make_workbook()))
        with self.assertRaises(psycopg.errors.ForeignKeyViolation), self.connection.transaction():
            self.connection.execute("update buddyup.catalog_mode_maps set mode_id = %s", (MISSING,))

    def test_reports_broken_reference_and_preserves_previous_snapshot(self):
        apply_snapshot(self.connection, read_snapshot(self.make_workbook()))
        snapshot = read_snapshot(self.make_workbook(mode_game=OTHER_ORIGIN))
        self.assertIn(("game_modes", 2, "game_id"),
                      [(error.sheet, error.row, error.column) for error in snapshot.errors])
        with self.assertRaises(ValueError):
            apply_snapshot(self.connection, snapshot)
        with self.connection.transaction():
            self.assertEqual(2, self.connection.execute("select count(*) from buddyup.catalog_game_modes").fetchone()[0])

    def test_reimport_preserves_ids_and_does_not_duplicate_rows(self):
        first = read_snapshot(self.make_workbook())
        apply_snapshot(self.connection, first)
        apply_snapshot(self.connection, first)
        with self.connection.transaction():
            first_id = self.connection.execute(
                "select id from buddyup.catalog_active_games where slug = 'active'"
            ).fetchone()[0]
        apply_snapshot(self.connection, read_snapshot(self.make_workbook(name="Updated game")))
        with self.connection.transaction():
            self.assertEqual(
                [(first_id, "Updated game")],
                self.connection.execute(
                    "select id, name from buddyup.catalog_active_games where slug = 'active'"
                ).fetchall(),
            )
            self.assertEqual(4, self.connection.execute("select count(*) from buddyup.catalog_games").fetchone()[0])

    def test_reports_player_data_in_catalog_snapshot(self):
        snapshot = read_snapshot(self.make_workbook(player_rows=True))
        self.assertEqual([("user_game_profiles", 2)],
                         [(error.sheet, error.row) for error in snapshot.errors])

    def test_reports_formula_without_cached_value(self):
        snapshot = read_snapshot(self.make_workbook(formula=True))
        self.assertIn(("games", 2, "cover_url"),
                      [(error.sheet, error.row, error.column) for error in snapshot.errors])


if __name__ == "__main__":
    unittest.main()
