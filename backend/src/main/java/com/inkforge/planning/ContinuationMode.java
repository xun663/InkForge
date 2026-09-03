package com.inkforge.planning;

/**
 * 续写模式（P6）：三种叙事策略。
 * <ul>
 *   <li>{@link #PLOT_CHOICE}：剧情选择 —— 分析当前故事，给出多个候选方向，由用户决定走向</li>
 *   <li>{@link #ENDING}：完结 —— 分析未解决剧情/伏笔/人物弧，制定分阶段收束方案</li>
 *   <li>{@link #EXPANSION}：拓展 —— 发现未开发的世界/人物/剧情线空间，开启新方向</li>
 * </ul>
 * 所有模式都先产出 StoryPlan（规划层），用户确认后才进入正式生成。
 */
public enum ContinuationMode {
    PLOT_CHOICE("剧情选择"),
    ENDING("完结"),
    EXPANSION("拓展");

    private final String label;

    ContinuationMode(String label) {
        this.label = label;
    }

    /** 中文名称，用于 Prompt 标记与前端文案。 */
    public String label() {
        return label;
    }

    /** Prompt 中的模式标记，MockLlmProvider 依赖它区分罐头输出，勿改文案。 */
    public String marker() {
        return "【规划模式：" + label + "】";
    }

    /** null/blank 抛 IllegalArgumentException（→400）；大小写不敏感。 */
    public static ContinuationMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("续写模式不能为空（可选 PLOT_CHOICE / ENDING / EXPANSION）");
        }
        try {
            return ContinuationMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "未知的续写模式: " + raw + "（可选 PLOT_CHOICE / ENDING / EXPANSION）");
        }
    }
}
