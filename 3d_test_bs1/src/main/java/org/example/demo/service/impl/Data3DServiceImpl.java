package org.example.demo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.minio.MinioClient;
import io.minio.PutObjectOptions;
import org.example.demo.bean.dto.TilesetDTO;
import org.example.demo.bean.entity.Data3D;
import org.example.demo.mapper.Data3DMapper;
import org.example.demo.service.Data3DService;
import org.locationtech.jts.io.WKTReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * 3D 产品数据服务实现类，处理 tileset.json 的解析、存储和 MinIO 上传
 * 适配 MinIO 7.0.1 的 API，支持动态 bucket 配置
 */
@Service
@Slf4j
public class Data3DServiceImpl extends ServiceImpl<Data3DMapper, Data3D> implements Data3DService {

    @Autowired
    private ObjectMapper objectMapper; // 用于 JSON 序列化和反序列化

    @Autowired
    private MinioClient minioClient; // MinIO 客户端，用于文件上传，版本 7.0.1

    @Value("${minio.bucket:3ddata}")
    private String bucketName;//动态配置 MinIO 存储桶名称，默认值 3ddata

    /**
     * 解析指定文件夹中的 tileset.json 文件，并将其数据保存到数据库
     * 该方法会遍历根文件夹下的子文件夹，查找 tileset.json，解析并存储
     * 如果数据已存在，则更新数据和 MinIO 内容
     * @param folderPath 包含 tileset.json 的根文件夹路径
     */
    @Override
    @Transactional
    public void parseAndSaveTileset(String folderPath) {
        try {
            Path rootPath = Paths.get(folderPath);
            // 计算整个文件夹的总大小，单位 GiB（二进制单位，1024^3 字节 = 1 GiB）
            double totalSizeInGiB = calculateTotalSize(rootPath) / 1_073_741_824.0;

            // 遍历根文件夹下的所有子文件夹（深度为 1），排除根文件夹本身
            try (Stream<Path> paths = Files.walk(rootPath, 1)) {
                paths.filter(Files::isDirectory)
                        .filter(path -> !path.equals(rootPath))
                        .forEach(subFolder -> {
                            try {
                                parseAndSaveTilesetForSubFolder(subFolder.toString(), totalSizeInGiB);
                            } catch (Exception e) {
                                log.error("Failed to parse tileset.json in folder: {}, skipping", subFolder, e);
                            }
                        });
            }
        } catch (IOException e) {
            log.error("Error traversing folder: {}, skipping", folderPath, e);
            throw new RuntimeException("Failed to traverse folder", e);
        }
    }

    /**
     * 计算指定文件夹的总大小，单位为字节
     * @param rootPath 文件夹路径
     * @return 总大小（字节）
     * @throws IOException 如果文件访问失败
     */
    private double calculateTotalSize(Path rootPath) throws IOException {
        try (Stream<Path> paths = Files.walk(rootPath)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            log.error("Error calculating size for file: {}, skipping", path, e);
                            return 0;
                        }
                    })
                    .sum();
        }
    }

    /**
     * 解析子文件夹中的 tileset.json 文件，并保存或更新到数据库和 MinIO
     * @param subFolderPath 子文件夹路径
     * @param totalSizeInGiB 文件夹总大小，单位 GiB
     */
    private void parseAndSaveTilesetForSubFolder(String subFolderPath, double totalSizeInGiB) {
        try {
            Path path = Paths.get(subFolderPath);
            String folderName = path.getFileName().toString(); // 子文件夹名作为数据标识（如 HsinchuCity-3DTiles）
            File tilesetFile = findTilesetJsonFile(subFolderPath);

            if (tilesetFile == null) {
                log.warn("No tileset.json found in folder: {}, skipping", subFolderPath);
                return;
            }

            // 检查数据库中是否已存在该数据
            String productKey = folderName + "_" + getTilesetVersion(tilesetFile);
            if (dataExists(productKey)) {
                log.info("Data for folder {} already exists, updating data and MinIO content.", folderName);
                updateExistingData(folderName, tilesetFile, totalSizeInGiB);
                return;
            }

            // 如果数据不存在，则保存新数据
            saveNewData(folderName, tilesetFile, totalSizeInGiB);
        } catch (Exception e) {
            log.error("Error parsing tileset.json in folder: {}, skipping MinIO upload", subFolderPath, e);
            throw new RuntimeException("Failed to parse or save/update tileset data", e);
        }
    }

    /**
     * 获取 tileset.json 的版本号
     * @param tilesetFile tileset.json 文件
     * @return 版本号字符串
     * @throws IOException 如果解析失败
     */
    private String getTilesetVersion(File tilesetFile) throws IOException {
        TilesetDTO tileset = objectMapper.readValue(tilesetFile, TilesetDTO.class);
        return tileset.getAsset().getVersion();
    }

    /**
     * 保存新数据到数据库和 MinIO
     * @param folderName 子文件夹名
     * @param tilesetFile tileset.json 文件
     * @param totalSizeInGiB 文件夹总大小，单位 GiB
     */
    private void saveNewData(String folderName, File tilesetFile, double totalSizeInGiB) {
        try {
            // 预计算层级和瓦片信息
            Map<Double, Integer> levelMap = preCalculateLevelMap(tilesetFile.getParent());
            List<TileInfo> tileInfos = preCalculateTileInfos(tilesetFile.getParent(), levelMap);

            // 解析 tileset.json
            TilesetDTO tileset = objectMapper.readValue(tilesetFile, TilesetDTO.class);
            Data3D data3D = new Data3D();

            // 设置属性
            data3D.setDataIdentification(folderName);
            data3D.setProductKey(folderName + "_" + tileset.getAsset().getVersion());
            data3D.setCoordinateSystem("EPSG:4978");
            data3D.setTileSetSize(totalSizeInGiB);

            // 生成 tilesId
            File glbFile = findDefaultGlbFile(tilesetFile.getParent());
            if (glbFile == null) {
                log.error("No default GLB file found in Data folder for: {}, skipping", tilesetFile.getAbsolutePath());
                return;
            }
            String glbUri = "Data/" + glbFile.getName();
            Long tilesId = generateTilesId(folderName, glbUri);
            data3D.setTilesId(tilesId);

            // 处理 boundingVolume
            TilesetDTO.BoundingVolume boundingVolume = tileset.getRoot().getBoundingVolume();
            if (boundingVolume instanceof TilesetDTO.Sphere) {
                List<Double> sphereCoords = ((TilesetDTO.Sphere) boundingVolume).getSphere();
                data3D.setBoundingBox(new WKTReader().read("POINT Z (" + sphereCoords.get(0) + " " +
                        sphereCoords.get(1) + " " + sphereCoords.get(2) + ")"));
            } else {
                log.warn("Unsupported boundingVolume type: {}, skipping", boundingVolume.getClass().getName());
            }
            //设置半径
            data3D.setRadius(((TilesetDTO.Sphere) boundingVolume).getSphere().get(3));
            // 设置层级范围
            int minLevel = 0;
            int maxLevel = tileInfos.stream()
                    .mapToInt(ti -> levelMap.getOrDefault(ti.geometricError, 0))
                    .max()
                    .orElse(0);
            data3D.setTileLevelMin(minLevel);
            data3D.setTileLevelMax(maxLevel);

            // 序列化 extraInfo
            data3D.setExtraInfoJson(objectMapper.writeValueAsString(tileset.getAsset()));

            // 上传到 MinIO
            String tilesetPath = uploadToMinio(tilesetFile, folderName);
            data3D.setPath(tilesetPath);


            // 保存到数据库
            boolean saved = this.save(data3D);
            if (saved) {
                log.info("Tileset data saved successfully and uploaded to MinIO for folder: {}, path: {}, minLevel: {}, maxLevel: {}",
                        folderName, tilesetPath, minLevel, maxLevel);
            } else {
                log.error("Failed to save new tileset data to database for folder: {}, skipping", folderName);
                throw new RuntimeException("Failed to save new tileset data to database");
            }
        } catch (Exception e) {
            log.error("Error saving new tileset data for folder: {}, skipping", folderName, e);
            throw new RuntimeException("Failed to save new tileset data", e);
        }
    }

    /**
     * 更新已存在的数据，包括数据库记录和 MinIO 内容
     * @param folderName 子文件夹名
     * @param tilesetFile tileset.json 文件
     * @param totalSizeInGiB 文件夹总大小，单位 GiB
     */
    private void updateExistingData(String folderName, File tilesetFile, double totalSizeInGiB) {
        try {
            // 预计算层级和瓦片信息
            Map<Double, Integer> levelMap = preCalculateLevelMap(tilesetFile.getParent());
            List<TileInfo> tileInfos = preCalculateTileInfos(tilesetFile.getParent(), levelMap);

            // 解析 tileset.json
            TilesetDTO tileset = objectMapper.readValue(tilesetFile, TilesetDTO.class);
            String productKey = folderName + "_" + tileset.getAsset().getVersion();

            // 创建更新条件
            UpdateWrapper<Data3D> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("product_key", productKey);

            // 准备更新数据
            Data3D data3D = new Data3D();
            data3D.setDataIdentification(folderName);
            data3D.setProductKey(productKey);
            data3D.setCoordinateSystem("EPSG:4978");
            data3D.setTileSetSize(totalSizeInGiB);

            // 生成 tilesId
            File glbFile = findDefaultGlbFile(tilesetFile.getParent());
            if (glbFile == null) {
                log.error("No default GLB file found in Data folder for: {}, skipping update", tilesetFile.getAbsolutePath());
                return;
            }
            String glbUri = "Data/" + glbFile.getName();
            Long tilesId = generateTilesId(folderName, glbUri);
            data3D.setTilesId(tilesId);

            // 处理 boundingVolume
            TilesetDTO.BoundingVolume boundingVolume = tileset.getRoot().getBoundingVolume();
            if (boundingVolume instanceof TilesetDTO.Sphere) {
                List<Double> sphereCoords = ((TilesetDTO.Sphere) boundingVolume).getSphere();
                data3D.setBoundingBox(new WKTReader().read("POINT Z (" + sphereCoords.get(0) + " " +
                        sphereCoords.get(1) + " " + sphereCoords.get(2) + ")"));
            } else {
                log.warn("Unsupported boundingVolume type: {}, skipping update", boundingVolume.getClass().getName());
            }
            //设置半径
            data3D.setRadius(((TilesetDTO.Sphere) boundingVolume).getSphere().get(3));
            // 设置层级范围
            int minLevel = 0;
            int maxLevel = tileInfos.stream()
                    .mapToInt(ti -> levelMap.getOrDefault(ti.geometricError, 0))
                    .max()
                    .orElse(0);
            data3D.setTileLevelMin(minLevel);
            data3D.setTileLevelMax(maxLevel);

            // 序列化 extraInfo
            data3D.setExtraInfoJson(objectMapper.writeValueAsString(tileset.getAsset()));

            // 上传或更新到 MinIO
            String tilesetPath = uploadToMinio(tilesetFile, folderName);
            data3D.setPath(tilesetPath);

            // 更新数据库
            boolean updated = this.update(data3D, updateWrapper);
            if (updated) {
                log.info("Updated tileset data and MinIO content for folder: {}, path: {}, minLevel: {}, maxLevel: {}",
                        folderName, tilesetPath, minLevel, maxLevel);
            } else {
                log.error("Failed to update tileset data for folder: {}, skipping", folderName);
                throw new RuntimeException("Failed to update tileset data");
            }
        } catch (Exception e) {
            log.error("Error updating tileset data for folder: {}, skipping", folderName, e);
            throw new RuntimeException("Failed to update tileset data", e);
        }
    }

    /**
     * 预计算层级表，基于 geometricError 分配层级
     */
    private Map<Double, Integer> preCalculateLevelMap(String folderPath) {
        Map<String, Double> fileErrors = new HashMap<>();
        Path rootPath = Paths.get(folderPath);
        try (Stream<Path> paths = Files.walk(rootPath, Integer.MAX_VALUE)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals("tileset.json"))
                    .forEach(jsonPath -> {
                        try {
                            TilesetDTO tileset = objectMapper.readValue(jsonPath.toFile(), TilesetDTO.class);
                            double error = tileset.getRoot().getGeometricError();
                            fileErrors.put(jsonPath.toString(), error);
                        } catch (Exception e) {
                            log.error("Failed to read JSON file for level map: {}, skipping", jsonPath, e);
                        }
                    });
        } catch (Exception e) {
            log.error("Error pre-calculating level map: {}, skipping", folderPath, e);
        }

        TreeSet<Double> sortedErrors = new TreeSet<>((a, b) -> Double.compare(b, a));
        sortedErrors.addAll(fileErrors.values());

        Map<Double, Integer> errorToLevel = new HashMap<>();
        int level = 0;
        for (Double error : sortedErrors) {
            errorToLevel.put(error, level++);
        }
        return errorToLevel;
    }

    /**
     * 预计算瓦片信息，包括 geometricError 和层级
     */
    private List<TileInfo> preCalculateTileInfos(String folderPath, Map<Double, Integer> levelMap) {
        List<TileInfo> tileInfos = new ArrayList<>();
        Path rootPath = Paths.get(folderPath);
        try (Stream<Path> paths = Files.walk(rootPath, Integer.MAX_VALUE)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals("tileset.json"))
                    .forEach(jsonPath -> {
                        try {
                            String productName = jsonPath.getParent().getParent().getFileName().toString();
                            TilesetDTO tileset = objectMapper.readValue(jsonPath.toFile(), TilesetDTO.class);
                            double geometricError = tileset.getRoot().getGeometricError();
                            tileInfos.add(new TileInfo(jsonPath.toFile(), null, productName, null, null, geometricError));
                        } catch (Exception e) {
                            log.error("Failed to process JSON file for tile info: {}, skipping", jsonPath, e);
                        }
                    });
        } catch (Exception e) {
            log.error("Error pre-calculating tile infos: {}, skipping", folderPath, e);
        }
        return tileInfos;
    }

    /**
     * 瓦片信息类
     */
    private static class TileInfo {
        File jsonFile;
        File glbFile;
        String productName;
        Long tileId;
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
     * 检查数据库中是否已存在指定 productKey 的数据
     */
    private boolean dataExists(String productKey) {
        QueryWrapper<Data3D> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("product_key", productKey);
        return this.count(queryWrapper) > 0;
    }

    /**
     * 在指定文件夹中查找 tileset.json 文件
     */
    private File findTilesetJsonFile(String folderPath) {
        File folder = new File(folderPath);
        if (folder.isDirectory()) {
            File[] files = folder.listFiles((dir, name) -> name.equalsIgnoreCase("tileset.json"));
            if (files != null && files.length > 0) {
                return files[0];
            }
        }
        return null;
    }

    /**
     * 将 tileset.json 文件上传到 MinIO 并返回存储路径
     */
    private String uploadToMinio(File tilesetFile, String folderName) {
        String objectName = folderName + "/" + tilesetFile.getName();
        String minioUrl = "http://localhost:9005/" + bucketName + "/" + objectName;

        try {
            if (!minioClient.bucketExists(bucketName)) {
                minioClient.makeBucket(bucketName);
                log.info("Bucket {} created in MinIO", bucketName);
            }

            PutObjectOptions options = new PutObjectOptions(tilesetFile.length(), -1);
            options.setContentType("application/json");

            minioClient.putObject(bucketName, objectName, tilesetFile.getAbsolutePath(), options);
            log.info("File {} uploaded to MinIO, path: {}", objectName, minioUrl);
        } catch (Exception e) {
            log.error("Error uploading file to MinIO: {}, skipping", e.getMessage());
            throw new RuntimeException("Failed to upload file to MinIO", e);
        }
        return minioUrl;
    }

    /**
     * 查找 Data 文件夹中的默认 GLB 文件
     */
    private File findDefaultGlbFile(String parentPath) {
        File dataFolder = new File(parentPath, "Data");
        if (dataFolder.exists() && dataFolder.isDirectory()) {
            File[] glbFiles = dataFolder.listFiles((dir, name) -> name.endsWith(".glb"));
            if (glbFiles != null && glbFiles.length > 0) {
                return glbFiles[0];
            }
        }
        return null;
    }

    /**
     * 生成 tilesId，通过哈希值与文件名完整数字拼接
     */
    private Long generateTilesId(String productName, String glbUri) {
        int productHash = Math.abs(productName.hashCode()) % 10000;
        String glbNumberStr = glbUri.replace(".glb", "").replace("Data/", "").replace("_", "");
        String idStr = productHash + glbNumberStr;
        return Long.parseLong(idStr);
    }
    /**
     * 根据名称获取 tileset.json 的存储路径
     * @param name 数据名称（如 HsinchuCity-3DTiles）
     * @return 路径信息
     * @throws Exception 如果数据集不存在
     */
    @Override
    public Map<String, String> getTilesetPathByName(String name) throws Exception {
        QueryWrapper<Data3D> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("\"3ddata_identification\"", name);// 使用数据库实际字段名
        Data3D data3D = this.getOne(queryWrapper);
        if (data3D == null) {
            throw new Exception("Dataset not found: " + name);
        }
        Map<String, String> result = new HashMap<>();
        log.info("Successfully retrieved tileset path for name: {}, path: {}", name, data3D.getPath());
        result.put("path", data3D.getPath());
        return result;
    }

}