package com.qxfly.imagehost.storage;

import com.qxfly.imagehost.common.BizException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageSettingTest {

    @Test
    void 缺省值会被补齐() {
        StorageSetting setting = StorageSetting.of(StorageType.LOCAL, 7L, Map.of());

        assertThat(setting.type()).isEqualTo(StorageType.LOCAL);
        assertThat(setting.configId()).isEqualTo(7L);
        assertThat(setting.get("baseDir")).isEqualTo("uploads");
        assertThat(setting.get("pathPrefix")).isEqualTo("images");
    }

    @Test
    void 必填项缺失时报错并带上中文名() {
        assertThatThrownBy(() -> StorageSetting.of(StorageType.GITHUB, Map.of("owner", "qxFly")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仓库名称")
                .hasMessageContaining("GitHub");
    }

    @Test
    void 路径前缀会被规范化() {
        assertThat(Setting.of("pathPrefix", "/a/b/").pathPrefix()).isEqualTo("a/b/");
        assertThat(Setting.of("pathPrefix", "a/b").pathPrefix()).isEqualTo("a/b/");
        // 空值会回落到字段默认值（LOCAL 的 pathPrefix 默认 images）
        assertThat(Setting.of("pathPrefix", "").pathPrefix()).isEqualTo("images/");
        assertThat(Setting.copyOf(null).pathPrefix()).isEqualTo("images/");
    }

    @Test
    void 取布尔与数字的宽容行为() {
        assertThat(Setting.of("x", "yes").getBool("x", false)).isTrue();
        assertThat(Setting.of("x", "1").getBool("x", false)).isTrue();
        assertThat(Setting.of("x", "no").getBool("x", true)).isFalse();
        // 非空但不认识的取值一律按 false（开关字段前端只会传 true/false）
        assertThat(Setting.of("x", "abc").getBool("x", true)).isFalse();
        assertThat(Setting.of("n", "42").getInt("n", 0)).isEqualTo(42);
        assertThat(Setting.of("n", "abc").getInt("n", 9)).isEqualTo(9);
        assertThat(Setting.copyOf(null).getInt("n", 9)).isEqualTo(9);
    }

    /** LOCAL 只有一个可选字段，用它承载任意键值，方便测工具方法 */
    private static final class Setting {
        static StorageSetting of(String key, String value) {
            Map<String, String> map = new LinkedHashMap<>();
            map.put(key, value);
            return StorageSetting.of(StorageType.LOCAL, map);
        }

        static StorageSetting copyOf(@SuppressWarnings("unused") Void ignored) {
            return StorageSetting.of(StorageType.LOCAL, Map.of());
        }
    }
}
