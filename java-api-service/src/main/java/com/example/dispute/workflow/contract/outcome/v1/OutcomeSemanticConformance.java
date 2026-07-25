package com.example.dispute.workflow.contract.outcome.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class OutcomeSemanticConformance {

    static final String MANIFEST_FILE = "outcome-semantic-conformance.v1.json";
    static final String PROTOCOL_VERSION = "outcome-semantic-conformance.v1";
    static final String RAW_SCHEMA_ONLY_STATUS = "NON_CONFORMANT";

    private static final String VALIDITY_RULE = "ALL_REQUIRED_STAGES_MUST_PASS";
    private static final List<String> REQUIRED_STAGES =
            List.of("DRAFT_2020_12_SCHEMA", "OUTCOME_SEMANTIC_RULES_V1");
    private static final String REVIEW_WINDOW_RULE = "review-window-order.v1";
    private static final String CAUSAL_REVISION_RULE = "causal-revision-adjacency.v1";
    private static final String TERMINAL_SUCCESS_COUNT_RULE =
            "terminal-success-count-equality.v1";
    private static final Set<String> ROOT_FIELDS = Set.of(
            "protocol_version",
            "raw_schema_only_validation",
            "validity_rule",
            "validity_claim_without_all_stages",
            "unsupported_protocol_or_rule",
            "required_stages",
            "rules");
    private static final Set<String> RULE_FIELDS = Set.of(
            "rule_id",
            "operator",
            "left_field",
            "right_field",
            "schema_files",
            "negative_fixtures");
    private static final Set<String> CONDITIONAL_RULE_FIELDS = Set.of(
            "rule_id",
            "operator",
            "left_field",
            "right_field",
            "condition",
            "schema_files",
            "negative_fixtures");
    private static final Set<String> CONDITION_FIELDS = Set.of("field", "operator", "values");
    private static final Set<String> FIXTURE_FIELDS = Set.of("schema_file", "fixture");
    private static final Set<String> DECLARATION_FIELDS =
            Set.of(
                    "protocol_version",
                    "manifest",
                    "raw_schema_only_validation",
                    "required_rules");
    private static final Set<String> CAUSAL_SCHEMAS = Set.of(
            "outcome-reviewer-decision-receipt.schema.json",
            "outcome-sla-escalation-receipt.schema.json",
            "outcome-operation-command.schema.json",
            "outcome-operation-receipt.schema.json",
            "outcome-execution-attempt-observation.schema.json",
            "outcome-attempt-reconciliation-receipt.schema.json",
            "outcome-compensation-receipt.schema.json",
            "outcome-closure-receipt.schema.json",
            "outcome-evaluation-receipt.schema.json");

    private final Map<String, List<Rule>> rulesBySchema;

    private OutcomeSemanticConformance(Map<String, List<Rule>> rulesBySchema) {
        this.rulesBySchema = rulesBySchema;
    }

    static OutcomeSemanticConformance load(
            Path contractRoot, ObjectMapper mapper, Set<String> registeredSchemas) {
        Path root = contractRoot.toAbsolutePath().normalize();
        JsonNode manifest = readJson(mapper, confinedRegularFile(root, MANIFEST_FILE));
        requireExactFields(manifest, ROOT_FIELDS, MANIFEST_FILE);
        requireText(manifest, "protocol_version", PROTOCOL_VERSION, MANIFEST_FILE);
        requireText(
                manifest,
                "raw_schema_only_validation",
                RAW_SCHEMA_ONLY_STATUS,
                MANIFEST_FILE);
        requireText(manifest, "validity_rule", VALIDITY_RULE, MANIFEST_FILE);
        requireText(
                manifest,
                "validity_claim_without_all_stages",
                "FORBIDDEN",
                MANIFEST_FILE);
        requireText(
                manifest,
                "unsupported_protocol_or_rule",
                "REJECT",
                MANIFEST_FILE);
        if (!textList(manifest.required("required_stages"), "required_stages")
                .equals(REQUIRED_STAGES)) {
            throw malformed("required_stages must publish both validation stages in order");
        }

        Map<String, Rule> rulesById = new LinkedHashMap<>();
        JsonNode rulesNode = manifest.required("rules");
        if (!rulesNode.isArray() || rulesNode.isEmpty()) {
            throw malformed("rules must be a non-empty array");
        }
        for (JsonNode ruleNode : rulesNode) {
            requireExactFields(
                    ruleNode,
                    ruleNode.has("condition") ? CONDITIONAL_RULE_FIELDS : RULE_FIELDS,
                    "semantic rule");
            Rule rule = parseRule(root, mapper, registeredSchemas, ruleNode);
            if (rulesById.putIfAbsent(rule.id(), rule) != null) {
                throw malformed("duplicate rule_id " + rule.id());
            }
        }
        if (!rulesById.keySet().equals(Set.of(
                REVIEW_WINDOW_RULE, CAUSAL_REVISION_RULE, TERMINAL_SUCCESS_COUNT_RULE))) {
            throw malformed("protocol v1 must publish exactly the frozen semantic rules");
        }
        verifyFrozenRuleShapes(rulesById);

        Map<String, List<Rule>> mutableBySchema = new LinkedHashMap<>();
        for (Rule rule : rulesById.values()) {
            for (String schemaFile : rule.schemaFiles()) {
                mutableBySchema.computeIfAbsent(schemaFile, ignored -> new ArrayList<>()).add(rule);
            }
        }
        verifySchemaDeclarations(root, mapper, registeredSchemas, mutableBySchema);

        Map<String, List<Rule>> immutableBySchema = new LinkedHashMap<>();
        mutableBySchema.forEach((schema, rules) -> immutableBySchema.put(schema, List.copyOf(rules)));
        return new OutcomeSemanticConformance(Map.copyOf(immutableBySchema));
    }

    void validate(String schemaFile, JsonNode instance) {
        for (Rule rule : rulesBySchema.getOrDefault(schemaFile, List.of())) {
            try {
                if (rule.condition() != null && !rule.condition().matches(instance)) {
                    continue;
                }
                switch (rule.operator()) {
                    case EPOCH_MILLI_REPRESENTABLE_INSTANT_STRICTLY_BEFORE ->
                        OutcomeWireTypes.reviewWindow(
                            Instant.parse(instance.required(rule.leftField()).textValue()),
                            Instant.parse(instance.required(rule.rightField()).textValue()));
                    case SAFE_INTEGER_SUCCESSOR -> OutcomeWireTypes.eventOrder(
                            instance.required(rule.leftField()).longValue(),
                            instance.required(rule.rightField()).longValue(),
                            instance.required("committed_event_sequence").longValue());
                    case SAFE_INTEGER_EQUALS -> {
                        if (instance.required(rule.leftField()).longValue()
                                != instance.required(rule.rightField()).longValue()) {
                            throw new IllegalArgumentException(
                                    rule.leftField() + " must equal " + rule.rightField());
                        }
                    }
                }
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(
                        schemaFile + " is invalid under " + rule.id() + ": "
                                + exception.getMessage(),
                        exception);
            }
        }
    }

    Set<String> ruleIds(String schemaFile) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        rulesBySchema.getOrDefault(schemaFile, List.of()).forEach(rule -> ids.add(rule.id()));
        return Set.copyOf(ids);
    }

    private static Rule parseRule(
            Path root,
            ObjectMapper mapper,
            Set<String> registeredSchemas,
            JsonNode ruleNode) {
        String id = requiredText(ruleNode, "rule_id", "semantic rule");
        Operator operator;
        try {
            operator = Operator.valueOf(requiredText(ruleNode, "operator", id));
        } catch (IllegalArgumentException exception) {
            throw malformed("unsupported operator for " + id, exception);
        }
        String leftField = requiredText(ruleNode, "left_field", id);
        String rightField = requiredText(ruleNode, "right_field", id);
        Condition condition = ruleNode.has("condition")
                ? parseCondition(ruleNode.required("condition"), id)
                : null;
        Set<String> schemaFiles = uniqueTextSet(ruleNode.required("schema_files"), id + " schema_files");
        if (schemaFiles.isEmpty() || !registeredSchemas.containsAll(schemaFiles)) {
            throw malformed(id + " references an empty or unregistered schema set");
        }

        Set<FixtureRef> negativeFixtures = new LinkedHashSet<>();
        JsonNode fixtureNodes = ruleNode.required("negative_fixtures");
        if (!fixtureNodes.isArray() || fixtureNodes.isEmpty()) {
            throw malformed(id + " must publish at least one negative fixture");
        }
        for (JsonNode fixtureNode : fixtureNodes) {
            requireExactFields(fixtureNode, FIXTURE_FIELDS, id + " negative fixture");
            String schemaFile = requiredText(fixtureNode, "schema_file", id);
            String fixture = requiredText(fixtureNode, "fixture", id);
            if (!schemaFiles.contains(schemaFile)) {
                throw malformed(id + " fixture references a schema outside the rule");
            }
            JsonNode schema = readJson(mapper, confinedRegularFile(root, schemaFile));
            JsonNode value = readJson(mapper, confinedRegularFile(root, fixture));
            String expectedVersion = schema.required("properties")
                    .required("schema_version")
                    .required("const")
                    .textValue();
            if (!expectedVersion.equals(value.required("schema_version").textValue())) {
                throw malformed(id + " fixture schema_version does not match " + schemaFile);
            }
            if (!negativeFixtures.add(new FixtureRef(schemaFile, fixture))) {
                throw malformed(id + " contains a duplicate negative fixture");
            }
        }
        return new Rule(
                id,
                operator,
                leftField,
                rightField,
                condition,
                schemaFiles,
                negativeFixtures);
    }

    private static Condition parseCondition(JsonNode conditionNode, String ruleId) {
        requireExactFields(conditionNode, CONDITION_FIELDS, ruleId + " condition");
        requireText(conditionNode, "operator", "IN", ruleId + " condition");
        return new Condition(
                requiredText(conditionNode, "field", ruleId + " condition"),
                uniqueTextSet(conditionNode.required("values"), ruleId + " condition values"));
    }

    private static void verifyFrozenRuleShapes(Map<String, Rule> rulesById) {
        Rule reviewWindow = rulesById.get(REVIEW_WINDOW_RULE);
        requireRuleShape(
                reviewWindow,
                Operator.EPOCH_MILLI_REPRESENTABLE_INSTANT_STRICTLY_BEFORE,
                "review_opened_at",
                "review_deadline_at",
                null,
                Set.of("outcome-workflow-start.schema.json"),
                Set.of(new FixtureRef(
                        "outcome-workflow-start.schema.json",
                        "fixtures/invalid/outcome-workflow-start-invalid-review-window.json")));

        Rule causalRevision = rulesById.get(CAUSAL_REVISION_RULE);
        requireRuleShape(
                causalRevision,
                Operator.SAFE_INTEGER_SUCCESSOR,
                "source_revision",
                "revision",
                null,
                CAUSAL_SCHEMAS,
                Set.of(new FixtureRef(
                        "outcome-reviewer-decision-receipt.schema.json",
                        "fixtures/invalid/outcome-reviewer-decision-revision-gap.json")));

        Rule terminalSuccessCount = rulesById.get(TERMINAL_SUCCESS_COUNT_RULE);
        requireRuleShape(
                terminalSuccessCount,
                Operator.SAFE_INTEGER_EQUALS,
                "terminal_success_receipt_count",
                "required_operation_count",
                new Condition("phase", Set.of("CLOSED", "EVALUATED")),
                Set.of("outcome-process-projection.schema.json"),
                Set.of(new FixtureRef(
                        "outcome-process-projection.schema.json",
                        "fixtures/invalid/outcome-process-projection-closed-success-count-mismatch.json")));
    }

    private static void requireRuleShape(
            Rule actual,
            Operator operator,
            String leftField,
            String rightField,
            Condition condition,
            Set<String> schemaFiles,
            Set<FixtureRef> negativeFixtures) {
        if (actual.operator() != operator
                || !actual.leftField().equals(leftField)
                || !actual.rightField().equals(rightField)
                || !java.util.Objects.equals(actual.condition(), condition)
                || !actual.schemaFiles().equals(schemaFiles)
                || !actual.negativeFixtures().equals(negativeFixtures)) {
            throw malformed(actual.id() + " does not match the frozen v1 rule shape");
        }
    }

    private static void verifySchemaDeclarations(
            Path root,
            ObjectMapper mapper,
            Set<String> registeredSchemas,
            Map<String, List<Rule>> rulesBySchema) {
        for (String schemaFile : registeredSchemas) {
            JsonNode schema = readJson(mapper, confinedRegularFile(root, schemaFile));
            List<Rule> requiredRules = rulesBySchema.getOrDefault(schemaFile, List.of());
            JsonNode declaration = schema.get("x-semantic-conformance");
            if (requiredRules.isEmpty()) {
                if (declaration != null) {
                    throw malformed(schemaFile + " declares semantic rules not present in the manifest");
                }
                continue;
            }
            if (declaration == null) {
                throw malformed(schemaFile + " omits mandatory x-semantic-conformance");
            }
            requireExactFields(declaration, DECLARATION_FIELDS, schemaFile + " declaration");
            requireText(declaration, "protocol_version", PROTOCOL_VERSION, schemaFile);
            requireText(declaration, "manifest", MANIFEST_FILE, schemaFile);
            requireText(
                    declaration,
                    "raw_schema_only_validation",
                    RAW_SCHEMA_ONLY_STATUS,
                    schemaFile);
            Set<String> declaredRules = uniqueTextSet(
                    declaration.required("required_rules"), schemaFile + " required_rules");
            Set<String> expectedRules = new LinkedHashSet<>();
            for (Rule rule : requiredRules) {
                expectedRules.add(rule.id());
                JsonNode properties = schema.required("properties");
                if (!properties.has(rule.leftField()) || !properties.has(rule.rightField())) {
                    throw malformed(schemaFile + " omits fields required by " + rule.id());
                }
                if (rule.condition() != null) {
                    verifyConditionField(schemaFile, schema, rule);
                }
            }
            if (!declaredRules.equals(expectedRules)) {
                throw malformed(schemaFile + " semantic declaration differs from the manifest");
            }
        }
    }

    private static void verifyConditionField(String schemaFile, JsonNode schema, Rule rule) {
        Condition condition = rule.condition();
        JsonNode conditionProperty = schema.required("properties").get(condition.field());
        if (conditionProperty == null || !conditionProperty.has("enum")) {
            throw malformed(schemaFile + " omits condition field enum for " + rule.id());
        }
        Set<String> allowedValues = uniqueTextSet(
                conditionProperty.required("enum"), schemaFile + " " + condition.field() + " enum");
        if (!allowedValues.containsAll(condition.values())) {
            throw malformed(schemaFile + " cannot represent every condition value for " + rule.id());
        }
    }

    private static Path confinedRegularFile(Path root, String relativePath) {
        Path candidate = root.resolve(relativePath).normalize();
        if (!candidate.startsWith(root)
                || Files.isSymbolicLink(candidate)
                || !Files.isRegularFile(candidate)) {
            throw malformed(relativePath + " is not a confined regular contract file");
        }
        try {
            Path realRoot = root.toRealPath();
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot)) {
                throw malformed(relativePath + " resolves outside the contract root");
            }
            return realCandidate;
        } catch (IOException exception) {
            throw malformed("cannot resolve " + relativePath, exception);
        }
    }

    private static JsonNode readJson(ObjectMapper mapper, Path path) {
        try {
            return mapper.readTree(path.toFile());
        } catch (IOException exception) {
            throw malformed("cannot read " + path.getFileName(), exception);
        }
    }

    private static void requireExactFields(JsonNode value, Set<String> expected, String context) {
        if (!value.isObject()) {
            throw malformed(context + " must be an object");
        }
        Set<String> actual = new TreeSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw malformed(context + " fields are not closed: " + actual);
        }
    }

    private static void requireText(
            JsonNode value, String field, String expected, String context) {
        String actual = requiredText(value, field, context);
        if (!expected.equals(actual)) {
            throw malformed(context + " requires " + field + "=" + expected);
        }
    }

    private static String requiredText(JsonNode value, String field, String context) {
        JsonNode fieldValue = value.required(field);
        if (!fieldValue.isTextual() || fieldValue.textValue().isBlank()) {
            throw malformed(context + " requires non-blank text field " + field);
        }
        return fieldValue.textValue();
    }

    private static List<String> textList(JsonNode value, String context) {
        if (!value.isArray()) {
            throw malformed(context + " must be an array");
        }
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw malformed(context + " must contain only non-blank strings");
            }
            result.add(item.textValue());
        });
        return List.copyOf(result);
    }

    private static Set<String> uniqueTextSet(JsonNode value, String context) {
        List<String> values = textList(value, context);
        LinkedHashSet<String> unique = new LinkedHashSet<>(values);
        if (unique.size() != values.size()) {
            throw malformed(context + " contains duplicate values");
        }
        return Set.copyOf(unique);
    }

    private static IllegalStateException malformed(String message) {
        return new IllegalStateException(MANIFEST_FILE + " is invalid: " + message);
    }

    private static IllegalStateException malformed(String message, Throwable cause) {
        return new IllegalStateException(MANIFEST_FILE + " is invalid: " + message, cause);
    }

    private enum Operator {
        EPOCH_MILLI_REPRESENTABLE_INSTANT_STRICTLY_BEFORE,
        SAFE_INTEGER_SUCCESSOR,
        SAFE_INTEGER_EQUALS
    }

    private record FixtureRef(String schemaFile, String fixture) {}

    private record Condition(String field, Set<String> values) {
        private Condition {
            values = Set.copyOf(values);
            if (values.isEmpty()) {
                throw malformed("condition values must not be empty");
            }
        }

        boolean matches(JsonNode instance) {
            return values.contains(instance.required(field).textValue());
        }
    }

    private record Rule(
            String id,
            Operator operator,
            String leftField,
            String rightField,
            Condition condition,
            Set<String> schemaFiles,
            Set<FixtureRef> negativeFixtures) {}
}
