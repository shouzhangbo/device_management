---
name: MQ 消息队列开发规范
version: 1.0.0
description: 跨语言 MQ 消息队列开发规范，覆盖开发规范（选型/Topic 命名/消息体设计）、Producer 规范（发送可靠性/失败处理）、Consumer 规范（幂等/顺序/耗时/重试/死信）、消息落地表 SQL 规范与索引设计（消息记录表/幂等表/死信表 DDL 与索引）、事务消息与顺序消息、日志与监控。适配 Kafka / RocketMQ / RabbitMQ。为 AI Agent 生成 MQ 相关代码提供正反例对照。约束等级：【强制】（必须遵守）/【推荐】（尽量遵守）。
---

# MQ 消息队列开发规范

## 前言

本规范约束 AI Agent 生成的 MQ 消息代码质量，适配 Kafka / RocketMQ / RabbitMQ，并给出 Java / Python / Node.js 的常用客户端示例。核心覆盖：**开发规范、Producer/Consumer 可靠性、SQL 规范（消息落地表）、索引设计（消息表/幂等表/死信表）、事务与顺序消息**。

约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。


## 一、MQ 开发规范

### 1.1 选型与使用原则

| 中间件 | 适用场景 | 说明 |
|--------|---------|------|
| Kafka | 大数据量、日志/埋点、流处理、顺序性 | 吞吐高，消息可回溯 |
| RocketMQ | 业务消息、事务消息、可靠投递 | 中文生态、事务消息成熟 |
| RabbitMQ | 灵活路由、延迟队列、低吞吐业务 | 功能丰富、协议多 |

**【强制】选型必须依据技术设计文档约定，禁止在代码中自行更换 MQ 中间件。**

### 1.2 Topic / 队列命名规范

**【强制】Topic 命名格式：`业务域.模块.事件类型`，全小写，点号分隔；命名必须能表达业务语义，禁止无意义命名。**

**正例（Java）：**
```java
// ✅ 正确：Topic 命名
public static final String ORDER_CREATED = "order.order.created";
public static final String ORDER_PAID    = "order.order.paid";
public static final String USER_REGISTERED = "user.user.registered";
```

**反例：**
```java
// ❌ 错误：无业务语义
public static final String TOPIC_1 = "topic1";
```

### 1.3 消息体与消息头设计

**【强制】消息体必须包含：消息 ID（幂等键）、业务 ID、事件时间、业务数据（结构化 JSON）；禁止在消息体中放入敏感明文（密码/银行卡号/Token）。**

**正例（JSON）：**
```json
{
  "msg_id": "uuid-xxx",
  "biz_id": "ORD-20260701-0001",
  "event_time": "2026-07-01T10:00:00Z",
  "type": "ORDER_CREATED",
  "data": { "order_no": "ORD-20260701-0001", "amount": 199.90 }
}
```

**【强制】消息体大小必须控制（建议 < 256KB），大对象不得整体发消息，应只发业务 ID 由消费端查询。**


## 二、Producer 生产规范

### 2.1 发送可靠性

**【强制】业务消息发送必须确认成功，禁止"发了就不管"；发送失败必须重试或降级（落库 + 定时补偿），禁止静默丢弃。**

**反例（Java）：**
```java
// ❌ 错误：发送失败无处理
kafkaTemplate.send(ORDER_CREATED, message);
```

**正例（Java）：**
```java
// ✅ 正确：确认 + 失败落库补偿
@Slf4j
@RequiredArgsConstructor
public class OrderProducer {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MessageRecordRepository messageRecordRepository;

    public void sendOrderCreated(Order order) {
        String msgId = UUID.randomUUID().toString();
        // 先落库（待发送状态），发送成功再标记已发送
        messageRecordRepository.insert(buildRecord(msgId, ORDER_CREATED, order));
        try {
            kafkaTemplate.send(ORDER_CREATED, msgId, JSON.toJSONString(order))
                .addCallback(
                    result -> {
                        log.info("消息发送成功, msgId={}, topic={}", msgId, ORDER_CREATED);
                        messageRecordRepository.markSent(msgId);
                    },
                    ex -> log.error("消息发送失败, msgId={}, topic={}", msgId, ORDER_CREATED, ex));
        } catch (Exception e) {
            log.error("消息发送异常, msgId={}", msgId, e);
            // 落库补偿任务会定时重发"待发送"状态的消息
        }
    }
}
```

### 2.2 Producer 禁止行为

**【强制】禁止在事务提交前发送消息（事务回滚后消息已发，消费端拿到脏数据）；必须在事务提交后（`TransactionSynchronization.afterCommit`）再发送。**

**【强制】禁止在热点循环中同步发消息；批量场景使用批量发送，控制单批大小。**

**【推荐】要求严格顺序的业务（如下单后必须按序处理）使用分区键（业务 ID hash）保证同一分区有序，禁止依赖随机分区。**


## 三、Consumer 消费规范

### 3.1 消费幂等

**【强制】Consumer 必须实现幂等消费，防止消息重复投递导致重复处理（重复扣款、重复入库）。幂等键优先使用业务唯一键 + 唯一索引/Redis SetNX。**

**正例（Java + Redis）：**
```java
@Slf4j
@RequiredArgsConstructor
public class OrderPaidConsumer {
    private final OrderService orderService;
    private final StringRedisTemplate redisTemplate;

    @KafkaListener(topics = "order.order.paid", groupId = "order-service")
    public void handle(ConsumerRecord<String, String> record) {
        String msgId = record.key();
        String dedupKey = "mq:dedup:" + msgId;
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", Duration.ofHours(24));
        if (Boolean.FALSE.equals(isNew)) {
            log.info("消息重复消费, 跳过, msgId={}", msgId);
            return;
        }
        try {
            orderService.markOrderPaid(record.value());
            log.info("订单已支付处理完成, msgId={}", msgId);
        } catch (Exception e) {
            redisTemplate.delete(dedupKey);  // 处理失败清除幂等标记，允许重试
            log.error("订单已支付处理失败, msgId={}, offset={}", msgId, record.offset(), e);
            throw e;
        }
    }
}
```

**正例（Python + 唯一索引幂等）：**
```python
def consume_order_created(message):
    msg_id = message["msg_id"]
    try:
        insert_processed_record(msg_id, order_no=message["biz_id"])  # 唯一索引防重
        order_service.create_from_message(message["data"])
    except IntegrityError:
        logger.info("消息重复消费, 跳过, msg_id=%s", msg_id)
```

### 3.2 消费耗时与并发

**【强制】Consumer 中禁止执行长耗时操作（大量 DB 写入、外部 HTTP 长调用、`Thread.sleep`），耗时操作必须异步化，防止消费超时触发 Rebalance 或堆积。**

**反例：**
```java
// ❌ 错误：消费内同步执行耗时操作
@KafkaListener(topics = "order.order.created")
public void handle(Order order) {
    Thread.sleep(30000);                 // 阻塞消费线程
    externalApi.batchCall(order.items);  // 外部慢调用
}
```

**正例：**
```java
// ✅ 正确：消费只做轻量落库，耗时操作丢线程池
@KafkaListener(topics = "order.order.created")
public void handle(Order order) {
    orderService.markReceived(order.getOrderNo());
    asyncExecutor.execute(() -> orderService.processAsync(order));
}
```

**【推荐】消费端必须设置并发数与消费速率控制，防止瞬时洪峰压垮下游；消费失败按重试策略退避重试。**

### 3.3 重试与死信

**【强制】消费失败必须进入重试机制（有限次退避重试），超过最大次数进入死信队列/死信表，禁止无限重试或静默丢弃。**

**正例（Java）：**
```java
// ✅ 正确：有限重试 + 失败落死信表
try {
    process(record);
} catch (Exception e) {
    int retry = incrRetry(msgId);        // Redis 计数
    if (retry >= MAX_RETRY) {
        deadLetterRepository.insert(record.value(), e.getMessage());  // 死信落库
        log.error("消息进入死信, msgId={}, 原因={}", msgId, e.getMessage());
        return;
    }
    log.warn("消息消费失败, 准备重试, msgId={}, 第{}次", msgId, retry);
    throw e;  // 交给 MQ 重试（或延迟队列）
}
```

**【强制】死信必须可查询、可重放：死信表记录消息体、消费失败原因、原始 Offset/Topic，并提供管理入口。**

### 3.4 顺序消费

**【推荐】严格顺序业务使用分区顺序消费：Producer 按业务键选择分区，Consumer 单分区单线程处理；非必须顺序的消息不要用顺序消费（降低吞吐）。**


## 四、消息落地表 SQL 规范（SQL 规范）

> MQ 通常需要三类落地表：**消息发送记录表（补偿重发）、消费幂等表（防重）、死信表（失败归档）**。建表必须遵循统一规范。

### 4.1 消息发送记录表（生产端补偿）

```sql
CREATE TABLE `mq_message_record` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `msg_id`       VARCHAR(64)   NOT NULL COMMENT '消息唯一 ID（幂等/追踪）',
  `biz_type`     VARCHAR(64)   NOT NULL COMMENT '业务类型，如 order/user',
  `topic`        VARCHAR(128)  NOT NULL COMMENT 'Topic 名称',
  `content`      TEXT          NOT NULL COMMENT '消息体（JSON）',
  `status`       TINYINT       NOT NULL DEFAULT 0 COMMENT '0-待发送 1-已发送 2-失败 3-已进入死信',
  `retry_count`  INT           NOT NULL DEFAULT 0 COMMENT '已重试次数',
  `created_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `sent_at`      DATETIME      NULL COMMENT '发送成功时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_id` (`msg_id`)                     COMMENT '消息唯一（幂等发送）',
  KEY `idx_status_created` (`status`, `created_at`)     COMMENT '补偿扫描待发送/失败消息'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MQ 消息发送记录表';
```

### 4.2 消费幂等表（防重）

```sql
CREATE TABLE `mq_consume_record` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `msg_id`      VARCHAR(64)  NOT NULL COMMENT '消息 ID',
  `biz_type`    VARCHAR(64)  NOT NULL COMMENT '业务类型',
  `biz_id`      VARCHAR(64)  NOT NULL COMMENT '业务 ID（如订单号）',
  `consume_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消费时间',
  `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '1-成功 2-失败',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_id` (`msg_id`)          COMMENT '消息级幂等（最强）',
  KEY `idx_biz` (`biz_type`, `biz_id`)       COMMENT '按业务维度查询/对账'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MQ 消费幂等记录表';
```

### 4.3 死信表（失败归档）

```sql
CREATE TABLE `mq_dead_letter` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `msg_id`       VARCHAR(64)   NOT NULL COMMENT '消息 ID',
  `topic`        VARCHAR(128)  NOT NULL COMMENT '原始 Topic',
  `consumer_group` VARCHAR(128) NOT NULL COMMENT '消费组',
  `content`      TEXT          NOT NULL COMMENT '消息体（JSON）',
  `fail_reason`  VARCHAR(1000) NOT NULL COMMENT '失败原因',
  `retry_count`  INT           NOT NULL DEFAULT 0 COMMENT '重试次数',
  `status`       TINYINT       NOT NULL DEFAULT 0 COMMENT '0-待处理 1-已重放 2-已丢弃',
  `created_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '进入死信时间',
  `updated_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_created` (`status`, `created_at`)  COMMENT '待处理死信扫描/告警',
  KEY `idx_msg_id` (`msg_id`)                        COMMENT '按消息定位'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MQ 死信表';
```

**SQL 规范要点：**
- 【强制】表必须有表注释；每列必须有列注释
- 【强制】主键使用自增 `BIGINT UNSIGNED`，禁止 UUID 主键（影响索引性能）
- 【强制】幂等字段必须建 **唯一索引**（`uk_msg_id`），这是防重的硬保证
- 【强制】状态扫描列必须建组合索引 `(status, created_at)`，禁止 `status` 单独无索引扫描

### 4.4 索引设计要求

| 表 | 索引 | 用途 | 等级 |
|----|------|------|------|
| 发送记录表 | `uk_msg_id` | 幂等发送、消息追踪 | 【强制】 |
| 发送记录表 | `idx_status_created` | 补偿任务扫描待发送/失败 | 【强制】 |
| 消费幂等表 | `uk_msg_id` | 消费防重 | 【强制】 |
| 消费幂等表 | `idx_biz` | 按业务对账 | 【推荐】 |
| 死信表 | `idx_status_created` | 死信扫描与告警 | 【强制】 |
| 死信表 | `idx_msg_id` | 消息定位/重放 | 【推荐】 |

**索引规范：**
- 联合索引遵循最左前缀原则，区分度高的列放前面
- 索引列长度必须约束（如 `msg_id` 用 VARCHAR(64)），避免超长索引
- 禁止在无索引列上做范围扫描条件（如 `WHERE content LIKE '%x%'`）


## 五、事务消息与顺序规范

### 5.1 本地事务表方案（推荐）

**【强制】需要"DB 与消息"强一致时，使用本地事务表方案：业务写操作与消息记录**同库同事务**写入，再由补偿任务扫描"待发送"消息发送。禁止用不可靠的"发消息放在业务事务里"。**

**正例（Java）：**
```java
@Transactional
public void createOrder(Order order) {
    orderRepository.insert(order);                       // 业务写
    messageRecordRepository.insert(待发送的订单创建消息);  // 同一事务写入消息表
    // 事务提交后，由补偿任务把"待发送"消息真正发出
}
```

**反例：**
```java
// ❌ 错误：事务内直接发消息，回滚后消息已发
@Transactional
public void createOrder(Order order) {
    orderRepository.insert(order);
    kafkaTemplate.send(ORDER_CREATED, order);  // 若后面 SQL 回滚，消息已发出
}
```

### 5.2 顺序消息

**【推荐】必须保序的业务（如"创建→支付→发货"状态流转）使用分区顺序消息，分区键 = 业务 ID；禁止把不同业务的顺序消息混在同一分区无节制堆积。**


## 六、日志与监控规范

**【强制】消息发送、消费成功/失败、幂等命中/跳过、进入死信等关键节点必须打印日志，含 msgId、topic、groupId、耗时；禁止打印消息体中的敏感明文。**

**正例（Java）：**
```java
log.info("消息消费开始, msgId={}, topic={}, groupId={}", msgId, topic, groupId);
log.info("消息消费成功, msgId={}, 耗时={}ms", msgId, costMs);
log.error("消息消费失败, msgId={}, 原因={}", msgId, e.getMessage(), e);
```

**【推荐】对消费延迟（Lag）、消费失败率、死信数量设置监控指标与告警。**


## 七、快速检查清单

- [ ] Topic 命名是否符合 `业务域.模块.事件类型`？
- [ ] 消息体是否含 msg_id/biz_id/事件时间，且无敏感明文？
- [ ] Producer 是否确认发送成功、失败是否落库补偿？
- [ ] 是否在事务提交后才发消息？
- [ ] Consumer 是否实现幂等（Redis SetNX 或唯一索引）？
- [ ] 消费中是否有长耗时操作？是否异步化？
- [ ] 消费失败是否有限次重试 + 死信归档？
- [ ] 消息发送记录表/幂等表/死信表是否按规范建表？
- [ ] 幂等字段是否建了唯一索引？
- [ ] 状态扫描是否建了 `(status, created_at)` 组合索引？
- [ ] 本地事务表方案是否"业务写 + 消息记录"同事务？
- [ ] 关键节点是否打印日志且避免敏感信息？
