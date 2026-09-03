# InkForge 开发问题日志

> 目的：记录开发中遇到的**真实问题与解决思路**，供事后复盘学习。
> 约定：每个有记录价值的问题按「现象 / 根因 / 解决 / 教训」四条记录，新条目写在前面，保持可检索。

---

## 2026-08-27 P4-UI-E（Settings 只读配置页）

### 浏览器自动化：杀 CDP 测试进程要按端口找 PID，别靠 bash 的 $!
- **现象**：`rm -rf` 临时 profile 目录报一堆 `Device or resource busy`；重启 Edge 后 9222 端口仍是旧实例。
- **根因**：P4-UI-D 时用 `taskkill //PID $!` 杀的是 bash 后台 job 的 PID，Edge 真正的子进程还活着，一直占着 9222 和 profile 锁。
- **解决**：`netstat -ano | grep ':9222' | grep LISTENING` 拿真实 PID → `taskkill //PID <pid> //F`；下次验证用全新 profile 目录。
- **教训**：Windows 下杀 GUI 子进程链不能只杀父 shell PID；任何"端口被占"先 netstat 定位，再精确 taskkill。

### 测试脚本断言字符串与 UI 文案错位
- **现象**：断言 `keyNote.includes('不显示、也不会保存')` 失败，UI 实际文案是"**不会**显示、也不会保存"。
- **根因**：手写断言时少打了一个"会"，与真实文案不完全一致。
- **解决**：改成与文案逐字一致；若文案可能改动，断言应宽容（只查关键子串）。
- **教训**：断言目标要贴源文案，别凭印象；先打印实际内容再断言。

---

## 2026-08-30 P4-UI-F（UX / Responsive / 交互打磨）

### 断点切换内联↔Drawer 会让组件重挂载、丢失缓存
- **现象**：Trace 面板宽屏内联、窄屏进 Drawer；跨越断点（如 1440→820）时面板被重挂载，state 缓存丢失，下次打开重新请求。
- **根因**：条件渲染 `{compact ? <Drawer>…</Drawer> : …}` 在 compact 翻转时 React 卸载旧分支、挂载新分支，组件实例重建。
- **解决**：Drawer 设计为**始终挂载 + CSS 隐藏**（`display:none` 切换 open/closed），同视口内打开/关闭不重挂载、缓存保留；跨断点重挂载是 CSS 断点的固有代价，接受。
- **教训**：需要保状态的子树放进「常挂载容器」（CSS 显隐），不要条件卸载。

### 加载未完成就关闭 → 重新打开必然重拉（这是对的行为）
- **现象**：测试断言「重开不重复请求」失败，before=2 after=3。
- **根因**：面板 fetch 还没返回时就 Escape 关闭，effect 的 `cancelled` 守卫丢弃了结果（trace 未缓存）；重开时 trace 为 null → 重拉。
- **解决**：测试先等 `.trace-intro`（数据渲染完成）再关闭；产品行为本身正确——「加载成功的才缓存，中途取消的不算」。
- **教训**：断言缓存前必须先让首次加载真正完成；cancelled 守卫带来的「关闭即放弃缓存」是特性不是 bug。

### React 19 StrictMode 开发态 effect 双跑
- **现象**：`performance` 里某次挂载出现 2 条同类请求。
- **根因**：dev 模式 StrictMode 会 mount→unmount→mount，effect 执行两次（第一个被 cancelled，但网络请求已发出）。
- **解决**：验证/统计请求次数时要区分 dev 双跑；生产构建无此现象。
- **教训**：dev 下的网络计数会翻倍，别把 StrictMode 双请求当成重复请求 bug。

### Provider 便捷预填启发式：「等于上一 provider 预设」太保守
- **现象**：mock 下 baseUrl/model 是 deepseek 启动默认，切到 ollama 时预填不触发（当前值 ≠ mock 预设的空串）。
- **根因**：判断「是否未自定义」只比对上一预设，忽略了启动默认值。
- **解决**：改为「当前值 ∈ 已知默认值集合（启动默认 + 全部预设）则预填」，用户自定义值不在集合内 → 不覆盖。
- **教训**：判断「用户是否手填过」要用「是否等于已知默认值」而非「是否等于上一个预设」。

---

## 2026-08-27 运行时 LLM 配置（前端选模型+填密钥）

### React DOM 嵌套警告：`<p>` 里包了块级元素，只在特定分支渲染时才暴露
- **现象**：`In HTML, <div> cannot be a descendant of <p>` 控制台警告，但之前 P4-UI-D/E 验证都干净。
- **根因**：`GenerationStatus.tsx` 里 `<p className="trace-note"><EmptyState/></p>`——`<p>` 包了渲染 `<div>` 的组件。只有 `!hasTrace && stage==='done'` 分支（续写失败无 trace）才渲染，此前所有续写都成功所以从未触发。
- **解决**：`<p>` 改 `<div>`。
- **教训**：React 19 对非法嵌套的校验是**按渲染路径**触发的；一次"功能全对"不代表所有分支的 DOM 合法。验证要专门打失败路径。

### React 19 对 `<dl>` 包 `<div>` 也报嵌套警告
- **现象**：配置页用 `<dl><div><dt><dd>`（HTML5 合法的 dl>div 分组），React dev 仍告警。
- **根因**：React 的校验器比 HTML5 规范更严，`<dl>` 的直接子级被限定为 dt/dd。
- **解决**：改用纯 `<div>` + `<span>` 结构，避开语义标签。
- **教训**：React 组件里别用 `<dl>`/`<dt>`/`<dd>` 做布局；要么 dt/dd 直接平铺，要么用 div。

### InMemory 仓库返回顺序不可靠
- **现象**：验证脚本断言"最新一条生成日志"取数组最后一项，结果拿到的是旧日志。
- **根因**：`ConcurrentHashMap.values()` 遍历无序，`findByNovelId` 的返回顺序不保证。
- **解决**：脚本按 `createdAt` 排序取最新。
- **教训**：断言"最新/最早"前先确认存储是否有序；Map 实现不保证顺序。

### 验证脚本状态污染：后端内存配置跨测试轮次残留
- **现象**：上一轮填的 deepseek key，下一轮验证开头就"已配置"，断言"初始未配置"失败。
- **根因**：RuntimeLlmConfig 的 key 存后端内存，跨多次页面加载/测试脚本残留。
- **解决**：每轮验证前重启后端（顺便验证"重启丢 key"），或先 PUT 清空。
- **教训**：可变全局状态（内存配置）会让断言依赖执行历史；测试要显式重置前置状态。

---

## 2026-08-27 P4-UI-D（Retrieval Trace 产品化）

### TS7053：对带可选键的「类型化 Map」按下标取元素报错
- **现象**：`trace.pipeline[s.key]`（s.key 为 `string`）报 `Element implicitly has an 'any' type`，因为 pipeline 类型是无 index signature 的对象字面量类型（`{ bm25?: ...; vector?: ... }`）。
- **根因**：TS 不允许用 `string` 索引一个只有具体可选键的类型；同时 `StageDefinition.key` 定义成了 `string`，丢失了键名约束。
- **解决**：把 key 类型收窄为 `keyof RetrievalTrace['pipeline']`，索引与遍历全部类型安全。
- **教训**：给「固定键集合的配置对象」写类型时，键字段用 `keyof 对应类型`，别用 `string`；否则每次按下标取都要做 as 断言或加 index signature。

### TS6133：重构后残留未使用的导入/函数
- **现象**：删掉一个辅助函数后，它导入的类型 `TraceRetrievalResult` 和自身声明都没用了，`tsc -b` 报 `declared but never read`。
- **根因**：增量重构时删了使用点、忘了同步删声明。
- **解决**：按报错删干净；build 立刻复跑。
- **教训**：前端 0-error 门槛是 `tsc -b` 的严格模式，未使用声明也算错；改完文件顺手 grep 下残留 import。

### 浏览器自动化：等「数据渲染后的 DOM」，别只等容器
- **现象**：CDP 点击「查看检索过程」后立刻断言，面板标题读到了，但方向卡片/流水线全是空的。
- **根因**：`.trace-panel` 在点击瞬间就挂载（先显示 loading），Trace 数据是异步 fetch 后才有 `.trace-intro` / `.direction-card`；等容器元素不等于等数据。
- **解决**：改等 `.trace-intro`（数据渲染后的首个稳定节点）再断言，全部断言通过。
- **教训**：驱动异步渲染 UI 时，waitFor 的目标要是「数据到达后才会出现的元素」，不是外层容器。

### Node ESM：`.mjs` 没有 `require`
- **现象**：写截图脚本用 `require('fs')` 直接 `ReferenceError: require is not defined`。
- **根因**：`.mjs` 是 ESM，没有 CommonJS 的 `require`。
- **解决**：`import { writeFileSync } from 'node:fs'`。
- **教训**：临时 Node 脚本用 `.mjs` 时，所有 CJS 写法要换成 ESM import。

---

## 2026-08-27 运行验证

### 端口占用：InkForge 前端 dev 端口不固定
- **现象**：`npm run dev` 后 Vite 提示 `Port 5173 is in use, trying another one...`，实际服务落在 5174。
- **根因**：`D:\coderag` 的旧 Vite dev server 长期占用 5173-5178，InkForge 的 Vite 配置写死 5173，冲突后自动后移。
- **解决**：无需改配置，看启动日志确定实际端口即可（本次为 5174）；`/api` 代理仍正确转发到 8080。
- **教训**：Vite 的 `server.port` 是"首选"而非"强制"；浏览器访问前先确认端口，别想当然。

---

## P1-P3 期间已沉淀（SB4 环境陷阱，写新代码必看）

### Jackson 3 包名迁移 + starter 依赖
- **现象**：按老教程用 `com.fasterxml.jackson` / `spring-boot-starter-web` 编译报错或注入不到 ObjectMapper。
- **根因**：Spring Boot 4 使用 Jackson 3，包名变为 `tools.jackson.databind`；webmvc starter 不传递 Jackson，需要显式加 `spring-boot-starter-json`。
- **解决**：导入 `tools.jackson.databind.ObjectMapper`，pom 依赖 `spring-boot-starter-json`。
- **教训**：SB4 是大版本迁移，命名空间/starter 结构都与 SB3 不同，别依赖旧记忆。

### Starter 与测试注解包名
- **现象**：`spring-boot-starter-web` 找不到、`@AutoConfigureMockMvc` 导入失败。
- **根因**：SB4 拆分为 `spring-boot-starter-webmvc` / `-webmvc-test`；MockMvc 自动配置注解移到 `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`。
- **解决**：改用上述 starter 与注解包名。
- **教训**：升级框架后先查该版本的 starter 清单，再写依赖。

### MockMvc 读 SSE 响应乱码/空
- **现象**：SSE 测试拿到的 content 为空或乱码。
- **根因**：SB4 MockMvc 的 SSE 响应没有 charset，直接 `getContentAsString()` 按默认编码读错。
- **解决**：`getContentAsString(StandardCharsets.UTF_8)`。
- **教训**：流式/字节响应的解码要显式指定字符集。

### Flyway 自动配置缺失 & DataSource 排除
- **现象**：默认 profile 启动报数据源相关错误；Flyway 不生效。
- **根因**：SB4 不再自动配置 Flyway；默认 profile 要手动排除 `DataSourceAutoConfiguration` / `HibernateJpaAutoConfiguration`（包名 `org.springframework.boot.jdbc.autoconfigure.*` / `org.springframework.boot.hibernate.autoconfigure.*`）。
- **解决**：默认 profile 显式 exclude。
- **教训**：SB4 默认更"裸"，自动配置收敛了，默认 profile 走内存仓储就要排除数据源自动配置。

### 同名 @Bean 方法被拒（enforceUniqueMethods）
- **现象**：配置类里两个同名方法上标 @Bean，启动直接报错。
- **根因**：SB4 默认 `enforceUniqueMethods=true`，同名方法不被允许。
- **解决**：方法名唯一 + `@Bean("name")` 显式命名；多构造器 bean 用 `@Autowired` 标注。
- **教训**：@Bean 命名要显式，别依赖方法名冲突后重载解析。

### Reactor Flux.map 不能返回 null
- **现象**：SSE 生成流中途 NPE / 元素丢失。
- **根因**：`Flux.map` 不允许返回 null；SSE data 需全 JSON 编码。
- **解决**：用 `handle` 替代 map 做过滤，或返回空串。
- **教训**：Reactor 算子契约要记住，map=1:1 且非 null。

### 同类型双 Bean 注入歧义
- **现象**：构造器注入报 `NoUniqueBeanDefinitionException`。
- **根因**：同一接口有两个实现同时被组件扫描。
- **解决**：用 `@Qualifier` 指定 bean 名，或去掉 `@Component` 改由 Config 显式装配。
- **教训**：多个实现时装配策略要显式，别靠扫描猜。

### Windows 下 Java 进程残留占端口
- **现象**：停掉 mvnw 后 8080 仍被占用，重启后端报端口冲突。
- **根因**：Windows 上 `TaskStop` 杀掉 Maven 父进程后，Java 子进程残留。
- **解决**：`netstat -ano | grep 8080` 找 PID → `taskkill //PID <pid> //F`。
- **教训**：Windows 进程树没有自动级联终止；本机 Java 在 `C:\Program Files\Java\jdk-21\bin`。

### Lucene 模块依赖与 TopDocs 语义
- **现象**：`QueryParser` 找不到；0 分文档进不了结果。
- **根因**：`QueryParser` 是 Lucene 独立模块需单独依赖；`TopDocs` 不含 0 分文档（默认不返回）。
- **解决**：pom 单独加 queryparser 依赖；检索设计时接受"无分文档不召回"这一语义。
- **教训**：Lucene 按模块拆分，别假设 core 全包。

### InMemory 仓储：chunkId 无法反推 novelId
- **现象**：拿到 chunkId 后不知道属于哪本小说，跨模块联调困难。
- **根因**：InMemory 仓储按 id upsert，chunkId 与 novelId 无反向映射。
- **解决**：存储层记录时直接带上 novelId 字段。
- **教训**：设计仓储时明确"谁持有父实体 ID"，别在存储层丢上下文。
