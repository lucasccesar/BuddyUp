import argparse
import hashlib
import json
import os
from dataclasses import asdict, dataclass
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation
from io import BytesIO
from pathlib import Path
from uuid import UUID
from zipfile import BadZipFile

import psycopg
from openpyxl import load_workbook
from openpyxl.utils.exceptions import InvalidFileException
from psycopg import sql

from catalog_format import CATALOG_TABLES, MODE_GAME_TABLES, PLAYER_SHEETS, TABLES


@dataclass(frozen=True)
class RowError:
    sheet: str
    row: int
    column: str | None
    reason: str


@dataclass(frozen=True)
class CatalogRow:
    line: int
    fields: dict

    def key(self, columns):
        return tuple(self.fields[column] for column in columns)


@dataclass
class Snapshot:
    sha256: str
    tables: dict[str, dict[tuple, CatalogRow]]
    errors: list[RowError]

    def report(self):
        return {
            "sha256": self.sha256,
            "validated_records": {name: len(rows) for name, rows in self.tables.items()},
            "active_games": sum(row.fields["is_active"] for row in self.tables["games"].values()),
            "errors": [asdict(error) for error in self.errors],
        }


def parse_cell(value, kind, column):
    if value is None or (isinstance(value, str) and not value.strip()):
        if kind.endswith("?"):
            return None
        raise ValueError("valor obrigatório")
    kind = kind.removesuffix("?")
    if kind == "boolean":
        if isinstance(value, bool):
            return value
        raise ValueError("use uma célula booleana")
    if kind in ("integer", "decimal"):
        try:
            number = Decimal(str(value))
        except InvalidOperation:
            raise ValueError("número inválido") from None
        if not number.is_finite():
            raise ValueError("número inválido")
        if kind == "decimal":
            if number.adjusted() >= 131072 or number.as_tuple().exponent < -16383:
                raise ValueError("decimal fora do intervalo do PostgreSQL")
            return number
        if number != number.to_integral_value() or not -(2 ** 31) <= number < 2 ** 31:
            raise ValueError("inteiro fora do formato ou intervalo")
        return int(number)
    if kind == "uuid":
        try:
            return UUID(str(value).strip())
        except ValueError:
            raise ValueError("UUID inválido") from None
    if kind in ("date", "timestamp"):
        try:
            if kind == "date":
                return value.date() if isinstance(value, datetime) else date.fromisoformat(str(value).strip())
            timestamp = datetime.fromisoformat(str(value).strip())
            return timestamp if timestamp.tzinfo is not None else timestamp.replace(tzinfo=timezone.utc)
        except ValueError:
            raise ValueError("data inválida") from None
    if column in ("division", "version") and isinstance(value, (float, int)) and not isinstance(value, bool):
        return format(Decimal(str(value)).normalize(), "f")
    if not isinstance(value, str) or "\x00" in value:
        raise ValueError("texto inválido")
    return value.strip()


def read_snapshot(path):
    # Read each source file once so the digest identifies the exact validated bytes.
    content = path.read_bytes()
    snapshot = Snapshot(hashlib.sha256(content).hexdigest(), {table: {} for table in TABLES}, [])
    workbook = load_workbook(BytesIO(content), read_only=True)
    try:
        for table, definition in TABLES.items():
            if table not in workbook:
                snapshot.errors.append(RowError(table, 1, None, "aba obrigatória ausente"))
                continue
            records = enumerate(workbook[table].iter_rows(), start=1)
            _, header_cells = next(records, (1, ()))
            header = [cell.value for cell in header_cells]
            headers = [value for value in header if value is not None]
            if len(headers) != len(definition.columns) or set(headers) != set(definition.columns):
                snapshot.errors.append(RowError(table, 1, None, "colunas ausentes, duplicadas ou desconhecidas"))
                continue
            for line, cells in records:
                if not any(cell.value is not None for cell in cells):
                    continue
                if table in PLAYER_SHEETS:
                    snapshot.errors.append(RowError(table, line, None, "dados de jogador estão fora da carga de catálogo"))
                    continue
                if any(cell.value is not None and (index >= len(header) or header[index] is None)
                       for index, cell in enumerate(cells)):
                    snapshot.errors.append(RowError(table, line, None, "célula preenchida sem cabeçalho"))
                    continue
                fields = {}
                for index, column in enumerate(header):
                    if column is None:
                        continue
                    try:
                        cell = cells[index] if index < len(cells) else None
                        if cell is not None and cell.data_type in ("f", "e"):
                            raise ValueError("exporte o valor da célula sem fórmula ou erro")
                        fields[column] = parse_cell(cell.value if cell is not None else None,
                                                    definition.columns[column], column)
                    except ValueError as error:
                        snapshot.errors.append(RowError(table, line, column, str(error)))
                if len(fields) != len(definition.columns):
                    continue
                row = CatalogRow(line, fields)
                key = row.key(definition.key)
                if key in snapshot.tables[table]:
                    snapshot.errors.append(RowError(table, line, None, "chave repetida na mesma origem"))
                else:
                    snapshot.tables[table][key] = row
    finally:
        workbook.close()
    validate_references(snapshot)
    if not snapshot.errors and not any(row.fields["is_active"] for row in snapshot.tables["games"].values()):
        snapshot.errors.append(RowError("games", 1, "is_active", "o catálogo inicial precisa de um jogo ativo"))
    return snapshot


def validate_references(snapshot):
    for table, definition in CATALOG_TABLES.items():
        for row in snapshot.tables[table].values():
            fields = row.fields
            source = fields["source_catalog"]
            parents = {}
            for parent_table, column in definition.references:
                if fields[column] is None:
                    continue
                parent = snapshot.tables[parent_table].get((source, fields[column]))
                if parent is None:
                    snapshot.errors.append(RowError(table, row.line, column, "referência ausente na mesma origem"))
                else:
                    parents[column] = parent.fields
            mode = parents.get("mode_id", {})
            game_id = fields.get("game_id", mode.get("game_id"))
            if table in MODE_GAME_TABLES:
                fields["game_id"] = game_id
            for column in ("rank_set_id", "role_id", "server_id", "map_id"):
                parent = parents.get(column, {})
                if game_id is not None and parent.get("game_id", game_id) != game_id:
                    snapshot.errors.append(RowError(table, row.line, column, "vínculo com outro jogo"))
            if mode:
                for column in ("platform_id", "platform_a_id", "platform_b_id"):
                    if column in parents and (source, game_id, fields[column]) not in snapshot.tables["game_platforms"]:
                        snapshot.errors.append(RowError(table, row.line, column, "plataforma sem vínculo com o jogo"))
            if table == "mode_roles":
                minimum, maximum = fields["min_slots_per_team"], fields["max_slots_per_team"]
                if minimum is not None and maximum is not None and minimum > maximum:
                    snapshot.errors.append(RowError(table, row.line, "max_slots_per_team", "menor que o mínimo"))
            if table == "mode_eligibility_rules":
                end = fields["effective_to"]
                if end is not None and end < fields["effective_from"]:
                    snapshot.errors.append(RowError(table, row.line, "effective_to", "anterior ao início"))


def apply_snapshot(connection, snapshot):
    if snapshot.errors:
        raise ValueError("O relatório contém erros. Nenhum registro foi alterado.")
    with connection.transaction(), connection.cursor() as cursor:
        cursor.execute("select pg_advisory_xact_lock(hashtext('buddyup:initial-catalog')::bigint)")
        for table, definition in CATALOG_TABLES.items():
            stage = sql.Identifier("stage_" + table)
            target = sql.Identifier("buddyup", "catalog_" + table)
            stored_columns = tuple(definition.columns)
            if table in MODE_GAME_TABLES:
                stored_columns += ("game_id",)
            columns = sql.SQL(", ").join(map(sql.Identifier, stored_columns))
            cursor.execute(sql.SQL("create temporary table {} (like {}) on commit drop").format(stage, target))
            with cursor.copy(sql.SQL("copy {} ({}) from stdin").format(stage, columns)) as copier:
                for row in snapshot.tables[table].values():
                    copier.write_row(row.key(stored_columns))
            assignments = sql.SQL(", ").join(
                sql.SQL("{0} = excluded.{0}").format(sql.Identifier(column))
                for column in stored_columns if column not in definition.key
            )
            keys = sql.SQL(", ").join(map(sql.Identifier, definition.key))
            cursor.execute(sql.SQL("""
                insert into {target} as current ({columns}) select {columns} from {stage}
                on conflict ({keys}) do update set {assignments}
                where current is distinct from excluded
            """).format(target=target, columns=columns, stage=stage, keys=keys, assignments=assignments))
        for table, definition in reversed(CATALOG_TABLES.items()):
            match = sql.SQL(" and ").join(
                sql.SQL("staged.{0} = current.{0}").format(sql.Identifier(column)) for column in definition.key
            )
            cursor.execute(sql.SQL("""
                delete from {} as current
                where not exists (select from {} as staged where {})
            """).format(sql.Identifier("buddyup", "catalog_" + table), sql.Identifier("stage_" + table), match))
        cursor.execute("""
            insert into buddyup.catalog_initial_snapshot (sha256, imported_at)
            values (%s, now()) on conflict (singleton) do update
            set sha256 = excluded.sha256, imported_at = excluded.imported_at
            where catalog_initial_snapshot.sha256 <> excluded.sha256
        """, (snapshot.sha256,))


def main():
    parser = argparse.ArgumentParser(description="Valida e importa uma planilha autorizada do catálogo BuddyUp.")
    parser.add_argument("workbook", type=Path, help="snapshot XLSX exportado do catálogo unificado")
    parser.add_argument("--report", type=Path, help="arquivo JSON para o relatório de validação")
    parser.add_argument("--apply", action="store_true", help="grava o snapshot no PostgreSQL")
    args = parser.parse_args()
    if args.report and args.report.resolve() == args.workbook.resolve():
        parser.error("o arquivo de relatório não pode ser a planilha de origem")
    try:
        snapshot = read_snapshot(args.workbook)
        report = json.dumps(snapshot.report(), ensure_ascii=False, indent=2)
        if args.report:
            args.report.write_text(report + "\n", encoding="utf-8")
        print(report)
    except (OSError, BadZipFile, InvalidFileException) as error:
        parser.exit(2, f"Falha ao ler a planilha ou escrever o relatório: {error}\n")
    if snapshot.errors:
        return 1
    if args.apply:
        dsn = os.environ.get("BUDDYUP_CATALOG_DSN")
        if not dsn:
            parser.error("defina BUDDYUP_CATALOG_DSN para aplicar a carga")
        try:
            with psycopg.connect(dsn) as connection:
                apply_snapshot(connection, snapshot)
        except psycopg.Error as error:
            parser.exit(1, f"Carga revertida pelo PostgreSQL ({error.sqlstate or error.__class__.__name__}).\n")
        print("Carga aplicada.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
