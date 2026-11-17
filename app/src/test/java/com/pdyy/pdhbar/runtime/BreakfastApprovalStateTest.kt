package com.pdyy.pdhbar.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class BreakfastApprovalStateTest {
    @Test
    fun falseAllowsExecutionWithoutChangingEntitlement() {
        val cache = BreakfastRightCacheNode(orderCode = "test", cachedAtEpochMs = 1L, allowBreakfast = false)
        assertEquals(WaitApprovalState.Approved, cache.allowBreakfast.toApprovalState())
        assertEquals(1, cache.allowBreakfast.toApprovalState().value)
        assertEquals(false, cache.allowBreakfast)
    }

    @Test
    fun trueStillAllowsExecution() {
        assertEquals(WaitApprovalState.Approved, true.toApprovalState())
    }

    @Test
    fun missingEntitlementStillRequiresQuery() {
        val entitlement: Boolean? = null
        assertEquals(WaitApprovalState.Pending, entitlement.toApprovalState())
    }
}
