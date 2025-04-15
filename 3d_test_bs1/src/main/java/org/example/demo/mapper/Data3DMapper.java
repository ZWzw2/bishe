package org.example.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.example.demo.bean.entity.Data3D;
import org.apache.ibatis.annotations.Mapper;

@Mapper // 添加 @Mapper 注解
public interface Data3DMapper extends BaseMapper<Data3D> {

}
