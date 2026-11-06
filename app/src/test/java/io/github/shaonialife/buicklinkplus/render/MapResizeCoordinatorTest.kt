package io.github.shaonialife.buicklinkplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证相同目标合并、拖动落定覆盖旧目标和跨会话隔离
 */
class MapResizeCoordinatorTest {
    @Test fun repeatedTargetKeepsLaunchRequestValid() {
        val coordinator = MapResizeCoordinator()
        val launch = coordinator.request(1200, 600)
        assertEquals(launch, coordinator.request(1200, 600))
        assertTrue(coordinator.owns(launch))
    }

    @Test fun newestSplitInvalidatesQueuedFullScreen() {
        val coordinator = MapResizeCoordinator()
        val full = coordinator.request(1200, 600)
        val split = coordinator.request(600, 600)
        assertFalse(coordinator.owns(full))
        assertTrue(coordinator.owns(split))
    }

    @Test fun sameDimensionsInNewSessionCannotReviveOldRequest() {
        val coordinator = MapResizeCoordinator()
        val old = coordinator.request(600, 600)
        coordinator.invalidate()
        val fresh = coordinator.request(600, 600)
        assertNotEquals(old, fresh)
        assertFalse(coordinator.owns(old))
    }

    /**
     * 像素尺寸相同但密度不同，也必须重新协调系统显示和缓冲
     */
    @Test fun densityChangeInvalidatesOldRequest() {
        val coordinator = MapResizeCoordinator()
        val old = coordinator.request(1200, 600, 200)
        val fresh = coordinator.request(1200, 600, 240)
        assertFalse(coordinator.owns(old))
        assertTrue(coordinator.owns(fresh))
    }

    /**
     * 快速来回切换不能令第一次全屏请求重新获得提交权
     */
    @Test fun returningToSameSizeDoesNotReviveOldTransaction() {
        val coordinator = MapResizeCoordinator()
        val first = coordinator.request(1200, 600)
        coordinator.request(600, 600)
        val latest = coordinator.request(1200, 600)
        assertFalse(coordinator.owns(first))
        assertTrue(coordinator.owns(latest))
        assertNotEquals(first.generation, latest.generation)
    }

    /**
     * 系统拒绝和缓冲确认失败都不能发布尺寸就绪
     */
    @Test fun partialFailureIsNotReady() {
        val request = MapResizeCoordinator().request(1200, 600)
        assertFalse(MapResizeCoordinator.Result(request, false, true).ready)
        assertFalse(MapResizeCoordinator.Result(request, true, false).ready)
        assertTrue(MapResizeCoordinator.Result(request, true, true).ready)
    }
}
