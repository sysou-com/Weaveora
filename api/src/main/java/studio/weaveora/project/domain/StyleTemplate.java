package studio.weaveora.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 风格模板：以 prompt_prefix/suffix/negative 注入出图/出视频的正反向词（is_system=系统内置）。 */
@Entity
@Table(name = "style_templates")
public class StyleTemplate {

    @Id
    private UUID id;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(nullable = false)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column(name = "prompt_prefix", nullable = false)
    private String promptPrefix = "";

    @Column(name = "prompt_suffix", nullable = false)
    private String promptSuffix = "";

    @Column(nullable = false)
    private String negative = "";

    @Column(name = "is_system", nullable = false)
    private boolean isSystem = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public UUID id() {
        return id;
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public String slug() {
        return slug;
    }

    public String name() {
        return name;
    }

    public String promptPrefix() {
        return promptPrefix;
    }

    public String promptSuffix() {
        return promptSuffix;
    }

    public String negative() {
        return negative;
    }
}
