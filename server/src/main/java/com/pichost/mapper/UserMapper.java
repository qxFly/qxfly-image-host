package com.pichost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pichost.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * UserEntity 数据访问层。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
