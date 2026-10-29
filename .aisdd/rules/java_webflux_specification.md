---
name: Spring Boot / Spring WebFlux 响应式开发规范
version: 1.0.0
description: 面向 AI Agent 的 Spring Boot + Spring WebFlux（响应式）开发规范，基线 Spring Framework 6.x / Spring Boot 3.2+ / Project Reactor 3.6+ / Java 17+。覆盖 WebFlux 选型判定（何时用响应式、何时 MVC + 虚拟线程更优）、事件循环线程模型与阻塞禁令、`Schedulers.boundedElastic()` 逃逸通道与上限、BlockHound 门禁、`reactor.netty.ioWorkerCount` 约束、控制层 Mono/Flux 返回类型与空值安全、SSE/NDJSON 流式输出与心跳、背压策略边界与有界容量、缓冲算子对 O(1) 内存保证的影响与 `maxInMemorySize`、flatMap/concatMap/zip/defer/share 组合与信号纪律、裸 subscribe 与嵌套订阅禁令、错误即终止与 `onErrorResume`/`doOnError`/全局 `WebExceptionHandler` + `ProblemDetail`、ThreadLocal/MDC 禁用与 Reactor `Context` + Micrometer context-propagation、Scheduler 选型矩阵、R2DBC 数据访问与连接池尺寸、Reactor Kafka 停维与 Spring for Apache Kafka 替代、出站 `WebClient` 与 `.timeout()`、资源安全（`using`/`usingWhen`、`DataBuffer` 引用计数）、优雅停机、以及 StepVerifier 响应式测试。与 Spring MVC、并发、Spring Boot、日志、中间件规范的分工参见对应文件。约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。
---

# Spring Boot / Spring WebFlux 响应式开发规范

## 前言

本规范面向 Spring Boot 3.2+ / Spring Framework 6.x + Spring WebFlux（Project Reactor 3.6+、Java 17+）的响应式服务端开发，只覆盖响应式特有的风险面：事件循环线程模型、阻塞与逃逸通道、流的信号语义与背压、错误即终止、跨线程上下文传播、R2DBC 数据访问、缓冲区与资源安全、响应式测试纪律。

分工边界（不在此重复）：命名/集合/异常/注释参见 `java_basic_specification.md`；Spring 核心 DI 与 Spring MVC 注解、参数绑定参见 `java_spring_specification.md`；日志与监控参见 `java_logging_specification.md`；Redis / MyBatis / Kafka 集成参见 `java_middleware_specification.md`；依赖与 starter 治理、`@ConfigurationProperties`、密钥外部化、Actuator 加固、graceful shutdown 通用配置、RestClient/WebClient 超时、测试分层与 Testcontainers、容器化构建参见 `java_springboot_specification.md`（本规范只写响应式侧的额外约束）；线程池参数、队列、CompletableFuture 与锁参见 `java_concurrency_specification.md`（本规范只覆盖 Scheduler 侧的对应问题）。

约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。本规范的调研依据与一手出处见 `research/research_spring_webflux.md`。

> 前提认知：响应式与非阻塞一般不会让单个请求更快，其收益是"以小固定线程池和更少内存来扩容"，因此选错模型的成本高于选错参数；官方也明确对多数应用"这种转变是不必要的"。

## 一、选型判定与架构定位

**【强制】仅当"高并发 I/O（慢 I/O 或扇出）+ 端到端全链路非阻塞依赖（R2DBC / WebClient / 响应式 MQ）+ 团队具备 Reactor 能力"三者同时成立时才允许新建 WebFlux 服务，否则默认 Spring MVC；无必要不选 WebFlux。**

失败模式：链路上只要有一个阻塞依赖（JPA / JDBC / 同步 SDK），响应式栈的扩容优势立刻消失，服务却背上最贵的编程模型，且阻塞点会让整条事件循环线程停摆。

**反例：**
```java
// ❌ 错误：选了 webflux 却继续用 JdbcTemplate，全链路仍被阻塞
@GetMapping("/order/{id}")
public Mono<Order> get(@PathVariable String id) {
    return Mono.just(jdbcTemplate.queryForObject(SQL, new OrderRowMapper(), id));
}
```

**正例：**
```java
// ✅ 正确：决策记录写明并发连接量、扇出倍数与非阻塞依赖清单（webflux + data-r2dbc）后才选型
@GetMapping("/order/{id}")
public Mono<Order> get(@PathVariable String id) { return orderRepository.findById(id); }
```

**【强制】WebFlux 应用禁止注册 Servlet Filter、禁止直接操作 Servlet API，跨切面逻辑一律实现 `WebFilter`。**

原因：同一上下文中混用阻塞 I/O 与非阻塞 I/O 会引发运行期问题，官方明确强烈不建议把 Servlet 过滤器映射进 WebFlux 应用。

**反例：**
```java
// ❌ 错误：把 MVC 的 OncePerRequestFilter 搬进 WebFlux 做鉴权
public class AuthFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest req, FilterChain chain) {
        userService.loadBlocking(req.getHeader("X-Token"));   // ❌ 阻塞
        chain.doFilter(req, resp);
    }
}
```

**正例：**
```java
// ✅ 正确：WebFilter 返回 Mono<Void>，鉴权本身非阻塞
public class AuthWebFilter implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String token = exchange.getRequest().getHeaders().getFirst("X-Token");
        return userService.findByToken(token)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)))
                .flatMap(user -> chain.filter(exchange));
    }
}
```

**【推荐】按可判定标准在 MVC 与 WebFlux 之间二选一，禁止凭"性能"直觉整体重写已稳定的 MVC 服务。**

| 判定信号 | 结论 |
|---|---|
| 目标只是消除"等待线程占用"，依赖为阻塞 JDBC / 同步远程调用，Java 21+ | Spring MVC + `spring.threads.virtual.enabled=true`（确认驱动无 `synchronized` 块内阻塞导致的 pinning） |
| 需要流式输出（SSE / 大文件 / 长连接）、背压控制、scatter-gather 编排、RSocket、高并发慢 I/O 网关 | WebFlux + R2DBC / WebClient |
| 典型 CRUD、后台管理、依赖含 JPA | Spring MVC |

**【参考】MVC 控制器也可以返回 `Mono`/`Flux`（走 Servlet 异步适配）：此时不适用"事件循环线程数极小"的前提，但第四至第六节的信号、组合与错误规则仍然适用。**

## 二、禁止阻塞事件循环与逃逸通道

**【强制】请求处理链（`WebFilter` / Controller / Service / Repository）中禁止任何阻塞调用：JDBC、JPA 懒加载、`Thread.sleep`、`InputStream.read`、同步 `RestTemplate`/`HttpClient`、`BlockingQueue.take`、`CountDownLatch.await`、`CompletableFuture.join`、以及任何 `synchronized` 内的 I/O。**

失败模式：WebFlux 用固定小线程池（约 CPU 核数个事件循环线程）承载全部连接，一次阻塞会把该线程上所有在途连接同时停摆，表现为吞吐雪崩式坍塌。

**反例：**
```java
public Mono<Quote> quote(String symbol) {
    Thread.sleep(200);                            // ❌ 错误：阻塞事件循环
    latch.await();                                // ❌ 错误：等待锁
    return Mono.just(syncClient.get(symbol));     // ❌ 错误：同步 SDK
}
```

**正例：**
```java
public Mono<Quote> quote(String symbol) {
    return quoteGateway.of(symbol)                        // ✅ 正确：返回 publisher，由框架订阅
            .delayElement(Duration.ofMillis(200));        // ✅ 正确：延迟用定时器，不 sleep
}
```

**【强制】生产代码禁止 `Mono.block()` / `Flux.blockFirst()` / `Flux.blockLast()` / `Flux.toStream()`；`.block()` 只允许出现在测试代码与独立 `main`/批处理入口（见第十节）。**

**反例：**
```java
// ❌ 错误：Netty 线程上 block，轻则自锁死锁，重则直接抛 IllegalStateException
public Mono<List<Item>> batch(@RequestBody Flux<Item> items) { return Mono.just(items.collectList().block()); }
```

**正例：**
```java
// ✅ 正确：把"等待结果"表达为组合算子，订阅由框架完成
public Mono<List<Item>> batch(@RequestBody Flux<Item> items) { return items.collectList(); }
```

**【强制】不可避免的阻塞调用只允许一种姿势：`Mono.fromCallable(阻塞调用).subscribeOn(Schedulers.boundedElastic())`，`subscribeOn` 必须紧跟数据源且一条链只出现一次；禁止用无上限的 `Schedulers.elastic()`，禁止把阻塞放在带 `NonBlocking` 标记的 `Schedulers.parallel()` / `single()` 上；阻塞 SDK 必须封装在独立适配层，对外只暴露 `Mono`/`Flux`。**

原因：`boundedElastic` 专为"给阻塞过程一个自己的线程"设计且带线程与队列上限；在 `parallel()/single()` 上 `block()` 直接抛 `IllegalStateException`；叠加多个 `subscribeOn` 只产生无效线程切换。

**反例：**
```java
return legacySdk.call().subscribeOn(Schedulers.elastic()).block();   // ❌ 错误：无上限 + block
return Mono.fromCallable(() -> legacySdk.call())
        .subscribeOn(Schedulers.parallel())            // ❌ 错误：NonBlocking 线程上跑阻塞
        .subscribeOn(Schedulers.boundedElastic());     // ❌ 错误：叠加 subscribeOn
```

**正例：**
```java
// ✅ 正确：阻塞调用整体包装，唯一一次 subscribeOn 紧跟源
public Mono<Balance> balanceOf(String accountId) {
    return Mono.fromCallable(() -> legacySdk.queryBalance(accountId))
            .subscribeOn(Schedulers.boundedElastic());
}
```

**【强制】CI 必须启用 BlockHound（`-javaagent` 或 `BlockHound.install()`）检测非阻塞线程上的阻塞调用并 fail build；新增 `BlockHoundIntegration` 白名单必须走评审。**

原因：阻塞点常藏在三方库内部（日志实现、DNS、缓存驱动），人工 review 发现不了，只有运行时插桩能给出精确栈；仅在 code review 里口头承诺"这条链应该没阻塞"不构成任何防护。

**正例：**
```java
// ✅ 正确：把阻塞检测固化为门禁；链上有阻塞时 BlockHound 抛 BlockingOperationError
```

**【强制】不得用 `reactor.netty.ioWorkerCount` 扩大事件循环线程数来"吸收"阻塞；默认值为 `max(availableProcessors, 4)`，任何覆盖必须附压测/容量数据。**

**反例：**
```java
// ❌ 错误：调到 200 来缓解 JDBC 卡顿，等于把 WebFlux 当线程池版 MVC 用，小线程池的内存优势同时失效
System.setProperty("reactor.netty.ioWorkerCount", "200");
```

**正例：**
```java
// ✅ 正确：保持默认事件循环规模；阻塞依赖走 boundedElastic，容量问题在依赖侧解决
// ✅ 正确：确有调参需求时，提交压测报告（线程数 vs P99 vs RSS）后再设置
```

**【推荐】理解并使用 `boundedElastic` 的既成上限：默认线程数 `CPU 核数 × 10`、排队任务上限 100 000、空闲 60s 回收；它是阻塞逃逸通道而非业务线程池，队列打满即快速拒绝。调度器内部参数治理参见 `java_concurrency_specification.md`。**

**【参考】Java 21+ 可用 `-Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true` 让 `boundedElastic` 以虚拟线程实现从而消除排队上限，仅建议在阻塞 SDK 极重、排队明显的服务压测后启用。**

## 三、控制层返回类型与空值安全

**【强制】`@RestController` 方法返回类型只能是 `Mono<T>` / `Flux<T>` 或 `ResponseEntity<Mono<T>>` / `ResponseEntity<Flux<T>>`：0..1 用 `Mono`、0..N 用 `Flux`、无响应体用 `Mono<Void>`；需要状态码/响应头时外层 `ResponseEntity` 同步构造、体内放 `Mono`，禁止返回裸实体或 `List` 并在其中阻塞取值。**

**反例：**
```java
// ❌ 错误：同步 List 返回值 + 内部 block（同时违反第二节禁令）
public List<Item> list() { return repository.findAll().collectList().block(); }

// ❌ 错误：为拿状态码先 block body
Item saved = item.flatMap(repository::save).block();
return ResponseEntity.created(URI.create("/items/" + saved.getId())).body(saved);
```

**正例：**
```java
public Flux<Item> list() { return repository.findAll(); }                              // ✅ 0..N
public Mono<Item> one(@PathVariable String id) { return repository.findById(id); }      // ✅ 0..1
public Mono<Void> delete(@PathVariable String id) { return repository.deleteById(id); } // ✅ 无体

// ✅ 正确：状态码可先于 body 解析发出，body 保持 Mono
public Mono<ResponseEntity<Item>> create(@RequestBody Mono<Item> item) {
    return repository.create(item).map(s -> ResponseEntity.created(URI.create("/items/" + s.getId())).body(s));
}
```

**【强制】`Mono` / `Flux` 的泛型参数不得为 `null`，禁止 `Mono.just(可能为 null 的值)`；空结果用 empty 序列表达，缺省用 `justOrEmpty` / `next()` / `switchIfEmpty` / `defaultIfEmpty`。**

原因：Reactive Streams 规范禁止 `onNext(null)`，`Mono.just(null)` 在装配期即抛 NPE。

**反例：**
```java
return Mono.just(userDao.findOrNull(id));       // ❌ 错误：可能为 null，运行期 NPE
return service.account().onErrorReturn(null);   // ❌ 错误：兜底值同样不得为 null
```

**正例：**
```java
// ✅ 正确：可缺失的值用 justOrEmpty，空结果显式兜底
return Mono.justOrEmpty(userDao.findOrNull(id))
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
return items.next();     // ✅ 正确：取首元素得到可能为空的 Mono，而不是 null
```

**【参考】请求体是单次消费的流：重复读取必须显式缓存并评估内存上界（见第四节）；请求体为 `Flux<T>` 的逐元素解码只在 NDJSON / json-stream 媒体类型下成立，`application/json` 单文档仍是整包解析；注解与绑定语义差异参见 `java_spring_specification.md`。**

## 四、流式输出、背压与缓冲上界

**【强制】响应默认逐元素流式写出，禁止把 MVC 时代"先攒全量再返回"的写法平移进来：不得仅为复用旧工具方法或仅为计数而 `collectList()`；计数用 `Flux.count()`，需要总数配分页端点。确需整包数组时必须是显式例外：注释理由 + 基数硬上限（`take(limit)`）。**

原因：`collectList()` 破坏 O(1) 内存保证；且响应一旦写出并被 commit，之后的错误无法再渲染成错误体。

**反例：**
```java
Mono<List<Row>> export = repository.findAll().collectList();               // ❌ 错误：整表物化
Mono<Integer> count = repository.findAll().collectList().map(List::size);  // ❌ 错误：仅为计数
```

**正例：**
```java
Flux<Row> export = repository.findAll();                        // ✅ 正确：流式写出
Mono<Long> count = repository.findAll().count();                // ✅ 正确：O(1) 计数
// ✅ 正确：显式例外 + 理由 + 硬上限（例外说明：下游 SDK 只接受整包数组，业务上限 2000 条）
Mono<List<Row>> summary = repository.findAll().take(2000).collectList();
```

**【强制】缓冲/窗口算子必须带上界参数：`buffer(maxSize, …)`、`window(maxSize)`、`windowTimeout(count, maxAge)`；禁止无界 `buffer()`；重叠窗口与 `bufferWhen` 聚合须评审（可能重复释放、内存膨胀）。**

**反例：**
```java
Flux<List<Event>> batched = events.buffer();   // ❌ 错误：无界缓冲，慢消费者把内存吃光
```

**正例：**
```java
Flux<List<Event>> batched = events.buffer(500, Duration.ofSeconds(2));             // ✅ 正确
Flux<Flux<Event>> windows = events.windowTimeout(1000, Duration.ofSeconds(30));    // ✅ 正确
```

**【强制】`onBackpressureX` 只能出现在真实的生产/消费边界，且必须显式声明策略语义并写注释；默认禁用无参 `onBackpressureBuffer()`（无界），需要缓冲时必须给 `maxSize` + `BufferOverflowStrategy` + 丢弃回调。**

| 策略 | 适用边界 | 语义代价 |
|---|---|---|
| `onBackpressureBuffer(maxSize, strategy, onDrop)` | 允许短时吸收突发 | 内存换时间，丢弃必须可观测 |
| `onBackpressureDrop()` | 指标、日志等可丢采样流 | 丢数据，必须打点 |
| `onBackpressureLatest()` | UI 状态、行情最新值 | 只保最新，历史丢失 |
| `onBackpressureError()` | 保护上游，快速失败 | 慢消费者直接终止 |

**反例：**
```java
source.onBackpressureBuffer();   // ❌ 错误：无界缓冲直连 SSE 输出，慢客户端导致服务端 OOM
```

**正例：**
```java
// ✅ 正确：有界 + 明确溢出策略 + 丢弃可观测（语义：丢最旧，保最新 8192 条）
source.onBackpressureBuffer(8192,
        discarded -> metrics.counter("webflux.buffer.dropped").increment(),
        BufferOverflowStrategy.DROP_OLDEST);
ticks.onBackpressureLatest();     // ✅ 正确：行情类只关心最新值
```

**【强制】`text/event-stream` 与 `application/x-ndjson` 流式输出必须配心跳（comment-only 空事件）并在数据流终止后停止，同时文档化取消语义（客户端断开 → `cancel` 向上传播，用 `doOnCancel` / `takeUntilOther` 释放上游订阅）。**

原因：不周期性发送数据就无法可靠、及时地感知断开的客户端，服务端会持续无谓生成并泄漏订阅。

**反例：**
```java
// ❌ 错误：长连接只推业务数据，客户端断线后上游仍在生成，无人释放
public Flux<String> stream() { return service.stream(); }
```

**正例：**
```java
public Flux<ServerSentEvent<String>> stream() {
    Flux<ServerSentEvent<String>> data = service.stream().map(ServerSentEvent::builder);
    Flux<ServerSentEvent<String>> hb = Flux.interval(Duration.ofSeconds(15))
            .map(t -> ServerSentEvent.<String>builder().comment("hb").build());
    // ✅ 正确：心跳兜住断线检测，数据流结束即停心跳
    return Flux.merge(data, hb.takeUntilOther(data.ignoreElements()));
}
```

**【强制】显式设置编解码缓冲上限 `maxInMemorySize`：服务端用 `spring.codec.max-in-memory-size`（默认 256KB），出站 `WebClient` 用 `ExchangeStrategies`；保留 `DataBufferLimitException` 作为护栏，禁止为"跑通业务"把它调到数十 MB 以上；上传与大请求体改用 multipart / `Flux<DataBuffer>` 边读边落盘，禁止 `Mono<byte[]>` 整包承接。**

**反例：**
```yaml
# ❌ 错误：把上限抬到 512MB 来"解决"大响应问题，护栏消失
spring:
  codec:
    max-in-memory-size: 512MB
```

**正例：**
```yaml
# ✅ 正确：按业务最大值设定，超限即快速失败并转为流式/分页
spring:
  codec:
    max-in-memory-size: 512KB
```
```java
// ✅ 正确：出站侧同样显式设定
WebClient client = builder.codecs(c -> c.defaultCodecs().maxInMemorySize(512 * 1024)).build();
```

**【推荐】`Flux.create` / `Sinks` 只用于 callback → 响应式的适配入口，必须指定溢出策略与 `bufferSize`，并处理 `tryEmitNext` 的失败枚举，禁止丢弃返回码导致溢出被静默吞掉。**

```java
Sinks.Many<Metric> sink = Sinks.many().multicast().onBackpressureBuffer(1024, false);
Sinks.EmitResult r = sink.tryEmitNext(metric);   // ✅ 正确：检查 EmitResult 并让溢出可见
if (r == Sinks.EmitResult.FAIL_OVERFLOW) { metrics.counter("sink.overflow").increment(); }
```

**【推荐】流式响应优先小批次、逐元素写出，不要在网关/负载均衡层开启整响应缓冲；需要压缩时对 SSE/NDJSON 用流式压缩，避免把流式收益换成对端延迟。**

## 五、组合算子与信号纪律

**【强制】`flatMap` 与 `concatMap` 必须按"并发 vs 保序"显式选择：需要顺序或串行限流用 `concatMap`；纯并发加速用 `flatMap(fn, concurrency)` 且并发度显式写值（对外调用建议 16~64，默认 256 视为过大）。**

**反例：**
```java
// ❌ 错误：默认并发 256，瞬时打爆下游
ids.flatMap(id -> webClient.get().uri(URI.create("/u/" + id)).retrieve().bodyToMono(User.class));
```

**正例：**
```java
ids.flatMap(id -> gateway.userOf(id), 32);              // ✅ 正确：并发度显式
events.concatMap(e -> repository.save(e));              // ✅ 正确：写库顺序必须保持
```

**【强制】生产代码禁止裸 `subscribe()`（含 "fire-and-forget"），禁止在 `map` / `flatMap` 的 lambda 内再次 `subscribe`；订阅只发生在框架层或入口层。串行副作用用 `then()` / `thenMany()`，并行等待用 `Mono.when()`；该约束应可用 ArchUnit / checkstyle 自动检查。**

原因：Reactor 是拉模型——订阅前什么都不发生；中间层 `subscribe()` 会吞掉错误（无 `onError` consumer 时抛 `UnsupportedOperationException`）、断开背压（无参订阅等于 `request(Long.MAX_VALUE)`）并丢失上下文。

**反例：**
```java
public Mono<Result> process(Request r) {
    auditRepository.save(r).subscribe();   // ❌ 错误：审计错误被吞，请求可能已开始执行却未落审计
    return coreFlow(r);
}
```

**正例：**
```java
public Mono<Result> process(Request r) {
    return auditRepository.save(r).then(coreFlow(r));    // ✅ 正确：留在同一条链
}
public Mono<Result> processParallel(Request r) {
    return Mono.when(auditRepository.save(r), coreFlow(r)).then(coreResult(r));  // ✅ 正确
}
```

**【强制】空完成语义必须显式处理：`Mono<Void>` 只能用 `then` / `when` 参与组合（它事实上是空序列，放进 `zipWith` / `zipWhen` 会让组合永不触发）；`Mono.zip` 要求每个源各产出至少一个元素，可能空完成的源必须先 `singleOptional()` / `defaultIfEmpty()`，否则不保证 zip 订阅了全部上游。**

**反例：**
```java
return stepA().zipWith(stepB(), (a, b) -> combine(a, b));              // ❌ stepA 是 Mono<Void>
return Mono.zip(auditRepo.save(a), coreRepo.save(c), Summary::of);     // ❌ audit 可能为空流
```

**正例：**
```java
return stepA().then(stepB()).map(this::combine);                        // ✅ Void 只做顺序控制
return Mono.when(auditRepo.save(a), coreRepo.save(c)).then(summaryOf(a, c));  // ✅ "都完成"语义
return Mono.zip(audit.save(a).singleOptional(), core.save(c).singleOptional(), Summary::of);  // ✅ 配对
```

**【推荐】异步副作用不在装配期发生：字段或方法内提前持有的 `Mono`/`Flux` 若包含真实调用或请求级状态，应用 `Mono.defer` / `Flux.defer` 延迟到订阅期创建，否则 `retry` 与多订阅语义被破坏。**

```java
// ❌ 错误：装配即发起调用，retry 复用的是同一个已完成的结果
private final Mono<Resp> result = webClient.get().retrieve().bodyToMono(Resp.class);

// ✅ 正确：延迟到每次订阅
private Mono<Resp> result() { return Mono.defer(() -> webClient.get().retrieve().bodyToMono(Resp.class)); }
```

**【推荐】数据变换用 `map` / `flatMap`，副作用观测用 `doOn*`，聚合用 `collect` / `scan` / `reduce`：禁止在 `doOnNext` 里往共享集合塞状态、禁止跨订阅共享可变对象；操作符是装饰器，返回值必须接回链或变量；`zip` / `merge` / `concat` 的选择写成注释（配对 / 时间序交错 / 顺序订阅拼接）。**

```java
// ❌ 错误：返回值被丢弃（过滤未生效）；用 doOnNext 聚合状态
flux.filter(x -> x.getId() != null);
flux.doOnNext(sharedList::add);

// ✅ 正确：链式接回，聚合交给算子
return flux.filter(x -> x.getId() != null).collectList().map(this::toSummary);
```

**【推荐】`share()` / `replay()` / ConnectableFlux 属多订阅语义变更，必须写明理由：`share()` = `publish().refCount()`，全部取消即断链、后续订阅重新订阅源且只能看到新元素；`replay` 必须带有界参数并文档化内存代价。**

**【推荐】并行 fan-out 只用 `flatMap(fn, concurrency)` / `Flux.merge(...)` / `Mono.when(...)` 表达，禁止在 `flatMap` 内部对结果再订阅；需要"只要终态"用 `ignoreElements()`，串行推进用 `then` / `thenMany`。**

```java
// ❌ 错误：lambda 内订阅，错误与背压都脱离主链
Flux.range(0, n).flatMap(i -> call(i).subscribe());

// ✅ 正确
Flux.range(0, n).flatMap(this::call, 16);
```

## 六、错误处理：错误即终止

**【强制】错误是终结信号：恢复必须放在错误发生处最近的合适边界，不得假设原序列在恢复后继续产出。需要"单条目失败不拖垮整条流"时，兜底必须写进 `flatMap` 内层的每条目子序列。**

**反例：**
```java
// ❌ 错误：序列已终止，onErrorReturn 之后不会有下一个 tick
Flux.interval(Duration.ofSeconds(1)).map(this::encode).onErrorReturn(FALLBACK);
```

**正例：**
```java
// ✅ 正确：兜底在条目级，单条外部调用失败不影响整条流
keys.flatMap(key -> callExternal(key).onErrorResume(e -> fallbackFor(key, e)), 8);
```

**【强制】兜底必须按异常类型精确匹配，`onErrorResume` 的未匹配分支必须 `return Mono.error(error)` 重新抛出；禁止无类型的 `onErrorResume(e -> Mono.empty())` / `onErrorReturn(默认值)` / `onErrorComplete()` 掩盖未知错误。日志与打点用 `doOnError` / `doOnEach`（只旁观、不改流程），转译用 `onErrorMap`；`onErrorReturn` / `onErrorComplete` 只允许用于业务定义为"可降级"的错误且必须带异常类型谓词并记录打点；三者的先后顺序即语义。**

**反例：**
```java
.onErrorResume(e -> Mono.empty())   // ❌ 错误：吞掉一切错误，调用方拿到语义不明的假空结果
pipeline.onErrorComplete();         // ❌ 错误：把所有错误变成正常完成，故障静默
```

**正例：**
```java
return flow()
        .doOnError(e -> log.error("下单流程失败 orderId={}", orderId, e))   // ✅ 旁观，不改流程
        .onErrorMap(NoSuchRecordException.class, e -> new NotFoundException(id, e))
        .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty())
        .onErrorResume(e -> Mono.error(e));                                 // ✅ 未匹配分支重新抛出

// ✅ 正确：只降级明确可容忍的超时，并留下痕迹
pipeline.doOnError(TimeoutException.class, e -> metrics.counter("digest.timeout").increment())
        .onErrorComplete(TimeoutException.class);
```

**【强制】REST 错误处理统一在框架层：`@RestControllerAdvice` 继承 `ResponseEntityExceptionHandler`，`@ExceptionHandler` 返回 `Mono<ResponseEntity<ProblemDetail>>`（错误体遵循 RFC 9457）；快速状态码错误用 `ResponseStatusException`；更底层（鉴权之前等）用实现 `Ordered` 的 `WebExceptionHandler` bean。禁止在每个 controller 尾部 `onErrorReturn(500)` 吞掉根因。**

**正例：**
```java
@RestControllerAdvice
class ApiAdvice extends ResponseEntityExceptionHandler {
    @ExceptionHandler(InsufficientFundsException.class)
    Mono<ResponseEntity<ProblemDetail>> onInsufficientFunds(InsufficientFundsException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, e.getMessage());
        pd.setProperty("orderId", e.getOrderId());
        return Mono.just(ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(pd));
    }
}
```

**【强制】信号完整性：`Mono.create` / `Flux.create` / `Sinks` 每次订阅必须保证恰好一个终态（`onComplete` 或 `onError`）并挂上取消/释放路径；缺终态让请求悬挂到超时，多终态违反 Reactive Streams 生命周期规则。**

**反例：**
```java
// ❌ 错误：只挂成功分支，失败时请求永久悬挂
return Mono.create(sink -> callback.onSuccess(sink::success));
```

**正例：**
```java
// ✅ 正确：成功 / 失败 / 取消三条路径全覆盖
return Mono.create(sink -> {
    callback.onSuccess(sink::success);
    callback.onError(sink::error);
    sink.onCancel(callback::abort);
});
```

**【推荐】算子内抛出 checked exception 用 `Exceptions.propagate` 包装（消费侧 `Exceptions.unwrap` 判断），或返回 `Flux.error(e)`；禁止 catch 后打印再返回假数据。**

```java
try { return converter.convert(item); }
catch (IOException e) { throw Exceptions.propagate(e); }   // ✅ 正确：无阻塞地带入错误信号
```

**【推荐】`retry` 的实现是重新订阅上游，因此只允许用于幂等操作；退避统一用 `Retry.backoff(...).maxBackoff(...).filter(可重试异常).onRetryExhaustedThrow(...)`；禁止对非幂等 POST 用 `retry(n)`，禁止 `retryWhen(Retry.from(c -> c.take(n)))`（最终 complete 而非 error，等于吞错）；熔断、并发限制与超时编排统一交给 Resilience4j Reactor 模块集中管理。**

```java
gateway.quote(symbol)
        .retryWhen(Retry.backoff(3, Duration.ofMillis(100)).maxBackoff(Duration.ofSeconds(2))
                .filter(IsTransientError::test)
                .onRetryExhaustedThrow((spec, rs) -> rs.failure()));   // ✅ 耗尽后抛原始错误
```

## 七、Scheduler、线程身份与上下文传播

**【强制】禁止依赖线程身份与 `ThreadLocal` / MDC 传递请求上下文：一个请求可跨越多个线程，线程号与 ThreadLocal 在声明式管线中不可靠。上下文一律走 Reactor `Context`——写入用 `contextWrite`，读取用 `Mono.deferContextual` / `Flux.deferContextual`，且 `contextWrite` 必须放在链的最外层（下游），因为操作符只能看到其下方定义的 Context。**

**反例：**
```java
MDC.put("traceId", traceId);      // ❌ 错误：跨线程的下游 operator 看不到
return flow();
```

**正例：**
```java
// ✅ 正确：读取处按需 deferContextual 取值，写入位于链最外层
return flow().contextWrite(Context.of("traceId", traceId));
```

**【强制】日志与链路关联字段必须通过 Micrometer context-propagation 自动桥接：Spring Boot 配置 `spring.reactor.context-propagation=auto`（等价于 `Hooks.enableAutomaticContextPropagation()`），自建 `ThreadLocal` 需注册 `ThreadLocalAccessor` 到 `ContextRegistry`；禁止在 `.map()` 里手写 `MDC.put/clear` 成对补丁。日志格式与字段约定参见 `java_logging_specification.md`。**

**反例：**
```java
// ❌ 错误：多处手写 MDC 成对读写，跨线程与异常路径必然漏
.map(item -> { MDC.put("itemId", item.getId()); return handle(item); })
```

**正例：**
```yaml
# ✅ 正确：自动传播，配合 tracing 把 traceId/spanId 写入日志关联字段
spring:
  reactor:
    context-propagation: auto
```

**【强制】Scheduler 选型必须按下表：`parallel`＝纯 CPU 计算（线程数＝核数），`boundedElastic`＝阻塞 I/O 逃逸，`single`＝需串行顺序的单个任务；禁止 CPU 重活占用 `boundedElastic`，禁止阻塞 I/O 落在 `parallel`。`publishOn` 用于其后的下游段、`subscribeOn` 用于源的订阅段，一条链各至多一次。**

| 场景 | 选择 |
|---|---|
| JSON / 加解密 / 图像等 CPU 密集变换段 | `publishOn(Schedulers.parallel())` |
| 阻塞 SDK、文件流、非响应式驱动 | `subscribeOn(Schedulers.boundedElastic())` |
| 单实例串行任务（顺序写入协调） | `Schedulers.single()` |

**反例：**
```java
flux.publishOn(Schedulers.boundedElastic()).map(this::heavyCrypto);   // ❌ 错误：CPU 密集占阻塞池
```

**正例：**
```java
// ✅ 正确：阻塞读取在 boundedElastic，昂贵变换段切到 parallel，各只切一次
return Flux.fromIterable(rows)
        .subscribeOn(Schedulers.boundedElastic())
        .publishOn(Schedulers.parallel())
        .map(this::heavyTransform);
```

**【推荐】不跨订阅共享可变状态：同一订阅内操作符顺序执行，`publishOn` 边界提供 happens-before，但这不能替代不可变设计；需要并发汇聚时用 `collect` / `scan` / `reduce` 算子而非外部集合。**

**【参考】自动上下文传播有性能开销，热点路径可退回手动 `contextCapture` / `doOnEach(logOnNext(...))`；线程池参数、队列策略与 `CompletableFuture` 参见 `java_concurrency_specification.md`，本规范只覆盖响应式侧的 Scheduler 对应问题。**

## 八、数据访问与消息集成

**【强制】WebFlux 的 SQL 层只能使用 R2DBC（或 Hibernate Reactive）：`spring-boot-starter-data-jpa`、`JdbcTemplate` 与 JPA 懒加载不得出现在 WebFlux 的 classpath 与请求路径上；并按 Spring Data R2DBC 的真实能力建模——它不是 ORM，没有懒加载、关系映射与一级缓存，关联数据必须显式 join 或二次查询，聚合保持小。**

**反例：**
```java
// ❌ 错误：响应式服务混入 JPA，懒加载在事件循环线程上触发同步 SQL
return Mono.fromCallable(() -> new OrderView(entityManager.find(Order.class, id).getItems()));
```

**正例：**
```java
// ✅ 正确：R2DBC + 显式二次查询，聚合有界
return orderRepository.findById(id)
        .flatMap(o -> itemRepository.findByOrderId(id).collectList().map(items -> new OrderView(o, items)));
```

**【强制】R2DBC 连接池必须显式选型并给出容量依据：使用 `r2dbc-pool`（`spring.r2dbc.pool.*`），默认 `max-size=10` 必须依据数据库 `max_connections`、实例数与并发规模重新设定并在配置旁注释依据；禁止在生产使用 Spring 自带的非池化 `ConnectionFactory`（官方定位仅供测试）。**

**反例：**
```yaml
# ❌ 错误：生产不配池参数，沿用默认 10 却在 200 并发下出现 pendingAcquire 超时
spring:
  r2dbc:
    url: r2dbc:mysql://db:3306/app
```

**正例：**
```yaml
# ✅ 正确：容量依据 = DB max_connections(200) / 实例数(6)，留余量
spring:
  r2dbc:
    pool: { enabled: true, initial-size: 8, max-size: 32, max-idle-time: 15m, max-acquire-time: 3s }
```

**【强制】响应式事务必须在注册 `R2dbcTransactionManager` 后使用 `@Transactional`，且事务链内禁止 `subscribeOn` / `publishOn` 换线程、禁止用 `flatMap` / `zip` 发起并行写库分支（并行支路不共享事务连接）；多个写操作用 `then` 串行。**

**反例：**
```java
@Transactional
public Mono<Void> transfer(String from, String to, long amount) {
    return Mono.when(ledger.debit(from, amount).subscribeOn(Schedulers.parallel()),
                     ledger.credit(to, amount));   // ❌ 错误：回滚边界与连接共享失效
}
```

**正例：**
```java
@Transactional
public Mono<Void> transfer(String from, String to, long amount) {
    return ledger.debit(from, amount).then(ledger.credit(to, amount)).then();   // ✅ 正确
}
```

**【强制】禁止引入 `reactor-kafka`：该库已于 2025-05 停止维护（最后 minor 版本 1.3，并将从 Reactor BOM 移除），配套旧响应式模板同样弃用；Kafka 响应式消费应走 Spring for Apache Kafka（受管容器 + `@KafkaListener`，必要时向响应式流适配），或显式把原生 client 包装为 `Flux`。Topic 命名、幂等消费与生产者失败处理沿用 `java_middleware_specification.md`。**

**反例：**
```java
// ❌ 错误：新依赖已停止维护的 Reactor Kafka
// build.gradle: implementation 'io.projectreactor.kafka:reactor-kafka'
```

**正例：**
```java
// ✅ 正确：Spring for Apache Kafka 受管容器投递，业务侧只暴露响应式接口
@KafkaListener(topics = "app.order.created", groupId = "order-service")
public void onOrder(ConsumerRecord<String, String> record) { sink.tryEmitNext(OrderEvent.decode(record)); }
```

**【推荐】Kafka 发送侧把回调桥接为 `Mono`（`SendCallback` → `Mono.create`）并显式处理失败；若确需原生 consumer 轮询，必须在 `Schedulers.boundedElastic()` 上执行并由实现自管 `commit` 与背压，禁止在事件循环线程轮询。**

```java
// ✅ 正确：错误进入响应式错误通道，而不是被忽略
public Mono<SendResult<String, String>> send(String topic, String key, String payload) {
    return Mono.create(sink -> template.send(topic, key, payload).addCallback(sink::success, sink::error));
}
```

## 九、外部调用、韧性与资源安全

**【强制】每一个外部调用与"等待回调"的响应式链必须显式 `.timeout(Duration)`，并把超时映射为可读异常：默认无超时等于订阅永久悬挂，持续占用连接与缓冲。**

**反例：**
```java
webClient.get().uri(url).retrieve().bodyToMono(Quote.class);   // ❌ 错误：无任何超时
```

**正例：**
```java
webClient.get().uri(url).retrieve().bodyToMono(Quote.class)
        .timeout(Duration.ofSeconds(2))                          // ✅ 正确
        .onErrorMap(TimeoutException.class, e -> new UpstreamTimeoutException("quote", e));
```

**【强制】出站 HTTP 统一使用单例 `WebClient` bean（注入 `WebClient.Builder` 派生），并让所有客户端共享 Reactor Netty 全局资源（`ReactorResourceFactory(globalResources = true)` / `HttpResources`）；禁止在方法内 `WebClient.create()` 各自新建 `HttpClient`，导致连接池与事件循环资源失控。连接池参数、凭据外部化与通用超时配置参见 `java_springboot_specification.md`。**

**反例：**
```java
// ❌ 错误：每次调用新建客户端（新连接池 + 新事件循环资源）
return WebClient.create().get().uri(path).retrieve().bodyToMono(Resp.class);
```

**正例：**
```java
// ✅ 正确：builder 派生单例、共享全局资源、codec 上限显式
public QuoteClient(WebClient.Builder builder) {
    this.client = builder.baseUrl(endpoint)
            .codecs(c -> c.defaultCodecs().maxInMemorySize(512 * 1024)).build();
}
```

**【强制】由 I/O 资源生成流必须用 `Flux.using` / `Mono.usingWhen` 保证关闭（覆盖完成、错误与取消三条路径），禁止 `Flux.fromInputStream` + 手工 `subscribe`。**

**反例：**
```java
// ❌ 错误：异常/取消路径不关闭，文件描述符泄漏
DataBufferUtils.read(new FileInputStream(path).getChannel(), factory, 4096);
```

**正例：**
```java
// ✅ 正确：同步资源用 using；异步资源用 usingWhen
return Flux.using(() -> Files.newInputStream(path), in -> DataBufferUtils.read(in, factory, 4096), IoUtils::closeQuietly);
return Mono.usingWhen(connFactory.create(), conn -> conn.commands().get(key), Connection::close);
```

**【强制】直接操作 `DataBuffer` / ByteBuf 的代码必须显式 `DataBufferUtils.release(buffer)`：Netty 缓冲区是引用计数的池化对象，漏释放即内存泄漏；默认交给 codec 管理不自已触碰；staging 与 CI 开启 `-Dio.netty.leakDetection.level=PARANOID`；丢弃对象需要清理时用幂等的 `doOnDiscard(DataBuffer.class, DataBufferUtils::release)`。**

**反例：**
```java
// ❌ 错误：手工聚合 DataBuffer 却不释放
return body.buffer().map(buffers -> buffers.get(0).toString(StandardCharsets.UTF_8));
```

**正例：**
```java
// ✅ 正确：join 之后立即释放，取消路径由 doOnDiscard 兜住
return DataBufferUtils.join(body).map(b -> {
    String text = b.toString(StandardCharsets.UTF_8);
    DataBufferUtils.release(b);
    return text;
});
```

**【推荐】出站调用的三层超时要同时具备，业务 `.timeout()` 只是最后一道：connector 层 `responseTimeout` / `ChannelOption.CONNECT_TIMEOUT_MILLIS`、通道层 `ReadTimeoutHandler` / `WriteTimeoutHandler`、链上 `.timeout()`。**

```java
// ✅ 正确：连接与读写在 Netty 层兜住，业务超时负责语义
HttpClient http = HttpClient.create()
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2_000)
        .responseTimeout(Duration.ofSeconds(3))
        .doOnConnection(c -> c.addHandlerLast(new ReadTimeoutHandler(3, TimeUnit.SECONDS)));
```

**【推荐】优雅停机除通用配置外，响应式侧的额外约束是：共享的 Reactor Netty 资源必须随容器关闭释放（由 `ReactorResourceFactory` 管理全局资源），且 `spring.lifecycle.timeout-per-shutdown-phase` 不得小于最长在途流（SSE / 大文件）的执行时间，否则在途 Mono 被强杀导致客户端 502。通用 graceful shutdown 配置参见 `java_springboot_specification.md`。**

**【推荐】指标与链路追踪使用 `tap(Micrometer.observation(...))` 或 `Flux#metrics(...)`，依赖第七节的 context-propagation 跨线程传递上下文；禁止用 `ThreadLocal` 手写计时器统计反应式链。指标命名与告警参见 `java_logging_specification.md`。**

```java
return repository.findByStatus(status)
        .tap(Micrometer.observation(registry, Observation.createNotStarted("order.read", registry)));
```

## 十、响应式测试

**【强制】单元测试用 `StepVerifier` 断言完整信号序列（元素序列 + 终态 `verifyComplete()` / `verifyError()` / `verify()`），错误路径必须单独断言；禁止用 `.block()` 的返回值做 `equals` 断言——后者丢失错误与完成语义，并掩盖"缺终态"缺陷。**

**反例：**
```java
// ❌ 错误：只断值，错误信号与"未完成"都测不出来
assertEquals(expected, service.call("A").block(Duration.ofSeconds(5)));
```

**正例：**
```java
StepVerifier.create(service.call("A")).expectNext(expected).verifyComplete();     // ✅ 元素 + 终态
StepVerifier.create(service.load("-1")).expectError(IllegalArgumentException.class).verify();  // ✅ 错误路径
```

**【强制】时间相关流必须用 `StepVerifier.withVirtualTime(() -> ...)`，且被验证的流只能在 Supplier 内部创建（惰性）：虚拟时间调度器必须先于算子实例化；推进时钟用 `thenAwait` / `expectNoEvent`，禁止用真实 `Thread.sleep` 等待 `interval`。**

**反例：**
```java
Flux<Digest> daily = service.dailyDigest();          // ❌ 错误：实例在 Supplier 之外创建
StepVerifier.create(daily).expectNext(digest).verifyComplete();
```

**正例：**
```java
StepVerifier.withVirtualTime(() -> service.dailyDigest())     // ✅ 正确：源在 Supplier 内
        .thenAwait(Duration.ofDays(1)).expectNext(digestOf("2026-10-01")).verifyComplete();
```

**【推荐】控制层切片用 `@WebFluxTest(XxxController.class)` + 自动装配的 `WebTestClient`（不启真实端口；纯单元可用 `WebTestClient.bindToController(...)`）；全链路验证用 `SpringBootTest(webEnvironment = RANDOM_PORT)` + 真实 HTTP 客户端。测试分层与 Testcontainers 策略参见 `java_springboot_specification.md`。**

```java
@WebFluxTest(ItemController.class)
class ItemControllerTest {
    @Autowired WebTestClient client;
    @MockitoBean ItemService service;

    @Test void returns200() {
        when(service.get("1")).thenReturn(Mono.just(new Item("1", "name")));
        client.get().uri("/items/1").exchange().expectStatus().isOk();
    }
}
```

**【推荐】兜底分支必须单独断言产出值，确保"未匹配必须重新抛出"没有被实现成吞错；`StepVerifier` 可与 BlockHound 组合在同一条链上跑门禁测试。**

```java
StepVerifier.create(service.loadWithFallback("A")).expectNext(FallbackValue.NONE).verifyComplete();
```

**【参考】依赖 `Context` 的行为可用 `expectAccessibleContext().contains("traceId")` 断言上下文可访问，复杂断言用 `consumeWith`；测试内 `.block()` 只允许出现在这类非生产路径。**

## 禁止事项

**【强制】禁止以下做法：**

1. ❌ 链路存在阻塞依赖（JPA / JDBC / 同步 SDK）时仍选择 WebFlux
2. ❌ 请求处理链上调用 `.block()` / `.blockFirst()` / `.blockLast()` / `.toStream()`
3. ❌ `Thread.sleep`、`CountDownLatch.await`、`CompletableFuture.join`、`InputStream.read`、`synchronized` 内 I/O
4. ❌ 用 `Schedulers.elastic()` 承载阻塞，或把阻塞放在 `Schedulers.parallel()` / `single()` 上
5. ❌ 一条链叠加多个 `subscribeOn`
6. ❌ 用 `-Dreactor.netty.ioWorkerCount=大值` 掩盖阻塞
7. ❌ WebFlux 应用注册 `OncePerRequestFilter` 等 Servlet Filter 或直接操作 Servlet API
8. ❌ Controller 返回 `List<T>` / 裸实体并配合阻塞取值；向 `Mono`/`Flux` 传递 `null` 元素
9. ❌ 无理由的 `collectList()`、无界 `buffer()`、无参 `onBackpressureBuffer()`
10. ❌ 为"跑通业务"把 `maxInMemorySize` 调到数十 MB 以上
11. ❌ SSE / NDJSON 长流不配心跳，断线无法感知
12. ❌ 生产代码裸 `subscribe()`、fire-and-forget、lambda 内嵌套订阅
13. ❌ 把 `Mono<Void>` 或可能空完成的源放进 `zipWith` / `Mono.zip` 并期待产出
14. ❌ 装配期触发副作用（字段持有已装配的调用结果），破坏 retry 与多订阅语义
15. ❌ `onErrorResume(e -> Mono.empty())`、无类型 `onErrorComplete()` / `onErrorReturn()` 吞掉未知错误
16. ❌ 在业务链尾部手写订阅错误处理，替代全局 `@RestControllerAdvice` / `WebExceptionHandler`
17. ❌ `Mono.create` / `Flux.create` / `Sinks` 缺少错误终态或取消路径
18. ❌ 依赖 `ThreadLocal` / MDC 在操作符之间传值；不启用 context-propagation 就打 MDC
19. ❌ WebFlux 引入 JPA / 懒加载；生产使用非池化 R2DBC `ConnectionFactory`
20. ❌ 响应式 `@Transactional` 方法内换线程或并行写库
21. ❌ 引入已停止维护的 `reactor-kafka` 依赖
22. ❌ 外部调用不加 `.timeout()`；方法内反复 `WebClient.create()`
23. ❌ `Flux.using` / `usingWhen` 缺失资源关闭；`DataBuffer` 不 `release`
24. ❌ 测试只用 `.block()` 比对返回值；时间流不用 `withVirtualTime`

## 快速检查清单

- [ ] 全链路依赖是否均为非阻塞（R2DBC / WebClient / 响应式 MQ），并评估过 MVC + 虚拟线程替代方案？
- [ ] 是否已排除 Servlet Filter / Servlet API，跨切面逻辑改用 `WebFilter`？
- [ ] CI 是否接入 BlockHound 且能 fail build？`reactor.netty.ioWorkerCount` 是否保持默认（或附压测数据）？
- [ ] 链上是否没有任何 `.block()` / `sleep` / `await` / `join` / 同步 I/O？
- [ ] 阻塞调用是否统一 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`，且 `subscribeOn` 只出现一次？
- [ ] Scheduler 选型是否符合 parallel＝CPU、boundedElastic＝阻塞 I/O、single＝串行单任务的矩阵？
- [ ] Controller 是否只返回 `Mono`/`Flux`（或 `ResponseEntity` 包裹），且无 `null` 元素？
- [ ] 是否避免了无理由的 `collectList()`，计数用 `count()`？
- [ ] 缓冲/窗口算子是否都带上界参数（`buffer(maxSize)`、`take(limit)`）？
- [ ] `onBackpressureBuffer` 是否有 `maxSize` + 溢出策略 + 丢弃打点？
- [ ] SSE / NDJSON 是否配心跳并在数据流结束后停止，是否文档化取消语义？
- [ ] `maxInMemorySize` 服务端与出站是否都显式设置？
- [ ] `flatMap` 是否显式并发度；保序场景是否用 `concatMap`？
- [ ] 生产代码是否无裸 `subscribe()` 与嵌套订阅（ArchUnit 规则是否生效）？
- [ ] `Mono<Void>` 是否只用 `then`/`when`；空完成源是否 `singleOptional()`？
- [ ] 异步副作用是否用 `defer` 延迟到订阅期？`share()`/`replay()` 是否写明多订阅语义与有界缓冲？
- [ ] 兜底是否放在错误发生处最近的边界（条目级恢复进 `flatMap` 内层）？
- [ ] `onErrorResume` 未匹配分支是否 `Mono.error(e)` 重新抛出？
- [ ] `onErrorReturn` / `onErrorComplete` 是否限定异常类型并记录打点？
- [ ] 是否用 `@RestControllerAdvice` + `ProblemDetail` 做全局错误体？
- [ ] `create` / `Sinks` 是否保证"恰好一个终态 + 取消路径"？
- [ ] `retry` 是否只用于幂等操作并带退避、异常过滤与耗尽抛出？
- [ ] 是否用 `contextWrite`（链最外层）/ `deferContextual` 替代 ThreadLocal/MDC？
- [ ] `spring.reactor.context-propagation=auto` 是否开启，自建 ThreadLocal 是否注册 `ThreadLocalAccessor`？
- [ ] SQL 层是否只有 R2DBC 且无懒加载建模？`spring.r2dbc.pool.*` 是否显式配置并写明容量依据？
- [ ] 响应式事务是否避免换线程与并行写库？是否排除了 `reactor-kafka`？
- [ ] 每个外部调用是否显式 `.timeout()`，并具备 connector / handler / 业务三层超时？
- [ ] `WebClient` 是否为单例 bean 且共享 Netty 全局资源？
- [ ] I/O 资源是否用 `using` / `usingWhen`（覆盖取消路径）？`DataBuffer` 是否释放？
- [ ] 停机相位超时是否不小于最长在途流？
- [ ] 是否用 `StepVerifier` 断言元素 + 终态，时间流用 `withVirtualTime` 且源在 Supplier 内创建？
- [ ] 控制层是否用 `@WebFluxTest` + `WebTestClient`，错误与兜底路径是否被测？
