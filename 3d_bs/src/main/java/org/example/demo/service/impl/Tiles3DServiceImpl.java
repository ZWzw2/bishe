package org.example.demo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import io.minio.PutObjectOptions;
import lombok.extern.slf4j.Slf4j;
import org.example.demo.bean.dto.TilesetDTO;
import org.example.demo.bean.entity.Tiles3D;
import org.example.demo.bean.message.ApiResponseMessage;
import org.example.demo.mapper.Tiles3DMapper;
import org.example.demo.service.Tiles3DService;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.activation.MimetypesFileTypeMap;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 3D Tiles 瓦片服务实现类，负责解析和保存瓦片文件到数据库和 MinIO
 */
@Service
@Slf4j
public class Tiles3DServiceImpl extends ServiceImpl<Tiles3DMapper, Tiles3D> implements Tiles3DService {

    @Autowired
    private ObjectMapper objectMapper; // JSON 解析器

    @Autowired
    private Tiles3DMapper tiles3DMapper; // 数据访问层

    @Autowired
    private MinioClient minioClient; // MinIO 客户端

    @Value("${minio.bucket:3ddata}")
    private String bucketName; // 动态配置 MinIO 存储桶名称，默认值 3ddata

    @Value("${minio.endpoint:http://localhost:9005}")
    private String minioEndpoint;// MinIO 端点

    private final Set<String> processedFiles = new HashSet<>(); // 已处理文件路径集合
    private volatile boolean bucketExistsChecked = false; // MinIO 存储桶是否存在检查标志

    /**
     * 启动异步保存所有瓦片文件到 SQL 和 MinIO
     * @param folderPath 根文件夹路径（如 E:/BaiduNetdiskDownload）
     * @return 任务启动确认消息
     */
    @Override
    public String parseAndSaveTileset(String folderPath) {
        processTilesAsync(folderPath); // 异步处理瓦片保存
        return "Task started asynchronously for folder: " + folderPath; // 立即返回，不等待完成
    }

    /**
     * 异步处理瓦片保存任务，逐条插入所有节点，并发上传 Data 文件夹到 MinIO
     * @param folderPath 根文件夹路径
     */
    @Async
    @Transactional(rollbackFor = Exception.class, timeout = 1800) // 30 分钟超时
    public void processTilesAsync(String folderPath) {
        processedFiles.clear(); // 清空已处理文件记录

        Path rootPath = Paths.get(folderPath);
        try (Stream<Path> paths = Files.walk(rootPath, 1)) {
            paths.filter(Files::isDirectory)
                    .filter(path -> !path.equals(rootPath))
                    .forEach(subFolder -> {
                        String productName = subFolder.getFileName().toString();
                        File tilesetFile = new File(subFolder.toString(), "tileset.json");
                        if (tilesetFile.exists()) {
                            // 从 tileset.json 开始预计算
                            Map<Double, Integer> levelMap = new HashMap<>();
                            Map<Long, Long> parentMap = new HashMap<>();
                            List<TileInfo> tileInfos = new ArrayList<>();
                            preCalculateFromTileset(tilesetFile, productName, levelMap, parentMap, tileInfos, null);

                            // 计算层级
                            TreeSet<Double> sortedErrors = new TreeSet<>((a, b) -> Double.compare(b, a));
                            tileInfos.forEach(ti -> sortedErrors.add(ti.geometricError));
                            int level = 0;
                            for (Double error : sortedErrors) {
                                levelMap.put(error, level++);
                            }
                            tileInfos.forEach(ti -> ti.level = levelMap.get(ti.geometricError));

                            // 按层级保存
                            log.info("Starting SQL insertion for product: {}, total tiles: {}", productName, tileInfos.size());
                            tileInfos.sort(Comparator.comparingInt(ti -> ti.level));
                            for (TileInfo tileInfo : tileInfos) {
                                processTileInfo(tileInfo, levelMap, parentMap);
                            }
                            log.info("Completed SQL insertion for product: {}, verifying data before MinIO upload", productName);
                            // 验证 SQL 插入是否完成
                            long count = tiles3DMapper.selectCount(null);
                            if (count > 0) {
                                log.info("Verified {} records in database, starting MinIO upload", count);
                                uploadDataFolder(subFolder.toFile(), productName);
                                log.info("Completed MinIO upload for product: {}", productName);
                            } else {
                                log.error("No records inserted into database for product: {}, skipping MinIO upload", productName);
                                throw new RuntimeException("No records inserted, check SQL insertion logic");
                            }
                        } else {
                            log.warn("No tileset.json found for product: {}, skipping", productName);
                        }
                    });
        } catch (Exception e) {
            log.error("Error processing tileset from folder: {}, rolling back transaction", folderPath, e);
            throw new RuntimeException("Failed to process tileset", e);
        }
    }

    /**
     * 瓦片信息类
     */
    private static class TileInfo {
        File jsonFile;
        File glbFile;
        String productName;
        Long tileId;
        int level;
        Long parentId;
        double geometricError;

        TileInfo(File jsonFile, File glbFile, String productName, Long tileId, Long parentId, double geometricError) {
            this.jsonFile = jsonFile;
            this.glbFile = glbFile;
            this.productName = productName;
            this.tileId = tileId;
            this.parentId = parentId;
            this.geometricError = geometricError;
        }
    }

    /**
     * 从 tileset.json 开始递归预计算
     */
    private void preCalculateFromTileset(File jsonFile, String productName, Map<Double, Integer> levelMap, Map<Long, Long> parentMap, List<TileInfo> tileInfos, Long parentId) {
        String jsonFilePath = jsonFile.getAbsolutePath();
        if (processedFiles.contains(jsonFilePath)) return;

        try {
            TilesetDTO tileset = objectMapper.readValue(jsonFile, TilesetDTO.class);
            String glbUri = tileset.getRoot().getUri();
            File glbFile;
            if (glbUri == null || glbUri.trim().isEmpty()) {
                log.warn("No URI found in JSON: {}, searching for default GLB", jsonFilePath);
                glbFile = findDefaultGlbFile(jsonFile.getParent());
                if (glbFile == null) {
                    log.error("No default GLB file found in Data folder for: {}, skipping", jsonFilePath);
                    return;
                }
                glbUri = "Data/" + glbFile.getName();
            } else {
                glbFile = new File(jsonFile.getParent(), glbUri);
                if (!glbFile.exists()) {
                    log.warn("GLB file not found: {}, skipping", glbFile.getAbsolutePath());
                    return;
                }
            }

            String jsonFileName = jsonFile.getName().equals("tileset.json") ? "0.json" : jsonFile.getName();
            Long tileId = generateTilesId(productName, glbUri, jsonFileName);
            double geometricError = tileset.getRoot().getGeometricError();
            tileInfos.add(new TileInfo(jsonFile, glbFile, productName, tileId, parentId, geometricError));
            processedFiles.add(jsonFilePath);

            List<TilesetDTO.Root.Child> children = tileset.getRoot().getChildren();
            if (children != null) {
                for (TilesetDTO.Root.Child child : children) {
                    String childUri = child.getUri();
                    if (childUri != null && childUri.endsWith(".json")) {
                        File childJsonFile = new File(jsonFile.getParent(), childUri);
                        if (childJsonFile.exists()) {
                            preCalculateFromTileset(childJsonFile, productName, levelMap, parentMap, tileInfos, tileId);
                        } else {
                            log.warn("Child JSON file not found: {}, skipping", childJsonFile.getAbsolutePath());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to pre-calculate from JSON: {}, rolling back", jsonFilePath, e);
            throw new RuntimeException("Failed to pre-calculate from tileset", e);
        }
    }

    /**
     * 查找 Data 文件夹中的默认 GLB 文件
     */
    private File findDefaultGlbFile(String parentPath) {
        File dataFolder = new File(parentPath, "Data");
        if (dataFolder.exists() && dataFolder.isDirectory()) {
            File[] glbFiles = dataFolder.listFiles((dir, name) -> name.endsWith(".glb"));
            if (glbFiles != null && glbFiles.length > 0) {
                return glbFiles[0]; // 返回第一个 .glb 文件作为根节点
            }
        }
        return null;
    }

    /**
     * 处理瓦片
     */
    private void processTileInfo(TileInfo tileInfo, Map<Double, Integer> levelMap, Map<Long, Long> parentMap) {
        String jsonFilePath = tileInfo.jsonFile.getAbsolutePath();
        if (tiles3DMapper.selectById(tileInfo.tileId) != null) {
            log.info("Skipping duplicate tile ID: {}", tileInfo.tileId);
            return;
        }

        try {
            Tiles3D tiles3D = new Tiles3D();
            tiles3D.setTileLevel(levelMap.get(tileInfo.geometricError));
            tiles3D.setParentId(tileInfo.parentId);
            String pathTiles = tileInfo.productName + "/Data/" + tileInfo.glbFile.getName();
            tiles3D.setPathTiles(pathTiles);
            tiles3D.setId(tileInfo.tileId);

            TilesetDTO tileset = objectMapper.readValue(tileInfo.jsonFile, TilesetDTO.class);
            TilesetDTO.BoundingVolume boundingVolume = tileset.getRoot().getBoundingVolume();
            if (boundingVolume instanceof TilesetDTO.Sphere) {
                List<Double> sphereCoords = ((TilesetDTO.Sphere) boundingVolume).getSphere();
                tiles3D.setBoundingVolume(new WKTReader().read("POINT Z (" + sphereCoords.get(0) + " " +
                        sphereCoords.get(1) + " " + sphereCoords.get(2) + ")"));
            }
            // 设置半径
            tiles3D.setRadius(((TilesetDTO.Sphere) boundingVolume).getSphere().get(3));
            tiles3D.setGeometricError(tileInfo.geometricError);
            tiles3D.setRefine(tileset.getRoot().getRefine());
            double dataSize = calculateTileSize(tileInfo.glbFile, tileInfo.jsonFile);
            tiles3D.setDataSize(dataSize);

            // 逐条保存到 SQL
            tiles3DMapper.insert(tiles3D);
            log.info("Saved tile with ID: {} at level: {}, parentId: {}, path: {}", tileInfo.tileId, tiles3D.getTileLevel(), tiles3D.getParentId(), pathTiles);

        } catch (Exception e) {
            log.error("Failed to process tile: {}, rolling back", jsonFilePath, e);
            throw new RuntimeException("Failed to process tile info", e);
        }
    }

    /**
     * 生成 tilesId
     */
    private Long generateTilesId(String productName, String glbUri, String jsonFileName) {
        int productHash = Math.abs(productName.hashCode()) % 10000;
        String glbNumberStr = glbUri.replace(".glb", "").replace("Data/", "").replace("_", "");
        String idStr = productHash + glbNumberStr;
        try {
            return Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            log.error("Invalid ID generated for product: {}, glbUri: {}, jsonFileName: {}", productName, glbUri, jsonFileName, e);
            throw new IllegalArgumentException("Invalid ID format: " + idStr, e);
        }
    }

    /**
     * 计算文件大小
     */
    private double calculateTileSize(File glbFile, File jsonFile) {
        double totalSize = 0;
        try {
            if (glbFile.exists()) totalSize += Files.size(glbFile.toPath()) / 1024.0;
            if (jsonFile.exists()) totalSize += Files.size(jsonFile.toPath()) / 1024.0;
        } catch (Exception e) {
            log.error("Error calculating size for: {}, skipping", glbFile.getName(), e);
        }
        return totalSize;
    }

    /**
     * 并发上传 Data 文件夹中的所有文件到 MinIO（仅上传 Data 目录下的文件）
     * @param folder 文件夹对象
     * @param productName 产品名称
     */
    private void uploadDataFolder(File folder, String productName) {
        File dataFolder = new File(folder, "Data");
        if (!dataFolder.exists() || !dataFolder.isDirectory()) {
            log.warn("Data folder not found for product: {}", productName);
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(5); // 减少到 5 线程
        List<File> failedFiles = new ArrayList<>(); // 记录失败文件
        try {
            File[] files = dataFolder.listFiles();
            if (files != null) {
                for (File file : files) {
                    final String filePath = productName + "/Data/" + file.getName(); // 确保所有文件上传到 Data 目录
                    executor.submit(() -> {
                        int retries = 3; // 重试 3 次
                        while (retries > 0) {
                            try {
                                uploadFile(file, bucketName, filePath);
                                log.info("Uploaded file to MinIO: {}/{}", productName, filePath);
                                return; // 上传成功，退出循环
                            } catch (Exception e) {
                                retries--;
                                if (retries > 0) {
                                    log.warn("Retrying upload for file: {}, attempts left: {}", file.getName(), retries, e);
                                    try {
                                        Thread.sleep(1000); // 等待 1 秒后重试
                                    } catch (InterruptedException ie) {
                                        log.error("Sleep interrupted while retrying upload", ie);
                                    }
                                } else {
                                    synchronized (failedFiles) {
                                        failedFiles.add(file);
                                    }
                                    log.error("Failed to upload file after retries: {}, error: {}", file.getName(), e.getMessage());
                                }
                            }
                        }
                    });
                }
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(15, TimeUnit.MINUTES)) { // 延长到 15 分钟
                    log.warn("MinIO upload tasks did not complete within 15 minutes, forcing shutdown");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.error("Error waiting for MinIO uploads to complete", e);
                executor.shutdownNow();
            }
            if (!failedFiles.isEmpty()) {
                log.warn("Failed to upload {} files: {}", failedFiles.size(), failedFiles);
            }
        }
    }

    /**
     * 上传文件到 MinIO
     * @param file 文件对象
     * @param bucketName 存储桶名称
     * @param fileName 目标文件路径
     * @throws Exception 上传可能抛出的异常
     */
    private void uploadFile(File file, String bucketName, String fileName) throws Exception {
        if (null == file || 0 == file.length()) {
            log.error("Upload file cannot be null or empty: {}", file.getName());
            throw new IllegalArgumentException("Upload file cannot be null or empty");
        }

        FileInputStream input = null; // 声明在方法级别，初始化为 null
        try {
            createBucket(bucketName);
            String originalFilename = file.getName();
            input = new FileInputStream(file); // 初始化 input
            PutObjectOptions putObjectOptions = new PutObjectOptions(file.length(), 5 * 1024 * 1024); // 设置多部分上传，5MB 部分大小
            putObjectOptions.setContentType(new MimetypesFileTypeMap().getContentType(file));
            minioClient.putObject(bucketName, fileName, input, putObjectOptions);
            log.info("Uploaded file to MinIO: {}/{}", bucketName, fileName);
        } catch (Exception e) {
            log.error("Error uploading file to MinIO: {}, skipping", file.getName(), e);
            throw new Exception("Failed to upload file: " + file.getName(), e);
        } finally {
            if (input != null) {
                try {
                    input.close(); // 安全关闭输入流
                } catch (IOException e) {
                    log.error("Error closing input stream for file: {}, skipping", file.getName(), e);
                }
            }
        }
    }

    /**
     * 创建 MinIO 存储桶（如果不存在）
     * @param bucketName 存储桶名称
     * @throws Exception 创建可能抛出的异常
     */
    private void createBucket(String bucketName) throws Exception {
        synchronized (this) {
            if (!bucketExistsChecked) {
                if (!minioClient.bucketExists(bucketName)) {
                    minioClient.makeBucket(bucketName);
                    log.info("Bucket {} created in MinIO", bucketName);
                }
                bucketExistsChecked = true;
            }
        }
    }
    /**
     * 通过id获取bounding volume
     * @param id 瓦片ID
     * @return 包含bounding volume和radius的Map
     */
    @Override
    public Map<String, Object> getBoundingVolumeById(Long id) {
        try {
            Tiles3D tile = tiles3DMapper.selectById(id);
            if (tile == null) {
                log.warn("No tile found with ID: {}", id);
                throw new RuntimeException("Tile not found with ID: " + id);
            }

            Map<String, Object> result = new HashMap<>();

            // 检查 boundingVolume 是否为空并记录日志
            Geometry boundingVolume = tile.getBoundingVolume();
            Double geometricError = tile.getGeometricError();
            Integer tileLevel = tile.getTileLevel();
            if (boundingVolume == null) {
                log.warn("Bounding volume is null for tile ID: {}", id);
            } else {
                try {
                    Coordinate coord = boundingVolume.getCoordinate();
                    log.debug("Retrieved bounding volume coordinates for tile ID {}: x={}, y={}, z={}",
                            id, coord.getX(), coord.getY(), coord.getZ());

                    // 构造 sphere 数组：[x, y, z, radius]
                    List<Double> sphereCoords = new ArrayList<>();
                    sphereCoords.add(coord.getX());
                    sphereCoords.add(coord.getY());
                    sphereCoords.add(coord.getZ());
                    sphereCoords.add(tile.getRadius());

                    // 封装成 boundingVolume 对象
                    Map<String, List<Double>> boundingVolumeMap = new HashMap<>();
                    boundingVolumeMap.put("sphere", sphereCoords);
                    result.put("boundingVolume", boundingVolumeMap);
                    // 添加 geometricError 和 tileLevel
                    result.put("geometricError", geometricError);
                    result.put("tileLevel", tileLevel);
                } catch (Exception e) {
                    log.error("Failed to parse bounding volume for tile ID: {}", id, e);
                    throw new RuntimeException("Failed to parse bounding volume: " + e.getMessage());
                }
            }

            log.info("Successfully retrieved data for tile ID: {}", id);
            return result;
        } catch (Exception e) {
            log.error("Error retrieving bounding volume for tile ID: {}", id, e);
            throw new RuntimeException("Failed to get bounding volume: " + e.getMessage());
        }
    }

    /**
     * 通过多个ID获取bounding volume
     */
    @Override
    public ApiResponseMessage<Map<Long, Map<String, Object>>> getBoundingVolumeByIds(List<Long> ids) {
        Map<Long, Map<String, Object>> result = new HashMap<>();

        // 检查输入是否为空
        if (ids == null || ids.isEmpty()) {
            log.warn("Received null or empty ID list");
            return new ApiResponseMessage<>(400, "ID list is null or empty", result);
        }

        try {
            // 批量查询数据库
            List<Tiles3D> tiles = tiles3DMapper.selectBatchIds(ids);
            if (tiles == null || tiles.isEmpty()) {
                log.warn("No tiles found for IDs: {}", ids);
                return new ApiResponseMessage<>(404, "No tiles found for IDs: " + ids, result);
            }

            // 将查询结果转为Map，便于检查未找到的ID
            Map<Long, Tiles3D> tileMap = tiles.stream()
                    .collect(Collectors.toMap(Tiles3D::getId, tile -> tile));

            // 检查未找到的ID
            List<Long> missingIds = ids.stream()
                    .filter(id -> !tileMap.containsKey(id))
                    .collect(Collectors.toList());

            // 处理每个瓦片
            for (Tiles3D tile : tiles) {
                Long id = tile.getId();
                Map<String, Object> tileData = new HashMap<>();

                Geometry boundingVolume = tile.getBoundingVolume();
                Double geometricError = tile.getGeometricError();
                Integer tileLevel = tile.getTileLevel();
                if (boundingVolume == null) {
                    log.warn("Bounding volume is null for tile ID: {}", id);
                } else {
                    try {
                        Coordinate coord = boundingVolume.getCoordinate();
                        log.debug("Retrieved bounding volume coordinates for tile ID {}: x={}, y={}, z={}",
                                id, coord.getX(), coord.getY(), coord.getZ());

                        List<Double> sphereCoords = new ArrayList<>();
                        sphereCoords.add(coord.getX());
                        sphereCoords.add(coord.getY());
                        sphereCoords.add(coord.getZ());
                        sphereCoords.add(tile.getRadius());

                        Map<String, List<Double>> boundingVolumeMap = new HashMap<>();
                        boundingVolumeMap.put("sphere", sphereCoords);
                        tileData.put("boundingVolume", boundingVolumeMap);
                        tileData.put("geometricError", geometricError);
                        tileData.put("tileLevel", tileLevel);
                    } catch (Exception e) {
                        log.error("Failed to parse bounding volume for tile ID: {}", id, e);
                        tileData.put("error", "Failed to parse bounding volume: " + e.getMessage());
                    }
                }

                result.put(id, tileData);
                log.info("Successfully retrieved data for tile ID: {}", id);
            }

            // 设置返回信息
            if (missingIds.isEmpty()) {
                return new ApiResponseMessage<>(200, "All tiles retrieved successfully", result);
            } else {
                log.warn("No tiles found for IDs: {}", missingIds);
                return new ApiResponseMessage<>(206, "Some tiles not found for IDs: " + missingIds, result);
            }
        } catch (Exception e) {
            log.error("Error retrieving bounding volumes for IDs: {}", ids, e);
            return new ApiResponseMessage<>(500, "Failed to get bounding volumes: " + e.getMessage(), result);
        }
    }
    /**
     * 通过多边形查询瓦片
     */
    @Override
    public ApiResponseMessage<Map<Long, Map<String, Object>>> queryByPolygon(List<List<Double>> polygon) {
        Map<Long, Map<String, Object>> result = new HashMap<>();
        String wkt = null;

        // 输入校验
        if (polygon == null || polygon.isEmpty() || polygon.size() < 3) {
            log.warn("Received null, empty, or invalid polygon: {}", polygon);
            return new ApiResponseMessage<>(400, "Polygon is null, empty, or has less than 3 points", result);
        }

        try {
            // 构造 WKT
            StringBuilder wktBuilder = new StringBuilder("POLYGON((");
            for (int i = 0; i < polygon.size(); i++) {
                List<Double> point = polygon.get(i);
                if (point.size() < 2) {
                    log.warn("Invalid point in polygon at index {}: {}", i, point);
                    return new ApiResponseMessage<>(400, "Invalid point in polygon at index " + i, result);
                }
                wktBuilder.append(point.get(0)).append(" ").append(point.get(1));
                if (i < polygon.size() - 1) wktBuilder.append(", ");
            }
            wktBuilder.append(", ").append(polygon.get(0).get(0)).append(" ").append(polygon.get(0).get(1)).append("))");
            wkt = wktBuilder.toString();
            log.info("Constructed WKT: {}", wkt);

            // 执行查询
            log.info("Executing findByPolygon with WKT: {}", wkt);
            List<Tiles3D> tiles = baseMapper.findByPolygon(wkt);
            log.info("Query returned {} tiles", tiles != null ? tiles.size() : 0);

            if (tiles == null || tiles.isEmpty()) {
                log.warn("No tiles found intersecting polygon: {}", wkt);
                return new ApiResponseMessage<>(404, "No tiles found intersecting the polygon", result);
            }

            // 处理结果
            for (Tiles3D tile : tiles) {
                Long id = tile.getId();
                Map<String, Object> tileData = new HashMap<>();

                Geometry boundingVolume = tile.getBoundingVolume();
                Double geometricError = tile.getGeometricError();
                Integer tileLevel = tile.getTileLevel();
                Double radius = tile.getRadius();

                if (boundingVolume == null) {
                    log.warn("Bounding volume is null for tile ID: {}", id);
                    tileData.put("error", "Bounding volume is null");
                } else {
                    try {
                        Coordinate coord = boundingVolume.getCoordinate();
                        log.debug("Coordinates for tile ID {}: x={}, y={}, z={}",
                                id, coord.getX(), coord.getY(), coord.getZ());

                        List<Double> sphereCoords = new ArrayList<>();
                        sphereCoords.add(coord.getX());
                        sphereCoords.add(coord.getY());
                        sphereCoords.add(coord.getZ());
                        sphereCoords.add(radius != null ? radius : 0.0);

                        Map<String, List<Double>> boundingVolumeMap = new HashMap<>();
                        boundingVolumeMap.put("sphere", sphereCoords);
                        tileData.put("boundingVolume", boundingVolumeMap);
                        tileData.put("geometricError", geometricError);
                        tileData.put("tileLevel", tileLevel);
                    } catch (Exception e) {
                        log.error("Failed to parse bounding volume for tile ID: {}", id, e);
                        tileData.put("error", "Failed to parse bounding volume: " + e.getMessage());
                    }
                }

                result.put(id, tileData);
                log.info("Processed tile ID: {}", id);
            }

            return new ApiResponseMessage<>(200, "Tiles retrieved successfully", result);
        } catch (Exception e) {
            log.error("Error querying tiles by polygon: {}", wkt, e);
            e.printStackTrace(); // 确保堆栈打印
            return new ApiResponseMessage<>(500, "Failed to query tiles by polygon: " + e.getMessage(), result);
        }
    }
}