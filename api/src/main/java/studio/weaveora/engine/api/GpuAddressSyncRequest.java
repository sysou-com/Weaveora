package studio.weaveora.engine.api;

/**
 * POST /api/v1/me/engine-settings/sync-address 请求：**一键**把「所有指向旧 GPU 机器的地址」换成新的 IP + 端口。
 *
 * <p>为什么需要它（2026-09-21 生产事故）：GPU 盒换实例后公网 IP/端口会变，
 * 但「GPU 服务器地址」字段**不会**自动改写 services 里那些**显式填过**的 URL
 * （{@code image.comfyUrl} / {@code music.url} / {@code talk.url} / {@code face.url} /
 * {@code tts.url} / {@code lipsync.comfyUrl} / {@code transcribe.url}）。只改端口字段时，
 * 出图链路仍打旧地址 → 参考图上传失败：{@code urlopen error [Errno 111] Connection refused}。
 *
 * @param host           新主机 / IP；允许 {@code http://1.2.3.4}、{@code 1.2.3.4}、{@code 1.2.3.4:27458} 三种写法
 * @param port           新端口（1–65535）；显式给出，优先于 host 里带的端口
 * @param oldHost        可选：要被替换掉的旧主机；缺省 = 当前 {@code gpuServerUrl} 里的 host
 * @param applyWorkerEnv 可选（默认 false）：**同时**改写 worker 机器上的
 *                       {@code /etc/weaveora/weaveora-gpu-worker.env} 并重启 worker。
 *                       那是 worker 的**回退值**（DB 为空时才会读），改它需要重启进程才生效，
 *                       因此只有在没有 queued/running 任务时才会执行。
 */
public record GpuAddressSyncRequest(String host, Integer port, String oldHost, Boolean applyWorkerEnv) {
}
