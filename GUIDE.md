# AdGate guide

## What it is

AdGate decides, in a few milliseconds, which ads may serve for one ad request, and says why the others may not.
If anything it needs is missing or stale, it says no (it "fails closed").

## Features

- Daily budgets and even pacing, from near-real-time spend (Kafka → Redis).
- Policy checks: placements and negative keywords.
- Traffic checks: bot user agents and device request rates.
- One reason code per declined ad, for analytics.
- Explain and replay any recent decision.
- API keys: a client key for decisions, an admin key for everything else.
- Metrics for Prometheus, plus a Grafana dashboard.

## Run it locally (no Docker)

You need JDK 21. Use four terminals, all in the repo folder.

1. Build: `./mvnw -DskipTests package`
2. Start the dev Kafka and Redis: `java -jar local-infra/target/local-infra.jar`
3. Start the spend aggregator: `java -jar spend-aggregator/target/spend-aggregator.jar`
4. Start the decision service: `java -jar eligibility-service/target/eligibility-service.jar`
5. Copy the `export ADGATE_ADMIN_KEY=... ADGATE_CLIENT_KEY=...` line that step 4 prints. Paste it in the last
   terminal, then run: `java -jar traffic-sim/target/traffic-sim.jar --sim.duration=60s`

The keys from step 4 are random and change on every restart. To keep them, set your own first:
`export ADGATE_ADMIN_KEY=$(openssl rand -hex 32) ADGATE_CLIENT_KEY=$(openssl rand -hex 32)`.

Try one decision yourself:

```bash
curl -s localhost:8081/v1/eligibility -H "X-API-Key: $ADGATE_CLIENT_KEY" -H 'Content-Type: application/json' \
  -d '{"requestId":"r-1","placement":"SEARCH_RESULTS","query":"photo editor",
       "device":{"id":"d-1"},"candidates":[{"adId":"ad-1","campaignId":"c-1"}]}'
```

## Run with Docker

1. `cp deploy/.env.example deploy/.env`
2. Fill in every empty value in `deploy/.env` (keys and passwords). Make each one with `openssl rand -hex 32`.
3. Start: `docker compose --env-file deploy/.env -f deploy/docker-compose.yml up --build -d`
4. Optional traffic: `docker compose --env-file deploy/.env -f deploy/docker-compose.yml --profile sim run --rm traffic-sim`
5. Grafana: http://localhost:3000. Log in with `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD`.

## Keep it safe

- **Keys.** The ad server gets only the client key. Keep the admin key for people and tools that manage campaigns.
- **Rotate keys.** List old and new, comma-separated: `ADGATE_CLIENT_KEY=old,new`. Restart, move callers to `new`,
  then remove `old` and restart again. Both keys work in between. The same works for `ADGATE_ADMIN_KEY`.
- **Production.** Run with `SPRING_PROFILES_ACTIVE=prod` (Docker does). It refuses to start without keys of 32+
  characters, and never makes keys for you.
- **Local-only ports.** Every port listens on `127.0.0.1` (Docker publishes on `127.0.0.1` too). To take calls from
  other machines, set `SERVER_ADDRESS=0.0.0.0` on the decision service only, and put it behind HTTPS.
- **Metrics port.** Metrics are on port `8181`, never on the API port. Keep it local.
- **Chaos switch.** `local-infra` and its chaos switch (port `8090`) are for development only. They have no
  passwords. Never run them on a server.
- **Never expose Redis or Kafka.** Kafka has no login, and Redis is only as safe as its password. Keep both on a
  private network. Never commit `deploy/.env`.

## Maintain it

- **Update dependencies.** Dependabot opens weekly PRs. Merge them when CI is green. Spring Boot 4 is a planned
  upgrade, not a weekly bump. Security overrides are listed in `pom.xml`; drop each once Spring Boot catches up.
- **Run the tests.** `./mvnw verify` (no Docker needed). For the browser playground: `cd playground && npm ci && npm test`.
- **Changed the engine?** `GoldenVectorsTest` fails until you regenerate `playground/src/engine/golden.json` (the
  command is in the test's message). Then update the TypeScript port in `playground/src/engine` until
  `npm test` passes again.
- **Check for known vulnerabilities.** CI does it on every change and weekly (OSV). Accepted ones, with reasons,
  are in `osv-scanner.toml`.
- **Read the metrics.** `curl localhost:8181/actuator/prometheus`, or Grafana. Watch
  `adgate_spend_watermark_lag_milliseconds` and refused calls (`http_server_requests_seconds_count{status="401"}`).
- **Add a campaign.**

  ```bash
  curl -X PUT localhost:8081/admin/campaigns/c-9 -H "X-API-Key: $ADGATE_ADMIN_KEY" \
    -H 'Content-Type: application/json' -d '{"status":"ACTIVE","dailyBudgetMicros":20000000,"pacing":"EVEN"}'
  ```

  Remove it with `curl -X DELETE localhost:8081/admin/campaigns/c-9 -H "X-API-Key: $ADGATE_ADMIN_KEY"`.

## Troubleshooting

- **401 or 403.** 401 means the key is missing or wrong. 403 means the wrong kind of key: decisions need the client
  key, everything else needs the admin key. After a restart without your own keys, the keys are new.
- **"Port already in use".** Change it: `--server.port=18081` on a service, `KAFKA_PORT` / `REDIS_PORT` for
  local-infra, or the port values in `deploy/.env` for Docker.
- **"APPLICATION FAILED TO START … API keys are not safe".** The `prod` profile needs both keys, different, and
  32+ characters each.
- **Everything says `SPEND_DATA_STALE`.** The spend aggregator is not running or cannot reach Kafka or Redis.
  Start it; decisions recover within seconds.
- **traffic-sim stops at once and asks for keys.** Paste the `export ...` line from the decision service first.
