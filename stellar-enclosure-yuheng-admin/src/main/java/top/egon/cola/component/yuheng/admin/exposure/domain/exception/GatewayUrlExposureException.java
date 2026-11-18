package top.egon.cola.component.yuheng.admin.exposure.domain.exception;

/** A closed set of safe public failures for the URL exposure API. */
public final class GatewayUrlExposureException extends RuntimeException {

    private final Failure failure;
    private final Long currentRevision;
    private final String field;

    private GatewayUrlExposureException(Failure failure, Long currentRevision, String field, Throwable cause) {
        super(failure.message, cause);
        this.failure = failure;
        this.currentRevision = currentRevision;
        this.field = field;
    }

    public static GatewayUrlExposureException requestInvalid(String path) {
        return new GatewayUrlExposureException(Failure.REQUEST_INVALID, null, path, null);
    }

    public static GatewayUrlExposureException preconditionRequired() {
        return new GatewayUrlExposureException(Failure.PRECONDITION_REQUIRED, null, "If-Match", null);
    }

    public static GatewayUrlExposureException preconditionFailed(long revision) {
        return new GatewayUrlExposureException(Failure.PRECONDITION_FAILED, revision, "If-Match", null);
    }

    public static GatewayUrlExposureException accessDenied() {
        return new GatewayUrlExposureException(Failure.ACCESS_DENIED, null, null, null);
    }

    public static GatewayUrlExposureException withdrawn(long revision) {
        return new GatewayUrlExposureException(Failure.WITHDRAWN, revision, null, null);
    }

    public static GatewayUrlExposureException bindingConflict() {
        return new GatewayUrlExposureException(Failure.BINDING_CONFLICT, null, "state", null);
    }

    public static GatewayUrlExposureException controlUnavailable(Throwable cause) {
        return new GatewayUrlExposureException(Failure.CONTROL_UNAVAILABLE, null, null, cause);
    }

    public int status() {
        return failure.status;
    }

    public String code() {
        return "YUHENG_EXPOSURE_" + failure.name();
    }

    public Long currentRevision() {
        return currentRevision;
    }

    public String field() {
        return field;
    }

    private enum Failure {
        REQUEST_INVALID(400, "The exposure request is invalid"),
        PRECONDITION_REQUIRED(428, "Supply the exposure's current ETag in If-Match"),
        PRECONDITION_FAILED(412, "Reload the current exposure before retrying the change"),
        ACCESS_DENIED(403, "This identity cannot perform the requested exposure action"),
        WITHDRAWN(409, "This exposure is permanently withdrawn"),
        BINDING_CONFLICT(409, "The required public exposure binding could not be verified"),
        CONTROL_UNAVAILABLE(503, "The exposure control store is temporarily unavailable");

        private final int status;
        private final String message;

        Failure(int status, String message) {
            this.status = status;
            this.message = message;
        }
    }
}
