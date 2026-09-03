package com.inkforge.planning;

/**
 * StoryPlan 生命周期（规划层数据，与 Story Memory 的 FactStatus 无关）。
 * DRAFT → CONFIRMED → IN_PROGRESS → COMPLETED / ABANDONED。
 */
public enum PlanStatus {
    DRAFT,
    CONFIRMED,
    IN_PROGRESS,
    COMPLETED,
    ABANDONED
}
