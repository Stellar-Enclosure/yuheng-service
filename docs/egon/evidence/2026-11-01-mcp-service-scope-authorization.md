# MCP SERVICE逐权限scope授权执行证据

Plan: [2026-11-01-15-57-mcp-service-scope-authorization.md](../plan/2026-11-01-15-57-mcp-service-scope-authorization.md)，Review/原用户蓝图授权；Yuheng baseline c27fcde728f191780c872a5b93831afe60b4ab3f；Spec SHA ea0a85117b16a99c365b88d49729b5d5b8e0025abfbe7055c5f60bf6fc81cd54。

**Verified，待本Step路径限定提交**。初始Plan锁定SHA `4086859ce6c48ff55a16f7a0972408b3763b46d2df85053a6d1f61785cb5c722`；CLAR003既有Binder复用与最终Validation type-use精化后Plan SHA `67741c38ff67d14b739bf90f075f88934211fc1fb09feb258a8150ae198e50ae`。Root全文/strict/resources/checklist，Props/Adapter/实际wiring/YAML各有效RED后GREEN；最终10类69PASS0skip。此为本Step source/inprocess实现证据，真实IAM/SQL/TLS/远端MCP及后续AS MCP链仍未验收。13scope、无新依赖/旧SQL/公开工具协议变化。

## Requirements

- PLAN-REQ-001: PASS — Props15实际测试证明old10/canonical11、finite256/deepcopy、scope64/URI/permission/reserved/duplicate与合法双index/未知字段；既有strict Binder充分，CLAR003无额外lifecycle。
- PLAN-REQ-002: PASS — 实际IdpIdentityAuthenticationProvider+受控Verifier机器subject/client/full64scopes；精确implicit+extra全部权限/scopes、跨resource与伪造self反例，SERVICE未载USER snapshot。
- PLAN-REQ-003: PASS — iat≤Clock<exp初始/最终、同physical context指针/active/available二次fence；minima非零503、zero ALLOW，原proof once DENY audit/失败503均通过。
- PLAN-REQ-004: PASS — actual工厂same bindings/Clock/Validator等6对象被验证，缺4依赖仍503；旧USER/old4empty/nullClock行为保持，publicTool/Request/codec/hash/pom/权限未改。
- PLAN-REQ-005: PASS — 两个YAML各仅1emptyleaf，无角色/runtime动作；最终10类69PASS0skip/15SQL一致/exact13scope/独审/17MC10Rules闭合。

## Blocking Manual Check

| Check ID | Applicability | Status | Evidence | Finding | Required action/exception |
| --- | --- | --- | --- | --- | --- |
| MC-ARCH-001 | Applicable | PASS | 现Gateway Config/Adapter/核心port路径；13scope无新业务层/CRUD/Entity/SQL | 原技术模块结构/既有MP-SDJ与Outbox边界保持 | None |
| MC-REUSE-001 | Applicable | PASS | 既有GatewayVerifiedIdentityBO/fullScopes/physical marker/observeDecision/Binder/Clock；现McpAuthorizationPort薄Adapter | 复用认证/审计/框架；无新registry/身份副本/USER授权API | None |
| MC-DEP-001 | Applicable | PASS | 5repo现source反应堆全部SUCCESS；无pom/依赖manifest变更 | JDK/Spring/Jakarta/已有IAM能力充分，无新依赖 | None |
| MC-NAME-001 | Applicable | PASS | 既有Properties/Adapter/Config；nestedServiceScopeBindingVO，newAssemblyTest | 值对象命名与既有工厂稳定，无新Bean名称漂移 | None |
| MC-VALID-001 | Applicable | PASS | @Validated根、List元素@NotNull @Valid/type-use、VO原生约束+canonical guard、factory ValidationUtils及actual commonproof/scope/time checks | 配置/元数据/实际授权handoff均验证，metadata不当凭据 | None |
| MC-MODEL-001 | Applicable | PASS | immutable value record/deepcopy/private final map；exactold10与canonical11 | record值对象豁免适用，原Adapter Lombok privateFactory保持 | None |
| MC-CONVERT-001 | Not applicable | N/A | 无PO/Entity/DTO投影或Converter；直接读取现typed metadata并建private索引 | 未触业务对象转换，无BaseConverter绕过 | None |
| MC-LOG-001 | Applicable | PASS | Adapter原@Slf4j/低基数ALLOW-DENY-INDETERMINATE metrics与固定reason复用，Request bearer redaction | 无新增敏感日志/无body输出；原proof审计一次 | None |
| MC-BEAN-001 | Applicable | PASS | 原managed factory+qualified gatewayClock/observer/validator；Lombok PRIVATE Factory不是Spring DI ctor；canonicalConstructorBinding非Autowired | 同6对象actual assembly断言；缺依赖503，未新增Service/Bean/field injection | None |
| MC-UTIL-001 | Applicable | PASS | 新imports仅现Spring/Jakarta、JDK Clock/Instant/URI/List/Set/Map | 无新工具库/认证框架，未重复手动Binder生命周期 | None |
| MC-JSON-001 | Applicable | PASS | McpAuthorizationRequest/RuntimeTool/admin/codec/旧canonicalJSON untouched；现Jackson source/inprocess回归 | 无需扩展发布shape/hash，HOST config独立于schema授权 | None |
| MC-TIME-001 | Applicable | PASS | decision+handoff同gatewayClock UTC Instant纳秒比较；expiry/future/closedscope/zeroMinima测试 | 明确physical入口SQL撤销边界，providerdeadline未冒充tool/runbudget | None |
| MC-CONFIG-001 | Applicable | PASS | 2YAML相同mcp-engine.service-scope-bindings:[]；actualbothrawList test及old10/new11/Binder index0/1 | default SERVICE拒绝，原身份/TLS/ports/env与legacy树保持 | None |
| MC-PATTERN-001 | Applicable | PASS | 现McpAuthorizationPort thinAdapter+configuredFactory；原USER与SERVICE授权翻译变点 | 复用批准Adapter/Factory模式，无额外策略/层/缓存 | None |
| MC-SCOPE-001 | Applicable | PASS | exact13paths与15SQL SHA逐字节一致；publicDTO/codec/POM/manifest无变化 | 保用户其他仓库工作；无scope注册/激活/rolegrant/部署 | None |
| MC-TEST-001 | Applicable | PASS | Props6 RED→15GREEN、Adapter22RED→22GREEN、assembly2RED→2GREEN、YAML2RED→2GREEN、final10class69PASS0skip | 实际Source/inprocess测试有健康正例/一次业务counter；2listener方法未选 | None |
| MC-BLOCKER-001 | Applicable | PASS | 全部17MC/10Rules、完整source diff/三次独审、strict/resources/checklist/cached gate | 本Step实现scope闭合，剩余真实环境与平台全目标明确未完成 | None |

## Literal Rules

| Literal rule | Applicability | Status | Diff/path/symbol evidence | Test/static evidence | Finding | Required action/exception |
| --- | --- | --- | --- | --- | --- | --- |
| Rule 1 | Applicable | PASS | ServiceScopeBindingVO/现Properties-Adapter-Config与stable factory | source/compile/13paths | 语义值对象与现命名保持 | None |
| Rule 2 | Applicable | PASS | 原生Jakarta约束、type-use级联、canonical invariants、共用proof+scope+时间边界 | Props15/Adapter17/Transport5/assembly2/默认2 | 各受影响边界验证，不仅ValidationUtils | None |
| Rule 3 | Applicable | PASS | record immutableVO/ScopeSet/List深copy/Map.copyOf，保oldctor；Adapter原Lombok | ctor/binding/mutation反例 | 无新普通实体或Converter，record豁免适用 | None |
| Rule 4 | Applicable | PASS | 原Adapter@Slf4j/PRIVATE RequiredArgsConstructor+qualified字段，手动configuredFactory；原namedconsumer | actualsame6对象/缺依赖503；Binding ctor非Autowired | 无新SpringService ctorDI或敏感日志 | None |
| Rule 5 | Applicable | PASS | 现JDK/Spring/Jakarta imports，noPOMchange | 5source reactor全SUCCESS | 无新增依赖/工具库 | None |
| Rule 6 | Applicable | PASS | 原Jackson协议/公开tool DTO/codec/enum未改 | inprocessToolCall/原USER与Federation回归 | 旧发布shape/hash保持，无ordinal新增 | None |
| Rule 7 | Applicable | PASS | base+operations只加sameemptykey，新strict11/old10 | bothProfiles/rawList+Binderunknown0/1 | 默认不开放SERVICE，旧配置值不变 | None |
| Rule 9 | Applicable | PASS | 原McpAuthorizationPort thinAdapter/Factory实现已选翻译模型 | exactperm/scopes/audit/time及actualconsumer矩阵 | 无多余继承/注册表/并行授权框架 | None |
| Rule 10 | Applicable | PASS | Clock/Instant UTC iat≤now<exp，原providerwindow语义 | 初始与最终expiry/future/scope丢失、sameClock断言 | 无java.util日期、时区猜测或新timer | None |
| Rule 11 | Applicable | PASS | 原技术Gateway结构，未触CRUD/SQL/业务Event；原IdpObserver Outbox路径复用 | 15SQL hash不变，13path reactor/static | 既有MP-SDJ与CQE/Outbox职责保持 | None |

## 未执行边界

未启动应用/HTTP listener/数据库/容器/browser/GUI，不注册激活权限/授角色/push/PR/release；不改MCP公共DTO/JSON/旧发布hash。AS MCP transport/journal/native与其余蓝图仍Partial；当前SQL撤销核验只在physical入口，不承诺长请求逐toolSQL实时撤销。

## Step lock

Root已保存15个现有Yuheng classpath SQL SHA至`/tmp/yuheng-step27-sql-baseline.json`；Tianji前步55ffe9d已commit/clean，Master Spec SHA见首段；Yuheng起始c27fcde clean，原用户授权涵盖本蓝图缺失MCP SERVICE consumer。exact13paths，worker1 Props/Test、worker2 Adapter/RbacTest/TransportTest、Root其余8paths。所有Java冻结后Root统一Maven，无生产/测试owner自建服务或并发build。

## Props实际RED/GREEN与CLAR-003

`/tmp/yuheng-mcp-service-scope-properties-red.log` 2026-11-01 16:04:26/19.463s exit1，selected6直接构造方法6fail/0error/0skip：空列表null、max/duplicate无拒绝、scope不copy、非法permission/HTTP URI及scope未拒，均实际缺行为。随后实现immutable配置/VO/annotations/canonicalConstructorBinding。

`/tmp/yuheng-mcp-service-scope-properties-deep-red.log` 2026-11-01 16:07:42/19.646s exit0，**15PASS/0failure/0error/0skip**，包含合法2项实际Binder、listener开关、unknownindex0/1拒绝与旧配置。现strict Binder已覆盖flat VO未知字段，不伪造deep RED或新增重复EnvironmentAware；Plan CLAR-003按实证复用调整。

独立Plan review mcp_scope_plan_review只读对baseline c27fcde与锁定4086859...确认无blocking security/API/compat/necessity缺陷；指出最终仍须实际same-pointer/active/time二次ALLOW fence与old4empty行为、全13scope验证。该review不是实现验收。

## Adapter与安全进程内工具链RED/GREEN

`/tmp/yuheng-mcp-service-scope-adapter-red.log` 2026-11-01 16:11:02/19.653s exit1，22selected/1fail/9error/0skip；12个旧测试通过，10个新用例均仅在允许的new6factory UOE编译声明失败，未见compiler/fixture错误。Root确认后才授权实现SERVICE行为。

`/tmp/yuheng-mcp-service-scope-adapter-green.log` 2026-11-01 16:13:28/19.607s exit0，**22PASS/0failure/0error/0skip**：Rbac17+safeTransport5。实际IdpIdentityAuthenticationProvider+受控Verifier产生coherent SERVICE与64个fullscopes，optional512摘要缺失；全部implicit/额外permission、跨resource、self/time/minima、once原proof审计、最终scope/time丢失与进程内businesscounter闭合。两真实listener方法未执行。

独立实现审阅mcp_scope_plan_review对Props/Adapter/新9Rbac+1进程内用例未发现blocking correctness/security/compat/concurrency/fixture问题；确认old4 emptyMap/nullClock路径先拒绝且不读Clock、不载USERsnapshot，common原proof边界先于分支，SERVICE ALLOW前同context pointer/active/时间二次fence。此仅clears当前组件，不完成实际工厂/YAML/13path门禁。

## 实际managed consumer wiring RED

`/tmp/yuheng-mcp-service-scope-assembly-red.log` 2026-11-01 16:17:42/19.597s exit1，2tests/0failure/1error/0skip。actual工厂与valid全配置已构造，缺依赖fallback一项通过；唯一error为Mockito static verification包装的明确参数/重载断言：wanted exact6（same bindings/Clock/Validator）但production line605 actual仅4args，非compiler/fixture错误。Root确认有效wiring RED后，仅该调用追加`properties.serviceScopeBindings(), gatewayClock`，随后执行GREEN，结果见下一节。该装配测试只截取McpSecurityGate与McpEngineHttpHandler构造，不启动listener/Redis/SQL/远端。

## 装配与默认配置GREEN

`/tmp/yuheng-mcp-service-scope-assembly-green.log`，2026-11-01 16:19:13/19.522s，2PASS/0failure/error/skip：same6对象参数与4种缺依赖的真实503 port通过。

`/tmp/yuheng-mcp-service-scope-defaults-red.log`，2026-11-01 16:20:51/18.368s，2tests/1fail/0error/0skip，valid YAML节点存在，预期emptyList实际null（缺新key）；之后才向两YAML加一emptyleaf。`/tmp/yuheng-mcp-service-scope-defaults-green.log`，16:22:16/18.618s，2PASS/0failure/error/skip，原client配置与两profile默认均通过。

## 最终精确Source回归

首轮`/tmp/yuheng-mcp-service-scope-final.log`，2026-11-01 16:25:24/21.882s，69PASS/0failure/error/skip。日志核查发现新List容器级@Valid触发Hibernate HV000271弃用警告；Root仅将其移到List元素type-use，保持逐元素级联，无新行为/依赖或额外harness。

最终`/tmp/yuheng-mcp-service-scope-final-validation-placement.log`，2026-11-01 16:29:40/22.382s，exit0，**10类69PASS/0failure/0error/0skip**。MCP Core SecurityGate6+Federation6=12；Gateway Startup3+Assembly2+Properties15+Rbac17+safeTransport5+Identity10+Yaml2+Observation3=57。Plan§8为实际JDK25/5source aggregate/10类加方法精确selector，traces/metrics/logs exporter全none。两启动真实HTTP的方法未执行；全部source reactor模块SUCCESS。HV000271已消失，仅未触Components RPC测试3条既有Jackson2弃用编译警告和25条预期负例Context启动拒绝WARN；无新生产warning或构建错误。

独审三轮：锁定设计无blocking发现；Props/Adapter+新SERVICE/physical tests无实现/fixture发现；实际managed factory、新Assembly/YAML/README与13scope最终review无code/config缺陷。末轮唯一docs状态PENDING发现由Root在本节实际log完成后修复。独审为只读/static，Maven结果来自Root。Root手动逐行复核当前冻结diff、5aliases、17MC/10Rules如上，不把Mock verifier或审计metadata当真实身份验收。

## 提交与临时制品门禁

Source文件冻结；final checklist全文重读、strict/resources/13scope/15SQL/cached check通过后立即paths限定commit，不预填hash。真实hash/paths记工作区原蓝图及continuation。Root authoring helpers和5repo临时聚合POM在commit后删除；它们不是新构建步骤/依赖或repo验证harness。无scripts/work未定制品。AS MCP客户端/记录/native与T3-08..T4仍继续，整个用户目标未完成。
