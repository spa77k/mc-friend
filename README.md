# Friend

[日本語](README.ja.md)

A Minecraft server plugin that gives each pair of friends a shared chest and lets friends teleport to each other without a request.

- **Shared Chest**: Each pair of friends gets a dedicated shared chest opened with `/friend chest <player>` from anywhere. Both players see and edit the same contents in real time.
- **Direct Teleport**: Teleport directly to a friend with `/friend tp <player>` without needing them to accept a request.
- **Economy Support (Vault)**: Optionally charges each player a fee when they become friends.

## Requirements

- Spigot or Paper 1.20.4 or later (tested on Paper 1.20.4, Paper 26.2 and Paper 26.3)
- Java 17 or later (whatever your server version needs)
- Optional: [Vault](https://github.com/MilkBowl/Vault) and an economy plugin (such as EssentialsX) to charge a fee

## Installation

1. Download `friend-1.0.1.jar` from [Releases](https://github.com/spa77k/mc-friend/releases).
2. Put it in your server's `plugins/` folder.
3. Restart the server. `plugins/Friend/config.yml` is generated.

## Commands

Base command: `/friend` (alias: `/friends`)

| Command | Description |
| --- | --- |
| `/friend` | Show command help |
| `/friend add <player>` | Send a friend request (target must be online; accepts if a request is already received) |
| `/friend accept [player]` | Accept a friend request (player name can be omitted if there is only one request) |
| `/friend deny [player]` | Deny a friend request (player name can be omitted if there is only one request) |
| `/friend list` | Show your friends (online/offline status) and received friend requests |
| `/friend chest <player>` | Open the chest you share with that friend |
| `/friend tp <player>` | Teleport to that friend |
| `/friend remove <player>` | Remove a friend (shared chest must be empty and closed) |

### Requests and Fees

- Friend requests expire after `request-timeout` seconds and are cleared when the server restarts.
- If someone already sent you a request, sending `/friend add <player>` back accepts it.
- When a request is accepted, both players are charged the configured fee. If either player cannot afford it, the request is not accepted and no money is deducted.
- Removing a friend is free and does not refund the fee.

### Shared Chest

- Each pair of friends shares one chest, accessible from anywhere.
- Both players can open and edit the chest at the same time. Contents are saved on every change and when the chest is closed.
- A friend cannot be removed while the shared chest has items or is currently open, preventing item loss.

### Teleport

- Stand still for `teleport-delay` seconds to teleport. Moving or taking damage cancels the teleport.
- The target friend must be online.

## Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `friend.use` | everyone | Use all `/friend` commands |
| `friend.free` | nobody | Become friends without paying the fee (waives this player's fee only) |

## Configuration

`plugins/Friend/config.yml`

| Key | Default | Description |
| --- | --- | --- |
| `language` | `en` | Message file to use (`messages_<language>.yml`). Bundled: `en`, `ja`. |
| `fee` | `500` | Fee paid by each of the two players when they become friends (`0` makes it free). Requires Vault and an economy plugin (always free without Vault/economy). |
| `max-friends` | `10` | Maximum friends allowed per player. |
| `request-timeout` | `300` | Seconds a friend request stays valid. |
| `chest-rows` | `3` | Rows (1–6) of the shared chest (9–54 slots). If lowered, chests with items in removed rows can no longer be opened. |
| `teleport-delay` | `3` | Seconds to stand still before teleporting (`0` teleports immediately). Moving or taking damage cancels. |

- All messages can be customized in `plugins/Friend/messages_<language>.yml`.
- Data is stored in SQLite database `plugins/Friend/friends.db`.
- Restart the server to apply configuration changes.

## License

[MIT](LICENSE)
