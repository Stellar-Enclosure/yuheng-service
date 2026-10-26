package top.egon.cola.component.yuheng.edge.service;

/** Stable safe codes; caller-owned credentials and SQL exceptions are never part of the message. */
public final class EdgeOAuthStateException extends RuntimeException {
    private final String code;

    public EdgeOAuthStateException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
