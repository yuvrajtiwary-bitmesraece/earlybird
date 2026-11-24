package io.github.yuvrajtiwary.earlybird.telegram;

import io.github.yuvrajtiwary.earlybird.persistence.Profile;
import io.github.yuvrajtiwary.earlybird.persistence.User;
import io.github.yuvrajtiwary.earlybird.profiles.ProfileService;
import io.github.yuvrajtiwary.earlybird.profiles.ResumeAnalyzer;
import io.github.yuvrajtiwary.earlybird.profiles.ResumeAnalyzer.ResumeException;
import io.github.yuvrajtiwary.earlybird.profiles.ResumeFacts;
import io.github.yuvrajtiwary.earlybird.telegram.Session.State;
import io.github.yuvrajtiwary.earlybird.telegram.api.Document;
import io.github.yuvrajtiwary.earlybird.telegram.api.Message;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramApi;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resume upload (spec 1.1 journey 2 and section 3.8). A PDF is accepted only right after the consent text
 * was shown: onboarding step 5, or /upload. The file is downloaded into memory, hashed, read, and dropped;
 * only the extracted facts and the hash are saved. Logs carry the user id only, never resume content.
 */
class ResumeUpload {

    private static final Logger log = LoggerFactory.getLogger(ResumeUpload.class);

    static final long MAX_BYTES = 5L * 1024 * 1024;

    private final TelegramApi api;
    private final SessionStore sessions;
    private final ProfileService profiles;
    private final ResumeAnalyzer analyzer;
    private final ProfileFlows flows;

    ResumeUpload(TelegramApi api, SessionStore sessions, ProfileService profiles, ResumeAnalyzer analyzer,
            ProfileFlows flows) {
        this.api = api;
        this.sessions = sessions;
        this.profiles = profiles;
        this.analyzer = analyzer;
        this.flows = flows;
    }

    /** /upload: show the consent text and wait for a PDF. */
    void prompt(User user, long chatId) {
        sessions.save(user.id(), Session.idle().with(State.AWAITING_RESUME));
        api.sendMessage(chatId, Texts.uploadPrompt(), Keyboards.cancelUpload());
    }

    void onDocument(User user, Message message) {
        long chatId = message.chat().id();
        Session session = sessions.load(user.id());
        if (session.state() != State.ONBOARDING_RESUME && session.state() != State.AWAITING_RESUME) {
            api.sendMessage(chatId, Texts.sendPdfFirst(), null);
            return;
        }
        Document doc = message.document();
        boolean pdf = "application/pdf".equals(doc.mimeType())
                || (doc.fileName() != null && doc.fileName().toLowerCase(Locale.ROOT).endsWith(".pdf"));
        if (!pdf) {
            api.sendMessage(chatId, "Please send your resume as a <b>PDF</b> file.", null);
            return;
        }
        if (doc.fileSize() != null && doc.fileSize() > MAX_BYTES) {
            api.sendMessage(chatId, "That file is over 5 MB. Please send a smaller PDF.", null);
            return;
        }
        api.sendMessage(chatId, "📄 Reading your resume…", null);

        byte[] bytes;
        try {
            bytes = api.downloadFile(api.getFile(doc.fileId()).filePath(), MAX_BYTES);
        } catch (TelegramException e) {
            log.warn("Resume download failed for user {}: {}", user.id(), e.getMessage());
            api.sendMessage(chatId, "I couldn't download that file from Telegram. Please send it again.", null);
            return;
        }
        String sha256 = sha256(bytes);
        Profile profile = profiles.find(user.id()).orElseThrow();

        if (sha256.equals(profile.resumeSha256())) {
            api.sendMessage(chatId, Texts.sameResume(profile), null); // same file: no second Gemini call
        } else {
            ResumeFacts facts;
            try {
                facts = analyzer.analyze(bytes);
            } catch (ResumeException e) {
                log.info("Resume not read for user {}: {}", user.id(), e.reason());
                api.sendMessage(chatId, Texts.resumeProblem(e.reason()), null);
                return; // stay in this step: they can send another file or skip
            }
            profiles.applyResume(user.id(), facts, sha256);
            log.info("Resume read for user {}: {} skills", user.id(), facts.skills().size());
            api.sendMessage(chatId, Texts.resumeRead(facts, profile.yearsExperience()), null);
        }

        if (session.state() == State.ONBOARDING_RESUME) {
            flows.finish(user, chatId); // sends recent matches too
        } else {
            sessions.save(user.id(), Session.idle());
            flows.sendRecentMatches(user); // new skills can mean new matches
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
