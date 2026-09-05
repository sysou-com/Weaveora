package studio.weaveora.infra.storage;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/** 本地目录适配器（§21.1 dev/单测；生产切 OSS）。根目录由 weaveora.storage.local-dir 指定。 */
@Component
public class LocalFileStorageAdapter implements StoragePort {

    private final Path root;

    public LocalFileStorageAdapter(@org.springframework.beans.factory.annotation.Value(
            "${weaveora.storage.local-dir:./data/storage}") String localDir) throws IOException {
        this.root = Paths.get(localDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override
    public String put(String key, InputStream data, long size, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("存储写入失败: " + key, e);
        }
        return key;
    }

    @Override
    public StoredObject get(String key) {
        Path p = resolve(key);
        if (!Files.exists(p)) {
            return null;
        }
        try {
            String type = guessContentType(p.getFileName().toString());
            return new StoredObject(key, Files.newInputStream(p), Files.size(p), type);
        } catch (IOException e) {
            throw new IllegalStateException("存储读取失败: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new IllegalStateException("存储删除失败: " + key, e);
        }
    }

    /** key 规范化并防穿越（../ 一律拒绝）。 */
    private Path resolve(String key) {
        Path rel = Paths.get(key).normalize();
        if (rel.isAbsolute() || rel.startsWith("..")) {
            throw new IllegalArgumentException("非法的存储 key: " + key);
        }
        return root.resolve(rel).normalize();
    }

    private static String guessContentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".zip")) return "application/zip";
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }
}
