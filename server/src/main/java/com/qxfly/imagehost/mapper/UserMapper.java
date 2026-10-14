package com.qxfly.imagehost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qxfly.imagehost.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * UserEntity 数据访问层。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
