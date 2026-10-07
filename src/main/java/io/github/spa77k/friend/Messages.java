package io.github.spa77k.friend;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * messages_<language>.yml の文面を読み、色コードと {key} の置き換えを済ませて返す。
 * サーバー側のファイルにないキーは、同梱の同じ言語のファイル、最後に英語の文面で補う。
 */
public final class Messages {

    private static final String[] BUNDLED = {"en", "ja"};

    private final YamlConfiguration messages;

    private Messages(YamlConfiguration messages) {
        this.messages = messages;
    }

    public static Messages load(JavaPlugin plugin, String language) {
        for (String bundled : BUNDLED) {
            if (!new File(plugin.getDataFolder(), fileName(bundled)).exists()) {
                plugin.saveResource(fileName(bundled), false);
            }
        }
        File file = new File(plugin.getDataFolder(), fileName(language));
        if (!file.exists()) {
            plugin.getLogger().warning(fileName(language) + " was not found. Using English messages.");
            file = new File(plugin.getDataFolder(), fileName("en"));
        }
        YamlConfiguration messages = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = bundled(plugin, fileName(language));
        YamlConfiguration english = bundled(plugin, fileName("en"));
        if (defaults == null) {
            defaults = english;
        } else {
            defaults.setDefaults(english);
        }
        messages.setDefaults(defaults);
        return new Messages(messages);
    }

    private static String fileName(String language) {
        return "messages_" + language + ".yml";
    }

    private static YamlConfiguration bundled(JavaPlugin plugin, String name) {
        InputStream stream = plugin.getResource(name);
        if (stream == null) {
            return null;
        }
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    /** 置き換えは "name", "Steve", "count", "3" のようにキーと値を交互に渡す。 */
    public String text(String key, Object... replacements) {
        return format(messages.getString(key, key), replacements);
    }

    public List<String> lines(String key, Object... replacements) {
        List<String> result = new ArrayList<>();
        for (String line : messages.getStringList(key)) {
            result.add(format(line, replacements));
        }
        return result;
    }

    public void send(CommandSender target, String key, Object... replacements) {
        target.sendMessage(text("prefix") + text(key, replacements));
    }

    private static String format(String raw, Object... replacements) {
        String result = raw;
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            result = result.replace("{" + replacements[i] + "}", String.valueOf(replacements[i + 1]));
        }
        return ChatColor.translateAlternateColorCodes('&', result);
    }
}
