package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.parsing.Parser;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.config;
import static io.restassured.http.ContentType.TEXT;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An AppSync Event API and a channel namespace wired to it with {@code Fn::GetAtt Api.ApiId}, the
 * shape Pulumi aws-native and the CDK produce, through a stack and through Cloud Control. An
 * unprovisioned type is stubbed as a success with a fake id, so the assertions are on the real
 * control plane and on the exact GetAtt values, not on the stack status.
 */
@QuarkusTest
class AppSyncEventApiCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/cloudformation/aws4_request";
    private static final String APPSYNC_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/appsync/aws4_request";
    private static final String STACK = "appsync-event-api-cfn-it";
    private static final String CLOUD_CONTROL = "application/x-amz-json-1.0";

    private static final String TEMPLATE = """
        {
          "Parameters": {"LogLevel": {"Type": "String"}},
          "Resources": {
            "EventApi": {
              "Type": "AWS::AppSync::Api",
              "Properties": {
                "Name": "event-api-cfn-it",
                "EventConfig": {
                  "AuthProviders": [{"AuthType": "API_KEY"}],
                  "ConnectionAuthModes": [{"AuthType": "API_KEY"}],
                  "DefaultPublishAuthModes": [{"AuthType": "API_KEY"}],
                  "DefaultSubscribeAuthModes": [{"AuthType": "API_KEY"}],
                  "LogConfig": {"LogLevel": {"Ref": "LogLevel"},
                                "CloudWatchLogsRoleArn": "arn:aws:iam::000000000000:role/logs"}
                },
                "Tags": [{"Key": "env", "Value": "dev"}]
              }
            },
            "Chat": {
              "Type": "AWS::AppSync::ChannelNamespace",
              "Properties": {
                "ApiId": {"Fn::GetAtt": ["EventApi", "ApiId"]},
                "Name": "chat",
                "PublishAuthModes": [{"AuthType": "API_KEY"}],
                "HandlerConfigs": {"OnPublish": {"Behavior": "DIRECT",
                  "Integration": {"DataSourceName": "fn", "LambdaConfig": {"InvokeType": "EVENT"}}}}
              }
            }
          },
          "Outputs": {
            "ApiRef": {"Value": {"Ref": "EventApi"}},
            "ApiId": {"Value": {"Fn::GetAtt": ["EventApi", "ApiId"]}},
            "ApiArn": {"Value": {"Fn::GetAtt": ["EventApi", "ApiArn"]}},
            "HttpDns": {"Value": {"Fn::GetAtt": ["EventApi", "Dns.Http"]}},
            "RealtimeDns": {"Value": {"Fn::GetAtt": ["EventApi", "Dns.Realtime"]}},
            "ChatRef": {"Value": {"Ref": "Chat"}},
            "ChatArn": {"Value": {"Fn::GetAtt": ["Chat", "ChannelNamespaceArn"]}}
          }
        }
        """;

    @BeforeAll
    static void registerCloudControlParser() {
        RestAssured.registerParser(CLOUD_CONTROL, Parser.JSON);
    }

    @Test
    void stackCreatesUpdatesAndDeletesAnEventApiWithANamespace() {
        cloudFormation("CreateStack", Map.of("LogLevel", "ERROR"));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(STACK, CFN_AUTH).status());
        Map<String, String> outputs = outputs();

        String apiId = outputs.get("ApiId");
        String apiArn = "arn:aws:appsync:us-east-1:000000000000:apis/" + apiId;
        assertTrue(apiId.matches("[0-9a-f]{26}"), apiId);
        assertEquals(apiArn, outputs.get("ApiRef"));
        assertEquals(apiArn, outputs.get("ApiArn"));
        assertEquals("localhost:4566/v2/apis/" + apiId, outputs.get("HttpDns"));
        assertEquals("localhost:4566/v2/apis/" + apiId, outputs.get("RealtimeDns"));
        assertEquals(apiArn + "/channelNamespaces/chat", outputs.get("ChatRef"));
        assertEquals(apiArn + "/channelNamespaces/chat", outputs.get("ChatArn"));

        appSync("/v2/apis/" + apiId)
            .body("api.name", equalTo("event-api-cfn-it"))
            .body("api.tags.env", equalTo("dev"))
            .body("api.eventConfig.logConfig.logLevel", equalTo("ERROR"));
        appSync("/v2/apis/" + apiId + "/channelNamespaces/chat")
            .body("channelNamespace.apiId", equalTo(apiId))
            .body("channelNamespace.publishAuthModes[0].authType", equalTo("API_KEY"))
            .body("channelNamespace.handlerConfigs.onPublish.integration.dataSourceName", equalTo("fn"));

        cloudFormation("UpdateStack", Map.of("LogLevel", "ALL"));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(STACK, CFN_AUTH).status());
        assertEquals(apiId, outputs().get("ApiId"));
        appSync("/v2/apis/" + apiId).body("api.eventConfig.logConfig.logLevel", equalTo("ALL"));
        appSync("/v2/apis/" + apiId + "/channelNamespaces/chat").body("channelNamespace.name", equalTo("chat"));

        cloudFormation("DeleteStack", Map.of());
        CfnStackWaits.awaitStackDeleted(STACK, CFN_AUTH);
        given().header("Authorization", APPSYNC_AUTH).when().get("/v2/apis/" + apiId).then().statusCode(404);
    }

    @Test
    void cloudControlCreatesAnEventApiAndANamespaceUnderIt() throws InterruptedException {
        String apiArn = createThroughCloudControl("AWS::AppSync::Api", """
            {"Name": "event-api-cc-it", "EventConfig": {
              "AuthProviders": [{"AuthType": "API_KEY"}],
              "ConnectionAuthModes": [{"AuthType": "API_KEY"}],
              "DefaultPublishAuthModes": [{"AuthType": "API_KEY"}],
              "DefaultSubscribeAuthModes": [{"AuthType": "API_KEY"}]}}
            """);
        assertTrue(apiArn.startsWith("arn:aws:appsync:us-east-1:000000000000:apis/"), apiArn);
        String apiId = apiArn.substring(apiArn.lastIndexOf('/') + 1);
        appSync("/v2/apis/" + apiId).body("api.name", equalTo("event-api-cc-it"));

        String namespaceArn = createThroughCloudControl("AWS::AppSync::ChannelNamespace",
                "{\"ApiId\": \"" + apiId + "\", \"Name\": \"updates\"}");
        assertEquals(apiArn + "/channelNamespaces/updates", namespaceArn);
        appSync("/v2/apis/" + apiId + "/channelNamespaces/updates").body("channelNamespace.apiId", equalTo(apiId));

        deleteThroughCloudControl("AWS::AppSync::ChannelNamespace", namespaceArn);
        deleteThroughCloudControl("AWS::AppSync::Api", apiArn);
        given().header("Authorization", APPSYNC_AUTH).when().get("/v2/apis/" + apiId).then().statusCode(404);
    }

    private static String createThroughCloudControl(String typeName, String desiredState)
            throws InterruptedException {
        String body = "{\"TypeName\": \"" + typeName + "\", \"DesiredState\": "
                + quote(desiredState.strip()) + "}";
        String token = cloudControl("CloudApiService.CreateResource", body)
                .extract().path("ProgressEvent.RequestToken");
        for (int i = 0; i < 50; i++) {
            ExtractableResponse<Response> event = cloudControl("CloudApiService.GetResourceRequestStatus",
                    "{\"RequestToken\": \"" + token + "\"}").extract();
            String status = event.path("ProgressEvent.OperationStatus");
            if ("SUCCESS".equals(status)) {
                String identifier = event.path("ProgressEvent.Identifier");
                assertNotNull(identifier);
                return identifier;
            }
            if ("FAILED".equals(status)) {
                fail(typeName + " CreateResource failed: " + event.path("ProgressEvent.StatusMessage"));
            }
            Thread.sleep(100);
        }
        fail(typeName + " CreateResource did not finish");
        return null;
    }

    private static void deleteThroughCloudControl(String typeName, String identifier) {
        cloudControl("CloudApiService.DeleteResource",
                "{\"TypeName\": \"" + typeName + "\", \"Identifier\": \"" + identifier + "\"}")
            .body("ProgressEvent.OperationStatus", equalTo("SUCCESS"));
    }

    private static ValidatableResponse cloudControl(String target, String body) {
        return given()
                .config(config().encoderConfig(encoderConfig().encodeContentTypeAs(CLOUD_CONTROL, TEXT)))
                .contentType(CLOUD_CONTROL)
                .header("X-Amz-Target", target)
                .body(body)
            .when().post("/")
            .then().statusCode(200);
    }

    private static String quote(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
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
        .when().post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue");
    }

    private static ValidatableResponse appSync(String path) {
        return given().header("Authorization", APPSYNC_AUTH).when().get(path).then().statusCode(200);
    }
}
