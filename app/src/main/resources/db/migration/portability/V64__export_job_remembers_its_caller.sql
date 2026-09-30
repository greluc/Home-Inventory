-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- REQ-PORT-003, REQ-PORT-006, REQ-AUTH-011.
--
-- A field marked `sensitive` is stored sealed (ADR-0019), with a key that never
-- leaves this deployment. An archive carrying the ciphertext carries something
-- no other instance can open, which is data lost on the way out with nothing
-- having failed -- exactly the shape REQ-PORT-003 cannot survive.
--
-- So an export opens them. Not all of them: only the ones the person who ASKED
-- for the archive may read, because an export that opened more would be a way
-- around `authz.field_visibility` with a download attached to it. That decision
-- needs the caller, and the caller is gone by the time the worker builds the
-- archive -- the request answers 202 and the build happens minutes later on
-- another thread in another container.
--
-- These three columns are how the decision travels. They are recorded while the
-- caller is present and their second factor is as fresh as it will ever be, and
-- the worker reconstructs exactly that caller rather than inventing one. A job
-- claimed by a worker therefore opens what its requester could have read in the
-- browser at the moment they asked, and nothing else.

ALTER TABLE portability.export_job
    -- The built-in role, mirroring `tenancy.membership.role`. Copied rather than
    -- joined: a membership changed or ended after the request must not change
    -- what the archive holds, because the archive is of the moment it was asked
    -- for and a later grant is not retroactive.
    ADD COLUMN caller_role text
        CHECK (caller_role IS NULL OR caller_role IN
               ('OWNER', 'ADMIN', 'MEMBER', 'CONTRIBUTOR', 'VIEWER', 'GUEST')),

    -- The tenant-owned role extending it (REQ-TEN-006), or null. No foreign key:
    -- this is a copy of what was true at request time, and a role deleted
    -- afterwards must leave the record standing rather than take it with it.
    ADD COLUMN caller_role_definition_id uuid,

    -- When the caller last proved their second factor, or null for a session
    -- that never did. REQ-AUTH-011 makes a sensitive field readable only inside
    -- that window, and `DefaultAttributeRedaction` reads exactly this value --
    -- so a job requested without a fresh proof produces an archive with the
    -- sensitive values withheld, and says so in its manifest.
    ADD COLUMN caller_second_factor_at timestamptz;

COMMENT ON COLUMN portability.export_job.caller_role IS
    'The requester''s built-in role at request time. A copy, not a join: the archive is of the '
    'moment it was asked for.';

COMMENT ON COLUMN portability.export_job.caller_role_definition_id IS
    'The tenant-owned role extending it, or null. Deliberately no foreign key.';

COMMENT ON COLUMN portability.export_job.caller_second_factor_at IS
    'When the requester last proved a second factor. Null means sensitive values stay sealed '
    '(REQ-AUTH-011).';
