package com.cylonid.nativealpha.util

import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.DomainRouter.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 域名路由穷举（默认浏览器接管核心判定）：
 * host 精确/子域匹配、多命中取更具体 baseUrl、未命中瞬态浏览、
 * 非法输入拒绝、baseUrl 坏数据跳过不影响其余。
 */
class DomainRouterTest {

    private fun app(baseUrl: String, id: Int) = WebApp(baseUrl, id)

    /** 精确 host 命中 → OpenInWebApp，保留完整路径（点文章开文章） */
    @Test
    fun openInWebApp_onExactHost_keepsFullPath() {
        val apps = listOf(app("https://linux.do/", 0))
        val action = DomainRouter.route("https://linux.do/t/topic/123?u=x", apps)
        assertTrue(action is Action.OpenInWebApp)
        assertEquals(0, (action as Action.OpenInWebApp).webApp.ID)
        assertEquals("https://linux.do/t/topic/123?u=x", action.url)
    }

    /** baseUrl 带路径/尾斜杠不影响匹配（host 级路由） */
    @Test
    fun match_ignoresBaseUrlPath() {
        val apps = listOf(app("https://www.zhihu.com/some/path", 3))
        val action = DomainRouter.route("https://www.zhihu.com/question/9", apps)
        assertTrue(action is Action.OpenInWebApp)
        assertEquals(3, (action as Action.OpenInWebApp).webApp.ID)
    }

    /** 子域命中：baseUrl=zhihu.com 吃下 www.zhihu.com */
    @Test
    fun match_subdomainOfBaseUrl() {
        val apps = listOf(app("https://zhihu.com", 1))
        val action = DomainRouter.route("https://www.zhihu.com/question/9", apps)
        assertTrue(action is Action.OpenInWebApp)
        assertEquals(1, (action as Action.OpenInWebApp).webApp.ID)
    }

    /** 反向不成立：baseUrl=www.zhihu.com 不吃下根域 zhihu.com */
    @Test
    fun noMatch_parentDomain() {
        val apps = listOf(app("https://www.zhihu.com", 1))
        val action = DomainRouter.route("https://zhihu.com/question/9", apps)
        assertTrue(action is Action.TransientBrowse)
    }

    /** 后缀伪装拒绝：evil-zhihu.com 不是 zhihu.com 的子域（须整段点分） */
    @Test
    fun noMatch_suffixImpersonation() {
        val apps = listOf(app("https://zhihu.com", 1))
        val action = DomainRouter.route("https://evil-zhihu.com/", apps)
        assertTrue(action is Action.TransientBrowse)
    }

    /** 多命中取 baseUrlHost 更长者（www.zhihu.com 优先于 zhihu.com） */
    @Test
    fun match_mostSpecificBaseWins() {
        val apps = listOf(app("https://zhihu.com", 1), app("https://www.zhihu.com", 2))
        val action = DomainRouter.route("https://www.zhihu.com/question/9", apps)
        assertTrue(action is Action.OpenInWebApp)
        assertEquals(2, (action as Action.OpenInWebApp).webApp.ID)
    }

    /** 未命中 → 瞬态浏览（浏览器兜底角色） */
    @Test
    fun transientBrowse_onUnmatchedHost() {
        val apps = listOf(app("https://linux.do/", 0))
        val action = DomainRouter.route("https://example.org/page", apps)
        assertTrue(action is Action.TransientBrowse)
        assertEquals("https://example.org/page", (action as Action.TransientBrowse).url)
    }

    /** 大小写归一：URL host 大写也命中 */
    @Test
    fun match_hostCaseInsensitive() {
        val apps = listOf(app("https://LINUX.DO/", 0))
        val action = DomainRouter.route("https://LINUX.DO/t/1", apps)
        assertTrue(action is Action.OpenInWebApp)
    }

    /** 非 http/https 或无 host → Invalid */
    @Test
    fun invalid_onNonHttpOrHostless() {
        val apps = listOf(app("https://linux.do/", 0))
        assertEquals(Action.Invalid, DomainRouter.route("ftp://linux.do/x", apps))
        assertEquals(Action.Invalid, DomainRouter.route("about:blank", apps))
        assertEquals(Action.Invalid, DomainRouter.route(null, apps))
        assertEquals(Action.Invalid, DomainRouter.route("", apps))
        assertEquals(Action.Invalid, DomainRouter.route("not a url", apps))
    }

    /** baseUrl 坏数据（用户手填）跳过该应用，不影响其余匹配 */
    @Test
    fun skipBrokenBaseUrl_othersStillMatch() {
        val apps = listOf(app("::::bad", 1), app("https://linux.do/", 2))
        val action = DomainRouter.route("https://linux.do/t/1", apps)
        assertTrue(action is Action.OpenInWebApp)
        assertEquals(2, (action as Action.OpenInWebApp).webApp.ID)
    }

    /** matchHost 直查：无候选返回 null */
    @Test
    fun matchHost_nullOnEmptyCandidates() {
        assertNull(DomainRouter.matchHost("linux.do", emptyList()))
    }

    /** 端口不参与匹配（host 级路由） */
    @Test
    fun match_portAgnostic() {
        val apps = listOf(app("http://192.168.1.10:8096", 5))
        val action = DomainRouter.route("http://192.168.1.10:8096/web/index.html", apps)
        assertTrue(action is Action.OpenInWebApp)
        assertEquals(5, (action as Action.OpenInWebApp).webApp.ID)
    }
}
