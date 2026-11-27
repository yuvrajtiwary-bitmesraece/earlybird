package io.github.yuvrajtiwary.earlybird.sources;

import io.github.yuvrajtiwary.earlybird.sources.BoardValidator.Report;
import io.github.yuvrajtiwary.earlybird.sources.BoardValidator.Status;
import io.github.yuvrajtiwary.earlybird.sources.SeedBoards.SeedBoard;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code java -jar earlybird.jar --validate-boards [--seed=path.csv] [--out=dir]}
 *
 * <p>Checks every seed board without starting Spring, the web server or the database: it only needs the
 * internet. Writes {@code board-validation.csv} (every board's status, job count, India/remote job count)
 * and {@code seed-boards.valid.csv} (the seed list minus 404s) to the output directory (default
 * {@code reports/}, which is gitignored).
 */
public final class ValidateBoardsCommand {

    public static final String FLAG = "--validate-boards";

    private ValidateBoardsCommand() {}

    public static boolean requested(String[] args) {
        return Arrays.asList(args).contains(FLAG);
    }

    public static int run(String[] args) {
        String seed = option(args, "--seed=");
        Path outDir = Path.of(option(args, "--out=") == null ? "reports" : option(args, "--out="));
        try {
            List<SeedBoard> boards;
            try (Reader reader = seed == null
                    ? new InputStreamReader(ValidateBoardsCommand.class.getResourceAsStream("/boards/seed-boards.csv"),
                            StandardCharsets.UTF_8)
                    : Files.newBufferedReader(Path.of(seed))) {
                boards = SeedBoards.parse(reader);
            }
            System.out.printf("Validating %d boards...%n", boards.size());
            var http = new SourceHttp(SourceHttp.userAgent(System.getenv("PUBLIC_BASE_URL")));
            var sources = JobSources.create(http, JsonMapper.builder().build(),
                    URI.create(GreenhouseSource.DEFAULT_BASE_URL), URI.create(LeverSource.DEFAULT_BASE_URL),
                    URI.create(AshbySource.DEFAULT_BASE_URL));
            long start = System.nanoTime();
            List<Report> reports = new BoardValidator(sources).validate(boards);
            long seconds = (System.nanoTime() - start) / 1_000_000_000;

            writeReports(outDir, reports);
            Map<Status, Long> counts = reports.stream()
                    .collect(Collectors.groupingBy(Report::status, Collectors.counting()));
            long valid = reports.stream().filter(Report::validToken).count();
            System.out.printf("Done in %d s: %d valid (%d with jobs, %d empty), %d not found, %d errors.%n",
                    seconds, valid, counts.getOrDefault(Status.OK, 0L), counts.getOrDefault(Status.EMPTY, 0L),
                    counts.getOrDefault(Status.NOT_FOUND, 0L), counts.getOrDefault(Status.ERROR, 0L));
            reports.stream()
                    .filter(r -> !r.validToken())
                    .forEach(r -> System.out.printf("  %-9s %s/%s (%s): %s%n", r.status(), r.board().source(),
                            r.board().token(), r.board().company(), r.message()));
            System.out.println("Reports written to " + outDir.toAbsolutePath());
            return 0;
        } catch (IOException e) {
            System.err.println("validate-boards failed: " + e.getMessage());
            return 1;
        }
    }

    private static void writeReports(Path outDir, List<Report> reports) throws IOException {
        Files.createDirectories(outDir);
        List<String> lines = new ArrayList<>();
        lines.add("source,token,company,status,jobs,india_or_remote_jobs,millis,message");
        reports.stream()
                .sorted(Comparator.comparing((Report r) -> r.status()).thenComparing(r -> r.board().company()))
                .forEach(r -> lines.add(String.join(",", lower(r.board().source()), r.board().token(),
                        r.board().company(), r.status().name(), String.valueOf(r.jobs()),
                        String.valueOf(r.indiaOrRemoteJobs()), String.valueOf(r.millis()),
                        csvSafe(r.message()))));
        Files.write(outDir.resolve("board-validation.csv"), lines, StandardCharsets.UTF_8);

        List<String> valid = new ArrayList<>();
        valid.add("source,token,domain,company");
        reports.stream()
                .filter(r -> r.status() != Status.NOT_FOUND)
                .map(Report::board)
                .sorted(Comparator.comparing((SeedBoard b) -> b.source()).thenComparing(b -> b.company().toLowerCase()))
                .forEach(b -> valid.add(lower(b.source()) + "," + b.token() + "," + (b.domain() == null ? "" : b.domain())
                        + "," + b.company()));
        Files.write(outDir.resolve("seed-boards.valid.csv"), valid, StandardCharsets.UTF_8);
    }

    private static String lower(Enum<?> e) {
        return e.name().toLowerCase();
    }

    private static String csvSafe(String s) {
        return s == null ? "" : s.replace(',', ';').replace('\n', ' ');
    }

    private static String option(String[] args, String prefix) {
        return Arrays.stream(args).filter(a -> a.startsWith(prefix)).map(a -> a.substring(prefix.length()))
                .findFirst().orElse(null);
    }
}
