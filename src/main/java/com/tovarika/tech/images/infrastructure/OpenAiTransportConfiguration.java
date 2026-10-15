package com.tovarika.tech.images.infrastructure;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods=false)
public class OpenAiTransportConfiguration {
    @Bean
    RestClient.Builder openAiRestClientBuilder() {
        return builder(Duration.ofSeconds(240));
    }
    static RestClient.Builder builder(Duration timeout) {
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var factory=new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory);
    }
}
