package com.tovarika.tech.images.infrastructure;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class OpenAiTransportTest {
    @Test void boundedTransportDoesNotRetryAnUnknownProviderOutcome() throws Exception {
        var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/responses",exchange -> {
            calls.incrementAndGet();
            try { Thread.sleep(500); exchange.sendResponseHeaders(200,0); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var client=OpenAiTransportConfiguration.builder(Duration.ofMillis(100)).build();
            assertThatThrownBy(() -> client.post().uri("http://127.0.0.1:"+server.getAddress().getPort()+"/responses")
                    .body("fake request").retrieve().body(String.class)).isInstanceOf(ResourceAccessException.class);
            assertThat(calls.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }
}
