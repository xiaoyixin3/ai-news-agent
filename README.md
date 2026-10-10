# 每日 AI 新闻助手 Agent

Java 17 + Spring Boot 3.5.16 + SQLite + DeepSeek Function Calling。填写身份与关注方向，生成中文 AI 新闻简报、查看历史和工具调用轨迹，并按每天的时间投递。

## 5 分钟启动

前置条件：安装 Java 17 或更新版本（`java -version`）。不需要单独安装 Maven、Node 或数据库。

Windows：解压代码包，双击 `start.cmd`，或在项目目录执行：

```powershell
.\start.cmd
```

macOS / Linux：

```sh
chmod +x mvnw start.sh
./start.sh
```

打开 <http://127.0.0.1:8080>。填写昵称、身份、话题等，点击“保存偏好”，再点击“生成简报”。展开“运行记录”查看工具执行过程。服务停止后数据仍保留在 `data/`；保持同一浏览器访问，以便使用本地保存的订阅令牌。

交付包包含已构建的 `target/ai-news-agent.jar`，可立即启动。如果仅克隆源码，启动脚本自动通过 Maven Wrapper 下载依赖并构建；首次构建时间受网络影响。源码修改后运行 `.\start.cmd -Rebuild` 或 `./start.sh --rebuild`。

默认是**离线演示模式**，不需要密钥。新闻是合成样例，模型响应是脚本模拟，用于展示订阅、生成、历史、去重与运行轨迹，**不能证明 LLM 自主决策，也不是实时新闻**。再次生成可能得到空简报，这是近期历史去重的结果。

## 使用真实 DeepSeek

在启动服务的同一终端中设置环境变量，或将 `.env.example` 复制为项目根目录的 `.env` 并在本地填写。进程环境变量优先；不要把真实 `.env` 提交到 Git。

```powershell
$env:DEMO_MODE = 'false'
$env:DEEPSEEK_API_KEY = '<在本地填写自己的密钥>'
$env:LLM_BASE_URL = 'https://api.deepseek.com'
$env:LLM_MODEL = 'deepseek-flash'
.\start.cmd
```

```sh
export DEMO_MODE=false
export DEEPSEEK_API_KEY='<在本地填写自己的密钥>'
export LLM_BASE_URL=https://api.deepseek.com
export LLM_MODEL=deepseek-flash
./start.sh
```

模型可改为账号实际支持、具备工具调用能力的名称。兼容网关可在 `LLM_BASE_URL` 里带 `/v1`，系统追加 `/chat/completions`，不会再追加 `/v1`。真实模式缺少密钥时生成接口返回 503，不会自动降级成演示。

循环每轮将消息与 10 个 Function Calling 工具发给模型，按模型返回的 `tool_calls` 执行并回传 `tool_call_id`。模型自行决定顺序、次数、换源和结束时间。Java 没有写死“搜索→摘要→发布”。简报必须由 `publish_brief` 校验通过，且模型正常结束，才事务入库。协议依据：[DeepSeek 工具调用文档](https://api-docs.deepseek.com/guides/tool_calls/)。

## 工具与来源

| 工具 | 功能 |
|---|---|
| list_dir / read_file / search_content / write_file | 本次运行工作区的目录、读取、搜索与写入 |
| bash | 真实 Shell 子进程，仅接受 `pwd` 或 `ls`；Windows 对应 PowerShell，子进程不继承密钥 |
| fetch_rss | 读取白名单公开 RSS，按实际发布时间筛选 |
| search_news | 模型主动构造 Google News RSS 查询，可重复搜索 |
| fetch_article | 阅读本次已取得来源的公开正文 |
| get_history | 当前用户最近 30 份简报来源索引，用于去重 |
| publish_brief | 提交结构化简报候选，校验来源、时间、排除词、数量与重复 |

默认源：Hugging Face Blog、Ars Technica、Google News RSS。RSS 和网页访问受公开域名白名单、重定向校验、20 秒总时限、2MB 大小限制约束；Google News 可用性取决于地区网络。来源不可达时模型收到工具错误，可以调整策略。部分网站限制正文抓取，Google News 链接也不保证能够取得原站正文。

源标题、链接、出处、发布时间来自服务端取得的来源索引；模型只填摘要和与你相关的意义。未知日期、过期、精确重复、命中排除词的条目不能发布。空简报可以发布，但必须说明原因。摘要事实和事件级语义去重仍需要人审，来源校验不等于事实核验。

## 每日调度与邮件

设置每日时间、时区并勾选自动简报。服务每 20 秒扫描；当天到时只领取一次，晚于设定时间启动会补领当天，不补发之前所有日期。关闭服务或电脑休眠时不会运行。定时任务失败保留记录，可手动重试。

站内渠道不需要额外配置。邮件渠道在 `.env` 或环境变量中配置：

```text
SMTP_HOST=
SMTP_PORT=587
SMTP_TLS=starttls
SMTP_USER=
SMTP_PASSWORD=
SMTP_FROM=
```

`SMTP_TLS` 支持 `starttls` 或 `ssl`（SSL 通常使用 465 端口，需按服务商配置）。再在页面选邮件并填写收件邮箱。演示模式永远不发送外部邮件。邮件失败保留站内简报，允许重试；同一份失败邮件原子领取后发送，不能并发重复重试。SMTP 超时可能发生在远端已经收信后，因此不保证外部邮件恰好一次。

## 配置与存储

完整示例在 `.env.example`：`PORT`、`DATA_DIR`、`MAX_ROUNDS`（16）、`MAX_TOOL_CALLS`（40）、`RUN_TIMEOUT`（300 秒）、`LLM_TIMEOUT`（60 秒）、`SCHEDULER_ENABLED`。最多并行 2 个生成任务，等待队列 32 个，同一订阅最多一个活动任务。

SQLite 自动建表并记录迁移版本，迁移 SQL 位于 `src/main/resources/migrations/001_initial.sql`。真实数据和每次运行工作区在 `data/`，不进入交付包。启动会将遗留排队/运行任务标记为中断失败；重新生成即可。

本地令牌隔离不同订阅，数据库仅保存令牌哈希。昵称用于展示，不是登录凭据。丢失浏览器令牌不能仅凭昵称找回，可重新创建订阅。服务默认只监听本机；当前版本是本地单实例应用，公网账户、恢复流程和分布式部署不在首版范围。

## 测试与交付

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
git log --oneline
```

测试覆盖工具顺序变化、错误恢复、同一响应中的多个调用、未发布/截断/超限失败、来源校验、路径和 Windows junction 越界、队列容量、每日去重、任务事务、重启恢复、超时、邮件失败重试和用户访问隔离。浏览器已验证保存→生成→来源→轨迹→历史→刷新持久化，以及桌面和手机布局。

真实 DeepSeek 与 SMTP 联调需要本地有效凭据，目前交付验收明确区分离线测试与真实服务验证。详见 [验收记录](docs/acceptance.md)、[架构设计](docs/architecture.md)、[演示步骤](docs/demo.md)。

常见问题：

- 端口被占用：设置 `PORT=8081` 后重启，打开相应端口。
- Maven 下载失败：检查访问 Maven Central 的网络；交付包已有 JAR 时可直接 `java -jar target/ai-news-agent.jar`。
- 真实生成失败：查看运行记录，检查密钥、模型名、模型和新闻来源网络。错误会脱敏，不输出密钥。
- 没有新闻：检查时间窗口和排除词；有依据的空简报优于凑数。
