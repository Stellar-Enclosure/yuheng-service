package top.egon.cola.component.yuheng.admin.exposure.converter;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.BeanMapping;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import org.springframework.stereotype.Component;
import top.egon.cola.component.common.core.converter.BaseForwardConverter;
import top.egon.cola.component.yuheng.admin.exposure.domain.bo.GatewayUrlExposureBO;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.exposure.domain.vo.GatewayUrlExposureVO;
import top.egon.cola.component.yuheng.admin.exposure.domain.vo.GatewayUrlExposureVO.ConditionVO;
import top.egon.cola.component.yuheng.admin.exposure.domain.vo.GatewayUrlExposureVO.KubernetesRouteVO;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One-way, local projection. It never fetches Kubernetes or advances persisted observations. */
@Slf4j
@Component("gatewayUrlExposureViewConverter")
public class GatewayUrlExposureViewConverter implements BaseForwardConverter<GatewayUrlExposureBO, GatewayUrlExposureVO> {

    private static final GatewayUrlExposureViewMapping MAPPING = Mappers.getMapper(GatewayUrlExposureViewMapping.class);
    private static final String CILIUM_CONTROLLER = "io.cilium/gateway-controller";

    public GatewayUrlExposureVO toView(GatewayUrlExposureBO row) {
        if (row == null) {
            return null;
        }
        GatewayUrlExposureVO view = MAPPING.toView(row);
        JsonNode observation = row.getObservation();
        boolean completeShape = false;
        if (observation != null && observation.isObject() && observation.path("schemaVersion").isIntegralNumber()
                && observation.path("schemaVersion").canConvertToInt()
                && observation.path("schemaVersion").intValue() == 1) {
            JsonNode storedRoute = observation.get("kubernetesRoute");
            JsonNode storedConditions = observation.get("conditions");
            view.setKubernetesRoute(route(row, storedRoute));
            view.setConditions(conditions(storedConditions));
            completeShape = storedRoute != null && (storedRoute.isNull() || view.getKubernetesRoute() != null)
                    && storedConditions != null && storedConditions.isArray() && storedConditions.size() <= 64
                    && storedConditions.size() == view.getConditions().size();
        }
        if (view.getConditions() == null) {
            view.setConditions(List.of());
        }
        if ("SYNCED".equals(view.getReconcileStatus()) && (!completeShape || !hasCurrentEvidence(view))) {
            // Preserve the last confirmed observation and stored error; do not fabricate convergence.
            view.setReconcileStatus("PENDING");
        }
        return view;
    }

    @Override
    public GatewayUrlExposureVO toTarget(GatewayUrlExposureBO source) {
        return toView(source);
    }

    private KubernetesRouteVO route(GatewayUrlExposureBO row, JsonNode value) {
        if (value == null || !value.isObject()
                || !"HTTPRoute".equals(text(value, "kind", 32))
                || !Objects.equals(row.getRouteKind(), text(value, "kind", 32))
                || !Objects.equals(row.getRouteNamespace(), text(value, "namespace", 63))
                || !Objects.equals(row.getRouteName(), text(value, "name", 253))
                || row.getRouteUid() == null || !Objects.equals(row.getRouteUid(), text(value, "uid", 128))
                || decimal(value, "metadataGeneration", false) == null
                || text(value, "resourceVersion", 128) == null
                || !value.path("attached").isBoolean()) {
            return null;
        }
        var route = new KubernetesRouteVO()
                .setKind("HTTPRoute").setNamespace(row.getRouteNamespace()).setName(row.getRouteName())
                .setUid(row.getRouteUid()).setResourceVersion(text(value, "resourceVersion", 128))
                .setMetadataGeneration(decimal(value, "metadataGeneration", false))
                .setAttached(value.path("attached").booleanValue());
        String controller = text(value, "controllerName", 253);
        String conditionGeneration = decimal(value, "conditionObservedGeneration", true);
        if (route.getAttached() && CILIUM_CONTROLLER.equals(controller)
                && route.getMetadataGeneration().equals(conditionGeneration)) {
            route.setControllerName(controller).setConditionObservedGeneration(conditionGeneration);
        }
        return route;
    }

    private List<ConditionVO> conditions(JsonNode values) {
        if (values == null || !values.isArray() || values.size() > 64) {
            return List.of();
        }
        List<ConditionVO> result = new ArrayList<>();
        for (JsonNode value : values) {
            String type = text(value, "type", 128);
            String status = text(value, "status", 16);
            String reason = text(value, "reason", 128);
            String generation = decimal(value, "observedGeneration", true);
            String message = text(value, "message", 1024);
            String time = text(value, "lastTransitionTime", 64);
            if (type == null || !type.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}") || reason == null
                    || !reason.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}") || status == null
                    || !Set.of("True", "False", "Unknown").contains(status)
                    || generation == null || message == null || time == null || !time.endsWith("Z")) {
                continue;
            }
            try {
                result.add(new ConditionVO(type, status, reason, message, generation, Instant.parse(time)));
            } catch (DateTimeParseException ignored) {
                // A malformed historical condition is never promoted to current evidence.
            }
        }
        return List.copyOf(result);
    }

    private boolean hasCurrentEvidence(GatewayUrlExposureVO view) {
        if (view.getDesiredState() == null || !Objects.equals(view.getDesiredGeneration(), view.getObservedGeneration())
                || !view.getDesiredState().wireValue().equals(view.getObservedState())) {
            return false;
        }
        if (view.getDesiredState() != GatewayUrlExposureStateEnum.OPEN) {
            return (view.getKubernetesRoute() == null || !view.getKubernetesRoute().getAttached())
                    && currentTrue(view, "Detached") && currentTrue(view, "NoAlternateRoute")
                    && currentTrue(view, "DataplaneConverged");
        }
        KubernetesRouteVO route = view.getKubernetesRoute();
        // Programmed is a Gateway/listener fact supplied by the writer, never a made-up Route condition.
        // The writer separately validates certificate, backend and authorization evidence before SYNCED.
        return route != null && route.getAttached() && CILIUM_CONTROLLER.equals(route.getControllerName())
                && Objects.equals(route.getMetadataGeneration(), route.getConditionObservedGeneration())
                && currentTrue(view, "Accepted") && currentTrue(view, "ResolvedRefs")
                && currentTrue(view, "Programmed") && currentTrue(view, "DataplaneConverged");
    }

    private boolean currentTrue(GatewayUrlExposureVO view, String type) {
        List<ConditionVO> matching = view.getConditions().stream().filter(value -> type.equals(value.getType())).toList();
        return matching.size() == 1 && "True".equals(matching.getFirst().getStatus())
                && Objects.equals(view.getDesiredGeneration(), matching.getFirst().getObservedGeneration());
    }

    private String text(JsonNode object, String name, int maxLength) {
        JsonNode node = object == null ? null : object.get(name);
        if (node == null || !node.isTextual() || node.textValue().length() > maxLength
                || node.textValue().codePoints().anyMatch(Character::isISOControl)) {
            return null;
        }
        return node.textValue();
    }

    private String decimal(JsonNode object, String name, boolean zero) {
        String value = text(object, name, 19);
        if (value == null || !value.matches(zero ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}")) {
            return null;
        }
        try {
            Long.parseLong(value);
            return value;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
interface GatewayUrlExposureViewMapping {

    @BeanMapping(builder = @Builder(disableBuilder = true))
    @Mapping(target = "exposureId", source = "id")
    @Mapping(target = "method", source = "httpMethod")
    @Mapping(target = "url", expression = "java(\"https://\" + row.getHostname() + row.getPathValue())")
    @Mapping(target = "terminal", expression = "java(row.getDesiredState() != null && row.getDesiredState().isTerminal())")
    @Mapping(target = "updatedAt", source = "updateTime")
    @Mapping(target = "kubernetesRoute", ignore = true)
    @Mapping(target = "conditions", ignore = true)
    GatewayUrlExposureVO toView(GatewayUrlExposureBO row);
}
