package top.egon.cola.component.yuheng.admin.mcp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import top.egon.cola.component.yuheng.admin.catalog.repository.GatewayCatalogRepository;
import top.egon.cola.component.yuheng.admin.mcp.domain.exception.McpValidationException;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpValidationFindingVO;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpValidationReportVO;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuleContent;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeTool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates API-to-tool projections, never native MCP assets or remote protocols. */
@Slf4j
@Service("mcpValidationService")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class McpValidationService {

    private static final Pattern PERMISSION = Pattern.compile(
            "^[a-z][a-z0-9._-]*(?::[A-Za-z0-9._*-]+)+$"
    );
    private static final Set<String> RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    @Qualifier("mpGatewayCatalogRepository")
    private final GatewayCatalogRepository catalog;
    @Qualifier("jacksonObjectMapper")
    private final ObjectMapper objectMapper;

    public void requireValid(McpRuleContent content) {
        Objects.requireNonNull(content, "content").validate();
        if (!content.resources().isEmpty() || !content.resourceTemplates().isEmpty()
                || !content.prompts().isEmpty() || !content.taskPolicies().isEmpty()
                || !content.apps().isEmpty() || !content.remoteProviders().isEmpty()
                || !content.remoteMounts().isEmpty()) {
            invalid("YUHENG_MCP_RULE_INVALID", "$",
                    "native MCP assets cannot be published by Yuheng");
        }
        Set<String> serverCodes = new HashSet<>();
        content.servers().forEach(server -> serverCodes.add(server.serverCode()));
        content.tools().forEach(tool -> {
            requireServer(serverCodes, tool.serverCode(), "tools");
            validateTool(tool);
        });
    }

    public McpValidationReportVO validate(McpRuleContent content) {
        List<McpValidationFindingVO> findings = new ArrayList<>();
        try {
            requireValid(content);
        } catch (McpValidationException failure) {
            findings.add(new McpValidationFindingVO(
                    failure.path(),
                    failure.code(),
                    failure.getMessage()
            ));
        } catch (IllegalArgumentException failure) {
            findings.add(new McpValidationFindingVO(
                    "$",
                    "YUHENG_MCP_RULE_INVALID",
                    failure.getMessage()
            ));
        }
        return new McpValidationReportVO(findings.isEmpty(), List.copyOf(findings));
    }

    private void validateTool(McpRuntimeTool tool) {
        if (!RISK_LEVELS.contains(tool.riskLevel())) {
            invalid(
                    "YUHENG_MCP_RISK_INVALID",
                    "tools." + tool.name() + ".riskLevel",
                    "unsupported MCP Tool risk level"
            );
        }
        if (!"LOCAL_OPERATION".equals(tool.sourceType())
                || tool.operationId() == null || tool.operationId().isBlank()
                || tool.remoteMountId() != null) {
            invalid("YUHENG_MCP_BINDING_INVALID", "tools." + tool.name(),
                    "only API-to-tool operation mappings can be published by Yuheng");
        }
        requireOperation(tool.operationId(), "tools." + tool.name());
        validateSchema(tool.inputSchema(), "tools." + tool.name()
                + ".inputSchema");
        validateSchema(tool.outputSchema(), "tools." + tool.name()
                + ".outputSchema");
        validatePermissions(
                tool.requiredPermissions(),
                "tools." + tool.name() + ".requiredPermissions"
        );
    }

    private void requireOperation(String operationId, String path) {
        var operation = catalog.findOperation(operationId)
                .orElseThrow(() -> error(
                        "YUHENG_MCP_OPERATION_NOT_FOUND",
                        path + ".operationId",
                        "gateway Operation " + operationId
                                + " was not found"
                ));
        if (catalog.loadDefinitions(operation.getId()).isEmpty()) {
            invalid(
                    "YUHENG_MCP_OPERATION_DEFINITION_NOT_FOUND",
                    path + ".operationId",
                    "gateway Operation has no active definition"
            );
        }
    }

    private void validateSchema(String schema, String path) {
        if (schema == null) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(schema);
            if (!root.isObject()) {
                invalid(
                        "YUHENG_MCP_SCHEMA_INVALID",
                        path,
                        "JSON Schema root must be an object"
                );
            }
            root.findValues("$ref").forEach(reference -> {
                String value = reference.asText();
                if (!value.startsWith("#")) {
                    invalid(
                            "YUHENG_MCP_SCHEMA_EXTERNAL_REF_FORBIDDEN",
                            path,
                            "external JSON Schema references are forbidden"
                    );
                }
            });
        } catch (JsonProcessingException failure) {
            invalid(
                    "YUHENG_MCP_SCHEMA_INVALID",
                    path,
                    "JSON Schema cannot be parsed"
            );
        }
    }

    private void validatePermissions(Set<String> values, String path) {
        values.forEach(value -> {
            if (!PERMISSION.matcher(value).matches()) {
                invalid(
                        "YUHENG_MCP_PERMISSION_INVALID",
                        path,
                        "permission name is invalid: " + value
                );
            }
        });
    }

    private void requireServer(
            Set<String> serverCodes,
            String serverCode,
            String path) {
        if (!serverCodes.contains(serverCode)) {
            invalid(
                    "YUHENG_MCP_SERVER_NOT_FOUND",
                    path,
                    "MCP capability references an unknown Server"
            );
        }
    }

    private void invalid(String code, String path, String message) {
        throw error(code, path, message);
    }

    private McpValidationException error(
            String code,
            String path,
            String message) {
        return new McpValidationException(code, path, message);
    }
}
