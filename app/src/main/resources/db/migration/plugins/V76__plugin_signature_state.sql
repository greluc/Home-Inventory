-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- REQ-PLG-004 — the core verifies the manifest signature, and says what it found.
--
-- `signed` has been a column since V48 and its MEANING changes here without its
-- type changing. It used to be what the operator reported having checked with
-- `cosign`; from now on it is what this core checked itself, against a public key
-- the operator installed beside the plugin (ADR-0085). No data migration goes with
-- that: every existing row is re-registered from its manifest at the next start-up,
-- because `InstalledPlugins` runs on every `ApplicationReadyEvent` and upserts.
--
-- `state_reason` is the new part. A registration already had two states, and the
-- surface could show that a plugin was out of service without being able to say
-- why -- which for a SIGNATURE failure is the whole of the information. Four
-- outcomes now reach this table and three of them need a sentence:
--
--   * verified            -> signed = true,  state = REGISTERED, no reason
--   * unsigned, permitted -> signed = false, state = REGISTERED, a reason saying so
--   * unsigned, refused   -> signed = false, state = DISABLED,   a reason saying so
--   * signature invalid   -> signed = false, state = DISABLED,   a reason saying so
--
-- The last two are deliberately different sentences. An unsigned plugin is one
-- nobody signed; an invalid signature is a document that does not match the
-- signature travelling with it, and no operator setting makes that acceptable.
-- Collapsing them into "not signed" is what would let the second pass as the first.

ALTER TABLE plugins.plugin_registration
    ADD COLUMN state_reason text;

COMMENT ON COLUMN plugins.plugin_registration.state_reason IS
    'Why this plugin is in its current state, in a sentence an operator can act on. '
    'Null when there is nothing to say -- a verified, running plugin.';

COMMENT ON COLUMN plugins.plugin_registration.signed IS
    'Whether THIS CORE verified the manifest signature against the public key the '
    'operator installed for this plugin (REQ-PLG-004, ADR-0085). Before V76 it was '
    'what the operator reported having checked; it is now a result, not a claim.';
