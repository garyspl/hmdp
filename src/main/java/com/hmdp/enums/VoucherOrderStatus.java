package com.hmdp.enums;

import java.util.EnumSet;

public enum VoucherOrderStatus {
    UNPAID(1), PAID(2), USED(3), CANCELLED(4), REFUNDING(5), REFUNDED(6);

    private final int code;
    VoucherOrderStatus(int code) { this.code = code; }
    public int getCode() { return code; }

    public boolean canTransitTo(VoucherOrderStatus target) {
        switch (this) {
            case UNPAID: return EnumSet.of(PAID, CANCELLED).contains(target);
            case PAID: return EnumSet.of(USED, REFUNDING).contains(target);
            case REFUNDING: return target == REFUNDED;
            default: return false;
        }
    }

    public static VoucherOrderStatus of(int code) {
        for (VoucherOrderStatus value : values()) if (value.code == code) return value;
        throw new IllegalArgumentException("unknown order status: " + code);
    }
}
