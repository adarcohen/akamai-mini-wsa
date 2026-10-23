# Mini WSA

A Java/Spring Boot security analytics backend, built incrementally for the Akamai assignment.

## Current milestone: durable ingestion

Implemented: event model, single/batch ingestion, schema validation, server-assigned
`receivedAt`, PostgreSQL persistence and atomic duplicate handling.

Classification, threat scoring, summary/samples APIs and data generation are subsequent
milestones. The optional full-stack Docker setup will follow; Compose currently runs
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
contains the original validated fields plus `receivedAt`. The success response is
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
- `domain/ReceivedEvent.java`: original event plus arrival time, shared by persistence and API layers.
- `api/EventsController.java`: HTTP endpoint; delegates validation and persistence.
- `api/IngestionRequestParser.java`: normalizes object/array input, parses and validates every event.
- `api/IngestionResponse.java`: the HTTP success envelope.
- `api/InvalidEventsException.java`: structured field validation failures.
- `api/ApiExceptionHandler.java`: maps validation, conflicts and temporary storage failures to HTTP errors.
- `api/IsoInstantDeserializer.java`: requires ISO-8601 timestamp strings.
- `api/IngestionExamples.java`: single and batch request examples for Swagger UI.
- `service/IngestionService.java`: assigns arrival times and owns the all-or-nothing transaction.
- `repository/EventRepository.java`: parameterized, batched JDBC inserts; contains no HTTP logic.
- `resources/application.yml`: database connection and strict JSON settings.
- `resources/db/migration/V1__create_security_events.sql`: Flyway's versioned table definition.
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
The primary key supplies the uniqueness index. Additional query indexes will be added
with the enrichment/analytics queries they support rather than indexing every field now.

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

## Tests

```bash
./mvnw test       # Model validation unit tests; no Docker or running database required
./mvnw verify     # Unit tests + real PostgreSQL/HTTP integration tests; Docker required
```

`SecurityEventValidationTest` checks required fields, boundaries and nested validation.
`EventsApiIT` sends real HTTP requests to the app on a random port, backed by an isolated
PostgreSQL 17 Testcontainer. It checks persisted payloads/timestamps, batch writes,
validation without writes, duplicate conflicts, rollback, concurrent duplicates,
IP literals and parameterized SQL handling.

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

The durable ingestion stage is ready for review before tagging `v0.1-ingestion`.
Next: classification and threat scoring, including an explicit ten-minute repeat-offender
policy and tests, then the statistics/samples APIs and a realistic data generator.

Current engineering challenges are atomic batches and concurrent duplicate requests.
They are handled by a transaction plus database uniqueness, verified against real PostgreSQL.
The injected clock makes arrival-time tests deterministic without sleeps.

Additional production concerns include tenant authorization, authentication, external
secrets, request-size limits, observability, clock-skew policy and accepted timestamp ranges.
The assignment's optional full-stack Compose and bonus features will be considered after
required behavior is complete. Small commits and milestone tags remain review steps;
no Git push or submission email is performed by the application.
