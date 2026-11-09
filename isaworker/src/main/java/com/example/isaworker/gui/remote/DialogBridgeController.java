package com.example.isaworker.gui.remote;

import com.example.isaworker.config.GuiProperties;
import com.example.isaworker.gui.remote.DialogBroker.Request;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;

/**
 * The HTTP side of the dialog bridge: what the dialog agent on the desktop talks to. The container publishes this port on
 * the host's loopback address only, and every call must carry the shared secret ({@code Authorization: Bearer <token>}).
 * <pre>
 *   GET  /api/dialogs/next?wait=20         200 + the next dialog, or 204 when none came within the wait
 *   POST /api/dialogs/{id}/received        the agent has the dialog (sent at once; else it is offered again after 5 s)
 *   POST /api/dialogs/{id}/answer          body {"text": "..."}  the person answered a question
 *   POST /api/dialogs/{id}/cancel          the person cancelled or closed a question
 *   POST /api/dialogs/{id}/ack             the person closed a read-only message
 * </pre>
 * A call about a dialog that is gone (its worker gave up) answers 404.
 */
@Slf4j
@RestController
@RequestMapping("/api/dialogs")
@ConditionalOnProperty(prefix = "isaworker.gui", name = "mode", havingValue = "remote")
public class DialogBridgeController {

    /** What the agent receives. */
    public record DialogDto(String id, String kind, String title, String prompt, String text) {
        static DialogDto of(Request request) {
            return new DialogDto(request.id(), request.kind().name(), request.title(), request.prompt(), request.text());
        }
    }

    /** What the agent sends for an answered question. */
    public record AnswerDto(String text) {
    }

    private final DialogBroker broker;
    private final GuiProperties.Remote settings;
    private final byte[] token;

    public DialogBridgeController(DialogBroker broker, GuiProperties gui) {
        this.broker = broker;
        this.settings = gui.getRemote();
        this.token = settings.getToken() == null ? new byte[0] : settings.getToken().getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/next")
    public ResponseEntity<DialogDto> next(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestParam(value = "wait", defaultValue = "20") long waitSeconds,
                                          @RequestParam(value = "agent", required = false) String agent) throws InterruptedException {
        authorize(authorization);
        Duration wait = Duration.ofSeconds(Math.max(0, Math.min(waitSeconds, settings.getPollWait().toSeconds())));
        Optional<Request> request = broker.next(wait);
        request.ifPresent(r -> log.info("Dialog {} (\"{}\") handed to the dialog agent {}", r.id(), r.title(), agent));
        return request.map(r -> ResponseEntity.ok(DialogDto.of(r))).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/received")
    public ResponseEntity<Void> received(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable String id) {
        authorize(authorization);
        return outcome(broker.received(id), id);
    }

    @PostMapping("/{id}/answer")
    public ResponseEntity<Void> answer(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable String id, @RequestBody AnswerDto body) {
        authorize(authorization);
        return outcome(broker.answer(id, body.text()), id);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable String id) {
        authorize(authorization);
        return outcome(broker.cancel(id), id);
    }

    @PostMapping("/{id}/ack")
    public ResponseEntity<Void> acknowledge(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @PathVariable String id) {
        authorize(authorization);
        return outcome(broker.acknowledge(id), id);
    }

    private ResponseEntity<Void> outcome(boolean accepted, String id) {
        if (!accepted) {
            log.warn("Dialog {} is unknown or already finished (its worker gave up?): the agent's reply is ignored", id);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    private void authorize(String authorization) {
        String expected = "Bearer ";
        byte[] given = authorization != null && authorization.startsWith(expected)
                ? authorization.substring(expected.length()).getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (token.length == 0 || !MessageDigest.isEqual(token, given)) {          // constant-time comparison
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or wrong dialog token");
        }
    }
}
