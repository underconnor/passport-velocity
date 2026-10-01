package io.github.underconnor.passport.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LinkFeedbackTest {
    private static final String TOKEN = "synthetic-service-token-for-link-feedback-tests";
    private record Response(int status, String body) {}

    @Test void repeatedGameConfirmationKeepsWebConfirmationGuidance() throws Exception {
        withResponse(new Response(409, "{\"code\":\"game_confirmation_consumed\"}"), api -> {
            ExecutionException error = assertThrows(ExecutionException.class,
                () -> api.confirm(UUID.randomUUID().toString(), UUID.randomUUID(), "synthetic-session").get(3, TimeUnit.SECONDS));
            assertEquals(ApiFailure.Reason.GAME_CONFIRMATION_CONSUMED, ApiFailure.reasonOf(error));
            LinkFeedback feedback = LinkFeedback.confirmation(error);
            assertFalse(feedback.message().contains("/passport confirm"));
            assertTrue(feedback.message().contains("웹"));
            assertFalse(feedback.refreshPolicy());
        });
    }

    @Test void expiredConsumedAndMismatchedLinksHaveDistinctRecoveryInstructions() {
        LinkFeedback expired = LinkFeedback.confirmation(failure(410, "link_expired"));
        LinkFeedback consumed = LinkFeedback.confirmation(failure(409, "link_consumed"));
        LinkFeedback mismatch = LinkFeedback.confirmation(failure(403, "game_session_mismatch"));
        assertTrue(expired.message().contains("만료"));
        assertFalse(expired.refreshPolicy());
        assertTrue(consumed.message().contains("완료되었거나 취소"));
        assertTrue(consumed.refreshPolicy());
        assertTrue(mismatch.message().contains("세션이 다릅니다"));
        assertFalse(mismatch.refreshPolicy());
        assertTrue(LinkFeedback.confirmation(failure(404, "link_not_found")).message().contains("찾을 수 없습니다"));
    }

    @Test void alreadyLinkedCreationRefreshesExistingPolicyInsteadOfReissuingLink() throws Exception {
        withResponse(new Response(409, "{\"code\":\"minecraft_already_linked\"}"), api -> {
            ExecutionException error = assertThrows(ExecutionException.class,
                () -> api.createLink(UUID.randomUUID(), "SyntheticPlayer", "synthetic-session").get(3, TimeUnit.SECONDS));
            LinkFeedback feedback = LinkFeedback.creation(error);
            assertTrue(feedback.message().contains("이미 계정이 연결"));
            assertTrue(feedback.message().contains("/passport status"));
            assertTrue(feedback.refreshPolicy());
            // A different operation cannot turn a recognized code into inappropriate guidance.
            assertFalse(LinkFeedback.confirmation(error).refreshPolicy());
        });
    }

    @Test void unknownMalformedAndOversizedBodiesNeverEscapeInErrors() throws Exception {
        String secret = "synthetic-private-marker";
        for (String body : new String[] {
            "{\"code\":\"" + secret + "\",\"url\":\"https://private.invalid/link#token=" + secret + "\"}",
            "{malformed-" + secret,
            "{\"code\":{\"private\":\"" + secret + "\"}}",
            "{\"code\":\"game_confirmation_consumed\",\"private\":\"" + secret + "x".repeat(65536) + "\"}"
        }) {
            withResponse(new Response(409, body), api -> {
                ExecutionException error = assertThrows(ExecutionException.class,
                    () -> api.confirm(UUID.randomUUID().toString(), UUID.randomUUID(), "synthetic-session").get(3, TimeUnit.SECONDS));
                assertEquals(ApiFailure.Reason.REJECTED, ApiFailure.reasonOf(error));
                StringWriter trace = new StringWriter();
                error.printStackTrace(new PrintWriter(trace));
                assertFalse(trace.toString().contains(secret));
                assertFalse(trace.toString().contains("https://private.invalid"));
                assertFalse(trace.toString().contains(TOKEN));
                assertFalse(LinkFeedback.confirmation(error).refreshPolicy());
            });
        }
    }

    @Test void allowlistedCodeRequiresItsExpectedHttpStatusAndHasNoParserCause() {
        ApiFailure response = failure(401, "game_confirmation_consumed");
        assertEquals(ApiFailure.Reason.REJECTED, response.reason());
        assertEquals(401, response.status());
        assertNull(response.getCause());
        assertFalse(LinkFeedback.confirmation(new TimeoutException()).refreshPolicy());
    }

    @Test void successfulPendingAndLinkedConfirmationsRemainUnchanged() throws Exception {
        for (String status : new String[] { "pending", "linked" }) {
            withResponse(new Response(200, "{\"status\":\"" + status + "\"}"), api ->
                assertEquals(status, api.confirm(UUID.randomUUID().toString(), UUID.randomUUID(), "synthetic-session")
                    .get(3, TimeUnit.SECONDS).get("status").getAsString()));
        }
    }

    private static ApiFailure failure(int status, String code) {
        return ApiFailure.fromResponse(status, ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8));
    }
    @FunctionalInterface private interface Scenario { void run(ApiClient api) throws Exception; }
    private static void withResponse(Response response, Scenario scenario) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (ApiClient api = new ApiClient("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN, true)) {
            scenario.run(api);
        } finally { server.stop(0); }
    }
}
