package studio.weaveora.infra;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移版本号唯一性守卫。
 *
 * <p>为什么要有这个测试（2026-09-18 **生产事故**）：新加了一个
 * {@code V10__image_max_resolution.sql}，而仓库里已有 {@code V10__shot_locks.sql}
 * —— 部署时 Maven 测试**全绿**（没人检查版本号），Flyway 在启动时校验失败
 * （applied migration 10 与本地文件不一致）→ **API 反复重启、服务不可用**，
 * 只能回滚/重建修复版。
 *
 * <p>这类错误无法靠编译发现，但用 20 行测试就能拦住：同版本号出现两次 = 直接失败。
 */
class MigrationVersionUniquenessTest {

    @Test
    void 迁移版本号必须唯一() throws Exception {
        java.net.URL url = getClass().getResource("/db/migration");
        assertThat(url).as("找不到 classpath:/db/migration（打包或测试路径变了？）").isNotNull();

        File dir = new File(url.toURI());
        Map<String, List<String>> byVersion = new LinkedHashMap<>();
        for (File f : Objects.requireNonNull(dir.listFiles(), "migration 目录不可读")) {
            String name = f.getName();
            int sep = name.indexOf("__");
            if (!name.startsWith("V") || !name.endsWith(".sql") || sep <= 1) {
                continue;   // 只认标准命名 V<version>__<desc>.sql（如 flyway 的 repeatable 以 R 开头，跳过）
            }
            String version = name.substring(1, sep);
            byVersion.computeIfAbsent(version, k -> new ArrayList<>()).add(name);
        }

        assertThat(byVersion).as("迁移目录为空 → 打包配置可能出了问题").isNotEmpty();

        List<String> duplicated = byVersion.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "V" + e.getKey() + " 重复：" + String.join(" / ", e.getValue()))
                .toList();

        assertThat(duplicated)
                .as("迁移版本号重复会让 Flyway 启动校验失败 → API 起不来（2026-09-18 事故）。"
                        + "修法：把新迁移改成一个未被占用的版本号（如 V19）。")
                .isEmpty();
    }
}
