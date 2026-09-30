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
}
