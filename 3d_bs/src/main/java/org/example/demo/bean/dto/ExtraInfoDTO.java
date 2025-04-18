package org.example.demo.bean.dto;

import lombok.Data;
import java.util.Map;

/**
 * 表示额外信息
 */
@Data
public class ExtraInfoDTO {
    private Map<String, Object> additionalData;
}
