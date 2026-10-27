package com.pdyy.pdhbar

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
    fun failureOpensScannerWithoutClicking() {
        val insightVisible = mutableStateOf(true)
        val reason = mutableStateOf<String?>(null)
        val navigations = AtomicInteger()
        compose.setContent {
            if (insightVisible.value) {
                Text("Insight显示区域")
                reason.value?.let { failure ->
                    InsightFailureFallback(failure) {
                        navigations.incrementAndGet()
                        insightVisible.value = false
                    }
                }
            } else {
                Text("扫码UI")
            }
        }

        compose.onNodeWithText("Insight显示区域").assertIsDisplayed()
        compose.runOnIdle { reason.value = "显示模块渲染进程被终止，已切换到扫码页面" }
        compose.waitForIdle()

        compose.onNodeWithText("扫码UI").assertIsDisplayed()
        compose.onNodeWithText("显示模块已停止，打开扫码页面").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, navigations.get()) }
    }

    @Test
    fun recompositionDoesNotRepeatFallback() {
        val reason = mutableStateOf("显示模块渲染进程崩溃，已切换到扫码页面")
        val navigations = AtomicInteger()
        compose.setContent {
            // Keep the effect mounted to verify recompositions do not trigger another navigation.
            InsightFailureFallback(reason.value) { navigations.incrementAndGet() }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, navigations.get())
            reason.value = "显示模块加载失败，已切换到扫码页面"
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, navigations.get()) }
    }
}
