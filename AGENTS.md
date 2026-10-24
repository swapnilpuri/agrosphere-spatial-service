# Repository instructions

Work within this independent Git repository. No parent repository, submodules, shared persistence models, cross-service SQL or multi-service Maven parent.

Current scope: Milestone 1B Spatial plus requested Milestone 1C integration patches only; preserve GIS semantics, event projection, contracts and persistence ownership.

Use ports/adapters and service-owned Flyway migrations. Java 21, Spring Boot 4.0.9, Maven Wrapper 3.9.12 and JUnit 5.14.4. Manage Spring integration contexts explicitly; do not use SpringExtension under this baseline. Advanced PostGIS SQL is parameterized and uses the local datasource only.

Versioned contracts are pinned under src/main/resources/contracts from contracts release 0.3.0. Coordinate schema changes through an independent contract release; never add sibling source dependencies. Never modify Estate Service internals or duplicate its business attributes. Canonical entity UUIDs remain stable across geometry versions.

GeoJSON is RFC 7946 WGS84 longitude/latitude. Explicitly label REAL, SIMULATED, ESTIMATED or PREDICTED. Procedural tree models are illustrative. The requested small synthetic examples are permitted; do not commit credentials, private environment files, generated build/test artifacts or large GIS assets.

Run ./mvnw verify, ./mvnw verify -Pintegration, python scripts/validate_contracts.py after integration, and docker build as documented in README.md. Report actual checks separately from unavailable checks. Version independently using SemVer and CHANGELOG.md. No remote creation, push or cloud deployment is authorized.
