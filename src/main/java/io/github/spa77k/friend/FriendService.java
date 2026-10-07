package io.github.spa77k.friend;

import io.github.spa77k.friend.storage.Friend;
import io.github.spa77k.friend.storage.FriendStore;
import io.github.spa77k.friend.storage.Pair;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * 申請・承認・解消の手続き。コマンドからもテストからも同じ手続きを使う。
 * 承認では、2人分の支払いと保存のどれかに失敗したら、引いたお金を戻して何も成立させない。
 */
public final class FriendService {

    public enum Result {
        OK, REQUESTED, SELF, ALREADY_FRIENDS, ALREADY_REQUESTED, NOT_FRIENDS, LIMIT_REACHED, OTHER_LIMIT_REACHED,
        NOT_ENOUGH_MONEY, OTHER_NOT_ENOUGH_MONEY, NO_REQUEST, CHEST_OPEN, CHEST_NOT_EMPTY, ERROR
    }

    /** 申請。申請した側の手数料は申請の時点で決める（承認のときオフラインだと権限を確かめられないため）。 */
    public record Request(UUID from, String fromName, double fee, long expiresAt) {
    }

    private final FriendStore store;
    private final SharedChests chests;
    private final Logger logger;
    private final double fee;
    private final int maxFriends;
    private final long requestTimeoutMillis;
    /** 申請された人 → 申請した人 → 申請 */
    private final Map<UUID, Map<UUID, Request>> requests = new HashMap<>();

    public FriendService(FriendStore store, SharedChests chests, Logger logger, double fee, int maxFriends,
                         long requestTimeoutMillis) {
        this.store = store;
        this.chests = chests;
        this.logger = logger;
        this.fee = fee;
        this.maxFriends = maxFriends;
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    public Economy economy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return null;
        }
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : provider.getProvider();
    }

    public double feeFor(Player player) {
        if (fee <= 0 || economy() == null || player.hasPermission("friend.free")) {
            return 0;
        }
        return fee;
    }

    /** 設定上の手数料。案内の表示に使う。 */
    public double fee() {
        return economy() == null ? 0 : Math.max(0, fee);
    }

    public int maxFriends() {
        return maxFriends;
    }

    public void rememberName(UUID id, String name) {
        try {
            store.rememberName(id, name);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to remember the name of " + name, e);
        }
    }

    public List<Friend> friends(UUID id) throws SQLException {
        return store.friends(id);
    }

    public boolean areFriends(UUID a, UUID b) {
        try {
            return !a.equals(b) && store.exists(Pair.of(a, b));
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to check a friendship", e);
            return false;
        }
    }

    /** フレンドを名前で探す。大文字・小文字は区別しない。 */
    public Optional<Friend> findFriend(UUID self, String name) throws SQLException {
        for (Friend friend : store.friends(self)) {
            if (friend.name().equalsIgnoreCase(name)) {
                return Optional.of(friend);
            }
        }
        return Optional.empty();
    }

    /** 期限切れを除いた、自分あての申請。古い順。 */
    public List<Request> requestsTo(UUID target) {
        Map<UUID, Request> pending = requests.get(target);
        if (pending == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        pending.values().removeIf(request -> request.expiresAt() <= now);
        return new ArrayList<>(pending.values());
    }

    public Optional<Request> requestFrom(UUID target, String name) {
        for (Request request : requestsTo(target)) {
            if (request.fromName().equalsIgnoreCase(name)) {
                return Optional.of(request);
            }
        }
        return Optional.empty();
    }

    /**
     * 申請する。相手から先に申請が来ていれば、そのまま承認する（戻り値は OK）。
     * 申請の時点では自分の所持金だけ確かめ、相手の所持金は承認のときに確かめる。
     */
    public Result request(OfflinePlayer from, String fromName, double fromFee, OfflinePlayer to, String toName) {
        UUID fromId = from.getUniqueId();
        UUID toId = to.getUniqueId();
        if (fromId.equals(toId)) {
            return Result.SELF;
        }
        Result check = check(fromId, toId);
        if (check != Result.OK) {
            return check;
        }
        if (requestsTo(fromId).stream().anyMatch(request -> request.from().equals(toId))) {
            return accept(from, fromName, fromFee, toId);
        }
        if (requestsTo(toId).stream().anyMatch(request -> request.from().equals(fromId))) {
            return Result.ALREADY_REQUESTED;
        }
        if (!canPay(from, fromFee)) {
            return Result.NOT_ENOUGH_MONEY;
        }
        requests.computeIfAbsent(toId, key -> new LinkedHashMap<>())
                .put(fromId, new Request(fromId, fromName, fromFee, System.currentTimeMillis() + requestTimeoutMillis));
        logger.info("Friend request: " + fromName + " -> " + toName);
        return Result.REQUESTED;
    }

    /** 申請を承認する。2人ともから手数料を引き、フレンドにする。 */
    public Result accept(OfflinePlayer accepter, String accepterName, double accepterFee, UUID requesterId) {
        UUID accepterId = accepter.getUniqueId();
        Request request = requestsTo(accepterId).stream()
                .filter(found -> found.from().equals(requesterId)).findFirst().orElse(null);
        if (request == null) {
            return Result.NO_REQUEST;
        }
        Result check = check(accepterId, requesterId);
        if (check != Result.OK) {
            if (check == Result.ALREADY_FRIENDS) {
                removeRequest(accepterId, requesterId);
            }
            return check;
        }
        OfflinePlayer requester = Bukkit.getOfflinePlayer(requesterId);
        if (!canPay(accepter, accepterFee)) {
            return Result.NOT_ENOUGH_MONEY;
        }
        if (!canPay(requester, request.fee())) {
            return Result.OTHER_NOT_ENOUGH_MONEY;
        }
        Economy economy = economy();
        if (!withdraw(economy, requester, request.fee())) {
            return Result.OTHER_NOT_ENOUGH_MONEY;
        }
        if (!withdraw(economy, accepter, accepterFee)) {
            deposit(economy, requester, request.fee());
            return Result.NOT_ENOUGH_MONEY;
        }
        try {
            store.add(Pair.of(accepterId, requesterId), System.currentTimeMillis());
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to save the friendship of " + request.fromName() + " and " + accepterName, e);
            deposit(economy, requester, request.fee());
            deposit(economy, accepter, accepterFee);
            return Result.ERROR;
        }
        removeRequest(accepterId, requesterId);
        logger.info("Friends: " + request.fromName() + " and " + accepterName
                + (request.fee() + accepterFee > 0 ? ", fee " + request.fee() + " + " + accepterFee : ""));
        return Result.OK;
    }

    public Result deny(UUID target, UUID requesterId) {
        return removeRequest(target, requesterId) ? Result.OK : Result.NO_REQUEST;
    }

    /** フレンドを解消する。共有チェストが空で、誰も開いていないときだけ解消できる。 */
    public Result remove(UUID self, String selfName, UUID friendId) {
        Pair pair = Pair.of(self, friendId);
        if (!areFriends(self, friendId)) {
            return Result.NOT_FRIENDS;
        }
        if (chests.isViewed(pair)) {
            return Result.CHEST_OPEN;
        }
        if (chests.hasItems(pair)) {
            return Result.CHEST_NOT_EMPTY;
        }
        try {
            store.remove(pair);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to remove a friendship of " + selfName, e);
            return Result.ERROR;
        }
        chests.forget(pair);
        logger.info("Friendship removed by " + selfName + ": " + pair);
        return Result.OK;
    }

    private Result check(UUID a, UUID b) {
        try {
            if (store.exists(Pair.of(a, b))) {
                return Result.ALREADY_FRIENDS;
            }
            if (store.count(a) >= maxFriends) {
                return Result.LIMIT_REACHED;
            }
            if (store.count(b) >= maxFriends) {
                return Result.OTHER_LIMIT_REACHED;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to check friends", e);
            return Result.ERROR;
        }
        return Result.OK;
    }

    private boolean removeRequest(UUID target, UUID requesterId) {
        Map<UUID, Request> pending = requests.get(target);
        return pending != null && pending.remove(requesterId) != null;
    }

    private boolean canPay(OfflinePlayer player, double amount) {
        if (amount <= 0) {
            return true;
        }
        Economy economy = economy();
        return economy != null && economy.has(player, amount);
    }

    private static boolean withdraw(Economy economy, OfflinePlayer player, double amount) {
        return amount <= 0 || (economy != null && economy.withdrawPlayer(player, amount).transactionSuccess());
    }

    private static void deposit(Economy economy, OfflinePlayer player, double amount) {
        if (amount > 0 && economy != null) {
            economy.depositPlayer(player, amount);
        }
    }
}
