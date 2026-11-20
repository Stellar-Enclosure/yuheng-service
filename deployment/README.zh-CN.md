# 玉衡部署与验收

玉衡 Admin 管理 URL 开放意图。Publisher 将已批准的意图投影成 `yuheng-routes` 命名空间里的 `HTTPRoute`。平台的 `edge-system/public-apps` Gateway 使用 `GatewayClass=cilium` 并终止公网 HTTPS。发布后的路由指向已批准的业务 Service、独立 MCP Access 或 Model Access Service。应用使用内网明文 HTTP 或 h2c，服务端执行 OAuth 和 access-guard 契约。

平台资源和发布流程在同级 `platform-deploy` 仓库，负责 Service 标识、证书引用、后端端口授权、网络策略与工作负载发布。通过玉衡读 API 查询 URL 收敛，再用正常 DNS、TLS 和已批准域名发起公网探测。

## 本地 fixture

把 PostgreSQL 测试密码放在容器可读的本地文件中，并在 `.env` 中填写文件路径。首次初始化创建带 pgvector 的 `gateway_admin` 数据库。根据场景，由对应模块的迁移初始化应用表结构。Redis 和 Kafka 都在 Compose 项目网络内。

在本目录执行：

```sh
cp .env.example .env
docker compose config --quiet
docker compose up -d
```

构建并启动 MVC、WebFlux 和 h2c fixture：

```sh
cd ..
./mvnw -pl stellar-enclosure-yuheng-test -am -DskipTests package
cd deployment
./scripts/demo.sh config
./scripts/demo.sh up -d --build
./scripts/demo.sh down
```

根据运行场景，通过 `.demo/` 下的对应文件提供应用配置。HTTP fixture 使用配置的 OAuth issuer/JWKS。RPC consumer 使用固定 Service 名称和 h2c 端口，Compose 网络别名匹配 Kubernetes DNS 形式。fixture 端口留在项目网络内。`demo/applications.json` 列出监听端口，`demo/policies.json` 记录独立集群验收所需的路由权重、重试和配额行为。

如需启动独立 Model Access fixture，先打包 `stellar-enclosure-model-access-service`，准备匹配的数据库 schema，并按 `.env.example` 指定本地配置和密钥版本目录。确保容器运行用户能读取挂载文件，再启用叠加配置。

```sh
docker compose -f compose.yml -f compose.llm.yml --profile llm up -d --build
```

在 Model Access overlay 后追加 `-f compose.ha.yml` 可启动两个独立进程。Compose 覆盖本地依赖和应用 fixture。Cilium 收敛、访问隔离、网关高可用和 GAMMA 权重要在平台环境验收。

## 已批准环境

将 `YUHENG_DEMO_BEARER_FILE` 指向适用于目标应用和 scope 的已签发 OAuth bearer token 文件。先读出最新强 ETag，每个新的变更意图使用独立的幂等键。

从已批准的管理网执行管理命令，使用集群内 HTTP Service 地址。

```sh
./scripts/demo.sh exposure-get "$APPROVED_ADMIN_ORIGIN" "$EXPOSURE_ID"
./scripts/demo.sh exposure-state "$APPROVED_ADMIN_ORIGIN" "$EXPOSURE_ID" "$EXPOSURE_ETAG" "$IDEMPOTENCY_KEY" demo/routes.json
./scripts/demo.sh verify "$APPROVED_PUBLIC_URL" "$EXPECTED_HTTP_STATUS"
```

HTTP 202 表示状态意图已落库。轮询读 API，确认对应路由已观测到当前 generation，再用新请求验证公网入口。管理面就绪检查留在管理网内，向 `wait-ready.sh` 传入该独立 readiness URL。

MCP 协议验收使用 `run-mcp-conformance.sh`，传入已发布的稳定版和草案版 MCP HTTPS URL，可指定证据目录。MCP 安全回归使用 `run-mcp-security.sh`，并提供已批准的 `YUHENG_CILIUM_MCP_ORIGIN`、`YUHENG_CILIUM_MCP_STABLE_PATH`、`YUHENG_CILIUM_MCP_RC_PATH` 和私有 `YUHENG_CILIUM_MCP_TOKEN_FILE`。两个路径均使用已发布的 `/mcp/` URL。协议行为、应用鉴权和 Cilium 可达性结果随发布证据留存。

### 逐节点收敛与请求权重

真实验收只对已审定的 fixture 开启。输入放在 `deployment/.cilium/` 或其他私有目录。`GatewayExposureConvergenceLiveIT` 使用 `yuheng.live.cilium.exposure.` 前缀，将 `admin-origin` 设为审定的内网 Kubernetes HTTP Service，再配置 `admin-token-file` 和 `public-token-file`。每个 `reversible`、`disposable`、`partitioned` 或 `websocket` fixture 各自配置 `id`、`public-url` 和私有 `targets-file`。目标文件为 JSON 数组，每个接客目标包含 `id`、`ip`、`port`。VIP 拨测走正常 DNS。逐节点拨测连接登记的地址，使用公网域名执行 SNI、证书校验和 HTTP Host 匹配。

- `enabled=true` 用同一个 ETag 并发提交两次关闭，核验关闭后恢复同一 HTTPRoute UID。`withdrawal-enabled=true` 只用于专门的终态撤回 fixture。
- `partition-enabled=true` 读取平台准备的分区场景。提供 `partitioned.stale-target-id` 和当前 `partitioned.generation`，验证仍有陈旧节点接收流量时，当前 CLOSED 世代不会报告 `SYNCED`。
- `websocket-drain-enabled=true` 使用可恢复的 WebSocket echo fixture，drain 预算需覆盖收敛。建立长流、关闭 URL、验证新请求及 Upgrade 被拒绝，再在协议时限内完成旧流。应用撤权与 drain 截止另随平台运行证据留存。

GAMMA 使用同一个 `gamma-run-id`，分别以 `stable-before`、`weighted`、`stable-after` 为 `gamma-phase` 运行。平台修改权重并在每个取样窗口前确认节点加载当前配置。以下属性均使用 `yuheng.live.cilium.` 前缀。

- unary gRPC 使用 `gamma-generation`、`rpc-driver-token-file`、`rpc-driver-origin` 和 `rpc-driver-comparison-origins`。第一个 Service DNS 绑定单个驱动进程，对照组提供至少两个由逗号分隔、分别绑定不同进程的 Service origin。所有驱动固定访问同一个 RPC 前门。`rpc-stable-provider` 和 `rpc-canary-provider` 各填写逗号分隔的审定 Pod 标识。
- HTTP 权重通过 `gamma-http-enabled=true` 开启。`gamma-http-north-url` 提供精确的 HTTPS `/api/providers/{requestId}`，`gamma-http-internal-origin` 提供服务相同路径的内网 Service。`internal-http` 与 `north-http` 各自提供 `gamma-<role>-generation`、`gamma-<role>-token-file`、`gamma-<role>-stable-providers` 和 `gamma-<role>-canary-providers`。
- 每种协议与连接模式记录尝试总数、后端 Pod 分布、失败状态和 95% Wilson 区间。验收同时关联健康端点、各接客 Envoy ACK 和实际上游连接及尝试次数，以核对收敛、负载均衡与重试。
