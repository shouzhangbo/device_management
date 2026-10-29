---
schema_version: 1
status: implementation_ready
tech_design: .aisdd/workspaces/session_20261029_102105/tech-design.md
generated_at: 2026-10-29
---

# 实现映射文档：外部 Kafka 驱动的设备信息镜像 + 对外查询服务

> 用途：把 `tech-design.md` V1.3.0 转为可编码规格。本文档不是最终源代码；它明确本次变更的代码落点、可复用证据、关键实现草图、规则控制流和测试边界，供 `task-planner` 直接消费。本文档生成时暴露的三项设计缺口（G-001 定时任务跨实例互斥 / G-002 未登记的必需配置键 / G-003 未登记的新 Redis key）已回写 `tech-design.md`、`data-model.md` 与契约；**V1.3.0 技术评审又暴露两项映射自身的缺陷（B-02 core 缺 spring-web 依赖 / B-03 熔断组件与配置无承接）并已在本文件内修正**，明细见 §0 末尾。

## 0. 实现就绪结论

| 项目 | 结论 |
|---|---|
| 状态 | `implementation_ready`（**但设计未锁定**，见声明 2） |
| 关联设计 | `tech-design.md` **V1.3.0** + `contracts/api-contract.yaml`（OpenAPI 3.1.0，**v1.2.1**，9 路径 / 11 操作 + `x-kafka-message-contracts` + `x-internal-entries`；v1.2.1 只改 `503/5030` 的描述文案，HTTP 面与字段集合未变）+ `data-model/data-model.md` **V1.1.2**（5 表 DDL + §8.4 维度映射；V1.1.2 表结构未变，只把 DDL 交付形态改回待裁决）+ `review_reports/tech_review.json`（评审**未通过**） |
| 覆盖范围 | FR-001~FR-006（6 条）／EP-001~EP-013（13 个入口）／写侧规则 R1~R15（15 条）／错误码 17 个／表 5 张；共 19 个 `IM-xxx` 单元 |
| 未决阻塞 | 单元级全部为 `none`（见各 IM 的 Blockers）。阶段级前置见下方"三条必须先读的声明"；另有两项**跨文件未决**：DDL 交付形态（附录 E #12，影响 `IM-DB-001`）与真实 `source` 编码（影响 `IM-CORE-004`/`IM-DB-001` 初始数据） |

**三条必须先读的声明（不伪装成已完成）**

1. **代码库基线为空**：仓库根目录当前只有配置类目录（`.aisdd/`、`.qoder/`、`.catpaw/`、`.claude/`、`.codex/`、`.cursor/`），**没有任何 Java 源代码、没有 `pom.xml`、没有测试**。因此本文档所有代码落点的 Action 均为 `CREATE`；`Evidence` 一节引用的是 `.aisdd/rules/` 规范条款与设计章节，**不是既有实现**——本期不存在"复用现有代码"这回事，任何声称复用的说法都是虚构。
2. **设计已评审但未通过、未锁定**（V1.3.0 更正本条旧表述"评审未执行"）：`tech-review` 已跑完 2 轮 React 循环，结论 **不通过**——`.aisdd/Constitution.md` 不存在导致 Step 0 宪法前置失败（宪法有一票否决权），且第 2 轮总分 7.600 < 8.0（⚠️ 该线受技能自身权重求和 = 0.90 的缺陷影响，归一化后为 8.444 已过线——详见 `review_reports/tech_review_discussion.md` 的 S-01。**结论不变**：一票否决独立成立；但也意味着**本映射的真实阻塞项只有宪法一项**，不是设计质量不够）。因此**不存在** `tech_design_final.lock.md`，本映射的 frontmatter 也只能继续指向真实存在的 `tech-design.md`（指向不存在的锁定件比如实写明更危险）。按 `implementation-map` SKILL 的契约本映射可以据此编码，但**编码范围应限制在不依赖四项外部输入的单元**（详见 `tech-design.md#14` R-010）。
3. **无 PRD、无 ADR、无知识库模块页**：`.aisdd/Constitution.md` 不存在，`.aisdd/knowledges/` 只有 `INDEX.md`；`adr-generate` 因锁定件未产出而**未被触发**。需求编号来自设计文档 §3 自拟的 FR 条目（来源为口述需求），不来自 PRD。

**外部依赖的未定项如何处置**：`tech-design.md` 附录 E 登记的未知项（真实 `source` 编码、峰值量级、SLA 数字、外部消息 `event_time` 契约缺口）**全部由配置项或部署流程承接，不构成代码缺口**——代码按配置键读取，值由运维填入。因此各单元 Blockers 可写 `none`，但上线前必须跑设计文档 §6.5 的授权表检查与 §9.2 的初始化 checklist。

**单元索引**

| IM | 归属进程 | 一句话职责 |
|---|---|---|
| IM-BUILD-001 | — | Maven 多模块骨架、BOM 依赖治理、profile 与密钥外部化 |
| IM-DB-001 | — | 表结构交付（**本期不启用版本化迁移**：需求方 2026-10-29 裁决"DDL 不需要迁移"，生产 DDL 由运维按 `data-model.md#6` 人工执行；集成测试用 `src/test/resources/db/test-schema.sql` 夹具）+ 分区与授权初始数据 |
| IM-CORE-001 | device-core | `DimType`/`DimSpec` 维度白名单单一真源 + 命令与结果模型 |
| IM-CORE-002 | device-core | DO / Mapper / 动态列 CAS SQL / 流水与幂等记录写入 |
| IM-CORE-003 | device-core | `DeviceInfoApplier`：唯一持有事务的写入入口 |
| IM-CORE-004 | device-core | `DimAuthGuard` + EP-012 维度授权配置加载 |
| IM-CORE-005 | device-core | `DeviceInfoCache`：Cache-Aside、哨兵、重建锁、afterCommit 失效 |
| IM-CORE-006 | device-core | 读侧查询服务与对外白名单装配（含哨兵时间转 `null`） |
| IM-CORE-007 | device-core | 统一响应外壳、错误码枚举、`trace_id`、全局异常 |
| IM-CORE-008 | device-core | `MessageEnvelope` 契约解析与 `data` 字段白名单校验（消费与重放共用） |
| IM-CORE-009 | device-core | 后台写服务：EP-004/005/009 编排，复用 Applier |
| IM-CORE-010 | device-core | 死信重放与丢弃：EP-011 后端逻辑 |
| IM-INGEST-001 | device-ingest | Kafka 容器、手动位点、重试预算、停机接线 |
| IM-INGEST-002 | device-ingest | EP-006 消费监听器主体：两层幂等 + 终态判定 |
| IM-INGEST-003 | device-ingest | EP-007 缓存对账任务 + 跨实例 cron 互斥锁 |
| IM-INGEST-004 | device-ingest | EP-008 归档清理与次月分区预建 |
| IM-OPENAPI-001 | device-openapi | EP-001/002 控制器 + HMAC 验签 + 配额限流 |
| IM-ADMIN-001 | device-admin | EP-003~011 控制器 + JWT 鉴权 + 操作人上下文 + `Idempotency-Key` |
| IM-OPS-001 | 三进程 | Actuator 探针、类型安全配置绑定、优雅停机（EP-013、R15） |

**映射阶段暴露的设计回写项（已在本文件写入，`tech-design.md` 需同步，见下）**

| # | 缺口 | 本映射的处置 | 需回写位置 |
|---|---|---|---|
| G-001 | `tech-design.md` 未规定 EP-007/EP-008 在 ingest **多实例**下的互斥方式（§12.1 只声明三进程共库，没有"单实例部署"约束），两个 cron 会重复跑：同批 SN 被多实例重复 MGET/DEL，且水位互相覆盖 | 新增组件 `CronLock`（`device:lock:cron:{job}`，TTL = 周期 × 0.8），两个任务进锁 | `tech-design.md#5.2 EP-007/EP-008` 约束列、`#11.1` |
| G-002 | 实现必需的配置键在设计文档里的登记情况：初版 12 个（`device.cron.lock-ttl-ratio`、`device.cron.watermark-ttl-hours`、`device.cache.rebuild-spin-times`、`device.ingest.max-payload-bytes`、`device.openapi.sign-window-seconds`、`device.openapi.qps-per-app`、`device.admin.jwt.issuer`、`device.admin.jwt.audience`、`device.admin.jwt.public-key-path`、`device.admin.idem-ttl-hours`、`device.archive.cleanup-cron`、`device.archive.prebuild-cron`）；**V1.3.0 评审后增至 14 个**（新增 `device.cron.lock-safety-margin-ratio`、`device.openapi.app-secret-path`） | 各 IM 的 Special constraints 逐条给出键名、默认值与不变式（例如"自旋次数必须有界"）；两个新键分别由 `IM-INGEST-003/004`（cron 截断）与 `IM-OPENAPI-001`（Secret 文件）读取 | `tech-design.md#11.1` 参数表（已回写，G-002 表 14 行） |
| G-003 | 三个新 Redis key 未进 §9.4 key 清单：`device:lock:cron:{job}`（任务互斥）、`device:cron:watermark:cache-reconcile`（对账水位）、`device:admin:idem:{key}`（后台建档幂等） | 本文档给出四段命名与 TTL 口径；设计文档的 key 表需补三行 | `tech-design.md#9.4` |

**技术评审（`tech-review` V1.3.0）暴露的映射自身缺陷与处置**

| # | 缺陷 | 严重度 | 本文件的处置 | 位置 |
|---|---|---|---|---|
| B-01 | `.aisdd/Constitution.md` 不存在 → `tech-review` Step 0 前置失败，设计**不可能被锁定**；本映射因此没有锁定件可指 | 🔴 P0（阻塞锁定，不阻塞编码动作本身） | frontmatter 的 `tech_design` **继续指向真实存在的** `tech-design.md`（若改指向不存在的 `tech_design_final.lock.md`，门禁的路径断言会失败且掩盖真实状态）；§0 声明 2 改写为"已评审未通过"；编码范围建议按 `tech-design#14` R-010 分段 | §0 声明 2、frontmatter |
| B-02 | **按 V1.2.0 的 POM 无法编译**：`IM-CORE-007` 把 `GlobalExceptionHandler`（`@RestControllerAdvice`）、`TraceIdFilter`（`OncePerRequestFilter`）等放在 `device-core`，而 `IM-BUILD-001` 的 core 依赖清单显式写"**不引** `spring-boot-starter-web`"——core 编译期没有 spring-web/webmvc/jakarta.servlet 类型 | 🟠 编译级 | core 依赖行改为**以 `provided` 作用域引入** `spring-boot-starter-web`；控制流步 4 同步改写（`provided` 不传递给 ingest，故 ingest 仍不拉起内嵌容器）；Tests 增加"core Web 件可编译"与"provided 不传递"两条断言 | `IM-BUILD-001` Code targets / Control flow / Tests |
| B-03 | **弹性口径无承接**：`IM-CORE-007` 控制流步 5 与 `IM-OPS-001`、`IM-OPENAPI-001` 都以"熔断打开 → `503/5030`"为判定条件，`device-ingest` 的 POM 里装了 `resilience4j-spring-boot3`，但产生 `503` 的入口在 `device-openapi`（依赖装错模块），且没有任何超时键与熔断配置 | 🟠 行为级 | 本文件**去掉 `resilience4j-spring-boot3`**（该框架在规范里只属【推荐】，超时才是【强制】）；三处"熔断"改为"依赖超时/连接失败 → fail-fast 抛 `BizException(5030)`"；超时键（Redis 200ms / MyBatis 2s / Hikari 3s）与行为口径由 `tech-design#11.1`、`#12.4` 承接；`AppKeyRegistry` 补 Secret 文件来源与双 secret 轮换 | `IM-BUILD-001` ingest 依赖行、`IM-CORE-005`、`IM-CORE-007` 步 5、`IM-OPENAPI-001` 约束与 Tests |
| B-04 | `IM-DB-001` 与需求方"DDL 不需要迁移"冲突：原单元把两个 `.sql` 当作已定交付物 | 🟡 形态级 | 两个 `.sql` 行首标注"**本轮未产出**"并写出两种裁决各自的后继动作；新增"人工执行形态"约束行（人工只允许执行已评审全文，禁止临场改列）；`application.yaml` 的 `spring.flyway.*` 行标注为"仅裁决为版本化迁移时产出"。**V1.3.1 编码启动时已定稿**：不再等附录 E #12，两读法对代码处置一致（不引 `flyway-core`、不落 `db/migration/*.sql`），改以 `src/test/resources/db/test-schema.sql` 作测试夹具 | `IM-DB-001` 全单元 |

**门禁自检留痕**

```bash
node .aisdd/bin/implementation-map-check.js . \
  --map .aisdd/workspaces/session_20261029_102105/implementation-map.md
```

| 断言 | 结果 |
|---|---|
| frontmatter 四字段齐备且 `status=implementation_ready` | ✅ |
| `IM-xxx` 条目数 = 19，每条含 `Traceability / Code targets / Implementation sketch / Control flow and rules / Tests / Evidence / Blockers` 七个精确小节 | ✅ |
| 每条含 FR 与 EP 引用、含 `CREATE/MODIFY/DELETE` 表格行、含语言标记的围栏代码块 | ✅ |
| 每条 Blockers 显式 `- none`，条目正文无门禁禁用字串（`implementation-map-check.js` 的 unresolved-marker 断言） | ✅（V1.3.0 改写的 5 个单元正文只写"待裁决"并指向 `tech-design` 附录 E 的具体条目号，未出现禁用字串，门禁 `errors=[]`） |
| 门禁 `errors=0`、`warnings=0`（无证据路径缺失告警） | ✅ 本轮最后一次执行 `valid=true / entries=19 / errors=[] / warnings=[]` |
| 未通过项 | 无（B-01 属**设计锁定前置**，不由本门禁判定，已在 §0 声明 2 如实披露） |


---

## IM-BUILD-001：Maven 多模块骨架与依赖治理

### Traceability

- FR：`FR-004`、`FR-005`、`FR-006`（进程形态承载；`FR-001~FR-003` 的 ingest 依赖亦在此声明）
- EP：全部入口的进程归属（`EP-001`~`EP-013`）
- 设计章节：`tech-design.md#4.7 TD-006：服务进程形态`（方案 B：Maven 多模块模块化单体，三进程共用 `device-core`）、`tech-design.md#12.1 影响范围`
- 契约 / 数据模型 / ADR：`contracts/api-contract.yaml` 的 `servers` 与 `x-internal-entries`；无 ADR

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `pom.xml` | 根聚合 POM | `packaging=pom`，声明 `<java.version>21</java.version>` + `maven.compiler.release=21`、以 `dependencyManagement` 导入 `spring-boot-dependencies:${spring-boot.version}`（属性固化 **3.5.16**）、`<modules>` 四模块、`maven-compiler-plugin` 与 `maven-surefire-plugin` 配置 | 无既有代码可复用；依据 `java_springboot_specification.md` §依赖治理【强制】"统一由 starter-parent 或 BOM 管理版本，禁止手工钉 Boot 托管依赖版本"。规范基线为"Java 17 / Spring Boot 3.2+"，本实现取 **Java 21 / Boot 3.5.16**（高于基线，属需求方 2026-10-29 明确指定的工具链，非偏离） |
| CREATE | `device-core/pom.xml` | `device-core` | 依赖 `spring-boot-starter-jdbc`、`mybatis-spring-boot-starter`、`spring-boot-starter-data-redis`、`mysql-connector-j`（runtime）、`spring-boot-starter-validation`、`jackson-databind`、`micrometer-core`；**`spring-kafka` 不进 core**（V1.3.1 编码时定稿：core 内不出现任何 `ConsumerRecord`/`Acknowledgment` 等 Kafka 类型，原清单把它列在 core 属死依赖，Kafka 只由 `device-ingest` 直接声明）；另以 **`provided` 作用域**引入 `spring-boot-starter-web`（`IM-CORE-007` 的 `@RestControllerAdvice` / `OncePerRequestFilter` / Jackson 配置在编译期需要 spring-web、spring-webmvc 与 jakarta.servlet 类型）。⚠️ **`flyway-core` 本期不引入**：需求方 2026-10-29 裁决"DDL 不需要迁移"，且附录 E #12 的两种读法（(a) 不产出 `.sql` / (b) 弃用 Flyway 人工执行）对代码的处置**完全一致**——都不落 `db/migration/*.sql`、都不加 Flyway 依赖，因此本行不等 #12 裁决即可定稿。建表脚本由 `data-model.md#6` 作为唯一真源，集成测试用 `src/test/resources/db/test-schema.sql`（**仅测试夹具，不构成生产迁移产物**，见 IM-DB-001） | `tech_design.md#12.4` B-02（评审：不声明即无法编译）；`java_springboot_specification.md` §依赖治理（`provided` 不把 Web 传递给无 MVC 需求的 `device-ingest`，避免误启内嵌容器）；`tech-design` 附录 E #12 |
| CREATE | `device-ingest/pom.xml` | `device-ingest` | 依赖 `device-core`、`spring-boot-starter`（非 web）、`spring-kafka`、`spring-boot-starter-actuator`；`spring-boot-maven-plugin` 打可执行 jar。**本期不引 `resilience4j-spring-boot3`**（V1.3.0 评审 B-03：`tech-design` §9.6 的 `503/5030` 改由"超时 + fail-fast"产生，无熔断组件；且原写法把依赖装在生产者侧、行为却发生在 `device-openapi` 侧，方向也是错的） | `java_springboot_specification.md` §Actuator；§外部调用弹性（超时=【强制】，熔断=【推荐】→ 本期只做【强制】）；`tech_design.md#12.4` 不引入清单 |
| CREATE | `device-openapi/pom.xml` | `device-openapi` | 依赖 `device-core`、`spring-boot-starter-web`、`spring-boot-starter-validation`、`spring-boot-starter-actuator`、`springdoc-openapi-starter-webmvc-ui` | HTTP 规范 §12【强制】OpenAPI 3.x 必须可由代码导出；`java_springboot_specification.md` §Web 层 |
| CREATE | `device-admin/pom.xml` | `device-admin` | 依赖 `device-core`、`spring-boot-starter-web`、`spring-boot-starter-security`、`spring-security-oauth2-resource-server`、`spring-boot-starter-actuator`、`springdoc-openapi-starter-webmvc-ui` | `java_springboot_specification.md` §安全配置 |
| CREATE | `device-core/src/main/java/com/example/device/core/package-info.java` | `com.example.device.core` | 包根约定：`com.example.device.core` / `.ingest` / `.openapi` / `.admin`；禁止跨进程反向依赖 | `java_basic_specification.md` 命名与包结构条款（`java_springboot_specification.md` §Bean 设计【强制】禁循环依赖） |

### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| 模块依赖方向 | `device-ingest`/`device-openapi`/`device-admin` → `device-core`，单向 | 禁止 `device-core` 依赖任一进程模块；禁止三个进程模块互相依赖；禁止 openapi/admin 各自定义实体或 Mapper | `tech-design.md#4.7`："实体与 Mapper 只能定义在 `device-core`，禁止 openapi/admin 各自建模"；§6.3 注："唯一模型、唯一事务入口都在 device-core" |
| 版本管理 | **Java 21（Temurin 21.0.11）+ Maven 3.9.16**；`spring-boot.version=3.5.16` 由根 POM 属性单点声明并 import BOM；非 Boot 托管依赖在根 POM `<properties>` 单点钉版：`mybatis-spring-boot-starter=3.0.5`、`springdoc-openapi-starter-webmvc-ui=2.8.17`、`testcontainers=1.21.4`（BOM import）；`spring-kafka`、`kafka-clients`、`mysql-connector-j`、`spring-security-oauth2-resource-server`、`jackson-*`、`micrometer-*`、`junit-*`、`logback-*` **一律不写版本号**（Boot 3.5.16 BOM 托管：spring-kafka 3.3.x / kafka-clients 3.9.x / security 6.5.x） | 禁止在子模块 POM 里对 Boot 托管依赖手工钉版本；仅在记录 CVE 理由时集中覆盖 BOM 属性（覆盖点只允许出现在根 POM） | `java_springboot_specification.md` 基线声明（"Java 17 / Spring Boot 3.2+"，本实现高于基线）与 §依赖治理【强制】 |
| 密钥与配置 | 数据源、Redis、Kafka、JWT 公钥、AppKey Secret 一律外部化（环境变量或 config tree 文件），profile 用 `spring.config.activate.on-profile`。AppKey Secret 走 `${device.openapi.app-secret-path}` 挂载文件，文件内为 `appKey → [当前, 上一版]` 双值（轮换窗口内双活，`tech-design.md#12.4`） | 禁止把任何密钥写进 `application.yaml` 或代码；禁止 `application-prod.yaml` 提交明文连接串；禁止只支持单 secret（轮换当天调用方全量 `401/4010`） | `java_springboot_specification.md` §配置【强制】密钥外部化；`tech_design.md#12.4` 密钥表 |
| 命名 | 配置键 kebab-case（`device.ingest.retry-budget-ms` 等） | 禁止 camelCase / 下划线配置键 | 与 `tech-design.md#11.1`、`data-model/data-model.md` 已统一的键集合一致 |

### Implementation sketch

```text
device_management (repo root)
├── pom.xml                    packaging=pom, BOM import, <modules>
├── device-core/               库 jar：实体 + Mapper + DimSpec + Applier + Cache + 读服务 + Web 公共件
├── device-ingest/             可执行：EP-006 消费 + EP-007/008 定时 + EP-012 加载
├── device-openapi/            可执行：EP-001/002
└── device-admin/              可执行：EP-003~011
依赖方向：ingest / openapi / admin ──▶ core（单向，编译期强制）
```

### Control flow and rules

```text
1. 根 POM 只做聚合与依赖管理，不写业务依赖。
2. device-core 编译产物为普通 jar，不装配 spring-boot-maven-plugin repackage。
3. 三个进程模块各自持有 Application 类与 application.yaml，各自独立可执行。
4. core 的 Web 公共件（响应外壳、异常处理、trace_id 过滤器、Jackson 配置）由 `spring-boot-starter-web` 以 **`provided` 作用域**提供编译期类型：`provided` 不传递给 `device-ingest`，因此 ingest 进程仍不会拉起内嵌容器；openapi/admin 各自以 compile 引入同一 starter，运行时类型齐备。禁止改成 compile（会让 ingest 误启 Servlet 容器）。
5. 任何在 openapi/admin 内新建实体类的改动都属于禁止项，由代码评审与 IM-CORE-002 的"唯一 Mapper"约束共同拦截。
```

### Tests

| 场景 | 断言 | 类型 |
|---|---|---|
| 依赖方向 | `mvn -q dependency:tree` 中 `device-core` 不出现 `device-ingest/openapi/admin`；`jdeps` 无环 | build |
| 版本治理 | 断言无 Boot 托管依赖被手工钉版本（`mvn versions:display-dependency-updates` 与依赖树人工比对固化为 CI 步骤） | build |
| 三进程可启动 | 每个进程 `contextLoads()` 通过；core 的 `provided` 依赖不传递，故 ingest 上下文仍不产生 `WebApplicationType.SERVLET` | unit（SpringBootTest slice） |
| core Web 件可编译 | `mvn -q -pl device-core compile` 通过（专门守 V1.3.0 评审 B-02：`GlobalExceptionHandler`/`TraceIdFilter` 在缺 `provided` starter-web 时编译失败） | build |
| core 无 Controller | 扫描 `device-core` 编译产物，断言不存在带 `@RestController` 的类 | unit |

### Evidence

- `java_springboot_specification.md` §依赖治理【强制】、§配置【强制】、§Actuator、§测试分层：本单元唯一约束来源，规范条款可逐条 diff 校验。
- `tech-design.md#4.7 TD-006`：进程形态选型结论与"共用同一 MySQL/Redis、写侧删缓存直达同一 key 空间"的前提。
- `tech-design.md#12.1`：四模块均为"新增"，无既有代码落点。

### Blockers

- none

---

## IM-DB-001：表结构交付与初始化数据（交付形态待裁决：`tech-design` 附录 E #12）

### Traceability

- FR：`FR-001`、`FR-002`、`FR-006`
- EP：`EP-006`（消费写库前置）、`EP-012`（授权表数据前置）、`EP-008`（分区预建）
- 设计章节：`data-model/data-model.md#9.1 迁移必要性评估`、`#9.2 初始化执行顺序`、`tech-design.md#12.1`、`tech-design.md` 附录 C（Spring Boot §迁移【强制】行）与附录 E #12
- 契约 / 数据模型 / ADR：`data-model/data-model.md#6.1~6.5`（5 张表 DDL 全文）

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `device-core/src/main/resources/db/migration/V1__device_init_tables.sql` | `device_info` / `device_info_change_record` / `mq_consume_record` / `mq_dead_letter` | **本期不产出（已定稿，不再等附录 E #12）**：需求方 2026-10-29 裁决"DDL 不需要迁移"，#12 的两种读法（(a) 不产出 `.sql` / (b) 弃用 Flyway 人工执行）对代码的处置一致——不落 `db/migration/*.sql`、不引 `flyway-core`。建表 DDL 的唯一真源保持为 `data-model.md#6.1~6.4`，由运维在上线时按该节人工执行；`tech-design` §12.1 与附录 C 相应改述为"不启用版本化迁移 + 豁免留痕" | `data-model.md#6.1~6.4` 为唯一真源，禁止在脚本里改列定义 |
| CREATE | `device-core/src/main/resources/db/migration/V2__device_dim_source_auth.sql` | `device_dim_source_auth` + 初始授权 INSERT | **本期同样不产出**。授权表建表 + 初始授权（`FACTORY/ONLINE/BIND/RECOVER` 各配占位 source、`PROFILE/MAINTAIN` 配 `ADMIN`）由运维按 `data-model.md#6.5` + §8.4 人工执行。⚠️ **人工形态不改变"授权数据必须有落库路径"这条硬要求**：占位 source 的发布门禁（下表）随之挂在人工脚本的执行记录上，不能因为不走 Flyway 就丢掉 | `data-model.md#6.5` 与 §9.1"配置数据初始化：是"；§8.4 默认授权来源列 |
| CREATE | `device-core/src/test/resources/db/test-schema.sql` | 集成测试夹具（**不是生产迁移产物**） | 供 Testcontainers MySQL 8 建表用，内容逐字取自 `data-model.md#6.1~6.5`（含 `device_info_change_record` 的 `PARTITION BY RANGE(TO_DAYS(create_time))` 与 `p202610/p202611/p202612/pmax`、`mq_consume_record` 不分区、`uk_source_msg`）+ 授权表初始行的**测试替身**（把占位 source 换成测试用固定串）。放置路径在 `src/test/resources` 且首行注释声明"仅测试夹具，生产 DDL 以 `data-model.md#6` 为准"，因此不构成本期对"DDL 不需要迁移"的违背 | `java_springboot_specification.md` §测试分层【强制】：结构类断言必须用真实 MySQL；`tech-design.md#13` 禁 H2 的理由（分区 / `ON UPDATE CURRENT_TIMESTAMP` / JSON 表达式默认值 / 唯一索引冲突语义） |
| CREATE | `device-core/src/test/resources/application-test.yaml` | 测试数据源 | 供 Testcontainers MySQL 8 与 Redis/Kafka 注入；禁用 H2；**不含任何 `spring.flyway.*` 键**（本期无 Flyway 依赖，写入即虚报） | `java_springboot_specification.md` §测试分层【强制】：结构类断言必须 Testcontainers |

### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| 无版本化迁移（本期确定，2026-10-29 需求方裁决"DDL 不需要迁移"） | 代码里**不得出现** `flyway-core` 依赖与任何 `spring.flyway.*` 配置键；表结构由运维按 `data-model.md#6` 人工执行。因此 Spring Boot 规范 §迁移【强制】在本项目属**有意偏离 + 豁免留痕**，`tech-design` 附录 C 同行已按此改述 | 禁止把"启用 Flyway"当作既定事实写进任何配置或文档；禁止在应用启动路径上做任何 DDL 自愈（如"表不存在就建表"） | `tech-design` 附录 C（Spring Boot §迁移行）与附录 E #12；`data-model.md#9.1` |
| 人工执行形态（本期采用） | DDL 文本**逐字取自** `data-model.md#6`，运维执行后把执行记录（实例、时间、执行人、脚本摘要）回写本单元；核心禁止项"禁止人工在库里改结构"仍成立——人工只允许执行**已评审的全文脚本**，不允许临场改列 | 禁止把散落的 `ALTER` 当初始化手段；禁止跳过 §9.2 步 5 的分区预建与步 6 的授权数据 | `data-model.md#9.2`、`#9.3` 回滚预案 |
| MySQL 分区 | `device_info_change_record` 用 `PARTITION BY RANGE (TO_DAYS(create_time))`，`PRIMARY KEY (id, create_time)`；首季分区随建表脚本一起落（人工脚本与 `test-schema.sql` 同形） | 禁止用 `PRIMARY KEY(id)` 单列（分区表要求分区键进主键）；`mq_consume_record` **不分区** | `data-model.md#6.2`、`#8.3`（不分区原因） |
| JSON 列 | `ext_json JSON NOT NULL DEFAULT (JSON_OBJECT())` 需 MySQL ≥ 8.0.13；DDL 文本首行注释声明该版本下限，`test-schema.sql` 同样声明 | 低于 8.0.13 时改用 `JSON NULL` 并在评审记录登记例外 | `data-model.md#6.1` 注记 1 |
| 授权表发布门禁 | `source` 占位值必须写成可被 CI 识别的哨兵串（如 `PLACEHOLDER_SOURCE`），CI 断言授权 INSERT 中不存在该串才允许发布 | 禁止把占位 source 带进生产：否则全部消息 `result=4`（未授权）而无人报警 | `data-model.md#9.2` 步 6、`tech-design.md#6.5` 上线前置检查项 |

### Implementation sketch

```sql
-- test-schema.sql / 人工建表脚本（结构以 data-model.md#6.1~6.4 为唯一真源，此处仅示意分区与主键形态）
-- 需要 MySQL >= 8.0.13（ext_json 的 JSON 表达式默认值）
CREATE TABLE `device_info` ( `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `sn` VARCHAR(64) NOT NULL DEFAULT '',
  /* ...22 列，逐列照搬 §6.1... */ `version` INT UNSIGNED NOT NULL DEFAULT 0, `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_sn` (`sn`), KEY `idx_update_time` (`update_time`) );

CREATE TABLE `device_info_change_record` ( `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  /* ... */ PRIMARY KEY (`id`, `create_time`) )
  PARTITION BY RANGE (TO_DAYS(`create_time`)) (
    PARTITION p202610 VALUES LESS THAN (TO_DAYS('2026-11-01')),
    PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
    PARTITION p202612 VALUES LESS THAN (TO_DAYS('2027-01-01')),
    PARTITION pmax    VALUES LESS THAN MAXVALUE );

-- 授权表初始数据：生产由运维按 data-model.md#6.5 人工写入（占位 source 需先替换），测试夹具写测试替身
INSERT INTO `device_dim_source_auth` (`dim_type`, `source`, `enabled`) VALUES ('FACTORY', 'PLACEHOLDER_SOURCE', 1);
```

### Control flow and rules

```text
1. 应用启动**不执行任何 DDL**：无 Flyway、无 ddl-auto；表不存在时连接池能用，但首条 SQL 报错即暴露（这是本期刻意的"运维前置"形态）。
2. 人工脚本 step 顺序照 `data-model.md#9.2`：建表 → 首季分区 → 授权表建表 + 初始授权 → 三进程发布。
3. 次月分区由 EP-008 动态 `ALTER TABLE ... ADD PARTITION` 维护（见 IM-INGEST-004），不在人工脚本里预排多年——预排会在跨年时与实际运维节奏脱节。
4. 集成测试用 `test-schema.sql` 在 Testcontainers MySQL 8 里建同构表；该文件与人工脚本同源于 `data-model.md#6`，出现差异即视为缺陷。
5. 授权表为空（或未替换占位 source）时消费侧仍正常工作，结果全部 `result=4`；该风险由发布门禁消除，不靠代码兜底。
```

### Tests

| 场景 | 断言 | 类型 |
|---|---|---|
| 空库建表后结构一致 | Testcontainers MySQL 8 起干净实例 → 执行 `test-schema.sql` → `information_schema` 中 5 张表的列名/类型/默认值/索引与 `data-model.md#6.1~6.5` 逐列一致 | integration |
| 分区形态 | 断言 `device_info_change_record` 的 `CREATE TABLE` 含 `PARTITION BY RANGE`、含 `p202610/p202611/p202612/pmax`，且主键为 `(id, create_time)`；`mq_consume_record` 无分区 | integration |
| 重复执行幂等 | 二次 `migrate` 无新变更、`validate` 通过 | integration |
| 占位 source 门禁 | CI 脚本 grep `V2__*.sql` 含 `PLACEHOLDER_SOURCE` 即构建失败 | build |

### Evidence

- `data-model.md#6.1~6.5`：全部 DDL 原文（本单元不新增任何列定义）。
- `java_springboot_specification.md` §迁移【强制】：`tech-design.md#12.2` 明确记录"原稿把 DDL 当人工执行 checklist 交付不合规"，本单元即为该修复的落点。
- `data-model.md#9.2`：10 步初始化顺序与发布门禁，本单元的脚本拆分形态（V1/V2）直接来自该节。

### Blockers

- none

---

## IM-CORE-001：维度白名单单一真源与命令 / 结果模型

### Traceability

- FR：`FR-001`、`FR-003`（维度正交与乐观锁的模型基础）
- EP：`EP-006`、`EP-012`，并被 `EP-005`/`EP-011` 复用
- 设计章节：`data-model.md#8.4 msg_type → dim_type → 可写列 映射（写入的唯一真源）`、`tech-design.md#9.3 R10 R14`、`tech-design.md#7.3 全链路数据变换追踪（EP-006）`
- 契约 / 数据模型 / ADR：`contracts/api-contract.yaml#x-kafka-message-contracts`（envelope schema）；`data-model.md#7.1`（对外白名单列集合）

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `device-core/src/main/java/com/example/device/core/dim/DimType.java` | `DimType`（enum：`FACTORY, ONLINE, BIND, RECOVER, PROFILE, MAINTAIN`） | 维度枚举，值与 `device_info_change_record.dim_type`、`device_dim_source_auth.dim_type` 字面一致 | `data-model.md#6.3` 字段表 `dim_type`、§8.4 表首列 |
| CREATE | `device-core/src/main/java/com/example/device/core/dim/MsgType.java` | `MsgType`（enum：`FACTORY_REGISTER, ONLINE_CHANGE, BIND_CHANGE, RECOVER_CHANGE, PROFILE_CHANGE`） | 与契约 `x-kafka-message-contracts` 的 `msg_type` enum 一一对应；**未知类型不属于枚举值**，解析失败时返回 `Optional.empty()` 而非抛异常 | `data-model.md#8.4` 前 5 行；`tech-design.md#9.3 R11` |
| CREATE | `device-core/src/main/java/com/example/device/core/dim/DimSpec.java` | `DimSpec#of(DimType)` / `#columns()` / `#javaProps()` | 每维度**两张不可变表**：DB 列名白名单（如 `RECOVER → recover_status, scrap_time`）与对应 Java 属性名白名单；提供 `containsColumn(String)`、`rejectUnknownKeys(Set<String>)` | `data-model.md#8.4` 第 3 列"允许写的列（严格白名单）"逐字映射 |
| CREATE | `device-core/src/main/java/com/example/device/core/applier/ApplyCommand.java` | `ApplyCommand`（record） | 字段：`sn, source, msgId, msgType, dimType, bizId, topic, partitionNo, offsetNo, msgKey, columnValues(Map<String,Object> 仅本维度), extJson, opUser` | `tech-design.md#7.3` 步骤 5~7 的数据形态 |
| CREATE | `device-core/src/main/java/com/example/device/core/applier/ApplyOutcome.java` | `ApplyOutcome`（enum：`APPLIED, DUPLICATE, MISSING_DEVICE_RECORDED, UNAUTHORIZED, UNKNOWN_MSG_TYPE, INVALID_PAYLOAD`） | 六种终态，供消费侧决定是否 ack、是否落死信、是否提交位点 | `tech-design.md#9.3 R13`（三种位点终态）＋§9.3 分支图 |
| CREATE | `device-core/src/main/java/com/example/device/core/applier/OptimisticConflictException.java` | `OptimisticConflictException` | CAS 影响行数 0 时抛出，仅在事务边界内传播，由外层重试处理 | `tech-design.md#9.3 R4 R5` |

### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| MyBatis 动态列 | `DimSpec` 是列名**唯一来源**，返回 `List<String>` 常量 | 禁止任何地方把消息 `data` 的 key 当列名/属性名使用；禁止 `${}` 拼列名以外的一切值；列名不能参数化，只能 `#{}` 绑值 | R14；`java_middleware_specification.md` §MyBatis【强制】 |
| 白名单越界处理 | `data` 出现非本维度 key 时**整体拒绝**（`INVALID_PAYLOAD`），不是忽略 | 禁止"忽略未知字段继续写"——那会让"忽略了哪个字段"成为无人知晓的隐式行为 | `data-model.md#8.4` 三条硬规则之 1 |
| 枚举与 SQL 字面一致 | `DimType.name()` == `dim_type` 列值；`MsgType.name()` == 契约 enum 值 | 禁止用中文/小写/缩写作为落库值 | `data-model.md#6.3`、契约 `x-kafka-message-contracts` |
| 常量表不可变 | `DimSpec` 内部用 `Map.copyOf` / `List.copyOf`，无 setter | 禁止运行时按消息内容增删列白名单 | R14 |

### Implementation sketch

```java
public enum DimType { FACTORY, ONLINE, BIND, RECOVER, PROFILE, MAINTAIN }

public final class DimSpec {
    private static final Map<DimType, List<String>> COLUMNS = Map.of(
        DimType.FACTORY,   List.of("factory_code", "factory_name", "produce_time", "register_status"),
        DimType.ONLINE,    List.of("online_status"),
        DimType.BIND,      List.of("bind_status", "bind_target", "bind_time"),
        DimType.RECOVER,   List.of("recover_status", "scrap_time"),
        DimType.PROFILE,   List.of("model_code", "model_name", "remark", "image_url"),
        DimType.MAINTAIN,  List.of("maintain_user", "maintain_time"));

    public static List<String> columnsOf(DimType dim) { return COLUMNS.get(dim); }
    public static boolean isAllowed(DimType dim, String column) { return COLUMNS.get(dim).contains(column); }
}
```

### Control flow and rules

```text
1. 消费/重放/后台三条写路径都先由 msgType 或调用方确定 DimType，再从 DimSpec 取列白名单。
2. 校验阶段：data 的每个 key 必须命中白名单，未命中 → INVALID_PAYLOAD（ACK 判定见 IM-INGEST-002）。
3. PROFILE 与 MAINTAIN 在同一次后台编辑中合并为一次 CAS、一个流水条目，DimSpec 不提供"跨维度合并列"的接口，由 IM-CORE-009 显式拼接两组列。
4. 逻辑删除不属于任何维度：`deleted` 列不在任何 DimSpec 白名单内，只能由 IM-CORE-009 的专用方法写入——这样消费路径不可能意外改 deleted（R6）。
5. 未知 msg_type 在 MsgType 解析阶段返回 empty，不进入 DimSpec，不抛异常（R11）。
```

### Tests

| 场景 | 断言 | 类型 |
|---|---|---|
| 白名单与文档一致 | 反射读取 `DimSpec.COLUMNS`，与 `data-model.md#8.4` 表第 3 列逐行等值（CI 断言，防文档/代码漂移） | unit |
| 越界 key 拒绝 | `data` 含 `online_status` 但 `msgType=BIND_CHANGE` → `INVALID_PAYLOAD`，且没有任何列被写入 | unit |
| deleted 不可达 | 断言六个维度的列集合都不含 `deleted`、`version`、`ext_json`、`id` | unit |
| 枚举与契约一致 | 解析契约 `x-kafka-message-contracts` 的 `msg_type` enum，与 `MsgType.values()` 集合等值 | unit |

### Evidence

- `data-model.md#8.4`：维度 → 列白名单的唯一真源（本单元的 `COLUMNS` 内容逐行来自该表）。
- `tech-design.md#9.3 R10 R11 R14`：分维度更新、未知类型不抛异常、列名只能来自代码枚举。
- `contracts/api-contract.yaml#x-kafka-message-contracts`：`msg_type` 取值集合与"未知类型"的契约位置。

### Blockers

- none

---

## IM-CORE-002：持久层 DO、Mapper 与动态列 CAS SQL

### Traceability

- FR：`FR-001`、`FR-002`、`FR-003`
- EP：`EP-006`、`EP-010`
- 设计章节：`data-model.md#6.1~6.5`（表结构）、`#8.4`（列白名单）、`tech-design.md#9.3 R1 R9 R10 R14`、`#11.2 缺陷与已知风险`
- 契约 / 数据模型 / ADR：`contracts/api-contract.yaml#components.schemas.Device`（读侧列集合）与 `DeviceAdminItem`（后台列集合）

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `device-core/src/main/java/com/example/device/core/entity/DeviceInfoDO.java` | `DeviceInfoDO` | 23 个字段与 `device_info` DDL 逐列对应（含 `id/version/deleted/create_time/update_time`）；时间列用 `LocalDateTime`，`extJson` 用 `String`（不在 DO 层解析 JSON） | `data-model.md#6.1`；契约 `DeviceAdminItem`（23 列） |
| CREATE | `device-core/src/main/java/com/example/device/core/entity/DeviceInfoChangeRecordDO.java` | `DeviceInfoChangeRecordDO` | 流水 DO，含 `sn/source/msgId/msgType/dimType/beforeJson/afterJson/result/opUser/createTime` | `data-model.md#6.2`、§7.2 字段表 |
| CREATE | `device-core/src/main/java/com/example/device/core/entity/MqConsumeRecordDO.java` | `MqConsumeRecordDO` | 幂等记录 DO，含 `msgId/source/bizType/bizId/msgType/topic/status/failReason/consumeTime` | `data-model.md#6.3`、§7.3 字段表 |
| CREATE | `device-core/src/main/java/com/example/device/core/entity/MqDeadLetterDO.java` | `MqDeadLetterDO` | 死信 DO，含 `msgId/source/topic/consumerGroup/partitionNo/offsetNo/msgKey/content/failReason/retryCount/status/handleUser/handleTime` | `data-model.md#6.4`、§7.4 字段表（含本轮补的 `consumer_group`） |
| CREATE | `device-core/src/main/java/com/example/device/core/entity/DeviceDimSourceAuthDO.java` | `DeviceDimSourceAuthDO` | 授权 DO：`dimType/source/enabled` | `data-model.md#6.5` |
| CREATE | `device-core/src/main/java/com/example/device/core/mapper/DeviceInfoMapper.java` | `DeviceInfoMapper#updateDimCas` / `#selectBySn` / `#selectBySnList` / `#insertProfile` / `#markDeletedCas` / `#selectUpdatedAfter` | 五个写方法 + 三个读方法；`updateDimCas` 是**唯一**的维度更新入口 | `tech-design.md#9.2 交互图`、`data-model.md#10 类图` |
| CREATE | `device-core/src/main/resources/mapper/DeviceInfoMapper.xml` | `<update id="updateDimCas">` | `<set>` 内用 `<foreach collection="columns">` 遍历**代码侧传入的** `列名=#{列名}` 结构；追加 `version = version + 1`；`WHERE sn = #{sn} AND version = #{version}`，**不加 `deleted` 过滤**（理由见控制流第 3 步） | R10 R14；`java_middleware_specification.md` §MyBatis【强制】 |
| CREATE | `device-core/src/main/java/com/example/device/core/mapper/DeviceInfoChangeRecordMapper.java` | `#insert` / `#selectBySnPaged` / `#deleteBefore` | 流水写入与分页读；分页走 `idx_sn_create_time` | `data-model.md#6.2` 索引、`tech-design.md#5.2 EP-010` |
| CREATE | `device-core/src/main/java/com/example/device/core/mapper/MqConsumeRecordMapper.java` | `#insert` / `#updateStatus` / `#deleteConsumedBefore` | `insert` 命中 `uk_source_msg` 时由调用方捕获 `DuplicateKeyException` | `data-model.md#6.3`、§8.2 |
| CREATE | `device-core/src/main/java/com/example/device/core/mapper/MqDeadLetterMapper.java` | `#insert` / `#selectPaged` / `#selectById` / `#casUpdateStatus` | `casUpdateStatus(id, from, to, handleUser, handleTime)` 用 `WHERE id=? AND status=?` 保证并发重放只成功一次 | `data-model.md#6.4`、`tech-design.md#5.2 EP-011`（`409/4093`） |
| CREATE | `device-core/src/main/java/com/example/device/core/mapper/DeviceDimSourceAuthMapper.java` | `#selectAllEnabled` | EP-012 周期刷新用，一次全量读（表行数 = 维度 × source，量级 ≤ 数十） | `data-model.md#6.5`、`tech-design.md#5.1 EP-012` |

### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| MyBatis 动态列 | 列名由 `DeviceInfoMapper.updateDimCas(@Param("columns") List<DimColumn> cols, ...)` 传入，`DimColumn` 持有 `column`（取自 `DimSpec`）与 `value`；XML 里 `${column}` **只允许出现在这个受控对象上** | 禁止把外部 map 的 key 直接当 `column`；禁止用 `${}` 拼接值；`DimColumn` 构造时断言 `DimSpec.isAllowed(dim, column)` | R14 + `java_middleware_specification.md` §MyBatis【强制】 |
| CAS 语义 | `WHERE sn = #{sn} AND version = #{version}`，返回受影响行数；`deleted` **不进 WHERE**（逻辑删设备仍接受外部维度更新） | 禁止 `WHERE deleted = 0`（R6）；禁止用 `SELECT ... FOR UPDATE`（并发靠版本，不靠行锁） | R6；`data-model.md#10 设计要点`："两条读方法都不加 FOR UPDATE" |
| 时间哨兵 | `produce_time/bind_time/scrap_time/maintain_time` 的"未同步"= `1970-01-01 00:00:00`，DO 层原样保留，转 `null` 只发生在装配层 | 禁止在 Mapper 层做哨兵转换（会让后台流水 `before_json` 与库值不一致） | `data-model.md#7.1` 字段表"哨兵→null"列 |
| 流水分区 | `device_info_change_record` 插入必须显式带 `create_time`（默认值可用，但删除/`DROP PARTITION` 依赖它） | 禁止更新流水行（只增不改） | `data-model.md#6.2`、§7.2 `result` 行 |
| 连接与事务 | Mapper 全部无 `@Transactional`；事务边界只由 IM-CORE-003 持有 | 禁止在 Mapper 层或 DO 层声明事务；禁止 `open-in-view=true` | `java_springboot_specification.md` §事务只读、§OSIV【强制】 |

### Implementation sketch

```java
public interface DeviceInfoMapper {
    int updateDimCas(@Param("sn") String sn, @Param("version") int version,
                     @Param("dim") String dim, @Param("columns") List<DimColumn> columns);
    int insertProfile(DeviceInfoDO record);              // EP-004：uk_sn 冲突交由调用方判定 409/4090
    int markDeletedCas(@Param("sn") String sn, @Param("version") int version);
    DeviceInfoDO selectBySn(@Param("sn") String sn);      // 含 deleted 行，供消费与后台
    List<DeviceInfoDO> selectBySnList(@Param("sns") List<String> sns); // 仅 deleted=0
    List<DeviceInfoDO> selectUpdatedAfter(@Param("after") LocalDateTime after, @Param("limit") int limit); // EP-007
}
```

```xml
<update id="updateDimCas">
  UPDATE device_info
  <set>
    <foreach collection="columns" item="c" separator=",">${c.column}=#{c.value}</foreach>
    version = version + 1
  </set>
  WHERE sn = #{sn} AND version = #{version}
</update>
```

### Control flow and rules

```text
1. 写侧只有 updateDimCas / insertProfile / markDeletedCas 三个变更入口，其余为只读。
2. updateDimCas 的列集合长度必须等于 DimSpec 该维度列数（或其后子集），且不含 version/deleted/id。
3. 消费路径命中已逻辑删行：不加 deleted 过滤 → 维度列照常更新、deleted 保持 1（R6）；查询侧用 deleted=0 过滤，对外继续不可见。
4. 影响行数 0 一律解释为版本冲突（uk_sn 保证 sn 唯一，不存在"多行命中"歧义）。
5. 流水 INSERT 与主档 UPDATE 在同一事务内，顺序为先 INSERT 幂等记录 → SELECT 取当行快照 → UPDATE → INSERT 流水（R1、§7.3 步骤顺序）。
6. `before_json` 只序列化本次涉及维度的当行列值，不做整行快照，避免流水膨胀。
```

### Tests

| 场景 | 断言 | 类型 |
|---|---|---|
| 维度隔离更新 | 预置两维度均有值 → 更新 `RECOVER` 维度 → 断言 `FACTORY/ONLINE/BIND/PROFILE` 列值字节不变（R10 回归） | integration（Testcontainers MySQL 8） |
| CAS 失败 | `version` 传错 → 影响行数 0 → 主档不变 | integration |
| 逻辑删行仍可更新 | `deleted=1` 行执行 `updateDimCas` → 列更新且 `deleted` 仍为 1（R6） | integration |
| 注入面 | 构造 `DimColumn("online_status = 1, scrap_time = now()")` 之类的字符串 → 断言 `DimColumn` 构造直接抛异常，SQL 不可能收到该值（R14） | unit |
| 唯一键冲突 | 同一 `(source, msg_id)` 二次 INSERT → `DuplicateKeyException` | integration |
| 分区存在性 | `EXPLAIN PARTITIONS` 断言按 `sn + create_time` 的流水查询只命中目标分区 | integration |

### Evidence

- `data-model.md#6.1~6.5`：列名、默认值、索引、注释的唯一真源；DO 字段与之一一对应。
- `data-model.md#8.4`：`updateDimCas` 列白名单来源。
- `tech-design.md#9.3 R1 R6 R10 R14`：顺序、逻辑删行为、分维度更新、注入面四条硬约束。
- `java_middleware_specification.md` §MyBatis：`${}` 使用范围的唯一规范依据（本单元是全文档唯一允许 `${}` 出现列标识符的位置）。

### Blockers

- none

---

## IM-CORE-003：唯一事务入口 `DeviceInfoApplier`

### Traceability

- FR：`FR-001`、`FR-002`、`FR-003`、`FR-006`（后台与重放复用同一入口）
- EP：`EP-005`、`EP-006`、`EP-011`
- 设计章节：`tech-design.md#9.1 核心业务逻辑时序图`、`#9.3 R1~R10`、`#11.2 缺陷 1`（"三个进程不得各自实现更新逻辑"）
- 契约 / 数据模型 / ADR：`contracts/api-contract.yaml#x-kafka-message-contracts`；`data-model.md#8.4` 三条硬规则

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `device-core/src/main/java/com/example/device/core/applier/DeviceInfoApplier.java` | `DeviceInfoApplier#apply(ApplyCommand)` | **全系统唯一**持有写事务的组件；`@Transactional(rollbackFor = Exception.class, timeoutString/timeout)`；返回 `ApplyOutcome` | `tech-design.md#6.3`："唯一持有事务的组件" |
| CREATE | `device-core/src/main/java/com/example/device/core/applier/DeviceRecorder.java` | `#recordApplied` / `#recordMissing` / `#recordUnauthorized` | 流水写入封装，`result` 只落 `1/3/4` | `data-model.md#7.2` `result` 行、附录 B |
| CREATE | `device-core/src/main/java/com/example/device/core/applier/CacheInvalidationRegistry.java` | `#registerSnDelete(String sn)` | 用 `TransactionSynchronizationManager.registerSynchronization(...afterCommit)` 注册缓存删除；无活动事务时直接执行 | R8；`java_springboot_specification.md` §事务【强制】 |
| CREATE | `device-core/src/test/java/com/example/device/core/applier/DeviceInfoApplierTest.java` | `shouldRollbackAllWhenRecordWriteFails` 等 | Testcontainers + 真实 MySQL；禁止 H2 | `java_springboot_specification.md` §测试分层【强制】 |
| CREATE | `device-core/src/test/java/com/example/device/core/applier/TestcontainersMysqlRedis.java` | 共享容器基类 | `@Testcontainers` + `static` 单例容器，供 core 全部集成测试复用 | 同上 |

### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| 事务 | `@Transactional(rollbackFor = Exception.class)`；超时取 `${device.ingest.tx-timeout-sec:3}`；`INSERT mq_consume_record` 必须是事务内第一条写语句 | 禁止事务内调用 Redis/HTTP/发消息（R8）；禁止在事务内 catch `DuplicateKeyException`（会把事务标记 rollback-only）——必须由外层方法捕获（R2） | R1 R2 R8 R9 |
| 幂等 | 唯一索引 `uk_source_msg` 是正确性**唯一依据**；Redis 键只是挡并发的廉价前置 | 禁止"Redis 判定重复就不写库"；禁止把 Redis 键当作幂等记录 | TD-002；`tech-design.md#9.4` |
| 乐观锁 | Applier **只尝试一次 CAS**，冲突即抛 `OptimisticConflictException` 并回滚 | 禁止在 Applier 内部做重试循环（重试在 IM-INGEST-002 的事务外层，R4）；禁止重读 version 后自旋（同快照必然再失败） | R4 R5 |
| 建档 | 仅 `FACTORY_REGISTER`（`FACTORY` 维度）在 SN 不存在时建档；其余维度不建档 → 流水 `result=3` 且**事务正常提交** | 禁止为"初始化"单独写批量 SQL 导入；建档与置位同事务 | `data-model.md#8.4`"SN 不存在时"列、§9.1 |
| 缓存失效 | afterCommit 删 `device:cache:info:{sn}` | 禁止 beforeCommit 删；禁止删后回写值 | R8、`java_middleware_specification.md` §Redis【强制】 |
| 日志 | 入口打 `source/msg_id/msg_type/sn`；判定结果与耗时在返回前打；异常打完整堆栈 | 禁止打印 `ext_json`/`image_url` 之外的敏感明文与整条消息体；禁止逐条刷屏批量场景 | `java_logging_specification.md` §核心流程【强制】、§脱敏；`tech-design.md#9.7` |

### Implementation sketch

```java
@Service
public class DeviceInfoApplier {
    @Transactional(rollbackFor = Exception.class, timeoutString = "${device.ingest.tx-timeout-sec:3}")
    public ApplyOutcome apply(ApplyCommand cmd) {
        consumeRecordMapper.insert(cmd.toConsumeRecord());        // 1. R1：幂等记录最先写，冲突即重复
        DeviceInfoDO current = deviceInfoMapper.selectBySn(cmd.sn()); // 2. 取当行快照（含 deleted 行）
        if (current == null) {
            if (cmd.dimType() != DimType.FACTORY) { recorder.recordMissing(cmd); return ApplyOutcome.MISSING_DEVICE_RECORDED; }
            deviceInfoMapper.insertProfile(cmd.toNewDevice());    // 3. 建档 + 置 register_status 同事务
        } else {
            List<DimColumn> cols = DimColumns.build(cmd.dimType(), cmd.columnValues());
            if (cmd.dimType() == DimType.FACTORY) cols.add(DimColumns.registerStatus());
            int rows = deviceInfoMapper.updateDimCas(cmd.sn(), current.getVersion(), cols);
            if (rows == 0) throw new OptimisticConflictException(cmd.sn());   // 4. R4：外层重试
            recorder.recordApplied(cmd, before, cmd.columnValues());           // 5. result=1
        }
        cacheInvalidation.registerSnDelete(cmd.sn());             // 6. R8：afterCommit 才删
        return ApplyOutcome.APPLIED;
    }
}
```

### Control flow and rules

```text
1. 幂等判定：INSERT 成功继续；DuplicateKeyException 由外层捕获 → DUPLICATE（不再进入本方法）。
2. 主档不存在且维度非 FACTORY：写 result=3 流水，正常提交，返回 MISSING_DEVICE_RECORDED（外层 ack + 提交位点）。
3. 主档不存在且维度为 FACTORY：insertProfile（sn + FACTORY 列 + register_status=1 + 其余维度保持默认值）。
4. 主档存在：按 DimSpec 组列 → updateDimCas；rows=0 → 抛 OptimisticConflictException → 全事务回滚（幂等记录一并回滚，重投可再次尝试）。
5. 成功路径必须在返回前注册 afterCommit 缓存删除；注册动作本身不产生 Redis 调用。
6. 逻辑删设备（deleted=1）走与正常行相同的更新分支，不改 deleted（R6）。
7. 任何分支都不得读取或写入 `ext_json` 以外的未白名单列；`ext_json` 存在时随本维度整体替换。
```

### Tests

| 场景 | 断言 | 覆盖用例 |
|---|---|---|
| 正常更新 | 主档列变化、`version+1`、幂等记录 `status=1`、流水 `result=1`、缓存 key 被删、位点可提交 | TC-006-01 |
| 流水写入注入异常 | 幂等记录 + 主档 + 流水**全部回滚**，无脏留痕 | TC-006-06（R1+R9） |
| 主档不存在（非 FACTORY） | 流水 `result=3`、主档无新行、事务提交、返回 MISSING_DEVICE_RECORDED | TC-006-07 |
| FACTORY 建档 | 新行 `register_status=1`、四个状态列初值符合 §5.2 默认、时间列为哨兵 | TC-006-01 变体 |
| CAS 冲突 | 抛 `OptimisticConflictException`、幂等记录被回滚（重投仍可处理）、缓存 key **未被删除** | TC-006-05 前置 |
| 逻辑删后重推 | `deleted` 保持 1、维度列更新、流水 `result=1` | TC-006-12 |

### Evidence

- `tech-design.md#9.1` EP-006 时序图：本单元方法体步骤与图中 `AP`/`DI`/`REC`/`RD` 交互逐一对应。
- `tech-design.md#9.3 R1~R9`：事务、幂等、回滚、缓存时机八条硬约束，是本单元控制流的直接来源。
- `data-model.md#8.4`：建档规则与"一条消息只碰一个维度"。

### Blockers

- none

---

## IM-CORE-004：维度授权守卫与 EP-012 配置加载

### Traceability

- FR：`FR-001`
- EP：`EP-006`、`EP-012`，并被 `EP-004`/`EP-005`/`EP-009`/`EP-011` 复用
- 设计章节：`tech-design.md#5.1 EP-012`、`#6.3`（红框"乱序缺口下的核心防线"）、`#9.3 R11`、`data-model.md#6.5`
- 契约 / 数据模型 / ADR：`data-model.md#8.4` 默认授权来源列；契约 `x-open-questions` 的 `source` 编码占位

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `device-core/src/main/java/com/example/device/core/auth/DimAuthGuard.java` | `#check(DimType, String source)` → `Decision`（`ALLOW` / `DENY`） | 纯判定，不写库；内存 Map 查找 | `tech-design.md#7.3` 步骤 4 |
| CREATE | `device-core/src/main/java/com/example/device/core/auth/DimAuthCache.java` | `#refresh()` / `#snapshot()` | `volatile Map<DimType, Set<String>>`，启动加载 + `@Scheduled(fixedDelayString = "${device.dim.auth-refresh-seconds:60}000")` 全量刷新 | `tech-design.md#5.1 EP-012`："进程启动 + 周期刷新" |
| CREATE | `device-core/src/main/java/com/example/device/core/auth/UnauthenticatedRefreshedException.java` | — | 授权表读取失败时保留上一份快照并 WARN，不阻断消费 | `java_springboot_specification.md` §外部调用弹性（配置类读失败降级） |
| CREATE | `device-core/src/main/java/com/example/device/core/metrics/DeviceIngestMetrics.java` | `unauthorizedSourceCounter(dim, source)` / `unknownMsgTypeCounter(msgType)` / `casConflictTimer()` | Micrometer 计数器，命名 `device_ingest_*` | `tech-design.md#9.3 R11`、`#11.1` 冲突率/lag 告警口径 |

### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| 授权判定 | `source` 必须**显式存在**于该维度的 enabled 集合才放行；`ADMIN` 走同一张表，无代码特权 | 禁止"表里查不到就当允许"的默认放行；禁止用 source 前缀/模糊匹配 | `data-model.md#6.5`、§9.1"配置数据初始化：必须有数据才能开始消费" |
| 刷新失败 | DB 不可用时继续用上一份内存快照 + WARN + 指标；首次启动即失败则启动失败 | 禁止每次消费都查库（放大 DB QPS）；禁止用 `null` 快照 | `java_springboot_specification.md` §外部调用弹性【强制】超时与降级 |
| 拒绝处置 | 未授权：事务**外**计数 + WARN + ack，**不写流水**；`result=4` 只用于"授权缺失但已进事务"的既有语义（见控制流第 3 步） | 禁止把未授权当异常抛出（会无限重投）；禁止重试 | R11 同源逻辑、`tech-design.md#9.3` 分支图 D1 节点"事务外，不留痕" |
| 多实例 | 各实例独立加载，允许 ≤ 刷新周期的授权不一致 | 禁止引入配置中心订阅（本期无该组件）；上线初期变更授权需知会窗口 | `tech-design.md#12.1`：无配置中心依赖 |

### Implementation sketch

```java
@Component
public class DimAuthCache {
    private volatile Map<DimType, Set<String>> enabled = Map.of();
    @Scheduled(fixedDelayString = "${device.dim.auth-refresh-seconds:60}s")
    public void refresh() { this.enabled = load(); }   // load 失败保留旧值 + WARN
}

@Component
public class DimAuthGuard {
    public Decision check(DimType dim, String source) {
        return authCache.snapshot().getOrDefault(dim, Set.of()).contains(source) ? ALLOW : DENY;
    }
}
```

### Control flow and rules

```text
1. 消费侧：EnvelopeValidator 通过 → DimAuthGuard.check → DENY 时计数 + WARN + ack（不进事务、不写流水）。
2. 后台侧：同一 check，但 DENY 抛业务异常映射为 403/4031（R5：策略不同）。
3. `result=4` 的产生条件：授权在事务开始前已判定（本单元），因此消费路径正常情况下不会产生 result=4；该编码保留给"重放时授权已变化"与历史兼容，编码含义在 data-model 附录 B，不新增触发点。
4. 死信重放必须重新过 check，不复用当初的授权结论。
5. 快照结构：`DimType → Set<source>`；`enabled=0` 的行不进入 Set。
```

### Tests

| 场景 | 断言 | 覆盖用例 |
|---|---|---|
| 未授权来源 | `source=UNKNOWN_SYS` 写 ONLINE → DENY；主档不变、计数 +1、无流水、消息被 ack | TC-006-08 |
| 授权缺失全表空 | 授权表清空后消费 → 全部 DENY 且 ack，Topic 不积压 | §6.5 上线检查反向 |
| 刷新生效 | 插入授权行 → 等待一个刷新周期 → 同一消息从 DENY 变 ALLOW | EP-012 unit |
| DB 不可用降级 | 容器停 MySQL 后 refresh 抛异常 → 快照保持上一次内容，消费继续 | integration |
| 后台越权维度 | `ADMIN` 未获 ONLINE 授权时 `PUT` 含在线列 → 403/4031 | TC-005-03 |

### Evidence

- `tech-design.md#6.3`：`DimAuthGuard` 定位为"乱序缺口下的核心防线"，是 R10 之外第二道防错位手段。
- `data-model.md#6.5` + §8.4"默认授权来源"：表结构与初始授权口径。
- `tech-design.md#9.3` 分支图 `D1`：未授权处置为"计数 + WARN → ack（事务外，不留痕）"。

### Blockers

- none

---

## IM-CORE-005：Redis 缓存组件与提交后失效

### Traceability

- FR：`FR-005`
- EP：`EP-001`、`EP-002`、`EP-006`、`EP-007`，失效动作被 `EP-004`/`EP-005`/`EP-009` 复用
- 设计章节：`tech-design.md#9.4 缓存设计`、`#4.6 TD-005`、`#9.1 EP-001 时序图`
- 契约 / 数据模型 / ADR：`contracts/api-contract.yaml#components.schemas.Device`（15 列）+ 缓存 Value 多一个 `update_time`（16 key）

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `device-core/src/main/java/com/example/device/core/cache/CacheKeys.java` | `infoKey(sn)` / `dedupKey(source, msgId)` / `rebuildLockKey(sn)` / `cronLockKey(job)` | 集中生成四段式 key，禁止散落字符串拼接 | `java_middleware_specification.md` §Redis【强制】Key 命名 `业务域:模块:实体:ID`；`tech-design.md#9.4` |
| CREATE | `device-core/src/main/java/com/example/device/core/cache/DeviceInfoCache.java` | `#get(sn)` / `#getMulti(sns)` / `#put(value, ttl)` / `#putEmpty(sn)` / `#evict(sn)` / `#evictMulti(sns)` / `#tryRebuildLock(sn)` | 读写全部经此类；Value = 16 key JSON；哨兵常量 `__EMPTY__` | `tech-design.md#9.4` 结构 = String 整条 JSON |
| CREATE | `device-core/src/main/java/com/example/device/core/cache/DeviceCacheValue.java` | record：15 个对外字段 + `updateTime` | 与契约 `Device` 的字段集合 + `update_time` 严格相等（不变式） | `tech-design.md#9.4` Value 行；契约 `Device`（15 列 required） |
| CREATE | `device-core/src/main/java/com/example/device/core/cache/CacheProperties.java` | `@ConfigurationProperties(prefix = "device.cache")` | 类型安全绑定 `device.cache.ttl-base`、`device.cache.ttl-jitter`、`device.cache.empty-ttl-seconds`、`device.cache.reconcile.batch-size`、`device.cache.reconcile.cron` | `java_springboot_specification.md` §类型安全配置绑定【强制】 |

### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| 写模式 | Cache-Aside：先更 DB，afterCommit `DEL`；删除而非更新 | 禁止先删缓存后写库；禁止写库成功后再 SET 新值（会与并发写竞争出旧值） | `java_middleware_specification.md` §Redis §2.2【强制】；`tech-design.md#9.4` |
| TTL | `ttl-base + random(0..ttl-jitter)`；空值 `device.cache.empty-ttl-seconds` | 禁止无 TTL 的 key；禁止固定 TTL（雪崩） | §Redis §2.3/§2.4【强制】 |
| 击穿 | `SETNX device:lock:cache:rebuild:{sn} EX 3`；未抢到 → 自旋 `≤ ${device.cache.rebuild-spin-times:3}` 次短睡 → 仍无值则降级直查 DB 且**不回填** | 禁止抢到锁后不自旋直接查库（击穿保护失效）；禁止降级路径回填 | `tech-design.md#9.1` EP-001 时序图 else 分支、§9.4 击穿行 |
| 幂等键 | `mq:dedup:{source}:{msg_id}`，TTL `${mq.dedup-ttl-hours:24}`；回滚路径必须 `DEL`（由 IM-INGEST-002 负责调用） | 禁止把幂等键当正确性依据；禁止键无 TTL | `java_middleware_specification.md` §Redis、MQ 规范 §3.1；R3 |
| 批量 | 读用 `MGET`、回填用 Pipeline；一次批量上限 `${device.query.batch-max:100}` | 禁止循环 `GET`/`SET`；禁止 `KEYS`/`FLUSHDB`（清理用 `SCAN`） | §Redis §1.4/§2.4【强制】；TC-002-03 |
| 大 Key | 单 Value 估算 < 2KB | 禁止把 `ext_json`、`maintain_*`、`version`、`deleted` 放进 Value | `tech-design.md#9.4` 大 Key 行、§9.5 |
| Redis 不可用 | 读侧超时/连接失败后直接查库（不回填）；DB 也失败 → 抛 `BizException(5030)`。写侧删除失败仅 WARN（TTL + 对账兜底） | 禁止因 Redis 宕机让消费线程抛异常无限重投；禁止无超时的远程调用 | `tech-design.md#11.1` 超时行、`#11.2`、`#9.4` 一致性口径 |

### Implementation sketch

```java
public class DeviceInfoCache {
    public Optional<CacheResult> get(String sn) {              // HIT / EMPTY_SENTINEL / MISS
        String raw = redis.opsForValue().get(CacheKeys.infoKey(sn));
        ...
    }
    public void put(String sn, DeviceCacheValue v) {
        long ttl = props.ttlBase() + ThreadLocalRandom.current().nextLong(props.ttlJitter());
        redis.opsForValue().set(CacheKeys.infoKey(sn), json(v), Duration.ofSeconds(ttl));
    }
    public boolean tryRebuildLock(String sn) {
        return Boolean.TRUE.equals(redis.opsForValue()
            .setIfAbsent(CacheKeys.rebuildLockKey(sn), "1", Duration.ofSeconds(3)));
    }
}
```

### Control flow and rules

```text
1. 读：GET → 命中正常值直接返回；命中 `__EMPTY__` 返回"确认不存在"（不回源）；MISS 走重建锁分支。
2. 重建：抢到锁 → 回源 `selectBySn`（`deleted=0`）→ 不存在写 `__EMPTY__`（短 TTL），存在写 JSON（抖动 TTL）→ 释放锁。
3. 未抢到锁：自旋 GET 若干次；超时后降级直查 DB 且不回填，避免用回源快照覆盖正在重建者的值。
4. 写侧失效：只调 `evict(sn)`，且由 Applier 的 afterCommit 注册触发；批量失效用一次 Pipeline `DEL`。
5. 哨兵清理：EP-004 新建 SN 成功后必须 `evict(sn)`，清除可能存在的 `__EMPTY__`（否则新设备对外 404 最长 60s）。
6. 批量读：`MGET` → 命中装配、`__EMPTY__` 归 `missing`、miss 集合一次 `WHERE sn IN (...)` 回源 → Pipeline 回填 + 缺失写哨兵。
```

### Tests

| 场景 | 断言 | 覆盖用例 |
|---|---|---|
| 命中不回源 | 预置缓存 → 查询 → 断言执行了 0 条 SQL | TC-001-01 |
| 穿透防护 | 不存在 SN 连查 2 次：第 1 次回源写哨兵，第 2 次不回源 | TC-001-03 |
| 击穿 | 20 线程同查一个 miss key → 回源 SQL 恰好 1 条 | TC-001-05 |
| TTL 抖动 | 100 次 `put` 的 TTL 值落在 `[base, base+jitter]` 且不全等 | unit |
| 批量命令数 | 100 条查询 → Redis 命令数 = 1 次 `MGET` + 1 次 Pipeline；SQL 条数 = 1 | TC-002-03 |
| 哨兵残留清除 | 先查不存在 SN（留哨兵）→ 后台新建该 SN → 立即查询返回 200 | TC-004-03 |
| Redis 宕机 | 停 Redis → 查询降级直查 DB 成功且不回填；消费侧 `evict` 失败仅 WARN，数据仍落库 | TC-006-15 |

### Evidence

- `tech-design.md#9.4`：Key/TTL/哨兵/击穿/雪崩/大 Key/幂等键/不做项的完整取值表，本单元逐条落地。
- `tech-design.md#9.1` EP-001 时序图：重建锁、自旋、降级不回填的分支顺序。
- `contracts/api-contract.yaml#components.schemas.Device`：15 列 required 集合，是 `DeviceCacheValue` 的字段来源（+1 = `update_time`）。

### Blockers

- none

---

## IM-CORE-006：读侧查询服务与对外白名单装配

### Traceability

- FR：`FR-004`、`FR-005`
- EP：`EP-001`、`EP-002`
- 设计章节：`tech-design.md#5.2 EP-001/EP-002`、`#9.4`、`#9.5 数据可见性与字段裁剪`、`#7.3`（`DeviceInfoPublicAssembler`）
- 契约 / 数据模型 / ADR：`contracts/api-contract.yaml#components.schemas.Device` / `BatchQueryRequest` / `BatchQueryItem` / `BatchQueryData`；`data-model.md#7.1` 对外白名单列

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `device-core/src/main/java/com/example/device/core/query/DeviceInfoQueryService.java` | `#findBySn(sn)` / `#findBatch(sns)` | 只读服务：缓存优先 → 回源 → 装配；**永不写库** | `tech-design.md#9.2`："EP-001/002 只读、不查也不写后台缓存形态" |
| CREATE | `device-core/src/main/java/com/example/device/core/query/PublicAssembler.java` | `#toPublic(DeviceInfoDO)` / `#toCacheValue(DO)` | 白名单裁剪 + 哨兵时间转 `null` + 时间格式（ISO 8601 带时区在响应序列化层，见 IM-CORE-007） | `tech-design.md#7.3` 步骤 [DeviceInfoPublicAssembler]、§9.5 |
| CREATE | `device-core/src/main/java/com/example/device/core/dto/DevicePublicView.java` | 15 字段 record | 字段集合必须与契约 `Device.properties` 等值；不含 `id/version/deleted/maintain_*/ext_json/update_time/create_time` | 契约 `Device`（15 required）；`data-model.md#7.1` 对外白名单列 |
| CREATE | `device-core/src/main/java/com/example/device/core/dto/DeviceAdminItem.java` | 23 字段 record（`DevicePublicView` + 内部列） | 后台列表/详情返回，含 `version`（乐观锁需要） | `tech-design.md#5.2 EP-003`："列表项含 `version`，与对外白名单不同" |
| CREATE | `device-core/src/main/java/com/example/device/core/query/BatchQueryResult.java` | `items` + `missing` | `found=false` 项进 `missing`，不占 `info` | 契约 `BatchQueryData` |

### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| 字段裁剪 | 对外集合与缓存 Value 集合由同一个装配类产出，`DeviceCacheValue` = `DevicePublicView` 字段 ∪ {`update_time`} | 禁止在 Controller 里再做一层字段过滤（会漂移）；禁止把 `update_time` 放进响应 | `tech-design.md#9.4` Value 不变式、§9.5 |
| 哨兵 | `produce_time/bind_time/scrap_time` 等于 `1970-01-01 00:00:00` → 输出 `null`；`maintain_*` 不对外 | 禁止把哨兵值输出给外部（外部会当成 1970 年生产） | `data-model.md#7.1`"哨兵→null"；TC-001-06 |
| 批量上限 | 请求 `sns.size() > ${device.query.batch-max:100}` → 直接 400/4003 | 禁止服务端自行拆分/分批；禁止去重后静默减少数量而不校验 | `tech-design.md#5.2 EP-002`；TC-002-02 |
| 逻辑删 | 查询侧 `deleted=0` 视为不存在（404/4040），与"从未存在"同样写 `__EMPTY__` | 禁止对外区分"已删除"与"不存在"（泄露内部状态） | `tech-design.md#9.5`；TC-001-04 |
| 响应字段 | 批量项含 `sn/found/info`，`info` 为 `Device` 或 `null`（`oneOf`） | 禁止在 miss 项里返回空对象（与 `found=false` 冗余且歧义） | 契约 `BatchQueryItem` |

### Implementation sketch

```java
public Optional<DevicePublicView> findBySn(String sn) {
    CacheOutcome c = cache.get(sn);
    if (c.isHit())            return Optional.of(assembler.fromCache(c.value()));
    if (c.isEmptySentinel())  return Optional.empty();
    if (!cache.tryRebuildLock(sn)) return Optional.ofNullable(queryWithSpinThenDb(sn)); // 自旋→降级直查，不回填
    try {
        DeviceInfoDO row = mapper.selectBySnVisible(sn);       // WHERE sn=? AND deleted=0
        if (row == null) { cache.putEmpty(sn); return Optional.empty(); }
        DeviceCacheValue v = assembler.toCacheValue(row); cache.put(sn, v);
        return Optional.of(assembler.fromCache(v));
    } finally { cache.releaseRebuildLock(sn); }
}
```

### Control flow and rules

```text
1. 点查：缓存 → 哨兵 → 重建锁 → 回源 → 回填/写哨兵 → 装配 → 返回。
2. 批量：一次 MGET → 命中装配 → `__EMPTY__` 归 missing → miss 集合一次 IN 查询 → Pipeline 回填 + 缺失写哨兵 → 组装 items/missing。
3. 回源查询与后台列表查询使用不同 Mapper 方法：本单元用 `selectBySnVisible`（deleted=0），消费侧用 `selectBySn`（含 deleted）。
4. 任何分支不写库、不发消息、不删缓存（读侧只读）。
5. 未命中与已删除统一返回 Optional.empty()，由 Controller 映射 404/4040。
```

### Tests

| 场景 | 断言 | 覆盖用例 |
|---|---|---|
| 缓存命中 | 200；响应不含 `maintain_user/maintain_time/ext_json/update_time/version/id`；0 条 SQL | TC-001-01 |
| 哨兵时间转换 | 库值 `1970-01-01 00:00:00` → 响应 `produce_time: null` | TC-001-06 |
| miss 回源 | 回源 1 次、回填 TTL 落在抖动区间、数据正确 | TC-001-02 |
| 批量部分命中 | `items[].found` 与 `missing` 集合正确；SQL 恰好 1 条 `IN` | TC-002-01 |
| 批量超限 | 101 条 → 400/4003，无 SQL | TC-002-02 |
| 逻辑删 | `deleted=1` → 404/4040 且缓存写哨兵 | TC-001-04 |
| 契约漂移 | 断言 `DevicePublicView` 字段集合 == 契约 `Device.properties` 键集合（CI） | TC-API-03 |

### Evidence

- `tech-design.md#5.2 EP-001/EP-002`：读侧流程、批量不拆分、`missing` 语义。
- `tech-design.md#9.5`：对外/后台/缓存三套可见列口径。
- `contracts/api-contract.yaml#components.schemas.Device/BatchQueryItem/BatchQueryData`：响应形状与 required 集合。

### Blockers

- none

---

## IM-CORE-007：统一响应外壳、错误码与 `trace_id`

### Traceability

- FR：`FR-004`、`FR-006`
- EP：`EP-001`~`EP-005`、`EP-009`~`EP-011`（全部 HTTP 入口共用）
- 设计章节：`tech-design.md#9.6 统一响应外壳与错误码`、`#附录 C`（ProblemDetail 与统一外壳的规范冲突登记）
- 契约 / 数据模型 / ADR：`contracts/api-contract.yaml#components.schemas.Envelope` / `Error`（17 个 code enum）

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `device-core/src/main/java/com/example/device/core/web/ApiEnvelope.java` | record `{code, message, data, traceId}` | `traceId` 序列化为 `trace_id`（全局 snake_case 命名策略在 Jackson 配置里声明一次） | `tech-design.md#9.6`；契约 `Envelope` |
| CREATE | `device-core/src/main/java/com/example/device/core/web/ErrorCode.java` | enum，17 项：`4001 4002 4003 4004 4010 4011 4030 4031 4040 4050 4090 4091 4092 4093 4290 5000 5030` | 每项携带 `(httpStatus, defaultMsg)`；与 §9.6 表逐行一致 | `tech-design.md#9.6`；契约 `Error.code` enum |
| CREATE | `device-core/src/main/java/com/example/device/core/web/BizException.java` | `BizException(ErrorCode, message)` | 业务拒绝统一异常，供全局处理器映射 | HTTP 规范 §5.1【强制】错误响应必带 `trace_id` |
| CREATE | `device-core/src/main/java/com/example/device/core/web/GlobalExceptionHandler.java` | `@RestControllerAdvice` | 处理 `BizException`、`MethodArgumentNotValidException`、`HttpRequestMethodNotSupportedException`、兜底 `Exception`；**不使用 ProblemDetail**（见 Special 约束） | `tech-design.md#附录 C` 登记的规范冲突裁决：本仓库以 §9.6 统一外壳为准 |
| CREATE | `device-core/src/main/java/com/example/device/core/web/TraceIdFilter.java` | 读 `X-Trace-Id` 或生成 → 写 MDC → 写响应头 `X-Trace-Id` → 请求结束清理 | 响应体 `trace_id` 与头同值 | `tech-design.md#9.6 trace_id 规则`；`java_logging_specification.md`【强制】可独立定位上下文 |
| CREATE | `device-core/src/main/java/com/example/device/core/web/JacksonConfig.java` | `#snakeCaseObjectMapper()` | `PropertyNamingStrategies.SNAKE_CASE` + `JavaTimeModule` + ISO 8601 带时区输出 + 禁用 `WRITE_DATES_AS_TIMESTAMPS` | HTTP 规范 §4.3【强制】时间格式；TC-001-08 |

### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| 响应契约 | 所有 HTTP 2xx/4xx/5xx 均返回 `ApiEnvelope`，**唯一例外是 `EP-009` 的 204 无响应体** | 禁止把 204 改成 `200 + {code:0}`；禁止返回裸 DTO 或 JPA/DO 实体 | `tech-design.md#9.6` 204 例外说明；`java_springboot_specification.md` §Web 层【强制】禁止直接返回实体 |
| 状态码 | HTTP status 表传输层 + `code` 表业务层，**不采用"恒 HTTP 200 + 业务 code"** | 禁止把 409 类冲突压成 400；禁止用 200 表达失败 | `tech-design.md#9.6` 末段、附录 C（与其他项目口径不同，以本仓库 HTTP 规范 §3.2 为准） |
| ProblemDetail | 本期显式不启用 `spring.mvc.problemdetails.enabled` | 禁止同时输出 ProblemDetail 与统一外壳（两套契约，调用方无法解析）；该冲突已在附录 C 登记，若后续规范反转需走变更流程 | `java_springboot_specification.md` §全局异常与 ProblemDetail vs `tech-design.md#附录 C` |
| `trace_id` | 入站有 `X-Trace-Id` 则沿用，否则生成；同时进 MDC、响应头、响应体 | 禁止在业务代码里手工传 traceId 参数；禁止日志缺失 traceId 字段 | `tech-design.md#9.6` |
| 429 | 必带 `Retry-After`；所有对外响应恒带 `X-RateLimit-Limit/Remaining/Reset` 与 `Cache-Control: no-store` | 禁止依赖 HTTP/CDN 缓存（会让对账窗口不可观测） | `tech-design.md#9.6` 注、附录 C；HTTP 规范 §10.1 |

### Implementation sketch

```java
public enum ErrorCode {
    PARAM_MISSING(4001, 400, "参数缺失"), VERSION_CONFLICT(4091, 409, "数据已被外部更新，请刷新后重试"), /* ...17 项... */;
    final int code; final HttpStatus status; final String message;
}

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BizException.class)
    ResponseEntity<ApiEnvelope<Void>> onBiz(BizException e) {
        return ResponseEntity.status(e.errorCode().status())
            .body(ApiEnvelope.error(e.errorCode(), e.getMessage(), MDC.get("trace_id")));
    }
}
```

### Control flow and rules

```text
1. 请求进入 TraceIdFilter → MDC + 属性；响应写出前回填 header。
2. Controller 只返回 ApiEnvelope 或 204；参数校验失败由 @Valid 触发 → 400/4002。
3. 业务拒绝统一抛 BizException（映射表：4031 未授权维度、4040 不存在、4090 SN 已存在、4091 版本冲突、4092 重放幂等冲突、4093 已处理）。
4. 方法不匹配 → 405/4050（框架异常直接映射，不新增码）。
5. 依赖超时或不可用 → 503/5030（`QueryDeviceService` 捕获 Redis 与 DB 的超时/连接异常后抛 `BizException(5030)`；本期无熔断组件，判定条件只有"这次调用的超时/连接结果"，见 `tech_design.md#12.4` 不引入清单）。
6. 兜底 Exception → 500/5000 + ERROR 完整堆栈，且响应体不回显内部异常信息。
7. 每个 ErrorCode 必须至少有一条可达触发路径（TC-API-04），禁止出现"登记了但代码不可达"或"代码用了但未登记"。
```

### Tests

| 场景 | 断言 | 覆盖用例 |
|---|---|---|
| 错误码集合一致 | `ErrorCode` 枚举值集合 == 契约 `Error.code` enum（CI 断言） | TC-API-03 |
| 204 无体 | `DELETE /api/v1/admin/devices/{sn}` 成功 → 状态 204 且 `Content-Length: 0` | EP-009 |
| trace_id 贯通 | 入站带 `X-Trace-Id` → 响应体与头同值；日志行含同一 traceId | §9.6 |
| 405 可达 | 对 `PUT /api/v1/devices/{sn}` 发起请求 → 405/4050 | TC-API-04 |
| 429 头 | 超配额 → 429/4290 + `Retry-After` + 三个 `X-RateLimit-*` + `Cache-Control: no-store` | TC-001-07 |
| 时间格式 | 任意时间字段输出 ISO 8601 带时区 | TC-001-08 |

### Evidence

- `tech-design.md#9.6`：外壳字段、`trace_id` 三条规则、状态码 × code 全表、429/缓存头/204 三处例外。
- `contracts/api-contract.yaml#components.schemas.Envelope/Error`：机器可读的错误码 enum 与外壳 schema（17 项）。
- `java_springboot_specification.md` §Web 层、§全局异常：Controller 精简与禁返回实体的强制条款。

### Blockers

- none

---

## IM-CORE-008：消息信封契约解析与 `data` 白名单校验

### Traceability

- FR：`FR-001`
- EP：`EP-006`、`EP-011`（死信重放复用同一解析）
- 设计章节：`tech-design.md#5.2` 消息信封字段表、`#7.3 全链路数据变换追踪（EP-006）` 步骤 1~3、`#9.3 R11 R12 R14`
- 契约 / 数据模型 / ADR：`contracts/api-contract.yaml#x-kafka-message-contracts`（envelope schema + 2 个消息示例）；`data-model.md#8.4`

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `device-core/src/main/java/com/example/device/core/contract/MessageEnvelope.java` | record：`source, msgId, msgType, bizId, data(Map<String,Object>), ext(Object)` | 与契约 envelope schema 字段一一对应；`event_time` **不建模为参与判定的字段**，仅在解析时若出现则记入日志 | 契约 `x-kafka-message-contracts.envelope`；`tech-design.md#4.5 TD-004` |
| CREATE | `device-core/src/main/java/com/example/device/core/contract/EnvelopeParser.java` | `#parse(byte[] payload)` → `ParseResult`（`OK` / `MALFORMED` / `OVERSIZE`） | 不可解析时不抛异常，返回 `MALFORMED` 并携带 `UNKNOWN-{partition}-{offset}` 占位标识 | `data-model.md#6.4` `msg_id` 行；R12 |
| CREATE | `device-core/src/main/java/com/example/device/core/contract/EnvelopeValidator.java` | `#validate(MessageEnvelope)` → `ValidatedCommand` 或 `INVALID_PAYLOAD` | 必填校验（`source/msg_id/msg_type/biz_id`）+ SN 长度 ≤ 64 + `data` key 全命中维度白名单 + 值类型/枚举合法性 + `_ext` 单独取出 | `data-model.md#8.4` 三条硬规则；`tech-design.md#7.3` 步骤 2 |
| CREATE | `device-core/src/main/java/com/example/device/core/contract/PayloadLimits.java` | `maxPayloadBytes` = `${device.ingest.max-payload-bytes:262144}` | 超限直接 `OVERSIZE`，不做截断 | `data-model.md#6.4` `content` 行；TC-006-14 |

### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| 类型绑定 | `data` 的 TINYINT 列值只接受 `0/1/2` 范围内的整数；DATETIME 列只接受 ISO 8601 字符串或 epoch millis，转换集中一处 | 禁止把任意 JSON 类型直接塞进 `DimColumn.value`（MyBatis 会静默转成意外类型） | `data-model.md#6.1` 列类型；R14 的"值必须 `#{}` 绑定"前提 |
| 未知字段 | `data` 出现非本维度 key → 整体拒绝 | 禁止忽略；禁止部分写入 | `data-model.md#8.4` 硬规则 1 |
| 未知 `msg_type` | 解析成功但 `MsgType` 查不到 → `UNKNOWN_MSG_TYPE`（**校验阶段拒绝，不落死信**） | 禁止抛异常（卡分区）；禁止落死信（仅信封不可解析才落，R12） | `data-model.md#8.4` 末行；R11 |
| `ext_json` | `_ext` 存在时序列化为 JSON 文本随本维度整体替换；不解析其内部结构 | 禁止把 `_ext` 的 key 当列名；禁止用它做筛选；禁止进缓存/对外 | `data-model.md#7.1` `ext_json` 行 |
| 时钟字段 | `event_time` 缺失不影响任何判定；本期不参与水位比较 | 禁止以"消息本地接收时间"冒充事件时间做乱序防护 | `tech-design.md#4.5 TD-004`、§1.1 偏差声明 |

### Implementation sketch

```java
public sealed interface ParseResult permits Parsed, Malformed, Oversize {}

public ValidatedCommand validate(MessageEnvelope env) {
    MsgType msgType = MsgType.find(env.msgType()).orElse(null);
    if (msgType == null) return ValidatedCommand.unknownType(env);      // R11：计数 + ack
    DimType dim = msgType.dim();
    for (String key : env.data().keySet())
        if (!DimSpec.isAllowed(dim, key)) return ValidatedCommand.invalid(env, key); // R14
    return ValidatedCommand.of(env, dim, coerceTypes(dim, env.data()), env.ext());
}
```

### Control flow and rules

```text
1. 尺寸检查（>max-payload-bytes → OVERSIZE → 落死信）。
2. JSON 解析（失败 → MALFORMED → 落死信，msg_id 用 UNKNOWN-{partition}-{offset}）。
3. 必填字段存在性：`source/msg_id/msg_type/biz_id` 任一缺失 → `INVALID_PAYLOAD`，事务外拒绝、不写流水（与"信封不可解析"的区别：后者才落死信）。
4. msg_type 映射（未知 → UNKNOWN_MSG_TYPE）。
5. data key 白名单（越界 → INVALID_PAYLOAD）与值类型转换（转换失败 → INVALID_PAYLOAD）。
6. 校验失败的三类（INVALID_PAYLOAD / UNKNOWN_MSG_TYPE / MALFORMED）中，只有 MALFORMED 与 OVERSIZE 落死信；前两者在事务外计数 + WARN + ack。
7. 通过校验后产出 `ValidatedCommand`，其中已确定 `dimType` 与列值，Applier 不再接触原始 JSON。
```

### Tests

| 场景 | 断言 | 覆盖用例 |
|---|---|---|
| 正常信封 | 产出 `ValidatedCommand`，列值类型正确（`produce_time` → `LocalDateTime`） | TC-006-01 |
| 越界 key | `BIND_CHANGE` 携带 `online_status` → INVALID_PAYLOAD，无库写入 | R14 |
| 未知类型 | `FOO_CHANGE` → UNKNOWN_MSG_TYPE，WARN + 计数，后续消息不积压 | TC-006-09 |
| 不可解析 | 非 JSON 字节流 → MALFORMED，落死信且 `msg_id=UNKNOWN-<p>-<o>`、`content` 原文保存 | R12 |
| 超限 | 300KB 消息 → OVERSIZE，直接落死信，不尝试解析 | TC-006-14 |
| 值域非法 | `online_status=7` → INVALID_PAYLOAD（不落库、不重试） | §9.3 拒绝分支 |
| `_ext` | 存在时整体替换 `ext_json`，不影响其他维度列 | `data-model.md#7.1` |

### Evidence

- `contracts/api-contract.yaml#x-kafka-message-contracts`：envelope schema、必填性、`event_time` 缺口标注、两个消息示例（可直接做测试数据）。
- `tech-design.md#7.3`：EP-006 全链路数据变换步骤 1~3 的输入输出形态。
- `data-model.md#8.4`：`msg_type → dim → 列` 与"越界整体拒绝"的规则原文。

### Blockers

- none

---

## IM-CORE-009：后台写服务（建档 / 编辑 / 逻辑删）

### Traceability

- FR：`FR-003`、`FR-006`
- EP：`EP-004`、`EP-005`、`EP-009`
- 设计章节：`tech-design.md#5.2 EP-004/EP-005/EP-009`、`#9.2`（后台写侧流程）、`#9.3 R5 R6`
- 契约 / 数据模型 / ADR：`contracts/api-contract.yaml` 的 `DeviceCreateCommand` / `DeviceProfileUpdateCommand`；`data-model.md#8.4` 后台三行

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `device-core/src/main/java/com/example/device/core/service/DeviceAdminService.java` | `#create(cmd, operator)` / `#updateProfile(cmd, operator)` / `#delete(sn, version, operator)` | 编排：授权检查 → 组 `ApplyCommand` → 调 `DeviceInfoApplier` → 转 ErrorCode；**自身不开新事务**（事务在 Applier） | `tech-design.md#11.2 缺陷 1`："唯一更新方法在 device-core，任何进程不得绕过" |
| CREATE | `device-core/src/main/java/com/example/device/core/service/IdempotencyKeyGuard.java` | `#runIfNew(key, supplier)` | `Idempotency-Key` 去重：Redis `SETNX device:admin:idem:{key}` + 结果快照 TTL；并发双提交只落一条 | HTTP 规范 §9.1【强制】；TC-004-02 |
| CREATE | `device-core/src/main/java/com/example/device/core/service/AdminCommands.java` | `ProfileUpdate` / `Create` 内部命令模型 | 只暴露 `sn + model_code/model_name/remark/image_url`；不含状态列与时间列 | 契约 `DeviceCreateCommand`（`additionalProperties:false`）；`tech-design.md#5.2 EP-004` |


### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| 入参面 | 建档与编辑请求体只接受 PROFILE 4 列（+ `version`）；状态列/时间列/`maintain_*`/`ext_json` 一律服务端写 | 禁止接受外部指定 `register_status/online_status/bind_status/recover_status/*_time/deleted/version` | `tech-design.md#5.2 EP-004`；契约 `DeviceCreateCommand` |
| 授权 | `create/updateProfile/delete` 分别检查 `(PROFILE, ADMIN)`、`(MAINTAIN, ADMIN)`；逻辑删不属于任何维度，**只由 ADMIN 通道执行**（Mapper 方法级隔离） | 禁止复用消费路径的 source 授权结论；禁止用代码硬编码"ADMIN 永远允许" | `data-model.md#8.4` 后台三行 + `deleted` 行；IM-CORE-004 |
| 冲突策略 | CAS 失败 → 409/4091，**不静默重试**、不重读 version 再提交 | 禁止像消费侧那样退避重试（会用过期编辑内容覆盖外部最新值，R5） | R5；TC-005-02 |
| PROFILE+MAINTAIN | 同一次编辑合并为一次 CAS、一个流水条目（`dim_type=PROFILE`、`op_user=操作人`） | 禁止拆成两次更新（第二次必然版本冲突） | `data-model.md#8.4` 硬规则 3 |
| 逻辑删 | `markDeletedCas` + 流水 + afterCommit `evict`；重复删除（已 `deleted=1`）→ 404/4040 | 禁止返回静默 204；禁止消费路径触达该方法 | `tech-design.md#5.2 EP-009` |
| 缓存 | 三个写操作成功后都要 `evict(sn)`，其中建档是为了清 `__EMPTY__` 哨兵 | 禁止只在编辑时删缓存 | `tech-design.md#5.2 EP-004`、§11.2 缺陷 9；TC-004-03 |
| 操作人 | `maintain_user` 存登录账号拼音（JWT `sub`）；`maintain_time` 服务端取当前时间 | 禁止接受请求体传入操作人；禁止存中文姓名 | `data-model.md#7.1` `maintain_user` 行（需求方裁决） |

### Implementation sketch

```java
public DeviceAdminItem updateProfile(ProfileUpdate cmd, String operator) {
    guard.checkOrThrow(DimType.PROFILE, "ADMIN");     // 未授权 → BizException(4031)
    guard.checkOrThrow(DimType.MAINTAIN, "ADMIN");
    ApplyCommand ac = ApplyCommand.builder()
        .sn(cmd.sn()).dimType(DimType.PROFILE)
        .columns(DimColumns.merge(DimType.PROFILE, cmd.profile(),
                                  DimType.MAINTAIN, Map.of("maintain_user", operator, "maintain_time", now())))
        .version(cmd.version()).source("ADMIN").msgId("ADM-" + uuid()).opUser(operator).build();
    ApplyOutcome r = applier.apply(ac);               // 复用同一事务入口（含幂等记录与流水）
    return switch (r) {
        case APPLIED -> itemOf(ac.sn());
        case DUPLICATE -> throw new BizException(SN_EXISTS_OR_ALREADY_HANDLED);
        default -> throw new BizException(VERSION_CONFLICT);   // 不重试
    };
}
```

### Control flow and rules

```text
1. 建档：Idempotency-Key 去重 → 授权检查 → Applier（FACTORY? 否：走 insertProfile 分支，dim_type=PROFILE 留痕 result=1）→ afterCommit evict → 201 + data。
2. 编辑：授权 → 取当前行（不存在或 deleted=1 → 404/4040）→ 合并 PROFILE+MAINTAIN 列 → 一次 CAS → 流水 → evict → 200 + data.version（新版本，供连续编辑）。
3. 逻辑删：取 version → `markDeletedCas` → 流水 → evict → 204。逻辑删不属于任何维度，因此流水写 `dim_type=''`（该列 `NOT NULL DEFAULT ''`）并把操作类型写进 `msg_type='ADMIN_DELETE'`——不新增列、不新增维度枚举值。
4. 三个写路径的幂等记录都写 `mq_consume_record`（`source=ADMIN`、`biz_type=device`），保证后台操作与外部消息在同一张表可对账。
5. 任何写路径不得直接调用 Mapper 的更新方法（除 `markDeletedCas`，它是维度白名单之外的唯一例外）。
```

> 说明：`dim_type=''` 的形态选择依据是 `data-model.md#8.4` 只规定了"逻辑删除不属于任何维度"，未规定具体值；本文档选定空串 + `msg_type='ADMIN_DELETE'`，不新增列也不发明枚举值。

### Tests

| 场景 | 断言 | 覆盖用例 |
|---|---|---|
| 新建已存在 SN | 409/4090，无流水 | TC-004-01 |
| 无 `Idempotency-Key` 并发双提交 | 并发两请求 → 库中仅一条该 SN 记录 | TC-004-02 |
| 新建前哨兵残留 | 查不存在 SN（写哨兵）→ 建档 → 立即查询 200 | TC-004-03 |
| 编辑仅影响 PROFILE+MAINTAIN | 其他维度列不变；一条流水 `dim_type=PROFILE`、`op_user=操作人` | TC-005-01 |
| `version` 过期 | 409/4091、主档不变、**无重试日志** | TC-005-02 |
| 后台写 ONLINE 列 | 请求体不接受该列（`additionalProperties:false` → 400/4002）；即便放开也 403/4031 | TC-005-03 |
| 逻辑删 | `deleted=1` + 流水 + 缓存删；再查 404/4040；重复删 404/4040 | TC-009-01 |

### Evidence

- `tech-design.md#5.2 EP-004/EP-005/EP-009`：入参面裁剪、状态码、冲突语义、删缓存理由逐条给定。
- `data-model.md#8.4` 后台三行 + 硬规则 3：PROFILE+MAINTAIN 一次 CAS。
- `contracts/api-contract.yaml#DeviceCreateCommand/DeviceProfileUpdateCommand`：请求体字段集合与 `required: [version]`。

### Blockers

- none

---

## IM-CORE-010：死信重放与丢弃

### Traceability

- FR：`FR-001`
- EP：`EP-011`
- 设计章节：`tech-design.md#5.2 EP-011`、`#9.3 R12 R13`、`#13 TC-011-01/02`
- 契约 / 数据模型 / ADR：`contracts/api-contract.yaml` 的 `DeadLetterItem` / `DeadLetterDetail` 与 4 个操作；`data-model.md#6.4`、§7.4

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `device-core/src/main/java/com/example/device/core/service/DeadLetterService.java` | `#page(status, page, pageSize)` / `#detail(id)` / `#replay(id, operator)` / `#discard(id, operator)` | 列表不含 `content`，详情含；重放复用 `EnvelopeParser` + `DeviceInfoApplier` | `tech-design.md#5.2 EP-011`（`content` 只在详情返回） |
| CREATE | `device-core/src/main/java/com/example/device/core/service/DeadLetterStatus.java` | `PENDING(0)` / `REPLAYED(1)` / `DISCARDED(2)` | 与 DDL `status` 注释一致 | `data-model.md#6.4` `status` 行 |

### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| 重放一致性 | 重放必须走与消费**完全相同**的校验 + 授权 + Applier 链路 | 禁止为"重放"开专用写方法或跳过幂等记录；禁止直接改主档 | `tech-design.md#5.2 EP-011`："重放复用同一 `DeviceInfoApplier`，受同一套幂等与授权约束" |
| 状态流转 | `casUpdateStatus(id, PENDING → REPLAYED/DISCARDED, handleUser, handleTime)`，`WHERE id=? AND status=0` | 影响行数 0 → 409/4093；禁止无条件 UPDATE（两个运维同时点重放会二次处理） | `tech-design.md#5.2`；`data-model.md#7.4` |
| 幂等冲突 | 原 `(source,msg_id)` 已成功消费过 → 重放得到 `DUPLICATE` → 409/4092，且死信状态**回滚为 PENDING**（不置 REPLAYED） | 禁止把 4092 当成功；禁止在 4092 时改数据 | `tech-design.md#9.6`（4092 语义）、TC-011-02 |
| 事务边界 | 死信状态更新与业务落地在**同一事务**（由 Applier 持有的事务扩展一层：先 CAS 死信状态 → 再 apply） | 禁止先置 REPLAYED 再重放（重放失败则死信"已处理"但数据没落地） | `tech-design.md#9.3 R1` 同源推理 |
| 审计 | `handle_user` 取 JWT `sub`；两个动作都写 `handle_time` | 禁止匿名操作 | `data-model.md#7.4` `handle_user/handle_time` |
| 日志 | 重放打 `deadLetterId + msg_id + sn + outcome`；批量列表不打内容 | 禁止把 `content` 打进日志（可达数百 KB） | `java_logging_specification.md` §批量操作、§脱敏 |

### Implementation sketch

```java
@Transactional(rollbackFor = Exception.class)
public void replay(long id, String operator) {
    if (dlqMapper.casUpdateStatus(id, PENDING, REPLAYED, operator, now()) == 0)
        throw new BizException(DEAD_LETTER_ALREADY_HANDLED);        // 4093
    MqDeadLetterDO row = dlqMapper.selectById(id);
    ApplyOutcome out = applier.apply(envelopeParser.parseAndValidate(row.getContent()));
    if (out == DUPLICATE) throw new BizException(REPLAY_HIT_IDEMPOTENT); // 4092 → 整个事务回滚，status 回到 PENDING
}
```

### Control flow and rules

```text
1. 列表：按 `status` + 分页读，返回 14 字段（无 `content`、无 `update_time`）。
2. 详情：`{id}` 不存在 → 404/4040；存在则附 `content` 原文（不修复、不改写）。
3. 重放：CAS 抢占状态 → 解析原文 → 校验（同 IM-CORE-008）→ 授权（同 IM-CORE-004）→ Applier → 提交。
4. 重放结果映射：APPLIED→200；DUPLICATE→409/4092 且回滚；MISSING_DEVICE_RECORDED→200 并在 message 说明留痕 result=3；UNAUTHORIZED→403/4031 且回滚；INVALID/UNKNOWN_TYPE→400/4002 且回滚。
5. 丢弃：仅状态流转 + 审计字段，不触碰主档与幂等表。
6. 任何回滚都不得留下"死信 REPLAYED 但数据未落地"的中间态。
```

### Tests

| 场景 | 断言 | 覆盖用例 |
|---|---|---|
| 修正后重放成功 | 主档落地、死信 `status=1`、`handle_user/time` 有值 | TC-011-01、TC-006-11 |
| 重放遇幂等冲突 | 原 msg_id 已消费 → 409/4092、主档二次不变、死信仍 `status=0` | TC-011-02 |
| 并发双点重放 | 两线程同时 replay → 一个成功、一个 409/4093 | EP-011 并发分支 |
| 重复丢弃 | 已 REPLAYED 的记录 discard → 409/4093 | §9.6 |
| 详情不存在 | `id=999` → 404/4040 | §5.2 |
| 列表体积 | 列表响应不含 `content` 字段（断言 JSON 键集合） | `DeadLetterItem` 14 字段 |

### Evidence

- `tech-design.md#5.2 EP-011`：三个操作 + 详情的字段切分、4092/4093 语义、审计字段。
- `data-model.md#6.4`/§7.4：`status` 取值、`handle_user/handle_time`、`content` 原文保存原则。
- `contracts/api-contract.yaml#DeadLetterItem/DeadLetterDetail`：响应形状（列表 14 字段、详情 +`content`）。

### Blockers

- none

---

## IM-INGEST-001：Kafka 容器、手动位点与重试预算接线

### Traceability

- FR：`FR-001`、`FR-002`
- EP：`EP-006`
- 设计章节：`tech-design.md#4.2 TD-001`、`#5.1 EP-006`、`#9.3 R13 R15`、`#11.1`
- 契约 / 数据模型 / ADR：`contracts/api-contract.yaml#x-kafka-message-contracts`（topic / dlq / group / partition-key）

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `device-ingest/src/main/java/com/example/device/ingest/IngestApplication.java` | `@SpringBootApplication` + `@EnableScheduling` | 非 web 进程；`spring.main.web-application-type=none`（仅 Actuator 独立端口） | `java_springboot_specification.md` §Actuator【强制】独立 management 端口 |
| CREATE | `device-ingest/src/main/java/com/example/device/ingest/config/KafkaListenerConfig.java` | `ConcurrentKafkaListenerContainerFactory` | `AckMode.MANUAL_IMMEDIATE`、`concurrency=${device.ingest.concurrency:3}`、`container.setMissingTopicsFatal(false)` 关闭、批量 poll 后逐条处理 | R13；MQ 规范 §2.2【强制】手动提交 |
| CREATE | `device-ingest/src/main/resources/application.yaml` | `spring.kafka.*` | `bootstrap-servers`、`group-id=device-management-ingest`、`enable-auto-commit=false`、`max-poll-interval-ms` 显式设置、`auto-offset-reset=earliest`、`key-deserializer=StringDeserializer`、`value-deserializer=ByteArrayDeserializer`（**不用** `JsonDeserializer`，解析交给 IM-CORE-008） | 契约 `x-kafka-message-contracts`；R12 要求拿到原始字节 |
| CREATE | `device-ingest/src/main/java/com/example/device/ingest/consumer/RetryBudget.java` | `#run(Callable, sn)` | 有界重试：次数 `${device.ingest.max-retry:5}` + 总预算 `${device.ingest.retry-budget-ms:1500}` + 退避 `${device.ingest.backoff-ms:50} × 2^n × (1 ± ${device.ingest.backoff-jitter:0.2})` | `java_springboot_specification.md` §重试【强制】有界 + jitter；`tech-design.md#11.1` |
| CREATE | `device-ingest/src/main/java/com/example/device/ingest/dlq/DeadLetterWriter.java` | `#write(rawRecord, reason, retryCount)` | 落 `mq_dead_letter(status=0)` + 计数 + 告警日志 | R12；`data-model.md#6.4` |

### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| 位点提交 | 仅在 `APPLIED / DUPLICATE / MISSING_DEVICE_RECORDED / UNAUTHORIZED / UNKNOWN_MSG_TYPE / 死信已落库` 六种终态后 `Acknowledgment.acknowledge()` | 禁止事务未提交先 ack（崩溃即丢消息）；禁止在 `@Transactional` 方法内部 ack | R13；`java_middleware_specification.md` §Consumer【强制】 |
| 重试预算 | `device.ingest.retry-budget-ms` **必须小于** `max.poll.interval.ms`；启动时用 `@PostConstruct` 断言该不等式，不成立直接启动失败 | 禁止无上限重试；禁止无 jitter 的纯指数退避（同 SN 并发重试会共振撞同一版本）；禁止在 rebalance 敏感期把消费线程拖长 | `tech-design.md#11.1` 重试总预算行；Spring Boot §重试【强制】 |
| 分区与顺序 | 分区键 = `sn`（外部约定）；单条消息一个事务，逐条 apply | 禁止整批一个事务（R7）；禁止消费端自己按 sn 二次分发（打破 Kafka 顺序保证） | R7；契约 `partition-key: sn` |
| 反序列化 | `value` 用 `ByteArrayDeserializer` 进监听器，字节原样交给 `EnvelopeParser` | 禁止用 `JsonDeserializer<Dto>` 在框架层解析（会丢失原文，死信无法保存原始内容） | R12、`data-model.md#6.4` `content` 行 |
| 优雅停机 | Kafka 容器 phase 早于 DataSource/Redis 关闭：`container.registerShutdownHook` 关闭 + `spring.lifecycle.timeout-per-shutdown-phase` | 禁止容器与数据源同 phase（会在途事务被中断，最坏位点已提交而事务未提交） | R15；`java_springboot_specification.md` §graceful【强制】 |
| 消费组 | 固定 `device-management-ingest`；`client.id` 带实例标识 | 禁止多进程共用消费组做负载分担以外的用途；禁止自动创建 Topic（运维前置） | 契约 `group` |

### Implementation sketch

```java
@Bean ConcurrentKafkaListenerContainerFactory<byte[], byte[]> deviceChangeContainer(...) {
    factory.getContainerProperties().setAckMode(AckMode.MANUAL_IMMEDIATE);
    factory.setConcurrency(props.concurrency());               // ≤ 分区数
    factory.getContainerProperties().setShutdownTimeout(Duration.ofSeconds(30));
    return factory;
}

@PostConstruct void validateBudget() {
    if (retryBudgetMs >= maxPollIntervalMs) throw new IllegalStateException(
        "device.ingest.retry-budget-ms must be < max.poll.interval.ms");
}
```

### Control flow and rules

```text
1. poll → 逐条交给 DeviceChangeHandler（IM-INGEST-002）→ 返回终态 → ack。
2. RetryBudget 只在事务外层循环；每次尝试调用一次 Applier（独立事务）。
3. 预算耗尽或次数耗尽 → DeadLetterWriter 落库 → 返回"死信已落库"终态 → ack。
4. 落死信本身失败（DB 不可用）→ 不 ack，让消息重投；这是本期唯一允许"不 ack"的分支。
5. SIGTERM：先停止拉取 → 等在途消息到终态并提交位点 → 关容器 → 关 DataSource/Redis。
```

### Tests

| 场景 | 断言 | 类型 |
|---|---|---|
| 终态才提交位点 | 六种终态各 ack 一次；在 Applier 抛非冲突异常时不 ack（重投） | integration（Embedded Kafka / Testcontainers） |
| 预算断言 | `retry-budget-ms ≥ max.poll.interval.ms` 时上下文启动失败 | unit |
| 退避有 jitter | 连续 5 次重试的间隔值互不相等且在 `[base×2^n, ±0.2]` 内 | unit |
| 停机顺序 | 发送 SIGTERM 时正在处理的消息完成后才关容器；容器关闭早于 DataSource（用 DisposableBean 顺序断言） | integration |
| 原文可保存 | 非 JSON 消息进入死信表后 `content` 与原始字节完全一致 | integration（TC-006-14 同构） |

### Evidence

- `tech-design.md#9.3 R13 R15`、`#11.1`：位点终态、预算不等式、停机顺序均为设计已定结论。
- `java_middleware_specification.md` §Consumer、`java_springboot_specification.md` §graceful：手动提交与优雅停机的强制条款。
- `contracts/api-contract.yaml#x-kafka-message-contracts`：topic / `device.info.change.dlq` / group / 分区键。

### Blockers

- none

---

## IM-INGEST-002：EP-006 消费主体与两层幂等

### Traceability

- FR：`FR-001`、`FR-002`、`FR-003`
- EP：`EP-006`
- 设计章节：`tech-design.md#4.3 TD-002`、`#9.1 EP-006 时序图`、`#9.3 R2 R3 R4 R5 R7 R11`、`#13.1 TC-006-01~15`
- 契约 / 数据模型 / ADR：`data-model.md#6.3` `uk_source_msg`；`contracts/api-contract.yaml#x-kafka-message-contracts`

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `device-ingest/src/main/java/com/example/device/ingest/consumer/DeviceChangeHandler.java` | `#handle(ConsumerRecord<byte[],byte[]>, Acknowledgment)` | 消费主流程编排：尺寸 → 解析 → 校验 → 授权 → Redis 挡重 → 有界重试调 Applier → 终态 → ack | `tech-design.md#9.3` 分支图 A→M 全链路 |
| CREATE | `device-ingest/src/main/java/com/example/device/ingest/consumer/DedupGuard.java` | `#tryMark(source, msgId)` / `#release(source, msgId)` | Redis `SETNX mq:dedup:{source}:{msg_id} EX ${mq.dedup-ttl-hours:24}h`；回滚路径必须 release | TD-002；R3 |
| CREATE | `device-ingest/src/main/java/com/example/device/ingest/consumer/ConsumeOutcomeResolver.java` | `#resolve(ApplyOutcome \| Exception)` | 把 Applier 结果与异常映射为六种终态之一 + 是否 ack | `tech-design.md#9.3 R13` |

### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| 两层幂等 | 第一层 Redis 挡并发（可失效）；第二层 DB 唯一索引是正确性依据 | 禁止只靠 Redis；禁止在 Redis 判重复后跳过 DB；Redis 不可用时退化到仅 DB 层且结果仍正确 | TD-002；TC-006-15 |
| 重复消息 | `DuplicateKeyException` 在**事务外**捕获 → `DUPLICATE` → ack，且**不释放** Redis 键 | 禁止在事务内 catch（rollback-only）；禁止向外抛（无限重投 + 积压） | R2 |
| 回滚释放 | CAS 冲突回滚后必须 `release(source,msgId)` 再重试 | 禁止带着 Redis 键重试（重试会被挡成"重复"直接 ack → 消息被吞） | R3；MQ 规范 §3.1 正例 |
| 重试 | 外层重试 ≤ `max-retry` 且 ≤ 预算；每次重试都重新 `SELECT` 取最新 version（由 Applier 内部完成） | 禁止事务内重试；禁止自调用带 `@Transactional` 的方法（代理失效） | R4 |
| 未授权 / 未知类型 | 事务外计数 + WARN + ack，不写流水 | 禁止抛异常、禁止重试、禁止落死信 | R11；§9.3 分支图 D1/B1 |
| 事务粒度 | 一条消息一个事务 | 禁止批量事务 | R7 |
| 指标 | `device_ingest_cas_conflict_total`、`_unauthorized_source_total`、`_unknown_type_total`、`_duplicate_total`、消费 lag（Kafka metrics） | 禁止只靠日志发现问题（冲突率 >1% 与 lag 阈值需可告警） | `tech-design.md#11.1` 告警口径 |

### Implementation sketch

```java
public void handle(ConsumerRecord<byte[], byte[]> rec, Acknowledgment ack) {
    ParseResult pr = parser.parse(rec.value());
    if (pr.isBad()) { deadLetterWriter.write(rec, pr.reason(), 0); ack.acknowledge(); return; }   // R12
    ValidatedCommand vc = validator.validate(pr.envelope());
    if (vc.isUnknownType()) { metrics.unknownType(vc); log.warn(...); ack.acknowledge(); return; }  // R11
    if (vc.isInvalid())     { metrics.invalid(vc);    log.warn(...); ack.acknowledge(); return; }
    if (!guard.checkOrThrow(dim, vc.source()).allowed()) { metrics.unauthorized(...); ack.acknowledge(); return; }
    if (!dedup.tryMark(vc.source(), vc.msgId())) { ack.acknowledge(); return; }                     // 廉价挡重
    retryBudget.run(() -> applier.apply(vc.toCommand()),
                    onConflict = () -> dedup.release(vc.source(), vc.msgId()));                     // R3
    ack.acknowledge();
}
```

### Control flow and rules

```text
1. 尺寸/解析失败 → 落死信 + ack（终态①）。
2. 未知 msg_type / 白名单越界 / 授权拒绝 → 计数 + WARN + ack，不写任何库（终态②）。
3. Redis SETNX 失败 → 判重复 → ack，不释放键（终态③）。
4. 进入 RetryBudget：每次尝试 = 一次独立事务（幂等记录 INSERT → 主档 CAS → 流水）。
5. DuplicateKeyException（DB 层重复）→ DUPLICATE → ack，不释放键（终态④）。
6. OptimisticConflictException → 回滚 → 释放 Redis 键 → 退避后重试。
7. 次数或预算耗尽 → 落死信 + 告警 → ack（终态⑤）。
8. 成功（含 MISSING_DEVICE_RECORDED 的正常提交）→ afterCommit 删缓存 → ack（终态⑥）。
```

### Tests

| 场景 | 断言 | 覆盖用例 |
|---|---|---|
| 正常消费 | 主档更新 + 幂等记录 + 流水 `result=1` + 缓存 key 删 + 位点提交 | TC-006-01 |
| 同 msg_id 重投 | 主档不变、无新流水、ack 且不重投 | TC-006-02 |
| 跨 source 同 msg_id | 两条都正常处理（唯一键含 source） | TC-006-03 |
| 同 SN 两维度并发 | 一成功一重试成功，两个维度终值都正确（R4+R10） | TC-006-04 |
| 冲突回滚不吞消息 | 外部先改 version 制造 CAS 失败 → dedup 键被释放 → 重试成功落地 | TC-006-05（专测 R3） |
| 重试超限 | 死信落库 + 告警计数 + 位点提交 | TC-006-10 |
| Redis 宕机 | 数据仍正确落地（DB 唯一索引兜底） | TC-006-15 |
| 乱序基线 | 构造"旧事件"消息 → 断言当前**会覆盖**（缺口固化为回归用例） | TC-006-13 |

### Evidence

- `tech-design.md#9.1` EP-006 时序图：本单元步骤 4~8 与图中 `L`/`RD`/`AP`/`REC` 交互一致。
- `tech-design.md#9.3 R2 R3 R4 R7 R11 R12 R13`：七条硬约束直接对应六个终态。
- `data-model.md#6.3`：`uk_source_msg` 是第二层幂等的物理载体。

### Blockers

- none

---

## IM-INGEST-003：EP-007 缓存对账任务与跨实例互斥

### Traceability

- FR：`FR-005`
- EP：`EP-007`
- 设计章节：`tech-design.md#5.1 EP-007`、`#5.2 EP-007`（定时任务参数表）、`#9.4` 一致性口径
- 契约 / 数据模型 / ADR：`contracts/api-contract.yaml#x-internal-entries`（EP-007 登记）；`data-model.md#6.1` `idx_update_time`

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `device-ingest/src/main/java/com/example/device/ingest/cron/CacheReconcileJob.java` | `#reconcile()` | `@Scheduled(cron = "${device.cache.reconcile.cron}")`；按 `update_time > 水位` 增量扫主档 → MGET 缓存 → 比对 Value 内 `update_time` → 不一致或缺失则 `DEL` | `tech-design.md#5.2 EP-007` 处置列 |
| CREATE | `device-ingest/src/main/java/com/example/device/ingest/cron/ReconcileWatermark.java` | `#load()` / `#save(Instant)` | 水位存 Redis key `device:cron:watermark:cache-reconcile`，TTL `${device.cron.watermark-ttl-hours:168}` 每轮续期；不新建状态表（设计未授权新增表，且水位只需单值） | Redis 规范 §1.2 四段命名 + §2.3【强制】必设 TTL；`tech-design.md#5.2`"水位持久化，进程重启不丢" |
| CREATE | `device-ingest/src/main/java/com/example/device/ingest/cron/CronLock.java` | `#tryLock(job, ttl)` / `#unlock(job)` | `SETNX device:lock:cron:{job} EX ttl`，`ttl = 周期 × ${device.cron.lock-ttl-ratio:0.8}`；抢不到直接跳过本轮 | 本映射新增的必要防线（见 Evidence 第 3 条） |

### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| 多实例互斥 | ingest 多实例部署时同一 cron 只允许一个实例执行：进任务先抢 `CronLock` | 禁止不加锁直接跑（同批 SN 被多实例重复 MGET/DEL，写放大且水位互相覆盖） | `tech-design.md#12.1`（无"单实例部署"约束）+ `java_middleware_specification.md` §定时任务【强制】幂等与互斥 |
| 任务时长 | 单轮最大时长 < 周期 × 0.8；超时长则本轮停止并下轮续跑（水位推进到已处理位置） | 禁止让对账自身成为写放大源；禁止全表扫描（只用 `idx_update_time` 增量） | `tech-design.md#5.2 EP-007` |
| 批量 | 单批 ≤ `${device.cache.reconcile.batch-size:500}`；比对用一次 MGET | 禁止逐条 GET/DEL 循环 | §Redis §1.4【强制】；本设计 §9.4 |
| 修复动作 | 只 `DEL`，不回写新值 | 禁止"发现不一致就 SET 新值"（与并发重建竞争，可能写出更旧的值） | `tech-design.md#5.2 EP-007`（"不一致或缺失则 DEL"） |
| 哨兵 | `__EMPTY__` 且 DB 有可见行 → 视为不一致 → `DEL` | 禁止把哨兵当作"已一致"跳过 | `tech-design.md#11.2 缺陷 9` |
| 水位恢复 | Redis 水位丢失时以 `now - 2 × 周期` 冷启动，宁可多扫一轮 | 禁止用 `0` 冷启动（首轮全表扫） | `tech-design.md#5.2`"进程重启不丢" |

### Implementation sketch

```java
@Scheduled(cron = "${device.cache.reconcile.cron}")
public void reconcile() {
    Duration ttl = Duration.ofMillis(cronPeriodMs * props.lockTtlRatio());
    if (!cronLock.tryLock("cache-reconcile", ttl)) return;                 // 已有实例在跑
    try {
        LocalDateTime watermark = mark.load();
        List<DeviceInfoDO> rows = mapper.selectUpdatedAfter(watermark, props.batchSize());
        Map<String, String> cached = cache.readUpdateTimeOf(rows.stream().map(DeviceInfoDO::getSn).toList());
        rows.stream().filter(r -> stale(r, cached.get(r.getSn()))).forEach(r -> cache.evict(r.getSn()));
        if (!rows.isEmpty()) mark.save(rows.get(rows.size() - 1).getUpdateTime());
    } finally { cronLock.unlock("cache-reconcile"); }
}
```

### Control flow and rules

```text
1. 抢锁 → 未抢到直接返回（记 debug 日志与跳过计数）。
2. 读水位 → 增量查主档（≤ batch-size，按 update_time 升序）。
3. 一次 MGET 取缓存内的 `update_time` → 逐条判定：miss / 哨兵 / 缓存值早于 DB → DEL。
4. 推进水位到最后一条已处理行的 update_time。
5. 任务本身不写主档、不发消息；只有 DEL 一种副作用。
6. Redis 不可用 → 本轮放弃并保留水位（下轮补），不视为失败告警（DB 与 TTL 仍兜底）。
7. 连续两轮结果必须一致（第二轮无额外 DEL），证明任务幂等。
```

### Tests

| 场景 | 断言 | 覆盖用例 |
|---|---|---|
| 造脏修复 | 手工"写库不删缓存"制造脏数据 → 一轮对账后 key 被删 → 下次查询读到新值 | TC-007-01 |
| 幂等 | 连续两轮：第二轮 DEL 次数为 0，水位不变 | TC-007-02 |
| 多实例互斥 | 两个 `CronLock` 并发调用 → 只有一个进入任务体 | integration（新增防线） |
| 不逐条访问 | 500 条批次 → Redis 命令数 = 1 MGET + ≤500 DEL（Pipeline），无循环 GET | integration |
| 哨兵纠正 | 缓存为 `__EMPTY__` 而 DB 有可见行 → 被 DEL | `#11.2 缺陷 9` |
| 窗口收敛 | 脏读窗口断言 ≤ `min(TTL, 对账周期)`（口径测试，不断言强一致） | `tech-design.md#9.4` |

### Evidence

- `tech-design.md#5.2 EP-007`：扫描方式、批量上限、时长约束、水位持久化四条要求逐条落地。
- `tech-design.md#9.4`："主动删除负责快，TTL 兜底负责错，定时对账负责补"——本单元是"补"的执行体。
- **本映射暴露的设计缺口**：`tech-design.md` 未规定 EP-007/EP-008 在多实例下的互斥方式（§12.1 只说三进程共库）。`CronLock` 是为消除该缺口新增的组件，需回写设计 §5.2 与 §11.1（新增键 `device.cron.lock-ttl-ratio`）。

### Blockers

- none

---

## IM-INGEST-004：EP-008 归档清理与次月分区预建

### Traceability

- FR：`FR-001`（留痕可运维）；NFR：`NFR-002` 留存与容量
- EP：`EP-008`
- 设计章节：`tech-design.md#5.2 EP-008`、`data-model.md#9.2` 步 5、`#8.3`（`mq_consume_record` 不分区的原因）
- 契约 / 数据模型 / ADR：`data-model.md#6.2` 分区定义；`#6.3` `consume_time`

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `device-ingest/src/main/java/com/example/device/ingest/cron/ArchiveCleanupJob.java` | `#cleanConsumeRecords()` / `#dropExpiredPartitions()` | 幂等记录按 `consume_time` 分批删（保留 `${device.consume.record-retention-days:90}`）；流水按分区 `DROP PARTITION`（保留 `${device.record.retention-months:12}`） | `tech-design.md#5.2 EP-008` |
| CREATE | `device-ingest/src/main/java/com/example/device/ingest/cron/PartitionPrebuildJob.java` | `#prebuildNextMonth()` | 每月 25 日 `ALTER TABLE device_info_change_record ADD PARTITION (...)` + 校验 `pmax` 行数；`pmax` 非空 → 告警 | `data-model.md#9.2` 步 5、§6.2 注记 |
| CREATE | `device-ingest/src/main/java/com/example/device/ingest/cron/CronSql.java` | `nextPartitionName(YearMonth)` / `valuesLessThan(YearMonth)` | 分区名与边界由代码按 `YYYYMM` 规则生成，**不接受外部输入** | `java_middleware_specification.md` §MyBatis【强制】：DDL 不可参数化，只能白名单拼接 |

### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| 删除方式 | 幂等记录：`DELETE ... WHERE consume_time < ? LIMIT ${archive.delete.batch-size:1000}` 循环至影响行数 0；流水：`ALTER TABLE ... DROP PARTITION pYYYYMM` | 禁止单条大事务删全量；禁止对分区表用 `DELETE` 清整月（`DROP PARTITION` 秒级） | `tech-design.md#5.2 EP-008`【强制】分批；`data-model.md#6.2` |
| `mq_consume_record` | **不分区**，只做行删除 | 禁止给它加分区（会让唯一索引失效：分区表唯一键必须含分区键，破坏幂等语义） | `data-model.md#8.3` |
| 分区预建 | 只允许向前追加新分区；`pmax` 必须始终存在且为空 | 禁止 `DROP PARTITION pmax`；禁止预排到多年以后（跨年节奏脱节）；漏建会导致数据落入 `pmax` 后无法清理 | `data-model.md#6.2` 注记、§9.2 步 5 |
| 互斥 | 两个任务都进 `CronLock`（`device:lock:cron:archive-cleanup` / `partition-prebuild`） | 禁止多实例并发 `DROP PARTITION`（MySQL DDL 元数据锁 + 重复执行报错） | 同 IM-INGEST-003 新增防线 |
| 误删保护 | 删除前先 `SELECT COUNT(*)` 并打日志（总数 + 每批成败计数）；`retention-days`/`retention-months` 为 0 或负数时任务拒绝执行并 ERROR | 禁止提供"清空全部"入口；禁止用 `TRUNCATE` | `java_logging_specification.md` §批量操作【强制】 |

### Implementation sketch

```java
@Scheduled(cron = "${device.archive.cleanup-cron}")
public void cleanConsumeRecords() {
    if (!cronLock.tryLock("archive-cleanup", lockTtl())) return;
    LocalDateTime before = now().minusDays(props.recordRetentionDays());
    int total = 0, rows;
    do { rows = consumeRecordMapper.deleteConsumedBefore(before, props.batchSize()); total += rows; }
    while (rows > 0);
    log.info("archive cleanup done, deleted={} before={}", total, before);
}

@Scheduled(cron = "${device.archive.prebuild-cron}")   // 每月 25 日
public void prebuildNextMonth() {
    String name = CronSql.nextPartitionName(YearMonth.now().plusMonths(1));
    partitionMapper.addPartition(name, CronSql.valuesLessThan(YearMonth.now().plusMonths(2)));
    if (partitionMapper.countRowsIn("pmax") > 0) alarm.warn("pmax not empty: partition prebuild missed");
}
```

### Control flow and rules

```text
1. 抢 cron 锁 → 未抢到跳过。
2. 幂等记录清理：算出截止 → 循环分批删 → 打总数；每批独立事务。
3. 分区维护：先确保次月分区存在（不存在则 ADD），再删除超出保留期的最老分区。
4. 校验 `pmax` 行数为 0；非 0 说明有月份漏建，告警并停止该轮 DROP（避免连带删除未知数据）。
5. 任何 DDL 语句的分区名只由 `CronSql` 生成；任务不接受任何外部参数。
6. 配置非法（保留期 ≤ 0、batch ≤ 0）→ 任务启动即失败，不执行删除。
```

### Tests

| 场景 | 断言 | 覆盖用例 |
|---|---|---|
| 分批删除 | 造 2500 行过期记录 → 三次 `DELETE`（1000/1000/500），每批独立事务 | TC-008-01 |
| 分区秒级清理 | 过期分区 `DROP PARTITION` 后该分区不存在，其余分区行数不变 | TC-008-01 |
| 预建生效 | 25 日执行后存在次月分区且 `pmax` 仍为空 | `data-model.md#9.2` 步 5 |
| 漏建告警 | 手工往 `pmax` 写一行 → 任务告警且不执行 DROP | §6.2 注记 |
| 互斥 | 两实例并发清理 → 只一个执行 | 同 IM-INGEST-003 |
| 非法配置 | `retention-days=0` → 启动/执行失败且不删任何行 | 误删保护 |

### Evidence

- `tech-design.md#5.2 EP-008`：分批上限、保留期键、`DROP PARTITION` 与 `pmax` 校验要求。
- `data-model.md#8.3`：`mq_consume_record` 不分区的唯一理由（保护 `uk_source_msg`）。
- `data-model.md#9.2` 步 5 与 §6.2 注记：分区预建的上线位置与漏建后果。

### Blockers

- none

---

## IM-OPENAPI-001：对外查询进程（验签 + 限流 + EP-001/002）

### Traceability

- FR：`FR-004`、`FR-005`
- EP：`EP-001`、`EP-002`
- 设计章节：`tech-design.md#5.2 EP-001/EP-002`、`#9.6`（429/限流头/缓存头）
- 契约 / 数据模型 / ADR：`contracts/api-contract.yaml` 的 `securitySchemes.AppKeyHmac`、两个 query 操作、`Error` 4010/4011/4030/4290

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `device-openapi/src/main/java/com/example/device/openapi/OpenApiApplication.java` | `@SpringBootApplication` | 只暴露 `/api/v1/devices/**`；无写接口 | `tech-design.md#4.7 TD-006` 方案 B |
| CREATE | `device-openapi/src/main/java/com/example/device/openapi/controller/DeviceQueryController.java` | `GET /api/v1/devices/{sn}`、`POST /api/v1/devices/batch` | 精简 Controller：仅参数绑定 + 调 `DeviceInfoQueryService` + 包装 `ApiEnvelope`；`@Valid` | `java_springboot_specification.md` §Web 层【强制】Controller 精简 |
| CREATE | `device-openapi/src/main/java/com/example/device/openapi/security/SignatureAuthInterceptor.java` | `#preHandle` | 校验 `X-App-Key/X-Timestamp/X-Signature` 三头一组；时间窗 ±`${device.openapi.sign-window-seconds:300}`；`HMAC-SHA256(secret, method+"\n"+path+"\n"+timestamp+"\n"+bodySha256)`；常数时间比较 | 契约 `securitySchemes.AppKeyHmac.description` |
| CREATE | `device-openapi/src/main/java/com/example/device/openapi/security/AppKeyRegistry.java` | `#secretOf(appKey)`、`#secretsOf(appKey)` | Secret 只从 `${device.openapi.app-secret-path}` 挂载文件读取（缺键即启动失败）；一个 `appKey` 对应**最多两个**有效 secret（当前 + 上一版），轮换窗口内两个都参与校验、任一命中即通过；轮换完成把上一版置空 | `java_springboot_specification.md` §密钥外部化【强制】；`tech_design.md#12.4` 密钥表；TC-SEC-02 |
| CREATE | `device-openapi/src/main/java/com/example/device/openapi/ratelimit/QuotaRateLimiter.java` | `#tryAcquire(appKey)` → `{limit, remaining, resetEpochSec}` | 按 AppKey 固定窗口或令牌桶，上限 `${device.openapi.qps-per-app}`；Redis 计数（多实例一致） | `tech-design.md#9.6` 429 与限流头；HTTP 规范 §10.1 |
| CREATE | `device-openapi/src/main/java/com/example/device/openapi/config/OpenApiWebConfig.java` | 拦截器注册 + 请求体缓存 | 需 `ContentCachingRequestWrapper` 计算 body sha256；仅 `/api/v1/**` 走验签 | 契约签名串定义要求 body 参与签名 |

### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| 验签 | 三头缺一 → 401/4010；时间戳超窗 → 401/4011；签名不匹配 → 401/4010；比较用 `MessageDigest.isEqual` | 禁止 `String.equals` 比较签名（时序侧信道）；禁止把 secret 写进日志或响应；禁止 GET 请求跳过 body 摘要（定义为空体摘要） | 契约 `AppKeyHmac`；HTTP 规范 §8 安全 |
| 限流 | 超限 → 429/4290 + `Retry-After`；所有响应恒带 `X-RateLimit-Limit/Remaining/Reset` 与 `Cache-Control: no-store` | 禁止只在超限时才带限流头；禁止放开 HTTP 缓存（对账窗口不可观测） | `tech-design.md#9.6` 两处注记 |
| 进程边界 | 本进程**只读**：无任何 INSERT/UPDATE/DELETE Mapper 调用 | 禁止在本进程提供写接口；禁止在本进程删缓存（失效归写侧） | `tech-design.md#6.3`："蓝= 对外查询（永不写库）" |
| 批量 | 上限 `${device.query.batch-max:100}`，超限 400/4003 | 禁止服务端拆分 | `tech-design.md#5.2 EP-002` |
| 依赖超时 | Redis `spring.data.redis.timeout` = 200ms、`connect-timeout` = 200ms；MySQL `mybatis.configuration.default-statement-timeout` = 2s、Hikari `connection-timeout` = 3s；Redis 超时 → 回源不回填；Redis 与 DB **同时**超时/连接失败 → 503/5030（无熔断组件，判定只看本次调用结果） | 禁止无超时的远程调用（【强制】）；禁止无界重试；禁止用熔断器掩盖未配超时——本期不引 Resilience4j（`tech_design.md#12.4`） | `java_springboot_specification.md` §外部调用弹性（超时【强制】/ 熔断【推荐】）；`tech_design.md#11.1`、TC-OPS-02 |
| 文档 | 由 SpringDoc 运行时导出 OpenAPI，并与 `contracts/api-contract.yaml` 做结构化 diff | 禁止只改代码不改契约，或只改契约不改代码 | TC-API-03 |

### Implementation sketch

```java
@GetMapping("/api/v1/devices/{sn}")
public ResponseEntity<ApiEnvelope<DevicePublicView>> one(@PathVariable @NotBlank String sn) {
    return queryService.findBySn(sn)
        .map(v -> ok(ApiEnvelope.success(v, traceId())))
        .orElseGet(() -> status(NOT_FOUND).body(ApiEnvelope.error(NOT_FOUND_4040, traceId())));
}

// SignatureAuthInterceptor
String expected = hmacSha256(secret, method + "\n" + path + "\n" + timestamp + "\n" + bodySha256);
if (!MessageDigest.isEqual(expected.getBytes(UTF_8), signature.getBytes(UTF_8))) throw new BizException(UNAUTHORIZED_4010);
```

### Control flow and rules

```text
1. 请求 → 尺寸/路径匹配 → 三头存在性 → 时间窗 → 验签 → AppKey 有效性（403/4030）→ 限流 → 放行。
2. 放行后：点查走 IM-CORE-006；批量先校验条数，再走批量装配。
3. 限流计数在验签之后（未验签流量不消耗配额，避免被打满计数）。
4. 任何拒绝分支都必须返回带 `trace_id` 的 ApiEnvelope 与对应 HTTP 状态。
5. 本进程不执行任何写 SQL。约束靠两条手段保证，不靠编译期隔离（`spring-tx` 经 `device-core` 传递进类路径）：约定不注入写方法 + 测试断言一次查询后写方法调用计数为 0。
```

### Tests

| 场景 | 断言 | 覆盖用例 |
|---|---|---|
| 错签名 / 缺头 | 401/4010，响应含 `trace_id` | TC-001-07 |
| 时间戳超窗 | 401/4011 | TC-001-07 |
| 超配额 | 429/4290 + `Retry-After` + 三个限流头 | TC-001-07 |
| 恒带缓存头 | 200 响应也有 `Cache-Control: no-store` | §9.6 |
| 只读性 | 一次成功查询后断言无任何写 SQL（MyBatis 拦截器统计） | §6.3 |
| 契约 diff | SpringDoc 导出与仓库 YAML 的路径/方法/状态码集合完全一致 | TC-API-03 |
| 依赖超时 | Redis 与 DB 同时超时/连接失败 → 503/5030，不出现 500/5000 | TC-OPS-02 |

### Evidence

- `contracts/api-contract.yaml#securitySchemes.AppKeyHmac`：签名串拼接顺序、三头名称、±5 分钟窗口（代码必须与之逐字符一致）。
- `tech-design.md#5.2 EP-001/EP-002`：状态码映射、批量不拆分。
- `tech-design.md#9.6`：429/限流头/缓存头/`trace_id` 的响应面要求。

### Blockers

- none

---

## IM-ADMIN-001：后台进程（鉴权 + 操作人上下文 + EP-003~011 控制器）

### Traceability

- FR：`FR-006`（含 `FR-003` 的冲突提示）
- EP：`EP-003`、`EP-004`、`EP-005`、`EP-009`、`EP-010`、`EP-011`
- 设计章节：`tech-design.md#5.2` 六个后台入口定义、`#9.6` 状态码表
- 契约 / 数据模型 / ADR：`contracts/api-contract.yaml` 的 `securitySchemes.AdminJwt` 与 7 个 admin 操作、`DeviceAdminItem` / `ChangeRecordItem` / `DeadLetterItem`

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `device-admin/src/main/java/com/example/device/admin/AdminApplication.java` | `@SpringBootApplication` | 只暴露 `/api/v1/admin/**` + Actuator 独立端口 | `tech-design.md#4.7` |
| CREATE | `device-admin/src/main/java/com/example/device/admin/controller/DeviceAdminController.java` | EP-003/004/005/009 | 列表、建档、编辑、逻辑删 | `tech-design.md#5.2` |
| CREATE | `device-admin/src/main/java/com/example/device/admin/controller/ChangeRecordController.java` | EP-010 | `GET /api/v1/admin/devices/{sn}/change-records` | 同上 |
| CREATE | `device-admin/src/main/java/com/example/device/admin/controller/DeadLetterController.java` | EP-011 | 列表 / 详情 / replay / discard 四个操作 | 同上 |
| CREATE | `device-admin/src/main/java/com/example/device/admin/security/OperatorContext.java` | `#current()` → 拼音账号 | 从 JWT `sub` 解析，无登录态则 401/4011 | 契约 `AdminJwt`；`data-model.md#7.1` `maintain_user` |
| CREATE | `device-admin/src/main/java/com/example/device/admin/security/SecurityConfig.java` | 资源服务器配置 | `issuer=${device.admin.jwt.issuer}`、`audience=${device.admin.jwt.audience}`、公钥来自 `${device.admin.jwt.public-key-path}`（外部化）；`/api/v1/admin/**` 需认证 | `java_springboot_specification.md` §安全配置【强制】 |
| CREATE | `device-admin/src/main/java/com/example/device/admin/web/IdempotencyKeyFilter.java` | 对 `POST` 建档强制 `Idempotency-Key` 头 | 缺失 → 400/4001 | HTTP 规范 §9.1【强制】 |

### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| 分页 | 参数 `page` / `page_size`；响应 `{items,total,page,page_size}`；`offset = (page-1)*page_size` 上限 `${device.admin.max-offset:10000}`，超出 400/4002 | 禁止 `limit/offset` 裸参数对外；禁止无条件全量翻页 | HTTP 规范 §6.1；`tech-design.md#5.2 EP-003` |
| 强制筛选 | EP-003 必须至少带一个高区分度条件（`sn` / `sn_prefix` 右模糊 / `model_code` + 时间范围），否则 400/4004 | 禁止 `%kw%` 左右模糊（走不了索引）；禁止只按状态列筛选而不带范围条件 | `tech-design.md#5.2 EP-003`、`data-model.md#6.1` 注记 2 |
| 幂等头 | 建档必须有 `Idempotency-Key`；并发双提交只落一条 | 禁止让 `uk_sn` 冲突成为唯一去重手段（那会把第二个请求变成 4090 而非"同一请求的重复提交"） | HTTP 规范 §9.1；TC-004-02 |
| 冲突语义 | 编辑/删除版本冲突 → 409/4091 并返回可读 message；**不重试** | 禁止 400 承载 409 语义 | R5；`tech-design.md#9.6` |
| 204 | 逻辑删成功返回 204 无响应体 | 禁止包成 200 + 外壳 | `tech-design.md#9.6` 204 例外 |
| 可见列 | 后台返回 23 列（含 `version/deleted/create_time`）；对外仍 15 列 | 禁止后台复用 `DevicePublicView`（缺 `version` 无法做乐观锁） | `tech-design.md#9.5`、契约 `DeviceAdminItem` |
| 审计 | 所有写操作把 `OperatorContext.current()` 传给 core 服务，由 core 写 `maintain_user/op_user/handle_user` | 禁止在 Controller 层自行拼审计字段 | `data-model.md#7.1`、§7.4 |

### Implementation sketch

```java
@PutMapping("/api/v1/admin/devices/{sn}")
public ApiEnvelope<DeviceAdminItem> update(@PathVariable String sn,
                                          @Valid @RequestBody DeviceProfileUpdateCommand cmd) {
    return ApiEnvelope.success(adminService.updateProfile(cmd.withSn(sn), operator.current()));
}

@GetMapping("/api/v1/admin/devices")
public ApiEnvelope<PageMeta Plus> list(@Valid DeviceQueryFilter f) {   // 校验"至少一个高区分度条件"
    if (!f.hasDiscriminatingCondition()) throw new BizException(MISSING_FILTER_4004);
    if (f.offset() > maxOffset) throw new BizException(PARAM_FORMAT_4002);
    ...
}
```

### Control flow and rules

```text
1. JWT 认证 → 操作人拼音账号 → 进入 Controller → 参数与筛选条件校验 → 调 device-core 服务 → 统一外壳返回。
2. 写路径全部经 `DeviceAdminService`/`DeadLetterService`，Controller 不接触 Mapper。
3. 死信重放遇幂等冲突 → 409/4092；已处理 → 409/4093；两者都不二次改数据。
4. 流水查询：设备不存在或已逻辑删 → 404/4040；存在但无记录 → 200 + 空 `items`。
5. 列表查询不读写 Redis（缓存只服务对外点查）。
6. 所有后台写操作成功/失败都要打日志：前值、后值、操作人、结果（日志规范【强制】）。
```

### Tests

| 场景 | 断言 | 覆盖用例 |
|---|---|---|
| 无条件列表查询 | 400/4004，无 SQL | TC-003-01 |
| 深翻页 | `page*page_size > 10000` → 400/4002 | `#5.2 EP-003` |
| `sn_prefix` 右模糊 | 执行计划命中 `uk_sn` 前缀；不出现 `%kw%` | TC-003-02 |
| 建档成功 | 201 + `data`；状态列与时间列为服务端默认；缓存被 evict | TC-004-01 反向、EP-004 |
| 版本冲突 | 409/4091 + 无重试 | TC-005-02 |
| 逻辑删 | 204 无体 | §9.6 例外 |
| 流水空 | 存在设备无流水 → 200 + `items: []` | `#5.2 EP-010` |
| 未登录 / token 过期 | 401/4011 | §9.6 |
| 死信全流程 | 列表 14 字段、详情含 content、replay/discard 写审计 | TC-011-01/02 |

### Evidence

- `tech-design.md#5.2`：六个后台入口的方法、状态码、必填条件、返回字段全部已定。
- `contracts/api-contract.yaml`：7 个 admin 操作的请求/响应 schema（`DeviceCreateCommand` 等）。
- `java_springboot_specification.md` §安全配置、§Web 层；HTTP 规范 §6.1 分页与 §9.1 幂等头。

### Blockers

- none

---

## IM-OPS-001：Actuator 探针、配置绑定与优雅停机

### Traceability

- FR：`FR-004`、`FR-005`（可用性运维面）；NFR：`NFR-004`
- EP：`EP-013`；并支撑 `EP-007`/`EP-008` 的可观测
- 设计章节：`tech-design.md#5.1 EP-013`、`#5.2 EP-013`、`#9.3 R15`
- 契约 / 数据模型 / ADR：`contracts/api-contract.yaml#x-internal-entries`（EP-013：liveness 禁含 DB/Redis/Kafka）

### Code targets

| Action | Path | Symbol / component | 变更说明 | 复用依据 |
|---|---|---|---|---|
| CREATE | `device-ingest/src/main/resources/application.yaml`（与 openapi/admin 同名文件同构） | `management.*` + `server.shutdown` | `management.server.port` 独立、`endpoints.web.exposure.include=health,info,prometheus`（显式白名单）、`health.probes.enabled=true`、`health.liveness.groups` 只含 `ping,diskSpace`、`readiness.groups` 含 `db,redis,kafka`、`server.shutdown=graceful`、`spring.lifecycle.timeout-per-shutdown-phase=35s` | `java_springboot_specification.md` §Actuator、§graceful【强制】 |
| CREATE | `device-core/src/main/java/com/example/device/core/config/DeviceIngestProperties.java` | `@ConfigurationProperties(prefix = "device.ingest")` | 类型安全绑定 `device.ingest.concurrency`、`device.ingest.partition`、`device.ingest.max-retry`、`device.ingest.backoff-ms`、`device.ingest.backoff-jitter`、`device.ingest.retry-budget-ms`、`device.ingest.tx-timeout-sec`、`device.ingest.lag-alarm`、`device.ingest.max-payload-bytes` | 同上 §类型安全配置绑定【强制】 |
| CREATE | `device-core/src/main/java/com/example/device/core/config/DevicePropertiesValidation.java` | `#validate()` `@PostConstruct` | 启动断言：`retry-budget-ms < max.poll.interval.ms`、`concurrency ≤ partition`、`batch-max > 0`、TTL 与 jitter 均为正 | `tech-design.md#11.1` 三条不等式 |
| CREATE | `device-core/src/main/java/com/example/device/core/config/ShutdownPhaseConfig.java` | `SmartLifecycle` phase 编排 | Kafka 容器停止（较高 phase）→ 在途到终态 → DataSource/Redis（较低 phase）关闭 | R15 |
| CREATE | `device-core/src/main/java/com/example/device/core/metrics/LagMonitor.java` | 消费 lag gauge + 阈值 `${device.ingest.lag-alarm:10000}` | 唯一能提前发现"对外数据开始滞后"的指标 | `tech-design.md#11.1` 消费 lag 告警行 |

### Special technology and module constraints

| 技术 / 模块 | 实现要求 | 边界与禁止项 | 证据 |
|---|---|---|---|
| liveness | 只含 `ping` + `diskSpace`，**不含** DB/Redis/Kafka | 禁止把外部依赖放进 liveness（依赖抖动会杀进程造成雪崩） | Spring Boot §Actuator【强制】；契约 `x-internal-entries` EP-013 |
| readiness | 含 `db`、`redis`、`kafka`；不就绪时从负载均衡摘除 | 禁止 readiness 缺失导致 Kafka 消费者在依赖不可用时继续拉取 | 同上 |
| 端点暴露 | 独立 `management.server.port`；除 `health`/`info` 外需鉴权 | 禁止 `management.endpoints.web.exposure.include=*`；禁止 `/env`、`/configprops`、`/heapdump` 暴露 | Spring Boot §Actuator【强制】加固 |
| 停机顺序 | `server.shutdown=graceful`；Kafka 容器 stop 早于 DataSource/Redis；`timeout-per-shutdown-phase` ≥ 重试预算 + 事务超时 | 禁止靠 `Runtime.addShutdownHook` 手工关组件；禁止无超时无限等待在途消息 | R15 |
| 配置键 | 全部 kebab-case 且带默认值 `${key:建议值}` 形态 | 禁止在代码里硬编码阈值；禁止 camelCase 键 | 与 `tech-design.md#11.1`、`data-model.md` 键集合一致 |
| 日志/追踪 | JSON 结构化日志 + `trace_id` 字段（来自 IM-CORE-007 MDC） | 禁止打印 AppKey Secret、JWT、消息中的敏感明文 | Spring Boot §结构化日志、日志规范 §脱敏 |

### Implementation sketch

```yaml
management:
  server:
    port: ${MANAGEMENT_PORT:9091}
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      probes:
        enabled: true
      group:
        liveness:
          include: ping,diskSpace
        readiness:
          include: db,redis,kafka
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 35s
```

### Control flow and rules

```text
1. 启动：配置绑定 → `DevicePropertiesValidation` 断言 → Kafka 容器 start。**没有迁移步骤**（本期不启用 Flyway，表结构由运维前置执行）。
2. 就绪探针决定外部流量与消费是否可用；存活探针只反映进程本身。
3. 停机：SIGTERM → 先停止拉取（容器 pause/stop）→ 在途消息跑到终态并提交位点 → 关容器 → 关 DataSource/Redis。
4. 断言失败（如重试预算 ≥ poll 间隔）→ 进程拒绝启动，不进入降级运行。
5. 三个进程的 application.yaml 结构一致，仅端口/暴露面/是否有 Kafka 段不同。
```

### Tests

| 场景 | 断言 | 类型 |
|---|---|---|
| liveness 独立性 | 关停 MySQL 后 `/actuator/health/liveness` 仍返回 UP | integration（Testcontainers） |
| readiness 依赖 | Redis 不可用时 `/readiness` DOWN、`/liveness` UP | integration |
| 端点白名单 | `/actuator/env`、`/actuator/heapdump` 返回 404 | integration |
| 优雅停机 | 发送 SIGTERM 时一条在途消息完整落地并提交位点 | integration（Embedded Kafka） |
| 配置断言 | 注入 `retry-budget-ms=5000, max-poll-interval-ms=3000` → 上下文启动失败 | unit |
| kebab-case | 断言所有 `device.*` 键在设计文档与 yaml 中同串（脚本比对，防漂移） | build |

### Evidence

- `tech-design.md#5.2 EP-013`：独立端口、显式白名单、liveness 不含外部依赖三条为设计已定要求。
- `tech-design.md#9.3 R15`：停机四步顺序及其后果推理。
- `java_springboot_specification.md` §Actuator、§graceful、§类型安全配置绑定、§结构化日志：全部为【强制】条款，可直接 diff 校验。

### Blockers

- none

---
