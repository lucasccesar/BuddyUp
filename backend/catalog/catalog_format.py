from dataclasses import dataclass


@dataclass(frozen=True)
class Table:
    columns: dict[str, str]
    key: tuple[str, ...]
    references: tuple[tuple[str, str], ...]


def table(fields: str, key: str, *references: str) -> Table:
    columns = dict(field.split(":") for field in fields.split())
    return Table(
        {"source_catalog": "text", **columns},
        ("source_catalog", *key.split()),
        tuple(tuple(reference.split(":")) for reference in references),
    )


# Keep the persisted definitions aligned with V4__create_catalog.sql.
TABLES = {
    "games": table(
        "id:uuid igdb_id:integer? name:text slug:text cover_url:text? summary:text? is_active:boolean "
        "last_synced_at:timestamp? igdb_source_url:text?",
        "id",
    ),
    "platforms": table(
        "id:uuid igdb_id:integer? name:text slug:text abbreviation:text",
        "id",
    ),
    "game_platforms": table(
        "game_id:uuid platform_id:uuid is_enabled:boolean",
        "game_id platform_id", "games:game_id", "platforms:platform_id",
    ),
    "rank_sets": table(
        "id:uuid game_id:uuid name:text code:text is_active:boolean",
        "id", "games:game_id",
    ),
    "ranks": table(
        "id:uuid rank_set_id:uuid code:text name:text division:text? sort_order:integer icon_url:text? "
        "is_active:boolean min_numeric_rating:decimal?",
        "id", "rank_sets:rank_set_id",
    ),
    "game_modes": table(
        "id:uuid game_id:uuid rank_set_id:uuid? name:text code:text team_size:integer? team_count:integer? "
        "uses_roles:boolean is_ranked:boolean is_active:boolean queue_availability_policy:text? "
        "queue_policy_source_url:text? mode_region_policy:text?",
        "id", "games:game_id", "rank_sets:rank_set_id",
    ),
    "mode_party_sizes": table(
        "mode_id:uuid party_size:integer size_basis:text?",
        "mode_id party_size", "game_modes:mode_id",
    ),
    "game_roles": table(
        "id:uuid game_id:uuid code:text name:text description:text? icon_url:text? is_active:boolean",
        "id", "games:game_id",
    ),
    "mode_roles": table(
        "mode_id:uuid role_id:uuid min_slots_per_team:integer? max_slots_per_team:integer?",
        "mode_id role_id", "game_modes:mode_id", "game_roles:role_id",
    ),
    "game_servers": table(
        "id:uuid game_id:uuid code:text name:text region_code:text? is_active:boolean",
        "id", "games:game_id",
    ),
    "mode_servers": table(
        "mode_id:uuid server_id:uuid is_available:boolean",
        "mode_id server_id", "game_modes:mode_id", "game_servers:server_id",
    ),
    "mode_crossplay": table(
        "mode_id:uuid platform_a_id:uuid platform_b_id:uuid is_compatible:boolean notes:text?",
        "mode_id platform_a_id platform_b_id", "game_modes:mode_id", "platforms:platform_a_id", "platforms:platform_b_id",
    ),
    "mode_platforms": table(
        "mode_id:uuid platform_id:uuid is_available:boolean evidence_level:text? source_url:text? "
        "support_decision:text? game_platform_source_url:text?",
        "mode_id platform_id", "game_modes:mode_id", "platforms:platform_id",
    ),
    "mode_team_sides": table(
        "mode_id:uuid side_code:text side_size:integer?",
        "mode_id side_code", "game_modes:mode_id",
    ),
    "mode_eligibility_rules": table(
        "id:uuid mode_id:uuid rule_code:text rule_value:text source_basis:text source_url:text "
        "version:text effective_from:date effective_to:date? validation_input:text? is_active:boolean",
        "id", "game_modes:mode_id",
    ),
    "game_maps": table(
        "id:uuid game_id:uuid code:text name:text is_active:boolean",
        "id", "games:game_id",
    ),
    "mode_maps": table(
        "mode_id:uuid map_id:uuid affects_rank:boolean is_active:boolean source_url:text reviewed_on:date",
        "mode_id map_id", "game_modes:mode_id", "game_maps:map_id",
    ),
    "user_game_profiles": table(
        "id:uuid user_id:uuid game_id:uuid platform_id:uuid game_identifier:text stats_url:text? "
        "created_at:timestamp updated_at:timestamp input_pool:text? matchmaking_region:text? "
        "client_variant:text?",
        "id",
    ),
    "user_game_roles": table(
        "user_game_profile_id:uuid role_id:uuid",
        "user_game_profile_id role_id",
    ),
    "user_mode_metrics": table(
        "id:uuid user_game_profile_id:uuid mode_id:uuid platform_id:uuid context_code:text "
        "metric_type:text rank_id:uuid? numeric_value:decimal? updated_at:timestamp map_id:uuid? "
        "rank_status:text?",
        "id",
    ),
}

PLAYER_SHEETS = frozenset({"user_game_profiles", "user_game_roles", "user_mode_metrics"})
CATALOG_TABLES = {name: definition for name, definition in TABLES.items() if name not in PLAYER_SHEETS}
MODE_GAME_TABLES = frozenset({"mode_roles", "mode_servers", "mode_crossplay", "mode_platforms", "mode_maps"})
