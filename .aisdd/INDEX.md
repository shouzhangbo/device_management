# .aisdd 全局文档索引

## 使用说明
- 本文件是后续 Skill 的首选加载入口
- 先查索引，再按需读取目标文档，避免一次性加载全部规则文档
- `.aisdd/knowledges/INDEX.md` 是知识库子索引，聚焦业务与技术知识，不替代本文件

## 文档索引
| doc_id | path | category | description | recommended_skills | load_when |
|--------|------|----------|-------------|--------------------|-----------|
| rule-prd | `.aisdd/rules/prd_specification.md` | rule | PRD 结构、字段、版本与评审规范 | `prd-generate`, `prd-review` | 生成或评审需求文档时 |
| rule-tech | `.aisdd/rules/tech_specification.md` | rule | 技术设计文档结构与内容规范 | `tech-generate`, `tech-review`, `task-planner` | 编写或评审技术方案时 |
| rule-java-basic | `.aisdd/rules/java_basic_specification.md` | rule | Java 基础规范：命名/集合/异常/类型/变量/方法/类/注释（并发细则见 rule-java-concurrency） | `task-executor`, `quick-dev`, `test-feedback` | 编写 Java 代码时（基础语法与编码规范） |
| rule-java-logging | `.aisdd/rules/java_logging_specification.md` | rule | Java 日志打印规范：日志级别/核心模块/敏感操作/格式与脱敏 | `task-executor`, `quick-dev`, `test-feedback`, `tech-review` | 编写 Java 代码涉及日志打印时 |
| rule-java-spring | `.aisdd/rules/java_spring_specification.md` | rule | Spring/Spring MVC 规范：DI/事务/控制器/拦截器/配置 | `task-executor`, `quick-dev`, `tech-generate`, `tech-review` | 编写 Spring/Spring MVC 代码时 |
| rule-java-middleware | `.aisdd/rules/java_middleware_specification.md` | rule | Spring 集成中间件规范：Redis/MyBatis/Kafka/HTTP | `task-executor`, `quick-dev`, `tech-generate` | 编写 Java 代码涉及 Redis/MyBatis/Kafka/HTTP 调用时 |
| rule-java-concurrency | `.aisdd/rules/java_concurrency_specification.md` | rule | Java 并发与线程池规范：线程池七参数与提交顺序/有界队列与选型/拒绝策略/优雅停机与 K8s 预算/CompletableFuture/锁与并发容器/ThreadLocal 与 MDC 传播/虚拟线程 | `task-executor`, `quick-dev`, `tech-generate`, `tech-review`, `test-feedback` | 编写 Java 并发、线程池、队列或异步任务代码时 |
| rule-java-springboot | `.aisdd/rules/java_springboot_specification.md` | rule | Spring Boot 工程规范：starter 与依赖治理/配置绑定与密钥外部化/自动配置/Bean 与循环依赖/Web 层 Controller 精简与统一响应/全局异常与 ProblemDetail/数据层与事务/Actuator 与探针/优雅停机/外部调用超时与熔断重试/测试分层/安全/容器化构建 | `task-executor`, `quick-dev`, `tech-generate`, `tech-review` | 项目为 Spring Boot 或编写 Boot 应用配置、分层与运行时治理代码时 |
| rule-java-webflux | `.aisdd/rules/java_webflux_specification.md` | rule | Spring WebFlux 响应式规范：选型判定/禁止阻塞事件循环/Scheduler 与 boundedElastic/BlockHound/Mono-Flux 契约与背压/错误处理/Reactor Context/R2DBC/StepVerifier 测试/资源安全 | `task-executor`, `quick-dev`, `tech-generate`, `tech-review` | 使用 Spring WebFlux / Reactor 编写响应式代码时 |
| rule-java-ai | `.aisdd/rules/java_ai_constraints.md` | rule | AI Agent 代码生成核心约束：清晰架构/避免过度编程与抽象/每个函数中文注释/敏感操作与核心逻辑日志/TODO伪代码 | `task-executor`, `quick-dev`, `task-planner`, `prd-generate` | AI 生成 Java 代码时（最高优先级约束） |
| rule-python | `.aisdd/rules/python_specification.md` | rule | Python 代码实现、.venv、日志、运行与测试规范，含 AI 代码生成核心约束（清晰架构/避免过度编程与抽象/中文注释/敏感操作日志）、SQL 操作专项规范、Redis 操作专项规范 | `task-executor`, `quick-dev`, `test-feedback`, `tech-generate` | 项目语言为 Python 或修改 Python 代码时 |
| rule-langgraph | `.aisdd/rules/langgraph_specification.md` | rule | LangGraph Agent 开发规范：StateGraph/State Schema/ToolNode/Checkpoint/中断与人工反馈/Subgraph/错误处理/观测性 | `tech-generate`, `task-executor`, `quick-dev` | 构建或修改 LangGraph Agent 项目时 |
| rule-flask | `.aisdd/rules/flask_specification.md` | rule | Flask Web 框架规范：应用工厂/Blueprint/扩展/配置分层/请求响应/错误处理/鉴权/安全头部/Jinja2/生产部署 Gunicorn | `task-executor`, `quick-dev` | 使用 Flask 构建 Web 后端时 |
| rule-fastapi | `.aisdd/rules/fastapi_specification.md` | rule | FastAPI 异步框架规范：项目结构/依赖注入/Pydantic v2/异步 SQLAlchemy/异常处理/中间件/OpenAPI/StreamingResponse/生产部署 | `task-executor`, `quick-dev` | 使用 FastAPI 构建异步 Web 后端时 |
| rule-http-restful | `.aisdd/rules/http_restful_specification.md` | rule | HTTP RESTful API 设计规范：资源命名/方法语义/状态码/统一响应/分页/排序/幂等键/限流头部/缓存控制/CORS/OpenAPI | `tech-generate`, `task-executor`, `quick-dev` | 设计或评审对外 HTTP RESTful API 时 |
| rule-python-mysql | `.aisdd/rules/python_mysql_specification.md` | rule | Python MySQL 规范：驱动选型/连接池/参数化查询/SQLAlchemy 2.0/事务边界/N+1/批量化/Alembic 迁移/慢查询 | `task-executor`, `quick-dev`, `tech-generate` | Python 项目访问 MySQL 或设计数据访问层时 |
| rule-python-redis | `.aisdd/rules/python_redis_specification.md` | rule | Python Redis 规范：redis-py/连接池/Key 命名/数据结构选型/Cache Aside/分布式锁/Pipeline/Lua/SCAN 迭代/集群 | `task-executor`, `quick-dev`, `tech-generate` | Python 项目访问 Redis 或使用分布式锁/限流/排行榜时 |
| rule-python-mq | `.aisdd/rules/python_mq_specification.md` | rule | Python MQ 规范：confluent-kafka/aiokafka/Producer 幂等/Consumer 手动 offset/幂等消费/重试队列与死信/消息落地表 | `task-executor`, `quick-dev`, `tech-generate` | Python 项目使用 Kafka/RocketMQ/RabbitMQ 时 |
| rule-js | `.aisdd/rules/js_specification.md` | rule | 前端 / JavaScript 开发规范 | `task-executor`, `quick-dev` | 项目含前端代码或修改 JS 代码时 |
| rule-node | `.aisdd/rules/node_specification.md` | rule | Node.js 后端规范：清晰架构/避免过度编程与抽象/中文注释/敏感操作与核心逻辑日志/异步错误处理 | `task-executor`, `quick-dev`, `test-feedback` | 项目语言为 Node.js 或编写 Node.js 后端代码时 |
| rule-mysql | `.aisdd/rules/mysql_specification.md` | rule | MySQL / Redis 数据访问与存储规范 | `tech-generate`, `task-executor` | 涉及数据库设计或数据访问代码时 |
| rule-redis | `.aisdd/rules/redis_specification.md` | rule | Redis 开发规范：开发规范/缓存与DB一致性/索引与数据结构设计/分布式锁/落地表SQL与索引 | `task-executor`, `quick-dev`, `test-feedback`, `tech-generate` | 涉及 Redis 缓存/分布式锁/计数等代码时 |
| rule-mq | `.aisdd/rules/mq_specification.md` | rule | MQ 消息队列开发规范：Topic与消息设计/Producer与Consumer/幂等重试死信/落地表SQL与索引设计/事务消息 | `task-executor`, `quick-dev`, `test-feedback`, `tech-generate` | 涉及 Kafka/RocketMQ/RabbitMQ 消息代码时 |
| rule-logging | `.aisdd/rules/logging_specification.md` | rule | 通用日志设计、日志级别与敏感信息约束 | `task-executor`, `test-feedback`, `tech-review` | 涉及日志实现或问题排查时 |
| rule-ui | `.aisdd/rules/ui_specification.md` | rule | UI 文档生成与界面约束规范 | `prd-generate`, `tech-generate` | 生成 UI 文档或设计界面时 |
| rule-research | `.aisdd/rules/research_specification.md` | rule | 调研类文档产出规范 | `tech-research`, `prd-generate` | 执行技术调研或产出调研文档时 |
| constitution | `.aisdd/Constitution.md` | governance | 项目最高约束文档 | `all` | 任何设计、开发、评审任务开始前 |
| project-config | `.aisdd/config.json` | config | 项目基础配置与 Pipeline 参数 | `all` | 需要读取项目配置时 |
| knowledge-index | `.aisdd/knowledges/INDEX.md` | knowledge-index | 知识库子索引，定位业务/技术知识文档 | `knowledge-init`, `tech-research`, `cause-review` | 需要追溯业务背景或技术知识时 |

