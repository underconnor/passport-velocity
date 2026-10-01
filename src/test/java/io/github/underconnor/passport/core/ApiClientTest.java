package io.github.underconnor.passport.core;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class ApiClientTest {
    private final String token="development-test-token-000000000000000000";
    @Test void plaintextRequiresExplicitOptInAndTokenIsMandatory() {
        assertThrows(IllegalArgumentException.class,()->new ApiClient("http://127.0.0.1:1",token,false));
        assertThrows(IllegalArgumentException.class,()->new ApiClient("https://api.example","",false));
        assertThrows(IllegalArgumentException.class,()->new ApiClient("https://user@api.example",token,false));
    }
    @Test void serviceCredentialsAreSentAndRedirectsAreRejected() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        CompletableFuture<String> authorization=new CompletableFuture<>();
        server.createContext("/v1/minecraft/policies/", exchange -> {
            authorization.complete(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Location","http://127.0.0.1:1/private");
            exchange.sendResponseHeaders(302,-1);exchange.close();
        });
        server.start();
        try(ApiClient api=new ApiClient("http://127.0.0.1:"+server.getAddress().getPort(),token,true)) {
            assertThrows(ExecutionException.class,()->api.policy(UUID.randomUUID()).get(3,TimeUnit.SECONDS));
            assertEquals("Bearer "+token,authorization.get(1,TimeUnit.SECONDS));
        } finally { server.stop(0); }
    }
    @Test void unauthenticatedResponseNeverBecomesPolicy() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> { exchange.sendResponseHeaders(401,-1);exchange.close(); });server.start();
        try(ApiClient api=new ApiClient("http://127.0.0.1:"+server.getAddress().getPort(),token,true)) {
            assertThrows(ExecutionException.class,()->api.policy(UUID.randomUUID()).get(3,TimeUnit.SECONDS));
        } finally {server.stop(0);}
    }
    @Test void requestHasTwoSecondDeadline() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/",exchange -> { try {Thread.sleep(2800);}catch(InterruptedException ignored){} exchange.close(); });server.start();
        try(ApiClient api=new ApiClient("http://127.0.0.1:"+server.getAddress().getPort(),token,true)) {
            long start=System.nanoTime();
            ExecutionException error=assertThrows(ExecutionException.class,()->api.policy(UUID.randomUUID()).get(4,TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof java.net.http.HttpTimeoutException || error.getCause() instanceof TimeoutException);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<3500);
        } finally {server.stop(0);}
    }
    @Test void eventPollingSendsExactDecimalCursorAndUsesServiceAuthentication() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        java.util.List<String> queries=new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/v1/minecraft/events",exchange -> {
            assertEquals("Bearer "+token,exchange.getRequestHeaders().getFirst("Authorization"));
            queries.add(String.valueOf(exchange.getRequestURI().getRawQuery()));
            byte[] body="{\"cursor\":\"9223372036854775807\",\"reset\":true,\"events\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try(ApiClient api=new ApiClient("http://127.0.0.1:"+server.getAddress().getPort(),token,true)) {
            assertTrue(api.events(null).get(3,TimeUnit.SECONDS).reset());
            assertEquals("9223372036854775807",api.events("9223372036854775807").get(3,TimeUnit.SECONDS).cursor());
            assertEquals(java.util.List.of("null","after=9223372036854775807"),queries);
            assertThrows(IllegalArgumentException.class,()->api.events("1&admin=true"));
        } finally {server.stop(0);}
    }

    @Test void gameInspectionKeepsConnectionProofInAuthenticatedPostBody() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String id=UUID.randomUUID().toString(); UUID player=UUID.randomUUID();
        String session="synthetic-active-game-session";
        CompletableFuture<String> requestBody=new CompletableFuture<>();
        CompletableFuture<String> method=new CompletableFuture<>(),authorization=new CompletableFuture<>(),query=new CompletableFuture<>();
        server.createContext("/v1/link-sessions/"+id+"/game-inspect",exchange -> {
            method.complete(exchange.getRequestMethod()); authorization.complete(exchange.getRequestHeaders().getFirst("Authorization"));
            query.complete(String.valueOf(exchange.getRequestURI().getRawQuery()));
            requestBody.complete(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String response="{\"id\":\""+id+"\",\"status\":\"pending\",\"expiresAt\":\""+java.time.Instant.now().plusSeconds(300)+"\",\"webConfirmed\":true,\"gameConfirmed\":false}";
            byte[] body=response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try(ApiClient api=new ApiClient("http://127.0.0.1:"+server.getAddress().getPort(),token,true)) {
            LinkInspection state=api.gameInspect(id,player,session).get(3,TimeUnit.SECONDS);
            assertEquals("POST",method.get()); assertEquals("Bearer "+token,authorization.get()); assertEquals("null",query.get());
            com.google.gson.JsonObject body=com.google.gson.JsonParser.parseString(requestBody.get()).getAsJsonObject();
            assertEquals(java.util.Set.of("minecraftUuid","gameSessionId"),body.keySet());
            assertEquals(player.toString(),body.get("minecraftUuid").getAsString()); assertEquals(session,body.get("gameSessionId").getAsString());
            assertTrue(state.webConfirmed()); assertFalse(state.gameConfirmed());
        } finally { server.stop(0); }
    }


    @Test void heartbeatPostsOnlyDiscoveryFieldsWithServiceAuthenticationAndIgnoresSuccessBody() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        CompletableFuture<String> requestBody=new CompletableFuture<>(),authorization=new CompletableFuture<>(),method=new CompletableFuture<>();
        server.createContext("/v1/minecraft/servers/heartbeat",exchange -> {
            authorization.complete(exchange.getRequestHeaders().getFirst("Authorization")); method.complete(exchange.getRequestMethod());
            requestBody.complete(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] response="{\"received\":1,\"registered\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try(ApiClient api=new ApiClient("http://127.0.0.1:"+server.getAddress().getPort(),token,true)) {
            api.heartbeat("paper",java.util.List.of(new ServerRegistration("lobby","로비"))).get(3,TimeUnit.SECONDS);
            assertEquals("POST",method.get());assertEquals("Bearer "+token,authorization.get());
            var body=com.google.gson.JsonParser.parseString(requestBody.get()).getAsJsonObject();
            assertEquals(java.util.Set.of("source","servers"),body.keySet());assertEquals("paper",body.get("source").getAsString());
            var entry=body.getAsJsonArray("servers").get(0).getAsJsonObject();assertEquals(java.util.Set.of("id","label"),entry.keySet());
            assertEquals("lobby",entry.get("id").getAsString());assertEquals("로비",entry.get("label").getAsString());
        } finally {server.stop(0);}
    }
    @Test void heartbeatAuthorizationFailureAndRegistryConflictAreNotSuccess() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicInteger status=new java.util.concurrent.atomic.AtomicInteger(401);
        server.createContext("/v1/minecraft/servers/heartbeat",exchange -> {exchange.sendResponseHeaders(status.get(),-1);exchange.close();});server.start();
        try(ApiClient api=new ApiClient("http://127.0.0.1:"+server.getAddress().getPort(),token,true)) {
            assertThrows(ExecutionException.class,()->api.heartbeat("velocity",java.util.List.of()).get(3,TimeUnit.SECONDS));
            status.set(409);assertThrows(ExecutionException.class,()->api.heartbeat("velocity",java.util.List.of()).get(3,TimeUnit.SECONDS));
        } finally {server.stop(0);}
    }

}
