# Milestone 1B completion report

Date: 2026-10-24 (America/Chicago). Spatial Service 0.2.0; proposed independent Contracts 0.3.0; Architecture 0.3.0. Work stops at Milestone 1B. No commits, pushes, remote repositories, cloud resources or Milestone 1C functionality were created.

## 1. Source files created or modified

The detailed inventory below covers service implementation, contract snapshots, sample fixtures, deployment and architecture updates. The original Application.java was renamed SpatialServiceApplication.java to avoid the Windows case-insensitive class/package collision with application/. Maven Wrapper and V1 migration remain unchanged. Estate Service and Infrastructure source files were not modified.

See the file inventory at the end of this report. Build outputs, logs, runtime response exports and generated event envelopes remain ignored under target/ or *.log. The small SIMULATED fixtures are explicitly requested source examples, not production data.

## 2. Database tables and indexes

V1 is preserved byte-for-byte: processed_event and outbox_event. V2 adds reference_entity, spatial_feature, spatial_feature_version, spatial_layer, digital_twin_asset and spatial_import_job; it extends outbox retry/delivery state and preserves already-published records.

| Table | Ownership/integrity |
|---|---|
| reference_entity | Minimal canonical IDs, parent/estate membership, lifecycle and revision; inferred division marker |
| spatial_feature | Canonical geometry(Geometry,4326), revision, provenance, derived spheroidal area; one active feature per entity |
| spatial_feature_version | Historical geometry/snapshot, SUPERSEDED status and effective validity times; feature/version primary key |
| spatial_layer | Estate-scoped layer codes, URI/format metadata and optimistic version |
| digital_twin_asset | Estate-scoped codes; optional Point and composite same-estate feature FK |
| spatial_import_job | Durable atomic import state and validation report |
| outbox_event | Domain-transaction event envelope, bounded attempts and publication state |
| processed_event | Consumer/event unique idempotency marker, committed with projection |

Explicit indexes: reference_estate_idx; feature_active_entity_uq (partial unique WHERE ACTIVE); feature_geometry_gist; feature_estate_reference_idx; feature_version_geometry_gist; layer_estate_idx; asset_position_gist; asset_estate_idx; import_estate_idx; outbox_due_idx; retained outbox_unpublished. Primary/unique indexes additionally enforce feature IDs/codes, layer/asset estate codes, reference identity and history versions. Geometry checks enforce SRID/type/dimensions/validity/ranges. Feature-to-reference, history-to-feature and asset-to-feature foreign keys exist only inside this database. There are no Estate Service database foreign keys, queries or persistence dependencies.

## 3. REST endpoints

All 22 operations are under /api/v1/spatial and documented with typed schemas in the pinned and authenticated runtime OpenAPI.

| Group | Operations |
|---|---|
| Features | POST /features; GET/PUT /features/{id}; GET /estates/{estateId}/features; GET /estates/{estateId}/boundaries |
| Blocks | GET /blocks/{blockId}/geometry; GET /blocks/{blockId}/history |
| Queries | POST /query/point; POST /query/intersects; POST /query/within; GET /estates/{estateId}/bbox |
| Layers | POST /layers; GET/PUT /layers/{id}; GET /estates/{estateId}/layers |
| Assets | POST /assets; GET /assets/{id}; GET /estates/{estateId}/assets |
| Import/export | POST /imports/geojson; GET /imports/{jobId}; GET /estates/{estateId}/export/geojson |
| CRS | POST /transform (explicit 2D Point EPSG:3857/4326 to 4326) |

Collections/export are paginated with size <=100 and stable ordering. Queries require bounded bbox and limit <=100 and report truncation. Errors are RFC 9457 Problem Details with UUID correlation IDs. Geometry and layer revisions reject stale PUTs. Inline block history is capped at 1000 versions with an explicit 422 rather than silent truncation.

## 4. GIS functionality

JTS validates Polygon, MultiPolygon, Point and LineString without repair. It rejects malformed/unclosed rings, self-intersections, invalid holes, empty/degenerate inputs, unsupported CRS, altitude/nonfinite/out-of-range coordinates and oversized coordinate sets. PostGIS stores 4326 geometry, provides envelope/centroid and calculates hectares with ST_Area(geometry::geography)/10000. Raw degree-based area is never used as hectares. JTS planar area is used only to detect degenerate polygons.

Point queries use ST_Covers (including boundaries); intersection uses ST_Intersects; within returns stored geometry covered by the supplied polygon. GiST envelope prefilters bound queries. Active block overlaps are compared in geography hectares with configurable tolerance. Available polygon parents cover children; parent changes cannot strand existing child features. Per-estate row locks serialize those checks, and conditional geometryVersion updates reject concurrent overwrites. Old snapshots remain accessible through block history; no operational/tapping/latex records are read or changed.

GeoJSON import is a bounded JSON FeatureCollection wrapper, atomically validated/persisted in the calling request with a durable job/report. No partial features/events survive failed imports. Export preserves feature/reference identifiers, geometry versions, provenance, bbox, centroid and calculated hectares. 3D Tiles metadata is supported through layers; no renderer, client or terrain engine is implemented.

## 5. Sample GeoJSON dataset

examples/synthetic-estate.geojson contains one estate, two divisions and ten nonoverlapping blocks within the estate near longitude 80E, latitude 6N. Every item is SIMULATED; it is not a real operating estate or survey. Division halves align with the estate, and blocks leave small internal gaps. The first estate/division/block IDs match existing contracts fixtures; eleven full v2 synthetic snapshots supply all required estate/block membership without a deployed Estate Service.

examples/synthetic-roads.geojson provides two access-road centerlines. examples/synthetic-assets.json provides two road markers, one latex collection facility (FACTORY), three synthetic sensors and one illustrative TREE_MODEL. HTTPS .invalid asset URLs are metadata placeholders; no real 3D models or surveyed tree positions are claimed. PostGIS calculates area at import. The verified first block area is 18.80587908 hectares; its bbox starts [80.0002,6.0002]. Actual calculated sample output is generated under target/api-contract-samples/export.json.

## 6. Events produced and consumed

Consumed on durable agrosphere.domain topic bindings: EstateCreated, EstateUpdated, PlantationBlockCreated, PlantationBlockUpdated, all version 2. Full pinned JSON Schema and format checks run before mutation; envelope/payload IDs and routing keys must agree. Consumer idempotency and projection share a transaction. Newer entity revision wins, including update-before-create; stale/duplicate delivery cannot overwrite newer state. Three bounded retries end in spatial.estate.v2.dead via spatial.dead; ACK follows committed effects.

Produced: SpatialFeatureCreated, SpatialFeatureUpdated, SpatialBoundaryChanged, SpatialLayerCreated and DigitalTwinAssetRegistered, all version 1 on agrosphere.<EventType>.v1. Envelopes contain eventId, type/version, occurredAt, correlationId, sourceService, estateId, payload and referenceEntityId/geometryVersion where applicable. They carry feature/layer/asset references instead of geometry blobs. Outbox and domain changes are atomic. Mandatory persistent publication, confirms, SKIP LOCKED, backoff and retained FAILED records provide bounded at-least-once delivery; downstream deduplication is required. README documents reviewed replay procedures.

## 7. Contract changes and discrepancies

Contracts 0.3.0 adds spatial OpenAPI and five event schemas/examples under events/spatial/v1; all foundation events/v1 and estate events/v2 schemas are preserved. The service carries pinned copies and builds independently. Runtime OpenAPI is customized from that same contract snapshot. Contract validators cover actual HTTP bodies/events, sample import requests and runtime OpenAPI.

Preparation found these differences: foundation pom/ADR-0005 use Java 25, while the explicit request asks for Java 21; Spatial targets 21 and was fully verified on 21. ADR-0008 says Java21 although the working Estate pom/README actually use Java25; Estate was left unchanged. M1A is available and publishes v2, so Spatial consumes existing v2 contracts rather than incompatible foundation v1 events. M1A emits no division lifecycle events: division membership is inferred from block snapshots and marked inferred, with authoritative division lifecycle deferred to a coordinated future release. Infrastructure already provisions the service-owned PostGIS database; no central infrastructure changes were needed. ADR-0009 and MILESTONE_1B.md record these decisions.

## 8. Security controls

RS256 signature, trusted issuer, audience and lifetime validation; administrator-managed realm roles and estate_ids; PLATFORM_ADMIN global access; ESTATE_ADMIN/MANAGER write assigned estates; AGRONOMIST/VIEWER read assigned estates. Imports require write scope. ID-based reads check persisted ownership; client estate IDs grant no access. Asset relationships enforce same-estate ownership. All business/docs endpoints require authentication; only health/probes are public.

Request bytes are bounded for Content-Length and streamed bodies, geometry coordinates are bounded, uploads accept simple filenames and JSON instead of filesystem paths, and URIs accept absolute HTTPS without credentials/fragments. The service never fetches asset URIs. Safe Problem Details hide stack traces/paths. Audit logs include actor, estate, feature, revision and correlation ID. TREE_MODEL requires SIMULATED provenance. The runtime uses nonroot UID/GID 10001:10001.

## 9. Tests executed and actual results

| Check | Actual result |
|---|---|
| Original scaffold Maven verify | PASS: 4 security tests before implementation |
| Java 21 full Maven verify -Pintegration inside Docker | PASS: 19 unit/security + 12 integration tests; zero failures/errors/skips |
| Unit suites | GeometryValidationTest 8; ApplicationRulesTest 7; SecurityPolicyTest 4 |
| Integration | Real PostGIS 17/3.6, RabbitMQ 4.3.6, HTTP and RSA-signed local OIDC; independent of Estate Service |
| Spatial cases | Flyway/GiST; all four geometry types; spheroidal area/minimum rule; bbox; point/intersection/containment; import/export; invalid geometry; duplicate active constraint; overlap; history; concurrent updates |
| Security cases | Anonymous denial, estate scope/read-write/import separation, AGRONOMIST read, wrong audience/issuer/signature, expired tokens, body limit |
| Messaging cases | Atomic domain/outbox rollback; confirms; duplicate delivery; retry/FAILED state; actual broker consumption; schema rejection; stale snapshots; DLQ |
| Contract validators | PASS: existing v1/v2 compatibility; five new spatial schemas/examples/negative cases; actual spatial envelopes and seven HTTP response shapes; sample import; runtime OpenAPI |
| Architecture full workspace audit | PASS: nine ADRs, twelve separate Git roots, no parent Git/submodules/remotes, workflow/YAML/ignore/heuristic secret checks |
| Compose overlay config --quiet | PASS using placeholder configuration; shared services were not started |
| Docker build | PASS: Java 21 builder executes 19 unit tests; independent nonroot image agrosphere-spatial-service:0.2.0 |
| Packaged image smoke | PASS: actual Java 21 runtime starts as 10001:10001, migrates PostGIS, passes readiness/liveness and returns Problem Details 401 for unauthenticated business access; disposable resources cleaned |

Local Windows default Java is 27. Initial host verification passed, but the authoritative final suite ran on actual Java 21 in a Linux Maven container with disposable Testcontainers resources. Standard local commands are in README; the Java 21 container verification used the service directory bind mount, Docker socket, TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal and mvn verify -Pintegration. Validation dependencies were available in the existing contracts virtual environment; system Python lacked openapi-spec-validator, so that installed environment was used successfully. Logs are ignored: java21-integration.log, docker-build.log, image-smoke-final.log; Maven reports and serialized contract evidence are under target/.

Early discovered failures were corrected: Windows Application/application name collision; outdated architecture ADR-count assertion; empty OIDC JWKS in the image smoke fixture. A generated public-only RSA fixture now supports smoke discovery; no private key is stored.

## 10. Docker and local run commands

~~~powershell
.\mvnw.cmd verify -Pintegration
python -m pip install -r requirements-test.txt
python scripts/validate_contracts.py
docker build -t agrosphere-spatial-service:0.2.0 .
python scripts/smoke_image.py
# After exporting private DB/broker values and trusted OIDC configuration:
.\mvnw.cmd spring-boot:run
# Or, with container-reachable addresses in a private .env:
docker run --rm --env-file .env -p 127.0.0.1:8082:8082 agrosphere-spatial-service:0.2.0
~~~

From agrosphere-infrastructure, after configuring private values and identity:

~~~powershell
docker compose --profile identity up -d postgres rabbitmq keycloak
docker compose -f compose.yaml -f ../agrosphere-spatial-service/deploy/compose.spatial.yaml up -d --build postgres rabbitmq spatial
~~~

Enable ESTATE_CONSUMER_ENABLED and OUTBOX_PUBLICATION_ENABLED after configuring broker topology/consumers. Supply the exact advertised OIDC issuer reachable by clients and service. README documents all variables, trusted claims, projection replay, import/export semantics and operational recovery.

## 11. Known limitations and unvalidated deployment items

Production Keycloak realm/client/claim provisioning and startup against the user's shared private Compose stack were not attempted; private identity/credentials and operator configuration are required. Tests use disposable infrastructure and a local OIDC fixture, not production Keycloak. Azure, Kubernetes, actual Cesium/Unreal/mobile clients and external asset availability are outside scope.

Division lifecycle cannot be authoritative until Estate contracts expose it; existing geometry after lifecycle/membership changes needs reconciliation. Imports are synchronous, atomic and capped at 100 features/2 MiB by default; no CSV endpoint, background job worker, geometry repair, altitude/vertical datum, antimeridian workflow or general projected-polygon transformation is included. Explicit CRS conversion supports points in 4326/3857 only. History has a documented inline cap, exports require paging, and queries can be truncated by explicit limit. Retention, alerting, DLQ operator permissions, reconciliation scheduling, publisher lock-timeout sizing, image digest pinning and performance/load characterization remain deployment hardening tasks. Synthetic asset URLs intentionally do not resolve.

## 12. Recommended Milestone 1C integration tasks (not started)

Coordinate review/release of contracts 0.3.0; provision trusted Keycloak audience/roles/estate scope; replay estate v2 snapshots and reconcile division membership/lifecycle; connect gateway and future clients to stable feature/reference IDs and paged GeoJSON; bind/deduplicate spatial events; establish monitored outbox/DLQ replay and retention; exercise shared-stack backup/restore and realistic spatial workloads. Any division-event or general CRS expansion needs an explicit follow-up contract/architecture decision. No automatic continuation is performed.

## Detailed file inventory

### agrosphere-spatial-service

Created:

- deploy/compose.spatial.yaml
- examples/README.md
- examples/synthetic-assets.json
- examples/synthetic-estate-events.json
- examples/synthetic-estate.geojson
- examples/synthetic-roads.geojson
- requirements-test.txt
- scripts/smoke_image.py
- scripts/validate_contracts.py
- src/main/java/com/agrosphere/spatial/adapter/in/messaging/EstateListener.java
- src/main/java/com/agrosphere/spatial/adapter/in/messaging/EstateProjection.java
- src/main/java/com/agrosphere/spatial/adapter/in/web/ApiErrors.java
- src/main/java/com/agrosphere/spatial/adapter/in/web/BodyLimitFilter.java
- src/main/java/com/agrosphere/spatial/adapter/in/web/CorrelationFilter.java
- src/main/java/com/agrosphere/spatial/adapter/in/web/OpenApiConfiguration.java
- src/main/java/com/agrosphere/spatial/adapter/in/web/SecurityProblem.java
- src/main/java/com/agrosphere/spatial/adapter/in/web/SpatialController.java
- src/main/java/com/agrosphere/spatial/adapter/out/messaging/JdbcEventOutbox.java
- src/main/java/com/agrosphere/spatial/adapter/out/messaging/MessagingConfiguration.java
- src/main/java/com/agrosphere/spatial/adapter/out/messaging/OutboxDispatcher.java
- src/main/java/com/agrosphere/spatial/adapter/out/persistence/JdbcSpatialStore.java
- src/main/java/com/agrosphere/spatial/adapter/out/security/JwtEstateAccess.java
- src/main/java/com/agrosphere/spatial/adapter/out/security/JwtValidationConfiguration.java
- src/main/java/com/agrosphere/spatial/application/CorrelationContext.java
- src/main/java/com/agrosphere/spatial/application/GeoJsonImports.java
- src/main/java/com/agrosphere/spatial/application/port/out/EstateAccess.java
- src/main/java/com/agrosphere/spatial/application/port/out/EventOutbox.java
- src/main/java/com/agrosphere/spatial/application/port/out/SpatialStore.java
- src/main/java/com/agrosphere/spatial/application/Requests.java
- src/main/java/com/agrosphere/spatial/application/SpatialService.java
- src/main/java/com/agrosphere/spatial/domain/GeometryValidator.java
- src/main/java/com/agrosphere/spatial/domain/SpatialException.java
- src/main/java/com/agrosphere/spatial/domain/SpatialModels.java
- src/main/java/com/agrosphere/spatial/SpatialServiceApplication.java
- src/main/resources/contracts/events/spatial/v1/DigitalTwinAssetRegistered.schema.json
- src/main/resources/contracts/events/spatial/v1/SpatialBoundaryChanged.schema.json
- src/main/resources/contracts/events/spatial/v1/SpatialFeatureCreated.schema.json
- src/main/resources/contracts/events/spatial/v1/SpatialFeatureUpdated.schema.json
- src/main/resources/contracts/events/spatial/v1/SpatialLayerCreated.schema.json
- src/main/resources/contracts/events/v2/EstateCreated.schema.json
- src/main/resources/contracts/events/v2/EstateUpdated.schema.json
- src/main/resources/contracts/events/v2/PlantationBlockCreated.schema.json
- src/main/resources/contracts/events/v2/PlantationBlockUpdated.schema.json
- src/main/resources/contracts/spatial-service.openapi.json
- src/main/resources/db/migration/V2__spatial_twin.sql
- src/test/java/com/agrosphere/spatial/ApplicationRulesTest.java
- src/test/java/com/agrosphere/spatial/GeometryValidationTest.java
- src/test/java/com/agrosphere/spatial/SpatialServiceIT.java
- src/test/resources/oidc-public-jwk.json
- src/test/resources/postgis.sql
- docs/MILESTONE_1B_REPORT.md

Modified:

- AGENTS.md
- README.md
- CHANGELOG.md
- VERSION
- pom.xml
- Dockerfile
- .env.example
- .github/workflows/ci.yml
- src/main/resources/application.yaml
- src/main/java/com/agrosphere/spatial/SecurityConfiguration.java
- src/main/java/com/agrosphere/spatial/adapter/in/web/package-info.java
- src/main/java/com/agrosphere/spatial/adapter/in/messaging/package-info.java
- src/main/java/com/agrosphere/spatial/adapter/out/persistence/package-info.java

### agrosphere-contracts

Created:

- events/spatial/v1/DigitalTwinAssetRegistered.schema.json
- events/spatial/v1/SpatialBoundaryChanged.schema.json
- events/spatial/v1/SpatialFeatureCreated.schema.json
- events/spatial/v1/SpatialFeatureUpdated.schema.json
- events/spatial/v1/SpatialLayerCreated.schema.json
- examples/spatial/v1/DigitalTwinAssetRegistered.json
- examples/spatial/v1/SpatialBoundaryChanged.json
- examples/spatial/v1/SpatialFeatureCreated.json
- examples/spatial/v1/SpatialFeatureUpdated.json
- examples/spatial/v1/SpatialLayerCreated.json

Modified:

- AGENTS.md
- README.md
- CHANGELOG.md
- VERSION
- scripts/validate.py
- openapi/spatial-service.yaml

### agrosphere-architecture

Created:

- docs/adr/0009-spatial-digital-twin.md
- docs/roadmap/MILESTONE_1B.md

Modified:

- AGENTS.md
- README.md
- VERSION
- CHANGELOG.md
- docs/roadmap/MILESTONES.md
- scripts/validate.py

Removed/renamed: src/main/java/com/agrosphere/spatial/Application.java → SpatialServiceApplication.java. No Estate Service or Infrastructure files were modified.
