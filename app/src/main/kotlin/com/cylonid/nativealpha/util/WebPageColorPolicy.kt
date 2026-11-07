package com.cylonid.nativealpha.util

/**
 * WebView 页面主题色判定策略（纯函数，2026-11-07 全量重构——判定层从注入
 * JS 下沉到 Kotlin，可 JVM 单测穷举；JS 侧只负责原始信号采集）。
 *
 * 判定三级（语义对齐 Chromium blink `Document::ThemeColor` + 壳应用增强）：
 *  - **S1**：站点 `color-scheme` computed 值明确为 dark/light 时，取树序
 *    第一条「media 匹配且 content 有效」的 theme-color（HTML 规范语义，
 *    first-match，Chromium document.cc 同构；系统 matchMedia 不参与——
 *    Discourse 类站点深浅与系统无关，v2.4.12/2.4.13 两轮白栏根因）。
 *  - **S2**：S1 无信号时按页面背景亮度（0.299/0.587/0.114，与宿主
 *    [ThemeUtils.isLightColor] 同口径）着色：
 *    - 极暗(<0.3)/极亮(>0.7)：直接按背景上栏（向 Chrome 用户体验对齐的
 *      **增强**——Chromium 自身无背景 fallback；linux.do 登录深色页无
 *      theme-color meta 实锤形态，v2.4.14 theme_signal color=none 实锤）；
 *    - 0.3~0.7 中间带：仅在存在对应声明条目（darkVal/lightVal）时跟随。
 *  - **S3**：透明背景/无任何信号 → null（不干预，回落应用主题色）。
 *
 * 无 media 的 meta（fallback）优先于 S2——站点显式给单条声明时信声明。
 */
internal object WebPageColorPolicy {

    /** JS 侧上报的单条 theme-color 原文（content/media 均为原始属性值，可空） */
    data class MetaEntry(val content: String?, val media: String?)

    /**
     * 页面颜色信号快照（JS 采集层原样上报，本对象负责解析）。
     * @param colorScheme `getComputedStyle(html).colorScheme` 原文
     * @param htmlBg / bodyBg computed backgroundColor 原文（透明即原文）
     */
    data class ColorSignals(
        val metas: List<MetaEntry>,
        val colorScheme: String?,
        val htmlBg: String?,
        val bodyBg: String?,
        /** 深层容器背景（body 子级→孙级第一个非透明，html/body 全透明时的最后信号） */
        val deepBg: String? = null
    )

    /** 需要着色的 theme-color meta 归一化结果（树序） */
    private data class ValidMeta(val hex: String, val hasDark: Boolean, val hasLight: Boolean)

    /**
     * 判定入口：信号快照 → 上栏 ARGB（0xFFRRGGBB），null = 不干预。
     */
    fun resolve(signals: ColorSignals): Int? {
        val scheme = actualScheme(signals.colorScheme)
        var mediaHit: String? = null
        var fallback: String? = null
        var darkVal: String? = null
        var lightVal: String? = null
        for (meta in signals.metas) {
            val hex = normalizeHex(meta.content) ?: continue
            val media = meta.media
            val hasDark = media != null && MEDIA_DARK.containsMatchIn(media)
            val hasLight = media != null && MEDIA_LIGHT.containsMatchIn(media)
            if (hasDark && darkVal == null) darkVal = hex
            if (hasLight && lightVal == null) lightVal = hex
            if (media.isNullOrEmpty()) {
                if (fallback == null) fallback = hex
            } else if (mediaHit == null) {
                // first-match（树序第一条命中即定，对齐 Chromium，禁后命中覆盖）
                if (scheme == "dark" && hasDark && !hasLight) mediaHit = hex
                else if (scheme == "light" && hasLight && !hasDark) mediaHit = hex
            }
        }
        (mediaHit ?: fallback)?.let { return parseHexColor(it) ?: it.argbOrNull() }
        // S2：背景亮度兜底（html→body 双读，透明放弃）
        val lum = opaqueBackgroundColor(signals) ?: return null
        return when {
            lum < 0.3 -> darkVal?.argbOrNull() ?: opaqueBgHex(signals)?.argbOrNull()
            lum < 0.45 -> darkVal?.argbOrNull()
            lum > 0.7 -> lightVal?.argbOrNull() ?: opaqueBgHex(signals)?.argbOrNull()
            lum >= 0.55 -> lightVal?.argbOrNull()
            else -> null
        }
    }

    /** 首个不透明背景色的 #rrggbb 归一化（极暗/极亮直接上栏用） */
    private fun opaqueBgHex(signals: ColorSignals): String? {
        for (col in bgChain(signals)) {
            val m = RGBA.find(col ?: continue) ?: continue
            val alpha = m.groupValues.getOrNull(4)?.toFloatOrNull() ?: 1f
            if (alpha >= 0.99f) return normalizeHex(col)
        }
        return null
    }

    /** 背景链（视觉从内到外：深层容器→body→html——最内层背景最贴近页面渲染） */
    private fun bgChain(signals: ColorSignals): List<String> =
        listOf(signals.deepBg, signals.bodyBg, signals.htmlBg).filterNotNull()

    /** 页面背景亮度（首个不透明背景，深层容器→body→html）；全透明返回 null */
    fun opaqueBackgroundColor(signals: ColorSignals): Double? {
        for (col in bgChain(signals)) {
            val m = RGBA.find(col ?: continue) ?: continue
            val alpha = m.groupValues.getOrNull(4)?.toFloatOrNull() ?: 1f
            if (alpha >= 0.99f) {
                val lum = (0.299 * m.groupValues[1].toInt() +
                    0.587 * m.groupValues[2].toInt() +
                    0.114 * m.groupValues[3].toInt()) / 255.0
                return lum
            }
        }
        return null
    }

    /**
     * `getComputedStyle().colorScheme` → "dark"/"light"/null。
     * 明确单词才算信号；`normal`/二义 `light dark` 返回 null（回落 S2/S3）。
     */
    fun actualScheme(raw: String?): String? {
        val cs = raw?.trim() ?: return null
        val hasDark = WORD_DARK.containsMatchIn(cs)
        val hasLight = WORD_LIGHT.containsMatchIn(cs)
        return when {
            hasDark && !hasLight -> "dark"
            hasLight && !hasDark -> "light"
            else -> null
        }
    }

    /**
     * meta content / 背景色原文 → #rrggbb（小写）。支持 3/6 位十六进制
     * （可省 #）与 rgb()/rgba()（rgba alpha<1 视为无效——栏色必须不透明）。
     * 其余（named color/渐变/非法）返回 null。
     */
    fun normalizeHex(raw: String?): String? {
        if (raw == null) return null
        val v = raw.trim()
        RGBA.find(v)?.let { m ->
            val alpha = m.groupValues.getOrNull(4)?.toFloatOrNull() ?: 1f
            if (alpha < 0.99f) return null
            return rgbToHex(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        val hex = v.removePrefix("#")
        return when (hex.length) {
            6 -> hex.takeIf { it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
                ?.lowercase()?.let { "#$it" }
            3 -> if (hex.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' })
                "#" + hex.lowercase().map { "$it$it" }.joinToString("")
            else null
            else -> null
        }
    }

    /** #rrggbb → ARGB；非法（长度/字符）返回 null */
    fun parseHexColor(raw: String?): Int? {
        val v = raw?.trim()?.removePrefix("#") ?: return null
        val hex = when (v.length) {
            6 -> v
            3 -> "${v[0]}${v[0]}${v[1]}${v[1]}${v[2]}${v[2]}"
            else -> return null
        }
        val rgb = hex.toLongOrNull(16) ?: return null
        return (0xFF000000 or rgb).toInt()
    }

    private fun rgbToHex(r: Int, g: Int, b: Int): String =
        "#" + listOf(r, g, b).joinToString("") {
            ("0" + it.coerceIn(0, 255).toString(16)).takeLast(2)
        }

    private fun String.argbOrNull(): Int? = parseHexColor(this)

    // (?![\w-]) 负向前瞻：畸形 media（如 dark-alike）不得被子串误命中
    // （Chromium MediaQueryMatcher 对非法 media 一律不匹配）
    private val MEDIA_DARK = Regex("prefers-color-scheme\\s*:\\s*dark(?![\\w-])", RegexOption.IGNORE_CASE)
    private val MEDIA_LIGHT = Regex("prefers-color-scheme\\s*:\\s*light(?![\\w-])", RegexOption.IGNORE_CASE)
    private val WORD_DARK = Regex("(^|[^a-z-])dark([^a-z-]|$)", RegexOption.IGNORE_CASE)
    private val WORD_LIGHT = Regex("(^|[^a-z-])light([^a-z-]|$)", RegexOption.IGNORE_CASE)
    private val RGBA = Regex(
        "rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*(?:,\\s*([\\d.]+)\\s*)?\\)",
        RegexOption.IGNORE_CASE
    )
}
