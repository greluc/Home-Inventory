-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- The wrapped data key of each tenant (ADR-0019, REQ-SEC-046..049).
--
-- Envelope encryption: a data key (DEK) per tenant, wrapped with a master key
-- (KEK) the deployment mounts as a file. A database dump therefore yields
-- ciphertext and wrapped keys and nothing else -- the KEK is in no backup of the
-- database, which is the whole point.
--
-- ITS OWN SCHEMA, for the reason 07 §7.8 gives for `idempotency`: several blocks
-- encrypt -- `inventory` for item attributes, `plugins` for settings of type
-- `secret` -- so it belongs to none of them, and it cannot live in `platform`,
-- which has no schema and no database access at all.

CREATE SCHEMA IF NOT EXISTS crypto;

GRANT USAGE ON SCHEMA crypto TO homeinv_app, homeinv_readonly;

CREATE TABLE crypto.tenant_data_key (
    tenant_id   uuid NOT NULL,
    -- One byte in the ciphertext header, so one byte here: 0..255 versions per
    -- tenant. A DEK is retired at 2^32 encryptions (ADR-0019), which at any rate
    -- this system can reach is not 256 rotations away.
    dek_id      smallint NOT NULL CHECK (dek_id BETWEEN 0 AND 255),
    wrapped_dek bytea NOT NULL CHECK (length(wrapped_dek) BETWEEN 44 AND 96),
    kek_version smallint NOT NULL CHECK (kek_version BETWEEN 1 AND 255),
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- A retired key still DECRYPTS; it no longer encrypts. That is what lets a
    -- rotation skip rewriting the data, and it is why this column is the only
    -- thing about a row that ever changes.
    retired_at  timestamptz,
    PRIMARY KEY (tenant_id, dek_id)
);

-- Which key encrypts right now: the newest that has not been retired. Partial,
-- so the index holds only the handful of live keys rather than every version
-- every tenant has ever had.
CREATE INDEX tenant_data_key_active
    ON crypto.tenant_data_key (tenant_id, dek_id DESC)
    WHERE retired_at IS NULL;

ALTER TABLE crypto.tenant_data_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE crypto.tenant_data_key FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON crypto.tenant_data_key
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- A key is issued, retired and removed with its tenant -- never edited. The
-- UPDATE is granted on ONE COLUMN for exactly that reason: retiring is the only
-- change a row may take, and a column grant says so in the place that enforces it
-- rather than only in a comment (07 §7.1, rule 4).
GRANT SELECT, INSERT, DELETE ON crypto.tenant_data_key TO homeinv_app;
GRANT UPDATE (retired_at) ON crypto.tenant_data_key TO homeinv_app;
GRANT SELECT ON crypto.tenant_data_key TO homeinv_readonly;
