# 每日 AI 新闻助手 Agent 工程实施方案

本方案把架构 v1.1 转成 Spring Boot 模块、接口、迁移步骤和验收任务。阶段表示开发顺序，不规定 Agent 的工具顺序。

## 1 模块与接口

```text
src/main/java/cn/zaozhi/
  config/        AppConfig, RuntimeLimits, ClockConfig, SingleInstanceGuard
  controller/    SubscriptionController, RunController, BriefController
  auth/          TokenService, SubscriptionPrincipal, AuthFilter
  subscription/  SubscriptionService, SubscriptionSnapshot
  run/           RunService, RunWorker, StartupRecovery
  agent/         AgentRunner, RunBudget, Conversation, CandidateStore
  llm/           LlmClient, DeepSeekClient, LlmResponse, Usage
  tool/          AgentTool, ToolRegistry, ToolExecutor, PermissionChecker
  workspace/     WorkspaceService, VirtualPath, ArtifactStore, CleanupService
  news/          SourceRegistry, NewsSearchProvider, SafeHttpFetcher, UrlPolicy
  brief/         BriefValidator, BriefCommitService, BriefRepository
  delivery/      DeliveryDispatcher, SmtpChannel, DeliveryRepository
  scheduler/     DailyScheduler, ScheduleCalculator
  trace/         EventService, TraceAssembler, MetricsService
  repository/    ConnectionFactory, TransactionRunner, MigrationRunner
```

首版继续 JDBC 与显式 Repository，避免为 SQLite 提前引入复杂 ORM 方言。所有租户数据访问接口必须携带 subscriptionId，Repository 不能以全局 ID 直接向 Controller 返回敏感对象。

```java
interface AgentTool {
    String name();
    JsonNode schema();
    ToolResult execute(JsonNode args, ToolContext context);
}

interface PermissionChecker {
    void authorize(AgentTool tool, JsonNode args, ToolContext context);
}

interface LlmClient {
    LlmResponse complete(Conversation conversation, List<ToolSchema> tools,
                         CallBudget budget);
}

interface BriefCommitService {
    CommitResult commit(AuthorizedRun run, ValidatedCandidate candidate);
}
```

这些是接口设计样例，不是已经编译的实现。ToolContext 注入受控 WorkspaceService 和 SourceRegistry，不暴露原始数据库连接、环境变量或宿主磁盘。

RunBudget 采用单调时钟控制耗时，UTC Instant 保存审计时间；可注入 Clock 控制调度测试。策略拒绝、工具失败和模型重试均记录实际预算变化。模型返回可处理的工具错误后仍可继续选择其他工具。

## 2 数据库实施

[001_initial.sql](schema/001_initial.sql)仅用于新建空库，保留与架构一致的六张业务表。迁移执行器维护 schema_version、版本与 checksum，拒绝修改已执行 migration；每次新连接都设置并检查 foreign_keys，迁移开启事务前完成连接 PRAGMA。

新库路径：创建数据目录 → 获取单实例锁 → 连接初始化 → 版本检查 → 事务内执行建表 → foreign_key_check → 启动恢复 → 开放 HTTP。

已有库升级路径：

1. 备份数据库与外置文件，停止旧写入；核对真实表结构及已有数据。
2. 识别 user_id 是否实际等于 subscriptions.id；存在独立用户映射时不能直接改名。
3. 使用独立 `002_...sql` 建新表并映射记录，排查孤儿、活动任务冲突、重复日期和重复 brief.run_id。
4. 将大 payload 脱敏后外置；登记对象引用与哈希，超限内容记录省略。数据不合规时输出迁移异常清单，不静默删除。
5. 迁移旧 inbox/sent/failed 等投递状态；旧 sending 无法确认时映射 UNKNOWN，demo 不创建邮件任务。
6. 在备份副本上验证完整性与历史数量，明确回滚步骤；验证成功后才在目标库迁移。

本轮没有现存 app.db，因此未编写假定真实数据形态的升级迁移。样例中复合外键保证 briefs 的订阅与 mode 与其 run 一致；items 内部 source_id 在提交服务校验。SQL CHECK 对 JSON 只做字节限额，JSON 格式、字段契约由 Jackson 与校验器处理。

## 3 关键并发操作

任务创建在 `BEGIN IMMEDIATE` 的短事务中重查活动任务与待运行数量、读取订阅快照并插入 run。唯一索引冲突按业务转换为 409 或返回当日既有任务。网络调用不持事务；数据库繁忙使用有限 busy_timeout，不能无限等待。

RunWorker 使用条件更新领取任务；未更新到一行就不执行。正常终止前 BriefCommitService 在单一事务内再次确认运行可提交、保存 brief、保存可选 delivery、标记 SUCCEEDED。失败或取消处理不得覆盖成功终态。事件写入失败时，在候选提交前决定停止还是允许降级；首版建议审计无法落地就终止，失败原因保存在 runs.error_code，并保留终态写入额度。

投递器条件更新 PENDING/可重试 FAILED 为 SENDING 后提交，随后 SMTP 调用。成功更新 SENT；明确未接受的临时故障标记 FAILED 和 next_attempt_at；接受结果不确定标记 UNKNOWN。人工重试 UNKNOWN 必须显示重复风险并由用户明确发起。用户取消和投递领取同样使用条件更新。

## 4 里程碑与可直接转成 Issue 的任务

|里程碑|任务|完成标准|
|---|---|---|
|M1 数据与访问|连接初始化、建表、订阅令牌、归属查询、幂等创建|新库启动；跨订阅访问 404；孤儿和并发冲突拒绝|
|M2 Runtime 与工具|LLM 适配、预算、候选契约、10 工具、权限层、安全文件与 HTTP|模型顺序可变；SSRF 与路径越界拒绝；预算内正常结束才提交|
|M3 简报与审计|来源注册、校验器、提交事务、有界 events、对象存储与清理|来源可追溯；无效候选拒绝；提交失败全部回滚|
|M4 调度与投递|时区、每日键、单实例恢复、邮件记录与 UNKNOWN|重复扫描不重复；重启保留成功简报；不确定邮件不自动重发|
|M5 页面与评估|订阅、历史、轨迹、投递状态、手机布局、离线评测|完整用户流程可用；demo/live 分开；指标分母明确|
|M6 开源交付|Maven Wrapper、配置样例、启动脚本、README、演示流程|干净环境按文档启动；无密钥入库；说明真实联调范围|

P0 安全与失败测试随相应里程碑交付，不延后到 M5。每个 Issue 至少包含输入、预期输出、错误码、边界条件和验收方式；不要用“增加安全性”作为唯一完成描述。

## 5 验收矩阵

|类别|场景|预期|
|---|---|---|
|数据库|运行引用未知订阅|插入失败|
|数据库|简报绑定其他订阅或不同 mode 的运行|复合外键拒绝|
|并发|两个连接同时创建同订阅活动任务|只有一个成功|
|并发|重复定时扫描与手动任务竞争|日期键和活动键均守住|
|并发|相同 Idempotency-Key 重放与不同请求体|返回旧任务 / 409|
|模型协议|一轮多个工具、错序回传、未知工具、截断响应|合法协议继续；非法协议明确失败|
|结束契约|候选已接受但模型继续至超限|FAILED，无 brief 或 delivery|
|资源|重复拒绝工具、超长正文、压缩炸弹、上下文增长|次数及字节限额生效，拒绝或明确截断|
|隔离|查询他人 run/brief/event、邮件重试|404，无存在性泄漏|
|文件|绝对路径、UNC、..、符号链接、Windows junction|拒绝，不读取根目录外文件|
|网络|RSS 指向私网、IPv6、本地映射、重定向、DNS 重绑定|连接目标持续受控|
|注入|正文要求寻找密钥、改邮箱、绕过发布|执行权限不变；不泄露密钥；输出另做质量抽检|
|新闻|日期缺失、未来日期、排除词、跟踪参数 URL|候选拒绝或按规则去重|
|事务|brief 后 delivery 前或成功状态前故障|整个提交事务回滚|
|恢复|提交后通知投递器前退出|PENDING 在重启后仍可消费|
|恢复|SMTP 已接受但本地断线或进程退出|UNKNOWN；不自动重发|
|恢复|工作区写完、DB 引用前退出|孤儿对象宽限后清理|
|清理|外置详情到期、活动 run、仍保留的 brief|摘要保留；活动跳过；run 外壳保留|
|调度|Asia/Shanghai、UTC、DST 缺失与重复时刻|按明确策略每日最多领取一次|
|模式|离线演示、live 缺密钥|演示不发邮件；live 返回配置错误|
|前端|保存订阅 → 生成 → 轨迹 → 历史 → 邮件失败提示|电脑与手机可操作，终态停止轮询|

离线评估集覆盖相关新闻、同事件转载、过期内容、排除词、空结果、恶意正文与事实不支持摘要。标注证据、期望处理、允许空简报的原因和人工评价项；不以脚本模型跑通来证明真实模型自主性。

## 6 演示和验收状态

建议演示流程：创建“Java 开发者”订阅 → 设置主题和排除词 → 手动生成 → 展示工具调用的实际顺序 → 打开来源与摘要 → 查看历史 → 演示邮件失败不丢站内简报。控制在三分钟左右，离线演示全程明显标识合成数据。

本轮只验证建表样例和其约束，不宣称 AgentRunner、HTTP 边界、调度器、真实 DeepSeek、真实 RSS 或 SMTP 已实现或通过上述验收。实际凭据只通过环境变量读取，不需要为了文档评审提供密钥。
