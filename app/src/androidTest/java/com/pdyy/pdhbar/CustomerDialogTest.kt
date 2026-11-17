package com.pdyy.pdhbar

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdyy.pdhbar.runtime.UiCommand
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomerDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun falseFreezesUntilThreeSecondsAndRecompositionDoesNotRestartTimer() {
        val customer = mutableStateOf(customer(false))
        var clicks = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme { CustomerDialog(customer.value, { clicks++ }, {}, {}) }
        }
        compose.onNodeWithText("此人无早餐权益，是否仍要继续？").assertIsDisplayed()
        compose.onNodeWithText("取消登记").assertIsEnabled()
        compose.onNodeWithText("完成早餐（3s）").assertIsNotEnabled().performClick()
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithText("完成早餐（2s）").assertIsNotEnabled()
        compose.runOnIdle { customer.value = customer.value.copy(name = "更新姓名") }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("完成早餐（1s）").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("完成早餐（1s）").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, clicks) }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("完成早餐").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.onNodeWithText("取消登记").assertIsEnabled()
    }

    @Test
    fun falseUsesSameCancelActionAsTrueEvenDuringFreeze() {
        val customer = mutableStateOf(customer(false))
        var doneClicks = 0
        var cancelClicks = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                CustomerDialog(customer.value, { doneClicks++ }, { cancelClicks++ }, {})
            }
        }
        compose.onNodeWithText("完成早餐（3s）").assertIsNotEnabled()
        compose.onNodeWithText("取消登记").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, cancelClicks)
            assertEquals(0, doneClicks)
            customer.value = customer.value.copy(allowBreakfast = true, blockReason = null)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("此人无早餐权益，是否仍要继续？").assertDoesNotExist()
        compose.onNodeWithText("完成早餐").assertIsEnabled()
        compose.onNodeWithText("取消登记").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(2, cancelClicks)
            assertEquals(0, doneClicks)
        }
    }

    @Test
    fun queryPlaceholderTimeDoesNotCountAndNewSessionRestartsFreeze() {
        val customer = mutableStateOf(customer(false).copy(querying = true))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme { CustomerDialog(customer.value, {}, {}, {}) }
        }
        compose.mainClock.advanceTimeBy(3_200)
        compose.onNodeWithText("后台审批").assertIsEnabled()
        compose.runOnIdle { customer.value = customer.value.copy(querying = false) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("完成早餐（3s）").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(3_100)
        compose.onNodeWithText("完成早餐").assertIsEnabled()
        // Same order, different scan session must not inherit the old timer.
        compose.runOnIdle { customer.value = customer.value.copy(queryId = 2L) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("完成早餐（3s）").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(3_100)
        compose.onNodeWithText("完成早餐").assertIsEnabled()
    }

    @Test
    fun trueIsImmediatelyEnabledButUnknownRemainsDisabled() {
        val customer = mutableStateOf(customer(true))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme { CustomerDialog(customer.value, {}, {}, {}) }
        }
        compose.onNodeWithText("完成早餐").assertIsEnabled()
        compose.runOnIdle { customer.value = customer.value.copy(allowBreakfast = null) }
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText("完成早餐").assertIsNotEnabled()
    }

    private fun customer(allowBreakfast: Boolean?) = UiCommand.ShowCustomer(
        orderCode = "test-order", cardNo = null, name = "测试客户", sex = null,
        phone = null, avatarUrl = null, birthDate = null, packageName = null,
        examType = null, organizationName = null, examTotalPrice = null,
        hasBreakfast = false, allowBreakfast = allowBreakfast,
        blockReason = if (allowBreakfast == false) "测试无权益原因" else null,
        queryId = 1L
    )
}
