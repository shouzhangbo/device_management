---
name: Java 中间件集成规范
version: 1.0.0
description: 覆盖 Spring 集成 Redis、MyBatis、Kafka MQ 及 HTTP 协议的开发规范，为 AI Agent 生成代码提供正反例对照。约束等级分为三级：【强制】（必须遵守）、【推荐】（尽量遵守）、【参考】（可选参考）。
---

# Java 中间件集成规范

## 前言

本规范覆盖 Spring Boot 集成 Redis 缓存、MyBatis 数据访问、Kafka 消息队列及 HTTP 协议的开发规范，为 AI Agent 生成代码提供正反例对照。

约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。


## 一、Redis 集成规范

**【强制】Redis Key 必须有统一前缀，格式为 `项目名:模块名:业务标识:唯一标识`。**

**反例：**
```java
// ❌ 错误：Key 无前缀，容易冲突
redisTemplate.opsForValue().set("user:1", user);
redisTemplate.opsForValue().set("token:abc123", token);
```

**正例：**
```java
// ✅ 正确：统一前缀
public class RedisKeyConstants {
    public static final String USER_INFO = "app:user:info:";      // app:user:info:{userId}
    public static final String USER_TOKEN = "app:user:token:";    // app:user:token:{token}
    public static final String ORDER_CACHE = "app:order:detail:"; // app:order:detail:{orderId}
}

// 使用
String key = RedisKeyConstants.USER_INFO + userId;
redisTemplate.opsForValue().set(key, user, Duration.ofMinutes(30));
```

**【强制】所有 Redis 操作必须设置过期时间，禁止存储不过期的数据。**

**反例：**
```java
// ❌ 错误：未设置过期时间
redisTemplate.opsForValue().set(key, value);
```

**正例：**
```java
// ✅ 正确：设置过期时间
redisTemplate.opsForValue().set(key, value, Duration.ofMinutes(30));
```

**【强制】禁止使用 `keys *` 命令，应使用 `SCAN` 或维护 Key 集合。**

**反例：**
```java
// ❌ 错误：keys * 阻塞 Redis
Set<String> keys = redisTemplate.keys("app:user:*");
```

**正例：**
```java
// ✅ 正确：使用 SCAN 或维护 Set 集合
// 方式一：SCAN
Set<String> keys = new HashSet<>();
try (Cursor<byte[]> cursor = redisTemplate.executeWithStickyConnection(
        connection -> connection.scan(ScanOptions.scanOptions()
                .match("app:user:*")
                .count(100)
                .build()))) {
    while (cursor.hasNext()) {
        keys.add(new String(cursor.next()));
    }
}

// 方式二：维护索引 Set
redisTemplate.opsForSet().add("app:user:ids", userId);
String key = RedisKeyConstants.USER_INFO + userId;
redisTemplate.opsForValue().set(key, user, Duration.ofMinutes(30));
```

**【强制】推荐使用 `RedisTemplate<String, String>` + Jackson 序列化器，禁止使用 JDK 序列化。**

**正例：**
```java
// ✅ 正确：配置 Jackson 序列化
@Configuration
public class RedisConfig {
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
```

**【推荐】缓存操作封装到独立的 CacheService 中，禁止在业务代码中直接使用 RedisTemplate。**

**正例：**
```java
// ✅ 正确：封装缓存服务
@Service
@RequiredArgsConstructor
public class UserCacheService {
    private final RedisTemplate<String, Object> redisTemplate;
    
    /**
     * 缓存用户信息
     */
    public void cacheUser(UserDO user) {
        String key = RedisKeyConstants.USER_INFO + user.getId();
        redisTemplate.opsForValue().set(key, user, Duration.ofMinutes(30));
    }
    
    /**
     * 获取缓存的用户信息
     */
    public UserDO getCachedUser(Long userId) {
        String key = RedisKeyConstants.USER_INFO + userId;
        return (UserDO) redisTemplate.opsForValue().get(key);
    }
    
    /**
     * 删除用户缓存
     */
    public void evictUser(Long userId) {
        redisTemplate.delete(RedisKeyConstants.USER_INFO + userId);
    }
}
```


## 二、MyBatis 规范

### 2.1 Mapper 接口与 XML 映射规范

**【强制】Mapper 接口名与 XML 文件名保持一致，namespace 必须写对应接口的全限定名。**

**正例：**
```java
// ✅ 正确：文件名和 namespace 一致
// 接口：com.example.repository.UserRepository.java
public interface UserRepository {
    UserDO selectById(Long id);
}

// XML：resources/mapper/UserRepository.xml
<mapper namespace="com.example.repository.UserRepository">
    <select id="selectById" resultType="com.example.model.entity.UserDO">
        SELECT id, username, email, created_at
        FROM users
        WHERE id = #{id}
    </select>
</mapper>
```

**【强制】SQL 参数绑定使用 `#{}`，禁止在条件值拼接中使用 `${}`，防止 SQL 注入。**

**【强制】模糊查询应使用 `CONCAT('%', #{keyword}, '%')` 方式。**

**正例：**
```xml
<!-- ✅ 正确：使用 CONCAT 函数 -->
<select id="searchUser" resultType="UserDO">
    SELECT * FROM users WHERE username LIKE CONCAT('%', #{keyword}, '%')
</select>
```

**【推荐】使用 `<where>` 标签替代 `WHERE 1=1`，使用 `<set>` 标签处理 UPDATE 语句。**

**正例：**
```xml
<!-- ✅ 正确：使用 <where> 标签 -->
<select id="selectByCondition" resultType="UserDO">
    SELECT * FROM users
    <where>
        <if test="username != null and username != ''">
            AND username = #{username}
        </if>
        <if test="email != null and email != ''">
            AND email = #{email}
        </if>
    </where>
</select>
```

**【推荐】避免使用 `SELECT *`，明确列出所需字段。**

**正例：**
```xml
<!-- ✅ 正确：只查询需要的字段 -->
<sql id="Base_Column_List">
    id, username, email, status, created_at
</sql>

<select id="selectUserList" resultMap="BaseResultMap">
    SELECT <include refid="Base_Column_List"/>
    FROM users
    WHERE status = #{status}
</select>
```

**【强制】当数据库字段与 Java 属性不一致时，必须使用 `<resultMap>` 显式映射。**

**正例：**
```xml
<!-- ✅ 正确：使用 resultMap 显式映射 -->
<resultMap id="BaseResultMap" type="com.example.model.entity.UserDO">
    <id column="id" property="id"/>
    <result column="user_name" property="userName"/>
    <result column="created_at" property="createdAt"/>
</resultMap>
```


## 三、Kafka MQ 集成规范

**【强制】Topic 命名使用 `项目名.模块名.事件类型` 格式，全部小写，点号分隔。**

**正例：**
```java
// ✅ 正确：Topic 命名
public class KafkaTopicConstants {
    public static final String ORDER_CREATED = "app.order.created";
    public static final String ORDER_PAID = "app.order.paid";
    public static final String USER_REGISTERED = "app.user.registered";
}
```

**【强制】Producer 发送消息必须处理发送失败，不可静默丢弃。**

**反例：**
```java
// ❌ 错误：发送失败无处理
kafkaTemplate.send("app.order.created", message);
```

**正例：**
```java
// ✅ 正确：处理发送结果
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderMessageProducer {
    private final KafkaTemplate<String, String> kafkaTemplate;
    
    /**
     * 发送订单创建消息
     */
    public void sendOrderCreated(Order order) {
        String message = JSON.toJSONString(order);
        String topic = KafkaTopicConstants.ORDER_CREATED;
        try {
            kafkaTemplate.send(topic, order.getId().toString(), message)
                    .addCallback(
                            result -> log.info("消息发送成功, topic={}, orderId={}", topic, order.getId()),
                            ex -> {
                                log.error("消息发送失败, topic={}, orderId={}", topic, order.getId(), ex);
                                // 降级处理：落库重试或降级到本地队列
                                saveToRetryTable(order);
                            }
                    );
        } catch (Exception e) {
            log.error("Kafka 发送异常, topic={}, orderId={}", topic, order.getId(), e);
            throw e;
        }
    }
}
```

**【强制】Consumer 必须实现幂等性，防止消息重复消费。**

**正例：**
```java
// ✅ 正确：幂等消费
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderMessageConsumer {
    private final OrderService orderService;
    private final RedisTemplate<String, Object> redisTemplate;
    
    private static final String PROCESSED_KEY = "app:mq:processed:";
    
    /**
     * 消费订单创建消息
     */
    @KafkaListener(topics = KafkaTopicConstants.ORDER_CREATED, groupId = "order-service")
    public void handleOrderCreated(ConsumerRecord<String, String> record) {
        String messageId = record.key() + ":" + record.partition() + ":" + record.offset();
        String dedupKey = PROCESSED_KEY + messageId;
        
        // 幂等检查：已处理则跳过
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", Duration.ofHours(2));
        if (Boolean.FALSE.equals(isNew)) {
            log.info("消息已处理, 跳过, messageId={}", messageId);
            return;
        }
        
        try {
            Order order = JSON.parseObject(record.value(), Order.class);
            log.info("消费订单创建消息, orderId={}", order.getId());
            orderService.processCreatedOrder(order);
        } catch (Exception e) {
            // 处理失败，删除幂等标记以便重试
            redisTemplate.delete(dedupKey);
            log.error("消息处理失败, messageId={}, offset={}", messageId, record.offset(), e);
            throw e;  // 抛出异常让 Kafka 重试
        }
    }
}
```

**【强制】Consumer 中禁止执行长耗时操作，超时会导致 Rebalance。**

**反例：**
```java
// ❌ 错误：消费者中执行耗时操作
@KafkaListener(topics = "app.order.created")
public void handle(Order order) throws Exception {
    // ❌ 耗时操作（如大量 DB 写入、外部 HTTP 调用）会导致消费超时
    Thread.sleep(30000);
    externalApi.batchCall(order.getItems());
}
```

**正例：**
```java
// ✅ 正确：消费只做轻量处理，耗时操作异步化
@KafkaListener(topics = "app.order.created")
public void handle(Order order) {
    // 只做轻量接收和入库
    orderService.markAsReceived(order);
    // 耗时操作丢到线程池异步处理
    asyncExecutor.execute(() -> orderService.processAsync(order));
}
```


## 四、HTTP 协议与拦截器规范

### 4.1 HTTP 请求规范

**【强制】外部 HTTP 调用必须设置超时时间，禁止无限等待。**

**反例：**
```java
// ❌ 错误：无超时设置
RestTemplate restTemplate = new RestTemplate();  // 默认无限超时
String result = restTemplate.getForObject(url, String.class);
```

**正例：**
```java
// ✅ 正确：设置超时
@Bean
public RestTemplate restTemplate() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(5000);   // 连接超时 5s
    factory.setReadTimeout(10000);     // 读取超时 10s
    return new RestTemplate(factory);
}

// 或使用 WebClient（响应式）
@Bean
public WebClient webClient() {
    return WebClient.builder()
            .baseUrl("https://api.example.com")
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .clientConnector(new ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(Duration.ofSeconds(10))
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            ))
            .build();
}
```

**【强制】外部 HTTP 调用必须打印请求和响应日志。**

**正例：**
```java
// ✅ 正确：通过拦截器统一记录日志
@Component
@Slf4j
public class RestTemplateLoggerInterceptor implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        log.info("HTTP 请求: method={}, url={}, body={}", 
                request.getMethod(), request.getURI(), new String(body));
        try {
            ClientHttpResponse response = execution.execute(request, body);
            log.info("HTTP 响应: status={}", response.getStatusCode());
            return response;
        } catch (Exception e) {
            log.error("HTTP 调用失败: url={}", request.getURI(), e);
            throw e;
        }
    }
}
```

### 4.2 拦截器与过滤器规范

**【强制】拦截器实现 `HandlerInterceptor`，过滤器实现 `Filter`，不可混用。**

| 类型 | 接口 | 执行时机 | 适用场景 |
|------|------|---------|---------|
| Filter | `javax.servlet.Filter` | Servlet 容器层，请求进入 Spring 之前 | 编码、跨域、请求日志 |
| Interceptor | `HandlerInterceptor` | Spring MVC 层，路由匹配之后 | 鉴权、权限、业务前置处理 |

**【强制】拦截器必须通过 `WebMvcConfigurer.addInterceptors` 注册，不可通过 `@Component` 单独使用。**

**正例：**
```java
// ✅ 正确：注册拦截器
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;
    private final LogInterceptor logInterceptor;
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/auth/**")
                .order(1);
        registry.addInterceptor(logInterceptor)
                .addPathPatterns("/api/**")
                .order(2);
    }
}
```


## 快速检查清单

### Redis
- [ ] Key 是否有统一前缀？
- [ ] 所有缓存是否设置了过期时间？
- [ ] 是否避免了 `keys *` 操作？
- [ ] 缓存操作是否封装到独立的 CacheService？

### MyBatis
- [ ] Mapper XML 的 namespace 是否与接口全限定名一致？
- [ ] SQL id 是否与接口方法名一致？
- [ ] 参数绑定是否使用 `#{}` 而非 `${}`？
- [ ] 模糊查询是否使用 `CONCAT('%', #{param}, '%')`？
- [ ] 字段不一致时是否使用 `<resultMap>` 显式映射？
- [ ] 是否避免使用 `SELECT *`？

### Kafka
- [ ] Topic 命名是否使用 `项目名.模块名.事件类型` 格式？
- [ ] Producer 是否处理了发送失败？
- [ ] Consumer 是否实现了幂等性？
- [ ] Consumer 中是否避免了长耗时操作？

### HTTP
- [ ] 外部 HTTP 调用是否设置了超时时间？
- [ ] 外部 HTTP 调用是否打印了请求和响应日志？
- [ ] 拦截器是否通过 `WebMvcConfigurer` 注册？
