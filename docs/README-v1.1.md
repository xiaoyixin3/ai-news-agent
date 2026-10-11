# 每日 AI 新闻助手 Agent v1.1

Java 17 + Spring Boot 3.5.16 + SQLite + DeepSeek Function Calling。填写身份与订阅偏好，生成中文简报、查看历史与工具轨迹，按每天的时间投递。v1.1 增加可靠邮件记录、取消与重试、运行预算、只读资料边界、来源持久化、清理与备份。

## 5 分钟启动

前置条件：Java 17 或更新版本。不需要另装 Maven、Node 或数据库。解压交付包，在项目目录双击 `start.cmd`；macOS / Linux 执行 `chmod +x mvnw start.sh && ./start.sh`。

打开 <http://127.0.0.1:8080>，填写昵称、身份与关注话题，保存偏好，再点击“生成简报”。展开运行记录可查看工具调用。保持同一浏览器访问，订阅令牌保存在浏览器，数据库只保存令牌哈希；昵称不能用于找回订阅。

交付 ZIP 包含 `.git` 和已构建的 `target/ai-news-agent.jar`，可直接启动。仅有源码时，启动脚本通过 Maven Wrapper 下载依赖并构建，耗时受网络影响。修改源码后运行 `.\start.cmd -Rebuild` 或 `./start.sh --rebuild`。

默认是**显式离线演示**：合成新闻和脚本模型走同一个 Runtime，用于验证页面、存储与工具协议，不代表实时新闻或真实 LLM 自主决策。重复生成可能得到有原因说明的空简报。演示模式永不发送外部邮件，历史和统计按 demo/live 分开。

## 使用真实 DeepSeek

把 `.env.example` 复制成项目根目录的 `.env`，在本地配置；或在启动终端设置环境变量。进程环境变量优先，不要提交或分享真实 `.env`。

```powershell
$env:DEMO_MODE = 'false'
$env:DEEPSEEK_API_KEY = '<在本地填写>'
$env:LLM_BASE_URL = 'https://api.deepseek.com'
$env:LLM_MODEL = 'deepseek-flash'
.\start.cmd
```

模型名可改为账号实际支持的名称。首版明确使用 `thinking.type=disabled`。兼容网关可在 BASE_URL 中带 `/v1`，系统只追加 `/chat/completions`。真实模式缺密钥时生成接口返回 503，不降级到演示。协议参考 [DeepSeek 工具调用](https://api-docs.deepseek.com/guides/tool_calls/)与[请求格式](https://api-docs.deepseek.com/api/create-chat-completion/)。

每轮发送消息与 10 个工具，由模型返回 `tool_calls` 决定调用顺序和次数；服务执行后回传对应 `tool_call_id`，没有固定搜索、摘要、发布流水线。`publish_brief` 只接受候选，模型正常结束、最终校验和提交事务都通过后才正式保存。后续无效候选不会抹掉已接受候选；取消、超时或达到上限不会保存候选。

## 工具与权限

| 工具 | 行为与边界 |
|---|---|
| list_dir | 列出本次虚拟工作区 |
| read_file | 分段读取，单次最多 8000 字；支持 offset/max_chars |
| search_content | 搜索正文、历史、笔记和草稿，最多 20 条 |
| write_file | 仅写 notes/ 和 drafts/，单文件最多 64 KiB |
| bash | Java 只读虚拟适配，只接受 pwd/ls；不创建 Shell 子进程 |
| fetch_rss | 白名单 RSS、固定新闻时间窗口 |
| search_news | 模型自主构造 Google News RSS 查询，可换词或多次搜索 |
| fetch_article | 仅本次 source_id，可缓存只读正文；返回短摘录与分段读取路径 |
| get_history | 当前订阅同模式最近 30 份来源索引；短预览与 history/index.json |
| publish_brief | 校验并接受候选，不能直接修改邮件或运行状态 |

context.json、preferences.json、sources.json、feeds.json、历史和正文均只读。模型看不到收件邮箱或服务密钥。绝对路径、UNC、`..`、符号链接与 Windows junction 越界被拒绝。资料中的指令没有执行授权。

默认来源为 Hugging Face Blog、Ars Technica、Google News RSS。每次跳转重新验证域名与公网 IP，再把验证地址绑定至实际 HTTP 连接，保留原域名 Host、TLS SNI 与证书校验；传输采用 [Apache HttpClient DnsResolver](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/DnsResolver.html)。新闻请求最多 20 秒，DNS 等待也计入期限；最多 3 次跳转、传输与解压后响应均最多 2 MiB。Google News 和正文可用性受地区网络与网站限制影响。

受限网络可在本地 `.env` 显式设置 `NEWS_DNS_MODE=cloudflare` 与 `NEWS_SOCKS_PROXY=socks5://127.0.0.1:7897`（端口换成实际本机代理端口），重启生效；默认仍是系统 DNS 和直连，不自动读取系统代理。代理只允许本机无认证 SOCKS 端口；传给代理的是已检查的数字 IP，仍保留原新闻域名的 TLS 验证。可选 DNS 使用固定公共地址的 HTTPS 查询，响应计入网络预算，再经过相同公网地址规则；不允许工具更换 DNS 服务。Google News 跳转页面可能没有正文，少于 120 字的真实正文提取结果会拒绝缓存，证据范围继续显示 RSS 摘录。

日期、标题、链接和出处取自服务注册的来源；未知日期、未来、窗口外、规范 URL 重复以及命中排除词的候选被拒绝。排除词覆盖标题、导语、摘录、已读正文、摘要和意义。来源成功且确实无合适新闻可以发布空简报；全部来源失败会明确失败。正文未读或被截断会显示证据范围。来源合法不等于摘要事实核验，事件级语义去重仍需人审。

简洁摘要目标 100–200 字，正文摘要最多 360 字，仅 RSS 证据最多 160 字，意义说明最多 160 字、导语最多 300 字。超长提交返回字段路径、实际字数及上限，交由模型重写，不截断已接受候选。每条只总结自己的来源；明显混入另一已注册来源标题/链接会被拒绝，但该规则不能保证所有事实准确。

## 预算与运行

默认每个 run：16 轮、40 次工具调用、总时限 300 秒（含排队）、单次模型 60 秒，每个逻辑模型请求最多 3 次尝试。整批工具请求超出剩余额度时整批不执行。模型每轮会收到可信的剩余轮次、调用次数、时间及预算估计，提示预留候选发布和正常结束的空间；这不替代服务端预算硬限制。最多 2 个生成线程与 32 个等待任务，同一订阅最多一个活动 run；数据目录只允许一个服务进程。

工具批次中途超时或取消时，当前调用记录“执行中止，结果未确认”，后续逐条记录“未执行”，不继续请求模型；已产生的文件或网络操作不会回滚。超时与用户取消保留数据库实际错误码。统计只以完成的工具尝试计算成功率，中止/未执行单列；终态任务缺少结束事件时页面显示结果未确认。

默认模型输入预算 24000、累计预算 100000；这是以 UTF-8 字节加固定余量计算的保守估计，**不是精确 tokenizer 或账单额度**。发送前预留输入加 4096 输出，每次重试都预留；供应商返回 usage 后调账，usage 缺失保留预留并统计未知请求。上下文超限明确失败，不裁断 Function Calling 协议。短来源预览、分段正文和历史文件用于减少上下文增长。搜索仅返回最多 5 条短预览，不重复传送长链接，完整来源保存在只读索引中。多轮真实检索和正文阅读可能超过默认输入估计；确认模型能力后可通过 `CONTEXT_TOKEN_LIMIT=64000` 调整，本次本机联调采用该值，累计上限仍为 100000。预算可配置不代表所有检索都会成功；到达上限仍明确失败。

单次运行最多 100 个来源、累计新闻网络量 20 MiB、工作区 10 MiB。完整可配置项见 `.env.example`。API 错误、工具详情和审计会脱敏。

## 每日调度与可靠邮件

设置时间、时区并开启每日自动简报。服务每 20 秒扫描；当天到时只领取一次，晚启动补领当天，不补所有过去日期。夏令时缺失时刻向后平移，重复时刻选择第一次；日期键避免二次领取。关闭服务或电脑休眠时不会运行。

站内渠道直接保存。邮件在本地配置 SMTP_HOST、SMTP_PORT、SMTP_TLS、SMTP_USER、SMTP_PASSWORD、SMTP_FROM，再在页面选择邮件与收件人。TLS 支持 starttls/ssl，端口按服务商设置。

简报、运行成功状态与邮件 pending 记录在同一个事务中保存。独立投递器每 5 秒扫描，生成线程不等待 SMTP；提交后即使丢失通知，也能再次扫描。收件人冻结为任务创建时的邮箱，更改偏好不改已有投递。

明确临时故障有限重试，每周期最多 3 次，等待 30/60 秒；永久故障保留 failed。远端可能已接受而本地无法确认时为 unknown，不自动重发；页面提示重复风险，需主动确认且经过默认 30 秒冷却才重试。pending/failed 可取消，发送中和 unknown 不可取消。邮件失败不丢站内简报，不承诺外部邮件恰好一次。

运行记录支持取消活动任务及重新生成失败/取消任务；重试创建新 run 并保留 retry_of。创建接口支持 Idempotency-Key，同键同请求返回原 run，同键不同请求返回 409。

## 升级、保留与备份

数据库是 `DATA_DIR/news.sqlite3`。已执行的 001 保持原样，002 负责真实 v1.0 升级，003 增加配额触发器。启动核对 migration checksum、每个连接启用外键；保留订阅令牌、简报和历史数量，升级前自动在 data/backups 生成一致 SQLite 备份。不合规数据整步回滚，不静默删除。

旧事件原始 payload 可能带邮箱，保存在升级前备份中隔离；新库保留事件序号、摘要和省略标识，不把未审查旧详情直接公开。旧 sending 转 unknown。迁移失败应保留整个数据目录，查看异常；回滚到 v1.0 需先停服务，用原 v1.0 包和升级前库在**独立新数据目录**启动，不能让旧代码写 v1.1 库。升级备份不是日常备份的替代品，旧工作区和审计文件应随升级前数据目录一起保留。

兼容实现沿用小写 API 状态、ISO UTC 历史时间；deadline/window/next_attempt 等显式 `_ms` 字段使用 UTC 毫秒。模块按原工程扩展，文件名与设计样例不逐字对应，核心约束见[交付对照](acceptance-v1.1.md)。docs/schema/001_initial.sql 是设计样例，**不能覆盖生产 migration**。

后台每 10 分钟清理：终态工作区 7 天、详细审计 30 天、运行及事件摘要 90 天、简报与投递 180 天。活动任务跳过；简报保留期间保留 run 外壳与重试祖先；不确定邮件不被自动删除。外置详情缺失显示 MISSING，到期显示 EXPIRED；孤儿对象宽限 24 小时。删除简报或订阅经过归属验证与引用顺序控制，发送中/unknown 会阻止删除。删除订阅会使旧浏览器令牌失效。

**停服务后**做一致性备份与恢复；目标都必须是新目录：

```powershell
java -jar target/ai-news-agent.jar --maintenance=backup --destination=C:/backups/ai-news-20261010
java -jar target/ai-news-agent.jar --maintenance=restore --source=C:/backups/ai-news-20261010 --destination=C:/restored/ai-news
```

备份包含 SQLite 快照、引用的审计和正文、文件 SHA-256 清单。恢复先校验清单、数据库与外键，再原子落入新目录。设置 DATA_DIR 为恢复目录后启动。运行中的服务持有锁，维护备份不会越过该锁。不要直接复制仍在写入的 SQLite 主文件。

## 验证与文档

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
git log --oneline
```

当前本地 83 个自动化用例通过，包括实际 HTTP/TLS 测试服务器上的 DNS 绑定、无效证书握手拒绝、压缩炸弹、重定向、预算、中断批次审计、取消/提交、迁移回滚、归属隔离、可靠投递、保留与恢复。离线评估有 9 组策略标注，另提供 Java 逐项事实复算工具；刻意区分权限/来源校验与人工事实/语义评价，见[评估说明](evaluation.md)。

桌面与手机浏览器流程见[验收记录](acceptance-v1.1.md)。真实 DeepSeek 与新闻网络已完成一次站内端到端联调：10 轮、20 次工具调用、3 条新闻保存成功，另有 5 个真实模式浏览器检查；见[真实联调报告](live-acceptance.md)。SMTP 未配置，真实邮件接收仍待联调。单次成功不代表长期可靠性或摘要事实已全部核验。运行统计的事实支持率和语义重复率为空，不生成虚假分数。

2026-10-11 又完成 AI 产品经理和 AI 研究员两种身份的真实生成，保留研究员一次预算失败及 retry_of 重试记录。三个身份的 7 条新闻抽选 14 项陈述，辅助对照支持 12 项、不支持 2 项，不能冒充独立人工准确率；明确记录来源混入和时间表述偏差。详见[补齐记录](quality-followup.md)与[脱敏标注](evaluation/real-sample-report.json)。跨日持续观察、长期存储压力和独立人审仍待执行。

设计基线：[架构 v1.1](architecture-v1.1.md)、[工程方案](engineering-plan-v1.1.md)、[四张设计图](diagrams.html)。v1.0 历史文档保留用于追踪，不代表当前功能状态。交付仓库含分批真实 Git 提交；不修改作者日期，也不伪造开发历史。

常见问题：端口冲突设 PORT=8081；首建下载失败检查 Maven Central 网络，交付包有 JAR 可直接运行；真实生成失败检查运行记录、密钥、模型名和来源网络；空简报查看排除词与窗口，不为凑数编造条目。
