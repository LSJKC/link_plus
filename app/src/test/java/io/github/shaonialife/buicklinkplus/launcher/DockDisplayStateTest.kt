package io.github.shaonialife.buicklinkplus.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证侧栏反映成功显示内容，并隔离失败、旧回调和断开会话
 */
class DockDisplayStateTest {
    /** 原生组件观察不能覆盖新点击、断开或已变化的前台快照 */
    @Test fun widgetObservationCannotOverrideNewerChoice() {
        val state = DockDisplayState()
        val observed = state.snapshot()
        val explicit = state.beginTarget("map/.Main")
        assertNull(state.beginObservedTarget(observed, "music/.Main"))
        assertEquals("map/.Main", state.pendingComponent())
        assertTrue(explicit != null && state.owns(explicit))
        state.disconnect()
        assertNull(state.beginObservedTarget(observed, "music/.Main"))
    }

    /** 当前且无竞争的官方组件前台，沿用应用启动检查流程 */
    @Test fun currentWidgetObservationCanStartOneVerifiedTransition() {
        val state = DockDisplayState()
        val observed = state.snapshot()
        val request = state.beginObservedTarget(observed, "music/.Main")
        assertTrue(request != null && state.owns(request))
        assertNull(state.beginObservedTarget(observed, "music/.Main"))
    }

    /**
     * 点击立即发布等待目标，真实显示内容继续保留上一个成功项
     */
    @Test fun pendingFeedbackDoesNotPretendDisplaySuccess() {
        val state = DockDisplayState()
        val shown = DockDisplayState.Content(DockDisplayState.Page.APP, "music/.Main")
        state.commit(state.begin(), shown)
        val request = requireNotNull(state.beginTarget("map/.Main"))
        assertEquals("map/.Main", state.pendingComponent())
        assertEquals(shown, state.content)
        state.fail(request)
        assertNull(state.pendingComponent())
        assertEquals(shown, state.content)
    }

    /**
     * 旧完成或旧失败不能清除新目标的等待反馈
     */
    @Test fun staleCallbacksCannotClearNewPendingTarget() {
        val state = DockDisplayState()
        val old = requireNotNull(state.beginTarget("old/.Main"))
        val latest = requireNotNull(state.beginTarget("new/.Main"))
        state.fail(old)
        assertFalse(state.commit(old, DockDisplayState.Content(DockDisplayState.Page.APP, "old/.Main")))
        assertEquals("new/.Main", state.pendingComponent())
        assertTrue(state.commit(latest, DockDisplayState.Content(DockDisplayState.Page.APP, "new/.Main")))
        assertNull(state.pendingComponent())
    }

    /**
     * 返回主页或断开须清除等待的应用组件，不能保留虚假高亮
     */
    @Test fun homeAndDisconnectClearPendingTarget() {
        val state = DockDisplayState()
        state.beginTarget("music/.Main")
        state.begin()
        assertNull(state.pendingComponent())
        state.beginTarget("map/.Main")
        state.disconnect()
        assertNull(state.pendingComponent())
        assertEquals(DockDisplayState.Page.DISCONNECTED, state.content.page)
    }

    /** 旧失败不能关闭后来请求的启动反馈 */
    @Test fun oldFailureKeepsLatestPendingFeedback() {
        val state = DockDisplayState()
        val old = requireNotNull(state.beginTarget("旧应用"))
        val latest = requireNotNull(state.beginTarget("新应用"))
        state.fail(old)
        assertTrue(state.hasPending)
        state.fail(latest)
        assertFalse(state.hasPending)
    }

    /**
     * 重复点击同一图标不能淘汰正在启动的凭据或提前提交选中状态
     */
    @Test fun repeatedTargetKeepsOriginalLaunchAlive() {
        val state = DockDisplayState()
        val request = requireNotNull(state.beginTarget("map/.Main"))
        repeat(5) { assertNull(state.beginTarget("map/.Main")) }
        assertTrue(state.owns(request))
        assertTrue(state.hasPending)
        assertEquals(DockDisplayState.Page.DISCONNECTED, state.content.page)
        assertTrue(state.commit(request, DockDisplayState.Content(DockDisplayState.Page.MAP, "map/.Main")))
    }

    /**
     * 改点另一应用或主页必须淘汰旧请求，同图标合并不能阻止真实切换
     */
    @Test fun anotherTargetAndHomeInvalidatePreviousTarget() {
        val state = DockDisplayState()
        val old = requireNotNull(state.beginTarget("map/.Main"))
        val latest = requireNotNull(state.beginTarget("music/.Main"))
        assertFalse(state.owns(old))
        assertTrue(state.owns(latest))
        state.begin()
        assertFalse(state.owns(latest))
        assertTrue(state.beginTarget("music/.Main") != null)
    }

    /**
     * 失败、成功和断开都须释放合并标记，重试同一图标不能被永久忽略
     */
    @Test fun completedOrDisconnectedTargetCanBeRetried() {
        val state = DockDisplayState()
        state.fail(requireNotNull(state.beginTarget("map/.Main")))
        val retry = requireNotNull(state.beginTarget("map/.Main"))
        assertTrue(state.commit(retry, DockDisplayState.Content(DockDisplayState.Page.MAP)))
        val next = requireNotNull(state.beginTarget("map/.Main"))
        state.disconnect()
        assertFalse(state.owns(next))
        assertTrue(state.beginTarget("map/.Main") != null)
    }

    /**
     * 点击及失败都不应抢先改变原来的选中项
     */
    @Test fun failedLaunchKeepsDisplayedApplication() {
        val state = DockDisplayState()
        val app = DockDisplayState.Content(DockDisplayState.Page.APP, "music/.Main")
        state.commit(state.begin(), app)
        val failed = state.begin()
        assertEquals(app, state.content)
        state.fail(failed)
        assertEquals(app, state.content)
        assertFalse(state.hasPending)
        assertFalse(state.commit(failed, DockDisplayState.Content(DockDisplayState.Page.MAP)))
    }

    /**
     * 旧应用的迟到确认不能覆盖后来成功的应用
     */
    @Test fun newerChoiceRejectsOldCallback() {
        val state = DockDisplayState()
        val old = state.begin()
        val latest = state.begin()
        val map = DockDisplayState.Content(DockDisplayState.Page.MAP, "map/.Main")
        assertTrue(state.commit(latest, map))
        assertFalse(state.commit(old, DockDisplayState.Content(DockDisplayState.Page.APP, "old/.Main")))
        state.fail(old)
        assertEquals(map, state.content)
    }

    /**
     * 同一组件在新会话出现也不能复活断开前的凭据
     */
    @Test fun disconnectedSessionRejectsOutstandingSelection() {
        val state = DockDisplayState()
        val old = state.begin()
        state.disconnect()
        val fresh = state.begin()
        assertFalse(state.commit(old, DockDisplayState.Content(DockDisplayState.Page.MAP)))
        assertTrue(state.commit(fresh, DockDisplayState.Content(DockDisplayState.Page.DESKTOP)))
    }

    /**
     * 启动期间的底层桌面恢复通知不能抢走请求，返回设置后应清除应用组件
     */
    @Test fun ownPageObservationRespectsPendingLaunch() {
        val state = DockDisplayState()
        val request = state.begin()
        assertFalse(state.observe(DockDisplayState.Content(DockDisplayState.Page.DESKTOP)))
        assertTrue(state.commit(request, DockDisplayState.Content(DockDisplayState.Page.APP, "music/.Main")))
        assertTrue(state.observe(DockDisplayState.Content(DockDisplayState.Page.SETTINGS)))
        assertEquals(null, state.content.component)
    }

    /**
     * 后台查询返回时，期间的新启动和断开必须使旧查询失效
     */
    @Test fun oldForegroundSnapshotCannotOverrideNewSelection() {
        val state = DockDisplayState()
        val old = state.snapshot()
        state.commit(state.begin(), DockDisplayState.Content(DockDisplayState.Page.MAP, "map/.Main"))
        assertFalse(state.observe(old, DockDisplayState.Content(DockDisplayState.Page.DESKTOP)))
        val beforeDisconnect = state.snapshot()
        state.disconnect()
        assertFalse(state.observe(beforeDisconnect, DockDisplayState.Content(DockDisplayState.Page.MAP)))
    }
}
