CREATE TABLE usuarios (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email      VARCHAR(254) NOT NULL UNIQUE,
    senha_hash VARCHAR(100) NOT NULL,
    criado_em  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
