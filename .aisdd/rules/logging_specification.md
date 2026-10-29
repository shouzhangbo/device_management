---
name: 代码日志规范（Logging Specification）
version: 1.0.0
description: 覆盖 Java / Python / JavaScript(Node.js) 三大语言栈的日志编写规范，涵盖日志级别使用、格式规范、结构化日志、敏感信息处理、性能、异常打印及禁止行为，为 AI Agent 生成代码提供明确的正反例对照。约束等级分为三级：【强制】（必须遵守）、【推荐】（尽量遵守）、【参考】（可选参考）。
---

# 代码日志规范（Logging Specification）

## 前言

日志是系统可观测性（Observability）的核心组成部分，是排查生产问题、监控系统健康的第一道防线。  
良好的日志习惯能让问题在分钟内定位，糟糕的日志只会产生噪音甚至暴露安全风险。

本规范覆盖 **Java / Python / JavaScript（Node.js）** 三大语言栈，通过正反例对照指导 AI Agent 生成符合生产标准的日志代码。

约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。

---

## 一、日志级别规范

### 1.1 级别定义与使用场景

**【强制】必须按照业务语义选择正确的日志级别，禁止将所有日志统一打为 INFO 或 DEBUG。**

| 级别 | 适用场景 | 示例 |
|------|---------|------|
| `FATAL` / `CRITICAL` | 系统崩溃级别，进程即将退出，需立即人工介入 | OOM、依赖中间件全部不可用 |
| `ERROR` | 业务异常，操作失败，需要关注和处理 | 数据库写入失败、外部调用超时、未捕获异常 |
| `WARN` | 潜在风险，当前可继续运行，但可能引发后续错误 | 降级策略触发、重试次数达到阈值、慢查询 |
| `INFO` | 关键业务节点、系统状态变更，量要少而精 | 服务启动/停止、订单创建成功、用户登录 |
| `DEBUG` | 开发/排查阶段的详细信息，生产环境默认关闭 | 方法入参、中间变量、循环状态 |
| `TRACE` | 比 DEBUG 更细粒度，追踪程序执行路径 | 每一行 SQL 的完整参数 |

**反例：**
```java
// ❌ 错误：所有日志统一打 INFO，缺少级别区分
log.info("用户 {} 登录失败，密码错误", userId);           // 应为 WARN/ERROR
log.info("数据库连接超时，重试第 {} 次", retryCount);     // 应为 WARN
log.info("系统内存不足，即将 OOM");                       // 应为 FATAL/ERROR
log.info("开始处理订单，入参: {}", JSON.toJSON(order));    // DEBUG 级别，生产不应打印
```

**正例：**
```java
// ✅ 正确：按语义选择级别
log.warn("用户 {} 登录失败，密码错误，剩余尝试次数: {}", userId, remainAttempts);
log.warn("数据库连接超时，当前重试第 {} 次，最大重试: {}", retryCount, MAX_RETRY);
log.error("系统内存严重不足，已触发 OOM 保护机制", e);
log.debug("开始处理订单，入参: {}", JSON.toJSON(order));
log.info("订单 {} 创建成功，用时 {}ms", orderId, cost);
```

---

### 1.2 生产环境日志级别控制

**【强制】生产环境日志级别必须设置为 `INFO` 或以上，严禁默认开启 `DEBUG` / `TRACE` 级别。**

**【推荐】支持通过配置中心动态调整日志级别，无需重启服务。**

```yaml
# ✅ 推荐：application.yml（Spring Boot）
logging:
  level:
    root: INFO
    com.example.service: INFO
    # 仅在排查问题时临时调整，排查后恢复
    # com.example.service.OrderService: DEBUG
```

```python
# ✅ 推荐：Python 通过环境变量控制
import logging
import os

log_level = os.getenv("LOG_LEVEL", "INFO").upper()
logging.basicConfig(level=getattr(logging, log_level))
```

---

## 二、日志内容规范

### 2.1 日志必须包含足够的上下文

**【强制】日志必须包含能独立定位问题的上下文信息，禁止打印无意义的字符串。**

**反例：**
```java
// ❌ 错误：日志无上下文，无法定位问题
log.error("处理失败");
log.info("进入方法");
log.error("exception", e);
log.warn("发生错误了!!!");
```

**正例：**
```java
// ✅ 正确：包含业务 ID、操作动作、关键参数
log.error("订单退款处理失败 | orderId={}, userId={}, refundAmount={}, reason={}",
    orderId, userId, refundAmount, reason, e);
log.info("用户登录成功 | userId={}, loginType={}, ip={}, costMs={}",
    userId, loginType, ip, cost);
log.warn("支付超时触发降级 | orderId={}, payChannel={}, timeoutMs={}",
    orderId, payChannel, timeoutMs);
```

---

### 2.2 日志格式规范

**【强制】日志格式必须统一，必须包含：时间戳、日志级别、线程名、类名、方法名、日志内容。**

**【推荐】在分布式系统中，日志必须携带 `traceId`（链路追踪 ID）以便跨服务关联。**

**推荐 Pattern（Java / Logback）：**
```xml
<!-- ✅ 推荐：包含 traceId 的标准格式 -->
<pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId}] %logger{36}.%method:%line - %msg%n</pattern>
```

输出示例：
```
2026-04-28 14:32:15.123 [http-nio-8080-exec-1] ERROR [abc-123-xyz] c.e.s.OrderService.refund:88 - 订单退款处理失败 | orderId=10086, ...
```

**Python（structlog / logging）：**
```python
# ✅ 推荐：使用 structlog 实现结构化日志
import structlog

log = structlog.get_logger()

# 绑定请求上下文（在中间件层注入）
log = log.bind(trace_id="abc-123", user_id=1001)

log.info("order_created", order_id=10086, amount=99.9, cost_ms=12)
log.error("payment_failed", order_id=10086, channel="alipay", error=str(e))
```

**Node.js（pino / winston）：**
```javascript
// ✅ 推荐：使用 pino 实现结构化日志（JSON 格式）
const pino = require('pino');
const logger = pino({ level: process.env.LOG_LEVEL || 'info' });

// 绑定请求上下文
const reqLogger = logger.child({ traceId: req.headers['x-trace-id'], userId });

reqLogger.info({ orderId: 10086, costMs: 12 }, 'order_created');
reqLogger.error({ orderId: 10086, err }, 'payment_failed');
```

---

### 2.3 结构化日志（Structured Logging）

**【推荐】优先使用结构化日志（JSON 格式），方便日志平台（ELK / Loki / 云日志）解析和检索。**

**反例：**
```python
# ❌ 错误：字符串拼接，日志平台无法解析字段
logger.info(f"用户 {user_id} 创建订单 {order_id}，金额 {amount}，耗时 {cost_ms}ms")
```

**正例：**
```python
# ✅ 正确：结构化日志，字段独立可检索
logger.info("order_created", extra={
    "user_id": user_id,
    "order_id": order_id,
    "amount": amount,
    "cost_ms": cost_ms,
    "channel": channel,
})
```

输出（JSON）：
```json
{
  "timestamp": "2026-04-28T14:32:15.123Z",
  "level": "info",
  "event": "order_created",
  "trace_id": "abc-123-xyz",
  "user_id": 1001,
  "order_id": 10086,
  "amount": 99.9,
  "cost_ms": 12,
  "channel": "alipay"
}
```

---

## 三、异常日志规范

### 3.1 异常必须打印完整堆栈

**【强制】捕获异常时，必须将异常对象传入日志方法，打印完整堆栈信息，禁止只打印异常消息（`e.getMessage()`）。**

**反例：**
```java
// ❌ 错误：只打印异常消息，丢失堆栈，无法定位根因
try {
    orderService.refund(orderId);
} catch (Exception e) {
    log.error("退款失败: " + e.getMessage());  // 字符串拼接，无堆栈
    log.error("退款失败");                      // 完全没有异常信息
}
```

**正例：**
```java
// ✅ 正确：异常对象作为最后一个参数传入，Logback 会自动打印堆栈
try {
    orderService.refund(orderId);
} catch (BusinessException e) {
    // 业务异常用 WARN，包含完整上下文
    log.warn("退款失败（业务异常）| orderId={}, errorCode={}, message={}",
        orderId, e.getCode(), e.getMessage());
} catch (Exception e) {
    // 系统/未知异常用 ERROR，必须传入异常对象
    log.error("退款失败（系统异常）| orderId={}", orderId, e);
}
```

**Python：**
```python
# ✅ 正确：使用 exc_info=True 或 logger.exception() 打印完整堆栈
try:
    order_service.refund(order_id)
except BusinessError as e:
    logger.warning("refund_failed_business", order_id=order_id, code=e.code, msg=str(e))
except Exception as e:
    # logger.exception 等价于 logger.error(..., exc_info=True)，自动附加堆栈
    logger.exception("refund_failed_system", order_id=order_id)
```

**Node.js：**
```javascript
// ✅ 正确：将 err 对象传入日志，pino/winston 会序列化堆栈
try {
  await orderService.refund(orderId);
} catch (err) {
  logger.error({ orderId, err }, 'refund_failed');
}
```

---

### 3.2 异常日志不重复打印

**【强制】同一个异常禁止在多个层（Controller / Service / DAO）重复打印，只在最终处理层打印一次。**

**反例：**
```java
// ❌ 错误：每层都打印，同一异常出现 3 条日志，干扰排查
// DAO 层
catch (Exception e) {
    log.error("数据库操作失败", e);  // 第1条
    throw e;
}

// Service 层
catch (Exception e) {
    log.error("业务处理失败", e);   // 第2条（同一异常）
    throw new BusinessException(e);
}

// Controller 层
catch (Exception e) {
    log.error("请求处理失败", e);   // 第3条（同一异常）
    return Result.fail();
}
```

**正例：**
```java
// ✅ 正确：底层抛出，只在统一异常处理器打印一次
// DAO 层：直接抛，不打日志（框架自动包装）
// Service 层：转译为业务异常，不打日志
// 统一异常处理器（GlobalExceptionHandler）：打印一次
@ExceptionHandler(Exception.class)
public Result handleException(Exception e, HttpServletRequest request) {
    log.error("请求处理异常 | uri={}, method={}", request.getRequestURI(), request.getMethod(), e);
    return Result.fail(ErrorCode.SYSTEM_ERROR);
}
```

---

## 四、敏感信息保护规范

### 4.1 禁止打印敏感数据

**【强制】日志中严禁出现以下敏感信息，违反此条视为安全漏洞：**

| 类型 | 示例 |
|------|------|
| 用户密码 | `password`、`passwd`、`pwd` 字段 |
| 身份证号 | 18 位数字 |
| 银行卡号 | 16~19 位数字 |
| 手机号 | 11 位手机号（完整） |
| Token / 密钥 | `token`、`secret`、`apiKey`、`accessKey` |
| 完整 Cookie / Session | `JSESSIONID`、`Authorization` header |

**反例：**
```java
// ❌ 错误：直接打印包含密码的请求对象
log.info("用户注册请求: {}", JSON.toJSON(registerRequest));
// 输出：{"username":"alice","password":"123456","phone":"13800138000"}
```

**正例：**
```java
// ✅ 正确：脱敏处理后再打印
log.info("用户注册请求 | username={}, phone={}",
    registerRequest.getUsername(),
    maskPhone(registerRequest.getPhone()));  // 138****8000

// 工具方法参考
public static String maskPhone(String phone) {
    if (phone == null || phone.length() != 11) return "***";
    return phone.substring(0, 3) + "****" + phone.substring(7);
}

public static String maskIdCard(String idCard) {
    if (idCard == null || idCard.length() < 8) return "***";
    return idCard.substring(0, 4) + "**********" + idCard.substring(idCard.length() - 4);
}
```

**Python：**
```python
# ✅ 正确：注册日志中排除敏感字段
safe_data = {k: v for k, v in register_data.items()
             if k not in ('password', 'id_card', 'bank_card')}
safe_data['phone'] = mask_phone(register_data.get('phone', ''))
logger.info("user_register", **safe_data)
```

---

## 五、性能相关日志规范

### 5.1 避免日志影响性能

**【强制】禁止在日志中进行复杂计算或序列化大对象，特别是 DEBUG 级别的日志。**

**反例：**
```java
// ❌ 错误：无论是否输出，都会执行 JSON 序列化（每次请求都会触发）
log.debug("当前订单列表: " + JSON.toJSON(orderList));        // 字符串拼接
log.debug("当前购物车: {}", cartService.getAllItems(userId)); // 执行了数据库查询
```

**正例：**
```java
// ✅ 正确1：使用占位符 {}，Logback 仅在 DEBUG 级别开启时才序列化
log.debug("当前订单列表: {}", JSON.toJSON(orderList));

// ✅ 正确2（最佳）：使用 isDebugEnabled() 守卫，彻底避免不必要的计算
if (log.isDebugEnabled()) {
    log.debug("当前订单列表: {}", JSON.toJSON(orderList));
}

// ✅ 正确3（Java 8+）：使用 Lambda 懒计算
log.debug("当前订单列表: {}", () -> JSON.toJSON(orderList));
```

**Python：**
```python
# ✅ 正确：不要用 f-string / % 格式化后传入（即使级别未开启也会格式化）
# ❌ 错误
logger.debug(f"订单列表: {json.dumps(order_list)}")  # 无论级别都会序列化

# ✅ 正确：传参延迟格式化（标准 logging 模块）
logger.debug("订单列表: %s", order_list)             # 只在 DEBUG 开启时才序列化

# ✅ 正确：structlog 的 lazy evaluation
logger.debug("order_list_dump", count=len(order_list))  # 不序列化完整列表
```

---

### 5.2 关键链路性能日志

**【推荐】对核心业务接口（支付、下单、核心查询），必须打印接口耗时日志，便于性能监控。**

```java
// ✅ 推荐：使用 StopWatch 打印耗时
@Around("execution(* com.example.service.*.*(..))")
public Object logExecutionTime(ProceedingJoinPoint pjp) throws Throwable {
    long start = System.currentTimeMillis();
    String methodName = pjp.getSignature().toShortString();
    try {
        Object result = pjp.proceed();
        long cost = System.currentTimeMillis() - start;
        if (cost > 1000) {
            log.warn("慢方法告警 | method={}, costMs={}", methodName, cost);
        } else {
            log.info("方法执行完成 | method={}, costMs={}", methodName, cost);
        }
        return result;
    } catch (Throwable e) {
        long cost = System.currentTimeMillis() - start;
        log.error("方法执行异常 | method={}, costMs={}", methodName, cost, e);
        throw e;
    }
}
```

```python
# ✅ 推荐：FastAPI 中间件打印耗时
import time
from fastapi import Request

@app.middleware("http")
async def log_requests(request: Request, call_next):
    start = time.time()
    response = await call_next(request)
    cost_ms = (time.time() - start) * 1000
    level = "warning" if cost_ms > 1000 else "info"
    getattr(logger, level)(
        "http_request",
        method=request.method,
        path=request.url.path,
        status=response.status_code,
        cost_ms=round(cost_ms, 2),
    )
    return response
```

---

## 六、禁止行为清单

**【强制】以下行为在任何情况下都不允许出现：**

| # | 禁止行为 | 原因 |
|---|---------|------|
| 1 | `System.out.println()` / `print()` 打印业务日志 | 无法控制级别、无法关闭、无格式、无上下文 |
| 2 | `e.printStackTrace()` 打印异常 | 输出到 stderr，与日志系统割裂，无法聚合 |
| 3 | 使用字符串拼接（`+`）构建日志消息 | 即使日志级别关闭也会执行拼接，浪费 CPU |
| 4 | 在循环内部打印 INFO 级别日志 | 产生海量日志，严重影响性能和检索效率 |
| 5 | 打印密码、Token、完整手机号等敏感信息 | 安全漏洞，违反数据安全法规 |
| 6 | 捕获异常后只打印消息不传异常对象 | 丢失堆栈信息，无法定位根因 |
| 7 | 同一异常在多层重复打印 | 日志噪音，干扰排查 |
| 8 | 注释掉日志而非删除或降级 | 代码污染，混淆意图 |
| 9 | 生产环境开启 DEBUG/TRACE 级别 | 敏感信息泄露 + 严重性能下降 |
| 10 | 使用不同类的 Logger（如把 A 类的日志打到 B 类 Logger） | 定位困难，误导排查方向 |

**反例（集中展示）：**
```java
// ❌ 以下全部禁止
System.out.println("订单创建成功: " + orderId);       // 禁止1
e.printStackTrace();                                   // 禁止2
log.info("处理数据: " + JSON.toJSON(data));            // 禁止3
for (Order o : orders) {
    log.info("处理订单: {}", o.getId());              // 禁止4
}
log.info("用户密码: {}", user.getPassword());          // 禁止5
catch (Exception e) { log.error(e.getMessage()); }    // 禁止6
```

---

## 七、各语言最佳实践汇总

### 7.1 Java（Logback + SLF4J）

```java
// ✅ 标准引入方式（使用 Lombok 的 @Slf4j 注解）
@Slf4j
@Service
public class OrderService {
    
    public OrderResult createOrder(CreateOrderRequest request) {
        log.info("创建订单开始 | userId={}, itemCount={}, totalAmount={}",
            request.getUserId(), request.getItems().size(), request.getTotalAmount());
        
        long start = System.currentTimeMillis();
        try {
            OrderResult result = doCreateOrder(request);
            log.info("创建订单成功 | orderId={}, userId={}, costMs={}",
                result.getOrderId(), request.getUserId(), System.currentTimeMillis() - start);
            return result;
        } catch (InsufficientStockException e) {
            log.warn("创建订单失败（库存不足）| userId={}, itemId={}, required={}, available={}",
                request.getUserId(), e.getItemId(), e.getRequired(), e.getAvailable());
            throw e;
        } catch (Exception e) {
            log.error("创建订单失败（系统异常）| userId={}, costMs={}",
                request.getUserId(), System.currentTimeMillis() - start, e);
            throw new SystemException("订单创建失败", e);
        }
    }
}
```

### 7.2 Python（structlog）

```python
import structlog

logger = structlog.get_logger(__name__)

class OrderService:
    
    def create_order(self, request: CreateOrderRequest) -> OrderResult:
        log = logger.bind(user_id=request.user_id, trace_id=request.trace_id)
        log.info("create_order_start", item_count=len(request.items), amount=request.total_amount)
        
        import time
        start = time.time()
        try:
            result = self._do_create_order(request)
            log.info("create_order_success",
                     order_id=result.order_id,
                     cost_ms=round((time.time() - start) * 1000, 2))
            return result
        except InsufficientStockError as e:
            log.warning("create_order_failed_stock",
                        item_id=e.item_id, required=e.required, available=e.available)
            raise
        except Exception:
            log.exception("create_order_failed_system",
                          cost_ms=round((time.time() - start) * 1000, 2))
            raise SystemError("订单创建失败")
```

### 7.3 Node.js（pino）

```javascript
const pino = require('pino');
const logger = pino({ level: process.env.LOG_LEVEL || 'info' });

class OrderService {
  async createOrder(request) {
    const log = logger.child({ userId: request.userId, traceId: request.traceId });
    log.info({ itemCount: request.items.length, amount: request.totalAmount }, 'create_order_start');
    
    const start = Date.now();
    try {
      const result = await this._doCreateOrder(request);
      log.info({ orderId: result.orderId, costMs: Date.now() - start }, 'create_order_success');
      return result;
    } catch (err) {
      if (err instanceof InsufficientStockError) {
        log.warn({ itemId: err.itemId, required: err.required, available: err.available },
          'create_order_failed_stock');
      } else {
        log.error({ err, costMs: Date.now() - start }, 'create_order_failed_system');
      }
      throw err;
    }
  }
}
```

---

## 八、日志文件存放规范

### 8.1 默认日志目录结构

**【强制】除非用户明确创建或指定日志目录，否则默认日志目录应为项目根目录下的 `logs/` 目录。**

**【强制】按日志级别分离文件，默认文件名规范如下：**

| 日志级别 | 文件名 | 说明 |
|---------|--------|------|
| INFO | `logs/info.log` | 记录 INFO 级别日志 |
| WARN | `logs/warn.log` | 记录 WARN 级别日志 |
| ERROR | `logs/error.log` | 记录 ERROR 级别日志 |

**【推荐】目录不存在时应自动创建，避免启动失败。**

**Java 示例（logback-spring.xml）：**
```xml
<!-- ✅ 正确：按级别分离，默认存放在 logs/ 目录 -->
<appender name="INFO_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/info.log</file>
    <filter class="ch.qos.logback.classic.filter.LevelFilter">
        <level>INFO</level>
        <onMatch>ACCEPT</onMatch>
        <onMismatch>DENY</onMismatch>
    </filter>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
        <fileNamePattern>logs/info.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
        <maxHistory>30</maxHistory>
    </rollingPolicy>
</appender>

<appender name="WARN_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/warn.log</file>
    <filter class="ch.qos.logback.classic.filter.LevelFilter">
        <level>WARN</level>
        <onMatch>ACCEPT</onMatch>
        <onMismatch>DENY</onMismatch>
    </filter>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
        <fileNamePattern>logs/warn.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
        <maxHistory>30</maxHistory>
    </rollingPolicy>
</appender>

<appender name="ERROR_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/error.log</file>
    <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
        <level>ERROR</level>
    </filter>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
        <fileNamePattern>logs/error.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
        <maxHistory>30</maxHistory>
    </rollingPolicy>
</appender>
```

**Python 示例：**
```python
# ✅ 正确：按级别分离，自动创建 logs/ 目录
import os
import logging
from logging.handlers import RotatingFileHandler

# 默认日志目录为项目根目录下的 logs/
LOG_DIR = os.getenv("LOG_DIR", "logs")
os.makedirs(LOG_DIR, exist_ok=True)  # 自动创建目录

# 按级别分离文件
handlers = {
    "info": RotatingFileHandler(f"{LOG_DIR}/info.log", maxBytes=10*1024*1024, backupCount=5),
    "warn": RotatingFileHandler(f"{LOG_DIR}/warn.log", maxBytes=10*1024*1024, backupCount=5),
    "error": RotatingFileHandler(f"{LOG_DIR}/error.log", maxBytes=10*1024*1024, backupCount=5),
}

handlers["info"].setLevel(logging.INFO)
handlers["warn"].setLevel(logging.WARNING)
handlers["error"].setLevel(logging.ERROR)

# 添加过滤器确保只记录对应级别
handlers["info"].addFilter(lambda r: r.levelno == logging.INFO)
handlers["warn"].addFilter(lambda r: r.levelno == logging.WARNING)
```

**Node.js 示例（pino）：**
```javascript
// ✅ 正确：按级别分离，默认 logs/ 目录
const pino = require('pino');
const fs = require('fs');
const path = require('path');

const LOG_DIR = process.env.LOG_DIR || 'logs';
// 自动创建目录
if (!fs.existsSync(LOG_DIR)) {
    fs.mkdirSync(LOG_DIR, { recursive: true });
}

const logger = pino({
    level: process.env.LOG_LEVEL || 'info',
}, pino.multistream([
    { level: 'info', stream: pino.destination({ dest: path.join(LOG_DIR, 'info.log'), sync: false }) },
    { level: 'warn', stream: pino.destination({ dest: path.join(LOG_DIR, 'warn.log'), sync: false }) },
    { level: 'error', stream: pino.destination({ dest: path.join(LOG_DIR, 'error.log'), sync: false }) },
]));
```

---

## 九、日志配置参考

### 9.1 Java（logback-spring.xml）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- 控制台输出（开发环境） -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId}] %logger{36} - %msg%n</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <!-- 滚动文件输出（生产环境） -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/app.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/app.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <timeBasedFileNamingAndTriggeringPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedFNATP">
                <maxFileSize>500MB</maxFileSize>
            </timeBasedFileNamingAndTriggeringPolicy>
            <maxHistory>30</maxHistory>       <!-- 保留 30 天 -->
            <totalSizeCap>10GB</totalSizeCap>  <!-- 总上限 10GB -->
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId}] %logger{36} - %msg%n</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <!-- 异步输出（生产必备，避免 I/O 阻塞业务线程） -->
    <appender name="ASYNC_FILE" class="ch.qos.logback.classic.AsyncAppender">
        <discardingThreshold>0</discardingThreshold>   <!-- 不丢弃任何日志 -->
        <queueSize>1024</queueSize>
        <includeCallerData>false</includeCallerData>   <!-- 性能优先，不记录调用栈位置 -->
        <appender-ref ref="FILE"/>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>  <!-- 本地开发 -->
        <appender-ref ref="ASYNC_FILE"/>
    </root>
</configuration>
```

### 9.2 Python（structlog 配置）

```python
import structlog
import logging
import sys

def configure_logging(log_level: str = "INFO"):
    """初始化结构化日志（生产环境输出 JSON，开发环境输出可读格式）"""
    import os
    is_production = os.getenv("ENV", "dev") == "prod"

    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,              # 合并请求上下文变量
            structlog.stdlib.filter_by_level,
            structlog.stdlib.add_logger_name,
            structlog.stdlib.add_log_level,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.processors.StackInfoRenderer(),
            structlog.processors.format_exc_info,
            structlog.processors.JSONRenderer() if is_production   # 生产：JSON
            else structlog.dev.ConsoleRenderer(),                  # 开发：可读
        ],
        wrapper_class=structlog.stdlib.BoundLogger,
        context_class=dict,
        logger_factory=structlog.stdlib.LoggerFactory(),
        cache_logger_on_first_use=True,
    )
    logging.basicConfig(
        format="%(message)s",
        stream=sys.stdout,
        level=getattr(logging, log_level.upper()),
    )
```

### 9.3 Node.js（pino 配置）

```javascript
// logger.js
const pino = require('pino');

const logger = pino({
  level: process.env.LOG_LEVEL || 'info',
  // 生产环境：JSON 格式；本地开发：使用 pino-pretty 美化输出
  transport: process.env.NODE_ENV !== 'production'
    ? { target: 'pino-pretty', options: { colorize: true } }
    : undefined,
  // 重命名内置字段，与日志平台规范对齐
  timestamp: pino.stdTimeFunctions.isoTime,
  formatters: {
    level: (label) => ({ level: label }),
  },
  // 脱敏规则（pino 内置 redact）
  redact: {
    paths: ['*.password', '*.token', '*.secret', '*.apiKey', 'req.headers.authorization'],
    censor: '[REDACTED]',
  },
});

module.exports = logger;
```

---

> 📎 **关联规范**：
> - [Java 基础规范](./java_basic_specification.md) — 异常处理章节
> - [Java 日志打印规范](./java_logging_specification.md) — 日志级别与敏感操作
> - [Python 开发规范](./python_specification.md) — 错误处理章节
> - [JavaScript 开发规范](./js_specification.md) — 错误处理章节
