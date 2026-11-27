package io.github.yuvrajtiwary.earlybird.ops;

import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties;
import io.github.yuvrajtiwary.earlybird.config.Role;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /internal/tick}, {@code /internal/digest} and {@code /internal/maintenance}, called by QStash on its
 * schedules (spec 3.3). Every call must carry a valid QStash signature for exactly this URL, or it gets 401. A
 * valid call takes the job's lock and gets {@code 202} at once, "started" or "skipped" (a run is still going):
 * QStash waits only seconds, and a free Render instance on 0.1 CPU can't finish a tick that fast.
 */
@RestController
@ConditionalOnRole(Role.SCHEDULER)
class InternalController {

    private static final Logger log = LoggerFactory.getLogger(InternalController.class);

    private final QStashVerifier verifier;
    private final InternalJobs jobs;
    private final String baseUrl;

    InternalController(QStashVerifier verifier, InternalJobs jobs, EarlyBirdProperties properties) {
        this.verifier = verifier;
        this.jobs = jobs;
        this.baseUrl = properties.publicBaseUrl().toString().replaceAll("/+$", "");
    }

    @PostMapping("/internal/{job}")
    ResponseEntity<Map<String, String>> run(@PathVariable String job,
            @RequestHeader(name = "Upstash-Signature", required = false) String signature,
            @RequestBody(required = false) byte[] body) {
        Optional<InternalJobs.Job> found = InternalJobs.Job.fromPath(job);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            verifier.verify(signature, body == null ? new byte[0] : body, baseUrl + "/internal/" + job);
        } catch (QStashVerifier.Rejected e) {
            log.warn("Refused /internal/{}: {}", job, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid signature"));
        }
        return switch (jobs.start(found.get())) {
            case STARTED -> ResponseEntity.accepted().body(Map.of("status", "started"));
            case SKIPPED -> ResponseEntity.accepted().body(Map.of("status", "skipped"));
            case NOT_HERE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "this process doesn't run " + job));
        };
    }
}
