package studio.weaveora.shared.api;

/** 统一错误响应体（§17）。 */
public record ErrorResponse(String code, String message, String traceId) {

    public static ErrorResponse of(ErrorCode code, String message, String traceId) {
        return new ErrorResponse(code.name(), message, traceId);
    }
}
