# Yuheng deployment and acceptance

Yuheng Admin manages URL exposure intent. The publisher reconciles approved `HTTPRoute` objects in `yuheng-routes`. The platform `edge-system/public-apps` Gateway uses `GatewayClass=cilium` and terminates public TLS. A published route targets the approved business Service or the independent MCP or Model Access Service. Applications receive plain HTTP or h2c on internal listeners and authorize requests using their own OAuth and access-guard contracts.

The platform manifests and release process live in the sibling `platform-deploy` repository. They own Service identities, certificate references, backend port grants, network policy, and workload rollout. A separate logical read through Yuheng reports exposure convergence. The public probe uses the approved hostname with its normal TLS and DNS.

## Local fixtures

Put the local PostgreSQL password in a file readable by the container and set its path in `.env`. A fresh fixture database initializes `gateway_admin` with pgvector. Install any additional application schema required by the scenario using its owning migration. The fixture Redis and Kafka instances are local to the Compose project.

From this directory:

```sh
cp .env.example .env
docker compose config --quiet
docker compose up -d
```

To build the MVC, WebFlux and h2c executable fixture JARs, use the repository Maven reactor:

```sh
cd ..
./mvnw -pl stellar-enclosure-yuheng-test -am -DskipTests package
cd deployment
./scripts/demo.sh config
./scripts/demo.sh up -d --build
./scripts/demo.sh down
```

Provide fixture-specific startup settings via the corresponding files under `.demo/` if needed. The HTTP fixtures use the configured OAuth issuer/JWKS. The RPC consumer uses a fixed service name and h2c port, with a Compose network alias matching the Kubernetes DNS form. Fixture ports stay on the project network. `demo/applications.json` lists their listeners and `demo/policies.json` describes the expected mesh and quota behavior for the separate environment check.

To start the independent Model Access fixture, package `stellar-enclosure-model-access-service`, provision its compatible database schema, and provide the explicit local configuration and secret-version directories from `.env.example`. The configured user must be able to read mounted files. Start the overlay with:

```sh
docker compose -f compose.yml -f compose.llm.yml --profile llm up -d --build
```

Add `-f compose.ha.yml` after the model overlay to exercise two independent model processes. Compose provides local dependencies and application fixtures. Cilium, route convergence, service reachability policy, gateway HA and GAMMA weights require the platform environment.

## Approved environment

Use an issued OAuth bearer token file for the selected application and its scopes. Export `YUHENG_DEMO_BEARER_FILE` to point to that file. Run `exposure-get` first and record the strong ETag. Use a fresh idempotency key for each intentional state change.

Run the management commands from the approved management network using the internal HTTP Service origin.

```sh
./scripts/demo.sh exposure-get "$APPROVED_ADMIN_ORIGIN" "$EXPOSURE_ID"
./scripts/demo.sh exposure-state "$APPROVED_ADMIN_ORIGIN" "$EXPOSURE_ID" "$EXPOSURE_ETAG" "$IDEMPOTENCY_KEY" demo/routes.json
./scripts/demo.sh verify "$APPROVED_PUBLIC_URL" "$EXPECTED_HTTP_STATUS"
```

A state update returning HTTP 202 records intent. Poll the read API and verify the observed generation for the reviewed route before treating it as effective. Probe the public URL as a new request. Keep protected management health checks on the management network. Invoke `wait-ready.sh` with that exact readiness URL.

For MCP, `run-mcp-conformance.sh` takes the explicit published stable and draft MCP HTTPS URLs and an optional evidence directory. Use `run-mcp-security.sh` with the approved `YUHENG_CILIUM_MCP_ORIGIN`, explicit `YUHENG_CILIUM_MCP_STABLE_PATH` and `YUHENG_CILIUM_MCP_RC_PATH`, and the protected `YUHENG_CILIUM_MCP_TOKEN_FILE`. Both paths must be published under `/mcp/`. Retain conformance, application authorization and Cilium reachability results with the rollout evidence.

### Listener convergence and request weights

Run the live tests only against reviewed fixtures. Keep input under `deployment/.cilium/` or another private directory. For `GatewayExposureConvergenceLiveIT`, use the `yuheng.live.cilium.exposure.` prefix. Set `admin-origin` to the reviewed internal Kubernetes HTTP Service, then set `admin-token-file` and `public-token-file`. Each fixture (`reversible`, `disposable`, `partitioned` or `websocket`) requires its own `id`, `public-url` and private `targets-file`. The file is a JSON array of objects with `id`, `ip` and `port` for every reviewed serving listener target. The VIP request uses ordinary DNS. Each node probe connects to the recorded address while using the public hostname in SNI, HTTP Host and certificate verification.

- Use `enabled=true` for two competing close commands with the same ETag, reopening the same route UID after verification. Use `withdrawal-enabled=true` only for the dedicated terminal fixture.
- For `partition-enabled=true`, the test expects the platform fixture to hold a reviewed stale node on the serving path after a close. Supply `partitioned.stale-target-id` and the current `partitioned.generation`. The probe confirms that a node still admitting requests prevents `SYNCED`.
- For `websocket-drain-enabled=true`, configure a reversible WebSocket echo fixture with a drain budget that covers reconciliation. The test opens a flow, closes the URL, checks new requests and upgrade rejection, then completes the old flow within its protocol timeout. Keep application-level revocation and drain-deadline evidence with the platform run.

For GAMMA, run each test in the `stable-before`, `weighted`, and `stable-after` windows using the same `gamma-run-id` and the current `gamma-phase`. The platform owns weights and waits for current node acknowledgements before each sampling window. The property prefix is `yuheng.live.cilium.`.

- The unary gRPC probe takes `gamma-generation`, `rpc-driver-token-file`, `rpc-driver-origin` and `rpc-driver-comparison-origins`. The first origin is a stable Kubernetes Service DNS name for one driver process. The comparison takes at least two different, comma-separated Service origins, each pinned to a distinct driver process. Bind every driver to the same reviewed RPC frontend. Set `rpc-stable-provider` and `rpc-canary-provider` to the comma-separated approved Pod identities for each version.
- Enable HTTP weights with `gamma-http-enabled=true`. Use `gamma-http-north-url` for the exact HTTPS `/api/providers/{requestId}` fixture and `gamma-http-internal-origin` for the internal Service serving the same path. For each role `internal-http` and `north-http`, provide `gamma-<role>-generation`, `gamma-<role>-token-file`, `gamma-<role>-stable-providers` and `gamma-<role>-canary-providers`.
- Each protocol and connection mode records the attempt denominator, provider counts, failure statuses and a 95% Wilson interval. Correlate sample evidence with active endpoints, each serving Envoy acknowledgement and upstream attempt/connection telemetry before signing off on convergence, load balancing and retry behavior.
