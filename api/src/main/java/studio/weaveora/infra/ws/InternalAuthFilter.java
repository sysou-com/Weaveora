package studio.weaveora.infra.ws;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import studio.weaveora.identity.JwtAuthFilter;
import studio.weaveora.identity.JwtUtil;
import studio.weaveora.shared.api.ErrorResponse;

import java.io.IOException;
import java.util.UUID;

/**
 * 机器通道鉴权（worker 内部 + WS 握手 token）：
 * - /internal/** 要求 X-Worker-Token == weaveora.worker.token（§19 内部/回执通道；对外 worker 通道经 nginx 不暴露）
 * - /api/v1/ws 要求 ?token=JWT(access)，并把 userId/projectId 放到 request attribute 供握手拦截器使用
 */
@Component
public class InternalAuthFilter extends OncePerRequestFilter {

    private static final String TOKEN_HEADER = "X-Worker-Token";
    public static final String WS_ATTR_PROJECT = JobWsHandler.ATTR_PROJECT;
    public static final String WS_ATTR_USER = JobWsHandler.ATTR_USER;

    private final String workerToken;
    private final JwtUtil jwtUtil;

    public InternalAuthFilter(@Value("${weaveora.worker.token:dev-worker-token}") String workerToken,
                              JwtUtil jwtUtil) {
        this.workerToken = workerToken;
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !(uri.startsWith("/internal/") || uri.startsWith("/api/v1/ws"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri.startsWith("/internal/")) {
            String token = request.getHeader(TOKEN_HEADER);
            if (token == null || !constantTimeEquals(workerToken, token)) {
                reject(response, "worker token 无效");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }
        // WS 握手鉴权
        String token = firstQuery(request, "token");
        String projectParam = firstQuery(request, "projectId");
        try {
            Claims claims = jwtUtil.parse(token, "access");
            UUID uid = jwtUtil.userId(claims);
            UUID projectId = projectParam == null ? null : UUID.fromString(projectParam);
            request.setAttribute(JobWsHandler.ATTR_USER, uid);
            request.setAttribute(JobWsHandler.ATTR_PROJECT, projectId);
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            reject(response, "token 无效或缺少 projectId");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String firstQuery(HttpServletRequest request, String name) {
        String q = request.getQueryString();
        if (q == null) return null;
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(i + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(ErrorResponse.of(studio.weaveora.shared.api.ErrorCode.UNAUTHENTICATED,
                        message, "")));
    }
}
