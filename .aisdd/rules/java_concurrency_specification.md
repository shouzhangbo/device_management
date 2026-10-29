---
name: Java 并发与线程池开发规范
version: 1.0.0
description: 面向 AI Agent 的 Java 并发与线程池/队列开发规范（J.U.C 以 Java 8+ 为基线；虚拟线程单列一章，要求 JDK 21+ 并标注 JDK 21–23 与 JDK 24+ 差异）。覆盖 ThreadPoolExecutor 七参数语义与 core→queue→max→reject 真实顺序、工作队列必须有界与容量推理、CPU/IO 密集线程数启发式与压测校准、ScheduledThreadPoolExecutor 替代 Timer、四种拒绝策略取舍与禁用项、线程命名与自定义 ThreadFactory、一业务语义一池与父子任务同池饥饿死锁、parallelStream 与 commonPool 禁承载阻塞 IO、任务提交与异常吞噬、Future.get 超时与 cancel(true) 与中断响应、优雅关闭序列 shutdown→awaitTermination→shutdownNow 与 SmartLifecycle phase 及 Kubernetes terminationGracePeriodSeconds 预算、进程内阻塞队列选型表（ArrayBlockingQueue / LinkedBlockingQueue / SynchronousQueue / LinkedTransferQueue / DelayQueue / Disruptor）与跨进程 MQ 边界、CompletableFuture 编排（显式 Executor、orTimeout/completeOnTimeout、thenCompose、exceptionally 不吞错、cancel 不中断线程）、ReentrantLock 与 tryLock 模板、多锁全局顺序、锁粒度与锁内禁 IO、volatile/Atomic/LongAdder/Lock 决策表、ConcurrentHashMap 复合方法与弱一致聚合禁令、CountDownLatch 超时兜底、ThreadLocal 与 MDC/TTL 上下文传播、虚拟线程（禁止池化、Semaphore 限流、不适用场景、pinning 与 JFR 检测）、线程池指标暴露与 jstack/jcmd 排障。本文件只讲 JVM 进程内并发与内存队列，跨进程消息中间件（Kafka/RocketMQ/RabbitMQ）见 `mq_specification.md`；命名/集合/异常等基础条目见 `java_basic_specification.md`；Spring DI/事务/MVC 见 `java_spring_specification.md`；Boot 配置与通用优雅停机见 `java_springboot_specification.md`；Reactor/Scheduler 见 `java_webflux_specification.md`；日志见 `java_logging_specification.md`。约束等级分为三级：**【强制】**（必须遵守，AI 可在 diff 中客观判定的硬规则）、**【推荐】**（尽量遵守，偏离需注释说明）、**【参考】**（可选参考）。
---

# Java 并发与线程池开发规范

## 前言

本规范面向 AI Agent 生成 Java 并发代码的场景，聚焦 **JVM 进程内的并发与内存队列**：线程池创建与参数语义、命名与池隔离、拒绝策略、任务提交与结果获取、优雅关闭、进程内队列选型、异步编排、锁与原子类与并发容器、ThreadLocal 与上下文传播、虚拟线程、Spring/Boot 接线与可观测性排障。每条规则给出失效模式与 ❌/✅ 正反例，便于在 diff 与评审中客观校验。

基线约定：**J.U.C 部分适用于 Java 8 及以上**；**第十一章（虚拟线程）要求 JDK 21 及以上**，禁止对 JDK 8/11 项目输出虚拟线程方案；凡版本影响结论处（尤其 `synchronized` pinning 在 JDK 21–23 与 JDK 24+（JEP 491）的差异），在规则文本内以 `JDK x+` 显式标注。

约束等级分为三级：**【强制】**（必须遵守，且是 AI 读取代码可客观判定的硬规则）、**【推荐】**（尽量遵守，偏离需在注释中说明理由）、**【参考】**（可选参考，知识性提示）。

与既有规范的边界：本文不重复 `java_basic_specification.md` 的基础并发条目（该章相关条目以本文为准），不展开 Spring DI/事务/MVC、Boot 通用配置与 Actuator、Reactor 调度器、外部 MQ 的 Topic/幂等/死信规范，只在必要处点名引用。

> 详细的调研依据与来源（JDK 21 Javadoc、JEP 444/491/453/505、Oracle 虚拟线程指南、阿里巴巴 Java 开发手册并发篇、Spring Boot/Framework 文档、Kubernetes Pod 生命周期、Micrometer、TransmittableThreadLocal、LMAX Disruptor）见 `research/research_java_concurrency.md`。

## 一、线程池创建与参数语义

**【强制】禁止用 `Executors.newFixedThreadPool` / `newCachedThreadPool` / `newSingleThreadExecutor` / `newScheduledThreadPool` 创建线程池，必须显式 `new ThreadPoolExecutor(...)` 传齐七参数（核心数、最大数、存活时间、单位、有界队列、命名 ThreadFactory、显式拒绝策略）。**

原因：前两者使用无界 `LinkedBlockingQueue`（默认容量 `Integer.MAX_VALUE`）导致任务堆积 OOM，后两者允许 `Integer.MAX_VALUE` 个线程导致线程耗尽；风险本质是"无界队列/无界线数量"，唯一例外是虚拟线程执行器（第十一章）。

**反例：**
```java
// ❌ 错误：无界队列 → 堆积 OOM；线程数无上限
ExecutorService pool = Executors.newFixedThreadPool(10);
ExecutorService cached = Executors.newCachedThreadPool();
```

**正例：**
```java
// ✅ 正确：七参数显式声明，队列容量与拒绝策略都是有意决策
ThreadPoolExecutor pool = new ThreadPoolExecutor(
        8, 16, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(2000),               // 有界
        new NamedThreadFactory("order-notify"),       // 见第二章
        new ThreadPoolExecutor.AbortPolicy());        // 见第三章
```

**【强制】必须按 `core → queue → max → reject` 的真实顺序设计参数：线程数不足 core 时新建线程（即使有空闲线程）；否则先入队；队列满才扩到 max；仍无法容纳才拒绝。队列满之前 `maximumPoolSize` 永不生效，禁止 `core≠max` 搭配大队列却无任何注释。**

原因：`core=8, max=100, queue=10000` 实际只有 8 个线程在工作，"100" 是幻觉参数；扩容与削峰互斥——想扩容就用小队列（甚至 `SynchronousQueue`），想削峰就令 `max ≈ core`。

**反例：**
```java
// ❌ 错误：意图"最高 120 并发"，队列填满前实际只有 8 并发
new ThreadPoolExecutor(8, 120, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(50000), factory, abort);
```

**正例：**
```java
// ✅ 正确：削峰型 core≈max（注释：不依赖扩容，队列满即拒绝降级）
new ThreadPoolExecutor(8, 8, 0L, SECONDS, new ArrayBlockingQueue<>(2000), factory, abort);
// ✅ 正确：扩容型小队列让 max 真正生效（注释：队列 16 满即扩到 32）
new ThreadPoolExecutor(8, 32, 60L, SECONDS, new ArrayBlockingQueue<>(16), factory, abort);
```

**【强制】队列实参必须携带容量字面量或配置项（禁止无参构造），并在注释中写明容量依据：`queueSize ≈ 最大可容忍排队延迟 × 处理速率`，且 `queueSize × 单任务驻留内存` 远小于堆余量。**

原因：队列驻留的是任务对象及其参数引用；容量决策不写下来就无法与 SLO 对齐，也无法在容量事故中复盘。

**反例：**
```java
// ❌ 错误：无参构造即 MAX_VALUE 容量；new ArrayBlockingQueue<>(2333) 这类来源不明的魔数
BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
```

**正例：**
```java
// ✅ 正确：容量与延迟/堆预算挂钩，可追溯
// 队列 2000 = 峰值 300 TPS × 可容忍排队 6.7 s；单任务驻留 ≈ 2 KB，共 ≈ 4 MB ≪ 堆余量
private static final int QUEUE_CAPACITY = 2_000;
BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
```

**【推荐】初始线程数按负载类型取值后压测校准：CPU 密集 `N ≈ N_cpu × 目标利用率(0.8~1.0) + 1`；IO/混合型 `N = N_cpu × (1 + W/C)`（W/C = 等待/计算时间，取自埋点或压测）；容器内以 cgroup 限额（`Runtime.availableProcessors()`）为准而非宿主机核数；潮汐明显且可容忍冷启动抖动的池可开 `allowCoreThreadTimeOut(true)`；运行期动态调参入口必须校验不变量（`core ≤ max`、非法值拒绝、队列容量只能显式重建）并记录审计日志。**

原因：公式只是起点；JDK ≥ 21 的 IO 密集负载应优先虚拟线程（第十一章），"池大小"退化为下游资源容量问题。

**正例：**
```java
// ✅ 正确：容器可用核数 → 公式起点 → 压测校准 → 上限受下游连接池约束
int ncpu = Runtime.getRuntime().availableProcessors();
int size = Math.min((int) Math.ceil(ncpu * (1 + waitComputeRatio)), downstreamPoolSize);
pool.allowCoreThreadTimeOut(true);
```

**【强制】定时/周期任务必须用 `ScheduledThreadPoolExecutor`，禁止 `java.util.Timer`。**

原因：`Timer` 承载多个 `TimerTask` 时，任一任务抛出未捕获异常会导致其余任务全部自动终止且难以察觉。

**反例：**
```java
// ❌ 错误：task1 抛未捕获异常 → task2 一起停摆
Timer timer = new Timer("cron", true);
timer.schedule(task1, 0, 60_000L);
```

**正例：**
```java
// ✅ 正确：命名工厂 + 任务体自捕获异常（异常不得逃逸给调度器）
ScheduledExecutorService s = new ScheduledThreadPoolExecutor(2, new NamedThreadFactory("cron"));
s.scheduleWithFixedDelay(() -> {
    try { reload(); } catch (Exception e) { log.error("scheduled reload failed", e); }
}, 0, 60, TimeUnit.SECONDS);
```

**【参考】"线程资源必须通过线程池提供、禁止自行显式创建线程"在虚拟线程时代需拆分为两条：平台线程仍一律走池（本文一至三章）；IO 密集任务在 JDK ≥ 21 推荐 `Executors.newVirtualThreadPerTaskExecutor()` 的 thread-per-task 模型，但仍禁止裸 `new Thread(...)` 循环创建平台线程。**

## 二、命名与池隔离

**【强制】创建线程或线程池必须指定有意义的、体现业务语义的名称，禁止沿用默认 `pool-N-thread-M` 格式。**

可检查判据：线程名匹配 `^[a-z][a-z0-9-]*(-\d+)?$` 且含业务前缀；共享池构造中不得出现 `Executors.defaultThreadFactory()`。

**反例：**
```java
// ❌ 错误：jstack 与告警堆栈里只有 pool-3-thread-7，无法定位业务
new ThreadPoolExecutor(8, 8, 0L, SECONDS, queue, Executors.defaultThreadFactory(), handler);
```

**正例：**
```java
// ✅ 正确：自定义 ThreadFactory —— 业务命名 + 非守护 + 统一直属异常处理器
public class NamedThreadFactory implements ThreadFactory {
    private final AtomicInteger seq = new AtomicInteger(1);
    private final String prefix;                                              // 例：order-notify
    public NamedThreadFactory(String prefix) { this.prefix = prefix; }
    @Override public Thread newThread(Runnable r) {
        Thread t = new Thread(r, prefix + "-" + seq.getAndIncrement());
        t.setUncaughtExceptionHandler(ConcurrentErrorLogHandler.INSTANCE);    // 见第四章
        return t;
    }
}
```

**【强制】一业务语义一池：不同延迟特性/SLA 的任务不得共用同一个线程池，禁止把通知、报表导出、RPC 聚合全塞进同一个 `taskExecutor`。**

原因：共享池即共享故障域——A 业务下游超时打满线程会让 B 业务全部饿死，队列共享会破坏 B 的延迟 SLO，且无法按业务分别设拒绝策略与告警阈值。

**反例：**
```java
// ❌ 错误：全工程唯一池，报表导出打满线程后订单通知一起延迟
@Bean("taskExecutor")
public Executor global() { /* core=8 max=16 queue=10000 */ }
```

**正例：**
```java
// ✅ 正确：按业务语义分池，参数与监控各自独立
@Bean("orderNotifyExecutor")  public Executor orderNotify()  { return build("order-notify", 8, 8, 2000); }
@Bean("reportExportExecutor") public Executor reportExport() { return build("report-export", 2, 4, 50); }
@Bean("rpcAggExecutor")       public Executor rpcAgg()       { return build("rpc-agg", 16, 16, 500); }
```

**【强制】禁止在池内任务中向同一个池提交子任务并阻塞等待其结果（父子任务同池 + 自等待 = 线程饥饿性死锁）。**

原因：所有线程都在等待自己所在池队列中的子任务，子任务永远轮不到执行，吞吐归零且无死锁告警，是典型伪死锁。

**反例：**
```java
// ❌ 错误：父任务占着线程等排在同一队列的子任务
pool.submit(() -> {
    Future<Data> sub = pool.submit(() -> load(t));    // 等待自己所在池的排队任务
    return combine(sub.get(2, TimeUnit.SECONDS));
});
```

**正例：**
```java
// ✅ 正确：父子分池
List<Future<Data>> subs = tasks.stream().map(t -> childPool.submit(() -> load(t))).collect(toList());
// ✅ 正确：或改为不占线程的 CompletableFuture 编排（见第七章）
CompletableFuture<List<Data>> all = CompletableFuture.supplyAsync(() -> loadAll(tasks), ioExecutor);
```

**【强制】禁止用 `parallelStream()` 或不带 `Executor` 参数的异步 API 承载阻塞 IO（HTTP、JDBC、文件、`Thread.sleep`）。**

原因：两者共用 `ForkJoinPool.commonPool()`（并行度 = CPU-1，JVM 全局唯一、无法按业务隔离），阻塞 IO 会占死公共池线程，连带同 JVM 内所有依赖 commonPool 的并行流与异步链一起饥饿。

**反例：**
```java
// ❌ 错误：公共池被远程调用占满，其他模块的并行流全部排队
List<Item> items = ids.parallelStream().map(id -> httpClient.get("/item/" + id)).collect(toList());
```

**正例：**
```java
// ✅ 正确：IO 交给专用 IO 池（JDK 21+ 可用虚拟线程执行器）；纯 CPU 计算才用并行流
ids.stream().map(id -> CompletableFuture.supplyAsync(() -> httpClient.get("/item/" + id), ioExecutor)).collect(toList());
boolean ok = chunks.parallelStream().allMatch(this::validateCpuBound);
```

**【推荐】每个 `ExecutorService` 必须是可发现的命名 Bean 或集中注册（注册表/统一工厂），禁止散落在各类中的 `static` 池。**

原因：`static` 池随类加载器生命周期存活，热部署与重启时线程泄漏，且无法统一优雅关闭与指标采集。

## 三、拒绝策略

**【强制】构造线程池必须显式声明拒绝策略并按语义选择；面向在线请求的池只允许 `AbortPolicy`。**

| 策略 | JDK 语义 | 适用 | 丢失什么 |
|------|---------|------|---------|
| `AbortPolicy`（默认） | 拒绝时抛 `RejectedExecutionException` | 需感知失败、上层可补偿/重试的请求型业务 | 不丢任务，但调用方必须处理异常 |
| `CallerRunsPolicy` | 由调用 `execute` 的线程自己执行，形成限速回压 | 批处理生产者限速 | 不丢任务，但阻塞调用方；池已 shutdown 期间静默丢弃 |
| `DiscardPolicy` | 静默丢弃无法执行的任务 | 仅"从不依赖其完成"的可丢弃遥测/心跳 | 静默丢任务、不可审计，业务任务禁用 |
| `DiscardOldestPolicy` | 丢弃队列头部最老任务后重试提交（仍可能失败） | 只关心最新数据（行情快照、位置上报） | 丢最老任务 |

**反例：**
```java
// ❌ 错误：业务任务用静默丢弃策略，故障无痕
new ThreadPoolExecutor(8, 8, 0L, SECONDS, queue, factory, new ThreadPoolExecutor.DiscardPolicy());
```

**正例：**
```java
// ✅ 正确：请求型 —— Abort + 上层降级
new ThreadPoolExecutor(8, 8, 0L, SECONDS, queue, factory, new ThreadPoolExecutor.AbortPolicy());
// ✅ 正确：快照型 —— 只留最新，但必须配丢弃计数（见下一条）
new ThreadPoolExecutor(2, 2, 0L, SECONDS, queue, factory, new ThreadPoolExecutor.DiscardOldestPolicy());
```

**【强制】拒绝必须可观测、可传播：必须打点 `executor.rejected` 计数；被拒绝的任务不得静默消失；提交后返回 `Future`/`CompletableFuture` 的任务被拒绝时必须让关联 future 异常完成，否则调用方永远等待。**

原因：JDK 的 `ThreadPoolExecutor` 不暴露拒绝计数、Micrometer 默认也不产出 rejected 指标，不写 handler 就等于任务凭空消失。

**反例：**
```java
// ❌ 错误：无日志无指标地丢弃，调用方持有的 future 永不完成
pool.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
```

**正例：**
```java
// ✅ 正确：统一 handler —— 计数 + 让 future 异常完成 + 关键任务落重试表
pool.setRejectedExecutionHandler((r, ex) -> {
    Metrics.counter("executor.rejected", "pool", poolName).increment();
    if (r instanceof TaskWrapper tw) {
        tw.future().completeExceptionally(new RejectedExecutionException("pool saturated", r));
    } else {
        log.warn("task rejected, pool={}, task={}", poolName, r);
        retryTable.save(r);
    }
});
```

**【推荐】`CallerRunsPolicy` 在 Web 请求线程（Tomcat/Netty event 线程）派生任务的场景慎用或禁用，只有批处理生产者线程适合用它限速。**

原因：饱和时任务在请求线程上执行，下游劣化会被放大为接口 RT 飙升乃至整机不可用；且池已 shutdown 时回压语义会切换为静默丢弃。

## 四、任务提交与结果获取

**【强制】`Future.get()` 必须带超时（`get(timeout, unit)`），禁止无参 `get()` 与裸 `join()`；超时后必须 `cancel(true)` 释放池容量，且任务体必须真正响应中断（阻塞调用可中断，或循环检查中断位）。**

原因：无参 `get()` 无限阻塞，下游 hang 会把调用线程（可能是 Web 线程）整段扣住；`cancel(true)` 只发出 interrupt，吞中断的任务不会退出，超时保护会变成幻觉。

**反例：**
```java
// ❌ 错误：无限阻塞
Result r = pool.submit(task).get();
// ❌ 错误：吞掉中断 → 任务永不退出，cancel 无效
try { while (running) { doWork(); } } catch (InterruptedException e) { log.warn("ignored"); }
```

**正例：**
```java
// ✅ 正确：带超时 + 超时即 cancel + 恢复中断位
Future<Result> future = pool.submit(task);
try {
    return future.get(2, TimeUnit.SECONDS);
} catch (TimeoutException | InterruptedException e) {
    future.cancel(true);
    Thread.currentThread().interrupt();
    throw new DegradedException("item load timeout", e);
} catch (ExecutionException e) {
    throw TransferException.of(e.getCause());
}
// ✅ 正确：任务体退出条件含中断检查
while (running && !Thread.currentThread().isInterrupted()) { doWork(); }
```

**【强制】提交进池的任务体必须自行捕获异常：`execute()` 路径依赖 `UncaughtExceptionHandler`（工作线程死亡并被替换，故障无声），`submit()` 路径异常被包在 `ExecutionException` 里，不 `get()` 就永远无人看到。**

原因：异步边界会切断异常传播链，静默失败比崩溃更难发现，必须在任务边界统一收口。

**反例：**
```java
// ❌ 错误：从未 get，异常彻底静默；execute 路径只依赖默认 UEH
pool.submit(() -> riskyCall());
pool.execute(() -> riskyCall());
```

**正例：**
```java
// ✅ 正确：任务体自捕获 + 上报
pool.execute(() -> {
    try { riskyCall(); }
    catch (Exception e) {
        log.error("async task failed, pool=order-notify, bizId={}", bizId, e);
        Metrics.counter("async.task.error", "pool", "order-notify").increment();
    }
});
// ✅ 正确：兜底 —— 覆写 afterExecute 打捞被 submit 吞掉的异常
@Override
protected void afterExecute(Runnable r, Throwable t) {
    super.afterExecute(r, t);
    if (t == null && r instanceof Future<?> f && f.isDone()) {
        try { f.get(); } catch (ExecutionException e) { log.error("task failed silently", e.getCause()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

**【推荐】对外 API 返回 `CompletableFuture` 而非 `Future`；等待多个任务用 `allOf` 组合而非逐个 `get()`。**

原因：逐个 `get()` 把超时串行累加（N 个 2 s 超时最坏 2N 秒）并扣住调用线程（正确编排见第七章）。

## 五、优雅关闭

**【强制】关闭必须走标准序列：`shutdown()` → `awaitTermination(t)` → 超时则 `shutdownNow()` → 再等待 → 仍未终止记录告警；捕获 `InterruptedException` 时同样 `shutdownNow()` 并恢复中断位；`shutdownNow()` 返回的未执行任务必须处理（落盘/重投/告警）不得丢弃；等待时长必须小于外层部署超时。**

原因：`shutdown()` 只停接新任务并跑完已提交任务；`shutdownNow()` 依赖 interrupt、是尽力而为；跳过等待则进程退出时在途任务被腰斩。

**反例：**
```java
// ❌ 错误：只 shutdown 不等待；或第一步就 shutdownNow 并丢弃返回值
pool.shutdown();
List<Runnable> dropped = pool.shutdownNow();
```

**正例：**
```java
// ✅ 正确：JDK 官方惯用法
void shutdownAndAwaitTermination(ExecutorService pool, String name) {
    pool.shutdown();
    try {
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
            retryTable.saveAll(pool.shutdownNow());          // 残留任务必须重投
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) log.error("pool {} did not terminate", name);
        }
    } catch (InterruptedException ex) {
        pool.shutdownNow();
        Thread.currentThread().interrupt();
    }
}
```

**【强制】JVM 退出路径必须触发线程池关闭：由容器生命周期托管，或注册 `Runtime.getRuntime().addShutdownHook(...)`；禁止依赖 `System.exit` 或让非守护线程把进程挂住。**

原因：静态池 + 非守护线程会让 JVM 无法退出；守护线程则让在途任务被腰斩。

**反例：**
```java
// ❌ 错误：static 池无人关闭，进程被非守护线程吊住或任务被腰斩
private static final ExecutorService POOL = Executors.newFixedThreadPool(8);
```

**正例：**
```java
// ✅ 正确（裸 JDK）：ShutdownHook 中执行上面的关闭序列
Runtime.getRuntime().addShutdownHook(new Thread(
        () -> shutdownAndAwaitTermination(pool, "order-notify"), "shutdown-order-notify"));
// ✅ 正确（JDK 21+）：ExecutorService 已是 AutoCloseable，close() 会 shutdown + 等待
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    tasks.forEach(executor::submit);
}
```

**【强制】Kubernetes 场景：`terminationGracePeriodSeconds ≥ preStop 停顿(2~5 s) + 每 phase 停机超时 + 池 awaitTermination + 5 s 缓冲`。**

原因：SIGTERM 与 Pod 从 Service Endpoints 摘除存在竞态，信号到达时流量可能仍在进入；宽限期耗尽即 SIGKILL，在途任务被腰斩导致数据不一致。

**反例：**
```yaml
# ❌ 错误：30 s 宽限期 < 停机预算（web 30 s + 池 30 s），必然被 SIGKILL
spec: { terminationGracePeriodSeconds: 30 }
```

**正例：**
```yaml
# ✅ 正确：预算 = 5 (preStop) + 30 (web 每 phase) + 30 (池 await) + 10 (缓冲) = 75
spec:
  terminationGracePeriodSeconds: 75
  containers:
    - name: app
      lifecycle:
        preStop: { exec: { command: ["/bin/sh", "-c", "sleep 5"] } }
```

**【推荐】关闭顺序：先停入口（Web 流量、MQ 消费者、`@Scheduled`），再停处理池，最后释放下游资源；用 `SmartLifecycle` 的 phase 显式表达，而非依赖 `@PreDestroy` 的不确定顺序。**

原因：入口未停就关池会产生 `RejectedExecutionException` 风暴；池未停就释放连接池会让在途任务全部报错；不同 Bean 的 `@PreDestroy` 顺序任意。

**正例：**
```java
// ✅ 正确：显式 phase，处理池排在 Web/消费者停止之后
public class NotifyExecutorLifecycle implements SmartLifecycle {
    @Override public int getPhase() { return Integer.MIN_VALUE + 1000; }
    @Override public void stop() { shutdownAndAwaitTermination(pool, "order-notify"); }
}
```

**【推荐】消息/任务型系统关闭时"消费完再退"：先停止拉取，等待 active 归零，再关闭连接；关闭日志必须打印未完成任务数与队列残留数，超时后未确认的消息必须重投而非静默丢弃。**

原因：消费者容器的关闭超时应取"最慢任务耗时"而非默认值；静默丢弃等于消息丢失。（外部 MQ 的消费语义与死信规范见 `mq_specification.md`。）

## 六、进程内队列选型

**【强制】线程池 `workQueue` 与进程内生产/消费管道一律使用有界队列（`SynchronousQueue` 除外，其容量为 0），按下表选型并注释容量与内存特征。**

边界说明：本文件只覆盖 JVM 进程内内存队列（不保证持久化与投递语义）；需要跨进程投递、持久化、重试、死信时使用消息中间件，规范见 `mq_specification.md` / `python_mq_specification.md`。

| 队列 | 容量与存储 | 适用 | 注意 |
|------|-----------|------|------|
| `ArrayBlockingQueue` | 固定数组预分配，创建后不可变 | 线程池默认选择，性能可预测 | 有公平模式：公平降吞吐但减少抖动、避免饥饿 |
| `LinkedBlockingQueue` | 链表，每元素一个节点对象 | 吞吐更高但延迟抖动更大 | 必须显式 capacity；无参构造容量为 `Integer.MAX_VALUE` |
| `SynchronousQueue` | 零存储，直接交接 | 来了就必须有线程执行、不排队 | 需搭配有界 max + Abort，否则退化为无限建线程 |
| `LinkedTransferQueue` | 无界链表 | 需 `transfer/tryTransfer` 或高并发吞吐 | 默认无界；`size()` 非常数时间，监控采样昂贵 |
| `DelayQueue` | 无界堆，元素须实现 `Delayed` | 订单超时关闭、缓存过期扫描 | 无界，须自带消费侧上界策略 |
| LMAX Disruptor | 预分配环形数组（天然有界背压） | 极限低延迟高吞吐单管道 | 仅在剖析证明 JDK 队列是瓶颈时引入 |

**反例：**
```java
// ❌ 错误：无参 `new LinkedBlockingQueue<>()` 即 MAX_VALUE 容量；DelayQueue/LTQ 默认无界
BlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
```

**正例：**
```java
// ✅ 正确：默认 Array（无节点对象开销、容量可预测）；Linked 必须显式 capacity
BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
```

**【推荐】`SynchronousQueue` 只用于"直接交接"：搭配有界 `maximumPoolSize` + `AbortPolicy` 形成快速拒绝而非无限建线程，只有"拒绝即降级"可接受的任务才这样配；到期/延迟语义一律用 `DelayQueue`（或 `ScheduledThreadPoolExecutor`），禁止轮询时间戳模拟。**

**正例：**
```java
// ✅ 正确：不排队，无空闲线程即拒绝并降级（禁止像 newCachedThreadPool 那样放开上限）
new ThreadPoolExecutor(4, 32, 60L, SECONDS, new SynchronousQueue<>(), new NamedThreadFactory("heavy-task"), abort);
// ✅ 正确：订单超时关闭，单消费者阻塞取到期项（JDK 21+ 可用虚拟线程）
ExpiredOrder o = delayQueue.poll(1, TimeUnit.MINUTES);
```

**【参考】`LinkedTransferQueue` 与 LMAX Disruptor 属特定场景选项：前者承担无界与 `size()` 代价换取 `transfer` 语义与吞吐，后者用序号屏障代替锁竞争替代"更长的 BlockingQueue 链"。默认优先 JDK 队列，引入 Disruptor 需有性能剖析数据支撑。**

## 七、CompletableFuture 与异步编排

**【强制】所有 `CompletableFuture` 异步方法（含 `thenApplyAsync` / `thenComposeAsync` 等 Async 变体）必须传显式 `Executor`，禁止依赖默认 commonPool；纯 CPU 短转换用同步 `thenApply`，避免无谓线程跳跃。**

原因：无 Executor 参数的异步方法与 Async 变体全部在 `ForkJoinPool.commonPool()` 上执行（并行度 JVM 全局、无法按业务隔离），阻塞 IO 会连带所有依赖公共池的功能饥饿。

**反例：**
```java
// ❌ 错误：默认 commonPool 承载远程调用；纯字段映射也跳一次线程
CompletableFuture<Item> f = CompletableFuture.supplyAsync(() -> loadRemote(id));
CompletableFuture<Dto> g = f.thenApplyAsync(this::toDto);
```

**正例：**
```java
// ✅ 正确：显式 IO 池 + 同步转换复用当前线程；确需换线程时才用带 executor 的 Async 变体
CompletableFuture<Dto> f = CompletableFuture.supplyAsync(() -> loadRemote(id), ioExecutor)
        .thenApply(this::toDto)
        .thenApplyAsync(this::enrich, cpuExecutor);
```

**【强制】每条对外暴露的 CF 链必须声明超时：`orTimeout`（以 `TimeoutException` 异常完成）或 `completeOnTimeout`（给降级值）二选一，禁止裸链无限等待；并须知道超时与 `CompletableFuture.cancel(true)` 都不中断执行线程，真正释放线程必须持有底层 `Future` 句柄并 `cancel(true)`。**

原因：CF 的 cancel 只改变自身完成状态（与 `FutureTask` 不同）；只 `orTimeout` 而不释放底层任务，池容量仍被占用。

**反例：**
```java
// ❌ 错误：无超时裸链，下游 hang 则永不完成；且以为 CF.cancel(true) 会中断执行体
CompletableFuture<Item> f = CompletableFuture.supplyAsync(this::loadRemote, ioExecutor);
f.cancel(true);
```

**正例：**
```java
// ✅ 正确：链上声明超时，失败时取消底层任务归还容量
CompletableFuture<Item> f = new CompletableFuture<>();
Future<?> handle = ioExecutor.submit(() -> { try { f.complete(loadRemote(id)); } catch (Exception e) { f.completeExceptionally(e); } });
Item item = f.orTimeout(300, TimeUnit.MILLISECONDS)
             .whenComplete((v, ex) -> { if (ex != null) handle.cancel(true); })
             .get(350, TimeUnit.MILLISECONDS);
// ✅ 正确：可降级读缓存 —— 超时给兜底值而不是失败
CompletableFuture<Item> safe = CompletableFuture.supplyAsync(() -> loadRemote(id), ioExecutor)
        .completeOnTimeout(cachedItem(id), 300, TimeUnit.MILLISECONDS);
```

**【强制】组合用 `thenCompose` 扁平化，禁止 `thenApply` 返回 `CompletableFuture<CompletableFuture<T>>` 再靠 `join` 收尾；等待多个任务用 `allOf(...).thenApply(...)` 在全完成后聚合，禁止 `allOf(...).join()` 后在业务线程逐个 `get()`（伪并行真串行），也不要把含 `join()` 的下游放到 commonPool。**

原因：嵌套 CF 忘记 `join` 时内层结果丢失或悬挂；中间 `join()` 把等待时长累加并扣住当前线程，而 `allOf` 本身不聚合结果，其完成后取各已完成 future 的值才不阻塞。

**反例：**
```java
// ❌ 错误：CF<CF<T>> + 阻塞 join
CompletableFuture<Order> r = userFuture.thenApply(u -> supplyAsync(() -> loadOrder(u), ioExecutor)).join();
// ❌ 错误：在业务/Web 线程上阻塞聚合
CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new)).join();
```

**正例：**
```java
// ✅ 正确：thenCompose 扁平化，全程不阻塞
CompletableFuture<Order> r = userFuture
        .thenCompose(u -> CompletableFuture.supplyAsync(() -> loadOrder(u), ioExecutor))
        .thenApply(this::enrich);
// ✅ 正确：allOf 后聚合并包一层总超时（此时 join 不阻塞）
List<CompletableFuture<A>> fs = ids.stream()
        .map(id -> CompletableFuture.supplyAsync(() -> load(id), ioExecutor).orTimeout(300, MS)).collect(toList());
CompletableFuture<List<A>> all = CompletableFuture.allOf(fs.toArray(new CompletableFuture[0]))
        .thenApply(v -> fs.stream().map(CompletableFuture::join).collect(toList())).orTimeout(400, MS);
```

**【强制】每条 CF 链必须有异常收口：`exceptionally` / `handle` 必须转译降级值或向上抛出，并在终端配一个"日志 + 指标"型 `whenComplete`；禁止 `exceptionally(t -> null)` 这类静默吞错。**

原因：CF 的异常完成不走 `UncaughtExceptionHandler`，若链终点没有 `whenComplete/join/exceptionally` 消费，异常会完全丢失。

**反例：**
```java
// ❌ 错误：异常变成 null，后续 NPE 且无痕迹
future.exceptionally(t -> null);
```

**正例：**
```java
// ✅ 正确：转译明确降级值 + 终端兜底日志与指标
return CompletableFuture.supplyAsync(() -> loadRemote(id), ioExecutor)
        .orTimeout(300, TimeUnit.MILLISECONDS)
        .exceptionally(ex -> { log.warn("load remote failed, id={}", id, ex); return Item.empty(id); })
        .whenComplete((v, ex) -> {
            if (ex != null) { log.error("unhandled cf failure, id={}", id, ex); Metrics.counter("cf.unhandled.error").increment(); }
        });
```

**【参考】结构化并发（一组相关子任务当作单一工作单元、失败级联、线程不逃逸）在 JDK 19–24 均为 preview，JDK 25 以 JEP 505 重做后仍是 preview 且 API 变化（`openAny()/openTimeout()` 工厂 + `Subtask` + try-with-resources `close()`）；生成代码优先用 `CompletableFuture` 编排，仅在团队锁定 JDK 版本并显式开启 preview 时允许 `StructuredTaskScope`。**

## 八、锁与原子类

**【强制】`ReentrantLock` 只有两种正确模板：阻塞式 `lock()` 必须"加锁在 try 之外、`unlock()` 在 finally"；`tryLock(timeout)` 必须先判断是否持有锁再决定解锁，失败分支不得 `unlock()`。**

原因：`lock()` 写在 try 内时，加锁本身失败会让 `finally` 的 `unlock()` 解掉别人的锁；`tryLock()` 失败仍解锁抛 `IllegalMonitorStateException`；带超时的 `tryLock` 还能打破无限等待。

**反例：**
```java
// ❌ 错误：lock() 进了 try，未获得锁时 finally 解别人的锁
try { lock.lock(); doWork(); } finally { lock.unlock(); }
// ❌ 错误：tryLock 失败分支仍 unlock
try { if (lock.tryLock()) doWork(); } finally { lock.unlock(); }
```

**正例：**
```java
// ✅ 正确：阻塞式 —— 加锁在 try 之外
lock.lock();
try { doWork(); } finally { lock.unlock(); }
// ✅ 正确：尝试式 —— 带超时且先判持有，失败即降级
if (lock.tryLock(200, TimeUnit.MILLISECONDS)) {
    try { doWork(); } finally { lock.unlock(); }
} else {
    Metrics.counter("lock.timeout", "lock", "stockUpdate").increment();
    return degrade();
}
```

**【强制】同时持有多个资源锁必须在全工程固定加锁顺序（按对象 id 排序或约定单向顺序），能收敛为单锁的不得嵌套。**

原因：两个线程以相反顺序申请同一组锁会形成循环等待即死锁；固定顺序破坏循环等待条件。

**反例：**
```java
// ❌ 错误：加锁顺序取决于参数顺序，反向调用即死锁
synchronized (a) { synchronized (b) { doTransfer(a, b); } }
```

**正例：**
```java
// ✅ 正确：全局按 id 升序加锁
Account first = a.getId() < b.getId() ? a : b, second = a.getId() < b.getId() ? b : a;
lock(first).lock();
try {
    lock(second).lock();
    try { doTransfer(a, b); } finally { lock(second).unlock(); }
} finally { lock(first).unlock(); }
```

**【强制】锁粒度最小化：能锁代码块不锁整个方法体，能用对象锁不用类锁；锁内禁止 RPC/HTTP、数据库、文件 IO、日志刷盘等慢操作。**

原因：持锁时间由最慢 IO 决定，临界区内的网络调用会把锁竞争放大为吞吐塌方；粗粒度的 `synchronized` 方法还会阻塞无关字段访问。

**反例：**
```java
// ❌ 错误：整个方法加锁且锁内做远程调用与持久化
public synchronized void updateStock(Long skuId) {
    stockRepo.load(skuId).addCount(rpc.queryInTransit(skuId));
}
```

**正例：**
```java
// ✅ 正确：慢数据在锁外取，锁内只做内存状态变更，持久化在锁外
public void updateStock(Long skuId) {
    int inTransit = rpc.queryInTransit(skuId);
    synchronized (stockLock(skuId)) { localState.get(skuId).addCount(inTransit); }
    stockRepo.save(localState.get(skuId));
}
```

**【强制】按下列决策表选择同步原语，禁止随手加 `synchronized`，也禁止用 `volatile` 保护复合操作。**

| 场景 | 选择 | 依据 |
|------|------|------|
| 一写多读的可见性（状态开关、停止标志） | `volatile` | 只保证可见性与禁重排；多写仍需 CAS 或锁 |
| 单变量原子更新且需精确值（序号、配额判断） | `AtomicInteger` / `AtomicLong` / `AtomicReference` | 提供 CAS 复合语义，读值精确 |
| 高频并发累加、偶尔读总数（打点、汇总） | `LongAdder` / `LongAccumulator` | 高竞争吞吐显著优于 `AtomicLong`，代价是更占内存、读值为近似值，不可当同步原语 |
| 复合不变量（check-then-act、多字段原子变更） | 锁，或并发容器的复合方法 | 单个原子变量无法保证跨变量不变式 |
| 保护对象不变式，无需超时/可中断/公平 | `synchronized` | 不易漏解锁；JDK 21–23 在虚拟线程下会 pin 载体线程（见第十一章），此时改用 `ReentrantLock` |
| 需要超时/可中断/多条件/公平 | `ReentrantLock` + `Condition` | `tryLock(timeout)` 可避免无限等待 |

**反例：**
```java
// ❌ 错误：volatile 只保证可见性，自增不是原子操作
private volatile int hits; public void record() { hits++; }
// ❌ 错误：需要精确值/配额判断却用 LongAdder（sum() 是近似快照）
if (adder.sum() >= QUOTA) throw new QuotaExceededException();
```

**正例：**
```java
// ✅ 正确：一写多读用 volatile；观测汇总用 LongAdder；参与控制流用 Atomic
private volatile boolean stopped = false;
private final LongAdder hits = new LongAdder();
private final AtomicInteger live = new AtomicInteger();
```

**【强制】`CountDownLatch` 等等待-通知必须有超时兜底：等待方用 `await(timeout)` 并处理超时返回值，工作线程的 `countDown()` 必须放在 `finally`。**

原因：任一任务异常路径漏掉 `countDown()`，无超时的 `await()` 就是永久挂起。

**反例：**
```java
// ❌ 错误：无超时；countDown 不在 finally，异常路径永不调用
latch.await();
try { doWork(); latch.countDown(); } catch (Exception e) { log.error("failed", e); }
```

**正例：**
```java
// ✅ 正确：countDown 收口在 finally，等待方带超时并降级
executor.submit(() -> {
    try { doWork(); } catch (Exception e) { log.error("sub task failed", e); } finally { latch.countDown(); }
});
if (!latch.await(3, TimeUnit.SECONDS)) { log.warn("aggregate timeout, remaining={}", latch.getCount()); return partialResult(); }
```

**【推荐】双重检查锁（DCL）延迟初始化的目标字段必须声明为 `volatile`；无特殊需要时优先用静态 holder 惰性类（无锁、无坑）。**

原因：没有 `volatile` 时"分配内存—初始化—引用赋值"可能被重排，其他线程会拿到半初始化对象。

**反例：**
```java
// ❌ 错误：字段非 volatile 的 DCL —— "分配—初始化—赋值"可被重排，其他线程拿到半初始化对象
private static Instance instance;
```

**正例：**
```java
// ✅ 正确：无特殊需要优先静态 holder，靠类初始化保证线程安全且无锁
public class Instance {
    private static class Holder { static final Instance VALUE = new Instance(); }
    public static Instance getInstance() { return Holder.VALUE; }
}
// ✅ 正确：确需 DCL 时目标字段必须 volatile
private static volatile Instance instance;
```

**【推荐】乐观锁与悲观锁按冲突率选择：冲突概率低于 20% 用乐观锁且重试次数不少于 3 次，否则用悲观锁；资金等金融敏感数据一律悲观锁。**

原因：高冲突下乐观锁重试风暴会放大压力甚至长期无法提交；乐观锁的丢失更新风险在资金场景不可接受。

## 九、并发容器

**【强制】禁止在 `ConcurrentHashMap` 上写 check-then-act 复合操作，必须用 `computeIfAbsent` / `computeIfPresent` / `compute` / `merge` / `putIfAbsent` 等原子复合方法；传入的 mapping function 必须短小简单，不得在其中做 IO/RPC/日志，也不得递归修改同一个 Map。**

原因：`containsKey` 与 `put` 之间会被其他线程插入，导致重复创建、覆盖或计数丢失；而计算期间该 bin 上的其他更新会被阻塞，慢函数或递归修改会把单点延迟放大为整表吞吐塌陷甚至死锁。

**反例：**
```java
// ❌ 错误：竞态窗口，可能创建两个实例并丢失累加
if (!cache.containsKey(key)) cache.put(key, build(key));
counter.get(key).incrementAndGet();
// ❌ 错误：mapping function 内做远程调用，锁住 bin 拖垮全表
map.computeIfAbsent(id, k -> httpClient.get("/detail/" + k));
```

**正例：**
```java
// ✅ 正确：复合方法完成"取或建"与累加；慢数据在复合方法之外填充
CacheEntry entry = cache.computeIfAbsent(key, this::build);
counter.merge(key, 1L, Long::sum);
if (!entry.isLoaded()) entry.load(httpClient.get("/detail/" + id));
```

**【强制】禁止把并发容器的 `size()` / `isEmpty()` 结果用于业务控制流（限流、配额、判空后操作），只可用于监控与估算。**

原因：聚合方法与迭代器都是弱一致的，只在无并发更新时才精确；以 `size() >= LIMIT` 限流会竞态超限。

**反例：**
```java
// ❌ 错误：并发下实际实例数可远超上限
if (runningInstances.size() >= MAX_INSTANCE) throw new QuotaExceededException();
runningInstances.put(id, newInstance);
```

**正例：**
```java
// ✅ 正确：用原子计数器承载配额判断，失败即回退计数
if (liveCount.incrementAndGet() > MAX_INSTANCE) { liveCount.decrementAndGet(); throw new QuotaExceededException(); }
try { runningInstances.put(id, newInstance); }
catch (RuntimeException e) { liveCount.decrementAndGet(); throw e; }
```

**【强制】共享可变状态禁止使用非线程安全容器（`HashMap` / `ArrayList` / `LinkedList`）；`HashMap` 并发写会丢数据，扩容期间在旧 JDK 上还会链表成环导致 CPU 飙升。**

原因：并发写破坏内部结构且不可复现，读线程可能永久自旋；这类容器只允许出现在线程封闭（方法内局部、构造后不再修改）的位置。

**反例：**
```java
// ❌ 错误：static HashMap 被多线程读写
private static final Map<String, Config> CONFIGS = new HashMap<>();
public static void refresh(String key, Config c) { CONFIGS.put(key, c); }
```

**正例：**
```java
// ✅ 正确：读多写少用不可变快照 + volatile 引用整体替换；常态并发读写用 ConcurrentHashMap
private static volatile Map<String, Config> CONFIGS = Map.of();
public static void refresh(Map<String, Config> next) { CONFIGS = Map.copyOf(next); }
private static final ConcurrentMap<String, Config> LIVE = new ConcurrentHashMap<>();
```

## 十、ThreadLocal 与上下文传播

**【强制】线程池场景使用 `ThreadLocal` 必须在 `try-finally` 中 `remove()`。**

原因：线程会被复用，未清理的 value 挂在复用线程的 `ThreadLocalMap` 上，既造成内存泄漏，更会让下一个请求读到上一个请求的租户/用户上下文——属于数据串号级别的安全问题。

**反例：**
```java
// ❌ 错误：无 remove，复用线程把上一个请求的用户带给下一个请求
USER_CTX.set(currentUser);
handle();
```

**正例：**
```java
// ✅ 正确：成对清理
try { USER_CTX.set(currentUser); handle(); } finally { USER_CTX.remove(); }
```

**【强制】MDC / 用户 / 租户上下文必须显式传播进池线程（`TaskDecorator` 或 TransmittableThreadLocal 包装），禁止假设子线程自动可见；`InheritableThreadLocal` 只对新建子线程有效，对池内复用线程无效。**

原因：MDC 基于 `ThreadLocal` 实现，池化线程在上下文设置前就已创建，继承发生在构造期，因此异步日志会丢 traceId、下游会读到错误或缺失的租户上下文。

**反例：**
```java
// ❌ 错误：以为 InheritableThreadLocal 能把上下文带进池线程
private static final ThreadLocal<String> TENANT = new InheritableThreadLocal<>();
pool.submit(() -> log.info("tenant={}", TENANT.get()));   // 读到 null 或上一个请求的脏值
```

**正例：**
```java
// ✅ 正确（Spring）：TaskDecorator 复制 MDC 进任务，执行后清理
executor.setTaskDecorator(runnable -> {
    Map<String, String> ctx = MDC.getCopyOfContextMap();
    return () -> { try { if (ctx != null) MDC.setContextMap(ctx); runnable.run(); } finally { MDC.clear(); } };
});
// ✅ 正确（非 Spring / 需跨池传递）：TransmittableThreadLocal + 包装器
TtlExecutors.getTtlExecutorService(pool).submit(TtlRunnable.get(() -> handle()));
```

**【推荐】不要把 `Random` 实例作为共享静态变量多线程使用，改用 `ThreadLocalRandom`（JDK 17+ 亦可用 `RandomGenerator`）。**

原因：共享 `Random` 虽线程安全，但所有线程竞争同一种子（CAS 自旋），高并发下性能显著劣化。

**反例：**
```java
// ❌ 错误：共享静态 Random，所有线程竞争同一种子（CAS 自旋）
private static final Random RANDOM = new Random();
int n = RANDOM.nextInt(100);
```

**正例：**
```java
// ✅ 正确：每线程独立种子，且无需清理（JDK 17+ 亦可用 RandomGenerator）
int n = ThreadLocalRandom.current().nextInt(100);
```

**【推荐】虚拟线程时代慎用 `ThreadLocal` 缓存昂贵对象：并发虚拟线程数量巨大，缓存会放大内存占用；上下文传递优先方法参数或显式作用域，确需 ThreadLocal 时控制 value 体积并确保 `remove()`。**

原因：池化复用是 ThreadLocal 缓存成立的前提，thread-per-task 模型没有这个前提。

**【参考】`ThreadLocal` 对象本身建议用 `static final` 修饰（作为线程维度的存储槽），但要清楚它无法解决共享对象的更新问题——它不是共享变量的同步方案。**

## 十一、虚拟线程（JDK 21+）

**【强制】本章仅适用于 JDK ≥ 21（虚拟线程自 JDK 21 正式），禁止对 JDK 8/11 项目输出虚拟线程方案；同时禁止池化虚拟线程，一律 thread-per-task（`Executors.newVirtualThreadPerTaskExecutor()` / `Thread.ofVirtual()`）；需要限流时用 `Semaphore` 或依赖下游资源本身（连接池、容器配额），禁止用"固定大小虚拟线程池"变相限流。**

原因：池的设计目的是共享稀缺资源，而虚拟线程廉价且不稀缺，装进固定大小池等于自造并发上限并丢掉扩展性；官方给出的限流构造就是信号量，用池大小当限流器会让容量语义散落两处。

**反例：**
```java
// ❌ 错误：池化虚拟线程 = 白造 200 并发上限，同时把限流逻辑藏在池参数里
ExecutorService pool = Executors.newFixedThreadPool(200, Thread.ofVirtual().factory());
```

**正例：**
```java
// ✅ 正确：每任务一个虚拟线程，try-with-resources 保证关闭并等待完成
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<Item>> futures = ids.stream().map(id -> executor.submit(() -> loadRemote(id))).collect(toList());
    awaitAll(futures, 300, TimeUnit.MILLISECONDS);
}
// ✅ 正确：显式信号量保护受限下游，取不到令牌即快速失败
private final Semaphore permits = new Semaphore(50);
Item load(Long id) throws InterruptedException {
    if (!permits.tryAcquire(200, TimeUnit.MILLISECONDS)) throw new DownstreamOverloadedException("search");
    try { return queryDownstream(id); } finally { permits.release(); }
}
```

**【强制】以下场景禁止迁移到虚拟线程：CPU 密集型计算、依赖"线程数少"假设的旧 API 或 JNI 原生库、依赖 `ThreadLocal` 缓存重对象的热点路径、需要串行化 OS 稀缺资源的临界区。**

原因：虚拟线程的收益来自"大部分时间在阻塞"，长计算任务不会因它变快；百万级虚拟线程会压垮按低线程数设计的原生库与本地缓存。

**反例：**
```java
// ❌ 错误：CPU 密集（图像编码/加解密/大集合计算）用虚拟线程，只增加调度开销
try (var e = Executors.newVirtualThreadPerTaskExecutor()) {
    images.forEach(img -> e.submit(() -> heavyEncode(img)));
}
```

**正例：**
```java
// ✅ 正确：CPU 并行仍走平台线程池（大小 = 可用核数）
ThreadPoolExecutor cpuPool = new ThreadPoolExecutor(ncpu, ncpu, 0L, SECONDS,
        new ArrayBlockingQueue<>(ncpu * 8), new NamedThreadFactory("encode"), new ThreadPoolExecutor.AbortPolicy());
// ✅ 正确：IO 扇出才用虚拟线程（任务几乎全程阻塞在 IO）
try (var e = Executors.newVirtualThreadPerTaskExecutor()) { urls.forEach(u -> e.submit(() -> fetch(u))); }
```

**【强制】JDK 21–23 运行虚拟线程时：禁止新增"临界区内含阻塞调用"的 `synchronized` 方法/块，需阻塞的互斥一律用 `ReentrantLock`，并持续监测 pinning（JFR 事件 `jdk.VirtualThreadPinned` 默认开启、阈值 20 ms；诊断开关 `-Djdk.tracePinnedThreads=full|short`）；JDK 24+（JEP 491）后本条降为【推荐】，但 native 帧仍会钉住载体线程，JDBC/加密库等 native 方法仍需评估。**

原因：JDK 21–23 中在 `synchronized` 内阻塞会让虚拟线程钉住载体线程，调度器可用线程被耗尽，性能塌方且不体现在业务指标上；JEP 491 让 monitor 阻塞可卸载，原生帧仍不可卸载。

**反例：**
```java
// ❌ 错误（JDK 21–23）：synchronized 内做阻塞 IO，钉住载体线程
public synchronized List<Row> query(String sql) { return jdbcTemplate.query(sql, MAPPER); }
```

**正例：**
```java
// ✅ 正确：显式锁 + 超时；native 阻塞尽量移出临界区
private final ReentrantLock lock = new ReentrantLock();

public List<Row> query(String sql) throws InterruptedException {
    if (!lock.tryLock(200, TimeUnit.MILLISECONDS)) throw new OverloadException("query critical section");
    try { return prepare(sql); } finally { lock.unlock(); }
}
// 排障：jfr print --events jdk.VirtualThreadPinned recording.jfr
```

**【强制】开启 `spring.threads.virtual.enabled=true`（JDK 21+，Spring Boot 3.2+）时必须同时处理三件事：`spring.main.keep-alive=true` 保证 JVM 保活；用下游连接池上界或 `Semaphore` 限流（该模式下每任务一线程、无并发上限、无队列与拒绝策略兜底）；认知 `@Async` 执行器由 `ThreadPoolTaskExecutor` 切换为虚拟线程 `SimpleAsyncTaskExecutor` 的行为变化。**

原因：全虚拟线程时可能没有非守护线程导致 main 退出；无并发上限会把压力直接推给下游连接池。（Boot 工程侧约束见 `java_springboot_specification.md`。）

**反例：**
```properties
# ❌ 错误：只开关虚拟线程，未处理保活与下游限流
spring.threads.virtual.enabled=true
```

**正例：**
```properties
# ✅ 正确：开关 + 保活；容量保护写在业务侧（Semaphore / 连接池上界）
spring.threads.virtual.enabled=true
spring.main.keep-alive=true
```

**【参考】经验阈值：应用并发虚拟线程数长期达不到 1 万级别很难从虚拟线程获益；反之 IO 扇出重的 thread-per-message 型消费者在虚拟线程下架构最简（每条消息一个虚拟线程，无需自管池与队列）。**

## 十二、Spring / Boot 接线（并发相关最小集）

**【强制】自定义执行器必须以命名 Bean 注册并交容器托管生命周期：`ThreadPoolTaskExecutor` 需 `setWaitForTasksToCompleteOnShutdown(true)` + `setAwaitTerminationSeconds(n)`；裸 `ThreadPoolExecutor` / `ExecutorService` 用 `@Bean(destroyMethod = ...)` 或 `SmartLifecycle` 完成等待。线程池参数、队列与拒绝策略规范见本文件第一至六章；Boot 工程侧配置约束见 `java_springboot_specification.md`。**

**反例：**
```java
// ❌ 错误：未命名、未设关闭等待，容器停机即腰斩任务
@Bean
public Executor asyncExecutor() { return Executors.newFixedThreadPool(8); }
```

**正例：**
```java
// ✅ 正确：命名 Bean + 完整参数 + 上下文传播 + 关闭等待
@Bean("orderNotifyExecutor")
public ThreadPoolTaskExecutor orderNotifyExecutor() {
    ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
    e.setCorePoolSize(8); e.setMaxPoolSize(8);
    e.setQueueCapacity(2000);                       // 有界，容量依据见注释
    e.setThreadNamePrefix("order-notify-");
    e.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    e.setTaskDecorator(mdcCopyingDecorator());      // 见第十章
    e.setWaitForTasksToCompleteOnShutdown(true);
    e.setAwaitTerminationSeconds(30);
    e.initialize();
    return e;
}
// ✅ 正确（JDK 21+）：close() 语义由容器负责
@Bean(destroyMethod = "close")
public ExecutorService rpcAggExecutor() { return Executors.newVirtualThreadPerTaskExecutor(); }
```

**【强制】`@Async` 必须显式指定执行器名（`@Async("orderNotifyExecutor")`），禁止全工程共用默认 `taskExecutor`；同时必须配置 `AsyncUncaughtExceptionHandler`，或让 `@Async` 方法返回 `CompletableFuture` 并由调用方消费异常。**

原因：`void` 返回的 `@Async` 方法异常默认只被日志吞掉；共用默认池等于所有业务共享一个故障域，违反第二章分池要求。

**反例：**
```java
// ❌ 错误：默认池 + void 返回，异常无人消费
@Async
public void notifyOrder(Order order) { doNotify(order); }
```

**正例：**
```java
// ✅ 正确：指定业务池 + 异常交给 AsyncUncaughtExceptionHandler 收口
@Async("orderNotifyExecutor")
public void notifyOrder(Order order) {
    try { doNotify(order); } catch (Exception e) { throw new NotifyException(e); }
}

@Override
public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
    return (ex, method, params) -> log.error("async method {} failed", method.getName(), ex);
}
```

**【推荐】Boot 侧优先用 `ThreadPoolTaskExecutorBuilder` / `SimpleAsyncTaskExecutorBuilder` 构建执行器（旧的 `TaskExecutorBuilder` 无参构造已废弃）；`SimpleAsyncTaskExecutor` 必须用带业务前缀的构造形式，虚拟线程通过 `setVirtualThreads(true)`（Framework 6.1+，需 JDK 21+）开启。**

**正例：**
```java
// ✅ 正确：显式线程名前缀，不依赖已废弃的无参 Builder
SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("order-notify-");
executor.setVirtualThreads(true);
```

## 十三、可观测性与排障

**【强制】每个业务线程池必须注册指标：`executor.pool.size/core/maximum`、`executor.active`、`executor.idle`、`executor.pool.queue.size`、`executor.pool.queue.remaining`、`executor.completed`，并由自定义拒绝处理器补 `executor.rejected` 计数（JDK 不暴露拒绝计数，Micrometer 默认也不产出）。**

可检查判据：每个池都有带 `pool` 标签的 `executor_*` 指标，且存在 `executor_rejected_total{pool=...}`。

**反例：**
```java
// ❌ 错误：池建完就无人可见，饱和与拒绝完全无数据
new ThreadPoolExecutor(8, 8, 0L, SECONDS, queue, factory, handler);
```

**正例：**
```java
// ✅ 正确：绑定 Micrometer 指标（rejected 仍需自定义 handler 打点，见第三章）
@Bean
public ThreadPoolTaskExecutorCustomizer metricsCustomizer(MeterRegistry registry) {
    return executor -> ExecutorServiceMetrics.monitor(registry, executor.getThreadPoolExecutor(), "order-notify");
}
```

**【推荐】故障排查工具链标准化：平台线程用 `jstack <pid>` 或 `jcmd <pid> Thread.print`（前者自动报告 Java 级死锁环）；JDK 21+ 虚拟线程必须用 `jcmd <pid> Thread.dump_to_file -format=json|text <file>` 加 JFR 事件（普通线程转储看不到虚拟线程，`Thread.getAllStackTraces()` 只返回平台线程）；生产镜像保留 JDK 诊断工具并配 `-XX:+HeapDumpOnOutOfMemoryError`。**

原因：队列堆积导致 OOM 时，heap dump 中巨大的 `LinkedBlockingQueue$Node` 数组就是直接证据；缺少虚拟线程转储会让异步卡顿无法定位。

**正例：**
```bash
# ✅ 正确：容器内标准取证动作
jcmd 1 Thread.dump_to_file -format=json /tmp/td.json      # 含虚拟线程
jstack 1 > /tmp/td.txt                                     # 平台线程 + 死锁检测
jfr print --events jdk.VirtualThreadPinned,jdk.VirtualThreadSubmitFailed recording.jfr
```

**【推荐】告警必须在饱和前触发：队列使用率 > 70% warning、> 90% critical、`active / max` > 0.9 持续 1 分钟、出现拒绝即 P0；并在任务打点中记录 enqueue → start 的排队等待时间，与容量推理注释互相引用。**

原因：排队延迟比 active 数量更早暴露拥塞，等拒绝发生已经太晚。

**正例：**
```java
// ✅ 正确：任务级排队延迟打点，比线程指标更灵敏
long enqueueAt = System.nanoTime();
pool.submit(() -> {
    Metrics.timer("executor.queue.wait", "pool", "order-notify").record(Duration.ofNanos(System.nanoTime() - enqueueAt));
    handle();
});
```

## 禁止事项

**【强制】禁止以下做法：**

1. ❌ 用 `Executors.newFixedThreadPool` / `newCachedThreadPool` / `newSingleThreadExecutor` / `newScheduledThreadPool` 创建线程池
2. ❌ 使用无参 `new LinkedBlockingQueue<>()` 或任何无界队列作为 workQueue；靠 setter 让池参数运行期无校验漂移
3. ❌ `core≠max` 却配大容量队列（队列满前 max 永不生效）且无任何注释说明参数意图
4. ❌ 用 `java.util.Timer` 承载多个定时任务
5. ❌ 使用默认 `ThreadFactory`，线程名保持 `pool-N-thread-M`
6. ❌ 不同业务语义共用同一个线程池（全工程一个 `taskExecutor`）
7. ❌ 在池内任务中向同一个池提交子任务并阻塞等待
8. ❌ 用 `parallelStream()` 或无 `Executor` 参数的异步方法承载阻塞 IO
9. ❌ 用 `DiscardPolicy` 承载业务任务（静默丢弃、不可审计）
10. ❌ 拒绝路径无指标无日志，或让被拒绝任务的 `Future`/`CompletableFuture` 永不完成
11. ❌ 无参 `Future.get()`、无超时的 `join()`、无超时的 `latch.await()`
12. ❌ 超时后不 `cancel(true)`；任务体吞掉 `InterruptedException` 不响应中断
13. ❌ `submit()` 后从不 `get()` 且任务体无 try-catch，让异常彻底静默
14. ❌ 只调 `shutdown()` 不等待，或第一步就 `shutdownNow()` 并丢弃其返回的排队任务
15. ❌ K8s 中 `terminationGracePeriodSeconds` 小于 preStop + 停机超时 + 池等待时间之和
16. ❌ `exceptionally(t -> null)` 或用空值吞掉 CompletableFuture 链上的异常而不留日志/指标
17. ❌ `thenApply` 产出嵌套 `CompletableFuture` 再靠 `join` 收尾；`allOf(...).join()` 后在业务线程逐个取值
18. ❌ `lock()` 写进 try 块；`tryLock()` 失败分支仍执行 `unlock()`
19. ❌ 多资源加锁无全局顺序；在锁代码块内做 RPC/HTTP/DB/文件 IO
20. ❌ 用 `volatile` 保护多写或复合不变量；把 `LongAdder` 当精确同步原语用于控制流
21. ❌ 在 `ConcurrentHashMap` 上写 check-then-act；在 mapping function 内做 IO 或递归修改本 Map
22. ❌ 用并发容器的 `size()` / `isEmpty()` 做限流、配额等控制流判断
23. ❌ static 共享 `HashMap`/`ArrayList` 供多线程读写；共享 `Random` 实例
24. ❌ 池内 `ThreadLocal` 不 `remove()`；用 `InheritableThreadLocal` 向池线程传上下文
25. ❌ 池化虚拟线程，或用固定大小虚拟线程池变相限流
26. ❌ 对 JDK 8/11 项目输出虚拟线程方案；JDK 21–23 在 `synchronized` 内做阻塞调用且不监测 pinning
27. ❌ 线程池无指标（尤其缺 `executor.rejected`）；生产镜像删除 `jstack`/`jcmd` 等诊断工具

## 快速检查清单

- [ ] 是否显式 `new ThreadPoolExecutor(...)` 传齐七参数，未使用 `Executors` 工厂方法？
- [ ] 队列是否有界且容量注释了推理（延迟预算 × 吞吐 / 堆余量）？
- [ ] 参数是否符合 core→queue→max→reject 顺序，`core≠max` + 大队列是否必有注释说明？
- [ ] 线程数是否按 CPU/IO 类型取起点、以容器可用核数计算、并经压测校准？
- [ ] 定时任务是否用 `ScheduledThreadPoolExecutor` 而非 `Timer`，任务体是否自捕获异常？
- [ ] 线程名是否带业务前缀，ThreadFactory 是否设置 `UncaughtExceptionHandler`？
- [ ] 是否一业务语义一池，父子任务是否分池或改为非阻塞编排？
- [ ] 是否没有 `parallelStream` / 无 Executor 的异步调用承载阻塞 IO？
- [ ] 拒绝策略是否显式声明且与业务语义匹配，rejected 是否有计数并能传播给调用方？
- [ ] 所有 `Future.get()` / `latch.await()` 是否带超时，超时后是否 `cancel(true)` 且任务响应中断？
- [ ] 关闭是否走 shutdown→awaitTermination→shutdownNow 序列，残留任务是否重投？
- [ ] 关闭顺序是否按入口→池→资源（SmartLifecycle phase），K8s gracePeriod 是否覆盖预算？
- [ ] 队列选型是否对照决策表，进程内管道与跨进程 MQ 边界是否清晰？
- [ ] 每条 `CompletableFuture` 链是否显式 executor、显式超时、用 `thenCompose`/`allOf` 组合、终端有 `whenComplete` 收口？
- [ ] `ReentrantLock` 模板是否正确（lock 在 try 外 / tryLock 先判持有），多锁是否有全局顺序，锁内是否无 IO？
- [ ] 同步原语是否按 volatile / Atomic / LongAdder / Lock 决策表选择，DCL 字段是否 volatile？
- [ ] `ConcurrentHashMap` 是否只用复合方法，mapping function 是否短小无 IO，是否未用 `size()` 做控制流？
- [ ] `ThreadLocal` 是否 try-finally `remove()`，MDC/租户上下文是否经 `TaskDecorator` 或 TTL 显式传播？
- [ ] 虚拟线程是否 thread-per-task（未池化），限流是否用 `Semaphore`/下游池，是否避开 CPU 密集与 native 场景？
- [ ] JDK 21–23 是否监测 `jdk.VirtualThreadPinned`，`synchronized` 内是否无阻塞调用？
- [ ] 开启 `spring.threads.virtual.enabled` 时是否配了 `spring.main.keep-alive` 与下游限流？
- [ ] `@Async` 是否指定业务池并配 `AsyncUncaughtExceptionHandler`？
- [ ] 每个池是否注册 Micrometer 指标并含 `executor.rejected`，是否配好 jstack/jcmd/JFR 取证路径？
