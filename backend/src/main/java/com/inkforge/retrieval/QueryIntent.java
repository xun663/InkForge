package com.inkforge.retrieval;

/**
 * P5-B1 检索意图（最小 6 类，不引入更多维度）。用于：不同意图生成不同倾向的检索表达。
 */
public enum QueryIntent {
    RECENT_PLOT,      // 当前剧情 / 最近发生的事情
    CHARACTER,        // 某个角色自身状态、经历、属性
    RELATIONSHIP,     // 角色之间关系、关系变化、冲突历史
    WORLDBUILDING,    // 世界观、修炼体系、组织规则、地理、设定
    FORESHADOWING,    // 长期伏笔、异常现象、尚未解释的线索
    HISTORICAL_EVENT  // 跨章节关键事件、过去经历、事件演化
}
