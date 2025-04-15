package org.example.demo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@MapperScan("org.example.demo.mapper")
public class ThreeDTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(ThreeDTestApplication.class, args);
    }
}
