package studio.weaveora.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        String email,
        String phone,
        @NotBlank @Size(min = 8, max = 72) String password,
        @Size(max = 50) String displayName
) {
}
