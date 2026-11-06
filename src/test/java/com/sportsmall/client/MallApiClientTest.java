package com.sportsmall.client;

import com.sun.net.httpserver.*;
import com.sportsmall.api.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class MallApiClientTest {
    @Test void clientOnlySendsMallCredentialsAndCarriesServerToken() throws Exception {
        List<String> authorizations = new ArrayList<>();
        AtomicReference<String> loginBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String output;
            if (exchange.getRequestURI().getPath().endsWith("/login")) {
                loginBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                output = "{\"token\":\"" + "x".repeat(43) + "\",\"user\":{\"userId\":7,\"username\":\"mall-user\",\"role\":\"USER\"}}";
            } else output="{\"loggedOut\":true}";
            byte[] bytes=output.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            MallApiClient client = new MallApiClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
            assertEquals(7,client.login("mall-user","mall-password".toCharArray()).userId());
            assertEquals(Set.of("username","password"), ApiJson.GSON.fromJson(loginBody.get(), com.google.gson.JsonObject.class).keySet());
            client.logout(); client.logout();
            assertEquals(2, authorizations.size()); assertNull(authorizations.get(0));
            assertEquals("Bearer "+"x".repeat(43),authorizations.get(1));
        } finally { server.stop(0); }
    }
    @Test void nonSuccessIsBusinessErrorRatherThanFakeSuccess() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            byte[] bytes="{\"code\":\"LOGIN_REQUIRED\",\"message\":\"请先登录\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            var client=new MallApiClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
            var error=assertThrows(ApiException.class,client::account);
            assertEquals(401,error.status());assertEquals("LOGIN_REQUIRED",error.code());
        } finally {server.stop(0);}
    }
    @Test void redirectsAndExternalAddressesCannotReceiveCredentials() {
        assertThrows(IllegalArgumentException.class,()->new MallApiClient(URI.create("http://example.test")));
        assertThrows(IllegalArgumentException.class,()->new MallApiClient(URI.create("http://root:password@localhost:18808")));
        assertThrows(IllegalArgumentException.class,()->new MallApiClient(URI.create("http://localhost:18808/api")));
    }
}
