package com.inkforge.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 蛊真人全量记忆重放路径。默认与 {@code GzrFullMemoryCostRun} / 本地实验资产对齐；
 * 可用环境变量覆盖。空字符串表示调用方必须在请求里显式提供路径。
 */
@ConfigurationProperties(prefix = "inkforge.gzr")
public record GzrImportProperties(String sourceTxt, String outcomesDir) {

    public GzrImportProperties {
        if (sourceTxt == null) {
            sourceTxt = "";
        }
        if (outcomesDir == null) {
            outcomesDir = "";
        }
    }
}
