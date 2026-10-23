# Mini WSA

A Java and Spring Boot security analytics backend, built incrementally for the Akamai assignment.

## Current stage: event model and validation

This stage contains a runnable Spring Boot web application, the security event model
and unit tests for its validation rules. Event ingestion, storage, enrichment,
analytics and Docker setup will be introduced in subsequent stages.

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

There are no API endpoints yet. This command should return HTTP 404, confirming that
an HTTP server is responding but no route has been implemented:

```bash
curl -i http://localhost:8080/
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
The next stage will connect this validation to the ingestion API.

IP syntax, JSON enum/timestamp parsing and HTTP error responses are not validated by
these unit tests; those API boundary checks will be implemented with ingestion.

Run the current tests without Docker or a database:

```bash
./mvnw test
```

## Next stage

Implement the ingestion endpoint, single/batch JSON parsing and API validation tests.
The `v0.1-ingestion` tag will be added only once that milestone is working.
Each stage will be reviewed and committed independently.
