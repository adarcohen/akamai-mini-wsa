# Mini WSA

A Java/Spring Boot security analytics backend, built incrementally for the Akamai assignment.

## Current milestone: event samples

Implemented: event model, single/batch ingestion, schema validation, server-assigned
`receivedAt`, PostgreSQL persistence, atomic duplicate handling, attack classification
and threat scoring, including concurrent repeat-offender detection, the statistics summary API
and filtered/paginated event samples.

Data generation is available through a standalone script. Docker Compose can run the
full stack, or only PostgreSQL while the application runs locally.

## Requirements

- JDK 17 or newer for local Java development and automated tests; full-stack Docker builds its own JDK.
- Docker Desktop running, for the development database and integration tests.
- Network access on the first build for Maven dependencies and Docker images.

The Maven Wrapper is included; a separate Maven installation is unnecessary.

## Run the full stack with Docker

With Docker Desktop running, from the repository root:

```bash
docker compose up --build -d --wait
```

Stop any locally running Java application first so port 8080 is available.
Open [Swagger UI](http://localhost:8080/docs). No host JDK or Maven is needed for
this mode: a multi-stage image builds the JAR using the Maven Wrapper, then runs
it with a Java 17 runtime under a non-root user. The image excludes DevTools.
The build skips tests; run the full test suite separately with `./mvnw verify`.
The first build needs internet access for base images and Maven dependencies.

The app connects to PostgreSQL using the Compose service name `db`, waits for the
database health check, and applies Flyway migrations on startup. Its health check
checks the HTTP OpenAPI endpoint after startup; it is a startup/HTTP check, not a
continuous database readiness check.

```bash
docker compose ps
docker compose logs --tail=100 app
docker compose down
```

`down` preserves the existing database volume and its events. Avoid `down -v` unless
intentionally deleting the data. Rebuild with `docker compose up --build -d --wait`
after code changes. For automatic restart while editing, use the local VS Code
workflow below instead.

Optional `.env` settings `APP_PORT` and `DB_PORT` change the published host ports.
Both bind to localhost. Internal app-to-database traffic always uses `db:5432`.
To switch back to local Java development, stop only the app container with
`docker compose stop app`, leave the database running, then use Run in VS Code.
If `DB_PORT` is changed, set local Java's `DB_URL` accordingly.

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

Retrieve stored events with `GET /v1/events/samples` (also available in Swagger),
or inspect the records directly:

```bash
docker compose exec db psql -U miniwsa -d miniwsa \
  -c 'SELECT event_id, event_time, received_at FROM security_events;'
```

The named volume preserves data across app/database restarts. `docker compose down`
stops the database and keeps its data. `docker compose down -v` also deletes its data;
use that only when intentionally resetting your development database.

### Automatic restart during development

Spring Boot DevTools restarts the local application when compiled classes change.
In VS Code, install **Extension Pack for Java**, open the repository root, and wait
for Maven import to finish. Keep **Java: Autobuild Enabled** enabled (the default).
Start the database using the command above, stop any existing app instance, then
open `MiniWsaApplication.java` and click **Run** above `main`.

Saving a Java file lets VS Code compile it and DevTools restart the application.
Wait for the new `Started MiniWsaApplication` message before sending requests.
Refresh Swagger UI after changing endpoints. If Maven dependencies change, reload
the Java project and restart the application manually.

When running through `./mvnw spring-boot:run` instead, DevTools still needs compiled
classes: run `./mvnw compile` in another terminal if your editor is not compiling
to the application's classpath. Saving source files alone does not trigger a restart.
Run `./mvnw verify` before a commit; it is not required after every save.
DevTools is automatically excluded from the packaged Spring Boot JAR.

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

## Generate test data

Python 3.9+ is needed only for the standalone generator; no Python packages are required.
Generate 10,000 events in 20 JSON arrays of 500 events:

```bash
python3 scripts/generate_events.py --count 10000 --seed 42 \
  --start 2026-05-20T00:00:00Z --output generated/demo-001
```

Each file is ready for `POST /v1/events/ingest`. With the app running, submit them
in order from a Bash or Zsh terminal, stopping immediately on an HTTP/network error:

```bash
for batch in generated/demo-001/batch-*.json; do
  curl --fail-with-body --silent --show-error \
    -X POST http://localhost:8080/v1/events/ingest \
    -H 'Content-Type: application/json' --data-binary "@$batch" \
    -o /tmp/mini-wsa-ingest-response.json || break
done
```

Inspect `/tmp/mini-wsa-ingest-response.json` for the last response. Each batch is an
independent transaction: an error does not roll back earlier successful batches.
Do not resend successful files: their event IDs will produce HTTP 409. For another
run, choose a new output directory; existing directories are refused to avoid mixing
batches. New runs receive unique UUID prefixes, even with the same seed.

Options: `--count` (positive, default 10000), `--batch-size` (1–1000, default 500),
`--seed` (default 42), `--start` (timezone-aware ISO-8601, default current UTC),
and required `--output` (new directory). The same seed and start reproduce event
content apart from the unique event IDs. Generation holds only one batch in memory.

The stream spans three configurations, all seven categories and all three actions.
Every 200-event block starts with a 50-event wave over five simulated seconds,
sharing an IP, path, category and configuration. Waves rotate between injection,
credential bots and denial of service, interleaved with background security events.
Small requested counts can contain only part of a wave and need not cover every category.
IPs use documentation-only ranges, and hostnames use `example.com`.

Client timestamps model the attack timeline. Repeat-offender scoring uses actual
server `receivedAt`, so fast ingestion of a long simulated timeline still falls
within the server's current ten-minute window. The generator never supplies enrichment
fields. Query summary/samples with the generated **event timestamp** range; the default
10,000-event example finishes before `2026-05-21T00:00:00Z`.

Test the generator separately:

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
```

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

## Samples API

`GET /v1/events/samples` returns individual enriched events. Every parameter is optional:
`configId`, `from`, `to`, `category`, `action`, `limit` and `offset`.

```bash
curl 'http://localhost:8080/v1/events/samples?configId=14227&category=INJECTION&action=DENY&limit=2&offset=0'
```

The response has `total`, `limit`, `offset` and `events`. `total` counts all matching events
before pagination; `events` contains the complete original fields plus stored `attackType`,
`threatScore` and `receivedAt`. Reading a sample never recalculates its threat score.

- `limit` defaults to 20, accepts 1-100, and rejects values outside that range with 400.
- `offset` defaults to 0 and must be nonnegative.
- Filters combine with AND. Either time bound may be omitted; both bounds are inclusive.
- Results sort by original event timestamp descending, then event ID descending for stable ties.
- No matches return 200 with zero and an empty list. An offset beyond the result set returns
  an empty list while preserving the full matching total.
- Invalid filters, enum values or pagination return 400 with a problem response.
- In Swagger, find **Events -> GET /v1/events/samples** and leave unwanted filters blank.

Filtering, ordering and pagination run in SQL. The application reads at most 100 event payloads
per request. The exact count and page use one read-only repeatable-read transaction, so a
concurrent insert cannot make the count and rows disagree within that response.

V4 replaces the V3 time indexes with `(config_id, event_time DESC, event_id DESC)` and
`(event_time DESC, event_id DESC)`, supporting stable ordering with and without a configuration.
Category/action predicates use parameterized SQL; further indexes should follow measurements.
Exact counts over broad ranges and deep offsets can still be costly. Offset pages can shift
between separate requests as new events arrive; cursor pagination is a possible later extension.

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
- `api/EventsController.java`: ingestion and samples HTTP endpoints; delegates to their services.
- `api/StatsController.java`: summary HTTP endpoint and Swagger parameter examples.
- `domain/SummaryQuery.java`: validated configuration/time criteria.
- `domain/StatsSummary.java`: typed summary result and its related result records.
- `service/StatsService.java`: consistent read transaction and summary assembly.
- `repository/StatsRepository.java`: parameterized aggregation and top-10 queries.
- `domain/SamplesQuery.java`: optional filter criteria and pagination validation.
- `domain/SamplesPage.java`: paginated result with the full matching total.
- `service/SamplesService.java`: consistent count/page transaction.
- `repository/SamplesRepository.java`: SQL filtering/count/pagination and stored-event mapping.
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
- `resources/db/migration/V4__index_sample_ordering.sql`: extends those indexes with the event ID tie-breaker.
- `MiniWsaApplication.java`: app entry point and injectable UTC clock.
- `compose.yaml`: app/database services, health checks and persistent database volume.
- `Dockerfile` and `.dockerignore`: isolated build stage and minimal source-only build context.
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
`SamplesApiIT` verifies defaults, page-size bounds, offsets, stable sorting, independent/combined
filters, optional time bounds, unchanged enrichment, empty pages, Swagger and concurrent count/page consistency.

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
`v0.3-stats` marks the summary API, `v0.4-samples` the samples API, and
`v0.5-generator` the data generator. Full-stack Docker support is ready for review.
Next are final review and an optional bonus endpoint.

Current engineering challenges include atomic batches, duplicate requests and stable repeat-offender
scores under concurrent ingestion. Transactions, database uniqueness and ordered IP locks address
these concerns, verified against real PostgreSQL.
The injected clock makes arrival-time tests deterministic without sleeps.

Additional production concerns include tenant authorization, authentication, external
secrets, request-size limits, observability, clock-skew policy and accepted timestamp ranges.
The required endpoints and generator are implemented, together with optional full-stack Compose.
An optional analytics bonus can be added after review. Small commits and milestone tags remain review steps;
no Git push or submission email is performed by the application.
