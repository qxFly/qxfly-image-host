package com.qxfly.imagehost.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qxfly.imagehost.common.BizException;
import com.qxfly.imagehost.dto.ShareRequest;
import com.qxfly.imagehost.dto.ShareVO;
import com.qxfly.imagehost.entity.ImageEntity;
import com.qxfly.imagehost.entity.ShareLinkEntity;
import com.qxfly.imagehost.mapper.ImageMapper;
import com.qxfly.imagehost.mapper.ShareLinkMapper;
import com.qxfly.imagehost.storage.support.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 分享链接（相册）。
 * <p>
 * 把选中的图片打包成一个随机 token 的公开链接，可选有效期与访问密码，随时可撤销；
 * 撤销后立刻失效。访问计数用于知道相册有没有被看过。
 * <p>
 * token 用 32 位十六进制随机数，不可枚举；密码存 BCrypt 哈希。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_IMAGES = 2000;

    private final ShareLinkMapper shareLinkMapper;
    private final ImageMapper imageMapper;
    private final ImageService imageService;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Transactional
    public ShareVO create(Long userId, ShareRequest request) {
        List<Long> ids = distinct(request.getImageIds());
        if (ids.isEmpty()) {
            throw new BizException("请先选择要分享的图片");
        }
        if (ids.size() > MAX_IMAGES) {
            throw new BizException("一个分享最多 " + MAX_IMAGES + " 张图片，当前选了 " + ids.size() + " 张");
        }
        // 只能分享自己可见的图片
        List<Long> visible = userService.visibleOwnerIds(new com.qxfly.imagehost.security.AuthUser(userId, null,
                com.qxfly.imagehost.security.Role.SUPER_ADMIN.name()));
        Long found = imageMapper.selectCount(Wrappers.<ImageEntity>lambdaQuery()
                .in(ImageEntity::getUserId, visible)
                .eq(ImageEntity::getDeleted, 0)
                .in(ImageEntity::getId, ids));
        if (found == null || found < ids.size()) {
            throw new BizException("有图片不存在或无权分享，请刷新后重试");
        }

        ShareLinkEntity entity = new ShareLinkEntity();
        entity.setUserId(userId);
        entity.setToken(randomToken());
        entity.setTitle(StringUtils.hasText(request.getTitle()) ? request.getTitle().trim() : "未命名相册");
        entity.setImageIds(Json.write(ids));
        entity.setEnabled(1);
        entity.setViewCount(0L);
        if (StringUtils.hasText(request.getPassword())) {
            entity.setPasswordHash(passwordEncoder.encode(request.getPassword().trim()));
        }
        if (request.getExpiresInDays() != null && request.getExpiresInDays() > 0) {
            entity.setExpiresAt(LocalDateTime.now().plusDays(request.getExpiresInDays()));
        }
        shareLinkMapper.insert(entity);
        auditService.log(AuditService.Actions.SHARE_CREATE, "SHARE", entity.getId(),
                "创建分享「" + entity.getTitle() + "」，共 " + ids.size() + " 张"
                        + (entity.getPasswordHash() == null ? "" : "（需要密码）")
                        + (entity.getExpiresAt() == null ? "" : "，"
                        + request.getExpiresInDays() + " 天后过期"));
        return toVO(entity, false);
    }

    public List<ShareVO> list(Long userId) {
        return shareLinkMapper.selectList(Wrappers.<ShareLinkEntity>lambdaQuery()
                        .eq(ShareLinkEntity::getUserId, userId)
                        .orderByDesc(ShareLinkEntity::getId))
                .stream().map(entity -> toVO(entity, false)).toList();
    }

    @Transactional
    public void delete(Long userId, Long id) {
        ShareLinkEntity entity = requireOwned(userId, id);
        shareLinkMapper.deleteById(id);
        auditService.log(AuditService.Actions.SHARE_DELETE, "SHARE", id, "取消分享「" + entity.getTitle() + "」");
    }

    /**
     * 公开访问：不需要登录。密码错误 / 已撤销 / 已过期都返回业务错误。
     *
     * @param password 相册密码，未设置密码时忽略
     */
    @Transactional
    public ShareVO view(String token, String password) {
        ShareLinkEntity entity = shareLinkMapper.selectOne(Wrappers.<ShareLinkEntity>lambdaQuery()
                .eq(ShareLinkEntity::getToken, token)
                .last("LIMIT 1"));
        if (entity == null) {
            throw BizException.notFound("分享不存在或已被取消");
        }
        if (entity.getEnabled() == null || entity.getEnabled() != 1) {
            throw BizException.forbidden("该分享已被关闭");
        }
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw BizException.forbidden("该分享已于 " + entity.getExpiresAt().toLocalDate() + " 过期");
        }
        if (StringUtils.hasText(entity.getPasswordHash())) {
            if (!StringUtils.hasText(password)) {
                throw BizException.forbidden("该分享需要访问密码");
            }
            if (!passwordEncoder.matches(password, entity.getPasswordHash())) {
                throw BizException.forbidden("访问密码不正确");
            }
        }
        long views = (entity.getViewCount() == null ? 0L : entity.getViewCount()) + 1;
        shareLinkMapper.update(null, Wrappers.<ShareLinkEntity>lambdaUpdate()
                .eq(ShareLinkEntity::getId, entity.getId())
                .set(ShareLinkEntity::getViewCount, views));
        // 返回自增后的值，否则前端看到的还是上一次的访问数
        entity.setViewCount(views);
        return toVO(entity, true);
    }

    private ShareLinkEntity requireOwned(Long userId, Long id) {
        ShareLinkEntity entity = shareLinkMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("分享不存在");
        }
        if (!entity.getUserId().equals(userId)) {
            throw BizException.forbidden("无权操作该分享");
        }
        return entity;
    }

    private ShareVO toVO(ShareLinkEntity entity, boolean withImages) {
        ShareVO vo = new ShareVO();
        vo.setId(entity.getId());
        vo.setToken(entity.getToken());
        vo.setTitle(entity.getTitle());
        vo.setUrl("/#/s/" + entity.getToken());
        vo.setPasswordProtected(StringUtils.hasText(entity.getPasswordHash()));
        vo.setExpiresAt(entity.getExpiresAt());
        vo.setEnabled(entity.getEnabled() != null && entity.getEnabled() == 1);
        vo.setExpired(entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(LocalDateTime.now()));
        vo.setViewCount(entity.getViewCount());
        vo.setCreatedAt(entity.getCreatedAt());
        List<Long> ids = readIds(entity.getImageIds());
        vo.setImageCount(ids.size());
        if (withImages && !ids.isEmpty()) {
            vo.setImages(imageService.listByIds(ids));
        }
        return vo;
    }

    private List<Long> readIds(String json) {
        try {
            Long[] values = Json.read(json, Long[].class);
            return values == null ? List.of() : List.of(values);
        } catch (Exception e) {
            log.warn("解析分享图片列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Long> distinct(List<Long> ids) {
        List<Long> result = new ArrayList<>();
        if (ids != null) {
            for (Long id : ids) {
                if (id != null && !result.contains(id)) {
                    result.add(id);
                }
            }
        }
        return result;
    }

    private String randomToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder builder = new StringBuilder(32);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }
}
