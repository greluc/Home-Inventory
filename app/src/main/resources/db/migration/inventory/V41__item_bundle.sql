-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- An item that contains other items (REQ-CORE-007).
--
-- A bundle is not a place. The requirement says it in as many words — "containing
-- other items **without removing them from their location**" — so this table adds
-- a membership and touches nothing else: the camera bag contains the lens, and the
-- lens is still on the shelf in the study, where somebody looking for it will go.
--
-- Not `item_relation` with a fifth `relation_type`. A relation is a statement
-- about two things that stays true whichever way it is read; containment is a
-- structure, and a structure has a rule a relation does not: it must not close a
-- loop. Putting it in the same table would put a check on four types that only
-- applies to one.
--
-- No `is_bundle` flag on the item either. An item that contains something is a
-- bundle and an item that contains nothing is not, and a flag would be a second
-- answer to a question the rows already answer — the kind that goes wrong when
-- the last member is removed.
--
-- MANY bundles per item, decided 2026-09-13: the graph is directed and acyclic
-- rather than a forest. A lens belongs in the camera bag AND in the "insured
-- equipment" set, and forcing a choice would make one of the two lists wrong. The
-- cost is that a cycle is a reachability question — a recursive walk — rather than
-- a path comparison the way it is for the location tree.

CREATE TABLE inventory.item_bundle (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id      uuid NOT NULL,          -- a discriminator, not a foreign key (07 §7.5)
    bundle_item_id uuid NOT NULL,
    member_item_id uuid NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    version        bigint NOT NULL DEFAULT 1,
    -- Nothing contains itself. The one-step case, checked by the row rather than
    -- by the application, because it is a property of the row; the transitive
    -- case needs the graph and is checked where the graph can be walked.
    CONSTRAINT item_bundle_not_self CHECK (bundle_item_id <> member_item_id),
    UNIQUE (tenant_id, id),
    -- Asked for twice, the second is the first.
    UNIQUE (tenant_id, bundle_item_id, member_item_id),
    CONSTRAINT item_bundle_bundle_same_tenant
        FOREIGN KEY (tenant_id, bundle_item_id)
        REFERENCES inventory.item (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT item_bundle_member_same_tenant
        FOREIGN KEY (tenant_id, member_item_id)
        REFERENCES inventory.item (tenant_id, id) ON DELETE CASCADE
);

-- Both directions are read: what is in this bundle, and which bundles is this in.
-- The second is not a rarer question here than the first, because an item may be
-- in several (see above), so it gets an index of its own rather than a scan.
CREATE INDEX item_bundle_by_bundle ON inventory.item_bundle (tenant_id, bundle_item_id);
CREATE INDEX item_bundle_by_member ON inventory.item_bundle (tenant_id, member_item_id);

ALTER TABLE inventory.item_bundle ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.item_bundle FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.item_bundle
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- No UPDATE. A membership exists or it does not; changing one is a DELETE and an
-- INSERT, which is why 07 §7.1 rule 4 carries it as "issued, never edited" beside
-- `inventory.item_relation` and why it has no `updated_at` to grant a write on.
GRANT SELECT, INSERT, DELETE ON inventory.item_bundle TO homeinv_app;
GRANT SELECT ON inventory.item_bundle TO homeinv_readonly;
