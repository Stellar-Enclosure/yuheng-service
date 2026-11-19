package top.egon.cola.component.yuheng.admin.mcp.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.egon.cola.component.yuheng.admin.shared.controller.GatewayAdminExceptionHandler;
import top.egon.cola.component.yuheng.admin.shared.controller.GatewayAdminActorArgumentResolver;
import top.egon.cola.component.yuheng.admin.mcp.service.McpControlPlaneService;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpAdminApiIT {

    @Test
    void nativeServerCreationHasNoMvcWriteEndpoint() throws Exception {
        McpControlPlaneService service = mock(McpControlPlaneService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new McpServerController(service))
                .setCustomArgumentResolvers(new GatewayAdminActorArgumentResolver())
                .setControllerAdvice(new GatewayAdminExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "controlled-admin", "", List.of()
                )
        );
        try {
            mvc.perform(post("/api/v1/yuheng/admin/mcp/servers")
                            .header("Idempotency-Key", "retired-native-server-write")
                            .contentType("application/json")
                            .content("""
                                    {
                                      "gatewayGroupId": "group-1",
                                      "serverCode": "billing",
                                      "displayName": "Billing",
                                      "dialects": ["STABLE_2025_11_25"],
                                      "resourceUri": "https://resource.egon.top/yuheng-mcp",
                                      "listCacheTtlSeconds": 30,
                                      "enabled": true,
                                      "expectedRevision": 0,
                                      "expectedDraftRevision": 0,
                                      "changeReason": "native definition belongs to Tianshu"
                                    }
                                    """))
                    .andExpect(status().isMethodNotAllowed());
            mvc.perform(put("/api/v1/yuheng/admin/mcp/servers/server-1"))
                    .andExpect(status().isMethodNotAllowed());
            mvc.perform(delete("/api/v1/yuheng/admin/mcp/servers/server-1"))
                    .andExpect(status().isMethodNotAllowed());
            verifyNoInteractions(service);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void nativeCapabilityAndRemoteProviderControllersAreAbsent() {
        for (String name : List.of("McpCapabilityController", "McpRemoteProviderController")) {
            assertThatThrownBy(() -> Class.forName(
                    "top.egon.cola.component.yuheng.admin.mcp.controller." + name))
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    void serverApiKeepsHistoricalReadsAndRejectsNativeWrites()
            throws Exception {
        McpControlPlaneService service = mock(McpControlPlaneService.class);
        when(service.listServers("group-1")).thenReturn(List.of(
                new top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO(
                        "server-1",
                        "group-1",
                        "billing",
                        "Billing",
                        null,
                        null,
                        Set.of("STABLE_2025_11_25"),
                        "https://resource.egon.top/yuheng-mcp",
                        30,
                        true,
                        0,
                        Instant.parse("2026-08-02T00:00:00Z"),
                        Instant.parse("2026-08-02T00:00:00Z")
                )
        ));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new McpServerController(service)
                )
                .setControllerAdvice(new GatewayAdminExceptionHandler())
                .build();

        mvc.perform(get("/api/v1/yuheng/admin/mcp/servers")
                        .queryParam("gatewayGroupId", "group-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].serverCode").value("billing"));

        mvc.perform(post("/api/v1/yuheng/admin/mcp/servers")
                        .contentType("application/json")
                        .content("""
                                {
                                  "gatewayGroupId": "group-1",
                                  "serverCode": "billing",
                                  "displayName": "Billing",
                                  "dialects": ["STABLE_2025_11_25"],
                                  "resourceUri": "https://resource.egon.top/yuheng-mcp",
                                  "listCacheTtlSeconds": 30,
                                  "expectedRevision": 0,
                                  "expectedDraftRevision": 0,
                                  "changeReason": "create billing MCP"
                                }
                                """))
                .andExpect(status().isMethodNotAllowed());
    }
}
