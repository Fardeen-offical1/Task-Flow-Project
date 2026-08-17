package com.taskmgmt.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Spring Boot's default RedisCacheManager serializes cached values with
 * plain JDK serialization, which requires every cached type to
 * implement java.io.Serializable. None of the JPA entities here do
 * (Task, etc.), so the first cache write (e.g. GET /tasks or
 * GET /tasks/{id}) failed with a SerializationException surfaced as a
 * 500 to the client — the read from Postgres succeeded, only the
 * Redis PUT afterward failed.
 *
 * Switching to JSON (Jackson) serialization sidesteps that: it works
 * off getters/setters, not the Serializable marker. But the plain
 * no-arg GenericJackson2JsonRedisSerializer() builds its own internal
 * ObjectMapper with no modules registered, so it doesn't know how to
 * write java.time.Instant (Task.dueDate/createdAt/updatedAt) either —
 * same symptom, different missing piece. This wires an ObjectMapper
 * with JavaTimeModule registered explicitly so Instant fields
 * serialize as ISO-8601 strings instead of throwing.
 */
@Configuration
public class RedisCacheConfig {

    @Bean
    public RedisCacheConfiguration cacheConfiguration() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // Needed so a cached List<Task> deserializes back into the concrete
        // Task type instead of a LinkedHashMap on the next cache read.
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(60))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(mapper)));
    }
}
