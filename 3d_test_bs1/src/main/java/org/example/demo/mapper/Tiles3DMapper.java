package org.example.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.demo.bean.entity.Tiles3D;

import java.util.List;


/**
 * Tiles3D 数据访问接口，映射到数据库表 3dtiles_table
 */
@Mapper
public interface Tiles3DMapper extends BaseMapper<Tiles3D> {
    @Select("SELECT \"3dtile_id\" AS id, tile_level, geometric_error, bounding_volume, radius " +
            "FROM \"3dtiles_table\" " +
            "WHERE tile_level = 2 " +
            "AND ST_Intersects(" +
            "  ST_GeomFromText(#{wkt}, 4326), " +
            "  ST_Buffer(ST_Transform(bounding_volume, 4326), radius * 0.000009)" +
            ")")
    List<Tiles3D> findByPolygon(@Param("wkt") String wkt);

}