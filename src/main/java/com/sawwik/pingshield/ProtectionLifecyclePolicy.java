package com.sawwik.pingshield;

/** Небольшие правила жизненного цикла, проверяемые без запуска сервера. */
final class ProtectionLifecyclePolicy {

    private ProtectionLifecyclePolicy() {
    }

    static boolean mayApplyFreeze(boolean playerOnline, boolean protectionStillActive, boolean released) {
        return playerOnline && protectionStillActive && !released;
    }

    static boolean preserveRecoveryMarker(ProtectionManager.EndReason reason) {
        return reason == ProtectionManager.EndReason.DEATH;
    }
}
