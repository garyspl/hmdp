package com.hmdp;

import com.hmdp.enums.VoucherOrderStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoucherOrderStatusTest {
    @Test
    void shouldOnlyAllowDefinedTransitions() {
        assertTrue(VoucherOrderStatus.UNPAID.canTransitTo(VoucherOrderStatus.PAID));
        assertTrue(VoucherOrderStatus.PAID.canTransitTo(VoucherOrderStatus.REFUNDING));
        assertTrue(VoucherOrderStatus.REFUNDING.canTransitTo(VoucherOrderStatus.REFUNDED));
        assertFalse(VoucherOrderStatus.UNPAID.canTransitTo(VoucherOrderStatus.REFUNDED));
        assertFalse(VoucherOrderStatus.CANCELLED.canTransitTo(VoucherOrderStatus.PAID));
    }
}
