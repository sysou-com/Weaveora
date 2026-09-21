package studio.weaveora.engine.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * 一键同步 GPU 地址：把「所有指向旧 GPU 机器的 IP:端口」换成新值。
     *
     * <p>为什么要有这个端点：换 GPU 实例后 IP/端口都会变，而 services 里**显式填过**的 URL
     * 不会跟随「GPU 服务器地址」字段（只改端口字段时出图链路仍打旧地址 → Connection refused）。
     * 返回替换清单 + 改漏清单，让用户不用自己逐个核对。
     */
    @org.springframework.web.bind.annotation.PostMapping("/sync-address")
    public GpuAddressSyncResponse syncAddress(HttpServletRequest request,
                                              @RequestBody GpuAddressSyncRequest body) {
        return service.syncGpuAddress(uid(request), body);
    }

    /**
     * worker 机器 env 的当前回退值（页面显示「worker 回退地址 = …」，对比与页面是否一致）。
     *
     * <p>为什么要暴露它：DB 是随任务下发的**生效值**，env 是 worker 未收到配置时的**回退值** ——
     * 两个真源不一致时，页面看着改了、实际某条链路仍在打旧地址（2026-09-21 事故的隐患）。
     */
    @GetMapping("/worker-env")
    public WorkerEnvStatusResponse workerEnv(HttpServletRequest request) {
        uid(request);   // 仅鉴权：这东西是全局的，不需要按用户区分
        return service.workerEnvStatus();
    }

    /** P12：主动刷新模型调用参数说明（配/换模型后拉取；也可手动点「刷新参数说明」）。 */
    @org.springframework.web.bind.annotation.PostMapping("/refresh-models")
    public EngineSettingsResponse refresh(HttpServletRequest request,
                                          @org.springframework.web.bind.annotation.RequestParam(value = "kind", required = false)
                                          String kind) {
        boolean image = kind == null || kind.isBlank() || "image".equals(kind);
        boolean video = kind == null || kind.isBlank() || "video".equals(kind);
        return service.refreshSchemas(uid(request), image, video);
    }

    /** P12 模型库：新增/更新条目（顺手刷新该模型的参数说明；apply=true 同时设为当前生效模型）。 */
    @org.springframework.web.bind.annotation.PostMapping("/models")
    public EngineSettingsResponse upsertModel(HttpServletRequest request,
                                              @Valid @RequestBody ModelPresetRequest body) {
        return service.upsertPreset(uid(request), body);
    }

    /** P12 模型库：刷新某条目的参数说明。 */
    @org.springframework.web.bind.annotation.PostMapping("/models/refresh")
    public EngineSettingsResponse refreshModel(HttpServletRequest request,
                                               @RequestParam("kind") String kind,
                                               @RequestParam(value = "baseUrl", required = false) String baseUrl,
                                               @RequestParam("model") String model) {
        return service.refreshPreset(uid(request), kind, baseUrl, model);
    }

    /** P12 模型库：删除条目。 */
    @org.springframework.web.bind.annotation.PostMapping("/models/delete")
    public EngineSettingsResponse deleteModel(HttpServletRequest request,
                                              @RequestParam("kind") String kind,
                                              @RequestParam(value = "baseUrl", required = false) String baseUrl,
                                              @RequestParam("model") String model) {
        return service.deletePreset(uid(request), kind, baseUrl, model);
    }

    private UUID uid(HttpServletRequest request) {
        String uid = (String) request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        return UUID.fromString(uid);
    }
}
