/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The web client's own version, substituted at build time from `package.json`.
 *
 * Named without the `__NAME__` wrapping Vite's own examples use: `oxlint` runs with
 * `--deny-warnings` and refuses a dangling underscore, and a lint exception for one constant is a
 * worse trade than a name that does not need one.
 */
declare const APP_VERSION: string;
