package com.biggerink.watch.security;

import com.biggerink.watch.service.RedisOps;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 解析 Authorization: Bearer <token>，写入 SecurityContext。
 * 兼容 SSE EventSource 的 ?token= 查询参数；refresh token 不可访问业务接口。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final RedisOps redisOps;

    public JwtAuthFilter(JwtUtil jwtUtil, RedisOps redisOps) {
        this.jwtUtil = jwtUtil;
        this.redisOps = redisOps;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && !token.isBlank()
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = jwtUtil.parse(token);
                if (!jwtUtil.isRefresh(claims) && !redisOps.isRevoked(JwtUtil.fingerprint(token))) {
                    LoginUser user = jwtUtil.toLoginUser(claims);
                    if (user.getUserId() != null) {
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(user, null,
                                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                } else {
                    request.setAttribute("jwt_error", "invalid");
                }
            } catch (ExpiredJwtException e) {
                request.setAttribute("jwt_error", "expired");
                SecurityContextHolder.clearContext();
            } catch (JwtException | IllegalArgumentException e) {
                request.setAttribute("jwt_error", "invalid");
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        return request.getParameter("token");
    }
}
