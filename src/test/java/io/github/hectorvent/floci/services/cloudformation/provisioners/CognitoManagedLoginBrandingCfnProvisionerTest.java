package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.ManagedLoginBranding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::Cognito::ManagedLoginBranding} in isolation: Ref and {@code ManagedLoginBrandingId} are
 * the style id, an update keeping pool and client changes the style in place, a change to either
 * replaces it, and every delete is addressed by the style's own recorded pool.
 */
class CognitoManagedLoginBrandingCfnProvisionerTest {

    private static final String TYPE = "AWS::Cognito::ManagedLoginBranding";
    private static final String POOL = "us-east-1_pool";
    private static final String OTHER_POOL = "us-east-1_other";

    private final CognitoService cognito = mock(CognitoService.class);
    private final CognitoManagedLoginBrandingCfnProvisioner provisioner =
            new CognitoManagedLoginBrandingCfnProvisioner(cognito);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ManagedLoginBranding> live = new HashMap<>();

    @BeforeEach
    void cognitoKeepsTheBrandingsItIsAskedFor() {
        when(cognito.createManagedLoginBranding(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> {
                    ManagedLoginBranding b = new ManagedLoginBranding();
                    b.setManagedLoginBrandingId(UUID.randomUUID().toString());
                    b.setUserPoolId(inv.getArgument(0));
                    b.setUseCognitoProvidedValues(Boolean.TRUE.equals(inv.getArgument(2)));
                    b.setSettings(inv.getArgument(3));
                    b.setAssets(inv.getArgument(4));
                    live.put(b.getManagedLoginBrandingId(), b);
                    return b;
                });
        when(cognito.describeManagedLoginBranding(anyString(), anyString())).thenAnswer(inv -> {
            ManagedLoginBranding b = live.get(inv.<String>getArgument(1));
            if (b == null || !b.getUserPoolId().equals(inv.getArgument(0))) {
                throw new AwsException("ResourceNotFoundException", "ManagedLoginBranding does not exist.", 400);
            }
            return b;
        });
        when(cognito.updateManagedLoginBranding(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> live.get(inv.<String>getArgument(1)));
    }

    @Test
    void servesTheBrandingType() {
        assertEquals(Set.of(TYPE), provisioner.resourceTypes());
    }

    @Test
    void createProvisionsTheStyleAndSetsRefAndGetAtt() {
        StackResource r = resource(null);

        provisioner.provision(r, props(POOL, "client-a", "#112233"), ctx(null));

        verify(cognito).createManagedLoginBranding(eq(POOL), eq("client-a"), isNull(),
                eq(Map.of("categories", Map.of("global", Map.of("colorSchemeMode", "#112233")))),
                eq(List.of(Map.of("Category", "PAGE_FOOTER_BACKGROUND", "ColorMode", "DARK",
                        "Extension", "SVG", "Bytes", "PHN2Zy8+"))));
        String id = r.getPhysicalId();
        assertTrue(live.containsKey(id));
        assertEquals(id, r.getAttributes().get("ManagedLoginBrandingId"));
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void cognitoProvidedValuesAreHandedOverAsABoolean() {
        ObjectNode props = mapper.createObjectNode();
        props.put("UserPoolId", POOL);
        props.put("ClientId", "client-a");
        props.put("UseCognitoProvidedValues", true);

        provisioner.provision(resource(null), props, ctx(null));

        verify(cognito).createManagedLoginBranding(POOL, "client-a", Boolean.TRUE, null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UserPoolId", "ClientId"})
    void poolAndClientAreRequired(String missing) {
        ObjectNode props = props(POOL, "client-a", "#112233");
        props.remove(missing);

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(null), props, ctx(null)));

        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals(TYPE + " requires " + missing, failure.getMessage());
        verifyNoInteractions(cognito);
    }

    @Test
    void anUpdateKeepingPoolAndClientChangesTheStyleInPlace() {
        StackResource r = created(POOL, "client-a");
        String id = r.getPhysicalId();
        ObjectNode props = props(POOL, "client-a", "#445566");
        props.remove("Assets");

        provisioner.provision(r, props, ctx(id));

        verify(cognito).updateManagedLoginBranding(POOL, id, Boolean.FALSE,
                Map.of("categories", Map.of("global", Map.of("colorSchemeMode", "#445566"))), List.of());
        verify(cognito, times(1)).createManagedLoginBranding(anyString(), anyString(), any(), any(), any());
        assertEquals(id, r.getPhysicalId());
        assertEquals(id, r.getAttributes().get("ManagedLoginBrandingId"));
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void aClientChangeReplacesTheStyleAndTheCleanupDeletesTheOldOne() {
        StackResource r = created(POOL, "client-a");
        String oldId = r.getPhysicalId();

        provisioner.provision(r, props(POOL, "client-b", "#112233"), ctx(oldId));

        verify(cognito).createManagedLoginBranding(eq(POOL), eq("client-b"), any(), any(), any());
        assertNotEquals(oldId, r.getPhysicalId());
        assertEquals(r.getPhysicalId(), r.getAttributes().get("ManagedLoginBrandingId"));
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(oldId, provisioner.updateCleanupPhysicalId(r));

        assertTrue(provisioner.completeUpdate(r).complete());

        verify(cognito).deleteManagedLoginBranding(POOL, oldId);
        verify(cognito, never()).deleteManagedLoginBranding(POOL, r.getPhysicalId());
    }

    @Test
    void aPoolChangeDeletesTheDisplacedStyleFromItsOwnPool() {
        StackResource r = created(POOL, "client-a");
        String oldId = r.getPhysicalId();

        provisioner.provision(r, props(OTHER_POOL, "client-a", "#112233"), ctx(oldId));
        assertTrue(provisioner.completeUpdate(r).complete());

        verify(cognito).deleteManagedLoginBranding(POOL, oldId);
        verify(cognito, never()).deleteManagedLoginBranding(eq(OTHER_POOL), anyString());
    }

    @Test
    void rollingBackAReplacementDeletesTheNewStyleAndNamesTheOldOneAgain() {
        StackResource r = created(POOL, "client-a");
        String oldId = r.getPhysicalId();
        provisioner.provision(r, props(OTHER_POOL, "client-b", "#112233"), ctx(oldId));
        String newId = r.getPhysicalId();

        assertTrue(provisioner.rollbackUpdate(r));

        verify(cognito).deleteManagedLoginBranding(OTHER_POOL, newId);
        verify(cognito, never()).deleteManagedLoginBranding(POOL, oldId);
        assertEquals(oldId, r.getPhysicalId());
        assertEquals(oldId, r.getAttributes().get("ManagedLoginBrandingId"));

        // The restored style is still matched to its own pool and client, so the next update
        // carrying the original properties changes it in place rather than creating another.
        provisioner.provision(r, props(POOL, "client-a", "#778899"), ctx(oldId));
        verify(cognito).updateManagedLoginBranding(eq(POOL), eq(oldId), any(), any(), any());
        assertEquals(oldId, r.getPhysicalId());
    }

    @Test
    void rollingBackAnInPlaceUpdateRestoresThePriorStyle() {
        StackResource r = created(POOL, "client-a");
        String id = r.getPhysicalId();
        provisioner.provision(r, props(POOL, "client-a", "#445566"), ctx(id));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(cognito).updateManagedLoginBranding(POOL, id, false,
                Map.of("categories", Map.of("global", Map.of("colorSchemeMode", "#112233"))),
                List.of(Map.of("Category", "PAGE_FOOTER_BACKGROUND", "ColorMode", "DARK",
                        "Extension", "SVG", "Bytes", "PHN2Zy8+")));
    }

    @Test
    void deleteAddressesTheRecordedPoolAndToleratesAStyleAlreadyGone() {
        StackResource r = created(POOL, "client-a");
        doThrow(new AwsException("ResourceNotFoundException", "gone", 400))
                .when(cognito).deleteManagedLoginBranding(POOL, r.getPhysicalId());

        provisioner.delete(r, "us-east-1");

        verify(cognito).deleteManagedLoginBranding(POOL, r.getPhysicalId());
    }

    @Test
    void aRealDeleteFailurePropagates() {
        StackResource r = created(POOL, "client-a");
        doThrow(new AwsException("InternalErrorException", "boom", 500))
                .when(cognito).deleteManagedLoginBranding(POOL, r.getPhysicalId());

        assertThrows(AwsException.class, () -> provisioner.delete(r, "us-east-1"));
    }

    @Test
    void anIdOnlyDeleteTakesThePoolFromThePrimaryIdentifierForm() {
        String id = UUID.randomUUID().toString();

        provisioner.delete(TYPE, POOL + "|" + id, "us-east-1");

        verify(cognito).deleteManagedLoginBranding(POOL, id);
    }

    @Test
    void anIdOnlyDeleteWithoutAPoolDoesNothing() {
        provisioner.delete(TYPE, UUID.randomUUID().toString(), "us-east-1");

        verify(cognito, never()).deleteManagedLoginBranding(anyString(), anyString());
    }

    @Test
    void aBrandingThatVanishedIsCreatedAgainOnUpdate() {
        StackResource r = created(POOL, "client-a");
        String oldId = r.getPhysicalId();
        live.clear();

        provisioner.provision(r, props(POOL, "client-a", "#112233"), ctx(oldId));

        assertNotEquals(oldId, r.getPhysicalId());
        assertNull(live.get(oldId));
        assertTrue(live.containsKey(r.getPhysicalId()));
    }

    private StackResource created(String pool, String client) {
        StackResource r = resource(null);
        provisioner.provision(r, props(pool, client, "#112233"), ctx(null));
        return r;
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private ObjectNode props(String userPoolId, String clientId, String colorScheme) {
        ObjectNode props = mapper.createObjectNode();
        props.put("UserPoolId", userPoolId);
        props.put("ClientId", clientId);
        props.put("ReturnMergedResources", false);
        props.putObject("Settings").putObject("categories").putObject("global").put("colorSchemeMode", colorScheme);
        ObjectNode asset = props.putArray("Assets").addObject();
        asset.put("Category", "PAGE_FOOTER_BACKGROUND");
        asset.put("ColorMode", "DARK");
        asset.put("Extension", "SVG");
        asset.put("Bytes", "PHN2Zy8+");
        return props;
    }

    private static StackResource resource(String physicalId) {
        StackResource r = new StackResource();
        r.setLogicalId("Branding");
        r.setResourceType(TYPE);
        r.setPhysicalId(physicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }
}
