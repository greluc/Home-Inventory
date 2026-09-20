-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- REQ-LIFE-009.
--
-- How long a thing of this kind is expected to last, in months. What the
-- built-in straight-line depreciation divides by, and the reason the
-- requirement calls the useful life "type-dependent": a sofa and a laptop do
-- not decline at the same rate, and one number for both is a number nobody
-- should put in a claim.
--
-- NULLABLE, AND EMPTY IN EVERY SHIPPED TYPE. Decided with the owner on
-- 2026-09-20. The alternative was shipping a figure per built-in type, which
-- would have meant this application inventing how long somebody's furniture
-- lasts and then putting that invention in an insurance report. A useful life
-- is a judgement about a household, so a tenant makes it; until they do, the
-- depreciation has nothing to divide by and produces nothing, which is the
-- honest state rather than a default in disguise.
--
-- On `item_type` and not on `item_type_version`: it is a property of the kind
-- of thing rather than of a version's field set, and changing it should not ask
-- somebody to publish a new version of their type.

ALTER TABLE catalog.item_type
    ADD COLUMN useful_life_months integer
        CHECK (useful_life_months IS NULL OR useful_life_months BETWEEN 1 AND 1200);

COMMENT ON COLUMN catalog.item_type.useful_life_months IS
    'How long a thing of this type is expected to last, for the straight-line depreciation of '
    'REQ-LIFE-009. Null means this type is not depreciated; no shipped type carries one.';
