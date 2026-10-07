# Friend

[日本語](README.ja.md)

A Minecraft server plugin that gives each pair of friends a shared chest and lets friends teleport to each other without a request.

- `/friend add <name>` sends a request. You become friends when the other player accepts.
- Each pair of friends has one shared chest, opened from anywhere with `/friend chest <name>`. Both players see the same contents, even at the same time.
- `/friend tp <name>` teleports you to a friend without asking.
- Optionally charges each of the two players a fee through Vault when they become friends.

## Requirements

- Spigot or Paper 1.20.4 or later (tested on Paper 1.20.4, Paper 26.2 and Paper 26.3)
- Java 17 or later (whatever your server version needs)
- Optional: [Vault](https://github.com/MilkBowl/Vault) and an economy plugin (such as EssentialsX) to charge a fee

## Installation

1. Download `friend-<version>.jar` from [Releases](https://github.com/spa77k/mc-friend/releases).
2. Put it in your server's `plugins/` folder.
3. Restart the server. `plugins/Friend/config.yml` is created.

## Commands

| Command | What it does |
| --- | --- |
| `/friend` | Show help |
| `/friend add <name>` | Send a friend request. The player must be online |
| `/friend accept [name]` | Accept a request. The name can be left out when there is only one |
| `/friend deny [name]` | Deny a request |
| `/friend list` | Show your friends (online or not) and your requests |
| `/friend chest <name>` | Open the chest you share with that friend |
| `/friend tp <name>` | Teleport to that friend |
| `/friend remove <name>` | Remove a friend |

### Requests and fees

- Requests expire after `request-timeout` seconds, and are cleared when the server restarts.
- If someone already sent you a request, `/friend add` back accepts it.
- The fee is charged to each player when a request is accepted. If either player cannot pay, nothing happens and no money is taken.
- Removing a friend is free and does not refund the fee.

### Shared chest

- The contents are saved after every change and when the chest is closed.
- A friend cannot be removed while the shared chest has items or is open, so removing a friend never deletes items.

### Teleport

- Stand still for `teleport-delay` seconds to teleport. Moving or taking damage cancels it.
- The friend must be online. World access rules of other plugins still apply.

## Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `friend.use` | everyone | Use all commands |
| `friend.free` | nobody | Become friends without paying (only that player's share) |

## Configuration

`plugins/Friend/config.yml`

| Key | Default | Description |
| --- | --- | --- |
| `language` | `en` | Message file to use (`messages_<language>.yml`). `en` and `ja` are bundled. |
| `fee` | `500` | Paid by each of the two players when they become friends. `0` makes it free. Always free without Vault and an economy plugin. |
| `max-friends` | `10` | Maximum friends per player. |
| `request-timeout` | `300` | Seconds a request stays valid. |
| `chest-rows` | `3` | Rows (1-6) of the shared chest. If you lower it, chests with items in removed rows can no longer be opened. |
| `teleport-delay` | `3` | Seconds to stand still before teleporting. `0` teleports at once. |

All messages can be changed in `plugins/Friend/messages_<language>.yml`.

Restart the server to apply changes.

## Data

Friends and shared chest contents are stored in `plugins/Friend/friends.db` (SQLite). Back it up with your worlds.
Requests, new friendships and removals are logged as `Friend request: ...`, `Friends: ...` and `Friendship removed by ...`.

## Build

```bash
mvn -B package
```

This creates `target/friend-<version>.jar`.

## License

[MIT](LICENSE)
