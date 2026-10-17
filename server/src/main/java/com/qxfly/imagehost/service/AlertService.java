package com.qxfly.imagehost.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qxfly.imagehost.dto.AlertVO;
import com.qxfly.imagehost.entity.ImageEntity;
import com.qxfly.imagehost.entity.ShareLinkEntity;
import com.qxfly.imagehost.mapper.ImageMapper;
import com.qxfly.imagehost.mapper.ShareLinkMapper;
import com.qxfly.imagehost.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 概览页的可行动提示。
 * <p>
 * 统计数字只能说明"有多少"，这里回答"该做什么"：回收站还有图没清、
 * 存储后端体检失败、分享相册快过期、有重复图片……只有存在问题时才返回条目，
 * 概览页据决定是否展示"待处理"区块。
 */
@Service
@RequiredArgsConstructor
public class AlertService {

    /** 分享快过期的提醒窗口（小时） */
    private static final int SHARE_EXPIRE_HOURS = 72;

    private final ImageMapper imageMapper;
    private final ShareLinkMapper shareLinkMapper;
    private final UserService userService;
    private final StorageCheckService storageCheckService;

    public List<AlertVO> alerts(AuthUser user) {
        List<Long> ownerIds = userService.visibleOwnerIds(user);
        List<AlertVO> alerts = new ArrayList<>();

        // 1) 回收站里还有图没处理
        Long trashed = imageMapper.selectCount(Wrappers.<ImageEntity>lambdaQuery()
                .in(ImageEntity::getUserId, ownerIds)
                .eq(ImageEntity::getDeleted, 1));
        if (trashed != null && trashed > 0) {
            AlertVO alert = new AlertVO();
            alert.setLevel("info");
            alert.setTitle("回收站里有 " + trashed + " 张图片");
            alert.setDetail("确认不需要了就彻底删除，误删的可以恢复");
            alert.setPath("/trash");
            alerts.add(alert);
        }

        // 2) 有重复图片
        long duplicateGroups = imageMapper.selectMaps(new QueryWrapper<ImageEntity>()
                        .select("hash")
                        .in("user_id", ownerIds)
                        .eq("deleted", 0)
                        .isNotNull("hash")
                        .ne("hash", "")
                        .groupBy("hash")
                        .having("count(*) > 1"))
                .size();
        if (duplicateGroups > 0) {
            AlertVO alert = new AlertVO();
            alert.setLevel("info");
            alert.setTitle("发现 " + duplicateGroups + " 组重复图片");
            alert.setDetail("每组保留一张即可回收空间，清理是移入回收站，可反悔");
            alert.setPath("/duplicates");
            alerts.add(alert);
        }

        // 3) 存储后端体检失败（凭证过期 / 桶被删 / 网络不通）
        for (StorageCheckService.CheckResult result : storageCheckService.results().values()) {
            if (result.ok()) {
                continue;
            }
            AlertVO alert = new AlertVO();
            alert.setLevel("danger");
            alert.setTitle("存储后端「" + result.name() + "」体检失败");
            alert.setDetail(StringUtils.hasText(result.message()) ? result.message() : "请到存储设置里手动测试");
            alert.setPath("/storage");
            alerts.add(alert);
        }

        // 4) 分享相册快过期（避免用户忘了通知对方）
        LocalDateTime soon = LocalDateTime.now().plusHours(SHARE_EXPIRE_HOURS);
        for (ShareLinkEntity share : shareLinkMapper.selectList(Wrappers.<ShareLinkEntity>lambdaQuery()
                .in(ShareLinkEntity::getUserId, ownerIds)
                .eq(ShareLinkEntity::getEnabled, 1)
                .isNotNull(ShareLinkEntity::getExpiresAt)
                .le(ShareLinkEntity::getExpiresAt, soon))) {
            if (share.getExpiresAt().isBefore(LocalDateTime.now())) {
                continue;
            }
            AlertVO alert = new AlertVO();
            alert.setLevel("warning");
            alert.setTitle("分享「" + share.getTitle() + "」即将过期");
            alert.setDetail("过期时间：" + share.getExpiresAt().toString().replace('T', ' '));
            alert.setPath("/shares");
            alerts.add(alert);
        }

        return alerts;
    }
}
