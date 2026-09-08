package studio.weaveora.engine.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import studio.weaveora.engine.EngineSettingsService;
import studio.weaveora.identity.JwtAuthFilter;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.UUID;

/** 生成引擎配置（图片/视频分别 GPU or 云；凭据密文展示）。 */
@RestController
@RequestMapping("/api/v1/me/engine-settings")
public class EngineSettingsController {

    private final EngineSettingsService service;

    public EngineSettingsController(EngineSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public EngineSettingsResponse get(HttpServletRequest request) {
        return service.toResponse(uid(request));
    }

    @PutMapping
    public EngineSettingsResponse put(HttpServletRequest request,
                                      @Valid @RequestBody EngineSettingsRequest body) {
        return service.update(uid(request), body);
    }

    private UUID uid(HttpServletRequest request) {
        String uid = (String) request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        return UUID.fromString(uid);
    }
}
