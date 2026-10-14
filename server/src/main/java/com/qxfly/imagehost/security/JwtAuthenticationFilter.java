package com.qxfly.imagehost.security;

import com.qxfly.imagehost.entity.UserEntity;
import com.qxfly.imagehost.mapper.UserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 解析 Authorization: Bearer &lt;token&gt;，写入 SecurityContext。
 * <p>
 * token 里虽然带了角色，但这里<b>以数据库为准</b>重新取一次账号：
 * <ul>
 *   <li>超级管理员只能改库赋予，所以改完要立刻生效，不能等 token 过期</li>
 *   <li>账号被禁用 / 删除后，旧 token 也必须马上失效</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserMapper userMapper;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (StringUtils.hasText(header) && header.startsWith(PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(PREFIX.length()).trim();
            AuthUser claims = jwtService.parse(token);
            if (claims != null) {
                authenticate(request, claims);
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, AuthUser claims) {
        UserEntity entity;
        try {
            entity = userMapper.selectById(claims.id());
        } catch (Exception e) {
            // 查库失败就当作未认证（宁可让用户重新登录，也不要拿着过期信息放行）
            log.warn("校验登录状态时查询用户失败（id={}）: {}", claims.id(), e.getMessage());
            return;
        }
        if (entity == null) {
            log.debug("token 里的用户已不存在，忽略该登录态: {}", claims.id());
            return;
        }
        if (entity.getStatus() == null || entity.getStatus() != 1) {
            log.debug("账号已被禁用，忽略该登录态: {}", entity.getUsername());
            return;
        }

        Role role = Role.of(entity.getRole());
        AuthUser user = new AuthUser(entity.getId(), entity.getUsername(), role.name());
        var authentication = new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority(role.authority())));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
