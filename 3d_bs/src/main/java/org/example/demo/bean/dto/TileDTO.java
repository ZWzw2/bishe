package org.example.demo.bean.dto;

import lombok.Data;

import java.util.List;

/**
 * 表示 tile.json 文件的内容结构
 */
@Data
public class TileDTO {
    private Asset asset;
    private double geometricError;
    private Root root;

    @Data
    public static class Asset {
        private String version; // 版本号
        private String gltfUpAxis; // glTF 坐标轴方向
    }

    @Data
    public static class Root {
        private BoundingVolume boundingVolume; // 根节点的包围体
        private double geometricError; // 几何误差
        private String refine; // 细化方法
        private Content content; // 内容
        private List<Child> children; // 子节点列表

        /**
         * 获取 URI
         * @return content 的 URI，如果不存在则返回 null
         */
        public String getUri() {
            return content != null ? content.getUri() : null;
        }

        @Data
        public static class Content {
            private String uri; // 内容的 URI
        }

        @Data
        public static class Child {
            private BoundingVolume boundingVolume; // 子节点的包围体
            private double geometricError; // 子节点的几何误差
            private Content content; // 子节点的内容
            private List<Child> children; // 子节点的子节点列表

            public String getUri() {
                return content != null ? content.getUri() : null;
            }
        }
    }

    // 定义 BoundingVolume 接口
    public interface BoundingVolume {}

    @Data
    public static class Box implements BoundingVolume {
        private List<Double> box; // 表示包围盒的坐标
    }

    @Data
    public static class Sphere implements BoundingVolume {
        private List<Double> sphere; // 表示球体的中心点和半径
    }

    @Data
    public static class Region implements BoundingVolume {
        private List<Double> region; // 表示地理区域的坐标
    }
}