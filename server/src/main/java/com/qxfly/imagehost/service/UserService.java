package com.qxfly.imagehost.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qxfly.imagehost.common.BizException;
import com.qxfly.imagehost.common.PageResult;
import com.qxfly.imagehost.dto.LoginRequest;
import com.qxfly.imagehost.dto.LoginResponse;
import com.qxfly.imagehost.dto.PasswordRequest;
import com.qxfly.imagehost.dto.ProfileRequest;
import com.qxfly.imagehost.dto.RegisterRequest;
import com.qxfly.imagehost.dto.UserCreateRequest;
import com.qxfly.imagehost.dto.UserUpdateRequest;
import com.qxfly.imagehost.dto.UserVO;
import com.qxfly.imagehost.entity.ImageEntity;
import com.qxfly.imagehost.entity.UserEntity;
import com.qxfly.imagehost.mapper.ImageMapper;
import com.qxfly.imagehost.mapper.UserMapper;
import com.qxfly.imagehost.security.AuthUser;
import com.qxfly.imagehost.security.JwtService;
import com.qxfly.imagehost.security.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 用户、登录与账号管理。
 * <p>
 * 账号管理接口只对超级管理员开放（在 SecurityConfig 里按路径限定）。
 * 这里再保证一件事：<b>任何接口都不会把账号提升成超级管理员</b>，也不会删改超级管理员账号 ——
 * 该角色只能直接改数据库。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    /** 账号列表一页最多多少条 */
    private static final long MAX_PAGE_SIZE = 200;

    private final UserMapper userMapper;
    private final ImageMapper imageMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final LoginAttemptService loginAttemptService;

    public LoginResponse login(LoginRequest request) {
        String username = request.getUsername().trim();
        // 被锁定就直接拒绝，连库都不用查（在线暴力破解的第一道闸）
        loginAttemptService.checkAllowed(username);

        List<UserEntity> users = userMapper.selectList(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, username));
        UserEntity user = users.isEmpty() ? null : users.get(0);
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            boolean locked = loginAttemptService.onFailure(username);
            auditService.logLogin(username, false, user == null ? null : user.getId(),
                    locked ? AuditService.Actions.LOGIN_LOCKED : AuditService.Actions.LOGIN_FAILED,
                    locked ? "连续失败达到上限，已锁定 " + loginAttemptService.lockMinutes() + " 分钟"
                            : "用户名或密码错误（还可尝试 " + loginAttemptService.remainingAttempts(username) + " 次）");
            throw BizException.unauthorized("用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            auditService.logLogin(username, false, user.getId(), AuditService.Actions.LOGIN_FAILED, "账号已被禁用");
            throw BizException.forbidden("账号已被禁用，请联系管理员");
        }
        loginAttemptService.onSuccess(username);
        Role role = Role.of(user.getRole());
        LoginResponse response = new LoginResponse();
        response.setToken(jwtService.create(new AuthUser(user.getId(), user.getUsername(), role.name())));
        response.setExpiresIn(jwtService.expireSeconds());
        response.setUser(toVO(user));
        auditService.logLogin(username, true, user.getId(), AuditService.Actions.LOGIN, "登录成功");
        return response;
    }

    /** 自助注册（默认关闭，见 app.register.enabled）：注册出来的一律是普通用户 */
    @Transactional
    public UserVO register(RegisterRequest request) {
        String username = request.getUsername().trim();
        requireUsernameFree(username);
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(StringUtils.hasText(request.getNickname()) ? request.getNickname() : username);
        user.setEmail(request.getEmail());
        user.setRole(Role.USER.name());
        user.setStatus(1);
        userMapper.insert(user);
        return toVO(user);
    }

    public UserVO getById(Long userId) {
        return toVO(requireUser(userId));
    }

    public UserVO updateProfile(Long userId, ProfileRequest request) {
        UserEntity user = requireUser(userId);
        user.setNickname(request.getNickname());
        user.setEmail(request.getEmail());
        userMapper.updateById(user);
        return toVO(user);
    }

    public void changePassword(Long userId, PasswordRequest request) {
        UserEntity user = requireUser(userId);
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BizException("原密码不正确");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userMapper.updateById(user);
    }

    // ================================================================ 账号管理

    /** 账号列表：按 id 升序（初始超级管理员在最前），支持按用户名 / 昵称筛选 */
    public PageResult<UserVO> page(String keyword, long page, long size) {
        LambdaQueryWrapper<UserEntity> wrapper = Wrappers.<UserEntity>lambdaQuery()
                .orderByAsc(UserEntity::getId);
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            wrapper.and(w -> w.like(UserEntity::getUsername, value).or().like(UserEntity::getNickname, value));
        }
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<UserEntity> result = userMapper.selectPage(new Page<>(Math.max(page, 1), safeSize), wrapper);
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(),
                result.getRecords().stream().map(this::toVO).toList());
    }

    /** 新建账号：角色固定为普通用户，超级管理员只能通过数据库赋予 */
    @Transactional
    public UserVO createUser(UserCreateRequest request) {
        String username = request.getUsername().trim();
        requireUsernameFree(username);
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(StringUtils.hasText(request.getNickname()) ? request.getNickname() : username);
        user.setEmail(request.getEmail());
        user.setRole(Role.USER.name());
        user.setStatus(1);
        userMapper.insert(user);
        auditService.log(AuditService.Actions.USER_CREATE, "USER", user.getId(), "新建账号 " + username);
        return toVO(user);
    }

    /**
     * 修改某个账号的资料（昵称 / 邮箱）。
     * 用户名是登录凭据、角色只能改库，所以这两个字段都不在这里。
     */
    @Transactional
    public UserVO updateUser(Long userId, UserUpdateRequest request) {
        UserEntity target = requireUser(userId);
        requireNotSuperAdmin(target, "超级管理员的资料请他自己在「个人中心」修改");
        target.setNickname(StringUtils.hasText(request.getNickname())
                ? request.getNickname().trim() : target.getUsername());
        target.setEmail(request.getEmail());
        userMapper.updateById(target);
        auditService.log(AuditService.Actions.USER_UPDATE, "USER", target.getId(),
                "修改资料 " + target.getUsername() + "：" + target.getNickname() + " / " + target.getEmail());
        return toVO(target);
    }

    /** 启用 / 禁用账号：禁用后对方的 token 立即失效（每次请求都会回库校验状态） */
    @Transactional
    public UserVO updateStatus(Long operatorId, Long userId, Integer status) {
        UserEntity target = requireUser(userId);
        requireNotSuperAdmin(target, "超级管理员的账号不能在这里修改");
        boolean enabled = status != null && status == 1;
        if (userId.equals(operatorId) && !enabled) {
            throw new BizException("不能禁用当前登录的账号");
        }
        target.setStatus(enabled ? 1 : 0);
        userMapper.updateById(target);
        auditService.log(AuditService.Actions.USER_STATUS, "USER", target.getId(),
                (enabled ? "启用" : "禁用") + "账号 " + target.getUsername());
        return toVO(target);
    }

    @Transactional
    public void resetPassword(Long userId, String newPassword) {
        UserEntity target = requireUser(userId);
        requireNotSuperAdmin(target, "超级管理员不能在这里重置密码，请在「个人中心」或数据库里修改");
        target.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(target);
        auditService.log(AuditService.Actions.USER_PASSWORD, "USER", target.getId(),
                "重置密码 " + target.getUsername());
    }

    /** 删除账号：名下还有图片时拒绝，避免图库记录变成孤儿 */
    @Transactional
    public void deleteUser(Long operatorId, Long userId) {
        UserEntity target = requireUser(userId);
        requireNotSuperAdmin(target, "超级管理员的账号不能删除");
        if (userId.equals(operatorId)) {
            throw new BizException("不能删除当前登录的账号");
        }
        Long images = imageMapper.selectCount(Wrappers.<ImageEntity>lambdaQuery()
                .eq(ImageEntity::getUserId, userId));
        if (images != null && images > 0) {
            throw new BizException("该账号名下还有 " + images + " 张图片，先清理或迁移后再删除");
        }
        userMapper.deleteById(userId);
        auditService.log(AuditService.Actions.USER_DELETE, "USER", userId, "删除账号 " + target.getUsername());
    }

    // ================================================================ 图库可见范围

    /**
     * 当前用户能看到的图库归属。
     * <p>
     * 图片与存储配置在库里都是按 user_id 隔离的，而普通用户又不能上传，
     * 所以「只读账号」必须能看到超级管理员的图库，否则登录后是一片空白：
     * <ul>
     *   <li>超级管理员：自己名下的图片（与上传、迁移、导入等写操作保持一致）</li>
     *   <li>普通用户：超级管理员名下的图片，只读</li>
     * </ul>
     */
    public List<Long> visibleOwnerIds(AuthUser user) {
        if (Role.of(user.role()).isSuperAdmin()) {
            return List.of(user.id());
        }
        List<Long> adminIds = superAdminIds();
        // 没有超级管理员（理论上不会）时给一个不存在的 id，保证查询返回空而不是报错
        return adminIds.isEmpty() ? List.of(-1L) : adminIds;
    }

    /** 所有超级管理员的 id（含历史数据里的 ADMIN） */
    public List<Long> superAdminIds() {
        return userMapper.selectList(Wrappers.<UserEntity>lambdaQuery()
                        .select(UserEntity::getId)
                        .in(UserEntity::getRole, List.of(Role.SUPER_ADMIN.name(), "ADMIN")))
                .stream()
                .map(UserEntity::getId)
                .toList();
    }

    private void requireUsernameFree(String username) {
        Long exists = userMapper.selectCount(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, username));
        if (exists != null && exists > 0) {
            throw new BizException("用户名已被占用");
        }
    }

    private UserEntity requireUser(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        return user;
    }

    /** 超级管理员是「数据库专属」角色：界面上既不能改角色，也不能改它的状态 / 密码 / 存亡 */
    private void requireNotSuperAdmin(UserEntity target, String message) {
        if (Role.of(target.getRole()).isSuperAdmin()) {
            throw BizException.forbidden(message);
        }
    }

    public UserVO toVO(UserEntity user) {
        Role role = Role.of(user.getRole());
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        vo.setRole(role.name());
        vo.setRoleLabel(role.getLabel());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }
}
