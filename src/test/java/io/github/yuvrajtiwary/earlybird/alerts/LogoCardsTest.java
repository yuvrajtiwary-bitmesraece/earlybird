package io.github.yuvrajtiwary.earlybird.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yuvrajtiwary.earlybird.sources.SeedBoards;
import io.github.yuvrajtiwary.earlybird.sources.SeedBoards.SeedBoard;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Issue #10: every seed company has a logo card the alert can show, made by scripts/logos/LogoCards.java. */
class LogoCardsTest {

    @Test
    void everySeedCompanyHasAFullSizeCard() throws Exception {
        List<SeedBoard> boards;
        try (var reader = new InputStreamReader(
                LogoCardsTest.class.getResourceAsStream("/boards/seed-boards.csv"), StandardCharsets.UTF_8)) {
            boards = SeedBoards.parse(reader);
        }

        assertThat(boards).allSatisfy(board -> {
            try (InputStream card = LogoCardsTest.class.getResourceAsStream("/static/logos/" + board.domain() + ".png")) {
                assertThat(card).as("card for %s (run scripts/logos/LogoCards.java)", board.domain()).isNotNull();
                BufferedImage image = ImageIO.read(card);
                assertThat(image.getWidth()).isEqualTo(1200);
                assertThat(image.getHeight()).isEqualTo(600);
            }
        });
    }
}
