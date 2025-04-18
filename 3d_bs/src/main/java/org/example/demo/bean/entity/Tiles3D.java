package org.example.demo.bean.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.type.JdbcType;
import org.example.demo.config.GeometryTypeHandler;
import org.locationtech.jts.geom.Geometry;

/**
 * 表示3D Tiles瓦片实体，映射到数据库表 3dtiles_table
 */
@Data
@Slf4j
@TableName("\"3dtiles_table\"")
public class Tiles3D {
    @TableId("\"3dtile_id\"")
    private Long id; // 3维瓦片ID，主键

    @TableField("\"3dtile_parentid\"")
    private Long parentId; // 父节点ID，关联本表的 3dtile_id，可能为空

    @TableField("tile_level")
    private Integer tileLevel; // 瓦片层级数，例如 0 表示根节点

    @TableField(value = "bounding_volume", typeHandler = GeometryTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Geometry boundingVolume; // 包围盒坐标，使用 PostGIS GEOMETRY 类型存储空间范围

    @TableField("geometric_error")
    private Double geometricError; // 几何误差系数，控制细节级别

    @TableField("refine")
    private String refine; // 与上一层次瓦片的关系，例如 "REPLACE" 或 "ADD"

    @TableField("data_size")
    private Double dataSize; // 该瓦片的几何数据量（字节），可能为空

    @TableField("path_tiles")
    private String pathTiles; // 瓦片存储路径，例如 "Lianjian/Data/0.glb"
    @TableField("radius")
    private Double radius;
}