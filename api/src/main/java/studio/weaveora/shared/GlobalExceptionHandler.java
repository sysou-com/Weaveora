package studio.weaveora.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;
import studio.weaveora.shared.api.ErrorResponse;

import java.util.stream.Collectors;

/** 全局异常 → 统一 JSON 错误体（§17）。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ErrorResponse> handleBiz(BizException ex) {
        ErrorCode code = ex.code();
        return ResponseEntity.status(code.httpStatus())
                .body(ErrorResponse.of(code, ex.getMessage(), ""));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.VALIDATION, msg, ""));
    }

    /**
     * 路径/查询参数类型不对（最典型：UUID 位置传了别的段）→ **400**，不是 500。
     *
     * <p>真实事故（2026-09-23）：前端在离开项目页后仍用空的 projectId 发请求
     * {@code GET /api/v1/projects//jobs}，Tomcat 把 {@code //} 归一成 {@code /}，Spring 把下一段
     * 当成 projectId ⇒ {@code Invalid UUID string: jobs}。它落到兜底 handler 里被记成
     * 「unhandled error」+ 500 ⇒ 日志里当成服务端故障，排查方向被带偏。
     * 参数绑定失败属于**客户端请求错**，返回 400 + 原话，便于一眼看出是哪个路径写错了。
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        String msg = "参数 '" + ex.getName() + "' 的值不合法（应为 "
                + (ex.getRequiredType() == null ? "期望类型" : ex.getRequiredType().getSimpleName())
                + "）：" + ex.getValue();
        log.warn("参数类型不匹配（400，非服务端故障）：{}", msg);
        return ResponseEntity.badRequest().body(ErrorResponse.of(ErrorCode.VALIDATION, msg, ""));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("unhandled error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(ErrorCode.VALIDATION, "internal error", ""));
    }
}
