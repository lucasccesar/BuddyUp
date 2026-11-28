
CREATE TABLE buddyup.account_session (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES buddyup.account(id),
    token_digest text NOT NULL UNIQUE,
    channel text NOT NULL CHECK (channel IN ('WEB', 'MOBILE')),
    remember_me boolean NOT NULL,
    session_epoch bigint NOT NULL,
    last_seen_at timestamptz NOT NULL,
    revoked_at timestamptz
);

CREATE INDEX ix_account_session_account ON buddyup.account_session (account_id);

ALTER TABLE buddyup.auth_challenge
    ADD COLUMN session_id uuid REFERENCES buddyup.account_session(id),
    ADD COLUMN next_delivery_at timestamptz,
    DROP CONSTRAINT auth_challenge_kind_check,
    ADD CONSTRAINT auth_challenge_kind_check CHECK (
        kind IN ('REGISTRATION_EMAIL', 'REGISTRATION_PROOF', 'PASSWORD_RESET', 'REAUTH_CHANGE_EMAIL', 'EMAIL_CHANGE')
    ),
    ADD CONSTRAINT auth_challenge_account_session_check CHECK (
        (kind = 'PASSWORD_RESET' AND account_id IS NOT NULL AND session_id IS NULL)
        OR (kind IN ('REAUTH_CHANGE_EMAIL', 'EMAIL_CHANGE') AND account_id IS NOT NULL AND session_id IS NOT NULL)
        OR (kind IN ('REGISTRATION_EMAIL', 'REGISTRATION_PROOF') AND session_id IS NULL)
    );

CREATE UNIQUE INDEX uq_auth_challenge_account_token ON buddyup.auth_challenge (kind, secret_digest)
    WHERE kind IN ('PASSWORD_RESET', 'REAUTH_CHANGE_EMAIL');
CREATE INDEX ix_auth_challenge_account_kind ON buddyup.auth_challenge (account_id, kind)
    WHERE account_id IS NOT NULL AND consumed_at IS NULL;
CREATE INDEX ix_password_reset_delivery ON buddyup.auth_challenge (next_delivery_at, id)
    WHERE kind = 'PASSWORD_RESET' AND next_delivery_at IS NOT NULL AND consumed_at IS NULL;

CREATE TABLE buddyup.account_attempt_window (
    scope text NOT NULL,
    subject_digest text NOT NULL,
    window_started_at timestamptz NOT NULL,
    attempts integer NOT NULL CHECK (attempts > 0),
    PRIMARY KEY (scope, subject_digest)
);

CREATE TABLE buddyup.email_change_notice (
    id uuid PRIMARY KEY,
    previous_email text NOT NULL,
    new_email text NOT NULL,
    next_attempt_at timestamptz NOT NULL
);

CREATE INDEX ix_email_change_notice_due ON buddyup.email_change_notice (next_attempt_at, id);
