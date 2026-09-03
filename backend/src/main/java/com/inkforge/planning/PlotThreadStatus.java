package com.inkforge.planning;

/**
 * 剧情线索状态。当前生产写入方（PlotThreadMerger）只创建/刷新 OPEN；
 * RESOLVED/ABANDONED 为后续"线索收束"能力预留（v1 无写入方，见 docs）。
 */
public enum PlotThreadStatus {
    OPEN,
    RESOLVED,
    ABANDONED
}
