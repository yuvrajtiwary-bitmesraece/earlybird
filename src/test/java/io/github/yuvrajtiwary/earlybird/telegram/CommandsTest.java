package io.github.yuvrajtiwary.earlybird.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yuvrajtiwary.earlybird.telegram.Commands.Command;
import io.github.yuvrajtiwary.earlybird.telegram.Commands.SkillsRequest.Action;
import org.junit.jupiter.api.Test;

class CommandsTest {

    @Test
    void parsesCommandsWithAndWithoutArgumentsAndBotSuffix() {
        assertThat(Commands.parse("/start")).isEqualTo(new Command("start", ""));
        assertThat(Commands.parse("/Skills add Kafka, Docker")).isEqualTo(new Command("skills", "add Kafka, Docker"));
        assertThat(Commands.parse("/prefs@EarlyBirdJobsBot")).isEqualTo(new Command("prefs", ""));
        assertThat(Commands.parse("  /help  ")).isNull(); // must start with "/"
        assertThat(Commands.parse("hello")).isNull();
        assertThat(Commands.parse("/")).isNull();
        assertThat(Commands.parse(null)).isNull();
    }

    @Test
    void parsesSkillEdits() {
        var add = Commands.parseSkills("add Kafka, Docker ,  Spring Boot");
        assertThat(add.action()).isEqualTo(Action.ADD);
        assertThat(add.skills()).containsExactly("Kafka", "Docker", "Spring Boot");

        var remove = Commands.parseSkills("remove PHP");
        assertThat(remove.action()).isEqualTo(Action.REMOVE);
        assertThat(remove.skills()).containsExactly("PHP");

        assertThat(Commands.parseSkills("rm PHP; Perl").skills()).containsExactly("PHP", "Perl");
        assertThat(Commands.parseSkills("").action()).isEqualTo(Action.LIST);
        assertThat(Commands.parseSkills("add").action()).as("nothing to add").isEqualTo(Action.LIST);
        assertThat(Commands.parseSkills("frobnicate x").action()).isEqualTo(Action.LIST);
    }

    @Test
    void theMenuHidesAdminCommands() {
        assertThat(Commands.MENU).extracting(c -> c.command())
                .contains("start", "prefs", "skills", "upload", "pause", "unpause", "stats", "delete", "help", "cancel")
                .doesNotContain("admin", "admin_tick");
    }
}
