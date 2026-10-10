# 实施计划

需求基线：用户提供的选项 A 与共同约束；最新要求使用 Java，模型采用 DeepSeek 兼容接口。

详细设计以 [docs/architecture.md](docs/architecture.md) 为准。本计划记录后续推进顺序，不表示功能已经完成。

1. Java 17 + Spring Boot + Maven 项目骨架、配置、SQLite migration、订阅接口。
2. DeepSeek 适配器、10 个工具、由模型自主决定工具顺序的 Function Calling 循环。
3. 新闻检索、来源与时间校验、结构化简报、历史及调用轨迹。
4. 时区每日调度、任务去重、站内收件箱与邮件适配器。
5. 简单中文前端、接口测试、浏览器验证与真实服务联调。
6. README、启动脚本、Git 历史、含 .git 的代码包和可选录屏。

当前交付：架构设计文档。用户要求先提供设计，后续实现尚未作为完成品交付。
