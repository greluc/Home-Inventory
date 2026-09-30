-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- REQ-LIFE-009.
--
-- The nightly depreciation run is looking for the tenants that have something
-- to depreciate, so there is no context to set yet -- the same shape, and the
-- same reason, as every other run of its kind (07 §7.5). It returns tenant ids
-- and nothing else: not a row, not a count of rows, not an amount, so it cannot
-- become a way to learn what another tenant owns or what it is worth.
--
-- The condition is deliberately loose: a purchase price and a date is all it
-- takes to be a candidate, and whether the type actually declares a useful life
-- is decided inside the tenant's own context where the catalogue is readable. A
-- function that reached into `catalog` to be precise here would be a function
-- reading two schemas across every tenant, which is a larger thing to trust
-- than one that occasionally names a tenant with nothing to do.

CREATE FUNCTION inventory.tenants_with_depreciable_items()
RETURNS TABLE (tenant_id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = inventory, pg_temp
AS $$
    SELECT DISTINCT i.tenant_id
    FROM inventory.item i
    WHERE i.purchase_amount IS NOT NULL
      AND i.purchased_on IS NOT NULL
      AND i.deleted_at IS NULL
      AND i.lifecycle_state IN ('ACTIVE', 'LENT')
      AND (i.current_source IS NULL OR i.current_source = 'DEPRECIATION');
$$;

GRANT USAGE, CREATE ON SCHEMA inventory TO homeinv_bootstrap;
ALTER FUNCTION inventory.tenants_with_depreciable_items() OWNER TO homeinv_bootstrap;
REVOKE CREATE ON SCHEMA inventory FROM homeinv_bootstrap;

REVOKE ALL ON FUNCTION inventory.tenants_with_depreciable_items() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION inventory.tenants_with_depreciable_items() TO homeinv_app;

CREATE POLICY bootstrap_depreciation_lookup ON inventory.item
    FOR SELECT
    TO homeinv_bootstrap
    USING (true);

GRANT SELECT ON inventory.item TO homeinv_bootstrap;

COMMENT ON FUNCTION inventory.tenants_with_depreciable_items() IS
    'Tenant ids with something a depreciation run could write. SECURITY DEFINER, on the list in '
    '07 §7.5: it returns ids and nothing else.';
