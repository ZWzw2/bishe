package org.example.demo.bean.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class BoundingVolumeDeserializerDTO extends StdDeserializer<TilesetDTO.BoundingVolume> {

    private static final long serialVersionUID = 1L;

    public BoundingVolumeDeserializerDTO() {
        super(TilesetDTO.BoundingVolume.class);
    }

    @Override
    public TilesetDTO.BoundingVolume deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        ObjectCodec codec = p.getCodec();
        JsonNode node = codec.readTree(p);

        if (node.has("box")) {
            TilesetDTO.Box box = new TilesetDTO.Box();
            List<Double> boxValues = parseDoubleList(node.get("box"));
            box.setBox(boxValues);
            return box;
        } else if (node.has("sphere")) {
            TilesetDTO.Sphere sphere = new TilesetDTO.Sphere();
            List<Double> sphereValues = parseDoubleList(node.get("sphere"));
            sphere.setSphere(sphereValues);
            return sphere;
        } else if (node.has("region")) {
            TilesetDTO.Region region = new TilesetDTO.Region();
            List<Double> regionValues = parseDoubleList(node.get("region"));
            region.setRegion(regionValues);
            return region;
        } else {
            throw new IOException("Unknown bounding volume type");
        }
    }

    private List<Double> parseDoubleList(JsonNode arrayNode) {
        List<Double> result = new ArrayList<>();
        if (arrayNode.isArray()) {
            for (JsonNode elementNode : arrayNode) {
                result.add(elementNode.asDouble());
            }
        }
        return result;
    }
}