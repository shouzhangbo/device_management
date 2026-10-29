---
name: Redis 开发规范
version: 1.0.0
description: 跨语言 Redis 开发规范，覆盖开发规范（连接/Key 命名/数据结构选型/命令使用）、缓存使用规范（一致性/失效/穿透击穿雪崩）、索引与数据结构设计（ZSET 排行索引/Hash 对象/二级索引键）、分布式锁、缓存与 MySQL 配合的 SQL 规范与落地表索引设计、日志与监控。为 AI Agent 生成 Redis 相关代码提供正反例对照。约束等级：【强制】（必须遵守）/【推荐】（尽量遵守）。
---

# Redis 开发规范

## 前言

本规范约束 AI Agent 生成的 Redis 相关代码质量，适用于 Java（RedisTemplate/Lettuce）、Python（redis-py）、Node.js（ioredis）等主流客户端。核心覆盖：**开发规范、缓存与 MySQL 一致性、索引与数据结构设计、SQL 规范（落地表）、分布式锁**。

约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。


## 一、Redis 开发规范

### 1.1 连接管理

**【强制】必须使用连接池管理连接，禁止每次操作新建连接；必须设置连接超时与读写超时。**

**反例（Java）：**
```java
// ❌ 错误：无连接池，未设超时
Jedis jedis = new Jedis("127.0.0.1", 6379);
```

**正例（Java）：**
```java
// ✅ 正确：连接池 + 超时
LettuceConnectionFactory factory = new LettuceConnectionFactory();
factory.setTimeout(Duration.ofSeconds(2));
```

**正例（Python）：**
```python
# ✅ 正确：连接池
pool = redis.ConnectionPool(host="127.0.0.1", port=6379, socket_timeout=2, max_connections=50)
redis_client = redis.Redis(connection_pool=pool)
```

**正例（Node）：**
```javascript
// ✅ 正确：ioredis 自带连接池
const redis = new Redis({ host: "127.0.0.1", port: 6379, connectTimeout: 2000 });
```

**【强制】禁止在循环或高频热路径中执行阻塞命令（`BLPOP`/`BRPOP`/`SUBSCRIBE`/大 `KEYS`），防止阻塞事件循环或占用连接。**

### 1.2 Key 命名规范

**【强制】Key 命名格式：`业务域:模块:实体:ID`，全小写，冒号分隔；必须设置 TTL（除非确有长期保存需求）。**

**反例：**
```java
// ❌ 错误：无业务前缀、无 TTL、可读性差
redisTemplate.opsForValue().set("u10086", user);
```

**正例：**
```java
// ✅ 正确：业务前缀 + TTL
String key = "order:refund:apply:" + refundId;   // 业务域:模块:实体:ID
redisTemplate.opsForValue().set(key, refundApply, Duration.ofMinutes(30));
```

**【强制】禁止将密码、身份证、银行卡号等敏感信息作为 Key 或 Value 明文存储（如需存储必须脱敏或加密）。**

### 1.3 数据结构选型

| 场景 | 推荐结构 | 说明 |
|------|---------|------|
| 简单值/计数/限流 | String | `INCR` 做计数器 |
| 对象整体读写 | Hash | 可局部更新字段，节省内存 |
| 排行榜/分页/范围查询 | ZSet | `ZREVRANGE` 排行、`ZRANGEBYSCORE` 时间窗 |
| 去重/标签 | Set | `SISMEMBER`、`SINTER` |
| 简单队列/FIFO | List | 生产端 `RPUSH`、消费端 `BLPOP` |
| 大数据对象 | 拆分存储 + 备注 | 单 Value 超过 10KB 建议拆分 |

**【强制】单 Key 的 Value 大小必须控制：String 建议 < 10KB；Hash 字段数建议 < 1000；集合成员数建议 < 10000，避免大 Key。出现大 Key 必须拆分或改造。**

### 1.4 命令使用规范

**【强制】禁止使用 `KEYS *` 全量扫描，必须使用 `SCAN` 游标迭代。**

**反例（Python）：**
```python
# ❌ 错误：KEYS 全量扫描，阻塞 Redis
keys = redis_client.keys("order:*")
```

**正例（Python）：**
```python
# ✅ 正确：SCAN 游标迭代
def scan_keys(pattern):
    cursor, keys = 0, []
    while True:
        cursor, batch = redis_client.scan(cursor, match=pattern, count=100)
        keys.extend(batch)
        if cursor == 0:
            break
    return keys
```

**【强制】批量写入/读取必须使用 Pipeline 或批量命令（`MSET`/`MGET`），禁止循环单条命令。**

**【推荐】热点读使用 `GET` + 本地无；写多读少的计数场景慎用 `INCR` 高频写，必要时异步合并。**


## 二、缓存使用规范

### 2.1 缓存模式

**【推荐】默认采用 Cache-Aside 模式：读（先查缓存 → 未命中回源 DB → 回填缓存 + TTL）；写（先更新 DB → 再删缓存）。**

```
读:  GET key → 命中? 返回 : 查 DB → SET key TTL → 返回
写:  更新 DB → DEL key（删除而非更新缓存）
```

### 2.2 缓存与数据库一致性

**【强制】写操作必须"先更新数据库，再删除缓存"，禁止先删缓存后写库（并发下会读到脏数据）。**

**反例：**
```java
// ❌ 错误：先删缓存后写库，并发读会回填旧数据
redisTemplate.delete(key);
orderRepository.update(order);  // 若 update 前有并发读，缓存被回填旧值
```

**正例：**
```java
// ✅ 正确：先写库后删缓存
orderRepository.update(order);
redisTemplate.delete(key);      // 删除缓存让下次读回源
```

**【推荐】对强一致要求的高频写场景，采用"延迟双删"或通过消息队列异步删缓存（先写库 → 发消息 → 消费端删缓存），避免删缓存与写库之间的窗口期。**

### 2.3 缓存失效

**【强制】必须为每个缓存 Key 设置合理 TTL，禁止永久 Key（明确持久化需求除外）；过期时间应加随机抖动，防止缓存同时过期导致缓存雪崩。**

**正例（Java）：**
```java
// ✅ 正确：TTL + 随机抖动
long base = 1800;
long jitter = ThreadLocalRandom.current().nextLong(0, 300);
redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(base + jitter));
```

### 2.4 缓存穿透 / 击穿 / 雪崩防护

**【强制】对"缓存不存在且 DB 也不存在"的场景必须做穿透防护：缓存空值（短 TTL）或布隆过滤器。**

**【强制】热点 Key 过期瞬间的击穿防护：使用互斥锁（分布式锁）控制回源，或缓存逻辑过期（后台异步刷新）。**

**【强制】雪崩防护：TTL 随机化 + 多级缓存（本地缓存 Caffeine/本地字典 + Redis）+ 熔断降级。**


## 三、索引与数据结构设计（索引设计）

> Redis 的"索引"指：用数据结构构建的查询索引键与排序结构。设计原则是**用空间换查询效率，避免全量扫描**。

### 3.1 二级索引键设计

**【强制】需要按非主键字段查询时，使用"索引键 → ID 集合"的二级索引模式，禁止全量 `KEYS`/`SCAN` 过滤。**

**正例（Java）：**
```java
// ✅ 正确：二级索引键 order:index:userId:{userId} → Set<orderId>
String idxKey = "order:index:userId:" + userId;
redisTemplate.opsForSet().add(idxKey, orderId);
// 查询用户订单 ID 列表
Set<String> orderIds = redisTemplate.opsForSet().members(idxKey);
```

**设计约定：**
- 索引键必须带业务前缀 + 索引维度（`模块:index:维度:值`）
- 索引维护必须与主数据写操作**同一事务/同一次写流程**中完成，避免索引与数据不一致
- 索引集合需设置过期策略或与数据同生命周期清理

### 3.2 排行榜 / 分页索引（ZSet）

**【强制】排行榜、TopN、时间窗分页必须使用 ZSet，禁止用 List 或全量加载后排序。**

**正例（Python）：**
```python
# ✅ 正确：ZSet 排行榜
redis_client.zadd("rank:sales", {f"sku_{sku_id}": score})
top = redis_client.zrevrange("rank:sales", 0, 9, withscores=True)  # Top10
```

### 3.3 对象索引（Hash）

**【推荐】读多改少的对象使用 Hash 存储，字段级局部更新，避免整对象序列化/反序列化开销。**

**正例（Node）：**
```javascript
// ✅ 正确：Hash 存对象，单字段更新
await redis.hset(`user:profile:${userId}`, "nickname", "张三");
const profile = await redis.hgetall(`user:profile:${userId}`);
```

### 3.4 索引维护规范

**【强制】索引与主数据必须强一致：主数据变更（增删改）时，对应的二级索引/排行索引必须同步更新；无法同步更新的场景必须在注释中说明并在 TTL 内自愈。**

**【强制】禁止在查询路径上用 `SCAN`/`KEYS` 过滤实现索引语义（性能不可控），必须使用显式索引键。**


## 四、分布式锁规范

### 4.1 锁实现要点

**【强制】分布式锁必须使用原子命令：`SET key value NX EX timeout`，禁止用"先 GET 再 SET"的非原子操作。**

**反例（Python）：**
```python
# ❌ 错误：非原子，存在竞态
if redis_client.exists(lock_key) == 0:
    redis_client.set(lock_key, token)
```

**正例（Python）：**
```python
# ✅ 正确：原子 SET NX EX
ok = redis_client.set(lock_key, token, nx=True, ex=30)
if ok:
    try:
        do_business()
    finally:
        # 用 Lua 保证"值匹配才删除"，防止误删他人锁
        redis_client.eval("if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
                          [lock_key], [token])
```

### 4.2 锁使用规范

**【强制】锁内禁止执行长耗时操作（超过锁过期时间会失效），必须设置合理的锁超时并开启看门狗/续期（如 Redisson watchDog）。**

**【强制】锁的粒度必须最小化：只锁需要保护的资源（如订单号），禁止用全局大锁。**

**【强制】释放锁时必须校验持有者（Value 用唯一 Token），防止误删其他线程的锁。**

**【推荐】能用数据库唯一约束/乐观锁解决并发时，优先使用，不引入分布式锁。**


## 五、SQL 规范（缓存与 MySQL 配合）

> 本章约束"缓存场景下对数据库的 SQL 操作"，以及可选的**缓存审计/刷新落地表**的建表与索引。

### 5.1 缓存读写中的 SQL 顺序

**【强制】写路径顺序：先执行 SQL 更新/插入 → 提交事务 → 删除缓存。禁止在事务未提交前删除缓存（回滚后缓存与 DB 不一致）。**

**正例（Java + MyBatis）：**
```java
// ✅ 正确：先提交 DB 事务，再删缓存
@Transactional
public void updateOrder(Order order) {
    orderRepository.updateById(order);   // SQL 更新
    // 事务提交后由事务同步器触发删缓存
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override public void afterCommit() {
                redisTemplate.delete(cacheKey);
            }
        });
}
```

### 5.2 缓存回源 SQL 约束

**【强制】缓存未命中回源 DB 时，SQL 必须只查询必要字段并加合理索引，禁止 `SELECT *`；热点回源必须限流/互斥，防止击穿压垮数据库。**

**反例：**
```sql
-- ❌ 错误：回源全量字段
SELECT * FROM t_order WHERE order_no = #{orderNo};
```

**正例：**
```sql
-- ✅ 正确：只查必要字段 + 唯一索引命中
SELECT id, order_no, amount, status FROM t_order WHERE order_no = #{orderNo};
```

### 5.3 缓存刷新/审计落地表（可选）

**【推荐】需要缓存变更审计或异步刷新时，使用落地表记录，表结构与索引如下：**

```sql
-- 缓存刷新任务表（记录待删/待刷新的缓存 Key）
CREATE TABLE `cache_refresh_task` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `biz_type`    VARCHAR(64)   NOT NULL COMMENT '业务域，如 order/user',
  `cache_key`   VARCHAR(255)  NOT NULL COMMENT '待刷新缓存 Key',
  `status`      TINYINT       NOT NULL DEFAULT 0 COMMENT '0-待处理 1-已处理 2-失败',
  `retry_count` INT           NOT NULL DEFAULT 0 COMMENT '重试次数',
  `created_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_created` (`status`, `created_at`)           COMMENT '待处理队列扫描',
  KEY `idx_cache_key`      (`cache_key`)                      COMMENT '按 Key 检索'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='缓存刷新任务表';
```

**索引设计要求：**
- 状态扫描用组合索引 `(status, created_at)`，避免全表扫描
- 高频按 Key 检索必须建 `cache_key` 索引
- 拒绝字段（如 `cache_key` 变长）长度必须约束，防止超长索引


## 六、日志与监控规范

**【强制】Redis 读写、缓存未命中回源、分布式锁获取/释放、缓存删除等关键操作必须打印日志，含 Key、耗时；禁止打印 Value 明文中的敏感信息。**

**正例（Java）：**
```java
log.info("缓存删除, key={}, 耗时={}ms", key, costMs);
log.info("缓存未命中回源, key={}, orderNo={}", key, orderNo);
```

**【推荐】对缓存命中率、大 Key、慢命令（> 50ms）设置监控指标与告警。**


## 七、快速检查清单

- [ ] 是否使用连接池并设置超时？
- [ ] Key 是否符合 `业务域:模块:实体:ID` 且设置了 TTL？
- [ ] 是否避免了大 Key（String>10KB / 集合>1万成员）？
- [ ] 是否禁用 `KEYS *`、改为 `SCAN`？
- [ ] 批量操作是否使用 Pipeline？
- [ ] 写操作是否"先写库后删缓存"？
- [ ] 是否处理了缓存穿透/击穿/雪崩？
- [ ] 二级索引/排行索引是否与主数据同步维护？
- [ ] 分布式锁是否原子获取、带 Token 释放、锁内无长耗时？
- [ ] 回源 SQL 是否只查必要字段、走索引？
- [ ] 缓存刷新落地表是否建了状态扫描与 Key 检索索引？
- [ ] Redis 关键操作是否有日志且避免打印敏感信息？
