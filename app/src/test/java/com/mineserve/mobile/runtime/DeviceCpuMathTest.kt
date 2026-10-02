package com.mineserve.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceCpuMathTest {

    // ── parse ──────────────────────────────────────────────────

    @Test
    fun parsesCpuLineWithTenColumns() {
        // 真实 /proc/stat 首行形态（user nice system idle iowait irq softirq steal guest guest_nice）
        val sample = DeviceCpuMath.parse("cpu  100 10 50 800 20 5 5 0 0 0")
        // 前八列之和：100+10+50+800+20+5+5+0 = 990
        assertEquals(990L, sample!!.total)
        assertEquals(800L, sample.idle)
    }

    @Test
    fun excludesGuestColumnsFromTotal() {
        // guest/guest_nice 已被内核计入 user/nice（kernel/sched/cputime.c 的
        // account_guest_time()），把它们再加一遍会让分母偏大、读数偏低。
        // total 必须只算前八列：200+20+100+1600+40+10+10+0 = 1980
        val sample = DeviceCpuMath.parse("cpu 200 20 100 1600 40 10 10 0 150 15")
        assertEquals(1980L, sample!!.total)
        assertEquals(1600L, sample.idle)
    }

    @Test
    fun guestColumnsWouldHaveInflatedOldTotal() {
        // 回归护栏：若退回「十列全和」，total 会变成 2145，本用例即失败。
        val sample = DeviceCpuMath.parse("cpu 200 20 100 1600 40 10 10 0 150 15")
        assertEquals("不得把 guest/guest_nice 计入总和", 1980L, sample!!.total)
    }

    @Test
    fun parsesMinimalFourColumns() {
        val sample = DeviceCpuMath.parse("cpu 1 2 3 4")
        assertEquals(10L, sample!!.total)
        assertEquals(4L, sample.idle)
    }

    @Test
    fun ignoresOtherCpuNLine() {
        // /proc/stat 里 cpu0/cpu1 等行以 "cpu0" 开头，不能当作整机行；
        // startsWith("cpu") 会命中，因此校验首个 token 必须恰好是 "cpu"。
        assertNull(DeviceCpuMath.parse("cpu0 1 2 3 4"))
        assertNull(DeviceCpuMath.parse("cpu1 1 2 3 4"))
    }

    @Test
    fun rejectsGarbageAndShortLines() {
        assertNull(DeviceCpuMath.parse(""))
        assertNull(DeviceCpuMath.parse("intr 12345"))
        assertNull(DeviceCpuMath.parse("cpu 1 2 3"))
        assertNull(DeviceCpuMath.parse("cpu a b c d"))
    }

    // ── percent ────────────────────────────────────────────────

    @Test
    fun fullyIdleIsZero() {
        val prev = DeviceCpuMath.Sample(total = 1000L, idle = 800L)
        val now = DeviceCpuMath.Sample(total = 1100L, idle = 900L)
        assertEquals(0, DeviceCpuMath.percent(now, prev))
    }

    @Test
    fun fullyBusyIs100() {
        val prev = DeviceCpuMath.Sample(total = 1000L, idle = 800L)
        val now = DeviceCpuMath.Sample(total = 1100L, idle = 800L)
        assertEquals(100, DeviceCpuMath.percent(now, prev))
    }

    @Test
    fun halfBusyIsHalf() {
        val prev = DeviceCpuMath.Sample(total = 1000L, idle = 800L)
        val now = DeviceCpuMath.Sample(total = 1200L, idle = 900L)
        assertEquals(50, DeviceCpuMath.percent(now, prev))
    }

    @Test
    fun iowaitCountsAsBusyNotIdle() {
        // idle 只取第 4 列：iowait 增量属于「非空闲」，不应被算成 idle。
        val prev = DeviceCpuMath.Sample(total = 1000L, idle = 500L)
        val now = DeviceCpuMath.Sample(total = 1100L, idle = 500L)
        assertEquals(100, DeviceCpuMath.percent(now, prev))
    }

    @Test
    fun counterRollbackReturnsNull() {
        val prev = DeviceCpuMath.Sample(total = 1200L, idle = 900L)
        val now = DeviceCpuMath.Sample(total = 1000L, idle = 800L)
        assertNull(DeviceCpuMath.percent(now, prev))
    }

    @Test
    fun tooShortWindowReturnsNull() {
        val prev = DeviceCpuMath.Sample(total = 1000L, idle = 800L)
        assertNull(DeviceCpuMath.percent(prev, prev))
    }

    @Test
    fun idleDeltaLargerThanTotalIsClampedToZero() {
        // idle 增量异常偏大（计数器重置），夹取后不产生负使用率。
        val prev = DeviceCpuMath.Sample(total = 1000L, idle = 800L)
        val now = DeviceCpuMath.Sample(total = 1010L, idle = 900L)
        assertEquals(0, DeviceCpuMath.percent(now, prev))
    }

    @Test
    fun neverExceedsHundredForWholeDevice() {
        // 整机口径：所有核心同时满载也最多 100%，绝不因多核而放大。
        val prev = DeviceCpuMath.Sample(total = 8_000L, idle = 1_000L)
        val now = DeviceCpuMath.Sample(total = 8_800L, idle = 1_000L)
        assertEquals(100, DeviceCpuMath.percent(now, prev))
    }

    // ── cpufreq 频率比方案 ──────────────────────────────────────

    @Test
    fun freqAllCoresAtMinimumIsZero() {
        // 所有核都降到最低频 = 空载。
        val cores = listOf(
            DeviceCpuMath.CoreFreq(curKhz = 300_000, minKhz = 300_000, maxKhz = 2_000_000),
            DeviceCpuMath.CoreFreq(curKhz = 300_000, minKhz = 300_000, maxKhz = 2_000_000)
        )
        assertEquals(0, DeviceCpuMath.percentFromFreqs(cores))
    }

    @Test
    fun freqAllCoresAtMaximumIsHundred() {
        val cores = listOf(
            DeviceCpuMath.CoreFreq(curKhz = 2_000_000, minKhz = 300_000, maxKhz = 2_000_000),
            DeviceCpuMath.CoreFreq(curKhz = 2_000_000, minKhz = 300_000, maxKhz = 2_000_000)
        )
        assertEquals(100, DeviceCpuMath.percentFromFreqs(cores))
    }

    @Test
    fun freqMidpointIsFifty() {
        // (1_150_000 - 300_000) / (2_000_000 - 300_000) = 0.5
        val cores = listOf(
            DeviceCpuMath.CoreFreq(curKhz = 1_150_000, minKhz = 300_000, maxKhz = 2_000_000)
        )
        assertEquals(50, DeviceCpuMath.percentFromFreqs(cores))
    }

    @Test
    fun freqAveragesAcrossCores() {
        // 一个核满载、一个核空载 -> 平均 50%
        val cores = listOf(
            DeviceCpuMath.CoreFreq(curKhz = 2_000_000, minKhz = 300_000, maxKhz = 2_000_000),
            DeviceCpuMath.CoreFreq(curKhz = 300_000, minKhz = 300_000, maxKhz = 2_000_000)
        )
        assertEquals(50, DeviceCpuMath.percentFromFreqs(cores))
    }

    @Test
    fun freqFixedFrequencyCoreUsesFullOrIdle() {
        // max == min：固定频率核，按是否达到最高频记 100% / 0%。
        assertEquals(
            100,
            DeviceCpuMath.percentFromFreqs(
                listOf(DeviceCpuMath.CoreFreq(curKhz = 1_000_000, minKhz = 1_000_000, maxKhz = 1_000_000))
            )
        )
    }

    @Test
    fun freqSkipsInvalidCoresAndUsesRemaining() {
        // 非法核（max<=0、cur<=0、min>max）被跳过，只用合法核计算。
        val cores = listOf(
            DeviceCpuMath.CoreFreq(curKhz = 0, minKhz = 0, maxKhz = 0),
            DeviceCpuMath.CoreFreq(curKhz = 2_000_000, minKhz = 300_000, maxKhz = 2_000_000),
            DeviceCpuMath.CoreFreq(curKhz = -1, minKhz = 300_000, maxKhz = 2_000_000),
            DeviceCpuMath.CoreFreq(curKhz = 500_000, minKhz = 900_000, maxKhz = 800_000)
        )
        assertEquals(100, DeviceCpuMath.percentFromFreqs(cores))
    }

    @Test
    fun freqWithNoValidCoreReturnsNull() {
        assertNull(DeviceCpuMath.percentFromFreqs(emptyList()))
        assertNull(DeviceCpuMath.percentFromFreqs(listOf(DeviceCpuMath.CoreFreq(0, 0, 0))))
    }

    @Test
    fun freqClampsOutOfRangeCurrent() {
        // cur 高于 max 或低于 min（读数是脏数据）时夹取，不产生越界百分比。
        assertEquals(
            100,
            DeviceCpuMath.percentFromFreqs(
                listOf(DeviceCpuMath.CoreFreq(curKhz = 9_999_999, minKhz = 300_000, maxKhz = 2_000_000))
            )
        )
        assertEquals(
            0,
            DeviceCpuMath.percentFromFreqs(
                listOf(DeviceCpuMath.CoreFreq(curKhz = 1, minKhz = 300_000, maxKhz = 2_000_000))
            )
        )
    }
}
