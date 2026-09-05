package studio.weaveora.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, Membership.MembershipId> {

    List<Membership> findByMembershipIdUserId(UUID userId);

    List<Membership> findByMembershipIdWorkspaceId(UUID workspaceId);

    Optional<Membership> findByMembershipIdWorkspaceIdAndMembershipIdUserId(UUID workspaceId, UUID userId);

    boolean existsByMembershipIdWorkspaceIdAndMembershipIdUserId(UUID workspaceId, UUID userId);
}
