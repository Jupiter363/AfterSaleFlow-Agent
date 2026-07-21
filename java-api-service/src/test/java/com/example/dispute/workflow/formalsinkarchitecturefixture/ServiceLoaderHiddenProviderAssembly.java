package com.example.dispute.workflow.formalsinkarchitecturefixture;

import com.example.dispute.workflow.temporal.room.intake.IntakeRoomActivities;
import java.util.Iterator;
import java.util.ServiceLoader;
import org.springframework.context.annotation.Configuration;

@Configuration
class ServiceLoaderHiddenProviderAssembly {

    private final IntakeServiceProviderLoader providerLoader;

    ServiceLoaderHiddenProviderAssembly(IntakeServiceProviderLoader providerLoader) {
        this.providerLoader = providerLoader;
    }

    Object hiddenProvider() {
        return providerLoader.hiddenProvider();
    }
}

final class IntakeServiceProviderLoader {

    private static final String HIDDEN_PROVIDER_INTENT =
            "com.example.dispute.workflow.activity.intake.IntakeRoomActivitiesAdapter";

    Object hiddenProvider() {
        ServiceLoader<IntakeRoomActivities> loader =
                ServiceLoader.load(IntakeRoomActivities.class);
        Iterator<IntakeRoomActivities> legacyProviders = loader.iterator();
        Iterator<ServiceLoader.Provider<IntakeRoomActivities>> providers =
                loader.stream().iterator();
        if (providers.hasNext()) {
            return providers.next().get();
        }
        return legacyProviders.hasNext() ? legacyProviders.next() : HIDDEN_PROVIDER_INTENT;
    }
}
