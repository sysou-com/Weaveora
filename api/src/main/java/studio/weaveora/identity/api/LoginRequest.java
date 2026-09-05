package studio.weaveora.identity.api;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String account,   // 邮箱或手机号
        @NotBlank String password
) {
}
