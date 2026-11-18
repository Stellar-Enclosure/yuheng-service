package top.egon.cola.component.yuheng.admin.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import top.egon.cola.component.yuheng.admin.exposure.domain.dto.GatewayUrlExposureStateRequestDTO;
import top.egon.cola.component.yuheng.admin.llm.domain.dto.LlmChannelCommandDTO;
import top.egon.cola.component.yuheng.admin.llm.domain.dto.LlmModelCommandDTO;
import top.egon.cola.component.yuheng.admin.llm.domain.dto.LlmRouteBindingDTO;

import java.util.List;

/**
 * 中文说明：{@code GatewayAiJsonConfiguration} 是管理面配置类，负责本批新增 LLM、知识库与 Wiki 命令 carrier 的严格 JSON 绑定边界，legacy DTO 与四套上游协议 payload 的 JSON 行为保持原样。
 * English summary: {@code GatewayAiJsonConfiguration} is an admin configuration that owns the strict JSON binding boundary of the selected LLM and URL exposure command carriers.
 *
 * 用法 / Usage: 通过 Spring MVC 自 5.3.4 起提供的 {@code registerObjectMappersForType} 按类型注册，复制该 converter 正在使用的 Spring Boot ObjectMapper 副本并只翻转未知属性失败这一个反序列化特性；不替换全局 ObjectMapper，也不改动全局 JSON 配置。/ Register the carriers per type through {@code registerObjectMappersForType}, available since Spring MVC 5.3.4, from a copy of the Spring Boot ObjectMapper the converter already uses with strict handling of unknown properties and additionally reject ambiguous JSON for URL exposure commands.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class GatewayAiJsonConfiguration implements WebMvcConfigurer {

    /**
     * Request carriers whose binding rejects unknown properties, so
     * a caller cannot smuggle a server-derived field such as a review status.
     * A nested carrier is listed too: it inherits the strictness of its root
     * carrier anyway, and listing it keeps the same rule if it is ever bound
     * as a request body of its own.
     */
    static final List<Class<?>> STRICT_REQUEST_TYPES = List.of(
            LlmChannelCommandDTO.class,
            LlmModelCommandDTO.class,
            LlmRouteBindingDTO.class,
            GatewayUrlExposureStateRequestDTO.class
    );

    /**
     * Registers the strict mapper copy for the selected request carriers on every Jackson
     * message converter Spring MVC contributes to the admin interface.
     *
     * @param converters the configured converter list, extended in place
     */
    @Override
    public void extendMessageConverters(
            List<HttpMessageConverter<?>> converters) {
        int configuredConverters = 0;
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter jackson) {
                registerStrictRequests(jackson);
                configuredConverters++;
            }
        }
        log.info(
                "Enabled strict JSON binding for {} request types on {}"
                        + " Jackson message converters",
                STRICT_REQUEST_TYPES.size(),
                configuredConverters
        );
    }

    private void registerStrictRequests(
            MappingJackson2HttpMessageConverter converter) {
        // Fail closed: strict binding is a validation control, so a converter
        // without its main mapper must break startup instead of being skipped.
        ObjectMapper strictMapper = converter.getObjectMapper().copy();
        strictMapper.configure(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                true
        );
        ObjectMapper exposureMapper = strictMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        for (Class<?> carrierType : STRICT_REQUEST_TYPES) {
            ObjectMapper requestMapper = carrierType == GatewayUrlExposureStateRequestDTO.class
                    ? exposureMapper : strictMapper;
            // MediaType.ALL keeps every media type the converter already
            // accepts bound through this mapper: a registration that matches no
            // media type would switch the carrier off entirely instead of
            // falling back to the converter's default ObjectMapper.
            converter.registerObjectMappersForType(
                    carrierType,
                    registrations -> registrations.put(MediaType.ALL, requestMapper)
            );
        }
    }
}
