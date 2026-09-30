# 实验记录（experiments.md）

> 本文件是评测驱动开发的完整证据链：每一次配置变更、每一个数字、每一个失败用例的归因。
> 简历上的每个数字都必须能在这里找到出处。原始报告 JSON 存于 `eval/result-*.json`。

---

## 实验环境

| 项 | 值 |
|---|---|
| 机器 | Windows 11 笔记本（开发机，非服务器） |
| 语料 | JavaGuide 7 份中文文档（Redis×2 / MySQL / Java并发×3 / 计算机网络），568 chunks |
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

**教训（面试可直接讲）**：
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

## 下一步实验队列（更新：实验 4-9 已完成）

| # | 实验 | 假设 | 状态 |
|---|---|---|---|
| ~~1~~ | ~~rrfK 敏感性~~ | ~~小 k 缓解共识偏差~~ | **已证伪**（实验 5：不敏感，根因不在 k 的作用域） |
| ~~2~~ | ~~向量路加权~~ | ~~加权偏向更强的向量路~~ | **已证伪**（实验 6：2x 不够，且权重零和） |
| ~~3~~ | ~~部署重排~~ | ~~修复 network-01~~ | **已做，零收益**（实验 4：归因=召回边界+金标过窄） |
| ~~4~~ | ~~查询改写~~ | ~~修复 para-03 跨文档语义混淆~~ | **已做，零收益**（实验 9：根因=问题歧义而非词面；默认关闭） |
| ~~5~~ | ~~minVectorScore 敏感性~~ | ~~0.35 误杀弱相关~~ | **已证伪**（实验 7：0.2-0.5 无感） |
| ~~8~~ | ~~重排复评~~ | ~~改写修复召回后重估重排~~ | **关闭**（改写零收益，复评前提不成立） |
| 9 | **frontmatter 剥离**（解析阶段去 YAML 元数据） | 元数据 chunk 是"万能磁铁"污染检索（network-01 top-3 直接观察到）；剥离后重灌语料、重跑基线，预期词法侧精度提升 | 待做（最高优先级，有 trace 直接证据） |
| 10 | 评测集 v1.4 治理 | para-03 加领域限定（或双金标）、network-01 放宽多金标、network-05 标为边界用例；修订后重跑并公布 v1.3/v1.4 双口径 | 待做 |
| 6 | IK 分词器替换 standard | 中文词级分词提升 BM25 精度，可能改变 lexical/hybrid 平衡 | 待做 |
| 7 | 上下文压缩 + token 成本核算 | 句级筛选降低 prompt token，量化成本-覆盖率权衡（PLAN 第 5 周余项） | 待做 |
