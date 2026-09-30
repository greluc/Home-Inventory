-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- A THIRD KIND OF THING A FILE CAN HANG ON (REQ-LIFE-003).
--
-- `media.attachment` is polymorphic on purpose: one table for what hangs on an
-- item and on a place, with one reference count and one cleanup path. The
-- maintenance log needs an attachment -- an invoice, a photograph of the worn
-- part -- and the honest way to give it one is to let it be a target here,
-- rather than to put a media id in `inventory` and a foreign key across a schema
-- boundary that 04 §4.5 forbids.
--
-- What this buys: reference counting, the deduplication of ADR-0032, the
-- malware scan and the signed URLs all apply to an invoice exactly as they
-- apply to a photograph, because it is the same mechanism and not a second one.

ALTER TABLE media.attachment DROP CONSTRAINT attachment_target_kind_check;

ALTER TABLE media.attachment
    ADD CONSTRAINT attachment_target_kind_check
    CHECK (target_kind IN ('ITEM', 'LOCATION', 'MAINTENANCE_ENTRY'));

COMMENT ON COLUMN media.attachment.target_kind IS
    'What the file hangs on: an item, a place, or a maintenance entry (REQ-LIFE-003). '
    'Polymorphic so that one reference count and one cleanup path serve all three.';
