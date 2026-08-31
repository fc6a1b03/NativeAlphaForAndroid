package com.cylonid.nativealpha.matrix

/**
 * 容量闸门判定（P4 D7，纯函数核心）。
 *
 * 平台事实：WebView 渲染内存位于共享渲染进程，App 堆探针不可见；预算
 * 必须基于系统级信号（availMem/低内存阈值）+ 设备端预计算的每窗边际
 * 成本（PSS 采样差值，加载前预估、onPageFinished 回采校准）。
 *
 * 判定纪律（严格 fail-open）：**拦 = 有确切证据，放 = 一切其他情况**——
 * 预算缺失/边际未知一律放行（交给 onTrimMemory 守卫与崩溃恢复兜底），
 * 计算故障不得误杀功能（用户明确要求）。唯一拦截依据：预算与边际都
 * 明确可算且确实不够。
 */
internal object MatrixCapacityGate {

    /** 预算快照（availMem 等系统读数在采样时刻固化） */
    data class Budget(
        /**
         * 可用渲染预算字节（availMem − 系统低内存阈值）；≤0 视为非法输入
         * 放行（fail-open：极低内存场景交给 onTrimMemory 守卫处置，闸门
         * 不在此区间做设备不足判定，避免计算歧义锁死功能）
         */
        val totalBytes: Long,
        /** 单窗边际成本字节（设备端预计算 EMA；≤0 表示尚无实测值） */
        val perCellBytes: Long
    )

    sealed interface Decision {
        /** 放行：创建 WebView 并加载 */
        data object Allow : Decision

        /** 容量受限：本格不开 WebView，进「容量受限」态（点击重试，预算动态） */
        data object LimitCell : Decision

        /** 设备不足：预算连首窗边际都盖不住，如实劝退（matrix_device_underpowered） */
        data object DeviceUnsupported : Decision
    }

    /**
     * 加载前拦截比对（错峰窗口内执行，不阻塞 UI——窗格先显示加载态）。
     *
     * @param activeCellCount 当前已活跃（已创建 WebView）的窗格数
     */
    fun decide(activeCellCount: Int, budget: Budget?): Decision = when {
        budget == null || budget.perCellBytes <= 0 || budget.totalBytes <= 0 -> Decision.Allow
        budget.perCellBytes * (activeCellCount + 1) <= budget.totalBytes -> Decision.Allow
        activeCellCount == 0 -> Decision.DeviceUnsupported
        else -> Decision.LimitCell
    }

    /**
     * 边际成本校准（onPageFinished 回采；EMA 平滑系统采样抖动）。
     * 首次实测直接采纳，其后按 3:1 加权——渐进逼近，粗值不破坏 fail-open。
     *
     * @param previous 已校准边际（≤0 表示无历史）
     * @param measured 本次实测差值（≤0 视为无效采样，原样返回）
     */
    fun calibratePerCell(previous: Long, measured: Long): Long = when {
        measured <= 0 -> previous
        previous <= 0 -> measured
        else -> (previous * 3 + measured) / 4
    }

    // ===== 设备呈现档位（动态窗口数上限） =====

    /** 低档设备：官方低内存标志或 MemTotal < 3GB——矩阵上限收缩到 3 窗 */
    const val LOW_MAX_WINDOWS = 3

    /** 基准档：主流设备矩阵上限 4 窗（原始 D2 档位） */
    const val BASELINE_MAX_WINDOWS = 4

    /** 扩展档：MemTotal ≥ 6GB 设备放开到 6 窗（2×3 网格，逐窗闸门继续兜底） */
    const val EXTENDED_MAX_WINDOWS = 6

    fun decideMaxWindows(totalRamBytes: Long, isLowRamDevice: Boolean): Int = when {
        // 探测无效：保守取基准档（不上浮不收缩，呈现与历史一致）
        totalRamBytes <= 0L -> BASELINE_MAX_WINDOWS
        // 官方低内存标志或 MemTotal < 3.5GB：低端收缩 3 窗（3=上2下1，仍有完整布局）
        isLowRamDevice || totalRamBytes < LOW_RAM_THRESHOLD_BYTES -> LOW_MAX_WINDOWS
        // 旗舰：MemTotal ≥ 7GB 才放开 6 窗（实机与模拟器有差异，刻意留余量——
        // 标称 6~7GB 机型落基准档，不做压线判定）
        totalRamBytes >= HIGH_RAM_THRESHOLD_BYTES -> EXTENDED_MAX_WINDOWS
        else -> BASELINE_MAX_WINDOWS
    }

    // 阈值刻度说明：totalMem 是内核报告值，比标称 RAM 低约 7-10%
    // （4GB 机型实测 ~3.7GB、8GB 机型 ~7.3GB）。阈值刻意留余量、不贴
    // 标称边界：LOW 3.5GB（标称 4GB 的低配机型多数落入收缩档，用户定调
    // 低端少开）、HIGH 7GB（标称 8GB+ 旗舰才放开 6 档，标称 6-7GB 机型
    // 留在基准档——档位宁可保守，呈现档位错误比少一档更伤信任）。
    val LOW_RAM_THRESHOLD_BYTES = 3584L * 1024 * 1024
    val HIGH_RAM_THRESHOLD_BYTES = 7L * 1024 * 1024 * 1024
}
