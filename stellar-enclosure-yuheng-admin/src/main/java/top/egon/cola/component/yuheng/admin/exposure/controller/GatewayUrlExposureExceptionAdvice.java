package top.egon.cola.component.yuheng.admin.exposure.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import top.egon.cola.component.yuheng.admin.exposure.domain.exception.GatewayUrlExposureException;
import top.egon.cola.component.yuheng.admin.shared.controller.GatewayAdminExceptionHandler;
import top.egon.cola.component.yuheng.admin.shared.domain.RequestAuditContext;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminIdempotencyConflictException;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminNotFoundException;
import top.egon.cola.component.yuheng.admin.shared.domain.vo.GatewayAdminErrorVO;
import top.egon.cola.component.yuheng.admin.shared.domain.vo.GatewayAdminFieldErrorVO;

import java.time.Instant;
import java.util.List;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = GatewayUrlExposureController.class)
public class GatewayUrlExposureExceptionAdvice {

    private final GatewayAdminExceptionHandler adminErrors;

    public GatewayUrlExposureExceptionAdvice(
            @Qualifier("gatewayAdminExceptionHandler") GatewayAdminExceptionHandler adminErrors) {
        this.adminErrors = adminErrors;
    }

    @ExceptionHandler(GatewayUrlExposureException.class)
    public ResponseEntity<GatewayAdminErrorVO> exposure(GatewayUrlExposureException failure, HttpServletRequest request) {
        List<GatewayAdminFieldErrorVO> fields = failure.field() == null ? List.of() : List.of(
                new GatewayAdminFieldErrorVO(failure.field(), failure.status() == 412 ? "STALE" : "INVALID",
                        failure.getMessage()));
        var body = new GatewayAdminErrorVO(failure.code(), failure.getMessage(), failure.currentRevision(), fields, Instant.now());
        return withExposureHeaders(ResponseEntity.status(failure.status()).body(body), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<GatewayAdminErrorVO> invalidJson(HttpServletRequest request) {
        return exposure(GatewayUrlExposureException.requestInvalid("body"), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<GatewayAdminErrorVO> invalidFields(MethodArgumentNotValidException failure, HttpServletRequest request) {
        return withExposureHeaders(adminErrors.validation(failure), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<GatewayAdminErrorVO> unsupportedType(HttpMediaTypeNotSupportedException failure, HttpServletRequest request) {
        return withExposureHeaders(adminErrors.unsupportedRequestMediaType(failure), request);
    }

    @ExceptionHandler(GatewayAdminNotFoundException.class)
    public ResponseEntity<GatewayAdminErrorVO> notFound(GatewayAdminNotFoundException failure, HttpServletRequest request) {
        return withExposureHeaders(adminErrors.notFound(failure), request);
    }

    @ExceptionHandler(GatewayAdminIdempotencyConflictException.class)
    public ResponseEntity<GatewayAdminErrorVO> idempotencyConflict(GatewayAdminIdempotencyConflictException failure, HttpServletRequest request) {
        return withExposureHeaders(adminErrors.idempotency(failure), request);
    }

    private ResponseEntity<GatewayAdminErrorVO> withExposureHeaders(ResponseEntity<GatewayAdminErrorVO> error,
                                                                      HttpServletRequest request) {
        String requestId = RequestAuditContext.current(request.getHeader("x-egon-request-id")).requestId();
        return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders()).cacheControl(CacheControl.noStore())
                .header("x-egon-request-id", requestId).body(error.getBody());
    }
}
