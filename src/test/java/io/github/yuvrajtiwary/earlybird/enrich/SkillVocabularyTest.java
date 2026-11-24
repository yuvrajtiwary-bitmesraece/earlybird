package io.github.yuvrajtiwary.earlybird.enrich;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SkillVocabularyTest {

    private static final SkillVocabulary VOCAB = SkillVocabulary.fromClasspath();

    @Test
    void theRealFileLoadsWithoutConflictsAndHasAround200Skills() {
        assertThat(VOCAB.canonicalNames()).hasSizeBetween(180, 320).contains("Java", "Spring Boot", "Kubernetes");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "k8s | Kubernetes",
        "K8S | Kubernetes",
        "springboot | Spring Boot",
        "spring-boot | Spring Boot",
        "JS | JavaScript",
        "golang | Go",
        "node.js | Node.js",
        "Node JS | Node.js",
        "postgres | PostgreSQL",
        "Python 3.11 | Python",
        "Java 17 | Java",
        "Angular 15 | Angular",
        "DSA | Data Structures and Algorithms",
        "c++ | C++",
        "C# | C#",
        "ci/cd | CI/CD",
        "aws s3 | Amazon S3",
    })
    void mapsAliasesToCanonicalNames(String raw, String canonical) {
        assertThat(VOCAB.canonical(raw)).contains(canonical);
    }

    @Test
    void normalizeDropsSoftSkillsAndDuplicatesAndKeepsUnknownRealSkills() {
        List<String> out = VOCAB.normalize(List.of("k8s", "Kubernetes", "Communication", "teamwork", "Apache Pinot",
                "  springboot ", "", "a", "x".repeat(60), "Java"), 40);

        assertThat(out).containsExactly("Kubernetes", "Apache Pinot", "Spring Boot", "Java");
    }

    @Test
    void normalizeRespectsTheLimit() {
        assertThat(VOCAB.normalize(List.of("Java", "Go", "Rust", "Python"), 2)).containsExactly("Java", "Go");
    }

    @Test
    void anAliasListedForTwoSkillsIsRejected() {
        String yaml = "Java: [jv]\nJavaScript: [jv]\n";

        assertThatThrownBy(() -> SkillVocabulary.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("jv");
    }
}
