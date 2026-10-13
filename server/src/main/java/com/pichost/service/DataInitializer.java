package com.pichost.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pichost.config.AppProperties;
import com.pichost.entity.UserEntity;
import com.pichost.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动时创建初始管理员账号。
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
        admin.setRole("ADMIN");
        admin.setStatus(1);
        userMapper.insert(admin);
        log.warn("已创建初始管理员账号: {} / {} ，请登录后立即修改密码", config.getUsername(), config.getPassword());
    }
}
