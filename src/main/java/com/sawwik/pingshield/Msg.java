package com.sawwik.pingshield;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Хелпер для сообщений: MiniMessage ({@code <gold>}, {@code <#ff8800>}) и классические
 * {@code &c}-коды, плюс простые плейсхолдеры {@code %player%}.
 */
public final class Msg {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private Msg() {
    }

    public static Component render(String raw, Object... placeholders) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        return MM.deserialize(applyPlaceholders(raw, placeholders));
    }

    public static void send(CommandSender to, String raw, Object... placeholders) {
        if (to == null || raw == null || raw.isEmpty()) {
            return;
        }
        to.sendMessage(render(raw, placeholders));
    }

    public static void send(Player to, String raw, Object... placeholders) {
        if (to == null || raw == null || raw.isEmpty()) {
            return;
        }
        to.sendMessage(render(raw, placeholders));
    }

    public static void actionBar(Player to, String raw, Object... placeholders) {
        if (to == null || raw == null || raw.isEmpty()) {
            return;
        }
        to.sendActionBar(render(raw, placeholders));
    }

    /**
     * Кнопка в чате: клик выполняет команду, наведение показывает саму команду.
     * Используется, чтобы встроить нативный поиск CoreProtect прямо в сообщения PingShield.
     *
     * @param command команда <b>без</b> ведущего слэша — так её принимает {@link ClickEvent#runCommand}
     */
    public static Component button(String label, String command, String hover, Object... placeholders) {
        Component base = render(label, placeholders);
        if (command == null || command.isBlank()) {
            return base;
        }
        Component tip = render(hover == null || hover.isBlank() ? "<gray>Нажмите, чтобы выполнить" : hover)
                .append(Component.newline())
                .append(Component.text("/" + command).color(NamedTextColor.GRAY));
        return base.clickEvent(ClickEvent.runCommand(command)).hoverEvent(HoverEvent.showText(tip));
    }

    /** Кнопка, которая копирует команду в буфер обмена (для тех, кто не хочет кликать). */
    public static Component copyButton(String label, String command, String hover, Object... placeholders) {
        Component base = render(label, placeholders);
        if (command == null || command.isBlank()) {
            return base;
        }
        Component tip = render(hover == null || hover.isBlank() ? "<gray>Скопировать команду" : hover)
                .append(Component.newline())
                .append(Component.text("/" + command).color(NamedTextColor.GRAY));
        return base.clickEvent(ClickEvent.copyToClipboard("/" + command)).hoverEvent(HoverEvent.showText(tip));
    }

    public static String plain(String raw, Object... placeholders) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return PLAIN.serialize(render(raw, placeholders));
    }

    private static String applyPlaceholders(String raw, Object... placeholders) {
        if (placeholders == null || placeholders.length < 2) {
            return raw;
        }
        String out = raw;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            out = out.replace("%" + placeholders[i] + "%", String.valueOf(placeholders[i + 1]));
        }
        return out;
    }
}
