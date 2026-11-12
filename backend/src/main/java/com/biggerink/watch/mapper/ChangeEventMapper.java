package com.biggerink.watch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.biggerink.watch.entity.ChangeEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChangeEventMapper extends BaseMapper<ChangeEvent> {
}
