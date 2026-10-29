---
name: LangGraph Agent 开发规范
version: 1.0.0
description: 面向 AI Agent 的 LangGraph（langgraph>=0.2）开发规范。覆盖 StateGraph 编排、State schema（TypedDict / Pydantic / MessageGraph）、Node 设计原则、Edge 路由、条件分支、Checkpoint/MemorySaver、Human-in-the-Loop interrupt、流式输出 stream_mode、ToolNode 与工具调用、Subgraph 嵌套、Error handling（fallback/retry/escalate）、可观测性（thread_id / run_id）、Production 部署（LangGraph Platform）。参考 LangGraph 官方文档与 GitHub Awesome LangGraph 仓库的业界最佳实践。
---

# LangGraph Agent 开发规范

## 前言

本文档专注于基于 LangGraph（langgraph>=0.2）构建 AI Agent 应用的工程规范。LangGraph 是 LangChain 团队推出的有状态、多角色 Agent 编排框架，通过 StateGraph 模型将 Agent 工作流抽象为「节点（Node）+ 边（Edge）+ 状态（State）」三元组。

本文档参考 LangGraph 官方文档、Anthropic Claude Code 源码分析与 GitHub 上 star 过千的开源项目，提炼工业级最佳实践，为 AI Agent 生成 LangGraph 代码提供正反例对照。

约束等级分为三级：**【强制】**（必须遵守）、**【推荐】**（尽量遵守）、**【参考】**（可选参考）。


## 一、整体架构原则

### 1.1 状态驱动的设计哲学

**【强制】所有 Agent 行为必须围绕显式 State 流转，禁止使用全局变量、模块级单例或函数闭包在节点间传递状态。**

**反例：**
```python
# ❌ 错误：使用全局变量传递状态
current_user_input = None  # 全局变量

def node_a(state):
    global current_user_input
    current_user_input = state["input"]
    return {"a_done": True}

def node_b(state):
    # 依赖全局变量，不可重入、不可并发
    return {"result": process(current_user_input)}
```

**正例：**
```python
# ✅ 正确：所有状态通过 State 字段传递
from typing import TypedDict
from langgraph.graph import StateGraph

class AgentState(TypedDict):
    """Agent 全局状态定义。"""
    user_input: str
    a_done: bool
    final_result: str

def node_a(state: AgentState) -> dict:
    return {"a_done": True, "intermediate": state["user_input"].upper()}

def node_b(state: AgentState) -> dict:
    return {"final_result": process(state["intermediate"])}
```


### 1.2 State Schema 设计

**【强制】State 必须使用 TypedDict（轻量）或 Pydantic BaseModel（强校验）显式声明字段类型，禁止使用裸 dict。**

**反例：**
```python
# ❌ 错误：使用裸 dict，IDE 无法提示、运行期无校验
def node_llm(state: dict) -> dict:
    user_msg = state["messages"][-1]["content"]  # 拼写错误无法发现
    return {"answer": user_msg}
```

**正例（TypedDict 方案）：**
```python
# ✅ 推荐：使用 TypedDict，轻量且 IDE 友好
from typing import TypedDict, Annotated, Sequence
from langchain_core.messages import BaseMessage
from langgraph.graph.message import add_messages

class AgentState(TypedDict):
    """Agent 全局状态定义（TypedDict 方案）。"""
    messages: Annotated[Sequence[BaseMessage], add_messages]  # 内置 reducer
    user_id: str
    retry_count: int
```

**正例（Pydantic 方案）：**
```python
# ✅ 推荐：使用 Pydantic，强类型校验
from pydantic import BaseModel, Field
from langchain_core.messages import BaseMessage

class AgentState(BaseModel):
    """Agent 全局状态定义（Pydantic 方案，适用于复杂校验场景）。"""
    messages: list[BaseMessage] = Field(default_factory=list)
    user_id: str
    retry_count: int = 0

    class Config:
        arbitrary_types_allowed = True
```

**【强制】需要累积追加的字段（如 messages、history）必须通过 `Annotated[..., add_messages]` 等 reducer 标记，禁止直接覆盖。**

**反例：**
```python
# ❌ 错误：直接覆盖导致历史丢失
class BadState(TypedDict):
    messages: list  # 没有 reducer，每次返回都会被覆盖

def node_llm(state):
    return {"messages": [new_msg]}  # 旧的 messages 被覆盖！
```

**正例：**
```python
# ✅ 正确：使用 add_messages reducer 累积
from langgraph.graph.message import add_messages
from typing import Annotated

class AgentState(TypedDict):
    messages: Annotated[list[BaseMessage], add_messages]
```


### 1.3 节点单一职责

**【强制】每个 Node 函数只做一件事（解析 / 推理 / 工具调用 / 合成），节点函数返回 dict 仅包含其修改的字段。**

**反例：**
```python
# ❌ 错误：一个节点既做意图识别又做工具调用又做总结
def god_node(state):
    intent = classify_intent(state["messages"])
    if intent == "search":
        tool_result = search(state["messages"][-1].content)
        state["messages"].append(tool_result)
    summary = llm.invoke(state["messages"])
    state["messages"].append(summary)
    return state  # 返回整个 state，违反 reducer 语义
```

**正例：**
```python
# ✅ 正确：拆分为三个单一职责节点
def intent_parser(state: AgentState) -> dict:
    """解析用户意图。"""
    last_msg = state["messages"][-1]
    intent = classify_intent(last_msg.content)
    return {"intent": intent}

def tool_invoker(state: AgentState) -> dict:
    """根据意图调用对应工具。"""
    if state["intent"] == "search":
        result = search_tool.invoke(state["messages"][-1].content)
        return {"messages": [result]}  # 仅返回新消息
    return {}

def synthesizer(state: AgentState) -> dict:
    """综合所有信息生成最终回复。"""
    summary = llm.invoke(state["messages"])
    return {"messages": [summary]}
```


## 二、Graph 编排规范

### 2.1 StateGraph 构建

**【强制】必须显式调用 `add_node` / `add_edge` / `add_conditional_edges` 构建图，禁止动态修改已编译的 graph。**

**正例：**
```python
# ✅ 正确：清晰声明式构建
from langgraph.graph import StateGraph, START, END

workflow = StateGraph(AgentState)

# 1. 注册节点
workflow.add_node("intent_parser", intent_parser)
workflow.add_node("tool_invoker", tool_invoker)
workflow.add_node("synthesizer", synthesizer)

# 2. 注册边
workflow.add_edge(START, "intent_parser")
workflow.add_edge("intent_parser", "tool_invoker")
workflow.add_edge("tool_invoker", "synthesizer")
workflow.add_edge("synthesizer", END)

# 3. 编译
app = workflow.compile()
```

### 2.2 条件路由

**【推荐】条件分支通过 `add_conditional_edges` 实现，分支函数必须返回目标节点名称的字符串，禁止返回 bool / enum 后在 graph 中再二次判断。**

**反例：**
```python
# ❌ 错误：条件函数返回 bool，graph 中再判断
def should_continue(state) -> bool:
    return len(state["messages"]) > 5

workflow.add_conditional_edges(
    "agent",
    should_continue,
    {True: "next", False: END}
)
```

**正例：**
```python
# ✅ 正确：直接返回节点名称
from typing import Literal

def route_after_llm(state: AgentState) -> Literal["tool_invoker", "synthesizer", END]:
    """LLM 推理后路由决策。"""
    last_msg = state["messages"][-1]
    if hasattr(last_msg, "tool_calls") and last_msg.tool_calls:
        return "tool_invoker"
    if state.get("retry_count", 0) >= 3:
        return END
    return "synthesizer"

workflow.add_conditional_edges(
    "llm_reasoner",
    route_after_llm,
    {
        "tool_invoker": "tool_invoker",
        "synthesizer": "synthesizer",
        END: END,
    }
)
```


### 2.3 ToolNode 使用

**【推荐】使用 `langgraph.prebuilt.ToolNode` 管理工具调用循环，避免手工拼装 messages。**

**正例：**
```python
# ✅ 正确：使用 ToolNode
from langgraph.prebuilt import ToolNode
from langchain_core.tools import tool

@tool
def search(query: str) -> str:
    """搜索外部知识库。"""
    return knowledge_base.search(query)

tools = [search]
tool_node = ToolNode(tools)

workflow.add_node("tools", tool_node)
workflow.add_conditional_edges(
    "llm",
    lambda state: "tools" if state["messages"][-1].tool_calls else END,
    {"tools": "tools", END: END}
)
workflow.add_edge("tools", "llm")  # 工具执行后回到 LLM
```


## 三、Checkpoint 与持久化

### 3.1 MemorySaver 使用

**【推荐】开发与测试阶段使用 `MemorySaver`；生产环境必须替换为 `PostgresSaver` 或 `RedisSaver`，并通过 `thread_id` 隔离会话。**

**正例：**
```python
# 开发环境：内存 Checkpoint
from langgraph.checkpoint.memory import MemorySaver

memory = MemorySaver()
app = workflow.compile(checkpointer=memory)

# 调用时必须传 configurable.thread_id
config = {"configurable": {"thread_id": "user-123"}}
result = app.invoke({"messages": [HumanMessage("你好")]}, config=config)
```

**正例（生产环境）：**
```python
# 生产环境：Postgres Checkpoint
from langgraph.checkpoint.postgres import PostgresSaver

DB_URI = "postgresql://user:pass@localhost:5432/langgraph"
checkpointer = PostgresSaver.from_conn_string(DB_URI)
checkpointer.setup()  # 首次运行建表

app = workflow.compile(checkpointer=checkpointer)
```

### 3.2 中断恢复

**【推荐】需要人工审批的步骤使用 `interrupt_before` / `interrupt_after`，审批通过后通过相同 thread_id 恢复执行。**

**正例：**
```python
# ✅ 正确：高风险操作前中断，等待人工审批
app = workflow.compile(
    checkpointer=memory,
    interrupt_before=["payment_executor"]  # 在支付节点前中断
)

# 第一次 invoke：执行到 payment_executor 之前暂停
result = app.invoke(input_data, config=config)

# 人工审核通过后，用相同 config 恢复
result = app.invoke(None, config=config)  # 传 None 表示从断点恢复
```


## 四、流式输出

### 4.1 stream_mode 选择

**【推荐】根据场景选择合适的 `stream_mode`：`"values"`（每次返回完整 state）/ `"updates"`（仅返回节点增量）/ `"events"`（LangChain 内部事件）/ `"messages"`（逐 token）。**

**正例：**
```python
# 场景 1：UI 实时显示 LLM 生成的 token
for token, metadata in app.stream(input, config, stream_mode="messages"):
    send_to_ui(token.content)

# 场景 2：调试时查看每个节点增量
for chunk in app.stream(input, config, stream_mode="updates"):
    print(chunk)  # {"node_name": {"field": value}}

# 场景 3：业务侧拿到最终完整 state
final = app.invoke(input, config, stream_mode="values")
```

**【强制】涉及 SSE/WebSocket 推送时，前端必须能区分 LLM token 增量与工具调用结果，建议使用 `stream_mode="messages"`。**


## 五、Subgraph 与复用

### 5.1 Subgraph 嵌套

**【推荐】复杂业务流可拆分为 Subgraph，通过 `add_node("subgraph_name", subgraph_app)` 嵌入父图；父图与子图的 State Schema 必须显式声明输入输出映射。**

**正例：**
```python
# 子图：完成"搜索→总结"子任务
search_subgraph = (
    StateGraph(SearchSubState)
    .add_node("search", search_node)
    .add_node("summarize", summarize_node)
    .add_edge(START, "search")
    .add_edge("search", "summarize")
    .add_edge("summarize", END)
    .compile()
)

# 父图：嵌入子图
workflow = StateGraph(AgentState)
workflow.add_node("search_and_summarize", search_subgraph)
# 父图通过 invoke 子图时，子图自动从父图 State 中取所需字段
workflow.add_edge(START, "search_and_summarize")
```


## 六、错误处理与重试

### 6.1 节点级异常处理

**【强制】调用外部服务（LLM / HTTP / DB / Tool）的节点必须包裹 try/except，对失败分类处理：可重试错误 → 回到上游节点重试；不可重试错误 → 进入降级节点。**

**正例：**
```python
import logging

logger = logging.getLogger(__name__)

def llm_reasoner(state: AgentState) -> dict:
    """调用 LLM 进行推理。"""
    try:
        response = llm.invoke(state["messages"])
        return {"messages": [response]}
    except RateLimitError as e:
        # 可重试错误：增加计数，回到 planner
        logger.warning("LLM 限流，将重试: %s", e)
        return {"retry_count": state.get("retry_count", 0) + 1}
    except ContentFilterError as e:
        # 不可重试错误：直接进入降级
        logger.error("内容过滤触发: %s", e)
        return {"fallback_response": "抱歉，您的请求触发了内容安全策略。"}
```

### 6.2 Graph 级降级路径

**【推荐】Graph 必须显式声明 `error_fallback` 节点（或 END），任何节点异常都能汇聚到此处，避免静默失败。**

**正例：**
```python
def fallback(state: AgentState) -> dict:
    """统一降级处理节点。"""
    return {"messages": [AIMessage(content="抱歉，服务暂时不可用，请稍后重试。")]}

workflow.add_node("fallback", fallback)

# 任一节点都可以条件路由到 fallback
def route_after_error(state) -> Literal["fallback", "retry", END]:
    if state.get("retry_count", 0) >= 3:
        return "fallback"
    return "retry"
```


## 七、可观测性

### 7.1 必传字段

**【强制】每次 `invoke` / `stream` 都必须传 `configurable.thread_id`；涉及多轮对话时建议同时传 `user_id` 与 `session_id` 便于排查。**

**反例：**
```python
# ❌ 错误：不传 thread_id，会话无法持久化
result = app.invoke({"messages": [HumanMessage("你好")]})
```

**正例：**
```python
config = {
    "configurable": {
        "thread_id": f"thread-{user_id}-{session_id}",
        "user_id": user_id,
        "session_id": session_id,
    }
}
result = app.invoke({"messages": [HumanMessage("你好")]}, config=config)
```

### 7.2 LangSmith 集成

**【推荐】生产环境必须开启 LangSmith 追踪，通过环境变量 `LANGSMITH_TRACING=true` 一键启用，便于跨节点追踪、Token 统计与回放。**


## 八、Tool 与 Skill 设计

### 8.1 Tool 定义规范

**【强制】Tool 函数必须有清晰的 Docstring（LangChain 会自动读取作为工具描述传给 LLM）；参数必须使用 Pydantic 字段声明类型与描述。**

**正例：**
```python
from langchain_core.tools import tool
from pydantic import Field

@tool
def query_user_orders(
    user_id: str = Field(description="用户唯一 ID，必填"),
    status: str | None = Field(default=None, description="订单状态过滤：paid / pending / cancelled"),
    limit: int = Field(default=10, ge=1, le=100, description="返回记录数上限"),
) -> list[dict]:
    """
    查询指定用户的订单列表。

    该工具仅返回用户自己名下的订单，不返回其他用户的订单。
    调用前请确认 user_id 已通过身份认证。

    Args:
        user_id: 用户 ID。
        status: 可选，按状态过滤。
        limit: 返回记录数上限，1-100。

    Returns:
        订单列表，每个订单包含 id / amount / status / created_at 字段。
    """
    return db.query("SELECT * FROM orders WHERE user_id = %s LIMIT %s", (user_id, limit))
```

### 8.2 工具调用安全

**【强制】任何 Tool 在执行写操作（INSERT / UPDATE / DELETE / 外部 POST）前必须检查：
1. 调用方是否已通过身份认证
2. 参数是否在合法范围内
3. 是否需要二次确认（Human-in-the-Loop）**

**反例：**
```python
@tool
def delete_user(user_id: str) -> bool:
    """删除用户。"""
    db.execute("DELETE FROM users WHERE id = %s", (user_id,))
    return True  # ❌ 缺少权限校验、二次确认、敏感日志
```

**正例：**
```python
@tool
def delete_user(user_id: str, current_user_role: str) -> bool:
    """
    删除用户（管理员权限）。

    调用前必须验证 current_user_role == "admin"。
    """
    if current_user_role != "admin":
        logger.warning("非管理员尝试删除用户 user_id=%s", user_id)
        raise PermissionError("仅管理员可执行删除操作")

    logger.info("管理员删除用户 user_id=%s operator_role=%s", user_id, current_user_role)
    affected = db.execute("DELETE FROM users WHERE id = %s", (user_id,))
    logger.info("删除完成 user_id=%s affected=%s", user_id, affected)
    return affected > 0
```


## 九、生产部署

### 9.1 LangGraph Platform

**【推荐】生产 Agent 服务优先使用 LangGraph Platform 部署，获得：
- 自动水平扩展
- 内置 Checkpoint 持久化
- 异步队列（Queue）+ Worker 调度
- Studio 可视化调试
- 跨语言 SDK（Python / JS / Java）**

**正例：**
```python
# langgraph.json
{
    "graphs": {
        "agent": "./agent.py:app"
    },
    "env": ".env",
    "checkpointer": {
        "type": "postgres",
        "url": "postgresql://..."
    }
}
```

### 9.2 recursion_limit

**【推荐】通过 `config={"recursion_limit": 50}` 设置最大递归步数，防止 Agent 进入死循环；默认值为 25，建议生产设为 50。**


## 十、快速检查清单

- [ ] State 是否使用 TypedDict 或 Pydantic 显式声明？
- [ ] 累积字段是否使用 `Annotated[..., reducer]` 标记？
- [ ] 每个节点是否只做一件事、返回 dict 仅包含修改字段？
- [ ] 条件路由函数是否直接返回目标节点名称字符串？
- [ ] 外部调用节点是否包裹 try/except 并明确重试 / 降级策略？
- [ ] 每次 invoke/stream 是否传 `configurable.thread_id`？
- [ ] 写操作 Tool 是否包含权限校验 + 敏感日志？
- [ ] 生产环境 Checkpointer 是否替换为 Postgres/Redis？
- [ ] 是否有显式的 fallback 节点处理所有异常路径？