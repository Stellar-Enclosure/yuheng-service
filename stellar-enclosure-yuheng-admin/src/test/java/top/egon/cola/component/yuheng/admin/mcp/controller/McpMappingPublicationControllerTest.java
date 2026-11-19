package top.egon.cola.component.yuheng.admin.mcp.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static top.egon.cola.component.yuheng.admin.mcp.application.McpMappingPublicationExportTest.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.release.service.GatewayReleaseService;
import top.egon.cola.component.yuheng.admin.shared.domain.AdminActor;
import top.egon.cola.component.yuheng.openapi.annotation.EgonGatewayPolicy;

class McpMappingPublicationControllerTest {
    static final String PATH = "/api/v1/yuheng/admin/mcp/groups/10/mapping-publications/301/si/201";
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }
    ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
            .withUserConfiguration(Fixture.class, McpMappingPublicationController.class)
            .withPropertyValues("yuheng.admin.mcp.publication-export.enabled=true");
    }
    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Fixture {
        @Bean("gatewayReleaseService")
        GatewayReleaseService releases() {
            return mock(GatewayReleaseService.class);
        }
        @Bean("gatewayPersistenceContextComponent")
        GatewayPersistenceContextComponent context() {
            var c = mock(GatewayPersistenceContextComponent.class);
            when(c.installationTenantId()).thenReturn(7L);
            return c;
        }
    }
    @Test
    void operatorCannotExportWithOnlyLegacyCapabilityOrReadScope() {
        runner().run(c -> {
            var controller = c.getBean(McpMappingPublicationController.class);
            var service = c.getBean(GatewayReleaseService.class);
            for (String[] authorities : List.of(new String[] {"CAP_*"}, new String[] {"SCOPE_yuheng:mcp:read", "CAP_*"},
                     new String[] {"SCOPE_yuheng:mcp:write"})) {
                SecurityContextHolder.getContext().setAuthentication(
                    new TestingAuthenticationToken("operator", "", authorities));
                assertThatThrownBy(() -> controller.export("10", "301", "201", "501", "1", ACTOR))
                    .isInstanceOf(AccessDeniedException.class);
            }
            verifyNoInteractions(service);
        });
    }
    @Test
    void internalOperatorGetUsesInstallationOwnerAndReturnsOnlyCanonicalProof() {
        runner().run(c -> {
            SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("operator", "", "SCOPE_yuheng:mcp:write", "CAP_yuheng:mcp:write"));
            var service = c.getBean(GatewayReleaseService.class);
            when(service.exportBoundMapping(eq(7L), eq("10"), eq("301"), eq("201"), eq("501"), eq("1"), eq(ACTOR)))
                .thenReturn(vector());
            var mvc = MockMvcBuilders.standaloneSetup(c.getBean(McpMappingPublicationController.class))
                          .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                              public boolean supportsParameter(MethodParameter p) {
                                  return p.getParameterType() == AdminActor.class;
                              }
                              public Object resolveArgument(MethodParameter p, ModelAndViewContainer m,
                                  NativeWebRequest r, WebDataBinderFactory f) {
                                  return ACTOR;
                              }
                          })
                          .setMessageConverters(new ByteArrayHttpMessageConverter())
                          .build();
            mvc.perform(get(PATH).param("versionId", "501").param("versionNo", "1"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(vector().canonicalBytes()))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("Content-Digest"));
            mvc.perform(get(PATH).param("versionId", "501")).andExpect(status().isBadRequest());
            mvc.perform(post(PATH)).andExpect(status().isMethodNotAllowed());
            verify(service, times(1))
                .exportBoundMapping(eq(7L), eq("10"), eq("301"), eq("201"), eq("501"), eq("1"), eq(ACTOR));
        });
    }
    @Test
    void disabledAndPublisherRolesHaveNoOperatorExportRoute() {
        runner()
            .withPropertyValues("yuheng.admin.mcp.publication-export.enabled=false")
            .run(c -> assertThat(c).doesNotHaveBean(McpMappingPublicationController.class));
        runner()
            .withPropertyValues("yuheng.admin.exposure.role=PUBLISHER")
            .run(c -> assertThat(c).doesNotHaveBean(McpMappingPublicationController.class));
        try {
            var method = McpMappingPublicationController.class.getMethod(
                "export", String.class, String.class, String.class, String.class, String.class, AdminActor.class);
            assertThat(method.getAnnotation(EgonGatewayPolicy.class).exposure())
                .isEqualTo(EgonGatewayPolicy.Exposure.INTERNAL);
        } catch (NoSuchMethodException failure) {
            throw new AssertionError(failure);
        }
    }
}
