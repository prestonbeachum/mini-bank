package dev.minibank.common;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

/** App-generated, time-ordered ids (UUIDv7), so primary-key inserts stay index-friendly. */
public final class Ids {

    private Ids() {}

    public static UUID newId() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
