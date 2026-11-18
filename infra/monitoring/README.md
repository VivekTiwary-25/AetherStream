# Optional monitoring

Start the demo stack with Prometheus and Grafana enabled:

```sh
docker compose --profile monitoring up -d
```

- Prometheus: http://localhost:19090 — scrapes coordinator `/coord/metrics` and control-api `/actuator/prometheus` every 5 seconds. Targets may show as down until those endpoints are available.
- Grafana: http://localhost:13000 — open the provisioned **AetherStream engine** dashboard. Anonymous access is read-only. The admin password comes from `GRAFANA_ADMIN_PASSWORD`; its default, `admin`, is for development only.

The dashboard shows alive workers and execution generation; completed/aborted checkpoint totals and the last checkpoint duration; recovery totals and last recovery duration; records per second per worker; queue size per worker and queue, including the 1000-item capacity line; and each worker's backpressure state. Metrics may be absent until instrumentation is exposed by the services.
