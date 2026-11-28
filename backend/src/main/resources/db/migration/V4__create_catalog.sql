CREATE TABLE buddyup.catalog_games (
    source_catalog text NOT NULL CHECK (length(btrim(source_catalog)) > 0),
    id uuid NOT NULL,
    igdb_id integer,
    name text NOT NULL,
    slug text NOT NULL,
    cover_url text,
    summary text,
    is_active boolean NOT NULL,
    last_synced_at timestamptz,
    igdb_source_url text,
    PRIMARY KEY (source_catalog, id)
);

CREATE TABLE buddyup.catalog_platforms (
    source_catalog text NOT NULL CHECK (length(btrim(source_catalog)) > 0),
    id uuid NOT NULL,
    igdb_id integer,
    name text NOT NULL,
    slug text NOT NULL,
    abbreviation text NOT NULL,
    PRIMARY KEY (source_catalog, id)
);

CREATE TABLE buddyup.catalog_game_platforms (
    source_catalog text NOT NULL CHECK (length(btrim(source_catalog)) > 0),
    game_id uuid NOT NULL,
    platform_id uuid NOT NULL,
    is_enabled boolean NOT NULL,
    PRIMARY KEY (source_catalog, game_id, platform_id),
    FOREIGN KEY (source_catalog, game_id) REFERENCES buddyup.catalog_games (source_catalog, id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (source_catalog, platform_id) REFERENCES buddyup.catalog_platforms (source_catalog, id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE buddyup.catalog_rank_sets (
    source_catalog text NOT NULL CHECK (length(btrim(source_catalog)) > 0),
    id uuid NOT NULL,
    game_id uuid NOT NULL,
    name text NOT NULL,
    code text NOT NULL,
    is_active boolean NOT NULL,
    PRIMARY KEY (source_catalog, id),
    UNIQUE (source_catalog, game_id, id),
    FOREIGN KEY (source_catalog, game_id) REFERENCES buddyup.catalog_games (source_catalog, id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE buddyup.catalog_ranks (
    source_catalog text NOT NULL CHECK (length(btrim(source_catalog)) > 0),
    id uuid NOT NULL,
    rank_set_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    division text,
    sort_order integer NOT NULL,
    icon_url text,
    is_active boolean NOT NULL,
    min_numeric_rating numeric,
    PRIMARY KEY (source_catalog, id),
    FOREIGN KEY (source_catalog, rank_set_id) REFERENCES buddyup.catalog_rank_sets (source_catalog, id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE buddyup.catalog_game_modes (
    source_catalog text NOT NULL CHECK (length(btrim(source_catalog)) > 0),
    id uuid NOT NULL,
    game_id uuid NOT NULL,
    rank_set_id uuid,
    name text NOT NULL,
    code text NOT NULL,
    team_size integer,
    team_count integer,
    uses_roles boolean NOT NULL,
    is_ranked boolean NOT NULL,
    is_active boolean NOT NULL,
    queue_availability_policy text,
    queue_policy_source_url text,
    mode_region_policy text,
    PRIMARY KEY (source_catalog, id),
    UNIQUE (source_catalog, game_id, id),
    FOREIGN KEY (source_catalog, game_id) REFERENCES buddyup.catalog_games (source_catalog, id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (source_catalog, game_id, rank_set_id)
        REFERENCES buddyup.catalog_rank_sets (source_catalog, game_id, id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE buddyup.catalog_mode_party_sizes (
    source_catalog text NOT NULL CHECK (length(btrim(source_catalog)) > 0),
    mode_id uuid NOT NULL,
    party_size integer NOT NULL,
    size_basis text,
    PRIMARY KEY (source_catalog, mode_id, party_size),
    FOREIGN KEY (source_catalog, mode_id) REFERENCES buddyup.catalog_game_modes (source_catalog, id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE buddyup.catalog_game_roles (
    source_catalog text NOT NULL CHECK (length(btrim(source_catalog)) > 0),
    id uuid NOT NULL,
    game_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    description text,
    icon_url text,
    is_active boolean NOT NULL,
    PRIMARY KEY (source_catalog, id),
    UNIQUE (source_catalog, game_id, id),
    FOREIGN KEY (source_catalog, game_id) REFERENCES buddyup.catalog_games (source_catalog, id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE buddyup.catalog_mode_roles (
    source_catalog text NOT NULL CHECK (length(btrim(source_catalog)) > 0),
    mode_id uuid NOT NULL,
    role_id uuid NOT NULL,
    min_slots_per_team integer,
    max_slots_per_team integer,
    game_id uuid NOT NULL,
    PRIMARY KEY (source_catalog, mode_id, role_id),
    FOREIGN KEY (source_catalog, game_id, mode_id)
        REFERENCES buddyup.catalog_game_modes (source_catalog, game_id, id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (source_catalog, game_id, role_id)
        REFERENCES buddyup.catalog_game_roles (source_catalog, game_id, id) DEFERRABLE INITIALLY DEFERRED,
    CHECK (min_slots_per_team <= max_slots_per_team)
);

CREATE TABLE buddyup.catalog_game_servers (
    source_catalog text NOT NULL CHECK (length(btrim(source_catalog)) > 0),
    id uuid NOT NULL,
    game_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    region_code text,
    is_active boolean NOT NULL,
    PRIMARY KEY (source_catalog, id),
    UNIQUE (source_catalog, game_id, id),
    FOREIGN KEY (source_catalog, game_id) REFERENCES buddyup.catalog_games (source_catalog, id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE buddyup.catalog_mode_servers (
    source_catalog text NOT NULL CHECK (length(btrim(source_catalog)) > 0),
    mode_id uuid NOT NULL,
    server_id uuid NOT NULL,
    is_available boolean NOT NULL,
    game_id uuid NOT NULL,
    PRIMARY KEY (source_catalog, mode_id, server_id),
    FOREIGN KEY (source_catalog, game_id, mode_id)
        REFERENCES buddyup.catalog_game_modes (source_catalog, game_id, id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (source_catalog, game_id, server_id)
        REFERENCES buddyup.catalog_game_servers (source_catalog, game_id, id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE buddyup.catalog_mode_crossplay (
    source_catalog text NOT NULL CHECK (length(btrim(source_catalog)) > 0),
    mode_id uuid NOT NULL,
    platform_a_id uuid NOT NULL,
    platform_b_id uuid NOT NULL,
    is_compatible boolean NOT NULL,
    notes text,
    game_id uuid NOT NULL,
    PRIMARY KEY (source_catalog, mode_id, platform_a_id, platform_b_id),
    FOREIGN KEY (source_catalog, game_id, mode_id)
        REFERENCES buddyup.catalog_game_modes (source_catalog, game_id, id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (source_catalog, game_id, platform_a_id)
        REFERENCES buddyup.catalog_game_platforms (source_catalog, game_id, platform_id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (source_catalog, game_id, platform_b_id)
        REFERENCES buddyup.catalog_game_platforms (source_catalog, game_id, platform_id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE buddyup.catalog_mode_platforms (
    source_catalog text NOT NULL CHECK (length(btrim(source_catalog)) > 0),
    mode_id uuid NOT NULL,
    platform_id uuid NOT NULL,
    is_available boolean NOT NULL,
    evidence_level text,
    source_url text,
    support_decision text,
    game_platform_source_url text,
    game_id uuid NOT NULL,
    PRIMARY KEY (source_catalog, mode_id, platform_id),
    FOREIGN KEY (source_catalog, game_id, mode_id)
        REFERENCES buddyup.catalog_game_modes (source_catalog, game_id, id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (source_catalog, game_id, platform_id)
        REFERENCES buddyup.catalog_game_platforms (source_catalog, game_id, platform_id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE buddyup.catalog_mode_team_sides (
    source_catalog text NOT NULL CHECK (length(btrim(source_catalog)) > 0),
    mode_id uuid NOT NULL,
    side_code text NOT NULL,
    side_size integer,
    PRIMARY KEY (source_catalog, mode_id, side_code),
    FOREIGN KEY (source_catalog, mode_id) REFERENCES buddyup.catalog_game_modes (source_catalog, id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE buddyup.catalog_mode_eligibility_rules (
    source_catalog text NOT NULL CHECK (length(btrim(source_catalog)) > 0),
    id uuid NOT NULL,
    mode_id uuid NOT NULL,
    rule_code text NOT NULL,
    rule_value text NOT NULL,
    source_basis text NOT NULL,
    source_url text NOT NULL,
    version text NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    validation_input text,
    is_active boolean NOT NULL,
    PRIMARY KEY (source_catalog, id),
    FOREIGN KEY (source_catalog, mode_id) REFERENCES buddyup.catalog_game_modes (source_catalog, id) DEFERRABLE INITIALLY DEFERRED,
    CHECK (effective_to >= effective_from)
);

CREATE TABLE buddyup.catalog_game_maps (
    source_catalog text NOT NULL CHECK (length(btrim(source_catalog)) > 0),
    id uuid NOT NULL,
    game_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    is_active boolean NOT NULL,
    PRIMARY KEY (source_catalog, id),
    UNIQUE (source_catalog, game_id, id),
    FOREIGN KEY (source_catalog, game_id) REFERENCES buddyup.catalog_games (source_catalog, id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE buddyup.catalog_mode_maps (
    source_catalog text NOT NULL CHECK (length(btrim(source_catalog)) > 0),
    mode_id uuid NOT NULL,
    map_id uuid NOT NULL,
    affects_rank boolean NOT NULL,
    is_active boolean NOT NULL,
    source_url text NOT NULL,
    reviewed_on date NOT NULL,
    game_id uuid NOT NULL,
    PRIMARY KEY (source_catalog, mode_id, map_id),
    FOREIGN KEY (source_catalog, game_id, mode_id)
        REFERENCES buddyup.catalog_game_modes (source_catalog, game_id, id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (source_catalog, game_id, map_id)
        REFERENCES buddyup.catalog_game_maps (source_catalog, game_id, id) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX ix_catalog_active_game_slug ON buddyup.catalog_games (slug) WHERE is_active;
CREATE INDEX ix_catalog_active_mode_game ON buddyup.catalog_game_modes (source_catalog, game_id) WHERE is_active;
CREATE INDEX ix_catalog_rank_set_game ON buddyup.catalog_rank_sets (source_catalog, game_id);
CREATE INDEX ix_catalog_ranks_set ON buddyup.catalog_ranks (source_catalog, rank_set_id);

CREATE TABLE buddyup.catalog_initial_snapshot (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    imported_at timestamptz NOT NULL
);

CREATE VIEW buddyup.catalog_active_games AS
SELECT md5(jsonb_build_array('games', source_catalog, id)::text)::uuid AS id,
       name, slug, cover_url, summary
FROM buddyup.catalog_games
WHERE is_active;

CREATE VIEW buddyup.catalog_active_game_modes AS
SELECT md5(jsonb_build_array('game_modes', mode.source_catalog, mode.id)::text)::uuid AS id,
       md5(jsonb_build_array('games', game.source_catalog, game.id)::text)::uuid AS game_id,
       mode.name, mode.code, mode.team_size, mode.team_count, mode.uses_roles, mode.is_ranked
FROM buddyup.catalog_game_modes mode
JOIN buddyup.catalog_games game
  ON game.source_catalog = mode.source_catalog AND game.id = mode.game_id
WHERE game.is_active AND mode.is_active;
