package top.egon.cola.component.yuheng.admin.exposure.converter;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.BeanMapping;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import org.springframework.stereotype.Component;
import top.egon.cola.component.common.core.converter.BaseConverter;
import top.egon.cola.component.yuheng.admin.exposure.domain.bo.GatewayUrlExposureBO;
import top.egon.cola.component.yuheng.admin.exposure.domain.po.GatewayUrlExposurePO;

/** Lossless control-row conversion; scope, audit identity and clocks are supplied by trusted callers. */
@Slf4j
@Component("gatewayUrlExposurePersistenceConverter")
public class GatewayUrlExposurePersistenceConverter implements BaseConverter<GatewayUrlExposurePO, GatewayUrlExposureBO> {

    private static final GatewayUrlExposureMapping MAPPING = Mappers.getMapper(GatewayUrlExposureMapping.class);

    public GatewayUrlExposurePO toPersistence(GatewayUrlExposureBO business) {
        if (business == null) {
            log.debug("URL exposure business carrier is absent");
            return null;
        }
        return MAPPING.toPersistence(business);
    }

    public GatewayUrlExposureBO toBusiness(GatewayUrlExposurePO persistence) {
        if (persistence == null) {
            log.debug("URL exposure persistence row is absent");
            return null;
        }
        return MAPPING.toBusiness(persistence);
    }

    @Override
    public GatewayUrlExposureBO toTarget(GatewayUrlExposurePO source) {
        return toBusiness(source);
    }

    @Override
    public GatewayUrlExposurePO toSource(GatewayUrlExposureBO target) {
        return toPersistence(target);
    }
}

@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
interface GatewayUrlExposureMapping {

    @BeanMapping(builder = @Builder(disableBuilder = true))
    @Mapping(target = "observation", source = "observation", qualifiedByName = "copyObservation")
    GatewayUrlExposurePO toPersistence(GatewayUrlExposureBO business);

    @BeanMapping(builder = @Builder(disableBuilder = true))
    @Mapping(target = "observation", source = "observation", qualifiedByName = "copyObservation")
    GatewayUrlExposureBO toBusiness(GatewayUrlExposurePO persistence);

    @Named("copyObservation")
    default JsonNode copyObservation(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }
}
