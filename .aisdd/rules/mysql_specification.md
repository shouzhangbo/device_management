---
name: Redis 与 MySQL 开发规范
version: 1.0.0
description: 专注于Redis缓存使用规范与MySQL数据库设计与使用规范。结合阿里巴巴开发手册及行业最佳实践，通过正反例对照的方式，指导AI Agent生成高性能、高可靠、易维护的数据层代码。
---

# Redis 与 MySQL 开发规范

## 前言

本文档接续《Java + Spring + Spring Boot + MyBatis 综合开发规范》，专注于 **Redis** 缓存使用规范与 **MySQL** 数据库设计与使用规范。结合阿里巴巴开发手册及行业最佳实践，通过正反例对照的方式，指导 AI Agent 生成高性能、高可靠、易维护的数据层代码。

约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。


## 一、MySQL 数据库规范

### 1.1 建表规约

**【强制】表名、字段名必须使用小写字母或数字，禁止出现数字开头，禁止两个下划线中间只出现数字。**

**反例：**
```sql
-- ❌ 错误：包含大写字母、数字开头、不规范下划线
CREATE TABLE UserInfo ( ... );
CREATE TABLE 1st_order ( ... );
CREATE TABLE order__detail ( ... );
```

**正例：**
```sql
-- ✅ 正确：全小写，下划线分隔，语义清晰
CREATE TABLE user_info ( ... );
CREATE TABLE order_detail ( ... );
```

**【强制】表名不使用复数名词。**

**反例：**
```sql
-- ❌ 错误：表名复数
CREATE TABLE users ( ... );
CREATE TABLE orders ( ... );
```

**正例：**
```sql
-- ✅ 正确：单数形式
CREATE TABLE user ( ... );
CREATE TABLE `order` ( ... );
```

**【强制】表必备三字段：`id`（主键）、`create_time`（创建时间）、`update_time`（更新时间）。**

**反例：**
```sql
-- ❌ 错误：缺少创建和更新时间字段
CREATE TABLE product (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    price DECIMAL(10,2) NOT NULL
);
```

**正例：**
```sql
-- ✅ 正确：包含必备字段
CREATE TABLE product (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    name VARCHAR(100) NOT NULL COMMENT '商品名称',
    price DECIMAL(10,2) NOT NULL COMMENT '价格',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';
```

**【强制】`id` 主键推荐使用 `BIGINT UNSIGNED` 自增，禁止使用 `VARCHAR` 作为主键。**

**反例：**
```sql
-- ❌ 错误：使用 UUID 字符串作主键
CREATE TABLE user (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(50) NOT NULL
);
```

**正例：**
```sql
-- ✅ 正确：使用自增 BIGINT
CREATE TABLE user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL
);
```

**【强制】字段尽量定义为 `NOT NULL` 并设置默认值，避免 `NULL` 值带来的判断复杂和索引失效问题。**

**反例：**
```sql
-- ❌ 错误：大量字段允许 NULL
CREATE TABLE user (
    id BIGINT PRIMARY KEY,
    nickname VARCHAR(50),
    avatar VARCHAR(255),
    status TINYINT
);
```

**正例：**
```sql
-- ✅ 正确：NOT NULL 并提供默认值
CREATE TABLE user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    nickname VARCHAR(50) NOT NULL DEFAULT '' COMMENT '昵称',
    avatar VARCHAR(255) NOT NULL DEFAULT '' COMMENT '头像URL',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1-正常，0-禁用'
);
```

**【强制】小数类型使用 `DECIMAL`，禁止使用 `FLOAT` 和 `DOUBLE`（存在精度丢失风险）。**

**反例：**
```sql
-- ❌ 错误：金额字段使用 FLOAT
CREATE TABLE order (
    id BIGINT PRIMARY KEY,
    amount FLOAT COMMENT '订单金额'  -- 浮点数精度问题
);
```

**正例：**
```sql
-- ✅ 正确：使用 DECIMAL
CREATE TABLE `order` (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '订单金额'
);
```

**【强制】字符集统一使用 `utf8mb4`，存储表情符号等 4 字节字符。**

**反例：**
```sql
-- ❌ 错误：使用 utf8（仅支持 3 字节）
CREATE TABLE user (
    ...
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
```

**正例：**
```sql
-- ✅ 正确：utf8mb4
CREATE TABLE user (
    ...
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

**【强制】表注释和字段注释必须完整，方便维护。**

**反例：**
```sql
-- ❌ 错误：缺少注释
CREATE TABLE user (
    id BIGINT PRIMARY KEY,
    name VARCHAR(50)
);
```

**正例：**
```sql
-- ✅ 正确：完整注释
CREATE TABLE user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    name VARCHAR(50) NOT NULL COMMENT '用户姓名',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户信息表';
```


### 1.2 索引规约

**【强制】业务上具有唯一特性的字段，即使是组合字段，也必须建立唯一索引。**

**反例：**
```sql
-- ❌ 错误：唯一字段未建唯一索引
CREATE TABLE user (
    id BIGINT PRIMARY KEY,
    mobile VARCHAR(11) NOT NULL  -- 手机号本应唯一，却未建唯一索引
);
```

**正例：**
```sql
-- ✅ 正确：建立唯一索引
CREATE TABLE user (
    id BIGINT PRIMARY KEY,
    mobile VARCHAR(11) NOT NULL COMMENT '手机号',
    UNIQUE KEY uk_mobile (mobile)
);
```

**【强制】超过三个表禁止使用 `JOIN`，需要 `JOIN` 的字段，数据类型必须绝对一致；多表关联查询时，保证被关联的字段有索引。**

**反例：**
```sql
-- ❌ 错误：关联字段类型不一致，且无索引
SELECT a.*, b.* 
FROM order a 
LEFT JOIN user b ON a.user_id = b.id;  -- a.user_id 为 VARCHAR，b.id 为 BIGINT，类型不匹配
```

**正例：**
```sql
-- ✅ 正确：字段类型一致，且关联字段有索引
CREATE TABLE `order` (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    INDEX idx_user_id (user_id)
);

SELECT a.*, b.* 
FROM `order` a 
LEFT JOIN user b ON a.user_id = b.id;  -- 类型一致，都有索引
```

**【强制】在 `VARCHAR` 字段上建立索引时，必须指定索引长度，避免全字段索引过长。**

**反例：**
```sql
-- ❌ 错误：长文本字段全字段索引
CREATE TABLE article (
    id BIGINT PRIMARY KEY,
    content TEXT,
    INDEX idx_content (content)  -- 索引过大
);
```

**正例：**
```sql
-- ✅ 正确：指定前缀长度
CREATE TABLE article (
    id BIGINT PRIMARY KEY,
    content TEXT,
    title VARCHAR(200) NOT NULL,
    INDEX idx_title (title(20))  -- 只索引前 20 个字符
);
```

**【强制】页面搜索严禁使用左模糊或全模糊查询（如 `LIKE '%keyword%'`），如需使用请走搜索引擎。**

**反例：**
```sql
-- ❌ 错误：全模糊查询，索引失效
SELECT * FROM user WHERE username LIKE '%张三%';
```

**正例：**
```sql
-- ✅ 正确：如需模糊搜索，使用右模糊，或引入 Elasticsearch
SELECT * FROM user WHERE username LIKE '张三%';
```

**【推荐】利用覆盖索引避免回表查询，减少 `SELECT *`。**

**反例：**
```sql
-- ❌ 错误：查询所有字段，需要回表
SELECT * FROM user WHERE mobile = '13800138000';
```

**正例：**
```sql
-- ✅ 正确：只查询索引覆盖的字段
-- 假设索引为 idx_mobile(mobile, username)
SELECT mobile, username FROM user WHERE mobile = '13800138000';
```

**【推荐】建组合索引时，区分度最高的字段放在最左侧。**

**反例：**
```sql
-- ❌ 错误：将低区分度字段放前面
-- 假设 status 只有 0 和 1，区分度低
CREATE INDEX idx_status_create ON user (status, create_time);
```

**正例：**
```sql
-- ✅ 正确：高区分度字段放前面
CREATE INDEX idx_create_status ON user (create_time, status);
```


### 1.3 SQL 编写规约

**【强制】禁止使用存储过程、触发器、视图、事件，业务逻辑在应用层处理。**

**反例：**
```sql
-- ❌ 错误：使用触发器自动更新统计
CREATE TRIGGER update_order_count AFTER INSERT ON `order`
FOR EACH ROW UPDATE user SET order_count = order_count + 1 WHERE id = NEW.user_id;
```

**正例：**
```sql
-- ✅ 正确：在应用层使用事务处理
@Transactional(rollbackFor = Exception.class)
public void createOrder(OrderDTO orderDTO) {
    orderRepository.insert(orderDO);
    userRepository.incrementOrderCount(orderDTO.getUserId());
}
```

**【强制】批量操作使用 `INSERT INTO table VALUES (...), (...), (...)`，禁止循环单条插入。**

**反例：**
```java
// ❌ 错误：循环插入
for (User user : userList) {
    userRepository.insert(user);  // 产生 N 次数据库交互
}
```

**正例：**
```java
// ✅ 正确：批量插入
<insert id="batchInsert" parameterType="list">
    INSERT INTO user (username, email, create_time) VALUES
    <foreach collection="list" item="item" separator=",">
        (#{item.username}, #{item.email}, #{item.createTime})
    </foreach>
</insert>
```

**【强制】`UPDATE` 语句必须带 `WHERE` 条件，除非确认需要全表更新。**

**反例：**
```java
// ❌ 错误：无条件更新
<update id="updateAllStatus">
    UPDATE user SET status = 1
</update>
```

**正例：**
```java
// ✅ 正确：带条件更新
<update id="updateStatusByIds">
    UPDATE user SET status = #{status}
    WHERE id IN
    <foreach collection="ids" item="id" open="(" separator="," close=")">
        #{id}
    </foreach>
</update>
```

**【强制】分页查询必须包含排序字段，避免数据重复或遗漏。**

**反例：**
```sql
-- ❌ 错误：分页无排序，结果不确定
SELECT * FROM user LIMIT 0, 10;
```

**正例：**
```sql
-- ✅ 正确：包含排序字段（最好是唯一键）
SELECT * FROM user ORDER BY id DESC LIMIT 0, 10;
```


### 1.4 ORM 映射规约

**【强制】MyBatis 查询返回结果时，若只查询部分字段，禁止使用实体类接收，应使用专门的 DTO 或 Map。**

**反例：**
```java
// ❌ 错误：使用实体类接收部分字段，导致字段为 null
List<UserDO> users = userRepository.selectIdAndNameList();  // 返回部分字段
// 此时 UserDO 中的 email、status 等字段为 null，业务误判
```

**正例：**
```java
// ✅ 正确：定义专门的 VO/DTO
@Data
public class UserSimpleVO {
    private Long id;
    private String name;
}

List<UserSimpleVO> users = userRepository.selectIdAndNameList();
```

**【强制】`<isEqual>` 等标签中 `property` 取值不能直接取 `Map` 中的 key，需使用 `@Param` 显式声明参数名。**

**反例：**
```xml
<!-- ❌ 错误：参数为 Map 时直接取 key，可读性差 -->
<if test="status != null">
    AND status = #{status}
</if>
```

**正例：**
```xml
<!-- ✅ 正确：接口方法使用 @Param 声明 -->
<!-- UserRepository.selectByMap(@Param("status") Integer status, @Param("name") String name) -->
<if test="status != null">
    AND status = #{status}
</if>
```

**【推荐】使用 MyBatis-Plus 等增强工具时，`LambdaQueryWrapper` 优于字符串字段名，避免字段名变更遗漏。**

**反例：**
```java
// ❌ 错误：字符串硬编码字段名
QueryWrapper<UserDO> wrapper = new QueryWrapper<>();
wrapper.eq("user_name", username);  // 字段名写死，重命名后遗漏修改
```

**正例：**
```java
// ✅ 正确：Lambda 表达式
LambdaQueryWrapper<UserDO> wrapper = new LambdaQueryWrapper<>();
wrapper.eq(UserDO::getUsername, username);
```


## 二、Redis 缓存规范

### 2.1 Key 设计规范

**【强制】Redis Key 命名统一采用 `业务前缀:模块:标识:属性` 格式，使用冒号分隔，全大写或全小写，保持团队一致。**

**反例：**
```
// ❌ 错误：无命名规范，难以管理
user_123
token:abc
cache-product-456
```

**正例：**
```
// ✅ 正确：统一格式，清晰可读
USER:INFO:123          // 用户信息，ID=123
USER:TOKEN:abc123def   // 用户令牌
PRODUCT:DETAIL:456     // 商品详情，ID=456
ORDER:LOCK:20260101001 // 订单分布式锁
```

**【强制】Key 禁止过长（建议不超过 50 字节），避免占用过多内存和网络带宽。**

**反例：**
```
// ❌ 错误：Key 包含完整 URL 或长描述
CACHE:USER:PROFILE:WITH_ALL_PERMISSIONS_AND_ROLES_FOR_DASHBOARD:1001
```

**正例：**
```
// ✅ 正确：简洁明了
USER:PROFILE:1001
```

**【强制】Key 必须设置合理的过期时间，避免内存无限增长。**

**反例：**
```java
// ❌ 错误：未设置过期时间
stringRedisTemplate.opsForValue().set("USER:SESSION:" + token, userId);
```

**正例：**
```java
// ✅ 正确：设置过期时间
stringRedisTemplate.opsForValue().set("USER:SESSION:" + token, userId, 
                                      Duration.ofHours(2));
```

**【推荐】热点 Key 命名避免使用递增数字后缀，防止缓存倾斜。**

**反例：**
```
// ❌ 错误：热点数据后缀连续数字，可能落到同一分片
HOT:PRODUCT:1
HOT:PRODUCT:2
HOT:PRODUCT:3
```

**正例：**
```
// ✅ 正确：使用散列算法打散
HOT:PRODUCT:MD5(productId)
```


### 2.2 数据结构选型规范

**【强制】根据业务场景选择合适的数据结构，避免滥用 String 存储复杂对象。**

**反例：**
```java
// ❌ 错误：用 String 存 JSON 实现计数器
String key = "ARTICLE:VIEW:" + articleId;
String views = redisTemplate.opsForValue().get(key);
redisTemplate.opsForValue().set(key, String.valueOf(Integer.parseInt(views) + 1));
// 问题：并发下计数不准，且无法原子操作
```

**正例：**
```java
// ✅ 正确：使用 Hash 存储对象，使用 String incr 做计数
// 存储对象
redisTemplate.opsForHash().putAll("USER:INFO:" + userId, userMap);
redisTemplate.expire("USER:INFO:" + userId, Duration.ofHours(24));

// 计数器
redisTemplate.opsForValue().increment("ARTICLE:VIEW:" + articleId);
```

**【推荐】Hash 结构存储对象时，单个 Hash 的 field 数量不宜超过 1000，否则考虑拆分或使用 String 存储 JSON。**

**反例：**
```java
// ❌ 错误：一个 Hash 存储上万 field
for (int i = 0; i < 10000; i++) {
    redisTemplate.opsForHash().put("USER:BEHAVIOR:" + userId, "item_" + i, value);
}
// 导致 hgetall 阻塞 Redis
```

**正例：**
```java
// ✅ 正确：拆分多个 Hash 或使用 JSON
String key = "USER:BEHAVIOR:" + userId + ":" + (i / 1000);
redisTemplate.opsForHash().put(key, "item_" + i, value);
```


### 2.3 缓存使用模式规范

**【推荐】读操作使用 Cache Aside 模式（旁路缓存）：先读缓存，缓存未命中则读数据库并回写缓存。**

**反例：**
```java
// ❌ 错误：先更新数据库，后删除缓存时可能因并发导致脏数据
@Transactional
public void updateUser(User user) {
    userRepository.updateById(user);
    redisTemplate.delete("USER:INFO:" + user.getId());
}
// 若线程A读请求在删除缓存后、更新数据库前发生，会读到旧数据并回写缓存，导致脏数据
```

**正例：**
```java
// ✅ 正确：先删除缓存，再更新数据库（配合延时双删）
@Transactional
public void updateUser(User user) {
    String key = "USER:INFO:" + user.getId();
    // 1. 删除缓存
    redisTemplate.delete(key);
    // 2. 更新数据库
    userRepository.updateById(user);
    // 3. 延时双删（异步）
    CompletableFuture.runAsync(() -> {
        try { Thread.sleep(500); } catch (InterruptedException e) { }
        redisTemplate.delete(key);
    });
}
```

**【强制】缓存空值或默认值以防止缓存穿透，并设置较短的过期时间。**

**反例：**
```java
// ❌ 错误：查询不到数据时不缓存，导致穿透
public User getById(Long id) {
    String key = "USER:INFO:" + id;
    User cached = (User) redisTemplate.opsForValue().get(key);
    if (cached != null) {
        return cached;
    }
    User user = userRepository.selectById(id);
    if (user != null) {
        redisTemplate.opsForValue().set(key, user, Duration.ofHours(1));
    }
    // 未缓存空值，恶意请求不存在的 id 会穿透到 DB
    return user;
}
```

**正例：**
```java
// ✅ 正确：缓存空对象，防止穿透
public User getById(Long id) {
    String key = "USER:INFO:" + id;
    User cached = (User) redisTemplate.opsForValue().get(key);
    if (cached != null) {
        return cached.getId() == null ? null : cached;  // 判断是否为空对象标记
    }
    User user = userRepository.selectById(id);
    if (user == null) {
        // 缓存空对象，过期时间短
        redisTemplate.opsForValue().set(key, new User(), Duration.ofMinutes(5));
        return null;
    }
    redisTemplate.opsForValue().set(key, user, Duration.ofHours(1));
    return user;
}
```

**【强制】使用分布式锁解决缓存击穿问题（热点 Key 失效时大量请求打到 DB）。**

**反例：**
```java
// ❌ 错误：未加锁，热点数据失效时大量请求同时查 DB
public Product getProduct(Long id) {
    String key = "PRODUCT:" + id;
    Product p = (Product) redisTemplate.opsForValue().get(key);
    if (p == null) {
        p = productRepository.selectById(id);  // 大量线程同时执行此行
        redisTemplate.opsForValue().set(key, p, Duration.ofHours(1));
    }
    return p;
}
```

**正例：**
```java
// ✅ 正确：使用分布式锁保证只有一个线程查库
public Product getProduct(Long id) {
    String key = "PRODUCT:" + id;
    Product p = (Product) redisTemplate.opsForValue().get(key);
    if (p != null) {
        return p;
    }
    String lockKey = "LOCK:PRODUCT:" + id;
    try {
        // 尝试获取锁
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 
                                                                 Duration.ofSeconds(10));
        if (Boolean.TRUE.equals(locked)) {
            p = productRepository.selectById(id);
            if (p != null) {
                redisTemplate.opsForValue().set(key, p, Duration.ofHours(1));
            } else {
                // 缓存空值
                redisTemplate.opsForValue().set(key, new Product(), Duration.ofMinutes(5));
            }
            return p;
        } else {
            // 等待锁释放后重试
            Thread.sleep(100);
            return getProduct(id);
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
    } finally {
        redisTemplate.delete(lockKey);
    }
}
```


### 2.4 缓存与数据库一致性规范

**【强制】更新操作时，推荐采用「先更新数据库，再删除缓存」或「先删除缓存，再更新数据库（配合延时双删）」，禁止先更新缓存后更新数据库。**

**反例：**
```java
// ❌ 错误：先更新缓存，再更新数据库，极易产生不一致
public void updateUser(User user) {
    // 1. 先更新缓存
    redisTemplate.opsForValue().set("USER:INFO:" + user.getId(), user);
    // 2. 再更新数据库（如果此时失败，缓存与 DB 不一致）
    userRepository.updateById(user);
}
```

**正例（推荐）：**
```java
// ✅ 正确：先更新数据库，再删除缓存
@Transactional(rollbackFor = Exception.class)
public void updateUser(User user) {
    userRepository.updateById(user);
    redisTemplate.delete("USER:INFO:" + user.getId());
}
```

**【推荐】对于强一致性要求的场景，采用「订阅 Binlog + 消息队列」异步更新缓存。**

**正例架构示意：**
```
MySQL Binlog -> Canal -> Kafka -> 缓存更新服务 -> Redis
```

### 2.5 Pipeline 与批量操作规范

**【推荐】批量获取或设置缓存时，使用 `Pipeline` 或 `mget/mset` 减少网络往返。**

**反例：**
```java
// ❌ 错误：循环单个获取，产生 N 次网络请求
List<User> users = new ArrayList<>();
for (Long id : ids) {
    User user = (User) redisTemplate.opsForValue().get("USER:INFO:" + id);
    users.add(user);
}
```

**正例：**
```java
// ✅ 正确：使用 mget 批量获取
List<String> keys = ids.stream().map(id -> "USER:INFO:" + id).collect(Collectors.toList());
List<Object> values = redisTemplate.opsForValue().multiGet(keys);
```

**【强制】使用 Pipeline 时注意不要打包过多命令，建议单次 Pipeline 命令数不超过 1000，避免阻塞 Redis。**


### 2.6 分布式锁规范

**【强制】使用 Redis 实现分布式锁时，必须同时满足：原子加锁、设置过期时间、锁续期、正确释放。**

**反例：**
```java
// ❌ 错误：非原子操作，可能死锁
Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1");
redisTemplate.expire(lockKey, Duration.ofSeconds(30));  // 如果上一行后服务宕机，锁永不过期
```

**正例（使用 Redisson）：**
```java
// ✅ 正确：使用 Redisson 成熟客户端
@Autowired
private RedissonClient redissonClient;

public void processOrder(String orderId) {
    RLock lock = redissonClient.getLock("ORDER:LOCK:" + orderId);
    try {
        // 尝试加锁，等待 10 秒，锁有效期 30 秒（自动续期）
        if (lock.tryLock(10, 30, TimeUnit.SECONDS)) {
            // 业务逻辑
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

**【强制】释放锁时必须验证锁的持有者，防止误删他人的锁。**

**反例（未使用 Redisson 时）：**
```java
// ❌ 错误：直接删除，可能误删其他客户端的锁
redisTemplate.delete(lockKey);
```

**正例（Lua 脚本保证原子性）：**
```java
// ✅ 正确：使用 Lua 脚本验证 value 后删除
String script = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "return redis.call('del', KEYS[1]) else return 0 end";
redisTemplate.execute(new DefaultRedisScript<>(script, Long.class),
                      Collections.singletonList(lockKey), lockValue);
```


### 2.7 缓存穿透/击穿/雪崩防护规范

| 问题 | 防护手段 | 说明 |
|------|---------|------|
| **缓存穿透** | 缓存空值、布隆过滤器 | 对不存在的 key 也缓存一个空标记 |
| **缓存击穿** | 分布式锁、永不过期 | 热点 key 失效时加锁查库 |
| **缓存雪崩** | 过期时间加随机值、多级缓存 | 避免大量 key 同时过期 |

**正例：过期时间加随机值**
```java
// ✅ 正确：基础过期时间 + 随机偏移，避免雪崩
int baseExpire = 3600;
int randomOffset = ThreadLocalRandom.current().nextInt(300);  // 0-300 秒
redisTemplate.opsForValue().set(key, value, 
                                Duration.ofSeconds(baseExpire + randomOffset));
```


## 三、整合示例：Spring Boot + MyBatis + Redis + MySQL

### 3.1 配置类（RedisConfig.java）

```java
@Configuration
@EnableCaching
public class RedisConfig {
    
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        
        // 使用 Jackson2JsonRedisSerializer 序列化值
        Jackson2JsonRedisSerializer<Object> jacksonSerializer = 
            new Jackson2JsonRedisSerializer<>(Object.class);
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                                 ObjectMapper.DefaultTyping.NON_FINAL);
        jacksonSerializer.setObjectMapper(om);
        
        // String 序列化
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jacksonSerializer);
        template.setHashValueSerializer(jacksonSerializer);
        
        template.afterPropertiesSet();
        return template;
    }
    
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();
        return RedisCacheManager.builder(factory)
                .cacheDefaults(config)
                .build();
    }
}
```

### 3.2 Service 层整合示例

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {
    
    private final ProductRepository productRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;
    
    private static final String CACHE_KEY_PREFIX = "PRODUCT:DETAIL:";
    private static final long CACHE_EXPIRE_HOURS = 1;
    private static final long NULL_CACHE_EXPIRE_MINUTES = 5;
    
    /**
     * 根据 ID 查询商品（缓存防穿透、击穿）
     */
    public ProductVO getProductById(Long id) {
        String cacheKey = CACHE_KEY_PREFIX + id;
        
        // 1. 查缓存
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            if (cached instanceof NullProduct) {
                return null;
            }
            return (ProductVO) cached;
        }
        
        // 2. 缓存未命中，加分布式锁防止击穿
        String lockKey = "LOCK:PRODUCT:" + id;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                // 双重检查
                cached = redisTemplate.opsForValue().get(cacheKey);
                if (cached != null) {
                    return cached instanceof NullProduct ? null : (ProductVO) cached;
                }
                
                // 查数据库
                ProductDO productDO = productRepository.selectById(id);
                if (productDO == null) {
                    // 缓存空对象防穿透
                    redisTemplate.opsForValue().set(cacheKey, new NullProduct(),
                            Duration.ofMinutes(NULL_CACHE_EXPIRE_MINUTES));
                    return null;
                }
                
                ProductVO productVO = convertToVO(productDO);
                // 缓存加随机过期时间防雪崩
                int randomOffset = ThreadLocalRandom.current().nextInt(300);
                redisTemplate.opsForValue().set(cacheKey, productVO,
                        Duration.ofSeconds(TimeUnit.HOURS.toSeconds(CACHE_EXPIRE_HOURS) + randomOffset));
                return productVO;
            } else {
                // 获取锁失败，短暂等待后递归重试
                Thread.sleep(100);
                return getProductById(id);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("系统繁忙，请稍后再试");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
    
    /**
     * 更新商品（先更新 DB，再删除缓存）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateProduct(ProductUpdateDTO dto) {
        ProductDO productDO = buildProductDO(dto);
        productRepository.updateById(productDO);
        // 删除缓存
        redisTemplate.delete(CACHE_KEY_PREFIX + dto.getId());
        log.info("商品更新成功，已清除缓存, id={}", dto.getId());
    }
    
    // 空对象标记
    private static class NullProduct implements Serializable { }
}
```


## 四、快速检查清单

### MySQL 检查项
- [ ] 表名、字段名是否全小写、下划线分隔？
- [ ] 是否包含 `id`、`create_time`、`update_time` 三字段？
- [ ] 主键是否使用自增 `BIGINT`？
- [ ] 金额字段是否使用 `DECIMAL`？
- [ ] 字符集是否为 `utf8mb4`？
- [ ] 是否有完整表注释和字段注释？
- [ ] 唯一字段是否建立唯一索引？
- [ ] 是否避免使用 `SELECT *`？
- [ ] 批量操作是否使用批量语句？
- [ ] `UPDATE` 是否带 `WHERE` 条件？
- [ ] 分页查询是否包含排序字段？

### Redis 检查项
- [ ] Key 命名是否遵循 `业务:模块:标识` 规范？
- [ ] 是否设置了合理的过期时间？
- [ ] 是否根据业务选择了正确的数据结构？
- [ ] 是否缓存了空值以防止穿透？
- [ ] 热点 Key 是否使用分布式锁防止击穿？
- [ ] 更新操作是否采用「先更新 DB，再删除缓存」模式？
- [ ] 批量操作是否使用了 `mget/mset` 或 `Pipeline`？
- [ ] 分布式锁是否使用 Redisson 或 Lua 脚本保证原子性？
- [ ] 是否避免使用 `keys *` 命令（生产禁用）？
- [ ] 缓存雪崩防护是否添加了随机过期时间？

---

本规范应与《Java + Spring + Spring Boot + MyBatis 综合开发规范》配合使用，作为 AI Agent 生成数据层代码的完整约束。建议在实际项目中根据业务场景适当调整过期时间、一致性策略等参数。