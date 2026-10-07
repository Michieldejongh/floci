package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.config;
import static io.restassured.http.ContentType.TEXT;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Provisions the five {@code AWS::MemoryDB::*} types through a CloudFormation stack and asserts that
 * {@code Ref} and each type's {@code Fn::GetAtt} attributes resolve to what the MemoryDB API serves,
 * that an update changes the cluster's description in place, and that deleting the stack removes the
 * cluster, ACL and user. A Cloud Control {@code CreateResource} goes through the same provisioner.
 *
 * <p>Runs MemoryDB in mock mode, so the cluster gets its endpoint without a Valkey container.
 */
@QuarkusTest
@TestProfile(MemoryDbCfnIntegrationTest.MockMemoryDbProfile.class)
class MemoryDbCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261007/us-east-1/cloudformation/aws4_request";
    private static final String MEMORYDB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261007/us-east-1/memorydb/aws4_request";
    private static final String STACK = "memorydb-cfn-it";
    private static final String USER = "cfn-mdb-it-user";
    private static final String ACL = "cfn-mdb-it-acl";
    private static final String CLUSTER = "cfn-mdb-it-cluster";
    private static final String ACCOUNT = "000000000000";

    private static final String TEMPLATE = """
        {
          "Parameters": {"Description": {"Type": "String"}},
          "Resources": {
            "AppUser": {
              "Type": "AWS::MemoryDB::User",
              "Properties": {
                "UserName": "%s",
                "AccessString": "on ~* &* +@all",
                "AuthenticationMode": {"Type": "password", "Passwords": ["a-long-enough-password"]}
              }
            },
            "Acl": {
              "Type": "AWS::MemoryDB::ACL",
              "Properties": {"ACLName": "%s", "UserNames": ["default", {"Ref": "AppUser"}]}
            },
            "Subnets": {
              "Type": "AWS::MemoryDB::SubnetGroup",
              "Properties": {
                "SubnetGroupName": "cfn-mdb-it-subnets",
                "Description": "isolated",
                "SubnetIds": ["subnet-11111111"]
              }
            },
            "Params": {
              "Type": "AWS::MemoryDB::ParameterGroup",
              "Properties": {
                "ParameterGroupName": "cfn-mdb-it-params",
                "Family": "memorydb_valkey7",
                "Description": "tweaks",
                "Parameters": {"tcp-keepalive": 900}
              }
            },
            "Cluster": {
              "Type": "AWS::MemoryDB::Cluster",
              "Properties": {
                "ClusterName": "%s",
                "ACLName": {"Ref": "Acl"},
                "NodeType": "db.t4g.small",
                "Engine": "valkey",
                "EngineVersion": "7.3",
                "NumShards": 1,
                "NumReplicasPerShard": 0,
                "ParameterGroupName": {"Ref": "Params"},
                "SubnetGroupName": {"Ref": "Subnets"},
                "SecurityGroupIds": ["sg-11111111"],
                "KmsKeyId": "arn:aws:kms:us-east-1:000000000000:key/11111111-2222-3333-4444-555555555555",
                "TLSEnabled": true,
                "MaintenanceWindow": "Sun:03:00-Sun:04:00",
                "SnapshotWindow": "23:00-03:00",
                "SnapshotRetentionLimit": 1,
                "AutoMinorVersionUpgrade": true,
                "Description": {"Ref": "Description"},
                "Tags": [{"Key": "team", "Value": "core"}]
              }
            }
          },
          "Outputs": {
            "UserRef": {"Value": {"Ref": "AppUser"}},
            "UserArn": {"Value": {"Fn::GetAtt": ["AppUser", "Arn"]}},
            "UserStatus": {"Value": {"Fn::GetAtt": ["AppUser", "Status"]}},
            "AclRef": {"Value": {"Ref": "Acl"}},
            "AclArn": {"Value": {"Fn::GetAtt": ["Acl", "Arn"]}},
            "AclStatus": {"Value": {"Fn::GetAtt": ["Acl", "Status"]}},
            "SubnetsRef": {"Value": {"Ref": "Subnets"}},
            "SubnetsArn": {"Value": {"Fn::GetAtt": ["Subnets", "ARN"]}},
            "ParamsRef": {"Value": {"Ref": "Params"}},
            "ParamsArn": {"Value": {"Fn::GetAtt": ["Params", "ARN"]}},
            "ClusterRef": {"Value": {"Ref": "Cluster"}},
            "ClusterArn": {"Value": {"Fn::GetAtt": ["Cluster", "ARN"]}},
            "ClusterStatus": {"Value": {"Fn::GetAtt": ["Cluster", "Status"]}},
            "ClusterAddress": {"Value": {"Fn::GetAtt": ["Cluster", "ClusterEndpoint.Address"]}},
            "ClusterPort": {"Value": {"Fn::GetAtt": ["Cluster", "ClusterEndpoint.Port"]}}
          }
        }
        """.formatted(USER, ACL, CLUSTER);

    @BeforeAll
    static void registerAwsJsonParsers() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createUpdateAndDeleteMemoryDbResources() {
        cloudFormation("CreateStack", "v1");
        String created = describeStacks("CREATE_COMPLETE");

        assertEquals(USER, outputValue(created, "UserRef"));
        assertEquals("arn:aws:memorydb:us-east-1:" + ACCOUNT + ":user/" + USER, outputValue(created, "UserArn"));
        assertEquals("active", outputValue(created, "UserStatus"));
        assertEquals(ACL, outputValue(created, "AclRef"));
        assertEquals("arn:aws:memorydb:us-east-1:" + ACCOUNT + ":acl/" + ACL, outputValue(created, "AclArn"));
        assertEquals("active", outputValue(created, "AclStatus"));
        assertEquals("cfn-mdb-it-subnets", outputValue(created, "SubnetsRef"));
        assertEquals("arn:aws:memorydb:us-east-1:" + ACCOUNT + ":subnetgroup/cfn-mdb-it-subnets",
                outputValue(created, "SubnetsArn"));
        assertEquals("cfn-mdb-it-params", outputValue(created, "ParamsRef"));
        assertEquals("arn:aws:memorydb:us-east-1:" + ACCOUNT + ":parametergroup/cfn-mdb-it-params",
                outputValue(created, "ParamsArn"));
        assertEquals(CLUSTER, outputValue(created, "ClusterRef"));
        assertEquals("available", outputValue(created, "ClusterStatus"));

        // The GetAtt values are the cluster the MemoryDB API serves, not literal "Cluster.Attr" strings.
        memoryDb("DescribeClusters", "{\"ClusterName\":\"" + CLUSTER + "\"}")
                .statusCode(200)
                .body("Clusters[0].ARN", equalTo(outputValue(created, "ClusterArn")))
                .body("Clusters[0].ACLName", equalTo(ACL))
                .body("Clusters[0].Engine", equalTo("valkey"))
                .body("Clusters[0].EngineVersion", equalTo("7.3"))
                .body("Clusters[0].TLSEnabled", equalTo(true))
                .body("Clusters[0].Description", equalTo("v1"))
                .body("Clusters[0].ClusterEndpoint.Address", equalTo(outputValue(created, "ClusterAddress")))
                .body("Clusters[0].ClusterEndpoint.Port",
                        equalTo(Integer.parseInt(outputValue(created, "ClusterPort"))));
        memoryDb("DescribeACLs", "{\"ACLName\":\"" + ACL + "\"}")
                .statusCode(200)
                .body("ACLs[0].UserNames", equalTo(List.of("default", USER)));

        cloudFormation("UpdateStack", "v2");
        String updated = describeStacks("UPDATE_COMPLETE");
        assertEquals(outputValue(created, "ClusterArn"), outputValue(updated, "ClusterArn"));
        memoryDb("DescribeClusters", "{\"ClusterName\":\"" + CLUSTER + "\"}")
                .statusCode(200)
                .body("Clusters[0].Description", equalTo("v2"));

        cloudFormation("DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(STACK, CFN_AUTH);

        memoryDb("DescribeClusters", "{\"ClusterName\":\"" + CLUSTER + "\"}")
                .statusCode(404).body("__type", containsString("ClusterNotFoundFault"));
        memoryDb("DescribeACLs", "{\"ACLName\":\"" + ACL + "\"}")
                .statusCode(404).body("__type", containsString("ACLNotFoundFault"));
        memoryDb("DescribeUsers", "{\"UserName\":\"" + USER + "\"}")
                .statusCode(404).body("__type", containsString("UserNotFoundFault"));
    }

    @Test
    void cloudControlCreateResourceCreatesARealUser() throws InterruptedException {
        String ct = "application/x-amz-json-1.0";
        String desired = "{\\\"UserName\\\":\\\"cc-mdb-it-user\\\",\\\"AccessString\\\":\\\"on ~* +@all\\\","
                + "\\\"AuthenticationMode\\\":{\\\"Type\\\":\\\"password\\\","
                + "\\\"Passwords\\\":[\\\"a-long-enough-password\\\"]}}";
        String token = cloudControl(ct, "CreateResource",
                "{\"TypeName\":\"AWS::MemoryDB::User\",\"DesiredState\":\"" + desired + "\"}")
                .path("ProgressEvent.RequestToken");

        String identifier = null;
        for (int i = 0; i < 50 && identifier == null; i++) {
            ExtractableResponse<Response> event = cloudControl(ct, "GetResourceRequestStatus",
                    "{\"RequestToken\":\"" + token + "\"}");
            if ("SUCCESS".equals(event.path("ProgressEvent.OperationStatus"))) {
                identifier = event.path("ProgressEvent.Identifier");
            } else {
                Thread.sleep(100);
            }
        }
        assertEquals("cc-mdb-it-user", identifier);

        memoryDb("DescribeUsers", "{\"UserName\":\"cc-mdb-it-user\"}")
                .statusCode(200)
                .body("Users[0].Authentication.Type", equalTo("password"));
        memoryDb("DeleteUser", "{\"UserName\":\"cc-mdb-it-user\"}").statusCode(200);
    }

    private static ExtractableResponse<Response> cloudControl(String ct, String action, String body) {
        return given()
                .config(config().encoderConfig(encoderConfig().encodeContentTypeAs(ct, TEXT)))
                .contentType(ct)
                .header("X-Amz-Target", "CloudApiService." + action)
                .body(body)
                .when().post("/")
                .then().statusCode(200)
                .extract();
    }

    private static void cloudFormation(String action, String description) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", STACK);
        if (description != null) {
            request.formParam("TemplateBody", TEMPLATE)
                    .formParam("Parameters.member.1.ParameterKey", "Description")
                    .formParam("Parameters.member.1.ParameterValue", description);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String expectedStatus) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(STACK, CFN_AUTH);
        assertEquals(expectedStatus, state.status(), state.reason());
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", STACK)
                .when().post("/").then().statusCode(200)
                .extract().asString();
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static ValidatableResponse memoryDb(String action, String body) {
        return given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonMemoryDB." + action)
                .header("Authorization", MEMORYDB_AUTH)
                .body(body)
                .when().post("/")
                .then();
    }

    /** MemoryDB without containers: the cluster record and endpoint are what this test asserts. */
    public static final class MockMemoryDbProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.memorydb.mock", "true");
        }
    }
}
