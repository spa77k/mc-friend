package io.github.spa77k.friend.storage;

import java.util.UUID;

/** フレンド1組。どちらから作っても同じ並びになるよう、UUID の文字列の小さい方を first にする。 */
public record Pair(UUID first, UUID second) {

    public static Pair of(UUID a, UUID b) {
        return a.toString().compareTo(b.toString()) <= 0 ? new Pair(a, b) : new Pair(b, a);
    }

    public UUID other(UUID self) {
        return first.equals(self) ? second : first;
    }
}
