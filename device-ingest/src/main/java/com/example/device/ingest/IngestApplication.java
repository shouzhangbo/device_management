package com.example.device.ingest;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 写侧进程入口：Kafka 消费（EP-006）、缓存对账（EP-007）、归档清理与分区预建（EP-008）。
 * <p>
 * 扫描根包含 {@code com.example.device.core}：写服务、事务入口、缓存与指标都住在 core，
 * 三个进程共用同一份实现，不允许各自复制一份"本地简化版"。
 * </p>
 * <p>
 * 本进程不是 Web 应用（{@code spring.main.web-application-type=none}），只通过 Actuator 独立端口
 * 暴露探针与指标（{@code java_springboot_specification} §Actuator【强制】）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@SpringBootApplication(scanBasePackages = {"com.example.device.core", "com.example.device.ingest"})
@ConfigurationPropertiesScan({"com.example.device.core.config", "com.example.device.core.cache",
        "com.example.device.core.query"})
@MapperScan("com.example.device.core.mapper")
@EnableScheduling
public class IngestApplication {

    /**
     * 进程入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(IngestApplication.class, args);
    }
}
