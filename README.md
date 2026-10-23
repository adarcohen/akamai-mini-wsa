# Mini WSA

A Java/Spring Boot security analytics backend, built incrementally for the Akamai assignment.

## Current milestone: statistics summary

Implemented: event model, single/batch ingestion, schema validation, server-assigned
`receivedAt`, PostgreSQL persistence, atomic duplicate handling, attack classification
and threat scoring, including concurrent repeat-offender detection, plus the statistics summary API.

The samples API and data generation are subsequent milestones. The optional full-stack Docker setup will follow; Compose currently runs
PostgreSQL while the application runs locally.

## Requirements

- JDK 17 or newer.
- Docker Desktop running, for the development database and integration tests.
- Network access on the first build for Maven dependencies and Docker images.

The Maven Wrapper is included; a separate Maven installation is unnecessary.

## Run locally with PostgreSQL in Docker

From the repository root:

```bash
docker compose up -d --wait db
./mvnw spring-boot:run
```

Stop an older application instance with `Ctrl+C` before restarting it with the new code.
The application starts on port 8080 and Flyway creates the schema automatically.
The root path `/` has no endpoint and returns 404.

In a second terminal, submit an event:

```bash
curl -i -X POST http://localhost:8080/v1/events/ingest \
  -H 'Content-Type: application/json' --data-binary @examples/event.json
```

The first request returns 201. Repeating it returns 409 because `eventId` is already
stored. Change `eventId` to ingest another event.

Inspect the stored records directly until the samples API is implemented:

```bash
docker compose exec db psql -U miniwsa -d miniwsa \
  -c 'SELECT event_id, event_time, received_at FROM security_events;'
```

The named volume preserves data across app/database restarts. `docker compose down`
stops the database and keeps its data. `docker compose down -v` also deletes its data;
use that only when intentionally resetting your development database.

### Configuration

The app uses `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`, defaulting to
`jdbc:postgresql://localhost:5432/miniwsa`, `miniwsa` and `miniwsa` respectively.
The Compose database binds only to `127.0.0.1:5432` and uses development credentials.

`.env.example` documents the Compose password setting. Compose automatically reads
`.env`; Spring Boot does not automatically load it. If you change the development
password, also export `DB_PASSWORD` in the application's terminal. Changing the variable
after a database volume has been initialized does not change the existing database password.

An already installed local PostgreSQL server can also be used without Docker: create a
`miniwsa` database owned by your app role, then set the three connection variables above.
Docker is still required for the automated integration suite.

To build and run the JAR:

```bash
./mvnw clean verify
java -jar target/mini-wsa-0.1.0-SNAPSHOT.jar
```

On Windows use `mvnw.cmd` instead of `./mvnw`. Run one instance per port.

## Interactive API documentation

After starting the app, open [Swagger UI](http://localhost:8080/docs).
Expand **Events**, select **POST /v1/events/ingest**, click **Try it out**, choose the single
or batch example, edit the body and click **Execute**. These are real requests that persist
records; use a new `eventId` after each successful submission or expect HTTP 409.

The OpenAPI document is available at `/v3/api-docs`. It describes both request shapes,
required event fields, enum values, success responses and HTTP 400/409/503 errors.

## Ingestion API

`POST /v1/events/ingest` accepts one event object or a nonempty array of up to 1000 events.
`examples/event.json` contains every required field.

Success returns HTTP 201 with `accepted` and an `events` array. Each response event
contains the original validated fields plus `receivedAt`, `attackType` and `threatScore`. The success response is
sent only after the database transaction commits. Events in one request share an
arrival timestamp, truncated to PostgreSQL's microsecond precision.

- Required fields, nested fields, enum values, numeric bounds and string limits are validated.
- Timestamps must be ISO-8601 strings with an offset or `Z`; numeric epochs are rejected.
- Unknown properties and client-supplied server fields are rejected.
- IPv4/IPv6 literals are validated without DNS resolution. Equivalent IPv6 addresses
  are stored in PostgreSQL's canonical form, while the original spelling stays in the payload.
- Any invalid event returns 400 with field details and no events from the request are written.
- Duplicate IDs within a submitted batch return 400.
- An ID already stored returns 409, rolling back the entire batch without overwriting existing data.
- Concurrent requests sharing an ID can create only one record, enforced by the primary key.
- Temporary database unavailability returns 503 without SQL or credentials in the response.

Errors use `application/problem+json`. Validation adds an `errors` array with `field`
and `message`, for example `events[1].rule.severity`. Rejected values are not echoed.

The batch-count limit is checked after JSON parsing. A deployment should also limit
incoming request bytes before parsing. API retries are not silently deduplicated:
a retry after a lost successful response will return 409 instead of counting the event twice.

## Statistics summary API

`GET /v1/stats/summary` requires `from` and `to` as ISO-8601 instants.
`configId` is optional; omit it to aggregate across all configurations.

```bash
curl 'http://localhost:8080/v1/stats/summary?configId=14227&from=2026-05-20T00:00:00Z&to=2026-05-21T00:00:00Z'
```

It is also available under **Statistics** in Swagger UI at `/docs`.

The response contains `configId`, `timeRange`, `totalEvents`, `byCategory` with counts and
average threat scores, `byAction` with counts, `topAttackers` with counts/average scores,
and `topTargetedPaths` with counts. Top lists return at most ten entries each.

- Filters use the original event `timestamp`, not `receivedAt`. Both time bounds are inclusive.
- Equal `from`/`to` is allowed. Reversed ranges, missing/invalid times and invalid configuration IDs return 400.
- Explicit offsets are accepted and normalized to UTC. Encode a literal `+` in URL parameters as `%2B`;
  Swagger handles encoding for you.
- Averages are calculated in SQL using decimal arithmetic and rounded to one decimal place.
- Top lists sort by count descending. Ties use numeric IP ordering or path ordering, respectively.
- Equivalent IPv6 representations are grouped as one attacker.
- Missing categories/actions are omitted. No matches return zero and empty maps/lists, not 404.

All aggregation and top-10 selection execute in PostgreSQL. The application receives only
bounded result groups, rather than loading raw events. Four focused queries keep the SQL
readable; they still scan matching rows, so broad historical queries will need measurement
and potentially rollups as data grows. Separate indexes support `(config_id, event_time)`
and global `event_time` filtering, at the cost of additional work on inserts.

`StatsService` uses a read-only repeatable-read transaction so every query observes the
same snapshot. An event inserted halfway through a summary cannot appear in only some
breakdowns. A concurrent-ingestion integration test verifies this behavior.

## Architecture and file responsibilities

One Maven project and one Git repository. Java package directories are not separate projects.

```text
HTTP request -> EventsController -> IngestionRequestParser (validation)
                              -> IngestionService (transaction + receivedAt)
                              -> EventRepository (SQL batch insert) -> PostgreSQL
```

- `domain/SecurityEvent.java`: immutable event, with nested `Rule`/`GeoLocation` records and validation annotations.
- `domain/Action.java`, `Category.java`, `Severity.java`: allowed enum values.
- `domain/ClientIp.java`: IP literal validation and canonicalization.
- `domain/EnrichedEvent.java`: original event plus classification, score and arrival time.
- `domain/EnrichmentPolicy.java`: pure scoring calculation from event, prior count and arrival time.
- `api/EventsController.java`: ingestion HTTP endpoint; delegates validation and persistence.
- `api/StatsController.java`: summary HTTP endpoint and Swagger parameter examples.
- `domain/SummaryQuery.java`: validated configuration/time criteria.
- `domain/StatsSummary.java`: typed summary result and its related result records.
- `service/StatsService.java`: consistent read transaction and summary assembly.
- `repository/StatsRepository.java`: parameterized aggregation and top-10 queries.
- `api/IngestionRequestParser.java`: normalizes object/array input, parses and validates every event.
- `api/IngestionResponse.java`: the HTTP success envelope.
- `api/InvalidEventsException.java`: structured field validation failures.
- `api/ApiExceptionHandler.java`: maps validation, conflicts and temporary storage failures to HTTP errors.
- `api/IsoInstantDeserializer.java`: requires ISO-8601 timestamp strings.
- `api/IngestionExamples.java`: single and batch request examples for Swagger UI.
- `service/IngestionService.java`: coordinates IP activity, enrichment and the all-or-nothing transaction.
- `repository/EventRepository.java`: parameterized, batched JDBC inserts; contains no HTTP logic.
- `resources/application.yml`: database connection and strict JSON settings.
- `resources/db/migration/V1__create_security_events.sql`: Flyway's original table definition.
- `resources/db/migration/V2__enrich_security_events.sql`: adds enrichment columns, an IP/time index and backfills existing data.
- `resources/db/migration/V3__index_event_time_queries.sql`: configuration/time and global time indexes.
- `MiniWsaApplication.java`: app entry point and injectable UTC clock.
- `compose.yaml`: development PostgreSQL service, health check and persistent volume.
- `.env.example`: example local configuration; `.env` itself is excluded from Git.
- `examples/event.json`: complete request for manual testing.
- `pom.xml`: dependencies, Java version and test/build plugins.
- `mvnw`, `mvnw.cmd`, `.mvn/wrapper/`: standard Maven Wrapper files.
- `.gitignore`: excludes generated builds, IDE files and secrets; `target/` is generated output.

## Why PostgreSQL and JDBC?

PostgreSQL provides durable storage, transactions, a uniqueness constraint and SQL
aggregations in one dependency. Query dimensions are typed columns; the complete
original event is JSONB. This supports future statistics without dropping original fields.
The primary key supplies the uniqueness index. An additional `(client_ip, received_at, event_id)`
index supports bounded repeat-offender lookups. Summary queries add configuration/time and global time indexes.

JDBC keeps the insert and future grouping queries explicit. There are no entity
relationships or lazy-loading requirements here, so JPA/Hibernate would add machinery
without a current need. An ORM is a valid alternative; neither choice alone guarantees
better throughput. SQL values are bound parameters, never concatenated into SQL.

`saveAll` uses a prepared JDBC batch with the PostgreSQL driver's batch-insert rewrite.
The Service's `@Transactional` boundary ensures that any failed insert undoes the whole
request, including when a conflicting event follows a new event. Responses are built
from immutable records, and the repository does not depend on API response classes.

This is a synchronous ingestion baseline, not a claim of big-data throughput. Future
scale improvements should follow measurements: bounded retention/time partitioning,
query indexes, aggregation rollups and queue-based buffering if bursts require it.
Global event-ID uniqueness must be reconsidered explicitly before time partitioning.

## Classification and threat scoring

Category mappings are exactly those in the assignment: injection to SQL/Command Injection,
XSS to Cross-Site Scripting, protocol violation to Protocol Anomaly, data leakage to Data
Exfiltration, BOT to Bot Activity, DOS to Denial of Service and RATE_LIMIT to Rate Limiting.

Scores add:

- Severity: CRITICAL 40, HIGH 30, MEDIUM 20, LOW 10.
- Action: DENY +20, ALERT +10, MONITOR +0.
- Sensitive path: +15 once if the case-sensitive path contains `/admin` or `/login`.
- Repeat offender: +15 on the sixth and later recent arrivals from the same canonical IP.

The result is capped at 100; the maximum reachable under the current weights is 90.
`EnrichmentPolicy` implements this calculation without database access or a clock dependency.

### Time-window decisions

The assignment leaves some counting details implicit. This implementation uses these explicit rules:

- The repeat window uses server arrival time (`receivedAt`), independently of the client's event timestamp.
  Historical logs can therefore still form a current arrival burst. Summary queries use event time.
- The current event is included in the threshold, so five prior arrivals qualify it for the bonus.
- The interval is inclusive: `[receivedAt - 10 minutes, receivedAt]`.
- Counts are global across configurations, keyed by canonical IP. Equivalent IPv6 spellings share counts.
- A batch has one arrival time and is scored in input-array order. Earlier events in that same batch count.
- Scores are assigned once and are not retroactively modified as events expire from the window.

The service locks all involved IPs before reading the clock or activity. PostgreSQL transaction-scoped
advisory locks coordinate multiple app instances, and sorted numeric lock keys avoid inverted lock order
for overlapping batches. A hash collision only serializes unrelated clients; counting still filters full IPs.
Explicit read-committed isolation gives the count query a fresh snapshot after a preceding writer commits.

A single query acquires all locks and another query fetches all prior counts. Each IP lookup stops after
five records, because higher counts cannot change the bonus. Earlier events in the current batch are
counted in a request-local map, then enriched records are inserted using the existing JDBC batch.
A rollback discards both the writes and those temporary counts. Locks release on commit or rollback.

This favors correctness for a hot IP over parallel writes for that same IP. It assumes reasonably
synchronized application clocks; clock-skew policy and throughput measurements remain future work.
Statement/lock timeouts bound database waits.

### Existing v0.1 data

Flyway applies V2 without dropping the V1 table or modifying its original migration. Existing records
receive classification and scores; original payloads and arrival times stay intact. The V1 schema did
not preserve array order for equal arrival timestamps, so the one-time legacy backfill breaks those ties
by `eventId`. New requests always use input-array order. The upgrade is verified against a seeded V1 database.

## Tests

```bash
./mvnw test       # Model validation unit tests; no Docker or running database required
./mvnw verify     # Unit tests + real PostgreSQL/HTTP integration tests; Docker required
```

`SecurityEventValidationTest` checks required fields, boundaries and nested validation.
`EnrichmentPolicyTest` covers all category mappings and the severity/action/path/repeat-threshold matrix.
`EnrichmentMigrationIT` upgrades a seeded V1 database and verifies data preservation and backfilled scores.
`EventsApiIT` sends real HTTP requests to the app on a random port, backed by an isolated
PostgreSQL 17 Testcontainer. It checks persisted payloads/timestamps, batch writes,
validation without writes, duplicate conflicts, rollback, concurrent duplicates,
IP literals and parameterized SQL handling. It also checks the sixth event in a batch, exact
window boundaries, expiry, cross-configuration counts, equivalent IPv6 addresses, failed-batch
count rollback, parallel scoring and overlapping multi-IP batches. Swagger schemas and examples
remain covered by HTTP tests.
`StatsApiIT` verifies configuration/time filtering, inclusive boundaries, timezone offsets,
counts, decimal averages, empty results, top-10 limits, deterministic ties, IPv6 grouping,
query errors, Swagger documentation and consistent snapshots during concurrent ingestion.

The `IT` filename suffix makes Maven Failsafe run these tests during `verify`, while
Surefire runs unit tests during `test`. No integration tests are silently skipped when
Docker is unavailable. The test database is separate from the Compose development database.
`src/test/resources/docker-java.properties` selects API 1.44 for Docker Engine 29 compatibility.

If Docker Desktop is running on macOS but the test client cannot find its socket:

```bash
export DOCKER_HOST="unix://$HOME/.docker/run/docker.sock"
./mvnw verify
```

## Milestones and next steps

`v0.1-ingestion` marks durable ingestion and `v0.2-enrichment` marks classification and scoring.
The summary API is ready for review before `v0.3-stats`. Next are the samples API and
a realistic data generator.

Current engineering challenges include atomic batches, duplicate requests and stable repeat-offender
scores under concurrent ingestion. Transactions, database uniqueness and ordered IP locks address
these concerns, verified against real PostgreSQL.
The injected clock makes arrival-time tests deterministic without sleeps.

Additional production concerns include tenant authorization, authentication, external
secrets, request-size limits, observability, clock-skew policy and accepted timestamp ranges.
The assignment's optional full-stack Compose and bonus features will be considered after
required behavior is complete. Small commits and milestone tags remain review steps;
no Git push or submission email is performed by the application.
