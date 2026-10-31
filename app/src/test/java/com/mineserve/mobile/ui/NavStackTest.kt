package com.mineserve.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 返回栈的序列化是「返回上一级」唯一依赖的持久化环节：
 * 进程被回收后重建，栈能原样还原才不会把用户甩回首页。
 */
class NavStackTest {

    @Test
    fun keepsPageOrderAndParameterlessEntriesRoundTrip() {
        val stack = listOf(
            NavEntry(SubPage.More),
            NavEntry(SubPage.Network),
            NavEntry(SubPage.EasyTierGuide)
        )

        val restored = stack.map { it.encode() }.mapNotNull { NavEntry.decode(it) }

        assertEquals(stack, restored)
        assertEquals(SubPage.More, restored.first().page)
        assertEquals(SubPage.EasyTierGuide, restored.last().page)
    }

    @Test
    fun restoresTheArgumentUsedToOpenAPage() {
        val entry = NavEntry(SubPage.FrpProfileEditor, "profile-42")

        assertEquals("FrpProfileEditor#profile-42", entry.encode())
        assertEquals(entry, NavEntry.decode(entry.encode()))
    }

    @Test
    fun argumentMayContainSeparatorCharacters() {
        // FRP 配置 id 是自由文本，万一含 '#'，只能按第一个分隔符切，不能丢内容
        val entry = NavEntry(SubPage.FrpProfileEditor, "a#b")

        assertEquals("a#b", NavEntry.decode(entry.encode())?.arg)
    }

    @Test
    fun samePageWithDifferentArgumentsGetsItsOwnStateKey() {
        // 同一个页面用不同参数打开时必须拿到不同的 state key，否则两层的滚动位置会互相污染
        val first = NavEntry(SubPage.EnvManager, "0")
        val second = NavEntry(SubPage.EnvManager, "1")

        assertEquals(first.page, second.page)
        assertEquals(false, first.key == second.key)
    }

    @Test
    fun onlyMoreCountsAsRootLevel() {
        // 「更多」挂在底栏最后一位，和 Tab 同级：不能因为它压了栈就隐藏底栏、加悬浮返回键
        assertEquals(true, NavEntry(SubPage.More).isRootLevel)
        assertEquals(false, NavEntry(SubPage.Network).isRootLevel)
        assertEquals(false, NavEntry(SubPage.EnvManager, "0").isRootLevel)
    }

    @Test
    fun dropsUnknownPagesInsteadOfCrashing() {
        // 旧版本残留 / 枚举被删时丢弃该层，而不是启动即崩
        assertNull(NavEntry.decode("SubPageThatNoLongerExists"))
        assertNull(NavEntry.decode(""))
    }
}
