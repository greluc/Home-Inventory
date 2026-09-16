# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Four things are versioned **separately**, because they move at different speeds
([08 §8.3](docs/architecture/08-api-contract.md)): the application, the REST API,
the plugin contract and the event schemas. Where an entry affects one of the
latter three, it says so.

Entries are short — one to three sentences on *what* changed and *why it matters
to the user*. Design rationale belongs in an ADR, not here.

## [Unreleased]

The project is in its design phase; nothing is released. This section records the
groundwork so that the first release has a history rather than a single "initial
commit".

### Changed

- **Every dependency is on its current stable release.** Valkey moves to 9,
  i18next to 26, react-i18next to 17, vitest to 5, gRPC to 1.84, protobuf to
  4.36, sha2 to 0.11, and seven GitHub Actions to their new majors. The smoke
  suite moves to Ubuntu 26.04, because that is the first runner image carrying
  the Podman 5 this deployment has required since it was first described.

- **An upload is answered before it has been scanned.** `POST /api/v1/media` now
  returns `202` with a `Location`, and the file is not retrievable until the scan
  has cleared it — poll `GET /api/v1/media/{id}`, which answers `200` with the
  signed URLs once clean, `422` if the scanner found something and `503` while
  there is no verdict. The scan had been running inside the request, which the
  architecture had said for months it must not, and which meant no upload worked
  in a real deployment at all. *REST API: breaking, pre-release.*

- **The plugin SDK will ship in five languages**, not two: Java, Kotlin, Rust,
  Python and Go, each with its own scaffold, example plugin and contract-test run.
  Stage 3, as before.

### Fixed

- **The malware scanner updates its signatures again.** Under Podman the
  updater failed to start and said so only in a line nobody read: the scanner
  itself came up on the signatures shipped in the image and scanned every
  upload with them, so nothing looked wrong while the definitions quietly aged.
  The smoke suite now fails if the updater cannot start.

- **The Podman deployment starts again.** The background worker refused to
  start because the generated unit passed it the *text* of a default setting
  rather than the setting: systemd does not substitute those, and Docker's
  Compose does, which is why only one of the two runtimes showed it. The
  generator now refuses to write such a line at all.

- **The application image builds again.** It stopped building when the
  Apache-2.0 plugin module joined the build, because the image never copied that
  module in. Nothing was released from it, and no installation was affected;
  the build was simply broken from that moment until now.

- **A TLS fix in the storage service.** `rustls` moves to 0.23.45, which rejects
  TLS 1.3 handshake messages that the previous release accepted across
  encryption level boundaries (RUSTSEC-2026-0285).

- **"Something went wrong" was hiding "there is no such thing".** Asking about a
  field, a type, a version or a value list the tenant does not have answered an
  error that told nobody anything — twelve places in the type system did this.
  They now say the thing is not there, in the same words as everywhere else. A
  mistyped id in an address says the request is malformed rather than blaming the
  server.

- **Two more endpoints now answer about the thing in their path.** Taking a
  relation or a bundle entry off an item that has been deleted said nothing at
  all, and asking what is in a place that does not exist answered "nothing is
  here" rather than "there is no such place". *Removing a relation also now
  requires the item it belongs to: the address always named one and the server
  used to ignore it.*

- **Putting a tag on something that is not there now says so.** Naming an item or
  a place that has been deleted, never existed, or belongs to somebody else
  answered "something went wrong" when assigning a tag, and answered nothing at
  all when removing one or listing what a thing carries. All six of those paths
  now say the thing is not there, in the same words as every other endpoint.

- **Asking for the second page of a list failed.** Every listing that is served
  straight from SQL — item types, location categories, value lists, tags, tag
  groups, a thing's tags, tenant-owned roles, an item's relations — answered an
  error as soon as a client followed the cursor it had just been given. Nothing
  showed it while the lists were short enough to fit on one page. All of them
  page to the end now, and a test walks each one past its first page so the next
  listing cannot arrive broken the same way.

- **The application could serve a blank page, with only a console message to
  say why.** Its Content-Security-Policy names the hash of the one inline script
  — the theme bootstrap that unhides the page — and that hash was taken from the
  file's bytes, while a browser takes it from the parsed text, in which every
  line ending has become a single newline. Built from a checkout with Windows
  line endings, the policy therefore blocked the bundle's own script and the
  interface stayed invisible. The hash is now computed the way the browser
  computes it, and CI checks the committed policy against the generated one
  rather than against itself.

- **The worker could start before the database had been migrated.** It is the
  same application as `api` under a different profile and validates the schema
  as it starts, but it did not wait for the migration the way `api` does — so it
  was a race, and it lost one: "missing table [identity.app_user]", with the
  migration still running alongside it.

- **A Podman deployment started `api` and left the user interface down.** The
  documented command was `systemctl --user start homeinv-api`, which starts
  `api` and the six units it requires — not `web`, which holds the only
  published port, nor `worker`, `clamav` or the egress proxy. `deploy/setup.sh`
  now starts the profile itself, on both runtimes one command, through a
  generated `homeinv-<profile>.target` that is reached only once every service
  in the profile is up. Enabling that one target also replaces enabling each
  unit, which used to start every service in the matrix at the next login —
  OpenSearch included, in a `minimal` deployment.

- **Under Podman, not one health check could run.** `HealthCmd` was written in
  Compose's `["CMD", ...]` shape, which Podman answers by re-splitting the raw
  text of the array on spaces — so every container carried a check made of
  brackets and quotes, and `api` and `worker`, the two whose units wait for one,
  started perfectly and were killed three and a half minutes later for never
  reporting healthy. The smoke suite now requires every running container to
  report healthy on both runtimes.

- **The WAL archive received nothing, on either runtime.** The recovery point
  this deployment promises rests on continuous archiving into the `pgwal`
  volume, and every segment since first start had been refused: the directory
  archiving writes into did not exist in the PostgreSQL image, so both runtimes
  created it root-owned under a server that does not run as root — and under
  Podman, systemd additionally read the archive command's `%f` and `%p` as two
  of its own specifiers. A failing archive command is a log line and nothing
  else, so both faults were silent. The smoke suite now asks the archiver
  instead of reading the setting.

- **A rootless Docker deployment could not start at all.** PostgreSQL, Valkey,
  ClamAV, the blob store and the egress proxy each stopped on "Permission
  denied" for a secret that was plainly there: mounted at mode 0600, a secret
  belongs to a user id the container does not run as, and Compose ignores the
  per-mount ownership that would fix it. Secrets are now readable inside the
  container and unreachable outside it, which is where `deploy/secrets/` being
  a 0700 directory was always doing the work.

- **No image could be uploaded to a real deployment.** Four separate faults in the
  libvips adapter and the image it runs in, each of which alone turned every photo
  into a `500`: the wrong binary was called, the output path grew a second file
  extension, warnings on standard error were parsed as part of the image's width,
  and the container had no AVIF encoder at all. Photographs now upload, re-encode
  and come back without their metadata.

- **Two places with the same name in the same place answered `500`.** The database
  had always refused it — names are unique among siblings — and nothing turned
  that refusal into an answer. It is now `409` with a `name-taken` problem type,
  and the name of a deleted place can be re-used as before. *REST API: additive.*

- **An infected upload stayed attached to the item, as its main picture.** The
  bytes were deleted and the attachment was not, so a list could lead with a
  photograph that could not be shown. A finding now detaches the file from
  everything it hangs on.

- **Sessions were never stored in Valkey and the session cookie had none of the
  attributes it was supposed to have.** Spring Boot 4 moved session
  auto-configuration into a module of its own, and the project depended on the
  plain Spring Session artefact — so the session filter was never registered, the
  session lived in the container's memory, and the cookie was the container's
  `JSESSIONID` with the container's defaults. It is now `__Host-`prefixed,
  `Secure`, `HttpOnly` and `SameSite=Strict`, and it survives a restart.

- **A client that had only read could not write.** Spring Security resolves the
  CSRF token only when something asks for it, and a browser that signs in and
  reads asks for nothing — so the first time a user created anything, it was
  refused. The token is now issued on every request.

### Added

- **An item keeps a service history.** Record what was done to something, when,
  what it cost and why — with the invoice attached. Entries are listed with the
  most recent work first, by when the work was *done* rather than when it was
  typed, and **no entry can be edited afterwards**: a correction is a second
  entry, the way a service history behaves on paper.

- **Every event carries the version of its own schema**, in the routing key, so
  a consumer binds the version it understands and two can run side by side.
  *Event schemas: breaking, pre-release — the keys changed from
  `homeinv.inventory::item-created` to `…::item-created.v1`.*

- **Every page says which build it is and where its source is.** A footer names
  the version, the exact commit and a link to the source — the AGPL's offer is
  about *this* instance, and two builds of the same version can differ. The same
  answer is at `GET /api/v1/version`, without a session, because an offer only
  signed-in people could take up would be owed only to them.

- **Each release carries a CycloneDX SBOM**, generated from what the artefact
  actually ships rather than from what the build files ask for.

- **A password that is already known to attackers is refused.** Choosing one
  now checks it against a list of the hundred thousand most breached passwords
  that ships with the system — no service is called, because this system calls
  nobody. Twelve characters are still the minimum, and there is still no rule
  demanding a capital letter or a digit. An operator who wants a live breach
  service can install a plugin for it; it is asked after the list, receives five
  characters of a hash rather than a password, and cannot let anything through.
  *Plugin contract: a fifteenth port, `PasswordBreachCheck`. Additive.*

- **An operator is told when the malware scanner is missing.** It was already
  impossible for an unscanned file to be served — uploads simply never become
  retrievable — but the cause was invisible, and looked like uploads being slow.
  The worker now says so at startup and reports it unhealthy while it lasts.

- **A forgotten password can be reset.** Ask at the login page and a link
  arrives that works once and for thirty minutes. Setting the new password ends
  every session the account has open — so somebody who took the account over is
  signed out by the real owner — and tells the address the account had before
  the change, which is where you find out if it was not you. Asking about an
  address that has no account looks exactly like asking about one that does.
  *Needs a mail plugin with an instance-level grant; without one the message
  waits and the delivery log says why.*

- **An operator can permit a plugin to act for the instance, not only for a
  tenant.** Security mail about an account — a password reset, a new second
  factor, a remote sign-out — has to go out even when the account belongs to no
  tenant, and until now every part of the path demanded one. The instance
  operator now grants such a plugin under `/api/v1/instance/plugins`, separately
  from any tenant's consent and reaching no tenant's data. *An operator
  installing `plugin-smtp` grants twice: once per tenant, once for the instance.*

- **The system can notify people, and say what became of each message.** Each
  person chooses what they want to hear about and where, and nothing goes to
  somebody who asked for nothing. A message that could not be delivered is tried
  again with a widening gap and then given up on out loud, with every attempt
  and what the far side said kept beside it — so "did my invitation go out" has
  an answer. *Mail itself still needs a plugin, which does not exist yet.*

- **The log can be asked whether it has been altered.** Each household's
  entries can be checked against one another, and every hour is sealed with a
  fingerprint spanning the whole installation and linked to the hour before it
  — so entries cannot be removed and the record quietly rebuilt. A deletion
  made by the retention rules is reported as exactly that, and tells which rule
  removed how much: without that, obeying the retention rules and being
  attacked would look the same.

- **Every change is recorded, and nothing else is.** Whatever alters something
  now leaves an entry saying who did it, from where and with what — and a
  request that only read, or that was refused, leaves none. The completeness
  does not depend on each part of the system remembering: anything that changed
  something and recorded nothing is recorded at the edge.

- **The system keeps an audit log that it cannot edit.** Every recorded action
  carries who did it, what they did, to what, from where and in which request,
  and each entry is chained to the one before it within the same household — so
  a removed or altered entry no longer matches. The application may add entries
  and read them and nothing else; that is enforced by the database, not by the
  program. A plugin's action is recorded under the plugin's own name, never
  under a person's. *Nothing writes to it yet — the write paths follow.*

- **The system can call a plugin.** It connects to one over a mutually
  authenticated connection and checks that the certificate answering is the one
  the operator registered — a different one is refused even when this
  installation's own authority issued it. Every call is bounded: it gives up
  rather than waiting, one plugin can occupy only so much of the system at once,
  and one that keeps failing is left alone for a while instead of being asked
  again. A plugin nobody in a household has agreed to is not reachable there at
  all.

- **The fourteen extension points a plugin can implement now exist.** Code
  formats, scan sources, label renderers and print targets, metadata resolvers,
  storage, search, notifications, federated login, image processing, virus
  scanning, valuation and imports — each one an interface a plugin author can
  build against, in the Apache-2.0 module, so a plugin stays theirs to license.
  Six of them belong to features that arrive later and have nothing behind them
  yet. *Plugin contract: adds `de.greluc.homeinv.plugin.api.port` and a protobuf
  service per port in `home_inv.plugin.v1`, plus the health check every plugin
  serves. Not published until the SDK is, and until then it may still change —
  `buf breaking` now gates every change to it.*

- **Administrators decide what a plugin may do in their household.** The plugin
  list now shows what each one asks for beside what the household has agreed to,
  and an administrator grants or withdraws each capability on its own. Nothing is
  allowed to begin with, agreeing twice is the same as once, and a capability the
  plugin never asked for is refused rather than stored. *REST API: adds
  `GET /api/v1/plugins`, `GET /api/v1/plugins/{id}` and `PUT`/`DELETE
  /api/v1/plugins/{id}/capabilities/{capability}`.*

- **The system registers the plugins an operator installed.** On start it reads
  the list the deployment generated, checks each plugin's manifest and whether it
  was built for a contract this version speaks, and registers the ones that fit.
  A plugin whose files are wrong is skipped with a note in the log — it can never
  stop the system from starting, and the plugins beside it are still registered.
  An installation with no plugins is a complete installation.

- **A plugin is installed once and permitted per household.** The system now
  keeps a register of what the operator has installed and, separately, what each
  household has allowed it to do. A plugin can do nothing at all until somebody
  says yes, and an update that asks for something new does not get it — what was
  already allowed keeps working, and the new request waits.

- **The plugin manifest format is readable.** `plugin-api` — the Apache-2.0
  module a plugin author compiles against — now holds the manifest model and its
  reader, so the same code that the system uses to register a plugin can tell an
  author what is wrong with theirs before they ship it. Nothing user-visible yet.

- **A search can be saved and reused.** Give a query a name and it becomes a
  smart list everybody in the household sees — "everything that needs mending",
  "the tools in the shed". Opening one runs the query again, so what it shows is
  what matches today rather than what matched when it was saved. A list can be
  renamed, changed and removed; who removed it is recorded. *REST API: new
  `/api/v1/saved-searches` resource.*

- **Notes, attribute values, tags and places are searchable.** A search used to
  look at an item's name and description alone. It now also finds an item by
  something written in its notes, by a value of one of its fields, by a tag on
  it, and by the name of the place it is kept in — the room as well as the shelf.
  This works without OpenSearch, so a small installation gets it too.

- **Search can be answered by OpenSearch.** An installation that runs the
  `standard` or `ha` profile can point the application at its OpenSearch, and
  searches are then stemmed and ranked across everything an item carries — its
  name, description, notes, attribute values, tags and where it is kept — rather
  than across its name and description alone. The index keeps itself up to date
  from what happens to an item, and if it is unavailable the answer comes from
  the database instead and says so. An installation that never asked for
  OpenSearch is unaffected and is not reported as degraded. *New settings:
  `HOMEINV_SEARCH_ENGINE`, `HOMEINV_SEARCH_URL`, `HOMEINV_SEARCH_FINGERPRINT`.*

- **An item now says what happened to it.** Creating, editing, moving, writing
  against another type, trashing and restoring each publish an event, so anything
  derived from the inventory — search first — can follow along instead of being
  rebuilt. Nothing user-visible changes yet. *Event schemas: six new ones.*

- **A list can say how it would narrow.** `GET /api/v1/items?facet=type,tag,location`
  answers with counts beside the rows — how many items each type, tag, place and
  countable field would leave — which is the sidebar next to a list of results.
  Each count is taken without its own filter, so after clicking "defekt" the tag
  list still shows the other tags rather than only the one already chosen; places
  descend into the tree one level at a time. *REST API: new `facet` parameter and
  a `facets` member on the response.*

- **Lists can be narrowed by type, tag or place.** Beside the attribute filters,
  `GET /api/v1/items` now takes `filter=type:power-tool`, `filter=tag:broken` and
  `filter=location:subtree:<id>`, which takes a place and everything under it.
  Several values in one filter widen it — `tag:in:broken,repair` is either — and
  a second filter narrows, which is what ticking two boxes in a sidebar means. A
  tag that has since been merged into another still finds the items it was on.
  *REST API: new filter dimensions.*

- **Lists can be narrowed by an item's own fields.** `GET /api/v1/items` takes
  repeatable `filter=attr.<key>:<op>:<value>` conditions over any field a type
  marks searchable, ranges included, and every condition given has to hold. A
  price or a weight has to say which unit it is asking about —
  `attr.purchasePrice:gte:100:EUR` — and a range without one is refused rather
  than answered across every currency at once. *REST API: new query parameter.*

- **Every list now answers in one shape.** Where each kind of list used to return
  its own slightly different wrapper, all of them now carry the rows under `data`,
  where the next page starts under `page`, and how the answer was produced under
  `meta` — including, in time, whether a part of the system was unavailable and
  something less capable answered instead. *REST API: breaking for every list
  endpoint. `items` is now `data`, and `nextCursor` moved into `page`.*

- **Five hundred items at a time.** Pick items in a list and move them, put a tag
  on them, write them against another type or delete them, in one call. Each item
  gets its own answer: one that somebody else has deleted in the meantime is
  reported on its own line and the rest still happen. A change of type keeps the
  values the new type has a field for and the revision keeps the others.
  *REST API: additive — `POST /api/v1/items/bulk`, which answers `207` when an
  entry failed.*

- **An item records what it cost, what covers it and what replacing it would
  cost.** Purchase price with its date and where it came from, a warranty that
  either ends on a day or lasts for life, a replacement value with the day it was
  true and who said so, and a current value beside it. The three figures are
  independent: a camera bought for 899 can be worth 200 and cost 1,100 to replace,
  and an insurer asks for the third. *REST API: additive.*

- **Money has a type of its own now**, so a total can never mix currencies: adding
  euros to dollars throws rather than producing a number that looks right.
  Rounding never happens without being asked for, amounts travel as text rather
  than as JSON numbers, and `49.9` and `49.90` are one value. No `double` or
  `float` may appear anywhere in the application any more — a build rule says so.

- **Eight ready-made item types.** Book, tool, appliance, furniture, clothing,
  software licence, document and food each arrive with their fields already
  defined — nine to twelve of them — and one call turns any of them into a type
  of your own that you can rename, extend and prune like any other. The software
  licence brings what a digital item needs: the account it sits in, the expiry,
  the number of seats, and a licence key that is stored encrypted.

- **A licence key is no longer readable in the database.** Any field a type marks
  sensitive is stored encrypted, and a database dump yields nothing without the
  master key the deployment mounts. Somebody who may not read such a field still
  sees the record and can edit it — and their save no longer deletes the value
  they were never shown. A sensitive field can no longer also be marked
  searchable, sortable or facetable: the index would hold ciphertext and match
  nothing, and the type editor now says so instead of accepting it.

- **Sensitive fields have a lock on them.** Each tenant gets a data key of its
  own, wrapped with the master key the deployment mounts, and a value sealed with
  it is bound to the tenant, the record and the field it belongs to — copied
  anywhere else it simply does not open. The master key can be rotated without
  rewriting a single stored value: mount the new one beside the old, raise the
  version, and each tenant catches up on its next write. An instance without a
  master key no longer starts, rather than starting and sealing nothing.

- **A retry no longer creates a second thing.** Creating an item or a place
  accepts `Idempotency-Key`: send the same key twice and the second request
  answers what the first one answered and creates nothing, however long the gap
  and whatever the network did in between. The same key with a different body, or
  at a different endpoint, is refused rather than quietly answered with the
  earlier result. Keys are forgotten after a day. *REST API: additive.*

- **Two people can no longer overwrite each other's edits.** Reading a single
  item or place now returns an `ETag`, and changing, moving or deleting one
  requires it back as `If-Match`: without the header the request is refused and
  nothing is written, and with one that has gone stale it is refused too, saying
  which version you had and which is current. The web client sends it. *REST API:
  breaking, pre-release.*

- **An item can be a bundle.** `POST /api/v1/items/{id}/bundle` puts something
  into it, `GET` lists what is in it and `GET /api/v1/items/{id}/bundles` says
  what an item is part of. Nothing moves: the lens in the camera bag is still in
  the drawer in the study, which is where you will go to find it. An item can be
  in several bundles at once, and a bundle cannot end up inside itself — not even
  through a chain of other bundles. *REST API: additive.*

- **Types and categories can be edited while the system runs.** A location
  category's name, icon and mobility change through `PUT
  /api/v1/catalog/location-categories/{id}`, an item type's icon through `PUT
  /api/v1/catalog/item-types/{id}`. The thirteen shipped categories are editable
  like any other — calling your `room` a *Zimmer* is naming your own tree, and the
  name reaches the picker you create a location with. Keys never move, so a client
  that translates the shipped ones keeps working. *REST API: additive.*

- **A place can be moved, and what is inside it comes along.** `POST
  /api/v1/locations/{id}/move` re-parents a location and everything under it —
  moving a box with two hundred things in it is one operation and one event, not
  two hundred. A move into the location's own subtree is refused, as is one that
  would push the deepest thing inside it past the twelve-level ceiling, or onto a
  name a sibling at the destination already has. *REST API: additive.*

- **A category can say what goes underneath it.** `PUT
  /api/v1/catalog/location-categories/{id}/child-categories` sets the categories a
  category takes — "only a shelf goes in a cupboard" — and a move that breaks the
  rule is refused. A category with no rule takes everything, which is where every
  category starts and what sending the empty set goes back to, so the restriction
  can be switched on in a tenant whose tree already exists and switched off again.
  Nothing already in place is moved out. *REST API: additive.*

- **Handing out permissions asks for the code again.** Granting a role, inviting
  somebody, defining a role, opening a sensitive field and asking for the tenant
  to be erased all need the second factor proved within the last fifteen minutes;
  one code covers a stretch of work. A sensitive field read after that is simply
  absent again rather than failing the whole request. *REST API: additive.*

- **Owners and administrators must have a second factor.** The role is granted
  as before — creating a tenant still makes you its owner — but it cannot be used
  until an authenticator exists: every request in that tenant says so and points
  at the setup, while signing out, switching tenants and enrolling stay
  reachable. A sensitive field cannot be granted to a role whose members sign in
  with a password alone. The web client shows the setup instead of an error.
  *REST API: additive.*

- **An instance can be closed to new accounts.** `HOMEINV_REGISTRATION_MODE`
  now does something: `invite_only` as before, and `closed`, where an invitation
  still adds somebody who already has an account and creates nobody who does not.
  A value that names no mode stops the instance rather than being guessed at.
  *REST API: additive.*

- **A tenant can hand a machine its own token.** A service account holds one of
  the six roles, belongs to one tenant and stops working on a date that has to be
  given. The token is shown once, at creation, and is never readable again;
  revoking one takes effect on the next request it makes. Issuing and revoking
  ask for the second factor again, because handing out a token is handing out the
  role it carries. *REST API: additive.*

- **You can see where your account is signed in, and end a session from another
  device.** Each entry says what the device called itself and which network it
  came from — the network, not the address — and ending one takes effect on that
  device's next request. *REST API: additive.*

- **A passkey can be the second factor instead of an app.** Register one from the
  browser, sign in with it, and use it where a code would be asked for again.
  Nothing about the authenticator's make is checked — what is checked is that the
  response answers this instance's challenge — and a passkey is bound to the
  address this instance answers at. *REST API: additive.*

- **An account can be protected by a second factor.** An authenticator app is set
  up in two steps — the code is shown once, and a code generated from it is what
  makes it count — and ten single-use recovery codes come with it, shown once and
  never again. The login is then two calls: the password, then the code. A code
  already used is refused for the rest of its own thirty seconds, and taking the
  factor off asks for a code rather than only for an open session. The secret is
  sealed with a key the deployment mounts, so a database dump is not a set of
  working second factors. *REST API: additive.*

- **When the thirty days are up, the tenant is erased and a certificate says what
  went.** The worker walks every building block in turn, each removes its share,
  and the result is one certificate per erased tenant — how many rows each block
  removed, and where something was deliberately left. The audit log is what was
  left: the application may not delete it, the retention run does within the
  tenant's own period, and the certificate says so rather than implying it went.
  The instance operator reads the certificates at `/api/v1/instance/erasures`.
  *REST API: additive.*

- **A tenant can ask to be erased, and has thirty days to change its mind.** The
  owner — and only the owner, which is the first thing an administrator may not
  do — asks, and the tenant stops answering straight away while its data stays
  exactly where it is. The request comes back with a link that undoes it, and the
  link works for somebody who cannot sign in, because being unable to sign in is
  what the request caused. A tenant that is blocked still tells its members why,
  and still lets them switch to another one. *REST API: additive.*

- **A membership can be confined to one part of the storage tree.** Somebody
  given the garage sees the garage and everything below it — places, items,
  searches — and nothing else, and cannot put anything anywhere else either. The
  confinement is enforced twice: in the application, which is what turns it into
  a comprehensible "not found", and in the database, so that a query nobody
  remembered to filter returns nothing rather than a room upstairs. An item with
  no place at all is not in anybody's garage and stays out of sight.
  *REST API: additive.*

- **A field marked sensitive is hidden from roles that may not read it.** Hidden
  rather than starred out: the key is absent from the answer, because a mask says
  the field exists and how long its value is, which for a purchase price is most
  of what somebody was after. It holds everywhere the value would otherwise
  appear — the item, a list of items and the revision history, whose entries keep
  the real value so that restoring one still works. Owners and administrators read
  everything unless a rule says otherwise, and a rule can be given to any role,
  including one the tenant defined itself. *REST API: additive.*

- **A tenant can define roles of its own.** Each one starts from a built-in role
  and adds permissions to it, so nobody has to assemble a role out of thirty
  choices, and a change to a role reaches people who are signed in while it
  happens rather than at their next sign-in. Nobody can define a role that adds
  something they do not hold themselves — defining one and handing it out are the
  same act, and both are measured against the same reach. Removing a role leaves
  its holders on the role it extended rather than stranding them.
  *REST API: additive.*

- **A tenant is bounded in what it may hold.** Items, stored bytes, plugins and
  API calls each have a limit; reaching one is refused with both numbers, so a
  client can say "48 000 of 50 000" rather than "something went wrong". The
  instance operator sets a tenant's limits without being a member of it and
  without being able to see inside it, and where nothing has been set the
  instance-wide default applies — 100 000 items, 50 GiB, 10 plugins and 100 000
  API calls a month. Signing in, switching tenant and asking how much is left
  keep working when the allowance is spent. *REST API: additive.*

- **People can be invited into a tenant, and the roles now mean different
  things.** An invitation is bound to an e-mail address, works once, runs out
  after a week, and can be withdrawn; accepting one is also how an account comes
  into being, which is the only way onto an instance. Members can be listed,
  promoted and removed — and nobody can hand out a role carrying permissions they
  do not hold themselves, nor take one away, nor leave the tenant without an
  owner. The ladder itself has been sharpened: a member now holds everything about
  the inventory's *content*, and configuring the type system or the membership is
  an administrator's. Somebody who belongs to no tenant can also sign in now,
  which is what lets a removed person be invited back. *REST API: additive.*

- **A person can have more than one tenant, and move between them without
  signing in again.** An account the operator has entitled creates tenants of its
  own — a household, a club, a workshop — up to a limit the operator sets per
  account or instance-wide, and a switch changes which one the session acts for
  and nothing else. Who may grant that, install a plugin or later view as another
  user is now a thing rather than a word: the instance operator is a flag on an
  account, the first one belongs to the account the deployment creates, and their
  own area is `/api/v1/instance`. *REST API: additive.*

- **Notes, relations between items, and a restocking level.** Every item carries a
  paragraph of notes in limited Markdown, and the HTML is removed before the text
  is stored, so a client that renders it cannot be made to run somebody's script.
  Items can be related to one another — accessory of, part of, replacement for, or
  simply related — and a relation is stated once and read from both ends, never
  points at its own item, and asked for twice is the one that already exists. A
  consumable can name the level below which it needs restocking; falling under it
  raises an event. *REST API: additive.*

- **Deleting an item is two steps, and every change is recoverable.** A deletion
  puts the item in the trash, where it is listed, restorable and out of every
  ordinary read; removing it for good is a separate operation behind a permission
  of its own, and it takes the attachments with it. Beside that, every change now
  keeps the state it produced: an item's history is readable, an earlier state can
  be made current again — as a new entry rather than a rewind, so the history
  still says what happened — and the record of an item outlives the item itself,
  because something has to be able to say it existed. *REST API: additive.*

- **Tags.** One tenant-wide vocabulary that goes on items and on places alike,
  with optional groups, a colour and an icon. A group can be made exclusive, so a
  thing carries one of new, used or broken rather than two of them. Two tags can
  be merged: every assignment moves, duplicates are dropped rather than failing
  the merge, and the tag that disappears leaves a marker pointing at what it
  became, so a link to the old one still leads somewhere. *REST API: additive.*

- **Items and places carry the fields their type declares.** A creation names a
  type, the server resolves it to the version published at that moment, and the
  attributes are checked against that version's schema — an offending value is a
  `422` naming its path. The fields a tenant marked searchable, sortable or
  facetable are mirrored into an index written in the same transaction, so a
  filter over them is exact rather than eventually right, and a money or quantity
  field carries its currency or unit along so no total ever adds euros to
  dollars. A field marked sensitive is never mirrored, whatever its other flags
  say. *REST API: additive, except that a creation now names a type rather than
  one of its versions.*

- **A tenant defines its own item types and location categories, while the system
  runs.** Fields with sixteen data types, multilingual labels and help texts,
  required flags, ranges, patterns, units, value lists that several types share,
  and a visibility rule over one other field. A type is versioned: fields are
  edited on a draft, publishing freezes it and generates the JSON Schema the API
  serves and the server validates against, and anything already created keeps the
  version it was written against. A type may inherit from another and may only
  tighten what it inherits. A field that is no longer wanted is hidden and keeps
  its values; destroying them is a separate operation that first says how many
  there are. *REST API: additive.*

- **The test suite runs the images the deployment runs**, pinned to their digests
  and read from the one file that describes the deployment. They had drifted: the
  tests exercised a RabbitMQ the deployment does not use.

- **Tenant isolation is now proven on every table, not asserted.** The database is
  seeded with two tenants' rows in all ten tenant-scoped tables and each is checked
  to hide the other's data — and to show nothing at all when no tenant context is
  set. A table added later with a wrong policy, or none, fails the build.

- **The shared kernel is measured.** `platform` holds 33 types in the shared
  kernel, and an architecture rule keeps it that way: it may depend on no
  building block, so it cannot come to hold one's domain. The figure moves with
  every release and a check compares it with the directory (REQ-NFR-024).

- **The deployment is checked by being run, not only by being read.** CI now
  brings the whole stack up on both container runtimes with the one setup
  command, proves that each segment refuses what it is meant to refuse, and walks
  the whole of "create an item, photograph it, store it, find it again" through
  the published port with a real malware scanner.

- **A deployed instance can be signed in to.** `deploy/setup.sh` now also creates
  the first owner and the tenant it owns, with a password generated into
  `deploy/secrets/bootstrap-password` and an address you set in `compose/.env`.
  Before this there was no way to create either, so a freshly deployed instance
  had nobody who could sign in.

- **The web client speaks German and English**, switchable in the bar, starting
  in the language on your profile and falling back to English. Nothing a user
  reads is written into a screen any more.

- **Things can be put somewhere, and photographed.** The client can build the
  tree of places, create a physical item in one of them, and add photographs to
  an item — from the camera directly on a phone. Dates are shown in your own time
  zone.

- **A client can find out where things go.** The locations of a tenant and the
  thirteen kinds of place they can be are now readable through the API, so
  somewhere to put an item can be offered and chosen. Without them a physical
  item could not be created through the API at all (**REST API**).

- **A photo you upload is the one lists show.** The first image attached to an
  item or a location becomes its primary image without being asked; it stays
  selectable (**REST API**).

- **Nothing answers with an unbounded list.** The attachments of a thing are
  paged by cursor like search results are, at most 200 to a page, and a JSON
  request body over one megabyte is refused before it is read. Requests, queries
  and locks all have a thirty-second ceiling (**REST API**).

- **The API describes itself.** `api/openapi.yaml` is generated from the running
  application and committed, so a client has a contract to build against — every
  endpoint, every field, and every way each one can fail. The build fails while
  the document and the code disagree (**REST API**).

- **Errors say what went wrong, everywhere.** Every failure now answers with an
  RFC 9457 document carrying a stable `type` a client can branch on — including
  the ones that never reach the application: an expired session, an unknown path,
  a method a path does not support. Each response carries a `traceId` that appears
  in the server log for the same request, so a report of "it said something went
  wrong" leads to the operation (**REST API**).

- **The log is JSON.** One event per line in ECS, with `traceId`, `tenantId` and
  `actorId` as fields, so a log shipper can query them instead of matching text.

- **The gates that read the repository rather than run it.** Secret scanning in
  CI and as a pre-commit hook, daily vulnerability scans of the dependencies and
  of all three images, CodeQL for Java and TypeScript, SpotBugs with
  `find-sec-bugs`, and a check that every fact the documentation states twice
  still agrees with the repository.

- **Media has somewhere to live.** `blobstore/` is a small service that owns the
  data volume, so `api` and `worker` hold no state and can be run more than once.
  It speaks gRPC over mutual TLS with a pinned certificate, verifies every blob
  against the address it was given, and ships as a binary in an image with no
  shell in it.

- **`web` is the ingress.** It proxies the API and the media path to `api` on the
  two-member frontend segment, appends its own step to `X-Forwarded-For` rather
  than overwriting it, and leaves every response header the application sets
  intact. CI checks all five rules of that hop.

- **Thumbnails and previews are generated in the background**, by the `worker`
  role over the broker, so photographing something on a phone does not wait for
  three image encodes. A variant that has not been produced is simply not offered,
  rather than offered as a broken link.

- **An uploaded image is re-encoded before it is stored**, to AVIF, with every
  metadata block removed — so an embedded payload never reaches the store and GPS
  coordinates never reach anyone. Media is served from its own hostname through
  short-lived signed links, never inline, never with a session cookie.

- **Text arriving through the API is canonicalised.** Unicode NFC, control
  characters removed, trimmed — so two spellings of the same name are one name,
  and a right-to-left override cannot make a label read as something it is not.
  A field the endpoint does not accept is refused with its name, rather than
  silently dropped.

- **Roles decide what a session may do.** Six built-in roles from a share-link
  `GUEST` up to `OWNER`, twelve permissions, and an endpoint that declares
  neither the permission it needs nor an explicit exemption fails the build.

- **`/livez` and `/readyz`**, on a management listener bound to the internal
  segment. Liveness consults nothing external, so a database outage does not
  become a restart loop; readiness consults the database *and* the schema
  version, so an instance whose migration was skipped never takes traffic.
- **Secrets are read from the files the deployment mounts**, named by
  `HOMEINV_<NAME>_FILE`. A missing one aborts startup; there is no generated
  default.
- **A configuration overview at startup**, with every credential-shaped key
  masked to its length.

- **One command brings the stack up.** `deploy/setup.sh` checks the host
  prerequisites a rootless deployment needs and refuses to continue without them,
  generates every secret the service matrix declares — random bytes, key pairs
  and certificates, each according to its declared kind — and renders the
  configuration files that have to live *inside* a container.

- The architecture as 14 arc42-oriented chapters, covering the building blocks,
  the runtime and deployment views, the data model, the API contract, the plugin
  system, identification and labels, offline synchronisation, security and
  operations.
- A requirements catalogue with 427 numbered, testable requirements across
  functional, non-functional, security and privacy areas, assigned to four
  delivery stages.
- 68 architecture decision records, each with its alternatives and consequences —
  including the ones that shape everything else: a modular monolith rather than
  microservices, row-level security as a second line of defence, rootless as the
  only supported way to run it, and a plugin runtime that keeps third-party code
  in its own process.
- The design system, in [`design-system/`](design-system/), and it is **binding**:
  one token set driving both the web client and the apps, dark as the default
  appearance everywhere with light one click away, 47 component modules, self-hosted
  IBM Plex and Lucide icons, and a measured contrast ratio for every colour pair
  in both themes.
- **A gate that keeps repeated numbers honest.** Counts and identifiers get written into
  several documents at once — the architecture chapters, the requirements catalogue, the
  changelog, the published website — and nothing compared them. Four were already wrong,
  including a decision count that went out on the front page one day after it changed. The
  numbers now have one source that is recomputed from the repository, superseded spellings
  cannot come back, and both are checked rather than trusted.
- **Two registries that clients can program against**, alongside the label
  catalogue: the stable error codes an API response can carry, and the tokens that
  say a result was served in a degraded mode. Both were referred to as documented
  sets that did not exist.
- A project website for GitHub Pages ([`website/`](website/)): five pre-rendered
  pages that work with JavaScript switched off, dark by default, and loading
  nothing from any host but their own — which a CI gate checks rather than
  assumes.
- A verified starter catalogue of label geometries
  ([`docs/reference/label-media.yaml`](docs/reference/label-media.yaml)), with a
  per-format flag distinguishing measured geometries from calculated ones.
- Project groundwork: licences, contribution and security policies, a code of
  conduct, issue and pull request templates, and the directory layout the
  implementation will fill.

### Changed

- **Every database, cache and broker now needs a password.** Only PostgreSQL asked for
  one; the cache holding your sessions, the message broker and the search index did not,
  and the web server — the part reachable from the internet — could talk to all of them
  directly. It now reaches the application and nothing else. Alongside that, the message
  broker could not have started at all without a login.
- **A crash now costs at most fifteen minutes of work, as promised.** The recovery target
  said fifteen minutes and the backup was a nightly dump, so the real answer was up to a
  day. Continuous write-ahead archiving closes the gap, and the weekly restore rehearsal
  now recovers to a moment between two backups rather than to the last backup.
- **Deleting old audit entries no longer looks like tampering.** Retention periods are
  set per tenant, and honouring one would have broken the tamper-evidence chain the audit
  log is built on — so the system would have raised an alarm about itself, daily. A
  deletion is now recorded as what it is, and the chain stays verifiable either side of it.
- **Searching German finds German.** The offline and small-installation search indexed
  words literally, so *Bohrmaschinen* did not find *Bohrmaschine*. It now understands
  German and English word forms.
- **Trashed items disappear from filters and reports immediately.** They kept answering
  searches over custom fields until they were finally removed, which for the default
  retention period is thirty days.
- **Scanning on iPhone and iPad was described wrongly.** Safari has no built-in barcode
  reader, so those devices always used the bundled decoder. It is now treated as the main
  path there, with its own speed target, rather than as a fallback nobody measured.
- **The design system speaks English and is translatable.** Fourteen components had
  German button and screen-reader labels baked into them, which no translation could
  reach; every one is now supplied by the application. Five recorded colour-contrast
  figures were wrong, and the rules meant to block off-system colours and spacing only
  warned instead of failing.
- **Photos and documents now have somewhere to live.** The default media store —
  the only one a small installation has — had no volume anywhere in the
  deployment and no backup entry, so in that configuration uploads had nowhere to
  be written and nothing to be restored from. It is now a small service of its
  own, which also keeps the application containers stateless.
- **Full-text search promises what it can deliver at each stage.** The first
  release searches names and descriptions; notes, custom field values, tags and
  storage paths join it with the next one, in both the fast index and the
  fallback — so a small installation without the search server is not left behind.
- **Anyone allowed to see licence keys and similar secrets now needs a second
  factor.** Re-confirming with a second factor was already required before such a
  field is revealed, but only administrators were obliged to have one.
- **The browser is no longer asked to commit your whole domain.** The strict
  transport header ships without the `preload` flag: the protection it actually
  provides stays, while enrolling a domain in the browsers' permanent preload
  list — slow to undo, and binding on every other service you run under the same
  domain — becomes a step you take deliberately.
- **Storing a licence key has a defined format.** The encryption was decided; the
  exact bytes were not, and they cannot be changed once the first secret is
  written. Key rotation no longer means rewriting stored values.
- **Requests now pass through the web container without losing anything.** Size
  limits, timeouts, live-update streaming and the visitor's real IP address are
  specified across that hop — the last of which decides whether rate limiting and
  the audit log record the visitor or the server in front of them.
- **Dymo label geometries stay marked unverified, and now say why.** Dymo does not
  publish printable areas per label the way Brother does; the printer reads them
  from the roll. For those labels the calibration sheet is the path, and the
  software says so instead of implying a missing lookup.
- **The first release's item now has one description instead of four.** What
  fields a newly created item carries was written down differently in the
  requirements, the roadmap and the data model; notes, purchase details and
  condition arrive with the second release, where they always belonged.
- **Every connection leaving the deployment is now made by a plugin.** Object
  storage on S3 or Nextcloud, outgoing mail, federated login, webhooks and push
  are no longer part of the core image. An installation using local file storage
  and no mail contacts nothing outside itself. The plugin runtime therefore
  arrives in stage 1 rather than stage 3.
- **The printed code is longer**: 10 characters plus a check symbol instead of 8
  plus one. At a million labels the old length produced a name clash roughly a
  third of the time, and printed labels cannot be recalled.
- **Uploads are scanned and served safely from the first release**, not from the
  second. Stage 0 already accepts documents, so it already needs both.
- **The malware scanner can now actually update its signatures.** It was
  mandatory and fail-closed from the first release while sitting on a network with
  no way out, so it would have run forever on whatever signature set its image was
  built with. The outbound proxy now runs in every setup, including the smallest
  one, carrying a single fixed entry for the signature mirror.
- **Each plugin now runs on its own network.** They shared one before, which let
  any plugin read the server's internal metrics, stop the virus scanner — and with
  it every upload in the system — and use another plugin's outbound route. None of
  that needed a permission the operator had granted.
- **Labels need about 15 mm, not 10.** The QR code carries the item's UUID as well
  as its short code, so it is larger than the earlier figure assumed. The preview
  now computes the minimum label size from the actual code content and warns
  before a sheet is printed that will not scan.
- **A degraded search now says so in the response body** rather than in an HTTP
  header that was withdrawn from the specification in 2022 and that clients are
  told to ignore.
- **Database migrations run as their own step, once, and then exit.** The account
  that owns the tables is no longer mounted inside the long-running server, so a
  break-in there cannot reach it. The server checks that the schema matches before
  it accepts traffic, instead of changing it.
- **One browser security header is deliberately gone.** It protected against a
  class of attack this system has nothing to lose to, and its cost was that images
  and plugin panels could silently fail to load — including for plugin authors who
  had no way to know why.
- **The deployment now publishes one port instead of two**, and the application
  server publishes none at all: requests reach it through the web container. This
  came out of testing an assumption that turned out to be wrong — a container on a
  fully isolated network cannot accept an incoming connection, which would have
  made the first installation unreachable.
- **Label templates know how much of a label can actually be printed.** A Brother
  "29 × 90 mm" label offers 25.9 × 83.9 mm, and the print head is not centred on
  the narrow rolls. Both figures now come from the manufacturer's own reference, and
  the two Dymo formats are marked unverified until theirs do too.

### Fixed

- `item_attr_index` had no column for the unit belonging to a number. A `money`
  or `quantity` attribute would have lost its currency or unit in the projection,
  and a total would have silently added EUR to USD. A `unit_value` column was
  added, and every aggregation now groups by it.
- The documentation claimed in seven places that the core never connects to the
  outside world, while five shipped features did exactly that. The claim is now
  true and is verified by a test rather than asserted.
- A foreign-key check in PostgreSQL ignores row-level security, so a record could
  have been made to point at another tenant's data despite the isolation rules.
  Every reference between records now carries the tenant.
- Offline synchronisation sent a device everything in its tenant, filtered only by
  tenant — not by what that user is allowed to see, and including fields marked
  sensitive. Both are now filtered per user.
- The content security policy blocked three of the features it was written to
  protect: plugin panels could not load, offline image caching could not run, and
  the camera fallback scanner could not start.
- The change log table could not be created at all: its primary key did not
  include the column it was partitioned by.
- The deployment matrix described a stack that could not start — every database
  and message broker image was pinned to a user ID that image does not have.
- Files identical across two tenants were stored once. Knowing a file's checksum
  was enough to attach it, and to inherit the other tenant's malware verdict.

[Unreleased]: https://github.com/greluc/Home-Inventory/commits/main
