package org.example.demo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.demo.bean.entity.Data3D;

import java.util.Map;

/**
 * 3D 产品数据服务接口
 */
public interface Data3DService extends IService<Data3D> {
    /**
     * 解析 tileset.json 文件并保存到数据库
     * @param folderPath 包含 tileset.json 文件的文件夹路径
     */
    void parseAndSaveTileset(String folderPath);
    // 通过数据名字查询指定数据集的 tileset.json 路径
    Map<String, String> getTilesetPathByName(String name) throws Exception;
}
