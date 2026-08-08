package com.mobile.standalone.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 周改动漏测看板：本地 git diff 只读扫描。
 * allowed-roots 非空时，仓库路径必须落在这些根目录下。
 */
@Data
@ConfigurationProperties(prefix = "change-review")
public class ChangeReviewProperties {
    /** 是否启用接口 */
    private boolean enabled = true;
    /**
     * 允许扫描的本地根目录（绝对路径）。
     * 为空时：允许用户家目录下的绝对路径（仍禁止 .. 与非 git 仓）。
     */
    private List<String> allowedRoots = new ArrayList<>();
    /** 是否允许在 diff 前执行 git fetch（默认关，避免改远程跟踪状态以外的副作用；仍只读工作区） */
    private boolean allowFetch = false;
    /** 单次 git 命令超时秒数 */
    private int timeoutSec = 90;
}
