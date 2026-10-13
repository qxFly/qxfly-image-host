package com.pichost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pichost.entity.ImageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * ImageEntity 数据访问层。
 */
@Mapper
public interface ImageMapper extends BaseMapper<ImageEntity> {
}
