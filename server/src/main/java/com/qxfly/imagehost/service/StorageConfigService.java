package com.qxfly.imagehost.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qxfly.imagehost.common.BizException;
import com.qxfly.imagehost.dto.StorageConfigRequest;
import com.qxfly.imagehost.dto.StorageConfigVO;
import com.qxfly.imagehost.dto.StorageTypeVO;
import com.qxfly.imagehost.entity.StorageConfigEntity;
import com.qxfly.imagehost.mapper.StorageConfigMapper;
import com.qxfly.imagehost.storage.StorageField;
import com.qxfly.imagehost.storage.StorageService;
import com.qxfly.imagehost.storage.StorageServiceFactory;
import com.qxfly.imagehost.storage.StorageSetting;
import com.qxfly.imagehost.storage.StorageType;
import com.qxfly.imagehost.storage.support.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储后端配置管理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageConfigService {

    /** 敏感字段回显占位符 */
    public static final String SECRET_MASK = "******";

    private final StorageConfigMapper storageConfigMapper;
    private final StorageServiceFactory storageServiceFactory;
    private final SecretCrypto secretCrypto;
    private final AuditService auditService;

    public List<StorageTypeVO> types() {
        return Arrays.stream(StorageType.values())
                .map(t -> new StorageTypeVO(t.name(), t.getLabel(), t.getShortLabel(), t.getDescription(), t.getFields()))
                .toList();
    }

    public List<StorageConfigVO> list(Long userId) {
        List<StorageConfigEntity> entities = storageConfigMapper.selectList(Wrappers.<StorageConfigEntity>lambdaQuery()
                .eq(StorageConfigEntity::getUserId, userId)
                .orderByDesc(StorageConfigEntity::getIsDefault)
                .orderByDesc(StorageConfigEntity::getId));
        return entities.stream().map(this::toVO).toList();
    }

    @Transactional
    public StorageConfigVO create(Long userId, StorageConfigRequest request) {
        Long exists = storageConfigMapper.selectCount(Wrappers.<StorageConfigEntity>lambdaQuery()
                .eq(StorageConfigEntity::getUserId, userId)
                .eq(StorageConfigEntity::getName, request.getName().trim()));
        if (exists != null && exists > 0) {
            throw new BizException("已存在同名配置: " + request.getName());
        }

        StorageConfigEntity entity = new StorageConfigEntity();
        entity.setUserId(userId);
        entity.setName(request.getName().trim());
        entity.setType(request.getType().name());
        entity.setRemark(request.getRemark());
        entity.setEnabled(request.getEnabled() == null || request.getEnabled() ? 1 : 0);

        Long total = storageConfigMapper.selectCount(Wrappers.<StorageConfigEntity>lambdaQuery()
                .eq(StorageConfigEntity::getUserId, userId));
        boolean first = total == null || total == 0;
        entity.setIsDefault(first || Boolean.TRUE.equals(request.getIsDefault()) ? 1 : 0);
        entity.setConfigJson(Json.write(encryptSecrets(request.getType(), request.getConfig(), Map.of())));

        // 先校验配置可用性，避免存入无效配置
        StorageSetting.of(request.getType(), Json.readStringMap(entity.getConfigJson()));

        storageConfigMapper.insert(entity);
        if (entity.getIsDefault() == 1) {
            clearOtherDefaults(userId, entity.getId());
        }
        auditService.log(AuditService.Actions.STORAGE_CREATE, "STORAGE_CONFIG", entity.getId(),
                "新建存储配置 " + entity.getName() + "（" + request.getType().getLabel() + "）");
        return toVO(entity);
    }

    @Transactional
    public StorageConfigVO update(Long userId, Long id, StorageConfigRequest request) {
        StorageConfigEntity entity = requireOwned(userId, id);
        StorageType type = request.getType();

        String name = request.getName().trim();
        Long duplicated = storageConfigMapper.selectCount(Wrappers.<StorageConfigEntity>lambdaQuery()
                .eq(StorageConfigEntity::getUserId, userId)
                .eq(StorageConfigEntity::getName, name)
                .ne(StorageConfigEntity::getId, id));
        if (duplicated != null && duplicated > 0) {
            throw new BizException("已存在同名配置: " + name);
        }

        Map<String, String> existing = Json.readStringMap(entity.getConfigJson());
        entity.setName(name);
        entity.setType(type.name());
        entity.setRemark(request.getRemark());
        entity.setEnabled(request.getEnabled() == null || request.getEnabled() ? 1 : 0);
        if (request.getIsDefault() != null) {
            entity.setIsDefault(request.getIsDefault() ? 1 : 0);
        }
        entity.setConfigJson(Json.write(encryptSecrets(type, request.getConfig(), existing)));

        StorageSetting.of(type, Json.readStringMap(entity.getConfigJson()));

        storageConfigMapper.updateById(entity);
        if (entity.getIsDefault() != null && entity.getIsDefault() == 1) {
            clearOtherDefaults(userId, id);
        }
        auditService.log(AuditService.Actions.STORAGE_UPDATE, "STORAGE_CONFIG", id, "修改存储配置 " + name);
        return toVO(entity);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        StorageConfigEntity entity = requireOwned(userId, id);
        auditService.log(AuditService.Actions.STORAGE_DELETE, "STORAGE_CONFIG", id, "删除存储配置 " + entity.getName());
        storageConfigMapper.deleteById(entity.getId());
        if (entity.getIsDefault() != null && entity.getIsDefault() == 1) {
            List<StorageConfigEntity> rest = storageConfigMapper.selectList(Wrappers.<StorageConfigEntity>lambdaQuery()
                    .eq(StorageConfigEntity::getUserId, userId)
                    .orderByAsc(StorageConfigEntity::getId)
                    .last("LIMIT 1"));
            if (!rest.isEmpty()) {
                rest.get(0).setIsDefault(1);
                storageConfigMapper.updateById(rest.get(0));
            }
        }
    }

    @Transactional
    public StorageConfigVO setDefault(Long userId, Long id) {
        StorageConfigEntity entity = requireOwned(userId, id);
        entity.setIsDefault(1);
        entity.setEnabled(1);
        storageConfigMapper.updateById(entity);
        clearOtherDefaults(userId, id);
        auditService.log(AuditService.Actions.STORAGE_DEFAULT, "STORAGE_CONFIG", id,
                "设为默认存储配置 " + entity.getName());
        return toVO(entity);
    }

    /** 测试已保存的配置 */
    public String test(Long userId, Long id) {
        StorageConfigEntity entity = requireOwned(userId, id);
        return test(typeOf(entity), toSetting(entity));
    }

    /**
     * 测试尚未保存的配置。
     * 敏感字段提交占位符或空值时，沿用 existingId 对应配置里已保存的密文。
     */
    public String test(Long userId, Long existingId, StorageConfigRequest request) {
        Map<String, String> existing = existingId == null
                ? Map.of()
                : Json.readStringMap(requireOwned(userId, existingId).getConfigJson());
        Map<String, String> merged = encryptSecrets(request.getType(), request.getConfig(), existing);
        return test(request.getType(), StorageSetting.of(request.getType(), merged));
    }

    public String test(StorageType type, StorageSetting setting) {
        StorageService service = storageServiceFactory.get(type);
        service.test(setting);
        String message = service.testMessage(setting);
        return StringUtils.hasText(message) ? message : "连接成功，凭证有效";
    }

    public StorageConfigEntity requireOwned(Long userId, Long id) {
        StorageConfigEntity entity = storageConfigMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("存储配置不存在");
        }
        if (!entity.getUserId().equals(userId)) {
            throw BizException.forbidden("无权访问该存储配置");
        }
        return entity;
    }

    /**
     * 按主键取配置，不做归属校验。
     * 只在调用方已经确认过访问权限时使用 —— 例如普通用户下载「系统图库」里的图片时，
     * 需要按图片所属账号的配置去取原始内容。
     */
    public StorageConfigEntity requireById(Long id) {
        StorageConfigEntity entity = storageConfigMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("存储配置不存在");
        }
        return entity;
    }

    /** 取用户默认存储配置；没有显式默认时取第一个启用项 */
    public StorageConfigEntity resolveConfig(Long userId, Long storageConfigId) {
        if (storageConfigId != null) {
            StorageConfigEntity entity = requireOwned(userId, storageConfigId);
            if (entity.getEnabled() != null && entity.getEnabled() == 0) {
                throw new BizException("存储配置已被禁用: " + entity.getName());
            }
            return entity;
        }
        List<StorageConfigEntity> defaults = storageConfigMapper.selectList(Wrappers.<StorageConfigEntity>lambdaQuery()
                .eq(StorageConfigEntity::getUserId, userId)
                .eq(StorageConfigEntity::getEnabled, 1)
                .orderByDesc(StorageConfigEntity::getIsDefault)
                .orderByAsc(StorageConfigEntity::getId)
                .last("LIMIT 1"));
        if (defaults.isEmpty()) {
            throw new BizException("请先在【存储设置】中添加一个存储后端");
        }
        return defaults.get(0);
    }

    /**
     * 按归属批量取配置实体。
     * 图库要给普通用户解析超级管理员的图片地址，所以不能只看当前用户。
     */
    public Map<Long, StorageConfigEntity> mapByOwners(List<Long> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, StorageConfigEntity> map = new LinkedHashMap<>();
        storageConfigMapper.selectList(Wrappers.<StorageConfigEntity>lambdaQuery()
                        .in(StorageConfigEntity::getUserId, ownerIds))
                .forEach(e -> map.put(e.getId(), e));
        return map;
    }

    public Map<Long, String> nameMap(Long userId) {
        Map<Long, String> map = new LinkedHashMap<>();
        storageConfigMapper.selectList(Wrappers.<StorageConfigEntity>lambdaQuery()
                        .eq(StorageConfigEntity::getUserId, userId))
                .forEach(e -> map.put(e.getId(), e.getName()));
        return map;
    }

    public StorageSetting toSetting(StorageConfigEntity entity) {
        StorageType type = typeOf(entity);
        Map<String, String> values = decryptSecrets(type, Json.readStringMap(entity.getConfigJson()));
        // 带上配置 ID：本地存储要按 /files/{配置ID}/... 生成访问地址
        return StorageSetting.of(type, entity.getId(), values);
    }

    public StorageType typeOf(StorageConfigEntity entity) {
        return StorageType.of(entity.getType());
    }

    private void clearOtherDefaults(Long userId, Long keepId) {
        storageConfigMapper.update(null, Wrappers.<StorageConfigEntity>lambdaUpdate()
                .eq(StorageConfigEntity::getUserId, userId)
                .ne(StorageConfigEntity::getId, keepId)
                .set(StorageConfigEntity::getIsDefault, 0));
    }

    /** 敏感字段加密落库；提交值为占位符或空时保留原值 */
    private Map<String, String> encryptSecrets(StorageType type, Map<String, String> incoming, Map<String, String> existing) {
        Map<String, String> result = new LinkedHashMap<>();
        if (incoming != null) {
            incoming.forEach((k, v) -> result.put(k, v == null ? null : v.trim()));
        }
        for (StorageField field : type.getSecretFields()) {
            String value = result.get(field.key());
            if (!StringUtils.hasText(value) || SECRET_MASK.equals(value)) {
                String previous = existing.get(field.key());
                if (StringUtils.hasText(previous)) {
                    result.put(field.key(), previous.startsWith("enc:") ? previous : secretCrypto.encrypt(previous));
                    continue;
                }
                result.remove(field.key());
                continue;
            }
            result.put(field.key(), secretCrypto.encrypt(value));
        }
        return result;
    }

    private Map<String, String> decryptSecrets(StorageType type, Map<String, String> raw) {
        Map<String, String> result = new LinkedHashMap<>(raw);
        for (StorageField field : type.getSecretFields()) {
            String value = result.get(field.key());
            if (StringUtils.hasText(value)) {
                result.put(field.key(), secretCrypto.decrypt(value));
            }
        }
        return result;
    }

    private StorageConfigVO toVO(StorageConfigEntity entity) {
        StorageType type = typeOf(entity);
        StorageConfigVO vo = new StorageConfigVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setType(type);
        vo.setTypeLabel(type.getLabel());
        vo.setIsDefault(entity.getIsDefault() != null && entity.getIsDefault() == 1);
        vo.setEnabled(entity.getEnabled() == null || entity.getEnabled() == 1);
        vo.setRemark(entity.getRemark());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        vo.setSupportsList(storageServiceFactory.get(type).supportsList());

        Map<String, String> config = Json.readStringMap(entity.getConfigJson());
        for (StorageField field : type.getSecretFields()) {
            config.put(field.key(), StringUtils.hasText(config.get(field.key())) ? SECRET_MASK : "");
        }
        vo.setConfig(config);
        return vo;
    }

    public StorageService serviceOf(StorageConfigEntity entity) {
        return storageServiceFactory.get(typeOf(entity));
    }
}
