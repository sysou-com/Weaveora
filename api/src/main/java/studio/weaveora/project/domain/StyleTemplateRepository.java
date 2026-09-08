package studio.weaveora.project.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StyleTemplateRepository extends JpaRepository<StyleTemplate, UUID> {

    List<StyleTemplate> findByIsSystemTrueOrderByNameAsc();
}
