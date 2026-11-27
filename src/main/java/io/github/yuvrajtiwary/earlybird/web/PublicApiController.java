package io.github.yuvrajtiwary.earlybird.web;

import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.Role;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The same data as the pages, as JSON: read-only, and open to any website (CORS), spec 3.12. */
@RestController
@ConditionalOnRole(Role.WEB)
@RequestMapping("/api/public")
@CrossOrigin(origins = "*", methods = org.springframework.web.bind.annotation.RequestMethod.GET)
class PublicApiController {

    private final PublicData data;

    PublicApiController(PublicData data) {
        this.data = data;
    }

    @GetMapping("/stats")
    Map<String, Object> stats() {
        PublicData.Home home = data.home();
        PublicData.Stats stats = data.stats();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("boardsChecked", home.boards());
        out.put("postingsLast24h", home.postings24h());
        out.put("alertsLast7d", home.alerts7d());
        out.put("deliveryP50", stats.deliveryP50());
        out.put("deliveryP95", stats.deliveryP95());
        out.put("publishedToAlertP50", stats.publishedP50());
        out.put("publishedToAlertP95", stats.publishedP95());
        out.put("alertRelevance", stats.relevancePercent());
        out.put("postingsPerDay", stats.postings().bars().stream().map(b -> Map.of("day", b.day(), "count", b.value())).toList());
        out.put("alertsPerDay", stats.alerts().bars().stream().map(b -> Map.of("day", b.day(), "count", b.value())).toList());
        out.put("topCompaniesThisWeek", stats.topCompanies());
        out.put("topFresherSkillsThisWeek", stats.topSkills());
        return out;
    }

    @GetMapping("/postings")
    List<Map<String, Object>> postings(@RequestParam(required = false) String role) {
        return data.live(Format.role(role).orElse(null)).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("company", r.company());
            m.put("title", r.title());
            m.put("location", r.location());
            m.put("role", r.role());
            m.put("tags", r.tags());
            m.put("firstSeenAt", r.firstSeenAt());
            m.put("url", r.url());
            return m;
        }).toList();
    }
}
