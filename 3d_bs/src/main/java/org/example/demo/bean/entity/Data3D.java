package org.example.demo.bean.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.type.JdbcType;
import org.example.demo.bean.dto.ExtraInfoDTO;
import org.example.demo.config.GeometryTypeHandler;
import org.example.demo.config.JsonbTypeHandler;
import org.locationtech.jts.geom.Geometry;

/**
 * 表示3D数据实体，映射到数据库表 3ddata_table
 */
@Data
@Slf4j
@TableName("\"3ddata_table\"")
public class Data3D {
    @TableId("product_key")
    private String productKey; // 产品唯一标识，由 dataIdentification 和 version 拼接，例如 "Lianjiang_1.0"

    @TableField("\"3ddata_identification\"")
    private String dataIdentification; // 数据集名称，对应文件夹名，例如 "Lianjiang"

    @TableField("\"3dtiles_id\"")
    private Long tilesId; // 根节点的 3dtile_id，关联 3dtiles_table

    @TableField("coordinate_system")
    private String coordinateSystem; // 坐标系，设为 "EPSG:4978"

    @TableField("tile_set_size")
    private Double tileSetSize; // 瓦片集总大小（字节），从文件计算

    @TableField(value = "bounding_box", typeHandler = GeometryTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Geometry boundingBox; // 数据集的总包围盒，使用 PostGIS GEOMETRY 类型

    @TableField("tile_level_min")
    private Integer tileLevelMin; // 最小瓦片层级，例如 0

    @TableField("tile_level_max")
    private Integer tileLevelMax; // 最大瓦片层级，例如 10

    @TableField(value = "extra_info_json", typeHandler = JsonbTypeHandler.class)
    private String extraInfoJson; // 额外信息，存储 JSON 数据，如 {"version": "1.0", "gltfUpAxis": "Y"}

    @TableField(exist = false)
    private ExtraInfoDTO extraInfo; // 非持久化字段，用于解析 extraInfoJson

    @TableField("path")
    private String path;// 存储 tileset.json 的 MinIO 路径，例如 "http://localhost:9000/tiles-bucket/Lianjiang/tileset.json"

    @TableField("radius")
    private Double radius;
}