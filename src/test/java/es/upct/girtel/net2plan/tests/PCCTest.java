package es.upct.girtel.net2plan.tests;

import es.tid.bgp.bgp4.messages.BGP4Update;
import es.tid.bgp.bgp4.update.fields.LinkNLRI;
import es.tid.bgp.bgp4.update.fields.PathAttribute;
import es.tid.bgp.bgp4.update.fields.pathAttributes.*;
import es.tid.bgp.bgp4.update.tlv.LocalNodeDescriptorsTLV;
import es.tid.bgp.bgp4.update.tlv.ProtocolIDCodes;
import es.tid.bgp.bgp4.update.tlv.RemoteNodeDescriptorsTLV;
import es.tid.bgp.bgp4.update.tlv.RoutingUniverseIdentifierTypes;
import es.tid.bgp.bgp4.update.tlv.node_link_prefix_descriptor_subTLVs.*;
import org.junit.Assert;
import org.junit.Test;

import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Arrays;

public class PCCTest
{
	@Test
	public void mpReachEncodingValidity()
	{
		MP_Reach_Attribute mp1 = new Generic_MP_Reach_Attribute();
		mp1.encode();
		byte[] bytesMp1 = mp1.getBytes();

		MP_Reach_Attribute mp2 = new Generic_MP_Reach_Attribute(bytesMp1, 0);
		byte[] bytesMp2 = mp2.getBytes();

		Assert.assertTrue("Bytes from both messages should be equal", Arrays.equals(bytesMp1, bytesMp2));
		Assert.assertEquals("Both MP_Reach_Attribute objects should be equal", mp1, mp2);
	}

	@Test
	public void bgpUpdateWithLinkNLRI()
	{
		try
		{
			//IP address
			Inet4Address sourceIP = (Inet4Address) Inet4Address.getByName("10.0.0.1");
			Inet4Address destinationIP = (Inet4Address) Inet4Address.getByName("10.0.0.2");

			//Link NLRI
			LinkNLRI nlri = new LinkNLRI();
			nlri.setProtocolID(ProtocolIDCodes.Direct_Protocol_ID);
			nlri.setIdentifier(RoutingUniverseIdentifierTypes.Level1Identifier);

			//Source node
			LocalNodeDescriptorsTLV localNodeDescriptorsTLV = new LocalNodeDescriptorsTLV();
			IGPRouterIDNodeDescriptorSubTLV igpRouterIDLNSubTLV = new IGPRouterIDNodeDescriptorSubTLV();

			igpRouterIDLNSubTLV.setIpv4AddressOSPF(sourceIP);
			igpRouterIDLNSubTLV.setIGP_router_id_type(IGPRouterIDNodeDescriptorSubTLV.IGP_ROUTER_ID_TYPE_OSPF_NON_PSEUDO);
			localNodeDescriptorsTLV.setIGPRouterID(igpRouterIDLNSubTLV); //This may be the problem!
			nlri.setLocalNodeDescriptors(localNodeDescriptorsTLV);

			//Destination node (same thing basically)
			RemoteNodeDescriptorsTLV remoteNodeDescriptorsTLV = new RemoteNodeDescriptorsTLV();
			IGPRouterIDNodeDescriptorSubTLV igpRouterIDDNSubTLV = new IGPRouterIDNodeDescriptorSubTLV();

			igpRouterIDDNSubTLV.setIpv4AddressOSPF(destinationIP);
			igpRouterIDDNSubTLV.setIGP_router_id_type(IGPRouterIDNodeDescriptorSubTLV.IGP_ROUTER_ID_TYPE_OSPF_NON_PSEUDO);
			remoteNodeDescriptorsTLV.setIGPRouterID(igpRouterIDDNSubTLV);
			nlri.setRemoteNodeDescriptorsTLV(remoteNodeDescriptorsTLV);

			//Interfaces IP addresses
			IPv4InterfaceAddressLinkDescriptorsSubTLV ipv4InterfaceAddressTLV = new IPv4InterfaceAddressLinkDescriptorsSubTLV();
			ipv4InterfaceAddressTLV.setIpv4Address(sourceIP);
			nlri.setIpv4InterfaceAddressTLV(ipv4InterfaceAddressTLV);
			IPv4NeighborAddressLinkDescriptorSubTLV ipv4NeighbourAddressTLV = new IPv4NeighborAddressLinkDescriptorSubTLV();
			ipv4NeighbourAddressTLV.setIpv4Address(destinationIP);
			nlri.setIpv4NeighborAddressTLV(ipv4NeighbourAddressTLV);

			//Interface identifiers
			LinkLocalRemoteIdentifiersLinkDescriptorSubTLV linkIdentifiersTLV = new LinkLocalRemoteIdentifiersLinkDescriptorSubTLV();
			linkIdentifiersTLV.setLinkLocalIdentifier(1L);
			linkIdentifiersTLV.setLinkRemoteIdentifier(2L);
			nlri.setLinkIdentifiersTLV(linkIdentifiersTLV);

			//Interfaces identifiers
			OriginAttribute or = new OriginAttribute();
			or.setValue(PathAttributesTypeCode.PATH_ATTRIBUTE_ORIGIN_IGP);
			ArrayList<PathAttribute> pathAttributes = new ArrayList<>();
			pathAttributes.add(or);
			PathAttribute reach = new Generic_MP_Reach_Attribute();
			//PathAttribute reach = new MP_Unreach_Attribute();
			pathAttributes.add(reach);

			//Create the message
			BGP4Update updateMsg1 = new BGP4Update();
			updateMsg1.setNlri(nlri);
			updateMsg1.setPathAttributes(pathAttributes);
			updateMsg1.encode();
			byte[] bytes1 = updateMsg1.getBytes();

			BGP4Update updateMsg2 = new BGP4Update(bytes1);
			byte[] bytes2 = updateMsg2.getBytes();

			Assert.assertTrue("Bytes from both objects should be the same",Arrays.equals(bytes1,bytes2));
//			Assert.assertEquals("Both objects should be the same",updateMsg1,updateMsg2); //Equals not implemented!




		}catch(Throwable e)
		{
			e.printStackTrace();
			Assert.fail("Exception thrown");
		}

	}
}