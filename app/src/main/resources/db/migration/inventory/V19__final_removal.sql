-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- The second stage of REQ-CORE-009: final removal.
--
-- Stage 0 granted the application SELECT, INSERT and UPDATE on `inventory.item`
-- and no DELETE, which was right while deletion meant setting `deleted_at`. The
-- requirement is two-stage — trash with restore, and "final removal as a
-- separate operation" — and the second stage is a real DELETE.
--
-- It stays a grant on the item table alone. Nothing else in this schema is ever
-- removed by the application: `item_attr_index` is derived and cascades with the
-- row, and the revision history deliberately outlives it (V18).

GRANT DELETE ON inventory.item TO homeinv_app;
