package com.qxfly.imagehost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qxfly.imagehost.entity.AuditLogEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {
}
