package top.egon.cola.component.yuheng.mcp.engine.mcp.converter;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import top.egon.cola.component.common.core.converter.BaseForwardConverter;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpTaskBO;
import top.egon.cola.component.yuheng.mcp.task.domain.McpTask;
import top.egon.cola.component.yuheng.mcp.task.service.McpTaskBindingVO;

import java.util.*;

/** Projects the runtime task without exposing its persisted transport binding or credential fields. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface McpTaskManagementConverter extends BaseForwardConverter<McpTask, McpTaskBO> {
    @Override
    @Mapping(target = "state", expression = "java(source.state().name())")
    @Mapping(target = "inputPayload", qualifiedByName = "safeTaskPayload")
    @Mapping(target = "resultPayload", qualifiedByName = "safeTaskPayload")
    @Mapping(target = "errorPayload", qualifiedByName = "safeTaskPayload")
    McpTaskBO toTarget(McpTask source);

    @Named("safeTaskPayload")
    default Map<String,Object> safeTaskPayload(Map<String,Object> payload) {
        if (payload == null) return null;
        return cleanMap(payload, 0);
    }
    private static Map<String,Object> cleanMap(Map<?,?> source, int depth) {
        if (depth > 32 || source.size() > 1000) throw new IllegalArgumentException("MCP_TASK_VIEW_INVALID");
        Map<String,Object> result = new LinkedHashMap<>();
        for (var entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("MCP_TASK_VIEW_INVALID");
            String normalized = key.replaceAll("[-_.]", "").toLowerCase(Locale.ROOT);
            if (key.equals(McpTaskBindingVO.PAYLOAD_KEY) || normalized.contains("token") || normalized.contains("secret")
                    || normalized.contains("password") || normalized.contains("credential") || normalized.equals("authorization")) continue;
            result.put(key, clean(entry.getValue(), depth + 1));
        }
        return Collections.unmodifiableMap(result);
    }
    private static Object clean(Object value, int depth) {
        if (value instanceof Map<?,?> map) return cleanMap(map, depth);
        if (value instanceof List<?> list) {
            if (depth > 32 || list.size() > 1000) throw new IllegalArgumentException("MCP_TASK_VIEW_INVALID");
            return list.stream().map(item -> clean(item, depth + 1)).toList();
        }
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) return value;
        throw new IllegalArgumentException("MCP_TASK_VIEW_INVALID");
    }
}
