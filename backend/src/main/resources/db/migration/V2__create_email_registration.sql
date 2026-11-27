CREATE TABLE buddyup.account (
    id uuid PRIMARY KEY,
    email_normalized text,
    email_verified_at timestamptz,
    password_hash text,
    state text NOT NULL CHECK (state IN ('PROFILE_REQUIRED', 'ACTIVE', 'SUSPENDED', 'DELETED')),
    adult_declared_at timestamptz,
    policy_version text,
    session_epoch bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    deleted_at timestamptz,
    CONSTRAINT uq_account_email UNIQUE (email_normalized)
);

CREATE TABLE buddyup.player_profile (
    account_id uuid PRIMARY KEY REFERENCES buddyup.account(id),
    display_name text NOT NULL,
    nickname text NOT NULL,
    nickname_key text NOT NULL,
    country_or_region_code text NOT NULL,
    bio text,
    avatar_object_key text,
    CONSTRAINT uq_player_profile_nickname UNIQUE (nickname_key)
);

CREATE TABLE buddyup.profile_language (
    profile_id uuid NOT NULL REFERENCES buddyup.player_profile(account_id),
    language_code text NOT NULL,
    PRIMARY KEY (profile_id, language_code)
);

CREATE TABLE buddyup.auth_challenge (
    id uuid PRIMARY KEY,
    account_id uuid REFERENCES buddyup.account(id),
    email_normalized text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('REGISTRATION_EMAIL', 'REGISTRATION_PROOF')),
    secret_digest text NOT NULL,
    request_source_digest text,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0)
);

CREATE INDEX ix_auth_challenge_email_requests
    ON buddyup.auth_challenge (email_normalized, created_at)
    WHERE kind = 'REGISTRATION_EMAIL';
CREATE INDEX ix_auth_challenge_source_requests
    ON buddyup.auth_challenge (request_source_digest, created_at)
    WHERE kind = 'REGISTRATION_EMAIL';
CREATE UNIQUE INDEX uq_auth_challenge_registration_proof
    ON buddyup.auth_challenge (secret_digest)
    WHERE kind = 'REGISTRATION_PROOF';
CREATE INDEX ix_auth_challenge_expires_at ON buddyup.auth_challenge (expires_at);
