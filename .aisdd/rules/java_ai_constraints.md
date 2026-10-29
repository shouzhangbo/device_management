---
name: AI Agent 代码生成核心约束（Java）
version: 1.0.0
description: AI Agent 生成 Java 代码时的最高优先级约束，覆盖不确定需求处理（TODO 伪代码）、清晰架构分层、禁止过度编程、禁止过度抽象、代码清洁与可读性、每个函数中文注释、敏感操作（写库/Redis/Kafka/外部调用）与核心逻辑强制日志、代码通用性原则。凌驾于所有技术规范之上。
---

# AI Agent 代码生成核心约束（Java）

> **本规范是 AI Agent 生成代码时的最高优先级约束，凌驾于所有技术规范之上。**

## 一、不确定需求处理原则

**【强制】遇到不确定的需求或缺少必要信息时，必须以 TODO 伪代码标注并停止实现，禁止自行虚拟、猜测或补充需求。**

**反例：**
```java
// ❌ 错误：AI 自行猜测业务逻辑
public void processRefund(Order order) {
    // AI 自行假设退款逻辑
    BigDecimal refundAmount = order.getAmount().multiply(new BigDecimal("0.8"));  // ❌ 猜测退款比例
    refundService.refund(order.getPaymentId(), refundAmount);
    // AI 自行假设要发通知
    notificationService.sendRefundEmail(order);  // ❌ 猜测需要发邮件
}
```

**正例：**
```java
// ✅ 正确：不确定部分以 TODO 标注，不猜测实现
public void processRefund(Order order) {
    log.info("退款流程开始, orderId={}", order.getId());
    
    // TODO[AI]: 退款金额计算规则未在需求中明确，需确认是否全额退款还是按比例退款
    // BigDecimal refundAmount = ???;
    
    // TODO[AI]: 退款后是否需要通知用户，通知渠道（邮件/短信/站内信）未明确
    // notificationService.???;
    
    // 确定部分正常实现
    refundService.refund(order.getPaymentId(), refundAmount);
}
```

**【强制】TODO 注释必须标注原因和待确认项，格式为 `// TODO[AI]: 待确认/待实现的描述`。**

**正例：**
```java
// TODO[AI]: 支付渠道选择逻辑未在需求中定义，待产品确认后补充
// PaymentChannel channel = determineChannel(order);

// TODO[AI]: 订单取消是否需要回滚已扣减的库存，需求未说明
// if (shouldRollbackStock) { stockService.restore(order); }
```


## 二、避免过度编程与过度抽象（最高优先级【强制】）

> **定义**：过度编程 = 实现了需求之外的功能、实体、分支、依赖或代码路径；过度抽象 = 为当前需求引入了不必要的接口、基类、设计模式、泛化封装或额外分层。
> **后果**：违反本节约定的任何一条，视为**任务失败**，禁止提交。编码完成后必须对照"提交前自查"逐项核对，不通过则先删除越界代码、重跑单测，再谈完成。

### 2.1 避免过度编程

**【强制规则】编码前先建需求清单，编码时只写"需求明确要求"的内容。**

1. **【编码前建两栏清单】** 开始编码前，将任务需求拆分为两栏并逐条列出：
   - **需求明确要求（必须实现）**：如"校验用户名唯一性""保存订单"
   - **需求未提及（禁止实现）**：缓存、重试、通知、埋点、权限、统计、定时任务、格式校验、日志中间件、默认角色等
   只实现"明确要求"栏；"未提及"栏内容若确实必要，用 `// TODO[AI]:` 标注待确认，**不得直接实现**。
2. **【最小实现优先】** 先写满足验收的**最小可行实现**；任何额外能力必须能在任务描述或技术设计文档中找到原文依据，找不到即为越界。
3. **【新增依赖门槛】** 新增第三方依赖（如 Guava、Apache Commons）、中间件调用（Redis/Kafka/ES）必须能在 PRD 或技术设计中找到依据；找不到 → 标 `// TODO[AI]:` 并停止，禁止自行引入。
4. **【一方法一职责】** 每个方法只做需求要求的一件事，禁止"顺手/顺便/加上更完善"式扩展。
5. **【代码量自检】** 方法超过 80 行，或单个方法出现 ≥2 个处理不同业务的 `if` 分支时，必须停下来自查是否混入了非需求逻辑。
6. **【越界即停止】** 一旦产生"加上这个更健壮/更完善"的念头，立即停止实现，改标 `// TODO[AI]:`，不得写进正式代码。

**反例：**
```java
// ❌ 错误：过度编程，自行增加了需求中未提到的功能
// 需求：创建用户时校验用户名唯一性
public UserVO createUser(UserDTO dto) {
    // ❌ 以下都是 AI 自行补充的非需求内容：
    validatePasswordStrength(dto.getPassword());   // 需求未要求密码强度校验
    checkEmailDomain(dto.getEmail());              // 需求未要求邮箱域名校验
    assignDefaultRole(dto);                        // 需求未要求默认角色
    sendWelcomeNotification(dto);                  // 需求未要求欢迎通知
    trackUserSource(dto);                          // 需求未要求来源追踪

    // ✅ 需求要求的内容
    validateUsernameUnique(dto.getUsername());
    UserDO user = buildUser(dto);
    userRepository.insert(user);
    return convertToVO(user);
}
```

**正例：**
```java
// ✅ 正确：只实现需求要求的功能
public UserVO createUser(UserDTO dto) {
    // 需求：校验用户名唯一性
    validateUsernameUnique(dto.getUsername());
    UserDO user = buildUser(dto);
    userRepository.insert(user);
    return convertToVO(user);
}
```

### 2.2 避免过度抽象

**【强制规则】能直接实现就不抽象；抽象必须满足"真实存在 ≥2 个实现/调用方"。**

1. **【单实现禁止抽象】** 一个抽象（接口、基类、抽象方法、工厂）当前只有 **1 个实现**时，禁止创建抽象层，直接写实现类。
2. **【两处真实复用才提取】** 公共代码只有在**同一需求内 ≥2 处真实使用**时才允许提取；仅 1 处使用禁止提取公共方法/工具类。
3. **【设计模式强门槛】** 设计模式（工厂/策略/模板方法/观察者/Builder 等）只在需求或技术设计文档明确要求时使用；"提高扩展性/将来可能需要"**不构成**使用理由。
4. **【禁止自造分层】** 只允许使用项目既定分层（如 Controller/Service/Repository/Converter）；禁止在既定分层之外自创额外抽象层（如额外的 Facade、ApplicationService、抽象 Mapper）。
5. **【简单模型先行】** 能用基础类型/DTO/枚举表达的，禁止引入值对象、领域模型、泛型工厂等复杂建模。
6. **【不维护"以防万一"抽象】** 若演进后某抽象只剩 1 个实现，必须删除抽象直接实现，禁止"以防万一"保留。

**反例：**
```java
// ❌ 错误：过度抽象，需求只要求一个简单查询，却创建两个接口 + 实现类
public interface UserRepository {
    UserDO selectById(Long id);
}

public interface UserRepositoryCustom {   // ❌ 当前只有 1 个实现，禁止抽象
    UserDO selectByUsername(String username);
}

public class UserRepositoryImpl implements UserRepository, UserRepositoryCustom {
    // ❌ 两个接口 + 实现类，需求只要求一个简单查询
}
```

**正例：**
```java
// ✅ 正确：直接实现，不做过度抽象
@Mapper
public interface UserRepository {
    UserDO selectById(Long id);
}
```

**【强制】禁止引入项目未使用或未声明的依赖。**

**反例：**
```java
// ❌ 错误：项目未引入 Guava，AI 自行使用
List<String> result = Lists.newArrayList();  // Guava 的 Lists
```

**正例：**
```java
// ✅ 正确：使用 JDK 或项目已引入的依赖
List<String> result = new ArrayList<>();
```

### 2.3 提交前自查（必须逐项通过）

- 需求原文出现过"缓存"吗？没有 → 删除所有缓存代码
- 需求原文出现过"通知/重试/埋点/统计/定时任务"吗？没有 → 删除对应代码
- 每个新增依赖都能指出它在需求或技术设计中的出处吗？指不出 → 删除
- 我创建的每个接口/基类/抽象方法，当前有 ≥2 个真实实现吗？没有 → 删除抽象，直接实现
- 我提取的每个公共方法，有 ≥2 处真实调用吗？没有 → 撤销提取
- 我使用的每个设计模式，在需求/技术设计中有出处吗？没有 → 移除
- 删除越界代码/抽象后，是否已重跑单测且全部通过？


## 三、代码清洁与可读性原则

**【强制】代码必须保持清洁、可读性强，禁止保留废弃代码、注释掉的代码块。**

**反例：**
```java
// ❌ 错误：保留废弃代码
public void process(Order order) {
    // oldVersion(order);
    // oldVersion2(order);
    // if (oldFlag) { ... }
    newVersionProcess(order);
}
```

**正例：**
```java
// ✅ 正确：干净整洁
public void process(Order order) {
    newVersionProcess(order);
}
```

**【强制】方法命名必须能表达意图，禁止使用 `process` / `handle` / `doSomething` 等模糊命名。**

**反例：**
```java
// ❌ 错误：命名模糊
public void process(Order order) { }
public void handle(User user) { }
public void doSomething(String input) { }
```

**正例：**
```java
// ✅ 正确：命名表达意图
public void createOrder(Order order) { }
public void verifyUser(User user) { }
public void parseOrderXml(String xml) { }
```

**【强制】禁止过度嵌套（超过 3 层），应使用提前返回或提取方法减少嵌套。**

**反例：**
```java
// ❌ 错误：4 层嵌套
public void process(Order order) {
    if (order != null) {
        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                if (item.getStatus() == Status.PENDING) {
                    // ...
                }
            }
        }
    }
}
```

**正例：**
```java
// ✅ 正确：提前返回，减少嵌套
public void process(Order order) {
    if (order == null) {
        return;
    }
    List<OrderItem> items = order.getItems();
    if (items == null || items.isEmpty()) {
        return;
    }
    for (OrderItem item : items) {
        processPendingItem(item);
    }
}

private void processPendingItem(OrderItem item) {
    if (item.getStatus() != Status.PENDING) {
        return;
    }
    // ...
}
```

**【强制】魔法数字和魔法字符串必须定义为常量。**

**反例：**
```java
// ❌ 错误：魔法值
if (user.getStatus() == 1) { }
if ("ADMIN".equals(user.getRole())) { }
```

**正例：**
```java
// ✅ 正确：定义为常量
private static final int USER_STATUS_ACTIVE = 1;
private static final String ROLE_ADMIN = "ADMIN";

if (user.getStatus() == USER_STATUS_ACTIVE) { }
if (ROLE_ADMIN.equals(user.getRole())) { }
```


## 四、代码通用性原则

**【强制】保持一定的代码通用性，不与特定业务场景过度耦合，但不违反"禁止过度编程"原则。**

**正例：**
```java
// ✅ 正确：通用的时间范围查询参数
@Data
public class DateRangeQuery {
    private LocalDate startDate;
    private LocalDate endDate;
}

// ✅ 正确：通用的分页参数
@Data
public class PageQuery {
    @Min(value = 1, message = "页码最小为1")
    private Integer page = 1;
    @Range(min = 1, max = 100, message = "每页大小1-100")
    private Integer size = 10;
    
    public int getOffset() {
        return (page - 1) * size;
    }
}

// ✅ 正确：通用的枚举模式，不硬编码状态值
public enum OrderStatus {
    PENDING("待支付"),
    PAID("已支付"),
    SHIPPED("已发货"),
    COMPLETED("已完成"),
    CANCELLED("已取消");
    
    private final String description;
    
    OrderStatus(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
}
```


## 五、清晰架构原则

**【强制】代码必须保持清晰的架构分层，禁止随意堆叠逻辑、跨层调用或在不该出现的位置放置代码。**

**分层约束（典型分层）：**

```
Controller（接口层） → Service（业务层） → Repository/Dao（数据层）
                        ↓
               外部服务/中间件（Redis/Kafka/MQ/第三方 HTTP）
```

**反例：**
```java
// ❌ 错误：Controller 直接操作数据库、混杂业务逻辑与格式转换
@RestController
public class OrderController {
    @Autowired
    private JdbcTemplate jdbcTemplate;  // ❌ 接口层直接依赖数据层

    @GetMapping("/order/{id}")
    public String getOrder(@PathVariable Long id) {
        // ❌ Controller 里写 SQL、拼 JSON、做业务判断
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM t_order WHERE id = ?", id);
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            sb.append("{").append(row.get("order_no")).append("}");
        }
        if (rows.isEmpty()) {
            return "订单不存在";  // ❌ 返回格式不统一
        }
        return sb.toString();
    }
}
```

**正例：**
```java
// ✅ 正确：分层清晰，接口层只做参数校验与结果封装
@RestController
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;

    @GetMapping("/order/{id}")
    public Result<OrderVO> getOrder(@PathVariable Long id) {
        // 接口层职责：参数校验 + 调用业务层 + 统一返回结构
        return Result.success(orderService.getOrderById(id));
    }
}

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {
    private final OrderRepository orderRepository;  // 业务层依赖数据层接口

    public OrderVO getOrderById(Long id) {
        OrderDO order = orderRepository.selectById(id);
        if (order == null) {
            throw new BusinessException(4004, "订单不存在");
        }
        return OrderConverter.toVO(order);  // 转换逻辑收敛到 Converter
    }
}
```

**【强制】每个类/模块必须职责单一，禁止一个方法承担"校验+业务+转换+持久化"全部职责。**

**【强制】禁止跨层调用（如 Controller 直接访问 Repository、Service 直接操作数据库连接），必须遵守技术设计文档约定的分层与依赖方向。**


## 六、强制注释规范

**【强制】每个函数（public 方法、private 方法）必须编写中文注释，说明方法职责、参数含义、返回值与关键约束；类必须编写类级注释。**

> 本约束凌驾于编码效率之上。无注释的代码即使逻辑正确，也视为未完成，禁止提交。

**反例：**
```java
// ❌ 错误：方法无注释，后续维护者无法理解
public OrderVO build(OrderDO o, List<OrderItemDO> items) {
    BigDecimal total = BigDecimal.ZERO;
    for (OrderItemDO item : items) {
        total = total.add(item.getPrice().multiply(item.getCount()));
    }
    o.setTotalAmount(total);
    return converter.convert(o);
}
```

**正例：**
```java
/**
 * 汇总订单商品金额并生成订单视图
 *
 * @param o     订单实体（会就地更新合计金额）
 * @param items 订单商品列表
 * @return 订单视图对象
 */
public OrderVO build(OrderVO o, List<OrderItemDO> items) {
    BigDecimal total = BigDecimal.ZERO;
    // 合计金额 = 每个商品单价 × 数量之和
    for (OrderItemDO item : items) {
        total = total.add(item.getPrice().multiply(item.getCount()));
    }
    o.setTotalAmount(total);
    return converter.convert(o);
}
```

**【强制】注释使用中文；核心业务逻辑、复杂分支、边界条件必须补充行注释说明业务意图，禁止将代码逐行翻译成注释。**


## 七、敏感操作与核心逻辑日志强制

**【强制】所有敏感操作（写数据库、写 Redis、发 Kafka/消息、调用外部服务、文件写入、删除/更新数据）执行前后必须打印日志，记录操作对象、关键参数、操作结果。**

> 参考详细规范：[java_logging_specification.md](./java_logging_specification.md)

**反例：**
```java
// ❌ 错误：写库、写缓存、发消息均无日志，故障时无法追踪
public void markOrderPaid(Long orderId) {
    orderRepository.updateStatus(orderId, OrderStatus.PAID);
    redisTemplate.delete("order:" + orderId);
    mqTemplate.send("order_paid_topic", orderId);
}
```

**正例：**
```java
// ✅ 正确：敏感操作前后均有日志，包含可追溯上下文
@Slf4j
public class OrderService {
    public void markOrderPaid(Long orderId, String operator) {
        log.info("标记订单已支付开始, orderId={}, operator={}", orderId, operator);
        // 写数据库（敏感操作）
        log.info("更新订单状态, orderId={}, beforeStatus={}, afterStatus={}",
                 orderId, OrderStatus.PENDING, OrderStatus.PAID);
        orderRepository.updateStatus(orderId, OrderStatus.PAID);
        // 写缓存（敏感操作）
        log.info("清除订单缓存, orderId={}", orderId);
        redisTemplate.delete("order:" + orderId);
        // 发消息（敏感操作）
        log.info("发送订单已支付消息, orderId={}, topic={}", orderId, "order_paid_topic");
        mqTemplate.send("order_paid_topic", orderId);
        log.info("标记订单已支付完成, orderId={}", orderId);
    }
}
```

**【强制】核心业务逻辑（状态机流转、金额计算、库存扣减、幂等判断等）的关键节点必须打印日志，覆盖：入口、关键状态变更、外部依赖调用结果、异常分支。**

**【强制】禁止在日志中打印敏感信息（密码、身份证、银行卡号、Token、全量报文），需脱敏后输出。**

**反例：**
```java
// ❌ 错误：日志打印敏感信息
log.info("用户登录成功, username={}, password={}", username, password);
log.info("支付回调报文: {}", callbackPayload);  // 可能含银行卡号
```

**正例：**
```java
// ✅ 正确：脱敏后输出
log.info("用户登录成功, username={}", username);
log.info("支付回调, orderId={}, cardNo={}****{}", orderId,
         cardNo.substring(0, 4), cardNo.substring(cardNo.length() - 4));
```


## 快速检查清单

- [ ] 不确定需求是否标注了 `// TODO[AI]:` 而非猜测实现？
- [ ] 是否只实现了需求要求的功能，未增加额外功能？（是否通过了"2.3 提交前自查"？）
- [ ] 是否未引入项目未声明的依赖？
- [ ] 是否未做过度抽象（每个抽象都有 ≥2 个真实实现）？
- [ ] 是否未使用需求/技术设计未要求的设计模式与自造分层？
- [ ] 代码是否清洁，无废弃代码和注释掉的代码块？
- [ ] 方法命名是否表达了意图，无模糊命名？
- [ ] 嵌套是否不超过 3 层？
- [ ] 魔法值是否定义为常量？
- [ ] 架构分层是否清晰，是否存在跨层调用？
- [ ] 每个函数是否都有中文注释？
- [ ] 敏感操作（写库/写缓存/发消息/调用外部服务）是否打印了日志？
- [ ] 日志是否避免了打印密码、银行卡号等敏感信息？
