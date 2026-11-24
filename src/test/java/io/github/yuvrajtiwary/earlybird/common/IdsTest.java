package io.github.yuvrajtiwary.earlybird.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void createsVersion7Uuids() {
        UUID id = Ids.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2); // RFC 9562 variant
    }

    @Test
    void embedsTheCreationTimeInTheFirst48Bits() {
        long before = System.currentTimeMillis();
        UUID id = Ids.newId();
        long after = System.currentTimeMillis();
        long embeddedMillis = id.getMostSignificantBits() >>> 16;
        assertThat(embeddedMillis).isBetween(before, after);
    }

    @Test
    void idsSortInCreationOrderEvenWithinOneMillisecond() {
        // String order equals byte order, which is how Postgres sorts uuid values.
        List<String> ids = IntStream.range(0, 10_000).mapToObj(i -> Ids.newId().toString()).toList();
        assertThat(ids).isSorted().doesNotHaveDuplicates();
    }
}
