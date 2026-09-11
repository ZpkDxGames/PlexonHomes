package com.plexon.homes.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

public final class Text {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private Text() {}
    public static Component mm(String input) { return MINI.deserialize(input); }
    public static String escape(String input) { return MINI.escapeTags(input == null ? "" : input); }
}
