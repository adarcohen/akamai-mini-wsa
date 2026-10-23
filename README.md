# Mini WSA

A Java and Spring Boot security analytics backend, built incrementally for the Akamai assignment.

## Current stage: ingestion HTTP contract

This stage accepts single events or batches over HTTP, validates them and returns their
server-assigned arrival timestamps. It includes model validation and real HTTP integration tests.

**This is an intermediate implementation: accepted events are returned but are not persisted.**
There is no cross-request deduplication yet. PostgreSQL persistence is the next stage;
the `v0.1-ingestion` milestone will only be tagged after storage is implemented.
Classification, scoring, analytics and Docker setup will follow.

## Requirements

- JDK 17 or newer.
- Internet access on the first build to download Maven and dependencies.

The Maven Wrapper is included, so a separate Maven installation is unnecessary.
No database or Docker installation is needed for this stage.

## Build and run

Open the repository folder in VS Code or another Java IDE. In a terminal at the repository root:

```bash
./mvnw clean package
./mvnw spring-boot:run
```

On Windows, use `mvnw.cmd` instead of `./mvnw`.

The application starts on port 8080. Look for `Started MiniWsaApplication` in the logs.
Stop it with `Ctrl+C`.

The root path has no route and returns HTTP 404. To exercise ingestion, use the example below:

```bash
curl -i -X POST http://localhost:8080/v1/events/ingest \
  -H 'Content-Type: application/json' --data-binary @examples/event.json
```

You can also run the packaged application:

```bash
java -jar target/mini-wsa-0.1.0-SNAPSHOT.jar
```

Run only one instance on port 8080 at a time.

## Files in this stage

- `pom.xml`: project identity, Java version, dependencies and build plugin.
- `src/main/java/com/akamai/miniwsa/MiniWsaApplication.java`: application entry point.
- `mvnw` and `mvnw.cmd`: standard Maven launch scripts for macOS/Linux and Windows.
- `.mvn/wrapper/maven-wrapper.properties`: Maven version used by the wrapper.
- `.gitignore`: excludes generated output, local IDE files and local secrets from Git.
- `README.md`: setup instructions and the scope of the current stage.
- `src/main/java/com/akamai/miniwsa/domain/`: security event model and allowed enum values.
- `src/test/java/com/akamai/miniwsa/domain/`: validation unit tests.

The nested Java directories correspond to the package `com.akamai.miniwsa`.
They belong to one Maven project and one Git repository. Maven creates `target/`
during a build; it is generated output and must not be committed.

## Event model and validation

`SecurityEvent` is an immutable Java record matching the assignment's event fields.
Its nested `Rule` and `GeoLocation` records keep these event-specific structures together.
`Category`, `Severity` and `Action` enums define the allowed values, without enrichment
or scoring behavior yet.

Jakarta Bean Validation annotations declare required fields, positive configuration IDs,
HTTP status bounds, nonnegative byte counts and string limits. `@Valid` also checks
nested rule and location fields. `timestamp` uses `Instant` to represent a point in time.
Wrapper types such as `Long` allow validation to distinguish a missing value from zero.
Creating a record alone does not run validation: callers must invoke a validator.
The ingestion parser invokes this validation for every event before generating a success response.

HTTP integration tests cover enum/timestamp parsing, strict JSON types and validation errors.
IP fields currently require a nonblank string with a maximum length; IP literal syntax validation
will be added with persistence and IP-based processing.

Run the current tests without Docker or a database:

```bash
./mvnw test
```

## Ingestion endpoint

`POST /v1/events/ingest` accepts one event object or an array of 1-1000 events.
Success returns HTTP 201 with `accepted` and an `events` array. Each response event
contains its original fields and `receivedAt`, assigned once per request using a UTC clock.
The clock is injected so tests can verify exact timestamps without sleeping.

Malformed JSON, invalid body shapes, missing required fields, unknown enums/properties,
numeric timestamps, incorrect scalar types and duplicate IDs within one batch return HTTP 400.
A whole batch must validate before any success response is produced. Timestamp strings must
contain an ISO-8601 date/time and an explicit offset or `Z`.

Validation errors use `application/problem+json`. The `errors` array identifies the array
index and field, such as `events[1].rule.severity`, without echoing rejected values.

The `api/` package contains:

- `EventsController`: the route and success response, currently without persistence.
- `IngestionRequestParser`: single/batch normalization, JSON conversion and validation.
- `IngestionResponse`: the success response structure.
- `InvalidEventsException`: validation failures and their field details.
- `ApiExceptionHandler`: maps invalid requests to HTTP 400 problem responses.
- `IsoInstantDeserializer`: requires ISO-8601 strings instead of numeric epoch values.

`src/main/resources/application.yml` makes JSON type/enum/property validation strict.
`examples/event.json` is a complete request body for manual testing.
`EventsApiTest` starts the real application on a random port and sends HTTP requests;
these integration tests need neither Docker nor a database at this stage.

The 1000-event limit applies after JSON parsing. An incoming byte-size limit will also
be needed before exposing this API to untrusted traffic.

## Next stage

Add PostgreSQL persistence, cross-request duplicate handling and database integration tests.
The `v0.1-ingestion` tag will be added only once that milestone is working.
Each stage will be reviewed and committed independently.
