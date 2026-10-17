package com.qxfly.imagehost.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qxfly.imagehost.common.ApiResult;
import com.qxfly.imagehost.security.JwtAuthenticationFilter;
import com.qxfly.imagehost.security.Role;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * 安全配置：无状态 JWT 鉴权 + 两种角色的接口权限。
 * <p>
 * 权限划分（角色定义见 {@link Role}）：
 * <ul>
 *   <li>匿名可访问：登录、健康检查、自助注册开关查询</li>
 *   <li>所有登录用户：图库浏览 / 查看 / 下载、存储后端只读列表、个人资料与改密</li>
 *   <li>只有超级管理员：上传 / 删除 / 迁移 / 导入、存储配置增删改、账号管理</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * 只有超级管理员能访问的接口：会改数据、能看到存储密钥、或者管理账号。
     * 图库的浏览 / 查看 / 下载接口刻意不在这里，普通用户也能用。
     */
    private static final String[] SUPER_ADMIN_ONLY = {
            "/api/users/**",
            "/api/storage-configs/**",
            // 系统设置（上传大小上限等）
            "/api/settings",
            "/api/settings/**",
            // 审计日志
            "/api/audit-logs",
            "/api/audit-logs/**",
            // 元数据清单导出 / 回灌
            "/api/images/export",
            "/api/images/export/**",
            "/api/images/import-manifest",
            "/api/images/import-manifest/**",
            // 重复图片查找与清理
            "/api/images/duplicates",
            "/api/images/duplicates/**",
            // 分享链接的创建 / 列表 / 撤销（公开的 view 已在上面单独放行）
            "/api/shares",
            "/api/shares/**",
            "/api/auth/register",
            "/api/images/upload",
            "/api/images/upload/**",
            "/api/images/import",
            "/api/images/import/**",
            "/api/images/migrate",
            "/api/images/migrate/**",
            "/api/images/repo-move/**",
            "/api/images/repo-dedupe/**",
            // 图库批量删除 = 批量移入回收站（单张删除走下面的 DELETE 规则）
            "/api/images/delete/batch",
            // 回收站：能看到自己删过什么、也能彻底删掉云端文件，只给超级管理员
            "/api/images/trash",
            "/api/images/trash/**",
    };

    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        boolean selfRegister = appProperties.getRegister().isEnabled();
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> {
                    registry.requestMatchers("/api/auth/login", "/api/auth/register-enabled").permitAll();
                    registry.requestMatchers("/actuator/health", "/actuator/info", "/error", "/favicon.ico").permitAll();
                    registry.requestMatchers("/h2-console/**").permitAll();
                    registry.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();

                    if (selfRegister) {
                        // 只有显式打开 app.register.enabled 才允许自助注册，
                        // 且注册出来的账号一律是普通用户
                        registry.requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll();
                    }

                    // 分享相册的公开访问：拿到 token 的人不用登录就能看（需要密码的在这里校验密码）
                    registry.requestMatchers(HttpMethod.POST, "/api/shares/*/view").permitAll();
                    registry.requestMatchers(HttpMethod.GET, "/api/shares/*/download").permitAll();

                    // 图库的「存储后端」筛选要读配置列表（密钥已打码），所有登录用户都能看
                    registry.requestMatchers(HttpMethod.GET, "/api/storage-configs").authenticated();

                    // 删除图片、以及所有管理类接口，只有超级管理员能用
                    registry.requestMatchers(HttpMethod.DELETE, "/api/images/**")
                            .hasRole(Role.SUPER_ADMIN.name());
                    registry.requestMatchers(SUPER_ADMIN_ONLY).hasRole(Role.SUPER_ADMIN.name());

                    // 其余接口登录即可（浏览 / 查看 / 下载）
                    registry.requestMatchers("/api/**").authenticated();
                    // 单进程部署时前端静态资源由本服务提供
                    registry.anyRequest().permitAll();
                })
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                write(response, 401, "未登录或登录状态已失效"))
                        .accessDeniedHandler((request, response, ex) ->
                                write(response, 403, "该操作仅超级管理员可用")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void write(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResult.fail(code, message)));
    }
}
