package studio.weaveora.identity.api;

import studio.weaveora.identity.domain.Membership;
import studio.weaveora.identity.domain.MembershipRepository;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.util.UUID;

/**
 * 工作区隔离守卫（§22：禁止前端传 userId 授权；从 JWT 取 userId + header 取 workspaceId 校验 membership）。
 * 放 identity.api 供各模块（project 等）跨模块调用——符合 Modulith "只经 *.api 通信" 规则。
 */
public class WorkspaceGuard {

    private final MembershipRepository memberships;

    public WorkspaceGuard(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    public void requireMember(UUID userId, UUID workspaceId) {
        boolean ok = memberships.existsByMembershipIdWorkspaceIdAndMembershipIdUserId(workspaceId, userId);
        if (!ok) {
            throw new BizException(ErrorCode.WORKSPACE_ACCESS_DENIED, "无权访问该工作区");
        }
    }

    public Membership.Role requireRole(UUID userId, UUID workspaceId, Membership.Role minRole) {
        Membership m = memberships
                .findByMembershipIdWorkspaceIdAndMembershipIdUserId(workspaceId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.WORKSPACE_ACCESS_DENIED, "无权访问该工作区"));
        if (m.role().ordinal() > minRole.ordinal()) { // OWNER(0) < EDITOR < REVIEWER < VIEWER
            throw new BizException(ErrorCode.FORBIDDEN, "权限不足");
        }
        return m.role();
    }
}
