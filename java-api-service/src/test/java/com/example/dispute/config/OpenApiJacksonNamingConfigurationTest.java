package com.example.dispute.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.casecore.api.SimulateImportRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenApiJacksonNamingConfigurationTest {

    @Test
    void schemaResolverUsesApplicationSnakeCasePropertyNamesGlobally() {
        ObjectMapper applicationObjectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        ModelResolver resolver = new OpenApiJacksonNamingConfiguration()
                .applicationObjectMapperModelResolver(applicationObjectMapper);
        ModelConverters converters = new ModelConverters();
        converters.addConverter(resolver);

        Map<String, Schema> schemas = converters.readAll(SimulateImportRequest.class);
        Schema<?> requestSchema = schemas.get("SimulateImportRequest");

        assertThat(resolver.objectMapper()).isSameAs(applicationObjectMapper);
        assertThat(requestSchema).isNotNull();
        assertThat(requestSchema.getProperties())
                .containsKeys("current_actor_id", "counterparty_actor_id", "simulation_batch_id")
                .doesNotContainKeys("currentActorId", "counterpartyActorId", "simulationBatchId");
    }
}
