-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- What an item cost, what it is covered by, and what it would cost to replace
-- (REQ-LIFE-001, REQ-LIFE-002, REQ-LIFE-009, REQ-LIFE-014, REQ-LIFE-015).
--
-- COLUMNS AND NOT TYPE ATTRIBUTES, decided with the owner on 2026-09-13.
--
-- 07 §7.3 sketched `purchasePrice` and `warrantyUntil` as fields of a type
-- version, and for one type in isolation that reads well. It does not survive
-- REQ-LIFE-015: a report of purchase price, current value and replacement value
-- "per location, type and tag" has to sum across EVERY type, and an attribute
-- exists only where its type happened to declare it -- under whatever name that
-- type chose. A total that silently omitted the types nobody had configured
-- would be the worst kind of wrong number: plausible.
--
-- 04 §4.4 already carried `Valuation` as a key notion of this block. These are
-- its columns.
--
-- THE THREE FIGURES ARE INDEPENDENT. REQ-LIFE-014 says it in as many words about
-- the replacement value: "independent of the current value and never derived
-- from it". A thing worth 200 on the used market can cost 900 to replace new,
-- and an insurer asks for the second. Nothing in this schema derives one from
-- another, and nothing later may: they are three facts about one object, each
-- with its own as-of date and its own provenance.

-- `varchar(3)` and not `char(3)`: PostgreSQL pads a `char` with spaces, so
-- 'EUR' and 'EUR ' would compare equal in some places and not in others, and a
-- currency code is exactly three characters or it is not one. Hibernate also
-- maps a `String` to `varchar` and refuses the schema otherwise, which is how
-- this was found.
ALTER TABLE inventory.item
    -- REQ-LIFE-001. The source is free text: a shop, a person, a marketplace
    -- listing -- and an invoice is an attachment like any other, which is why no
    -- column here names one.
    ADD COLUMN purchase_amount      numeric(19,4),
    ADD COLUMN purchase_currency    varchar(3),
    ADD COLUMN purchased_on         date,
    ADD COLUMN purchase_source      text CHECK (purchase_source IS NULL
                                                OR length(purchase_source) <= 300),

    -- REQ-LIFE-002. A lifetime warranty has no date, which is exactly why it is a
    -- flag rather than a date far in the future: "2099-12-31" is a date somebody
    -- would eventually have to explain.
    ADD COLUMN warranty_until       date,
    ADD COLUMN lifetime_warranty    boolean NOT NULL DEFAULT false,

    -- REQ-LIFE-014, with its as-of date and its provenance.
    ADD COLUMN replacement_amount   numeric(19,4),
    ADD COLUMN replacement_currency varchar(3),
    ADD COLUMN replacement_as_of    date,
    ADD COLUMN replacement_source   text CHECK (replacement_source IS NULL
                                                OR replacement_source IN ('MANUAL', 'PLUGIN')),

    -- REQ-LIFE-009's current value. Written by a person or by a valuation plugin;
    -- the straight-line depreciation the requirement describes is a calculation
    -- SOMETHING ELSE performs and stores here, not a formula this column implies.
    ADD COLUMN current_amount       numeric(19,4),
    ADD COLUMN current_currency     varchar(3),
    ADD COLUMN current_as_of        date;

-- An amount without a currency is not an amount (ADR-0025). Stated per figure,
-- because a row may carry one of the three and not the others.
ALTER TABLE inventory.item
    ADD CONSTRAINT item_purchase_has_currency
        CHECK (num_nonnulls(purchase_amount, purchase_currency) <> 1),
    ADD CONSTRAINT item_replacement_has_currency
        CHECK (num_nonnulls(replacement_amount, replacement_currency) <> 1),
    ADD CONSTRAINT item_current_has_currency
        CHECK (num_nonnulls(current_amount, current_currency) <> 1),
    -- A lifetime warranty and an expiry date are two answers to one question.
    ADD CONSTRAINT item_warranty_is_one_answer
        CHECK (NOT (lifetime_warranty AND warranty_until IS NOT NULL));

-- The report of REQ-LIFE-015 groups by currency and sums per figure, per tenant.
-- Partial, because most items carry no price at all and an index over their NULLs
-- would be an index over nothing.
CREATE INDEX item_purchase_currency ON inventory.item (tenant_id, purchase_currency)
    WHERE purchase_amount IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX item_replacement_currency ON inventory.item (tenant_id, replacement_currency)
    WHERE replacement_amount IS NOT NULL AND deleted_at IS NULL;

-- REQ-LIFE-013 collects expiry dates in one overview, and REQ-LIFE-002's
-- reminder is a query for "whose warranty ends soon".
CREATE INDEX item_warranty_until ON inventory.item (tenant_id, warranty_until)
    WHERE warranty_until IS NOT NULL AND deleted_at IS NULL;
