# corpus/ 语料说明

当前索引为 **corpus-v3 双语料**：本目录的 JavaGuide 7 篇（561 chunks）+ 外部课程笔记 190 篇（3732 chunks），合计 4293 chunks。

## 来源一：JavaGuide（本目录）

全部文档取自 [JavaGuide](https://github.com/Snailclimb/JavaGuide)（GitHub 上最流行的中文 Java 知识库之一），经 jsdelivr CDN 下载，获取日期见 git 历史或文件时间戳。

| 文件 | 原始路径（JavaGuide 仓库） |
|---|---|
| Redis核心问题.md | docs/database/redis/redis-questions-01.md |
| Redis持久化.md | docs/database/redis/redis-persistence.md |
| MySQL核心问题.md | docs/database/mysql/mysql-questions-01.md |
| Java并发核心问题.md | docs/java/concurrent/java-concurrent-questions-01.md |
| 计算机网络核心问题.md | docs/cs-basics/network/other-network-questions.md |

## 来源二：南大软件学院课程笔记（外部目录，不入仓库）

- 出处：学长 SpriCoder（张洪胤，NJU 软院 → 清华，Apache IoTDB Committer）的公开博客
  [spricoder.github.io](https://spricoder.github.io/)（GitHub Pages 仓库渲染产物）
- 获取：2026-10-02 经 gcore.jsdelivr.net CDN 抓取 209 篇（190 讲义入语料 + 18 考试题单独存放
  只作出题素材，1 篇 MIT-6.824 过薄剔除），HTML 提取正文转 markdown，存放于
  `D:\简历\课程资料\corpus-course\`，抓取脚本与清单在该目录
- 覆盖 17 个课程系列：操作系统 / 计组 / 计网 / 数据库×2 / 数据结构 / 编译 / Linux×2 /
  软件系统设计 / 软工II / DevOps / 软件测试 / DDIA·凤凰架构·Java并发艺术读书笔记
- **授权状态（诚实记录）**：该博客仓库无 LICENSE（其 Tec-Be 仓库是 MIT 但课程笔记不在其中）。
  仅本地个人学习使用，不进任何公开仓库（`.gitignore` 排除 `corpus/**/*.md`），
  已向本人打招呼/待打招呼状态见 `课程资料/SOURCE-来源与授权.md`

## 用途与许可注意

- 仅用于**本地个人学习**：作为 doc-rag 检索问答系统的语料
- 版权归各原作者所有；**不要把语料内容重新分发**；公开仓库只保留来源链接
- markdown 里的图片链接指向原仓库/CDN，本地不解析，不影响检索（课程笔记的课件截图信息
  在 HTML 提取时丢失——已知语料质量限制，向原作者索取 PDF 可改善）

## 为什么是这批语料

1. **JavaGuide**：后端核心知识四大领域，术语密集适合考察 BM25，概念解释适合考察向量检索
2. **课程笔记**：规模 7.7 倍于 JavaGuide（4293 chunks），17 门课之间的概念关联
   （OS 缺页↔计组 TLB、数据库索引↔DDIA 存储引擎）是**多跳评测题**的天然素材；
   配合 `eval/course-eval.jsonl`（v1.5，46 条，含同学真实提问与跨课程多跳题）
3. 考试题库（18 篇）**刻意不进语料**——防止评测变成背题

## 灌入状态

- corpus-v2（JavaGuide，frontmatter 剥离后 561 chunks）：2026-09-30
- corpus-v3（+190 篇课程笔记 → 4293 chunks）：2026-10-02，异步入库 197/197 零失败，
  其中前 20 篇为削峰演练（峰值积压 17、排空 49s、消费 24.5 篇/分钟、受理 p99≈142ms）
- 用 `GET /api/documents` 可随时核对文档与 chunk 数
