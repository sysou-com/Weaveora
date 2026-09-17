package studio.weaveora.script.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 剧本（「我的剧本」模块，V17 scripts 表）。
 *
 * <p>8 个创作要素：标题 / 类型 / 人物及人物介绍 / 故事 / 冲突 / 情节结构 / 语言 / 舞台说明；
 * 另有 {@code condensedStory}——由 AI 持续维护的「精简的故事」，是后续每一集生成的唯一连续记忆。
 *
 * <p>呼应 CLAUDE.md 铁律：AI 只产出「值」，要素与集内容由用户确认后落库；
 * 本实体不提供任何「AI 直接覆盖用户数据」的入口。
 */
@Entity
@Table(name = "scripts",
        indexes = {
                @Index(name = "idx_scripts_ws", columnList = "workspace_id, updated_at DESC"),
                @Index(name = "idx_scripts_share", columnList = "share_status, updated_at DESC"),
        })
public class Script {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String genre = "";

    @Column(nullable = false)
    private String characters = "";

    @Column(nullable = false)
    private String story = "";

    @Column(nullable = false)
    private String conflict = "";

    @Column(name = "plot_structure", nullable = false)
    private String plotStructure = "";

    @Column(nullable = false)
    private String language = "";

    @Column(name = "stage_directions", nullable = false)
    private String stageDirections = "";

    @Column(name = "condensed_story", nullable = false)
    private String condensedStory = "";

    /** 各要素的分段写作提纲：{@code {"characters":["1. …"], "story":[…]}}（V18）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode outlines = JsonNodeFactory.instance.objectNode();

    @Column(nullable = false)
    private String status = "draft";        // draft | writing | completed

    /** 剧本集市：null=未分享 | pending=待审 | approved=已上架 | rejected=驳回 */
    @Column(name = "share_status")
    private String shareStatus;

    @Column(name = "shared_at")
    private OffsetDateTime sharedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected Script() {
    }

    public static Script create(UUID workspaceId, UUID createdBy, String title, String genre,
                                String characters, String story, String conflict,
                                String plotStructure, String language, String stageDirections) {
        Script s = new Script();
        s.workspaceId = workspaceId;
        s.createdBy = createdBy;
        s.title = title;
        s.genre = orEmpty(genre);
        s.characters = orEmpty(characters);
        s.story = orEmpty(story);
        s.conflict = orEmpty(conflict);
        s.plotStructure = orEmpty(plotStructure);
        s.language = orEmpty(language);
        s.stageDirections = orEmpty(stageDirections);
        s.status = "draft";
        return s;
    }

    private static String orEmpty(String v) {
        return v == null ? "" : v;
    }

    /** 局部更新：null = 不改（空串 = 显式清空）。 */
    public void patch(String title, String genre, String characters, String story, String conflict,
                      String plotStructure, String language, String stageDirections) {
        if (title != null) this.title = title;
        if (genre != null) this.genre = genre;
        if (characters != null) this.characters = characters;
        if (story != null) this.story = story;
        if (conflict != null) this.conflict = conflict;
        if (plotStructure != null) this.plotStructure = plotStructure;
        if (language != null) this.language = language;
        if (stageDirections != null) this.stageDirections = stageDirections;
        this.updatedAt = OffsetDateTime.now();
    }

    /** AI 维护的「精简的故事」（保留空值不覆盖：空串表示显式清空）。 */
    public void setCondensedStory(String condensedStory) {
        this.condensedStory = orEmpty(condensedStory);
        this.updatedAt = OffsetDateTime.now();
    }

    /** 写入某要素的分段提纲（V18：供「AI 更新」复用同一份提纲）。 */
    public void putOutline(String fieldKey, JsonNode segments) {
        if (fieldKey == null || fieldKey.isBlank()) return;
        if (segments == null || !segments.isArray() || segments.isEmpty()) return;
        com.fasterxml.jackson.databind.node.ObjectNode obj = this.outlines instanceof
                com.fasterxml.jackson.databind.node.ObjectNode o
                ? (com.fasterxml.jackson.databind.node.ObjectNode) o.deepCopy()
                : JsonNodeFactory.instance.objectNode();
        obj.set(fieldKey, segments);
        this.outlines = obj;
        this.updatedAt = OffsetDateTime.now();
    }

    /** 某要素已存的提纲（无则空数组）。 */
    public JsonNode outlineOf(String fieldKey) {
        if (fieldKey == null || outlines == null) return JsonNodeFactory.instance.arrayNode();
        JsonNode n = outlines.path(fieldKey);
        return n.isArray() ? n : JsonNodeFactory.instance.arrayNode();
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }

    public void submitShare() {
        this.shareStatus = "pending";
        this.sharedAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    public void reviewShare(boolean approved) {
        this.shareStatus = approved ? "approved" : "rejected";
        this.updatedAt = OffsetDateTime.now();
    }

    public void markDeleted() {
        this.deletedAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    public boolean deleted() {
        return this.deletedAt != null;
    }

    public UUID id() { return id; }
    public UUID workspaceId() { return workspaceId; }
    public UUID createdBy() { return createdBy; }
    public String title() { return title; }
    public String genre() { return genre; }
    public String characters() { return characters; }
    public String story() { return story; }
    public String conflict() { return conflict; }
    public String plotStructure() { return plotStructure; }
    public String language() { return language; }
    public String stageDirections() { return stageDirections; }
    public String condensedStory() { return condensedStory; }
    public JsonNode outlines() { return outlines; }
    public String status() { return status; }
    public String shareStatus() { return shareStatus; }
    public OffsetDateTime sharedAt() { return sharedAt; }
    public OffsetDateTime createdAt() { return createdAt; }
    public OffsetDateTime updatedAt() { return updatedAt; }
    public OffsetDateTime deletedAt() { return deletedAt; }
}
