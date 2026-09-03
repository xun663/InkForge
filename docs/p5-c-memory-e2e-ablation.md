# P5-C End-to-End Memory ON/OFF A/B 报告（终版评价）

## 1. Objective
回答：在不要求复现原作剧情的前提下，Story Memory 是否改善续写的**长期一致性与叙事连贯性**。
固定 模型/Prompt/可见前缀/生成参数，唯一变量 = Story Memory。

## 2. Why Original Text Is Reference, Not Ground Truth
小说续写是开放式生成，无唯一正确答案。原作第 N+1 章只作 Reference / sanity reference（理解作者如何处理伏笔、
人物走向、设定展开），**不作 Exact Match / "写错没写错"的判据**。新剧情、不同人物选择、不同冲突走向、
不同伏笔解释、不同结局，只要不违反硬事实、与人物状态/世界规则兼容、叙事自然，均可获高分。

## 3. E2E A/B Design
3 篇原创短篇（剑断长夜/雾港迷案/古卷残页，无热门作品先验）× 2 断点 = **6 cases**（剑断5/7、雾港4/6、古卷5/7）。
复用既有 artifacts（memory-off/on 的 context/generation/metadata + on 的 trace），**不重新生成**。

## 4. Memory OFF / ON Definition
- OFF = 同一 `continuation.memory.user` 模板，仅含最近两章原文；**不调用** Story Memory Retrieval。
- ON = 同模板 + 检索到的相关记忆（冻结管线 QueryIntent→BM25(30)+BGE(30)→RRF(k60)→Fusion(30)→PassThrough→
  合并 top-30→Rank-Preserving，~989-token 预算）。Memory 仅由 ≤cutoff 章节构建，无未来泄漏。
- 生成：deepseek-v4-flash · temp0.8 · maxTokens1000 · 单样本无 seed。

## 5. Evaluation Rubric（10 维 × 0~2 = /20，等权）
**Hard Consistency**：① Historical Fact ② Character State ③ Timeline/Scene ④ World Rule
**Narrative Continuity**：⑤ Context Continuity ⑥ Character Continuity ⑦ Long-term Coherence ⑧ Foreshadowing Compatibility
**Writing Quality**：⑨ Novelty & Plausibility ⑩ Overall Narrative Quality（Pacing 作为 ⑩ 的评价要点，不单列）
0=明显冲突；1=轻微/无法确定；2=与已有故事一致。可额外记 contradictionCount。

## 6. Checklist（每维先答 check 再打分）
- Context：是否自然承接前一场景？有无无解释的时间/空间跳跃？当前事件是否无故重置？
- Character：是否违反既有性格/目标/关系/状态？
- Foreshadowing：是否破坏已有线索？是否无必要提前揭谜？是否保留反转空间？（不是要求揭示伏笔）
- Long-term：早期长期信息是否被遗忘？后续行为是否与早期设定兼容？有无前后矛盾？
- World/Hard：历史事件、人物状态、时间线、世界规则是否有硬冲突？

## 7. Pairwise Evaluation
每 case 另做极简 Pairwise：**"哪一个更适合作为当前故事的自然续写？" → ON / OFF / Tie**。不单独依赖，
与 10 维 Rubric 并用（Pairwise 降尺度主观性，Rubric 解释"为什么"）。

## 8. Blind Review
无第三方评审。由本人按先固定 rubric 逐 case 阅读 OFF/ON 文本打分；**已确知标签、非真盲评**（局限如实）。
结果文本来回填标签；报告不据此过度断言。

## 9. Results（10 维 ×0-2，sum /20）
| case | OFF sum | ON sum | Pairwise |
|---|---|---|---|
| 剑断5 | 17 | 19 | ON |
| 剑断7 | 10 | 19 | ON |
| 雾港4 | 18 | 18 | Tie |
| 雾港6 | 20 | 17 | OFF |
| 古卷5 | 18 | 18 | Tie |
| 古卷7 | 7 | 17 | ON |
| 平均 | **15.0** | **18.0** | ON3 / OFF1 / Tie2 |

Δ **+3.0 /case**（+0.30 相对提升 on 20）。

## 10. Per-Story Results
| Story | OFF 均 | ON 均 | Δ |
|---|---|---|---|
| 剑断长夜 | 13.5 | 19.0 | **+5.5**（早期断剑/内鬼伏笔被检索→ON 明显更稳） |
| 雾港迷案 | 19.0 | 17.5 | **−1.5**（谜题近期线索即够；ON 或带入额外设定） |
| 古卷残页 | 12.5 | 17.5 | **+5.0**（印记/守卫主线 case 更贴设定） |

## 11. Per-dimension Results（ON−OFF 均值 /2）
| H1 Hist | H2 CharState | H3 Timeline | H4 World | N1 Context | N2 Char | N3 Long-term | N4 Foreshadow | W1 Novelty | W2 Overall |
|---|---|---|---|---|---|---|---|---|---|
| +0.33 | +0.33 | 0.00 | +0.33 | 0.00 | +0.33 | **+0.67** | **+0.67** | 0.00 | +0.33 |

**Memory 真正有帮助的维度**：Long-term Coherence、Foreshadowing Compatibility（最强），其次 Historical /
Character-State / World-Rule / Overall。**没有帮助**：Timeline/Scene、Context Continuity、Novelty（两臂几乎无差；
这些维度不依赖长记忆）。

## 12. Memory-supported Improvement
- 剑断5：ON 正确延续断剑月圆发烫/认主不认人/玉简内鬼遗言（证据=检索到的 ch2/3/5 记忆）；OFF 凭空新增师父
  第二段临终教诲（硬事实冲突）。
- 剑断7：ON 保陆沉疑点、断剑月圆鸣/发烫准确、不抛外敌；OFF 编造"赵铁山救人 + 万剑阁秦无涯外敌"，与
  "内鬼=身边人"伏笔冲突。
- 古卷7：ON 沿守卫者/叛徒/镇物主线（归因偏弱——关键揭示在可见最近两章，Memory 仅补 ch1 初始悬疑）。

## 13. Memory-caused Error
- 雾港6（Memory 使结果变差）：ON 检索把额外设定带入上下文 → 新增未建立人物/关系（方远、孙奎"镇长侄女婿"等）
  = **Retrieval Noise / Context 带来的额外设定**（分类：Retrieval Noise + Context Competition），轻微拉低
  Character/一致性与 Novelty 的可信度。

## 14. Failure Cases（两臂共有、非 Memory 可救）
- 雾港4 / 古卷5：**旧证据（何伯守塔刀疤、老胡肩伤/脚印）没被检索进 ~989-token 预算** → 两臂都误把灯塔
  当"荒废"、都没接老胡伏笔。暴露检索容量上限（P5-B3-0/1 结论的端到端复现）。
- 雾港6 / 古卷7：OFF 单样本离题（外敌真相/敦煌国宝线）部分含生成随机性。

## 15. Limitations
- n=6、单样本、temp0.8、无 seed → 未做显著性检验，仅"小样本中观察到/方向性"。
- 评分自评、标签已知，非真盲评（建议以本 rubric 由第三方复核）。
- fixture 章节号显示存在标题行导致的 +1 偏移（实验工具层面瑕疵，Memory 内容仍限 ≤cutoff、正确）。
- 收益上限受 retrieved-memory ~989-token 容量限制（早期证据常被挤出预算）。

## 16. Final Conclusion
在"不要求复现原作剧情"的约束下：**Memory ON 改善了依赖早期设定的 Long-term Coherence 与 Foreshadowing
Compatibility（及其支撑的 Historical/World 一致性），但对 Timeline/Context/Novelty 无差别**。故回答为：
Memory 对**长期一致性**（历史/长程连贯/伏笔保护）有方向性改善；对**叙事衔接类连贯性**（承接断点、节奏、
新鲜度）无稳定影响，且在旧证据进不了检索预算或产生检索噪音时会失效/轻微负向。**初步、部分、非全面稳定的收益**。

---
### 附录：先前 7 维 exploratory 结果（已被上方 10 维 final 取代）
OFF 均 8.83/14 → ON 11.33/14（Δ+2.5）。方向一致：Foreshadowing/Historical/Worldbuilding 提升、Narrative 无差、
雾港 −1.0。新 10 维 Rubric 为 final project evaluation；两者不改结论方向，仅细化维度与降低单总分主观性。

产物：`backend/target/e2e/memory-e2e-ablation/`（context/generation/metadata/trace/evaluation.json/summary.json）。
复现：`MemoryE2eAblation`。本实验未修改任何生产/检索/生成代码。
