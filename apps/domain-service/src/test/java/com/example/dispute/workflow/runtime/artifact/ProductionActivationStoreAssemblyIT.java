package com.example.dispute.workflow.runtime.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.dispute.workflow.runtime.ProductionActivationCaseLedger;
import com.example.dispute.workflow.runtime.ProductionActivationLifecycleStore;
import com.example.dispute.workflow.runtime.ProductionActivationReplayStore;
import com.example.dispute.workflow.runtime.ProductionActivationRuntimeConfiguration;
import com.example.dispute.workflow.runtime.persistence.JdbcProductionActivationStores;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class ProductionActivationStoreAssemblyIT {

  @Test
  void everyRoleHasExactlyOneOwnerForAllThreeActivationStoreInterfaces() throws Exception {
    Path classes = Path.of(System.getProperty("production.classesDirectory"));
    String prefix = "com.example.dispute.workflow.runtime.artifact.";
    assertThat(classes.resolve(prefix.replace('.', '/') + "ProductionControlConfiguration.class"))
        .isRegularFile();
    try (URLClassLoader loader = new URLClassLoader(
        new java.net.URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
      for (String role : new String[] {"CONTROL", "API", "AGENT"}) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
          context.setClassLoader(loader);
          context.getEnvironment().setActiveProfiles("production-runtime",
              role.equals("CONTROL") ? "control-worker" : role.equals("API") ? "api" : "agent-worker");
          context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
              "activation-store-test", Map.of(
                  "app.production-runtime.enabled", "true", "app.temporal.worker.role", role)));
          DataSource dataSource = mock(DataSource.class);
          context.registerBean("dataSource", DataSource.class, () -> dataSource);
          context.registerBean(Clock.class, Clock::systemUTC);
          context.register(ProductionActivationRuntimeConfiguration.class,
              Class.forName(prefix + "ProductionControlConfiguration", true, loader),
              Class.forName(prefix + "ProductionApiConfiguration", true, loader));
          // Parse the real role configurations, but do not start unrelated workers or transports.
          context.addBeanFactoryPostProcessor(factory -> {
            for (String name : factory.getBeanDefinitionNames()) {
              factory.getBeanDefinition(name).setLazyInit(true);
            }
          });
          context.refresh();
          if (role.equals("AGENT")) {
            assertThat(context.getBeansOfType(JdbcProductionActivationStores.class)).isEmpty();
            assertThat(context.getBeansOfType(ProductionActivationReplayStore.class)).isEmpty();
          } else {
            String owner = role.equals("CONTROL")
                ? "productionControlActivationStores" : "productionApiActivationStores";
            assertThat(context.getBeanNamesForType(JdbcProductionActivationStores.class))
                .containsExactly(owner);
            Object store = context.getBean(owner);
            assertThat(context.getBean(ProductionActivationReplayStore.class)).isSameAs(store);
            assertThat(context.getBean(ProductionActivationCaseLedger.class)).isSameAs(store);
            assertThat(context.getBean(ProductionActivationLifecycleStore.class)).isSameAs(store);
          }
          verifyNoInteractions(dataSource);
        }
      }
    }
  }
}
