package studio.weaveora.shared;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import studio.weaveora.shared.api.ErrorResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路径参数类型不匹配必须返回 **400**（客户端错），不是 500（服务端故障）。
 *
 * <p>真实事故（2026-09-23，用户报「生产定妆图都报错了」）：前端离开项目页后仍用空的 projectId
 * 发请求 `GET /api/v1/projects//jobs`，nginx 把 `//` 归一后应用收到 `/api/v1/projects/jobs`，
 * 匹配到 `/projects/{projectId}` 并把 "jobs" 绑给 UUID ⇒ `MethodArgumentTypeMismatchException`。
 * 它落到兜底 handler ⇒ 日志记成 `unhandled error` + HTTP 500 ⇒ 排查方向被带偏成"服务端故障"，
 * 而真实原因只是**前端把路径拼坏了**。
 */
class GlobalExceptionHandlerTest {

    @Test
    void UUID路径参数非法_返回400且带原始值() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "jobs", UUID.class, "projectId", null,
                new IllegalArgumentException("Invalid UUID string: jobs"));

        ResponseEntity<ErrorResponse> resp = new GlobalExceptionHandler().handleTypeMismatch(ex);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        ErrorResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("VALIDATION");
        assertThat(body.message()).contains("projectId").contains("jobs").contains("UUID");
    }
}
