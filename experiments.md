# 实验记录（experiments.md）

> 本文件是评测驱动开发的完整证据链：每一次配置变更、每一个数字、每一个失败用例的归因。
> 对外引用的每个数字都必须能在这里找到出处。原始报告 JSON 存于 `eval/result-*.json`。

---

## 实验环境

| 项 | 值 |
|---|---|
| 机器 | Windows 11 笔记本（开发机，非服务器） |
| 语料 | JavaGuide 7 份中文文档（Redis×2 / MySQL / Java并发×3 / 计算机网络），568 chunks（实验 10 起为剥离 frontmatter 的 corpus-v2：561 chunks） |
| Embedding | 阿里云百炼 `text-embedding-v4`（1024 维） |
| 生成模型 | 阿里云百炼 `qwen-plus`，temperature=0.1 |
| 检索配置 | ES 8.15.3 单索引（BM25 + dense_vector script_score），chunk=500/overlap=80，candidateK=30，rrfK=60，minVectorScore=0.35，topK=8 |
| 评测集 | `eval/my-eval.jsonl` v1.3，48 条（43 可答 + 5 拒答） |
| 日期 | 2026-09-24 |

---

## 实验 0：评测集设计的三次迭代（最重要的教训）

**v1（35 条，全术语题）→ 三配置全部 recall=1.0，零区分度。**

原因一：题目从文档标题/关键词出（"ThreadPoolExecutor 核心参数"），题面与文档词面高度重合，BM25 精确匹配即可命中。
原因二：**recall 是文档级匹配**——top-8 里出现任意一个正确文档的 chunk 就算命中。7 篇文档的小语料下，standard 分词器的中文单字匹配让"随便一篇正确文档的随便一个 chunk"几乎必然进 top-8。

**v1.1（+13 条口语化改写题）→ 文档级 recall 依旧饱和（lexical 1.0），但暴露了 para-03 的向量漏检。**

改写题设计：题面刻意避开文档原词（"程序突然断电了，内存里还没写到磁盘的数据怎么办" → redo log）。

**v1.2（补 chunk 级金标 expectedContent）→ 终于有了区分度。**

每条用例标注正确 chunk 的独享短语（如 para-03 → `redo`，全语料唯一，经 `scripts/verify-terms.ps1` 验证）。ContentRecall@K = 金标短语出现在任一被检索 chunk 内容中的比例。

**教训**：
1. 评测集会失效——饱和的指标等于没有指标。发现"所有配置都满分"时，第一反应应该是怀疑评测集而不是庆祝。
2. 小语料上文档级召回没有意义，必须下沉到 chunk 级内容金标。
3. 金标短语的唯一性要脚本化验证（QUIC 在 Redis 文档里被 quicklist 撞车、SYN 全是 synchronized 的子串、React 撞 Reactive——不验证就会埋进假金标）。

---

## 实验 1：检索 A/B（48 条，skipGeneration=true，零生成成本）

| config | SrcRecall@8 | **ContentRecall@8** | ContentHit@8 | mean ms | p95 ms | 漏检用例 |
|---|---|---|---|---|---|---|
| ① lexical-only | 1.000 | **0.884** | 0.884 | 57 | 117 | redis-03, network-01, para-03, para-08, para-12 |
| ② vector-only | 0.979 | **0.953** | 0.953 | 270 | 442 | para-01, para-03 |
| ③ hybrid (RRF) | 0.979 | **0.953** | 0.953 | 276 | 691 | network-01, para-03 |
| ④ hybrid + rerank | TODO（重排服务未部署） | | | | | |

原始报告：`eval/result-hybrid-baseline.json` / `result-lexical-only.json` / `result-vector-only.json`

### 结论

1. **混合 > 纯关键词：ContentRecall@8 0.953 vs 0.884（+6.9pp）**。纯关键词漏掉全部口语化改写题中的两条（para-08 QUIC、para-12 ThreadLocal 弱引用）——题面词与文档词不重合时 BM25 无能为力，这正是向量检索存在的理由。
2. **混合与纯向量打平（0.953 = 0.953）但失败集不同——互补性被量化了**：
   - 向量漏 para-01（AOF 重写，精确术语场景），混合靠词法信号救回 ✓
   - 向量命中 network-01，混合反而漏 ✗（见下面的 RRF 共识偏差）
   - 若保住两者，混合可达 0.977。差距就是第 3-4 周调优（权重/rrfK/重排）的空间。
3. **延迟代价清晰**：词法 57ms → 混合 276ms（多出的 ~220ms 几乎全是 embedding 调用，两路检索本身并行）。
4. **para-03 三配置全漏**——评测集里最难的用例，见失败归因。

### 失败归因（四类）

| 用例 | 现象 | 归因 | 分析 |
|---|---|---|---|
| para-03 | 三配置都检索不到 MySQL redo chunk | **跨文档语义混淆** | "突然断电后内存数据怎么办"在语义空间里离 Redis 持久化文档（大量"断电丢数据"内容）更近，把 MySQL redo log 章节挤出 top-8；词法侧"断电"二字在 redo 章节也不出现（文档用词是"崩溃"）。候选解法：查询改写（断电→崩溃恢复/durable）、重排、或语料侧补充同义表述 |
| network-01 | vector 命中，hybrid+lexical 漏 | **RRF 共识偏差** | 正确 chunk（传输层 TCP/UDP 定义，仅两行）只在向量路排名靠前，词法路 top-30 无它（TCP/UDP 两词在长 chunk 里出现密度更高的协议列表章节抢占了排名）；RRF 下"两路都中游"的平庸 chunk 分数 ≈ 2/61 高于"单路第一"的正确 chunk ≈ 1/61，被挤出 top-8。候选解法：重排（交叉编码器会给正确 chunk 高分）、调低 rrfK 放大头部排名权重、或向量路加权 |
| redis-03 | lexical 漏 everysec chunk | **chunk 级精度不足** | 题面 RDB/AOF 与持久化文档大量 chunk 词面匹配，BM25 top-8 被其他 RDB/AOF chunk 占满，含 everysec 的 fsync 策略 chunk 排不进 |
| para-08, para-12 | lexical 漏 | **词面失配（设计预期内）** | 口语化改写题就是为暴露这一点设计的 |

---

## 实验 2：完整评测（hybrid，skipGeneration=false，48 次生成调用）

| 指标 | v1 运行（修复前） | **v2 运行（最终）** |
|---|---|---|
| ContentRecall@8 | 0.953 | **0.953** |
| 引用准确率 | 1.000 | **0.998** |
| 关键点覆盖率 | 0.875 | **0.865** |
| 拒答准确率 | 0.917 (44/48) | **0.979 (47/48)** |
| mean / p95 延迟 | 5635 / 10793 ms | **5542 / 10312 ms** |

原始报告：`eval/result-hybrid-full-v2.json`（v1 为 result-hybrid-full.json）

两次运行间的差异（引用 1.000→0.998、覆盖 0.875→0.865）来自 temperature=0.1 下仍存在的生成随机性——**同一配置重复跑会有 ±1-2pp 波动，比较配置差异时要看是否超出这个噪声带**。

### 关键验证结论

1. **引用准确率 0.998（48 条）**：字母标签 `[A]` + 有效标签白名单方案在规模化评测下成立。此前数字标签方案的引用准确率在受影响用例上是 0%（见 README 踩坑记录 #2/#3）。
2. **拒答准确率 0.917→0.979**：修复"哨兵后置误判"后，4 个误判消除了 3 个。修复内容：模型给出完整答案后在末尾追加 `INSUFFICIENT_CONTEXT` 作为警示语（"…但 excerpts 未覆盖 X"），`contains()` 判定会把它误判为拒答；改为**哨兵前置才算拒答**（`trim().startsWith()`），末尾追加视为"弱依据作答"。已加回归测试钉住。
3. **唯一剩余拒答误判 network-05 是标注本身可辩**：OSI 各层作用——语料只列了层名+一句话+外链，excerpts 确实缺每层细节，模型有时选择拒答其实合理。教训：**评测标注自己也会错**，这类用例要么补充语料要么标记为边界用例，而不是硬算模型的账。

### 生成层失败归因（keyPointCov 0.865 的缺口在哪）

| 用例 | cov | 归因类别 | 说明 |
|---|---|---|---|
| redis-04 / para-01 | 0.0 / 0.5 | **生成丢失事实** | 检索命中了 AOF 重写 chunk，答案讲了触发条件/BGREWRITEAOF，但没提 fork 子进程机制（金标短语在 chunk 里，模型没用上） |
| mysql-04 / para-02 | 0.5 / 0.5 | **生成丢失事实** | 答案讲了 MVCC 但没提 undo 版本链 |
| para-11 | 0.0 | **生成过浅** | 只答出"这叫乐观锁"，没展开版本号/CAS 机制（检索命中了乐观锁 chunk） |
| para-03 | 0.0 | **检索失败的下游灾难** | 检索回 Redis AOF chunk，模型基于错误文档给出流畅、带有效引用（cite=1.0）、完全误导的答案——"突然断电会丢数据，取决于 appendfsync 策略"。**这是 RAG 最危险的失败模式：引用有效≠内容正确，只看答案流畅度和引用指标都会漏掉它，只有 chunk 级金标能抓住** |
| network-01 | 0.0 | 检索失败（RRF 共识偏差）下游 | 同 para-03 链条，本轮模型未拒答而是基于弱相关 chunk 作答 |

失败分布汇总（48 条）：检索失败 2、生成丢失/过浅 5、标注可辩 1、金标过严 1（para-04，v1.3 已修）。

---

## 实验 3：评测框架自身的两个 bug（本日修复）

1. **skipGeneration 名不副实**：文档声称"检索层评测零 LLM 成本"，实现里却无条件调用 `ragService.answer()`（含生成）。修复：新增 `RagService.retrieveOnly()`，skipGeneration 走纯检索路径。
2. **评测走答案缓存**：10 分钟 TTL 内重跑同一评测集会命中缓存，延迟数字测的是 Redis 不是管线。修复：`answer(question, topK, allowCache)` 三参重载，评测恒传 false。

两个 bug 的共同教训：**评测代码和生产代码同等重要，评测说谎比没有评测更糟。**

---

## 实验 4：重排（gte-rerank-v2，DashScope 原生 API）

配置：hybrid + `rag.enable-rerank=true`，`RerankerConfig` 工厂按 `rerank.provider=dashscope` 装配 `DashScopeReranker`（响应格式 `output.results[{index,relevance_score}]`，与 Jina 风格不同故独立适配器）。生效性经独立验证（enableRerank=True、rerankMillis>0、rerankSkipped 为空）后才跑评测——**防止配置未绑定产生假标签数据**。

| config | ContentRecall@8 | ContentHit@8 | mean ms | p95 ms | 漏检 |
|---|---|---|---|---|---|
| hybrid（对照，实验1） | 0.953 | 0.953 | 276 | 691 | network-01, para-03 |
| **hybrid + rerank** | **0.953** | **0.953** | 262 | 391 | network-01, para-03 |

**结果：重排零收益。** 归因（两条漏检各有原因，都指向重排的能力边界）：

1. **para-03——重排救不了"没被召回"**：金标 chunk（MySQL redo log）没进任何一路的 top-30，根本不在重排的候选池里。**重排只能重排召回的候选，召回失败必须在上游治**（查询改写：断电→崩溃恢复；或语料侧补同义表述）。
2. **network-01——金标过窄，重排的判断其实合理**：金标 chunk（传输层 TCP/UDP 两行定义）在候选池里，但 gte-rerank 把"HTTP/3 为什么放弃 TCP"等深入对比 TCP/UDP 的 chunk 排得更高——对"TCP 和 UDP 有什么区别、用在什么场景"这个问题，那些 chunk 可以说支撑力更强。归类为**标注可辩**：问题存在多个合理支撑 chunk，金标却只绑定了其中一个。

3. **延迟账**：重排单次调用 ~50-100ms（rerankMillis 实测），mean/p95 与对照组的差异（262/391 vs 276/691）在 embedding 网络抖动噪声带内，不能声称"重排降低了延迟"。

**结论与决策**：当前语料与评测集下重排收益未显现，但机制保留（开关+自适应阈值 `rag.rerank-min-candidates`）。**优先级调整：查询改写（治 para-03 类召回失败）排在重排调优之前**——这是数据驱动优先级决策的实例。

原始报告：`eval/result-hybrid-rerank.json`

---

## 实验 5-7：融合参数敏感性（rrfK / 权重 / minVectorScore）

| 实验 | 配置 | ContentRecall@8 | 漏检 | 结论 |
|---|---|---|---|---|
| 5a | rrf-k=10 | 0.953 | network-01, para-03 | 与 k=60 无差异 |
| 5b | rrf-k=100 | 0.953 | network-01, para-03 | 与 k=60 无差异 |
| 6 | vec-weight=2.0 | 0.953 | network-01, para-03 | 向量路加倍权重仍救不回 network-01 |
| 7a | min-vector-score=0.2 | 0.953 | network-01, para-03 | 阈值放宽无影响 |
| 7b | min-vector-score=0.5 | 0.953 | network-01, para-03 | 阈值收紧无影响 |

**三个"调了没用"的结论比"调了有用"更有信息量：**

1. **rrfK 不敏感**：k 只影响"已进候选"的排名折算曲线。两条漏检用例的根因一个是"金标没进候选"（para-03），一个是"重排都认可别的 chunk"（network-01）——都不在 k 的作用域内。
2. **权重 2x 不够**：network-01 的金标 chunk 只在向量单路出现，2/(60+r) 仍不敌两路共识 chunk 的 1/(60+r1)+1/(60+r2)。要翻转需要更极端的权重，但那会牺牲 para-01 这类词法救回的用例——**权重是零和的，重排才是正解**（虽然本评测集上重排也判了别的 chunk 更高，见实验 4 归因 2）。
3. **minScore 0.2-0.5 无感**：金标 chunk 对该 query 的相似度要么远高于 0.5、要么低于 0.2（para-03 属于后者），阈值在中间地带扫过没有边际效应。

原始报告：`eval/result-rrf-k10.json` / `result-rrf-k100.json` / `result-vecweight2.json` / `result-minscore-020.json` / `result-minscore-050.json`

---

## 实验 8：chunk-size 敏感性（300 / 500 / 800，需重建索引+重灌语料）

| chunk-size | 语料 chunk 总数 | ContentRecall@8 | 漏检 | mean ms |
|---|---|---|---|---|
| 300 | 1142 | **0.884** | network-01, para-02, para-03, para-08, para-09 | 305 |
| **500（基线）** | 568 | **0.953** | network-01, para-03 | 276 |
| 800 | 322 | **0.930** | network-01, para-03, para-08 | 256 |

**清晰的单峰曲线，500 是当前语料的最优点：**

- **300 太碎**：漏检从 2 → 5。两个机制：① 金标事实被切碎后单个 chunk 的语义信号被稀释（para-02 的 MVCC 解释、para-09 的淘汰策略列表被切散）；② 候选总数翻倍（1142 vs 568），top-30 内竞争更激烈，para-08 这类改写题的弱信号 chunk 被挤出。
- **800 太粗**：para-08 漏检——大 chunk 里 QUIC/0-RTT 的关键句被大量无关内容平均掉了 embedding 信号（一个 chunk 讲太多事，向量指向"平均主题"而不是关键句）。
- 与 500 的差异（±0.023~0.069）超出运行噪声带（检索层指标是确定性的，噪声只来自 embedding API 的极小非确定性），结论可信。
- **未做的维度（诚实记录）**：overlap（0/80/150）与 chunk-size 的交叉实验没跑——每组合都要重灌语料（~3 分钟+embedding 成本），留作后续；单变量维度已足够支撑"500 附近是甜区"的结论。

实验后语料已恢复 500 基线（568 chunks，重灌验证一致）。原始报告：`eval/result-chunk-300.json` / `result-chunk-800.json`

---

## 实验 9：查询改写（LLM query rewriting）

实现：`QueryRewriter`（fail-open；输出硬清洗=取首行/剥引号/200 字符上限），独立改写模型 `llm.rewrite-model=qwen-turbo`（短模板任务用便宜快的模型，空则回退 chat-model），开关 `rag.enable-rewrite`，trace 记录 rewrittenQuery/rewriteMillis；6 个单元测试（禁用直通 / 故障 fail-open / 模型选择 / 回退 / 超长拒用 / 清洗）。生效性经独立验证（rewrittenQuery ≠ question）后才跑评测。

| 配置 | ContentRecall@8 | mean ms | 漏检 |
|---|---|---|---|
| hybrid（对照） | 0.953 | 276 | network-01, para-03 |
| hybrid + rewrite | **0.953** | **678** | network-01, para-03 |

**结果：召回零收益（逐用例 diff 为零），平均延迟 +402ms/查询。**

归因（直接查验线上改写输出，不猜）：
1. **para-03**："程序突然断电了，内存里还没写到磁盘的数据怎么办" 被改写为 "程序突然断电内存中未写入磁盘的数据如何恢复"——只做了语法整理，没有注入领域术语。根因重判：**问题本身领域不明**（"程序断电"对 Redis 和 MySQL 都成立），改写模型无从猜测领域；且 top-8 全是 Redis AOF chunk——细想这是**对字面问题的正确回答**（AOF everysec 正是处理"断电时内存数据"的机制）。**para-03 由"检索失败"重新归类为"标注可辩"**（问题歧义，金标绑定了两个合理答案之一）。
2. **network-01**：改写同样温和，金标过窄问题不变（实验 4 归因）。**新发现：top-3 里出现 YAML frontmatter chunk**（"content: 计算机网络面试题,TCP/IP…"这类关键词堆叠的文档元数据）——frontmatter 被当正文索引，成为匹配一切查询的"万能磁铁"，污染检索结果。这是确凿的系统改进点（→ 实验 10）。
3. **深层结论**：口语化题的词面鸿沟**已经被向量检索覆盖**（para-08/para-12 不开改写也命中），改写只在"向量也 miss"的场景才可能帮忙——而唯一的向量 miss 是歧义问题不是词汇问题。**改写在当前评测集上零收益是结构性的必然，不是实现不好。**

**决策**：`enable-rewrite` 默认关闭（有成本无收益，数据驱动的 go/no-go）。机制保留——对词面鸿沟更宽、向量检索也吃力的语料仍有价值。

### 累计结论（实验 4-9 汇总）

**检索栈已触及当前评测集的天花板**：重排、改写、rrfK、权重、阈值全部零收益或不敏感；剩余 2 条漏检均确认为评测集缺陷（para-03 问题歧义、network-01 金标过窄）。**排除这两条后，混合检索在良定义问题上的 ContentRecall@8 = 46/46。** 下一步提升不来自调参，而来自：① 语料预处理（剥离 frontmatter）② 评测集治理（v1.4：para-03 加领域限定、network-01 放宽为多金标）③ IK 分词器（词法侧词级精度）。

原始报告：`eval/result-hybrid-rewrite.json`

---

## 实验 10：frontmatter 剥离（corpus-v2）——收益最大的单项改动

改动：`IngestionService.process()` 在切分前剥离 YAML frontmatter（`---` 围栏块，4 个单测钉住行为：正常剥离/无围栏不动/围栏不闭合不吞/CRLF 兼容）。重灌语料：568 → **561 chunks**（元数据块消失）。

| 配置 | corpus-v1（含 frontmatter） | **corpus-v2（剥离后）** |
|---|---|---|
| lexical-only ContentRecall@8 | 0.884（漏 5：redis-03, network-01, para-03, para-08, para-12） | **0.953（只漏 network-01, para-03）** |
| hybrid ContentRecall@8 | 0.953（漏 network-01, para-03） | **0.953（不变）** |
| lexical mean/p95 ms | 57 / 117 | **15 / 18** |
| hybrid mean/p95 ms | 276 / 691 | **210 / 376** |

**结论：**
1. **词法检索 +6.9pp，一次解析改动**。v1 里 lexical 漏掉的 redis-03/para-08/para-12 全部被 frontmatter"万能磁铁"chunk 挤出 top-8——关键词堆叠的元数据块（"content: 计算机网络面试题,TCP/IP四层模型,HTTP面试,…"）对几乎任何查询都拿高分。剥离后三条全部复位。**语料卫生的收益超过了此前尝试的全部算法手段（重排/改写/调参合计零收益）。**
2. **诚实的另一面：v2 语料上混合对纯关键词的实测优势归零**（0.953 = 0.953，失败集相同）。混合检索的价值从"可测的召回优势"变为"面向词面失配的保险"（+195ms 成本）。当前评测集已无法区分两者——这是评测集天花板，不是混合无用的证明；换更口语化/更长尾的查询集才能重新度量。
3. 完整评测（v2，48 次 qwen-plus）：引用准确率 0.998、**拒答准确率 1.000（48/48，network-05 本轮未拒答——生成随机性，仍在噪声带内观察）**、关键点覆盖率 0.885（v1 语料是 0.865，语料变干净答案更完整）、mean 5705ms / p95 10509ms。
4. 剩余漏检仍是那两条已归因的评测集缺陷（para-03 问题歧义、network-01 金标过窄）→ v1.4 治理已入队。

原始报告：`eval/result-v2-hybrid.json` / `result-v2-lexical.json` / `result-v2-hybrid-full.json`

---

## 实验 11：上下文压缩（句级筛选，第 5 周余项）

实现：`ContextCompressor`——CJK 二元组 + 拉丁词与问题的重合度给句子打分，按分取句、**按原文顺序**拼接，预算 250 字符/chunk；无相关句时退化为前缀截断。压缩器本身零模型调用（压缩的意义是省钱，压缩器花钱就本末倒置）。7 个单测。trace 新增 `promptContextChars` 作为成本口径。

| 指标 | 无压缩 | 压缩（250/chunk） | 变化 |
|---|---|---|---|
| promptContextChars（探针问题） | 3851 | **954** | **-75.2%** |
| keyPointCoverage（48 条） | 0.885 | **0.844** | **-4.1pp（质量代价）** |
| citationAccuracy | 0.998 | 1.000 | 无退化 |
| refusalAccuracy | 1.000 | 1.000 | 无退化 |
| mean / p95 延迟 | 5705 / 10509 ms | **4830 / 9945 ms** | **-15% / -5%** |

**结论**：成本-质量权衡被完整量化——省 75% 上下文、快 15%，代价是关键点覆盖率 -4.1pp（部分事实所在的句子被压缩器判为不相关而丢弃）。**默认关闭**；适用场景是高 QPS/低风险路径。改进杠杆：预算 250→400、或给压缩器换更强的相关性打分。注意 -4.1pp 略超单轮噪声带（±1-2pp）但只跑了一轮，方向性结论可信、精确值需多轮平均——诚实记录。

原始报告：`eval/result-v2-compression-full.json`

---

## 实验 12：异步入库（第 6 周）+ bug #10

实现：PostgreSQL `documents` 状态机（PENDING/PROCESSING/DONE/FAILED + attempts + error）+ RabbitMQ（消息只带 docId，载荷在库里——重投递不会复活过期内容）+ 消费者重试 3 次指数退避 + 死信队列 `docrag.ingest.dlq` + 幂等（确定性 docId、ES 先删后写、消费者对 DONE 跳过）。`?sync=true` 保留同步路径给脚本。

**7 份文档（261KB）突发上传验收：**

| 指标 | 同步（旧） | **异步（新）** |
|---|---|---|
| API 受理延迟 | 8-21s/篇（阻塞到向量化完成） | **mean 38ms / max 155ms（202 Accepted）** |
| 请求线程占用 | 整个入库周期 | 毫秒级 |
| 消费结果 | — | 7/7 DONE、561 chunks、attempts 全为 1（零重试）、ES 计数核对一致 |
| 积压可观测 | 无 | 管理台 15672 队列深度 + DB 状态查询 |

**未演练路径（诚实记录）**：重试与死信在本次突发中未触发（attempts 全 1），拓扑与配置就绪但缺一次毒消息演练——已入队（实验 11）。消费总墙钟时间被 bug #10 污染（状态接口 500 导致轮询空转满 25 分钟超时），从 DB 时间戳看 7 份文档的 DONE 落库分布在 91 秒窗口内。

**bug #10（本次突发测试抓到的第 10 个）**：`GET /api/documents` 500。根因：`ElasticsearchConfig` 里手写的 `@Bean ObjectMapper`（裸 `new ObjectMapper()`）**顶掉了 Spring Boot 自动配置的 mapper**（带 JavaTimeModule），`DocumentRecord` 的 `Instant` 字段序列化即炸。修复：删掉自定义 bean，全项目注入 Boot 的 mapper。教训两条：① **不要无理由重定义框架提供的 bean**；② 上下文装配测试抓不住序列化问题——Web 层集成测试（MockMvc）入队。

---

## 实验 13：SSE 流式端点与 TTFT（第 7 周）

实现：`GET /api/answer/stream?q=...`（GET 是为浏览器 EventSource 兼容），事件序列 `meta`（检索统计）→ `token`*（增量文本）→ `done`（完整 QaAnswer JSON 含引用与 trace）；生成走 `llmClient.stream()`（SSE 帧解析 + `stream_options.include_usage` 保证流式也进 token 账）；虚拟线程上运行，慢订阅者不占平台线程；trace 新增 `ttftMillis`；流式路径刻意不走答案缓存（缓存命中会让 TTFT 失去意义）。

实测（语料内问题"RDB 和 AOF 两种持久化方式有什么区别？"，107 token 答案）：

| 指标 | 值 |
|---|---|
| **TTFT（首字延迟）** | **1577ms** |
| 完整答案总时长 | 12262ms |
| 感知延迟改善 | **87%**（1.6s 开始读到内容，而不是干等 12.3s） |
| 引用行为 | 与非流式一致（citationAcc=1.0，8 条引用） |

附带验证：探测中误用旧语料时代的问题（制动盘），系统在流式路径同样正确拒答（3 token 哨兵，TTFT ~1.2s）——拒答防线不受流式影响。

---

## 实验 14：死信演练（毒消息全链路）

投喂不支持的文件类型（poison.bin）：

```
202 受理（docId 立即返回）→ 消费者 attempt 1 失败（Unsupported file type）
→ 重试（5s/10s 指数退避）→ attempt 3 失败 → 记录 FAILED(attempts=3, error 落库)
→ reject（default-requeue-rejected=false）→ 死信队列 docrag.ingest.dlq：messages=1 ✓
```

第一次演练误报 DLQ=0：轮询在第 2 次尝试写入 FAILED 时 break 并立即查询，第 3 次尝试（t≈15s）尚未完成。固定等满 60s 复查后 attempts=3、DLQ=1。**教训：验证异步链路必须等完整重试链走完，看到中间态就下结论等于没测。**

---

## 实验 15：评测集 v1.4 治理 + 重跑（当前基线）

v1.4 变更（全部来自实验 4/9 的归因，逐条记录在 my-eval.jsonl 头注释）：
- **para-03** 加领域限定"MySQL 数据库"——原题领域歧义（Redis AOF 文档对字面问题是合法答案），不能要求系统读心
- **network-05** 简化为"自下而上分为哪几层"——原第二问"各层的作用"语料只有外链没有细节，拒答反而合理
- **network-01 金标维持不变**——传输层定义 chunk 确是"TCP/UDP 区别+场景"的最直接答案，混合配置漏它属于 RRF 共识偏差的真实系统缺陷，**不通过改金标美化数字**

| 指标（hybrid，48 条） | v1.3（corpus-v2） | **v1.4（当前基线）** |
|---|---|---|
| ContentRecall@8 | 0.953 | **0.977（42/43，唯一漏检=network-01）** |
| SrcRecall@8 | 0.979 | **1.000** |
| 引用准确率 | 0.998 | 0.998 |
| 关键点覆盖率 | 0.885 | **0.906** |
| 拒答准确率 | 1.000 | 1.000 |
| mean / p95 延迟 | 5705 / 10509 ms | 5528 / 10737 ms |

para-03 修复后立刻通过（"MySQL"入题后词法路直接命中 redo 章节）——反向证实了实验 9 的歧义归因。剩余唯一漏检 network-01 是已定性的 RRF 共识偏差，**指标就该留着疼**，作为融合策略改进（队列 #15）的靶子。

原始报告：`eval/result-v14-hybrid.json` / `result-v14-hybrid-full.json`

---

## 实验 16：小规模并发压测 + 可观测性验收（第 7 周）

**压测**（4 并发 worker × 12 个不同问题，先 FLUSHDB 清答案缓存保证全走实时管线）：

| 指标 | 值 |
|---|---|
| 请求数 / 成功 | 48 / 48（零错误） |
| 吞吐 | 0.67 req/s |
| 延迟 p50 / p95 / max | 5.51s / 11.18s / 12.86s |

解读（诚实版）：吞吐被**生成端 LLM API 延迟**卡死（单请求 ~5.5s，4 并发近似线性叠加），应用侧零错误、检索层 ~200ms 不构成瓶颈。提升路径在生成端：语义缓存扩命中、流式降感知延迟（已做）、供应商并发配额。**这是小规模健康检查，不是容量规划**——没有测出应用侧极限 QPS，只验证了 4 并发下无错误无异常退化。

**Prometheus 指标验收**（/actuator/prometheus 实测采样）：

```
llm_tokens_total{kind="prompt",model="qwen-plus"}      236797   ← 当日全部实验的 prompt token
llm_tokens_total{kind="completion",model="qwen-plus"}   21576
rag_answer_seconds_count{cache="false",refused="false"}    86
rag_answer_seconds_count{cache="false",refused="true"}     11
rag_ttft_millis_count / rag_context_chars_count / rag_stage_* 全部在采
```

token 账本直接换算成本：当日约 26 万 token ≈ ¥0.5，与"完整评测一轮约 ¥0.3"的既有估算吻合——**成本从估算变成了计量**。

---

## 下一步实验队列（更新：实验 4-16 已完成）

| # | 实验 | 假设 | 状态 |
|---|---|---|---|
| ~~1-5~~ | ~~rrfK / 权重 / 重排 / 改写 / 阈值~~ | 见实验 4-9 | **全部完成：零收益或不敏感，均已逐用例归因** |
| ~~9~~ | ~~frontmatter 剥离~~ | 元数据磁铁污染检索 | **已证实，收益最大单项**（实验 10：lexical 0.884→0.953） |
| ~~7~~ | ~~上下文压缩~~ | 句级筛选降成本 | **已做**（实验 11：上下文 -75%、覆盖率 -4.1pp，默认关） |
| ~~10~~ | ~~评测集 v1.4 治理~~ | 修复两条缺陷用例 | **已做**（实验 15：新基线 ContentRecall 0.977 / SrcRecall 1.000） |
| ~~11~~ | ~~死信演练~~ | 毒消息 → 重试 → FAILED → DLQ | **已验证**（实验 14：attempts=3、DLQ messages=1） |
| ~~12~~ | ~~SSE 端点 + TTFT~~ | 流式降感知延迟 | **已做**（实验 13：TTFT 1577ms vs 全量 12262ms，-87%） |
| ~~13~~ | ~~MockMvc Web 层测试~~ | 钉住序列化契约 | **已做**（WebLayerSerializationTest ×4，测试总数 50） |
| 6 | IK 分词器替换 standard | 词级分词再提词法精度（v2 语料下 lexical 已 0.953，预期收益收窄） | 待做 |
| 14 | Grafana 看板 | Prometheus 端点已就绪，差可视化 | 待做 |
| 15 | network-01 融合策略改进 | RRF 共识偏差的针对性解法（单路高分保护 / 融合前轻量重排） | 待做（唯一遗留系统缺陷） |
| 16 | 多轮对话指代消解 | 查询改写机制已在，缺会话上下文管理 | 待做 |
