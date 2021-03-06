package org.batfish.representation.aws;

import static com.google.common.collect.Iterables.getOnlyElement;
import static org.batfish.datamodel.Interface.NULL_INTERFACE_NAME;
import static org.batfish.datamodel.acl.AclLineMatchExprs.matchDst;
import static org.batfish.datamodel.acl.AclLineMatchExprs.matchSrc;
import static org.batfish.datamodel.matchers.AbstractRouteDecoratorMatchers.hasNextHopInterface;
import static org.batfish.datamodel.matchers.AbstractRouteDecoratorMatchers.hasPrefix;
import static org.batfish.datamodel.matchers.AbstractRouteDecoratorMatchers.isNonForwarding;
import static org.batfish.datamodel.matchers.ConfigurationMatchers.hasDeviceModel;
import static org.batfish.datamodel.matchers.ConfigurationMatchers.hasVrf;
import static org.batfish.datamodel.matchers.VrfMatchers.hasStaticRoutes;
import static org.batfish.datamodel.transformation.TransformationStep.shiftDestinationIp;
import static org.batfish.datamodel.transformation.TransformationStep.shiftSourceIp;
import static org.batfish.representation.aws.AwsVpcEntity.JSON_KEY_INTERNET_GATEWAYS;
import static org.batfish.representation.aws.InternetGateway.AWS_BACKBONE_ASN;
import static org.batfish.representation.aws.InternetGateway.AWS_INTERNET_GATEWAY_AS;
import static org.batfish.representation.aws.InternetGateway.BACKBONE_EXPORT_POLICY_NAME;
import static org.batfish.representation.aws.InternetGateway.BACKBONE_INTERFACE_NAME;
import static org.batfish.representation.aws.InternetGateway.configureNat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import org.batfish.common.util.BatfishObjectMapper;
import org.batfish.common.util.CommonUtil;
import org.batfish.common.util.IspModelingUtils;
import org.batfish.datamodel.BgpActivePeerConfig;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.DeviceModel;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.PrefixRange;
import org.batfish.datamodel.PrefixSpace;
import org.batfish.datamodel.bgp.Ipv4UnicastAddressFamily;
import org.batfish.datamodel.transformation.Transformation;
import org.batfish.datamodel.transformation.TransformationStep;
import org.junit.Test;

/** Tests for {@link InternetGateway} */
public class InternetGatewayTest {

  @Test
  public void testDeserialization() throws IOException {
    String text =
        CommonUtil.readResource("org/batfish/representation/aws/InternetGatewayTest.json");

    JsonNode json = BatfishObjectMapper.mapper().readTree(text);
    ArrayNode gatewaysArray = (ArrayNode) json.get(JSON_KEY_INTERNET_GATEWAYS);
    List<InternetGateway> gateways = new LinkedList<>();

    for (int index = 0; index < gatewaysArray.size(); index++) {
      gateways.add(
          BatfishObjectMapper.mapper()
              .convertValue(gatewaysArray.get(index), InternetGateway.class));
    }

    assertThat(
        gateways,
        equalTo(
            ImmutableList.of(
                new InternetGateway("igw-fac5839d", ImmutableList.of("vpc-925131f4")))));
  }

  @Test
  public void testToConfiguration() {

    Vpc vpc = new Vpc("vpc", ImmutableSet.of());
    Configuration vpcConfig = Utils.newAwsConfiguration(vpc.getId(), "awstest");

    Ip privateIp = Ip.parse("10.10.10.10");
    Ip publicIp = Ip.parse("1.1.1.1");

    NetworkInterface ni =
        new NetworkInterface(
            "ni",
            "subnet",
            vpc.getId(),
            ImmutableList.of(),
            ImmutableList.of(new PrivateIpAddress(true, privateIp, publicIp)),
            "desc",
            null);

    InternetGateway internetGateway = new InternetGateway("igw", ImmutableList.of(vpc.getId()));

    Region region =
        Region.builder("region")
            .setInternetGateways(ImmutableMap.of(internetGateway.getId(), internetGateway))
            .setVpcs(ImmutableMap.of(vpc.getId(), vpc))
            .setNetworkInterfaces(ImmutableMap.of(ni.getId(), ni))
            .build();

    ConvertedConfiguration awsConfiguration =
        new ConvertedConfiguration(ImmutableMap.of(vpcConfig.getHostname(), vpcConfig));

    Configuration igwConfig = internetGateway.toConfigurationNode(awsConfiguration, region);
    assertThat(igwConfig, hasDeviceModel(DeviceModel.AWS_INTERNET_GATEWAY));

    // gateway should have interfaces to the backbone and vpc
    assertThat(
        igwConfig.getAllInterfaces().values().stream()
            .map(i -> i.getName())
            .collect(ImmutableList.toImmutableList()),
        equalTo(ImmutableList.of(BACKBONE_INTERFACE_NAME)));

    Interface bbInterface = igwConfig.getAllInterfaces().get(BACKBONE_INTERFACE_NAME);
    Prefix bbInterfacePrefix = bbInterface.getConcreteAddress().getPrefix();

    assertTrue(igwConfig.getAllInterfaces().containsKey(BACKBONE_INTERFACE_NAME));
    assertThat(
        igwConfig.getDefaultVrf().getBgpProcess().getRouterId(),
        equalTo(bbInterfacePrefix.getStartIp()));

    // check NAT configuration
    assertThat(
        bbInterface.getOutgoingTransformation(),
        equalTo(
            Transformation.when(matchSrc(privateIp))
                .apply(shiftSourceIp(publicIp.toPrefix()))
                .build()));
    assertThat(
        bbInterface.getIncomingTransformation(),
        equalTo(
            Transformation.when(matchDst(publicIp))
                .apply(TransformationStep.shiftDestinationIp(privateIp.toPrefix()))
                .build()));

    assertThat(
        igwConfig.getRoutingPolicies().get(BACKBONE_EXPORT_POLICY_NAME).getStatements(),
        equalTo(
            Collections.singletonList(
                IspModelingUtils.getAdvertiseStaticStatement(
                    new PrefixSpace(PrefixRange.fromPrefix(publicIp.toPrefix()))))));

    assertThat(
        igwConfig,
        hasVrf(
            Configuration.DEFAULT_VRF_NAME,
            hasStaticRoutes(
                contains(
                    allOf(
                        hasPrefix(publicIp.toPrefix()),
                        hasNextHopInterface(NULL_INTERFACE_NAME),
                        isNonForwarding(true))))));

    BgpActivePeerConfig nbr =
        getOnlyElement(igwConfig.getDefaultVrf().getBgpProcess().getActiveNeighbors().values());
    assertThat(
        nbr,
        equalTo(
            BgpActivePeerConfig.builder()
                .setLocalIp(bbInterfacePrefix.getStartIp())
                .setLocalAs(AWS_INTERNET_GATEWAY_AS)
                .setRemoteAs(AWS_BACKBONE_ASN)
                .setPeerAddress(bbInterfacePrefix.getEndIp())
                .setIpv4UnicastAddressFamily(
                    Ipv4UnicastAddressFamily.builder()
                        .setExportPolicy(BACKBONE_EXPORT_POLICY_NAME)
                        .build())
                .build()));
  }

  @Test
  public void testConfigureNatEmptyMap() {
    Interface iface = Interface.builder().setName("iface").build();
    configureNat(iface, ImmutableMap.of());
    assertThat(iface.getIncomingTransformation(), nullValue());
    assertThat(iface.getOutgoingTransformation(), nullValue());
  }

  @Test
  public void testConfigureNat() {
    Ip pvt1 = Ip.parse("10.10.10.1");
    Ip pvt2 = Ip.parse("10.10.10.2");
    Ip pub1 = Ip.parse("1.1.1.1");
    Ip pub2 = Ip.parse("1.1.1.2");

    Interface iface = Interface.builder().setName("iface").build();
    configureNat(iface, ImmutableMap.of(pvt1, pub1, pvt2, pub2));

    assertThat(
        iface.getIncomingTransformation(),
        equalTo(
            Transformation.when(matchDst(pub2))
                .apply(shiftDestinationIp(pvt2.toPrefix()))
                .setOrElse(
                    Transformation.when(matchDst(pub1))
                        .apply(shiftDestinationIp(pvt1.toPrefix()))
                        .build())
                .build()));
    assertThat(
        iface.getOutgoingTransformation(),
        equalTo(
            Transformation.when(matchSrc(pvt2))
                .apply(shiftSourceIp(pub2.toPrefix()))
                .setOrElse(
                    Transformation.when(matchSrc(pvt1))
                        .apply(shiftSourceIp(pub1.toPrefix()))
                        .build())
                .build()));
  }
}
