package org.example.demo.bean.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

import java.util.List;

@Data
public class TilesetDTO {
    private Asset asset;
    private double geometricError;
    private Root root;

    @Data
    public static class Asset {
        private String version;
        private String gltfUpAxis;
    }

    @Data
    public static class Root {
        @JsonDeserialize(using = BoundingVolumeDeserializerDTO.class)
        private BoundingVolume boundingVolume;
        private double geometricError;
        private String refine;
        private Content content;
        private List<Child> children;

        public String getUri() {
            return content != null ? content.getUri() : null;
        }

        @Data
        public static class Content {
            private String uri;
        }

        @Data
        public static class Child {
            @JsonDeserialize(using = BoundingVolumeDeserializerDTO.class)
            private BoundingVolume boundingVolume;
            private double geometricError;
            private Content content;
            private List<Child> children;

            public String getUri() {
                return content != null ? content.getUri() : null;
            }
        }
    }

    public interface BoundingVolume {}

    @Data
    public static class Box implements BoundingVolume {
        private List<Double> box;
    }

    @Data
    public static class Sphere implements BoundingVolume {
        private List<Double> sphere;
    }

    @Data
    public static class Region implements BoundingVolume {
        private List<Double> region;
    }
}