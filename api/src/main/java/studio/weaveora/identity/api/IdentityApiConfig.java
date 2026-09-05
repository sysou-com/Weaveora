package studio.weaveora.identity.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import studio.weaveora.identity.domain.MembershipRepository;

/** identity 模块对外暴露的 api Bean（供其它模块经 *.api 依赖）。 */
@Configuration
public class IdentityApiConfig {

    @Bean
    public WorkspaceGuard workspaceGuard(MembershipRepository memberships) {
        return new WorkspaceGuard(memberships);
    }
}
