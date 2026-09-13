-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- The evidence that a tenant was erased (REQ-TEN-011, REQ-PRIV-005).
--
-- "An erasure certificate is produced" is the acceptance, and a certificate that
-- said only "done" would not be one. This row carries the completion report per
-- building block — how many rows each removed, and where something was left, why.
--
-- ---------------------------------------------------------------------------
-- WHY IT IS INSTANCE-WIDE
-- ---------------------------------------------------------------------------
--
-- It is the sixth entry on the closed list in 07 §7.1, and the reason is the
-- plainest one there: a tenant-scoped certificate would be erased by the very run
-- that writes it. Evidence of an erasure has to outlive the thing it is about.
--
-- The compensating control is what it contains and who reads it. There is no
-- content in here — no item, no name of a person, no address: a tenant id, the
-- tenant's display name as it stood, the ids of who asked and when, and counts.
-- It is read by the instance operator through `/api/v1/instance/**`, which is
-- gated on the INSTANCE_OPERATOR entitlement (ADR-0057), and by nobody else: the
-- application exposes no tenant-scoped path to it, exactly as it exposes none to
-- `identity.app_user`.
--
-- The requester's account id stays. 05 §5.9 keeps the audit entry about the
-- erasure "pseudonymised", and an opaque id is what that means here: it says
-- which account asked, and nothing about the person behind it.

CREATE TABLE tenancy.erasure_certificate (
    id                uuid PRIMARY KEY DEFAULT uuidv7(),
    -- No `tenant_id` and no policy: this table is instance-wide (07 §7.1). The
    -- column below is a reference to a tenant that no longer has rows, which is
    -- why it carries no foreign key either.
    erased_tenant_id  uuid NOT NULL,
    tenant_name       text NOT NULL,
    requested_at      timestamptz NOT NULL,
    requested_by      uuid,
    completed_at      timestamptz NOT NULL DEFAULT now(),
    -- One entry per building block: its name, how many rows it removed, and a
    -- note where something was deliberately left. JSONB rather than a second
    -- table, because a report is read whole or not at all and nothing ever
    -- queries one block's line across certificates.
    report            jsonb NOT NULL,
    CONSTRAINT erasure_certificate_report_is_an_array
        CHECK (jsonb_typeof(report) = 'array')
);

CREATE UNIQUE INDEX erasure_certificate_per_tenant
    ON tenancy.erasure_certificate (erased_tenant_id);

CREATE INDEX erasure_certificate_recent
    ON tenancy.erasure_certificate (completed_at DESC);

-- INSERT and SELECT, and no UPDATE or DELETE at all. A certificate that could be
-- edited afterwards would be evidence of nothing, which is the same argument
-- REQ-SEC-069 makes about the audit log.
GRANT SELECT, INSERT ON tenancy.erasure_certificate TO homeinv_app;
GRANT SELECT ON tenancy.erasure_certificate TO homeinv_readonly;
