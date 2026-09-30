-- SPDX-FileCopyrightText: Lucas Greuloch
-- SPDX-License-Identifier: AGPL-3.0-or-later

ALTER ROLE homeinv_app          WITH PASSWORD 'test-app';
ALTER ROLE homeinv_migrator     WITH PASSWORD 'test-migrator';
ALTER ROLE homeinv_readonly     WITH PASSWORD 'test-readonly';
ALTER ROLE homeinv_housekeeping WITH PASSWORD 'test-housekeeping';
