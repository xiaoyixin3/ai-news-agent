# 离线评估与真实服务验收

评估数据：`src/test/resources/evaluation/news-cases.json`，执行器：`OfflineEvaluationTest`。运行 `.\mvnw.cmd test` 会验证 9 组策略样本；时间相对 run 创建时的冻结窗口计算，避免样本自然过期造成假失败。

| 样本 | 自动结果 | 人工项 |
|---|---|---|
| 相关新闻 | 接受来源与候选 | 核对事实、身份相关性 |
| 同事件不同站点转载 | 不同 URL 可接受 | 标注为事件级重复 |
| 过期 / 未来 | 拒绝 | 确认时间证据 |
| 正文排除词 | 拒绝 | 检查正文抽取范围 |
| 成功检索的空结果 | 可发布空简报 | 理由是否合理 |
| 恶意正文 | 文件越界、只读改写、env 命令拒绝 | 真实模型是否受指令误导另测 |
| 无证据“提升 500%”摘要 | 来源合法校验可接受 | 标注事实不支持，不能计事实验证成功 |
| 跟踪参数重复 | 规范 URL 去重拒绝 | 无额外语义判断 |

自动测试刻意保留两个盲区：转载和无证据摘要可通过结构/来源校验，提醒评审者不要把链接合法当成新闻质量达标。当前事实支持率、语义重复率不设默认 100%，页面显示待人工标注。

真实验收记录表包含日期、模型/模式、身份与窗口、来源 URL/证据范围、run_id、工具序列、usage、最终状态和邮件状态。逐条摘要拆为可核验事实，分别标注 supported / unsupported / uncertain，并给出当前来源的原文依据；新闻另外标注事件组。按 v1.1 架构口径：事实支持率 = 来源支持的事实数 / 全部核验事实数，uncertain 纳入分母并单列。事件重复率 = 同一份简报中组内多余条目数 / 条目总数；不同身份收到同事件不算单份简报重复。未完成全条事件标注时该率为空。

## 2026-10-11 真实样本与复算工具

已经取得 Java 开发者、AI 产品经理和 AI 研究员三种身份的 3 次成功真实生成，共 7 条新闻。额外保留研究员一次预算失败，修复字段长度反馈后以 retry_of 创建新任务，7 轮、11 工具成功。详细运行与脱敏标注见 [real-sample-report.json](evaluation/real-sample-report.json)，本次整体记录见 [补齐记录](quality-followup.md)。

每条选择 2 项陈述，共 14 项，辅助对照支持 12 项，不支持 2 项，不可判断 0 项；辅助样本支持率 12/14（85.7%）。两处问题为旧 InfoQ 条目混入其他新闻，以及产品经理条目声称“两天完成七个模型项目”但当前原文不足以支持该时长。事件组在各自简报中无重复；这是辅助标注、小样本、非随机抽查，不能代表全部事实准确率、独立人审或长期质量。站内人工质量指标继续为空。

Java 离线工具 `cn.zaozhi.evaluation.QualityEvaluation` 验证来源证据哈希、逐字引文是否属于当前条目、全条标注覆盖、重复标注和统计分母。引文匹配只证明引用确实存在，不证明引文能推出所标事实；事实判断由标注者负责。reviewer.kind 必须标明 human / assisted / unreviewed；assisted 不填充 human 的 fact_support_rate。模板默认 unreviewed，不能将默认模板当核验结果。

交付包含工具和脱敏标注摘要，不含抓取全文；本机复算输入保留在 `work/v11-implementation/quality-20261011`。自行准备 bundle 与 labels 后，可直接用交付 JAR 运行，无需额外依赖：

```powershell
java '-Dloader.main=cn.zaozhi.evaluation.QualityEvaluation' -cp target/ai-news-agent.jar org.springframework.boot.loader.launch.PropertiesLauncher --template bundle.json labels.json
java '-Dloader.main=cn.zaozhi.evaluation.QualityEvaluation' -cp target/ai-news-agent.jar org.springframework.boot.loader.launch.PropertiesLauncher bundle.json labels.json report.json
```

bundle.runs 每项包含 run_id、role、brief.items 和 evidence；items 包含 source_id、evidence_scope；evidence 以 source_id 为键，包含 text、scope 和 text 的 SHA-256。labels.reviewer 指明 kind/name；reviews 对每项 run_id/source_id 包含 event_id、facts 数组，每个 fact 为 claim/verdict/quote。有支持标签须提供当前来源中的非空 quote。未审事实可留空，指标不会自动补为 100%。

后续仍需独立人审、扩大跨日期样本、SMTP 受控邮箱接收及供应商断线/重启联调。凭据只留本地环境变量，不写报告、录屏或 Git。
