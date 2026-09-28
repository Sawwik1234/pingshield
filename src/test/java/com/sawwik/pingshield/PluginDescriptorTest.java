package com.sawwik.pingshield;

import org.bukkit.permissions.Permission;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверка дескриптора плагина <b>тем же кодом, которым его читает сервер</b>:
 * {@link PluginDescriptionFile} — это ровно тот парсер (SnakeYAML), на котором Paper/Folia
 * валится с «Invalid plugin.yml». Этот тест появился не просто так: однажды правка
 * {@code permissions.children} уехала на другой уровень отступа, YAML стал невалидным,
 * и плагин не загрузился на живом сервере — а сборка и все остальные тесты при этом были
 * зелёными. Теперь такое падает в CI, а не у администратора.
 */
class PluginDescriptorTest {

    /** Подкоманды /pingshield, для которых обязаны быть права pingshield.command.*. */
    private static final Set<String> SUBCOMMANDS = Set.of(
            "reload", "status", "net", "check", "protect", "unprotect",
            "info", "profile", "perf", "cp", "search", "perms", "via");

    /** Текст дескриптора как есть — для проверок тех ключей, которых нет в API этого билда. */
    private static String descriptorText() throws IOException {
        try (InputStream stream = PluginDescriptorTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream, "plugin.yml должен попадать в JAR (src/main/resources)");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static PluginDescriptionFile descriptor() throws IOException, InvalidDescriptionException {
        try (InputStream stream = PluginDescriptorTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream, "plugin.yml должен попадать в JAR (src/main/resources)");
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return new PluginDescriptionFile(reader);
            }
        }
    }

    @Test
    @DisplayName("Сервер способен прочитать plugin.yml (тот же парсер, что в Paper/Folia)")
    void serverCanParseDescriptor() {
        assertDoesNotThrow(() -> {
            PluginDescriptionFile file = descriptor();
            assertNotNull(file.getName());
        }, "если здесь падает — плагин не загрузится на сервере с «Invalid plugin.yml»");
    }

    @Test
    @DisplayName("Обязательные поля дескриптора заполнены")
    void descriptorFields() throws Exception {
        PluginDescriptionFile file = descriptor();

        assertEquals("PingShield", file.getName());
        assertEquals("com.sawwik.pingshield.PingShieldPlugin", file.getMain());
        assertFalse(file.getVersion().isBlank());
        assertFalse(file.getVersion().contains("${"), "версия должна быть подставлена processResources");
        assertEquals("26.1", file.getAPIVersion());
        assertTrue(descriptorText().contains("folia-supported: true"),
                "Folia должна быть явно разрешена в дескрипторе");
        assertTrue(file.getSoftDepend().containsAll(List.of("CoreProtect", "LuckPerms")),
                "интеграции подключаются как softdepend, фактический список: " + file.getSoftDepend());
    }

    @Test
    @DisplayName("Команда /pingshield описана вместе с алиасами")
    void commandIsDeclared() throws Exception {
        PluginDescriptionFile file = descriptor();
        assertTrue(file.getCommands().containsKey("pingshield"), "команда pingshield должна быть в plugin.yml");

        // В этом билде paper-api команды отдаются как сырые карты: {key: {описание, usage, aliases}}
        Map<String, Object> command = file.getCommands().get("pingshield");
        assertNotNull(command.get("description"), "у команды должно быть описание");
        String usage = String.valueOf(command.get("usage"));
        assertTrue(usage.contains("/pingshield"), "usage: " + usage);
        @SuppressWarnings("unchecked")
        List<String> aliases = (List<String>) command.get("aliases");
        assertFalse(aliases == null || aliases.isEmpty(), "алиасы нужны, чтобы ничьи привычки не ломались");
        // usage перечисляет подкоманды — сверяем с реальным списком из кода
        for (String sub : SUBCOMMANDS) {
            assertTrue(usage.contains(sub), "в usage нет подкоманды " + sub + ": " + usage);
        }
    }

    @Test
    @DisplayName("У каждой подкоманды есть право pingshield.command.<подкоманда>")
    void everySubcommandHasPermission() throws Exception {
        Map<String, Permission> byName = permissionsByName(descriptor());
        for (String sub : SUBCOMMANDS) {
            String node = "pingshield.command." + sub;
            assertTrue(byName.containsKey(node), "нет права " + node);
            assertNotNull(byName.get(node).getDescription(), "у права " + node + " нет описания");
        }
    }

    @Test
    @DisplayName("«Зонтик» pingshield.admin ссылается только на существующие права (та самая ошибка отступов)")
    void adminChildrenAreConsistent() throws Exception {
        PluginDescriptionFile file = descriptor();
        Map<String, Permission> byName = permissionsByName(file);
        Permission admin = byName.get("pingshield.admin");
        assertNotNull(admin, "pingshield.admin должен быть объявлен");

        Map<String, Boolean> children = admin.getChildren();
        assertEquals(SUBCOMMANDS.size() + 1, children.size(),
                "14 детей: 13 подкоманд + pingshield.notify, фактически: " + children.keySet());
        for (Map.Entry<String, Boolean> entry : children.entrySet()) {
            assertEquals(Boolean.TRUE, entry.getValue());
            assertTrue(byName.containsKey(entry.getKey()),
                    "ребёнок " + entry.getKey() + " не объявлен как право — YAML съехал");
        }
        for (String sub : SUBCOMMANDS) {
            assertTrue(children.containsKey("pingshield.command." + sub),
                    "в зонтике нет подкоманды " + sub);
        }
    }

    @Test
    @DisplayName("Права для игроков: bypass, notify, notify.self, coreprotect, порог")
    void playerPermissionsExist() throws Exception {
        Map<String, Permission> byName = permissionsByName(descriptor());
        for (String node : List.of("pingshield.bypass", "pingshield.notify",
                "pingshield.notify.self", "pingshield.coreprotect")) {
            assertTrue(byName.containsKey(node), "нет права " + node);
        }
        assertEquals(19, byName.size(), "всего прав в дескрипторе: 1 зонтик + 13 подкоманд + 4 игрока + 1 порог-префикс");
    }

    @Test
    @DisplayName("plugin.yml — валидный UTF-8 без BOM (кириллица в описаниях не ломает парсер)")
    void descriptorEncodingIsClean() throws IOException {
        try (InputStream stream = PluginDescriptorTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream);
            byte[] bytes = stream.readAllBytes();
            assertTrue(bytes.length > 3);
            assertFalse(bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF,
                    "BOM в начале файла ломает разбор на некоторых сборках SnakeYAML");
            String text = new String(bytes, StandardCharsets.UTF_8);
            assertTrue(text.contains("Доступ ко всем командам"), "кириллица должна читаться как UTF-8");
        }
    }

    private static Map<String, Permission> permissionsByName(PluginDescriptionFile file) {
        return file.getPermissions().stream()
                .collect(Collectors.toMap(Permission::getName, permission -> permission));
    }
}
