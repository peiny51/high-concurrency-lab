# High-Concurrency Backend Lab

A Java and Spring Boot application for exploring concurrency-safe inventory
purchases, Redis caching, request rate limiting, and Kafka-based asynchronous
processing, with a Vue interface for interacting with each business scenario.

The project follows a product purchase through the backend: reading product
information, accepting a requested quantity, protecting downstream services from
bursts of traffic, processing purchase intents, and reporting their outcomes.
Each scenario focuses on a specific failure mode and its handling, while the
combined workflow shows how the mechanisms fit together in an application.

## What the Project Demonstrates

### Concurrency-safe inventory purchases

Multiple customers can attempt to purchase the same product at the same time.
The inventory flow checks availability and deducts the requested quantity in
one conditional MySQL update, rather than reading stock and writing back a
calculated value in separate steps.

- Quantity-based purchases with no partial fulfillment.
- Atomic stock checks and deductions inside a transaction.
- A Vue page for setting demo stock, purchasing, and comparing concurrent
  request outcomes with the resulting stock change.

### Product caching and cache consistency

Frequently requested product names are read through Redis, with MySQL remaining
the source of truth. A cache miss loads the name from MySQL and populates Redis;
renaming a product updates MySQL and invalidates the associated cache entries.

The cache scenarios cover:

- **Cache aside:** cache-first reads, expiry, and invalidation after a name update.
- **Cache penetration:** short-lived not-found markers for repeated missing IDs.
- **Cache breakdown:** a per-product Redis lease coordinates rebuilding a missing
  hot key, with a second cache check and owner-token verification during release.
- **Cache avalanche:** randomized expirations spread rebuild work across time,
  while bounded per-instance admission limits simultaneous rebuild sections.

Each case has a separate page showing actual API results, data sources, errors,
and relevant expiry or protection information. Cache expiry and invalidation
reduce stale data; they do not make Redis and MySQL one atomic transaction.

### Request rate limiting

A Redis-backed fixed-window limiter controls how many product queries enter a
protected endpoint during a time window. Lua combines the quota decision,
counter update, and initial expiry without interleaving other client commands.

- A shared endpoint quota across clients and application instances using the
  same Redis key.
- HTTP 429 responses with a machine-readable error code and `Retry-After`.
- A separate Vue page for single and concurrent requests, rejection counts, and
  observing recovery after a window expires.

Rate limits control arrivals over time. Rebuild concurrency limits control the
number of operations executing together; these are separate protections.

### Asynchronous stock deduction

Kafka separates purchase submission from background processing, allowing incoming
purchase intents to be buffered and processed independently of the HTTP request.
The workflow distinguishes broker-confirmed submission from a completed stock
deduction. The `/kafka` page creates neither orders nor payments; purchase-related
API and Java names identify the inventory-processing intent.

- A client request ID identifies one purchase intent, alongside product ID and
  quantity.
- Submission acknowledgement before returning HTTP 202.
- Background processing with concurrency-safe inventory deductions and durable
  processing outcomes.
- Business-level idempotency to prevent duplicate delivery from causing repeated
  inventory changes.
- Two additional attempts for retryable processing errors, followed by a
  broker-confirmed copy to `lab.purchase-requests.v1.DLT` when retries are exhausted.
- Invalid request content goes directly to the failure topic without retrying.
- Result lookup so the interface can report the final outcome without requiring
  another purchase submission.

Producer retry deduplication and business idempotency address different problems.
An unconfirmed send is treated as uncertain delivery, rather than proof that the
broker rejected the message.

Stock deduction and result insertion share one MySQL transaction. Reusing a
completed request ID with the same product and quantity returns the saved result;
conflicting reuse is rejected. A technical failure routed to the DLT does not
create a completed business result. HTTP 404 from result lookup therefore means
no completed result is available, rather than proving that a request failed.

Source progress does not advance when failure-topic publishing fails. The retry
count is not a lifetime limit across restarts or failed recovery. The local setup
uses one partition and one replica, and does not provide broker high availability.

## Purchase Workflow

```text
Browse a product through the cache
                |
Submit a purchase intent with request ID, product ID, and quantity
                |
Publish to Kafka and confirm submission
                |
Process the message in the background
                |
Check idempotency and apply the inventory transaction
                |
Persist the outcome and acknowledge processing
                |
Query the outcome and display the result
```

The focused scenario pages let users inspect individual mechanisms before
following the combined purchase workflow. Query rate limiting and cache rebuild
protection also have their own independent endpoints and controls.

## Technology Stack

| Technology | Role |
| --- | --- |
| Java 21 and Spring Boot | HTTP APIs, validation, dependency injection, and business services |
| Spring Data JPA / Hibernate | Database access and transactional operations |
| MySQL | Product data, inventory, and durable purchase processing state |
| Redis and Lua | Name caching, not-found markers, rebuild coordination, and shared request quotas |
| Kafka | Purchase-intent delivery and asynchronous processing |
| Vue 3 and Vue Router | Separate interactive pages for business scenarios |
| Vite | Frontend development, API proxying, and production builds |
| Docker and Docker Compose | Application packaging and reproducible local dependencies |
| GitHub Actions | Continuous integration for tests, frontend builds, and packaging checks |
| Spring Boot Actuator | Application health and operational diagnostics |

## Verification and Delivery

Validation focuses on observable business outcomes: stock consistency under
concurrent requests, cache hits and rebuilds, quota enforcement and recovery,
submission acknowledgement, duplicate handling, and failure recovery.

The delivery workflow combines version-controlled configuration templates,
automated checks, repeatable build artifacts, and environment-specific runtime
settings. Local connection details stay outside version control and application
JARs. Scenario-sized concurrency runs demonstrate behavior; they are not
throughput benchmarks or a substitute for capacity testing.

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
