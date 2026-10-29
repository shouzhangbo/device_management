---
name: Java 日志打印规范
version: 1.0.0
description: 整合阿里巴巴 Java 开发手册日志规范，覆盖日志级别使用、核心模块日志、敏感操作日志（增删改）、日志格式与敏感信息处理，为 AI Agent 生成代码提供正反例对照。约束等级分为三级：【强制】（必须遵守）、【推荐】（尽量遵守）、【参考】（可选参考）。
---

# Java 日志打印规范

## 前言

本规范整合阿里巴巴 Java 开发手册日志规范，并补充核心模块、敏感操作的日志打印要求。日志是系统可观测性的核心，是排查生产问题的第一道防线。

约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。


## 一、日志级别使用规范

**【强制】日志级别使用规则：**

| 级别 | 使用场景 | 示例 |
|------|---------|------|
| `ERROR` | 系统异常、业务失败、影响功能的错误 | `log.error("订单创建失败, orderId={}", orderId, e)` |
| `WARN` | 可恢复的异常、降级、非预期但可处理的情况 | `log.warn("重试第{}次, orderId={}", retryCount, orderId)` |
| `INFO` | 核心业务流程、关键状态变更、入口参数、外部依赖结果 | `log.info("订单创建成功, orderId={}", orderId)` |
| `DEBUG` | 开发调试、SQL 执行细节、中间过程 | `log.debug("查询参数: username={}", username)` |
| `TRACE` | 极其详细的执行轨迹（生产环境默认关闭） | `log.trace("进入方法, params={}", params)` |

**【强制】禁止使用 `System.out` / `System.err` 输出日志，必须使用日志框架。**

**反例：**
```java
// ❌ 错误：使用 System.out
System.out.println("用户创建成功: " + username);
e.printStackTrace();
```

**正例：**
```java
// ✅ 正确：使用日志框架
log.info("用户创建成功, username={}", username);
log.error("用户创建失败, username={}", username, e);
```


## 二、核心模块日志打印规范

**【强制】核心业务流程必须在关键节点输出日志，至少覆盖：入口参数、关键状态变化、外部依赖调用结果和异常分支。**

**反例：**
```java
// ❌ 错误：核心流程无关键日志，故障时难以排查
public void createOrder(Order order) {
    deductStock(order);
    paymentClient.pay(order);
    orderRepository.save(order);
}
```

**正例：**
```java
// ✅ 正确：核心流程关键节点有日志
@Slf4j
public class OrderService {
    public void createOrder(Order order) {
        log.info("订单创建开始, orderId={}, userId={}", order.getId(), order.getUserId());
        try {
            // 先锁库存再支付，避免并发场景下出现超卖
            deductStock(order);
            log.info("库存锁定成功, orderId={}", order.getId());
            paymentClient.pay(order);
            log.info("支付调用成功, orderId={}", order.getId());
            orderRepository.save(order);
            log.info("订单保存成功, orderId={}", order.getId());
        } catch (Exception e) {
            log.error("订单创建失败, orderId={}, userId={}", order.getId(), order.getUserId(), e);
            throw e;
        }
    }
}
```

**【强制】外部依赖调用（HTTP/RPC/DB）前后必须打印日志，记录请求参数和响应结果。**

**正例：**
```java
// ✅ 正确：外部调用前后有日志
public PayResult pay(Order order) {
    log.info("支付请求开始, orderId={}, amount={}", order.getId(), order.getAmount());
    try {
        PayResult result = paymentClient.pay(order);
        log.info("支付响应成功, orderId={}, result={}", order.getId(), result.getCode());
        return result;
    } catch (Exception e) {
        log.error("支付调用异常, orderId={}, amount={}", order.getId(), order.getAmount(), e);
        throw e;
    }
}
```


## 三、敏感操作日志打印规范

**【强制】新增（INSERT）、修改（UPDATE）、删除（DELETE）等敏感数据操作必须打印操作日志，记录操作人、操作对象、操作前后值。**

**反例：**
```java
// ❌ 错误：敏感操作无日志
public void deleteUser(Long userId) {
    userRepository.deleteById(userId);
}
```

**正例：**
```java
// ✅ 正确：敏感操作有完整日志
public void deleteUser(Long userId, String operator) {
    UserDO existingUser = userRepository.selectById(userId);
    if (existingUser == null) {
        throw new BusinessException(4004, "用户不存在");
    }
    // 删除前记录操作日志
    log.info("删除用户, operator={}, userId={}, username={}, beforeStatus={}",
             operator, userId, existingUser.getUsername(), existingUser.getStatus());
    userRepository.deleteById(userId);
    log.info("用户删除成功, operator={}, userId={}", operator, userId);
}
```

**【强制】更新操作必须记录变更前后的关键字段值。**

**正例：**
```java
// ✅ 正确：更新操作记录前后值
public void updateUserStatus(Long userId, Integer newStatus, String operator) {
    UserDO before = userRepository.selectById(userId);
    log.info("更新用户状态, operator={}, userId={}, beforeStatus={}, afterStatus={}",
             operator, userId, before.getStatus(), newStatus);
    before.setStatus(newStatus);
    userRepository.updateById(before);
    log.info("用户状态更新成功, userId={}, currentStatus={}", userId, newStatus);
}
```

**【强制】批量操作必须记录操作总数和成功/失败计数。**

**正例：**
```java
// ✅ 正确：批量操作记录统计
public void batchImportUsers(List<UserDO> users) {
    int success = 0;
    int failed = 0;
    log.info("批量导入用户开始, totalCount={}", users.size());
    for (UserDO user : users) {
        try {
            userRepository.insert(user);
            success++;
        } catch (Exception e) {
            log.error("导入用户失败, username={}", user.getUsername(), e);
            failed++;
        }
    }
    log.info("批量导入用户完成, total={}, success={}, failed={}", users.size(), success, failed);
}
```


## 四、日志格式与内容规范

**【强制】日志使用占位符 `{}`，禁止使用字符串拼接。**

**反例：**
```java
// ❌ 错误：字符串拼接（即使日志级别不输出也会执行拼接开销）
log.debug("查询用户: " + username + ", email: " + email);
```

**正例：**
```java
// ✅ 正确：使用占位符
log.debug("查询用户: username={}, email={}", username, email);
```

**【强制】异常日志必须打印完整堆栈，使用最后一个参数传递 Throwable 对象。**

**反例：**
```java
// ❌ 错误：异常堆栈丢失
log.error("处理失败: " + e.getMessage());  // 只有 message，没有堆栈
log.error("处理失败", e.getMessage());
```

**正例：**
```java
// ✅ 正确：异常对象作为最后一个参数
log.error("处理失败, orderId={}", orderId, e);  // 输出完整堆栈
```

**【强制】禁止打印敏感信息（密码、身份证、银行卡号、Token 等）。**

**反例：**
```java
// ❌ 错误：打印敏感信息
log.info("用户登录, username={}, password={}", username, password);
log.info("支付请求, cardNo={}", cardNo);
```

**正例：**
```java
// ✅ 正确：脱敏处理
log.info("用户登录, username={}", username);
log.info("支付请求, cardNo={}****{}", cardNo.substring(0, 4), cardNo.substring(cardNo.length() - 4));
```


## 快速检查清单

- [ ] 是否使用日志框架而非 `System.out`？
- [ ] 日志是否使用 `{}` 占位符而非字符串拼接？
- [ ] 异常日志是否传递 Throwable 对象打印完整堆栈？
- [ ] 核心流程关键节点是否打印了日志？
- [ ] 敏感操作（增/删/改）是否记录了操作人和操作对象？
- [ ] 更新操作是否记录了变更前后的值？
- [ ] 批量操作是否记录了成功/失败计数？
- [ ] 是否避免了打印密码、身份证等敏感信息？
