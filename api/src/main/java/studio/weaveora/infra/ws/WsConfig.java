package studio.weaveora.infra.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** WS 端点 /api/v1/ws（§17.6）；鉴权在 InternalAuthFilter 完成（?token= JWT access），拦截器登记会话上下文。 */
@Configuration
@EnableWebSocket
public class WsConfig implements WebSocketConfigurer {

    private final JobWsHandler jobWsHandler;
    private final WsHandshakeInterceptor interceptor;

    public WsConfig(JobWsHandler jobWsHandler, WsHandshakeInterceptor interceptor) {
        this.jobWsHandler = jobWsHandler;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(jobWsHandler, "/api/v1/ws")
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns("*");
    }
}
