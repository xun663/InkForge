package com.inkforge.memory.build;

/** 全量记忆构建 Job 的生命周期状态。状态转换由 MemoryBuildJob 校验。 */
public enum MemoryBuildStatus {
    PENDING,          // 已创建，尚未开始处理
    RUNNING,          // 正在按章节顺序构建
    PAUSED,           // 协作式暂停：当前章节完成后停
    COMPLETED,        // 全部章节成功
    PARTIAL_FAILED,   // 有章节失败（可 retry-failed）
    CANCELLED         // 用户取消，不能自动恢复
}
