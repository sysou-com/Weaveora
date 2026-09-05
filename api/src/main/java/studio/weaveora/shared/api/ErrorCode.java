package studio.weaveora.shared.api;

/** 业务异常码（镜像 Weaveora.md §17.1，先建 MVP 用到的子集）。 */
public enum ErrorCode {
    UNAUTHENTICATED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    VALIDATION(400),
    EMAIL_ALREADY_EXISTS(409),
    NICKNAME_ALREADY_EXISTS(409),
    INVALID_CREDENTIALS(401),
    USER_DISABLED(403),
    TOKEN_INVALID(401),
    TOKEN_EXPIRED(401),
    EMAIL_CODE_SEND_TOO_FREQUENT(429),
    EMAIL_CODE_INVALID(400),
    EMAIL_CODE_EXPIRED(400),
    RATE_LIMITED(429),
    WORKSPACE_ACCESS_DENIED(403);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
