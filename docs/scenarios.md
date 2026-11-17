# Project Scenarios

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
