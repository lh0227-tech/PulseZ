# PulseZ 技术说明：长聊、记忆与费用

PulseZ 的重点是让你和角色能在**同一条会话里持续聊天**。聊得很长时，应用会在后台分段整理；当前对话、人设、旧事摘录和提取出的记忆会一起帮助后续回复接住故事。你也可以管理记忆、导入旧聊天、按关键词或意思找旧事，配置聊天和检索服务，并使用语音转写。

本文以公开版本 v0.6.9 的固定提交 [a3dd337](https://github.com/lh0227-tech/PulseZ/tree/a3dd337684172de88b1303bc62b4862d0e28b1dd) 为依据。源码链接都指向这个提交，服务商的计费与功能说明则链接到官方文档。

## 先回答三个问题

### 1. 能在同一个对话里一直聊下去吗？

可以。分段是应用整理长历史的办法；你看到的会话仍是原来的那一条。每轮回复结束后，应用会在后台检查是否该整理。达到本地交接条件时，它会把较长的历史分段处理：一部分对话原文会留下短摘录，聊天模型也会尝试提取事实、事件、偏好等记忆。新的一段继续使用角色设定、近期对话、记忆和较早的故事摘录，让 TA 尽量接住人物关系和情节。

原文摘录和记忆是两种材料：摘录保留少量原话，记忆是模型整理出的条目。后台任务失败时，聊天仍可继续；只是这次的交接或记忆整理可能没有完成。它能帮助长聊延续，但旧事会经过筛选和压缩，细节仍可能漏掉或被记错。[ChatViewModel.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/ui/chat/ChatViewModel.kt#L1990-L2015) · [ChatEngine.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/ChatEngine.kt#L516-L614) · [ChatEngine.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/ChatEngine.kt#L576-L654)

### 2. 聊太长会不会超过模型的处理容量？

有可能。这里的容量叫上下文窗口。为了看懂“可能超过”是什么意思，假设某个模型一次最多处理 100,000 个 token：角色设定和已有对话占 92,000，本轮新输入占 6,000，回答还需要 5,000，合起来就是 103,000，已经超出这个假设的容量。数字纯为解释算法，不对应任何具体模型。

为了优先照顾角色扮演和长篇写作的连续感，PulseZ 尽量保留当前段较多对话原文；达到应用设置的本地预算后，才在回复结束后的后台尝试交接。当前实现按应用预算阈值分段，没有逐模型读取实际容量、再自动调整或裁剪旧历史以确保请求放得下。因此在交接前，组好的请求仍可能超过某个模型的容量，这是保留更多故事背景与容量、费用风险之间的设计取舍。最新一条输入另有应用侧长度上限。模型容量及输入、思考、输出如何占用容量，以模型服务说明为准。[Google 长上下文说明](https://ai.google.dev/gemini-api/docs/long-context) · [BatonPolicy.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/mem/BatonPolicy.kt#L106-L143) · [PromptAssembler.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/mem/PromptAssembler.kt#L89-L115) · [ChatEngine.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/ChatEngine.kt#L576-L654)

### 3. 费用会不会越聊越多？

如果使用按量计费的服务，每次请求的费用都会计入累计账单。许多服务商在价目表里按每 100 万 token 分别列出输入、输出和缓存用量单价，再按实际用量计费；输入、输出、缓存读写的单价可能不同。若模型、输入输出规模和缓存命中条件大致相近，单轮花费可能相对接近；累计总费用则会随请求次数和实际用量继续增加。单轮金额仍会受实际用量及服务商计价规则影响，以当前官方价目表为准。[DeepSeek 价格说明](https://api-docs.deepseek.com/quick_start/pricing/) · [OpenAI API 定价](https://platform.openai.com/pricing/) · [Gemini API 定价](https://ai.google.dev/gemini-api/docs/pricing)

PulseZ 会尽量让每轮提示内容的开头保持稳定：系统规则、人设和语气样本在前，较早故事摘录和已有对话随后，新记忆与本轮输入放在后面。若服务商支持提示缓存，就有机会重复利用前面相同的内容。

- **短聊或前缀经常变化时**：缓存可能尚未达到服务商的命中条件，或者根本没有命中；这时输入通常按未命中用量计。
- **长聊且前缀稳定时**：多轮重复的角色设定和既有历史有机会命中缓存，服务商可能按不同于普通输入的规则计价；本轮新增内容和回答仍会产生各自用量。

所以短对话也可能因为回答长、输入未命中或输出单价较高而花费较多；长对话的输入虽然更多，重复部分命中缓存时，新增输入费用也可能较低。缓存是否开启、命中门槛、读写或存放价格都由服务商决定。PulseZ 的稳定排列只是创造复用机会，没有调用某家专用缓存接口的承诺。[OpenAI 提示缓存说明](https://developers.openai.com/api/docs/guides/prompt-caching) · [DeepSeek 缓存说明](https://api-docs.deepseek.com/guides/kv_cache/) · [PromptAssembler.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/mem/PromptAssembler.kt#L36-L48) · [Pricing.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/wire/Pricing.kt#L36-L56)

用量页是应用估算，不是服务商账单。它按收到的用量和应用内价格表估算聊天费用；图片和语音转文字也使用估算规则。日/月费用上限会按应用记录的估算，在开始新聊天请求前拦截；它不是服务商账户上的硬限额。后台记忆提取和长聊整理也会请求聊天服务，而本提交无法确认这些请求是否全部计入用量页。核对实际支出请看服务商账单。[ChatEngine.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/ChatEngine.kt#L485-L514) · [UsageLimits.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/UsageLimits.kt#L5-L12) · [DeepSeek 定价](https://api-docs.deepseek.com/quick_start/pricing/)

## 这些技术词在 PulseZ 里是什么意思？

### 一次聊天请求会带什么

一次请求不是把手机里这条会话的全部内容原样重发。PulseZ 会挑选材料并按固定顺序组装：**角色与系统规则 → 旧事编年史 → 当前段对话原文 → 记忆和找回的旧事 → 你刚发的内容**。这样既能让当前回复看到相关背景，也能把“角色设定”“近期原文”和“长期记忆”分别管理。[ChatEngine.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/ChatEngine.kt#L119-L221) · [PromptAssembler.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/mem/PromptAssembler.kt#L36-L48)

- **提示内容（prompt）**：这次交给模型的一整组材料。比如你刚输入一句话时，prompt 还可能带上角色人设、最近的对话和相关记忆。它是“本轮要参考什么”，不是一份单独的聊天记录。
- **token**：模型读写文本时使用的计量单位，用来估计输入长度和用量。它和汉字数、词数没有固定换算关系；同一段中文在不同模型里也可能拆成不同数量。比如“你好。”有几个 token，要看使用的模型和分词方式。[TokenEstimator.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/mem/TokenEstimator.kt)
- **上下文窗口**：模型一次能处理的容量。它通常涉及本轮输入和输出，具体怎么算由模型服务说明决定。前面的 100,000 token 例子就是用这个容量解释超限。
- **模型**：实际生成回复或整理记忆的 AI 服务。设置里的模型名告诉服务商要调用哪个模型；模型能力、容量和收费由服务商发布。
- **API**：应用与服务沟通时遵循的一套请求和回应格式。PulseZ 把聊天、语音转写或旧事检索请求发到设置的服务地址，再读取服务的回应。
- **服务商**：提供模型或相关接口的一方。聊天服务与“找旧事”检索服务分别配置；语音转写使用 DeepSeek 聊天服务的地址和聊天密钥，不能另配一个转写服务地址。若服务地址经过代理或中转，实际处理请求的一方可能还包括代理服务。
- **API 密钥**：服务商用来识别账户和授权请求的凭据。PulseZ 在设备上加密保存密钥，发请求时将其用于身份验证；服务方收到数据后的保存规则要看服务商自己的政策。[SecretStore.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/util/SecretStore.kt#L17-L44) · [LlmClient.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/net/LlmClient.kt#L49-L61)

### 长聊、记忆和找旧事

- **交接与分段**：当前段达到应用的本地规划条件后，后台会尝试整理它、封存旧段并开始新段。交接过程中，对话会按块送去处理；每块末尾的少量原文会组成编年史摘录，另有模型调用提取记忆。后续聊天仍可沿用同一会话。[ChatEngine.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/ChatEngine.kt#L576-L654)
- **长期记忆**：从聊天里整理出的事实、事件、偏好、提醒或边界等条目。它和当前段原文分开保存；“TA 记得多清楚”设置调整的是近期原文保留量，不是记忆条目数量。[SettingsScreen.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/ui/settings/SettingsScreen.kt#L1156-L1190) · [MemoryExtractionPrompt.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/mem/MemoryExtractionPrompt.kt)
- **双时态记忆**：每条记忆同时记录两条时间线：事情在现实中何时有效，以及应用何时看到、更新过这条信息。例如，某个字段先记录值 A，之后修正为值 B；记录会保留 A 曾经有效的时间，并记录应用先后观察到 A、B 的时间。这样修正新信息时，旧记录可标为已被替代，而不是抹去它曾存在的历史。[MemTypes.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/mem/MemTypes.kt#L75-L110)
- **embedding（向量表示）**：把一段文字转换成一串数字，让系统比较两段文字的意思是否接近。检索服务已配置且可用，并且导入时选中“顺便按意思找旧事”后，导入的聊天片段才会发给检索服务转换；需要按意思检索时，查询文字也会发给该服务转换。应用把旧片段和对应数字保存在本地，用它们找相关对话。[DeepSeekImportScreen.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/ui/importer/DeepSeekImportScreen.kt#L744-L765) · [EmbeddingClient.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/net/EmbeddingClient.kt#L46-L89) · [ImportIndexer.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/ImportIndexer.kt#L430-L447)
- **语义检索**：按表达的意思找相关旧聊天，而不只依赖相同字词。例如，查询换了说法时，数字表示仍有机会找到意思接近的片段。它不是联网搜网页；未配置检索服务或请求失败时，PulseZ 可退回本地关键词检索。[Recall.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/mem/Recall.kt#L27-L37) · [Recall.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/mem/Recall.kt#L117-L124)
- **关键词索引**：应用本地整理出的文字索引，适合查找原话或相同字词。它不需要另一个检索服务；语义检索不可用时仍可使用。

### 长聊和导入旧聊天时，为什么值得开启“找旧事”？

如果你打算长期接着同一条会话聊天，或把以前的 DeepSeek 聊天导入 PulseZ 后继续聊，建议单独配置“找旧事”服务。专门的文本检索模型会把待查的旧聊天片段和查询文字分别转换成一串数字，再按相似度找到意思接近的片段；PulseZ 同时在本地做关键词匹配，并融合关键词分数与语义相似度。适合回忆旧事的聊天轮次最多挑三条相关片段交给聊天模型参考，不会每轮都把全部导入历史交给生成模型重读。[Recall.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/mem/Recall.kt#L31-L37) · [ChatEngine.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/ChatEngine.kt#L285-L322)

这种“检索模型负责找、聊天模型负责写”的分工，能减少重复交给聊天模型处理的大量旧历史，检索模型通常也能以较低的单位价格完成查找。例如，硅基流动普通版 BAAI/bge-m3 的官方页当前列出输入免费，智谱 Embedding-3 的官方页可查模型说明与定价；实际价格要以你选的模型和服务商当前页面为准。[硅基流动 BAAI/bge-m3 模型页](https://www.siliconflow.cn/models/BAAI%2Fbge-m3) · [智谱 Embedding-3 官方说明](https://docs.bigmodel.cn/cn/guide/models/embedding/embedding-3) 它带来工程和用量上的效率空间，但不能保证每个人的总账都会下降：建立索引会发送待索引的聊天片段，合适的检索请求也会发送查询文字，两者都可能产生服务商用量或费用。你填写的检索服务会收到这些文本；使用前应查看该服务商的数据处理政策，只导入并查询你接受交给该服务商处理的内容。[ImportIndexer.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/ImportIndexer.kt#L430-L447) · [ChatEngine.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/ChatEngine.kt#L285-L322) · [EmbeddingClient.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/net/EmbeddingClient.kt#L46-L89)

### 实用配置：硅基流动 BAAI/bge-m3

截至 2026-11-25，硅基流动官方模型页中普通版 `BAAI/bge-m3`（非 Pro）的输入价格标为免费。价格和服务政策可能变化；这是服务商官网当前的标价，不代表已经在你的设备或账号上实测，也不保证你的账号有相同的可用额度。[硅基流动 BAAI/bge-m3 模型页](https://www.siliconflow.cn/models/BAAI%2Fbge-m3)

1. 在 PulseZ 设置里打开“让 TA 更会找旧事”，点“硅基流动”示例。示例只会填写地址 `https://api.siliconflow.cn/v1` 和模型名 `BAAI/bge-m3`，不会替你填写密钥。
2. 在硅基流动账号中自行取得 API 密钥，填入“密钥”一栏。不要把密钥发给别人或写进公开文档。
3. 点“测一下找旧事”确认这组设置可连接，再点“保存”。[FindOldThingsConfigScreen.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/ui/config/FindOldThingsConfigScreen.kt#L82-L105) · [FindOldThingsConfigScreen.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/ui/config/FindOldThingsConfigScreen.kt#L372-L418)
4. 导入 DeepSeek 旧聊天时，勾选“顺便按意思找旧事”，让导入片段在导入时建立语义索引。若已有导入片段当时没有建立索引，可在条件符合时到记忆面板点“让 TA 能按意思找旧事”补建；入口要求已配置检索服务且确有待补建片段，操作由你点击后才开始。补建只处理仍没有检索表示的导入片段，并且每条会话最多扫描最近 2,000 个检索块，因此不能保证覆盖所有更早的旧聊天。[DeepSeekImportScreen.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/ui/importer/DeepSeekImportScreen.kt#L744-L765) · [MemoryViewModel.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/ui/memory/MemoryViewModel.kt#L413-L455) · [MemoryViewModel.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/ui/memory/MemoryViewModel.kt#L614-L629) · [MemoryViewModel.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/ui/memory/MemoryViewModel.kt#L794-L839)

### 其他服务商的官方资料

- [智谱 Embedding-3 官方说明](https://docs.bigmodel.cn/cn/guide/models/embedding/embedding-3)：了解另一种文本检索模型及其接口。
- [OpenAI Embeddings 官方指南](https://developers.openai.com/api/docs/guides/embeddings)：了解文本转换为表示并用于相似度检索的基本方式。
- [火山引擎方舟“向量化”文档](https://docs.volcengine.com/docs/ark/vectorization?lang=zh)：可作模型和接口技术资料，不是 PulseZ 的推荐配置。v0.6.9 客户端固定按 OpenAI 兼容的 `/embeddings` 接口发送请求并读取返回值；火山文档中的接口能否直接兼容尚未实测，因此不应据此认定可直接填入 PulseZ 使用。[EmbeddingClient.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/net/EmbeddingClient.kt#L78-L127)

### 提示缓存

**缓存**是服务商短期保留并复用重复输入的一种方式。PulseZ 把相对稳定的规则和人设放在前面，旧事摘录和历史也按稳定顺序追加；每轮变化的记忆、检索内容和最新输入放在后面。因此，连续对话中前面较大的部分可能重复。服务商若报告缓存命中，PulseZ 会按命中与未命中用量分别估算输入成本；没有拆分数据时，估算会把输入按未命中处理。缓存命中条件和价目表以官方说明为准。[OpenAI 提示缓存说明](https://developers.openai.com/api/docs/guides/prompt-caching) · [DeepSeek 缓存说明](https://api-docs.deepseek.com/guides/kv_cache/) · [Gemini API 定价](https://ai.google.dev/gemini-api/docs/pricing) · [LlmClient.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/net/LlmClient.kt#L531-L555)

## 设置和其他功能

- **“TA 记得多清楚”**：选择应用保留近期对话原文的档位。保留量较大时，较多近期内容可能随每轮请求发给聊天服务；它影响应用何时安排交接，也通常增加输入用量。长期记忆单独提取，不会因为档位变大而自动增加。
- **创作模式**：用于一对一角色聊天，让回复可以展开更多故事和画面。群聊不使用这个模式；是否开由你决定。[CreativeMode.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/CreativeMode.kt#L13-L32)
- **深度思考与智能搜索**：这些选项只在配置 DeepSeek 官方服务地址时提供。深度思考改变发给模型的思考设置；智能搜索请求 DeepSeek 的搜索工具。实际是否可用取决于所选模型和服务端回应。[Payloads.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/net/Payloads.kt#L80-L90) · [LlmClient.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/net/LlmClient.kt#L213-L229)
- **图片**：发送前会压缩图片。聊天请求会带上本轮图片，以及最近一个带图的用户回合；更早图片在对话里以文字占位保留。若同一张图后来再次随请求发送，服务商可能再次计算相关用量。[ImageCopy.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/ui/copy/ImageCopy.kt#L18-L35) · [ChatEngine.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/ChatEngine.kt#L157-L166)
- **语音输入与朗读**：语音输入录音会发给已配置的 DeepSeek 聊天服务转成文字，文字先进入输入框供你修改；录音在处理后从临时位置删除。你可以设置自动朗读、语速和音色；朗读使用手机上的 Android 文字转语音引擎，是否联网取决于设备安装的引擎。[VoiceCopy.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/ui/copy/VoiceCopy.kt#L139-L190) · [AndroidTtsEngine.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/audio/AndroidTtsEngine.kt)
- **导入 DeepSeek 旧聊天**：应用在本地解析你选中的导出文件；你可以按角色整理。导入后会建本地关键词索引。开启“按意思找旧事”会把聊天片段发给配置的检索服务建立索引；可选的记忆整理或自动分角色、人设生成会把相应片段交给聊天模型处理。导入与整理的 AI 用量可能收费，页面估价以服务商账单为准。[DeepSeekImporter.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/importer/deepseek/DeepSeekImporter.kt) · [ImportIndexer.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/ImportIndexer.kt#L69-L82) · [ImportMemoryConsolidator.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/ImportMemoryConsolidator.kt#L65-L95)

## 谁会收到什么数据？

数据会发送到你配置的对应服务，而不是笼统地发给 PulseZ：

- **聊天服务**收到本轮组装出的角色设定、当前段对话、需要的记忆或旧事摘录、你刚输入的话和本轮图片，以及较早对话中最近一个带图用户回合的图片。如果在导入后开始续聊且当前段还较短，应用还可能把最近导入段中的部分原话补进请求。[ChatEngine.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/ChatEngine.kt#L134-L155) 长聊交接或记忆提取也会另行请求聊天服务。[ChatEngine.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/ChatEngine.kt#L157-L166)
- **“找旧事”服务**在启用语义检索并配置有效服务时，正常单聊建立索引会发送本轮用户和 TA 的对话片段（各最多 200 字）；导入历史建立索引时会发送导入片段，查找时会发送查询文字。服务返回数字表示；关键词索引、旧聊天片段和已生成的表示保存在本地。[ChatEngine.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/ChatEngine.kt#L414-L455) · [ImportIndexer.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/ImportIndexer.kt#L430-L447) · [EmbeddingClient.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/net/EmbeddingClient.kt#L46-L89)
- **语音转写**使用已配置的 DeepSeek 聊天服务地址和聊天密钥发送录音并接收识别文字。自动分角色、导入记忆整理等可选功能会把待处理的聊天片段交给聊天服务。
- **Android 朗读引擎**读取要朗读的文字；引擎是否把文字发往网络由设备上的引擎决定。

聊天和“找旧事”可以使用不同地址与密钥。密钥在设备上加密保存，也不会进入 PulseZ 备份。服务地址可以指向中转；客户端源码能说明发送了什么请求，无法说明服务商或代理收到之后如何保存。[ConfigScreen.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/ui/config/ConfigScreen.kt) · [FindOldThingsConfigScreen.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/ui/config/FindOldThingsConfigScreen.kt#L181-L202) · [SecretStore.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/util/SecretStore.kt#L17-L44) · [BackupCopy.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/ui/copy/BackupCopy.kt#L98-L109)

## 删除、清空和备份

**删除一条记忆**会让它不再作为当前有效记忆进入提示内容；产生这条记忆的聊天原文仍在。若旧聊天之后又被提取，可能重新整理出相似记忆。源码提供了“删掉来源消息后将相关记忆标为未核实”的方法，但 v0.6.9 主流程没有调用点，因此删除单条消息后相关记忆会如何变化，未核实；可在记忆面板单独检查和管理。单条消息删除后，相关检索索引是否同步清理也未核实。[ChatEngine.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/core/ChatEngine.kt#L348-L352) · [MemoryRepository.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/data/repo/MemoryRepository.kt#L192-L214) · [MemorySchema.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/mem/MemorySchema.kt#L270-L308)

**清空所有数据**会删除单聊和群聊、角色资料、人设、记忆与找旧事索引、关系和心情状态、图片副本、用量与花费记录、群聊发言顺序记录、临时录音和导入文件、未完成导入进度、服务商设置、费用上限及大部分偏好。清空完成后，这些数据无法找回；协议同意记录仍会保留。[ClearCopy.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/ui/copy/ClearCopy.kt)

**备份**保存角色、对话和记忆，适合换手机或重装后恢复。它不包含图片和文件本体、头像、语义索引、已删除内容、关系阶段/昵称/心情、用量与费用记录、服务商地址和密钥，以及记忆来源的原句链接。恢复会新建一份角色和对话，不覆盖手机里已有内容。[BackupCopy.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/ui/copy/BackupCopy.kt#L38-L51) · [BackupCopy.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/ui/copy/BackupCopy.kt#L74-L109)

**检查更新**会告诉你有无新版本；选择下载后由浏览器打开下载页，是否安装由你决定。[UpdateCopy.kt](https://github.com/lh0227-tech/PulseZ/blob/a3dd337684172de88b1303bc62b4862d0e28b1dd/app/src/main/java/cc/pulsez/app/ui/copy/UpdateCopy.kt#L118-L147)






