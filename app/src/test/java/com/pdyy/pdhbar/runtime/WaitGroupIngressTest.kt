package com.pdyy.pdhbar.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class WaitGroupIngressTest {
    @Test
    fun queueApiExposesExactlyTwoBusinessActions() {
        assertEquals(2, ApprovalQueueAction.entries.size)
        assertEquals(
            setOf(WaitGroupTask.Enroll, WaitGroupTask.CancelEnrollment),
            ApprovalQueueAction.entries.map(ApprovalQueueAction::toTask).toSet()
        )
    }
}