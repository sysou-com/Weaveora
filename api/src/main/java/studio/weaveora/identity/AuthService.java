package studio.weaveora.identity;

import io.jsonwebtoken.JwtException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studio.weaveora.identity.api.RegisterRequest;
import studio.weaveora.identity.api.TokenPairResponse;
import studio.weaveora.identity.domain.CreditWallet;
import studio.weaveora.identity.domain.CreditWalletRepository;
import studio.weaveora.identity.domain.Membership;
import studio.weaveora.identity.domain.MembershipRepository;
import studio.weaveora.identity.domain.User;
import studio.weaveora.identity.domain.UserRepository;
import studio.weaveora.identity.domain.Workspace;
import studio.weaveora.identity.domain.WorkspaceRepository;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import java.math.BigDecimal;
import java.util.Optional;

/** 注册/登录/刷新（参考 MirrorTalk AuthService 思路重写；用户模型按 §14，验证码走 Redis 见 CodeService）。 */
@Service
public class AuthService {

    /** 新用户赠送额度（§22.1 free 套餐 100；可被 Nacos/配置覆盖——MVP 用常量+simplified 模式）。 */
    private static final BigDecimal WELCOME_CREDITS = new BigDecimal("100");

    private final UserRepository users;
    private final WorkspaceRepository workspaces;
    private final MembershipRepository memberships;
    private final CreditWalletRepository wallets;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(UserRepository users,
                       WorkspaceRepository workspaces,
                       MembershipRepository memberships,
                       CreditWalletRepository wallets,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil) {
        this.users = users;
        this.workspaces = workspaces;
        this.memberships = memberships;
        this.wallets = wallets;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    /** 注册：同一事务创建 users + workspaces + memberships(owner) + credit_wallets（§14）。 */
    @Transactional
    public TokenPairResponse register(RegisterRequest req) {
        String email = req.email() == null ? null : req.email().trim().toLowerCase();
        String phone = req.phone() == null ? null : req.phone().trim();
        if (email != null && users.existsByEmailAndDeletedAtIsNull(email)) {
            throw new BizException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        String displayName = (req.displayName() == null || req.displayName().isBlank())
                ? (email != null ? email.split("@")[0] : "用户" + phone)
                : req.displayName().trim();

        User user = users.save(User.register(email, phone, passwordEncoder.encode(req.password()), displayName));
        Workspace ws = workspaces.save(Workspace.create(user.displayName() + " 的工作区", user.id()));
        memberships.save(Membership.create(ws.id(), user.id(), Membership.Role.OWNER));
        wallets.save(CreditWallet.create(ws.id(), WELCOME_CREDITS));

        return issue(user);
    }

    @Transactional(readOnly = true)
    public TokenPairResponse login(String account, String password) {
        // account 可为邮箱或手机号
        User user = findByAccount(account)
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_CREDENTIALS, "账号或密码错误"));
        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new BizException(ErrorCode.INVALID_CREDENTIALS, "账号或密码错误");
        }
        if (user.isDisabled()) {
            throw new BizException(ErrorCode.USER_DISABLED);
        }
        return issue(user);
    }

    @Transactional(readOnly = true)
    public TokenPairResponse refresh(String refreshToken) {
        try {
            var claims = jwtUtil.parse(refreshToken, "refresh");
            User user = users.findByIdAndDeletedAtIsNull(jwtUtil.userId(claims))
                    .orElseThrow(() -> new BizException(ErrorCode.INVALID_CREDENTIALS, "用户不存在"));
            if (user.isDisabled()) {
                throw new BizException(ErrorCode.USER_DISABLED);
            }
            return issue(user);
        } catch (JwtException e) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "refresh token 无效或过期");
        }
    }

    private TokenPairResponse issue(User user) {
        return new TokenPairResponse(
                jwtUtil.generateAccessToken(user.id()),
                jwtUtil.generateRefreshToken(user.id()));
    }

    private Optional<User> findByAccount(String account) {
        String a = account.trim().toLowerCase();
        return users.findByEmailIgnoreCaseAndDeletedAtIsNull(a);
    }
}
