---
name: Spring / Spring MVC 开发规范
version: 1.1.0
description: 覆盖 Spring 依赖注入、Bean 配置、事务管理、Spring MVC 控制器、请求映射、参数接收、拦截器与过滤器的规范，为 AI Agent 生成代码提供正反例对照。Spring Boot 工程约束见 java_springboot_specification.md，WebFlux 响应式约束见 java_webflux_specification.md，线程池与并发约束见 java_concurrency_specification.md。约束等级分为三级：【强制】（必须遵守）、【推荐】（尽量遵守）、【参考】（可选参考）。
---

# Spring / Spring MVC 开发规范

## 前言

本规范覆盖 Spring Framework 依赖注入与事务管理、Spring MVC 控制器与拦截器，为 AI Agent 生成代码提供正反例对照。

以下专项内容**不在本文件维护**，冲突处以专项规范为准：

- Spring Boot 工程约束（配置绑定与密钥、Bean 与循环依赖、Web 层 Controller 精简与统一响应契约、数据层与 OSIV、Actuator 与探针、优雅停机、外部调用超时与熔断、测试分层、容器化）→ `java_springboot_specification.md`（doc_id: `rule-java-springboot`）
- Spring WebFlux / Reactor 响应式约束（禁止阻塞事件循环、Scheduler、背压、错误处理、Reactor Context、R2DBC、StepVerifier）→ `java_webflux_specification.md`（doc_id: `rule-java-webflux`）
- 线程池、队列、CompletableFuture、锁与并发容器、虚拟线程 → `java_concurrency_specification.md`（doc_id: `rule-java-concurrency`）

约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。


## 一、Spring Framework 规范

### 1.1 依赖注入规范

**【强制】优先使用构造器注入，避免字段注入。**

**反例：**
```java
// ❌ 错误：字段注入
@Service
public class OrderService {
    @Autowired
    private PaymentService paymentService;
}
```

**正例：**
```java
// ✅ 正确：构造器注入（推荐使用 @RequiredArgsConstructor）
@Service
@RequiredArgsConstructor
public class OrderService {
    private final PaymentService paymentService;
    private final UserService userService;
}
```

**【推荐】当有多个同类型 Bean 时，使用 `@Qualifier` 或 `@Primary` 明确指定。**

**正例：**
```java
// ✅ 正确：使用 @Qualifier 指定
@Service
public class NotificationService {
    private final MessageSender messageSender;
    
    public NotificationService(@Qualifier("smsSender") MessageSender messageSender) {
        this.messageSender = messageSender;
    }
}
```

### 1.2 Bean 配置规范

**【推荐】Bean 配置使用 Java Config 方式，避免 XML 配置。**

**正例：**
```java
// ✅ 正确：使用 Java Config
@Configuration
public class AppConfig {
    @Bean
    public UserService userService(UserRepository userRepository) {
        return new UserService(userRepository);
    }
}
```

### 1.3 事务管理规范

**【强制】`@Transactional` 注解应加在 Service 层方法上，不要加在 Controller 层。**

**【强制】`@Transactional` 注解必须指定 `rollbackFor = Exception.class`。**

**反例：**
```java
// ❌ 错误：未指定 rollbackFor
@Transactional
public void updateUser(User user) { }
```

**正例：**
```java
// ✅ 正确：明确指定 rollbackFor
@Transactional(rollbackFor = Exception.class)
public void updateUser(User user) { }
```

**【强制】`@Transactional` 不可加在 private / final 方法上（Spring AOP 无法代理）。**

**反例：**
```java
// ❌ 错误：private 方法加 @Transactional（不生效）
@Transactional(rollbackFor = Exception.class)
private void processPayment(Order order) { }
```

**正例：**
```java
// ✅ 正确：public 方法
@Transactional(rollbackFor = Exception.class)
public void processPayment(Order order) { }
```

**【强制】禁止在同一个类内部方法调用 `@Transactional` 方法（自调用不走代理，事务不生效）。**

**反例：**
```java
// ❌ 错误：类内部自调用，事务不生效
@Service
public class OrderService {
    public void batchCreate(List<Order> orders) {
        for (Order order : orders) {
            createOrder(order);  // ❌ 自调用，@Transactional 不生效
        }
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void createOrder(Order order) { }
}
```

**正例：**
```java
// ✅ 正确：通过注入自身代理对象调用
@Service
public class OrderService {
    private final OrderService self;
    
    public OrderService(@Lazy OrderService self) {
        this.self = self;
    }
    
    public void batchCreate(List<Order> orders) {
        for (Order order : orders) {
            self.createOrder(order);  // ✅ 通过代理调用，事务生效
        }
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void createOrder(Order order) { }
}
```


## 二、Spring MVC 规范

### 2.1 控制器注解规范

**【强制】Controller 层使用 `@RestController` + `@RequestMapping` 组合，禁止使用过时的 `@Controller` + `@ResponseBody`。**

**反例：**
```java
// ❌ 错误：过时写法
@Controller
@RequestMapping("/api/users")
public class UserController {
    @Autowired
    private UserService userService;
    
    @GetMapping("/{id}")
    @ResponseBody
    public User getUser(@PathVariable Long id) { }
}
```

**正例：**
```java
// ✅ 正确：使用 @RestController
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    
    @GetMapping("/{id}")
    public Result<UserVO> getUser(@PathVariable Long id) { }
}
```

### 2.2 请求映射规范

**【强制】RESTful API 使用标准 HTTP 方法语义。**

| HTTP 方法 | 用途 | 注解 | 示例 |
|-----------|------|------|------|
| GET | 查询 | `@GetMapping` | `@GetMapping("/{id}")` |
| POST | 新增 | `@PostMapping` | `@PostMapping` |
| PUT | 全量更新 | `@PutMapping("/{id}")` |
| PATCH | 部分更新 | `@PatchMapping("/{id}")` |
| DELETE | 删除 | `@DeleteMapping("/{id}")` | `@DeleteMapping("/{id}")` |

**【强制】API 路径使用复数名词，版本号通过前缀体现。**

**正例：**
```java
// ✅ 正确：复数名词 + 版本前缀
@RestController
@RequestMapping("/api/v1/users")
public class UserController { }

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController { }
```

**【强制】禁止在 URL 路径中使用动词，动作由 HTTP 方法体现。**

**反例：**
```java
// ❌ 错误：URL 包含动词
@PostMapping("/api/users/create")
@PostMapping("/api/users/delete/{id}")
@GetMapping("/api/users/search")
```

**正例：**
```java
// ✅ 正确：动词由 HTTP 方法体现
@PostMapping("/api/v1/users")
@DeleteMapping("/api/v1/users/{id}")
@GetMapping("/api/v1/users")  // 列表查询
```

### 2.3 参数接收规范

**【强制】请求参数必须使用 `@Valid` / `@Validated` 触发校验，DTO 字段必须标注校验注解。**

**正例：**
```java
// ✅ 正确：参数校验
@PostMapping
public Result<UserVO> createUser(@Valid @RequestBody UserCreateDTO dto) {
    return Result.success(userService.createUser(dto));
}

@Data
public class UserCreateDTO {
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 20, message = "用户名长度应在 3-20 之间")
    private String username;
    
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;
}
```

**【强制】路径参数使用 `@PathVariable`，查询参数使用 `@RequestParam`，请求体使用 `@RequestBody`。**

**正例：**
```java
// ✅ 正确：参数接收方式
@GetMapping("/{id}")
public Result<UserVO> getUser(@PathVariable Long id) { }

@GetMapping
public Result<PageResult<UserVO>> listUsers(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(required = false) String keyword) { }

@PostMapping
public Result<UserVO> createUser(@Valid @RequestBody UserCreateDTO dto) { }
```

### 2.4 拦截器规范

**【强制】拦截器必须实现 `HandlerInterceptor` 接口，职责单一。**

**正例：**
```java
// ✅ 正确：登录鉴权拦截器
/**
 * 登录鉴权拦截器
 * <p>校验请求头中的 Token，验证用户登录状态</p>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final TokenService tokenService;
    
    public AuthInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader("Authorization");
        if (StringUtils.isBlank(token)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return false;
        }
        // 校验 Token
        UserInfo userInfo = tokenService.verify(token);
        if (userInfo == null) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return false;
        }
        // 将用户信息存入请求上下文
        request.setAttribute("currentUser", userInfo);
        return true;
    }
}

/**
 * Web MVC 配置
 * <p>注册拦截器并配置拦截路径</p>
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;
    private final LogInterceptor logInterceptor;
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 鉴权拦截器（优先级高）
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/auth/login", "/api/v1/auth/register")
                .order(1);
        // 日志拦截器
        registry.addInterceptor(logInterceptor)
                .addPathPatterns("/api/**")
                .order(2);
    }
}
```

**【强制】拦截器中禁止执行耗时数据库操作，拦截器应尽量轻量。**

**正例：**
```java
// ✅ 正确：拦截器只做轻量校验
@Override
public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    // 从缓存/Token 解析获取用户信息，不查库
    String token = request.getHeader("Authorization");
    UserInfo userInfo = tokenService.verifyFromCache(token);
    if (userInfo == null) {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        return false;
    }
    request.setAttribute("currentUser", userInfo);
    return true;
}
```

### 2.5 过滤器规范

**【推荐】跨域、字符编码等全局配置使用 Filter，业务鉴权使用 Interceptor。**

**正例：**
```java
// ✅ 正确：跨域过滤器
/**
 * 跨域配置过滤器
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorsFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        HttpServletResponse response = (HttpServletResponse) res;
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
        response.setHeader("Access-Control-Max-Age", "3600");
        chain.doFilter(req, res);
    }
}
```


## 三、Spring Boot 规范（已迁移至专项规范）

> Spring Boot 工程约束已迁移到 `java_springboot_specification.md`（doc_id: `rule-java-springboot`），本文件不再维护，避免重复与冲突。该专项规范覆盖：
>
> - 依赖与 starter 治理、BOM 与版本管理
> - `@ConfigurationProperties` 配置绑定、profile 策略、密钥外部化
> - 自动配置与自定义 starter（`AutoConfiguration.imports`）
> - Bean 设计与循环依赖策略、`@Configuration` 代理模式
> - Web 层：Boot 应用分层结构、Controller 精简、统一响应契约、全局异常与 `ProblemDetail`、禁止直接返回 JPA 实体
> - 数据层：OSIV 关闭、Flyway/Liquibase 迁移、`ddl-auto` 取值、事务边界与 `rollbackFor`
> - Actuator 加固、liveness/readiness 探针、优雅停机与 SIGTERM 预算
> - 外部调用超时、重试退避与熔断
> - 测试分层（slice test / Testcontainers / `@MockitoBean`）、安全配置与 CORS、容器化构建与启动性能
>
> 命名/集合/异常/注释等基础编码要求见 `java_basic_specification.md`；DI、事务、MVC 注解、参数绑定、拦截器与过滤器见本文件第一、二章。

## 快速检查清单

- [ ] 是否使用构造器注入而非字段注入？
- [ ] 多 Bean 时是否使用 `@Qualifier` 或 `@Primary`？
- [ ] `@Transactional` 是否在 Service 层且指定了 `rollbackFor`？
- [ ] `@Transactional` 是否加在 public 方法上？
- [ ] 是否避免了类内部自调用 `@Transactional` 方法？
- [ ] 是否使用 `@RestController` 而非 `@Controller` + `@ResponseBody`？
- [ ] RESTful 路径是否使用复数名词且无动词？
- [ ] 请求参数是否使用 `@Valid` 触发校验？
- [ ] 拦截器是否通过 `WebMvcConfigurer` 注册？
- [ ] 拦截器中是否避免了耗时操作？
- [ ] Spring Boot 工程约束（配置绑定、数据层与事务边界、Actuator、优雅停机、外部调用容错、测试分层）是否已按 `java_springboot_specification.md` 逐条核验？
- [ ] 若为响应式（WebFlux/Reactor）实现，是否已按 `java_webflux_specification.md` 逐条核验？
- [ ] 若涉及线程池/队列/异步编排，是否已按 `java_concurrency_specification.md` 逐条核验？
