package com.cylonid.nativealpha.model

import com.google.gson.JsonObject

/**
 * 备份分区贡献者接口（C1：webevent 规则纳入备份）。
 *
 * 备份 schema 归 DataManager（model 层），但规则库住在 webevent 包的自持
 * DataStore——用接口倒置避免 model→webevent 反向依赖：WebeventRuntime.init
 * 时注入实现，导出/导入时若已装配则携带该分区，未装配（理论不可达，init
 * 先于任何 UI）则按无规则备份。
 */
interface WebeventBackup {

    /** 导出分区：{"rules":[...], "mutedSites":[...]}（Gson 契约，字段名即 schema） */
    fun exportJson(): JsonObject

    /** 导入分区：实现方自行容错（损坏分区降级为空，不阻断整体导入） */
    fun importJson(obj: JsonObject)
}
