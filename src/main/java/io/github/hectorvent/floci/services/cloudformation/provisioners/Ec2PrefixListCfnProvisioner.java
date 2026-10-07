package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.ManagedPrefixList;
import io.github.hectorvent.floci.services.ec2.model.PrefixListEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EC2::PrefixList}: a real customer-managed prefix
 * list, so a security group rule naming it through {@code SourcePrefixListId} or
 * {@code DestinationPrefixListId} resolves.
 *
 * <p>An update modifies the list in place through {@code ModifyManagedPrefixList}. AWS rejects a
 * call that changes the entries and the size together, so a resize runs as its own call: before
 * the entry change when the list grows, after it when the list shrinks. A changed
 * {@code AddressFamily} cannot be modified, so it creates a new list and the engine deletes the
 * displaced one.
 */
@ApplicationScoped
public class Ec2PrefixListCfnProvisioner implements CfnResourceProvisioner {

    private static final String NOT_FOUND = "InvalidPrefixListID.NotFound";

    private final Ec2Service ec2Service;

    @Inject
    public Ec2PrefixListCfnProvisioner(Ec2Service ec2Service) {
        this.ec2Service = ec2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::EC2::PrefixList");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        String name = ctx.resolveOptional(props, "PrefixListName");
        String addressFamily = ctx.resolveOptional(props, "AddressFamily");
        String maxEntriesValue = ctx.resolveOptional(props, "MaxEntries");
        Integer maxEntries = maxEntriesValue != null && !maxEntriesValue.isBlank()
                ? Integer.valueOf(maxEntriesValue.trim()) : null;
        Map<String, String> entries = resolveEntries(props, ctx.engine());

        ManagedPrefixList existing = ctx.isUpdate()
                ? existingListToReconcile(ctx.priorPhysicalId(), addressFamily, region) : null;
        ManagedPrefixList list = existing != null
                ? update(existing, name, maxEntries, entries, region)
                : ec2Service.createManagedPrefixList(region, name, addressFamily, maxEntries,
                        toEntryList(entries), null);

        r.setPhysicalId(list.getPrefixListId());
        r.getAttributes().put("PrefixListId", list.getPrefixListId());
        r.getAttributes().put("Arn", list.getPrefixListArn());
        r.getAttributes().put("OwnerId", list.getOwnerId());
        r.getAttributes().put("Version", String.valueOf(list.getVersion()));
        Ec2Tags.reconcile(ec2Service, region, list.getPrefixListId(), ctx.resolveTags(props, "Tags"));
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("prefix list", physicalId,
                () -> ec2Service.deleteManagedPrefixList(region, physicalId), NOT_FOUND);
    }

    /**
     * The list this resource already points at, or null when it should be created instead: the
     * list was deleted out of band, or the template changed its {@code AddressFamily}, which
     * {@code ModifyManagedPrefixList} cannot change.
     */
    private ManagedPrefixList existingListToReconcile(String priorPhysicalId, String addressFamily, String region) {
        ManagedPrefixList existing;
        try {
            existing = ec2Service.describeManagedPrefixLists(region, List.of(priorPhysicalId), Map.of())
                    .stream().findFirst().orElse(null);
        } catch (AwsException e) {
            if (!NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            return null;
        }
        if (existing == null || (addressFamily != null && !addressFamily.equals(existing.getAddressFamily()))) {
            return null;
        }
        return existing;
    }

    private ManagedPrefixList update(ManagedPrefixList existing, String name, Integer maxEntries,
                                     Map<String, String> desired, String region) {
        String id = existing.getPrefixListId();
        Map<String, String> current = new LinkedHashMap<>();
        for (PrefixListEntry entry : ec2Service.getManagedPrefixListEntries(region, id, null)) {
            current.put(entry.getCidr(), entry.getDescription());
        }
        List<String> remove = new ArrayList<>();
        for (String cidr : current.keySet()) {
            if (!desired.containsKey(cidr)) {
                remove.add(cidr);
            }
        }
        // An existing CIDR with a new description is re-added: the add replaces the entry.
        List<PrefixListEntry> add = new ArrayList<>();
        for (Map.Entry<String, String> entry : desired.entrySet()) {
            if (!current.containsKey(entry.getKey())
                    || !Objects.equals(blankToNull(current.get(entry.getKey())), blankToNull(entry.getValue()))) {
                add.add(new PrefixListEntry(entry.getKey(), entry.getValue()));
            }
        }
        String rename = name != null && !name.isBlank() && !name.equals(existing.getPrefixListName()) ? name : null;
        Integer resize = maxEntries != null && !maxEntries.equals(existing.getMaxEntries()) ? maxEntries : null;
        boolean entriesChange = !add.isEmpty() || !remove.isEmpty();

        ManagedPrefixList list = existing;
        boolean growFirst = resize != null && resize > existing.getMaxEntries();
        if (growFirst) {
            list = ec2Service.modifyManagedPrefixList(region, id, null, rename, resize, null, null);
            rename = null;
        }
        if (entriesChange || rename != null) {
            list = ec2Service.modifyManagedPrefixList(region, id, list.getVersion(), rename, null,
                    add.isEmpty() ? null : add, remove.isEmpty() ? null : remove);
        }
        if (resize != null && !growFirst) {
            list = ec2Service.modifyManagedPrefixList(region, id, null, null, resize, null, null);
        }
        return list;
    }

    /** {@code Entries} as CIDR to description, in template order. */
    private static Map<String, String> resolveEntries(JsonNode props, CloudFormationTemplateEngine engine) {
        Map<String, String> entries = new LinkedHashMap<>();
        if (props == null || !props.has("Entries")) {
            return entries;
        }
        JsonNode resolved = engine.resolveNode(props.get("Entries"));
        if (resolved == null || !resolved.isArray()) {
            return entries;
        }
        for (JsonNode entry : resolved) {
            String cidr = engine.resolve(entry.path("Cidr"));
            if (cidr == null || cidr.isBlank()) {
                continue;
            }
            entries.put(cidr, engine.resolve(entry.path("Description")));
        }
        return entries;
    }

    private static List<PrefixListEntry> toEntryList(Map<String, String> entries) {
        List<PrefixListEntry> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            list.add(new PrefixListEntry(entry.getKey(), entry.getValue()));
        }
        return list;
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
