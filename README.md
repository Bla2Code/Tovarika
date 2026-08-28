# Tovarika Backend

Spring Boot backend with contract-first REST API, PostgreSQL, Liquibase and MinIO.

## Requirements

- JDK 21;
- Docker with Docker Compose;
- Node.js 22.18+ and npm 10+ only when rebuilding the API contract artifacts;
- the `tovarika-api-contract` repository next to this repository.

By default the contract is read from `../tovarika-api-contract`. Override the path with
`-PapiContractDir=/absolute/path` or `TOVARIKA_API_CONTRACT_DIR`.

## Run

Run the application locally while PostgreSQL and MinIO are managed by Spring Boot Docker
Compose support:

```bash
./gradlew bootRun
```

Before the first run, or after changing the contract, prepare its artifacts in the contract
repository:

```bash
(cd ../tovarika-api-contract && npm ci && npm run build)
```

Then run the entire stack, including the application, in Docker:

```bash
docker compose --profile full up --build --wait
```

The application service is kept in the `full` profile so it is not started recursively by
`bootRun`. The Docker build context is the parent directory because the build uses both this
repository and the sibling `tovarika-api-contract` repository. `Dockerfile.dockerignore` limits
the files sent to the Docker daemon.

Stop the stack without deleting PostgreSQL and MinIO data:

```bash
docker compose --profile full down
```

The application creates the configured MinIO bucket on startup. Useful local URLs:

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- MinIO console: <http://localhost:9001>
- MinIO S3 endpoint: <http://localhost:9000>

Default development credentials are `tovarika` / `tovarika-secret` for MinIO and
`tovarika` / `tovarika` for PostgreSQL. Override them through the environment variables shown
in `compose.yaml` and `application.properties` outside local development.

## API contract

The API contract repository owns validation, bundling and frontend generation. The backend
consumes its prepared `dist/*.yaml` artifacts; therefore the backend Docker image does not
contain or run Node.js. `generatePublicApi` and `generateProviderApi` create Spring API
interfaces and DTOs under `build/generated/openapi`. Generated sources are never edited or
committed.

```bash
./gradlew generatePublicApi generateProviderApi
```

`buildApiContract` is an explicit utility task for updating `dist` through the contract
repository's Node.js toolchain. It is not part of `build`, `bootRun` or the backend Docker build.

Controllers implement interfaces from `com.tovarika.api.publicapi` or
`com.tovarika.api.provider`; application and domain logic must not be added to generated code.

## Tests

```bash
./gradlew test
```

The integration test starts PostgreSQL 18 with Testcontainers and verifies that the Liquibase
bootstrap changeset was applied to the real database.
