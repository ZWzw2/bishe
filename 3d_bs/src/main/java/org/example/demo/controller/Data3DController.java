package org.example.demo.controller;

import org.example.demo.bean.message.ApiResponseMessage;
import org.example.demo.bean.entity.Data3D;
import org.example.demo.service.Data3DService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 控制器层，处理HTTP请求
 */
@RestController
@RequestMapping("/api/3ddata")
@Slf4j
public class Data3DController {

    @Autowired
    private Data3DService data3DService;
    /**
     * 获取 tileset.json 文件在minio的存储路径，并返回给客户端
     * @param name tileset.json 文件名
     * 返回 tileset.json 文件在minio的存储路径
     * 如果文件不存在，则返回404错误
     */
    @CrossOrigin(origins = "*")
    @GetMapping("/{name}")
    public Map<String, String> getTileset(@PathVariable String name) throws Exception {
        return data3DService.getTilesetPathByName(name);
    }


    /**
     * 解析并保存 tileset.json 文件
     * @param folderPath 包含 tileset.json 文件的文件夹路径
     * @return 操作结果，ApiResponse 包含状态和消息
     */
    @PostMapping("/parse-tileset")
    public ResponseEntity<ApiResponseMessage<Void>> parseTileset(@RequestParam String folderPath) {
        try {
            data3DService.parseAndSaveTileset(folderPath);
            ApiResponseMessage<Void> response = new ApiResponseMessage<>(200, "Tileset parsed and saved successfully", null);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            if (e.getMessage().contains("already exists")) {
                // 如果是数据已经存在的情况，返回特定的响应
                ApiResponseMessage<Void> response = new ApiResponseMessage<>(200, "Data already exists, no new data saved", null);
                return ResponseEntity.ok(response);
            } else {
                // 其他异常情况
                log.error("Exception occurred while parsing and saving tileset", e);
                ApiResponseMessage<Void> response = new ApiResponseMessage<>(500, "Internal server error", null);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }
        }
    }

}
