-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- REQ-LIFE-016, amending REQ-LIFE-001.
--
-- WHAT AN ATTACHMENT IS FOR. REQ-LIFE-001 decided on 2026-09-13 that an invoice
-- is "an attachment like any other and needs no field of its own", and that was
-- right for what it was answering: an invoice does not need a COLUMN ON THE ITEM,
-- and a type-version field would have hidden it from every report.
--
-- REQ-LIFE-016 asks for something that decision cannot give. An insurance report
-- carries "the primary photo, the purchase receipt and the as-of date per item",
-- and with nothing to tell a receipt from a photograph the report can only offer
-- a list and leave the reader to find it -- in the one document somebody files
-- after a fire. Amended with the owner on 2026-09-20.
--
-- A ROLE ON THE ATTACHMENT, not on the media object: the same scan of a receipt
-- may be the purchase proof of one item and an ordinary document on another, and
-- the file is stored once by content address either way (ADR-0032).

ALTER TABLE media.attachment
    ADD COLUMN role text NOT NULL DEFAULT 'PHOTO'
        CHECK (role IN ('PHOTO', 'RECEIPT', 'WARRANTY_PROOF', 'OTHER'));

COMMENT ON COLUMN media.attachment.role IS
    'What the attachment is for: PHOTO, RECEIPT, WARRANTY_PROOF or OTHER (REQ-LIFE-016). '
    'Everything uploaded before 2026-09-20 is PHOTO, which is what it was.';

-- What the insurance report looks up per item, and what a client filtering an
-- item's attachments by kind reads. Partial on the live rows, because a deleted
-- attachment is not in any report.
CREATE INDEX attachment_by_role ON media.attachment (tenant_id, target_kind, target_id, role)
    WHERE deleted_at IS NULL;
