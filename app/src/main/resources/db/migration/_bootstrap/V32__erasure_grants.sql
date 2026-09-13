-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- What the application may remove when a tenant is erased (REQ-TEN-011,
-- REQ-PRIV-005).
--
-- 05 §5.9 says each block deletes its share of its schema, so the application
-- role is the one that does it — and until now it could not: most of these tables
-- were granted SELECT, INSERT and UPDATE only, because every ordinary removal in
-- this system is a tombstone (07 §7.1, rule 5). An erasure is the one operation
-- that is not.
--
-- THIS FILE SPANS SCHEMAS, and that is deliberate rather than an oversight.
-- 07 §7.9 forbids one block's migration naming another's schema; this is not one
-- block's migration. It sits in `_bootstrap` beside `V1`, which granted
-- `USAGE ON SCHEMA` across every schema for the same reason: the grant is a
-- property of the deployment, not of a block, and splitting it into seven files
-- would make "may the application erase a tenant" a question with seven answers.
--
-- WHAT IS NOT HERE:
--
--   * `audit.revision_record` and everything else the audit log holds.
--     `REQ-SEC-069` gives the application INSERT and SELECT and nothing more, and
--     retention deletions run under `homeinv_housekeeping` (ADR-0046). A tenant
--     erasure therefore LEAVES the audit log, and the erasure certificate says so
--     in as many words rather than implying it went.
--   * `identity.app_user`. An account is not a tenant's: the same person may be a
--     member of another one, and erasing a tenant must not erase them. Account
--     erasure is `REQ-PRIV-005`'s other half and its own flow.
--   * `tenancy.tenant` itself, which is tombstoned rather than removed: the
--     erasure certificate references it, and a certificate pointing at nothing
--     would be evidence of an erasure that cannot say what was erased.

GRANT DELETE ON
    inventory.item,
    locations.location,
    catalog.item_type,
    catalog.item_type_version,
    catalog.location_category,
    catalog.location_category_version,
    catalog.value_list,
    catalog.value_list_entry,
    media.media_object,
    media.media_variant,
    media.attachment,
    tagging.tag,
    tagging.tag_group,
    authz.role_definition,
    tenancy.membership,
    tenancy.invitation,
    tenancy.tenant_quota,
    tenancy.quota_usage
    TO homeinv_app;
