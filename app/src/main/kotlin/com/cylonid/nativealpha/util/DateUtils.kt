package com.cylonid.nativealpha.util

import android.annotation.SuppressLint
import java.text.SimpleDateFormat
import java.util.Calendar

import java.util.Locale

object DateUtils {

    @JvmStatic
    fun getTimeInSeconds(): Long {
        return System.currentTimeMillis() / 1000
    }

    @JvmStatic
    @SuppressLint("SimpleDateFormat")
    fun getHourMinFormat(): SimpleDateFormat {
        return SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    @JvmStatic
    @SuppressLint("SimpleDateFormat")
    fun getDayHourMinuteSecondsFormat(): SimpleDateFormat {
        return SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.getDefault())
    }

    @JvmStatic
    fun convertStringToCalendar(str: String?): Calendar? {
        if (str.isNullOrBlank()) return null
        return try {
            val parsedDate = getHourMinFormat().parse(str)
            Calendar.getInstance().also { it.time = parsedDate!! }
        } catch (e: Exception) {
            null
        }
    }

    @JvmStatic
    fun isInInterval(low: Calendar, time: Calendar, high: Calendar): Boolean {
        // Bring timestamp with day_current + HH:mm => day_unixZero + HH:mm by parsing it again...
        val middle = Calendar.getInstance()
        val parsed = getHourMinFormat().parse(
            getHourMinFormat().format(time.time)
        ) ?: return false
        middle.time = parsed

        // CASE: If the end of our timespan is after midnight, add one day to the end date to get a proper span.
        if (high.before(low)) {
            high.add(Calendar.DATE, 1)
            if (middle.before(low)) {
                middle.add(Calendar.DATE, 1)
            }
        }
        return middle.after(low) && middle.before(high)
    }

    @JvmStatic
    fun isOlderThanDays(timestamp: Long, days: Int, targetTime: Long = System.currentTimeMillis()): Boolean {
        val daysInMillis = days * 24L * 60 * 60 * 1000
        return (targetTime - timestamp) > daysInMillis
    }

    /** epoch ms → 可读时间（yyyy-MM-dd HH:mm） */
    @JvmStatic
    @SuppressLint("SimpleDateFormat")
    fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0) return "—"
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(timestamp))
        } catch (e: Exception) {
            "—"
        }
    }

    /** 文件名用紧凑日期（yyyyMMdd）——导出文件名统一格式（唯一实现处） */
    @JvmStatic
    @SuppressLint("SimpleDateFormat")
    fun compactDate(): String =
        SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(java.util.Date())

    /** 文件名用紧凑时间戳（yyyyMMdd_HHmmss）——备份文件名统一格式（唯一实现处） */
    @JvmStatic
    @SuppressLint("SimpleDateFormat")
    fun compactTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(java.util.Date())
}