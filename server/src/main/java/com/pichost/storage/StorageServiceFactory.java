package com.pichost.storage;

import com.pichost.common.BizException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按类型路由到具体的 {@link StorageService} 实现。
 */
@Component
public class StorageServiceFactory {

    private final Map<StorageType, StorageService> registry;

    public StorageServiceFactory(List<StorageService> services) {
        Map<StorageType, StorageService> map = new EnumMap<>(StorageType.class);
        for (StorageService service : services) {
            map.put(service.type(), service);
        }
        this.registry = Collections.unmodifiableMap(map);
    }

    public StorageService get(StorageType type) {
        StorageService service = registry.get(type);
        if (service == null) {
            throw new BizException("暂未实现该存储类型: " + type.getLabel());
        }
        return service;
    }

    public Set<StorageType> supportedTypes() {
        return registry.keySet();
    }
}
