package studio.weaveora.shared.api;

/** 统一业务异常（§17.1），由 @RestControllerAdvice 转 JSON。 */
public class BizException extends RuntimeException {

    private final ErrorCode code;

    public BizException(ErrorCode code) {
        super(code.name());
        this.code = code;
    }

    public BizException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
