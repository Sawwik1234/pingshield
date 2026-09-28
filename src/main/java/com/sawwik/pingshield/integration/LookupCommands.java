package com.sawwik.pingshield.integration;

/**
 * Генератор команд поиска CoreProtect.
 *
 * <p>Вынесено отдельным классом специально: он <b>не зависит ни от CoreProtect, ни от Bukkit</b>,
 * поэтому формат команд покрыт юнит-тестами. Если CoreProtect изменит синтаксис — правку видно
 * по упавшим тестам, а не по жалобам администраторов.</p>
 *
 * <p>Команды возвращаются <b>без</b> ведущего слэша: именно в таком виде их принимает
 * {@code ClickEvent.runCommand} (Adventure добавит слэш сам, а если он уже есть — команда
 * не выполнится и в чат уйдёт ошибка).</p>
 */
public final class LookupCommands {

    /** Тег маркера без названия события, например {@code [PingShield}. */
    public static String filterPrefix(String markerTag) {
        String tag = (markerTag == null || markerTag.isBlank()) ? "PingShield" : markerTag.trim();
        return "[" + tag.replace("[", "").replace("]", "").replace(",", "").replace(" ", "");
    }

    /** Полный тег события: {@code [PingShield/START]}. */
    public static String markerTag(String markerTag, String kind) {
        return filterPrefix(markerTag) + "/" + kind + "]";
    }

    /** Время в формате CoreProtect: минуты → суффикс m, целые часы → h. */
    public static String time(int minutes) {
        int value = Math.max(1, minutes);
        if (value % 60 == 0) {
            return (value / 60) + "h";
        }
        return value + "m";
    }

    /** Все события PingShield за период. */
    public static String chatAll(String markerTag, int minutes) {
        return "co lookup a:chat f:" + filterPrefix(markerTag) + " t:" + time(minutes);
    }

    /** События PingShield одного игрока. */
    public static String chatPlayer(String markerTag, String user, int minutes) {
        return "co lookup u:" + user + " a:chat f:" + filterPrefix(markerTag) + " t:" + time(minutes);
    }

    /** События конкретного вида ({@code START}, {@code END}, {@code HOLD}, {@code CP}, {@code IMMUNITY}). */
    public static String chatKind(String markerTag, String user, int minutes, String kind) {
        String tag = markerTag(markerTag, kind);
        StringBuilder command = new StringBuilder("co lookup");
        if (user != null && !user.isBlank()) {
            command.append(" u:").append(user);
        }
        return command.append(" a:chat f:").append(tag).append(" t:").append(time(minutes)).toString();
    }

    /** Блоки вокруг игрока. */
    public static String blocks(String user, int minutes, int radius) {
        return "co lookup u:" + user + " t:" + time(minutes) + " r:" + Math.max(1, radius);
    }

    /** Всё в радиусе от того, кто выполняет команду. */
    public static String area(int minutes, int radius) {
        return "co lookup t:" + time(minutes) + " r:" + Math.max(1, radius);
    }

    /** Взаимодействия (туда попадают записи logInteraction). */
    public static String clicks(int minutes, int radius) {
        return "co lookup a:click t:" + time(minutes) + " r:" + Math.max(1, radius);
    }

    /** Контейнеры рядом. */
    public static String containers(String user, int minutes, int radius) {
        return "co lookup u:" + user + " a:container t:" + time(minutes) + " r:" + Math.max(1, radius);
    }

    /** Число записей без вывода содержимого. */
    public static String count(String command) {
        return command + " #count";
    }

    /** То же с ведущим слэшем — для показа в чате. */
    public static String display(String command) {
        return "/" + command;
    }

    private LookupCommands() {
    }
}
