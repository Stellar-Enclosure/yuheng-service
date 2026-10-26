package top.egon.cola.component.yuheng.admin.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import top.egon.cola.platform.tianquan.jianshen.starter.field.PermissionJsonMapperFactory;
import java.util.List;

/** Field policy is installed only on HTTP mapper copies; native OAuth and SQL codecs keep their original mapper. */
@Configuration(proxyBeanMethods=false)
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(prefix="egon.cola.platform.tianquan.jianshen",name="enabled",havingValue="true")
public class GatewayPermissionWebConfiguration implements WebMvcConfigurer {
    private final PermissionJsonMapperFactory fields;
    private final com.fasterxml.jackson.databind.ObjectMapper transportMapper;
    public GatewayPermissionWebConfiguration(PermissionJsonMapperFactory fields,
            @org.springframework.beans.factory.annotation.Qualifier("jacksonObjectMapper") com.fasterxml.jackson.databind.ObjectMapper transportMapper) {
        this.fields=fields; this.transportMapper=transportMapper;
    }
    @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        var preferred = converters.stream().filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(MappingJackson2HttpMessageConverter.class::cast).findFirst()
                .orElseGet(() -> new MappingJackson2HttpMessageConverter(transportMapper.copy()));
        converters.remove(preferred);
        int jsonIndex = 0;
        while (jsonIndex < converters.size() && !converters.get(jsonIndex).canRead(java.util.Map.class, org.springframework.http.MediaType.APPLICATION_JSON)) jsonIndex++;
        converters.add(jsonIndex, preferred);
        new GatewayAiJsonConfiguration().extendMessageConverters(List.of(preferred));
        for(var converter:converters) if(converter instanceof MappingJackson2HttpMessageConverter json) {
            json.setObjectMapper(fields.forTransport(json.getObjectMapper()));
            for (var type : java.util.List.of(
                    top.egon.cola.component.yuheng.admin.llm.domain.dto.LlmChannelCommandDTO.class,
                    top.egon.cola.component.yuheng.admin.llm.domain.dto.LlmModelCommandDTO.class,
                    top.egon.cola.component.yuheng.admin.llm.domain.dto.LlmRouteBindingDTO.class)) {
                json.registerObjectMappersForType(type, mappings -> mappings.replaceAll((media, mapper) -> fields.forTransport(mapper)));
            }
        }
    }
}
