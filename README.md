# yuheng-service

[English](README.md) | [中文](README.zh-CN.md)

HTTP/RPC, MCP and LLM gateways with a management control plane and browser Edge/BFF for Stellar Enclosure. All first-party modules start at **0.0.1**.

## Current capabilities

- Manage applications, credentials, gateway groups, interface catalogs and OpenAPI definitions; edit/validate route and policy drafts, publish immutable releases, compare versions, retry and roll back.
- Forward HTTP→HTTP, HTTP→RPC and RPC→RPC requests using Tianshu provider discovery, health checks, balancing and bounded retries; apply routing, authentication, traffic policies and tracing.
- Manage MCP servers, tools, resources, prompts, remote providers/mounts, approvals and the existing task extensions; provide an independent MCP data plane.
- Configure LLM channels/model policies and serve chat completion, embedding, response, message and model endpoints. The business gateway also contains streaming/multipart/WebSocket proxy support.
- Provide browser OIDC login, server-side OAuth token storage, BFF session/CSRF/logout and token relay through the Edge gateway. Expose runtime projections, dashboards and observability APIs from Admin.

## Modules

| Entry | Responsibility |
|---|---|
| [Contract](stellar-enclosure-yuheng-contract/) and [Core](stellar-enclosure-yuheng-core/) | Shared rule/release/provider contracts and routing/filter/security models. |
| [MCP Core](stellar-enclosure-yuheng-mcp-core/) and [Runtime Core](stellar-enclosure-yuheng-runtime-core/) | Shared MCP runtime primitives and non-executable engine traffic/resilience logic. |
| [Biz Gateway](stellar-enclosure-yuheng-biz-gateway/) | Executable Reactor Netty HTTP/RPC data plane. |
| [MCP Gateway](stellar-enclosure-yuheng-mcp-gateway/) | Executable MCP data plane. |
| [Edge Gateway](stellar-enclosure-yuheng-edge-gateway/) | Browser-facing Spring Cloud Gateway/OIDC/BFF entry point. |
| [LLM Gateway](stellar-enclosure-yuheng-llm-gateway/) | Executable model invocation gateway. |
| [Admin](stellar-enclosure-yuheng-admin/) | Persistent management control plane, rule compilation, release and management APIs. |
| [Starter](stellar-enclosure-yuheng-starter/) | Provider definition reporting and Yuheng metadata contribution to Tianshu HTTP registration. |
| [OpenAPI Starter](stellar-enclosure-yuheng-starter-openapi/), [WebMVC adapter](stellar-enclosure-yuheng-starter-openapi-webmvc/), [WebFlux adapter](stellar-enclosure-yuheng-starter-openapi-webflux/) | Provider OpenAPI reporting with framework-specific adapters. |
| [Test reactor](stellar-enclosure-yuheng-test/) | Provider/consumer examples and topology verification. |

## Integration and boundaries

[yuheng-web](https://github.com/Stellar-Enclosure/yuheng-web) owns the console. Tianshu owns registry/configuration state; Shoubing owns OAuth identity and resource-bound tokens; downstream services apply Jianshen user authorization or local SERVICE-scope checks. Knowledge bases and Wiki are owned by [yaoguang-service](https://github.com/Stellar-Enclosure/yaoguang-service), not Yuheng Admin.

The current MCP task protocol is an Egon extension, not evidence of standard MCP Tasks compatibility; see the [protocol boundary](README.zh-CN.md#mcp-扩展边界). Source/build checks do not establish live release propagation, provider routing or OAuth behavior. Use the [integration guide](docs/developer-integration.md), [deployment guide](deployment/README.md) and [performance scenarios](performance/README.md) for their separate validation steps.

## Build

Requires Java 25 and the included Maven Wrapper. The shared parent manages Spring Boot 4.1.2. Once the 0.0.1 dependencies are published:

```bash
./mvnw -B -ntp test-compile
```

Before the first Central publication, clone the eight Java repositories next to one another and bootstrap their artifact graph explicitly:

```bash
bash ../Stellar-Enclosure-Parent/scripts/bootstrap-workspace.sh \
  --workspace "$(cd .. && pwd)" \
  --local-repository "$HOME/.m2/repository"

# Resolve subsequent Maven commands and generator subprocesses from this same cache.
export MAVEN_ARGS="${MAVEN_ARGS:-} -Dmaven.repo.local=$HOME/.m2/repository"
```

The bootstrap installs parent/BOM models before building components and business starters in Maven reactor order. It compiles test sources and installs packages with runtime tests disabled. It never runs applications, databases, containers, browsers, or deploy.

## Publish to Maven Central

Publishes the Yuheng parent, contract, core, MCP core, runtime core and four Starters (`starter`, `starter-openapi`, `starter-openapi-webmvc`, `starter-openapi-webflux`). Admin, all four executable gateways and the entire test reactor are excluded. Requires the published platform parent, Components and Tianshu Starters.

Run these commands from this repository. Configure a Central Portal token under Maven server ID `central` and a local GPG signing key; credentials stay outside Git. See the [credential setup and initial dependency order](https://github.com/Stellar-Enclosure/Stellar-Enclosure-Parent/blob/main/docs/first-release.md).

```bash
# Verify release artifacts locally; signing and upload are disabled.
./mvnw -B -ntp -Prelease -Dgpg.skip=true -DskipITs -Dinvoker.skip=true -Darchetype.test.skip=true clean verify

# Sign and publish this repository's public artifacts.
./mvnw -B -ntp -Prelease -DskipITs -Dinvoker.skip=true -Darchetype.test.skip=true clean deploy
```

Unit tests remain enabled. `release` attaches sources, Javadoc and GPG signatures, then publishes through the Central Portal. Ordinary builds and Git pushes do not publish. Each published GAV is immutable: use a new version for changed content, and use `-pl` to select only unpublished modules after a partial release. Before the first release, use the Build section's local bootstrap or publish the prerequisite artifacts first; bootstrap installation alone does not publish dependencies.

## Compatibility and publication

Java packages/classes, Bean names, configuration keys, OAuth/RPC identities, APIs, and database ownership are retained. Existing SQL and DDL history are copied byte for byte. Artifact/build versions and necessary build paths change. A successful build does not imply application startup or live infrastructure acceptance.

Git pushes run validation only. Central and npm publication are separate user actions. CI defaults to initial source bootstrap; after publication set `STELLAR_USE_SOURCE_BOOTSTRAP=false` to consume published artifacts directly. Private organizations may supply the Actions secret `STELLAR_ENCLOSURE_READ_TOKEN` with repository read access.

Original MIT/LGPL licenses and author attribution are preserved.
