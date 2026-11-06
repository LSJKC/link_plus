package io.github.shaonialife.buicklinkplus.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 在 Android 上检查任务解析 */
class MapLaunchResolverAndroidTest {
    /** 检查任务明细中的可见和绘制状态 */
    @Test fun actualAlignedHistoryKeepsDetailedTask() {
        val dump =
            """
            Display #9 (activities from top to bottom):
              topResumedActivity=ActivityRecord{a u0 com.kugou.android.auto/.MediaActivity t57}
              * Task{b #57 type=standard U=0}
                * Hist  #0: ActivityRecord{a u0 com.kugou.android.auto/.MediaActivity t57}
                  mState=RESUMED mVisible=true mReportedDrawn=true
              mLastPausedActivity: ActivityRecord{c u0 com.kugou.android.auto/.SplashActivity t56}
            Display #10:
              ResumedActivity: ActivityRecord{a u0 com.kugou.android.auto/.MediaActivity t57}
              Display: mDisplayId=9 (organized)
                * Task{b #57 type=standard U=0}
                  * ActivityRecord{a u0 com.kugou.android.auto/.MediaActivity t57}
            """.trimIndent()
        val state = MapLaunchResolver.taskStates(dump, "", 9, detailedOnly = true).single()
        assertEquals(57, state.taskId)
        assertTrue(state.ready)
        assertTrue(AppLaunchRecovery.hasTask(dump, "com.kugou.android.auto", 9))
        assertEquals(AppLaunchRecovery.Move(9, 57, 57), AppLaunchRecovery.reusableTask(dump, "com.kugou.android.auto", 9, -1))
        assertEquals(null, MapLaunchResolver.activeComponent(dump, 10))
    }

    /** 旧屏的暂停摘要不能盖过另一屏恢复的音乐任务 */
    @Test
    fun widgetPageUsesActualDisplayAndIgnoresPausedSummary() {
        val pkg = "com.kugou.android.auto"
        val dump =
            """
            Display #131:
              mLastPausedActivity: ActivityRecord{old u0 $pkg/.ui.activity.main.MediaActivity t14831}
            Display #132:
              * Task{current #14832 type=standard U=0}
                * Hist #0: ActivityRecord{active u0 $pkg/.ui.activity.main.MediaActivity t14832}
                  mState=RESUMED mVisibleRequested=true
            """.trimIndent()
        assertFalse(AppLaunchRecovery.hasTask(dump, pkg, 131))
        assertEquals(AppLaunchRecovery.Move(132, 14832, 14832), AppLaunchRecovery.reusableTask(dump, pkg, 131, 132))
        assertTrue(AppLaunchRecovery.hasTask(dump, pkg, 132))
    }

    /** 缺少可见字段时保留未知值，交给输入帧检查 */
    @Test fun missingVisibilityDoesNotRejectDrawnResumedMap() {
        val dump =
            """
            Display #129:
              * Hist #0: ActivityRecord{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity t7473}
                mState=RESUMED mReportedDrawn=true
            """.trimIndent()
        val state = MapLaunchResolver.taskStates(dump, "", 129).single()
        assertEquals(null, state.visible)
        assertTrue(state.ready)
        assertFalse(MapLaunchResolver.taskStates(dump.replace("RESUMED", "STOPPED"), "", 129).single().ready)
    }

    /** 检查任务记录中右大括号的解析 */
    @Test
    fun androidRegexAcceptsClosedActivityRecord() {
        val dump =
            """
            Display #125:
              * Hist #0: ActivityRecord{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity t22}
                mState=RESUMED mVisibleRequested=true mReportedDrawn=true
            """.trimIndent()

        val state = MapLaunchResolver.taskStates(dump, "", 125, MapLaunchResolver.AMAP).single()

        assertEquals(22, state.taskId)
        assertTrue(state.ready)
    }

    /** 检查百度和高德的任务记录不跨括号或显示屏合并 */
    @Test
    fun adjacentMapRecordsRemainBoundToTheirOwnTaskAndDisplay() {
        val dump =
            """
            Display #125:
              * Hist #0: ActivityRecord{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity t22}
                mState=RESUMED mVisibleRequested=true
              * Hist #1: ActivityRecord{b u0 com.baidu.BaiduMap/.Main t23}
                mState=STOPPED mVisibleRequested=false mReportedDrawn=true
            """.trimIndent()
        val windows =
            """
            Window #0 Window{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity}:
              mDisplayId=0
              mActivityRecord=ActivityRecord{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity t22}
              mDrawState=HAS_DRAWN
            """.trimIndent()

        val states = MapLaunchResolver.taskStates(dump, windows, 125)

        assertEquals(listOf(22, 23), states.map { it.taskId })
        assertFalse(states.first().ready)
        assertFalse(states.last().ready)
        assertTrue(MapLaunchResolver.taskStates(dump, windows.replace("mDisplayId=0", "mDisplayId=125"), 125).first().ready)
    }
}
