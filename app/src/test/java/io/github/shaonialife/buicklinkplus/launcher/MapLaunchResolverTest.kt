package io.github.shaonialife.buicklinkplus.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLaunchResolverTest {
    /** 检查 Hist 后对齐空格的任务明细解析 */
    @Test fun alignedHistoryKeepsDetailedLifecycleAndIgnoresOldSummary() {
        val dump =
            """
            Display #9 (activities from top to bottom):
              topResumedActivity=ActivityRecord{a u0 com.kugou.android.auto/.MediaActivity t57}
              * Task{b #57 type=standard U=0}
                * Hist  #0: ActivityRecord{a u0 com.kugou.android.auto/.MediaActivity t57}
                  mState=RESUMED mVisible=true mReportedDrawn=true
              mLastPausedActivity: ActivityRecord{c u0 com.kugou.android.auto/.SplashActivity t56}
            """.trimIndent()
        for (spacing in listOf(" ", "  ", "\t")) {
            val states =
                MapLaunchResolver.taskStates(dump.replace("Hist  #", "Hist$spacing#"), "", 9, detailedOnly = true)
            assertEquals(1, states.size)
            assertEquals(57, states.single().taskId)
            assertTrue(states.single().ready)
            assertTrue(states.single().lifecycleKnown)
        }
    }

    /** 恢复且已绘制的任务缺少可见字段时，继续检查新输入帧 */
    @Test fun resumedDrawnMapWithMissingVisibilityCanReachFrameVerification() {
        val dump =
            """
            Display #129:
              * Hist #0: ActivityRecord{a u0 com.baidu.BaiduMap/com.baidu.baidumaps.MapsActivity t7474}
                mState=RESUMED mReportedDrawn=true
            """.trimIndent()
        val state = MapLaunchResolver.taskStates(dump, "", 129).single()
        assertNull(state.visible)
        assertTrue(state.ready)
        assertFalse(MapLaunchResolver.taskStates(dump.replace("mReportedDrawn=true", "mReportedDrawn=false"), "", 129).single().ready)
        assertFalse(MapLaunchResolver.taskStates(dump.replace("RESUMED", "STOPPED"), "", 129).single().ready)
    }

    /**
     * 请求可见不能掩盖实际不可见，缺少绘制证据也不能直接进入提交
     */
    @Test fun actualVisibilityTakesPriorityOverRequestedVisibility() {
        val dump =
            """
            Display #129:
              * Hist #0: ActivityRecord{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity t7473}
                mState=RESUMED mVisibleRequested=true mVisible=false mReportedDrawn=true
            """.trimIndent()
        val state = MapLaunchResolver.taskStates(dump, "", 129).single()
        assertEquals(false, state.visible)
        assertFalse(state.ready)
        assertFalse(MapLaunchResolver.taskStates(dump.replace("mReportedDrawn=true", ""), "", 129).single().ready)
    }

    /**
     * 可见字段缺失时仍须由同任务同屏窗口提供绘制证据，其他窗口不能补齐
     */
    @Test fun missingVisibilityStillRequiresMatchingDrawnWindow() {
        val dump =
            """
            Display #129:
              * Hist #0: ActivityRecord{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity t7473}
                mState=RESUMED
            """.trimIndent()
        val window =
            """
            Window #0 Window{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity}:
              mDisplayId=129
              mActivityRecord=ActivityRecord{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity t7473}
              mDrawState=HAS_DRAWN
            """.trimIndent()
        assertTrue(MapLaunchResolver.taskStates(dump, window, 129).single().ready)
        assertFalse(MapLaunchResolver.taskStates(dump, window.replace("mDisplayId=129", "mDisplayId=0"), 129).single().ready)
        assertFalse(MapLaunchResolver.taskStates(dump, window.replace("t7473", "t7474"), 129).single().ready)
    }

    /**
     * 厂商省略恢复摘要时仍可通过目标屏详细状态识别前台，历史任务不能冒充
     */
    @Test fun detailedResumedStateWorksWithoutSummary() {
        val dump =
            """
            Display #70:
              * Hist #0: ActivityRecord{a u0 com.kugou.android/.Main t31}
                mState=RESUMED mVisibleRequested=true mReportedDrawn=true
            Display #71:
              * Hist #0: ActivityRecord{b u0 other.app/.Main t32}
                mState=RESUMED mVisibleRequested=true
            """.trimIndent()
        assertEquals("com.kugou.android.Main", MapLaunchResolver.activePage(dump, 70, "com.kugou.android"))
        assertEquals("com.kugou.android/com.kugou.android.Main", MapLaunchResolver.activeComponent(dump, 70))
        assertNull(MapLaunchResolver.activePage(dump, 71, "com.kugou.android"))
        assertNull(MapLaunchResolver.activePage(dump.replace("mState=RESUMED", "mState=STOPPED"), 70, "com.kugou.android"))
        assertNull(MapLaunchResolver.activePage(dump.replace("mVisibleRequested=true", "mVisibleRequested=false"), 70, "com.kugou.android"))
    }

    /** 命令接受和任务前台分别检查，成功前缀不能盖过后续错误 */
    @Test fun startingPrefixDoesNotHideCommandFailure() {
        assertTrue(MapLaunchResolver.commandAccepted(true, "Starting: Intent {}\nStatus: ok"))
        assertTrue(MapLaunchResolver.commandAccepted(true, "Warning: Activity not started, task brought to front"))
        assertFalse(MapLaunchResolver.commandAccepted(false, "Starting: Intent {}"))
        assertFalse(MapLaunchResolver.commandAccepted(true, "Starting: Intent {}\nError: Activity not started"))
        assertFalse(MapLaunchResolver.commandAccepted(true, "Starting: Intent {}\nPermission Denial: startActivity"))
    }

    /**
     * 启动确认必须匹配同屏的任务、恢复、可见和绘制状态
     */
    @Test fun taskReadinessRequiresAllTargetEvidence() {
        val dump =
            """
            Display #7 (activities from top to bottom):
              * Hist #0: ActivityRecord{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity t22}
                mState=RESUMED mVisibleRequested=true mVisible=true mReportedDrawn=true
              mResumedActivity: ActivityRecord{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity t22}
            Display #8 (activities from top to bottom):
              * Hist #0: ActivityRecord{b u0 other.app/.Main t23}
                mState=RESUMED mVisibleRequested=true mReportedDrawn=true
            """.trimIndent()
        val state = MapLaunchResolver.taskStates(dump, "", 7, MapLaunchResolver.AMAP).single()
        assertEquals(22, state.taskId)
        assertTrue(state.ready)
        assertFalse(MapLaunchResolver.taskStates(dump.replace("mReportedDrawn=true", "mReportedDrawn=false"), "", 7).single().ready)
        assertTrue(MapLaunchResolver.taskStates(dump, "", 8, MapLaunchResolver.AMAP).isEmpty())
    }

    /**
     * 不能拿其他应用窗口的绘制字段补齐地图记录
     */
    @Test fun anotherActivityCannotSupplyMissingMapFields() {
        val dump =
            """
            Display: mDisplayId=7
              * Hist #0: ActivityRecord{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity t22}
              * Hist #0: ActivityRecord{b u0 other.app/.Main t23}
                mState=RESUMED mVisibleRequested=true mReportedDrawn=true
            """.trimIndent()
        val state = MapLaunchResolver.taskStates(dump, "", 7, MapLaunchResolver.AMAP).single()
        assertNull(state.visible)
        assertNull(state.drawn)
        assertFalse(state.ready)
        assertFalse(state.obscuredBy(MapLaunchResolver.taskStates(dump, "", 7)))
    }

    /**
     * 窗口证据必须同时匹配任务、显示屏和组件，手机上的已绘制窗口无效
     */
    @Test fun windowEvidenceIsBoundToDisplayAndTask() {
        val dump =
            """
            Display #7:
              * Hist #0: ActivityRecord{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity t22}
                mState=RESUMED mVisibleRequested=true
            """.trimIndent()
        val window =
            """
            Window #0 Window{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity}:
              mDisplayId=7
              mActivityRecord=ActivityRecord{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity t22}
              mDrawState=HAS_DRAWN
            """.trimIndent()
        assertTrue(MapLaunchResolver.taskStates(dump, window, 7).single().ready)
        assertNull(MapLaunchResolver.taskStates(dump, window.replace("mDisplayId=7", "mDisplayId=0"), 7).single().drawn)
        assertNull(MapLaunchResolver.taskStates(dump, window.replace("t22", "t23"), 7).single().drawn)
    }

    /** 缺少活动详情时保留历史任务身份，避免重复启动 */
    @Test fun summaryOnlyTaskRemainsKnownButNotReady() {
        val dump =
            """
            Display #7:
              mResumedActivity: ActivityRecord{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity t22}
            """.trimIndent()
        val state = MapLaunchResolver.taskStates(dump, "", 7).single()
        assertTrue(state.resumed)
        assertFalse(state.ready)
        assertTrue(MapLaunchResolver.hasDisplay(dump, 7))
        assertFalse(MapLaunchResolver.hasDisplay(dump, 70))
    }

    /**
     * 明确不可见的地图仅能由同屏实际前台任务触发恢复
     */
    @Test fun taskFocusRequiresConfirmedObstruction() {
        val map = MapLaunchResolver.TaskState(7, 22, MapLaunchResolver.AMAP, "地图", false, false, true)
        val other = MapLaunchResolver.TaskState(7, 23, "other.app", "设置", true, true, true)
        assertTrue(map.obscuredBy(listOf(map, other)))
        assertTrue(map.copy(visible = null, drawn = false).obscuredBy(listOf(other.copy(visible = null))))
        assertTrue(map.copy(visible = null, drawn = null).obscuredBy(listOf(other)))
        assertTrue(map.obscuredBy(listOf(other.copy(drawn = null))))
        assertFalse(map.copy(visible = null, lifecycleKnown = false).obscuredBy(listOf(other)))
        assertFalse(map.obscuredBy(listOf(other.copy(resumed = false))))
        assertFalse(map.copy(visible = true).obscuredBy(listOf(other)))
        assertFalse(map.obscuredBy(listOf(other.copy(visible = false))))
        assertFalse(map.obscuredBy(listOf(other.copy(displayId = 0))))
        assertFalse(map.copy(resumed = true).obscuredBy(listOf(other)))
    }

    /**
     * 用户切回缺少生命周期详情的已有地图时恢复原任务，后台诊断仍不猜测状态
     */
    @Test
    fun explicitSelectionRestoresSummaryOnlyMap() {
        val amap = MapLaunchResolver.TaskState(13, 7637, MapLaunchResolver.AMAP, "车载地图", false, null, false, false)
        val baidu = MapLaunchResolver.TaskState(13, 7638, "com.baidu.BaiduMap", "百度地图", true, null, true)
        assertFalse(amap.obscuredBy(listOf(amap, baidu)))
        assertTrue(amap.obscuredBy(listOf(amap, baidu), userRequested = true))
        assertFalse(amap.obscuredBy(listOf(baidu.copy(displayId = 0)), userRequested = true))
        assertFalse(amap.obscuredBy(listOf(baidu.copy(resumed = false)), userRequested = true))
        assertFalse(amap.copy(resumed = true).obscuredBy(listOf(baidu), userRequested = true))
        assertFalse(amap.copy(visible = true).obscuredBy(listOf(baidu), userRequested = true))
    }

    /**
     * 同一活动同时存在摘要和详情时保留真实生命周期，不因绘制字段相同而退回未知
     */
    @Test
    fun detailedLifecycleWinsOverSummaryWithWindowEvidence() {
        val dump =
            """
            Display #13:
              mLastPausedActivity: ActivityRecord{a u0 com.autonavi.minimap/.MapActivity t7637}
              * Hist #0: ActivityRecord{a u0 com.autonavi.minimap/.MapActivity t7637}
                mState=STOPPED
              mResumedActivity: ActivityRecord{b u0 com.baidu.BaiduMap/.Main t7638}
            """.trimIndent()
        val windows =
            """
            Window #0 Window{a u0 com.autonavi.minimap/.MapActivity}:
              mDisplayId=13
              mActivityRecord=ActivityRecord{a u0 com.autonavi.minimap/.MapActivity t7637}
              mDrawState=HAS_DRAWN
            """.trimIndent()
        val states = MapLaunchResolver.taskStates(dump, windows, 13)
        val map = states.single { it.taskId == 7637 }
        assertTrue(map.lifecycleKnown)
        assertFalse(map.resumed)
        assertTrue(map.obscuredBy(states))
    }

    @Test
    fun rejectedOrWrongDisplayCarLaunchFallsBackToNormalOnlyOnce() {
        val calls = mutableListOf<String>()
        assertTrue(
            launchPreferredMap(listOf("车载引导", "车载地图"), "普通入口") { candidate, car ->
                calls.add(candidate)
                !car
            },
        )
        assertEquals(listOf("车载引导", "车载地图", "普通入口"), calls)
        assertTrue(launchPreferredMap(emptyList<String>(), "普通入口") { _, car -> !car })
        assertFalse(launchPreferredMap(listOf("未导出入口"), "普通入口") { _, _ -> false })
    }

    @Test
    fun targetDisplayAndResumedPageAreBothRequired() {
        val dump =
            """
            Display: mDisplayId=0
              mResumedActivity: ActivityRecord{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity t1}
            Display: mDisplayId=7
              Hist #0: ActivityRecord{b u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity t2}
              mResumedActivity: ActivityRecord{c u0 com.autonavi.minimap/com.autonavi.map.activity.NewMapActivity t3}
            """.trimIndent()
        val page = MapLaunchResolver.activePage(dump, 7, MapLaunchResolver.AMAP)
        assertFalse(MapLaunchResolver.isCarPage(page))
        assertTrue(MapLaunchResolver.isCarPage(MapLaunchResolver.activePage(dump, 0, MapLaunchResolver.AMAP)))
        assertNull(MapLaunchResolver.activePage(dump, 8, MapLaunchResolver.AMAP))
    }

    /**
     * 车载模式失败和入口缺失时都不能自动启动普通手机页面
     */
    @Test
    fun carModeDoesNotSilentlyLaunchNormalMap() {
        val calls = mutableListOf<String>()
        assertFalse(
            launchPreferredMap(listOf("车载入口"), "普通入口", allowNormalFallback = false) { candidate, _ ->
                calls.add(candidate)
                false
            },
        )
        assertEquals(listOf("车载入口"), calls)
        assertFalse(
            launchPreferredMap(emptyList<String>(), "普通入口", allowNormalFallback = false) { candidate, _ ->
                calls.add(candidate)
                true
            },
        )
        assertEquals(listOf("车载入口"), calls)
    }

    /**
     * 新系统显示屏标题和缩写组件名应正确识别，同时隔离手机屏与历史页面
     */
    @Test
    fun numberedDisplaysAndRelativePagesAreRecognized() {
        val dump =
            """
            Display #0 (activities from top to bottom):
              topResumedActivity=ActivityRecord{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity t1}
            Display #59 (activities from top to bottom):
              Hist #0: ActivityRecord{b u0 com.autonavi.minimap/com.autonavi.map.activity.NewMapActivity t2}
              mResumedActivity: ActivityRecord{c u0 com.autonavi.minimap/.map.activity.UcarMapActivity t3}
            Display #60 (activities from top to bottom):
              mResumedActivity: ActivityRecord{d u0 other.app/.MainActivity t4}
            """.trimIndent()
        assertEquals("com.autonavi.minimap.map.activity.UcarMapActivity", MapLaunchResolver.activePage(dump, 59, MapLaunchResolver.AMAP))
        assertTrue(MapLaunchResolver.isCarPage(MapLaunchResolver.activePage(dump, 0, MapLaunchResolver.AMAP)))
        assertNull(MapLaunchResolver.activePage(dump, 60, MapLaunchResolver.AMAP))
        assertNull(MapLaunchResolver.activePage(dump, 58, MapLaunchResolver.AMAP))
    }
}
