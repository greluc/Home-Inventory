-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- The production role script, plus the one thing it deliberately omits:
-- passwords. Production gets them from mounted secrets; a test container needs
-- literals, and these are literals that exist only inside a container that is
-- destroyed when the test class ends.
--
-- Runs after 00-roles.sql, which is the production file copied in by Gradle.
-- The tests therefore exercise the real roles - NOBYPASSRLS included, which is
-- the property every isolation test depends on.

ALTER ROLE homeinv_app          WITH PASSWORD 'test-app';
ALTER ROLE homeinv_migrator     WITH PASSWORD 'test-migrator';
ALTER ROLE homeinv_readonly     WITH PASSWORD 'test-readonly';
ALTER ROLE homeinv_housekeeping WITH PASSWORD 'test-housekeeping';

-- The migrator owns the schema, so it must be able to create one.
GRANT CREATE ON DATABASE homeinv TO homeinv_migrator;
