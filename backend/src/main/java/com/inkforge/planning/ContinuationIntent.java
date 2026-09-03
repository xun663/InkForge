package com.inkforge.planning;

/**
 * 一次续写请求的规划意图（P6）。
 *
 * <p>legacy（mode/planId 均空）：旧版直接续写，行为字节级不变。
 * 携带 planId：按已确认的 StoryPlan 生成；mode 可省略（默认取计划的模式）。
 */
public record ContinuationIntent(ContinuationMode mode, String planId, Integer stepIndex, String userInstruction) {

    public static ContinuationIntent legacy() {
        return new ContinuationIntent(null, null, null, null);
    }

    /** 旧版直连续写：无模式、无计划、无附加参数。 */
    public boolean isLegacy() {
        return mode == null && planId == null;
    }
}
