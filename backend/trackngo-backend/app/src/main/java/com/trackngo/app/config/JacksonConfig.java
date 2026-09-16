package com.trackngo.app.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Sends timestamps with the zone they are actually in.
 *
 * Every instant in this system is stored in UTC: the EC2 host, the MySQL server
 * and the JDBC connection all run UTC, so LocalDateTime.now() writes UTC and
 * reads it back unchanged. The values are correct. The problem was the wire
 * format - they were serialised bare, as "2026-09-16T16:17:16", with nothing to
 * say which zone that is.
 *
 * JavaScript parses a date-time string WITHOUT an offset as local time. So an
 * app in Sri Lanka took a UTC instant and read it as Colombo time, and every
 * timestamp in the product displayed five and a half hours early.
 *
 * Marking the offset fixes that at the source. The clients already format with
 * toLocaleTimeString(), which converts an offset-carrying instant into the
 * device's own zone, so they start showing the right time without any change to
 * them.
 *
 * Only LocalDateTime is affected. LocalDate and LocalTime are calendar days and
 * wall-clock times - journey_date, journey_time, the bus timetable - and are
 * deliberately left alone: stamping an offset on a 09:00 departure would turn it
 * into 14:30 and corrupt real bookings.
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter UTC_ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer utcTimestamps() {
        return builder -> {
            builder.serializerByType(LocalDateTime.class, new JsonSerializer<LocalDateTime>() {
                @Override
                public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                        throws IOException {
                    if (value == null) {
                        gen.writeNull();
                        return;
                    }
                    gen.writeString(value.atOffset(ZoneOffset.UTC).format(UTC_ISO));
                }
            });

            /* The counterpart to the above. A client that sends a timestamp back -
               the message-history cursor does exactly this - now returns the value
               we gave it, which carries an offset. Without this the request would
               fail to bind, so pagination would break the moment the serialiser
               above started marking the zone. Bare values are still accepted, so
               older clients keep working. */
            builder.deserializerByType(LocalDateTime.class, new JsonDeserializer<LocalDateTime>() {
                @Override
                public LocalDateTime deserialize(JsonParser parser, DeserializationContext context)
                        throws IOException {
                    String text = parser.getText();
                    if (text == null || text.isBlank()) {
                        return null;
                    }
                    try {
                        return OffsetDateTime.parse(text)
                                .withOffsetSameInstant(ZoneOffset.UTC)
                                .toLocalDateTime();
                    } catch (DateTimeParseException notOffset) {
                        return LocalDateTime.parse(text);
                    }
                }
            });
        };
    }
}
