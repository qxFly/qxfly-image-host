package com.qxfly.imagehost.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qxfly.imagehost.config.AppProperties;
import com.qxfly.imagehost.entity.UserEntity;
import com.qxfly.imagehost.mapper.UserMapper;
import com.qxfly.imagehost.security.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动时创建初始账号（超级管理员）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    @Override
    public void run(ApplicationArguments args) {
        normalizeLegacyRoles();

        AppProperties.InitAdmin config = appProperties.getInitAdmin();
        if (!config.isEnabled()) {
            return;
        }
        Long exists = userMapper.selectCount(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, config.getUsername()));
        if (exists != null && exists > 0) {
            log.info("管理员账号已存在，跳过初始化: {}", config.getUsername());
            return;
        }
        UserEntity admin = new UserEntity();
        admin.setUsername(config.getUsername());
        admin.setPassword(passwordEncoder.encode(config.getPassword()));
        admin.setNickname(config.getNickname());
        admin.setRole(Role.SUPER_ADMIN.name());
        admin.setStatus(1);
        userMapper.insert(admin);
        log.warn("已创建初始超级管理员账号: {} / {} ，请登录后立即修改密码", config.getUsername(), config.getPassword());
    }

    /**
     * 旧版本把超级管理员存成 {@code ADMIN}，这里统一成 {@code SUPER_ADMIN}（幂等）。
     * 代码里也仍然认 ADMIN，所以即使这一步失败也不会把管理员挡在外面。
     */
    private void normalizeLegacyRoles() {
        try {
            int updated = userMapper.update(null, Wrappers.<UserEntity>lambdaUpdate()
                    .set(UserEntity::getRole, Role.SUPER_ADMIN.name())
                    .eq(UserEntity::getRole, "ADMIN"));
            if (updated > 0) {
                log.info("已把 {} 个历史 ADMIN 账号归一化为 SUPER_ADMIN", updated);
            }
        } catch (Exception e) {
            log.warn("历史角色归一化失败（不影响启动）: {}", e.getMessage());
        }
    }
}
