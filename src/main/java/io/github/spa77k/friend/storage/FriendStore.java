package io.github.spa77k.friend.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.inventory.ItemStack;

/**
 * フレンドと共有チェストの中身を SQLite に保存する。
 * フレンドの解消とチェストの書き換えは1つのトランザクションで行い、途中で失敗しても中身が半端に残らないようにする。
 */
public final class FriendStore {

    private final Logger logger;
    private Connection connection;

    public FriendStore(Logger logger) {
        this.logger = logger;
    }

    public void open(File file) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    uuid TEXT PRIMARY KEY,
                    name TEXT NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS friendships (
                    first_uuid TEXT NOT NULL,
                    second_uuid TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY (first_uuid, second_uuid)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS chest_items (
                    first_uuid TEXT NOT NULL,
                    second_uuid TEXT NOT NULL,
                    slot INTEGER NOT NULL,
                    item_data TEXT NOT NULL,
                    PRIMARY KEY (first_uuid, second_uuid, slot)
                )
                """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_friendships_second ON friendships(second_uuid)");
        }
    }

    public void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to close the database", e);
        }
    }

    public void rememberName(UUID id, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO players (uuid, name) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET name = excluded.name")) {
            statement.setString(1, id.toString());
            statement.setString(2, name);
            statement.executeUpdate();
        }
    }

    /** フレンドになった順に返す。 */
    public List<Friend> friends(UUID id) throws SQLException {
        List<Friend> friends = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT f.other, COALESCE(p.name, f.other), f.created_at FROM (
                    SELECT second_uuid AS other, created_at FROM friendships WHERE first_uuid = ?
                    UNION ALL
                    SELECT first_uuid AS other, created_at FROM friendships WHERE second_uuid = ?
                ) f LEFT JOIN players p ON p.uuid = f.other
                ORDER BY f.created_at
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, id.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    friends.add(new Friend(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
                }
            }
        }
        return friends;
    }

    public int count(UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM friendships WHERE first_uuid = ? OR second_uuid = ?")) {
            statement.setString(1, id.toString());
            statement.setString(2, id.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public boolean exists(Pair pair) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM friendships WHERE first_uuid = ? AND second_uuid = ?")) {
            bind(statement, pair);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void add(Pair pair, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO friendships (first_uuid, second_uuid, created_at) VALUES (?, ?, ?)")) {
            bind(statement, pair);
            statement.setLong(3, now);
            statement.executeUpdate();
        }
    }

    /** フレンドを解消し、共有チェストの記録も消す。中身が空かどうかの確認は呼び出し側で済ませる。 */
    public void remove(Pair pair) throws SQLException {
        transaction(() -> {
            for (String table : new String[] {"chest_items", "friendships"}) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE first_uuid = ? AND second_uuid = ?")) {
                    bind(statement, pair);
                    statement.executeUpdate();
                }
            }
            return null;
        });
    }

    /** 1つでも読めないアイテムがあれば例外にする。空のチェストとして開くと、保存で中身を消してしまうため。 */
    public ItemStack[] loadChest(Pair pair, int size) throws SQLException, InvalidConfigurationException {
        ItemStack[] contents = new ItemStack[size];
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT slot, item_data FROM chest_items WHERE first_uuid = ? AND second_uuid = ?")) {
            bind(statement, pair);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    int slot = rs.getInt(1);
                    if (slot < 0 || slot >= size) {
                        throw new InvalidConfigurationException("Slot " + slot + " does not fit in a chest of " + size);
                    }
                    contents[slot] = ItemCodec.decode(rs.getString(2));
                }
            }
        }
        return contents;
    }

    public void saveChest(Pair pair, ItemStack[] contents) throws SQLException {
        transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM chest_items WHERE first_uuid = ? AND second_uuid = ?")) {
                bind(statement, pair);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO chest_items (first_uuid, second_uuid, slot, item_data) VALUES (?, ?, ?, ?)")) {
                for (int slot = 0; slot < contents.length; slot++) {
                    ItemStack item = contents[slot];
                    if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
                        continue;
                    }
                    bind(statement, pair);
                    statement.setInt(3, slot);
                    statement.setString(4, ItemCodec.encode(item));
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    private static void bind(PreparedStatement statement, Pair pair) throws SQLException {
        statement.setString(1, pair.first().toString());
        statement.setString(2, pair.second().toString());
    }

    private interface Work<T> {
        T run() throws SQLException;
    }

    private <T> T transaction(Work<T> work) throws SQLException {
        connection.setAutoCommit(false);
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }
}
