package studio.weaveora.engine.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserEngineSettingsRepository extends JpaRepository<UserEngineSettings, UUID> {

    Optional<UserEngineSettings> findByUserId(UUID userId);
}
