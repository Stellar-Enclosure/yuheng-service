# EDGE 无 Session OAuth Client

EDGE 使用 Spring Security 原生 Authorization Code/OIDC、PKCE 和 Refresh Token 客户端；自定义 adapter 只负责固定应用目录、一次性 SQL 事务和 Cookie 传输。WebSession、Session SecurityContext、AuthorizedClient token vault、隐式刷新和 Redis 状态均已移除。任何误用 WebSession 的组件都会失败。

## 路由与凭据

每个部署固定的 `appAlias` 使用 `/apps/{appAlias}/auth/login`、`callback`、`identity`、`csrf`、`refresh`、`refresh-operations/{operationId}` 和 `logout`。`identity` 只返回当前校验后的最小身份事实；`refresh` 必须显式 POST `{"operationId":"UUID"}`，状态重读不返回 Access/Refresh，也不补发 Cookie。

浏览器业务路径为 `/apps/{appAlias}` 加原始 API 路径，例如 `/apps/edge-tianshu/api/v1/tianshu/registry/...`；转发前剥离前两个路径段。原 `/api/**`、`/mcp/**`、`/v1/**` 使用直接 Bearer 通道，不能混入浏览器身份 Cookie。固定路由同时校验 alias、route id、资源 URI 与当前 JWT audience；上游只收到已校验 Bearer，不收到 Cookie 或客户端伪造的身份 Header。只有精确 GET `/api/v1/tianshu/i18n/public/bundle` 可以匿名转发。

Access Cookie 为 host-only、Secure、HttpOnly、SameSite=Lax，Path 为 `/apps/{alias}/`；Refresh 与浏览器绑定 Cookie 的 Path 为 `/apps/{alias}/auth`。Cookie 载体持续到授权族绝对到期，以便专用刷新分支核验已过期的签名 Access；业务请求始终严格校验 Access `exp`。CSRF 采用绑定 issuer/client/resource/subject/授权族/authn 的短期签名双提交证明，写请求另校验 Origin/Referer。JavaScript 不读取 Token。

IdP 授权、Token、JWKS、登录及 SSO 退出访问其已注册的公开 issuer。EDGE 不再代理 `/tenants/**` 或 `/platform/**`；`EGON_YUHENG_EDGE_OIDC_ISSUER` 及 B issuer 不得仍指向旧 EDGE 代理，避免回路。每个应用浏览器 Client 必须在 IdP 显式设置 `browserBound=true`（创建默认 false 不适用于 EDGE），并按部署 allowlist 配置 `postLogoutRedirectUris`；SERVICE Client 不得设置这两个浏览器属性。每个浏览器注册的回调必须精确配置为 `{EDGE_PUBLIC_ORIGIN}/apps/{alias}/auth/callback`。当前应用退出先提交本地撤销意图，再清理该应用 Cookie；响应中的 `remoteState=PENDING/UNKNOWN` 不能解释为远端已经退出。SSO/账号切换须继续导航 IdP 的 `ssoLogoutUri`，先关闭旧认证上下文关联的授权族；迟到旧 Cookie 会被 IdP 当前状态和 EDGE 本地关闭状态拒绝。

## 数据库与秘密配置

独立 PostgreSQL owner 保存一次性 OAuth 事务、Refresh 操作协调、撤销意图和交付回执；不能连接 IdP/RBAC/业务 owner 数据库。冻结迁移为 `db/migration/V1__edge_oauth_state.sql`，history 为 `flyway_edge_oauth_history`，不采用 baseline/repair。历史迁移不得修改。

- `EGON_YUHENG_EDGE_OAUTH_JDBC_URL`、`EGON_YUHENG_EDGE_OAUTH_DB_USERNAME`、`EGON_YUHENG_EDGE_OAUTH_DB_PASSWORD`：独立 EDGE 数据库及受控秘密。
- `EGON_YUHENG_EDGE_OAUTH_STATE_KEY_FILE`：仅所有者可读的 32 字节 AES 密钥文件；默认 key id 为 `v1`。轮换时通过 `encryption.active-key-id/key-files` 保留仍有短期密文引用的旧 key。
- `egon.yuheng.edge.identity-authority.bindings`：每个 `(issuer, source-resource)` 绑定独立受控 SERVICE 注册和 authority audience。示例配置覆盖每个应用的 A/B/PLATFORM issuer，秘密变量为 `EGON_YUHENG_EDGE_AUTHORITY_{APP}_{A|B|PLATFORM}_CLIENT_ID/CLIENT_SECRET`。不使用一个 EDGE SERVICE 身份查询任意目标。多个 alias 若共享完全相同资源 URI，应在部署配置中合并该 tuple；重复 tuple 使启动失败。
- `EGON_YUHENG_EDGE_IDENTITY_AUTHORITY_RESOURCE_URI`：IdP authority audience，与被查询的 source-resource 分开。SERVICE 客户端在 IdP 的 sourceResource 归属必须与 binding 一致；最小能力为 `tianquan-shoubing:security-state:resolve`、`tianquan-shoubing:refresh-context:resolve`、`tianquan-shoubing:audit:append`。

PKCE verifier 与 OIDC nonce 在回调 claim 后立即从数据库清除。撤销意图短期保存原 Refresh 密文，只用于原客户端的标准 revoke；只有 HTTP 200 确认成功，其他响应与断网保持重试/未知，终止时清除密文。SQL CAS/租约拥有跨实例协调事实。Refresh 未确认、响应丢失或取消会关闭旧代际并持久排队撤销；失败响应不清理另一个成功响应的 Cookie。数据库、状态查询、审计确认失败均拒绝请求，没有 Redis 或本地正向缓存降级。

## 不启动服务的验证

在仓库根目录使用 JDK 25：

```bash
./mvnw -pl stellar-enclosure-yuheng-edge-gateway test
```

测试不连接数据库、不启动应用/容器。覆盖真实 Spring Security/OIDC 过滤链（DB、Token HTTP 与 authority 边界替身）、禁止 Session、PKCE/nonce、重复回调、Cookie/Bearer 隔离、CSRF、公开 i18n、路由 audience、Refresh 成功/重复状态/取消/失败 CAS，以及迁移摘要、加密与 JDBC 调度。此证据不等同于真实 PostgreSQL、IdP、浏览器、多实例网络或跨应用账号切换验收。

## 已准备 PostgreSQL 环境的验证

`edge-oauth-postgresql-it` 只连接操作者已准备的隔离数据库，没有 Testcontainers 或进程启动。提供 `EGON_EDGE_TEST_PG_URL`、`EGON_EDGE_TEST_PG_USERNAME`、`EGON_EDGE_TEST_PG_PASSWORD`，并明确设置 `EGON_EDGE_TEST_PG_ALLOW_DDL=true`：

```bash
./mvnw -pl stellar-enclosure-yuheng-edge-gateway -Pedge-oauth-postgresql-it verify
```

该测试创建随机 `edge_oauth_it_*` schema，应用冻结迁移、核对双连接 claim/回滚/回执和租约恢复，最后仅清理该测试 schema。缺少隔离环境或 DDL 开关会失败。本任务没有执行此 PostgreSQL IT；真实数据库/网络/浏览器验收由已授权的后续运行阶段完成。
