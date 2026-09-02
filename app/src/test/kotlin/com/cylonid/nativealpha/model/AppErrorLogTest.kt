package com.cylonid.nativealpha.model

import android.content.Context
import com.cylonid.nativealpha.model.AppErrorEntry
import com.cylonid.nativealpha.model.AppErrorLogRepository
import com.cylonid.nativealpha.util.AppStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * AppErrorLogRepository 单测：3 天滚动保留（写入时超龄清理）+
 * getRecent 统一过滤口径 + 只读语义（导出场景重复读取不清除）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppErrorLogTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetStore() = runBlocking {
        AppStorage.writeString(context, AppStorage.KEY_APP_ERRORS, "[]")
    }

    @Test
    fun append_prunesEntriesOlderThan3Days() = runBlocking {
        val aged = AppErrorEntry(
            time = System.currentTimeMillis() - 4L * 24 * 60 * 60 * 1000,
            message = "old"
        )
        AppErrorLogRepository.append(context, aged)

        assertTrue("超龄（>3 天）记录写入即清理", AppErrorLogRepository.getAll(context).isEmpty())
    }

    @Test
    fun getRecent_returnsFull3DayWindow_andRepeatReadNeverClears() = runBlocking {
        val now = System.currentTimeMillis()
        AppErrorLogRepository.append(context, AppErrorEntry(time = now - 2L * 24 * 60 * 60 * 1000, message = "within"))
        AppErrorLogRepository.append(context, AppErrorEntry(time = now, message = "today"))

        val first = AppErrorLogRepository.getRecent(context)
        assertEquals("3 天内记录全部保留", 2, first.size)
        // 只读语义：再次读取（导出场景）仍是完整 3 天窗口，不清除
        val second = AppErrorLogRepository.getRecent(context)
        assertEquals("重复读取（导出）不清除记录", first.size, second.size)
    }
}
