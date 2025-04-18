package org.example.demo.service;

import org.example.demo.bean.message.ApiResponseMessage;

import java.util.List;
import java.util.Map;

/**
 * 3D Tiles 瓦片服务接口
 */
public interface Tiles3DService {
    /**
     * 解析 tileset.json 文件及其子节点并保存到数据库
     * @param folderPath 包含 tileset.json 文件的文件夹路径
     */
    String parseAndSaveTileset(String folderPath);

    /**
     * 根据瓦片 ID 获取瓦片的边界体积信息
     * @param id
     * @return
     */
    Map<String, Object> getBoundingVolumeById(Long id);
    /**
     * 根据瓦片 ID 列表批量获取瓦片的边界体积信息
     * @param ids
     * @return
     */
    ApiResponseMessage<Map<Long, Map<String, Object>>> getBoundingVolumeByIds(List<Long> ids);
    /**
     * 根据多边形查询相交的瓦片信息
     * @param polygon 多边形顶点列表（WGS84 经纬度）
     * @return ApiResponseMessage 包含瓦片数据的响应
     */
    ApiResponseMessage<Map<Long, Map<String, Object>>> queryByPolygon(List<List<Double>> polygon);
}