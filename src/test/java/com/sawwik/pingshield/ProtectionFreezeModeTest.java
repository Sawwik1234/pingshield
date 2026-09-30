package com.sawwik.pingshield;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtectionFreezeModeTest {

    @Test
    @DisplayName("Снимок фиксирует режим заморозки, даже если серверный конфиг перезагрузили")
    void capturesServerModeForRelease() {
        Protection protection = new Protection(UUID.randomUUID(), "Player", Protection.Reason.MANUAL,
                null, 0L, 3000);

        protection.captureFreezeMode(PingShieldConfig.FreezeMode.PASSIVE);

        assertEquals(PingShieldConfig.FreezeMode.PASSIVE,
                protection.effectiveFreezeMode(PingShieldConfig.FreezeMode.TELEPORT));
    }

    @Test
    @DisplayName("Снимок сохраняет персональный режим LuckPerms")
    void capturesPersonalModeForRelease() {
        Protection protection = new Protection(UUID.randomUUID(), "Player", Protection.Reason.MANUAL,
                null, 0L, 3000);
        protection.setFreezeModeOverride(PingShieldConfig.FreezeMode.FLY);

        protection.captureFreezeMode(PingShieldConfig.FreezeMode.PASSIVE);
        protection.setFreezeModeOverride(PingShieldConfig.FreezeMode.TELEPORT);

        assertEquals(PingShieldConfig.FreezeMode.FLY,
                protection.effectiveFreezeMode(PingShieldConfig.FreezeMode.TELEPORT));
    }
}
