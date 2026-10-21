-- One row per user and music provider (ADR 0003). Tokens go through TokenCipher (AES-GCM in #11)
CREATE TABLE connected_accounts (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider                VARCHAR(32) NOT NULL,
    access_token            TEXT        NOT NULL,
    access_token_expires_at TIMESTAMPTZ,
    refresh_token           TEXT,
    scopes                  TEXT        NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT connected_accounts_user_provider_key UNIQUE (user_id, provider)
);
