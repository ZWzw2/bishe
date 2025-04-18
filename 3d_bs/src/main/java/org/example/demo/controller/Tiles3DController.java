package org.example.demo.controller;
import org.example.demo.bean.message.ApiResponseMessage;
import org.example.demo.service.Tiles3DService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制器层，处理HTTP请求
 */
@RestController
@RequestMapping("/api/3dtiles")
@Slf4j
public class Tiles3DController {

    @Autowired
    private Tiles3DService tiles3DService;

    /**
     * 解析并保存 tileset.json 文件
     * @param folderPath 包含 tileset.json 文件的文件夹路径
     * @return 操作结果，ApiResponse 包含状态和消息
     */
    @PostMapping("/parse-tileset")
    public ResponseEntity<ApiResponseMessage<Void>> parseTileset(@RequestParam String folderPath) {
        try {
            tiles3DService.parseAndSaveTileset(folderPath);
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
    /**
     * 根据ID查询bounding volume
     * @param id 瓦片ID
     * @return 包含bounding volume的响应
     */
    @CrossOrigin(origins = "*")
    @GetMapping("/id/{id}")
    public ResponseEntity<Map<String, Object>> getBoundingVolume(@PathVariable Long id) {
        try {
            Map<String, Object> result = tiles3DService.getBoundingVolumeById(id);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Exception occurred while getting bounding volume for id: {}", id, e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to get bounding volume: " + e.getMessage());
            return ResponseEntity.status(404).body(error);
        }
    }
    /**
     * 根据多个ID查询bounding volume
     */
    @CrossOrigin(origins = "*")
    @PostMapping("/ids")
    public ApiResponseMessage<Map<Long, Map<String, Object>>> getBoundingVolumeByIds(@RequestBody List<Long> ids) {
        return tiles3DService.getBoundingVolumeByIds(ids);
    }
    /**
     * 根据多边形查询bounding volume
     */
    @CrossOrigin(origins = "*")
    @PostMapping("/polygon")
    public ApiResponseMessage<Map<Long, Map<String, Object>>> queryByPolygon(@RequestBody Map<String, List<List<Double>>> request) {
        List<List<Double>> polygon = request.get("polygon");
        return tiles3DService.queryByPolygon(polygon);
    }
}
