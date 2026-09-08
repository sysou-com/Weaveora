package studio.weaveora.project.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import studio.weaveora.project.domain.StyleTemplate;
import studio.weaveora.project.domain.StyleTemplateRepository;

import java.util.List;
import java.util.UUID;

/** 风格模板目录（新建项目/详情可选；出图/出视频时注入 prompt）。 */
@RestController
@RequestMapping("/api/v1/style-templates")
public class StyleTemplateController {

    private final StyleTemplateRepository repo;

    public StyleTemplateController(StyleTemplateRepository repo) {
        this.repo = repo;
    }

    public record StyleDto(UUID id, String slug, String name) {
    }

    @GetMapping
    public List<StyleDto> list() {
        return repo.findByIsSystemTrueOrderByNameAsc().stream()
                .map(s -> new StyleDto(s.id(), s.slug(), s.name()))
                .toList();
    }
}
