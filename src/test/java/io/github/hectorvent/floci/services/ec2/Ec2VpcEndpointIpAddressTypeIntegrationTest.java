package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.xml.HasXPath.hasXPath;

/**
 * CreateVpcEndpoint and ModifyVpcEndpoint accept {@code IpAddressType} and
 * {@code DnsOptions.DnsRecordIpType}, and DescribeVpcEndpoints reports them as
 * {@code ipAddressType} and {@code dnsOptions/dnsRecordIpType}. Terraform and Pulumi read both back
 * after every apply; an emulator that drops them plans an {@code ip_address_type} update on every
 * run.
 *
 * <p>An interface endpoint created without either parameter reports {@code ipv4} for both, the
 * AWS default. A gateway endpoint reports {@code ipv4} as its address type and a
 * {@code dnsOptions} block without a record type, which is what the Terraform provider's
 * {@code TestAccVPCEndpoint_gatewayBasic} observes against AWS.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_VpcEndpoint.html">VpcEndpoint</a>
 */
@QuarkusTest
class Ec2VpcEndpointIpAddressTypeIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String ITEM =
            "//*[local-name()='vpcEndpointSet']/*[local-name()='item']";
    private static final String IP_ADDRESS_TYPE = ITEM + "/*[local-name()='ipAddressType']";
    private static final String DNS_RECORD_IP_TYPE =
            ITEM + "/*[local-name()='dnsOptions']/*[local-name()='dnsRecordIpType']";

    private ValidatableResponse ec2(String action, String... formParams) {
        RequestSpecification req = given().formParam("Action", action)
                .header("Authorization", AUTH_HEADER);
        for (int i = 0; i < formParams.length; i += 2) {
            req = req.formParam(formParams[i], formParams[i + 1]);
        }
        return req.when().post("/").then();
    }

    private String ec2Value(String action, String element, String... formParams) {
        return ec2(action, formParams).statusCode(200).extract().path(element);
    }

    private String createVpc(String cidr) {
        return ec2Value("CreateVpc", "CreateVpcResponse.vpc.vpcId", "CidrBlock", cidr);
    }

    private String createSubnet(String vpcId, String cidr) {
        return ec2Value("CreateSubnet", "CreateSubnetResponse.subnet.subnetId",
                "VpcId", vpcId, "CidrBlock", cidr, "AvailabilityZone", "us-east-1a");
    }

    private ValidatableResponse describe(String endpointId) {
        return ec2("DescribeVpcEndpoints", "VpcEndpointId.1", endpointId).statusCode(200);
    }

    @Test
    void anInterfaceEndpointDefaultsToIpv4() {
        String vpcId = createVpc("10.80.0.0/16");
        String subnetId = createSubnet(vpcId, "10.80.1.0/24");

        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId,
                "ServiceName", "com.amazonaws.us-east-1.ecs",
                "VpcEndpointType", "Interface",
                "SubnetId.1", subnetId);

        describe(endpointId)
                .body(hasXPath(IP_ADDRESS_TYPE, equalTo("ipv4")))
                .body(hasXPath(DNS_RECORD_IP_TYPE, equalTo("ipv4")));
    }

    @Test
    void createKeepsTheRequestedTypesAndEchoesThem() {
        String vpcId = createVpc("10.81.0.0/16");
        String subnetId = createSubnet(vpcId, "10.81.1.0/24");

        String endpointId = ec2("CreateVpcEndpoint",
                "VpcId", vpcId,
                "ServiceName", "com.amazonaws.us-east-1.ecs",
                "VpcEndpointType", "Interface",
                "SubnetId.1", subnetId,
                "IpAddressType", "dualstack",
                "DnsOptions.DnsRecordIpType", "service-defined")
                .statusCode(200)
                .body("CreateVpcEndpointResponse.vpcEndpoint.ipAddressType", equalTo("dualstack"))
                .body("CreateVpcEndpointResponse.vpcEndpoint.dnsOptions.dnsRecordIpType",
                        equalTo("service-defined"))
                .extract().path("CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId");

        for (int round = 0; round < 2; round++) {
            describe(endpointId)
                    .body(hasXPath(IP_ADDRESS_TYPE, equalTo("dualstack")))
                    .body(hasXPath(DNS_RECORD_IP_TYPE, equalTo("service-defined")));
        }
    }

    @Test
    void modifyRewritesBothTypes() {
        String vpcId = createVpc("10.82.0.0/16");
        String subnetId = createSubnet(vpcId, "10.82.1.0/24");

        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId,
                "ServiceName", "com.amazonaws.us-east-1.ecs",
                "VpcEndpointType", "Interface",
                "SubnetId.1", subnetId);

        ec2("ModifyVpcEndpoint",
                "VpcEndpointId", endpointId,
                "IpAddressType", "dualstack",
                "DnsOptions.DnsRecordIpType", "dualstack")
                .statusCode(200)
                .body("ModifyVpcEndpointResponse.return", equalTo("true"));

        describe(endpointId)
                .body(hasXPath(IP_ADDRESS_TYPE, equalTo("dualstack")))
                .body(hasXPath(DNS_RECORD_IP_TYPE, equalTo("dualstack")));

        ec2("ModifyVpcEndpoint", "VpcEndpointId", endpointId, "IpAddressType", "ipv6")
                .statusCode(200);

        describe(endpointId)
                .body(hasXPath(IP_ADDRESS_TYPE, equalTo("ipv6")))
                .body(hasXPath(DNS_RECORD_IP_TYPE, equalTo("dualstack")));
    }

    @Test
    void aGatewayEndpointDefaultsItsAddressTypeOnly() {
        String vpcId = createVpc("10.83.0.0/16");

        String bare = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId,
                "ServiceName", "com.amazonaws.us-east-1.s3");
        describe(bare)
                .body(hasXPath(IP_ADDRESS_TYPE, equalTo("ipv4")))
                .body(hasXPath(ITEM + "/*[local-name()='dnsOptions']"
                        + "/*[local-name()='privateDnsOnlyForInboundResolverEndpoint']", equalTo("false")))
                .body(not(hasXPath(DNS_RECORD_IP_TYPE)));

        String pinned = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId,
                "ServiceName", "com.amazonaws.us-east-1.dynamodb",
                "IpAddressType", "dualstack");
        describe(pinned)
                .body(hasXPath(IP_ADDRESS_TYPE, equalTo("dualstack")))
                .body(not(hasXPath(DNS_RECORD_IP_TYPE)));
    }

    @Test
    void anUnknownIpAddressTypeIsRejected() {
        String vpcId = createVpc("10.84.0.0/16");
        String subnetId = createSubnet(vpcId, "10.84.1.0/24");

        ec2("CreateVpcEndpoint",
                "VpcId", vpcId,
                "ServiceName", "com.amazonaws.us-east-1.ecs",
                "VpcEndpointType", "Interface",
                "SubnetId.1", subnetId,
                "IpAddressType", "ipv5")
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));

        ec2("CreateVpcEndpoint",
                "VpcId", vpcId,
                "ServiceName", "com.amazonaws.us-east-1.ecs",
                "VpcEndpointType", "Interface",
                "SubnetId.1", subnetId,
                "DnsOptions.DnsRecordIpType", "ipv5")
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
    }
}
