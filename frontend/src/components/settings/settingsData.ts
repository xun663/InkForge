/**
 * Settings 静态配置说明数据源（P4-UI-E，方案 A：只读 Configuration Guide）。
 *
 * ⚠️ 安全约束：
 * - 此处保存的每个值都是「默认值」，必须明确标注，不得写成「当前生效值」。
 * - 绝不在此文件或任何前端代码中保存真实 API Key / Token / Password / Secret。
 * - 只展示环境变量「名称」，永不展示其「值」。
 * - 与 backend/src/main/resources/application.yml 对齐；改 yml 需同步此处。
 *
 * 定位：配置说明 + 环境变量修改指南。不支持运行时编辑、不提供保存按钮。
 */

export interface ConfigRow {
  label: string
  /** 默认值（未覆盖时生效） */
  value: string
  /** 覆盖此默认值的环境变量名（只展示名称） */
  envVar?: string
  /** 人话帮助说明 */
  hint?: string
}

export interface ConfigSection {
  id: string
  title: string
  description?: string
  rows: ConfigRow[]
}

export interface ConfigGuide {
  /** 页面顶部诚实的只读说明 */
  guideNote: string
  /** 普通用户分组 */
  sections: ConfigSection[]
  /** 高级配置（默认折叠） */
  advanced: ConfigSection[]
  /** API Key 安全说明（系统组内） */
  apiKeyNote: {
    title: string
    lines: string[]
  }
}

export const configGuide: ConfigGuide = {
  guideNote:
    '本页为配置说明。上方「LLM 配置」支持运行时切换与填写 API Key（仅保存在后端内存，重启后恢复默认）；' +
    '其余为只读默认配置，实际生效值来自环境变量或 application.yml，修改后需重启后端。' +
    '界面不会显示任何 API Key。',

  sections: [
    {
      id: 'ai-models',
      title: 'AI 模型',
      description: 'LLM 见上方「LLM 配置」运行时区；以下为 Embedding 与 Reranker 默认配置。',
      rows: [
        {
          label: 'Embedding Provider',
          value: 'mock',
          envVar: 'INKFORGE_EMBEDDING_PROVIDER',
          hint: '将故事记忆转换为向量，用于语义检索。真实语义需配置 OpenAI 兼容 Embedding（如 bge-m3）。',
        },
        {
          label: 'Embedding Model',
          value: 'bge-m3',
          envVar: 'INKFORGE_EMBEDDING_MODEL',
        },
        {
          label: 'Embedding Dimension',
          value: '1024',
          hint: '必须与向量索引维度一致；改动需同步数据库，否则启动会报错而非静默截断。',
        },
        {
          label: 'Reranker',
          value: 'passthrough',
          envVar: 'INKFORGE_RERANKER',
          hint: '对候选记忆进一步排序。passthrough（默认）= 确定性截断，不调 LLM；llm = 用 LLM 重排。',
        },
      ],
    },
    {
      id: 'generation',
      title: '生成',
      description: 'AI 续写内容的输出参数。',
      rows: [
        {
          label: 'Max Output Tokens',
          value: '2048',
          hint: '单次续写最多生成的 token 数。',
        },
        {
          label: 'Temperature',
          value: '0.8',
          hint: '越高越随机，越低越稳定。',
        },
      ],
    },
    {
      id: 'retrieval',
      title: '检索',
      description: '混合检索管线：关键词 + 语义 → 融合 → 重排。',
      rows: [
        {
          label: 'BM25 Top K',
          value: '30',
          hint: 'BM25：根据关键词寻找相关剧情，取前 K 条候选。',
        },
        {
          label: 'Vector Top K',
          value: '30',
          hint: '向量检索：根据语义相似度寻找，取前 K 条候选。',
        },
        {
          label: 'Fusion Top K',
          value: '30',
          hint: '融合关键词与语义两条结果后的候选上限。',
        },
        {
          label: 'RRF K',
          value: '60',
          hint: 'RRF 融合的平滑参数，控制排名加权。',
        },
        {
          label: 'Rerank Top K',
          value: '8',
          hint: '重排后最终进入续写上下文的记忆条数。',
        },
      ],
    },
    {
      id: 'system',
      title: '系统',
      description: '运行方式与安全说明。',
      rows: [
        {
          label: 'Spring Profile',
          value: 'default（InMemory）',
          hint: '默认使用内存存储，零依赖、零 Docker。可选 postgres profile 启用 PostgreSQL 持久化。',
        },
        {
          label: 'Storage',
          value: 'InMemory（默认） / PostgreSQL 16 + pgvector（可选）',
          hint: 'PostgreSQL 非必须；仅当你需要持久化记忆与向量时，用 docker compose 启动并加 postgres profile。',
        },
      ],
    },
  ],

  advanced: [
    {
      id: 'context',
      title: 'Context',
      description: '续写时上下文预算（整体不变量：总 token ≤ 上限）。',
      rows: [
        { label: 'Context Max Tokens', value: '8192' },
        { label: 'Breakpoint Tail Chars', value: '2000' },
      ],
    },
    {
      id: 'memory-extraction',
      title: 'Memory Extraction',
      description: '故事记忆提取预算与校验参数。',
      rows: [
        { label: 'Extract Window', value: '3', hint: '每次「建立故事记忆」处理的未提取章节数。' },
        { label: 'Extraction Input Budget', value: '12000', hint: '单次提取调用允许的输入 token 预算。' },
        { label: 'Extraction Max Output Tokens', value: '2048' },
        { label: 'Extraction Temperature', value: '0.2' },
        { label: 'Max Retries', value: '2', hint: '提取校验失败后的重试次数。' },
        { label: 'Confirm Confidence', value: '0.7', hint: '人物事实确认的置信度阈值。' },
        { label: 'Source Quote Max Chars', value: '300' },
        { label: 'Chunk Overlap Chars', value: '200' },
      ],
    },
    {
      id: 'import',
      title: 'Import',
      description: '导入层的资源保护上限（防御性限制，非常规路径限制）。',
      rows: [
        { label: 'Max File Size', value: '100MB' },
        { label: 'Max Chapters', value: '10000' },
        { label: 'Max Chapter Chars', value: '100000' },
      ],
    },
    {
      id: 'cost',
      title: 'Cost',
      description: '成本估算单价（仅用于界面显示估算费用）。',
      rows: [{ label: 'deepseek-chat', value: '输入 $0.27 / 百万 token · 输出 $1.10 / 百万 token' }],
    },
  ],

  apiKeyNote: {
    title: 'API Key 安全说明',
    lines: [
      '运行时填写的 LLM API Key 仅保存在后端内存，绝不落库、不写日志、不在界面回显，后端重启后失效。',
      '也可用环境变量提供：INKFORGE_LLM_API_KEY 与 INKFORGE_EMBEDDING_API_KEY。',
      '未配置 Key 时使用内置 Mock Provider，整套流程可零 Key 运行。',
    ],
  },
}
