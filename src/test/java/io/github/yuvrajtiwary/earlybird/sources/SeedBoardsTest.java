package io.github.yuvrajtiwary.earlybird.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yuvrajtiwary.earlybird.persistence.Source;
import io.github.yuvrajtiwary.earlybird.sources.SeedBoards.SeedBoard;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SeedBoardsTest {

    private static List<SeedBoard> parse(String csv) throws Exception {
        return SeedBoards.parse(new StringReader(csv));
    }

    @Test
    void parsesBoardsSkippingCommentsAndBlankLines() throws Exception {
        List<SeedBoard> boards = parse("""
                # comment
                source,token,company

                greenhouse,stripe,Stripe
                lever,cred,CRED
                ashby,notion,Notion, Inc.
                """);

        assertThat(boards).containsExactly(
                new SeedBoard(Source.GREENHOUSE, "stripe", "Stripe"),
                new SeedBoard(Source.LEVER, "cred", "CRED"),
                new SeedBoard(Source.ASHBY, "notion", "Notion, Inc.")); // commas allowed in the last column
    }

    @Test
    void rejectsBadLinesWithTheirLineNumber() {
        assertThatThrownBy(() -> parse("greenhouse,stripe,Stripe\n")).hasMessageContaining("header");
        assertThatThrownBy(() -> parse("source,token,company\nworkday,x,X\n"))
                .hasMessageContaining("line 2").hasMessageContaining("unknown source");
        assertThatThrownBy(() -> parse("source,token,company\ngreenhouse,../etc,X\n")).hasMessageContaining("invalid token");
        assertThatThrownBy(() -> parse("source,token,company\ngreenhouse,x,\n")).hasMessageContaining("company is empty");
        assertThatThrownBy(() -> parse("source,token,company\ngreenhouse,x\n")).hasMessageContaining("line 2");
        assertThatThrownBy(() -> parse("source,token,company\nlever,cred,A\nlever,CRED,B\n"))
                .hasMessageContaining("duplicate");
    }

    /** The real seed list ships in the jar; this keeps it loadable and big enough. */
    @Test
    void theRealSeedListIsValid() throws Exception {
        List<SeedBoard> boards;
        try (var reader = new InputStreamReader(
                SeedBoardsTest.class.getResourceAsStream("/boards/seed-boards.csv"), StandardCharsets.UTF_8)) {
            boards = SeedBoards.parse(reader);
        }
        Map<Source, Long> bySource = boards.stream().collect(Collectors.groupingBy(SeedBoard::source, Collectors.counting()));

        assertThat(boards).hasSizeGreaterThanOrEqualTo(120);
        assertThat(bySource).containsOnlyKeys(Source.values());
    }
}
