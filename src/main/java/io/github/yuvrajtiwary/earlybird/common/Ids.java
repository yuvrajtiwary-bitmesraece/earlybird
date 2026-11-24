package io.github.yuvrajtiwary.earlybird.common;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import java.util.UUID;

/**
 * UUID v7 ids: the first 48 bits are the Unix time in milliseconds, so ids sort by creation time.
 * That keeps B-tree index inserts at the right-hand edge (like a sequence) while ids can still be
 * created anywhere without asking the database. Postgres 17 has no built-in v7 generator (18 does).
 */
public final class Ids {

    // Thread-safe, and monotonic within the same millisecond.
    private static final TimeBasedEpochGenerator V7 = Generators.timeBasedEpochGenerator();

    private Ids() {}

    public static UUID newId() {
        return V7.generate();
    }
}
