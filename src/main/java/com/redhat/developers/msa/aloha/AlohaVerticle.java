/**
 * JBoss, Home of Professional Open Source
 * Copyright 2016, Red Hat, Inc. and/or its affiliates, and individual
 * contributors by the @authors tag. See the copyright.txt in the
 * distribution for a full listing of individual contributors.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redhat.developers.msa.aloha;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import com.github.kennedyoliveira.hystrix.contrib.vertx.metricsstream.EventMetricsStreamHandler;
import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.Brave.Builder;
import com.github.kristofa.brave.EmptySpanCollectorMetricsHandler;
import com.github.kristofa.brave.ServerRequestInterceptor;
import com.github.kristofa.brave.ServerSpan;
import com.github.kristofa.brave.http.DefaultSpanNameProvider;
import com.github.kristofa.brave.http.HttpServerRequestAdapter;
import com.github.kristofa.brave.http.HttpServerResponseAdapter;
import com.github.kristofa.brave.http.HttpSpanCollector;
import com.github.kristofa.brave.httpclient.BraveHttpRequestInterceptor;
import com.github.kristofa.brave.httpclient.BraveHttpResponseInterceptor;

import feign.Logger;
import feign.Logger.Level;
import feign.httpclient.ApacheHttpClient;
import feign.hystrix.HystrixFeign;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.JWTAuthHandler;
import io.vertx.ext.web.handler.StaticHandler;

public class AlohaVerticle extends AbstractVerticle {

    private static Brave BRAVE = null;

    public AlohaVerticle() {
        String zipkingServer = System.getenv("ZIPKIN_SERVER_URL");
        Builder builder = new Brave.Builder("aloha");
        if (null == zipkingServer) {
            // Default configuration
            BRAVE = builder.build();
            System.out.println("No ZIPKIN_SERVER_URL defined. Printing zipkin traces to console.");
        } else {
            // Brave configured for a Server
            BRAVE = builder.spanCollector(HttpSpanCollector.create(System.getenv("ZIPKIN_SERVER_URL"),
                new EmptySpanCollectorMetricsHandler()))
                .build();
        }
    }

    @Override
    public void start() throws Exception {
        Router router = Router.router(vertx);
        router.route().handler(ctx -> {
            // note: this is *not* an example of how to properly integrate vert.x with zipkin
            // for a more appropriate way to do that, check the vert.x documentation
            ServerRequestInterceptor serverRequestInterceptor = BRAVE.serverRequestInterceptor();
            serverRequestInterceptor.handle(new HttpServerRequestAdapter(new VertxHttpServerRequest(ctx.request()), new DefaultSpanNameProvider()));
            ctx.data().put("zipkin.span", BRAVE.serverSpanThreadBinder().getCurrentServerSpan());
            ctx.next();
            ctx.addBodyEndHandler(v -> BRAVE.serverResponseInterceptor().handle(new HttpServerResponseAdapter(() -> ctx.response().getStatusCode())));
        });
        router.route().handler(BodyHandler.create());
        router.route().handler(CorsHandler.create("*")
            .allowedMethods(new HashSet<>(Arrays.asList(HttpMethod.values())))
            .allowedHeader("Origin, X-Requested-With, Content-Type, Accept, Authorization"));

        // Aloha EndPoint
        router.get("/api/aloha").handler(ctx -> ctx.response().end(aloha()));

        String keycloackServer = System.getenv("KEYCLOAK_AUTH_SERVER_URL");

        if (keycloackServer != null) {
            // Create a JWT Auth Provider
            JWTAuth jwt = JWTAuth.create(vertx, new JsonObject()
                .put("public-key",
                    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArfmb1i36YGxYxusjzpNxmw9a/+M40naa5RxtK826nitmWESF9XiXm6bHLWmRQyhAZluFK4RZDLhQJFZTLpC/w8HdSDETYGqnrP04jL3/pV0Mw1ReKSpzi3tIde+04xGuiQM6nuR84iRraLxtoNyIiqFmHy5pmI9hQhctfZNOVvggntnhXdt/VKuguBXqitFwGbfEgrJTeRvnTkK+rR5MsRDHA3iu2ZYaM4YNAoDbqGyoI4Jdv5Kl1LsP3qESYNeagRz6pIfDZWOoJ58p/TldVt2h70S1bzappbgs8ZbmJXg+pHWcKvNutp5y8nYw30qzU73pX6DW9JS936OB6PiU0QIDAQAB"));
            router.route("/api/aloha-secured").handler(JWTAuthHandler.create(jwt));
        }
        router.get("/api/aloha-secured").handler(ctx -> {
            User user = ctx.user();
            ctx.response().end("This is a secured resource. You're logged as " + user.principal().getString("name"));   
        });

        // Aloha Chained Endpoint
        router.get("/api/aloha-chaining").handler(ctx -> alohaChaining(ctx, (list) -> ctx.response()
            .putHeader("Content-Type", "application/json")
            .end(Json.encode(list))));

        // Health Check
        router.get("/api/health").handler(ctx -> ctx.response().end("I'm ok"));

        // Hystrix Stream Endpoint
        router.get(EventMetricsStreamHandler.DEFAULT_HYSTRIX_PREFIX).handler(EventMetricsStreamHandler.createHandler());

        // Static content
        router.route("/*").handler(StaticHandler.create());

        vertx.createHttpServer().requestHandler(router::accept).listen(8080);
        System.out.println("Service running at 0.0.0.0:8080");
    }

    private String aloha() {
        String hostname = System.getenv().getOrDefault("HOSTNAME", "unknown");
        return String.format("TEST: Aloha mai %s", hostname);
    }

    private void alohaChaining(RoutingContext context, Handler<List<String>> resultHandler) {
        vertx.<String> executeBlocking(
            // Invoke the service in a worker thread, as it's blocking.
            future -> future.complete(getNextService(context).bonjour()),
            ar -> {
                // Back to the event loop
                // result cannot be null, hystrix would have called the fallback.
                String result = ar.result();
                List<String> greetings = new ArrayList<>();
                greetings.add(aloha());
                greetings.add(result);
                resultHandler.handle(greetings);
            });
    }

    /**
     * This is were the "magic" happens: it creates a Feign, which is a proxy interface for remote calling a REST endpoint with
     * Hystrix fallback support.
     *
     * @return The feign pointing to the service URL and with Hystrix fallback.
     */
    private BonjourService getNextService(RoutingContext context) {
        final String serviceName = "bonjour";
        // This stores the Original/Parent ServerSpan from ZiPkin.
        final ServerSpan serverSpan = (ServerSpan) context.data().get("zipkin.span");
        final CloseableHttpClient httpclient =
            HttpClients.custom()
                .addInterceptorFirst(new BraveHttpRequestInterceptor(BRAVE.clientRequestInterceptor(), new DefaultSpanNameProvider()))
                .addInterceptorFirst(new BraveHttpResponseInterceptor(BRAVE.clientResponseInterceptor()))
                .build();
        final String url = String.format("http://%s:8080/", serviceName);
        return HystrixFeign.builder()
            // Use apache HttpClient which contains the ZipKin Interceptors
            .client(new ApacheHttpClient(httpclient))
            // Bind Zipkin Server Span to Feign Thread, but this probably won't work in a real-world scenario
            // as a concurrent request might override the value set to this thread
            .requestInterceptor((t) -> BRAVE.serverSpanThreadBinder().setCurrentSpan(serverSpan))
            .logger(new Logger.ErrorLogger()).logLevel(Level.BASIC)
            .target(BonjourService.class, url,
                () -> "Bonjour response (fallback)");
    }

}
