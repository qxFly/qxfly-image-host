package com.pichost.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pichost.common.BizException;
import com.pichost.dto.LoginRequest;
import com.pichost.dto.LoginResponse;
import com.pichost.dto.PasswordRequest;
import com.pichost.dto.ProfileRequest;
import com.pichost.dto.RegisterRequest;
import com.pichost.dto.UserVO;
import com.pichost.entity.UserEntity;
import com.pichost.mapper.UserMapper;
import com.pichost.security.AuthUser;
import com.pichost.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户与登录。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public LoginResponse login(LoginRequest request) {
        List<UserEntity> users = userMapper.selectList(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, request.getUsername().trim()));
        UserEntity user = users.isEmpty() ? null : users.get(0);
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw BizException.unauthorized("用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw BizException.forbidden("账号已被禁用，请联系管理员");
        }
        LoginResponse response = new LoginResponse();
        response.setToken(jwtService.create(new AuthUser(user.getId(), user.getUsername(), user.getRole())));
        response.setExpiresIn(jwtService.expireSeconds());
        response.setUser(toVO(user));
        return response;
    }

    @Transactional
    public UserVO register(RegisterRequest request) {
        Long exists = userMapper.selectCount(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, request.getUsername().trim()));
        if (exists != null && exists > 0) {
            throw new BizException("用户名已被占用");
        }
        UserEntity user = new UserEntity();
        user.setUsername(request.getUsername().trim());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname() == null || request.getNickname().isBlank()
                ? request.getUsername().trim() : request.getNickname());
        user.setEmail(request.getEmail());
        user.setRole("USER");
        user.setStatus(1);
        userMapper.insert(user);
        return toVO(user);
    }

    public UserVO getById(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        return toVO(user);
    }

    public UserVO updateProfile(Long userId, ProfileRequest request) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        user.setNickname(request.getNickname());
        user.setEmail(request.getEmail());
        userMapper.updateById(user);
        return toVO(user);
    }

    public void changePassword(Long userId, PasswordRequest request) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BizException("原密码不正确");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userMapper.updateById(user);
    }

    public UserVO toVO(UserEntity user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }
}
