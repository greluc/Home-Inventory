-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Passkeys: the other half of the second factor (REQ-AUTH-002, 12 §12.4).
--
-- V35 already allows `PASSKEY` in the kind check — "a constraint that has to be
-- widened later is a migration nobody plans for" — and this brings the three
-- columns one needs.
--
-- WHAT IS STORED, AND WHY NONE OF IT IS SEALED
--
-- A passkey leaves the SERVER a public key. There is no shared secret to keep:
-- the private half never leaves the authenticator, which is the property that
-- makes a passkey worth more than a TOTP secret in the first place. So
-- `material` here holds the attested credential data — the credential id and the
-- COSE public key — in clear, unlike the TOTP secret beside it, which is sealed
-- because it is a secret.
--
-- `sign_count` is the authenticator's own counter. A response carrying a counter
-- that did not move forward is a cloned authenticator, which the library checks
-- and this column is what it checks against.

ALTER TABLE identity.credential
    -- Base64url of the raw credential id, which is what an assertion presents.
    ADD COLUMN credential_id text,
    ADD COLUMN sign_count bigint NOT NULL DEFAULT 0,
    -- `usb`, `nfc`, `internal`, … as the authenticator reported them. A hint for
    -- the client's prompt and nothing the server decides on.
    ADD COLUMN transports text;

-- One row per credential id, globally: an assertion arrives with an id and
-- nothing else, so two accounts sharing one would be an ambiguous lookup — and
-- the same authenticator registered twice for one account would make removing it
-- look like it had not worked.
CREATE UNIQUE INDEX credential_by_credential_id
    ON identity.credential (credential_id)
    WHERE credential_id IS NOT NULL AND deleted_at IS NULL;

-- The column belongs to exactly one kind, in both directions. Without the second
-- half a TOTP row could carry a credential id nothing would ever read.
ALTER TABLE identity.credential
    ADD CONSTRAINT credential_passkey_has_an_id
        CHECK ((kind = 'PASSKEY') = (credential_id IS NOT NULL));
