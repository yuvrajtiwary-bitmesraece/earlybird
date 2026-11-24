package io.github.yuvrajtiwary.earlybird.profiles;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yuvrajtiwary.earlybird.support.TestPdfs;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** Contact details must never reach Gemini; technical text must survive. */
class ContactStripperTest {

    /** Each line is preceded by a header so the "name on the first line" rule doesn't interfere. */
    private static String strip(String line) {
        return ContactStripper.strip("SKILLS\n" + line).replaceFirst("^SKILLS\\n?", "");
    }

    @ParameterizedTest(name = "removes: {0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "asha.kumari@example.com",
        "Email: asha@example.co.in",
        "+91 98765 43210",
        "+91-9876543210",
        "098765-43210",
        "9876543210",
        "+1 (415) 555-0100",
        "080-2345 6789",
        "https://asha.dev/blog",
        "www.ashakumari.in",
        "linkedin.com/in/asha-kumari-dev",
        "github.com/ashak",
        "Portfolio: asha.vercel.app",
        "Flat 12, Green Apartments, MG Road, Bengaluru 560001",
        "Address: 14 Park Street",
        "Sector 15, Noida, Uttar Pradesh 201301",
        "Mumbai 400001",
        "House No. 7, Near City Mall, Main Road",
    })
    void removesContactDetails(String line) {
        assertThat(strip(line)).isBlank();
    }

    @ParameterizedTest(name = "keeps: {0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "B.Tech in Computer Science, 2021 - 2025, CGPA 8.4",
        "Worked here 2019 2020 2021",
        "Built APIs with Node.js/Express and ASP.NET Core",
        "Grew the app to 100000 users with Redis caching",
        "Java 17, Spring Boot 3, PostgreSQL 16",
        "Bengaluru, India",
        "Main thread optimisation cut latency 40%",
        "Socket.io real-time chat",
    })
    void keepsTechnicalText(String line) {
        assertThat(strip(line)).isEqualTo(line);
    }

    @Test
    void removesOnlyTheContactPartOfAMixedLine() {
        assertThat(strip("Java developer | asha@example.com | +91 98765 43210")).isEqualTo("Java developer | |");
    }

    @Test
    void dropsANameLikeFirstLine() {
        assertThat(ContactStripper.strip("Asha Kumari\nSKILLS\nJava")).isEqualTo("SKILLS\nJava");
        assertThat(ContactStripper.strip("ASHA KUMARI\nJava")).isEqualTo("Java");
        assertThat(ContactStripper.strip("Dr. A. P. J. Kalam\nJava")).isEqualTo("Java");
    }

    @Test
    void keepsAFirstLineThatIsNotAName() {
        assertThat(ContactStripper.strip("SKILLS\nJava")).isEqualTo("SKILLS\nJava");
        assertThat(ContactStripper.strip("Software engineer with 2 years in fintech\nJava"))
                .startsWith("Software engineer with 2 years");
    }

    @Test
    void aWholeFakeResumeKeepsItsSkillsAndLosesItsContactDetails() {
        String clean = ContactStripper.strip(String.join("\n", TestPdfs.FAKE_RESUME));

        assertThat(clean).doesNotContain("Asha", "Kumari", "@", "98765", "43210", "linkedin", "github.com",
                "Green Apartments", "560001");
        assertThat(clean).contains("B.Tech in Computer Science", "2021 - 2025", "CGPA 8.4", "Spring Boot", "Kafka",
                "springboot", "SKILLS");
    }
}
