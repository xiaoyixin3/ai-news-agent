# 每日 AI 新闻助手 Agent 设计图

四图对应架构 v1.1。图形预览见 `diagrams.html`，可编辑 Mermaid 源码在 `diagrams/`。连线表示职责与数据联系，不规定新闻工具的调用顺序。

## 1 总体架构

模型经工具执行层访问业务能力；权限、预算和提交由服务端控制。SQLite 同时保存业务状态和待投递记录，首版不依赖独立消息队列。

```mermaid
flowchart TB
  UI[中文页面] --> API[HTTP API 与令牌认证]
  API --> SUB[订阅与偏好]
  API --> RUN[任务服务]
  SCH[时区调度器] --> RUN
  RUN --> WORK[有界任务线程池]
  WORK --> AGENT[AgentRunner 与预算]
  AGENT <--> LLM[DeepSeek 模型适配器]
  AGENT --> GATE[工具 schema 与权限检查]
  GATE --> TOOLS[十个受控工具]
  TOOLS --> NEWS[来源注册与安全 HTTP]
  TOOLS --> WS[本次工作区]
  TOOLS --> CAND[候选提交与校验]
  AGENT --> COMMIT[正常结束后复检并提交]
  CAND --> COMMIT
  COMMIT --> DB[(SQLite)]
  SUB --> DB
  RUN --> DB
  AGENT --> TRACE[有界事件与脱敏详情]
  TRACE --> DB
  TRACE --> OBJ[外置审计对象]
  DB --> DELIVERY[独立邮件投递器]
  DELIVERY --> SMTP[SMTP]
  NEWS --> PUBLIC[允许的公网新闻来源]
```

## 2 执行时序

工具调用可以反复、改顺序和处理失败。图中模型正常结束后的提交是必须经过的业务关口；并非所有模型都会执行相同新闻工具序列。

```mermaid
sequenceDiagram
  participant U as 页面或调度器
  participant R as 任务服务
  participant A as AgentRunner
  participant L as DeepSeek
  participant T as 权限与工具执行器
  participant C as 校验与提交服务
  participant D as SQLite
  participant E as 邮件投递器
  U->>R: 创建任务
  R->>D: 短事务检查容量并保存快照
  R-->>U: run ID
  R->>A: 有界线程池领取
  loop 模型自主选择工具
    A->>L: 消息与工具定义
    L-->>A: tool_calls
    A->>T: 校验权限与预算并顺序执行
    T-->>A: 对应 tool_call_id 的结果或错误
  end
  Note over A,C: publish_brief 只登记有效候选
  A->>L: 回传工具结果
  L-->>A: 正常结束且没有 tool_calls
  A->>C: 最终复检与提交
  C->>D: 同事务保存简报 成功状态 可选投递
  D-->>C: COMMIT
  C-->>A: SUCCEEDED
  E->>D: 条件领取 PENDING
  Note over D,E: 提交领取状态后再访问 SMTP
  E->>E: 固定收件人邮件发送
  E->>D: SENT 或 FAILED 或 UNKNOWN
  Note over A,D: 超时 截断 无候选或预算耗尽均失败
```

## 3 数据库关系

首版以订阅为身份主体。简报通过 `(run_id, subscription_id, mode)` 复合外键与运行一致；事件直接属于运行，不属于简报。一个运行至多有一份简报，一份简报至多有一条 EMAIL 投递记录。retry_of 指向相同订阅与模式的旧运行。

```mermaid
erDiagram
  subscriptions ||--o{ runs : owns
  runs ||--o{ run_sources : registers
  runs ||--o{ events : traces
  runs ||--o| briefs : commits
  briefs ||--o| deliveries : emails
  runs o|--o{ runs : retry_of
  subscriptions {
    TEXT id PK
    TEXT token_hash UK
    TEXT profile_json
  }
  runs {
    TEXT id PK
    TEXT subscription_id FK
    TEXT status
    TEXT phase
    TEXT mode
    TEXT schedule_date
    TEXT preferences_json
    TEXT retry_of FK
  }
  run_sources {
    TEXT run_id PK,FK
    TEXT source_id PK
    TEXT canonical_url
    INTEGER published_at
    TEXT body_ref
  }
  events {
    INTEGER id PK
    TEXT run_id FK
    INTEGER seq
    TEXT step_id
    TEXT payload_state
    TEXT payload_ref
  }
  briefs {
    TEXT id PK
    TEXT run_id FK,UK
    TEXT subscription_id FK
    TEXT mode
    TEXT items_json
  }
  deliveries {
    TEXT id PK
    TEXT brief_id FK,UK
    TEXT status
    TEXT recipient
    INTEGER attempt_count
  }
```

## 4 状态机

生成与投递是独立状态机；生成的 phase 不改变活动任务判断。图中 DELIVERY_FAILED 和 DELIVERY_CANCELLED 分别对应 deliveries 表的 FAILED 与 CANCELLED。UNKNOWN 不自动重发。重启旧活动任务失败；已经成功提交的简报不回退。

```mermaid
flowchart TB
  subgraph GEN[任务生成]
    direction LR
    Q[QUEUED] -->|领取| R[RUNNING]
    Q -->|取消| C[CANCELLED]
    Q -->|重启| F[FAILED]
    R -->|正常结束并提交| S[SUCCEEDED]
    R -->|超时 超限 错误 重启| F
    R -->|提交前取消| C
  end
  subgraph MAIL[邮件投递]
    direction LR
    P[PENDING] -->|领取| W[SENDING]
    P -->|取消| X[DELIVERY_CANCELLED]
    W -->|明确接受| T[SENT]
    W -->|明确未接受| E[DELIVERY_FAILED]
    W -->|超时或重启| U[UNKNOWN]
    E -->|有限重试| W
    U -->|用户明确重试| W
    U -->|取消| X
    E -->|取消| X
  end
```
