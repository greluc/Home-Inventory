# 07 — Data Model

## 7.1 Ground rules

1. **PostgreSQL 18 is the single source of truth.** Every other store is derived
   and rebuildable.
2. **Every domain table carries `tenant_id`** and is subject to row-level
   security. There is no exception for "but this table is global" — but there are
   four tables that are **not domain tables**, and pretending otherwise would
   force either a fake `tenant_id` or a silently weakened check. They are listed
   exhaustively here, and the RLS check reads this list rather than treating them
   as violations:

   | Table | Why it is instance-wide | What protects it instead |
   |---|---|---|
   | `identification.public_code` | The printed code must resolve **before** any tenant is known, and `UNIQUE(code)` is global so a scan is unambiguous ([ADR-0030](../adr/0030-public-code-format.md)). A per-tenant code space would make resolution impossible | The code reveals nothing about the tenant. Resolution goes through one `SECURITY DEFINER` function that returns a binding only after the caller's permission is checked — it is on the documented list of `REQ-SEC-008` |
   | `identification.code_binding` | Follows `public_code` for the same reason | Carries `tenant_id` and is RLS-protected on read; only the resolver function crosses it |
   | `identification.label_base_url_usage` | Records which base URLs this **instance** has ever printed. It is deployment history, not tenant data ([10 §10.2.1](10-identification-and-labels.md)) | Readable by the operator only; contains no tenant reference at all |
   | `identity.app_user` | Authentication happens **before** any tenant is known: the credential presented is an e-mail address, and an e-mail address does not name a tenant. A `tenant_id` here would have to be read before the row carrying it has been found | Reachable only through `tenancy.membership`, which is RLS-protected: no endpoint returns a user by anything but the caller's own session or a membership of the caller's tenant. Enumeration is answered identically for a known and an unknown address (`REQ-SEC-016`) |
   | `audit.chain_anchor` | The anchor spans all tenants by design — that is the property it provides ([ADR-0031](../adr/0031-audit-chain-per-tenant.md)) | Contains hashes only, never content. Readable by the operator; a tenant verifies its own chain against its own entries |

   **The list is closed.** A new instance-wide table needs an entry here and a
   sentence saying what protects it instead — which is deliberately more friction
   than adding `tenant_id`.
3. **Primary keys are UUIDv7** (`uuidv7()`, built into PostgreSQL 18). They are
   time-ordered — unlike UUIDv4, therefore index-friendly — and can be generated
   by the client while offline ([ADR-0016](../adr/0016-identifiers.md)).
4. **Every domain table carries `version bigint`** for optimistic locking, plus
   `created_at`/`updated_at`/`created_by`/`updated_by`. The DDL in this chapter
   spells them out rather than eliding them for brevity — the first draft did
   elide them, and four of the five sample tables then disagreed with this rule.
   A migration check enforces it, so the samples and the rule cannot drift again.

   **A *domain* table is one that holds mutable state a user edits.** Three kinds
   of table are not that, carry no `version` and no audit columns, and are listed
   here exhaustively for the same reason the instance-wide list above is closed —
   otherwise the check contradicts this chapter's own DDL, which it did:

   | Kind | Tables | Why the columns would be meaningless |
   |---|---|---|
   | **Derived** | `inventory.item_attr_index` | A projection of `item.attributes`, rebuilt wholesale by `REINDEX_ATTRIBUTES`. It has no version of its own — the row it mirrors does — and no author but the application |
   | **Append-only records of an event** | `sync.change_log`, `audit.audit_entry`, `audit.chain_anchor`, `audit.chain_truncation` ([ADR-0046](../adr/0046-truncatable-audit-chain.md)), `identification.label_base_url_usage` | Nothing updates them, so `version`, `updated_at` and `updated_by` would be dead columns. `occurred_at` and the actor are already in the row, under the names the record uses |
   | **Infrastructure** | `outbox.event_publication`, `idempotency.processed_request`, `crypto.tenant_data_key` | Owned by a mechanism, not by a block's domain model. A data key is issued and retired, never edited |
   | **Issued, never edited** | `identification.public_code`, `identification.code_binding` | A code is drawn, printed and bound; nobody edits one. `public_code` has no `tenant_id` at all (see rule 2), so it has no `updated_by` that could mean anything, and `code_binding` is **historised** — a reassignment appends a row rather than updating one, which is what preserves the binding history (`REQ-IDENT-004`). Both were on rule 2's closed list and on neither of rule 4's, so rule 4 demanded `version` and four audit columns from tables with no tenant and no editing user |

   **The list is closed.** A new table that is none of the three needs the columns;
   a new table that claims to be one of the three needs a row here.
5. **No deletion without a tombstone.** A domain delete sets `deleted_at`; final
   removal is a separate, logged operation that leaves a tombstone for
   reconciliation.
6. **Every building block owns its schema** and touches no other.

## 7.2 Overview

```mermaid
erDiagram
    TENANT ||--o{ MEMBERSHIP : has
    USER ||--o{ MEMBERSHIP : has
    MEMBERSHIP }o--|| ROLE : carries

    TENANT ||--o{ ITEM_TYPE : defines
    ITEM_TYPE ||--o{ ITEM_TYPE_VERSION : versions
    ITEM_TYPE_VERSION ||--o{ FIELD_DEFINITION : contains
    ITEM_TYPE ||--o{ ITEM_TYPE : inherits_from

    TENANT ||--o{ LOCATION_CATEGORY : defines
    LOCATION_CATEGORY ||--o{ LOCATION : types
    LOCATION ||--o{ LOCATION : contains

    ITEM_TYPE_VERSION ||--o{ ITEM : types
    LOCATION ||--o{ ITEM : holds
    ITEM ||--o{ ITEM_ATTR_INDEX : projects
    ITEM ||--o{ ITEM_RELATION : relates
    ITEM ||--o{ ATTACHMENT : has
    ITEM ||--o{ TAG_ASSIGNMENT : has
    ITEM ||--o{ CODE_BINDING : carries
    ITEM ||--o{ MAINTENANCE_ENTRY : logs
    ITEM ||--o{ LOAN : lent_as

    MEDIA_OBJECT ||--o{ MEDIA_VARIANT : derives
    MEDIA_OBJECT ||--o{ ATTACHMENT : used_in
    TAG ||--o{ TAG_ASSIGNMENT : assigned

    ITEM ||--o{ CHANGE_LOG_ENTRY : produces
    ITEM ||--o{ REVISION_RECORD : historised
```

## 7.3 The attribute model (variant D)

The central decision: **JSONB as the source of truth, plus a fixed,
application-maintained index side table for searchable fields** — with no DDL at
runtime. Rationale and alternatives in
[ADR-0004](../adr/0004-attribute-storage-model.md).

### Schema definition (relational, versioned)

```sql
CREATE TABLE catalog.item_type (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,                    -- see the note under 7.5 on why
                                                      -- this is NOT a foreign key
    key             text NOT NULL,                    -- 'book', 'power-tool'
    parent_id       uuid,
    kind            text NOT NULL CHECK (kind IN ('PHYSICAL','DIGITAL')),
    icon            text,
    archived_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid, updated_by uuid,
    version         bigint NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, key),
    UNIQUE (tenant_id, id),                           -- the target of composite FKs, see 7.5
    FOREIGN KEY (tenant_id, parent_id) REFERENCES catalog.item_type (tenant_id, id)
);

CREATE TABLE catalog.item_type_version (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    item_type_id    uuid NOT NULL,
    tenant_id       uuid NOT NULL,
    version_number  int  NOT NULL,
    json_schema     jsonb NOT NULL,       -- generated, never hand-written
    published_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    version         bigint NOT NULL DEFAULT 1,
    UNIQUE (item_type_id, version_number),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, item_type_id)
        REFERENCES catalog.item_type (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE catalog.location_category_version (   -- the mirror of item_type_version
    id                   uuid PRIMARY KEY DEFAULT uuidv7(),
    location_category_id uuid NOT NULL,
    tenant_id            uuid NOT NULL,
    version_number       int  NOT NULL,
    json_schema          jsonb NOT NULL DEFAULT '{}'::jsonb,
    published_at         timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    created_by           uuid,
    version              bigint NOT NULL DEFAULT 1,
    UNIQUE (location_category_id, version_number),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, location_category_id)
        REFERENCES catalog.location_category (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE catalog.value_list (                 -- an enum source, reusable (REQ-CORE-029)
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id   uuid NOT NULL,
    key         text NOT NULL,
    labels      jsonb NOT NULL DEFAULT '{}'::jsonb,
    builtin     boolean NOT NULL DEFAULT false,
    archived_at timestamptz,
    -- audit columns and `version` as everywhere
    UNIQUE (tenant_id, key),
    UNIQUE (tenant_id, id)
);

CREATE TABLE catalog.value_list_entry (
    id            uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id     uuid NOT NULL,
    value_list_id uuid NOT NULL,
    value         text NOT NULL,        -- what lands in `attributes`; stable
    labels        jsonb NOT NULL DEFAULT '{}'::jsonb,
    display_order int NOT NULL DEFAULT 0,
    archived_at   timestamptz,          -- still valid for items that carry it
    UNIQUE (value_list_id, value),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, value_list_id)
        REFERENCES catalog.value_list (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE catalog.field_definition (
    id                           uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                    uuid NOT NULL,
    -- EXACTLY ONE of these two. A field belongs to an item type version or to a
    -- location category version, and both are versioned for the same reason: a
    -- definition change must not reach back into rows already written.
    item_type_version_id         uuid,
    location_category_version_id uuid,
    key                          text NOT NULL,   -- 'isbn', 'purchasePrice'
    data_type                    text NOT NULL,   -- the sixteen kinds listed in 04
    labels                       jsonb NOT NULL DEFAULT '{}'::jsonb,  -- {"de":"ISBN","en":"ISBN"}
    help_texts                   jsonb NOT NULL DEFAULT '{}'::jsonb,
    required                     boolean NOT NULL DEFAULT false,
    default_value                jsonb,
    constraints                  jsonb,  -- {"pattern":"…","min":0,"max":10,"unit":"g",
                                         --  "minLength":1,"maxLength":80,"referenceKind":"item"}
    value_list_id                uuid,   -- `enum` and `multi-enum` only, and always
    visibility                   jsonb,  -- {"field":"hasWarranty","operator":"equals","value":true}
    field_group                  text,
    display_order                int NOT NULL DEFAULT 0,
    searchable                   boolean NOT NULL DEFAULT false,
    sortable                     boolean NOT NULL DEFAULT false,
    facetable                    boolean NOT NULL DEFAULT false,
    sensitive                    boolean NOT NULL DEFAULT false,   -- encrypted + permission-gated
    deprecated_at                timestamptz,
    created_at                   timestamptz NOT NULL DEFAULT now(),
    updated_at                   timestamptz NOT NULL DEFAULT now(),
    created_by                   uuid, updated_by uuid,
    version                      bigint NOT NULL DEFAULT 1,
    CHECK (num_nonnulls(item_type_version_id, location_category_version_id) = 1),
    CHECK ((data_type IN ('enum','multi-enum')) = (value_list_id IS NOT NULL)),
    UNIQUE (item_type_version_id, key),
    UNIQUE (location_category_version_id, key),
    FOREIGN KEY (tenant_id, item_type_version_id)
        REFERENCES catalog.item_type_version (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, location_category_version_id)
        REFERENCES catalog.location_category_version (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, value_list_id)
        REFERENCES catalog.value_list (tenant_id, id)
);
```

> **Why a location category is versioned at all** (decided 2026-09-13). An item
> references a type *version*, so a type change devalues nothing that exists
> (`REQ-CORE-025`). A location referenced its *category* — and `REQ-CORE-041`
> gives categories their own fields, which would have made a category the one
> configurable thing whose every edit reached back into rows already written,
> tightening a constraint under locations nobody was looking at. Categories are
> versioned the same way, their fields hang off the version, and `location`
> points at the version. One shape, one generator, one validator.

### Values (JSONB, one item = one row)

```sql
CREATE TABLE inventory.item (
    id                  uuid PRIMARY KEY,                -- from the client (UUIDv7)
    tenant_id           uuid NOT NULL,
    item_type_version_id uuid NOT NULL,
    name                text NOT NULL,
    description         text,
    kind                text NOT NULL CHECK (kind IN ('PHYSICAL','DIGITAL')),
    location_id         uuid,
    quantity            numeric(19,4) NOT NULL DEFAULT 1 CHECK (quantity >= 0),
    quantity_unit       text,
    lifecycle_state     text NOT NULL DEFAULT 'ACTIVE',
    attributes          jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- Fallback search, GENERATED so it cannot drift from the row it describes. TWO
    -- columns, one per shipped UI language: 'simple' does no stemming at all, and this
    -- is the ONLY full-text mechanism at stage 0 and the permanent one in the `minimal`
    -- profile (ADR-0047). A per-tenant configuration is not available — to_tsvector is
    -- immutable only with a literal regconfig, and a non-immutable expression cannot be
    -- a generated column.
    search_vector_de    tsvector
                        GENERATED ALWAYS AS (
                            to_tsvector('german',
                                coalesce(name,'') || ' ' || coalesce(description,''))
                        ) STORED,
    search_vector_en    tsvector
                        GENERATED ALWAYS AS (
                            to_tsvector('english',
                                coalesce(name,'') || ' ' || coalesce(description,''))
                        ) STORED,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid, updated_by uuid,
    deleted_at          timestamptz,
    version             bigint NOT NULL DEFAULT 1,
    CONSTRAINT physical_needs_location
        CHECK (kind <> 'PHYSICAL' OR location_id IS NOT NULL OR deleted_at IS NOT NULL),
    UNIQUE (tenant_id, id),                          -- the target of composite FKs, see 7.5
    -- Composite, so a reference cannot cross a tenant boundary even though
    -- foreign key checks bypass RLS — see 7.5.
    CONSTRAINT item_location_same_tenant
        FOREIGN KEY (tenant_id, location_id)
        REFERENCES locations.location (tenant_id, id),
    CONSTRAINT item_type_version_same_tenant
        FOREIGN KEY (tenant_id, item_type_version_id)
        REFERENCES catalog.item_type_version (tenant_id, id)
);

CREATE INDEX item_attributes_gin ON inventory.item
    USING gin (attributes jsonb_path_ops);
CREATE INDEX item_tenant_updated ON inventory.item (tenant_id, updated_at DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX item_search_fts_de ON inventory.item USING gin (search_vector_de);
CREATE INDEX item_search_fts_en ON inventory.item USING gin (search_vector_en);
```

### The index side table

```sql
CREATE TABLE inventory.item_attr_index (
    tenant_id   uuid    NOT NULL,
    item_id     uuid    NOT NULL,
    field_key   text    NOT NULL,
    num_value   numeric(38,10),
    text_value  text,
    date_value  timestamptz,
    bool_value  boolean,
    ref_value   uuid,
    unit_value  text,          -- ISO 4217 code for `money`, unit symbol for `quantity`
    PRIMARY KEY (item_id, field_key),
    FOREIGN KEY (tenant_id, item_id)
        REFERENCES inventory.item (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX iai_num  ON inventory.item_attr_index (tenant_id, field_key, num_value)
    WHERE num_value IS NOT NULL;
CREATE INDEX iai_text ON inventory.item_attr_index (tenant_id, field_key, text_value)
    WHERE text_value IS NOT NULL;
CREATE INDEX iai_date ON inventory.item_attr_index (tenant_id, field_key, date_value)
    WHERE date_value IS NOT NULL;
CREATE INDEX iai_ref  ON inventory.item_attr_index (tenant_id, field_key, ref_value)
    WHERE ref_value IS NOT NULL;
CREATE INDEX iai_unit ON inventory.item_attr_index (tenant_id, field_key, unit_value, num_value)
    WHERE unit_value IS NOT NULL;
```

> **Why `unit_value` exists — and why leaving it out was a defect.** Two of the
> declared field types carry a unit alongside their number: `money` (amount +
> ISO 4217 code) and `quantity` (value + unit). Projected into `num_value` alone,
> that unit is lost, and `SUM(num_value)` would cheerfully add EUR to USD, or
> grams to kilograms, and return a plausible-looking wrong number. Any aggregation
> over a `money` or `quantity` field therefore **groups by `unit_value`**; a
> mixed-unit total is never produced silently
> ([REQ-CORE-032](../requirements/01-functional.md),
> [REQ-LIFE-017](../requirements/01-functional.md),
> [ADR-0025](../adr/0025-money-representation.md)).

**Properties:**

| Property | Implementation |
|---|---|
| Who maintains it | The application layer, in **the same transaction** as the `item` `UPDATE`. Only fields marked `searchable`, `sortable` or `facetable` are mirrored. |
| How much write cost | A `DELETE`+`INSERT` of that item's rows — typically 3–8 rows, not 50. |
| On a field definition change | `FieldSearchabilityChanged` triggers a background run that re-projects the affected items. No DDL, no lock, cancellable at any time. |
| Rebuild | A `REINDEX_ATTRIBUTES` administrative run, entirely from `item.attributes` — through the same code path as a normal write, so there is no second truth. |
| **Soft deletion** | Setting `deleted_at` **removes** the item's rows from the side table, in the same transaction; restoring the item rebuilds them. The `ON DELETE CASCADE` above fires only on a *final* removal, which is a separate, later operation (`REQ-CORE-009`), so without this rule a trashed item would keep answering attribute filters for the whole retention period. The alternative — keeping the rows and joining back to `item` in every query — was rejected: `REQ-CORE-013` calls this path *transactionally exact*, and a correctness property that depends on every future query remembering a filter is not that ([ADR-0004](../adr/0004-attribute-storage-model.md)) |
| Consistency proof | A nightly reconciliation compares samples between `attributes` and `item_attr_index` and reports deviations as a metric. |
| Relation to OpenSearch | OpenSearch serves full text and facets; `item_attr_index` serves **transactionally exact** filters, sorting and reporting, plus search in the `minimal` profile and during an OpenSearch outage. |

### Example

```jsonc
// inventory.item.attributes of a book
{
  "isbn": "9783836287456",
  "author": ["Kent Beck"],
  "publishedYear": 2022,
  "purchasePrice": { "amount": "49.90", "currency": "EUR" },
  "condition": "good",
  "warrantyUntil": "2027-03-01"
}
```

```
-- item_attr_index (searchable/sortable/facetable only)
item_id | field_key      | text_value      | num_value | unit_value | date_value
--------+----------------+-----------------+-----------+------------+------------------------
 …      | isbn           | 9783836287456   |           |            |
 …      | publishedYear  |                 | 2022      |            |
 …      | purchasePrice  |                 | 49.90     | EUR        |
 …      | condition      | good            |           |            |
 …      | warrantyUntil  |                 |           |            | 2027-03-01 00:00:00+00
```

### Validation

Validation happens in **three** places, deliberately redundant:

1. **Client** (web and apps) against the shipped JSON Schema of the type version —
   instant feedback, works offline.
2. **Application layer** against the same schema — the binding check. Plus domain
   rules JSON Schema cannot express (references exist and belong to the tenant,
   units are convertible).
3. **Database** through a `CHECK` that secures the basic shape — protection
   against writes that bypass the application. It is one `IMMUTABLE` function,
   `catalog.attribute_shape_is_valid(jsonb)`, on both tables that carry
   attributes: the value is an object, every key is shaped like a field key
   (`^[a-z][A-Za-z0-9]*$`), and the whole set is at most **64 KiB**. It cannot
   check a value against its definition — a `CHECK` sees one row and no
   catalogue — and it is not meant to: it holds what is true of every attribute
   set whatever the type says.

## 7.4 The location tree

```sql
CREATE TABLE locations.location (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid NOT NULL,
    category_version_id uuid NOT NULL,   -- the VERSION, as an item references a type version
    parent_id           uuid,
    name            text NOT NULL,
    path            ltree NOT NULL,      -- materialised path
    depth           int   NOT NULL,
    is_mobile       boolean NOT NULL DEFAULT false,
    attributes      jsonb NOT NULL DEFAULT '{}'::jsonb,
    sealed_at       timestamptz,          -- moving box closed
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid, updated_by uuid,
    deleted_at      timestamptz,
    version         bigint NOT NULL DEFAULT 1,
    CONSTRAINT depth_limit CHECK (depth <= 12),
    UNIQUE (tenant_id, id),               -- the target of composite FKs, see 7.5
    FOREIGN KEY (tenant_id, category_version_id)
        REFERENCES catalog.location_category_version (tenant_id, id),
    FOREIGN KEY (tenant_id, parent_id)
        REFERENCES locations.location (tenant_id, id)
);

CREATE INDEX location_path_gist ON locations.location USING gist (path);
CREATE INDEX location_tenant_parent ON locations.location (tenant_id, parent_id);
```

| Question | Query |
|---|---|
| Everything below "cellar" | `WHERE path <@ 'cellar'` — one index lookup, no recursion |
| A location's path for display | Straight from `path`, no join |
| Move a subtree | `UPDATE … SET path = 'new' \|\| subpath(path, nlevel(old))` for the subtree, in one transaction |
| Prevent cycles | A `CHECK` that the target is not `<@` the source, plus an application check |

`parent_id` **and** `path` are both maintained: `parent_id` is the truth for
foreign keys and cascades, `path` is the accelerator. A trigger keeps `path` and
`depth` consistent; a nightly reconciliation verifies the agreement.

## 7.5 Tenant isolation: row-level security

The **second, independent** line of defence. It works even when a query in the
application forgets the tenant filter.

```sql
ALTER TABLE inventory.item ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.item FORCE  ROW LEVEL SECURITY;   -- applies to the owner too

CREATE POLICY tenant_isolation ON inventory.item
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```

> **Why `nullif` is there, and why leaving it out was a defect.** The obvious
> spelling is `current_setting('app.tenant_id', true)::uuid`, and it is wrong on a
> pooled connection. A two-part GUC name that PostgreSQL does not recognise becomes
> a **session placeholder** the first time it is set. `SET LOCAL` reverts at the end
> of the transaction — but to the placeholder's session value, which is the **empty
> string**, not `NULL`. So `current_setting(…, true)` returns `NULL` only on a
> connection that has never carried a tenant; on every connection that has served
> one transaction it returns `''`, and `''::uuid` raises `22P02`.
>
> The consequence is not a leak — an exception is as closed as an empty result —
> but it is not what the row below promises, and it is not what `REQ-SEC-005`
> tests for. A forgotten `SET LOCAL` would surface as a `500` on an unrelated
> query rather than as the documented empty set, and the obvious "fix" for a
> confusing `22P02` is to loosen the cast, which is where a real bug would enter.
> `nullif` makes the documented behaviour the actual behaviour.

**What it takes for this to actually hold:**

| Point | Rule |
|---|---|
| Roles | `homeinv_app` (the application): **no** `BYPASSRLS`, **no** table ownership, **no** DDL rights · `homeinv_migrator`: owner, active only in the one-shot `migrate` service ([ADR-0041](../adr/0041-migration-as-its-own-service.md)) · `homeinv_readonly`: for reporting · **`homeinv_housekeeping`**: `DELETE` on the retention-governed tables and nothing else — no DDL, no `BYPASSRLS`, no ownership. It exists because `REQ-SEC-069` denies the application `DELETE` on the audit log and `REQ-PRIV-010` still requires a retention period to be enforced ([ADR-0046](../adr/0046-truncatable-audit-chain.md)) |
| Setting the context | `SET LOCAL app.tenant_id` at the start of every transaction, from the authenticated context — never from a request parameter |
| Connection pool | `SET LOCAL` is transaction-scoped and is discarded automatically when the connection returns. A test ensures no connection goes back into the pool with a tenant still set. |
| Missing context | `nullif(current_setting(..., true), '')` yields `NULL` → the policy fails → **zero rows**. A forgotten context yields empty results, not foreign data — on a fresh connection *and* on a pooled one that has already served a tenant, which is the case the plain cast got wrong (see the note above). |
| Cross-tenant administration | An explicit, logged `SECURITY DEFINER` function with its own permission check — never `BYPASSRLS` |
| Proof | A test procedure creates two tenants, sets the context wrongly or not at all, and verifies for **every** table that nothing leaks. A new table without RLS fails the test. |

### The gap RLS does not close: foreign keys

PostgreSQL documents it plainly: *referential integrity checks, such as unique or
primary key constraints and foreign key references, always bypass row security.*
The referential triggers run outside the policy, and no `FORCE` changes that.

That matters here, because the tables in [7.3](#73-the-attribute-model-variant-d)
reference across schemas — `inventory.item.location_id` into
`locations.location`, `item_type_version_id` into `catalog.item_type_version`,
`locations.location.category_id` into `catalog.location_category`. Written
naively, an `INSERT` carrying a **foreign tenant's** `location_id` passes the
`WITH CHECK` policy on `item` (its own `tenant_id` is correct) and passes the
foreign key check (which ignores RLS). The row comes into existence, pointing
across a tenant boundary. The application layer catches it
([05 §5.1](05-runtime-view.md): foreign location → `404`) — but the whole purpose
of RLS here is to hold **when the application layer is wrong**.

**Therefore every cross-aggregate reference carries the tenant in the key:**

```sql
-- Referenced side: the tenant-qualified key the reference points at.
ALTER TABLE locations.location
    ADD CONSTRAINT location_tenant_key UNIQUE (tenant_id, id);

Siblings additionally carry **distinct names**, case-insensitively, and the index
that enforces it is **partial**:

```sql
CREATE UNIQUE INDEX location_sibling_name ON locations.location (
    tenant_id,
    coalesce(parent_id, '00000000-0000-0000-0000-000000000000'::uuid),
    lower(name)
) WHERE deleted_at IS NULL;
```

The `coalesce` is what makes the rule apply to the roots as well: `NULL` is not
equal to `NULL`, so without it every root would be unique by virtue of having no
parent. `WHERE deleted_at IS NULL` is rule 5 of §7.1 — a deleted location leaves a
tombstone, and a tombstone must not hold a name hostage.

A violation is `409` with `type: …/name-taken` (`REQ-CORE-064`). *This index existed
from the first migration and was described in no chapter until 2026-09-12, which is
why the API answered `500` for the one case it was built to refuse.*

-- Referencing side: the tenant travels with the reference.
ALTER TABLE inventory.item
    ADD CONSTRAINT item_location_same_tenant
    FOREIGN KEY (tenant_id, location_id)
    REFERENCES locations.location (tenant_id, id);
```

The check still bypasses RLS — it now simply cannot succeed across a tenant
boundary, because `tenant_id` is part of what is matched. The same pattern
applies to `item_type_version_id`, `category_id`, `media_object`, `code_binding`
and every other reference between aggregates.

| Point | Rule |
|---|---|
| Every referenced table | carries `UNIQUE (tenant_id, id)` in addition to its primary key |
| **`tenant_id` itself is not a foreign key** | It is a discriminator, not a reference between aggregates. Declaring `REFERENCES tenancy.tenant(id)` on it would put a cross-schema foreign key in **every** table of **every** block — sixteen blocks reaching into `tenancy` — which is the rule in [04 §4.5](04-building-blocks.md) turned inside out, and would need sixteen entries on the exception list in [7.9](#79-migrations) rather than three. `catalog.item_type` carried one until 2026-09-11 and no other table did; the migration check would have failed the build on this chapter's own DDL. Tenant existence is enforced by the application and by RLS, and a row whose `tenant_id` names no tenant is invisible to every policy — which is the same outcome the constraint would have produced |
| Every foreign key between aggregates | is composite: `(tenant_id, <ref>_id)`, never `<ref>_id` alone |
| Proof | The isolation test is extended: it is not enough that tenant A cannot **read** tenant B's rows — tenant A must also be unable to **create a reference** to one. A single-column foreign key between two tenant-scoped tables fails the build, checked over the migration files. |

## 7.6 The change log for reconciliation

```sql
CREATE TABLE sync.change_log (
    tenant_id     uuid    NOT NULL,
    seq           bigint  NOT NULL,      -- strictly monotonic per tenant
    entity_type   text    NOT NULL,      -- see the entity type list below
    entity_id     uuid    NOT NULL,
    operation     text    NOT NULL,      -- 'UPSERT' | 'DELETE'
    entity_version bigint NOT NULL,
    payload       jsonb,                 -- NULL on DELETE
    actor_id      uuid,
    device_id     uuid,                  -- originating device, for echo suppression
    occurred_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, seq, occurred_at)
) PARTITION BY RANGE (occurred_at);

-- The cursor lookup path. Not UNIQUE: uniqueness of (tenant_id, seq) is
-- guaranteed by the per-tenant sequence, not by an index — see the note below.
CREATE INDEX change_log_cursor ON sync.change_log (tenant_id, seq);
```

> **Why `occurred_at` sits in the primary key — and why leaving it out was a
> defect.** PostgreSQL requires every unique constraint on a partitioned table to
> contain **all** partition key columns. `PRIMARY KEY (tenant_id, seq)` with
> `PARTITION BY RANGE (occurred_at)` does not satisfy that, and the
> `CREATE TABLE` fails outright. Adding `occurred_at` makes the DDL valid but
> moves the guarantee: the database no longer enforces that `seq` is unique
> within a tenant. That enforcement therefore rests entirely on the per-tenant
> sequence, allocated in the same transaction as the change — which is where the
> monotonicity comes from in any case. A nightly check verifies the invariant
> (no duplicate `(tenant_id, seq)`, no gap that is not explained by a rolled-back
> transaction) and reports deviations as a metric, the same way
> `item_attr_index` is reconciled.

| Aspect | Decision |
|---|---|
| Monotonic sequence | One sequence per tenant. The entry is created in the same transaction as the change. Uniqueness of `(tenant_id, seq)` is a property of the sequence, not of a constraint — see the note above. |
| Entity types, reconciled **both ways** | `item`, `location`, `tag`, `tag_assignment`, `item_relation`, `attachment`, `maintenance_entry`, `loan`, `stocktake`, `code_binding` |
| Entity types, **download only** | `item_type`, `item_type_version`, `field_definition`, `location_category`, `value_list`, `label_template` — configuration is never edited offline ([11 §11.2](11-offline-synchronisation.md)) |
| Partitioning | Monthly, **instance-wide**. A partition is detached only once **every** tenant's retention has elapsed for its whole range — i.e. at the instance maximum. Detach is bulk reclamation, not retention enforcement, and no tenant's guarantee depends on it ([ADR-0046](../adr/0046-truncatable-audit-chain.md)) |
| Retention | **Configurable per tenant** within fixed bounds: at least 30, at most 365 days, default 90. Enforced by a **daily row deletion** under `homeinv_housekeeping`, inside live partitions — not by detach, which is instance-wide and would give a tenant choosing 30 days whatever the most conservative tenant on the instance chose. A device whose cursor is older is asked to perform a **full sync** — which is why tombstones must live at least as long. The UI states explicitly how long a device may stay offline at the chosen setting. |
| Echo suppression | `device_id` prevents a device from receiving its own changes back. |
| Size | By far the fastest-growing table. Its size is monitored as a metric. |

## 7.7 The event outbox

Provided by Spring Modulith and extended:

```sql
CREATE TABLE outbox.event_publication (
    id                     uuid PRIMARY KEY,
    listener_id            text NOT NULL,
    event_type             text NOT NULL,
    serialized_event       text NOT NULL,
    tenant_id              uuid,          -- our addition, for attribution
    trace_id               text,          -- our addition
    publication_date       timestamptz NOT NULL,
    completion_date        timestamptz,
    -- Spring Modulith 2.x. Added here 2026-09-11: this DDL described the 1.x
    -- shape, and the application would not start against it — Hibernate
    -- validates the registry's entity mapping at boot and reported the three
    -- columns missing. Taken from what that mapping generates.
    completion_attempts    integer NOT NULL DEFAULT 0,
    last_resubmission_date timestamptz,
    status                 text
        CHECK (status IN ('PUBLISHED','PROCESSING','COMPLETED','FAILED','RESUBMITTED'))
);
CREATE INDEX outbox_incomplete ON outbox.event_publication (publication_date)
    WHERE completion_date IS NULL;
```

A relay process reads incomplete entries and publishes them to RabbitMQ.
`completion_date` is set only after the broker acknowledges. That yields
**at-least-once** delivery; all consumers are therefore built to be idempotent
(deduplicating on the event ID).

## 7.8 Other load-bearing tables

| Table | Purpose | Notable |
|---|---|---|
| `identification.public_code` | The printed short code | `UNIQUE(code)` **globally**, not per tenant — otherwise a scan could not be resolved unambiguously. The code reveals nothing about the tenant. |
| `identification.code_binding` | Code → entity | Historising: a label can be reassigned, the history stays |
| `identification.label_base_url_usage` | Which base URLs have ever been printed onto labels | The source for the "labels with the old base" report ([10 §10.2.1](10-identification-and-labels.md)) |
| `media.media_object` | Blob metadata | `sha256` with `UNIQUE(tenant_id, sha256)`, `ref_count` per tenant. The blob path carries the tenant (`sha256/<tenantId>/<hash>`), so metadata and storage are scoped the same way — they were not before, and the mismatch is what made the dedup short cut leak ([ADR-0032](../adr/0032-per-tenant-blob-addressing.md)) |
| `audit.audit_entry` | Append-only log | Partitioned monthly; `prev_hash`/`entry_hash` form a chain **per tenant**, so a write locks only against that tenant's other writes and a tenant can verify its own chain under its own RLS context ([ADR-0031](../adr/0031-audit-chain-per-tenant.md)). The application role has only `INSERT` and `SELECT` |
| `audit.chain_anchor` | Hourly Merkle root over all tenants | Instance-wide, no `tenant_id` — see the exception list in [7.1](#71-ground-rules). The per-tenant chains prove internal consistency; the anchor is what a rewrite cannot reproduce, because it spans every tenant and chains to its own predecessor. Never pruned: ~9 000 rows a year, and pruning would discard exactly the evidence. Carries `pruned boolean`: a window whose entries a retention run has removed keeps its anchor and its place in the anchor chain, and the verification run stops expecting a recomputation over contents that are gone ([ADR-0046](../adr/0046-truncatable-audit-chain.md)) |
| `audit.chain_truncation` | Where a tenant's verifiable chain begins | Append-only. `(tenant_id, truncated_at)`, plus the `oldest_seq` and `oldest_hash` still retained, the count removed, and `reason` — `retention` or `tenant-erasure`. Verification starts here instead of at genesis. Without it, honouring `REQ-PRIV-010`'s per-tenant audit retention and being attacked produce identical evidence, and the instance raises its own tampering alert daily ([ADR-0046](../adr/0046-truncatable-audit-chain.md)) |
| `audit.revision_record` | Domain history | JSONB snapshot per version, restore possible |
| `tagging.tag` | A tenant-wide label | `UNIQUE(tenant_id, lower(name))` **where it is still itself**: a merged tag keeps its name so history reads correctly, and `merged_into` points at what it became so a client holding the old id is redirected rather than told it never existed (`REQ-CORE-063`) |
| `tagging.tag_group` | A group of tags | `exclusive` makes a thing carry at most one of them — "condition" is new, used or broken, never two of those |
| `tagging.tag_assignment` | Tag → item or place | Exactly one target, in two nullable columns with `num_nonnulls(...) = 1` and a composite foreign key each. A polymorphic `(kind, id)` pair would be a reference the database cannot check, which is the same reason [7.5](#75-tenant-isolation-row-level-security) gives for every composite key here, and `MigrationRulesTest` fails a reference between tenant-scoped tables that is not composite |
| `inventory.item_relation` | Relations | `relation_type` (`ACCESSORY_OF`, `PART_OF`, `REPLACEMENT_FOR`, `RELATED`), directed, `CHECK` against self-reference |
| `inventory.loan` | Lending | Borrower (internal or free text), handed out, due back, returned |
| `plugins.plugin_registration` | Plugin registry | Manifest, contract version, certificate fingerprint |
| `plugins.granted_capability` | Granted capabilities | Per tenant, with timestamp, granting person and revocation |
| `tenancy.quota_usage` | Usage counters | Carried forward, not counted on every query |
| `crypto.tenant_data_key` | The wrapped per-tenant data key | `(tenant_id, dek_id)`, the wrapped DEK, the KEK version that wrapped it, and `created_at`/`retired_at`. A retired key still **decrypts**; it no longer encrypts, which is what lets a rotation skip rewriting the data ([ADR-0019](../adr/0019-sensitive-field-encryption.md)). RLS applies. **Its own schema**, for the same reason as `idempotency`: several blocks encrypt — `inventory` for item attributes, `plugins` for settings of type `secret` — so it belongs to none of them, and `platform` has no schema |
| `idempotency.processed_request` | `Idempotency-Key` → the first response | `PRIMARY KEY (tenant_id, key)`, plus the payload hash and the serialised response. Written **in the same transaction** as the record it protects ([ADR-0009](../adr/0009-messaging-and-events.md)); a daily run removes entries older than 24 h. **Its own schema, not `platform`** — the shared kernel has no schema and no database access at all ([04 §4.3](04-building-blocks.md)), and it cannot belong to a block either, because every block writes it inside its own transaction. Cross-cutting infrastructure, exactly like `outbox` |

## 7.9 Migrations

| Aspect | Decision |
|---|---|
| Tool | Flyway, numbered SQL scripts, one script per change |
| Executing role | `homeinv_migrator` — the application role **never** has DDL rights |
| Location | `src/main/resources/db/migration/<schema>/V<n>__<description>.sql` — one directory per building block |
| CI checks | Migration against a copy of a realistic data set · no lock held longer than 5 s · backward compatibility with the previous code version · every new table has RLS and `tenant_id` (checked automatically) · every new domain table has `version` and the four audit columns ([7.1](#71-ground-rules), rule 4) · **no single-column foreign key between two tenant-scoped tables** — references are composite on `(tenant_id, id)` ([7.5](#75-tenant-isolation-row-level-security)) · **`tenant_id` carries no foreign key of its own** ([7.5](#75-tenant-isolation-row-level-security)) · every new table that persists state has a backup row in [06 §6.13](06-deployment-view.md) · no statement in one block's migration names another block's schema ([04 §4.5](04-building-blocks.md)) — **with the one documented exception**: the composite, tenant-qualified foreign keys of [7.3](#73-the-attribute-model-variant-d) and [7.5](#75-tenant-isolation-row-level-security) (`inventory.item` → `locations.location` and → `catalog.item_type_version`, `locations.location` → `catalog.location_category`, `tenancy.membership` → `identity.app_user`). The last is **single-column on purpose**: the composite rule governs references between two *tenant-scoped* tables, and `app_user` is instance-wide, so there is no tenant to carry. The check reads that list the way the RLS check reads the instance-wide list in [7.1](#71-ground-rules): an FK **on the exception list** passes, anything else naming a foreign schema fails, and a new entry needs a row there. Stated absolutely — as this cell was until 2026-09-11 — the check would have failed the build on the very DDL that makes tenant isolation survive a foreign-key check |
| Forbidden | Destructive changes in the same release as the corresponding code change (the three-step from [06 §6.12](06-deployment-view.md)) |
| Test data | Separate from migrations, never inside `V*` scripts |

## 7.10 Volume estimate

| Subject | Assumption | Size |
|---|---|---|
| `item` | 1 M rows, attributes ⌀ 2 KB | ~2.5 GB, **plus ~300 MB** for the two `tsvector` columns and their GIN indexes ([ADR-0047](../adr/0047-bilingual-search-vectors.md)) — about 12 %, for the difference between a search that stems and one that matches prefixes |
| `item_attr_index` | ⌀ 6 rows per item | ~600 MB including indexes |
| `change_log` | 90 days, 5 000 changes/day | ~1.5 GB |
| `audit_entry` | 1 year | ~2 GB |
| Media | 1 M items, 30 % with photos, ⌀ 2 MB + derivatives | ~700 GB → **lives in the `BlobStore`, not in the database** |
| WAL archive | 15-minute segments plus write bursts, pruned to the newest verified base backup | a few GB, bounded by the pruning run — and monitored, because a full archive volume stops PostgreSQL writing ([ADR-0045](../adr/0045-wal-archive-volume.md)) |
| OpenSearch | 1 M documents | ~3 GB |

From this follow the sizing in [06 §6.7](06-deployment-view.md) and the decision
never to store media in the database.
