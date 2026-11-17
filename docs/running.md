# Running, Testing and CI

Run the commands below from the repository root unless a command changes directories.

## Local configuration

The repository includes shared settings in `src/main/resources/application.properties`.
Machine-specific connections belong in `application-local.properties`, which Git
ignores and Maven excludes from application JARs.

For a fresh checkout, copy the template once:

```sh
cp src/main/resources/application-local.properties.example src/main/resources/application-local.properties
```

Edit the copy and replace `YOUR_LOCAL_DB_USERNAME` and `YOUR_LOCAL_DB_PASSWORD`
with credentials for your local database. The checked-in Docker Compose file
currently creates a disposable local database with username `lab` and password
`lab123`; these are demo values, not deployment credentials.

Start the local dependencies:

```sh
docker compose up -d
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
`docker compose down --volumes` when preserving local data.

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

## Continuous Integration

The GitHub Actions workflow in `.github/workflows/ci.yml` runs on pushes, pull
requests, and manual dispatch. Two independent jobs validate the backend and
frontend:

- **Backend:** Java 21, disposable MySQL/Redis/Kafka services, Maven tests, and
  executable JAR packaging.
- **Frontend:** Node.js 24.12.0, `npm ci`, and the Vue production build.

The backend job uses `docker-compose.ci.yml` with separate ports (13307, 16379,
and 19092), temporary data, readiness checks, and explicit CI environment
variables. It does not use `application-local.properties` or the development
containers. The `ci-only` database credentials are disposable test values.

Test reports and successful build artifacts are retained for seven days.
Dependency diagnostics are printed on failure, and CI containers are cleaned up
after the backend job. Workflow permissions are read-only; official Actions are
pinned to commit hashes. These jobs validate builds and do not deploy applications.

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
