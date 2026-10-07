package io.github.spa77k.friend;

import io.github.spa77k.friend.FriendService.Request;
import io.github.spa77k.friend.FriendService.Result;
import io.github.spa77k.friend.storage.Friend;
import io.github.spa77k.friend.storage.FriendStore;
import io.github.spa77k.friend.storage.Pair;
import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

public final class FriendPlugin extends JavaPlugin implements TabExecutor, Listener {

    private static final List<String> SUBCOMMANDS = List.of("add", "accept", "deny", "remove", "list", "chest", "tp");

    private FriendStore store;
    private SharedChests chests;
    private FriendService service;
    private Teleports teleports;
    private Messages messages;
    private int requestTimeoutSeconds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = Messages.load(this, getConfig().getString("language", "en"));
        store = new FriendStore(getLogger());
        try {
            store.open(new File(getDataFolder(), "friends.db"));
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to open the database. Friend is disabled.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        double fee = getConfig().getDouble("fee", 500);
        requestTimeoutSeconds = Math.max(10, getConfig().getInt("request-timeout", 300));
        chests = new SharedChests(this, store, getConfig().getInt("chest-rows", 3));
        service = new FriendService(store, chests, getLogger(), fee, Math.max(1, getConfig().getInt("max-friends", 10)),
                requestTimeoutSeconds * 1000L);
        teleports = new Teleports(this, service, messages, getConfig().getInt("teleport-delay", 3));
        getServer().getPluginManager().registerEvents(chests, this);
        getServer().getPluginManager().registerEvents(teleports, this);
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("friend");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        for (Player player : getServer().getOnlinePlayers()) {
            service.rememberName(player.getUniqueId(), player.getName());
        }
        if (fee > 0 && getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault is not installed, so becoming friends is free.");
        }
    }

    @Override
    public void onDisable() {
        if (teleports != null) {
            teleports.cancelAll();
        }
        if (chests != null) {
            chests.closeAll();
        }
        if (store != null) {
            store.close();
        }
    }

    /** テストから手続きを直接呼ぶための入口。 */
    public FriendService service() {
        return service;
    }

    public SharedChests chests() {
        return chests;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        service.rememberName(player.getUniqueId(), player.getName());
        int count = service.requestsTo(player.getUniqueId()).size();
        if (count > 0) {
            messages.send(player, "join-requests", "count", count);
        }
    }

    private String feeText(double fee) {
        Economy economy = service.economy();
        return fee <= 0 || economy == null ? messages.text("free") : economy.format(fee);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("friend.use")) {
            messages.send(player, "no-permission");
            return true;
        }
        if (args.length == 0) {
            help(player);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String name = args.length >= 2 ? args[1] : null;
        try {
            switch (sub) {
                case "add" -> {
                    if (name == null) {
                        help(player);
                    } else {
                        add(player, name);
                    }
                }
                case "accept" -> accept(player, name);
                case "deny" -> deny(player, name);
                case "remove" -> {
                    if (name == null) {
                        help(player);
                    } else {
                        remove(player, name);
                    }
                }
                case "list" -> list(player);
                case "chest" -> {
                    if (name == null) {
                        help(player);
                    } else {
                        chest(player, name);
                    }
                }
                case "tp" -> {
                    if (name == null) {
                        help(player);
                    } else {
                        teleport(player, name);
                    }
                }
                default -> help(player);
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to run /friend " + sub + " for " + player.getName(), e);
            messages.send(player, "error");
        }
        return true;
    }

    private void help(Player player) {
        for (String line : messages.lines("help", "fee", feeText(service.fee()), "max", service.maxFriends())) {
            player.sendMessage(line);
        }
    }

    private Player onlinePlayer(String name) {
        Player exact = getServer().getPlayerExact(name);
        if (exact != null) {
            return exact;
        }
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }

    private void add(Player player, String name) {
        Player target = onlinePlayer(name);
        if (target == null || !player.canSee(target)) {
            messages.send(player, "player-not-online", "name", name);
            return;
        }
        double fee = service.feeFor(player);
        Result result = service.request(player, player.getName(), fee, target, target.getName());
        if (result == Result.REQUESTED) {
            messages.send(player, "request-sent", "name", target.getName(), "seconds", requestTimeoutSeconds);
            messages.send(target, "request-received", "name", player.getName(), "fee", feeText(service.feeFor(target)),
                    "seconds", requestTimeoutSeconds);
        } else if (result == Result.OK) {
            becameFriends(player, target.getName(), target);
        } else {
            report(player, result, target.getName(), fee);
        }
    }

    private void accept(Player player, String name) {
        Optional<Request> request = pickRequest(player, name);
        if (request.isEmpty()) {
            return;
        }
        double fee = service.feeFor(player);
        Result result = service.accept(player, player.getName(), fee, request.get().from());
        if (result == Result.OK) {
            becameFriends(player, request.get().fromName(), getServer().getPlayer(request.get().from()));
        } else {
            report(player, result, request.get().fromName(), fee);
        }
    }

    private void deny(Player player, String name) {
        Optional<Request> request = pickRequest(player, name);
        if (request.isEmpty()) {
            return;
        }
        service.deny(player.getUniqueId(), request.get().from());
        messages.send(player, "request-denied", "name", request.get().fromName());
        Player requester = getServer().getPlayer(request.get().from());
        if (requester != null) {
            messages.send(requester, "request-denied-notify", "name", player.getName());
        }
    }

    /** 名前がなければ、申請が1件だけのときにそれを選ぶ。選べなければ案内を出して空を返す。 */
    private Optional<Request> pickRequest(Player player, String name) {
        List<Request> requests = service.requestsTo(player.getUniqueId());
        if (name != null) {
            Optional<Request> found = service.requestFrom(player.getUniqueId(), name);
            if (found.isEmpty()) {
                messages.send(player, "no-request", "name", name);
            }
            return found;
        }
        if (requests.size() == 1) {
            return Optional.of(requests.get(0));
        }
        if (requests.isEmpty()) {
            messages.send(player, "no-requests");
        } else {
            messages.send(player, "choose-request", "names", names(requests));
        }
        return Optional.empty();
    }

    private static String names(List<Request> requests) {
        List<String> names = new ArrayList<>();
        for (Request request : requests) {
            names.add(request.fromName());
        }
        return String.join(", ", names);
    }

    private void becameFriends(Player player, String otherName, Player other) {
        messages.send(player, "became-friends", "name", otherName);
        if (other != null) {
            messages.send(other, "became-friends", "name", player.getName());
        }
    }

    private void remove(Player player, String name) throws SQLException {
        Optional<Friend> friend = service.findFriend(player.getUniqueId(), name);
        if (friend.isEmpty()) {
            messages.send(player, "not-friends", "name", name);
            return;
        }
        Result result = service.remove(player.getUniqueId(), player.getName(), friend.get().id());
        if (result == Result.OK) {
            messages.send(player, "removed", "name", friend.get().name());
            Player other = getServer().getPlayer(friend.get().id());
            if (other != null) {
                messages.send(other, "removed-notify", "name", player.getName());
            }
        } else {
            report(player, result, friend.get().name(), 0);
        }
    }

    private void list(Player player) throws SQLException {
        List<Friend> friends = service.friends(player.getUniqueId());
        messages.send(player, "list-header", "count", friends.size(), "max", service.maxFriends());
        for (Friend friend : friends) {
            Player online = getServer().getPlayer(friend.id());
            boolean visible = online != null && player.canSee(online);
            player.sendMessage(messages.text(visible ? "list-online" : "list-offline", "name", friend.name()));
        }
        List<Request> requests = service.requestsTo(player.getUniqueId());
        if (!requests.isEmpty()) {
            messages.send(player, "list-requests", "names", names(requests));
        }
    }

    private void chest(Player player, String name) throws SQLException {
        Optional<Friend> friend = service.findFriend(player.getUniqueId(), name);
        if (friend.isEmpty()) {
            messages.send(player, "not-friends", "name", name);
            return;
        }
        Pair pair = Pair.of(player.getUniqueId(), friend.get().id());
        String first = pair.first().equals(player.getUniqueId()) ? player.getName() : friend.get().name();
        String second = pair.first().equals(player.getUniqueId()) ? friend.get().name() : player.getName();
        Inventory inventory = chests.inventory(pair, messages.text("chest-title", "first", first, "second", second));
        if (inventory == null) {
            messages.send(player, "error");
            return;
        }
        player.openInventory(inventory);
    }

    private void teleport(Player player, String name) throws SQLException {
        Optional<Friend> friend = service.findFriend(player.getUniqueId(), name);
        if (friend.isEmpty()) {
            messages.send(player, "not-friends", "name", name);
            return;
        }
        Player target = getServer().getPlayer(friend.get().id());
        if (target == null || !player.canSee(target)) {
            messages.send(player, "teleport-target-offline");
            return;
        }
        teleports.start(player, target);
    }

    private void report(Player player, Result result, String name, double fee) {
        switch (result) {
            case SELF -> messages.send(player, "cannot-add-self");
            case ALREADY_FRIENDS -> messages.send(player, "already-friends", "name", name);
            case ALREADY_REQUESTED -> messages.send(player, "already-requested", "name", name);
            case NOT_FRIENDS -> messages.send(player, "not-friends", "name", name);
            case LIMIT_REACHED -> messages.send(player, "limit-reached", "max", service.maxFriends());
            case OTHER_LIMIT_REACHED -> messages.send(player, "other-limit-reached", "name", name);
            case NOT_ENOUGH_MONEY -> messages.send(player, "not-enough-money", "fee", feeText(fee));
            case OTHER_NOT_ENOUGH_MONEY -> messages.send(player, "other-not-enough-money", "name", name);
            case NO_REQUEST -> messages.send(player, "no-request", "name", name);
            case CHEST_OPEN -> messages.send(player, "chest-open", "name", name);
            case CHEST_NOT_EMPTY -> messages.send(player, "chest-not-empty", "name", name);
            default -> messages.send(player, "error");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (!(sender instanceof Player player)) {
            return result;
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(prefix)) {
                    result.add(sub);
                }
            }
            return result;
        }
        if (args.length != 2) {
            return result;
        }
        List<String> candidates = new ArrayList<>();
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                for (Player online : getServer().getOnlinePlayers()) {
                    if (!online.equals(player) && player.canSee(online)) {
                        candidates.add(online.getName());
                    }
                }
            }
            case "accept", "deny" -> {
                for (Request request : service.requestsTo(player.getUniqueId())) {
                    candidates.add(request.fromName());
                }
            }
            case "remove", "chest", "tp" -> {
                try {
                    for (Friend friend : service.friends(player.getUniqueId())) {
                        candidates.add(friend.name());
                    }
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "Failed to list friends for tab completion", e);
                }
            }
            default -> {
            }
        }
        String prefix = args[1].toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(candidate);
            }
        }
        return result;
    }
}
