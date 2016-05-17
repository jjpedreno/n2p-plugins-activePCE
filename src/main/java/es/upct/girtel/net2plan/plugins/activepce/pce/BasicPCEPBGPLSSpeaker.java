/*
 *  Copyright (c) 2016 Jose-Juan Pedreno-Manresa, Jose-Luis Izquierdo-Zaragoza, Pablo Pavon-Marino
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the GNU Lesser Public License v3
 *  which accompanies this distribution, and is available at
 *  http://www.gnu.org/licenses/lgpl.html
 *
 *  Contributors:
 *          Jose-Juan Pedreno-Manresa
 *          Jose-Luis Izquierdo-Zaragoza
 */

package es.upct.girtel.net2plan.plugins.activepce.pce;

import cern.colt.matrix.tdouble.DoubleMatrix2D;
import com.net2plan.interfaces.networkDesign.*;
import com.net2plan.libraries.GraphUtils;
import com.net2plan.libraries.WDMUtils;
import com.net2plan.utils.Pair;
import es.tid.bgp.bgp4.messages.BGP4Message;
import es.tid.bgp.bgp4.messages.BGP4MessageTypes;
import es.tid.bgp.bgp4.messages.BGP4Update;
import es.tid.bgp.bgp4.update.fields.LinkNLRI;
import es.tid.bgp.bgp4.update.fields.NLRI;
import es.tid.bgp.bgp4.update.fields.NodeNLRI;
import es.tid.bgp.bgp4.update.fields.PathAttribute;
import es.tid.bgp.bgp4.update.fields.pathAttributes.PathAttributesTypeCode;
import es.tid.bgp.bgp4.update.tlv.RoutingUniverseIdentifierTypes;
import es.tid.pce.pcep.PCEPProtocolViolationException;
import es.tid.pce.pcep.constructs.Path;
import es.tid.pce.pcep.constructs.Request;
import es.tid.pce.pcep.constructs.Response;
import es.tid.pce.pcep.constructs.UpdateRequest;
import es.tid.pce.pcep.messages.*;
import es.tid.pce.pcep.objects.*;
import es.tid.rsvp.RSVPProtocolViolationException;
import es.tid.rsvp.constructs.gmpls.DWDMWavelengthLabel;
import es.tid.rsvp.objects.subobjects.GeneralizedLabelEROSubobject;
import es.tid.rsvp.objects.subobjects.IPv4prefixEROSubobject;
import es.tid.rsvp.objects.subobjects.ServerLayerInfo;
import es.tid.rsvp.objects.subobjects.UnnumberIfIDEROSubobject;
import es.upct.girtel.net2plan.plugins.activepce.utils.Constants;
import es.upct.girtel.net2plan.plugins.activepce.utils.Utils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

public class BasicPCEPBGPLSSpeaker extends IPCEEntity
{
	private final Map<InetAddress, Long> _ipNodeIdMap;
	private       NetworkLayer           _wdmLayer, _ipLayer;
	private DoubleMatrix2D _wavelengthFiberOccupancy;

	public BasicPCEPBGPLSSpeaker()
	{
		_ipNodeIdMap = new HashMap<InetAddress, Long>();

		_wdmLayer = _netPlan.getNetworkLayerDefault();
		_netPlan.setRoutingType(com.net2plan.utils.Constants.RoutingType.SOURCE_ROUTING, _wdmLayer);
		_ipLayer = _netPlan.addLayer("IP", "IP Layer", null, null, null);
		_netPlan.setRoutingType(com.net2plan.utils.Constants.RoutingType.SOURCE_ROUTING, _ipLayer);

	}

	@Override
	public List handleBGPMessage(BGP4Message message)
	{
		List outMsg = new LinkedList();

		int messageType = BGP4Message.getMessageType(message.getBytes());
		switch(messageType)
		{
			case BGP4MessageTypes.MESSAGE_UPDATE:
				if(Constants.DEBUG) System.out.println("\tHandling BGP UPDATE");
				outMsg.addAll(handleBGPUpdate(new BGP4Update(message.getBytes())));
				break;

			default:
				break; /* Do nothing */
		}
		return outMsg;
	}

	@Override
	public List handlePCEPMessage(PCEPMessage message)
	{
		List outMsg = new LinkedList();

		try
		{
			int messageType = PCEPMessage.getMessageType(message.getBytes());
			switch(messageType)
			{
				case PCEPMessageTypes.MESSAGE_PCREQ:
					PCEPRequest request = new PCEPRequest(message.getBytes());
					if(Constants.DEBUG) System.out.println("\tHandling PCEP REQUEST message");
					outMsg.addAll(handlePCEPRequest(request));
					break;

				case PCEPMessageTypes.MESSAGE_REPORT:
					PCEPReport report = new PCEPReport(message.getBytes());
					if(Constants.DEBUG) System.out.println("\tHandling PCEP REPORT message");
					outMsg.addAll(handlePCEPReport(report));
					break;

				default:
					break;
			}
		}catch(PCEPProtocolViolationException e)
		{
			if(Constants.DEBUG) e.printStackTrace();
		}catch(Throwable e)
		{
			if(Constants.DEBUG) e.printStackTrace();
		}

		return outMsg;
	}

	private Pair<Path, Bandwidth> generateMultiLayerPath(Link originLink, Link destinationLink, Route lpRoute, float bandwidthInGbps)
	{
		if(Constants.DEBUG) System.out.println("\tGenerating Multilayer Path");
		try
		{
			Path path = new Path();
			ExplicitRouteObject ero = new ExplicitRouteObject();
			path.setEro(ero);

			IPv4prefixEROSubobject eroSubObject_sourceNode = new IPv4prefixEROSubobject();
			eroSubObject_sourceNode.setIpv4address(Utils.getLinkSourceIPAddress(_netPlan, originLink.getId()));
			eroSubObject_sourceNode.setLoosehop(false);
			eroSubObject_sourceNode.setPrefix(32);
			ero.addEROSubobject(eroSubObject_sourceNode);

			ServerLayerInfo eroSubObject_switchToWDMLayer = new ServerLayerInfo();
			eroSubObject_switchToWDMLayer.setEncoding(8);
			eroSubObject_switchToWDMLayer.setSwitchingCap(150);
			ero.addEROSubobject(eroSubObject_switchToWDMLayer);

			List<Link> seqFibers = lpRoute.getSeqLinksRealPath();
			int[] seqWavelengths = WDMUtils.getLightpathSeqWavelengths(lpRoute);
			ListIterator<Link> fiberIt = seqFibers.listIterator();
			while(fiberIt.hasNext())
			{
				int hopId = fiberIt.nextIndex();
				Link fiber = fiberIt.next();
				int wavelengthId = seqWavelengths[hopId];

				UnnumberIfIDEROSubobject eroSubObject_thisFiber = new UnnumberIfIDEROSubobject();
				eroSubObject_thisFiber.setRouterID(Utils.getLinkSourceIPAddress(_netPlan, fiber.getId()));
				eroSubObject_thisFiber.setInterfaceID(Utils.getLinkSourceInterface(_netPlan, fiber.getId()));
				eroSubObject_thisFiber.setLoosehop(false);
				ero.addEROSubobject(eroSubObject_thisFiber);

				GeneralizedLabelEROSubobject genLabel = new GeneralizedLabelEROSubobject();
				ero.addEROSubobject(genLabel);
				DWDMWavelengthLabel wavelengthLabel = new DWDMWavelengthLabel();
				wavelengthLabel.setGrid(1);
				wavelengthLabel.setChannelSpacing(2);
				wavelengthLabel.setN(wavelengthId);
				wavelengthLabel.setIdentifier(0);
				wavelengthLabel.encode();
				genLabel.setLabel(wavelengthLabel.getBytes());
			}

			ServerLayerInfo eroSubObject_switchToIPLayer = new ServerLayerInfo();
			eroSubObject_switchToIPLayer.setEncoding(8);
			eroSubObject_switchToIPLayer.setSwitchingCap(150);
			ero.addEROSubobject(eroSubObject_switchToIPLayer);

			IPv4prefixEROSubobject eroSubObject_destinationNode = new IPv4prefixEROSubobject();
			eroSubObject_destinationNode.setIpv4address(Utils.getLinkDestinationIPAddress(_netPlan, destinationLink.getId()));
			eroSubObject_destinationNode.setLoosehop(false);
			eroSubObject_destinationNode.setPrefix(32);
			ero.addEROSubobject(eroSubObject_destinationNode);

			BandwidthRequested bw = new BandwidthRequested();
			bw.setBw(bandwidthInGbps * 1E9f / 8f);

			return Pair.of(path, (Bandwidth) bw);
		}catch(UnknownHostException | RSVPProtocolViolationException e)
		{
			if(Constants.DEBUG) e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	private Pair<Path, Bandwidth> generateSingleLayerPath(Route route)
	{
		try
		{
			if(Constants.DEBUG) System.out.println("\tGenerating Single Layer Path:");
			Path path = new Path();
			ExplicitRouteObject ero = new ExplicitRouteObject();
			path.setEro(ero);

			long destinationLinkId = - 1;
			List<Link> seqLinks = route.getSeqLinksRealPath();

			for(Link link : seqLinks)
			{
				IPv4prefixEROSubobject eroSubObject_thisLink = new IPv4prefixEROSubobject();
				eroSubObject_thisLink.setIpv4address(Utils.getLinkSourceIPAddress(_netPlan, link.getId()));
				eroSubObject_thisLink.setLoosehop(false);
				eroSubObject_thisLink.setPrefix(32);
				ero.addEROSubobject(eroSubObject_thisLink);

				destinationLinkId = link.getId();
				if(Constants.DEBUG) System.out.println("\t\tLink from " + Utils.getLinkSourceIPAddress(_netPlan, link.getId()).getHostAddress()+" to "+Utils.getLinkDestinationIPAddress(_netPlan,link.getId()).getHostAddress());
			}

			BandwidthRequested bw = new BandwidthRequested();
			bw.setBw((float) route.getCarriedTraffic() * 1E9f / 8f);

			if(Constants.DEBUG)
			{
				System.out.println("\t\tIP = " + Utils.getLinkDestinationIPAddress(_netPlan, destinationLinkId).getHostAddress());
				System.out.println("\t\tBandwidth = " + bw.getBw());
			}

			return Pair.of(path, (Bandwidth) bw);
		}catch(Throwable e)
		{
			if(Constants.DEBUG) e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	private List handleBGPUpdate(BGP4Update updateMessage)
	{
		List outMsg = new LinkedList();

		NLRI nlri = updateMessage.getNlri();
		boolean isUp = false;
		for(PathAttribute pathAttribute : updateMessage.getPathAttributes())
		{
			switch(pathAttribute.getTypeCode())
			{
				case PathAttributesTypeCode.PATH_ATTRIBUTE_TYPECODE_MP_REACH_NLRI:
					if(Constants.DEBUG) System.out.println("\tPath Attribute = MP_REACH");
					isUp = true;
					break;

				case PathAttributesTypeCode.PATH_ATTRIBUTE_TYPECODE_MP_UN_REACH_NLRI:
					if(Constants.DEBUG) System.out.println("\tPath Attribute = MP_UNREACH");
					isUp = false;
					break;

				default:
					break; /* Do nothing */
			}
		}

		if(nlri instanceof NodeNLRI)
		{
			if(Constants.DEBUG) System.out.println("\tNode NLRI");
			NodeNLRI nodeNLRI = (NodeNLRI) nlri;
			outMsg.addAll(handleNodeUpdate(nodeNLRI, isUp));
		} else if(nlri instanceof LinkNLRI)
		{
			if(Constants.DEBUG) System.out.println("\tLink NLRI");
			LinkNLRI linkNLRI = (LinkNLRI) nlri;
			outMsg.addAll(handleLinkUpdate(linkNLRI, isUp));
		} else
		{
			/*
			 * RFC4271 Section 6.3 (UPDATE Message Error Handling)
			 * All errors detected while processing the UPDATE message MUST be 
			 * indicated by sending the NOTIFICATION message with the Error Code
             * UPDATE Message Error.  The error subcode elaborates on the specific
             * nature of the error
			 *
			 * TODO Error code = 3 (UPDATE Message Error), Error subcode = 1 (Malformed Attribute List)
			 */

			//			BGP4Notification bgpError = new BGP4Notification();
			//			bgpError.encode();
			//			outMsg.add(bgpError);
		}

		return outMsg;
	}

	private List handleFailureReparationEvent()
	{
		_wavelengthFiberOccupancy = WDMUtils.getMatrixWavelength2FiberOccupancy(_netPlan, true, _wdmLayer);
		if(Constants.DEBUG) System.out.println("\tHandling failure event!");
		try
		{
			List outMsg = new LinkedList();

			LinkedList<UpdateRequest> updateList = new LinkedList<UpdateRequest>();
			List<Route> affectedLightpathRoutes = _netPlan.getRoutes(_wdmLayer);
			for(Route lpRoute : affectedLightpathRoutes)
			{
				if(Constants.DEBUG) System.out.println("\t\tRoute down: " + lpRoute.getIndex());
				List<Link> seqFibers = lpRoute.getSeqLinksRealPath();
				int[] seqWavelengths = WDMUtils.getLightpathSeqWavelengths(lpRoute);
				WDMUtils.releaseResources(seqFibers, seqWavelengths, _wavelengthFiberOccupancy, null, null);

				Node originNode = lpRoute.getIngressNode();
				Node destinationNode = lpRoute.getEgressNode();

				List<Node> nodesUp = new LinkedList<>(_netPlan.getNodesUp());
				List<Link> linksUP = new LinkedList<>(_netPlan.getLinksUp(_wdmLayer));

				List<List<Link>> cpl = GraphUtils.getKLooplessShortestPaths(nodesUp, linksUP, originNode, destinationNode, null, 5, - 1, - 1, - 1, - 1, - 1, - 1);
				int[] wavelengths = new int[0];

				for(List<Link> cp : cpl)
				{
					wavelengths = WDMUtils.WA_firstFit(cp, _wavelengthFiberOccupancy);
					if(wavelengths.length > 0)
					{
						WDMUtils.allocateResources(cp, wavelengths, _wavelengthFiberOccupancy, null, null);
						lpRoute.setSeqLinksAndProtectionSegments(cp);
						WDMUtils.setLightpathSeqWavelengths(lpRoute, wavelengths);
						if(Constants.DEBUG) System.out.println("\t\tNew sequence of fibers and wavelengths found!");
						break;
					}
				}

				UpdateRequest updateRequest = new UpdateRequest();

				SRP srp = new SRP();
				updateRequest.setSrp(srp);

				LSP lsp = new LSP();
				lsp.setLspId(Integer.parseInt(lpRoute.getAttribute(Constants.ATTRIBUTE_LSP_ID)));
				lsp.setAFlag(wavelengths.length > 0);
				updateRequest.setLsp(lsp);

				if(wavelengths.length > 0)
				{
					Path path = new Path();
					ExplicitRouteObject ero = new ExplicitRouteObject();
					path.setEro(ero);

					seqFibers = lpRoute.getSeqLinksRealPath();
					Link lastLink = null;
					for(Link link : seqFibers)
					{
						long originNodeId_thisFiber = link.getOriginNode().getId();
						UnnumberIfIDEROSubobject eroso = new UnnumberIfIDEROSubobject();
						eroso.setRouterID(Utils.getNodeIPAddress(_netPlan, originNodeId_thisFiber));
						eroso.setInterfaceID(Utils.getLinkSourceInterface(_netPlan, link.getId()));
						eroso.setLoosehop(false);
						ero.addEROSubobject(eroso);

						GeneralizedLabelEROSubobject genLabel = new GeneralizedLabelEROSubobject();
						ero.addEROSubobject(genLabel);
						DWDMWavelengthLabel wavelengthLabel = new DWDMWavelengthLabel();
						wavelengthLabel.setGrid(1);
						wavelengthLabel.setChannelSpacing(2);
						wavelengthLabel.setN(wavelengths[0]);
						wavelengthLabel.setIdentifier(0);
						wavelengthLabel.encode();
						genLabel.setLabel(wavelengthLabel.getBytes());

						lastLink = link;
					}

					UnnumberIfIDEROSubobject eroso = new UnnumberIfIDEROSubobject();
					eroso.setRouterID(Utils.getLinkDestinationIPAddress(_netPlan, lastLink.getId()));
					eroso.setInterfaceID(Utils.getLinkDestinationInterface(_netPlan, lastLink.getId()));
					eroso.setLoosehop(false);
					ero.addEROSubobject(eroso);

					updateRequest.setPath(path);
					lpRoute.setAttribute(Constants.ATTRIBUTE_LSP_ID, String.valueOf(wavelengths[0])); //Update the identifier of the Route
				}

				updateList.add(updateRequest);
			}

			if(! updateList.isEmpty())
			{
				PCEPUpdate updateMsg = new PCEPUpdate();
				updateMsg.setUpdateRequestList(updateList);
				updateMsg.encode();
				outMsg.add(updateMsg);
			}

			return outMsg;
		}catch(Throwable e)
		{
			if(Constants.DEBUG) e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	private List handleLinkUpdate(LinkNLRI linkNLRI, boolean isUp)
	{
		if(Constants.DEBUG) System.out.println("\tHandling Link Update");
		List outMsg = new LinkedList();

		Inet4Address sourceNodeIPAddress = linkNLRI.getIpv4InterfaceAddressTLV().getIpv4Address();
		Inet4Address destinationNodeIPAddress = linkNLRI.getIpv4NeighborAddressTLV().getIpv4Address();
		Node originNode = _netPlan.getNodeFromId(_ipNodeIdMap.get(sourceNodeIPAddress));
		Node destinationNode = _netPlan.getNodeFromId(_ipNodeIdMap.get(destinationNodeIPAddress));

		Set<Link> possibleLinks;

		String sourceNodeType = originNode.getAttribute(Constants.ATTRIBUTE_NODE_TYPE);
		String destinationNodeType = destinationNode.getAttribute(Constants.ATTRIBUTE_NODE_TYPE);

		if(sourceNodeType.equals(destinationNodeType)) //Both are ROADM (routers shouldn't be directly connected)
		{
			possibleLinks = _netPlan.getNodePairLinks(originNode, destinationNode, false, _wdmLayer); //Links are in the bottom layer

			if(Constants.DEBUG) System.out.println("\tBoth ends nodes are SAME type");

			long srcIf = linkNLRI.getLinkIdentifiersTLV().getLinkLocalIdentifier();
			long dstIf = linkNLRI.getLinkIdentifiersTLV().getLinkRemoteIdentifier();

			for(Link link : possibleLinks)
			{
				long linkId = link.getId();
				if(Constants.DEBUG) System.out.println("\tExisting link");
				long srcIf_thisLink = Utils.getLinkSourceInterface(_netPlan, linkId);
				long dstIf_thisLink = Utils.getLinkDestinationInterface(_netPlan, linkId);

				if(srcIf == srcIf_thisLink && dstIf == dstIf_thisLink)
				{
					link.setFailureState(isUp);
					if(Constants.DEBUG) System.out.println("\tLink in WDM layer " + link.getIndex() + " set to: " + isUp);
				}
			}
			if(! possibleLinks.isEmpty())
			{
				outMsg.addAll(handleFailureReparationEvent());
				return outMsg;
			}
			try
			{
				if(Constants.DEBUG) System.out.println("\tNo previous link, creating one");
				Link newLink = _netPlan.addLink(originNode, destinationNode, Constants.W, 0, Double.MAX_VALUE, null, _wdmLayer);

				newLink.setAttribute(Constants.ATTRIBUTE_SOURCE_INTERFACE, Long.toString(srcIf));
				newLink.setAttribute(Constants.ATTRIBUTE_DESTINATION_INTERFACE, Long.toString(dstIf));
				newLink.setFailureState(isUp);
				if(Constants.DEBUG) System.out.println("\tLink created");
			}catch(Throwable e){ e.printStackTrace(); } //DEBUG

		} else //Both Nodes are different type: one ROADM and one ROUTER
		{
			possibleLinks = _netPlan.getNodePairLinks(originNode, destinationNode, false, _ipLayer);

			if(Constants.DEBUG) System.out.println("\tBoth ends nodes are DIFFERENT type");
			if(possibleLinks.isEmpty())
			{
				if(Constants.DEBUG) System.out.println("\t No previous link, creating one");
				Link newLink = _netPlan.addLink(originNode, destinationNode, Double.MAX_EXPONENT, 0, Double.MAX_VALUE, null, _ipLayer);
				newLink.setFailureState(isUp);
			} else
			{
				Link link = possibleLinks.iterator().next();  //Assuming only one link (in each direction) between ROADM and ROUTER
				link.setFailureState(isUp);
			}
		}

		return outMsg;
	}

	private List handleNodeUpdate(NodeNLRI nodeNLRI, boolean isUp)
	{
		if(Constants.DEBUG) System.out.println("\tHandling Node update");
		List outMsg = new LinkedList();

		//Inet4Address ipAddress = ((IGPRouterIDNodeDescriptorSubTLV) nodeNLRI.getLocalNodeDescriptors().getNodeDescriptorsSubTLVList().get(0)).getIpv4AddressOSPF();
		Inet4Address ipAddress = nodeNLRI.getLocalNodeDescriptors().getIGPRouterID().getIpv4AddressOSPF();
		if(_ipNodeIdMap.containsKey(ipAddress))
		{
			long nodeId = _ipNodeIdMap.get(ipAddress);
			Node node = _netPlan.getNodeFromId(nodeId);
			if(Constants.DEBUG) System.out.println("\tSetting node " + node.getIndex() + " as : " + isUp);
			node.setFailureState(isUp);
			outMsg.addAll(handleFailureReparationEvent());
		} else
		{
			Node node = _netPlan.addNode(0, 0, "", null);
			node.setAttribute(Constants.ATTRIBUTE_IP_ADDRESS, ipAddress.getHostAddress());
			node.setAttribute(Constants.ATTRIBUTE_NODE_TYPE, nodeNLRI.getRoutingUniverseIdentifier() == RoutingUniverseIdentifierTypes.Level3Identifier ? Constants.NODE_TYPE_IPROUTER : Constants.NODE_TYPE_ROADM);

			_ipNodeIdMap.put(ipAddress, node.getId());

			node.setFailureState(isUp);
		}

		return outMsg;
	}

	private List handlePCEPReport(PCEPReport report)
	{
		return new LinkedList();
	}

	private List handlePCEPRequest(PCEPRequest requestMsg)
	{
		if(Constants.DEBUG) System.out.println("\tHandling PCEP REQUEST");
		try
		{
			_wavelengthFiberOccupancy = WDMUtils.getMatrixWavelength2FiberOccupancy(_netPlan, true, _wdmLayer);
			List outMsg = new LinkedList();

			PCEPResponse responseMsg = new PCEPResponse();

			LinkedList<Response> responseList = new LinkedList<Response>();
			for(Request request : requestMsg.getRequestList())
			{
				long requestId = request.getRequestParameters().getRequestID();

				boolean allocatedConnection = false;

				EndPointsIPv4 endPoints = (EndPointsIPv4) request.getEndPoints();
				final long ingressNodeId = _ipNodeIdMap.get(endPoints.getSourceIP());
				final long egressNodeId = _ipNodeIdMap.get(endPoints.getDestIP());
				BandwidthRequested bw = (BandwidthRequested) request.getBandwidth();
				float bandwidthInGbps = bw.getBw() * 8f / 1E9f;

				/* Add demand in the IP layer */
				Node ingressNode = _netPlan.getNodeFromId(ingressNodeId);
				Node egressNode = _netPlan.getNodeFromId(egressNodeId);
				Demand ipDemand = _netPlan.addDemand(ingressNode, egressNode, bandwidthInGbps, null, _ipLayer);
				ipDemand.setAttribute(Constants.ATTRIBUTE_REQUEST_ID, Long.toString(requestId));

				if(Constants.DEBUG)
				{
					System.out.println("\tIP Ingress Node = " + ingressNode);
					System.out.println("\tIP Egress Node = " + egressNode);
					System.out.println("\tIP Demand index = " + ipDemand.getIndex());
				}
				
				/* First, try to allocate over a single path over the available resources at the IP layer */
				//TODO select K shortest paths instead
				List<Link> seqLinks = GraphUtils.getCapacitatedShortestPath(_netPlan.getNodes(), _netPlan.getLinks(_ipLayer), ingressNode, egressNode, null, null, bandwidthInGbps);

				if(! seqLinks.isEmpty())
				{
					if(Constants.DEBUG) System.out.println("\tAllocating IP demand through the upper layer");
					//Add a route in the IP layer
					Route ipRoute = _netPlan.addRoute(ipDemand, bandwidthInGbps, bandwidthInGbps, seqLinks, null);
					if(Constants.DEBUG) System.out.println("\tAdded Route in IP layer. Index = " + ipRoute.getIndex() + ", CarriedTraffic = " + ipRoute.getCarriedTraffic());
					Pair<Path, Bandwidth> aux = generateSingleLayerPath(ipRoute);
					Path path = aux.getFirst();
					Bandwidth bw1 = aux.getSecond();

					Response response = new Response();
					response.addPath(path);
					response.setBandwidth(bw1);
					response.setRequestParameters(request.getRequestParameters());
					responseList.add(response);

					allocatedConnection = true;
				}

				if(allocatedConnection) continue;
				if(Constants.DEBUG) System.out.println("\tAdding lightpath + MPLS route");

				Node ingressNode_roadm = ingressNode.getOutNeighbors(_ipLayer).iterator().next();
				Node egressNode_roadm = egressNode.getInNeighbors(_ipLayer).iterator().next();

				int allowedBifurcationDegree = 1;
				float minBandwidthPerPathInGbps = bandwidthInGbps;

				LoadBalancing lb = request.getLoadBalancing();
				if(lb != null)
				{
					allowedBifurcationDegree = lb.getMaxLSP();
					minBandwidthPerPathInGbps = lb.getMinBandwidth() * 8f / 1E9f;
				}

				int numLps = (int) Math.ceil(bandwidthInGbps / Constants.LIGHTPATH_BINARY_RATE_GBPS);
				if(Constants.DEBUG)
				{
					System.out.println("\tBandwidth needed = " + bandwidthInGbps);
					System.out.println("\tNumber of lightpaths needed = " + numLps);
					System.out.println("\tAllowed bifurcation = " + allowedBifurcationDegree);
					System.out.println("\tMax bandwidth per lightpath = " + minBandwidthPerPathInGbps);
				}

				if(numLps <= allowedBifurcationDegree && bandwidthInGbps / numLps >= minBandwidthPerPathInGbps)
				{
					if(Constants.DEBUG) System.out.println("\tBalancing constrains are met, adding ligthpaths...");
					Set<Pair<Route, Route>> addedLightpaths = new LinkedHashSet<>();

					for(int lp = 0; lp < numLps; lp++)
					{
						if(Constants.DEBUG) System.out.println("\t\tCandidate lightpath " + lp);
						List<Node> nodesUp = new LinkedList<>(_netPlan.getNodesUp());
						List<Link> linksUp = new LinkedList<>(_netPlan.getLinksUp(_wdmLayer));

						List<List<Link>> cpl = GraphUtils.getKLooplessShortestPaths(nodesUp, linksUp, ingressNode_roadm, egressNode_roadm, null, 5, - 1, - 1, - 1, - 1, - 1, - 1);
						if(Constants.DEBUG) System.out.println("\t\tCandidate PathList size = " + cpl.size());
						for(List<Link> cp : cpl)
						{
							int[] wavelengths = WDMUtils.WA_firstFit(cp, _wavelengthFiberOccupancy);
							if(wavelengths.length > 0)
							{
								if(Constants.DEBUG) System.out.println("\t\t\tWavelength found");
								/* Add lightpath */
								Demand wdmDemand = _netPlan.addDemand(ingressNode_roadm, egressNode_roadm, Constants.LIGHTPATH_BINARY_RATE_GBPS, null, _wdmLayer);
								Route lpRoute = WDMUtils.addLightpathAndUpdateOccupancy(wdmDemand, cp, Constants.LIGHTPATH_BINARY_RATE_GBPS, wavelengths, _wavelengthFiberOccupancy);
								lpRoute.setAttribute(Constants.ATTRIBUTE_LSP_ID, String.valueOf(wavelengths[0]));
								Link ipLink = _netPlan.addLink(ingressNode_roadm, egressNode_roadm, Constants.LIGHTPATH_BINARY_RATE_GBPS, 0, Double.MAX_VALUE, null, _ipLayer);
								ipLink.coupleToLowerLayerDemand(wdmDemand);

								/* Add IP route */
								Demand ipDemand2 = _netPlan.addDemand(ingressNode, egressNode, bandwidthInGbps / numLps, null, _ipLayer);
								List<Link> ipPath = new LinkedList<>();
								Link originLink = _netPlan.getNodePairLinks(ingressNode, ingressNode_roadm, false, _ipLayer).iterator().next();
								Link destinationLink = _netPlan.getNodePairLinks(egressNode_roadm, egressNode, false, _ipLayer).iterator().next();
								ipPath.add(originLink);
								ipPath.add(ipLink);
								ipPath.add(destinationLink);
								Route ipRoute = _netPlan.addRoute(ipDemand2, bandwidthInGbps / numLps, bandwidthInGbps / numLps, ipPath, null);

								addedLightpaths.add(Pair.of(lpRoute, ipRoute));
								break;
							}
						}
					}
					if(Constants.DEBUG) System.out.println("\tLightpaths added = " + addedLightpaths.size());

					if(addedLightpaths.size() == numLps)
					{
						Link originLink = _netPlan.getNodePairLinks(ingressNode, ingressNode_roadm, false, _ipLayer).iterator().next();
						Link destinationLink = _netPlan.getNodePairLinks(egressNode_roadm, egressNode, false, _ipLayer).iterator().next();

						for(Pair<Route, Route> p : addedLightpaths)
						{
							Pair<Path, Bandwidth> aux = generateMultiLayerPath(originLink, destinationLink, p.getFirst(), bandwidthInGbps / numLps);
							Path path = aux.getFirst();
							Bandwidth bw1 = aux.getSecond();

							Response response = new Response();
							response.addPath(path);
							response.setBandwidth(bw1);
							response.setRequestParameters(request.getRequestParameters());
							responseList.add(response);
						}

						allocatedConnection = true;
					} else
					{
						for(Pair<Route, Route> p : addedLightpaths)
							removeLightpath(p);
					}
				}

				if(allocatedConnection) continue;

				NoPath noPath = new NoPath();
				noPath.setNatureOfIssue(0);

				Response response = new Response();
				response.setNoPath(noPath);
				response.setRequestParameters(request.getRequestParameters());
				responseList.add(response);
			}

			responseMsg.setResponsetList(responseList);
			responseMsg.encode();

			outMsg.add(responseMsg);
			return outMsg;
		}catch(Throwable e)
		{
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	private void removeLightpath(Pair<Route, Route> p)
	{
		if(Constants.DEBUG) System.out.println("\tRemoving lightpath = " + p.getFirst().getIndex());
		Route lp = p.getFirst();
		Route ip = p.getSecond();

		/* Remove lightpath */
		Link ipLink = lp.getDemand().getCoupledLink();
		WDMUtils.removeLightpathAndUpdateOccupancy(lp, _wavelengthFiberOccupancy, true);

		/* Remove IP demand and route */
		ip.getDemand().remove();
		ipLink.remove();
	}

}
