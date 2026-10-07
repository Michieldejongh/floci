package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AWS::EC2::PrefixList} creates a real customer-managed prefix list: {@code Ref} and the
 * four {@code Fn::GetAtt} attributes resolve to it, a security group egress rule naming it through
 * {@code DestinationPrefixListId} is authorized (a stubbed id fails that with
 * {@code InvalidPrefixListID.NotFound}), an update changes entries and name in place and bumps
 * {@code Version}, and {@code DeleteStack} removes the list.
 */
@QuarkusTest
class CloudFormationEc2PrefixListIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260907/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260907/us-east-1/ec2/aws4_request";
    private static final String STACK = "ec2-prefix-list-it";

    private static String template(String vpcId, String name, String entries) {
        return """
        {
          "Resources": {
            "Pl": {"Type": "AWS::EC2::PrefixList",
                   "Properties": {"PrefixListName": "%2$s", "AddressFamily": "IPv4", "MaxEntries": 5,
                                  "Entries": [%3$s],
                                  "Tags": [{"Key": "team", "Value": "net"}]}},
            "Sg": {"Type": "AWS::EC2::SecurityGroup",
                   "Properties": {"GroupName": "prefix-list-it-sg", "GroupDescription": "uses the prefix list", "VpcId": "%1$s",
                                  "SecurityGroupEgress": [{"IpProtocol": "tcp", "FromPort": 443, "ToPort": 443,
                                                           "DestinationPrefixListId": {"Ref": "Pl"}}]}}
          },
          "Outputs": {
            "PlRef": {"Value": {"Ref": "Pl"}},
            "PlId": {"Value": {"Fn::GetAtt": ["Pl", "PrefixListId"]}},
            "PlArn": {"Value": {"Fn::GetAtt": ["Pl", "Arn"]}},
            "PlOwner": {"Value": {"Fn::GetAtt": ["Pl", "OwnerId"]}},
            "PlVersion": {"Value": {"Fn::GetAtt": ["Pl", "Version"]}},
            "SgId": {"Value": {"Ref": "Sg"}}
          }
        }
        """.formatted(vpcId, name, entries);
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void prefixListIsRealUsableBySecurityGroupsUpdatedInPlaceAndDeleted() throws Exception {
        String vpcId = between(ec2("CreateVpc", Map.of("CidrBlock", "10.52.0.0/16")), "<vpcId>", "</vpcId>");
        String oneEntry = "{\"Cidr\": \"10.0.0.5/32\", \"Description\": \"server 1\"}";
        cloudFormation("CreateStack", template(vpcId, "servers", oneEntry));

        Map<String, String> out = outputs("CREATE_COMPLETE");
        String plId = out.get("PlRef");
        assertTrue(plId.startsWith("pl-"), "Ref must be a real prefix list id: " + plId);
        assertEquals(plId, out.get("PlId"));
        assertTrue(out.get("PlArn").endsWith(":prefix-list/" + plId), out.get("PlArn"));
        assertEquals("000000000000", out.get("PlOwner"));
        assertEquals("1", out.get("PlVersion"));

        String described = ec2("DescribeManagedPrefixLists", Map.of("PrefixListId.1", plId));
        assertTrue(described.contains("<prefixListName>servers</prefixListName>"), described);
        assertTrue(described.contains("<maxEntries>5</maxEntries>"), described);
        assertTrue(described.contains("<key>team</key>"), "tags must reach EC2: " + described);
        String entries = ec2("GetManagedPrefixListEntries", Map.of("PrefixListId", plId));
        assertTrue(entries.contains("10.0.0.5/32") && entries.contains("server 1"), entries);

        String groups = ec2("DescribeSecurityGroups", Map.of("GroupId.1", out.get("SgId")));
        assertTrue(groups.contains("<prefixListId>" + plId + "</prefixListId>"),
                "the egress rule must name the prefix list: " + groups);

        String twoEntries = "{\"Cidr\": \"10.0.0.10/32\", \"Description\": \"server 2\"}, "
                + "{\"Cidr\": \"10.0.0.5/32\", \"Description\": \"server one\"}";
        cloudFormation("UpdateStack", template(vpcId, "servers-renamed", twoEntries));
        Map<String, String> updated = outputs("UPDATE_COMPLETE");
        assertEquals(plId, updated.get("PlRef"), "entries and name update in place");
        assertEquals("2", updated.get("PlVersion"), "an entry change bumps the version");
        assertEquals(out.get("SgId"), updated.get("SgId"));
        String renamed = ec2("DescribeManagedPrefixLists", Map.of("PrefixListId.1", plId));
        assertTrue(renamed.contains("<prefixListName>servers-renamed</prefixListName>"), renamed);
        String updatedEntries = ec2("GetManagedPrefixListEntries", Map.of("PrefixListId", plId));
        assertTrue(updatedEntries.contains("10.0.0.10/32"), updatedEntries);
        assertTrue(updatedEntries.contains("server one") && !updatedEntries.contains("server 1"), updatedEntries);

        cloudFormation("DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(STACK);
        assertFalse(ec2("DescribeManagedPrefixLists").contains(plId), "stack delete must remove " + plId);
        ec2("DeleteVpc", Map.of("VpcId", vpcId));
    }

    private static Map<String, String> outputs(String expectedStatus) {
        return XmlParser.extractPairs(describeStacks(expectedStatus), "Outputs", "OutputKey", "OutputValue");
    }

    private static String between(String haystack, String open, String close) {
        int start = haystack.indexOf(open);
        int end = haystack.indexOf(close, start);
        assertTrue(start >= 0 && end > start, "missing " + open + " in " + haystack);
        return haystack.substring(start + open.length(), end);
    }

    private static void cloudFormation(String action, String templateBody) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", STACK);
        if (templateBody != null) {
            request.formParam("TemplateBody", templateBody);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", STACK)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    private static String ec2(String action) {
        return ec2(action, Map.of());
    }

    private static String ec2(String action, Map<String, String> params) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", action)
            .formParam("Version", "2016-11-15");
        params.forEach(request::formParam);
        return request.when().post("/").then().statusCode(200).extract().asString();
    }
}
