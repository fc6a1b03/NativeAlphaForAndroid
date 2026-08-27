package com.cylonid.nativealpha.helper

/**
 * 双击手势判定契约：LONGPRESS_JS 脚本构建 + 返回值语义解析 + 弹菜单决策。
 *
 * 为什么独立成对象：「JS 返回什么语义」与「Kotlin 如何消费语义」两端字符串若各自维护
 * 必然漂移且无法被单测捕获；收口此处后整个契约可 JVM 穷举单测（WebGestureHelperTest），
 * WebViewActivity 只负责 evaluateJavascript 编排（P3 手势拆分同落点）。
 */
object WebViewGestureHelper {

    /** JS 返回语义：真空白（弹小菜单） */
    const val RESULT_BLANK = "blank"

    /** JS 返回语义：文本输入点（弹小菜单——选择/粘贴是输入框高频操作） */
    const val RESULT_INPUT = "input"

    /** JS 返回语义：正文文本字符上（交还系统选中单词，不弹菜单） */
    const val RESULT_TEXT = "text"

    /** JS 返回语义：功能控件（按钮/链接等，交还网页，不弹菜单） */
    const val RESULT_INTERACTIVE = "interactive"

    /** JS 返回语义：媒体元素（图片/视频/画布，不弹菜单） */
    const val RESULT_MEDIA = "media"

    /**
     * 视为「文本输入」的 input type 白名单：命中则双击弹小菜单；缺省 type 按 text 处理。
     * 其余类型（checkbox/radio/button/submit/file/date 等）保持 interactive 归网页，
     * 白名单收紧而非放行未知类型——防止浏览器新增实验性 type 意外改变菜单行为。
     */
    val MENU_INPUT_TYPES = listOf(
        "text", "email", "password", "search", "url", "number", "tel"
    )

    /**
     * 构建图片长按检测 JS 模板（占位符与坐标换算同 [buildLongPressJs]）。
     * 返回长按点命中的 img 的绝对 URL（src/currentSrc），没命中返回 'null'。
     * **只匹配 img**——视频不做长按下载（全屏播放/页面视频均不误触）。
     */
    fun buildMediaLongPressJs(): String =
        "(function(){" +
            "var px=%1\$f,py=%2\$f;" +
            "var dpr=window.devicePixelRatio||1;" +
            "var innerW=window.innerWidth||document.documentElement.clientWidth;" +
            "var outerW=window.outerWidth||innerW;" +
            "var scale=dpr*outerW/innerW;" +
            "if(!(scale>0)||scale===1){scale=1;}" +
            "var x=px/scale,y=py/scale;" +
            "var e=document.elementFromPoint(x,y);" +
            "if(!e)return 'null';" +
            "var te=e;" +
            "while(te&&te!==document.body){" +
            "var tt=te.tagName?te.tagName.toLowerCase():'';" +
            "if(tt==='img'){var s=te.currentSrc||te.src;" +
            "if(s&&s.indexOf('data:')!==0)return s;" +
            "return 'null';}" +
            "te=te.parentElement;" +
            "}" +
            "return 'null';})()"

    /**
     * 解析 evaluateJavascript 回调值为语义类型。
     * evaluateJavascript 对 JS 字符串返回带引号 JSON 文本、对 undefined/null
     * 返回字面量 "null"——统一归一化为 blank（保守放行菜单）。
     */
    fun parseLongPressResult(raw: String?): String {
        val type = raw?.replace("\"", "").orEmpty()
        return if (type.isEmpty() || type == "null") RESULT_BLANK else type
    }

    /** 双击是否弹小菜单：真空白或文本输入点（input 语义优先于媒体命中） */
    fun shouldShowMenuOnDoubleTap(type: String): Boolean =
        type == RESULT_BLANK || type == RESULT_INPUT

    /**
     * 构建 LONGPRESS_JS 脚本模板（%1$f/%2$f 占位符由调用方 String.format 填坐标）。
     *
     * 检测链（自命中元素向 body 上溯，首中即返回）：
     * 1. textarea / 文本型 input / role=textbox / isContentEditable → 'input'
     * 2. 其余功能控件（button/a/select 及 checkbox 等 input 类型）与交互 role → 'interactive'
     * 3. img/canvas/svg/video/iframe 祖先 → 'media'
     * 4. caretRangeFromPoint + 字符矩形双重命中 → 'text'
     * 5. 兜底 'blank'
     *
     * 兼容 Taro/WebComponents：标准 DOM 探测命不中的自定义组件文字按 text 处理
     * （双击文字交还系统选中单词）；caretRangeFromPoint 返回「最近插入位置」所以
     * 空白处必须加矩形命中才能区分「点在字符上」vs「点在空白但邻近文本」。
     */
    fun buildLongPressJs(): String {
        // input type 判定链由 MENU_INPUT_TYPES 生成——脚本与常量永远同源
        val inputTypeChain = MENU_INPUT_TYPES.joinToString("||") { "ty==='$it'" }
        return "(function(){" +
            "var px=%1\$f,py=%2\$f;" +
            "var dpr=window.devicePixelRatio||1;" +
            "var innerW=window.innerWidth||document.documentElement.clientWidth;" +
            "var outerW=window.outerWidth||innerW;" +
            "var scale=dpr*outerW/innerW;" +
            "if(!(scale>0)||scale===1){scale=1;}" +
            "var x=px/scale,y=py/scale;" +
            "var e=document.elementFromPoint(x,y);" +
            "if(!e)return 'blank';" +
            "var tag=e.tagName?e.tagName.toLowerCase():'';" +
            "if(tag==='html'||tag==='body')return 'blank';" +
            // 交互元素检测：功能控件交还网页；文本输入语义交回 App 弹小菜单
            "var it=e;" +
            "while(it&&it!==document.body){" +
            "var t2=it.tagName?it.tagName.toLowerCase():'';" +
            "if(t2==='textarea')return 'input';" +
            "if(t2==='input'){var ty=(it.getAttribute?it.getAttribute('type'):'')||'text';" +
            "ty=(''+ty).toLowerCase();" +
            "if($inputTypeChain)return 'input';" +
            "return 'interactive';}" +
            "if(t2==='button'||t2==='a'||t2==='select'||t2==='option'||t2==='label'||t2==='form'" +
            "||t2==='details'||t2==='summary'||t2==='audio'||t2==='embed'||t2==='object')" +
            "return 'interactive';" +
            "var role=it.getAttribute?it.getAttribute('role'):'';" +
            "if(role==='textbox')return 'input';" +
            "if(role==='button'||role==='link'||role==='tab'||role==='checkbox'" +
            "||role==='radio'||role==='switch'||role==='menuitem'||role==='slider'" +
            "||role==='combobox'||role==='listbox'||role==='option'||role==='searchbox')" +
            "return 'interactive';" +
            "if(it.isContentEditable)return 'input';" +
            "var tb=it.getAttribute?it.getAttribute('tabindex'):null;" +
            "if(tb&&tb!=='-1')return 'interactive';" +
            "it=it.parentElement;" +
            "}" +
            "var te=e;" +
            "while(te&&te!==document.body){" +
            "var tt=te.tagName?te.tagName.toLowerCase():'';" +
            "if(tt==='img'||tt==='canvas'||tt==='svg'||tt==='video'||tt==='iframe')return 'media';" +
            "te=te.parentElement;" +
            "}" +
            "var range=null;" +
            "if(document.caretRangeFromPoint){range=document.caretRangeFromPoint(x,y);}" +
            "if(range&&range.startContainer){" +
            "var n=range.startContainer;" +
            "if(n.nodeType===3){" +
            "var len=n.length||0;" +
            "if(range.startOffset>0&&range.startOffset<len){" +
            "var full=document.createRange();" +
            "full.selectNodeContents(n);" +
            "var rect=full.getBoundingClientRect();" +
            "if(x>=rect.left&&x<=rect.right&&y>=rect.top&&y<=rect.bottom){" +
            "return 'text';" +
            "}" +
            "}" +
            "}" +
            "}" +
            "return 'blank';})()"
    }
}
