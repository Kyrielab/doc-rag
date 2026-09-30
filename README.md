# doc-rag — 技术文档混合检索问答服务

面向技术手册 / 维修文档的 RAG（检索增强生成）问答后端。核心不是"能问答"，而是**每条质量结论都有可复现的数据支撑**：混合检索、RRF 融合、重排、评测框架全链路打通，每次改动都能用同一套用例量化出 delta。

---

## 1. 这个项目解决什么问题

纯向量检索在技术文档上会漏掉型号、故障码、数值这类**精确匹配**信息；纯关键词检索又无法处理"发动机打不着火"和"启动困难"这种**语义改写**。技术文档同时需要两者，所以本项目做混合检索，并用评测框架证明混合确实优于单路。

同时，技术文档问答有零容忍的错误类型：
- **编造引用** → 用引用溯源 + 引用准确率指标约束
- **编造答案** → 用显式拒答机制 + 拒答准确率指标约束
- **定位不到原文** → 每条答案返回 chunk 级出处

---

## 2. 架构

```
                    ┌──────────────────────────────────────────┐
   POST /api/answer │              RagService                  │
   ────────────────▶│  缓存 → 检索 → 融合 → 重排 → 提示 → 生成 → 引用 │
                    └───────────────┬──────────────────────────┘
                                    │
              ┌─────────────────────┴─────────────────────┐
              ▼                                           ▼
   ┌────────────────────┐                    ┌────────────────────────┐
   │  RetrievalService  │  并行（虚拟线程）    │     PromptBuilder      │
   │  ┌──────────────┐  │                    │  编号片段 + 拒答指令     │
   │  │ BM25 检索     │  │                    │  引用标记解析           │
   │  ├──────────────┤  │                    └───────────┬────────────┘
   │  │ 向量检索      │  │                                │
   │  └──────┬───────┘  │                                ▼
   │         │          │                    ┌────────────────────────┐
   │  ReciprocalRank    │                    │  LLM（OpenAI 兼容）      │
   │  Fusion (RRF)      │                    │  流式 / 非流式          │
   │         │          │                    └────────────────────────┘
   │  ┌──────▼───────┐  │
   │  │  重排（可选）  │  │
   │  └──────────────┘  │
   └─────────┬──────────┘
             │
             ▼
   ┌───────────────────────────────────────────────┐
   │  Elasticsearch  单索引同时承载 BM25 + dense_vector │
   └───────────────────────────────────────────────┘

   写入链路：解析(PDF/DOCX/TXT) → 切分 → 向量化 → 批量索引
```

**关键设计取舍（每条都能展开讲）**

| 决策 | 选择 | 理由 |
|---|---|---|
| 检索引擎 | Elasticsearch 单索引承载 BM25 + 向量 | 避免双存储的写一致性问题，混合检索无需跨库 join |
| 融合算法 | RRF（只用排名，不用原始分数） | BM25 分数与余弦相似度量纲不可比；RRF 无需归一化，语料变化不用重新调参 |
| 检索并行 | 两路检索并发（虚拟线程） | 延迟从 `a+b` 降到 `max(a,b)` |
| 向量写入 | 脚本内 L2 归一化 | 余弦相似度退化为点积，分数跨文档可比 |
| ES 客户端 | 低层 REST Client，不用 Spring Data ES | 查询 DSL 完全显式可控，便于解释"到底发了什么查询" |
| 引用标签 | 字母 `[A][B]` + 提示词末尾附有效标签白名单 | 数字标签与文档章节号混淆是实测 failures：`[1]`→模型引`[3]`（正文"三、"），`[S1]`→模型引`[S3]`（S 被读作 Section），字母与章节号零冲突；白名单利用指令近因效应 |
| 引用指标口径 | 任何 ≤6 字符的方括号 token 都算"引用尝试"，解析失败记无效 | 严格解析器在模型输出畸形引用时静默得 100 分——指标恰好在模型出错时失明（实测被 `[ S3 ]` 骗过一次） |
| 缓存内容 | 只存答案文本 + chunk id | 直接缓存整个响应会把文档正文冻结在 Redis 里，重灌文档后引用过期 |
| 缓存指纹 | key 含 systemPrompt 哈希 + 模板版本号 + 模型名 | 只指纹 system prompt 不够：改了 user prompt 模板后旧缓存照常命中，实测被假结果骗过一次，因此模板版本常量随形状变更手动递增 |
| 重排失败 | fail-open，降级到融合顺序 | 降级回答优于 500，且 trace 记录"已跳过重排" |
| 检索失败 | 单路失败仍返回另一路结果 | 一个检索器挂了不应该是整个查询挂掉 |
| 关系库 | 暂不接 JPA/PostgreSQL | 当前没有任何需要持久化的关系数据，死依赖只会制造启动失败点；第 6 周异步入库状态机落地时再带真实表结构接入 |

**真实踩坑记录（全部由端到端实跑暴露，每个都已修复并有回归测试/日志证据）**

1. `script_score` 的内层 query 写成了空对象 `{}` 而非 `{"match_all":{}}` → ES 400 "empty clause found"，向量检索整路静默降级。教训：**错误处理必须带出下游原始报错**，包装异常只留自己的话等于藏证据。
2. 引用标签三轮迭代：数字 `[3]` → 加前缀 `[S3]` → 字母+白名单 `[A]`。前两轮都失败于同一根因：标签里的数字和正文章节号竞争。教训：**别跟模型讲道理，改掉会冲突的 token 空间**。
3. 缓存指纹漏了 user prompt 模板 → 改完提示词重测，拿到的还是旧缓存答案（`totalMs=58, genMs=0` 识破）。教训：**验证脚本必须打印 cacheHit**，否则假结果以假乱真。
4. Windows 下 PowerShell 脚本编码坑：无 BOM 的 UTF-8 .ps1 里的中文按 GBK 误读，问题变乱码 → 词法检索 0 命中。意外收获：乱码问题下**向量检索仍命中正确 chunk**，直观展示了语义检索对词面失真的鲁棒性。修复：中文测试数据放独立 UTF-8 文件、显式 `-Encoding UTF8` 读取，不依赖脚本文件编码。
5. 无实体类却挂着 JPA starter → PostgreSQL 一停应用就起不来（Hibernate 方言探测硬依赖 JDBC 连接）。教训：**不用的依赖是负资产**。

---

## 3. 快速开始

### 3.1 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 21+ | 使用了虚拟线程和 record |
| Maven | 3.9+ | |
| Docker Desktop | 任意近期版本 | 起 PostgreSQL / Redis / Elasticsearch |

### 3.2 启动基础设施

```bash
docker compose up -d
docker compose ps          # 等三个容器都 healthy
curl http://localhost:9200 # 确认 ES 可访问
```

中文文档需要 IK 分词器（否则中文按单字切分，BM25 效果会明显变差）：

```bash
docker compose --profile zh up -d --build
# 然后把 application.yml 的 elasticsearch.analyzer 改为 ik_max_word
```

### 3.3 配置模型

复制 `.env.example` 为 `.env` 并填入 key，或者直接设环境变量：

```bash
# 任意 OpenAI 兼容端点均可，例如 DeepSeek / 通义 / 智谱 / 本地 Ollama
export LLM_API_KEY=sk-xxxx
```

`application.yml` 中相关配置（当前为已实测跑通的阿里云百炼配置）：

```yaml
llm:
  base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
  embedding-model: text-embedding-v4
  embedding-dimension: 1024   # text-embedding-v4 默认维度；换模型必须同步改并重建索引
  chat-model: qwen-plus
```

> **注意**：`embedding-dimension` 与实际模型返回维度不一致时启动后第一次写入会直接报错（这是刻意的，维度错误只会在查询期暴露，代价更高）。改动 embedding 模型后必须删除并重建索引。

### 3.4 启动服务

```bash
mvn spring-boot:run
```

自检：

```bash
curl http://localhost:8080/api/admin/status
```

该接口会告诉你 Elasticsearch / Redis 是否可达、模型配置是否生效、检索参数当前取值——排查问题的第一个入口。

### 3.5 灌入文档

```bash
# 文件上传（PDF / DOCX / TXT / MD）
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@你的手册.pdf" -F "title=维修手册"

# 直接贴文本
curl -X POST http://localhost:8080/api/documents/text \
  -H "Content-Type: application/json" \
  -d '{"title":"故障码说明","content":"P0301 表示第 1 缸失火……"}'

# 查看已入库文档
curl http://localhost:8080/api/documents
```

### 3.6 提问

```bash
curl -X POST http://localhost:8080/api/answer \
  -H "Content-Type: application/json" \
  -d '{"question":"发动机无法启动时应该先检查什么？","topK":8}'
```

响应包含答案、引用列表，以及完整的检索 trace：

```json
{
  "answer": "应先检查蓄电池电压和燃油供给 [1][2]。",
  "citations": [{"chunkId":"a1b2c3d4#3","title":"维修手册","source":"维修手册","excerpt":"...","rank":2}],
  "citationRefs": [{"marker":"[1]","ordinal":1,"valid":true,"chunkId":"a1b2c3d4#3"}],
  "refused": false,
  "citationAccuracy": 1.0,
  "trace": {
    "lexicalHits": 30, "vectorHits": 30, "fusedCount": 41,
    "lexicalMillis": 12, "vectorMillis": 168, "fusionMillis": 1,
    "rerankMillis": 0, "generateMillis": 2140, "totalMillis": 2330,
    "retrievedChunkIds": ["..."],
    "cacheHit": false, "rerankSkipped": "disabled by config"
  }
}
```

---

## 4. 评测：这个项目的核心资产

评测框架是整个项目最有价值的部分。没有它，"我优化了检索效果"只是一句无法验证的话。

### 4.1 用例格式

`eval/sample-eval.jsonl`，每行一个 JSON：

```json
{"id":"case-001","question":"...","expectedSources":["维修手册"],"keyPoints":["蓄电池"],"shouldRefuse":false}
```

| 字段 | 含义 |
|---|---|
| `expectedSources` | 必须出现在被检索 chunk 的标题或来源中的子串 |
| `keyPoints` | 答案中必须出现的事实点（大小写不敏感子串匹配） |
| `shouldRefuse` | 语料确实无法回答时为 `true` |

**如何构造用例集**
1. 先灌入你自己的文档，把 `expectedSources` 换成你的真实文件名
2. 从 10–20 条起步，逐步扩到 100–300 条，来源是真实提问
3. **至少包含 5 条 `shouldRefuse: true`**——一个从不拒答的管线等于没被测试
4. 数值点必须用 ASCII 数字，全角数字匹配不上

### 4.2 指标定义

| 指标 | 定义 | 为什么用它 |
|---|---|---|
| SrcRecall@K（文档级召回） | 期望来源文档出现在 top-K chunk 中的比例 | 粗粒度护栏。**在小语料上会饱和**（实测三配置全 1.0、零区分度），只用于发现灾难性失败 |
| ContentRecall@K（chunk 级内容召回） | 金标短语（正确 chunk 独有的词，如 para-03 的 `redo`）出现在任一被检索 chunk 内容中的比例 | **主指标**：直接度量"装着答案的那个 chunk 有没有被检索到"，金标短语唯一性经脚本验证 |
| ContentHit@K | 全部金标短语都命中的用例比例 | 严格版本 |
| 引用准确率 | 解析出的引用标记中能对应到真实 chunk 的比例（任何 ≤6 字符方括号 token 都算引用尝试，防止畸形引用静默得满分） | **编造引用是幻觉最清晰的信号** |
| 关键点覆盖率 | 答案命中的必含事实点比例（keyPoints 只收录"正确答案必然包含"的事实，避免惩罚最小正确答案） | 确定性的正确性代理指标 |
| 拒答准确率 | 拒答行为与 `shouldRefuse` 一致的用例比例。拒答判定为**哨兵前置**：模型答完在末尾追加哨兵作警示的，算"弱依据作答"而非拒答（实测 contains 判定会误伤 3/48） | 覆盖另一种失败模式：不懂装懂 |
| mean / p95 延迟 | 端到端耗时 | 优化不能只看均值 |

> **诚实说明**：关键点覆盖率是弱指标，无法判断表述是否准确、是否自相矛盾，只能证明关键事实在不在。生产系统会用 LLM 裁判或人工抽检。把这个局限讲清楚，比假装它是正确性分数更有说服力。

### 4.3 跑评测

```bash
# 只评检索，不调用 LLM：零生成成本，调参主循环用这个
curl -X POST "http://localhost:8080/api/eval/run?label=baseline&topK=8&skipGeneration=true&casesFile=eval/my-eval.jsonl"

# 完整评测（每用例一次生成调用，产生费用）
curl -X POST "http://localhost:8080/api/eval/run?label=full&topK=8&skipGeneration=false&casesFile=eval/my-eval.jsonl"
```

评测默认绕过答案缓存（`allowCache=false`），保证每次跑的都是真实管线，延迟数字不被缓存命中污染。

### 4.4 A/B 对比实验：已跑出的真实数据（2026-09-24）

**同一套用例（`eval/my-eval.jsonl` v1.3，48 条）、同一份语料（7 文档 568 chunks）**，只改配置。完整过程与失败归因见 `experiments.md`，原始报告在 `eval/result-*.json`。

检索层（skipGeneration=true，零生成成本）：

| config | SrcRecall@8 | **ContentRecall@8** | mean ms | p95 ms | 漏检用例 |
|---|---|---|---|---|---|
| ① lexical-only | 1.000 | **0.884** | 57 | 117 | redis-03, network-01, para-03, para-08, para-12 |
| ② vector-only | 0.979 | **0.953** | 270 | 442 | para-01, para-03 |
| ③ hybrid (RRF) | 0.979 | **0.953** | 276 | 691 | network-01, para-03 |
| ④ hybrid + rerank (gte-rerank-v2) | 0.979 | **0.953** | 262 | 391 | network-01, para-03（**零收益，归因见 experiments.md 实验 4**） |

参数敏感性（第 4 周实验矩阵，全部检索层零生成成本）：

| 维度 | 取值 | ContentRecall@8 | 结论 |
|---|---|---|---|
| rrf-k | 10 / 60 / 100 | 0.953 / 0.953 / 0.953 | 不敏感——k 救不了"没进候选"和"金标过窄" |
| vec-weight | 1.0 / 2.0 | 0.953 / 0.953 | 2x 不足以翻转共识偏差，且权重是零和的 |
| min-vector-score | 0.2 / 0.35 / 0.5 | 0.953 ×3 | 无感——金标相似度远离阈值带 |
| **chunk-size** | 300 / **500** / 800 | 0.884 / **0.953** / 0.930 | **单峰曲线，500 是甜区**：300 稀释语义信号+候选竞争翻倍，800 关键句被无关内容平均掉 |
| 查询改写 (qwen-turbo) | off / on | 0.953 / 0.953 | 零收益且 +402ms/查询——剩余漏检是问题歧义不是词面鸿沟，**默认关闭**（实验 9） |

生成层（hybrid 完整评测，48 次 qwen-plus 调用）：**引用准确率 0.998、拒答准确率 0.979、关键点覆盖率 0.865**、mean 5.5s / p95 10.3s（生成为主）。

四个最有含金量的结论：
1. **混合比纯关键词 ContentRecall@8 高 6.9pp**，救回的全是口语化改写题（BM25 词面失配场景）
2. **混合与纯向量打平但失败集不同**：混合救回向量漏的精确术语题（para-01），却因 RRF 共识偏差丢了向量能命中的 network-01（正确 chunk 只在单路排名靠前，被"两路都中游"的平庸 chunk 挤出 top-8）
3. **重排与查询改写都实测零收益，且归因不同**：重排只能重排已召回的候选（救不了 para-03）；改写的目标场景（词面鸿沟）已被向量检索覆盖。剩余 2 条漏检最终确认为**评测集缺陷**（para-03 问题领域歧义、network-01 金标过窄）——排除后良定义问题上 ContentRecall@8 = 46/46。**检索栈已触及当前评测集天花板，继续调参是无用功，下一步是语料预处理（frontmatter 剥离）与评测集治理**
4. **文档级 SrcRecall 全配置饱和（1.0/0.979），零区分度**——小语料上必须用 chunk 级内容金标，评测集设计本身是会骗人的（详见 experiments.md 实验 0）

**每组失败用例都已归因分类**（检索失败 / RRF 融合压错 / 生成丢失事实 / 标注可辩），见 `experiments.md`。失败归因分析往往比那行数字更能体现工程能力。

---

## 5. API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/documents/upload` | 上传文件（multipart） |
| POST | `/api/documents/text` | 贴文本入库 |
| GET | `/api/documents` | 文档列表与 chunk 数 |
| DELETE | `/api/documents/{docId}` | 删除文档及其全部 chunk |
| POST | `/api/answer` | 问答（返回答案 + 引用 + trace） |
| POST | `/api/answer/quick?q=` | 便捷问答（浏览器/压测用） |
| POST | `/api/eval/run` | 跑评测，返回聚合报告 |
| POST | `/api/eval/markdown` | 评测结果渲染为 markdown 表格行 |
| GET | `/api/admin/status` | 依赖连通性与生效配置 |

---

## 6. 测试

```bash
mvn test
```

**当前状态：29 个测试全部通过**（JDK 21.0.12 / Maven 3.9.16 / Spring Boot 3.5.6 实测）。

| 测试类 | 覆盖内容 |
|---|---|
| `DocRagApplicationTests` | Spring 容器装配（6 个用例）：检索/问答/入库协作 bean、四个 Controller、三个解析器、重排默认关闭、配置项绑定。排除存储自动配置并用 mock 替换 Redis 模板，**无需 Docker 即可运行**，因此失败必然意味着接线或配置有 bug |
| `ReciprocalRankFusionTest` | 两路命中优先、只用排名不用原始分数、权重影响排序、缺失排名记为 miss、空输入不崩 |
| `TextChunkerTest` | 尺寸预算、相邻 chunk 重叠、chunk id 确定性、超长句硬切、空输入不产出空 chunk |
| `PromptBuilderTest` | 引用解析、越界标记判为无效、重复引用去重、引用准确率、拒答哨兵、上下文预算 |

---

## 7. 已知限制与下一步

**当前限制**
1. PDF 解析依赖文本层，扫描件需要先 OCR；表格和标题层级会丢失
2. **Markdown 的 YAML frontmatter 会被当正文索引**，形成关键词堆叠的"万能磁铁"chunk 污染检索（network-01 的 top-3 里直接观察到）——下一步最高优先级
3. 查询改写已实现并实测（qwen-turbo，默认关闭：当前评测集零收益 +402ms/查询，见实验 9）；多轮对话的指代消解未实现
4. 文档入库同步执行，大量文档会占满请求线程
5. 关键点覆盖率是弱正确性指标
6. 无鉴权与多租户隔离

**下一步优先级（按数据支撑的紧迫度排序）**
- [ ] **frontmatter 剥离**：解析阶段去除 YAML 元数据，重灌语料重跑基线（有 trace 直接证据，预期词法侧精度提升）
- [ ] **评测集 v1.4 治理**：para-03 加领域限定、network-01 放宽多金标、network-05 标为边界用例（归因已完成，见实验 4/9）
- [ ] **IK 中文分词器**：词级分词替换单字切分，提升 BM25 精度（compose profile 已备好）
- [ ] **入库异步化**：上传接口只落库 + 投递 MQ，消费者做解析/向量化，支持断点续传与失败重试（体现削峰与可靠性设计）
- [ ] **上下文压缩**：对召回的 chunk 做句子级筛选，降低 token 成本，记录成本前后对比
- [ ] **流式输出（SSE）端点**：客户端 SSE 帧解析已实现，补 HTTP 端点把首字延迟（TTFT）从秒级降到亚秒级
- [ ] **可观测性**：接入 Micrometer + Prometheus，把检索/生成各阶段耗时与 token 消耗做成看板
- [ ] **语义缓存 / 限流配额**：向量近邻缓存提高命中率；按调用方做 token 配额

---

## 8. 目录结构

```
doc-rag/
├── pom.xml
├── docker-compose.yml
├── Dockerfile.es-zh              # 带 IK 分词器的 ES 镜像
├── experiments.md                # 完整实验记录（9 组实验 + 失败归因 + 队列）
├── eval/
│   ├── sample-eval.jsonl         # 用例格式示例
│   ├── my-eval.jsonl             # 正式评测集 v1.3（48 条，chunk 级金标）
│   └── result-*.json             # 各配置原始评测报告（数字出处）
├── scripts/                      # start-dev / smoke-test / run-ab / run-week4 / run-rerank / run-rewrite / verify-terms
└── src/
    ├── main/java/com/zzx/docrag/
    │   ├── api/                  # REST 层：文档、问答、评测、诊断
    │   ├── config/               # 配置绑定、ES 客户端、索引初始化
    │   ├── es/                   # ES 网关：索引、BM25、向量检索、mget
    │   ├── eval/                 # 评测用例、指标、报告
    │   ├── ingest/               # 解析 → 切分 → 向量化 → 索引
    │   ├── llm/                  # OpenAI 兼容的 embedding + chat 客户端
    │   ├── rag/                  # 编排、提示构造、引用解析、缓存
    │   └── retrieve/             # 混合检索、RRF、重排
    └── test/java/com/zzx/docrag/ # 纯逻辑单元测试
```

---

## 9. 许可

个人学习项目，可自由参考。
