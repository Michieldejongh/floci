package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.config;
import static io.restassured.http.ContentType.TEXT;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provisions an {@code AWS::EFS::FileSystem} with a mount target and an access point through a
 * CloudFormation stack and checks that {@code Ref} and every {@code Fn::GetAtt} attribute resolve
 * to what the EFS API then serves, that an update retags the same file system, and that deleting
 * the stack removes all three. A second case drives a file system through Cloud Control's
 * {@code CreateResource}, which shares the provisioners.
 */
@QuarkusTest
class EfsCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261007/us-east-1/cloudformation/aws4_request";
    private static final String STACK = "efs-cfn-it";
    private static final String CLOUD_CONTROL = "application/x-amz-json-1.0";

    private static final String TEMPLATE = """
        {
          "Parameters": {"TagValue": {"Type": "String"}},
          "Resources": {
            "Fs": {
              "Type": "AWS::EFS::FileSystem",
              "Properties": {
                "PerformanceMode": "generalPurpose",
                "ThroughputMode": "bursting",
                "Encrypted": true,
                "KmsKeyId": "alias/aws/elasticfilesystem",
                "BackupPolicy": {"Status": "ENABLED"},
                "LifecyclePolicies": [{"TransitionToIA": "AFTER_30_DAYS"}],
                "FileSystemPolicy": {
                  "Version": "2012-10-17",
                  "Statement": [{"Effect": "Allow", "Principal": {"AWS": "*"},
                                 "Action": "elasticfilesystem:ClientMount"}]
                },
                "FileSystemTags": [{"Key": "stage", "Value": {"Ref": "TagValue"}}]
              }
            },
            "Mt": {
              "Type": "AWS::EFS::MountTarget",
              "Properties": {
                "FileSystemId": {"Ref": "Fs"},
                "SubnetId": "subnet-efscfnit",
                "SecurityGroups": ["sg-efscfnit"],
                "IpAddress": "10.0.5.20"
              }
            },
            "Ap": {
              "Type": "AWS::EFS::AccessPoint",
              "Properties": {
                "FileSystemId": {"Fn::GetAtt": ["Fs", "FileSystemId"]},
                "PosixUser": {"Uid": "1000", "Gid": "1000"},
                "RootDirectory": {
                  "Path": "/lambda",
                  "CreationInfo": {"OwnerUid": "1000", "OwnerGid": "1000", "Permissions": "0750"}
                },
                "AccessPointTags": [{"Key": "Name", "Value": "lambda"}]
              }
            }
          },
          "Outputs": {
            "FsRef": {"Value": {"Ref": "Fs"}},
            "FsId": {"Value": {"Fn::GetAtt": ["Fs", "FileSystemId"]}},
            "FsArn": {"Value": {"Fn::GetAtt": ["Fs", "Arn"]}},
            "MtRef": {"Value": {"Ref": "Mt"}},
            "MtId": {"Value": {"Fn::GetAtt": ["Mt", "Id"]}},
            "MtIp": {"Value": {"Fn::GetAtt": ["Mt", "IpAddress"]}},
            "ApRef": {"Value": {"Ref": "Ap"}},
            "ApId": {"Value": {"Fn::GetAtt": ["Ap", "AccessPointId"]}},
            "ApArn": {"Value": {"Fn::GetAtt": ["Ap", "Arn"]}}
          }
        }
        """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createUpdateAndDeleteFileSystemMountTargetAndAccessPoint() {
        cloudFormation("CreateStack", Map.of("TagValue", "v1"));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(STACK, CFN_AUTH).status());
        String created = describeStacks();

        String fsId = outputValue(created, "FsId");
        String mtId = outputValue(created, "MtId");
        String apId = outputValue(created, "ApId");
        assertTrue(fsId.startsWith("fs-"), fsId);
        assertTrue(mtId.startsWith("fsmt-"), mtId);
        assertTrue(apId.startsWith("fsap-"), apId);
        assertEquals(fsId, outputValue(created, "FsRef"));
        assertEquals(mtId, outputValue(created, "MtRef"));
        assertEquals(apId, outputValue(created, "ApRef"));
        assertEquals("10.0.5.20", outputValue(created, "MtIp"));
        assertTrue(outputValue(created, "FsArn").endsWith(":file-system/" + fsId));
        assertTrue(outputValue(created, "ApArn").endsWith(":access-point/" + apId));

        given().queryParam("FileSystemId", fsId).when().get("/2015-02-01/file-systems").then()
            .statusCode(200)
            .body("FileSystems[0].FileSystemArn", equalTo(outputValue(created, "FsArn")))
            .body("FileSystems[0].Encrypted", equalTo(true))
            .body("FileSystems[0].KmsKeyId", equalTo("alias/aws/elasticfilesystem"))
            .body("FileSystems[0].NumberOfMountTargets", equalTo(1))
            .body("FileSystems[0].Tags.find { it.Key == 'stage' }.Value", equalTo("v1"));
        given().when().get("/2015-02-01/file-systems/" + fsId + "/backup-policy").then()
            .statusCode(200).body("BackupPolicy.Status", equalTo("ENABLED"));
        given().when().get("/2015-02-01/file-systems/" + fsId + "/lifecycle-configuration").then()
            .statusCode(200).body("LifecyclePolicies[0].TransitionToIA", equalTo("AFTER_30_DAYS"));
        given().when().get("/2015-02-01/file-systems/" + fsId + "/policy").then()
            .statusCode(200).body("Policy", containsString("elasticfilesystem:ClientMount"));
        given().queryParam("MountTargetId", mtId).when().get("/2015-02-01/mount-targets").then()
            .statusCode(200)
            .body("MountTargets[0].FileSystemId", equalTo(fsId))
            .body("MountTargets[0].SubnetId", equalTo("subnet-efscfnit"))
            .body("MountTargets[0].IpAddress", equalTo("10.0.5.20"));
        given().queryParam("AccessPointId", apId).when().get("/2015-02-01/access-points").then()
            .statusCode(200)
            .body("AccessPoints[0].FileSystemId", equalTo(fsId))
            .body("AccessPoints[0].RootDirectory.Path", equalTo("/lambda"))
            .body("AccessPoints[0].RootDirectory.CreationInfo.Permissions", equalTo("0750"))
            .body("AccessPoints[0].PosixUser.Uid", equalTo(1000))
            .body("AccessPoints[0].Tags.Key", hasItem("Name"));

        cloudFormation("UpdateStack", Map.of("TagValue", "v2"));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(STACK, CFN_AUTH).status());
        String updated = describeStacks();
        assertEquals(fsId, outputValue(updated, "FsId"));
        assertEquals(mtId, outputValue(updated, "MtId"));
        assertEquals(apId, outputValue(updated, "ApId"));
        given().queryParam("FileSystemId", fsId).when().get("/2015-02-01/file-systems").then()
            .statusCode(200)
            .body("FileSystems[0].Tags.find { it.Key == 'stage' }.Value", equalTo("v2"));

        cloudFormation("DeleteStack", Map.of());
        CfnStackWaits.awaitStackDeleted(STACK, CFN_AUTH);

        given().queryParam("FileSystemId", fsId).when().get("/2015-02-01/file-systems").then()
            .statusCode(404);
        given().queryParam("FileSystemId", fsId).when().get("/2015-02-01/mount-targets").then()
            .statusCode(200).body("MountTargets.size()", equalTo(0));
    }

    @Test
    void cloudControlCreateResourceProvisionsAFileSystem() throws InterruptedException {
        String token = given()
            .config(config().encoderConfig(encoderConfig().encodeContentTypeAs(CLOUD_CONTROL, TEXT)))
            .contentType(CLOUD_CONTROL)
            .header("X-Amz-Target", "CloudApiService.CreateResource")
            .body("{\"TypeName\":\"AWS::EFS::FileSystem\",\"DesiredState\":"
                    + "\"{\\\"PerformanceMode\\\":\\\"maxIO\\\",\\\"Encrypted\\\":true}\"}")
            .when().post("/")
            .then().statusCode(200)
            .extract().path("ProgressEvent.RequestToken");

        String identifier = null;
        for (int i = 0; i < 40 && identifier == null; i++) {
            ExtractableResponse<Response> event = given()
                .config(config().encoderConfig(encoderConfig().encodeContentTypeAs(CLOUD_CONTROL, TEXT)))
                .contentType(CLOUD_CONTROL)
                .header("X-Amz-Target", "CloudApiService.GetResourceRequestStatus")
                .body("{\"RequestToken\":\"" + token + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract();
            String status = event.path("ProgressEvent.OperationStatus");
            String message = event.path("ProgressEvent.StatusMessage");
            assertNotEquals("FAILED", status, message);
            if ("SUCCESS".equals(status)) {
                identifier = event.path("ProgressEvent.Identifier");
            } else {
                Thread.sleep(100);
            }
        }
        assertNotNull(identifier, "Cloud Control never reported SUCCESS");

        given().queryParam("FileSystemId", identifier).when().get("/2015-02-01/file-systems").then()
            .statusCode(200)
            .body("FileSystems[0].FileSystemId", startsWith("fs-"))
            .body("FileSystems[0].PerformanceMode", equalTo("maxIO"))
            .body("FileSystems[0].Encrypted", equalTo(true));
    }

    private static void cloudFormation(String action, Map<String, String> parameters) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", STACK);
        if (!"DeleteStack".equals(action)) {
            request.formParam("TemplateBody", TEMPLATE);
        }
        int index = 1;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            request.formParam("Parameters.member." + index + ".ParameterKey", parameter.getKey());
            request.formParam("Parameters.member." + index + ".ParameterValue", parameter.getValue());
            index++;
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks() {
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
}
