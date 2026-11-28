package io.github.yuvrajtiwary.earlybird.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import io.github.yuvrajtiwary.earlybird.profiles.LocationChoice;
import io.github.yuvrajtiwary.earlybird.telegram.api.InlineKeyboardButton;
import io.github.yuvrajtiwary.earlybird.telegram.api.InlineKeyboardMarkup;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeyboardsTest {

    private static List<InlineKeyboardButton> buttons(InlineKeyboardMarkup keyboard) {
        return keyboard.inlineKeyboard().stream().flatMap(List::stream).toList();
    }

    @Test
    void everyButtonsCallbackDataFitsTelegramsLimit() {
        for (InlineKeyboardMarkup keyboard : Keyboards.all()) {
            for (InlineKeyboardButton button : buttons(keyboard)) {
                assertThat(button.callbackData().getBytes(StandardCharsets.UTF_8))
                        .as(button.text()).hasSizeLessThanOrEqualTo(64);
            }
        }
    }

    @Test
    void selectedChoicesAreTicked() {
        var roles = buttons(Keyboards.roles(List.of(RoleCategory.BACKEND)));
        assertThat(roles).filteredOn(b -> b.callbackData().equals("r:backend"))
                .singleElement().extracting(InlineKeyboardButton::text).isEqualTo("✓ Backend");
        assertThat(roles).filteredOn(b -> b.callbackData().equals("r:frontend"))
                .singleElement().extracting(InlineKeyboardButton::text).isEqualTo("Frontend");
        assertThat(roles).extracting(InlineKeyboardButton::callbackData).contains("r:done").doesNotContain("r:other");

        var locations = buttons(Keyboards.locations(List.of(LocationChoice.REMOTE_INDIA)));
        assertThat(locations).filteredOn(b -> b.callbackData().equals("l:rin"))
                .singleElement().extracting(InlineKeyboardButton::text).isEqualTo("✓ Remote (India)");
    }

    @Test
    void offersExactlyTheSpecsChoices() {
        assertThat(buttons(Keyboards.roles(List.of()))).hasSize(10 + 1);             // 10 roles + Done
        assertThat(buttons(Keyboards.locations(List.of()))).hasSize(10 + 1);         // 8 cities, 2 remote + Done
        assertThat(buttons(Keyboards.experience())).extracting(InlineKeyboardButton::callbackData)
                .containsExactly("x:0", "x:0.5", "x:1.5", "x:2.5");
    }
}
