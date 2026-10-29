---
name: Java Spring Boot 工程规范
version: 1.0.0
description: 面向 AI Agent 的 Spring Boot 工程层开发规范（Java 17 / Spring Boot 3.2+ 基线）。覆盖依赖与 starter 治理、BOM 与版本管理、devtools 作用域、类型安全配置绑定与 profile、密钥外部化与 configtree、自动配置与自定义 starter、AutoConfiguration.imports 注册、Bean 设计与循环依赖/Bean 覆盖禁令、@Configuration lite 模式、Web 层 Controller 精简与统一响应契约、全局异常与 ProblemDetail、禁止直接返回 JPA 实体、数据层 OSIV 关闭、ddl-auto 与 Flyway/Liquibase 迁移、事务只读与 N+1、Actuator 加固与 liveness/readiness 探针、结构化日志与 tracing、graceful shutdown 与 SIGTERM、虚拟线程与执行器 Bean 的 Boot 配置、外部调用超时与 Resilience4j 重试/熔断、测试分层（slice test / Testcontainers / @MockitoBean）、安全配置与 CORS、容器化分层构建与启动性能（layered jar、CDS/AOT、MaxRAMPercentage）。命名、集合、异常、基础并发、Spring 核心 DI、@Transactional 基本用法、MVC 注解、Redis/MyBatis/Kafka、日志级别与脱敏不在本文范围，见对应规范文档。约束等级分为三级：【强制】（必须遵守，AI 可在 diff 中客观判定）、【推荐】（尽量遵守）、【参考】（可选参考）。
---

# Java Spring Boot 工程规范

## 前言

本规范面向 AI Agent 生成 Spring Boot 代码的场景，聚焦 Spring Boot「工程层」：依赖治理、配置绑定、自动配置、Bean 装配、Web/数据层契约、Actuator 运维、生命周期、外部调用弹性、测试分层、安全配置与容器化启动性能。文档为每条规则提供 ❌/✅ 正反例，便于在代码评审与 diff 中客观校验。

本规范以 **Java 17 / Spring Boot 3.2+** 为基线；凡 Spring Boot 2.x 行为不同的地方，均在规则内以 `(2.x: …)` 显式标注。

约束等级分为三级：**【强制】**（必须遵守，且是 AI 读取代码可客观判定的硬规则）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。

为避免与既有规范重复，以下内容不在本文展开，仅按需要引用其名称：命名/集合/异常处理/基础类型/变量/方法/类/注释/基础并发见 `java_basic_specification.md`；Spring 核心 DI、Bean 配置、`@Transactional` 基本用法、Spring MVC 注解/映射/参数绑定/拦截器/过滤器见 `java_spring_specification.md`；Spring 集成 Redis/MyBatis/Kafka/HTTP 拦截见 `java_middleware_specification.md`；Java 日志级别与脱敏见 `java_logging_specification.md`；线程池参数/队列选型/拒绝策略/CompletableFuture/锁与原子类/虚拟线程本身见 `java_concurrency_specification.md`。

> 详细的调研依据与来源（上游官方文档、Release Notes 及第三方权威资料）见 `research/research_spring_boot.md`。

## 一、依赖与 starter 治理

**【强制】统一由 `spring-boot-starter-parent` 或导入 `spring-boot-dependencies` BOM 管理版本，禁止对 Boot 托管的依赖手工钉版本。**

原因：Jackson、Hibernate、Micrometer、Logback、junit-jupiter 等版本由 BOM 统一协调，手工钉版本会造成版本漂移、CVE 组合不兼容，且 Boot 升级无法传导。

**反例：**
```xml
<!-- ❌ 错误：把 BOM 托管的库钉到任意版本，破坏 Boot 3.2 预期 -->
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>2.13.0</version>
</dependency>
```

**正例：**
```xml
<!-- ✅ 正确：继承 starter-parent，托管依赖不写 <version> -->
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.2.12</version>
  <relativePath/>
</parent>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```
自有 parent 时改为导入 BOM（`spring-boot-dependencies`，`scope=import`、`type=pom`）。仅在有记录的理由（如 BOM 未收录的 CVE）才覆盖托管版本，且集中到属性（如 `<jackson-bom.version>`）。

**【强制】starter 命名遵循官方约定：官方为 `spring-boot-starter-*`，第三方/自定义自动配置模块为 `<项目>-spring-boot-starter`，不得以 `spring-boot-starter` 开头。**

原因：命名约定表达「一套已策展的依赖集」，驱动文档与 IDE 补全；不合规命名会误导使用方。`(2.x: 约定相同。)`

**【强制】`spring-boot-devtools` 必须以 Maven `<optional>true</optional>`（runtime scope）或 Gradle `developmentOnly` 声明，禁止 compile/runtime 直接依赖或放到 test scope。**

原因：devtools 不得传递依赖给消费方，且会被自动从 repackage 后的 jar 中排除；把 devtools 带到生产是显式安全风险（远程重启）。运行 `java -jar` 时 devtools 会自动禁用。

**反例：**
```xml
<!-- ❌ 错误：热重载机制进入每个构建/测试类路径 -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-devtools</artifactId>
</dependency>
```

**正例：**
```xml
<!-- ✅ 正确：Gradle 用 developmentOnly(...) -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-devtools</artifactId>
  <scope>runtime</scope>
  <optional>true</optional>
</dependency>
```

**【推荐】`spring-boot-starter-test` 只在 `test` scope 使用，构建期工具依赖不得进入运行时类路径。**

原因：该 starter 引入 JUnit Jupiter、AssertJ、Mockito、JSONassert，绝不应出现在生产运行时。

## 二、配置绑定与 Profile

**【强制】分组配置使用类型安全的 `@ConfigurationProperties` 类，禁止散落 `@Value`。**

原因：`@Value` 无 relaxed binding、无 IDE 元数据、无校验、跨 Bean 蔓延；`@ConfigurationProperties` 整棵树原子绑定、可测试，配合元数据生成获得 IDE 补全。

**反例：**
```java
// ❌ 错误：@Value 蔓延，拼写错误变成远离根因的启动失败
@Service
class BillingService {
    @Value("${acme.billing.timeout}") private Duration timeout;
    @Value("${acme.billing.max-retries}") private int maxRetries;
}
```

**正例：**
```java
// ✅ 正确：record + 构造器绑定（需 -parameters，starter-parent 默认开启）
@ConfigurationProperties(prefix = "acme.billing")
@Validated
public record BillingProperties(
        @DefaultValue("30s") Duration timeout,
        @Positive int maxRetries,
        @NotBlank String apiKeyRef) {}
// 注册一次：应用类上 @ConfigurationPropertiesScan，或 @EnableConfigurationProperties(BillingProperties.class)
```

**【强制】配置属性类必须用 `@Validated`（JSR-303）约束，启动即校验、快速失败。**

原因：配置错误若在启动阶段不拦截，会在凌晨生产环境才暴露；校验在绑定期间执行，约束不满足时应用拒绝启动。

**反例：**
```java
// ❌ 错误：无校验，host=null 直传到运行时 SMTP 客户端
@ConfigurationProperties(prefix = "acme.mail")
public class MailProperties { private String host; }
```

**正例：**
```java
// ✅ 正确：启动即拒绝非法配置
@Validated
@ConfigurationProperties(prefix = "acme.mail")
public class MailProperties {
    @NotBlank private String host;
    @Range(min = 1, max = 65535) private int port = 25;
    @DurationMin(seconds = 1) private Duration connectTimeout = Duration.ofSeconds(5);
}
```

**【强制】真实密钥禁止写入 `application.yml`/`application.properties` 或提交到源码库；通过环境变量、configtree 或密钥管理器注入。**

原因：仓库内文件对所有有访问权的人可读。Boot 支持把 Kubernetes Secrets 挂载为 configtree。`(2.x: configtree import 自 2.4 起支持。)`

**反例：**
```yaml
# ❌ 错误：提交了真实凭证
acme:
  billing:
    api-key: "sk_live_51H8abc..."
```

**正例：**
```yaml
# ✅ 正确：从密钥/环境注入，配置文件不落明文
spring:
  config:
    import: "optional:configtree:/etc/config/"
acme:
  billing:
    api-key-ref: ${ACME_BILLING_API_KEY}
```

**【强制】配置项的规范名使用 kebab-case，环境变量依赖 relaxed binding 而非在文件里写 camelCase。**

原因：kebab-case 是 canonical 形式，可从 YAML、properties、环境变量（`ACME_BILLING_MAX_RETRIES`）、命令行一致绑定；文件里写 camelCase 只是「恰好能用」，破坏环境变量对称性。

**反例：**
```yaml
# ❌ 错误：maxRetries: 3  非 canonical，破坏环境变量对称性
```

**正例：**
```yaml
# ✅ 正确（canonical kebab-case）
acme:
  billing:
    max-retries: 3
```

**【推荐】profile 仅用于环境差异，并遵循明确的属性优先级模型。**

原因：优先级为 配置文件 < 系统环境变量 < JVM 系统属性 < 命令行参数；`application-{profile}` 覆盖非 profile 文档，`spring.profiles.active=prod,live` 时最后者胜出；多文档 YAML（`---` + `spring.config.activate.on-profile`）可在单文件内保持可读。对可选导入用 `optional:` 前缀避免启动中断；需要「追加」配置用 `spring.config.additional-location`，不要用会「替换」默认位置的 `spring.config.location`。

**【参考】自定义属性使用公司前缀命名空间（如 `acme.*`），禁止放在 `spring.*`/`server.*`/`management.*` 根下。**

原因：这些根被 Boot/AutoConfiguration 保留，自定义属性放在此处会与未来框架属性冲突。自动配置文档明确要求自有属性不要使用这三个前缀。

## 三、自动配置与自定义 Starter

**【强制】自动配置类注册在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，每行一个类；`spring.factories` 的自动配置机制在 2.7 废弃、3.2 移除。**

原因：在 Boot 3.2+ 上仍用旧文件，你的自动配置会被静默忽略。嵌套类用 `$`。重命名/废弃既有自动配置类时使用 `AutoConfiguration.replacements` 文件。

**反例：**
```properties
# ❌ 错误：2.7 及更早机制，3.2+ 不再被识别为自动配置
# META-INF/spring.factories
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
com.acme.autoconfigure.AcmeMetricsAutoConfiguration
```

**正例：**
```properties
# ✅ 正确
# src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
com.acme.autoconfigure.AcmeMetricsAutoConfiguration
com.acme.autoconfigure.AcmeWebAutoConfiguration
```

**【强制】自动配置类用 `@AutoConfiguration`（Boot 3.x）并显式声明 `after`/`before` 顺序，禁止用普通 `@Configuration`；自动配置包不得被组件扫描命中。**

原因：`@AutoConfiguration` 携带排序语义且 meta 标注了 `@Configuration(proxyBeanMethods=false)`；被应用包扫描会破坏自动配置隔离。`(2.x: <2.7 无 @AutoConfiguration，用 @Configuration + @AutoConfigureOrder/Before/After。)`

**反例：**
```java
// ❌ 错误：作为 @Configuration 被应用包扫描发现
@Configuration
public class AcmeMetricsAutoConfiguration { ... }
```

**正例：**
```java
// ✅ 正确：位于独立包，显式排在 DataSource 之后
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@EnableConfigurationProperties(AcmeMetricsProperties.class)
public class AcmeMetricsAutoConfiguration { ... }
```

**【强制】`@ConditionalOnMissingBean` 只能用于返回具体类型（非接口）的 `@Bean` 方法，且不得依赖同类产出的 Bean。**

原因：条件按「到目前为止已定义的 Bean」评估，返回接口类型或评估顺序颠倒会让「用户覆盖」静默失效——要么用户 Bean 看到已注册框架默认值而启动失败，要么默认值胜出。依赖同类 Bean 的逻辑应放入普通 `@Configuration`。

**反例：**
```java
// ❌ 错误：声明类型是接口，条件看不到目标类型
@Bean
@ConditionalOnMissingBean
public Object sender() { ... }
```

**正例：**
```java
// ✅ 正确：签名返回具体类型；自动配置天然在用户配置之后运行
@Bean
@ConditionalOnMissingBean
public AcmeSender acmeSender() { return new NoopAcmeSender(); }
```

**【推荐】自定义 starter 拆成 `xxx-spring-boot-autoconfigure`（含自动配置）+ `xxx-spring-boot-starter`（空聚合器），并用 `spring-boot-configuration-processor` 生成元数据、`spring-boot-autoconfigure-processor` 预过滤条件。**

原因：`spring-configuration-metadata.json` 提供 IDE 补全与可审计属性文档；autoconfigure 处理器加速启动。

## 四、Bean 设计与装配

> 构造器注入优先、`@Qualifier`/`@Primary` 等 Spring 核心 DI 规范见 `java_spring_specification.md`，本节只覆盖 Boot 装配层的强约束。

**【强制】禁止把 `ApplicationContext`/Bean 引用存入静态持有者（`private static ApplicationContext ctx`）。**

原因：静态上下文持有者制造不可见全局状态，破坏测试间的上下文隔离，使 Bean 无法直接测试，并在 devtools/容器重启时泄漏 classloader 绑定的静态上下文。

**反例：**
```java
// ❌ 错误：静态持有 + getBean
SpringContextHolder.getApplicationContext().getBean(FontRegistry.class);
```

**正例：**
```java
// ✅ 正确：协作者注入；可选/延迟查找用 ObjectProvider
@Service
public class PdfRenderer {
    private final ObjectProvider<FontRegistry> fonts;
    public PdfRenderer(ObjectProvider<FontRegistry> fonts) { this.fonts = fonts; }
    public void render() { FontRegistry r = fonts.getIfAvailable(FontRegistry::defaultRegistry); }
}
```

**【强制】禁止开启循环依赖：`spring.main.allow-circular-references` 保持未设置或 false；出现环必须重构打破。**

原因：Boot 2.6+ 遇环直接拒绝启动（`BeanCurrentlyInCreationException`）；重新启用会掩盖设计缺陷（初始化顺序、半构造 Bean）。修复：抽出共享逻辑到第三个 Bean、反转一条依赖、用 `ObjectProvider`/事件，或作为最后手段在单条边上用 `@Lazy`。`(2.x: 2.6 起默认 false。)`

**反例：**
```properties
# ❌ 错误：用配置掩盖设计问题
spring.main.allow-circular-references=true
```

**正例：**
```java
// ✅ 正确：A -> (ObjectProvider)B 打断环
class A {
    private final ObjectProvider<B> b;
    void use() { b.getObject().foo(); }
}
```

**【强制】禁止依赖 Bean 覆盖：`spring.main.allow-bean-definition-overriding` 保持默认 false；需共存的同类型 Bean 用不同名或 `@Primary`/限定符。**

原因：静默覆盖会让一个定义按注册顺序替换另一个，是跨 profile/自动配置顺序的经典 heisenbug。`(2.x: 自 2.1 默认禁用覆盖。)`

**【推荐】`@Bean` 方法之间不互相调用时，配置类使用 `@Configuration(proxyBeanMethods = false)`（lite 模式）。**

原因：full 模式的 CGLIB 代理增加启动开销且要求类非 final；lite 是自动配置与简单配置类的合理默认。注意 lite 模式下的 Bean 方法互调会产生新实例而非返回受管 Bean。

**【参考】组件扫描只覆盖自有代码：`@SpringBootApplication` 放在模块根包，需要额外 Bean 用 `@Import` 而非扩大扫描范围。**

原因：扫描成本随 classpath 增长；扫入框架/三方包会拾取未知 Bean 并破坏自动配置隔离。

## 五、Web 层：全局异常与响应契约

**【推荐】按分层组织 Boot 应用包结构（`config/` `controller/` `service/` `repository/` `model/`（`dto`/`entity`/`vo`）`exception/` `util/`），禁止跨层调用；分层清晰与「不过度编程/不过度抽象」的通用要求见 `java_ai_constraints.md` 与 `java_basic_specification.md`。**

**【强制】Controller 保持精简：只做参数绑定与校验、调用 Service、组装响应，业务逻辑与事务边界下沉到 Service 层。**

原因：控制器承载业务后无法被 Service 层复用与单测，事务边界（`@Transactional`）会被错误地放到 Web 层，且异常处理、日志、权限逻辑随之散落。

**反例：**
```java
// ❌ 错误：控制器里直接写 SQL/事务/业务分支
@PostMapping("/api/v1/orders")
public Result<Order> create(@RequestBody OrderForm form) {
    Order order = new Order();
    // 校验库存、算价格、写库全部堆在控制器
    ...
    return Result.success(order);
}
```

**正例：**
```java
// ✅ 正确：控制器只做调度
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Validated
public class UserController {
    private final UserService userService;

    @PostMapping
    public Result<UserVO> createUser(@Valid @RequestBody UserCreateDTO userDTO) {
        return Result.success(userService.createUser(userDTO));
    }
}
```

**【强制】对外响应契约在全项目内保持一致：成功体统一使用同一套封装（裸 DTO 或统一 `Result<T>` 包装，二选一），错误体统一使用 `ProblemDetail`（见下条），禁止同一服务内混用多种响应风格。**

**正例：**
```java
// ✅ 正确：统一响应结构（若项目选择包装风格，则全部接口一致）
@Data
@AllArgsConstructor
public class Result<T> {
    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data);
    }

    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }
}
```

> 资源命名、方法语义、状态码与错误体的 API 设计契约见 `http_restful_specification.md`（doc_id: `rule-http-restful`）。

**【强制】API 错误集中在 `@RestControllerAdvice` 处理，返回 `ProblemDetail`（RFC 9454/7807）体，禁止裸抛堆栈或每控制器各自 try/catch。**

原因：无全局处理时每个控制器重复 try/catch，未捕获异常泄漏 Boot 默认错误体与类名。Boot 3 由 Spring Framework 6 提供 `ProblemDetail`/`ErrorResponse`/`ResponseEntityExceptionHandler`；可用 `spring.mvc.problemdetails.enabled=true` 开启框架自动处理（order 0，覆盖时自定义 advice 需排在其前）。

**反例：**
```java
// ❌ 错误：控制器各自 try/catch，把堆栈序列化给客户端
@GetMapping("/orders/{id}") Order get(@PathVariable long id) {
    try { ... } catch (Exception e) {
        return ResponseEntity.status(500).body(Map.of("trace", Arrays.toString(e.getStackTrace())));
    }
}
```

**正例：**
```java
// ✅ 正确：集中 advice，MVC 异常也走 problem+json，隐藏 cause
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleNotFound(OrderNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setProperty("orderId", e.getOrderId());
        return pd;
    }
}
```

**【强制】禁止把 JPA 实体作为请求/响应体，必须映射到 DTO/record。**

原因：Jackson 序列化实体会在 OSIV 关闭时对代理抛 `SerializationException`、产生 N+1 查询、并把 API 与持久层契约耦合（列变更即破坏客户端）；`@JsonIgnoreProperties` 只是脆弱补丁。

**反例：**
```java
// ❌ 错误：实体入响应，items 为 persistent bag -> N+1 + 懒加载失败
@GetMapping("/orders/{id}")
Order getOrder(@PathVariable long id) { return repository.findById(id).orElseThrow(); }
```

**正例：**
```java
// ✅ 正确：紧凑 record + 服务边界处映射
public record OrderSummary(long id, String status, Money total) {}

@GetMapping("/orders/{id}")
OrderSummary getOrder(@PathVariable long id) { return mapper.toSummary(service.find(id)); }
```

**【强制】禁止在 Web 边界把异常吞成 `null`/空响应，禁止在控制器宽泛 `catch (Exception)`。**

原因：被吞掉的失败对客户端表现为「神秘空数据」，且在指标/日志中不可见。应让 advice（见上）翻译异常。

**反例：**
```java
// ❌ 错误：吞异常返回 null
try { return service.find(id); } catch (Exception e) { log.error("error", e); return null; }
```

**正例：**
```java
// ✅ 正确：直接返回，DataAccessException -> advice -> ProblemDetail
return service.find(id);
```

**【推荐】引入 `spring-boot-starter-validation` 让 Bean Validation 自动配置生效；`@Valid`/约束注解本身用法见 `java_spring_specification.md`。**

原因：Boot 侧关注点是自动配置依赖与版本行为——`jakarta.validation` 实现（Hibernate Validator）在类路径时才自动配置校验；`(2.x: 用 javax.validation.*；Boot 3.4 改变了方法校验自动配置——任何类级 @Validated 的 Bean 都会自动配置 validator，无需显式 MethodValidationPostProcessor。)`

**【推荐】出站调用优先使用 `RestClient`（Boot 3.2+）/`WebClient`（响应式），不要新增 `RestTemplate` 代码。**

原因：`RestTemplate` 处于维护态（Spring Framework 7 起废弃）；`RestClient` 提供流式 API 并带 Boot 的 `RestClientBuilderConfigurer` 默认（指标、经 request factory 的超时），可用 `MockRestServiceServer`/`@RestClientTest` 测试。

**正例：**
```java
// ✅ 正确：构造器注入 builder（自动配置、可测试）
AcmeClient(RestClient.Builder builder) {
    this.restClient = builder.baseUrl("https://api.acme.com").build();
}
```

**【参考】转发 `traceparent`/关联头，并把 trace id 落入日志。**

原因：MDC `traceId/spanId`（`logging.pattern.correlation`）把 ProblemDetail 事件响应关联到链路；开启 tracing 桥（见「Actuator、指标与链路」）后 Boot 自动提供。

## 六、数据层（Boot 侧）

> `@Transactional` 加在 Service 层、`rollbackFor = Exception.class`、private/final 不可代理、自调用不走代理等事务基本规范见 `java_spring_specification.md`，本节只覆盖 Boot/数据源工程配置。

**【强制】关闭 open-in-view：`spring.jpa.open-in-view=false`。**

原因：OSIV 让 EntityManager/JPA session 贯穿整个请求（Tomcat 线程 + DB 连接在序列化/视图逻辑期间一直被持有），高负载下耗尽连接池，并诱导控制器/序列化器里的懒加载 N+1 陷阱。默认 `true` 只为模板视图便利，Boot 启动时会对默认值告警。

**正例：**
```properties
# ✅ 正确
spring.jpa.open-in-view=false
```

**【强制】所有环境用版本化迁移（Flyway 或 Liquibase）管理表结构，`spring.jpa.hibernate.ddl-auto=none`（灰度期可用 `validate`）。**

原因：`ddl-auto=update` 不能安全删除、无法迁移数据、不可评审、跨实例竞争；显式 `none` 防止意外的 `create-drop`。类路径有 Flyway/Liquibase 时 Boot 在 JPA bootstrap 前执行迁移（需要 `data.sql` 在迁移后跑则配 `spring.jpa.defer-datasource-initialization`）。

**正例：**
```yaml
# ✅ 正确
spring:
  jpa:
    hibernate:
      ddl-auto: none
  flyway:
    enabled: true
```

**【强制】消除 N+1：用 `@EntityGraph`/`join fetch` 或投影/DTO 查询批量取数，并设置 `hibernate.default_batch_fetch_size` 作为安全网。**

原因：OSIV 关闭后事务外懒访问抛异常，开启则序列化按行扇出查询（经典 N+1）。修复靠取数策略，而不是回开 OSIV。

**反例：**
```java
// ❌ 错误：findAll 后循环访问懒加载集合
List<Order> orders = repository.findAll();
orders.forEach(o -> o.getItems().size()); // 每行一次查询
```

**正例：**
```java
// ✅ 正确：实体图或 DTO 投影
@EntityGraph(attributePaths = "items") List<Order> findByStatus(Status s);

@Query("select new com.acme.OrderSummary(o.id, o.status) from Order o where o.status = :s")
List<OrderSummary> summaries(@Param("s") Status s);
```

**【推荐】只读查询标注 `@Transactional(readOnly = true)`（单个仓库读可不显式开事务）。**

原因：readOnly 带来 Hibernate 脏检查/HikariCP 优化、防止意外 flush，并支持读副本路由；对热点 GET 缺失是可度量的浪费。JPA + Hibernate 文档组合是 `readOnly=true` + `FlushMode.MANUAL`。

**正例：**
```java
// ✅ 正确
@Transactional(readOnly = true)
public List<Order> findByStatus(Status s) { return repository.findByStatus(s); }
```

**【推荐】把连接池超时收紧到快速失败：Hikari `connection-timeout`（默认 30s，酌情调低）、`validation-timeout`。**

原因：DB 故障时请求线程等连接 30s 与无限挂起表现相同；让池与启动检查快速反馈，readiness 才能反映真实状态。

**正例：**
```yaml
# ✅ 正确
spring:
  datasource:
    hikari:
      connection-timeout: 3000
      validation-timeout: 1000
```

## 七、Actuator、指标与链路

**【强制】Actuator 运行在独立 management 端口并限制绑定地址。**

原因：端点在主端口时可被任何能访问 API 的对象触及（并共享其线程池/连接耗尽）；独立端口让负载均衡/ingress 排除它，并可只绑定内网接口。

**反例：**
```yaml
# ❌ 错误：/actuator/** 在 server.port=8080 上，仅靠 Spring Security 保护
```

**正例：**
```yaml
# ✅ 正确
management:
  server:
    port: 8081
    address: 127.0.0.1
```

**【强制】显式白名单暴露端点，生产禁止 `include=*`；除 health/info 外一律鉴权。**

原因：默认仅 HTTP 暴露 `/actuator/health`；`env`、`beans`、`heapdump`、`shutdown`、`threaddump`、`configprops` 会泄漏密钥/内部结构（heapdump 即内存中的凭证）或成为攻击面（POST /shutdown）。`exclude` 优先于 `include`；`/env` 值默认被脱敏（`show-values=never`）。声明自定义 SecurityFilterChain 会关闭 actuator 自动保护，须显式补 `EndpointRequest.toAnyEndpoint()` 规则（见安全配置节）。

**反例：**
```yaml
# ❌ 错误
management.endpoints.web.exposure.include: "*"
```

**正例：**
```yaml
# ✅ 正确
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      show-details: when-authorized
    env:
      show-values: never
```

**【强制】暴露 liveness/readiness 健康分组并接为 Kubernetes 探针；liveness 禁止包含外部依赖检查。**

原因：Boot 在 K8s 上自动启用 `/actuator/health/liveness` 与 `/readiness`（也可 `management.endpoint.health.probes.enabled=true`）。因远程 DB 抖动而重启 Pod（liveness）会放大故障；readiness 才用于闸控流量。需要让 LB 在应用端口上做健康检查时用 `add-additional-paths=true` 镜像 `/livez`/`/readyz`。

**反例：**
```yaml
# ❌ 错误：把外部依赖放进 liveness
management.endpoint.health.group.liveness.include: livenessState,db
```

**正例：**
```yaml
# ✅ 正确
management:
  endpoint:
    health:
      group:
        liveness:
          include: livenessState        # 只含自足状态
        readiness:
          include: readinessState,db    # 依赖下游的检查放这里
```

**【推荐】给指标打服务身份标签并导出到 Prometheus。**

原因：`management.metrics.tags.application=${spring.application.name}`（外加 `deployment.environment`）让按服务的看板/告警可用；`micrometer-registry-prometheus` 自动配置 `/actuator/prometheus`，核心指标（`http.server.requests`、JVM、池）自动采集。

**正例：**
```yaml
# ✅ 正确
management:
  metrics:
    tags:
      application: ${spring.application.name}
```

**【推荐】通过 Micrometer Tracing 桥启用链路并显式设置采样率。**

原因：仅在类路径存在桥（`micrometer-tracing-bridge-brave`/`-otel` 或 zipkin starter）时自动配置才生效；默认采样 0.1，应按场景有意设置（canary 用 1.0、热点路径用 0.01）；优先 W3C 传播，日志关联用 `%X{traceId}`。

**【推荐】生产使用结构化 JSON 日志（Boot 3.4+ 内置）。**

原因：多行自由文本 pattern 会破坏 Loki/ES/CloudWatch 的机器解析；一个属性即可 `logging.structured.format.console=ecs|gelf|logstash`，MDC 键（含 traceId）自动内嵌。`(2.x / Boot <3.4: 需手动配置 logback-ecs-encoder 等。)` 日志级别与脱敏规则本身见 `java_logging_specification.md`。

**正例：**
```yaml
# ✅ 正确
logging:
  structured:
    format:
      console: ecs
```

## 八、优雅停机与生命周期

**【强制】启用优雅停机：`server.shutdown=graceful`（Boot 3.x 默认为 `immediate`）。**

原因：SIGTERM 下 `immediate` 会杀死在途请求与 DB 事务；负载均衡要等 readiness 翻转才摘除 Pod，无宽限期则每次部署都出现 502。`graceful` 让 Web 服务器在网络层停止接收新请求，并在 `spring.lifecycle.timeout-per-shutdown-phase`（默认 20s，通常短于最长请求，应有意调大）内让在途请求完成。容器侧 `terminationGracePeriodSeconds` 需大于该超时 + preStop 停顿，以便 LB 先排空。`(Boot 4+ 默认开启；2.x: 自 2.3 起同名属性。)`

**正例：**
```yaml
# ✅ 正确
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

**【推荐】用 lifecycle phase 排序停机逻辑，而非一串 `@PreDestroy`；上下文关闭前先 flush producer/cache。**

原因：无依赖关联的 Bean 间 `@PreDestroy` 顺序任意；Web 服务器停止发生在最早的 `SmartLifecycle` phase。Kafka/Rabbit producer、executor、scheduler 应在显式 phase 上停止并给出排空超时以 flush 缓冲消息。

**正例：**
```java
// ✅ 正确：独立生命周期 Bean，显式控制停止顺序
public class ProducerLifecycle implements SmartLifecycle { /* stop() 中 flush + close */ }
```

**【推荐】虚拟线程/执行器只在 Boot 配置层设置，具体参数与使用规范见 `java_concurrency_specification.md`。**

原因：Boot 3.2+ 提供 `spring.threads.virtual.enabled=true`（需 Java 21）切换 Web 服务器与 `@Async`/任务调度所用执行器；自定义 `AsyncTaskExecutor` Bean 需纳入 graceful shutdown 生命周期。本文只约束这些 Boot 开关与 Bean 注册，不展开线程池参数、队列与拒绝策略。`(2.x: 无虚拟线程支持。)`

**正例：**
```yaml
# ✅ 正确：Boot 层开关，线程池本身用法遵循 java_concurrency_specification.md
spring:
  threads:
    virtual:
      enabled: true
```

**【参考】用 `startupProbe` 承接慢启动，禁止对慢应用配短超时的 liveness。**

原因：JPA 校验 + Flyway + 上下文检查带来启动期快速失败；大 schema/AOT 关闭时启动慢，应走 startupProbe，避免在 liveness 上反复重启。

## 九、外部调用弹性

**【强制】每一个出站 HTTP 调用必须设置连接 + 读/响应超时，禁止用「无超时」默认值。**

原因：无读超时的上游挂起会永久占用请求线程 -> 线程池耗尽 -> 级联自伤。Boot 3.2+ 集中了设置（`ClientHttpRequestFactorySettings` / 3.4 起 `spring.http.client.*` 与 request-factory 自动配置）。`(2.x: 通过 builder `requestFactory(...)` 或 HttpComponents 直接设置。)`

**反例：**
```java
// ❌ 错误：无工厂超时，等于无限
new RestTemplate();
```

**正例：**
```java
// ✅ 正确：Boot 3.x 集中设置连接/读超时
ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
        .withConnectTimeout(Duration.ofSeconds(2))
        .withReadTimeout(Duration.ofSeconds(5));
var f = ClientHttpRequestFactoryBuilder.apache().build(settings);
RestClient client = RestClient.builder().requestFactory(f).build();
```

**【强制】WebClient/Reactor Netty 必须在连接提供者上设置 `responseTimeout`（或读写超时）——Netty 默认无响应超时。**

原因：无 responseTimeout 的响应式调用会挂住订阅直到 TCP 死；同时限制 `pendingAcquireTimeout`/最大连接，避免上游停摆吃掉连接池。

**正例：**
```java
// ✅ 正确
HttpClient http = HttpClient.create(provider)
        .responseTimeout(Duration.ofSeconds(3))
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000);
WebClient client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
```

**【强制】任何重试必须有界：最大次数、超时预算、带 jitter 的指数退避，且只重试幂等调用。**

原因：无界/同步重试会把局部故障放大成惊群（retry storm）。Resilience4j 默认 `maxAttempts=3`、`waitDuration=500ms`；退避+jitter 用 `IntervalFunction.ofExponentialRandomBackoff`；绝不重试 4xx（校验类），只对瞬时 IO/5xx 的幂等操作重试。

**反例：**
```java
// ❌ 错误：无界重试
while (true) { try { call(); break; } catch (Exception ignore) {} }
```

**正例：**
```java
// ✅ 正确：有界 + 指数随机退避 + 限定异常
RetryConfig config = RetryConfig.custom()
        .maxAttempts(3)
        .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                Duration.ofMillis(200), 2.0, 0.5))
        .retryExceptions(SocketTimeoutException.class)
        .ignoreExceptions(OrderRejectedException.class)
        .build();
```

**【推荐】对同步外部调用用 Resilience4j 熔断器包装，让慢上游快速跳闸并配降级。**

原因：熔断让调用方线程池恢复，而不是每次都对死依赖等待；默认滑动窗口、`failureRateThreshold=50%`、`waitDurationInOpenState=60s`、半开探测。

**正例：**
```yaml
# ✅ 正确
resilience4j:
  circuitbreaker:
    instances:
      billing:
        sliding-window-size: 20
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
```

**【推荐】在客户端调用与外层预算两处都设超时，内层超时必须短于调用方超时。**

原因：网关总预算 2s 时，下游读超时 10s 毫无意义——调用方先超时并在你线程仍燃烧时重试。原则：客户端超时 < 调用方超时；DB 池 checkout < 请求超时。使用 OpenFeign 时同理：显式设置每客户端 `connect-timeout`/`read-timeout`，非幂等 POST 保持 `Retryer.NEVER_RETRY` 默认。

## 十、测试分层

**【强制】默认使用切片测试（`@WebMvcTest`、`@DataJpaTest`、`@JsonTest`、`@RestClientTest`），`@SpringBootTest` 只留给真正的集成测试。**

原因：`@SpringBootTest` 启动完整上下文（每个自动配置、DB 池、迁移），单类数秒且把测试耦合到无关基础设施；切片只加载目标子集并用 `@MockitoBean` mock 协作者/`@DataJpaTest` 用嵌入式库。

**反例：**
```java
// ❌ 错误：为断言一个 mapper 启动整个应用
@SpringBootTest class OrderControllerTest { @Autowired MockMvc mvc; }
```

**正例：**
```java
// ✅ 正确：控制器切片只含 Web 层 + advice
@WebMvcTest(OrderController.class)
class OrderControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean OrderService service;
}
```

**【强制】用 Testcontainers + `@ServiceConnection`（Boot 3.1+）在测试中跑真实 DB/broker；禁止用 H2 代替生产库、禁止手搓 `@DynamicPropertySource` URL。**

原因：Postgres/MySQL 方言、针对真实引擎的 Flyway 迁移、Kafka 语义都与 H2/内存伪实现不同；`@ServiceConnection` 自动把容器连接详情接入上下文。`(2.x: Boot <3.1 用 @DynamicPropertySource。)`

**正例：**
```java
// ✅ 正确
@SpringBootTest
@Testcontainers
class OrderFlowIT {
    @ServiceConnection @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
}
```
```yaml
# ❌ 错误：测试连 H2、生产跑 Postgres，方言/迁移逃逸
# url=jdbc:h2:mem:...
```

**【强制】Mock Bean 用 `@MockitoBean`/`@MockitoSpyBean`（Boot 3.4+）；`@MockBean`/`@SpyBean` 已废弃（Boot 4 移除）。**

原因：新注解配合 `spring-boot-test` 的 `MockitoTestExecutionListener`；Boot 3.4 迁移是机械替换。`(2.x: @MockBean 是唯一选项——生成代码时注意版本。)`

**正例：**
```java
// ✅ 正确
@WebMvcTest(OrderController.class)
class T { @MockitoBean OrderService service; }
```

**【强制】不要 mock 你要测试的框架本身：禁止 mock `ApplicationContext`、JPA `EntityManager` 内部或 `RestClient` 内部；配置类用 `ApplicationContextRunner` 测试。**

原因：mock Spring 机制（直接调 `@Bean` 方法测配置、stub MVC dispatcher）会得到「真实集成已坏却仍通过」的测试；Web JSON 用 MockMvc/`MockMvcTester` 测。

**正例：**
```java
// ✅ 正确：不 mock 容器内部，用 runner 验证自动配置
new ApplicationContextRunner()
    .withUserConfiguration(AcmeMetricsAutoConfiguration.class)
    .withPropertyValues("acme.metrics.enabled=true")
    .run(ctx -> assertThat(ctx).hasSingleBean(Meter.class));
```

**【推荐】保持 `@SpringBootTest` 上下文可缓存：跨测试类使用相同的属性/profile/mock 集。**

原因：测试上下文缓存以配置参数/mock/属性源为键；每类散落 `@TestPropertySource`/不同 mock 集会静默倍增昂贵的启动；IT 应放到独立模块或 tag，保持单测阶段快。

**【参考】端到端全服务器测试仅覆盖少数流程：`@SpringBootTest(webEnvironment=RANDOM_PORT)` + `TestRestTemplate`/`RestClient`。**

## 十一、安全配置

**【强制】用 `SecurityFilterChain` Bean 配置 Spring Security（Boot 3 lambda DSL；`WebSecurityConfigurerAdapter` 已移除），默认拒绝、显式声明公开路径。**

原因：Boot 3.x 安全 starter 默认所有请求需鉴权 + 随机密码 dev 兜底（不是生产方案）；适配器在 Spring Security 6 移除。声明自己的 chain 会关闭 actuator 自动保护，须有意加入端点规则。

**反例：**
```java
// ❌ 错误：放行一切上线；或 5.x 适配器风格
http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
```

**正例：**
```java
// ✅ 正确：Boot 3.x lambda DSL，默认拒绝 + 显式公开路径 + 无状态 API 关 CSRF
@Bean
SecurityFilterChain api(HttpSecurity http) throws Exception {
    return http.securityMatcher("/api/**")
        .authorizeHttpRequests(a -> a.requestMatchers("/api/health").permitAll().anyRequest().authenticated())
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
        .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**")).build();
}
```

**【强制】CORS 显式列出允许来源；带凭证的请求禁止用 `*`。**

原因：浏览器会在跨源带凭证请求中发送 cookie/Authorization；Spring 运行时拒绝 `allowCredentials(true) + allowedOrigins("*")`（来源确为模式绑定才用 `allowedOriginPatterns`）。通配会把任意网站变成代理攻击者。

**反例：**
```java
// ❌ 错误：cookie 在场仍每个控制器 @CrossOrigin(origins = "*")
@CrossOrigin(origins = "*")
```

**正例：**
```java
// ✅ 正确：显式来源，按路径注册
var c = new CorsConfiguration();
c.setAllowedOrigins(List.of("https://app.acme.com"));
c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
c.setAllowCredentials(true);
```

**【强制】Actuator 敏感值保持脱敏，并在 CI 扫描密钥。**

原因：`management.endpoint.env.show-values=never`（默认）、`health.show-details` 门控、`configprops` 脱敏，正是因为有人会往配置放密钥；再加 gitleaks/trufflehog 作为第二道网。密钥外部化本身见「配置绑定与 Profile」。(2.x: 自 2.3 起同样脱敏。)

**【推荐】反向代理后校验 `X-Forwarded-*`/forwarded 头：`server.forward-headers-strategy=framework/native`。**

原因：不配置时生成的 URL、OAuth 重定向与安全方案探测会用内部 `http://host:port`，导致登录循环、不安全 cookie。受支持路径为 Spring Security 的 `ForwardedHeaderFilter` 与 Tomcat 的 RemoteIpValve。

## 十二、容器化构建与启动性能

**【强制】用分层 jar 制作镜像：`layertools` 提取各层并按层 COPY，跨代码变更缓存依赖层。**

原因：不分层则每次重建都 COPY 整个 fat jar，冲掉镜像缓存。Boot jar 含 `layers.idx`（dependencies / spring-boot-loader / snapshot-dependencies / application）。替代方案：`spring-boot:build-image`（Buildpacks）自动分层。

**反例：**
```dockerfile
# ❌ 错误：每次构建新增一个 300MB 层
COPY app.jar /app.jar
```

**正例：**
```dockerfile
# ✅ 正确：多阶段分层构建（每层单独 COPY，依赖层跨代码变更复用缓存）
FROM eclipse-temurin:17-jdk AS extract
COPY target/app.jar /app.jar
RUN java -Djarmode=layertools -jar /app.jar extract
FROM eclipse-temurin:17-jre
COPY --from=extract /dependencies/ ./
COPY --from=extract /spring-boot-loader/ ./
COPY --from=extract /snapshot-dependencies/ ./
COPY --from=extract /application/ ./
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

**【推荐】用 CDS/AOT 加速容器冷启动（Boot 3.3+ 支持 layertools/CDS；`spring.aot.enabled` 预计算 Bean）。**

原因：容器冷启动 = JVM 对上千类的加载与校验；AppCDS 把已加载堆归档为 mmap，启动提升约百分之几十且跨 JVM 共享内存。`(2.x: 无内置支持；追求极致冷启动再评估 CRaC/GraalVM native。)`

**【推荐】按容器上限给 JVM 定容：用 `-XX:MaxRAMPercentage`（如 70–80）而非固定 `-Xmx`。**

原因：固定字节 `-Xmx` 忽略 Pod 内存变化；默认堆为容器 25% 可能饿死小 Pod 或 OOMKill 大 Pod；Java 10+/17 会读取 cgroup 限制。

**正例：**
```yaml
# ✅ 正确：随 Pod 内存自适应；❌ 错误：512Mi 的 Pod 里固定 -Xmx512m -> OOMKilled
env:
  - name: JAVA_TOOL_OPTIONS
    value: "-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
resources:
  limits:
    memory: 512Mi
```

## 禁止事项

**【强制】禁止以下做法：**

1. ❌ 对 Boot BOM 托管依赖手工钉版本，或不用 starter-parent/BOM
2. ❌ 把 `spring-boot-devtools` 带到 compile/runtime 或生产
3. ❌ 用散落 `@Value` 承载分组配置；配置类不做 `@Validated`；密钥写进配置文件或提交仓库
4. ❌ 在 `META-INF/spring.factories` 注册自动配置；自动配置类用 `@Configuration` 被扫描；对返回接口类型的 `@Bean` 用 `@ConditionalOnMissingBean`
5. ❌ 用静态 `ApplicationContext` 持有者取 Bean；开启 `allow-circular-references` 或用 Bean 覆盖达成装配
6. ❌ 控制器各自 try/catch、返回裸堆栈，或把异常吞成 `null`/空响应；把 JPA 实体直接作为响应体
7. ❌ 保持 `spring.jpa.open-in-view` 默认 `true`；生产用 `ddl-auto=update`/`create-drop` 代替版本化迁移
8. ❌ 循环访问懒加载集合造成 N+1，或用回开 OSIV 来「解决」N+1
9. ❌ Actuator 在主端口暴露、生产 `include=*` 或暴露 heapdump/shutdown；把外部依赖检查放进 liveness 探针
10. ❌ 关闭优雅停机（`server.shutdown=immediate`）就上线
11. ❌ 出站 HTTP 不设连接/读超时；重试无界、无 jitter 或重试非幂等/4xx；内层超时 > 外层预算
12. ❌ 用 `@SpringBootTest` 做本可切片完成的测试；用 H2 冒充生产库；继续用 `@MockBean`；mock 被测框架本身
13. ❌ `.anyRequest().permitAll()` 上线；带凭证仍 `allowedOrigins("*")`
14. ❌ 不分层地 COPY 整个 fat jar；512Mi Pod 里固定 `-Xmx512m`

## 快速检查清单

- [ ] Boot 托管依赖是否都交给 starter-parent/BOM，未手工钉版本？devtools 是否 `optional`/`developmentOnly`？
- [ ] 分组配置是否 `@ConfigurationProperties` + `@Validated`、kebab-case；密钥是否全部外部化且仓库无明文？
- [ ] 自动配置是否注册在 `AutoConfiguration.imports`、用 `@AutoConfiguration` 且未被扫描？
- [ ] 是否存在静态上下文持有者、循环依赖或 Bean 覆盖？
- [ ] Controller 是否只做绑定/校验/调用 Service/组装响应；全项目响应契约是否统一（成功体一种风格 + 错误体 `ProblemDetail`）？
- [ ] 错误是否集中在 `@RestControllerAdvice` + `ProblemDetail`；响应是否 DTO/record 而非 JPA 实体？
- [ ] `spring.jpa.open-in-view=false`、`ddl-auto=none` + Flyway/Liquibase 是否已设；N+1 是否消除、只读是否 `readOnly`？
- [ ] Actuator 是否独立端口、白名单暴露并鉴权；liveness 是否只含自足状态？
- [ ] `server.shutdown=graceful` 与 `timeout-per-shutdown-phase` 是否配置，容器宽限期是否更大？
- [ ] 每个出站调用是否都有连接/读超时；重试是否有界 + jitter + 只幂等；是否配熔断与超时预算？
- [ ] 测试是否优先切片、DB 用 Testcontainers `@ServiceConnection`、Mock 用 `@MockitoBean`、未 mock 框架本身？
- [ ] Security 是否 `SecurityFilterChain` 默认拒绝、CORS 是否显式来源？
- [ ] 镜像是否用分层 jar 构建，JVM 是否用 `MaxRAMPercentage` 定容？
