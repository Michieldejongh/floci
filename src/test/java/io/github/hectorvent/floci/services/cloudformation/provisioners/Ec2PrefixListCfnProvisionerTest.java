package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.ManagedPrefixList;
import io.github.hectorvent.floci.services.ec2.model.PrefixListEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code AWS::EC2::PrefixList}. */
class Ec2PrefixListCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String ID = "pl-0123456789abcdef0";

    private Ec2Service ec2;
    private Ec2PrefixListCfnProvisioner provisioner;
    private CloudFormationTemplateEngine engine;

    @BeforeEach
    void setUp() {
        ec2 = mock(Ec2Service.class);
        provisioner = new Ec2PrefixListCfnProvisioner(ec2);
        engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));
        when(ec2.describeTags(anyString(), any())).thenReturn(List.of());
    }

    private static JsonNode props(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static ManagedPrefixList list(String id, String name, String family, int maxEntries, long version) {
        ManagedPrefixList l = new ManagedPrefixList();
        l.setPrefixListId(id);
        l.setPrefixListName(name);
        l.setAddressFamily(family);
        l.setMaxEntries(maxEntries);
        l.setVersion(version);
        l.setOwnerId("000000000000");
        l.setPrefixListArn("arn:aws:ec2:us-east-1:000000000000:prefix-list/" + id);
        return l;
    }

    private StackResource provision(String priorPhysicalId, String json) {
        return provision(priorPhysicalId, priorPhysicalId, json);
    }

    private StackResource provision(String contextPriorId, String resourcePhysicalId, String json) {
        StackResource r = new StackResource();
        r.setLogicalId("Pl");
        r.setResourceType("AWS::EC2::PrefixList");
        r.setPhysicalId(resourcePhysicalId);
        ProvisionContext ctx =
                new ProvisionContext(engine, REGION, "000000000000", "my-stack", contextPriorId);
        provisioner.provision(r, props(json), ctx);
        return r;
    }

    private void existing(ManagedPrefixList l, List<PrefixListEntry> entries) {
        when(ec2.describeManagedPrefixLists(REGION, List.of(l.getPrefixListId()), Map.of())).thenReturn(List.of(l));
        when(ec2.getManagedPrefixListEntries(REGION, l.getPrefixListId(), null)).thenReturn(entries);
    }

    @Test
    void createSetsRefAndEveryGetAttAttribute() {
        when(ec2.createManagedPrefixList(eq(REGION), eq("servers"), eq("IPv4"), eq(10), any(), isNull()))
                .thenReturn(list(ID, "servers", "IPv4", 10, 1));

        StackResource r = provision(null, """
                {"PrefixListName": "servers", "AddressFamily": "IPv4", "MaxEntries": 10,
                 "Entries": [{"Cidr": "10.0.0.5/32", "Description": "server 1"}, {"Cidr": "10.0.0.10/32"}],
                 "Tags": [{"Key": "team", "Value": "net"}]}
                """);

        assertEquals(ID, r.getPhysicalId());
        assertEquals(Map.of("PrefixListId", ID,
                "Arn", "arn:aws:ec2:us-east-1:000000000000:prefix-list/" + ID,
                "OwnerId", "000000000000",
                "Version", "1"), r.getAttributes());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PrefixListEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(ec2).createManagedPrefixList(eq(REGION), eq("servers"), eq("IPv4"), eq(10), entries.capture(), isNull());
        assertEquals(2, entries.getValue().size());
        assertEquals("10.0.0.5/32", entries.getValue().get(0).getCidr());
        assertEquals("server 1", entries.getValue().get(0).getDescription());
        assertNull(entries.getValue().get(1).getDescription());
        verify(ec2).createTags(eq(REGION), eq(List.of(ID)), any());
    }

    @Test
    void aPhysicalIdOnTheResourceWithoutAPriorIdIsStillACreate() {
        when(ec2.createManagedPrefixList(anyString(), anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(list(ID, "servers", "IPv4", 5, 1));

        provision(null, "pl-stale", """
                {"PrefixListName": "servers", "AddressFamily": "IPv4", "MaxEntries": 5}
                """);

        verify(ec2, never()).describeManagedPrefixLists(anyString(), any(), any());
        verify(ec2).createManagedPrefixList(anyString(), anyString(), anyString(), anyInt(), any(), any());
    }

    @Test
    void anUnchangedUpdateDoesNotModifyTheList() {
        existing(list(ID, "servers", "IPv4", 5, 3), List.of(new PrefixListEntry("10.0.0.5/32", "server 1")));

        StackResource r = provision(ID, """
                {"PrefixListName": "servers", "AddressFamily": "IPv4", "MaxEntries": 5,
                 "Entries": [{"Cidr": "10.0.0.5/32", "Description": "server 1"}]}
                """);

        assertEquals(ID, r.getPhysicalId());
        assertEquals("3", r.getAttributes().get("Version"));
        verify(ec2, never()).modifyManagedPrefixList(anyString(), anyString(), any(), any(), any(), any(), any());
        verify(ec2, never()).createManagedPrefixList(anyString(), anyString(), anyString(), anyInt(), any(), any());
    }

    @Test
    void anUpdateAddsRemovesAndRedescribesEntriesAndRenamesInOneCall() {
        ManagedPrefixList current = list(ID, "servers", "IPv4", 5, 1);
        existing(current, List.of(new PrefixListEntry("10.0.0.5/32", "server 1"),
                new PrefixListEntry("10.0.0.6/32", "gone")));
        when(ec2.modifyManagedPrefixList(eq(REGION), eq(ID), eq(1L), eq("renamed"), isNull(), any(), any()))
                .thenReturn(list(ID, "renamed", "IPv4", 5, 2));

        StackResource r = provision(ID, """
                {"PrefixListName": "renamed", "AddressFamily": "IPv4", "MaxEntries": 5,
                 "Entries": [{"Cidr": "10.0.0.5/32", "Description": "server one"}, {"Cidr": "10.0.0.7/32"}]}
                """);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PrefixListEntry>> add = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> remove = ArgumentCaptor.forClass(List.class);
        verify(ec2).modifyManagedPrefixList(eq(REGION), eq(ID), eq(1L), eq("renamed"), isNull(),
                add.capture(), remove.capture());
        assertEquals(List.of("10.0.0.5/32", "10.0.0.7/32"), add.getValue().stream().map(PrefixListEntry::getCidr).toList());
        assertEquals("server one", add.getValue().get(0).getDescription());
        assertEquals(List.of("10.0.0.6/32"), remove.getValue());
        assertEquals(ID, r.getPhysicalId());
        assertEquals("2", r.getAttributes().get("Version"));
    }

    @Test
    void growingTheListResizesBeforeChangingEntries() {
        existing(list(ID, "servers", "IPv4", 1, 1), List.of(new PrefixListEntry("10.0.0.5/32", null)));
        when(ec2.modifyManagedPrefixList(eq(REGION), eq(ID), isNull(), isNull(), eq(2), isNull(), isNull()))
                .thenReturn(list(ID, "servers", "IPv4", 2, 1));
        when(ec2.modifyManagedPrefixList(eq(REGION), eq(ID), eq(1L), isNull(), isNull(), any(), isNull()))
                .thenReturn(list(ID, "servers", "IPv4", 2, 2));

        provision(ID, """
                {"PrefixListName": "servers", "AddressFamily": "IPv4", "MaxEntries": 2,
                 "Entries": [{"Cidr": "10.0.0.5/32"}, {"Cidr": "10.0.0.6/32"}]}
                """);

        InOrder order = inOrder(ec2);
        order.verify(ec2).modifyManagedPrefixList(eq(REGION), eq(ID), isNull(), isNull(), eq(2), isNull(), isNull());
        order.verify(ec2).modifyManagedPrefixList(eq(REGION), eq(ID), eq(1L), isNull(), isNull(), any(), isNull());
    }

    @Test
    void shrinkingTheListChangesEntriesBeforeResizing() {
        existing(list(ID, "servers", "IPv4", 5, 1), List.of(new PrefixListEntry("10.0.0.5/32", null),
                new PrefixListEntry("10.0.0.6/32", null)));
        when(ec2.modifyManagedPrefixList(eq(REGION), eq(ID), eq(1L), isNull(), isNull(), isNull(), any()))
                .thenReturn(list(ID, "servers", "IPv4", 5, 2));
        when(ec2.modifyManagedPrefixList(eq(REGION), eq(ID), isNull(), isNull(), eq(1), isNull(), isNull()))
                .thenReturn(list(ID, "servers", "IPv4", 1, 2));

        provision(ID, """
                {"PrefixListName": "servers", "AddressFamily": "IPv4", "MaxEntries": 1,
                 "Entries": [{"Cidr": "10.0.0.5/32"}]}
                """);

        InOrder order = inOrder(ec2);
        order.verify(ec2).modifyManagedPrefixList(eq(REGION), eq(ID), eq(1L), isNull(), isNull(), isNull(),
                eq(List.of("10.0.0.6/32")));
        order.verify(ec2).modifyManagedPrefixList(eq(REGION), eq(ID), isNull(), isNull(), eq(1), isNull(), isNull());
    }

    @Test
    void aChangedAddressFamilyCreatesANewList() {
        existing(list(ID, "servers", "IPv4", 5, 1), List.of());
        when(ec2.createManagedPrefixList(eq(REGION), eq("servers"), eq("IPv6"), eq(5), any(), isNull()))
                .thenReturn(list("pl-new", "servers", "IPv6", 5, 1));

        StackResource r = provision(ID, """
                {"PrefixListName": "servers", "AddressFamily": "IPv6", "MaxEntries": 5}
                """);

        assertEquals("pl-new", r.getPhysicalId());
        verify(ec2, never()).modifyManagedPrefixList(anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void aListDeletedOutOfBandIsRecreatedOnUpdate() {
        when(ec2.describeManagedPrefixLists(REGION, List.of(ID), Map.of()))
                .thenThrow(new AwsException("InvalidPrefixListID.NotFound", "gone", 400));
        when(ec2.createManagedPrefixList(anyString(), anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(list("pl-new", "servers", "IPv4", 5, 1));

        StackResource r = provision(ID, """
                {"PrefixListName": "servers", "AddressFamily": "IPv4", "MaxEntries": 5}
                """);

        assertEquals("pl-new", r.getPhysicalId());
    }

    @Test
    void deleteToleratesAnAlreadyDeletedListOnly() {
        doThrow(new AwsException("InvalidPrefixListID.NotFound", "gone", 400))
                .when(ec2).deleteManagedPrefixList(REGION, ID);
        provisioner.delete("AWS::EC2::PrefixList", ID, REGION);

        doThrow(new AwsException("InvalidPrefixListModification", "in use", 400))
                .when(ec2).deleteManagedPrefixList(REGION, "pl-busy");
        assertThrows(AwsException.class, () -> provisioner.delete("AWS::EC2::PrefixList", "pl-busy", REGION));
    }

    @Test
    void deleteDeletesTheList() {
        provisioner.delete("AWS::EC2::PrefixList", ID, REGION);
        verify(ec2).deleteManagedPrefixList(REGION, ID);
    }
}
