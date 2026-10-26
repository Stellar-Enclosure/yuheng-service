package top.egon.cola.component.yuheng.edge.service.domain.bo;

/** Only ACQUIRED permits the caller to proceed to the single dispatch CAS. */
public record EdgeRefreshClaimResultBO(Outcome outcome, EdgeRefreshOperationBO operation) {
    public enum Outcome { MISSING, ACQUIRED, EXISTING, IN_PROGRESS, REPLAY, CLOSED, CONFLICT }
}
