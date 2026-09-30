package com.sawwik.pingshield.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class CoreProtectLookupArgumentsTest {

    @DisplayName("Для CoreProtect API передаётся изменяемый список действий")
    @Test
    void actionsAreMutableAndIndependent() {
        List<Integer> actions = CoreProtectLookupArguments.mutableActions(0, 1, 2);

        assertEquals(List.of(0, 1, 2), actions);
        actions.removeIf(action -> action == 1);
        assertEquals(List.of(0, 2), actions, "CoreProtect 24.1 вызывает removeIf у переданного списка");
        actions.add(3);
        assertEquals(List.of(0, 2, 3), actions);
        assertNotSame(actions, CoreProtectLookupArguments.mutableActions(0, 1, 2),
                "каждый запрос должен получать отдельный список");
    }
}
