---
name: Python MQ 消息队列开发规范
version: 1.0.0
description: 面向 AI Agent 的 Python MQ 消息队列开发规范。覆盖驱动选择（kafka-python / confluent-kafka-python / aiokafka / confluent-kafka 异步 / rocketmq-python / pika RabbitMQ）、Producer / Consumer 模式、消息可靠性（acks / idempotence / 重试 / 死信）、幂等性（业务唯一键）、消息顺序（partition key）、事务消息、消息落地表 SQL 规范、消息体设计（JSON Schema / Avro / Protobuf）、Consumer Group、Offset 提交策略（自动 / 手动）、Consumer 限流、错误处理与重试隔离、监控指标（lag / commit rate / produce rate）。Python 3.x 默认支持。
---

# Python MQ 消息队列开发规范

## 前言

消息队列（Message Queue）是分布式系统解耦、削峰、异步通信的关键中间件。Python 生态支持 Kafka、RocketMQ、RabbitMQ 等主流 MQ，本文基于主流官方驱动与行业实践，提供统一规范。MQ 通用设计原则参见 `mq_specification.md`（Java 版），本文聚焦 Python 实现细节。

约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。

> Python 3.x 默认支持；本规范不涉及 Python 2.x 兼容代码。

## 一、驱动选择

### 1.1 Kafka

| 驱动 | 类型 | 适用场景 |
|------|------|----------|
| `confluent-kafka` | C 实现，性能最优 | **生产首选**，高吞吐 |
| `aiokafka` | 纯 Python asyncio | 异步 FastAPI 项目 |
| `kafka-python` | 纯 Python | 仅推荐学习与原型 |

**【推荐】同步项目优先 `confluent-kafka`；异步项目优先 `aiokafka`。**

### 1.2 RocketMQ

**【推荐】RocketMQ 使用 `rocketmq-python`（社区版）或阿里云官方 SDK。**

### 1.3 RabbitMQ

**【推荐】RabbitMQ 使用 `aio-pika`（异步）或 `pika`（同步）。**


## 二、Producer 规范

### 2.1 Kafka 异步生产者（confluent-kafka）

**【推荐】使用 `Producer` 异步发送 + 回调函数，禁止在主线程 `producer.produce()` 后立即 `poll()` 阻塞。**

**正例：**
```python
from confluent_kafka import Producer

producer = Producer({
    "bootstrap.servers": settings.KAFKA_BOOTSTRAP_SERVERS,
    "client.id": "order-service-producer",
    "acks": "all",                    # 强制要求所有副本确认
    "enable.idempotence": True,       # 幂等性：避免重复消息
    "retries": 2147483647,            # 配合幂等性的最大重试
    "compression.type": "lz4",
    "linger.ms": 5,
    "batch.num.messages": 1000,
})

def delivery_report(err, msg):
    """发送结果回调。"""
    if err is not None:
        logger.error("Kafka 发送失败: %s", err)
    else:
        logger.info(
            "Kafka 发送成功 topic=%s partition=%s offset=%s",
            msg.topic(), msg.partition(), msg.offset(),
        )

def send_order_event(order_id: str, payload: dict) -> None:
    """发送订单事件。"""
    producer.produce(
        topic="order.events",
        key=order_id.encode("utf-8"),      # key 保证同一订单进同一分区（顺序保证）
        value=json.dumps(payload).encode("utf-8"),
        on_delivery=delivery_report,
    )
    producer.poll(0)  # 非阻塞触发回调

# 应用关闭前必须 flush
producer.flush(timeout=10)
```

### 2.2 RocketMQ Producer

```python
from rocketmq.client import Producer, Message

producer = Producer("order-service-producer")
producer.set_name_server_address(settings.ROCKETMQ_NAME_SERVERS)
producer.start()

def send_order_created(order_id: str, payload: dict) -> None:
    msg = Message("order_created")
    msg.set_keys(order_id)
    msg.set_tags("paid")
    msg.set_body(json.dumps(payload).encode("utf-8"))
    producer.send_sync(msg)  # 同步发送
```

### 2.3 消息体设计

**【强制】消息体必须使用 JSON 或 Avro/Protobuf，禁止明文拼接；必须包含以下字段：**

| 字段 | 必填 | 说明 |
|------|------|------|
| `id` / `event_id` | ✅ | 事件唯一 ID（UUID），用于幂等 |
| `type` | ✅ | 事件类型常量（如 `order.created`） |
| `occurred_at` | ✅ | 事件发生时间（ISO 8601） |
| `source` | ✅ | 来源服务名 |
| `version` | ✅ | 事件 schema 版本号 |
| `data` | ✅ | 业务数据载荷 |

**正例：**
```json
{
  "event_id": "01J8Z3X4Y5Z6A7B8C9D0E1F2G3",
  "event_type": "order.created",
  "occurred_at": "2026-08-11T10:30:00+08:00",
  "source": "order-service",
  "version": "v1",
  "data": {
    "order_id": "20260811103000001",
    "user_id": "10086",
    "amount": 199.00
  }
}
```

### 2.4 Partition Key 选择

**【强制】对需要顺序保证的事件，必须使用业务唯一键作为 partition key（如 order_id、user_id）。**


## 三、Consumer 规范

### 3.1 Kafka 异步消费者（aiokafka）

**【正例】异步消费者使用 `aiokafka.AIOKafkaConsumer`：**

```python
from aiokafka import AIOKafkaConsumer
import asyncio

async def consume_order_events():
    """消费订单事件。"""
    consumer = AIOKafkaConsumer(
        "order.events",
        bootstrap_servers=settings.KAFKA_BOOTSTRAP_SERVERS,
        group_id="inventory-service",
        enable_auto_commit=False,           # 手动提交，确保消费成功后再提交 offset
        auto_offset_reset="earliest",
        max_poll_records=100,
        session_timeout_ms=30000,
    )
    await consumer.start()
    try:
        async for msg in consumer:
            try:
                payload = json.loads(msg.value.decode("utf-8"))
                await handle_order_event(payload)

                # 业务处理成功后手动提交 offset
                await consumer.commit()
            except Exception as e:
                logger.exception("消费失败: %s", e)
                # 不提交 offset，下次重新消费
                # 严重错误可发送到死信队列
    finally:
        await consumer.stop()
```

### 3.2 幂等消费

**【强制】Consumer 必须实现幂等：通过 `event_id` + Redis 去重表 / MySQL 唯一索引，保证同一消息多次投递只生效一次。**

**正例（MySQL 唯一索引）：**
```python
async def handle_order_event(payload: dict) -> None:
    """处理订单事件（幂等）。"""
    event_id = payload["event_id"]

    # 1. 尝试插入事件记录（唯一索引 event_id）
    try:
        async with db.begin():
            await db.execute(
                insert(ProcessedEvent).values(event_id=event_id, event_type=payload["event_type"])
            )
    except IntegrityError:
        # 已处理过，直接跳过
        logger.info("事件已处理过，跳过 event_id=%s", event_id)
        return

    # 2. 处理业务逻辑
    await process_order(payload["data"])
```

### 3.3 失败重试与死信

**【强制】消费失败必须隔离，不阻塞后续消息；推荐"重试队列 + 死信队列"模式：**

| 失败次数 | 处理 |
|----------|------|
| 1-3 次 | 投递到 `topic.retry.1/2/3`（带退避） |
| >3 次 | 投递到 `topic.dlq` + 告警人工介入 |

**正例：**
```python
RETRY_TOPICS = ["order.events.retry.1", "order.events.retry.2", "order.events.retry.3"]
DLQ_TOPIC = "order.events.dlq"
MAX_RETRIES = 3

async def handle_with_retry(msg):
    """带重试的消费逻辑。"""
    payload = json.loads(msg.value.decode("utf-8"))
    retry_count = int(msg.headers.get("retry_count", 0)) if msg.headers else 0

    try:
        await handle_order_event(payload)
    except Exception as e:
        logger.warning("消费失败 retry_count=%d err=%s", retry_count, e)
        if retry_count < MAX_RETRIES:
            # 重试：发到下一个 retry topic
            retry_topic = RETRY_TOPICS[retry_count]
            await producer.send(
                retry_topic,
                key=msg.key,
                value=msg.value,
                headers={**msg.headers, "retry_count": retry_count + 1},
            )
        else:
            # 死信
            await producer.send(DLQ_TOPIC, key=msg.key, value=msg.value)
            logger.error("消息投递死信 topic=%s key=%s", DLQ_TOPIC, msg.key)
        # 不提交 offset，让 retry topic 重新消费
        return
    await consumer.commit()
```

### 3.4 Offset 提交策略

**【强制】必须手动提交 offset（`enable_auto_commit=False`），业务处理成功后再 commit，避免消息丢失。**

| 策略 | 时机 |
|------|------|
| 自动提交（不推荐） | 每 5 秒自动提交，存在消息丢失风险 |
| 手动同步提交（推荐） | 处理成功后立即 commit |
| 手动异步提交 | 性能更优但需处理提交失败 |


## 四、消息落地表规范

**【推荐】所有消息消费记录持久化到 MySQL，便于排查、重放与对账：**

```sql
CREATE TABLE message_log (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    message_id VARCHAR(64) NOT NULL COMMENT '消息唯一ID（业务幂等键）',
    topic VARCHAR(100) NOT NULL,
    partition_id INT NOT NULL,
    offset_value BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    consume_status TINYINT NOT NULL DEFAULT 1 COMMENT '1=成功 2=失败 3=死信',
    retry_count INT NOT NULL DEFAULT 0,
    consumed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    error_message TEXT,
    UNIQUE KEY uk_message_id (message_id),
    KEY idx_topic_offset (topic, partition_id, offset_value),
    KEY idx_event_type (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息落地表';
```


## 五、监控指标

**【推荐】采集以下指标并接入 Prometheus：**

| 指标 | 类型 | 说明 |
|------|------|------|
| `kafka_consumer_lag` | Gauge | 消费 lag（落后消息数） |
| `kafka_producer_record_send_rate` | Gauge | 生产速率 |
| `kafka_consumer_records_consumed_rate` | Gauge | 消费速率 |
| `kafka_consumer_commit_rate` | Gauge | 提交速率 |
| `kafka_producer_record_error_rate` | Counter | 发送失败计数 |
| `mq_retry_count` | Histogram | 消息重试次数分布 |
| `mq_dlq_count` | Counter | 死信队列计数 |


## 六、Confluent Kafka 同步消费

**【正例】confluent-kafka 同步消费：**

```python
from confluent_kafka import Consumer, KafkaError

consumer = Consumer({
    "bootstrap.servers": settings.KAFKA_BOOTSTRAP_SERVERS,
    "group.id": "order-service",
    "enable.auto.commit": False,
    "auto.offset.reset": "earliest",
})

consumer.subscribe(["order.events"])

try:
    while True:
        msg = consumer.poll(timeout=1.0)
        if msg is None:
            continue
        if msg.error():
            if msg.error().code() == KafkaError._PARTITION_EOF:
                continue
            else:
                logger.error("Kafka 错误: %s", msg.error())
                continue

        try:
            payload = json.loads(msg.value())
            handle_order_event_sync(payload)
            consumer.commit(message=msg, asynchronous=False)
        except Exception:
            logger.exception("消费失败，跳过本次 offset")
finally:
    consumer.close()
```


## 七、安全与鉴权

**【推荐】Kafka 启用 SASL_SSL 鉴权：**

```python
producer = Producer({
    "bootstrap.servers": settings.KAFKA_BOOTSTRAP_SERVERS,
    "security.protocol": "SASL_SSL",
    "sasl.mechanism": "PLAIN",
    "sasl.username": settings.KAFKA_SASL_USER,
    "sasl.password": settings.KAFKA_SASL_PASSWORD,
    "ssl.ca.location": "/path/to/ca.pem",
})
```


## 八、禁止事项

**【强制】禁止以下做法：**

1. ❌ 不使用幂等机制直接消费（重复消费会导致业务重复处理）
2. ❌ 开启 `enable.auto.commit=true`（消息可能丢失）
3. ❌ Producer 不设 `acks=all`（消息丢失风险）
4. ❌ 在 Consumer 中做长阻塞操作（HTTP / 慢查询）阻塞整批消息
5. ❌ 业务失败不隔离（一条消息失败导致后续全部卡住）
6. ❌ 消息体不携带 `event_id`（无法实现幂等）
7. ❌ 单一 Consumer 实例处理过多 partition（单点性能瓶颈）


## 九、快速检查清单

- [ ] Producer 是否设置 `acks=all` + `enable.idempotence=true`？
- [ ] Consumer 是否手动提交 offset？
- [ ] 是否使用 `event_id` + MySQL 唯一索引实现幂等？
- [ ] 失败消息是否路由到重试队列 / 死信队列？
- [ ] 消息体是否包含 `event_id` / `event_type` / `occurred_at` / `source` / `version`？
- [ ] 顺序敏感场景是否使用业务唯一键作为 partition key？
- [ ] 是否采集 consumer lag / produce rate / 死信数等监控指标？
- [ ] 应用关闭前是否 flush Producer 并 stop Consumer？