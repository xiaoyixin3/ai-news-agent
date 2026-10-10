# 实施计划

需求基线：用户提供的选项 A 与共同约束；最新要求使用 Java，模型采用 DeepSeek 兼容接口。

当前升级基线为 [docs/architecture-v1.1.md](docs/architecture-v1.1.md)，原 [docs/architecture.md](docs/architecture.md) 保留为 v1.0 快照。本计划记录实际推进状态，不表示设计中的功能均已完成。

1. Java 17 + Spring Boot + Maven 项目骨架、配置、SQLite migration、订阅接口。
2. DeepSeek 适配器、10 个工具、由模型自主决定工具顺序的 Function Calling 循环。
3. 新闻检索、来源与时间校验、结构化简报、历史及调用轨迹。
4. 时区每日调度、任务去重、站内收件箱与邮件适配器。
5. 简单中文前端、接口测试、浏览器验证与真实服务联调。
6. README、启动脚本、Git 历史、含 .git 的代码包和可选录屏。

v1.0 历史交付：Java/Maven、10 个工具、DeepSeek 协议适配、调用循环、基础新闻校验、SQLite、调度、站内/邮件渠道与中文页面；原交付包包含 .git 和 JAR，另有 28.8 秒离线演示。

v1.1 本地升级已交付（2026-10-10）：

- 已接入：002 事务升级、升级前一致性备份、migration checksum、复合归属外键、运行快照和幂等创建、单实例锁、取消裁决、原子邮件记录、独立扫描投递、UNKNOWN 和有限退避。
- 已完成：Runtime/权限/预算、DNS 实际连接绑定与压缩边界、来源与审计生命周期、Trace 和操作页面、订阅删除、备份恢复、离线评估和新版 README。
- 验收：60 个自动化用例、11 个浏览器检查流程通过；旧数据副本升级和 CLI 备份恢复保留数量，完整性/外键通过。详细对照见 [docs/acceptance-v1.1.md](docs/acceptance-v1.1.md)。
- 交付：分批保留实际 Git 历史，新版代码包含 .git、SQL、配置样例及预构建 JAR；旧 v1.0 交付包继续保留。
- 真实 DeepSeek 与 SMTP 仍待本地有效凭据联调，不作为已通过项。脚本演示不能替代真实 Agent 验收。
