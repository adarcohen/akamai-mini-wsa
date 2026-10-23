FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
# Integration tests require Docker and run separately through ./mvnw verify.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system miniwsa \
    && useradd --system --gid miniwsa --home-dir /app miniwsa
WORKDIR /app
COPY --from=build --chown=miniwsa:miniwsa /workspace/target/mini-wsa-*.jar app.jar
USER miniwsa
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
    CMD curl --fail --silent http://localhost:8080/v3/api-docs > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
