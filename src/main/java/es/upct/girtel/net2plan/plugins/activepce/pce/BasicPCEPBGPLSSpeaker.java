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

import com.net2plan.interfaces.networkDesign.*;
import com.net2plan.libraries.GraphUtils;
import com.net2plan.libraries.WDMUtils;
import com.net2plan.utils.LongUtils;
import com.net2plan.utils.Pair;
import edu.uci.ics.jung.algorithms.shortestpath.DijkstraShortestPath;
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
import org.apache.commons.collections15.Transformer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

public class BasicPCEPBGPLSSpeaker extends IPCEEntity
{
	private final Map<InetAddress, Long> _ipNodeIdMap;
	private       NetworkLayer           _wdmLayer, _ipLayer;

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

	private long addLightpath(NetPlan netPlan, long originNodeId, long destinationNodeId)
	{
		if(! fiberTopology_currentState.containsVertex(originNodeId) || ! fiberTopology_currentState.containsVertex(destinationNodeId))
			return - 1;

		double bestCost = Double.MAX_VALUE;
		List<Long> bestSeqFibers = null;
		int bestW = - 1;
		for(int wavelengthId = 0; wavelengthId < Constants.W; wavelengthId++)
		{
			Set<Long> fibersToOmit_thisWavelength = new HashSet<Long>(wavelengthFiberOccupancy.get(wavelengthId));
			Transformer<Long, Double> fiberWeightTransformer_thisWavelength = new WavelengthOccupancyTransformer(null, fibersToOmit_thisWavelength);
			DijkstraShortestPath<Long, Long> dsp = new DijkstraShortestPath<Long, Long>(fiberTopology_currentState, fiberWeightTransformer_thisWavelength);

			Number pathCost = dsp.getDistance(originNodeId, destinationNodeId);
			if(pathCost.doubleValue() == Double.MAX_VALUE) continue;

			if(pathCost.doubleValue() < bestCost)
			{
				bestCost = pathCost.doubleValue();
				bestSeqFibers = dsp.getPath(originNodeId, destinationNodeId);
				bestW = wavelengthId;
			}
		}

		if(bestSeqFibers == null) return - 1;

		long lpDemandId = netPlan.addDemand(wdmLayerId, originNodeId, destinationNodeId, Constants.LIGHTPATH_BINARY_RATE_GBPS, null);
		long lpRouteId = netPlan.addRoute(wdmLayerId, lpDemandId, Constants.LIGHTPATH_BINARY_RATE_GBPS, 1, bestSeqFibers, null);
		WDMUtils.allocateResources(bestSeqFibers, bestW, wavelengthFiberOccupancy);
		WDMUtils.setLightpathSeqWavelengths(netPlan, wdmLayerId, lpRouteId, bestW);
		netPlan.setRouteAttribute(wdmLayerId, lpRouteId, "lspId", Long.toString(lpRouteId));

		long lightpathId = netPlan.createUpperLayerLinkFromDemand(wdmLayerId, lpDemandId, ipLayerId);
		return lightpathId;
	}

	private Pair<Path, Bandwidth> generateMultiLayerPath(NetPlan netPlan, long originLinkId, long lightpathId, long destinationLinkId, float bandwidthInGbps)
	{
		try
		{
			Path path = new Path();
			ExplicitRouteObject ero = new ExplicitRouteObject();
			path.setEro(ero);

			IPv4prefixEROSubobject eroSubObject_sourceNode = new IPv4prefixEROSubobject();
			eroSubObject_sourceNode.setIpv4address(Utils.getLinkSourceIPAddress(netPlan, ipLayerId, originLinkId));
			eroSubObject_sourceNode.setLoosehop(false);
			eroSubObject_sourceNode.setPrefix(32);
			ero.addEROSubobject(eroSubObject_sourceNode);

			ServerLayerInfo eroSubObject_switchToWDMLayer = new ServerLayerInfo();
			eroSubObject_switchToWDMLayer.setEncoding(8);
			eroSubObject_switchToWDMLayer.setSwitchingCap(150);
			ero.addEROSubobject(eroSubObject_switchToWDMLayer);

			long lpRouteId = netPlan.getDemandRoutes(wdmLayerId, netPlan.getLinkCoupledLowerLayerDemand(ipLayerId, lightpathId).getSecond()).iterator().next();
			List<Long> seqFibers = netPlan.getRouteSequenceOfLinks(wdmLayerId, lpRouteId);
			int[] seqWavelengths = WDMUtils.getLightpathSeqWavelengths(netPlan, wdmLayerId, lpRouteId);
			ListIterator<Long> fiberIt = seqFibers.listIterator();
			while(fiberIt.hasNext())
			{
				int hopId = fiberIt.nextIndex();
				long fiberId = fiberIt.next();
				int wavelengthId = seqWavelengths[hopId];

				UnnumberIfIDEROSubobject eroSubObject_thisFiber = new UnnumberIfIDEROSubobject();
				eroSubObject_thisFiber.setRouterID(Utils.getLinkSourceIPAddress(netPlan, wdmLayerId, fiberId));
				eroSubObject_thisFiber.setInterfaceID(Utils.getLinkSourceInterface(netPlan, wdmLayerId, fiberId));
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
			eroSubObject_destinationNode.setIpv4address(Utils.getLinkDestinationIPAddress(netPlan, ipLayerId, destinationLinkId));
			eroSubObject_destinationNode.setLoosehop(false);
			eroSubObject_destinationNode.setPrefix(32);
			ero.addEROSubobject(eroSubObject_destinationNode);

			BandwidthRequested bw = new BandwidthRequested();
			bw.setBw(bandwidthInGbps * 1E9f / 8f);

			return Pair.of(path, (Bandwidth) bw);
		}catch(UnknownHostException | RSVPProtocolViolationException e)
		{
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
				if(Constants.DEBUG) System.out.println("\t\t"+Utils.getLinkSourceIPAddress(_netPlan, link.getId()).getHostAddress());
			}

			IPv4prefixEROSubobject eroSubObject_destinationNode = new IPv4prefixEROSubobject();
			eroSubObject_destinationNode.setIpv4address(Utils.getLinkDestinationIPAddress(_netPlan, destinationLinkId));
			eroSubObject_destinationNode.setLoosehop(false);
			eroSubObject_destinationNode.setPrefix(32);
			ero.addEROSubobject(eroSubObject_destinationNode);

			BandwidthRequested bw = new BandwidthRequested();
			bw.setBw((float) route.getCarriedTraffic() * 1E9f / 8f);

			if(Constants.DEBUG)
			{
				System.out.println("\t\t"+Utils.getLinkDestinationIPAddress(_netPlan, destinationLinkId));
				System.out.println("\t\tBandwidth = "+bw.getBw());
			}

			return Pair.of(path, (Bandwidth) bw);
		}catch(Throwable e)
		{
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

	private List handleFailureReparationEvent() //FIXME ver que hace aquí
	{
		System.out.println("Handling failure event!"); //DEBUG
		try
		{
			List outMsg = new LinkedList();

			LinkedList<UpdateRequest> updateList = new LinkedList<UpdateRequest>();
			Set<Long> affectedLightpathRoutes = new LinkedHashSet<Long>(_netPlan.getRoutesDown(wdmLayerId));
			for(long lpRouteId : affectedLightpathRoutes)
			{
				System.out.println("Route " + lpRouteId + " DOWN"); //DEBUG
				List<Long> seqFibers = _netPlan.getRouteSequenceOfLinks(wdmLayerId, lpRouteId);
				int[] seqWavelengths = WDMUtils.getLightpathSeqWavelengths(_netPlan, wdmLayerId, lpRouteId);
				WDMUtils.releaseResources(seqFibers, seqWavelengths, wavelengthFiberOccupancy);

				long originNodeId = _netPlan.getRouteIngressNode(wdmLayerId, lpRouteId);
				long destinationNodeId = _netPlan.getRouteEgressNode(wdmLayerId, lpRouteId);

				int chosenWavelengthId = - 1;
				for(int wavelengthId = 0; wavelengthId < Constants.W; wavelengthId++)
				{
					Set<Long> fibersToOmit_thisWavelength = new HashSet<Long>(wavelengthFiberOccupancy.get(wavelengthId));
					Transformer<Long, Double> fiberWeightTransformer_thisWavelength = new WavelengthOccupancyTransformer(null, fibersToOmit_thisWavelength);
					DijkstraShortestPath<Long, Long> dsp = new DijkstraShortestPath<Long, Long>(fiberTopology_currentState, fiberWeightTransformer_thisWavelength);

					Number pathCost = dsp.getDistance(originNodeId, destinationNodeId);
					if(pathCost.doubleValue() == Double.MAX_VALUE) continue;

					List<Long> newSeqFibers = dsp.getPath(originNodeId, destinationNodeId);
					_netPlan.setRouteSequenceOfLinks(wdmLayerId, lpRouteId, newSeqFibers);
					WDMUtils.allocateResources(newSeqFibers, wavelengthId, wavelengthFiberOccupancy);
					WDMUtils.setLightpathSeqWavelengths(_netPlan, wdmLayerId, lpRouteId, wavelengthId);

					chosenWavelengthId = wavelengthId;
					_netPlan.setRouteUp(wdmLayerId, lpRouteId);
					System.out.println("New seq of links:"); //DEBUG
					System.out.println(Arrays.toString(LongUtils.toArray(_netPlan.getRouteSequenceOfLinks(wdmLayerId, lpRouteId)))); //DEBUG
					break;
				}

				UpdateRequest updateRequest = new UpdateRequest();

				SRP srp = new SRP();
				updateRequest.setSrp(srp);

				LSP lsp = new LSP();
				lsp.setLspId(Integer.parseInt(_netPlan.getRouteAttribute(wdmLayerId, lpRouteId, "lspId")));
				lsp.setAFlag(chosenWavelengthId != - 1);
				updateRequest.setLsp(lsp);

				if(chosenWavelengthId != - 1)
				{
					Path path = new Path();
					ExplicitRouteObject ero = new ExplicitRouteObject();
					path.setEro(ero);

					seqFibers = _netPlan.getRouteSequenceOfLinks(wdmLayerId, lpRouteId);
					long lastFiberId = - 1;
					for(long fiberId : seqFibers)
					{
						long originNodeId_thisFiber = _netPlan.getLinkOriginNode(wdmLayerId, fiberId);
						UnnumberIfIDEROSubobject eroso = new UnnumberIfIDEROSubobject();
						eroso.setRouterID(Utils.getNodeIPAddress(_netPlan, originNodeId_thisFiber));
						eroso.setInterfaceID(Utils.getLinkSourceInterface(_netPlan, wdmLayerId, fiberId));
						eroso.setLoosehop(false);
						ero.addEROSubobject(eroso);

						GeneralizedLabelEROSubobject genLabel = new GeneralizedLabelEROSubobject();
						ero.addEROSubobject(genLabel);
						DWDMWavelengthLabel wavelengthLabel = new DWDMWavelengthLabel();
						wavelengthLabel.setGrid(1);
						wavelengthLabel.setChannelSpacing(2);
						wavelengthLabel.setN(chosenWavelengthId);
						wavelengthLabel.setIdentifier(0);
						wavelengthLabel.encode();
						genLabel.setLabel(wavelengthLabel.getBytes());

						lastFiberId = fiberId;
					}

					UnnumberIfIDEROSubobject eroso = new UnnumberIfIDEROSubobject();
					eroso.setRouterID(Utils.getLinkDestinationIPAddress(_netPlan, wdmLayerId, lastFiberId));
					eroso.setInterfaceID(Utils.getLinkDestinationInterface(_netPlan, wdmLayerId, lastFiberId));
					eroso.setLoosehop(false);
					ero.addEROSubobject(eroso);

					updateRequest.setPath(path);
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
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	private List handleLinkUpdate(LinkNLRI linkNLRI, boolean isUp)
	{
		List outMsg = new LinkedList();

		Inet4Address sourceNodeIPAddress = linkNLRI.getIpv4InterfaceAddressTLV().getIpv4Address();
		Inet4Address destinationNodeIPAddress = linkNLRI.getIpv4NeighborAddressTLV().getIpv4Address();
		long originNodeId = _ipNodeIdMap.get(sourceNodeIPAddress);
		long destinationNodeId = _ipNodeIdMap.get(destinationNodeIPAddress);

		Set<Long> possibleLinkIds = _netPlan.getNodePairLinks(originNodeId, destinationNodeId);
		String sourceNodeType = _netPlan.getNodeAttribute(originNodeId, "type");
		String destinationNodeType = _netPlan.getNodeAttribute(destinationNodeId, "type");

		if(sourceNodeType.equals(destinationNodeType))
		{
			long srcIf = linkNLRI.getLinkIdentifiersTLV().getLinkLocalIdentifier();
			long dstIf = linkNLRI.getLinkIdentifiersTLV().getLinkRemoteIdentifier();

			for(long linkId : possibleLinkIds)
			{
				long srcIf_thisLink = Utils.getLinkSourceInterface(_netPlan, linkId);
				long dstIf_thisLink = Utils.getLinkSourceInterface(_netPlan, linkId);

				if(! isUp)
				{
					System.out.println("srcIf = " + srcIf);
					System.out.println("srcIf_thisLink =" + srcIf_thisLink);
					System.out.println("dstIf = " + dstIf);
					System.out.println("dstIf_thisLink = " + dstIf_thisLink);
				}

				//if(srcIf == srcIf_thisLink && dstIf == dstIf_thisLink) FIXME whyyy?
				if(true)
				{
					//					if (_netPlan.isLinkDown(wdmLayerId, linkId)) _netPlan.setLinkUp(wdmLayerId, linkId);
					//					else _netPlan.setLinkDown(wdmLayerId, linkId);

					//					if (isUp && _netPlan.isLinkDown(wdmLayerId, linkId)) _netPlan.setLinkUp(wdmLayerId, linkId);
					//					else _netPlan.setLinkDown(wdmLayerId, linkId); //Deprecated!

					if(isUp && _netPlan.isLinkDown(wdmLayerId, linkId))
					{
						_netPlan.setLinkUp(wdmLayerId, linkId);
						System.out.println("Link " + linkId + "set as DOWN"); //DEBUG
					} else _netPlan.setLinkDown(wdmLayerId, linkId);

					updateFiberTopology();
					outMsg.addAll(handleFailureReparationEvent());

					return outMsg;
				}
			}

			long linkId = _netPlan.addLink(wdmLayerId, originNodeId, destinationNodeId, 40, 0, null);
			_netPlan.setLinkAttribute(wdmLayerId, linkId, "srcIf", Long.toString(srcIf));
			_netPlan.setLinkAttribute(wdmLayerId, linkId, "dstIf", Long.toString(dstIf));

			//			if(isUp && _netPlan.isLinkDown(wdmLayerId, linkId)) _netPlan.setLinkUp(wdmLayerId, linkId);
			//			else _netPlan.setLinkDown(wdmLayerId, linkId);

			fiberTopology.addEdge(linkId, originNodeId, destinationNodeId);
			updateFiberTopology();
		} else
		{
			if(possibleLinkIds.isEmpty())
			{
				long linkId = _netPlan.addLink(ipLayerId, originNodeId, destinationNodeId, Double.MAX_VALUE, 0, null);
				if(! isUp) _netPlan.setLinkDown(ipLayerId, linkId);
			} else
			{
				long linkId = possibleLinkIds.iterator().next();

				if(_netPlan.isLinkDown(ipLayerId, linkId)) _netPlan.setLinkUp(ipLayerId, linkId);
				else _netPlan.setLinkDown(ipLayerId, linkId);

				//				if (isUp && _netPlan.isLinkDown(ipLayerId, linkId)) _netPlan.setLinkUp(ipLayerId, linkId);
				//				else _netPlan.setLinkDown(ipLayerId, linkId);
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

			node.setFailureState(isUp);

			outMsg.addAll(handleFailureReparationEvent());
		} else
		{
			Node node = _netPlan.addNode(0, 0, "", null);
			node.setAttribute(Constants.ATTRIBUTE_IP_ADDRESS, ipAddress.getHostAddress());
			node.setAttribute(Constants.ATTRIBUTE_NODE_TYPE, nodeNLRI.getRoutingUniverseIdentifier() == RoutingUniverseIdentifierTypes.Level3Identifier ? "ipRouter" : "roadm");

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
					System.out.println("\tIP Ingress Node = "+ingressNode);
					System.out.println("\tIP Egress Node = "+egressNode);
					System.out.println("\tIP Demand index = "+ipDemand.getIndex());
				}
				
				/* First, try to allocate over a single path over the available resources at the IP layer */
				//TODO select K shortest paths instead
				List<Link> seqLinks = GraphUtils.getCapacitatedShortestPath(_netPlan.getNodes(), _netPlan.getLinks(_ipLayer), ingressNode, egressNode, null, null, bandwidthInGbps);

				if(! seqLinks.isEmpty())
				{
					if(Constants.DEBUG) System.out.println("\tAllocating IP demand through the upper layer");
					//Add a route in the IP layer
					Route ipRoute = _netPlan.addRoute(ipDemand, bandwidthInGbps, bandwidthInGbps, seqLinks, null);
					if(Constants.DEBUG) System.out.println("\tAdded Route in IP layer. Index = "+ipRoute.getIndex()+", CarriedTraffic = "+ipRoute.getCarriedTraffic());
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

				long ingressNodeId_roadm = _netPlan.getNodeOutNeighbors(ipLayerId, ingressNodeId).iterator().next();
				long egressNodeId_roadm = _netPlan.getNodeInNeighbors(ipLayerId, egressNodeId).iterator().next();

				int allowedBifurcationDegree = 1;
				float minBandwidthPerPathInGbps = bandwidthInGbps;

				LoadBalancing lb = request.getLoadBalancing();
				if(lb != null)
				{
					allowedBifurcationDegree = lb.getMaxLSP();
					minBandwidthPerPathInGbps = lb.getMinBandwidth() * 8f / 1E9f;
				}

				int numLps = (int) Math.ceil(bandwidthInGbps / Constants.LIGHTPATH_BINARY_RATE_GBPS);

				if(numLps <= allowedBifurcationDegree && bandwidthInGbps / numLps >= minBandwidthPerPathInGbps)
				{
					Set<Long> addedLightpaths = new LinkedHashSet<Long>();

					for(int lp = 0; lp < numLps; lp++)
					{
						long lightpathId = addLightpath(_netPlan, ingressNodeId_roadm, egressNodeId_roadm);
						if(lightpathId == - 1) break;

						addedLightpaths.add(lightpathId);
					}

					if(addedLightpaths.size() == numLps)
					{
						long originLinkId = _netPlan.getNodePairLinks(ipLayerId, ingressNodeId, ingressNodeId_roadm).iterator().next();
						long destinationLinkId = _netPlan.getNodePairLinks(ipLayerId, egressNodeId_roadm, egressNodeId).iterator().next();

						for(long lightpathId : addedLightpaths)
						{
							Pair<Path, Bandwidth> aux = generateMultiLayerPath(_netPlan, originLinkId, lightpathId, destinationLinkId, bandwidthInGbps / numLps);
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
						for(long lightpathId : addedLightpaths)
							removeLightpath(lightpathId);
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

	private void removeLightpath(long lightpathId)
	{
		long lpRouteId = _netPlan.getDemandRoutes(wdmLayerId, _netPlan.getLinkCoupledLowerLayerDemand(ipLayerId, lightpathId).getSecond()).iterator().next();
		List<Long> seqFibers = _netPlan.getRouteSequenceOfLinks(wdmLayerId, lpRouteId);
		int[] seqWavelengths = WDMUtils.getLightpathSeqWavelengths(_netPlan, wdmLayerId, lpRouteId);

		WDMUtils.releaseResources(seqFibers, seqWavelengths, wavelengthFiberOccupancy);
		_netPlan.removeLink(ipLayerId, lightpathId);
	}

}
