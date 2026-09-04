package com.example.dispute.workflow.runtime;

import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.BuildBindings;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.CaseScope;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.DatabaseIdentities;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.DatabaseIdentity;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.ExplicitCaseIds;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.GraphBinding;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.ImageDigests;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.IsolatedSyntheticNewCases;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.MeasuredAuthorityFacts;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.RoomType;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.SyntheticFixtureDeployment;
import com.example.dispute.workflow.runtime.ProductionRuntimeMeasurementProvider.DatabasePrivilegeEvidence;
import com.example.dispute.workflow.runtime.ProductionRuntimeMeasurementProvider.MeasurementChallenge;
import com.example.dispute.workflow.runtime.ProductionRuntimeMeasurementProvider.MeasurementEvidence;
import java.io.IOException;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.jar.JarFile;
import javax.sql.DataSource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/** Measures production runtime artifact, Spring configuration, JDBC identities, privileges, and isolation. */
public final class SpringJdbcProductionRuntimeMeasurementProvider
    implements ProductionRuntimeMeasurementProvider {

  public static final String ARTIFACT_MARKER = "PRODUCTION_RUNTIME_JAVA_ARTIFACT_V1";

  private static final String PREFIX = "app.production-runtime.measurement.";
  private static final String ARTIFACT_MARKER_RESOURCE =
      "META-INF/after-sale-flow/production-runtime-artifact.marker";
  private static final int MAXIMUM_ATTESTATION_BYTES = 24 * 1024;
  private static final Pattern SPLIT = Pattern.compile("\\s*,\\s*");
  private static final Set<String> EXTERNAL_ENDPOINT_PROPERTIES =
      Set.of(
          "app.agent.base-url",
          "app.ocr.base-url",
          "app.ocr.callback-base-url",
          "app.notifications.base-url",
          "app.outcome.external-effects.base-url");

  private final ConfigurableEnvironment environment;
  private final DatabaseProbe databaseProbe;
  private final DataSource domainDataSource;
  private final DataSource graphDataSource;
  private final ArtifactProbe artifactProbe;
  private final ProductionIsolationAttestationVerifier attestationVerifier;
  private final Path attestationPath;

  public SpringJdbcProductionRuntimeMeasurementProvider(
      ConfigurableEnvironment environment,
      DataSource domainDataSource,
      DataSource graphDataSource,
      ProductionIsolationAttestationPublicKeySet attestationPublicKeys,
      Clock clock) {
    this(
        environment,
        domainDataSource,
        graphDataSource,
        new JdbcDatabaseProbe(),
        new CodeSourceArtifactProbe(),
        new ProductionIsolationAttestationVerifier(attestationPublicKeys, clock),
        requiredPath(environment, PREFIX + "isolation-attestation-path"));
  }

  SpringJdbcProductionRuntimeMeasurementProvider(
      ConfigurableEnvironment environment,
      DataSource domainDataSource,
      DataSource graphDataSource,
      DatabaseProbe databaseProbe,
      ArtifactProbe artifactProbe,
      ProductionIsolationAttestationVerifier attestationVerifier,
      Path attestationPath) {
    this.environment = Objects.requireNonNull(environment, "environment");
    this.domainDataSource = Objects.requireNonNull(domainDataSource, "domainDataSource");
    this.graphDataSource = Objects.requireNonNull(graphDataSource, "graphDataSource");
    this.databaseProbe = Objects.requireNonNull(databaseProbe, "databaseProbe");
    this.artifactProbe = Objects.requireNonNull(artifactProbe, "artifactProbe");
    this.attestationVerifier = Objects.requireNonNull(attestationVerifier, "attestationVerifier");
    this.attestationPath = Objects.requireNonNull(attestationPath, "attestationPath");
  }

  @Override
  public MeasuredRuntime measure(MeasurementChallenge challenge) {
    Objects.requireNonNull(challenge, "challenge");
    Set<String> profiles = measuredProfiles();
    ArtifactMeasurement artifact = artifactProbe.measure();
    if (!ARTIFACT_MARKER.equals(required(PREFIX + "artifact-marker"))
        || !ARTIFACT_MARKER.equals(artifact.marker())) {
      throw new IllegalStateException("production runtime artifact marker does not match measured code");
    }
    requireSafeConfiguration();
    DatabaseObservation domain = databaseProbe.measure(domainDataSource);
    DatabaseObservation graph = databaseProbe.measure(graphDataSource);
    DatabasePrivilegeEvidence domainPrivileges =
        domain.privilegesWithPeerConnect(
            databaseProbe.peerPrincipalCanConnect(domainDataSource, graph.roleName()));
    DatabasePrivilegeEvidence graphPrivileges =
        graph.privilegesWithPeerConnect(
            databaseProbe.peerPrincipalCanConnect(graphDataSource, domain.roleName()));
    requireSafePrivileges(domainPrivileges, graphPrivileges);
    requirePhysicalDatabaseIsolation(domain.identity(), graph.identity());
    DatabaseIdentities databases = new DatabaseIdentities(domain.identity(), graph.identity());
    if (!databases.equals(configuredDatabaseIdentities())) {
      throw new IllegalStateException(
          "measured PostgreSQL identities do not match the provisioned activation identities");
    }
    ProductionActivationExpectedRuntime runtime = measuredRuntime(databases);
    requireWorkerBinding(runtime.buildBindings());
    String compactAttestation = readAttestation();
    ProductionIsolationAttestationVerifier.VerifiedAttestation attestation =
        attestationVerifier.verify(
            compactAttestation,
            challenge,
            runtime,
            artifact.digest(),
            domainPrivileges,
            graphPrivileges);
    MeasurementEvidence evidence =
        new MeasurementEvidence(
            profiles,
            artifact.marker(),
            artifact.digest(),
            required("app.temporal.worker.role"),
            domainPrivileges,
            graphPrivileges,
            attestation);
    return new MeasuredRuntime(runtime, evidence);
  }

  private ProductionActivationExpectedRuntime measuredRuntime(DatabaseIdentities databases) {
    CaseScope caseScope = caseScope();
    Optional<SyntheticFixtureDeployment> fixtureDeployment = fixtureDeployment(caseScope);
    return new ProductionActivationExpectedRuntime(
        "production-runtime",
        required(PREFIX + "environment-id"),
        positiveLong(PREFIX + "environment-generation"),
        required(PREFIX + "candidate-sha"),
        required(PREFIX + "tenant-surrogate"),
        caseScope,
        roomTypes(),
        new BuildBindings(
            required(PREFIX + "build.case"),
            required(PREFIX + "build.control"),
            required(PREFIX + "build.agent")),
        new GraphBinding(
            required(PREFIX + "graph.key"),
            required(PREFIX + "graph.version"),
            required(PREFIX + "graph.checkpoint-schema-version"),
            required(PREFIX + "graph.binding-hash"),
            required(PREFIX + "graph.code-build-id")),
        new ImageDigests(
            required(PREFIX + "images.java-api"),
            required(PREFIX + "images.temporal-control-worker"),
            required(PREFIX + "images.temporal-agent-worker"),
            required(PREFIX + "images.python-agent"),
            required(PREFIX + "images.frontend")),
        required("app.temporal.namespace"),
        databases,
        fixtureDeployment,
        new MeasuredAuthorityFacts(
            true,
            required(PREFIX + "environment-class"),
            required(PREFIX + "graph-output-authority"),
            false,
            false,
            false,
            required(PREFIX + "formal-writer"),
            true,
            false,
            false,
            false,
            false,
            required(PREFIX + "production-formal-selector-default"),
            required(PREFIX + "activation-default")));
  }

  private DatabaseIdentities configuredDatabaseIdentities() {
    return new DatabaseIdentities(
        new DatabaseIdentity(
            required(PREFIX + "database.domain.cluster-identity"),
            required(PREFIX + "database.domain.database-identity"),
            required(PREFIX + "database.domain.runtime-principal-identity")),
        new DatabaseIdentity(
            required(PREFIX + "database.graph.cluster-identity"),
            required(PREFIX + "database.graph.database-identity"),
            required(PREFIX + "database.graph.runtime-principal-identity")));
  }

  private CaseScope caseScope() {
    String mode = required(PREFIX + "case-scope.mode");
    if ("EXPLICIT_CASE_IDS".equals(mode)) {
      return new ExplicitCaseIds(tokens(PREFIX + "case-scope.allowed-case-ids"));
    }
    if ("ISOLATED_SYNTHETIC_NEW_CASES".equals(mode)) {
      return new IsolatedSyntheticNewCases(
          required(PREFIX + "case-scope.case-id-prefix"),
          Math.toIntExact(positiveLong(PREFIX + "case-scope.max-cases")),
          required(PREFIX + "case-scope.fixture-set-id"),
          required(PREFIX + "case-scope.fixture-set-hash"),
          false,
          false);
    }
    throw new IllegalStateException("production runtime case scope mode is invalid");
  }

  private Optional<SyntheticFixtureDeployment> fixtureDeployment(CaseScope scope) {
    if (!(scope instanceof IsolatedSyntheticNewCases synthetic)) {
      return Optional.empty();
    }
    return Optional.of(
        new SyntheticFixtureDeployment(
            synthetic.fixtureSetId(),
            required(PREFIX + "case-scope.fixture-read-only-path"),
            synthetic.fixtureSetHash()));
  }

  private Set<RoomType> roomTypes() {
    EnumSet<RoomType> rooms = EnumSet.noneOf(RoomType.class);
    for (String value : tokens(PREFIX + "allowed-room-types")) {
      rooms.add(RoomType.valueOf(value));
    }
    return rooms;
  }

  private void requireSafeConfiguration() {
    if (!"true".equalsIgnoreCase(required("app.temporal.worker.enabled"))
        || "NONE".equals(required("app.temporal.worker.versioning-mode"))
        || !"LEGACY".equals(required(PREFIX + "production-formal-selector-default"))
        || !"DISABLED".equals(required(PREFIX + "activation-default"))
        || !"PROPOSAL_ONLY".equals(required(PREFIX + "graph-output-authority"))
        || !"JAVA_FINALIZER_ONLY".equals(required(PREFIX + "formal-writer"))
        || !"ATTESTED_DENY_EXTERNAL_EGRESS".equals(required(PREFIX + "network-isolation-mode"))
        || booleanProperty(PREFIX + "external-effects-enabled")) {
      throw new IllegalStateException("production runtime runtime configuration exceeds authority");
    }
    for (String property : EXTERNAL_ENDPOINT_PROPERTIES) {
      String value = environment.getProperty(property);
      if (value != null && !value.isBlank() && !"DISABLED".equalsIgnoreCase(value.trim())) {
        throw new IllegalStateException("external effect endpoint is configured: " + property);
      }
    }
    rejectGraphDomainCredentialKeys();
  }

  private void rejectGraphDomainCredentialKeys() {
    for (PropertySource<?> source : environment.getPropertySources()) {
      if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
        continue;
      }
      for (String name : enumerable.getPropertyNames()) {
        String normalized = name.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        boolean graphDomain = normalized.contains("GRAPH") && normalized.contains("DOMAIN");
        boolean credential =
            normalized.contains("PASSWORD")
                || normalized.contains("USERNAME")
                || normalized.contains("CREDENTIAL")
                || normalized.contains("JDBC_URL")
                || normalized.contains("DATASOURCE_URL");
        Object value = source.getProperty(name);
        if (graphDomain && credential && value != null && !value.toString().isBlank()) {
          throw new IllegalStateException("Graph Domain credential property is present");
        }
      }
    }
  }

  private void requireWorkerBinding(BuildBindings builds) {
    String role = required("app.temporal.worker.role");
    String buildId = required("app.temporal.worker.build-id");
    boolean matches =
        switch (role) {
          case "CONTROL" ->
              ProductionActivationContract.same(buildId, builds.caseBuildId())
                  && ProductionActivationContract.same(buildId, builds.controlBuildId());
          case "AGENT" -> ProductionActivationContract.same(buildId, builds.agentBuildId());
          default -> false;
        };
    if (!matches) {
      throw new IllegalStateException("running worker role/build does not match measured bindings");
    }
  }

  private static void requireSafePrivileges(
      DatabasePrivilegeEvidence domain, DatabasePrivilegeEvidence graph) {
    if (elevated(domain)
        || elevated(graph)
        || domain.peerPrincipalCanConnect()
        || graph.peerPrincipalCanConnect()) {
      throw new IllegalStateException("production runtime runtime retains elevated or peer privileges");
    }
  }

  private static void requirePhysicalDatabaseIsolation(
      DatabaseIdentity domain, DatabaseIdentity graph) {
    if (domain.clusterIdentity().equals(graph.clusterIdentity())
        || domain.databaseIdentity().equals(graph.databaseIdentity())
        || domain.runtimePrincipalIdentity().equals(graph.runtimePrincipalIdentity())) {
      throw new IllegalStateException(
          "production runtime Domain and Graph databases are not physically isolated");
    }
  }

  private static boolean elevated(DatabasePrivilegeEvidence privileges) {
    return privileges.superuser()
        || privileges.createRole()
        || privileges.createDatabase()
        || privileges.replication()
        || privileges.bypassRowLevelSecurity();
  }

  private Set<String> measuredProfiles() {
    Set<String> profiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
    if (!profiles.equals(Set.of("production-runtime", "control-worker"))) {
      throw new IllegalStateException("only production-runtime and control-worker profiles may be active");
    }
    return profiles;
  }

  private String readAttestation() {
    try {
      long size = Files.size(attestationPath);
      if (!Files.isRegularFile(attestationPath) || size < 1 || size > MAXIMUM_ATTESTATION_BYTES) {
        throw new IllegalStateException("runtime isolation attestation file is invalid");
      }
      return Files.readString(attestationPath, StandardCharsets.US_ASCII).trim();
    } catch (IOException failure) {
      throw new IllegalStateException("runtime isolation attestation is unreachable", failure);
    }
  }

  private Set<String> tokens(String property) {
    LinkedHashSet<String> values = new LinkedHashSet<>();
    for (String value : SPLIT.split(required(property))) {
      if (value.isBlank() || !values.add(value)) {
        throw new IllegalStateException("production runtime list property is invalid: " + property);
      }
    }
    return Set.copyOf(values);
  }

  private long positiveLong(String property) {
    try {
      return ProductionActivationContract.generation(Long.parseLong(required(property)));
    } catch (NumberFormatException failure) {
      throw new IllegalStateException(
          "production runtime integer property is invalid: " + property, failure);
    }
  }

  private boolean booleanProperty(String property) {
    String value = required(property);
    if (!Set.of("true", "false").contains(value.toLowerCase(Locale.ROOT))) {
      throw new IllegalStateException("production runtime boolean property is invalid: " + property);
    }
    return Boolean.parseBoolean(value);
  }

  private String required(String property) {
    String value = environment.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("required measured property is absent: " + property);
    }
    return value.trim();
  }

  private static Path requiredPath(ConfigurableEnvironment environment, String property) {
    String value = environment.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("runtime isolation attestation path is required");
    }
    return Path.of(value).toAbsolutePath().normalize();
  }

  interface DatabaseProbe {
    DatabaseObservation measure(DataSource dataSource);

    boolean peerPrincipalCanConnect(DataSource dataSource, String peerRoleName);
  }

  interface ArtifactProbe {
    ArtifactMeasurement measure();
  }

  record ArtifactMeasurement(String marker, String digest) {
    ArtifactMeasurement {
      if (!ARTIFACT_MARKER.equals(marker)) {
        throw new IllegalArgumentException("production runtime artifact marker is invalid");
      }
      ProductionActivationContract.sha256(digest, "artifactDigest");
    }
  }

  record DatabaseObservation(
      DatabaseIdentity identity,
      String roleName,
      boolean superuser,
      boolean createRole,
      boolean createDatabase,
      boolean replication,
      boolean bypassRowLevelSecurity) {

    DatabaseObservation {
      Objects.requireNonNull(identity, "identity");
      ProductionActivationContract.identifier(roleName, "database role name");
    }

    DatabasePrivilegeEvidence privilegesWithPeerConnect(boolean peerCanConnect) {
      return new DatabasePrivilegeEvidence(
          superuser,
          createRole,
          createDatabase,
          replication,
          bypassRowLevelSecurity,
          peerCanConnect);
    }
  }

  private static final class JdbcDatabaseProbe implements DatabaseProbe {

    private static final String IDENTITY_SQL =
        """
        select control.system_identifier::text,
               database.oid::text,
               role.oid::text,
               role.rolname,
               role.rolsuper,
               role.rolcreaterole,
               role.rolcreatedb,
               role.rolreplication,
               role.rolbypassrls
          from pg_control_system() control
          join pg_database database on database.datname = current_database()
          join pg_roles role on role.rolname = current_user
        """;
    private static final String PEER_CONNECT_SQL =
        """
        select exists (
          select 1
            from pg_roles role
            join pg_database database on database.datname = current_database()
           where role.rolname = ?
             and has_database_privilege(role.oid, database.oid, 'CONNECT'))
        """;

    @Override
    public DatabaseObservation measure(DataSource dataSource) {
      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement()) {
        statement.setQueryTimeout(5);
        try (ResultSet result = statement.executeQuery(IDENTITY_SQL)) {
          if (!result.next()) {
            throw new IllegalStateException("database identity query returned no row");
          }
          DatabaseObservation observation =
              new DatabaseObservation(
                  new DatabaseIdentity(
                      "pg-system-id/" + result.getString(1),
                      "pg-database-oid/" + result.getString(2),
                      "pg-role-oid/" + result.getString(3)),
                  result.getString(4),
                  result.getBoolean(5),
                  result.getBoolean(6),
                  result.getBoolean(7),
                  result.getBoolean(8),
                  result.getBoolean(9));
          if (result.next()) {
            throw new IllegalStateException("database identity query returned multiple rows");
          }
          return observation;
        }
      } catch (SQLException failure) {
        throw new IllegalStateException("database identity measurement failed", failure);
      }
    }

    @Override
    public boolean peerPrincipalCanConnect(DataSource dataSource, String peerRoleName) {
      try (Connection connection = dataSource.getConnection();
          PreparedStatement statement = connection.prepareStatement(PEER_CONNECT_SQL)) {
        statement.setQueryTimeout(5);
        statement.setString(1, peerRoleName);
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next()) {
            throw new IllegalStateException("database privilege query returned no row");
          }
          boolean allowed = result.getBoolean(1);
          if (result.next()) {
            throw new IllegalStateException("database privilege query returned multiple rows");
          }
          return allowed;
        }
      } catch (SQLException failure) {
        throw new IllegalStateException("database privilege measurement failed", failure);
      }
    }
  }

  private static final class CodeSourceArtifactProbe implements ArtifactProbe {

    @Override
    public ArtifactMeasurement measure() {
      try {
        CodeSource codeSource =
            SpringJdbcProductionRuntimeMeasurementProvider.class
                .getProtectionDomain()
                .getCodeSource();
        if (codeSource == null) {
          throw new IllegalStateException("production runtime artifact code source is unavailable");
        }
        Path artifact = runningArtifact();
        if (!Files.isRegularFile(artifact) || Files.size(artifact) < 1) {
          throw new IllegalStateException("running code is not the production runtime artifact");
        }
        return new ArtifactMeasurement(readMarker(artifact), sha256(artifact));
      } catch (Exception failure) {
        if (failure instanceof IllegalStateException stateFailure) {
          throw stateFailure;
        }
        throw new IllegalStateException("production runtime artifact measurement failed", failure);
      }
    }

    private static Path runningArtifact() throws IOException {
      String classPath = System.getProperty("java.class.path");
      if (classPath == null || classPath.isBlank()) {
        throw new IllegalStateException("production runtime runtime classpath is unavailable");
      }
      String[] entries = classPath.split(Pattern.quote(File.pathSeparator), -1);
      if (entries.length != 1 || entries[0].isBlank()) {
        throw new IllegalStateException("production runtime runtime must execute from one sealed jar");
      }
      return Path.of(entries[0]).toRealPath();
    }

    private static String readMarker(Path artifact) throws IOException {
      try (JarFile jar = new JarFile(artifact.toFile())) {
        var entry = jar.getJarEntry(ARTIFACT_MARKER_RESOURCE);
        if (entry == null || entry.isDirectory() || entry.getSize() > 256) {
          throw new IllegalStateException("production runtime artifact marker is absent or oversized");
        }
        try (InputStream input = jar.getInputStream(entry)) {
          byte[] bytes = input.readNBytes(257);
          if (bytes.length > 256 || input.read() != -1) {
            throw new IllegalStateException("production runtime artifact marker is oversized");
          }
          return new String(bytes, StandardCharsets.US_ASCII).strip();
        }
      }
    }

    private static String sha256(Path artifact) throws IOException, NoSuchAlgorithmException {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(artifact)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
          if (read > 0) {
            digest.update(buffer, 0, read);
          }
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    }
  }
}
