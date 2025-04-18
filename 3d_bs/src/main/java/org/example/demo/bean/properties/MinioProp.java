package org.example.demo.bean.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import javax.validation.constraints.NotNull;

@Data
@ConfigurationProperties(prefix = "minio")
@Component
public class MinioProp {
    @NotNull(message = "MinIO endpoint cannot be null")
    private String endpoint;

    @NotNull(message = "MinIO accessKey cannot be null")
    private String accessKey;

    @NotNull(message = "MinIO secretKey cannot be null")
    private String secretKey;

    @NotNull(message = "MinIO bucket name cannot be null")
    private String bucketName;
}