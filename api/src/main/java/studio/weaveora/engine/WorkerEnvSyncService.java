package studio.weaveora.engine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import studio.weaveora.engine.api.GpuAddressSyncResponse;
import studio.weaveora.job.domain.GenerationJobRepository;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * worker 机器的 systemd env 同步（默认 {@code /etc/weaveora/weaveora-gpu-worker.env}）。
 *
 * <p>为什么需要（2026-09-21 用户点名）：页面上的「GPU 服务器地址 / 服务地址」写进的是 **DB**，
 * 随任务下发给 worker，**那才是生效处**；而 worker 启动时读的 env 是**回退值**
 * （`comfy_client` 的注释：空值/未配置一律不覆盖，保持环境变量默认）。于是同一个地址有了两个真源：
 * 页面清空某字段 → env 静默接管 → 又是一模一样的 {@code Connection refused}，且日志里看不出用的哪一份。
 *
 * <p>因此本服务把 env 也纳入「一键同步」，并遵守三条纪律：
 * <ol>
 *   <li><b>只碰白名单键</b>（{@link #KEYS}）：其余行（注释、模型名、LoRA 开关、超时…）原样不动；</li>
 *   <li><b>改前必留备份</b>（同目录 {@code .bak.<ts>}，保留属性）；写临时文件 + 原子 rename，不会留半截文件；</li>
 *   <li><b>有 queued/running 任务时拒绝</b>：env 是进程启动时读的常量，改完必须重启 worker 才生效，
 *       而重启会打断在跑的任务（与 {@code deploy/api-deploy.sh} 同一口径的护栏）。</li>
 * </ol>
 *
 * <p>可用性：env 文件不存在（开发机 / Windows）时 {@link #available()} 为 false，
 * 端点只返回「不可用」说明，不报错 —— 页面照常能改 DB。
 */
@Service
public class WorkerEnvSyncService {

    /** 白名单：只有这些键会被改写。它们都表示「GPU 网关地址」（值形如 {@code http://host:port} 或带路径）。 */
    private static final List<String> KEYS = List.of(
            "WEAVEORA_COMFY_URL",
            "WEAVEORA_IMAGE_COMFY_URL",
            "WEAVEORA_TTS_URL",
            "WEAVEORA_MUSIC_URL",
            "WEAVEORA_FACE_URL");

    /** URL 前缀：`scheme://host[:port]`；后面的路径（`/audio`、`/bgm`、`/talk`）必须原样保留。 */
    private static final Pattern URL_PREFIX = Pattern.compile(
            "^(?<scheme>[a-zA-Z][a-zA-Z0-9+.\\-]*)://(?<host>[^/:\\s]+)(?::(?<port>\\d{1,5}))?");

    private static final int RESTART_TIMEOUT_SEC = 60;

    private final Path file;
    private final String service;
    private final GenerationJobRepository jobs;

    public WorkerEnvSyncService(
            @Value("${weaveora.worker-env-file:/etc/weaveora/weaveora-gpu-worker.env}") String envFile,
            @Value("${weaveora.worker-service:weaveora-gpu-worker}") String serviceName,
            GenerationJobRepository jobs) {
        String f = (envFile == null || envFile.isBlank())
                ? "/etc/weaveora/weaveora-gpu-worker.env" : envFile.trim();
        this.file = Path.of(f);
        this.service = (serviceName == null || serviceName.isBlank()) ? "weaveora-gpu-worker" : serviceName.trim();
        this.jobs = jobs;
    }

    public boolean available() {
        return Files.isRegularFile(file) && Files.isWritable(file);
    }

    public String filePath() {
        return file.toString();
    }

    public String serviceName() {
        return service;
    }

    /** 当前 env 里白名单键的值（页面用它显示「worker 回退值」，一眼看出与页面是否一致）。 */
    public Map<String, String> currentValues() {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String k = keyOf(line);
                if (k != null && KEYS.contains(k)) {
                    out.put(k, valueOf(line));
                }
            }
        } catch (IOException e) {
            // 读不到就当作没有：状态接口不该把页面打挂
        }
        return out;
    }

    /** systemd 服务状态（active / inactive / failed / unknown）。 */
    public String serviceState() {
        return runCapture(List.of("systemctl", "is-active", service));
    }

    /** 有 queued/running 任务时不允许重启 worker（与 api-deploy.sh 同一护栏）。 */
    public long busyJobCount() {
        try {
            return jobs.countByState("queued") + jobs.countByState("running");
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /**
     * 同步 env（备份 → 原子写）。返回实际改了什么；**不负责重启**。
     *
     * @param newBase 新网关基址，如 {@code http://180.127.11.169:27458}（不带尾部路径）
     */
    public ApplyResult apply(String newBase) {
        if (!available()) {
            throw new BizException(ErrorCode.VALIDATION, "worker env 不可用（文件不存在或不可写）：" + file);
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(ErrorCode.WORKER_UNAVAILABLE, "读取 worker env 失败：" + e.getMessage());
        }
        Rewrite r = rewrite(lines, newBase);
        if (r.changes().isEmpty()) {
            return new ApplyResult(List.of(), null);
        }
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path backup = file.resolveSibling(file.getFileName() + ".bak." + ts);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp." + ts);
        try {
            Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
            String body = String.join("\n", r.lines());
            if (!body.endsWith("\n")) {
                body = body + "\n";
            }
            Files.write(tmp, body.getBytes(StandardCharsets.UTF_8));
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (RuntimeException | IOException ignore) {
                // 非 POSIX 文件系统（开发机）忽略：生产是 Linux，权限会被显式设成 600
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new BizException(ErrorCode.WORKER_UNAVAILABLE, "写 worker env 失败：" + e.getMessage());
        }
        return new ApplyResult(r.changes(), backup.toString());
    }

    /** 重启 worker（systemctl restart）；返回是否成功发出并退出 0。 */
    public boolean restart() {
        try {
            Process p = new ProcessBuilder("systemctl", "restart", service)
                    .redirectErrorStream(true).start();
            if (!p.waitFor(RESTART_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 纯函数：按白名单改写行（路径保留），返回新行 + 变更清单。 */
    static Rewrite rewrite(List<String> lines, String newBase) {
        List<String> out = new ArrayList<>(lines.size());
        List<GpuAddressSyncResponse.Change> changes = new ArrayList<>();
        for (String line : lines) {
            String key = keyOf(line);
            if (key == null || !KEYS.contains(key)) {
                out.add(line);
                continue;
            }
            int eq = line.indexOf('=');
            String indent = line.substring(0, line.indexOf(key));
            String rawValue = line.substring(eq + 1).trim();
            String trailing = "";
            int hash = rawValue.indexOf(" #");
            if (hash > 0) {
                trailing = rawValue.substring(hash);
                rawValue = rawValue.substring(0, hash).trim();
            }
            Matcher m = URL_PREFIX.matcher(rawValue);
            if (!m.find()) {
                out.add(line);
                continue;
            }
            String after = newBase + rawValue.substring(m.end());
            if (after.equals(rawValue)) {
                out.add(line);
                continue;
            }
            changes.add(new GpuAddressSyncResponse.Change(key, rawValue, after));
            out.add(indent + key + "=" + after + trailing);
        }
        return new Rewrite(out, changes);
    }

    /** 取 `KEY=...` 里的 KEY；注释行与非法行返回 null。 */
    static String keyOf(String line) {
        if (line == null) {
            return null;
        }
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("#")) {
            return null;
        }
        int eq = t.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        return t.substring(0, eq).trim();
    }

    static String valueOf(String line) {
        String t = line.trim();
        int eq = t.indexOf('=');
        return eq < 0 ? "" : t.substring(eq + 1).trim();
    }

    private String runCapture(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "unknown";
            }
            String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? "unknown" : s;
        } catch (IOException e) {
            return "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    /** 改写结果（新行 + 变更清单）。 */
    record Rewrite(List<String> lines, List<GpuAddressSyncResponse.Change> changes) {
    }

    /** 落盘结果（变更清单 + 备份路径；无改动时 backupPath 为 null）。 */
    public record ApplyResult(List<GpuAddressSyncResponse.Change> changes, String backupPath) {
        public ApplyResult {
            changes = changes == null ? Collections.emptyList() : changes;
        }
    }
}
