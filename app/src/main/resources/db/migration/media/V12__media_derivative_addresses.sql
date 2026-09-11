-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Where the `thumb` and `preview` derivatives live.
--
-- `sha256` addresses the `full` variant: the re-encoded, metadata-stripped image
-- that the upload stores (REQ-SEC-041, REQ-MED-003 — no stored blob carries a
-- HEIC magic number, because HEIC is transcoded before anything is written).
--
-- The other two are produced afterwards, in the worker, over the broker
-- (ADR-0051). They are NULL until they exist, and that is load-bearing rather
-- than incidental: `MediaService` offers a URL only for a variant whose column
-- is set, so a client never receives a link to something that has not been
-- produced. A flag saying "derivatives ready" would have been one bit for two
-- files and would have had to lie during the moment between them.
--
-- Each derivative is content-addressed in its own right, so two objects whose
-- thumbnails come out identical store one thumbnail — which is what content
-- addressing is for, and is common: two photographs of the same white wall have
-- the same 200-pixel version.
ALTER TABLE media.media_object
    ADD COLUMN thumb_sha256   text CHECK (thumb_sha256   IS NULL OR thumb_sha256   ~ '^[0-9a-f]{64}$'),
    ADD COLUMN preview_sha256 text CHECK (preview_sha256 IS NULL OR preview_sha256 ~ '^[0-9a-f]{64}$'),
    -- When the derivatives were produced. NULL means "not yet"; a value means the
    -- worker finished, whether or not it had anything to produce — a PDF has no
    -- derivatives and must not be retried for ever.
    ADD COLUMN derived_at     timestamptz;

-- The worker's claim query: everything that still needs deriving, oldest first.
-- Partial, so the index holds only the backlog rather than every media object
-- ever stored.
CREATE INDEX media_object_pending_derivation
    ON media.media_object (created_at)
    WHERE derived_at IS NULL AND deleted_at IS NULL;
