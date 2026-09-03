package com.example.dispute.workflow.observability;

import com.example.dispute.config.AppProperties;
import com.example.dispute.config.AppProperties.Temporal.PayloadProtection.Mode;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TemporalPayloadCodecConfiguration {

    @Bean
    DataConverter temporalDataConverter(AppProperties properties) {
        AppProperties.Temporal.PayloadProtection protection =
                properties.temporal().payloadProtection();
        DataConverter delegate = DefaultDataConverter.newDefaultInstance();
        if (protection.mode() == Mode.DISABLED) {
            return delegate;
        }
        return new CodecDataConverter(
                delegate,
                List.of(AesGcmTemporalPayloadCodec.from(protection)),
                true);
    }
}
