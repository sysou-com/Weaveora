package studio.weaveora;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Weaveora 织影 API — Spring Modulith modular monolith.
 * 模块边界见 Weaveora.md §16.2；架构真源为仓库根 Weaveora.md v2.0。
 */
@SpringBootApplication
public class WeaveoraApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeaveoraApiApplication.class, args);
    }
}
