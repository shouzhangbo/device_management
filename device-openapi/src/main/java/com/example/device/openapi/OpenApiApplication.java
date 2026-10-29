package com.example.device.openapi;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 对外查询进程（EP-001 / EP-002）：只读、验签、限流。
 * <p>
 * 不启用定时任务：对账与归档只属于写侧进程（{@code tech-design} §6.3）。
 * 本进程扫描 core 是为了复用 Mapper、缓存、装配与统一外壳，但没有任何写方法调用。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@SpringBootApplication(scanBasePackages = {"com.example.device.core", "com.example.device.openapi"})
@ConfigurationPropertiesScan({"com.example.device.core.config", "com.example.device.core.cache",
        "com.example.device.core.query"})
@MapperScan("com.example.device.core.mapper")
public class OpenApiApplication {

    /**
     * 进程入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(OpenApiApplication.class, args);
    }
}
