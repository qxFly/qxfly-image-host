package com.qxfly.imagehost.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppSettingServiceTest {

    @Test
    void 上限文案的可读化() {
        assertThat(AppSettingService.readable(0)).isEqualTo("无上限");
        assertThat(AppSettingService.readable(-1)).isEqualTo("无上限");
        assertThat(AppSettingService.readable(512)).isEqualTo("512 B");
        assertThat(AppSettingService.readable(1024)).isEqualTo("1.0 KB");
        assertThat(AppSettingService.readable(31_457_280)).isEqualTo("30.0 MB");
        assertThat(AppSettingService.readable(1024L * 1024 * 1024)).isEqualTo("1.0 GB");
        assertThat(AppSettingService.readable(1024L * 1024 * 1024 * 1024)).isEqualTo("1.0 TB");
    }
}
