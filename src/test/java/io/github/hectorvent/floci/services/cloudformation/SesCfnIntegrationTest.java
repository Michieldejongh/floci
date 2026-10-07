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

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.config;
import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.http.ContentType.TEXT;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Provisions the four SES types through a CloudFormation stack and asserts that each one exists in
 * the SES stores: {@code Ref}, {@code Fn::GetAtt Id} and the six DKIM {@code Fn::GetAtt} attributes
 * resolve to what the SESv2 API serves, a configuration set made here takes event destinations from
 * both the v1 Query API and the v2 REST API, an update changes options in place, and deleting the
 * stack removes everything. A second test creates a configuration set through Cloud Control
 * {@code CreateResource} and adds a classic v1 event destination to it.
 */
@QuarkusTest
class SesCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261007/us-east-1/cloudformation/aws4_request";
    private static final String SES_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261007/us-east-1/email/aws4_request";
    private static final String STACK = "ses-cfn-it";
    private static final String CONFIG_SET = "ses-cfn-it-cs";
    private static final String POOL = "ses-cfn-it-pool";
    private static final String DOMAIN = "ses-cfn-it.example.com";
    private static final String EMAIL = "sender@ses-cfn-it.example.com";
    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000000:ses-cfn-it-events";

    private static final String TEMPLATE = """
        {
          "Parameters": {"Sending": {"Type": "String"}, "TagValue": {"Type": "String"}},
          "Resources": {
            "Pool": {
              "Type": "AWS::SES::DedicatedIpPool",
              "Properties": {"PoolName": "%s", "ScalingMode": "STANDARD"}
            },
            "ConfigSet": {
              "Type": "AWS::SES::ConfigurationSet",
              "Properties": {
                "Name": "%s",
                "DeliveryOptions": {"SendingPoolName": {"Ref": "Pool"}, "TlsPolicy": "REQUIRE"},
                "ReputationOptions": {"ReputationMetricsEnabled": false},
                "SendingOptions": {"SendingEnabled": {"Ref": "Sending"}},
                "SuppressionOptions": {"SuppressedReasons": ["BOUNCE", "COMPLAINT"]},
                "VdmOptions": {"DashboardOptions": {"EngagementMetrics": "ENABLED"}},
                "Tags": [{"Key": "stack", "Value": {"Ref": "TagValue"}}]
              }
            },
            "Destination": {
              "Type": "AWS::SES::ConfigurationSetEventDestination",
              "Properties": {
                "ConfigurationSetName": {"Ref": "ConfigSet"},
                "EventDestination": {
                  "Name": "cfn-sns",
                  "Enabled": true,
                  "MatchingEventTypes": ["send", "bounce", "renderingFailure"],
                  "SnsDestination": {"TopicARN": "%s"}
                }
              }
            },
            "Domain": {
              "Type": "AWS::SES::EmailIdentity",
              "Properties": {
                "EmailIdentity": "%s",
                "ConfigurationSetAttributes": {"ConfigurationSetName": {"Ref": "ConfigSet"}},
                "FeedbackAttributes": {"EmailForwardingEnabled": false},
                "MailFromAttributes": {"MailFromDomain": "bounce.%s", "BehaviorOnMxFailure": "REJECT_MESSAGE"}
              }
            },
            "Email": {
              "Type": "AWS::SES::EmailIdentity",
              "DependsOn": "Domain",
              "Properties": {"EmailIdentity": "%s"}
            }
          },
          "Outputs": {
            "PoolRef": {"Value": {"Ref": "Pool"}},
            "ConfigSetRef": {"Value": {"Ref": "ConfigSet"}},
            "DestinationRef": {"Value": {"Ref": "Destination"}},
            "DestinationId": {"Value": {"Fn::GetAtt": ["Destination", "Id"]}},
            "DomainRef": {"Value": {"Ref": "Domain"}},
            "TokenName1": {"Value": {"Fn::GetAtt": ["Domain", "DkimDNSTokenName1"]}},
            "TokenName2": {"Value": {"Fn::GetAtt": ["Domain", "DkimDNSTokenName2"]}},
            "TokenName3": {"Value": {"Fn::GetAtt": ["Domain", "DkimDNSTokenName3"]}},
            "TokenValue1": {"Value": {"Fn::GetAtt": ["Domain", "DkimDNSTokenValue1"]}},
            "TokenValue2": {"Value": {"Fn::GetAtt": ["Domain", "DkimDNSTokenValue2"]}},
            "TokenValue3": {"Value": {"Fn::GetAtt": ["Domain", "DkimDNSTokenValue3"]}},
            "EmailTokenName1": {"Value": {"Fn::GetAtt": ["Email", "DkimDNSTokenName1"]}}
          }
        }
        """.formatted(POOL, CONFIG_SET, TOPIC, DOMAIN, DOMAIN, EMAIL);

    @BeforeAll
    static void registerAwsJsonParser() {
        RestAssured.registerParser("application/x-amz-json-1.0", Parser.JSON);
    }

    @Test
    void createUpdateAndDeleteSesResources() {
        cloudFormation("CreateStack", Map.of("Sending", "true", "TagValue", "v1"));
        String created = describeStacks("CREATE_COMPLETE");

        assertEquals(POOL, outputValue(created, "PoolRef"));
        assertEquals(CONFIG_SET, outputValue(created, "ConfigSetRef"));
        assertEquals("cfn-sns", outputValue(created, "DestinationRef"));
        assertEquals("cfn-sns", outputValue(created, "DestinationId"));
        assertEquals(DOMAIN, outputValue(created, "DomainRef"));

        sesV2("/v2/email/dedicated-ip-pools/" + POOL)
            .statusCode(200)
            .body("DedicatedIpPool.PoolName", equalTo(POOL))
            .body("DedicatedIpPool.ScalingMode", equalTo("STANDARD"));
        sesV2("/v2/email/configuration-sets/" + CONFIG_SET)
            .statusCode(200)
            .body("DeliveryOptions.SendingPoolName", equalTo(POOL))
            .body("DeliveryOptions.TlsPolicy", equalTo("REQUIRE"))
            .body("SendingOptions.SendingEnabled", equalTo(true))
            .body("ReputationOptions.ReputationMetricsEnabled", equalTo(false))
            .body("SuppressionOptions.SuppressedReasons", hasItems("BOUNCE", "COMPLAINT"))
            .body("VdmOptions.DashboardOptions.EngagementMetrics", equalTo("ENABLED"))
            .body("Tags.find { it.Key == 'stack' }.Value", equalTo("v1"));
        sesV2("/v2/email/configuration-sets/" + CONFIG_SET + "/event-destinations")
            .statusCode(200)
            .body("EventDestinations[0].Name", equalTo("cfn-sns"))
            .body("EventDestinations[0].Enabled", equalTo(true))
            .body("EventDestinations[0].MatchingEventTypes", hasItems("SEND", "BOUNCE", "RENDERING_FAILURE"))
            .body("EventDestinations[0].SnsDestination.TopicArn", equalTo(TOPIC));

        // The DKIM attributes are the CNAME records SES asks the domain owner to publish.
        List<String> tokens = sesV2("/v2/email/identities/" + DOMAIN)
            .statusCode(200)
            .body("ConfigurationSetName", equalTo(CONFIG_SET))
            .body("FeedbackForwardingStatus", equalTo(false))
            .body("MailFromAttributes.MailFromDomain", equalTo("bounce." + DOMAIN))
            .body("MailFromAttributes.BehaviorOnMxFailure", equalTo("REJECT_MESSAGE"))
            .extract().path("DkimAttributes.Tokens");
        assertEquals(3, tokens.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(tokens.get(i) + "._domainkey." + DOMAIN, outputValue(created, "TokenName" + (i + 1)));
            assertEquals(tokens.get(i) + ".dkim.amazonses.com", outputValue(created, "TokenValue" + (i + 1)));
        }
        // An address reports its parent domain's DKIM, as SES does.
        assertEquals(tokens.get(0) + "._domainkey." + DOMAIN, outputValue(created, "EmailTokenName1"));
        sesV2("/v2/email/identities/" + EMAIL).statusCode(200).body("IdentityType", equalTo("EMAIL_ADDRESS"));

        // The set CloudFormation made is the one the classic and v2 event destination APIs address.
        addV1EventDestination(CONFIG_SET, "classic-v1");
        given()
            .contentType("application/json")
            .header("Authorization", SES_AUTH)
            .body("""
                {"EventDestinationName": "added-v2",
                 "EventDestination": {"Enabled": true, "MatchingEventTypes": ["DELIVERY"],
                   "SnsDestination": {"TopicArn": "%s"}}}
                """.formatted(TOPIC))
        .when().post("/v2/email/configuration-sets/" + CONFIG_SET + "/event-destinations")
        .then().statusCode(200);
        sesV2("/v2/email/configuration-sets/" + CONFIG_SET + "/event-destinations")
            .statusCode(200)
            .body("EventDestinations.Name", hasItems("cfn-sns", "classic-v1", "added-v2"));

        cloudFormation("UpdateStack", Map.of("Sending", "false", "TagValue", "v2"));
        describeStacks("UPDATE_COMPLETE");

        // Options change on the same set; the destinations added outside the stack survive.
        sesV2("/v2/email/configuration-sets/" + CONFIG_SET)
            .statusCode(200)
            .body("SendingOptions.SendingEnabled", equalTo(false))
            .body("Tags.find { it.Key == 'stack' }.Value", equalTo("v2"));
        sesV2("/v2/email/configuration-sets/" + CONFIG_SET + "/event-destinations")
            .statusCode(200)
            .body("EventDestinations.Name", hasItems("cfn-sns", "classic-v1", "added-v2"));

        cloudFormation("DeleteStack", Map.of());
        CfnStackWaits.awaitStackDeleted(STACK);

        sesV2("/v2/email/configuration-sets/" + CONFIG_SET).statusCode(404);
        sesV2("/v2/email/identities/" + DOMAIN).statusCode(404);
        sesV2("/v2/email/identities/" + EMAIL).statusCode(404);
        sesV2("/v2/email/dedicated-ip-pools/" + POOL).statusCode(404);
    }

    @Test
    void configurationSetCreatedThroughCloudControlTakesAClassicEventDestination() throws InterruptedException {
        String name = "ses-cloudcontrol-cs";
        String ct = "application/x-amz-json-1.0";
        String token = given()
            .config(config().encoderConfig(encoderConfig().encodeContentTypeAs(ct, TEXT)))
            .contentType(ct)
            .header("X-Amz-Target", "CloudApiService.CreateResource")
            .body("{\"TypeName\":\"AWS::SES::ConfigurationSet\",\"DesiredState\":\"{\\\"Name\\\":\\\""
                + name + "\\\",\\\"SendingOptions\\\":{\\\"SendingEnabled\\\":true}}\"}")
        .when().post("/")
        .then().statusCode(200)
            .extract().path("ProgressEvent.RequestToken");

        String identifier = null;
        for (int i = 0; i < 50 && identifier == null; i++) {
            ExtractableResponse<Response> progress = given()
                .config(config().encoderConfig(encoderConfig().encodeContentTypeAs(ct, TEXT)))
                .contentType(ct)
                .header("X-Amz-Target", "CloudApiService.GetResourceRequestStatus")
                .body("{\"RequestToken\":\"" + token + "\"}")
            .when().post("/")
            .then().statusCode(200)
                .extract();
            if ("SUCCESS".equals(progress.path("ProgressEvent.OperationStatus"))) {
                identifier = progress.path("ProgressEvent.Identifier");
            } else {
                Thread.sleep(100);
            }
        }
        assertNotNull(identifier, "Cloud Control never reported SUCCESS");
        assertEquals(name, identifier);

        addV1EventDestination(name, "classic-after-cc");
        sesV2("/v2/email/configuration-sets/" + name + "/event-destinations")
            .statusCode(200)
            .body("EventDestinations[0].Name", equalTo("classic-after-cc"));
    }

    private static void addV1EventDestination(String configSet, String destinationName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", SES_AUTH)
            .formParam("Action", "CreateConfigurationSetEventDestination")
            .formParam("ConfigurationSetName", configSet)
            .formParam("EventDestination.Name", destinationName)
            .formParam("EventDestination.Enabled", "true")
            .formParam("EventDestination.MatchingEventTypes.member.1", "send")
            .formParam("EventDestination.SNSDestination.TopicARN", TOPIC)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("CreateConfigurationSetEventDestinationResponse"));
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

    private static String describeStacks(String expectedStatus) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(STACK);
        assertEquals(expectedStatus, state.status(), state.reason());
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", STACK)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static ValidatableResponse sesV2(String path) {
        return given().header("Authorization", SES_AUTH).when().get(path).then();
    }
}
