package com.sawwik.pingshield.integration;

import java.util.ArrayList;
import java.util.List;

/** Безопасные аргументы-запросы для CoreProtect API, которое может менять переданные списки. */
final class CoreProtectLookupArguments {

    private CoreProtectLookupArguments() {
    }

    /** Возвращает новую изменяемую копию списка действий для legacy performLookup. */
    static List<Integer> mutableActions(int... actionIds) {
        List<Integer> actions = new ArrayList<>(actionIds.length);
        for (int actionId : actionIds) {
            actions.add(actionId);
        }
        return actions;
    }
}
