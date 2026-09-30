package com.sawwik.pingshield;

/** Решает, нужна ли короткая защита от урона падения после снятия заморозки. */
final class FallDamageGracePolicy {

    private FallDamageGracePolicy() {
    }

    static boolean shouldApplySlowFalling(boolean onGround) {
        return !onGround;
    }

    static boolean shouldGrant(ProtectionManager.EndReason reason, boolean airborne, int graceMs) {
        if (!airborne || graceMs <= 0) {
            return false;
        }
        return reason == ProtectionManager.EndReason.PING_OK
                || reason == ProtectionManager.EndReason.TIMEOUT
                || reason == ProtectionManager.EndReason.MANUAL;
    }
}
