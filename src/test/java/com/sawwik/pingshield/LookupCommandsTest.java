package com.sawwik.pingshield;

import com.sawwik.pingshield.integration.LookupCommands;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Формат команд CoreProtect. Тесты намеренно жёсткие: если CoreProtect сменит синтаксис,
 * сломается именно этот тест, а не кнопка в чате у администратора.
 *
 * <p>Отдельно проверяется отсутствие ведущего слэша: {@code ClickEvent.runCommand} ждёт команду
 * без него, иначе клик отправит в чат ошибку «Unknown command».</p>
 */
class LookupCommandsTest {

    @Test
    @DisplayName("Тег фильтра чистится от лишних символов")
    void filterPrefixSanitises() {
        assertEquals("[PingShield", LookupCommands.filterPrefix("PingShield"));
        assertEquals("[PingShield", LookupCommands.filterPrefix("[PingShield]"));
        assertEquals("[MyTag", LookupCommands.filterPrefix(" My Tag "));
        assertEquals("[abc", LookupCommands.filterPrefix("a,b,c"), "запятая в CoreProtect = разделитель, её нельзя");
        assertEquals("[PingShield", LookupCommands.filterPrefix(null));
        assertEquals("[PingShield", LookupCommands.filterPrefix("   "));
    }

    @Test
    @DisplayName("Полный тег маркера содержит вид события")
    void markerTag() {
        assertEquals("[PingShield/START]", LookupCommands.markerTag("PingShield", "START"));
        assertEquals("[PingShield/END]", LookupCommands.markerTag("[PingShield]", "END"));
        assertEquals("[PingShield/CP]", LookupCommands.markerTag(null, "CP"));
    }

    @Test
    @DisplayName("Время: минуты и целые часы, минимум 1 минута")
    void timeFormat() {
        assertEquals("1m", LookupCommands.time(1));
        assertEquals("30m", LookupCommands.time(30));
        assertEquals("59m", LookupCommands.time(59));
        assertEquals("1h", LookupCommands.time(60));
        assertEquals("2h", LookupCommands.time(120));
        assertEquals("1m", LookupCommands.time(0), "ноль подменяется минимальным окном");
        assertEquals("1m", LookupCommands.time(-15));
    }

    @Test
    @DisplayName("Поиск по логу сообщений: все маркеры / один игрок / один вид")
    void chatCommands() {
        assertEquals("co lookup a:chat f:[PingShield t:1h", LookupCommands.chatAll("PingShield", 60));
        assertEquals("co lookup a:chat f:[PingShield t:10m", LookupCommands.chatAll("PingShield", 10));

        assertEquals("co lookup u:Sawwik a:chat f:[PingShield t:10m",
                LookupCommands.chatPlayer("PingShield", "Sawwik", 10));

        assertEquals("co lookup u:Sawwik a:chat f:[PingShield/START] t:1h",
                LookupCommands.chatKind("PingShield", "Sawwik", 60, "START"));
        assertEquals("co lookup a:chat f:[PingShield/END] t:30m",
                LookupCommands.chatKind("PingShield", "  ", 30, "END"), "без игрока — по всему серверу");
        assertEquals("co lookup a:chat f:[PingShield/END] t:30m",
                LookupCommands.chatKind("PingShield", null, 30, "END"));
    }

    @Test
    @DisplayName("Поиск по блокам, области, контейнерам и кликам")
    void spaceCommands() {
        assertEquals("co lookup u:Sawwik t:1h r:5", LookupCommands.blocks("Sawwik", 60, 5));
        assertEquals("co lookup t:10m r:3", LookupCommands.area(10, 3));
        assertEquals("co lookup a:click t:10m r:3", LookupCommands.clicks(10, 3));
        assertEquals("co lookup u:Sawwik a:container t:1h r:4", LookupCommands.containers("Sawwik", 60, 4));

        assertEquals("co lookup t:10m r:1", LookupCommands.area(10, 0), "радиус не может быть нулевым");
        assertEquals("co lookup t:10m r:1", LookupCommands.area(10, -7));
    }

    @Test
    @DisplayName("Подсчёт и показ: #count и ведущий слэш")
    void countAndDisplay() {
        String command = LookupCommands.blocks("Sawwik", 60, 5);
        assertEquals(command + " #count", LookupCommands.count(command));
        assertEquals("/" + command, LookupCommands.display(command));
    }

    @Test
    @DisplayName("Ни одна команда для клика не начинается со слэша")
    void noLeadingSlash() {
        String[] commands = {
                LookupCommands.chatAll("PingShield", 60),
                LookupCommands.chatPlayer("PingShield", "Sawwik", 10),
                LookupCommands.chatKind("PingShield", "Sawwik", 10, "START"),
                LookupCommands.blocks("Sawwik", 10, 3),
                LookupCommands.area(10, 3),
                LookupCommands.clicks(10, 3),
                LookupCommands.containers("Sawwik", 10, 3)
        };
        for (String command : commands) {
            assertFalse(command.startsWith("/"), "ClickEvent.runCommand получает команду без слэша: " + command);
            assertTrue(command.startsWith("co lookup"), "все выборки — командой /co lookup: " + command);
        }
        assertTrue(LookupCommands.display(commands[0]).startsWith("/"), "для показа в чате слэш добавляется");
    }
}
