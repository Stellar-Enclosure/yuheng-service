# MCP工具的可信SERVICE逐权限Scope授权

| Field | Value |
| --- | --- |
| Document | `2026-11-01-15-57-mcp-service-scope-authorization.md` |
| Template Version | `5` |
| Status | `Review` |
| Created | `2026-11-01 15:57 Asia/Shanghai` |
| Updated | `2026-11-01 16:32 Asia/Shanghai` |
| Owner | 用户原蓝图授权，Root交付与验证 |
| Repository | `yuheng-service` |
| Scope | 13paths；HOST scope翻译/严格SERVICE授权/真实consumer，不改工具发布格式 |
| Source Requirement | 原蓝图T3-07与§12.1/13.1/13.2/17，可信SERVICE独立权限且不冒充USER |
| Baseline Revision | `main@c27fcde728f191780c872a5b93831afe60b4ab3f` clean |
| Implements Spec | [平台蓝图](../../../../docs/plans/agent-framework-upgrade-plan.md) |
| Spec Status | Legacy blueprint；用户明确继续执行，非Accepted文档 |
| Spec Revision | 2026-11-01；SHA-256 `ea0a85117b16a99c365b88d49729b5d5b8e0025abfbe7055c5f60bf6fc81cd54` |
| Effective Specs | [平台蓝图](../../../../docs/plans/agent-framework-upgrade-plan.md) |
| Depends On Plans | `None` |
| Supersedes | `None` |
| Superseded By | `None` |
| Related Plans | `None` |

## 1. Summary
沿用McpAuthorizationPort与Rbac3McpAuthorizationAdapter，对实际已验证SERVICE做逐permission scope检查。HOST静态有限映射不授角色/不证明schema；原USER snapshot与发布tool DTO/JSON完全保持。严格同observation、self client、当前token时间、零RBAC minima与原拒绝审计，真实factory绑定同Clock。一个逻辑Step、13paths、一commit；MCP客户端/AR journal/native仍后续。

## 2. Target Spec and Effective Design
原蓝图§12.1要求MCP同治理与工具执行前授权、无隐藏loop；§13.1区分USER/SERVICE与可信原始上下文；§13.2保持已发布版本；§17要求伪造身份与授权失败闭合。当前strict adapter只允许USER，因此异步SERVICE无法经玉衡工具入口。复用既有同次GatewayVerifiedIdentityBO/verifiedScopes；现IAM明确SERVICE用Shoubing scopes，不增USER式permission API。只实现原授权模型的缺失consumer分支，无新认证机制。

## 3. Effective Requirements and Acceptance
| ID | Source | Acceptance | Files |
| --- | --- | --- | --- |
| PLAN-REQ-001 | §12.1/13.1/13.2/17；T3-07 | 默认空finite immutable HOST精确resource+permission→业务scopes；旧10ctor/canonical11与strictindex兼容 | 本Step13paths按§7分工 |
| PLAN-REQ-002 | §12.1/13.1/13.2/17；T3-07 | 同物理请求可信SERVICE、自身份、全部requiredPermissions/fullverifiedScopes，禁止USER模拟或通用scope授权全部工具 | 本Step13paths按§7分工 |
| PLAN-REQ-003 | §12.1/13.1/13.2/17；T3-07 | iat≤now<exp决策+handoff、同active observation、零RBAC minima；失败审计与503边界不弱化 | 本Step13paths按§7分工 |
| PLAN-REQ-004 | §12.1/13.1/13.2/17；T3-07 | 实际managed handler factory接同bindings/Clock；oldUSER/constructors/published JSON/schema/权限不改 | 本Step13paths按§7分工 |
| PLAN-REQ-005 | §12.1/13.1/13.2/17；T3-07 | 两YAML默认空/无runtime或角色动作，source regression与13path单提交/17MC10Rules实证 | 本Step13paths按§7分工 |

## 4. Implementation Strategy and Dependency Order
### 4.1 Repository baseline
Yuheng c27fcde clean；Tianji前步55ffe9d已9path提交且clean。原蓝图SHA见metadata，所有现有SQL不可变。MCP gateway已依赖实际Shoubing adapter/Jianshen loader/Validator，无新增依赖。
### 4.2 Test-first strategy
Props最小component/old10/nested record仅供编译，原guards保持；直接构造与合法ValueObject实际RED后新guards/canonical绑定，合法Binder成功后执行unknownindex反例；若现框架已严格拒绝，则复用既有能力，按CLAR-003不另造生命周期。PropsGREEN后Adapter新6factory仅UOE编译声明，Service+安全inprocess测试先RED，再实现。AdapterGREEN后实际assembly test要求传完整bindings/同Clock，旧configured4会有效RED，再改factory。最后YAML断言先RED再两个emptykey。compiler/fixture失败不计行为RED。
### 4.3 Sequential and parallel boundaries
worker1仅Props/Test2paths；worker2仅Adapter/RbacIntegration/TransportIntegration3paths；Root其余8paths。不是独自在代码库，保他人工作，不revert他人。Root统一Maven，各owner冻结全部Java后才运行；测试草稿可并行仓库外准备，依赖GREEN后才sourceGO。无后续Step先写。
### 4.4 Commit boundaries
一个完整授权逻辑切片13paths，全部gates后立即 `feat(mcp): authorize verified services with exact tool scopes`，无push/PR/部署/激活。
### 4.5 Spec Simplicity and Implementation-necessity Audit
| Element | Source necessity | Current evidence | Direct/reuse alternative | Decision |
| --- | --- | --- | --- | --- |
| SERVICE工具授权 | §12/13可信SERVICE不得模拟USER | adapter strict114明确USER-only | 原context evidence+fullscopes与每requiredPermission映射 | Implement现Adapter分支 |
| published tool scope新字段 | §13保持版本，非必要新接口 | RuntimeTool15args/两个codec NON_NULL，新增empty会改旧SHA；producer重建白名单 | Gate已注入完整implicitpermission，HOST映射即可 | 不改DTO/admin/JSON/旧发布hash |
| 新policy registry/loader | §13无额外框架 | existingMcpAuthorizationPort/loader/observer | private immutable map+sameClock | 不加层/Bean/cache/job/dependency |
| 配置位置 | 需要strict/immutable HOST policy | EngineProperties @Validated strict record；factory已有properties；mutablelegacy1100行Aware早于bind | 在现immutable bootstrap身份配置增可选列表/oldctor | Implement，无legacycapability重映射 |
### 4.6 Change-unit Dependency Matrix
| Unit | Requirements | RED | Prerequisite | Produces | Consumer | Step |
| --- | --- | --- | --- | --- | --- | --- |
| strict HOST profiles | PLAN-REQ-001 | PropsTest | compiledecl/合法metadata | immutable有限列表 | Adapter | Step1 |
| actual SERVICE scopes | PLAN-REQ-002/003 | RbacIntegration+safeTransport | PropsGREEN/newfactoryUOE | failclosed决策 | 现Gate/handler | Step1 |
| managed factory/defaults | PLAN-REQ-004/005 | newAssembly+YamlConfigTest | AdapterGREEN | 实际配置consumer | 用户review | Step1 |
### 4.7 Java, Spring, and Egon-COLA Implementation Standards
| Concern | Current repository evidence | Effective Spec decision | Planned implementation consequence | Owning Steps/checks |
| --- | --- | --- | --- | --- |
| Architecture | 既有Yuheng技术Gateway Adapter/Config/Core port | 当前结构保持，非新CRUD/业务模块 | 无DDL/Entity/Mapper/新层 | Step1 MC-ARCH-001 |
| Validation/model | @Validated strict record、Jakarta、immutable context | @Valid列表/VO+exact invariant/NoUnbound；metadata不是authority | record VO豁免普通class Lombok；old10ctor+canonical11 | Step1 MC-VALID-001/MC-MODEL-001 |
| Business Adapter | 现@Slf4j/private RequiredArgsConstructor/配置factory | 保日志/Factory构造，不新增Service DI | private新增metadataMap/Clock；原publiccompat | Step1 MC-LOG-001/MC-BEAN-001 |
| Conversion/config/time | 原typed immutable request/BO、gatewayClock | 无实体转换/协议序列化变化；java.time | 无新Converter/JSON库，2profile key parity | Step1 MC-CONVERT-001/MC-JSON-001/MC-TIME-001 |
| Pattern | 现McpAuthorizationPort与thinAdapter | Adapter翻译可信身份与外部授权模型；原factory选择依赖 | 不为两个类型分支新增策略类/接口 | Step1 MC-PATTERN-001 |

| Need | Candidates inspected | Exact evidence | Fit/gap | Decision | Added dependency/custom code | Owning Step/check |
| --- | --- | --- | --- | --- | --- | --- |
| verified SERVICE scope | actualIdpIdentityAuthenticationProvider/GatewayVerifiedIdentityBO | BO fullSet≤64，同physical scope; adapter当前共同校验 | 已有可信信息，缺pertoolconsumer | reuse | None | Step1 MC-REUSE-001 |
| immutable strict HOST list | Spring Binder/NoUnbound/@Validated | EngineProperties record10与Outbound兼容构造模式 | 可选列表即可，无新DTO协议 | reuse | None | Step1 MC-DEP-001 |
| audit/versions | existingobserveDecision/marker/USERsnapshot | DENY一次原proof，fenced503，SERVICE无USERsnapshot | 复用原审计，版本只零 | reuse | None | Step1 MC-REUSE-001 |
### 4.8 User-mandated Java Rule Implementation Matrix
| Literal rule | Spec source | Repository evidence | Exact files and order | Pseudocode obligations | Validation gate | Steps | Status/blocker |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Rule 1 | §12/13/17 | 既有Config/Adapter命名，nestedServiceScopeBindingVO语义值对象 | §7 exact13paths | §6/逐文件 | source/REDGREEN/manual | Step1 | PASS |
| Rule 2 | §12/13/17 | Jakarta @Valid/constraints+canonical invariants/既有strict NoUnbound与实际scope/proof每边界 | §7 exact13paths | §6/逐文件 | source/REDGREEN/manual | Step1 | PASS |
| Rule 3 | §12/13/17 | immutable record/oldctor/private final map，现Adapter Lombok基线保持 | §7 exact13paths | §6/逐文件 | source/REDGREEN/manual | Step1 | PASS |
| Rule 4 | §12/13/17 | 原@Slf4j/RequiredArgsConstructor私人Factory、qualifiers、同gatewayClock，无新Service ctorDI | §7 exact13paths | §6/逐文件 | source/REDGREEN/manual | Step1 | PASS |
| Rule 5 | §12/13/17 | 现Boot/Spring/JDK集合与URI；无新依赖 | §7 exact13paths | §6/逐文件 | source/REDGREEN/manual | Step1 | PASS |
| Rule 6 | §12/13/17 | 现Jackson wire/旧tool JSON/hash不变，无ordinal | §7 exact13paths | §6/逐文件 | source/REDGREEN/manual | Step1 | PASS |
| Rule 7 | §12/13/17 | base+operations同一emptykey/默认deny | §7 exact13paths | §6/逐文件 | source/REDGREEN/manual | Step1 | PASS |
| Rule 9 | §12/13/17 | 现Adapter/McpAuthorizationPort+Factory模式隔离身份授权翻译，无新层 | §7 exact13paths | §6/逐文件 | source/REDGREEN/manual | Step1 | PASS |
| Rule 10 | §12/13/17 | java.time Instant/Clock决策与handoff边界 | §7 exact13paths | §6/逐文件 | source/REDGREEN/manual | Step1 | PASS |
| Rule 11 | §12/13/17 | 现技术Gateway结构/原Outbox审计保持，不动SQL/CRUD/业务Event | §7 exact13paths | §6/逐文件 | source/REDGREEN/manual | Step1 | PASS |

### 4.9 Time semantics and conversion contract
Time contract: Required
| Time ID | Fields/symbols | Semantics | Java/runtime type | Storage policy | Wire policy | Business zone source | Display zone source | Boundary / DST rules | Precision / Clock | Compatibility | Verification |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TIME-001 | SERVICE request.issuedAt/expiresAt +decision/handoff | Occurred instant | Instant/Clock | Not persisted | Internal | Not applicable: 技术token时刻 | Not applicable: 无UI | issuedAt≤now<expiresAt；初始过期/未来DENY需原审计；allow交付前失去scope/时间变失效503，不能伪造allow/deny | 注入同gatewayClock，纳秒比较不floor；UTC Instant | USER旧路径不改；不把入口provider deadline当tool/run总限 | Step 1 TEST-002/003 |
| TIME-002 | GatewayAuthContext.deadline | Occurred instant | Instant | Not persisted | Internal | Not applicable: 请求生命周期 | Not applicable: 无UI | scope active只是未close；SQL撤销核验在每physical入口，不承诺同长请求逐tool SQL复查 | 原close/synchronized scope；无新timer | 当前provider window/原物理请求顺序保持 | Step 1 TEST-002/003 |

## 5. Change File Tree
| Operation | Repository-relative path | Responsibility | Step |
| --- | --- | --- | --- |
| MODIFY | stellar-enclosure-yuheng-mcp-gateway/src/test/java/top/egon/cola/component/yuheng/mcp/engine/config/McpGatewayEnginePropertiesTest.java | 有限HOST scope配置与old10/canonical11兼容 | Step1 |
| MODIFY | stellar-enclosure-yuheng-mcp-gateway/src/main/java/top/egon/cola/component/yuheng/mcp/engine/config/McpGatewayEngineProperties.java | 只增可选不可变HOST权限翻译元数据 | Step1 |
| MODIFY | stellar-enclosure-yuheng-mcp-gateway/src/test/java/top/egon/cola/component/yuheng/mcp/engine/mcp/adapter/security/McpRbac3IntegrationTest.java | 实际严格Adapter SERVICE授权/拒绝与USER回归 | Step1 |
| MODIFY | stellar-enclosure-yuheng-mcp-gateway/src/test/java/top/egon/cola/component/yuheng/mcp/engine/mcp/service/McpTransportIntegrationTest.java | 不启动监听器的真实handler/gate SERVICE工具调用 | Step1 |
| MODIFY | stellar-enclosure-yuheng-mcp-gateway/src/main/java/top/egon/cola/component/yuheng/mcp/engine/mcp/adapter/security/Rbac3McpAuthorizationAdapter.java | 复用同一严格验证与审计的SERVICE scope策略 | Step1 |
| CREATE | stellar-enclosure-yuheng-mcp-gateway/src/test/java/top/egon/cola/component/yuheng/mcp/engine/bootstrap/config/McpServiceScopeAssemblyTest.java | 验证实际工厂选择新配置与同Clock | Step1 |
| MODIFY | stellar-enclosure-yuheng-mcp-gateway/src/main/java/top/egon/cola/component/yuheng/mcp/engine/bootstrap/config/McpGatewayEngineConfiguration.java | 将已配置的HOST列表与原Clock接到实际授权consumer | Step1 |
| MODIFY | stellar-enclosure-yuheng-mcp-gateway/src/test/java/top/egon/cola/component/yuheng/mcp/engine/mcp/service/GatewayEngineRbac3ConfigurationTest.java | 两个YAML配置的默认空映射与key parity | Step1 |
| MODIFY | stellar-enclosure-yuheng-mcp-gateway/src/main/resources/application.yml | 默认不开放SERVICE工具 | Step1 |
| MODIFY | stellar-enclosure-yuheng-mcp-gateway/src/main/resources/application-operations.yml | operations配置key parity | Step1 |
| MODIFY | README.md | 说明原生SERVICE scope授权与边界 | Step1 |
| CREATE | docs/egon/plan/2026-11-01-15-57-mcp-service-scope-authorization.md | 锁定单逻辑任务13文件顺序/时间/安全/门禁 | Step1 |
| CREATE | docs/egon/evidence/2026-11-01-mcp-service-scope-authorization.md | 记录实际验证与限制 | Step1 |

## 6. Prerequisites, Constraints, and Plan Clarifications
**Exact config.** McpGatewayEngineProperties追加第11field `@Size(max=256) List<@NotNull @Valid ServiceScopeBindingVO> serviceScopeBindings`；null→empty/deepimmutable，old10 public ctor保留委托empty；canonical显式ConstructorBinding（非Autowired）。nested VO为 `(URI resourceUri,String permission,Set<String> scopes)`，用Jakarta约束与canonical guard双边界。URI必须absoluteHTTPS/host、ASCII/normalized、无userinfo/query/fragment、≤2048字符；permission精确非空graphicASCII≤512，无trim别名/whitespace/control。Scopes每项 `[A-Za-z0-9._:-]{1,128}`，集合1..64，拒openid/profile/email/phone/address/offline_access，copy/null元素拒；总binding≤256、相同(resourceASCII,permission)重复一律拒。现strict record的Boot默认NoUnbound路径已由合法双项Binder及index0/1未知字段反例实证闭合（含listener=false），按CLAR-003复用；不新增EnvironmentAware或setEnvironment。两YAML mcp-engine仅新增service-scope-bindings:[]；不触legacy engine.mcp.security或发布tool DTO。

**Exact Adapter.** 新public static factory `createConfigured(SingleFlightSnapshotLoader,IdpObservedVerificationComponent,IdpSecurityMetricsRecorder,Validator,List<ServiceScopeBindingVO>,Clock)` 复制校验有限Map<List<String>,Set<String>>，不加registry类型。保legacy1 ctor与原configured4；旧configured4空map仍SERVICE拒绝USER_PRINCIPAL_REQUIRED，USER真实snapshot流程原样。Private RequiredArgsConstructor沿现Factory模式，非Spring DI constructor；现业务@Slf4j/qualifiers保，Clock来源gatewayClock，不加手写DI。

**Exact permission decision.** 原共同verifiedContext/同Reactor keys/同active authorizationScope/实际verifiedContext/proof validation/issuer/resource/subject/tenant/client/token/iat/exp/ingress scopes先执行。USER分支不变；SERVICE遇empty index在共同验证后仍按旧USER_PRINCIPAL_REQUIRED拒绝，不读新Clock/不调用USERloader，以保configured4行为；非空映射时实际SERVICE必须subject==clientId。初始token检查Clock iat≤now<exp；requiredPermissions.size≤256、每permission(resource exact)都映射非空scopes，union≤64，actual同次fullverifiedScopes containsAll union；缺任一用既有TIANQUAN_JIANSHEN_PERMISSION_DENIED拒绝（self/初始时间失败同码，不新增公开错误协议）。不得读512char service-scopes描述属性或request fields建立身份。三minimum版本只零，非零同SNAPSHOT_FENCED走503；allow版本0/0/0不伪装USER版本。初始权限/时间DENY沿原observeDecision和一次proof marker；audit失败503无allowfallback。before SERVICE ALLOW复核observation.requireAvailable/同verifiedContext pointer、active与当前token时间；失效503不交付allow，不制造原始拒绝事实。UNKNOWN类型沿原拒绝，不进入USERloader。新fields/private methods只在现Adapter，不扩公共port/request。

**Actual trust/time boundary.** GatewayAuthContext来自该physical请求的真实IdpIdentityAuthenticationProvider和实际Gate绑定。TokenAuditProofBO公开record仅secret-free审计metadata，不能单独作认证凭据；新SERVICEfixture须真实provider+fake verifier产生coherent机器subject/client/tenant/fullscopes/proof，不沿用USER password/MFA伪造SERVICE。currentSQL revocation/Resource状态已在physical IdpGatewayJwtVerifier入口验证；active只未关闭，不宣称长请求每tool重新读SQL。入口context.deadline是provider窗口不扩成总tool/run时限。HOST mapping是permission级翻译，不授schema/release；当前request-bound tool catalog/provider fingerprint与AS后续固定profiles各自保留职责。

**Wiring/default/dependency.** gatewayMcpHttpHandler现已有properties+gatewayClock，新6factory参数精确来自两者。Managed existingSnapshotLoader/observer/metrics/validator缺失的unavailable分支保持，SERVICE不调用USERloader。无新Bean/HTTP endpoint/listener/cache/job/依赖/数据库/权限激活。所有旧SQL immutable；Profile为空默认SERVICE继续不可用。每次Maven前全部Java冻结，Root统一JDK25；不运行有真实listener的TransportIntegration两个方法。

**CLAR-001 local necessity.** 复用既有Gate完整requiredPermissions（含implicit工具key）和同次verifiedScopes，用HOST映射实现原已选SERVICE scopes机制。无需published tool新字段/新USER permission API；避免canonicalNON_NULL序列化改旧SHA和adminproducer多链变化。此映射不声称锁schema。
**CLAR-002 config placement.** 现immutable @Validated/strict EngineProperties可严格绑定安全身份策略；不在legacy mutable RuntimeProperties增新的Aware/lifecycle cache，保持原capability树不改。old10ctor与newcanonical11回归明确，不改外部tool协议。

**CLAR-003 actual strict Binder reuse.** 初始compile-only直接6用例19.463s/16:04:26实际全部行为失败，之后才实现配置guards。合法Binder与unknownindex反例加入后，完整15用例于16:07:42/19.646s全部通过，未添加手动NoUnbound循环：flat三字段VO在原ignoreUnknownFields=false下已严格拒绝索引0/1未知字段，listener关闭也拒。复用现框架能力，删除原计划的重复EnvironmentAware生命周期，仅保实际测试；未扩大任何字段/权限/行为。

## 7. Ordered File-by-file Implementation Steps
### Step 1 — 为实际MCP consumer接入可信SERVICE逐权限Scope授权
- Requirements: PLAN-REQ-001、PLAN-REQ-002、PLAN-REQ-003、PLAN-REQ-004、PLAN-REQ-005
- Dependencies: Yuheng c27fcde clean、原蓝图授权、前逻辑Step26 Tianji55ffe9d已commit；42baseline通过。
- Baseline state: strict adapter只USER；EngineProperties10字段；实际factory configured4；MCP公共tool/schema发布原样。
- Observable outcome: 只有同次验证SERVICE且精确所有工具权限scopes满足才允许，原USER及默认无SERVICE映射保持。
- End state: actual managed consumer/finite tests闭合，后续AR MCP transport/journal/native另行执行。
- Test-first gate: Required — Props、实际SERVICE授权、实际factory/wire、YAML各有效RED后GREEN。
- Manual Checks: MC-ARCH-001, MC-REUSE-001, MC-DEP-001, MC-NAME-001, MC-VALID-001, MC-MODEL-001, MC-CONVERT-001, MC-LOG-001, MC-BEAN-001, MC-UTIL-001, MC-JSON-001, MC-TIME-001, MC-CONFIG-001, MC-PATTERN-001, MC-SCOPE-001, MC-TEST-001, MC-BLOCKER-001
- Literal Rules: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11
- Ordered files:

#### File 1 — `MODIFY stellar-enclosure-yuheng-mcp-gateway/src/test/java/top/egon/cola/component/yuheng/mcp/engine/config/McpGatewayEnginePropertiesTest.java`
- Purpose: 有限HOST scope配置与old10/canonical11兼容。
- Symbols: serviceScopeBindings/canonicalBinding/ServiceScopeBindingVO。
- Repository evidence: 现5项actualContextRunner bootstrap正反例；Outbound已显式绑定多构造。
- Dependencies and consumers: compile-only新增component/old10/nestedrecord后先直接构造RED；新guards/annotation之后合法Binder+unknownindex独立RED。
- Why now: §4.6/§6依赖顺序；tests先于对应行为，必要新签名仅编译声明/UOE，Root实际RED后最小GREEN。
- Contract/signature changes: §6 exact新配置11/old10与factory6/old4，现McpAuthorizationPort/Request/RuntimeTool均不改。
- Input/output and state mapping: defaultempty/immutable256、64scopes/128token、duplicate/resource/permission/unknown0与1；无schema/SQL/Event写入。
- Error and edge behavior: §6确切缺映射/全部scopes/当前时间/同scope/审计/兼容约束，失败闭合，不暴露bearer/profile正文。
- Standards impact: MC-ARCH-001/MC-REUSE-001/MC-VALID-001/MC-MODEL-001/MC-BEAN-001/MC-JSON-001/MC-TIME-001/MC-CONFIG-001/MC-SCOPE-001/MC-TEST-001；§4.7/12.5逐项。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11；§6 immutable/Validation/现Factory-Adapter/日志与时间合同具体落实。
- Implementation pseudocode:
```java
assertOldTenConstructorAndNewCanonicalElevenBinding();
assertDirectNullEmptyCopyBoundsAndValidatedServiceScopeBindingVo();
assertExactHttpsResourcePermissionBusinessScopesAndDuplicateKeysRejected();
assertBothListIndicesUnknownFieldsFailAfterValidActualBinderPositive();
```
- Verification contribution: TEST-001/002/003/004；对应实际日志与有限非web/source证据记evidence。
- After this file: 该贡献可review，后续依赖门禁保持，不将metadata或fake proof当真实IAM/网络授权验收。

#### File 2 — `MODIFY stellar-enclosure-yuheng-mcp-gateway/src/main/java/top/egon/cola/component/yuheng/mcp/engine/config/McpGatewayEngineProperties.java`
- Purpose: 只增可选不可变HOST权限翻译元数据。
- Symbols: record11/serviceScopeBindings/ServiceScopeBindingVO/strictBinder。
- Repository evidence: 既有@Validated strict record10与canonical constructor；嵌套Outbound兼容ctor模式。
- Dependencies and consumers: File1有效RED后实现；不改现listener/identity/outbound校验。
- Why now: §4.6/§6依赖顺序；tests先于对应行为，必要新签名仅编译声明/UOE，Root实际RED后最小GREEN。
- Contract/signature changes: §6 exact新配置11/old10与factory6/old4，现McpAuthorizationPort/Request/RuntimeTool均不改。
- Input/output and state mapping: null→empty/copy/max256；scope profile深copy，operator配置非认证/授角色；无schema/SQL/Event写入。
- Error and edge behavior: §6确切缺映射/全部scopes/当前时间/同scope/审计/兼容约束，失败闭合，不暴露bearer/profile正文。
- Standards impact: MC-ARCH-001/MC-REUSE-001/MC-VALID-001/MC-MODEL-001/MC-BEAN-001/MC-JSON-001/MC-TIME-001/MC-CONFIG-001/MC-SCOPE-001/MC-TEST-001；§4.7/12.5逐项。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11；§6 immutable/Validation/现Factory-Adapter/日志与时间合同具体落实。
- Implementation pseudocode:
```java
preserveOldTenConstructorAndAllOldBodies();
@ConstructorBinding canonicalEleven { copyOptionalBindingsMax256(); rejectDuplicateExactResourcePermission(); }
record ServiceScopeBindingVO(URI resourceUri, String permission, Set<String> scopes) { validateAnnotationsAndCanonicalInvariants(); copyScopesMax64(); }
retainExistingIgnoreUnknownFieldsFalseAndVerifyBootRejectsEachFlatBindingUnknownField();
```
- Verification contribution: TEST-001/002/003/004；对应实际日志与有限非web/source证据记evidence。
- After this file: 该贡献可review，后续依赖门禁保持，不将metadata或fake proof当真实IAM/网络授权验收。

#### File 3 — `MODIFY stellar-enclosure-yuheng-mcp-gateway/src/test/java/top/egon/cola/component/yuheng/mcp/engine/mcp/adapter/security/McpRbac3IntegrationTest.java`
- Purpose: 实际严格Adapter SERVICE授权/拒绝与USER回归。
- Symbols: verifiedServiceScopesAuthorizeEveryRequiredPermission/strict/requestProof。
- Repository evidence: 现8项strict adapter/Validator/fixedClock/observer/snapshot loader；原SERVICE用例仅翻USERtype。
- Dependencies and consumers: PropsGREEN后new6factory仅UOE编译声明；新实际SERVICE fixture必须subject=clientId/fullverifiedScopes且无USER MFA。
- Why now: §4.6/§6依赖顺序；tests先于对应行为，必要新签名仅编译声明/UOE，Root实际RED后最小GREEN。
- Contract/signature changes: §6 exact新配置11/old10与factory6/old4，现McpAuthorizationPort/Request/RuntimeTool均不改。
- Input/output and state mapping: 真实同scope/proof上下文+HOST映射→零版本allow；拒绝/审计失败→deny或503；无schema/SQL/Event写入。
- Error and edge behavior: §6确切缺映射/全部scopes/当前时间/同scope/审计/兼容约束，失败闭合，不暴露bearer/profile正文。
- Standards impact: MC-ARCH-001/MC-REUSE-001/MC-VALID-001/MC-MODEL-001/MC-BEAN-001/MC-JSON-001/MC-TIME-001/MC-CONFIG-001/MC-SCOPE-001/MC-TEST-001；§4.7/12.5逐项。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11；§6 immutable/Validation/现Factory-Adapter/日志与时间合同具体落实。
- Implementation pseudocode:
```java
createCoherentServicePrincipalThroughActualIdpIdentityAuthenticationProviderWithFakeVerifier();
assertStrictServiceUsesEveryImplicitAndExtraPermissionScopeAndNeverLoadsUserSnapshot();
assertNoMappingMissingScopeCrossResourceForgedIdentityExpiredFutureAndNonzeroMinVersionsFailClosed();
assertWrongClosedOrLostObservationAndLateExpiryCannotDeliverAllow();
assertDenyAuditUsesOriginalProofOnceAndFailureNeverAllows();
retainAllExistingUserAndOldFactoryTests();
```
- Verification contribution: TEST-001/002/003/004；对应实际日志与有限非web/source证据记evidence。
- After this file: 该贡献可review，后续依赖门禁保持，不将metadata或fake proof当真实IAM/网络授权验收。

#### File 4 — `MODIFY stellar-enclosure-yuheng-mcp-gateway/src/test/java/top/egon/cola/component/yuheng/mcp/engine/mcp/service/McpTransportIntegrationTest.java`
- Purpose: 不启动监听器的真实handler/gate SERVICE工具调用。
- Symbols: enforcesVerifiedServiceScopesForToolCallsWithoutStartingAListener。
- Repository evidence: 现4个safe inProcess methods；两个其他方法会start真实HTTP，不能全选类。
- Dependencies and consumers: 与File3同轮RED，先写真实SERVICE inprocess test再File5行为；列在此为共享fixture依赖。
- Why now: §4.6/§6依赖顺序；tests先于对应行为，必要新签名仅编译声明/UOE，Root实际RED后最小GREEN。
- Contract/signature changes: §6 exact新配置11/old10与factory6/old4，现McpAuthorizationPort/Request/RuntimeTool均不改。
- Input/output and state mapping: handler→physical observation→实际Adapter→Gate→Fake业务counter；allow一次，scope缺失/篡改无业务；无schema/SQL/Event写入。
- Error and edge behavior: §6确切缺映射/全部scopes/当前时间/同scope/审计/兼容约束，失败闭合，不暴露bearer/profile正文。
- Standards impact: MC-ARCH-001/MC-REUSE-001/MC-VALID-001/MC-MODEL-001/MC-BEAN-001/MC-JSON-001/MC-TIME-001/MC-CONFIG-001/MC-SCOPE-001/MC-TEST-001；§4.7/12.5逐项。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11；§6 immutable/Validation/现Factory-Adapter/日志与时间合同具体落实。
- Implementation pseudocode:
```java
@Test enforcesVerifiedServiceScopesForToolCallsWithoutStartingAListener() {
 arrange actualHandlerAndGateWithFixedClockCoherentServiceEvidenceAndHostScopeBindings();
 callInProcessAllowedToolAndAssertOneBusinessCallAndClosedPhysicalScope();
 callMissingExtraOrImplicitScopeAndAssert403WithOriginalProofAuditAndNoBusinessCall();
 callExpiredOrForeignScopeAndAssertFailClosedWithoutListenerOrBearerDisclosure();
}
```
- Verification contribution: TEST-001/002/003/004；对应实际日志与有限非web/source证据记evidence。
- After this file: 该贡献可review，后续依赖门禁保持，不将metadata或fake proof当真实IAM/网络授权验收。

#### File 5 — `MODIFY stellar-enclosure-yuheng-mcp-gateway/src/main/java/top/egon/cola/component/yuheng/mcp/engine/mcp/adapter/security/Rbac3McpAuthorizationAdapter.java`
- Purpose: 复用同一严格验证与审计的SERVICE scope策略。
- Symbols: createConfigured6/immutable serviceScopeIndex/Clock/authorizeStrict。
- Repository evidence: 既有@Slf4j/@RequiredArgsConstructor(PRIVATE)、legacy1ctor、configured4、GatewayVerifiedIdentityBO strict共同验证。
- Dependencies and consumers: File3与File4有效RED后实现，不动USER snapshot语义。
- Why now: §4.6/§6依赖顺序；tests先于对应行为，必要新签名仅编译声明/UOE，Root实际RED后最小GREEN。
- Contract/signature changes: §6 exact新配置11/old10与factory6/old4，现McpAuthorizationPort/Request/RuntimeTool均不改。
- Input/output and state mapping: 全部requiredPermissions按resource精确命中且fullverifiedScopes包含并集；allow0/0/0；无schema/SQL/Event写入。
- Error and edge behavior: §6确切缺映射/全部scopes/当前时间/同scope/审计/兼容约束，失败闭合，不暴露bearer/profile正文。
- Standards impact: MC-ARCH-001/MC-REUSE-001/MC-VALID-001/MC-MODEL-001/MC-BEAN-001/MC-JSON-001/MC-TIME-001/MC-CONFIG-001/MC-SCOPE-001/MC-TEST-001；§4.7/12.5逐项。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11；§6 immutable/Validation/现Factory-Adapter/日志与时间合同具体落实。
- Implementation pseudocode:
```java
preserveLegacyConstructorAndConfiguredFourFactory();
createConfigured(snapshots, observer, metrics, validator, bindings, Clock gatewayClock) { validateAndCopyFiniteExactScopeIndex(); return sameAdapterWithClock(); }
authorizeStrict { requireSameActivePhysicalObservationAndVerifiedContextFirst();
 if (actualType == USER) useExistingIdentityPrincipalAndSnapshotPathUnchanged();
 else if (actualType == SERVICE) requireSelfClientAndCurrentToken(); requireZeroRbacMinimaOrSnapshotFenced503(); requireEveryPermissionMappedToExactResourceAndAllScopes();
 else returnExistingUserPrincipalRequiredDenial();
 denyUsesExistingObservedProofHandoffAndMarker(); beforeServiceAllow recheckSameAvailableObservationAndTokenWindow(); returnVersionZero(); }
```
- Verification contribution: TEST-001/002/003/004；对应实际日志与有限非web/source证据记evidence。
- After this file: 该贡献可review，后续依赖门禁保持，不将metadata或fake proof当真实IAM/网络授权验收。

#### File 6 — `CREATE stellar-enclosure-yuheng-mcp-gateway/src/test/java/top/egon/cola/component/yuheng/mcp/engine/bootstrap/config/McpServiceScopeAssemblyTest.java`
- Purpose: 验证实际工厂选择新配置与同Clock。
- Symbols: managedFactoryPassesExactServiceScopeBindingsAndClock。
- Repository evidence: 现公开gatewayMcpHttpHandler已有properties+gatewayClock，默认实际createConfigured4。
- Dependencies and consumers: AdapterGREEN后写本Test、旧factory仍传4参数，应有效wiring RED。
- Why now: §4.6/§6依赖顺序；tests先于对应行为，必要新签名仅编译声明/UOE，Root实际RED后最小GREEN。
- Contract/signature changes: §6 exact新配置11/old10与factory6/old4，现McpAuthorizationPort/Request/RuntimeTool均不改。
- Input/output and state mapping: actual public factory+mockStatic CALLS_REAL_METHODS；外部依赖mocks，handler构造截取，无listener/Redis/SQL；无schema/SQL/Event写入。
- Error and edge behavior: §6确切缺映射/全部scopes/当前时间/同scope/审计/兼容约束，失败闭合，不暴露bearer/profile正文。
- Standards impact: MC-ARCH-001/MC-REUSE-001/MC-VALID-001/MC-MODEL-001/MC-BEAN-001/MC-JSON-001/MC-TIME-001/MC-CONFIG-001/MC-SCOPE-001/MC-TEST-001；§4.7/12.5逐项。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11；§6 immutable/Validation/现Factory-Adapter/日志与时间合同具体落实。
- Implementation pseudocode:
```java
mockStatic(Rbac3McpAuthorizationAdapter, CALLS_REAL_METHODS);
mockConstruction(McpEngineHttpHandler); useTempArtifactRootAndEmptyTaskDataSourceProviders();
invokeActualGatewayMcpHttpHandlerWithValidPropertiesBindingsAndSameClock();
verifyConfiguredSixFactoryReceivesExactSnapshotsObserverMetricsValidatorBindingsAndClock();
assertUnavailableFallbackPreservedWhenManagedSecurityDependencyMissing();
```
- Verification contribution: TEST-001/002/003/004；对应实际日志与有限非web/source证据记evidence。
- After this file: 该贡献可review，后续依赖门禁保持，不将metadata或fake proof当真实IAM/网络授权验收。

#### File 7 — `MODIFY stellar-enclosure-yuheng-mcp-gateway/src/main/java/top/egon/cola/component/yuheng/mcp/engine/bootstrap/config/McpGatewayEngineConfiguration.java`
- Purpose: 将已配置的HOST列表与原Clock接到实际授权consumer。
- Symbols: gatewayMcpHttpHandler createConfigured invocation。
- Repository evidence: 现gatewayMcpHttpHandler569–801先validate legacy mcp props，605configured4，最终handler。
- Dependencies and consumers: File6有效wiring RED后只改createConfigured调用参数。
- Why now: §4.6/§6依赖顺序；tests先于对应行为，必要新签名仅编译声明/UOE，Root实际RED后最小GREEN。
- Contract/signature changes: §6 exact新配置11/old10与factory6/old4，现McpAuthorizationPort/Request/RuntimeTool均不改。
- Input/output and state mapping: properties.serviceScopeBindings()+同gatewayClock；原缺依赖unavailable/USER loader prerequisite不变；无schema/SQL/Event写入。
- Error and edge behavior: §6确切缺映射/全部scopes/当前时间/同scope/审计/兼容约束，失败闭合，不暴露bearer/profile正文。
- Standards impact: MC-ARCH-001/MC-REUSE-001/MC-VALID-001/MC-MODEL-001/MC-BEAN-001/MC-JSON-001/MC-TIME-001/MC-CONFIG-001/MC-SCOPE-001/MC-TEST-001；§4.7/12.5逐项。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11；§6 immutable/Validation/现Factory-Adapter/日志与时间合同具体落实。
- Implementation pseudocode:
```java
keepAllCurrentPrerequisitesAndFallbacks();
authorization = Rbac3McpAuthorizationAdapter.createConfigured(snapshotLoader, auditObserver, securityMetrics, validator, properties.serviceScopeBindings(), gatewayClock);
passSameAuthorizationThroughExistingMcpSecurityGateAndHandlers();
```
- Verification contribution: TEST-001/002/003/004；对应实际日志与有限非web/source证据记evidence。
- After this file: 该贡献可review，后续依赖门禁保持，不将metadata或fake proof当真实IAM/网络授权验收。

#### File 8 — `MODIFY stellar-enclosure-yuheng-mcp-gateway/src/test/java/top/egon/cola/component/yuheng/mcp/engine/mcp/service/GatewayEngineRbac3ConfigurationTest.java`
- Purpose: 两个YAML配置的默认空映射与key parity。
- Symbols: declaresDefaultEmptyServiceScopeBindingsInBothProfiles。
- Repository evidence: 现YamlPropertiesFactoryBean测下游客户端配置；两profiles有同mcp-engine树。
- Dependencies and consumers: Props/Adapter/wiring GREEN后约束默认配置。
- Why now: §4.6/§6依赖顺序；tests先于对应行为，必要新签名仅编译声明/UOE，Root实际RED后最小GREEN。
- Contract/signature changes: §6 exact新配置11/old10与factory6/old4，现McpAuthorizationPort/Request/RuntimeTool均不改。
- Input/output and state mapping: base+operations仅新空service-scope-bindings；无角色、无enable或资源激活；无schema/SQL/Event写入。
- Error and edge behavior: §6确切缺映射/全部scopes/当前时间/同scope/审计/兼容约束，失败闭合，不暴露bearer/profile正文。
- Standards impact: MC-ARCH-001/MC-REUSE-001/MC-VALID-001/MC-MODEL-001/MC-BEAN-001/MC-JSON-001/MC-TIME-001/MC-CONFIG-001/MC-SCOPE-001/MC-TEST-001；§4.7/12.5逐项。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11；§6 immutable/Validation/现Factory-Adapter/日志与时间合同具体落实。
- Implementation pseudocode:
```java
loadBothApplicationYamlProfilesWithoutStartingContext();
assertMcpEngineServiceScopeBindingsIsEmptyListInBoth();
assertOldSecurityClientAndTransportKeysUnchanged();
```
- Verification contribution: TEST-001/002/003/004；对应实际日志与有限非web/source证据记evidence。
- After this file: 该贡献可review，后续依赖门禁保持，不将metadata或fake proof当真实IAM/网络授权验收。

#### File 9 — `MODIFY stellar-enclosure-yuheng-mcp-gateway/src/main/resources/application.yml`
- Purpose: 默认不开放SERVICE工具。
- Symbols: mcp-engine.service-scope-bindings: []。
- Repository evidence: 现strict bootstrap YAML identity/transport根。
- Dependencies and consumers: File8新默认assertion先RED，随后一行配置。
- Why now: §4.6/§6依赖顺序；tests先于对应行为，必要新签名仅编译声明/UOE，Root实际RED后最小GREEN。
- Contract/signature changes: §6 exact新配置11/old10与factory6/old4，现McpAuthorizationPort/Request/RuntimeTool均不改。
- Input/output and state mapping: 空HOST列表，原mode/ports/TLS/env默认不变；无schema/SQL/Event写入。
- Error and edge behavior: §6确切缺映射/全部scopes/当前时间/同scope/审计/兼容约束，失败闭合，不暴露bearer/profile正文。
- Standards impact: MC-ARCH-001/MC-REUSE-001/MC-VALID-001/MC-MODEL-001/MC-BEAN-001/MC-JSON-001/MC-TIME-001/MC-CONFIG-001/MC-SCOPE-001/MC-TEST-001；§4.7/12.5逐项。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11；§6 immutable/Validation/现Factory-Adapter/日志与时间合同具体落实。
- Implementation pseudocode:
```yaml
# Insert only this empty leaf; retain existing identity, listener TLS and outbound placeholders.
egon:
  cola:
    component:
      yuheng:
        mcp-engine:
          service-scope-bindings: []
```
- Verification contribution: TEST-001/002/003/004；对应实际日志与有限非web/source证据记evidence。
- After this file: 该贡献可review，后续依赖门禁保持，不将metadata或fake proof当真实IAM/网络授权验收。

#### File 10 — `MODIFY stellar-enclosure-yuheng-mcp-gateway/src/main/resources/application-operations.yml`
- Purpose: operations配置key parity。
- Symbols: mcp-engine.service-scope-bindings: []。
- Repository evidence: 现同bootstrap root，保用户环境占位符。
- Dependencies and consumers: 与File9同逻辑配置，一行。
- Why now: §4.6/§6依赖顺序；tests先于对应行为，必要新签名仅编译声明/UOE，Root实际RED后最小GREEN。
- Contract/signature changes: §6 exact新配置11/old10与factory6/old4，现McpAuthorizationPort/Request/RuntimeTool均不改。
- Input/output and state mapping: 空列表与base一致，不改现运行参数；无schema/SQL/Event写入。
- Error and edge behavior: §6确切缺映射/全部scopes/当前时间/同scope/审计/兼容约束，失败闭合，不暴露bearer/profile正文。
- Standards impact: MC-ARCH-001/MC-REUSE-001/MC-VALID-001/MC-MODEL-001/MC-BEAN-001/MC-JSON-001/MC-TIME-001/MC-CONFIG-001/MC-SCOPE-001/MC-TEST-001；§4.7/12.5逐项。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11；§6 immutable/Validation/现Factory-Adapter/日志与时间合同具体落实。
- Implementation pseudocode:
```yaml
# Insert only this empty leaf; retain existing identity, listener TLS and outbound placeholders.
egon:
  cola:
    component:
      yuheng:
        mcp-engine:
          service-scope-bindings: []
```
- Verification contribution: TEST-001/002/003/004；对应实际日志与有限非web/source证据记evidence。
- After this file: 该贡献可review，后续依赖门禁保持，不将metadata或fake proof当真实IAM/网络授权验收。

#### File 11 — `MODIFY README.md`
- Purpose: 说明原生SERVICE scope授权与边界。
- Symbols: HOST bindings/USER compatibility/request-time semantics。
- Repository evidence: 现README身份/构建/安全操作说明。
- Dependencies and consumers: actualsource/log后写，不假装runtime验收。
- Why now: §4.6/§6依赖顺序；tests先于对应行为，必要新签名仅编译声明/UOE，Root实际RED后最小GREEN。
- Contract/signature changes: §6 exact新配置11/old10与factory6/old4，现McpAuthorizationPort/Request/RuntimeTool均不改。
- Input/output and state mapping: 示例元数据不授scopes；schema/release由既有catalog/profile控制；无schema/SQL/Event写入。
- Error and edge behavior: §6确切缺映射/全部scopes/当前时间/同scope/审计/兼容约束，失败闭合，不暴露bearer/profile正文。
- Standards impact: MC-ARCH-001/MC-REUSE-001/MC-VALID-001/MC-MODEL-001/MC-BEAN-001/MC-JSON-001/MC-TIME-001/MC-CONFIG-001/MC-SCOPE-001/MC-TEST-001；§4.7/12.5逐项。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11；§6 immutable/Validation/现Factory-Adapter/日志与时间合同具体落实。
- Implementation pseudocode:
```java
documentExactResourcePermissionScopeTranslationAndDefaults();
documentZeroServiceRbacVersionsAndPerPhysicalRequestRevocationBoundary();
documentOriginalUserPathAuditAndNoRuntimeActivation();
```
- Verification contribution: TEST-001/002/003/004；对应实际日志与有限非web/source证据记evidence。
- After this file: 该贡献可review，后续依赖门禁保持，不将metadata或fake proof当真实IAM/网络授权验收。

#### File 12 — `CREATE docs/egon/plan/2026-11-01-15-57-mcp-service-scope-authorization.md`
- Purpose: 锁定单逻辑任务13文件顺序/时间/安全/门禁。
- Symbols: V5/5aliases/13scope。
- Repository evidence: 原蓝图§12/13/17；5source reactor42baseline通过。
- Dependencies and consumers: Root全文/strict/resources/checklist/hash后GO。
- Why now: §4.6/§6依赖顺序；tests先于对应行为，必要新签名仅编译声明/UOE，Root实际RED后最小GREEN。
- Contract/signature changes: §6 exact新配置11/old10与factory6/old4，现McpAuthorizationPort/Request/RuntimeTool均不改。
- Input/output and state mapping: Review原授权，不伪Accepted；元数据字段必要而无发布DTO改动；无schema/SQL/Event写入。
- Error and edge behavior: §6确切缺映射/全部scopes/当前时间/同scope/审计/兼容约束，失败闭合，不暴露bearer/profile正文。
- Standards impact: MC-ARCH-001/MC-REUSE-001/MC-VALID-001/MC-MODEL-001/MC-BEAN-001/MC-JSON-001/MC-TIME-001/MC-CONFIG-001/MC-SCOPE-001/MC-TEST-001；§4.7/12.5逐项。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11；§6 immutable/Validation/现Factory-Adapter/日志与时间合同具体落实。
- Implementation pseudocode:
```java
lockC27BaselineAndExactThirteenPaths();
requirePropertiesThenServiceAdapterThenActualWiringAndYamlRedGreen();
requireScopedCommitAfter17ManualChecksAnd10LiteralRules();
```
- Verification contribution: TEST-001/002/003/004；对应实际日志与有限非web/source证据记evidence。
- After this file: 该贡献可review，后续依赖门禁保持，不将metadata或fake proof当真实IAM/网络授权验收。

#### File 13 — `CREATE docs/egon/evidence/2026-11-01-mcp-service-scope-authorization.md`
- Purpose: 记录实际验证与限制。
- Symbols: RED/GREEN/17MC10Rules/commit boundary。
- Repository evidence: 本计划与真实stdout/source diff。
- Dependencies and consumers: 先PENDING，实际完成后逐行评价。
- Why now: §4.6/§6依赖顺序；tests先于对应行为，必要新签名仅编译声明/UOE，Root实际RED后最小GREEN。
- Contract/signature changes: §6 exact新配置11/old10与factory6/old4，现McpAuthorizationPort/Request/RuntimeTool均不改。
- Input/output and state mapping: 不把fake proof/metadata/42baseline当真实IAM/网络/整个MCP完成；无schema/SQL/Event写入。
- Error and edge behavior: §6确切缺映射/全部scopes/当前时间/同scope/审计/兼容约束，失败闭合，不暴露bearer/profile正文。
- Standards impact: MC-ARCH-001/MC-REUSE-001/MC-VALID-001/MC-MODEL-001/MC-BEAN-001/MC-JSON-001/MC-TIME-001/MC-CONFIG-001/MC-SCOPE-001/MC-TEST-001；§4.7/12.5逐项。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11；§6 immutable/Validation/现Factory-Adapter/日志与时间合同具体落实。
- Implementation pseudocode:
```java
recordRealBehaviorRedSeparatelyFromCompilerAndFixtureFailures();
recordExactFinalSourceReactorAndSafeMethodSelectors();
recordManualRulesScopeAndUnperformedRuntimeRoleActions();
```
- Verification contribution: TEST-001/002/003/004；对应实际日志与有限非web/source证据记evidence。
- After this file: 该贡献可review，后续依赖门禁保持，不将metadata或fake proof当真实IAM/网络授权验收。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/yuheng-service`；strict从workspace。
- Verification command: §8精确source reactor及focused selectors；逐component RED/GREEN Root统一执行。
- Expected result: 所有命名用例通过、zero skip，来源scope/时间/审计错误不得ALLOW；两profile默认empty，13scope无其他diff。
- Failure returns to: 配置回File1/2，Service协议/physical marker回File3/4/5，实际consumer回File6/7，YAML回File8/9/10；fixture/编译先修，不算RED。
- Completion criteria: 13paths/SQL byte hash/17MC10Rules/独审/strict/resources/checklist/cached通过后单commit，无knownrequiredStep work遗留。
- Rollback: 停用新HOST映射即可保原USER、默认拒SERVICE；不变更已发布tool/schema/history，不移除IAM权限或repair数据库。
- Commit paths: stellar-enclosure-yuheng-mcp-gateway/src/test/java/top/egon/cola/component/yuheng/mcp/engine/config/McpGatewayEnginePropertiesTest.java、stellar-enclosure-yuheng-mcp-gateway/src/main/java/top/egon/cola/component/yuheng/mcp/engine/config/McpGatewayEngineProperties.java、stellar-enclosure-yuheng-mcp-gateway/src/test/java/top/egon/cola/component/yuheng/mcp/engine/mcp/adapter/security/McpRbac3IntegrationTest.java、stellar-enclosure-yuheng-mcp-gateway/src/main/java/top/egon/cola/component/yuheng/mcp/engine/mcp/adapter/security/Rbac3McpAuthorizationAdapter.java、stellar-enclosure-yuheng-mcp-gateway/src/test/java/top/egon/cola/component/yuheng/mcp/engine/mcp/service/McpTransportIntegrationTest.java、stellar-enclosure-yuheng-mcp-gateway/src/test/java/top/egon/cola/component/yuheng/mcp/engine/bootstrap/config/McpServiceScopeAssemblyTest.java、stellar-enclosure-yuheng-mcp-gateway/src/main/java/top/egon/cola/component/yuheng/mcp/engine/bootstrap/config/McpGatewayEngineConfiguration.java、stellar-enclosure-yuheng-mcp-gateway/src/test/java/top/egon/cola/component/yuheng/mcp/engine/mcp/service/GatewayEngineRbac3ConfigurationTest.java、stellar-enclosure-yuheng-mcp-gateway/src/main/resources/application.yml、stellar-enclosure-yuheng-mcp-gateway/src/main/resources/application-operations.yml、README.md、docs/egon/plan/2026-11-01-15-57-mcp-service-scope-authorization.md、docs/egon/evidence/2026-11-01-mcp-service-scope-authorization.md
- Commit: `feat(mcp): authorize verified services with exact tool scopes`

## 8. Test, Validation, and Quality Gates
| ID | Gate | Evidence / expected result |
| --- | --- | --- |
| TEST-001 | PropsTest实际config/canonical11/old10/finite/unknownindex | direct有效RED后guardGREEN，合法Binder成功再unknown反例闭合（现strict能力无需重复实现）；caller值对象不授权限 |
| TEST-002 | McpRbac3IntegrationTest+safeTransport新SERVICE方法 | 同physical SERVICE/gate/scopes/time/minima/audit；USER原样，defaultemptydeny |
| TEST-003 | newAssembly+twoYAML test | actualfactory精确new6参数/同Clock；baseline/operations同emptykey |
| TEST-004 | belowfullselected source reactor/static/13paths/SQL/gates | 全适用PASS后commit；真实IAM/网络/PG用户验收 |

已有baseline精确八类42PASS0skip，`/tmp/yuheng-mcp-service-scope-baseline.log`，40.445s，2026-11-01 15:42:56；并非本Step实现结果。现source reactor metadata为 `/private/tmp/yuheng-agent-framework-hawdoetc/pom.xml`，仅5现有源码repo模块Components/Shoubing/Jianshen/Tianshu/Yuheng，按现Tianji脚本方式生成Maven聚合数据，无新构建脚本/依赖；原Parent bootstrap hardcode skipTests且扩全workspace不适用。该metadata本Step后删除；实际测试仍由既有wrapper/JUnit suite覆盖，无新增验证harness/scripts/work。

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./mvnw -f /private/tmp/yuheng-agent-framework-hawdoetc/pom.xml \
  -pl :stellar-enclosure-yuheng-mcp-gateway -am \
  -gs /tmp/tianji-compat-fix/settings.xml -s /tmp/tianji-compat-fix/settings.xml \
  -B -ntp -DskipTests=false \
  '-Dtest=McpGatewayEnginePropertiesTest,McpRbac3IntegrationTest,McpRequestObservationComponentTest,McpGatewayIdentityAuthenticatorTest,McpSecurityGateTest,McpFederationTest,GatewayEngineRbac3ConfigurationTest,McpGatewaySecurityObservationStartupCheckTest,McpServiceScopeAssemblyTest,McpTransportIntegrationTest#cancellationClosesTheOuterPhysicalRequestScope+strictCapabilityReadKeepsDenyAndUnavailableDistinctAndBusinessUncalled+preservesSecurityStatusesWithoutDispatchingBusinessOrStartingAListener+keepsInitializationAndMissingSessionProtocolShapesInProcess+enforcesVerifiedServiceScopesForToolCallsWithoutStartingAListener' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dotel.traces.exporter=none -Dotel.metrics.exporter=none -Dotel.logs.exporter=none \
  test > /tmp/yuheng-mcp-service-scope-final-validation-placement.log 2>&1
```
focused沿同命令替换test selector与独立log，禁止全类McpTransportIntegrationTest（其中两个方法start真实HTTP）。static检查exact13paths、无pom/旧SQL/公开协议DTO/发布codec变更、两YAML同key、git diff/cached check；currentexecute/writing resource preflight及strictPlan，17MC/10Rules独立人工评估。源码测试不证明deployedIAM/crypto/远端MCP/PG。

## 9. Migration, Compatibility, Rollout, and Rollback
无新DDL/SQL/manifest/权限注册激活/角色grant。Propsold10兼容、canonical11显式绑定；Adapterlegacy1与configured4保持原行为，新增factory由显式HOST列表选择实际SERVICE策略。USER路径/公共Request/tool15arg/旧canonicalJSON/hash/adminproducer完全保持。默认两YAML empty；启用HOST映射本身不授caller scope，仍需实际已验证目标Resource+当前scopes。停映射恢复SERVICE拒绝，已发布规则/源审计/历史不变。

## 10. Requirement-to-Step Traceability Matrix
| Requirement | Source | Step | Files | Verification | Acceptance |
| --- | --- | --- | --- | --- | --- |
| PLAN-REQ-001 | §12/13/17 | Step 1 | §7 exact13paths对应 | TEST-001/002/003/004 | 默认空finite immutable HOST精确resource+permission→业务scopes；旧10ctor/canonical11与strictindex兼容 |
| PLAN-REQ-002 | §12/13/17 | Step 1 | §7 exact13paths对应 | TEST-001/002/003/004 | 同物理请求可信SERVICE、自身份、全部requiredPermissions/fullverifiedScopes，禁止USER模拟或通用scope授权全部工具 |
| PLAN-REQ-003 | §12/13/17 | Step 1 | §7 exact13paths对应 | TEST-001/002/003/004 | iat≤now<exp决策+handoff、同active observation、零RBAC minima；失败审计与503边界不弱化 |
| PLAN-REQ-004 | §12/13/17 | Step 1 | §7 exact13paths对应 | TEST-001/002/003/004 | 实际managed handler factory接同bindings/Clock；oldUSER/constructors/published JSON/schema/权限不改 |
| PLAN-REQ-005 | §12/13/17 | Step 1 | §7 exact13paths对应 | TEST-001/002/003/004 | 两YAML默认空/无runtime或角色动作，source regression与13path单提交/17MC10Rules实证 |

## 11. Risks, Blockers, and User Decisions
| ID | Risk/decision | Evidence/resolution | Owner | Status |
| --- | --- | --- | --- | --- |
| DEC-001 | 发布scope新字段会改旧SHA/producer链 | 采用既有permission+HOST map直接替代，无需字段 | Root | Closed |
| DEC-002 | SERVICE不具USER RBAC版本 | 只零minimum/allow0，非零fenced503，不查USERsnapshot | Root | Closed |
| DEC-003 | mutable旧Props严格绑定需新生命周期 | 选现immutable strict bootstrap；old10+canonical11 | Root | Closed |
| RISK-001 | 将审计proof/普通属性当认证 | 只有原physical verifiedContext/observer，同次fullScopes；fake只测试 | Root | Tracked tests |
| RISK-002 | mapping被误称schema授权/即时撤销 | 明确request-boundcatalog+后续ASprofile职责、perphysical SQL入口边界 | Root | Tracked docs/tests |
| RISK-003 | 误跑listener测试/临时Mavenmetadata遗留 | exact方法selector、元数据执行后删除，无自动runtime | Root | Tracked |

## 12. Review and Acceptance
### 12.1 Original requirement fidelity
原继续蓝图授权内补齐工具SERVICE调用边界，分步提交；不启动app/DB/server/browser，不激活权限/授角色。
### 12.2 Spec consistency
复用§12/13既定SERVICE scope/可信context，HOST metadata为必要本地实现；无新认证/角色机制/公开工具协议或过度前置查询。两个Clarification均具体源证据，原USER与发布版本职责保持。
### 12.3 Repository executability
实际路径/签名/ctor/consumer/两profiles均已查，5repo source reactor42baseline已验证；13paths顺序/ownership/RED及Root统一Maven完整。
### 12.4 Test and release completeness
只有限source/nonweb/inprocess测试；真实身份、撤销、TLS网络、权限部署和AS MCP bridge后续验证，不将这些预填PASS。
### 12.5 Blocking Manual Check
| Check ID | Applicability | Status | Evidence | Finding | Required action/exception |
| --- | --- | --- | --- | --- | --- |
| MC-ARCH-001 | Applicable | PASS | 现技术Gateway/Adapter/Config/Core port，无新层/CRUD/迁移 | 计划合同闭合，实施后fresh diff逐项重核 | None |
| MC-REUSE-001 | Applicable | PASS | 原verifiedContext/fullBO/scopes/观察marker/Adapter/Clock/Binder | 计划合同闭合，实施后fresh diff逐项重核 | None |
| MC-DEP-001 | Applicable | PASS | 实际5repo source42baseline，均现POM依赖，无新增artifact | 计划合同闭合，实施后fresh diff逐项重核 | None |
| MC-NAME-001 | Applicable | PASS | nestedServiceScopeBindingVO与现Properties/Adapter；不增Bean | 计划合同闭合，实施后fresh diff逐项重核 | None |
| MC-VALID-001 | Applicable | PASS | §6 exactURI/permission/scopes/duplicate/index/proof/时间/marker全边界 | 计划合同闭合，实施后fresh diff逐项重核 | None |
| MC-MODEL-001 | Applicable | PASS | immutableVO record/List/Map copy，既有Adapter LombokFactory保持 | 计划合同闭合，实施后fresh diff逐项重核 | None |
| MC-CONVERT-001 | Not applicable | N/A | 无业务PO/DTO/Entity转换或新Converter，metadata直接typedcopy | 计划合同闭合，实施后fresh diff逐项重核 | None |
| MC-LOG-001 | Applicable | PASS | 现Adapter@Slf4j/固定reasonCode/低基数metrics，保脱敏日志 | 计划合同闭合，实施后fresh diff逐项重核 | None |
| MC-BEAN-001 | Applicable | PASS | 原managedfactory/qualifiedobserver/validator/Clock，无新DI构造 | 计划合同闭合，实施后fresh diff逐项重核 | None |
| MC-UTIL-001 | Applicable | PASS | 现Spring/JDK Binder/Map/Set/URI，未造认证工具框架 | 计划合同闭合，实施后fresh diff逐项重核 | None |
| MC-JSON-001 | Applicable | PASS | tool/request/admin/codec不改，避免NON_NULL旧hash变化 | 计划合同闭合，实施后fresh diff逐项重核 | None |
| MC-TIME-001 | Applicable | PASS | TIME001/002明确UTC Instant/同Clock/decision+handoff与物理边界 | 计划合同闭合，实施后fresh diff逐项重核 | None |
| MC-CONFIG-001 | Applicable | PASS | 现strict record11/old10，两YAMLempty同key，不变旧capability树 | 计划合同闭合，实施后fresh diff逐项重核 | None |
| MC-PATTERN-001 | Applicable | PASS | 原McpAuthorizationPort thinAdapter+Factory，实际身份翻译变点 | 计划合同闭合，实施后fresh diff逐项重核 | None |
| MC-SCOPE-001 | Applicable | PASS | §5 exact13paths，现SQL/publicDTO/pom只读 | 计划合同闭合，实施后fresh diff逐项重核 | None |
| MC-TEST-001 | Applicable | PASS | 3component+YAML有效RED、最终安全10类selector、42baseline已实跑 | 计划合同闭合，实施后fresh diff逐项重核 | None |
| MC-BLOCKER-001 | Applicable | PASS | 所有设计决策已closed，范围/默认/验证边界明确，实施后逐项重核 | 计划合同闭合，实施后fresh diff逐项重核 | None |

### 12.6 Final verdict
PASS — Ready for user review

本Step实际执行结果：13paths source完成，最终source-reactor10类69PASS0fail/error/skip、15既有SQL字节不变，详见evidence。Review原用户授权实施，不伪Accepted或整个MCP/蓝图完成。
