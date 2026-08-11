# 项目展示与仓库收尾设计

## 1. 背景

平台已经实现双重记账、幂等支付、并发控制、可恢复对账、Transactional Outbox、RabbitMQ 消息投递和审计闭环，但仓库首页仍以使用说明为主，缺少架构总览、真实页面截图和可在三分钟内完成的演示路径。仓库也没有开源许可证，macOS 元数据文件未被忽略。

本阶段只改善项目展示和仓库完整性，不改变 Java 业务代码、数据库结构、运行配置或现有业务行为。

## 2. 目标

- 让首次访问仓库的人在 README 首屏理解项目定位、核心工程能力和技术栈。
- 用一张 Mermaid 图说明支付、账本、Outbox、RabbitMQ、通知和对账之间的关系。
- 用四张真实中文管理页面截图展示已有闭环，而不是仅靠文字声明功能。
- 提供一条可重复执行的三分钟演示流程，覆盖资金操作、可靠消息和对账异常处理。
- 增加 MIT 许可证并忽略 macOS `.DS_Store` 文件。
- 用自动化文档测试约束 README、截图和许可证，防止后续改动破坏展示入口。

## 3. 非目标

- 不修改 Java 业务逻辑、Spring Security、数据库迁移、Docker Compose 或运行中的服务。
- 不在本阶段加入 Testcontainers、REST API、Prometheus、Grafana、RBAC 或双人复核。
- 不为了截图新增演示数据接口、生产种子数据或绕过认证的入口。
- 不在 README 宣称尚未实现的能力，例如 JWT、微服务、分布式调度或 exactly-once。
- 不提交真实密码、真实客户信息、公司数据、本机绝对路径或浏览器私人信息。

## 4. README 信息结构

README 调整为以下顺序，保留现有准确的使用和约束说明：

1. 项目标题与 GitHub Actions `Build` 状态徽章。
2. 一段项目定位：面向学习和面试展示的交易账本与自动对账平台，只处理模拟资金和合成数据。
3. 四张截图组成的“界面预览”，每张图片带简短中文说明。
4. “核心能力”列表，突出双重记账、支付幂等、行锁并发控制、Spring Batch 可恢复对账、Transactional Outbox、消费去重和审计闭环。
5. “系统架构” Mermaid 图和关键边界说明。
6. “三分钟演示”入口。
7. 现有管理页面入口、技术栈、前置条件、快速启动、验证和 100,000 行演示。
8. 文档链接和许可证说明。

徽章使用当前公开仓库和工作流名称：

```text
https://github.com/32694/ledger-reconciliation-platform/actions/workflows/build.yml
```

README 不重复用户手册中的全部操作细节。完整启动、业务操作和故障处理继续链接到 `docs/USER_GUIDE.md`，迁移说明继续链接到 `docs/MIGRATION.md`。

## 5. 界面截图

新增 `docs/images/`，提交以下四个 PNG 文件：

- `docs/images/operations-overview.png`：经营概览，展示账户、支付、账本和对账的汇总信息。
- `docs/images/payment-detail-reversal.png`：交易详情，展示不可变原交易、账本分录及退款或冲正入口/结果。
- `docs/images/reconciliation-case.png`：对账异常案件，展示差异信息、处理状态和不可变时间线。
- `docs/images/messaging-operations.png`：消息运维，展示 Outbox 状态、投递尝试、RabbitMQ 队列深度和重试入口。

截图从真实运行的中文管理页面获取，使用统一桌面视口，裁去浏览器地址栏和桌面区域，确保文字清晰且四张图视觉尺寸一致。截图只使用合成、脱敏数据；不得出现密码、Cookie、令牌、本机路径、真实人员或公司信息。若页面数据不足以说明功能，只通过现有管理页面创建合成演示数据，不新增应用能力。

README 使用仓库相对路径嵌入四张图片，并为每张图提供能够描述页面目的的中文替代文本。图片不使用外部图床，保证离线克隆后仍可查看。

## 6. 系统架构图

README 使用 Mermaid `flowchart LR` 表达以下已有链路：

1. 管理员通过 Thymeleaf/HTMX 管理页面发起充值、转账、退款或冲正。
2. 支付模块在同一 PostgreSQL 事务中写入支付状态、双重记账 journal、审计事件和 Outbox 事件。
3. Outbox Publisher 使用 publisher confirm 将事件投递到 RabbitMQ。
4. 通知消费者按 `eventId` 去重并生成站内通知；永久失败消息进入 DLQ。
5. 渠道账单进入 Spring Batch 对账，与内部支付事实匹配，生成对账结果和异常案件。
6. 对账成功事件也经 Outbox 进入同一消息链路。

图中把 PostgreSQL 画成共享持久化边界，但不暗示所有模块共享未受约束的内部 API。RabbitMQ 明确标注为业务事件通知通道，不承担对账任务调度。图旁文字明确投递语义是 at-least-once，消费端通过幂等实现业务去重。

## 7. 三分钟演示

README 增加一条按顺序执行的演示流程，前提是已按“快速启动”运行全部服务并登录管理端：

1. 在账户页面创建两个合成账户，在充值页面给付款账户充值。
2. 发起一笔转账，打开交易详情检查一借一贷的平衡分录；提交全额冲正并查看原交易与反向交易互链。
3. 打开站内通知和消息运维页面，确认支付事件已经从 Outbox 发布并只生成一条通知。
4. 导入用户手册提供的合成渠道账单，等待 Spring Batch 对账完成。
5. 在异常工作台认领并解决一条差异，查看案件时间线和审计日志。

每一步只描述用户要执行的动作和应观察到的结果。幂等键使用明确的演示示例值，并提醒重复演示时更换值。复杂故障注入、100,000 行性能验证和 RabbitMQ 管理台操作不放入三分钟主路径，继续由用户手册和现有性能章节说明。

## 8. 许可证与忽略规则

仓库根目录新增标准 MIT `LICENSE`，版权年份使用 `2026`，版权持有人使用 GitHub 账号 `32694`。README 在末尾链接该许可证。

根 `.gitignore` 增加 `.DS_Store`。主工作区中现有未跟踪 `.DS_Store` 不进入提交；实现阶段删除该未跟踪文件或保持在忽略状态，最终 `git status` 不再显示它。

## 9. 自动化文档约束

扩展 `DocumentationTest`，增加一个聚焦项目展示的测试，验证：

- 根目录存在常规文件 `LICENSE`。
- 四个约定路径均存在常规 PNG 文件，并且文件大小大于零。
- README 包含 GitHub Actions 工作流徽章目标、`## 界面预览`、`## 系统架构` 和 `## 三分钟演示`。
- README 包含 Mermaid 代码块，以及 `Transactional Outbox`、`RabbitMQ`、`Spring Batch`、`at-least-once` 和 `eventId` 等架构关键信息。
- README 以相对路径引用全部四张截图。
- README 链接 `docs/USER_GUIDE.md`、`docs/MIGRATION.md` 和 `LICENSE`。

测试只约束稳定的展示入口和关键语义，不逐字锁定整段文案，也不解析 PNG 视觉内容。真实截图的中文、脱敏和页面对应关系通过人工检查验收。

## 10. 实施边界

本阶段预计只修改或新增以下路径：

- `README.md`
- `.gitignore`
- `LICENSE`
- `docs/images/operations-overview.png`
- `docs/images/payment-detail-reversal.png`
- `docs/images/reconciliation-case.png`
- `docs/images/messaging-operations.png`
- `src/test/java/io/github/user32694/ledgerplatform/DocumentationTest.java`

若获取截图时发现现有页面有功能性错误，记录为独立问题，不在本阶段顺带修改。所有文本文件沿用仓库现有格式，底层路径、技术名词和代码标识保持英文，用户可见说明使用中文。

## 11. 验证与验收

实现采用先测试后修改的顺序：先扩展 `DocumentationTest` 并观察因缺少展示材料而失败，再补 README、许可证、忽略规则和截图直至通过。

自动验证：

```sh
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger_platform_portfolio_test \
SPRING_DATASOURCE_USERNAME=ledger_app \
SPRING_DATASOURCE_PASSWORD=ledger_app \
./mvnw test
```

验收条件：

- 全部测试通过，且现有基线 `385` 个测试没有业务回归。
- GitHub Markdown 能正常渲染架构图和四张图片，图片无破损链接。
- 四张截图来自真实中文页面，内容清晰且不包含敏感信息。
- 三分钟演示步骤与现有用户手册、路由和系统行为一致。
- `LICENSE` 为完整 MIT 文本，`.DS_Store` 不再出现在 `git status`。
- 提交中不包含业务 Java 代码、数据库迁移或运行配置变更。
