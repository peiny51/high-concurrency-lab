# Local Monitoring

Run commands from the repository root with Docker Desktop and the backend running.
See [the running guide](running.md) for application setup.

## Local Prometheus Monitoring

Run the backend with the `local` Spring profile to expose
`http://localhost:8080/actuator/prometheus`. With Docker Desktop running, validate
the monitoring configuration and start Prometheus:

```sh
docker compose -f docker-compose.monitoring.yml config --quiet
docker compose -f docker-compose.monitoring.yml run --rm --no-deps \
  --entrypoint /bin/promtool prometheus check config /etc/prometheus/prometheus.yml
docker compose -f docker-compose.monitoring.yml up -d
```

Open `http://localhost:9090/targets` and confirm that `high-concurrency-lab` is
`UP`. In the Prometheus query page, run:

```promql
up{job="high-concurrency-lab"}
http_server_requests_seconds_count{job="high-concurrency-lab",uri="/api/overselling/stock",method="GET",status="200"}
```

The first expression returns `1` after a successful scrape. The second appears
after a successful stock lookup and the next scrape. Query an existing product:

```sh
curl -fsS 'http://localhost:8080/api/overselling/stock?productId=1'
```

Prometheus v3.13.5 collects samples every 15 seconds and retains them for seven
days in the `prometheus_data` Docker volume. The monitoring UI is bound to the
Mac's loopback interface. The container uses Docker Desktop's
`host.docker.internal:8080` to reach the backend running on the Mac.

Stop the monitoring service while retaining its collected data:

```sh
docker compose -f docker-compose.monitoring.yml stop prometheus
```

## Local Grafana Dashboard

The monitoring Compose file also starts Grafana OSS v13.3.1. Open
`http://localhost:3000/d/lab-backend-overview/backend-monitoring` and sign in with
the disposable local account `admin` / `local-grafana-only`.

Grafana automatically loads its Prometheus data source and the **Backend
Monitoring** dashboard from `monitoring/grafana/`. The five panels show the latest
backend scrape status, cumulative requests, requests per second, and average
server-side response time, plus HTTP 5xx error percentages grouped by `/api/` path. The
dashboard refreshes every 15 seconds. A request becomes visible after the next
Prometheus scrape and Grafana refresh, which can take about 30 seconds.

The data source uses `http://prometheus:9090` within the Compose network. Grafana
users and settings are stored in the `grafana_data` volume. Dashboard definitions
are maintained in JSON files; change the checked-in JSON to retain edits across
provisioning updates. The count panel shows cumulative values since backend
startup. The rate panel plots `rate(...[1m])` over the selected time range, using
each point's preceding minute of samples. It requires at least two samples per
series and calculates rates before summing by path so counter resets are handled.
The response-time panel divides the summed duration rate by the summed request
rate, then converts seconds to milliseconds. It uses the same one-minute window
and leaves gaps when there are no requests to average.
The server-error panel divides the HTTP 5xx rate by the total request rate and
multiplies by 100. Active paths without a 5xx series show 0%; idle windows retain
gaps. HTTP 400 and 429 responses are included in total requests but not in 5xx errors.

Start or stop both monitoring services:

```sh
docker compose -f docker-compose.monitoring.yml up -d
docker compose -f docker-compose.monitoring.yml stop prometheus grafana
```

The Grafana port is bound to `127.0.0.1`. The checked-in login is for local lab
use only; supply deployment credentials separately for another environment.

## Backend Availability Alert

Prometheus evaluates `monitoring/alerts.yml` every 15 seconds. The
`BackendMetricsUnavailable` rule becomes pending when
`up{job="high-concurrency-lab"} == 0` matches the configured backend target. It
fires after the condition remains true for one minute and returns to inactive
after a successful scrape and rule evaluation.

Open `http://localhost:9090/alerts` to inspect the state. Check
`http://localhost:9090/targets` for the failed scrape's error message. The rule
detects failed metric collection; it may indicate an application, connection, or
metrics-endpoint problem. The one-minute delay starts at the first matching rule
evaluation, so it is not an exact one-minute timer from process shutdown.

Validate and apply the configuration:

```sh
docker compose -f docker-compose.monitoring.yml run --rm --no-deps \
  --entrypoint /bin/promtool prometheus check config /etc/prometheus/prometheus.yml
docker compose -f docker-compose.monitoring.yml up -d prometheus
```

After editing rule contents without changing mounts, restart Prometheus to load
the updated files:

```sh
docker compose -f docker-compose.monitoring.yml restart prometheus
```

This local setup shows alert states in Prometheus. Email and other notification
delivery require a configured notification service such as Alertmanager.
