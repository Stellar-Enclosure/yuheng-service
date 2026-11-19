package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.core.operation.GatewayInvocationResult;
import top.egon.cola.component.yuheng.core.operation.GatewayOperationCall;
import top.egon.cola.component.yuheng.core.operation.GatewayOperationInvocation;
import top.egon.cola.component.yuheng.mcp.task.domain.McpTask;
import top.egon.cola.component.yuheng.mcp.task.service.McpTaskBindingVO;
import top.egon.cola.component.yuheng.mcp.task.service.McpTaskExecutor;

/**
 * 中文说明：恢复任务只使用受理时固定的绑定，并由 bound invoker 在发送前重新验证权威与独立出站 SERVICE 授权。
 * 不读取当前 activation、任意 URI 或任务中保存的 token，也不把历史 subject 当成下游 USER 身份。
 * English summary: Resumes the exact admitted binding through fresh authority and independent outbound SERVICE
 * authorization. No active-version/URI/token fallback and no synthetic downstream USER identity.
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public final class McpTaskOperationExecutor implements McpTaskExecutor {
    @Qualifier("mcpBoundOperationInvoker") private final McpBoundOperationInvoker operationInvoker;
    @Qualifier("jacksonObjectMapper") private final ObjectMapper objectMapper;

    /**
     * 中文说明：读取固定引用后委托原子验证+发送边界；缺 pin 的历史任务仅可查看或取消，不执行。
     * English summary: Delegates pinned execution to the authorization/send boundary; unpinned history never executes.
     * @param task 已认领任务；the leased task.
     * @return 协议结果；the protocol outcome.
     */
    @Override
    public Publisher<Outcome> execute(McpTask task) {
        Objects.requireNonNull(task, "task");
        return Mono.defer(() -> {
            McpTaskBindingVO binding;
            try {
                binding = McpTaskBindingVO.require(task.inputPayload());
            } catch (IllegalStateException invalid) {
                return Mono.just(Outcome.failed(Map.of("code", invalid.getMessage())));
            }
            if (!binding.selection().serverCode().equals(task.serverCode())) {
                return Mono.just(Outcome.failed(Map.of("code", "MCP_TASK_BINDING_INVALID")));
            }
            Object operation = task.inputPayload().get("operationId");
            if (!(operation instanceof String operationId) || operationId.isBlank()) {
                return Mono.just(Outcome.failed(Map.of("code", "MCP_TASK_OPERATION_MISSING")));
            }
            GatewayOperationInvocation invocation = new GatewayOperationInvocation(
                new GatewayOperationCall(operationId, arguments(task.inputPayload().get("pathArguments")),
                    arguments(task.inputPayload().get("queryArguments")),
                    body(task.inputPayload().get("body"), task.inputPayload().get("inputResponse"))),
                null, null, null, Map.of());
            return operationInvoker.invokeBound(binding.selection(), binding.pin(), invocation).map(this::outcome);
        });
    }

    /**
     * 中文说明：执行 arguments 操作；该方法是 {@code McpTaskOperationExecutor}
     * 的调用入口，负责根据输入完成对应的运行时、管理面或协议处理。 English summary: Executes the arguments operation;
     * this method is the invocation entry point on {@code McpTaskOperationExecutor} and performs the corresponding
     * runtime, management, or protocol work.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code
     * McpTaskOperationExecutor.arguments(...)}。调用方应准备合法参数并处理返回值或异常；/ Call it with valid arguments
     * and handle the return value or exception according to the owning component's lifecycle.
     * @param configured 参数 configured；parameter configured。
     * @return 返回 arguments 的处理结果；returns the result of the operation.
     */
    private Map<String, Object> arguments(Object configured) {
        if (!(configured instanceof Map<?, ?> source)) {
            return Map.of();
        }
        LinkedHashMap<String, Object> arguments = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key instanceof String name) {
                arguments.put(name, value);
            }
        });
        return Map.copyOf(arguments);
    }

    /**
     * 中文说明：执行 body 操作；该方法是 {@code McpTaskOperationExecutor}
     * 的调用入口，负责根据输入完成对应的运行时、管理面或协议处理。 English summary: Executes the body operation; this
     * method is the invocation entry point on {@code McpTaskOperationExecutor} and performs the corresponding runtime,
     * management, or protocol work.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code
     * McpTaskOperationExecutor.body(...)}。调用方应准备合法参数并处理返回值或异常；/ Call it with valid arguments and
     * handle the return value or exception according to the owning component's lifecycle.
     * @param configured 参数 configured；parameter configured。
     * @param inputResponse 参数 input响应；parameter input response。
     * @return 返回 body 的处理结果；returns the result of the operation.
     */
    private Object body(Object configured, Object inputResponse) {
        if (!(inputResponse instanceof Map<?, ?> input)) {
            return configured;
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (configured instanceof Map<?, ?> source) {
            source.forEach((key, value) -> {
                if (key instanceof String name) {
                    result.put(name, value);
                }
            });
        }
        result.put("input", input);
        return Map.copyOf(result);
    }

    /**
     * 中文说明：执行 outcome 操作；该方法是 {@code McpTaskOperationExecutor}
     * 的调用入口，负责根据输入完成对应的运行时、管理面或协议处理。 English summary: Executes the outcome operation;
     * this method is the invocation entry point on {@code McpTaskOperationExecutor} and performs the corresponding
     * runtime, management, or protocol work.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code
     * McpTaskOperationExecutor.outcome(...)}。调用方应准备合法参数并处理返回值或异常；/ Call it with valid arguments
     * and handle the return value or exception according to the owning component's lifecycle.
     * @param result 参数 result；parameter result。
     * @return 返回 outcome 的处理结果；returns the result of the operation.
     */
    private Outcome outcome(GatewayInvocationResult result) {
        Map<String, Object> payload = payload(result.body());
        String inputKey = firstHeader(result.headers(), "x-egon-mcp-input-key");
        if (result.statusCode() == 202 && inputKey != null) {
            return Outcome.inputRequired(inputKey, payload);
        }
        if (result.statusCode() >= 400) {
            return Outcome.failed(Map.of("code", "MCP_TASK_UPSTREAM_FAILED", "status", result.statusCode()));
        }
        return Outcome.completed(payload);
    }

    /**
     * 中文说明：执行 payload 操作；该方法是 {@code McpTaskOperationExecutor}
     * 的调用入口，负责根据输入完成对应的运行时、管理面或协议处理。 English summary: Executes the payload operation;
     * this method is the invocation entry point on {@code McpTaskOperationExecutor} and performs the corresponding
     * runtime, management, or protocol work.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code
     * McpTaskOperationExecutor.payload(...)}。调用方应准备合法参数并处理返回值或异常；/ Call it with valid arguments
     * and handle the return value or exception according to the owning component's lifecycle.
     * @param responseBody 参数 响应Body；parameter response body。
     * @return 返回 payload 的处理结果；returns the result of the operation.
     */
    private Map<String, Object> payload(byte[] responseBody) {
        if (responseBody.length == 0) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root != null && root.isObject()) {
                return objectMapper.convertValue(root, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception ignored) {
            // Non-JSON task results are returned as bounded Base64 content.
        }
        if (responseBody.length > 1024 * 1024) {
            return Map.of("code", "MCP_TASK_RESULT_TOO_LARGE");
        }
        return Map.of("contentBase64", Base64.getEncoder().encodeToString(responseBody));
    }

    /**
     * 中文说明：执行 firstHeader 操作；该方法是 {@code McpTaskOperationExecutor}
     * 的调用入口，负责根据输入完成对应的运行时、管理面或协议处理。 English summary: Executes the first header
     * operation; this method is the invocation entry point on {@code McpTaskOperationExecutor} and performs the
     * corresponding runtime, management, or protocol work.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code
     * McpTaskOperationExecutor.firstHeader(...)}。调用方应准备合法参数并处理返回值或异常；/ Call it with valid
     * arguments and handle the return value or exception according to the owning component's lifecycle.
     * @param headers 参数 headers；parameter headers。
     * @param name 参数 name；parameter name。
     * @return 返回 firstHeader 的处理结果；returns the result of the operation.
     */
    private String firstHeader(Map<String, List<String>> headers, String name) {
        return headers.entrySet()
            .stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase(name))
            .flatMap(entry -> entry.getValue().stream())
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .map(String::trim)
            .orElse(null);
    }

    /**
     * 中文说明：执行 required 操作；该方法是 {@code McpTaskOperationExecutor}
     * 的调用入口，负责根据输入完成对应的运行时、管理面或协议处理。 English summary: Executes the required operation;
     * this method is the invocation entry point on {@code McpTaskOperationExecutor} and performs the corresponding
     * runtime, management, or protocol work.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code
     * McpTaskOperationExecutor.required(...)}。调用方应准备合法参数并处理返回值或异常；/ Call it with valid arguments
     * and handle the return value or exception according to the owning component's lifecycle.
     * @param value 参数 值；parameter value。
     * @param field 参数 field；parameter field。
     * @return 返回 required 的处理结果；returns the result of the operation.
     */
    private static String required(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalStateException(field + " is required");
        }
        return value;
    }
}
