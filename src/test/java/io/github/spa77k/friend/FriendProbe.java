package io.github.spa77k.friend;

import io.github.spa77k.friend.FriendService.Result;
import io.github.spa77k.friend.storage.Pair;
import java.io.File;
import java.util.List;
import java.util.logging.Level;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 隔離サーバーで、申請・承認・手数料・上限・共有チェスト・解消を確かめる。
 * コマンドの表示、画面の操作、テレポートは、実クライアントで別に確かめる。
 */
public final class FriendProbe extends JavaPlugin {

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                runProbe();
                getLogger().info("FRIEND_PROBE_PASS");
            } catch (Throwable error) {
                getLogger().log(Level.SEVERE, "FRIEND_PROBE_FAIL", error);
            } finally {
                Bukkit.shutdown();
            }
        }, 40);
    }

    private static void check(boolean result, String message) {
        if (!result) {
            throw new AssertionError(message);
        }
        Bukkit.getLogger().info("ok: " + message);
    }

    @SuppressWarnings("deprecation")
    private void runProbe() throws Exception {
        FriendPlugin plugin = (FriendPlugin) Bukkit.getPluginManager().getPlugin("Friend");
        FriendService service = plugin.service();
        SharedChests chests = plugin.chests();
        Economy economy = service.economy();
        check(economy != null, "経済プラグインを見つけた");

        OfflinePlayer alice = Bukkit.getOfflinePlayer("Alice");
        OfflinePlayer bob = Bukkit.getOfflinePlayer("Bob");
        OfflinePlayer carol = Bukkit.getOfflinePlayer("Carol");
        OfflinePlayer dave = Bukkit.getOfflinePlayer("Dave");
        for (OfflinePlayer player : List.of(alice, bob, carol, dave)) {
            economy.createPlayerAccount(player);
            economy.withdrawPlayer(player, economy.getBalance(player));
            economy.depositPlayer(player, 1000);
            service.rememberName(player.getUniqueId(), player.getName());
        }

        check(service.request(alice, "Alice", 500, alice, "Alice") == Result.SELF, "自分には申請できない");
        check(service.request(alice, "Alice", 500, bob, "Bob") == Result.REQUESTED, "申請できる");
        check(economy.getBalance(alice) == 1000, "申請の時点ではお金を引かない");
        check(service.request(alice, "Alice", 500, bob, "Bob") == Result.ALREADY_REQUESTED, "同じ相手へ二重に申請しない");
        check(service.requestsTo(bob.getUniqueId()).size() == 1, "相手に申請が1件届いた");
        check(service.accept(alice, "Alice", 500, bob.getUniqueId()) == Result.NO_REQUEST, "申請していない側は承認できない");

        check(service.accept(bob, "Bob", 500, alice.getUniqueId()) == Result.OK, "承認でフレンドになる");
        check(economy.getBalance(alice) == 500 && economy.getBalance(bob) == 500, "2人とも500ずつ払った");
        check(service.areFriends(alice.getUniqueId(), bob.getUniqueId())
                && service.areFriends(bob.getUniqueId(), alice.getUniqueId()), "どちらから見てもフレンド");
        check(service.requestsTo(bob.getUniqueId()).isEmpty(), "承認した申請は消えた");
        check(service.request(bob, "Bob", 500, alice, "Alice") == Result.ALREADY_FRIENDS, "フレンド同士は申請できない");
        check(service.findFriend(alice.getUniqueId(), "bob").map(friend -> friend.name().equals("Bob")).orElse(false),
                "名前の大文字・小文字を区別せずフレンドを探せる");

        // 相手の所持金が足りないと成立せず、どちらのお金も動かない
        economy.withdrawPlayer(carol, economy.getBalance(carol));
        economy.depositPlayer(carol, 100);
        check(service.request(alice, "Alice", 500, carol, "Carol") == Result.REQUESTED, "所持金の少ない相手にも申請はできる");
        check(service.accept(carol, "Carol", 500, alice.getUniqueId()) == Result.NOT_ENOUGH_MONEY,
                "承認する側の所持金が足りなければ成立しない");
        check(economy.getBalance(alice) == 500 && economy.getBalance(carol) == 100, "足りないときは誰のお金も動かない");
        economy.depositPlayer(carol, 900);
        economy.withdrawPlayer(alice, 400);
        check(service.accept(carol, "Carol", 500, alice.getUniqueId()) == Result.OTHER_NOT_ENOUGH_MONEY,
                "申請した側の所持金が承認までに減っていれば成立しない");
        check(economy.getBalance(alice) == 100 && economy.getBalance(carol) == 1000, "このときもお金は動かない");
        check(service.deny(carol.getUniqueId(), alice.getUniqueId()) == Result.OK
                && service.requestsTo(carol.getUniqueId()).isEmpty(), "申請を断れる");
        check(service.request(alice, "Alice", 500, carol, "Carol") == Result.NOT_ENOUGH_MONEY,
                "所持金が足りなければ申請もできない");
        economy.depositPlayer(alice, 900);

        // 相手から先に申請が来ていれば、申請し返すだけで成立する
        check(service.request(carol, "Carol", 500, dave, "Dave") == Result.REQUESTED, "CarolがDaveへ申請");
        check(service.request(dave, "Dave", 0, carol, "Carol") == Result.OK, "申請し返すと承認になる");
        check(economy.getBalance(carol) == 500 && economy.getBalance(dave) == 1000, "手数料0の人は払わない");

        // 共有チェスト
        Pair pair = Pair.of(alice.getUniqueId(), bob.getUniqueId());
        check(pair.equals(Pair.of(bob.getUniqueId(), alice.getUniqueId())), "どちらからでも同じ組になる");
        Inventory chest = chests.inventory(pair, "test");
        check(chest != null && chest.getSize() == 27 && chest.isEmpty(), "空の共有チェストを開ける");
        check(chests.inventory(pair, "other") == chest, "2人目は同じチェストを開く（中身が二重にならない）");
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.setDisplayName("Excalibur");
        meta.addEnchant(Enchantment.getByKey(NamespacedKey.minecraft("sharpness")), 5, true);
        sword.setItemMeta(meta);
        chest.setItem(4, sword);
        chest.setItem(26, new ItemStack(Material.DIAMOND, 64));
        check(chests.save(pair), "中身を保存できた");
        chests.forget(pair);
        Inventory reloaded = chests.inventory(pair, "test");
        check(reloaded != chest && reloaded.getItem(4).isSimilar(sword)
                && reloaded.getItem(26).getAmount() == 64, "保存した中身が同じ位置に戻る");

        check(service.remove(alice.getUniqueId(), "Alice", bob.getUniqueId()) == Result.CHEST_NOT_EMPTY,
                "中身が残っていると解消できない");
        reloaded.clear();
        check(chests.save(pair), "空にして保存");
        check(service.remove(alice.getUniqueId(), "Alice", bob.getUniqueId()) == Result.OK, "空なら解消できる");
        check(!service.areFriends(alice.getUniqueId(), bob.getUniqueId()), "解消後はフレンドでない");
        check(economy.getBalance(alice) == 1000 && economy.getBalance(bob) == 500, "解消は無料で、払った分は戻らない");
        check(service.remove(alice.getUniqueId(), "Alice", bob.getUniqueId()) == Result.NOT_FRIENDS,
                "フレンドでない相手は解消できない");

        // 上限（テスト設定では2人）
        check(service.request(alice, "Alice", 0, bob, "Bob") == Result.REQUESTED
                && service.accept(bob, "Bob", 0, alice.getUniqueId()) == Result.OK, "AliceとBobがまたフレンドに");
        check(service.request(alice, "Alice", 0, carol, "Carol") == Result.REQUESTED
                && service.accept(carol, "Carol", 0, alice.getUniqueId()) == Result.OK, "AliceとCarolもフレンドに");
        check(service.request(alice, "Alice", 0, dave, "Dave") == Result.LIMIT_REACHED, "自分が上限なら申請できない");
        check(service.request(dave, "Dave", 0, alice, "Alice") == Result.OTHER_LIMIT_REACHED, "相手が上限なら申請できない");
        check(service.friends(alice.getUniqueId()).size() == 2, "フレンド一覧に2人");
        Inventory newChest = chests.inventory(Pair.of(alice.getUniqueId(), bob.getUniqueId()), "test");
        check(newChest.isEmpty(), "解消後に組み直したチェストは空から始まる");

        Messages messages = Messages.load(plugin, "ja");
        check(messages.text("free").equals("無料") && messages.lines("help", "fee", "500", "max", 10).size() == 8,
                "設定した言語の文面を使う");
        check(new File(getDataFolder().getParentFile(), "Friend/friends.db").isFile(), "データベースを作った");
    }
}
