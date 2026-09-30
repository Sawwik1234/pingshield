package com.sawwik.pingshield;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectionLifecyclePolicyTest {

    @Test
    @DisplayName("Отложенная задача не включает защиту после её снятия")
    void releasedOrRemovedProtectionCannotFreezePlayer() {
        assertFalse(ProtectionLifecyclePolicy.mayApplyFreeze(true, false, false));
        assertFalse(ProtectionLifecyclePolicy.mayApplyFreeze(true, true, true));
        assertFalse(ProtectionLifecyclePolicy.mayApplyFreeze(false, true, false));
        assertTrue(ProtectionLifecyclePolicy.mayApplyFreeze(true, true, false));
    }

    @Test
    @DisplayName("Маркер восстановления сохраняется до респауна после смерти")
    void deathKeepsMarkerUntilRespawn() {
        assertTrue(ProtectionLifecyclePolicy.preserveRecoveryMarker(ProtectionManager.EndReason.DEATH));
        assertFalse(ProtectionLifecyclePolicy.preserveRecoveryMarker(ProtectionManager.EndReason.MANUAL));
        assertFalse(ProtectionLifecyclePolicy.preserveRecoveryMarker(ProtectionManager.EndReason.PING_OK));
    }
}
