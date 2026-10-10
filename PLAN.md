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

v1.1 升级进行中（2026-10-10）：

- 已接入：002 事务升级、升级前一致性备份、migration checksum、复合归属外键、运行快照和幂等创建、单实例锁、取消裁决、原子邮件记录、独立扫描投递、UNKNOWN 和有限退避。
- 数据/投递批次验收：新增 V11PersistenceTest 的 10 个故障与约束用例通过；兼容回归共 29 个测试通过。
- 正在推进：Runtime/权限/预算、DNS 绑定与压缩边界、来源与审计生命周期、Trace 和操作页面、离线评估、交付包更新。
- 真实 DeepSeek 与 SMTP 仍待本地有效凭据联调，不作为已通过项。脚本演示不能替代真实 Agent 验收。
