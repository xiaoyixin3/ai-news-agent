# 每日 AI 新闻 Agent：实施与验收计划

## 目标
按两张需求截图实现选项 A。Python 后端、简单中文前端，支持身份与偏好订阅、每日生成和推送、历史查看。模型提供方采用用户指定的 DeepSeek 兼容接口。

## 技术与边界
- FastAPI + 原生 HTML/CSS/JavaScript：无需 Node 构建，5 分钟启动。
- SQLite + 建表 migration；本地单进程运行，默认仅绑定 127.0.0.1。
- 手写 function-calling 循环：每轮把完整工具定义、消息历史、执行结果交给 LLM；模型决定工具、顺序、次数及结束。轮次、工具次数和总时长仅作为上限。
- 新闻来源：RSS、Google News RSS 搜索、公开文章。模型可以自主选来源、改关键词、补充阅读。返回来源、发布时间、链接；失效源返回工具错误供模型调整。
- 10 个工具：list_dir / read_file / search_content / write_file / bash / fetch_rss / search_news / fetch_article / get_history / publish_brief。
- 文件工具限单次运行工作区；bash 提供受限只读 shell 命令；网络工具限制公开 HTTP(S) 地址、重定向和大小。
- 简报先写站内收件箱，用户可选邮件。SMTP 参数和模型密钥只从环境变量读取。
- 每日时区调度、数据库唯一键防重复；手动生成；重启恢复中断记录；失败可手动重试。
- 每个订阅使用随机访问令牌隔离历史。定位本地演示 / 作业项目，不含公网账户系统。
- 离线演示显式标注，使用合成新闻与脚本化模拟模型，仅方便无密钥验收页面与流程；真实模式不会退回演示。

## 推进步骤
1. 需求和架构记录进 Git。
2. 实现数据库、工具、DeepSeek 适配器、LLM 循环、调度和推送。
3. 实现订阅表单、简报历史、执行状态及工具轨迹。
4. 验证非固定工具顺序、失败恢复、权限隔离、调度去重、推送与浏览器交互。
5. 交付含 .git 的代码包、README、一键启动、验收说明和可选演示录屏。

## 验收对应
| 原要求 | 交付证据 |
|---|---|
| Python 或 Java | Python 3.11+ 项目 |
| 至少 5 个工具 | app/tools.py 中 10 个工具及 schema |
| LLM 决定调用顺序 | app/agent.py；任意顺序模拟响应测试；页面工具调用记录 |
| 每日自动简报和推送 | app/service.py 时区调度；SQLite inbox；SMTP 邮件 |
| 订阅与历史前端 | app/static/ 页面及 API |
| Git 提交历史 | 本地 .git 与打包中的提交历史 |
| 密钥环境变量 | .env.example；.gitignore 排除 .env |
| 一键依赖环境 | requirements.txt；start.ps1 / start.sh |
| 建表 SQL / migration | migrations/001_initial.sql |
| 5 分钟启动说明 | README.md |
| 可选录屏 | 不超过 3 分钟的浏览器演示（环境允许时） |

## 待用户本地配置
真实调用需要 DEEPSEEK_API_KEY；邮件需要 SMTP 凭据。没有这些凭据时仍完成真实模式代码与协议模拟验证，并清楚列出尚未进行的真实服务验证。
