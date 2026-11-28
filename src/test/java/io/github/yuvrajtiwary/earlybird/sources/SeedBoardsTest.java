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

    private static final String HEADER = "source,token,domain,company\n";

    private static List<SeedBoard> parse(String csv) throws Exception {
        return SeedBoards.parse(new StringReader(csv));
    }

    @Test
    void parsesBoardsSkippingCommentsAndBlankLines() throws Exception {
        List<SeedBoard> boards = parse("""
                # comment
                source,token,domain,company

                greenhouse,stripe,stripe.com,Stripe
                lever,cred,,CRED
                ashby,notion,notion.com,Notion, Inc.
                """);

        assertThat(boards).containsExactly(
                new SeedBoard(Source.GREENHOUSE, "stripe", "stripe.com", "Stripe"),
                new SeedBoard(Source.LEVER, "cred", null, "CRED"), // the domain may be left empty
                new SeedBoard(Source.ASHBY, "notion", "notion.com", "Notion, Inc.")); // commas allowed in the last column
    }

    @Test
    void rejectsBadLinesWithTheirLineNumber() {
        assertThatThrownBy(() -> parse("greenhouse,stripe,stripe.com,Stripe\n")).hasMessageContaining("header");
        assertThatThrownBy(() -> parse("source,token,company\ngreenhouse,stripe,Stripe\n")).hasMessageContaining("header");
        assertThatThrownBy(() -> parse(HEADER + "workday,x,x.com,X\n"))
                .hasMessageContaining("line 2").hasMessageContaining("unknown source");
        assertThatThrownBy(() -> parse(HEADER + "greenhouse,../etc,x.com,X\n")).hasMessageContaining("invalid token");
        assertThatThrownBy(() -> parse(HEADER + "greenhouse,x,x.com,\n")).hasMessageContaining("company is empty");
        assertThatThrownBy(() -> parse(HEADER + "greenhouse,x,X\n")).hasMessageContaining("line 2");
        assertThatThrownBy(() -> parse(HEADER + "lever,cred,a.com,A\nlever,CRED,b.com,B\n"))
                .hasMessageContaining("duplicate");
    }

    /** The domain becomes part of a logo URL, so only a bare lower-case host name is accepted. */
    @Test
    void rejectsAnythingButABareDomain() {
        for (String bad : List.of("https://x.com", "X.com", "x", "x.com/careers", "-x.com", "x..com", "x.com?a=b")) {
            assertThatThrownBy(() -> parse(HEADER + "greenhouse,x," + bad + ",X\n"))
                    .as(bad).hasMessageContaining("invalid domain");
        }
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
        // Every seed company has a website, so every alert can carry its logo (issue #10).
        assertThat(boards).allSatisfy(b -> assertThat(b.domain()).as(b.company()).isNotNull());
    }
}
