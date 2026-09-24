# corpus/ 语料说明

## 来源

全部文档取自 [JavaGuide](https://github.com/Snailclimb/JavaGuide)（GitHub 上最流行的中文 Java 面试知识库之一），经 jsdelivr CDN 下载，获取日期见 git 历史或文件时间戳。

| 文件 | 原始路径（JavaGuide 仓库） |
|---|---|
| Redis核心问题.md | docs/database/redis/redis-questions-01.md |
| Redis持久化.md | docs/database/redis/redis-persistence.md |
| MySQL核心问题.md | docs/database/mysql/mysql-questions-01.md |
| Java并发核心问题.md | docs/java/concurrent/java-concurrent-questions-01.md |
| 计算机网络核心问题.md | docs/cs-basics/network/other-network-questions.md |

## 用途与许可注意

- 仅用于**本地个人学习**：作为 doc-rag 检索问答系统的语料，同时作为秋招后端面试复习资料
- 版权归 JavaGuide 作者（Guide 哥）所有；**不要把本目录内容重新分发**，若仓库要公开到 GitHub，把 `corpus/` 加进 `.gitignore`，README 里只保留来源链接
- markdown 里的图片链接指向原仓库/CDN，本地不解析，不影响检索（图片不参与文本切分与向量化）

## 为什么选这批文档

语料 = 秋招后端面试四大必考领域（Redis / MySQL / Java 并发 / 计算机网络）：

1. 提问、写评测用例时不需要额外学习领域知识——问题就是你面试会被问的问题
2. 文档本身是结构化面试题精讲，含大量精确数值与术语（适合考察 BM25），也有概念解释（适合考察向量检索），是混合检索的理想测试语料
3. 评测用例可以直接从文档内容出：例如「AOF 重写和 RDB fork 的关系」「为什么用 B+ 树不用 B 树」「线程池核心参数有哪些」

## 灌入状态（2026-09-23 实测）

见本次会话的灌入记录；用 `GET /api/documents` 可随时核对文档与 chunk 数。
