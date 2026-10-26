# Yuheng non-Edge permission producers

The four executable hosts produce independent compiler manifests for business `xingyuan`, application `yuheng`. Each producer scans only its executable module. The Edge producer and CI delivery are documented separately.

| Producer | Expected module | Owned resource prefixes | Inventory |
| --- | --- | --- | --- |
| `yuheng-admin-java` | `stellar-enclosure-yuheng-admin` | `yuheng.api.`, `yuheng.field.` | Managed MVC APIs, reachable Jackson fields, explicit multipart input and native report/management metadata |
| `yuheng-llm-java` | `stellar-enclosure-yuheng-llm-gateway` | `yuheng.llm.` | Six existing protocol entries and health; native verifier, scopes and model rules remain authoritative |
| `yuheng-biz-java` | `stellar-enclosure-yuheng-biz-gateway` | `yuheng.biz.` | Native HTTP route table, gRPC registry and management metadata |
| `yuheng-mcp-java` | `stellar-enclosure-yuheng-mcp-gateway` | `yuheng.mcp.` | Current and legacy transports, protected-resource metadata and management metadata |

The `permission-producer` profile installs the annotation processor only on javac's processor path. The build-only CLI is an exec plugin dependency: `prepare-package` assembles FULL from the successful executable fragment; `verify` inspects the final `*-exec.jar` and writes `target/permissions/artifact-evidence.json`. Neither the processor nor CLI is an application runtime dependency.

Admin's `PermissionProducerCoverageTest` compares actual Spring mappings and every reachable request/response property with the compiler fragment. Request fields use write rules; local response projections keep existing JSON shapes while owning their read rules. `McpArtifactUploadCommandDTO` is the explicit multipart adapter contract; its guard rejects undeclared fields and authorizes submitted fields before reading file bytes. The field catalog resolves actual registered HTTP roots and nested DTOs, plus this explicit multipart input. The HTTP configuration places the permission-aware Jackson 2 reader before a Jackson 3 default while retaining String, byte and streaming converter precedence and preserves the AI carriers' strict unknown-field binding.

Biz HTTP methods and paths come from the activated `RuntimeHttpRoute` table, including custom HTTP methods. Its SOURCE-retained `@PermissionNativeDispatcher` points to the compiler-verified `pathPattern(): String` and `methods(): Set<String>` accessors. The exported target records the native route type and accessor sources; it does not invent a finite method list. The exact metadata-only holder is not a Spring bean or MVC controller. gRPC continues through its real registry and native security processors. MCP GET metadata does not implicitly claim HEAD support.

LLM `/v1/model-policies` remains a read-only capability query. Verified USER and SERVICE callers need `yuheng:llm:read`, each requested alias must authorize that subject, and only one or two distinct aliases can be queried. The existing native verifier and bounded protocol responses remain in use. The host explicitly uses its Jackson 2 codec for native JSON trees and enum wire values, and Security 7's HTTPS redirect checks the servlet's secure transport state.

## Runtime configuration boundary

The hosts use the current identity SQL authority clients for revocation/state and durable audit receipts. Legacy authorization Redis runtime settings and Redis audit/revocation settings are removed. Every existing trusted issuer has a fixed authority binding under `egon.cola.platform.tianquan.shoubing.authority`; destinations never come from request/JWT values. Deployment must supply:

- `TIANQUAN_SHOUBING_AUTHORITY_BASE_URI` and `TIANQUAN_SHOUBING_AUTHORITY_RESOURCE_URI`.
- Per-host `YUHENG_<ADMIN|LLM|BIZ|MCP>_AUTHORITY_SERVICE_APP_ID`.
- Per-host, per-issuer `YUHENG_<HOST>_AUTHORITY_<TENANT_A|TENANT_B|PLATFORM>_REGISTRATION_ID`, matching an explicitly configured OAuth client registration. Admin retains its two tenant issuers; the other hosts retain their existing tenant and platform issuer inventories.
- Only `tianquan-shoubing:security-state:resolve` and `tianquan-shoubing:audit:append` in each authority binding, with the issuer's exact TENANT/PLATFORM context and tenant ID.

Admin and LLM business cache clients are owned by those hosts, independent from authorization state. Their `redissonClient` beans use `yuheng.admin.cache.redis.*` and `yuheng.llm.cache.redis.*`, configured through `YUHENG_<ADMIN|LLM>_CACHE_REDIS_ADDRESS`, optional `..._DATABASE` and `..._PASSWORD_FILE`. The password is read from the file at runtime and is never placed in argv or logged. Existing Biz traffic and MCP protocol Redis clients keep their own responsibilities. No Redis or identity SQL service was contacted during this task's offline verification.

## Offline verification and explicitly deferred listeners

Run from this repository with JDK 25. The following command compiles, runs offline tests, assembles FULL manifests and verifies all four final executable artifacts. It does not start application servers, databases or containers:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./mvnw -B -ntp -fae \
  -pl :stellar-enclosure-yuheng-admin,:stellar-enclosure-yuheng-llm-gateway,:stellar-enclosure-yuheng-biz-gateway,:stellar-enclosure-yuheng-mcp-gateway \
  -Ppermission-producer -Dpermission.commit="$(git rev-parse HEAD)" -Dpermission.buildId=local-s27-y \
  '-Dtest=*Test,!LlmLocalCredentialEgressTest,!McpGatewayHttpServerTest,!McpTransportIntegrationTest,!RpcGatewayCredentialForwardingTest,!RpcGatewayServerTest,!GatewayHttpListenerStreamingTest,!GatewayHttpServerWebSocketTest,!GatewayHttpServerTest,!ReactorNettyHttpUpstreamAdapterTest,!DefaultGatewayHttpDataPlaneHandlerCredentialForwardingTest,!ReactorNettyWebSocketUpstreamAdapterTest,!GatewayOpenAiTransportComponentTest,!GatewayTransportTimeoutComponentTest,!GatewayCancellationPropagationTest' \
  clean verify
```

The excluded classes include tests that start real HTTP, gRPC or WebSocket listeners/upstreams. Those listener methods are **NOT_RUN** and remain available without a permanent disable in the project. Four in-process methods from the mixed MCP class are safe and can be run separately:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./mvnw -B -ntp -pl :stellar-enclosure-yuheng-mcp-gateway \
  '-Dtest=McpTransportIntegrationTest#cancellationClosesTheOuterPhysicalRequestScope+strictCapabilityReadKeepsDenyAndUnavailableDistinctAndBusinessUncalled+preservesSecurityStatusesWithoutDispatchingBusinessOrStartingAListener+keepsInitializationAndMissingSessionProtocolShapesInProcess' test
```

After the user explicitly authorizes runtime listeners, run the deferred classes with their own test selectors. PostgreSQL/Redis/container integration tests and real identity/permission catalog activation, authority calls, bootstrap execution and browser acceptance remain separate runtime evidence. Offline PASS and final artifact verification do not establish those results.

## Recorded offline result

On 2026-10-26, the final JDK 25 clean profile verification passed with 621 tests, zero failures, zero errors and zero skips: Admin 370, LLM 63, Biz 124 and MCP 64. Four additional in-process MCP transport methods passed separately. The final FULL manifests contain Admin 95 API / 816 FIELD resources and 14 permission definitions; LLM 7, Biz 5 and MCP 6 native API resources. Each final executable JAR passed the build CLI's artifact verification, contains the exact assembled manifest and contains neither the annotation processor nor manifest CLI in `BOOT-INF/lib`.

The listener exclusions and database/Redis/runtime boundaries above remain NOT_RUN. The focused compiler extension passed all 49 contract and 33 processor tests, including positive and negative JavaCompiler fixtures for dynamic dispatchers. No migration SQL, commit or push was performed.
