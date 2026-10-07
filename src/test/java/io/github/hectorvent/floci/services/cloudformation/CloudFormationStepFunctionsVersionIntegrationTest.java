package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.config;
import static io.restassured.http.ContentType.TEXT;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end: CloudFormation publishes a real {@code AWS::StepFunctions::StateMachineVersion} and
 * routes a real {@code AWS::StepFunctions::StateMachineAlias} to it, instead of stubbing both.
 * Cloud Control's {@code CreateResource} goes through the same provisioner.
 */
@QuarkusTest
class CloudFormationStepFunctionsVersionIntegrationTest {

    private static final String SFN_CT = "application/x-amz-json-1.0";
    private static final String CC_CT = "application/x-amz-json-1.0";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void versionAndAliasArePublishedAndFollowANewRevision() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "sfn-version-" + suffix;
        String machineName = "sfn-version-machine-" + suffix;
        String machineArn = "arn:aws:states:us-east-1:000000000000:stateMachine:" + machineName;

        cfn("CreateStack", stackName, template(machineName, "first"));
        String describe = describeStack(stackName, "CREATE_COMPLETE");

        String revision = output(describe, "Revision");
        String versionArn = output(describe, "VersionArn");
        String aliasArn = output(describe, "AliasArn");
        assertNotNull(revision);
        assertEquals(machineArn + ":1", versionArn, "Fn::GetAtt Version.Arn is the published version");
        assertEquals(versionArn, output(describe, "VersionRef"), "Ref Version is the version ARN");
        assertEquals(machineArn + ":live", aliasArn, "Fn::GetAtt Alias.Arn is the alias ARN");
        assertEquals(aliasArn, output(describe, "AliasRef"));

        sfn("ListStateMachineVersions", "{\"stateMachineArn\":\"" + machineArn + "\"}")
                .body("stateMachineVersions.size()", equalTo(1))
                .body("stateMachineVersions[0].stateMachineVersionArn", equalTo(versionArn));
        sfn("DescribeStateMachine", "{\"stateMachineArn\":\"" + versionArn + "\"}")
                .body("revisionId", equalTo(revision))
                .body("description", equalTo("v-first"));
        sfn("DescribeStateMachineAlias", "{\"stateMachineAliasArn\":\"" + aliasArn + "\"}")
                .body("routingConfiguration[0].stateMachineVersionArn", equalTo(versionArn))
                .body("routingConfiguration[0].weight", equalTo(100));

        // A new definition is a new revision: the version is replaced and the alias follows it.
        cfn("UpdateStack", stackName, template(machineName, "second"));
        describe = describeStack(stackName, "UPDATE_COMPLETE");
        String newVersionArn = output(describe, "VersionArn");
        assertEquals(machineArn + ":2", newVersionArn);
        sfn("DescribeStateMachineAlias", "{\"stateMachineAliasArn\":\"" + aliasArn + "\"}")
                .body("routingConfiguration[0].stateMachineVersionArn", equalTo(newVersionArn));
        List<String> versions = sfn("ListStateMachineVersions", "{\"stateMachineArn\":\"" + machineArn + "\"}")
                .extract().path("stateMachineVersions.stateMachineVersionArn");
        assertEquals(List.of(newVersionArn), versions, "the displaced version is deleted after the update");

        cfn("DeleteStack", stackName, null);
        given().contentType(SFN_CT).header("X-Amz-Target", "AWSStepFunctions.DescribeStateMachineAlias")
                .body("{\"stateMachineAliasArn\":\"" + aliasArn + "\"}")
                .when().post("/").then().statusCode(400)
                .body("__type", containsString("ResourceNotFound"));
    }

    @Test
    void cloudControlCreateResourcePublishesTheVersion() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String machineName = "cc-version-machine-" + suffix;
        String machineArn = sfn("CreateStateMachine", """
                {"name":"%s","roleArn":"arn:aws:iam::000000000000:role/r",
                 "definition":"{\\"StartAt\\":\\"D\\",\\"States\\":{\\"D\\":{\\"Type\\":\\"Pass\\",\\"End\\":true}}}"}
                """.formatted(machineName)).extract().path("stateMachineArn");

        String token = given().config(config().encoderConfig(encoderConfig().encodeContentTypeAs(CC_CT, TEXT)))
                .contentType(CC_CT)
                .header("X-Amz-Target", "CloudApiService.CreateResource")
                .body("{\"TypeName\":\"AWS::StepFunctions::StateMachineVersion\",\"DesiredState\":"
                        + "\"{\\\"StateMachineArn\\\":\\\"" + machineArn + "\\\"}\"}")
                .when().post("/").then().statusCode(200)
                .extract().path("ProgressEvent.RequestToken");

        String identifier = null;
        for (int i = 0; i < 20 && identifier == null; i++) {
            ExtractableResponse<Response> event = given()
                    .config(config().encoderConfig(encoderConfig().encodeContentTypeAs(CC_CT, TEXT)))
                    .contentType(CC_CT)
                    .header("X-Amz-Target", "CloudApiService.GetResourceRequestStatus")
                    .body("{\"RequestToken\":\"" + token + "\"}")
                    .when().post("/").then().statusCode(200).extract();
            if ("SUCCESS".equals(event.path("ProgressEvent.OperationStatus"))) {
                identifier = event.path("ProgressEvent.Identifier");
            } else {
                Thread.sleep(100);
            }
        }
        assertEquals(machineArn + ":1", identifier);
        sfn("ListStateMachineVersions", "{\"stateMachineArn\":\"" + machineArn + "\"}")
                .body("stateMachineVersions[0].stateMachineVersionArn", equalTo(machineArn + ":1"));
    }

    private static String template(String machineName, String marker) {
        return """
                {
                  "Resources": {
                    "Machine": {
                      "Type": "AWS::StepFunctions::StateMachine",
                      "Properties": {
                        "StateMachineName": "%1$s",
                        "RoleArn": "arn:aws:iam::000000000000:role/sfn-version-role",
                        "DefinitionString": "{\\"StartAt\\":\\"D\\",\\"States\\":{\\"D\\":{\\"Type\\":\\"Pass\\",\\"Result\\":\\"%2$s\\",\\"End\\":true}}}"
                      }
                    },
                    "Version": {
                      "Type": "AWS::StepFunctions::StateMachineVersion",
                      "Properties": {
                        "StateMachineArn": {"Ref": "Machine"},
                        "StateMachineRevisionId": {"Fn::GetAtt": ["Machine", "StateMachineRevisionId"]},
                        "Description": "v-%2$s"
                      }
                    },
                    "Alias": {
                      "Type": "AWS::StepFunctions::StateMachineAlias",
                      "Properties": {
                        "Name": "live",
                        "RoutingConfiguration": [
                          {"StateMachineVersionArn": {"Ref": "Version"}, "Weight": 100}
                        ]
                      }
                    }
                  },
                  "Outputs": {
                    "Revision": {"Value": {"Fn::GetAtt": ["Machine", "StateMachineRevisionId"]}},
                    "VersionArn": {"Value": {"Fn::GetAtt": ["Version", "Arn"]}},
                    "VersionRef": {"Value": {"Ref": "Version"}},
                    "AliasArn": {"Value": {"Fn::GetAtt": ["Alias", "Arn"]}},
                    "AliasRef": {"Value": {"Ref": "Alias"}}
                  }
                }
                """.formatted(machineName, marker);
    }

    private static ValidatableResponse sfn(String target, String body) {
        return given().contentType(SFN_CT).header("X-Amz-Target", "AWSStepFunctions." + target)
                .body(body).when().post("/").then().statusCode(200);
    }

    private static void cfn(String action, String stackName, String template) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("StackName", stackName);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStack(String stackName, String expectedStatus) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
                .when().post("/")
                .then().statusCode(200)
                .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
                .extract().asString();
    }

    private static String output(String describeXml, String key) {
        return XmlPath.from(describeXml).getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member"
                + ".Outputs.member.find { it.OutputKey == '" + key + "' }.OutputValue");
    }
}
