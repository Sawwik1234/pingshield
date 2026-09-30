package com.sawwik.pingshield;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallDamageGracePolicyTest {

    @Test
    @DisplayName("На земле после ручного снятия защиты новая попытка падения не получает льготу")
    void noGraceForNewFallFromGround() {
        assertFalse(FallDamageGracePolicy.shouldGrant(ProtectionManager.EndReason.MANUAL, false, 3000));
    }

    @Test
    @DisplayName("При снятии защиты в воздухе короткая льгота сохраняется")
    void graceWhenReleasedAirborne() {
        assertTrue(FallDamageGracePolicy.shouldGrant(ProtectionManager.EndReason.PING_OK, true, 3000));
    }

    @Test
    @DisplayName("Slow Falling даётся только если защита снята в воздухе")
    void slowFallingOnlyWhenAirborne() {
        assertTrue(FallDamageGracePolicy.shouldApplySlowFalling(false));
        assertFalse(FallDamageGracePolicy.shouldApplySlowFalling(true));
    }

    @Test
    @DisplayName("После смерти льгота к урону падения не нужна")
    void noGraceAfterDeath() {
        assertFalse(FallDamageGracePolicy.shouldGrant(ProtectionManager.EndReason.DEATH, true, 3000));
    }

    @Test
    @DisplayName("Отключённая или нулевая льгота не создаёт окно защиты")
    void disabledGrace() {
        assertFalse(FallDamageGracePolicy.shouldGrant(ProtectionManager.EndReason.MANUAL, true, 0));
    }
}
