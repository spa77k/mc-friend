package io.github.spa77k.friend.storage;

import java.util.UUID;

/** 一覧に出すフレンド。name は最後に見た名前。 */
public record Friend(UUID id, String name, long since) {
}
