package com.sawwik.pingshield;

/** Решает, нужна ли короткая защита от урона падения после снятия заморозки. */
final class FallDamageGracePolicy {

    private FallDamageGracePolicy() {
    }

    static boolean shouldApplySlowFalling(boolean onGround, ProtectionManager.EndReason reason) {
        return !onGround
                && reason != ProtectionManager.EndReason.MANUAL
                && reason != ProtectionManager.EndReason.DEATH;
    }

    static boolean shouldGrant(ProtectionManager.EndReason reason, boolean airborne, int graceMs) {
        if (!airborne || graceMs <= 0) {
            return false;
        }
        // Администраторское снятие — немедленное: не отменяем падение и не выдаём Slow Falling.
        return reason == ProtectionManager.EndReason.PING_OK
                || reason == ProtectionManager.EndReason.TIMEOUT;
    }
}
