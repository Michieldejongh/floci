package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.config;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provisions an {@code AWS::AppConfig::Application}, {@code Environment} and hosted
 * {@code ConfigurationProfile} through a stack, then drives the native API with the ids the stack
 * returns: a hosted configuration version is created and deployed. Asserts {@code Ref} and the
 * schema's {@code Fn::GetAtt} attributes, an in-place update, and that deleting the stack removes
 * all three. A second test creates an application through Cloud Control {@code CreateResource}.
 */
@QuarkusTest
class CloudFormationAppConfigIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261007/us-east-1/cloudformation/aws4_request";
    private static final String STACK = "appconfig-cfn-it";
    private static final String CC_JSON = "application/x-amz-json-1.0";

    private static final String TEMPLATE = """
        {
          "Parameters": {"EnvName": {"Type": "String"}, "EnvDescription": {"Type": "String"}},
          "Resources": {
            "App": {
              "Type": "AWS::AppConfig::Application",
              "Properties": {
                "Name": "appconfig-cfn-it-app",
                "Description": "from cloudformation",
                "Tags": [{"Key": "team", "Value": "core"}]
              }
            },
            "Env": {
              "Type": "AWS::AppConfig::Environment",
              "Properties": {
                "ApplicationId": {"Ref": "App"},
                "Name": {"Ref": "EnvName"},
                "Description": {"Ref": "EnvDescription"},
                "Monitors": [{"AlarmArn": "arn:aws:cloudwatch:us-east-1:000000000000:alarm:a"}],
                "Tags": [{"Key": "team", "Value": "core"}]
              }
            },
            "Profile": {
              "Type": "AWS::AppConfig::ConfigurationProfile",
              "Properties": {
                "ApplicationId": {"Ref": "App"},
                "Name": "settings",
                "LocationUri": "hosted",
                "Type": "AWS.Freeform",
                "Validators": [{"Type": "JSON_SCHEMA", "Content": "{}"}]
              }
            }
          },
          "Outputs": {
            "AppRef": {"Value": {"Ref": "App"}},
            "AppId": {"Value": {"Fn::GetAtt": ["App", "ApplicationId"]}},
            "EnvRef": {"Value": {"Ref": "Env"}},
            "EnvId": {"Value": {"Fn::GetAtt": ["Env", "EnvironmentId"]}},
            "ProfileRef": {"Value": {"Ref": "Profile"}},
            "ProfileId": {"Value": {"Fn::GetAtt": ["Profile", "ConfigurationProfileId"]}}
          }
        }
        """;

    @Test
    void createDeployUpdateAndDeleteThroughCloudFormation() {
        cloudFormation("CreateStack", Map.of("EnvName", "prod", "EnvDescription", "v1"));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(STACK, CFN_AUTH).status());
        Map<String, String> outputs = outputs();
        String appId = outputs.get("AppRef");
        String envId = outputs.get("EnvRef");
        String profileId = outputs.get("ProfileRef");

        // Ref is the id, and each Fn::GetAtt id attribute resolves to that same id.
        assertTrue(appId.matches("[a-z0-9]{7}"), appId);
        assertEquals(appId, outputs.get("AppId"));
        assertEquals(envId, outputs.get("EnvId"));
        assertEquals(profileId, outputs.get("ProfileId"));

        given().when().get("/applications/" + appId).then().statusCode(200)
            .body("Name", equalTo("appconfig-cfn-it-app"))
            .body("Description", equalTo("from cloudformation"));
        given().when().get("/applications/" + appId + "/environments/" + envId).then().statusCode(200)
            .body("Name", equalTo("prod"))
            .body("Description", equalTo("v1"));
        given().when().get("/applications/" + appId + "/configurationprofiles/" + profileId).then().statusCode(200)
            .body("Name", equalTo("settings"))
            .body("LocationUri", equalTo("hosted"))
            .body("Type", equalTo("AWS.Freeform"));

        // The stack's ids work with the native API: create a hosted version and deploy it.
        given()
            .header("Content-Type", "application/json")
            .body("{\"feature\": true}")
            .when().post("/applications/" + appId + "/configurationprofiles/" + profileId + "/hostedconfigurationversions")
            .then().statusCode(201)
            .header("Version-Number", equalTo("1"));
        given()
            .contentType(ContentType.JSON)
            .body("{\"ConfigurationProfileId\": \"" + profileId + "\", \"ConfigurationVersion\": \"1\","
                + " \"DeploymentStrategyId\": \"AppConfig.AllAtOnce\"}")
            .when().post("/applications/" + appId + "/environments/" + envId + "/deployments")
            .then().statusCode(201)
            .body("State", equalTo("COMPLETE"));

        // Name and Description update in place: same ids, new values.
        cloudFormation("UpdateStack", Map.of("EnvName", "production", "EnvDescription", "v2"));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(STACK, CFN_AUTH).status());
        Map<String, String> updated = outputs();
        assertEquals(appId, updated.get("AppRef"));
        assertEquals(envId, updated.get("EnvRef"));
        assertEquals(profileId, updated.get("ProfileRef"));
        given().when().get("/applications/" + appId + "/environments/" + envId).then().statusCode(200)
            .body("Name", equalTo("production"))
            .body("Description", equalTo("v2"));

        cloudFormation("DeleteStack", Map.of());
        CfnStackWaits.awaitStackDeleted(STACK, CFN_AUTH);

        given().when().get("/applications/" + appId + "/environments/" + envId).then().statusCode(404);
        given().when().get("/applications/" + appId + "/configurationprofiles/" + profileId).then().statusCode(404);
        given().when().get("/applications/" + appId).then().statusCode(404);
    }

    @Test
    void cloudControlCreateResourceCreatesARealApplication() throws InterruptedException {
        String token = cloudControl("CreateResource", "{\"TypeName\":\"AWS::AppConfig::Application\","
                + "\"DesiredState\":\"{\\\"Name\\\":\\\"appconfig-cc-it\\\"}\"}")
            .path("ProgressEvent.RequestToken");
        String identifier = null;
        for (int i = 0; i < 20 && identifier == null; i++) {
            ExtractableResponse<Response> status = cloudControl("GetResourceRequestStatus",
                    "{\"RequestToken\":\"" + token + "\"}");
            if ("SUCCESS".equals(status.path("ProgressEvent.OperationStatus"))) {
                identifier = status.path("ProgressEvent.Identifier");
            } else {
                Thread.sleep(100);
            }
        }
        assertNotNull(identifier);
        assertNotEquals("", identifier);

        given().when().get("/applications/" + identifier).then().statusCode(200)
            .body("Name", equalTo("appconfig-cc-it"))
            .body("Description", nullValue());

        cloudControl("DeleteResource", "{\"TypeName\":\"AWS::AppConfig::Application\",\"Identifier\":\""
                + identifier + "\"}");
        given().when().get("/applications/" + identifier).then().statusCode(404);
    }

    private static ExtractableResponse<Response> cloudControl(String operation, String body) {
        return given()
            .config(config().encoderConfig(encoderConfig().encodeContentTypeAs(CC_JSON, ContentType.TEXT)))
            .contentType(CC_JSON)
            .header("X-Amz-Target", "CloudApiService." + operation)
            .body(body)
            .when().post("/")
            .then().statusCode(200)
            .extract();
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

    private static Map<String, String> outputs() {
        String xml = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", STACK)
            .when().post("/").then().statusCode(200)
            .extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue");
    }
}
