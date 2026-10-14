package com.qxfly.imagehost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qxfly.imagehost.entity.AppSettingEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统设置读写。
 */
@Mapper
public interface AppSettingMapper extends BaseMapper<AppSettingEntity> {
}
