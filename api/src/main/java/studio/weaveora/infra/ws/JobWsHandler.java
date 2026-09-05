package studio.weaveora.infra.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Job 进度推送（§17.6）。MVP 单实例：session 按 projectId 登记，JobEventPublisher 直推；
 * 多实例扩展为 Redis Pub/Sub 扇出（当前单机部署足够，后续拆实例时再加）。
 */
@Component
public class JobWsHandler extends TextWebSocketHandler {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> byProject = new ConcurrentHashMap<>();

    public static final String ATTR_PROJECT = "weaveora.ws.projectId";
    public static final String ATTR_USER = "weaveora.ws.userId";

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Object pid = session.getAttributes().get(ATTR_PROJECT);
        if (pid instanceof UUID projectId) {
            byProject.computeIfAbsent(projectId, k -> ConcurrentHashMap.newKeySet()).add(session);
            session.sendMessage(new TextMessage("{\"type\":\"hello\",\"projectId\":\"" + projectId + "\"}"));
        } else {
            session.close(CloseStatus.BAD_DATA);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object pid = session.getAttributes().get(ATTR_PROJECT);
        if (pid instanceof UUID projectId) {
            Set<WebSocketSession> set = byProject.get(projectId);
            if (set != null) {
                set.remove(session);
                if (set.isEmpty()) byProject.remove(projectId);
            }
        }
    }

    /** 向某项目所有已连接会话推送事件 JSON。 */
    public void push(UUID projectId, Object event) {
        Set<WebSocketSession> set = byProject.get(projectId);
        if (set == null || set.isEmpty()) return;
        try {
            String text = mapper.writeValueAsString(event);
            for (WebSocketSession s : set) {
                if (s.isOpen()) {
                    s.sendMessage(new TextMessage(text));
                }
            }
        } catch (Exception e) {
            // 推送失败不影响主流程
        }
    }
}
