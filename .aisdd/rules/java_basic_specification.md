---
name: Java 基础开发规范
version: 1.1.0
description: 覆盖 Java 命名规范、集合处理、异常处理、基础类型与数据结构、变量定义、方法定义、类定义、注释规范与并发处理基线，为 AI Agent 生成 Java 代码提供正反例对照。线程池/队列/CompletableFuture/锁/虚拟线程等并发专项细则见 java_concurrency_specification.md。约束等级分为三级：【强制】（必须遵守）、【推荐】（尽量遵守）、【参考】（可选参考）。
---

# Java 基础开发规范

## 前言

本规范整合阿里巴巴 Java 开发手册核心要求，覆盖命名、集合、异常、类型、变量、方法、类、注释与并发处理，旨在为 AI Agent 生成代码提供明确的正反例对照。

约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。


## 一、命名规范

**【强制】所有命名均不能以下划线或美元符号开始，也不能以下划线或美元符号结束**。

**反例：**
```java
// ❌ 错误：使用下划线和美元符号
public class _UserService {
    private String $name;
    public void _processData_() { }
}
```

**正例：**
```java
// ✅ 正确：符合驼峰命名规范
public class UserService {
    private String name;
    public void processData() { }
}
```

**【强制】严禁使用拼音与英文混合的方式，更不允许直接使用中文。**

**反例：**
```java
// ❌ 错误：拼音英文混合、中文命名
public class DaZhePromotion { }           // 打折促销
public int getPingfenByName() { }         // 评分
public void 处理订单() { }                 // 中文方法名
```

**正例：**
```java
// ✅ 正确：使用英文或国际通用拼音
public class DiscountPromotion { }
public int getRatingByName() { }
public void processOrder() { }
```

**【强制】类名使用 UpperCamelCase 风格，领域模型相关命名如 DO / DTO / VO / DAO 等全部大写。**

**反例：**
```java
// ❌ 错误：大小写混乱
public class macroPolo { }
public class UserDo { }
public class XMLService { }
```

**正例：**
```java
// ✅ 正确
public class MarcoPolo { }
public class UserDO { }
public class XmlService { }
```

**【强制】方法名、参数名、成员变量、局部变量统一使用 lowerCamelCase 风格。**

**反例：**
```java
// ❌ 错误
public class UserService {
    private String UserName;          // 变量名首字母大写
    public void GetHttpMessage() { }  // 方法名首字母大写
}
```

**正例：**
```java
// ✅ 正确
public class UserService {
    private String userName;
    public String getHttpMessage() { }
}
```

**【强制】常量命名全部大写，单词间用下划线隔开，力求语义完整。**

**正例：**
```java
// ✅ 正确：语义完整清晰
public static final int MAX_STOCK_COUNT = 100;
public static final String DEFAULT_USER_STATUS = "ACTIVE";
```

**【强制】抽象类命名使用 Abstract 或 Base 开头；异常类命名使用 Exception 结尾；测试类命名以它要测试的类名开始，以 Test 结尾。**

**正例：**
```java
// ✅ 正确
public abstract class AbstractUserService { }
public class BusinessException extends RuntimeException { }
public class UserServiceTest { }
```


## 二、集合处理规范

**【强制】判断集合是否为空时使用 `isEmpty()` 方法，而非 `size() == 0`。**

**反例：**
```java
// ❌ 错误：使用 size() 判空
if (list.size() == 0) {
    // do something
}
```

**正例：**
```java
// ✅ 正确：使用 isEmpty()
if (list.isEmpty()) {
    // do something
}
// 或使用 Spring 的 CollectionUtils
if (CollectionUtils.isEmpty(list)) {
    // do something
}
```

**【推荐】初始化集合时指定容量，避免频繁扩容。**

**反例：**
```java
// ❌ 错误：不指定容量
List<String> list = new ArrayList<>();
for (int i = 0; i < 10000; i++) {
    list.add(String.valueOf(i));
}
```

**正例：**
```java
// ✅ 正确：预估并指定容量
List<String> list = new ArrayList<>(10000);
for (int i = 0; i < 10000; i++) {
    list.add(String.valueOf(i));
}
```

**【强制】禁止在 foreach 循环中进行元素的 remove/add 操作，应使用 Iterator。**

**正例：**
```java
// ✅ 正确：使用 Iterator
List<String> list = new ArrayList<>(Arrays.asList("a", "b", "c"));
Iterator<String> iterator = list.iterator();
while (iterator.hasNext()) {
    String item = iterator.next();
    if ("b".equals(item)) {
        iterator.remove();
    }
}
```

**【强制】禁止在 foreach 循环中对 Map 进行 put/get 操作（多线程场景下会导致 ConcurrentModificationException）。**

**反例：**
```java
// ❌ 错误：遍历 Map 时同时修改
for (Map.Entry<String, String> entry : map.entrySet()) {
    if (entry.getValue().equals("invalid")) {
        map.remove(entry.getKey());
    }
}
```

**正例：**
```java
// ✅ 正确：先收集 key 再删除
List<String> keysToRemove = new ArrayList<>();
for (Map.Entry<String, String> entry : map.entrySet()) {
    if ("invalid".equals(entry.getValue())) {
        keysToRemove.add(entry.getKey());
    }
}
keysToRemove.forEach(map::remove);
```

**【推荐】遍历 Map 使用 `entrySet()` 而非 `keySet()` + `get()`，避免多次查找。**

**反例：**
```java
// ❌ 错误：keySet + get，每次 get 都需要重新计算 hash
for (String key : map.keySet()) {
    String value = map.get(key);
    // do something
}
```

**正例：**
```java
// ✅ 正确：entrySet 一次获取 key 和 value
for (Map.Entry<String, String> entry : map.entrySet()) {
    String key = entry.getKey();
    String value = entry.getValue();
    // do something
}
```


## 三、异常处理规范

**【强制】不要捕获 Throwable，也不要直接抛出 Throwable。**

**反例：**
```java
// ❌ 错误：捕获 Throwable
try {
    // business logic
} catch (Throwable t) {
    // 会捕获 Error，可能掩盖 JVM 严重错误
}
```

**正例：**
```java
// ✅ 正确：捕获具体异常
try {
    // business logic
} catch (BusinessException e) {
    // 处理业务异常
} catch (Exception e) {
    // 处理其他异常
}
```

**【强制】异常信息应包括现场信息，不要仅输出一句错误日志。**

**反例：**
```java
// ❌ 错误：日志信息不足
try {
    userService.updateUser(user);
} catch (Exception e) {
    log.error("更新失败");  // 缺少关键信息，无法定位问题
}
```

**正例：**
```java
// ✅ 正确：包含足够上下文信息
try {
    userService.updateUser(user);
} catch (Exception e) {
    log.error("更新用户失败, userId={}, userName={}", user.getId(), user.getName(), e);
}
```

**【强制】方法返回值可以为 null，但调用方必须做 NPE 防御。**

**正例：**
```java
// ✅ 正确：调用方做 null 判断
UserDO user = userRepository.selectById(id);
if (user == null) {
    throw new BusinessException(4004, "用户不存在");
}
// 使用前确保非 null
String username = user.getUsername();
```

**【强制】禁止在 try 块中定义资源后，finally 块中逐个关闭，应使用 try-with-resources。**

**正例：**
```java
// ✅ 正确：try-with-resources 自动关闭
try (BufferedReader reader = new BufferedReader(new FileReader("data.txt"))) {
    String line;
    while ((line = reader.readLine()) != null) {
        // process
    }
} catch (IOException e) {
    log.error("文件读取失败", e);
}
```


## 四、基础类型与数据结构规范

**【强制】禁止使用基本类型直接赋值给包装类型，避免自动装箱的 NPE 风险。**

**反例：**
```java
// ❌ 错误：自动装箱可能 NPE
Integer count = null;
int result = count;  // NullPointerException
```

**正例：**
```java
// ✅ 正确：先判空再拆箱
Integer count = getCount();
int result = (count != null) ? count : 0;
```

**【强制】所有 POJO 类属性必须使用包装数据类型；所有局部变量使用基本数据类型。**

**反例：**
```java
// ❌ 错误：POJO 使用基本类型，无法表达 null 语义
public class UserDO {
    private int status;        // 无法表达"未设置"状态
    private boolean isActive;  // 默认 false，无法区分"未初始化"
}
```

**正例：**
```java
// ✅ 正确：POJO 使用包装类型
public class UserDO {
    private Integer status;   // null 表示未设置，0 表示禁用，1 表示启用
    private Boolean isActive; // null 表示未设置
}
```

**【强制】比较 Integer 值必须使用 `equals()`，禁止使用 `==`（Integer 缓存范围 -128~127 外会失效）。**

**反例：**
```java
// ❌ 错误：使用 == 比较 Integer
Integer a = 200;
Integer b = 200;
if (a == b) {  // false，超出缓存范围
    // ...
}
```

**正例：**
```java
// ✅ 正确：使用 equals
Integer a = 200;
Integer b = 200;
if (a.equals(b)) {  // true
    // ...
}
```

**【强制】字符串比较常量在前、变量在后，避免 NPE。**

**反例：**
```java
// ❌ 错误：变量在前，可能 NPE
if (status.equals("ACTIVE")) {
    // 如果 status 为 null，抛出 NPE
}
```

**正例：**
```java
// ✅ 正确：常量在前
if ("ACTIVE".equals(status)) {
    // status 为 null 也不会 NPE
}
```

**【推荐】大量字符串拼接使用 `StringBuilder`，禁止在循环中使用 `+`。**

**反例：**
```java
// ❌ 错误：循环中使用 + 拼接字符串
String result = "";
for (String item : items) {
    result += item;  // 每次创建新对象
}
```

**正例：**
```java
// ✅ 正确：使用 StringBuilder
StringBuilder sb = new StringBuilder(items.size() * 16);
for (String item : items) {
    sb.append(item);
}
String result = sb.toString();
```

**【强制】金额、数量等精度敏感场景禁止使用 `double`/`float`，必须使用 `BigDecimal`。**

**反例：**
```java
// ❌ 错误：浮点数精度丢失
double total = 0.1 + 0.2;  // 结果是 0.30000000000000004
```

**正例：**
```java
// ✅ 正确：使用 BigDecimal
BigDecimal total = new BigDecimal("0.1").add(new BigDecimal("0.2"));
// 比较使用 compareTo，禁止使用 equals（equals 会比较精度）
if (total.compareTo(new BigDecimal("0.3")) == 0) {
    // 金额相等
}
```

**【推荐】日期时间统一使用 `java.time` API，禁止使用 `Date` / `Calendar`。**

**正例：**
```java
// ✅ 正确：使用 java.time
LocalDateTime now = LocalDateTime.now();
LocalDate date = LocalDate.of(2026, 1, 1);
String formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
// 时间戳
Instant instant = Instant.now();
long epochMilli = instant.toEpochMilli();
```


## 五、变量定义规范

**【强制】每个变量声明占一行，禁止一条语句声明多个变量。**

**反例：**
```java
// ❌ 错误：一行声明多个变量
int a, b, c;
String name, email;
```

**正例：**
```java
// ✅ 正确：逐个声明
int a;
int b;
int c;
String name;
String email;
```

**【强制】成员变量使用 `private` 修饰，通过 getter/setter 访问，禁止使用 `public` 修饰字段。**

**反例：**
```java
// ❌ 错误：public 字段
public class UserDO {
    public String username;
    public String email;
}
```

**正例：**
```java
// ✅ 正确：private 字段 + getter/setter（或使用 Lombok @Data）
@Data
public class UserDO {
    private String username;
    private String email;
}
```

**【强制】局部变量在使用前必须初始化，禁止使用未初始化的变量。**

**正例：**
```java
// ✅ 正确：先初始化再使用
List<String> names = new ArrayList<>();
String defaultStatus = "ACTIVE";

// 需要条件初始化时，必须覆盖所有分支
String status;
if (user.isAdmin()) {
    status = "ADMIN";
} else {
    status = "USER";
}
```

**【强制】常量必须使用 `static final` 修饰，并在定义时赋值。**

**正例：**
```java
// ✅ 正确：常量定义
public class UserConstants {
    public static final int MAX_RETRY_COUNT = 3;
    public static final String DEFAULT_ROLE = "ROLE_USER";
    public static final Duration CACHE_TIMEOUT = Duration.ofMinutes(30);
}
```

**【推荐】局部变量尽量在使用处附近声明，缩小作用域。**

**反例：**
```java
// ❌ 错误：方法开头集中声明，作用域过大
public void process(Order order) {
    String customerName;  // 距离使用处 50 行
    int itemCount;        // 距离使用处 80 行
    // ... 大量代码 ...
    customerName = order.getCustomer().getName();
    itemCount = order.getItems().size();
}
```

**正例：**
```java
// ✅ 正确：就近声明
public void process(Order order) {
    // ... 代码 ...
    String customerName = order.getCustomer().getName();
    int itemCount = order.getItems().size();
}
```


## 六、方法定义规范

**【强制】方法长度不超过 80 行（含空行和注释），超过时应拆分为多个私有方法。**

**反例：**
```java
// ❌ 错误：方法过长，职责混杂
public void processOrder(Order order) {
    // 100 行代码混合了校验、业务逻辑、数据库操作、日志...
}
```

**正例：**
```java
// ✅ 正确：拆分为职责单一的小方法
public void processOrder(Order order) {
    validateOrder(order);
    deductStock(order);
    processPayment(order);
    saveOrder(order);
}

private void validateOrder(Order order) { /* 校验逻辑 */ }
private void deductStock(Order order) { /* 扣减库存 */ }
private void processPayment(Order order) { /* 支付处理 */ }
private void saveOrder(Order order) { /* 持久化 */ }
```

**【强制】方法参数不超过 5 个，超过时必须封装为参数对象。**

**反例：**
```java
// ❌ 错误：参数过多
public void createUser(String username, String email, String phone,
                       String address, Integer status, String role,
                       String department, Integer age) {
    // ...
}
```

**正例：**
```java
// ✅ 正确：封装为参数对象
public void createUser(UserCreateRequest request) {
    // ...
}

@Data
public class UserCreateRequest {
    private String username;
    private String email;
    private String phone;
    private String address;
    private Integer status;
    private String role;
    private String department;
    private Integer age;
}
```

**【强制】方法职责单一，一个方法只做一件事。**

**反例：**
```java
// ❌ 错误：一个方法做了校验 + 业务 + 发送通知
public UserVO createUser(UserDTO dto) {
    // 校验
    if (dto.getUsername() == null) { throw new BusinessException(...); }
    // 业务
    UserDO user = new UserDO();
    user.setUsername(dto.getUsername());
    userRepository.insert(user);
    // 通知
    notificationService.sendWelcomeEmail(user);
    // 返回
    return convertToVO(user);
}
```

**正例：**
```java
// ✅ 正确：职责分离
public UserVO createUser(UserDTO dto) {
    validateUser(dto);
    UserDO user = buildUser(dto);
    userRepository.insert(user);
    notificationService.sendWelcomeEmail(user);
    return convertToVO(user);
}
```

**【推荐】返回布尔值的方法命名以 `is` / `has` / `can` / `should` 开头。**

**正例：**
```java
// ✅ 正确：布尔方法命名
public boolean isActive() { }
public boolean hasPermission(String role) { }
public boolean canAccess(Long resourceId) { }
public boolean shouldRetry(int attemptCount) { }
```


## 七、类定义规范

**【强制】一个 `.java` 文件只定义一个顶层类。**

**反例：**
```java
// ❌ 错误：一个文件定义多个类
// UserService.java
public class UserService { }
class UserValidator { }
class UserHelper { }
```

**正例：**
```java
// ✅ 正确：一个文件一个类
// UserService.java
public class UserService { }

// UserValidator.java
public class UserValidator { }
```

**【强制】类层次不超过 4 层（不含 Object），过深的继承层次应改用组合。**

**反例：**
```java
// ❌ 错误：过深的继承层次
class AbstractBaseService { }
class AbstractUserService extends AbstractBaseService { }
class AbstractUserServiceBase extends AbstractUserService { }
class UserServiceImpl extends AbstractUserServiceBase { }
```

**正例：**
```java
// ✅ 正确：使用组合替代多层继承
@Service
public class UserService {
    private final UserValidator userValidator;  // 组合
    private final UserRepository userRepository;
    
    public UserService(UserValidator userValidator, UserRepository userRepository) {
        this.userValidator = userValidator;
        this.userRepository = userRepository;
    }
}
```

**【强制】工具类禁止实例化，必须提供 private 构造方法。**

**正例：**
```java
// ✅ 正确：工具类私有构造
public final class StringUtils {
    private StringUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
    
    public static boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
}
```

**【强制】POJO 类不包含任何业务逻辑，只负责数据承载。**

**反例：**
```java
// ❌ 错误：POJO 中包含业务逻辑
public class UserDO {
    private String username;
    private String password;
    
    // ❌ POJO 不应有业务逻辑
    public boolean isPasswordValid() {
        return password.length() >= 8;
    }
}
```

**正例：**
```java
// ✅ 正确：POJO 纯数据，逻辑下沉到 Service
@Data
public class UserDO {
    private String username;
    private String password;
}

@Service
public class UserService {
    public boolean isPasswordValid(UserDO user) {
        return user.getPassword() != null && user.getPassword().length() >= 8;
    }
}
```

**【推荐】使用 Lombok 注解简化样板代码，但禁止使用 `@AllArgsConstructor` 用于 POJO（可能导致参数顺序错误）。**

**正例：**
```java
// ✅ 正确：使用 Lombok
@Data                    // getter/setter/toString/equals/hashCode
@Builder                 // 构造器模式
@NoArgsConstructor       // 无参构造
@AllArgsConstructor(access = AccessLevel.PRIVATE)  // 私有全参构造
public class UserDO {
    private Long id;
    private String username;
    private String email;
}
```


## 八、注释规范

**【强制】每个类必须有类级别 Javadoc 注释，说明类的职责和用途。**

**反例：**
```java
// ❌ 错误：类无注释
public class OrderService {
    // ...
}
```

**正例：**
```java
// ✅ 正确：完整的类注释
/**
 * 订单业务服务
 * <p>
 * 负责订单的创建、支付、取消、退款等核心业务流程。
 * 所有订单状态变更必须通过本服务完成，禁止直接操作数据库。
 * </p>
 *
 * @author example
 * @since 2026-01-01
 */
@Service
@Slf4j
public class OrderService {
    // ...
}
```

**【强制】每个 public 方法必须有 Javadoc 注释，说明方法用途、参数含义、返回值说明。**

**反例：**
```java
// ❌ 错误：方法无注释
public UserVO getUserById(Long id) {
    return userRepository.selectById(id);
}
```

**正例：**
```java
// ✅ 正确：完整的方法注释
/**
 * 根据 ID 查询用户信息
 *
 * @param id 用户ID，不能为 null
 * @return 用户视图对象
 * @throws BusinessException 当用户不存在时抛出（code=4004）
 */
public UserVO getUserById(Long id) {
    UserDO user = userRepository.selectById(id);
    if (user == null) {
        throw new BusinessException(4004, "用户不存在");
    }
    return convertToVO(user);
}
```

**【强制】private 方法也必须添加注释说明用途（可使用行注释或 Javadoc）。**

**正例：**
```java
// ✅ 正确：私有方法注释
/**
 * 将用户实体转换为视图对象
 */
private UserVO convertToVO(UserDO userDO) {
    UserVO vo = new UserVO();
    vo.setId(userDO.getId());
    vo.setUsername(userDO.getUsername());
    return vo;
}
```

**【强制】核心业务逻辑、关键分支和重要业务规则必须添加中文注释，说明业务背景、设计意图或边界条件；禁止使用无意义注释或将代码逐行翻译成注释。**

**反例：**
```java
// ❌ 错误：无意义注释
// 设置用户名
user.setUsername(name);
// 保存用户
userRepository.save(user);
```

**正例：**
```java
// ✅ 正确：解释业务意图
// 先锁定库存再发起支付，避免支付成功后出现超卖
deductStock(order);
paymentClient.pay(order);
```

**【强制】TODO 注释必须标注原因和负责人，格式为 `// TODO[负责人]: 原因描述`。**

**正例：**
```java
// TODO[zhangsan]: 需要确认是否支持多渠道支付，待产品确认后补充
// PaymentChannel channel = determineChannel(order);
// channel.pay(order);
```


## 九、并发处理规范

> 本章仅保留与基础编码相关的两条约束。**线程池、队列选型、拒绝策略、优雅停机、CompletableFuture、锁与并发容器、ThreadLocal 与 MDC 传播、虚拟线程等并发专项规范，统一见 `java_concurrency_specification.md`（doc_id: `rule-java-concurrency`）**，本文件不再重复，冲突处以专项规范为准。

**【强制】创建线程池禁止使用 `Executors` 工厂方法（无界队列或无线程数上限，可能 OOM），必须显式构造 `ThreadPoolExecutor` 并指定有界队列、线程命名与拒绝策略。**

**反例：**
```java
// ❌ 错误：使用 Executors（无界队列，可能 OOM）
ExecutorService executor = Executors.newFixedThreadPool(10);
ExecutorService cachedExecutor = Executors.newCachedThreadPool();
```

**正例：**
```java
// ✅ 正确：手动创建线程池（参数语义与容量推理见 java_concurrency_specification.md）
ThreadPoolExecutor executor = new ThreadPoolExecutor(
    5,                              // 核心线程数
    10,                             // 最大线程数
    60L,                           // 空闲存活时间
    TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(200), // 有界队列
    new ThreadFactoryBuilder().setNameFormat("order-pool-%d").build(),
    new ThreadPoolExecutor.CallerRunsPolicy()  // 拒绝策略
);
```

**【强制】`SimpleDateFormat` 线程不安全，禁止定义为 static 共享变量。**

**反例：**
```java
// ❌ 错误：static 共享 SimpleDateFormat
private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd");
// 多线程调用 SDF.format() 会产生数据错乱
```

**正例：**
```java
// ✅ 正确：使用 DateTimeFormatter（线程安全）
private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
// 或使用 ThreadLocal
private static final ThreadLocal<SimpleDateFormat> TL_SDF =
    ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));
```

**【强制】多线程操作共享变量时必须保证线程安全，优先使用 `java.util.concurrent` 提供的原子类与并发容器；加锁粒度、锁顺序与 `ConcurrentHashMap` 复合操作约束见 `java_concurrency_specification.md`。**

**正例：**
```java
// ✅ 正确：使用 Atomic 类
private final AtomicInteger counter = new AtomicInteger(0);
public int increment() {
    return counter.incrementAndGet();
}
```


## 快速检查清单

- [ ] 类名是否使用 UpperCamelCase？
- [ ] 方法名、变量名是否使用 lowerCamelCase？
- [ ] 常量是否全部大写并用下划线分隔且语义完整？
- [ ] 是否使用 `isEmpty()` 判断集合为空？
- [ ] 集合初始化时是否指定了容量？
- [ ] POJO 属性是否使用包装类型？
- [ ] Integer 比较是否使用 `equals()` 而非 `==`？
- [ ] 金额是否使用 `BigDecimal` 而非 `double`？
- [ ] 日期时间是否使用 `java.time` API？
- [ ] 字符串比较常量是否在前？
- [ ] 是否一行只声明一个变量？
- [ ] 成员变量是否使用 `private` 修饰？
- [ ] 方法长度是否不超过 80 行？
- [ ] 方法参数是否不超过 5 个？
- [ ] 方法是否职责单一？
- [ ] 局部变量是否就近声明？
- [ ] 一个文件是否只定义一个顶层类？
- [ ] 工具类是否提供 private 构造方法？
- [ ] POJO 是否只承载数据不包含业务逻辑？
- [ ] 每个类是否有 Javadoc 类注释？
- [ ] 每个 public 方法是否有 Javadoc 方法注释？
- [ ] private 方法是否有注释说明用途？
- [ ] 核心业务逻辑是否有中文注释说明意图？
- [ ] TODO 注释是否标注了原因和负责人？
