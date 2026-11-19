package top.egon.cola.component.yuheng.admin.mcp.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.egon.cola.component.common.core.converter.BaseForwardConverter;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolProjectionVO;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO;

/**
 * 将已校验的 MCP 映射投影为管理响应，按 Spring Jackson 配置解析 JSON Schema。
 * Projects an approved MCP mapping onto the management response and decodes JSON Schema
 * with the application's Jackson mapper.
 */
@Component("mcpApiMappingConverter")
@RequiredArgsConstructor
@Slf4j
public class McpApiMappingConverter implements BaseForwardConverter<McpManagedToolProjectionVO, McpManagedToolVO> {
    private static final McpApiMappingProjection PROJECTION = Mappers.getMapper(McpApiMappingProjection.class);

    @Qualifier("jacksonObjectMapper")
    private final ObjectMapper objectMapper;

    /**
     * 投影已审批的操作、SI 引用及有效权限；空输入返回空。
     * Maps the approved operation, SI reference and effective permissions, returning null for a null source.
     */
    @Override
    public McpManagedToolVO toTarget(McpManagedToolProjectionVO source) {
        if (source == null) {
            log.debug("MCP mapping projection has no source");
            return null;
        }
        return PROJECTION.toView(source, objectMapper);
    }
}

/** Selects the public fields from the mapping and its compiled tool. */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
interface McpApiMappingProjection {
    @Mapping(target = "toolId", source = "tool.toolId")
    @Mapping(target = "operationId", source = "tool.operationId")
    @Mapping(target = "name", source = "tool.name")
    @Mapping(target = "description", source = "tool.description")
    @Mapping(target = "operationProtocol", source = "tool.operationProtocol")
    @Mapping(target = "inputSchema", source = "tool.inputSchema", qualifiedByName = "readSchema")
    @Mapping(target = "outputSchema", source = "tool.outputSchema", qualifiedByName = "readSchema")
    @Mapping(target = "serverCode", source = "tool.serverCode")
    @Mapping(target = "effectivePermissions", source = "tool.requiredPermissions")
    @Mapping(target = "effectiveRiskLevel", source = "tool.riskLevel")
    @Mapping(target = "idempotent", source = "tool.idempotent")
    @Mapping(target = "enabled", source = "tool.enabled")
    McpManagedToolVO toView(McpManagedToolProjectionVO source, @Context ObjectMapper objectMapper);

    @Named("readSchema")
    default Object readSchema(String value, @Context ObjectMapper objectMapper) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("stored MCP schema is invalid", failure);
        }
    }
}
