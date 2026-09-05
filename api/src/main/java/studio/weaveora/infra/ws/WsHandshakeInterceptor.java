package studio.weaveora.infra.ws;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

/** 把鉴权后的请求上下文写入 WS session attributes（auth 由 InternalAuthFilter 完成）。 */
@Component
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servlet) {
            Object pid = servlet.getServletRequest().getAttribute(JobWsHandler.ATTR_PROJECT);
            Object uid = servlet.getServletRequest().getAttribute(JobWsHandler.ATTR_USER);
            if (pid instanceof UUID projectId) {
                attributes.put(JobWsHandler.ATTR_PROJECT, projectId);
            }
            if (uid instanceof UUID userId) {
                attributes.put(JobWsHandler.ATTR_USER, userId);
            }
            return pid instanceof UUID;
        }
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
