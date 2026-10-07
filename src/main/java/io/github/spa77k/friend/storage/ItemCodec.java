package io.github.spa77k.friend.storage;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * ItemStack を Bukkit 標準の YAML 形式で文字列にする。Spigot と Paper のどちらでも読み書きでき、
 * サーバーの版を上げたときもアイテムのデータ版から移行される。
 */
final class ItemCodec {

    private ItemCodec() {
    }

    static String encode(ItemStack item) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("item", item);
        return yaml.saveToString();
    }

    static ItemStack decode(String data) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(data);
        ItemStack item = yaml.getItemStack("item");
        if (item == null) {
            throw new InvalidConfigurationException("item is missing");
        }
        return item;
    }
}
