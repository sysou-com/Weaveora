package studio.weaveora.infra.storage;

import java.io.InputStream;
import java.time.Duration;

/**
 * 对象存储抽象（§21.1）。dev=本地目录；生产=OSS（后续适配）。key 形如 {workspace}/{project}/{job}/… 。
 * 实现须防止路径穿越；生成/读取都只经本接口，禁止业务把文件落本地当生产存储。
 */
public interface StoragePort {

    /** 写入对象；key 由调用方给出（可含 uuid 段），返回规范化后的 key。 */
    String put(String key, InputStream data, long size, String contentType);

    /** 读取对象字节流（不存在 → 抛 BizException NOT_FOUND 语义由调用方处理）。 */
    StoredObject get(String key);

    void delete(String key);

    /** 对外（下载）有效期；MVP 本地适配器忽略，供 OSS 签名 URL 使用。 */
    default Duration presignTtl() {
        return Duration.ofMinutes(30);
    }

    record StoredObject(String key, InputStream stream, long size, String contentType) {
    }
}
