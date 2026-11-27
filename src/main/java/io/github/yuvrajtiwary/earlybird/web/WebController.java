package io.github.yuvrajtiwary.earlybird.web;

import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties;
import io.github.yuvrajtiwary.earlybird.config.Role;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

/** The pages (spec 3.12). Server-rendered; Thymeleaf escapes every value, so posting text can't inject HTML. */
@Controller
@ConditionalOnRole(Role.WEB)
class WebController {

    static final String GITHUB = "https://github.com/yuvrajtiwary-bitmesraece/earlybird";

    private final PublicData data;
    private final String botUsername;

    WebController(PublicData data, EarlyBirdProperties properties) {
        this.data = data;
        this.botUsername = properties.telegram().botUsername();
    }

    @ModelAttribute
    void common(Model model) {
        model.addAttribute("telegramUrl", "https://t.me/" + botUsername);
        model.addAttribute("githubUrl", GITHUB);
    }

    @GetMapping("/")
    String home(Model model) {
        model.addAttribute("home", data.home());
        return "index";
    }

    @GetMapping("/live")
    String live(@RequestParam(required = false) String role, Model model) {
        String selected = Format.role(role).orElse(null);
        model.addAttribute("rows", data.live(selected));
        model.addAttribute("roles", Format.ROLES);
        model.addAttribute("selected", selected);
        return "live";
    }

    @GetMapping("/stats")
    String stats(Model model) {
        model.addAttribute("stats", data.stats());
        return "stats";
    }

    @GetMapping("/privacy")
    String privacy() {
        return "privacy";
    }

    @GetMapping("/status")
    String status(Model model) {
        model.addAttribute("status", data.status());
        return "status";
    }
}
