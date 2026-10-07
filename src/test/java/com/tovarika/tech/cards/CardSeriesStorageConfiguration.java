package com.tovarika.tech.cards;

import com.tovarika.tech.products.application.ProductStorage;
import com.tovarika.tech.templates.TemplatePlaceholder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods=false)
class CardSeriesStorageConfiguration {
    @Bean @Primary MemoryStorage memoryStorage() { return new MemoryStorage(); }
    static class MemoryStorage implements ProductStorage {
        final Map<String,byte[]> data=new ConcurrentHashMap<>();
        boolean failReads;
        public void put(String key,byte[] bytes,String mediaType) { data.put(key,bytes.clone()); }
        public byte[] read(String key) {
            if(failReads) throw new IllegalStateException("Test storage unavailable");
            return data.getOrDefault(key,TemplatePlaceholder.png()).clone();
        }
        public void delete(String key) { data.remove(key); }
    }
}
