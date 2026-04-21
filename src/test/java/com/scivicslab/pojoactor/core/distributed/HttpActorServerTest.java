package com.scivicslab.pojoactor.core.distributed;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HttpActorServer.
 * Path parsing tests run without a network. E2E test uses loopback.
 */
class HttpActorServerTest {

    // ---- Path extraction tests (no server needed) ----

    @Test
    void extractActorName_validPath() {
        assertEquals("math", HttpActorServer.extractActorName("/actor/math/invoke"));
    }

    @Test
    void extractActorName_hyphenatedName() {
        assertEquals("order-saga", HttpActorServer.extractActorName("/actor/order-saga/invoke"));
    }

    @Test
    void extractActorName_missingInvoke() {
        assertNull(HttpActorServer.extractActorName("/actor/math/run"));
    }

    @Test
    void extractActorName_noActorSegment() {
        assertNull(HttpActorServer.extractActorName("/actor//invoke"));
    }

    @Test
    void extractActorName_wrongPrefix() {
        assertNull(HttpActorServer.extractActorName("/other/math/invoke"));
    }

    @Test
    void extractActorName_nullPath() {
        assertNull(HttpActorServer.extractActorName(null));
    }

    // ---- E2E test: real HTTP server on loopback ----

    /** Simple actor that supports add/echo via CallableByActionName. */
    static class CalcActor implements com.scivicslab.pojoactor.core.CallableByActionName {
        @Override
        public ActionResult callByActionName(String action, String args) {
            return switch (action) {
                case "add" -> {
                    String[] parts = args.split(",");
                    int sum = Integer.parseInt(parts[0].trim()) + Integer.parseInt(parts[1].trim());
                    yield new ActionResult(true, String.valueOf(sum));
                }
                case "echo" -> new ActionResult(true, args);
                default -> new ActionResult(false, "Unknown action: " + action);
            };
        }
    }

    @Test
    void e2e_serverDispatchesRequestToLocalActor() throws Exception {
        int port = 18181;
        ActorSystem system = new ActorSystem("test");
        CalcActor calc = new CalcActor();
        ActorRef<CalcActor> ref = system.actorOf("calc", calc);

        HttpActorServer server = new HttpActorServer(system, port);
        server.start();

        try {
            HttpClient client = HttpClient.newHttpClient();
            ActorMessage msg = new ActorMessage("calc", "add", "10,32");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/actor/calc/invoke"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(msg.toJson()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"success\":true"));
            assertTrue(response.body().contains("\"result\":\"42\""));
        } finally {
            server.close();
            system.terminate();
        }
    }

    @Test
    void e2e_unknownActorReturnsFailure() throws Exception {
        int port = 18182;
        ActorSystem system = new ActorSystem("test");

        HttpActorServer server = new HttpActorServer(system, port);
        server.start();

        try {
            HttpClient client = HttpClient.newHttpClient();
            ActorMessage msg = new ActorMessage("nonexistent", "action", "args");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/actor/nonexistent/invoke"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(msg.toJson()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"success\":false"));
        } finally {
            server.close();
            system.terminate();
        }
    }
}
