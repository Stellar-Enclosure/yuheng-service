package top.egon.cola.component.yuheng.llm.proxy.repository;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.yuheng.llm.proxy.domain.bo.LlmModelSnapshotBO;

import java.util.List;
import java.util.Optional;

/**
 * 中文说明：模型执行的唯一配置端口。运行期具名bean由后台coordinator提供，读取已完成的不可变完整发布，
 * 不在请求内查询数据库或发现物理实例。来源仅为部署租户的当前权威模型/渠道行；源仓储在短只读RR事务内批量捕获，
 * 不提供任意历史版本或离线无限期回退。未加载/失效返回原生503，真实已验证空集合才表示alias不存在。
 * English summary: Runtime requests read one completed immutable publication from the named coordinator, never a
 * database connection or physical registry. Background captures current deployment-tenant rows in a bounded read-only
 * RR transaction. Unavailable or expired publications fail with native 503; only a verified empty set represents absence.
 *
 * 用法 / Usage: 使用既有 {@code mpLlmConfigurationRepository} 限定名（别名 {@code llmConfigurationRepository}）。
 * 入口读一次并复用到路由、缓存查找和流结束后的写回；提示只唤醒，周期对账及固定有效期提供收敛边界。
 * / Resolve the existing named runtime port once per invocation and retain that snapshot through routing and cache
 * completion. Wake hints carry no policy authority and never extend the publication age.
 */
@Validated
public interface LlmConfigurationRepository {

    /**
     * 中文说明：读取一个模型 alias 的只读一致快照：模型侧业务事实，加上其 {@code routes[]} 显式声明并按渠道 key
     * 批量解析出的渠道事实（顺序保持持久层声明顺序，route 数与 key 数分别受 16/64 上限约束）。
     * English summary: Reads the read-only coherent snapshot of one model alias: the model-side business facts plus the
     * channel facts resolved by the channel keys its {@code routes[]} explicitly declare, keeping the persisted
     * declaration order under the separate 16-route and 64-key ceilings.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code LlmConfigurationRepository.findSnapshot(modelKey)}；在请求进入时调用一次，
     * 整个请求（含至多两次尝试）复用同一份快照，不在重试时重读配置。
     * / Call it once as the request arrives and reuse the same snapshot for the whole request, including its at most two
     * attempts, rather than re-reading configuration on a retry.
     * @param modelKey 参数 已校验的客户端 alias，非空、至多 64 且符合受管 key 形态；parameter the validated client alias, non-blank, at most 64 and in the managed key shape.
     * @return 返回 该 alias 的只读快照，alias 不存在或已软删时为 {@link Optional#empty()}；returns the snapshot, or {@link Optional#empty()} when the alias is absent or logically deleted.
     */
    Optional<LlmModelSnapshotBO> findSnapshot(@NotBlank
                                              @Size(max = 64)
                                              @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,63}$")
                                              String modelKey);

    /**
     * 中文说明：读取当前租户的活跃模型目录，按稳定 {@code id} 升序且有界（配置表规模由部署约束在各 1000 行以内），
     * 不做上游动态发现；返回的快照只保证模型侧字段，其 route 的渠道事实未解析即为 {@code null}，因此目录读不能被
     * 误用为路由依据。
     * English summary: Reads the current tenant's active model catalog ordered by the stable {@code id} ascending and
     * bounded (the configuration tables are deployment-capped at 1000 rows each) with no upstream dynamic discovery. A
     * returned snapshot guarantees only the model-side fields, and a route whose channel facts are unresolved stays
     * {@code null}, so the catalog read may never be mistaken for a routing decision.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code LlmConfigurationRepository.findCatalog()}；由目录入口在其之上继续施加
     * enabled 与 subject 授权过滤后投影为原生响应。
     * / The catalog endpoint keeps applying the enabled and subject-authorization filters above this read before
     * projecting the native response.
     * @return 返回 有界、按 id 升序的只读快照列表，没有配置时为空列表而非 {@code null}；returns the bounded, id-ascending snapshots, an empty list rather than {@code null} when nothing is configured.
     */
    List<LlmModelSnapshotBO> findCatalog();
}
