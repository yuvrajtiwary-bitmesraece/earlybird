package io.github.yuvrajtiwary.earlybird.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CitiesAndBase62Test {

    @Test
    void citiesHaveOneNameEach() {
        assertThat(Cities.canonical("Bangalore")).contains("Bengaluru");
        assertThat(Cities.canonical(" GURGAON ")).contains("Gurugram");
        assertThat(Cities.canonical("New Delhi")).contains("Delhi");
        assertThat(Cities.canonical("London")).isEmpty();
        assertThat(Cities.normalize("London")).isEqualTo("London");
    }

    @Test
    void findsCitiesInText() {
        assertThat(Cities.findIn("Hybrid in Navi Mumbai or Bangalore; Bengaluru preferred"))
                .containsExactly("Navi Mumbai", "Bengaluru");
        assertThat(Cities.findIn("Remote - US")).isEmpty();
    }

    @Test
    void base62RoundTripsUuidsInAtMost22Characters() {
        for (int i = 0; i < 1000; i++) {
            UUID id = Ids.newId();
            String encoded = Base62.encode(id);
            assertThat(encoded).hasSizeLessThanOrEqualTo(22).matches("[0-9A-Za-z]+");
            assertThat(Base62.decode(encoded)).isEqualTo(id);
        }
        UUID max = new UUID(-1L, -1L);
        assertThat(Base62.decode(Base62.encode(max))).isEqualTo(max);
        assertThat(("fb:n:" + Base62.encode(max)).length()).isLessThanOrEqualTo(64); // Telegram's callback limit
    }

    @Test
    void base62RejectsGarbage() {
        assertThatThrownBy(() -> Base62.decode("not-base62!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62.decode("z".repeat(40))).isInstanceOf(IllegalArgumentException.class);
    }
}
