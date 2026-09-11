# plugin-sdk/

What a plugin author gets so that writing a plugin is an afternoon, not a week.

**Licence: Apache-2.0** — same reasoning as [`plugin-api/`](../plugin-api/).

## Contents

```
plugin-sdk/
├── java/        gRPC server scaffolding, manifest validation, health endpoint,
│                logging, test helpers
├── python/      the same for Python — because metadata and device integrations
│                frequently already exist there
└── testkit/     the contract test suite
```

## The test suite is the important part

`homeinv-plugin-testkit` is a suite that **every** port implementation must pass:
correct error handling, deadlines honoured, idempotency, and the right behaviour
when a capability has not been granted.

It exists because of a specific failure mode: a plugin that misbehaves looks, to
the person using it, like a broken core. The suite is also the single technical
bar for getting listed in [`PLUGINS.md`](../PLUGINS.md).

## The intended workflow

```bash
homeinv-plugin init --port MetadataResolver --lang java --id com.example.mpn
# implement exactly three methods
./gradlew pluginContractTest
homeinv-plugin dev --core https://localhost:8443 --tenant dev
docker build -t example/mpn:1.0.0 . && cosign sign example/mpn:1.0.0
```

Note what is absent: nothing about getting permission. A plugin needs no
blessing from this project to exist. It needs an operator who installs it and a
tenant administrator who grants it capabilities
([09 Extensibility](../docs/architecture/09-extensibility-and-plugins.md)).

## Status

Empty. Stage 3.
