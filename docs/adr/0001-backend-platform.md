# ADR-0001 — Java 25 and Spring Boot 4 as the backend platform

**Status:** Accepted · **Date:** 2026-09-11

## Context

The backend has to be maintained by one person over years, enforce strict module
boundaries mechanically, carry a plugin system with two runtimes, meet high
security requirements, and talk to PostgreSQL, OpenSearch, RabbitMQ and gRPC.

## Options

| Option | For | Against |
|---|---|---|
| **Java 25 + Spring Boot 4** | Familiar to the developer · Spring Modulith makes module boundaries machine-verifiable and brings the transactional outbox with it · the most mature security and OAuth2 ecosystem on the JVM · virtual threads · isolated classloaders · JPA, jOOQ, Testcontainers, ArchUnit all first-rate | Around 400 MB RAM per instance · a few seconds of startup time · **no effective sandboxing** for in-process plugins since the `SecurityManager` was removed |
| Kotlin + Spring Boot | The same ecosystem, more compact, shares a language with the KMP apps | An additional language on the server side without a compelling benefit; some Spring tooling stays Java-centric |
| Go | Very lean containers, a single binary, fast startup | Plugins only make sense out-of-process · a weaker ecosystem for persistence, JSON Schema and OAuth2 · less familiar |
| Python + Django/FastAPI | Fast development, InvenTree as a model, strong libraries for barcodes and image processing | Weaker type safety and long-term maintainability for large codebases · barely any tooling to enforce module boundaries mechanically |

## Decision

**Java 25 (LTS) with Spring Boot 4, Spring Modulith, Gradle 9 (Kotlin DSL) and a
version catalog.**

## Rationale

Two quality goals decide it: **Q2 modularity** and **Q4 maintainability**. Spring
Modulith is the only widely used way to enforce module boundaries mechanically
inside a monolith while getting events with a transactional outbox along the way
— exactly the two things this design rests on. Resource consumption is
immaterial at ≥ 8 GB RAM and was explicitly classified as a subordinate goal.

## Consequences

- Java 25 is supported until at least 2028; moving to the next LTS is planned and
  is not a break.
- All dependency versions live in the version catalog
  (`gradle/libs.versions.toml`), not in `build.gradle.kts`.
- **In-process plugins cannot be sandboxed.** That is the direct reason for
  [ADR-0006](0006-plugin-runtime.md), and it is a property of the platform, not
  an oversight.
- Startup time is immaterial for single-host operation; with frequent scaling
  under Kubernetes, AppCDS is used.
- Production image: distroless with a JRE 25, no full JDK.
