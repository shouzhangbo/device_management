package com.example.device.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 运营后台进程（EP-003 ~ EP-011）。
 * <p>
 * 启用定时任务不是为了跑 cron 作业，而是让 {@code DimAuthCache} 的周期刷新生效：
 * 后台写操作与死信重放共用同一份授权快照，配置改了却要重启才生效会让"先修配置再重放"这条运维路径失效。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@SpringBootApplication(scanBasePackages = {"com.example.device.core", "com.example.device.admin"})
@ConfigurationPropertiesScan({"com.example.device.core.config", "com.example.device.core.cache",
        "com.example.device.core.query", "com.example.device.admin.config"})
@MapperScan("com.example.device.core.mapper")
@EnableScheduling
public class AdminApplication {

    /**
     * 进程入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
