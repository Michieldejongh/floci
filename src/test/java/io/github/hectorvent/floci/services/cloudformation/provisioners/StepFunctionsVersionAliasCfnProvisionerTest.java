package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;
import io.github.hectorvent.floci.services.stepfunctions.model.RoutingConfiguration;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachineAlias;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachineVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::StepFunctions::StateMachineVersion} and {@code AWS::StepFunctions::StateMachineAlias}:
 * a version is published (idempotently per revision) and replaced when its create-only properties
 * change; an alias is created from its routing, updated in place while its ARN holds, and replaced
 * when the name changes.
 */
class StepFunctionsVersionAliasCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String STACK = "my-stack";
    private static final String VERSION_TYPE = "AWS::StepFunctions::StateMachineVersion";
    private static final String ALIAS_TYPE = "AWS::StepFunctions::StateMachineAlias";
    private static final String SM_ARN = "arn:aws:states:us-east-1:000000000000:stateMachine:flow";
    private static final String V1 = SM_ARN + ":1";
    private static final String V2 = SM_ARN + ":2";

    private StepFunctionsService sfn;
    private StepFunctionsCfnProvisioner provisioner;
    private CloudFormationTemplateEngine engine;

    @BeforeEach
    void setUp() {
        sfn = mock(StepFunctionsService.class);
        provisioner = new StepFunctionsCfnProvisioner(sfn, mock(S3Service.class), MAPPER);
        engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));
        when(sfn.createStateMachineAlias(anyString(), any(), anyList())).thenAnswer(i ->
                alias(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
        when(sfn.updateStateMachineAlias(anyString(), any(), anyBoolean(), anyList())).thenAnswer(i -> {
            String arn = i.getArgument(0);
            return alias(arn.substring(arn.lastIndexOf(':') + 1), i.getArgument(1), i.getArgument(3));
        });
    }

    @Test
    void servesTheVersionAndAliasTypes() {
        assertTrue(provisioner.resourceTypes().containsAll(Set.of(VERSION_TYPE, ALIAS_TYPE)));
    }

    // ── StateMachineVersion ─────────────────────────────────────────────────

    @Test
    void createPublishesTheVersionAndExposesItsArn() {
        when(sfn.publishStateMachineVersion(SM_ARN, "rev-1", "first")).thenReturn(version(V1));

        StackResource r = provision(VERSION_TYPE, """
                {"StateMachineArn": "%s", "StateMachineRevisionId": "rev-1", "Description": "first"}
                """.formatted(SM_ARN), null, Map.of());

        verify(sfn).publishStateMachineVersion(SM_ARN, "rev-1", "first");
        assertEquals(V1, r.getPhysicalId(), "Ref is the version ARN");
        assertEquals(V1, r.getAttributes().get("Arn"), "Fn::GetAtt Arn is the version ARN");
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void versionWithoutStateMachineArnIsRejectedBeforeAnyCall() {
        AwsException e = assertThrows(AwsException.class,
                () -> provision(VERSION_TYPE, "{\"Description\": \"x\"}", null, Map.of()));
        assertEquals("ValidationError", e.getErrorCode());
        verifyNoInteractions(sfn);
    }

    @Test
    void updateThatPublishesANewRevisionReplacesTheVersionAndDeletesTheOldOne() {
        when(sfn.publishStateMachineVersion(SM_ARN, "rev-2", null)).thenReturn(version(V2));

        StackResource r = provision(VERSION_TYPE, """
                {"StateMachineArn": "%s", "StateMachineRevisionId": "rev-2"}
                """.formatted(SM_ARN), V1, Map.of("Arn", V1));

        assertEquals(V2, r.getPhysicalId());
        assertEquals(V2, r.getAttributes().get("Arn"));
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(V1, provisioner.updateCleanupPhysicalId(r));
        provisioner.completeUpdate(r);
        verify(sfn).deleteStateMachineVersion(V1);
        verify(sfn, never()).deleteStateMachineVersion(V2);
    }

    @Test
    void updateOnTheSameRevisionKeepsTheVersion() {
        when(sfn.publishStateMachineVersion(SM_ARN, "rev-1", null)).thenReturn(version(V1));

        StackResource r = provision(VERSION_TYPE, """
                {"StateMachineArn": "%s", "StateMachineRevisionId": "rev-1"}
                """.formatted(SM_ARN), V1, Map.of("Arn", V1));

        assertEquals(V1, r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void rollingBackAVersionReplacementDeletesTheNewVersionAndRestoresTheOld() {
        when(sfn.publishStateMachineVersion(SM_ARN, "rev-2", null)).thenReturn(version(V2));
        StackResource r = provision(VERSION_TYPE, """
                {"StateMachineArn": "%s", "StateMachineRevisionId": "rev-2"}
                """.formatted(SM_ARN), V1, Map.of("Arn", V1));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(sfn).deleteStateMachineVersion(V2);
        assertEquals(V1, r.getPhysicalId());
        assertEquals(V1, r.getAttributes().get("Arn"));
    }

    @Test
    void deletingAVersionDeletesItThroughTheService() {
        provisioner.delete(VERSION_TYPE, V1, REGION);
        verify(sfn).deleteStateMachineVersion(V1);
    }

    // ── StateMachineAlias ───────────────────────────────────────────────────

    @Test
    void createRoutesTheAliasToTheVersion() {
        StackResource r = provision(ALIAS_TYPE, """
                {"Name": "live", "Description": "prod",
                 "RoutingConfiguration": [{"StateMachineVersionArn": "%s", "Weight": 100}]}
                """.formatted(V1), null, Map.of());

        List<RoutingConfiguration> routing = capturedCreateRouting("live", "prod");
        assertEquals(1, routing.size());
        assertEquals(V1, routing.get(0).getStateMachineVersionArn());
        assertEquals(100, routing.get(0).getWeight());
        assertEquals(SM_ARN + ":live", r.getPhysicalId(), "Ref is the alias ARN");
        assertEquals(SM_ARN + ":live", r.getAttributes().get("Arn"), "Fn::GetAtt Arn is the alias ARN");
    }

    @Test
    void aDeploymentPreferenceRoutesAllTrafficToItsVersion() {
        provision(ALIAS_TYPE, """
                {"Name": "live", "DeploymentPreference": {"StateMachineVersionArn": "%s", "Type": "ALL_AT_ONCE"}}
                """.formatted(V2), null, Map.of());

        List<RoutingConfiguration> routing = capturedCreateRouting("live", null);
        assertEquals(V2, routing.get(0).getStateMachineVersionArn());
        assertEquals(100, routing.get(0).getWeight());
    }

    @Test
    void anAliasWithoutRoutingOrDeploymentPreferenceIsRejected() {
        AwsException e = assertThrows(AwsException.class,
                () -> provision(ALIAS_TYPE, "{\"Name\": \"live\"}", null, Map.of()));
        assertEquals("ValidationError", e.getErrorCode());
        verifyNoInteractions(sfn);
    }

    @Test
    void anUnnamedAliasGetsAGeneratedNameItKeepsOnUpdate() {
        StackResource created = provision(ALIAS_TYPE, routingTo(V1), null, Map.of());
        String arn = created.getPhysicalId();
        assertTrue(arn.startsWith(SM_ARN + ":" + STACK + "-Alias-"), arn);

        when(sfn.describeStateMachineAlias(arn)).thenReturn(
                alias(arn.substring(arn.lastIndexOf(':') + 1), null, List.of(new RoutingConfiguration(V1, 100))));
        StackResource updated = provision(ALIAS_TYPE, routingTo(V2), arn, created.getAttributes());

        assertEquals(arn, updated.getPhysicalId());
        verify(sfn).updateStateMachineAlias(eq(arn), isNull(), eq(true), anyList());
    }

    @Test
    void anUpdateKeepingTheArnUpdatesInPlaceAndRollsBackFromTheSnapshot() {
        String arn = SM_ARN + ":live";
        when(sfn.describeStateMachineAlias(arn)).thenReturn(
                alias("live", "old", List.of(new RoutingConfiguration(V1, 100))));

        StackResource r = provision(ALIAS_TYPE, """
                {"Name": "live", "Description": "new",
                 "RoutingConfiguration": [{"StateMachineVersionArn": "%s", "Weight": 100}]}
                """.formatted(V2), arn, Map.of("Arn", arn, "FlociStepFunctionsAliasNameMode", "explicit"));

        verify(sfn, never()).createStateMachineAlias(anyString(), any(), anyList());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RoutingConfiguration>> routing = ArgumentCaptor.forClass(List.class);
        verify(sfn).updateStateMachineAlias(eq(arn), eq("new"), eq(true), routing.capture());
        assertEquals(V2, routing.getValue().get(0).getStateMachineVersionArn());
        assertFalse(provisioner.hasReplacementUpdate(r));

        assertTrue(provisioner.rollbackUpdate(r));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RoutingConfiguration>> restored = ArgumentCaptor.forClass(List.class);
        verify(sfn).updateStateMachineAlias(eq(arn), eq("old"), eq(true), restored.capture());
        assertEquals(V1, restored.getValue().get(0).getStateMachineVersionArn());
        assertEquals(100, restored.getValue().get(0).getWeight());
    }

    @Test
    void renamingAnAliasReplacesItAndDeletesTheOldOne() {
        String oldArn = SM_ARN + ":live";
        StackResource r = provision(ALIAS_TYPE, """
                {"Name": "prod", "RoutingConfiguration": [{"StateMachineVersionArn": "%s", "Weight": 100}]}
                """.formatted(V1), oldArn, Map.of("Arn", oldArn, "FlociStepFunctionsAliasNameMode", "explicit"));

        assertEquals(SM_ARN + ":prod", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(oldArn, provisioner.updateCleanupPhysicalId(r));
        provisioner.completeUpdate(r);
        verify(sfn).deleteStateMachineAlias(oldArn);
    }

    @Test
    void deletingAnAliasThatIsAlreadyGoneIsTolerated() {
        doThrow(new AwsException("ResourceNotFound", "gone", 400))
                .when(sfn).deleteStateMachineAlias(SM_ARN + ":live");
        provisioner.delete(ALIAS_TYPE, SM_ARN + ":live", REGION);
    }

    @Test
    void deletingAnAliasStubTouchesNothing() {
        StackResource stub = resource(ALIAS_TYPE);
        stub.setPhysicalId("Alias-1a2b3c4d");
        stub.getAttributes().put("Arn", CfnResourceDispatcher.STUB_ARN_PREFIX + "Alias");
        provisioner.delete(stub, REGION);
        verifyNoInteractions(sfn);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private List<RoutingConfiguration> capturedCreateRouting(String name, String description) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RoutingConfiguration>> routing = ArgumentCaptor.forClass(List.class);
        verify(sfn).createStateMachineAlias(eq(name), eq(description), routing.capture());
        return routing.getValue();
    }

    private static String routingTo(String versionArn) {
        return """
                {"RoutingConfiguration": [{"StateMachineVersionArn": "%s", "Weight": 100}]}
                """.formatted(versionArn);
    }

    private static StateMachineVersion version(String arn) {
        return new StateMachineVersion(arn, Integer.parseInt(arn.substring(arn.lastIndexOf(':') + 1)), 0);
    }

    private static StateMachineAlias alias(String name, String description, List<RoutingConfiguration> routing) {
        StateMachineAlias alias = new StateMachineAlias();
        alias.setName(name);
        alias.setStateMachineArn(SM_ARN);
        alias.setStateMachineAliasArn(SM_ARN + ":" + name);
        alias.setDescription(description);
        alias.setRoutingConfiguration(routing);
        return alias;
    }

    private static StackResource resource(String type) {
        StackResource r = new StackResource();
        r.setLogicalId(VERSION_TYPE.equals(type) ? "Version" : "Alias");
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    /** Provisions as a create (no prior id) or an update (prior id and the attributes it left). */
    private StackResource provision(String type, String json, String priorPhysicalId,
                                    Map<String, String> priorAttributes) {
        StackResource r = resource(type);
        r.setPhysicalId(priorPhysicalId);
        r.getAttributes().putAll(priorAttributes);
        try {
            provisioner.provision(r, MAPPER.readTree(json),
                    new ProvisionContext(engine, REGION, ACCOUNT_ID, STACK, priorPhysicalId));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        return r;
    }
}
