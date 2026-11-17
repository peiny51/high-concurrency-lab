# High-Concurrency Backend Lab

A Spring Boot backend with a Vue interface for testing concurrency-safe inventory
updates, Redis cache protection, rate limiting, and Kafka-based asynchronous stock
deduction. Dedicated scenario pages let users send requests and inspect results,
while Prometheus and Grafana expose application metrics and availability alerts.

**Stack:** Java 21, Spring Boot, JPA, MySQL, Redis, Kafka, Vue 3, Docker Compose,
GitHub Actions, Prometheus, Grafana.

## Features

- **Concurrency-safe inventory:** supports quantity-based purchases under
  concurrent requests. Conditional SQL checks availability and deducts stock
  atomically, rejecting purchases when the requested quantity is unavailable.
- **Redis caching and protection:** caches product names and invalidates entries
  after a rename. Negative caching, per-product rebuild coordination, randomized
  expiry, and bounded rebuild concurrency address penetration, breakdown, and
  avalanche scenarios.
- **Shared rate limiting:** uses Redis and Lua to enforce an endpoint quota across
  application instances. Excess requests receive HTTP 429 with `Retry-After`.
- **Kafka asynchronous processing:** returns HTTP 202 after broker acknowledgement,
  then processes stock deductions in the background. Request-ID idempotency and
  transactional MySQL results prevent repeated deductions for completed requests;
  bounded retries and a failure topic handle processing errors. Clients query
  the outcome using the original request ID.
- **Interactive Vue scenarios:** provides dedicated pages for inventory, caching,
  rate limiting, and Kafka. Users can send requests, run concurrency cases, and
  inspect API responses and processing outcomes.
- **Monitoring and alerts:** Grafana displays request counts, request rates,
  average response times, 5xx error percentages, and scrape status. Prometheus
  raises an availability alert after one minute of continuous backend scrape
  failures and clears it after collection recovers.

## Run Locally

Requires Java 21, Node.js 24.12+ and Docker Desktop.

Copy the local configuration template once, then set its database username and
password. The local Compose database uses the demo values `lab` / `lab123`.

```sh
cp src/main/resources/application-local.properties.example \
  src/main/resources/application-local.properties
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

In another terminal:

```sh
cd frontend
npm ci
npm run dev
```

Open [the scenario UI](http://localhost:5173). The backend runs on port 8080.
Kafka consumption is disabled by default; follow the
[Kafka startup instructions](docs/running.md#enable-kafka-stock-deduction)
to enable asynchronous processing after reviewing local backlog and offsets.

## Monitoring

With the backend running under the `local` profile:

```sh
docker compose -f docker-compose.monitoring.yml up -d
```

- [Grafana dashboard](http://localhost:3000/d/lab-backend-overview/backend-monitoring)
  — local demo login `admin` / `local-grafana-only`.
- [Prometheus targets](http://localhost:9090/targets) and
  [availability alerts](http://localhost:9090/alerts).

## CI and Security

[GitHub Actions](.github/workflows/ci.yml) runs backend tests with disposable
MySQL/Redis/Kafka services, packages the JAR, and builds the frontend.
Dependabot checks Maven, npm, and Actions dependencies. Repository secret scanning
and push protection are enabled.

## Documentation

- [Scenario design and processing flow](docs/scenarios.md)
- [Local setup, Kafka, testing and packaging](docs/running.md)
- [Monitoring queries, dashboards and alerts](docs/monitoring.md)
- [Experiment screenshots](docs/image/)
