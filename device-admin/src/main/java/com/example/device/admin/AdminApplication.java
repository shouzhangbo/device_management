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

    //controller 接口层 定义出入参数  入参  出参
    //service层 业务逻辑 出厂注册 入参 SN=厂家编码+厂家设备sn
    //dao层 操作数据库库 增查改 删
    //mysql 数据库 持久化存储、行存储 设备信息
    //redis 缓存服务取用 减轻数据库压力，将数据库中数据放入redis 5个数据结构 key val
    // string 一句话,json格式的字符串 、hash k-v k-filed v、list 队列 list.add pop、set 不重复的集合、zset 有序 score值
    // 缓存穿透 缓存雪崩 缓存击穿
    // MQ 消息队列 Kafka、RocketMQ 解耦、削峰填谷
    // 解耦  http接口、共享数据  消息投递给MQ


    /**
     * 进程入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
