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
    @DisplayName("При ручном снятии падение не смягчается")
    void manualReleaseDoesNotProtectFall() {
        assertFalse(FallDamageGracePolicy.shouldGrant(ProtectionManager.EndReason.MANUAL, true, 3000));
        assertFalse(FallDamageGracePolicy.shouldApplySlowFalling(false, ProtectionManager.EndReason.MANUAL));
    }

    @Test
    @DisplayName("Slow Falling даётся только при автоматическом снятии в воздухе")
    void slowFallingOnlyWhenAirborneAndAutomatic() {
        assertTrue(FallDamageGracePolicy.shouldApplySlowFalling(false, ProtectionManager.EndReason.PING_OK));
        assertFalse(FallDamageGracePolicy.shouldApplySlowFalling(true, ProtectionManager.EndReason.PING_OK));
        assertFalse(FallDamageGracePolicy.shouldApplySlowFalling(false, ProtectionManager.EndReason.DEATH));
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
