package com.inkforge.retrieval;

import com.inkforge.common.TokenCounter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test-only simulation of retrieved-memory <b>Context Selection</b> under a token budget.
 * Used to A/B two selection strategies on the SAME ranked retrieval output (single variable):
 *
 * <ul>
 *   <li><b>A — 当前生产策略（fitTail 保尾）</b>：忠实复刻 {@code MemoryAwareContextBuilder}
 *       的 retrieved-memory 渲染 —— 把所有 result（高分在前）拼成一个 body，再对整个 body 做
 *       {@code fitTail}（超预算就反复从<b>头部</b>裁 10%，只保留<b>尾部</b>）。</li>
 *   <li><b>B — Rank-Preserving（保序截断）</b>：从 rank1 开始按序累加 chunk，下一个放不下就
 *       break（不删前面已加入的高排名证据）；仅当单个 rank1 chunk 超预算时才复用 fitTail 兜底。</li>
 * </ul>
 *
 * <p>纯诊断用，不改任何生产类。行文/标记与生产 {@code renderRetrievedMemory} 逐字一致
 * （"· 第N章 · [TYPE] text"），fitTail 算法与生产逐行一致（含省略标记）。生产代码：
 * {@code MemoryAwareContextBuilder#fitTail} 与 {@code #renderRetrievedMemory}。
 */
public final class RetrievalSelectionSim {

    private RetrievalSelectionSim() {
    }

    public static final String OMITTED_MARK = "（内容过长，已按上下文预算省略）";
    public static final String SECTION_HEADER = "【检索到的相关记忆】";

    private static final Pattern CHAPTER_MARK = Pattern.compile("· 第(\\d+)章 · \\[");

    /** 单个 result 的生产渲染片段（含前导换行），与 renderRetrievedMemory 逐字一致。 */
    public static String unit(RetrievalResult r) {
        return "\n· 第" + (r.chapterOrdinal() + 1) + "章 · [" + r.memoryType() + "] "
                + (r.text() == null ? "" : r.text());
    }

    /** 生产 body：header 之后第一个 result 无前导换行，其余保留前导换行。 */
    public static String joinBody(List<RetrievalResult> results) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (RetrievalResult r : results) {
            String u = unit(r);
            sb.append(first ? u.substring(1) : u);
            first = false;
        }
        return sb.toString();
    }

    /** 逐字复刻生产 {@code MemoryAwareContextBuilder#fitTail}：超预算从头部裁、保留尾部。 */
    public static String fitTail(String content, int tokenBudget, TokenCounter tc) {
        String tail = content;
        while (tc.count(OMITTED_MARK + tail) > tokenBudget && !tail.isEmpty()) {
            tail = tail.substring(Math.max(1, tail.length() / 10));
        }
        if (tc.count(OMITTED_MARK + tail) > tokenBudget) {
            return "";
        }
        return tail.equals(content) ? content : OMITTED_MARK + tail;
    }

    /** 策略 A（当前生产）：body = 全量拼接后 fitTail。返回保留的 body 文本（不含 header）。 */
    public static String selectTail(List<RetrievalResult> results, int bodyTokenBudget, TokenCounter tc) {
        return fitTail(joinBody(results), bodyTokenBudget, tc);
    }

    /**
     * 策略 B（Rank-Preserving）：按 rank 序累加，下一个放不下就 break；rank1 即使超预算也保留
     * （交由 fitTail 兜底裁到预算内）。返回保留的 body 文本（不含 header）。
     */
    public static String selectRankPreserving(List<RetrievalResult> results, int bodyTokenBudget, TokenCounter tc) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        List<String> pieces = new ArrayList<>();
        boolean first = true;
        for (RetrievalResult r : results) {
            String u = unit(r);
            pieces.add(first ? u.substring(1) : u);
            first = false;
        }
        StringBuilder acc = new StringBuilder(pieces.get(0)); // rank1 总是先加入（保序证据优先）
        for (int i = 1; i < pieces.size(); i++) {
            String cand = acc + pieces.get(i);
            if (tc.count(cand) <= bodyTokenBudget) {
                acc = new StringBuilder(cand);
            } else {
                break; // 放不下 → 停，不删前面已加入的高排名证据
            }
        }
        return fitTail(acc.toString(), bodyTokenBudget, tc); // 仅单个超长 chunk 时兜底裁到预算内
    }

    /** 某 gold 章节是否出现在最终 Context 文本中（按其单元标记判存）。 */
    public static boolean chapterPresent(String finalSectionText, int chapterNo) {
        return finalSectionText.contains("· 第" + chapterNo + "章 · [");
    }

    /** 从最终 Context 文本解析出出现的章节号（去重、升序）。 */
    public static Set<Integer> chaptersPresent(String finalSectionText) {
        Set<Integer> chapters = new LinkedHashSet<>();
        if (finalSectionText == null) {
            return chapters;
        }
        Matcher m = CHAPTER_MARK.matcher(finalSectionText);
        while (m.find()) {
            chapters.add(Integer.parseInt(m.group(1)));
        }
        return chapters;
    }
}
