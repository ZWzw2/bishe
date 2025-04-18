package org.example.demo.bean.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 标准 API 响应包装类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponseMessage<T> {
    private int status;
    private String message;
    private T data;
}
