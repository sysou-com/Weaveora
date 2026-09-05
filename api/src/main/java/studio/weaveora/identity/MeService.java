package studio.weaveora.identity;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.identity.api.MeResponse;
import studio.weaveora.identity.domain.Membership;
import studio.weaveora.identity.domain.MembershipRepository;
import studio.weaveora.identity.domain.User;
import studio.weaveora.identity.domain.UserRepository;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** GET /api/v1/me：当前用户 + 其工作区列表（§17.2）。 */
@Service
public class MeService {

    private final UserRepository users;
    private final MembershipRepository memberships;

    public MeService(UserRepository users, MembershipRepository memberships) {
        this.users = users;
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public MeResponse me(UUID userId) {
        User user = users.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "用户不存在"));

        List<MeResponse.WorkspaceInfo> wsList = memberships.findByMembershipIdUserId(userId).stream()
                .sorted(Comparator.comparing(Membership::workspaceId))
                .map(m -> new MeResponse.WorkspaceInfo(
                        m.workspaceId(), m.role().name()))
                .toList();

        return new MeResponse(user.id(), user.email(), user.displayName(), wsList);
    }
}
