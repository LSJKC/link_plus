package io.github.shaonialife.buicklinkplus.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 使用按 Android 转储格式构造的任务样例 */
class AppLaunchRecoveryTest {
    private val pkg = "com.kugou.android"
    private val requested = "$pkg.app.logo_icon_9"
    private val launchOutput = "Status: ok\nActivity: $pkg/.app.MediaActivity"
    private val unlocked =
        """
        Display #0 (activities from top to bottom):
          * Task{a #14500 type=standard A=com.kugou.android U=0 visible=true}
            * Hist #0: ActivityRecord{b u0 com.kugou.android/.app.logo_icon_9 t14500}
              mState=RESUMED mVisibleRequested=true
            mResumedActivity: ActivityRecord{b u0 com.kugou.android/.app.logo_icon_9 t14500}
        Display #93 (activities from top to bottom):
        Display #94 (activities from top to bottom):
          * Task{c #14501 type=standard A=io.github.shaonialife.buicklinkplus U=0 visible=true}
            * Hist #0: ActivityRecord{d u0 io.github.shaonialife.buicklinkplus/.launcher.GridActivity t14501}
              mState=RESUMED mVisibleRequested=true
        """.trimIndent()

    /** 窗口摘要重复列出任务时，不制造重复归属 */
    @Test fun repeatedWindowTreeDoesNotDuplicateTaskOwnership() {
        val first = unlocked.substringBefore("Display #93").replace("Display #0", "Display #94").replace("Hist #", "Hist  #")
        val dump =
            first +
                """

                Display #93:
                  ResumedActivity: ActivityRecord{b u0 $pkg/.app.logo_icon_9 t14500}
                  Display: mDisplayId=94 (organized)
                      * Task{a #14500 type=standard A=$pkg U=0}
                        * ActivityRecord{b u0 $pkg/.app.logo_icon_9 t14500}
                  Display: mDisplayId=93 (organized)
                """.trimIndent()
        assertEquals(AppLaunchRecovery.Move(94, 14500, 14500), AppLaunchRecovery.reusableTask(dump, pkg, 93, 94))
        assertEquals(AppLaunchRecovery.Move(94, 14500, 14500), candidate(dump))
        assertFalse(AppLaunchRecovery.hasTask(dump, pkg, 93))
        assertNull(MapLaunchResolver.activeComponent(dump, 93))
        assertNull(MapLaunchResolver.activePage(dump, 93, pkg))
    }

    /**
     * 手机同包历史任务不妨碍复用另一应用屏上的真实任务
     */
    @Test
    fun reusesActualVirtualTaskWithoutSelectingPhoneHistory() {
        val dump = unlocked.replace("io.github.shaonialife.buicklinkplus/.launcher.GridActivity", "$pkg/.app.MediaActivity")
        assertEquals(AppLaunchRecovery.Move(94, 14501, 14501), AppLaunchRecovery.reusableTask(dump, pkg, 93, 94))
        assertNull(AppLaunchRecovery.reusableTask(unlocked, pkg, 93, 94))
    }

    /** 模拟 QQ 启动页在原生屏、超采样屏为空，缺少生命周期字段时也不重复新建 */
    @Test
    fun qqMusicStarterOnNativeDisplayCanBeFocusedWithoutNewTask() {
        val qq = "com.tencent.qqmusic"
        val dump =
            """
            Display #0:
              * Task{a #7791 type=standard A=$qq U=0}
                * Hist #0: ActivityRecord{b u0 $qq/.activity.AppStarterActivity t7791}
            Display #88:
              * Task{c #7827 type=standard A=10300:$qq U=0}
                * Hist #0: ActivityRecord{d u0 $qq/.activity.AppStarterActivity t7827}
            Display #89:
            """.trimIndent()
        assertEquals(AppLaunchRecovery.Move(88, 7827, 7827), AppLaunchRecovery.reusableTask(dump, qq, 89, 88))
    }

    /**
     * 同包普通页面和车载页面并存时，只复用用户当前选择的页面任务
     */
    @Test
    fun qqMusicPageChoiceKeepsTasksSeparate() {
        val qq = PhoneCarPages.QQ_MUSIC
        val dump =
            """
            Display #88:
              * Task{a #51 type=standard A=$qq U=0}
                * Hist #0: ActivityRecord{b u0 $qq/.activity.AppStarterActivity t51}
                  mState=RESUMED mVisible=true
            Display #89:
              * Task{c #52 type=standard A=$qq U=0}
                * Hist #0: ActivityRecord{d u0 $qq/.business.carwith.ui.CarWithPlayerActivity t52}
                  mState=STOPPED mVisible=false
            """.trimIndent()
        assertEquals(
            AppLaunchRecovery.Move(89, 52, 52),
            AppLaunchRecovery.reusableTask(dump, qq, 88, 89, PhoneCarPages::isQqCarPage),
        )
        assertEquals(
            AppLaunchRecovery.Move(88, 51, 51),
            AppLaunchRecovery.reusableTask(dump, qq, 88, 89) { !PhoneCarPages.isQqCarPage(it) },
        )
    }

    /**
     * 目标屏与另一应用屏都有实例时，优先用户要求的目标屏
     */
    @Test
    fun preferredVirtualDisplayWinsOverOtherInstance() {
        val dump =
            unlocked
                .replace("Display #0", "Display #93")
                .replace("Display #93 (activities from top to bottom):\nDisplay #94", "Display #94")
                .replace("io.github.shaonialife.buicklinkplus/.launcher.GridActivity", "$pkg/.app.MediaActivity")
        assertEquals(AppLaunchRecovery.Move(93, 14500, 14500), AppLaunchRecovery.reusableTask(dump, pkg, 93, 94))
    }

    /**
     * 启动别名已跳到真实页面时仍复用原任务，不能重新拉起启动页
     */
    @Test
    fun realPageAndStoppedLifecycleStillIdentifyExistingTask() {
        val dump =
            unlocked
                .substringBefore("Display #93")
                .replace("Display #0", "Display #94")
                .replace("logo_icon_9", "MediaActivity")
                .replace("RESUMED", "STOPPED")
                .replace("mResumedActivity:", "mLastPausedActivity:") + "Display #93:"
        assertEquals(AppLaunchRecovery.Move(94, 14500, 14500), AppLaunchRecovery.reusableTask(dump, pkg, 93, 94))
    }

    /** 只有暂停摘要时，不视为可聚焦任务 */
    @Test
    fun staleSummaryWithoutTaskTreeCannotBeReused() {
        val dump = "Display #94:\n  mLastPausedActivity: ActivityRecord{a u0 $pkg/.app.logo_icon_9 t7791}\nDisplay #93:"
        assertNull(AppLaunchRecovery.reusableTask(dump, pkg, 93, 94))
        assertFalse(AppLaunchRecovery.hasTask(dump, pkg, 94))
    }

    /**
     * 组件实际页面已在另一应用屏恢复时，不选择偏好屏上已暂停的旧实例
     */
    @Test
    fun resumedWidgetPageWinsOverStoppedPreferredInstance() {
        val native =
            unlocked
                .substringBefore("Display #93")
                .replace("Display #0", "Display #93")
                .replace("RESUMED", "STOPPED")
                .replace("mResumedActivity:", "mLastPausedActivity:")
        val actual = unlocked.substringBefore("Display #93").replace("Display #0", "Display #94").replace("14500", "14502")
        assertEquals(AppLaunchRecovery.Move(94, 14502, 14502), AppLaunchRecovery.reusableTask("$native\n$actual", pkg, 93, 94))
    }

    /**
     * 页面迁走后遗留的暂停摘要不能阻止冷启动，也不能阻止同包真实任务迁回
     */
    @Test
    fun stalePausedSummaryDoesNotBlockRealTaskRecovery() {
        val dump =
            unlocked.replace(
                "Display #93 (activities from top to bottom):",
                "Display #93:\n  mLastPausedActivity: ActivityRecord{old u0 $pkg/.app.MediaActivity t14000}",
            )
        assertFalse(AppLaunchRecovery.hasTask(dump, pkg, 93))
        assertEquals(AppLaunchRecovery.Move(0, 14500, 14500), candidate(dump))
    }

    /**
     * 活动明细存在但缺少任务树时保持保守，不将未知当成可以重复新建
     */
    @Test
    fun detailedActivityWithoutHierarchyStillBlocksNewTask() {
        val dump = "Display #93:\n  * Hist #0: ActivityRecord{a u0 $pkg/.app.MediaActivity t14500}"
        assertTrue(AppLaunchRecovery.hasTask(dump, pkg, 93))
        assertNull(AppLaunchRecovery.reusableTask(dump, pkg, 93, 94))
    }

    /**
     * 同包多个任务只有一个真实恢复时可复用该任务，否则保持歧义
     */
    @Test
    fun multipleTasksRequireUniqueResumedTask() {
        val task = unlocked.substringBefore("Display #93").replace("Display #0", "Display #94")
        val another =
            task
                .substringAfter('\n')
                .replace("14500", "14502")
                .replace("RESUMED", "STOPPED")
                .replace("mResumedActivity:", "mLastPausedActivity:")
        val dump = "$task\n$another\nDisplay #93:"
        assertEquals(AppLaunchRecovery.Move(94, 14500, 14500), AppLaunchRecovery.reusableTask(dump, pkg, 93, 94))
        assertNull(
            AppLaunchRecovery.reusableTask(
                dump
                    .replace("RESUMED", "STOPPED")
                    .replace("mResumedActivity:", "mLastPausedActivity:"),
                pkg,
                93,
                94,
            ),
        )
    }

    /**
     * 嵌套结构只聚焦活动叶任务，不把根任务编号传给聚焦命令
     */
    @Test
    fun reusableTaskKeepsLeafIdentity() {
        val dump =
            """
            Display #94:
              * Task{a #100 type=standard U=0}
                * Task{b #101 type=standard rootTaskId=100 U=0}
                  * Hist #0: ActivityRecord{c u0 $pkg/.app.MediaActivity t101}
                    mState=STOPPED
            Display #93:
            """.trimIndent()
        assertEquals(AppLaunchRecovery.Move(94, 100, 101), AppLaunchRecovery.reusableTask(dump, pkg, 93, 94))
        assertNull(AppLaunchRecovery.reusableTask(dump.replace("rootTaskId=100", "rootTaskId=200"), pkg, 93, 94))
    }

    /**
     * 手机、地图、侧栏与系统组织器任务不参与普通应用屏复用
     */
    @Test
    fun reuseExcludesUnownedDisplaysAndOrganizedRoots() {
        assertNull(AppLaunchRecovery.reusableTask(unlocked, pkg, 0, -1))
        val dump = unlocked.substringBefore("Display #93").replace("Display #0", "Display #95") + "Display #93:"
        assertNull(AppLaunchRecovery.reusableTask(dump, pkg, 93, 94))
        val organized =
            dump
                .replace("Display #95", "Display #94")
                .replace("    * Hist", "    mCreatedByOrganizer=true\n    * Hist")
        assertNull(AppLaunchRecovery.reusableTask(organized, pkg, 93, 94))
    }

    /**
     * 手机屏唯一活动任务可恢复到空应用屏，保留原任务身份
     */
    @Test
    fun recoversUniquePhoneTask() {
        assertEquals(AppLaunchRecovery.Move(0, 14500, 14500), candidate(unlocked))
    }

    /** 嵌套任务使用最外层根任务迁移，前台检查仍使用原子任务 */
    @Test
    fun nestedTasksKeepRootAndActivityTaskSeparate() {
        val dump =
            """
            Display #0:
              * Task{a #100 type=standard U=0}
                * Task{b #101 type=standard rootTaskId=100 U=0}
                  * Task{c #14500 type=standard rootTaskId=100 U=0}
                    * Hist #0: ActivityRecord{d u0 com.kugou.android/.app.MediaActivity t14500}
                      mState=RESUMED mVisibleRequested=true
            Display #93:
            """.trimIndent()
        assertEquals(AppLaunchRecovery.Move(0, 100, 14500), candidate(dump))
        assertNull(candidate(dump.replace("rootTaskId=100", "rootTaskId=200")))
    }

    /** 无恢复摘要时，根据详细生命周期识别前台 */
    @Test
    fun detailedResumedStateDoesNotRequireSummary() {
        val dump = unlocked.lineSequence().filterNot { it.contains("mResumedActivity:") }.joinToString("\n")
        assertEquals(AppLaunchRecovery.Move(0, 14500, 14500), candidate(dump))
    }

    /**
     * 锁屏后无恢复活动时，用户请求的别名仍能明确同包任务
     */
    @Test
    fun requestedAliasCanIdentifyLockedTask() {
        val dump = unlocked.replace("RESUMED", "STOPPED").replace("mResumedActivity:", "mLastPausedActivity:")
        assertEquals(AppLaunchRecovery.Move(0, 14500, 14500), candidate(dump))
    }

    /**
     * 启动返回真实页面且别名已退出时，使用同包页面匹配，拒绝其他包返回值
     */
    @Test
    fun reportedPageMustBelongToRequestedPackage() {
        val dump =
            unlocked
                .replace("logo_icon_9", "MediaActivity")
                .replace("RESUMED", "STOPPED")
                .replace("mResumedActivity:", "mLastPausedActivity:")
        assertEquals(AppLaunchRecovery.Move(0, 14500, 14500), candidate(dump))
        assertNull(candidate(dump, output = "Status: ok\nActivity: other.app/.Main"))
        assertNull(candidate(dump, output = "Status: ok"))
    }

    /**
     * 目标屏已有该包任务或目标屏快照缺失时，不迁移手机侧任务
     */
    @Test
    fun targetTaskOrUnknownTargetBlocksMigration() {
        val targetTask =
            unlocked.replace(
                "Display #93 (activities from top to bottom):",
                "Display #93:\n  * Hist #0: ActivityRecord{e u0 $pkg/.app.MediaActivity t14502}",
            )
        assertNull(candidate(targetTask))
        assertNull(candidate(unlocked.replace("Display #93", "Display #96")))
    }

    /**
     * 同包两个活动任务不能猜测选择其中一个
     */
    @Test
    fun multipleActiveTasksAreAmbiguous() {
        val dump =
            unlocked.replace("io.github.shaonialife.buicklinkplus/.launcher.GridActivity", "$pkg/.app.MediaActivity")
        assertNull(candidate(dump))
    }

    /**
     * 混合包根任务和未给出活动归属的叶任务均不得整体迁移
     */
    @Test
    fun mixedOrUnknownRootMembersBlockMigration() {
        val nested =
            """
            Display #0:
              * Task{a #100 type=standard U=0}
                * Task{b #14500 type=standard U=0}
                  * Hist #0: ActivityRecord{c u0 com.kugou.android/.app.MediaActivity t14500}
                    mState=RESUMED
                * Task{d #14503 type=standard U=0}
                  * Hist #0: ActivityRecord{e u0 other.app/.Main t14503}
                    mState=STOPPED
            Display #93:
            """.trimIndent()
        assertNull(candidate(nested))
        val unknownLeaf = nested.lineSequence().filterNot { it.contains("other.app/") }.joinToString("\n")
        assertNull(candidate(unknownLeaf))
    }

    /**
     * 缺少根任务层级或仅有子任务时，不将子任务误当作根任务
     */
    @Test
    fun missingRootHierarchyBlocksMigration() {
        val noHeader = unlocked.lineSequence().filterNot { it.contains("* Task{") }.joinToString("\n")
        assertNull(candidate(noHeader))
        assertNull(candidate(unlocked.replace("A=com.kugou.android", "rootTaskId=100 A=com.kugou.android")))
    }

    /**
     * 桌面及系统组织器管理的根任务不能随普通应用迁移
     */
    @Test
    fun homeAndOrganizedRootsAreExcluded() {
        assertNull(candidate(unlocked.replace("type=standard A=com.kugou.android", "type=home A=com.kugou.android")))
        val organized =
            unlocked.replace("    * Hist #0: ActivityRecord{b", "    mCreatedByOrganizer=true\n    * Hist #0: ActivityRecord{b")
        assertNull(candidate(organized))
    }

    /**
     * 地图屏及侧栏不在普通应用恢复来源之内，另一应用屏可按明确任务恢复
     */
    @Test
    fun onlyAllowedSourceDisplaysCanBeUsed() {
        assertNull(candidate(unlocked.replace("Display #0", "Display #95")))
        assertNull(candidate(unlocked.replace("Display #0", "Display #92")))
        val dump = unlocked.substringBefore("Display #93").replace("Display #0", "Display #94") + "Display #93:"
        assertEquals(AppLaunchRecovery.Move(94, 14500, 14500), candidate(dump))
    }

    /**
     * 成功空输出允许继续回读，但错误文本和失败退出必须拒绝
     */
    @Test
    fun operationAcceptanceRequiresBothExitAndText() {
        assertTrue(AppLaunchRecovery.operationAccepted(true, ""))
        assertTrue(AppLaunchRecovery.operationAccepted(true, "Setting focus to task 14500"))
        assertFalse(AppLaunchRecovery.operationAccepted(false, ""))
        val errors =
            listOf("Error: invalid task", "Exception occurred", "SecurityException: denied", "Permission Denial", "Unknown command")
        for (error in errors) {
            assertFalse(AppLaunchRecovery.operationAccepted(true, error))
        }
    }

    /**
     * 所有样例统一沿用本轮原生屏目标及允许的来源屏
     */
    private fun candidate(
        dump: String,
        output: String = launchOutput,
    ): AppLaunchRecovery.Move? = AppLaunchRecovery.candidate(dump, pkg, requested, output, 93, setOf(0, 94))
}
