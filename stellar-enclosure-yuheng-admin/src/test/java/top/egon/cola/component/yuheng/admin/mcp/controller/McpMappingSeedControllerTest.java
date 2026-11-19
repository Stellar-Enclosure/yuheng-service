package top.egon.cola.component.yuheng.admin.mcp.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static top.egon.cola.component.yuheng.admin.mcp.application.McpMappingSeedArtifactTest.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import top.egon.cola.component.yuheng.admin.mcp.service.McpMappingSeedService;
import top.egon.cola.component.yuheng.admin.shared.domain.*;
import top.egon.cola.component.yuheng.admin.shared.domain.enums.AdminActorTypeEnum;
import top.egon.cola.component.yuheng.openapi.annotation.EgonGatewayPolicy;

class McpMappingSeedControllerTest {
    static final String PATH = "/api/v1/yuheng/admin/mcp/groups/10/mapping-seeds";
    static final AdminActor ACTOR = new AdminActor(
        "operator", AdminActorTypeEnum.USER, Set.of("yuheng:mcp:write"), Set.of(), Set.of("yuheng:mcp:write"));
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }
    ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
            .withUserConfiguration(SecurityFixture.class, McpMappingSeedController.class)
            .withPropertyValues("yuheng.admin.mcp.mapping-seed.enabled=true");
    }
    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class SecurityFixture {
        @Bean("mcpMappingSeedService")
        McpMappingSeedService service() {
            return mock(McpMappingSeedService.class);
        }
    }
    @Test
    void realMethodSecurityRequiresBothVerifiedOauthAndCapability() {
        runner().run(c -> {
            var controller = c.getBean(McpMappingSeedController.class);
            var service = c.getBean(McpMappingSeedService.class);
            for (String[] authorities : List.of(new String[] {"CAP_*"}, new String[] {"SCOPE_yuheng:mcp:write"},
                     new String[] {"SCOPE_yuheng:mcp:read", "CAP_*"})) {
                SecurityContextHolder.getContext().setAuthentication(
                    new TestingAuthenticationToken("operator", "", authorities));
                assertThatThrownBy(() -> controller.freeze("10", command(), "k1", ACTOR))
                    .isInstanceOf(AccessDeniedException.class);
            }
            verifyNoInteractions(service);
        });
    }
    @Test
    void approvedOperatorDownloadsExactBytesAndOnlyPostIsMapped() {
        runner().run(c -> {
            SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("operator", "", "SCOPE_yuheng:mcp:write", "CAP_yuheng:mcp:write"));
            var service = c.getBean(McpMappingSeedService.class);
            when(service.freeze(eq("10"), any(), eq("k1"), eq(ACTOR), any())).thenReturn(artifact());
            var mvc = MockMvcBuilders.standaloneSetup(c.getBean(McpMappingSeedController.class))
                          .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                              public boolean supportsParameter(MethodParameter p) {
                                  return p.getParameterType() == AdminActor.class;
                              }
                              public Object resolveArgument(MethodParameter p, ModelAndViewContainer m,
                                  NativeWebRequest r, WebDataBinderFactory f) {
                                  return ACTOR;
                              }
                          })
                          .setMessageConverters(new ByteArrayHttpMessageConverter(),
                              new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                          .build();
            mvc.perform(post(PATH)
                            .contentType("application/json")
                            .header("Idempotency-Key", "k1")
                            .content(new ObjectMapper().writeValueAsBytes(command())))
                .andExpect(status().isOk())
                .andExpect(content().bytes(artifact().canonicalBytes()))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Mcp-Mapping-Artifact-Sha256", artifact().artifactSha256()))
                .andExpect(header().exists("Content-Digest"));
            mvc.perform(get(PATH)).andExpect(status().isMethodNotAllowed());
        });
    }
    @Test
    void seedEndpointIsInternalAndDisabledByDefault() {
        runner()
            .withPropertyValues("yuheng.admin.mcp.mapping-seed.enabled=false")
            .run(c -> assertThat(c).doesNotHaveBean(McpMappingSeedController.class));
        try {
            var method = McpMappingSeedController.class.getMethod("freeze", String.class,
                top.egon.cola.component.yuheng.admin.mcp.domain.dto.McpMappingSeedFreezeCommand.class, String.class,
                AdminActor.class);
            assertThat(method.getAnnotation(EgonGatewayPolicy.class).exposure())
                .isEqualTo(EgonGatewayPolicy.Exposure.INTERNAL);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }
}
