package org.example.demo.config;

import io.minio.MinioClient;
import io.minio.errors.InvalidEndpointException;
import io.minio.errors.InvalidPortException;
import org.example.demo.bean.properties.MinioProp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    private static final Logger logger = LoggerFactory.getLogger(MinioConfig.class);

    @Autowired
    private MinioProp minioProp;

    @Bean
    public MinioClient minioClient() {
        try {
            // 确保 endpoint 以 http:// 或 https:// 开头
            String endpoint = minioProp.getEndpoint();
            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                throw new InvalidEndpointException("Endpoint must start with http:// or https://", endpoint);
            }

            // 初始化 MinioClient
            MinioClient client = new MinioClient(
                    endpoint,
                    minioProp.getAccessKey(),
                    minioProp.getSecretKey(),
                    false // secure = false 表示使用 HTTP（endpoint: http://127.0.0.1:9005）
            );

            logger.info("MinioClient initialized successfully with endpoint: {}", endpoint);
            return client;
        } catch (InvalidEndpointException | InvalidPortException e) {
            logger.error("Failed to initialize MinioClient due to invalid endpoint or port: {}", minioProp.getEndpoint(), e);
            throw new RuntimeException("Invalid MinIO endpoint or port configuration", e);
        } catch (Exception e) {
            logger.error("Unexpected error initializing MinioClient with endpoint: {}", minioProp.getEndpoint(), e);
            throw new RuntimeException("Failed to initialize MinioClient", e);
        }
    }
}