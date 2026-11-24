package io.github.yuvrajtiwary.earlybird.profiles;

import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties;
import io.github.yuvrajtiwary.earlybird.config.Role;
import io.github.yuvrajtiwary.earlybird.enrich.GeminiClient;
import io.github.yuvrajtiwary.earlybird.enrich.SkillVocabulary;
import io.github.yuvrajtiwary.earlybird.persistence.ProfileRepository;
import java.time.Clock;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

@Configuration(proxyBeanMethods = false)
@ConditionalOnRole(Role.BOT)
class ProfilesConfig {

    private static final Logger log = LoggerFactory.getLogger(ProfilesConfig.class);

    @Bean
    ProfileService profileService(ProfileRepository profiles, SkillVocabulary vocabulary, Clock clock,
            EarlyBirdProperties properties) {
        return new ProfileService(profiles, vocabulary, clock, properties.alerts());
    }

    @Bean
    ResumeAnalyzer resumeAnalyzer(GeminiClient gemini, SkillVocabulary vocabulary, Clock clock) {
        return new ResumeAnalyzer(new ResumeParser(gemini, vocabulary, clock));
    }

    /**
     * PDFBox's first use loads fonts and many classes, which is slow on 0.1 CPU. Do it once in the background
     * at startup so the first real resume isn't the one that pays for it.
     */
    @EventListener(ApplicationReadyEvent.class)
    void warmUpPdfBox() {
        Thread.ofVirtual().name("pdfbox-warmup").start(() -> {
            long start = System.nanoTime();
            try (var doc = new PDDocument(); var out = new java.io.ByteArrayOutputStream()) {
                doc.addPage(new PDPage());
                doc.save(out);
                PdfText.extract(out.toByteArray(), 1, 100);
                log.info("PDFBox warmed up in {} ms", (System.nanoTime() - start) / 1_000_000);
            } catch (Exception e) {
                log.warn("PDFBox warm-up failed: {}", e.toString());
            }
        });
    }
}
