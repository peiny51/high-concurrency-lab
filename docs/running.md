# Running, Testing and CI

Run the commands below from the repository root unless a command changes directories.

## Local configuration

The repository includes shared settings in `src/main/resources/application.properties`.
Machine-specific connections belong in `application-local.properties`, which Git
ignores and Maven excludes from application JARs.

For a fresh checkout, copy the templates, then fill every value in `.env.test`:

```sh
cp -n .env.example .env.test
cp -n src/main/resources/application-local.properties.example src/main/resources/application-local.properties
```

Set `YOUR_LOCAL_DB_USERNAME` and `YOUR_LOCAL_DB_PASSWORD` in the local Spring
configuration to match `DB_USERNAME` and `DB_PASSWORD` in `.env.test`. Compose
reads credentials from that file. `.env.test` is ignored by Git; `.env.example`
contains empty placeholders. `cp -n` preserves an existing local file.

For existing MySQL and Grafana volumes, use the accounts already stored there.
Changing an environment-file value does not rotate an existing account password.

Start the local dependencies:

```sh
docker compose --env-file .env.test up -d
```

Start the backend with the **Spring** profile `local`:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

### Enable Kafka stock deduction

The consumer is disabled by default to avoid processing an existing development
backlog unexpectedly. Before enabling it, review the topic backlog, consumer group
and committed offsets. On a first run without a committed offset, the default
`earliest` policy reads retained historical messages. Use a dedicated local group
and `latest` only when intentionally starting from the current end of the topic:

```sh
SPRING_KAFKA_LISTENER_AUTO_STARTUP=true \
SPRING_KAFKA_CONSUMER_GROUP_ID=lab.purchase-processing.local-ui.v1 \
SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET=latest \
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Start one backend process; use this command instead of the ordinary startup
command above. Existing committed offsets take precedence over `latest`.
Once the backend is running, open `/kafka`, submit one deduction request and use
**Check Result** with the same request ID. HTTP 202 confirms Kafka acceptance;
HTTP 200 from the result endpoint reports the completed business outcome.

The browser saves the last attempted request before sending it and never
automatically resubmits on refresh. An uncertain submission should be followed
using the same ID. The public endpoints are `POST /api/purchase-requests` and
`GET /api/purchase-results/{requestId}`.

Kafka logs and KRaft metadata use the mounted `/var/lib/kafka/data` volume.
Message retention still applies after consumption; a volume is not an indefinite
archive or backup. For an existing container that wrote outside this mount,
migrate and verify its data before recreating it. Do not use
`docker compose --env-file .env.test down --volumes` when preserving local data.

### Backend tests

Run backend tests using the same local configuration and running dependencies:

```sh
./mvnw test -Dspring.profiles.active=local
```

A fresh CI runner must supply its own connection settings and services; it cannot
use your ignored local file. No local profile is enabled by default.

## Frontend

In a second terminal:

```sh
cd frontend
npm ci
npm run dev
```

Open `http://localhost:5173`. Vite forwards `/api` requests to the backend on port
8080. Build the frontend with `npm run build`.


## Packaging

```sh
./mvnw verify -Dspring.profiles.active=local
```

The local configuration and its template are excluded from application JARs.
The packaged application requires environment-specific configuration at runtime,
such as datasource, Redis, and Kafka settings supplied through environment variables
or an external Spring configuration file. Git ignore rules alone do not prevent
files from being included in build artifacts.

## Backend Docker Image

Build the backend from source using the project's Maven Wrapper:

```sh
docker build --progress=plain -t high-concurrency-lab:local .
```

The build stage uses a Java 21 JDK. The final stage contains a Java 21 JRE and
`/app/app.jar`, and runs as user/group `10001:10001`. Its default entrypoint is
`java -jar /app/app.jar`; application connection settings are supplied at runtime.
The Docker build skips test execution; the CI workflow runs the integration tests
with its disposable services.

Check the image's Java runtime without starting the application:

```sh
docker run --rm --entrypoint java high-concurrency-lab:local -version
```

The build context includes the Maven Wrapper, `pom.xml` and backend sources.
Local connection settings and their template are excluded by `.dockerignore` and
the Maven JAR configuration. Use container-appropriate MySQL, Redis and Kafka
addresses when starting this image with its dependencies.

## Run the Backend with Compose

With Docker Desktop running, start the backend and its dependencies:

```sh
docker compose --env-file .env.test --profile app up -d --build --wait --wait-timeout 180
```

The `app` profile enables the backend service. Compose builds its image and waits
for MySQL, Redis and Kafka health checks before starting it. The backend is
available at `http://localhost:8080`, with health at `/actuator/health` and metrics
at `/actuator/prometheus`. Java and Maven are supplied by the image build stages;
Compose reads `.env.test` and supplies connection settings through environment
variables. Its username and password values are required; missing or empty values
stop configuration loading with a named error.

Use one backend startup method at a time to avoid a port 8080 conflict. Stop a
manually started backend before using the command above. To switch back to the
local Maven startup command, stop the backend container first:

```sh
docker compose --env-file .env.test --profile app stop backend
```

Container connections use `mysql:3306`, `redis:6379` and `kafka:29092`. Mac clients
continue to use `localhost:3307`, `localhost:6379` and `localhost:9092`. Kafka
advertises its internal and external addresses on separate listeners.

The named MySQL and Kafka data volumes retain existing data. A fresh database
starts without demo products; use the Inventory page's stock setup before
querying or purchasing a product. Kafka consumption retains the shared default
of disabled until the backlog and consumer offsets have been reviewed.

The Vue frontend can use its existing Vite proxy to port 8080. Run the frontend
using the instructions above. Monitoring services can be started using the
[monitoring guide](monitoring.md).

## Continuous Integration

The GitHub Actions workflow in `.github/workflows/ci.yml` runs on pushes, pull
requests, and manual dispatch. Two independent jobs validate the backend and
frontend:

- **Backend:** Java 21, disposable MySQL/Redis/Kafka services, Maven tests, and
  executable JAR packaging, and backend Docker image construction.
- **Frontend:** Node.js 24.12.0, `npm ci`, and the Vue production build.

The backend job uses `docker-compose.ci.yml` with separate ports (13307, 16379,
and 19092), temporary data, readiness checks, and explicit CI environment
variables. It does not use `application-local.properties` or the development
containers. The `ci-only` database credentials are disposable test values.

Test reports and successful build artifacts are retained for seven days.
Dependency diagnostics are printed on failure, and CI containers are cleaned up
after the backend job. Workflow permissions are read-only; official Actions are
pinned to commit hashes. These jobs validate builds and do not deploy applications.
After backend tests and packaging pass, CI builds `high-concurrency-lab:ci` from
the Dockerfile. The image remains on the temporary runner; it is not pushed to
a registry. The Docker build packages the source again with tests skipped,
since the preceding Maven step has already run them.

## Dependency and Secret Protection

`.github/dependabot.yml` checks Maven dependencies, frontend npm dependencies,
and GitHub Actions weekly. Each ecosystem allows up to three open version-update
PRs. Updates require review and are not merged automatically; the configuration
is activated from the repository's default branch.

The GitHub repository has Dependabot vulnerability alerts and security-update
PRs enabled separately from scheduled version updates. Secret scanning and push
protection are also enabled for supported credential patterns. These features
do not guarantee detection of every secret or vulnerability. Keep real local
credentials in ignored configuration and review dependency changes before merging.
