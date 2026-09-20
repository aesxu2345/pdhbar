package com.pdyy.pdhbar

import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class InsightFailureFallbackTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun recoverySurvivesScannerNavigationAndReopensUIKit() {
        val visible = mutableStateOf(true)
        val reason = mutableStateOf<String?>(null)
        val instances = AtomicInteger()
        val disposals = AtomicInteger()
        val reopened = AtomicInteger()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            if (!visible.value) Text("扫码UI")
            InsightRecoveryHost(visible = visible.value, onReopen = {
                visible.value = true
                reopened.incrementAndGet()
            }) { restart ->
                val instance = remember { instances.incrementAndGet() }
                DisposableEffect(Unit) {
                    onDispose { disposals.incrementAndGet() }
                }
                Text("Insight实例$instance")
                reason.value?.let { failure ->
                    InsightFailureFallback(failure) {
                        reason.value = null
                        restart()
                        visible.value = false
                    }
                }
            }
        }
        compose.runOnIdle { reason.value = "显示模块渲染进程被终止" }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("扫码UI").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, disposals.get())
            assertEquals(1, instances.get())
        }
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
        compose.onNodeWithText("Insight实例2").assertIsDisplayed()
        compose.onNodeWithText("扫码UI").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, instances.get())
            assertEquals(1, reopened.get())
        }
    }

    @Test
    fun failureRecreatesInsightWithoutClicking() {
        val reason = mutableStateOf<String?>(null)
        val instances = AtomicInteger()
        val disposals = AtomicInteger()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            InsightRecoveryHost { restart ->
                val instance = remember { instances.incrementAndGet() }
                DisposableEffect(Unit) {
                    onDispose { disposals.incrementAndGet() }
                }
                Text("Insight实例$instance")
                reason.value?.let { failure ->
                    InsightFailureFallback(failure) {
                        reason.value = null
                        restart()
                        restart() // Duplicate requests must not create another generation.
                    }
                }
            }
        }

        compose.onNodeWithText("Insight实例1").assertIsDisplayed()
        repeat(2) { failureIndex ->
            compose.runOnIdle { reason.value = "显示模块渲染进程被终止，正在重新打开UIKit" }
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithText("正在重新打开UIKit…").assertIsDisplayed()
            compose.runOnIdle { assertEquals(failureIndex + 1, instances.get()) }
            compose.mainClock.advanceTimeBy(1_100)
            compose.waitForIdle()
            compose.onNodeWithText("Insight实例${failureIndex + 2}").assertIsDisplayed()
            compose.onNodeWithText("扫码UI").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(failureIndex + 2, instances.get())
                assertEquals(failureIndex + 1, disposals.get())
            }
        }
    }

    @Test
    fun recompositionDoesNotRepeatFallback() {
        val reason = mutableStateOf("显示模块渲染进程崩溃，正在重新打开UIKit")
        val restarts = AtomicInteger()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            // Keep the effect mounted to verify recompositions do not trigger another restart.
            InsightFailureFallback(reason.value) { restarts.incrementAndGet() }
        }
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, restarts.get())
            reason.value = "显示模块加载失败，正在重新打开UIKit"
        }
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, restarts.get()) }
    }

    @Test
    fun persistentFailureStopsAndAllowsManualReopenWithoutOpeningScanner() {
        val instances = AtomicInteger()
        val disposals = AtomicInteger()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            InsightRecoveryHost { restart ->
                remember { instances.incrementAndGet() }
                DisposableEffect(Unit) {
                    onDispose { disposals.incrementAndGet() }
                }
                InsightFailureFallback("显示模块加载失败", restart)
            }
        }
        repeat(3) {
            compose.mainClock.advanceTimeBy(1_200)
            compose.waitForIdle()
        }
        compose.onNodeWithText("UIKit连续恢复失败，已暂停自动重开").assertIsDisplayed()
        compose.onNodeWithText("扫码UI").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(3, instances.get())
            assertEquals(3, disposals.get())
        }
        compose.mainClock.advanceTimeBy(120_000)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(3, instances.get()) }

        compose.onNodeWithText("重新打开UIKit").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("正在重新打开UIKit…").assertIsDisplayed()
        compose.runOnIdle { assertEquals(4, instances.get()) }
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
        // Advancing the Compose clock does not expire the host's real monotonic retry window.
        compose.onNodeWithText("UIKit连续恢复失败，已暂停自动重开").assertIsDisplayed()
        compose.runOnIdle { assertEquals(4, disposals.get()) }
    }

    @Test
    fun retiredGenerationCannotReopenCurrentInstance() {
        val instances = AtomicInteger()
        val restarts = mutableListOf<() -> Unit>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            InsightRecoveryHost { restart ->
                val instance = remember {
                    restarts.add(restart)
                    instances.incrementAndGet()
                }
                Text("Insight实例$instance")
            }
        }
        compose.runOnIdle { restarts[0]() }
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
        compose.onNodeWithText("Insight实例2").assertIsDisplayed()
        compose.runOnIdle { restarts[0]() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, instances.get()) }
    }

    @Test
    fun leavingScreenCancelsPendingRestart() {
        val visible = mutableStateOf(true)
        val restarts = AtomicInteger()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            if (visible.value) {
                InsightRecoveryHost(onReopen = { restarts.incrementAndGet() }) { restart ->
                    InsightFailureFallback("显示模块加载失败", restart)
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { visible.value = false }
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, restarts.get()) }
    }
}
