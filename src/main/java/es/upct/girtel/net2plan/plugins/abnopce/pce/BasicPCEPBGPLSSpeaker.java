/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package es.upct.girtel.net2plan.plugins.abnopce.pce;

import com.net2plan.cli.CLINet2Plan;
import com.net2plan.interfaces.networkDesign.NetPlan;
import com.net2plan.internal.plugins.ICLIModule;
import com.net2plan.internal.plugins.PluginSystem;
import com.net2plan.libraries.GraphUtils;
import com.net2plan.libraries.GraphUtils.JUNGUtils;
import com.net2plan.libraries.WDMUtils;
import com.net2plan.utils.LongUtils;
import com.net2plan.utils.Pair;
import com.net2plan.utils.StringUtils;
import edu.uci.ics.jung.algorithms.shortestpath.DijkstraShortestPath;
import edu.uci.ics.jung.graph.Graph;
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
import es.upct.girtel.net2plan.plugins.abnopce.utils.Utils;
import org.apache.commons.collections15.Transformer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

/**
 *
 * @author Jose Luis
 */
public class BasicPCEPBGPLSSpeaker extends IPCEEntity
{
	private final Map<InetAddress, Long> ip2NodeId;
	private final long wdmLayerId, ipLayerId;
	private final int W;
	private final double lightpathBinaryRate_Gbps;
	private final List<Set<Long>> wavelengthFiberOccupancy;
	private Graph<Long, Long> fiberTopology, fiberTopology_currentState;
	
	public BasicPCEPBGPLSSpeaker()
	{
		ip2NodeId = new HashMap<InetAddress, Long>();
		
		lightpathBinaryRate_Gbps = 40;
		W = 40;
		wdmLayerId = 0;
		ipLayerId = netPlan.addLayer("ipLayer", null, null, null, null);
		
		wavelengthFiberOccupancy = new ArrayList<Set<Long>>(W);
		for(int wavelengthId = 0; wavelengthId < W; wavelengthId++)
			wavelengthFiberOccupancy.add(new HashSet<Long>());
		
		fiberTopology = JUNGUtils.getGraphFromLinkMap(new NetPlan().getLinkMap());
		updateFiberTopology();
	}
	
	@Override
	public List handleBGPMessage(BGP4Message message)
	{
//		System.out.println("handleBGPMessage: " + message);
		
		List outMsg = new LinkedList();
		
		int messageType = BGP4Message.getMessageType(message.getBytes());
		switch(messageType)
		{
			case BGP4MessageTypes.MESSAGE_UPDATE:
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
//		System.out.println("handlePCEPMessage: " + message);
		
		List outMsg = new LinkedList();
		
		try
		{
			int messageType = PCEPMessage.getMessageType(message.getBytes());
			switch(messageType)
			{
				case PCEPMessageTypes.MESSAGE_PCREQ:
					PCEPRequest request = new PCEPRequest(message.getBytes());
					outMsg.addAll(handlePCEPRequest(request));
					break;

				case PCEPMessageTypes.MESSAGE_REPORT:
					PCEPReport report = new PCEPReport(message.getBytes());
					outMsg.addAll(handlePCEPReport(report));
					break;

				default:
					break;
			}
		}
		catch(PCEPProtocolViolationException e)
		{
			e.printStackTrace();
		}
		catch(Throwable e)
		{
			e.printStackTrace();
		}
		
		return outMsg;
	}

	public static void main(String args[]) throws InstantiationException, IllegalAccessException
	{
		PluginSystem.addPlugin(ICLIModule.class, PCEMainServer.class);
		args = StringUtils.arrayOf("--mode", "pce");
		CLINet2Plan.main(args);
	}

	
	private long addLightpath(NetPlan netPlan, long originNodeId, long destinationNodeId)
	{
		if (!fiberTopology_currentState.containsVertex(originNodeId) || !fiberTopology_currentState.containsVertex(destinationNodeId))
			return -1;
		
		double bestCost = Double.MAX_VALUE;
		List<Long> bestSeqFibers = null;
		int bestW = -1;
		for(int wavelengthId = 0; wavelengthId < W; wavelengthId++)
		{
			Set<Long> fibersToOmit_thisWavelength = new HashSet<Long>(wavelengthFiberOccupancy.get(wavelengthId));
			Transformer<Long, Double> fiberWeightTransformer_thisWavelength = new WavelengthOccupancyTransformer(null, fibersToOmit_thisWavelength);
			DijkstraShortestPath<Long, Long> dsp = new DijkstraShortestPath<Long, Long>(fiberTopology_currentState, fiberWeightTransformer_thisWavelength);

			Number pathCost = dsp.getDistance(originNodeId, destinationNodeId);
			if (pathCost.doubleValue() == Double.MAX_VALUE) continue;
			
			if (pathCost.doubleValue() < bestCost)
			{
				bestCost = pathCost.doubleValue();
				bestSeqFibers = dsp.getPath(originNodeId, destinationNodeId);
				bestW = wavelengthId;
			}
		}
		
		if (bestSeqFibers == null) return -1;
		
		long lpDemandId = netPlan.addDemand(wdmLayerId, originNodeId, destinationNodeId, lightpathBinaryRate_Gbps, null);
		long lpRouteId = netPlan.addRoute(wdmLayerId, lpDemandId, lightpathBinaryRate_Gbps, 1, bestSeqFibers, null);
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
		}
		catch(UnknownHostException | RSVPProtocolViolationException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	private Pair<Path, Bandwidth> generateSingleLayerPath(NetPlan netPlan, long layerId, List<Long> seqLinks, float bandwidthInGbps)
	{
		try
		{
			Path path = new Path();
			ExplicitRouteObject ero = new ExplicitRouteObject();
			path.setEro(ero);

			long destinationLinkId = -1;
			for(long linkId : seqLinks)
			{
				IPv4prefixEROSubobject eroSubObject_thisLink = new IPv4prefixEROSubobject();
				eroSubObject_thisLink.setIpv4address(Utils.getLinkSourceIPAddress(netPlan, layerId, linkId));
				eroSubObject_thisLink.setLoosehop(false);
				eroSubObject_thisLink.setPrefix(32);
				ero.addEROSubobject(eroSubObject_thisLink);
				
				destinationLinkId = linkId;
			}

			IPv4prefixEROSubobject eroSubObject_destinationNode = new IPv4prefixEROSubobject();
			eroSubObject_destinationNode.setIpv4address(Utils.getLinkDestinationIPAddress(netPlan, layerId, destinationLinkId));
			eroSubObject_destinationNode.setLoosehop(false);
			eroSubObject_destinationNode.setPrefix(32);
			ero.addEROSubobject(eroSubObject_destinationNode);
			
			BandwidthRequested bw = new BandwidthRequested();
			bw.setBw(bandwidthInGbps * 1E9f / 8f);

			return Pair.of(path, (Bandwidth) bw);
		}
		catch(Throwable e)
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
					isUp = true;
					break;
					
				case PathAttributesTypeCode.PATH_ATTRIBUTE_TYPECODE_MP_UN_REACH_NLRI:
					isUp = false;
					break;

				default:
					break; /* Do nothing */
			}
		}
		
		if (nlri instanceof NodeNLRI)
		{
			NodeNLRI nodeNLRI = (NodeNLRI) nlri;
			outMsg.addAll(handleNodeUpdate(nodeNLRI, isUp));
		}
		else if (nlri instanceof LinkNLRI)
		{
			LinkNLRI linkNLRI = (LinkNLRI) nlri;
			outMsg.addAll(handleLinkUpdate(linkNLRI, isUp));
		}
		else
		{
			/*
			 * RFC4271 Section 6.3 (UPDATE Message Error Handling)
			 * All errors detected while processing the UPDATE message MUST be 
			 * indicated by sending the NOTIFICATION message with the Error Code
             * UPDATE Message Error.  The error subcode elaborates on the specific
             * nature of the error
			 *
			 * ToDo: Error code = 3 (UPDATE Message Error), Error subcode = 1 (Malformed Attribute List)
			 */
			
//			BGP4Notification bgpError = new BGP4Notification();
//			bgpError.encode();
//			outMsg.add(bgpError);
		}
			
		return outMsg;
	}
	
	private List handleFailureReparationEvent() //FIXME ver que hace aquí
	{
		try
		{
			List outMsg = new LinkedList();
			
			LinkedList<UpdateRequest> updateList = new LinkedList<UpdateRequest>();
			Set<Long> affectedLightpathRoutes = new LinkedHashSet<Long>(netPlan.getRoutesDown(wdmLayerId));
			for(long lpRouteId : affectedLightpathRoutes)
			{
				System.out.println("Route "+lpRouteId+" DOWN"); //DEBUG
				List<Long> seqFibers = netPlan.getRouteSequenceOfLinks(wdmLayerId, lpRouteId);
				int[] seqWavelengths = WDMUtils.getLightpathSeqWavelengths(netPlan, wdmLayerId, lpRouteId);
				WDMUtils.releaseResources(seqFibers, seqWavelengths, wavelengthFiberOccupancy);

				long originNodeId = netPlan.getRouteIngressNode(wdmLayerId, lpRouteId);
				long destinationNodeId = netPlan.getRouteEgressNode(wdmLayerId, lpRouteId);

				int chosenWavelengthId = -1;
				for(int wavelengthId = 0; wavelengthId < W; wavelengthId++)
				{
					Set<Long> fibersToOmit_thisWavelength = new HashSet<Long>(wavelengthFiberOccupancy.get(wavelengthId));
					Transformer<Long, Double> fiberWeightTransformer_thisWavelength = new WavelengthOccupancyTransformer(null, fibersToOmit_thisWavelength);
					DijkstraShortestPath<Long, Long> dsp = new DijkstraShortestPath<Long, Long>(fiberTopology_currentState, fiberWeightTransformer_thisWavelength);

					Number pathCost = dsp.getDistance(originNodeId, destinationNodeId);
					if (pathCost.doubleValue() == Double.MAX_VALUE) continue;

					List<Long> newSeqFibers = dsp.getPath(originNodeId, destinationNodeId);
					netPlan.setRouteSequenceOfLinks(wdmLayerId, lpRouteId, newSeqFibers);
					WDMUtils.allocateResources(newSeqFibers, wavelengthId, wavelengthFiberOccupancy);
					WDMUtils.setLightpathSeqWavelengths(netPlan, wdmLayerId, lpRouteId, wavelengthId);

					chosenWavelengthId = wavelengthId;
					netPlan.setRouteUp(wdmLayerId, lpRouteId);
					System.out.println("New seq of links:"); //DEBUG
					System.out.println(Arrays.toString(LongUtils.toArray(netPlan.getRouteSequenceOfLinks(wdmLayerId, lpRouteId)))); //DEBUG
					break;
				}

				UpdateRequest updateRequest = new UpdateRequest();
				
				SRP srp = new SRP();
				updateRequest.setSrp(srp);
				
				LSP lsp = new LSP();
				lsp.setLspId(Integer.parseInt(netPlan.getRouteAttribute(wdmLayerId, lpRouteId, "lspId")));
				lsp.setAFlag(chosenWavelengthId != -1);
				updateRequest.setLsp(lsp);

				if (chosenWavelengthId != -1)
				{
					Path path = new Path();
					ExplicitRouteObject ero = new ExplicitRouteObject();
					path.setEro(ero);

					seqFibers = netPlan.getRouteSequenceOfLinks(wdmLayerId, lpRouteId);
					long lastFiberId = -1;
					for(long fiberId : seqFibers)
					{
						long originNodeId_thisFiber = netPlan.getLinkOriginNode(wdmLayerId, fiberId);
						UnnumberIfIDEROSubobject eroso = new UnnumberIfIDEROSubobject();
						eroso.setRouterID(Utils.getNodeIPAddress(netPlan, originNodeId_thisFiber));
						eroso.setInterfaceID(Utils.getLinkSourceInterface(netPlan, wdmLayerId, fiberId));
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
					eroso.setRouterID(Utils.getLinkDestinationIPAddress(netPlan, wdmLayerId, lastFiberId));
					eroso.setInterfaceID(Utils.getLinkDestinationInterface(netPlan, wdmLayerId, lastFiberId));
					eroso.setLoosehop(false);
					ero.addEROSubobject(eroso);
					
					updateRequest.setPath(path);
				}

				updateList.add(updateRequest);
			}

			if (!updateList.isEmpty())
			{
				PCEPUpdate updateMsg = new PCEPUpdate();
				updateMsg.setUpdateRequestList(updateList);
				updateMsg.encode();
				
				outMsg.add(updateMsg);
			}
			
			return outMsg;
		}
		catch(Throwable e)
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
		long originNodeId = ip2NodeId.get(sourceNodeIPAddress);
		long destinationNodeId = ip2NodeId.get(destinationNodeIPAddress);
		
		Set<Long> possibleLinkIds = netPlan.getNodePairLinks(originNodeId, destinationNodeId);
		String sourceNodeType = netPlan.getNodeAttribute(originNodeId, "type");
		String destinationNodeType = netPlan.getNodeAttribute(destinationNodeId, "type");
		
		if (sourceNodeType.equals(destinationNodeType))
		{
			long srcIf = linkNLRI.getLinkIdentifiersTLV().getLinkLocalIdentifier();
			long dstIf = linkNLRI.getLinkIdentifiersTLV().getLinkRemoteIdentifier();
			
			for(long linkId : possibleLinkIds)
			{
				long srcIf_thisLink = Utils.getLinkSourceInterface(netPlan, linkId);
				long dstIf_thisLink = Utils.getLinkSourceInterface(netPlan, linkId);
				
				if (srcIf == srcIf_thisLink && dstIf == dstIf_thisLink)
				{
					if (netPlan.isLinkDown(wdmLayerId, linkId)) netPlan.setLinkUp(wdmLayerId, linkId);
					else netPlan.setLinkDown(wdmLayerId, linkId);
					
//					if (isUp && netPlan.isLinkDown(wdmLayerId, linkId)) netPlan.setLinkUp(wdmLayerId, linkId);
//					else netPlan.setLinkDown(wdmLayerId, linkId);
					
					updateFiberTopology();
					outMsg.addAll(handleFailureReparationEvent());
					
					return outMsg;
				}
			}		
			
			long linkId = netPlan.addLink(wdmLayerId, originNodeId, destinationNodeId, 40, 0, null);
			netPlan.setLinkAttribute(wdmLayerId, linkId, "srcIf", Long.toString(srcIf));
			netPlan.setLinkAttribute(wdmLayerId, linkId, "dstIf", Long.toString(dstIf));
			
			if (!isUp) netPlan.setLinkDown(wdmLayerId, linkId);
			
			fiberTopology.addEdge(linkId, originNodeId, destinationNodeId);
			updateFiberTopology();
		}
		else
		{
			if (possibleLinkIds.isEmpty())
			{
				long linkId = netPlan.addLink(ipLayerId, originNodeId, destinationNodeId, Double.MAX_VALUE, 0, null);
				if (!isUp) netPlan.setLinkDown(ipLayerId, linkId);
			}
			else
			{
				long linkId = possibleLinkIds.iterator().next();
				
				if (netPlan.isLinkDown(ipLayerId, linkId)) netPlan.setLinkUp(ipLayerId, linkId);
				else netPlan.setLinkDown(ipLayerId, linkId);
				
//				if (isUp && netPlan.isLinkDown(ipLayerId, linkId)) netPlan.setLinkUp(ipLayerId, linkId);
//				else netPlan.setLinkDown(ipLayerId, linkId);
			}
		}
		
		return outMsg;
	}
	
	private List handleNodeUpdate(NodeNLRI nodeNLRI, boolean isUp)
	{
		List outMsg = new LinkedList();
		
		//Inet4Address ipAddress = ((IGPRouterIDNodeDescriptorSubTLV) nodeNLRI.getLocalNodeDescriptors().getNodeDescriptorsSubTLVList().get(0)).getIpv4AddressOSPF();
		Inet4Address ipAddress = nodeNLRI.getLocalNodeDescriptors().getIGPRouterID().getIpv4AddressOSPF();
		if (ip2NodeId.containsKey(ipAddress))
		{
			long nodeId = ip2NodeId.get(ipAddress);
			
			if (isUp && netPlan.isNodeDown(nodeId)) netPlan.setNodeUp(nodeId);
			else netPlan.setNodeDown(nodeId);
			
			updateFiberTopology();
			outMsg.addAll(handleFailureReparationEvent());
		}
		else
		{
			long nodeId = netPlan.addNode(0, 0, "", null);
			netPlan.setNodeAttribute(nodeId, "ipAddress", ipAddress.getHostAddress());
			netPlan.setNodeAttribute(nodeId, "type", nodeNLRI.getRoutingUniverseIdentifier() == RoutingUniverseIdentifierTypes.Level3Identifier ? "ipRouter" : "roadm");
			ip2NodeId.put(ipAddress, nodeId);
			
			if (netPlan.getNodeAttribute(nodeId, "type").equals("roadm"))
				updateFiberTopology();
			
			if (!isUp) netPlan.setNodeDown(nodeId);
		}
		
		return outMsg;
	}
	
	private List handlePCEPReport(PCEPReport report)
	{
		return new LinkedList();
	}

	private List handlePCEPRequest(PCEPRequest requestMsg)
	{
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
				final long ingressNodeId = ip2NodeId.get(endPoints.getSourceIP());
				final long egressNodeId = ip2NodeId.get(endPoints.getDestIP());
				BandwidthRequested bw = (BandwidthRequested) request.getBandwidth();
				float bandwidthInGbps = bw.getBw() * 8f / 1E9f;
				
				long demandId = netPlan.addDemand(ipLayerId, ingressNodeId, egressNodeId, bandwidthInGbps, null);
				netPlan.setDemandAttribute(ipLayerId, demandId, "requestId", Long.toString(requestId));
				
				/* First, try to allocate over a single path over the available resources at the IP layer */
				Graph<Long, Long> graph = JUNGUtils.getGraphFromLinkMap(netPlan.getLinkMap(ipLayerId));
				Transformer<Long, Double> spareCapacityTransformer = JUNGUtils.getEdgeWeightTransformer(netPlan.getLinkSpareCapacityMap(ipLayerId));
				List<Long> seqLinks = JUNGUtils.getCapacitatedShortestPath(graph, null, ingressNodeId, egressNodeId, spareCapacityTransformer, bandwidthInGbps);
				if (!seqLinks.isEmpty())
				{
					Pair<Path, Bandwidth> aux = generateSingleLayerPath(netPlan, ipLayerId, seqLinks, bandwidthInGbps);
					Path path = aux.getFirst();
					Bandwidth bw1 = aux.getSecond();
					
					Response response = new Response();
					response.addPath(path);
					response.setBandwidth(bw1);
					response.setRequestParameters(request.getRequestParameters());
					responseList.add(response);
					
					allocatedConnection = true;
				}
				
				if (allocatedConnection) continue;
				
				long ingressNodeId_roadm = netPlan.getNodeOutNeighbors(ipLayerId, ingressNodeId).iterator().next();
				long egressNodeId_roadm = netPlan.getNodeInNeighbors(ipLayerId, egressNodeId).iterator().next();
				
				int allowedBifurcationDegree = 1;
				float minBandwidthPerPathInGbps = bandwidthInGbps;
				
				LoadBalancing lb = request.getLoadBalancing();
				if (lb != null)
				{
					allowedBifurcationDegree = lb.getMaxLSP();
					minBandwidthPerPathInGbps = lb.getMinBandwidth() * 8f / 1E9f;
				}
				
				int numLps = (int) Math.ceil(bandwidthInGbps / lightpathBinaryRate_Gbps);
				
				if (numLps <= allowedBifurcationDegree && bandwidthInGbps / numLps >= minBandwidthPerPathInGbps)
				{
					Set<Long> addedLightpaths = new LinkedHashSet<Long>();

					for(int lp = 0; lp < numLps; lp++)
					{
						long lightpathId = addLightpath(netPlan, ingressNodeId_roadm, egressNodeId_roadm);
						if (lightpathId == -1) break;

						addedLightpaths.add(lightpathId);
					}
					
					if (addedLightpaths.size() == numLps)
					{
						long originLinkId = netPlan.getNodePairLinks(ipLayerId, ingressNodeId, ingressNodeId_roadm).iterator().next();
						long destinationLinkId = netPlan.getNodePairLinks(ipLayerId, egressNodeId_roadm, egressNodeId).iterator().next();
						
						for(long lightpathId : addedLightpaths)
						{
							Pair<Path, Bandwidth> aux = generateMultiLayerPath(netPlan, originLinkId, lightpathId, destinationLinkId, bandwidthInGbps / numLps);
							Path path = aux.getFirst();
							Bandwidth bw1 = aux.getSecond();

							Response response = new Response();
							response.addPath(path);
							response.setBandwidth(bw1);
							response.setRequestParameters(request.getRequestParameters());
							responseList.add(response);
						}
						
						allocatedConnection = true;
					}
					else
					{
						for(long lightpathId : addedLightpaths)
							removeLightpath(lightpathId);
					}
				}
				
//				/* Then, if load balancing is allowed, try to allocate over multiple path at the IP layer */
//				LoadBalancing lb = request.getLoadBalancing();
//				if (lb != null)
//				{
//					int allowedBifurcationDegree = lb.getMaxLSP();
//					float minBandwidthPerPathInGbps = lb.getMinBandwidth() * 8f / 1E9f;
//					
//					graph = JUNGUtils.filterGraph(graph, null, spareCapacityTransformer, minBandwidthPerPathInGbps);
//					CandidatePathList cpl = new CandidatePathList(netPlan, ipLayerId, "computePaths", "false");
//					List<List<Long>> paths = JUNGUtils.getKLooplessShortestPaths(graph, null, ingressNodeId, egressNodeId, 10);
//					if (!paths.isEmpty())
//						for(List<Long> seqLinks1 : paths)
//							cpl.addPath(demandId, seqLinks1);
//					
//					int P = cpl.getNumberOfPaths();
//					if (P > 0)
//					{
//						/* Create the optimization problem object (JOM library) */
//						OptimizationProblem op = new OptimizationProblem();
//
//						/* Add the decision variables to the problem */
//						op.addDecisionVariable("x_p", false, new int[] { 1, P }, 0, 1); /* the amount of traffic of demand d(p) carried by path p */
//						op.addDecisionVariable("xx_p", true, new int[] { 1, P }, 0, 1); /* 1 if the path p carries traffic, 0 otherwise */
//						op.addDecisionVariable("rho", false, new int[] { 1, 1 }, 0, 1); /* amount of traffic in link e */
//
//						/* Set some input parameters */
//						op.setInputParameter("u_e", netPlan.getLinkSpareCapacityVector(ipLayerId), "row");
//						op.setInputParameter("tMin", minBandwidthPerPathInGbps);
//						op.setInputParameter("BIFMAX", allowedBifurcationDegree);
//						op.setInputParameter("h_d", bandwidthInGbps);
//
//						/* Sets the objective function */
//						op.setObjectiveFunction("minimize", "rho");
//
//						/* VECTORIAL FORM OF THE CONSTRAINTS  */
//						op.setInputParameter("A_ep", cpl.computeLink2PathAssignmentMatrix());
//						op.addConstraint("sum(x_p) == 1"); /* for each demand, the 100% of the traffic is carried (summing the associated paths) */
//						op.addConstraint("A_ep * (h_d * x_p)' <= u_e' * rho"); /* for each link, its utilization is below or equal to rho */
//						op.addConstraint("xx_p >= x_p"); /* if a path carries traffic => xx_p = 1 */
//						op.addConstraint("x_p >= tMin * xx_p"); /* if a path does not carry traffic, then xx_p = 0. If it carriers, then x_p >= tMin */
//						op.addConstraint("sum(xx_p) <= BIFMAX"); /* the number of paths carrying traffic of a demand is limited by BIFMAX */
//
//						/* Call the solver to solve the problem */
//						String solverName = "cplex";
//						String solverLibraryName = "C:\\Program Files\\IBM\\ILOG\\CPLEX_Studio1261\\cplex\\bin\\x64_win64\\cplex1261.dll";
//						op.solve(solverName, "solverLibraryName", solverLibraryName);
//
//						/* If an optimal solution was not found, quit */
//						if (op.solutionIsOptimal())
//						{
//							/* Retrieve the optimum solutions */
//							double[] x_p = op.getPrimalSolution("x_p").to1DArray();
//
//							/* Update netPlan object adding the calculated routes */
//							for (int pathId = 0; pathId < P; pathId++)
//							{
//								if (x_p[pathId] > 1e-3)
//								{
//									Pair<Path, Bandwidth> aux = generateSingleLayerPath(netPlan, ipLayerId, cpl.getPathSequenceOfLinks(pathId), (float) x_p[pathId]);
//									Path path = aux.getFirst();
//									Bandwidth bw1 = aux.getSecond();
//
//									Response response = new Response();
//									response.addPath(path);
//									response.setBandwidth(bw1);
//									response.setRequestParameters(request.getRequestParameters());
//									responseList.add(response);
//								}
//							}
//
//							allocatedConnection = true;
//						}
//					}
//				}
//				
//				if (allocatedConnection) continue;
//				
//				long ingressNodeId_roadm = netPlan.getNodeOutNeighbors(ipLayerId, ingressNodeId).iterator().next();
//				long egressNodeId_roadm = netPlan.getNodeInNeighbors(ipLayerId, egressNodeId).iterator().next();
//				
//				Set<Long> addedLightpaths = new LinkedHashSet<Long>();
//				
//				/* Then, try to route over a new end-to-end lightpath */
//				long lightpathId = addLightpath(netPlan, ingressNodeId_roadm, egressNodeId_roadm);
//				if (lightpathId != -1)
//				{
//					addedLightpaths.add(lightpathId);
//					
//					System.out.println("lightpathId " + lightpathId);
//					
//					long originLinkId = netPlan.getNodePairLinks(ipLayerId, ingressNodeId, ingressNodeId_roadm).iterator().next();
//					long destinationLinkId = netPlan.getNodePairLinks(ipLayerId, egressNodeId_roadm, egressNodeId).iterator().next();
//					graph.addEdge(lightpathId, ingressNodeId_roadm, egressNodeId_roadm);
//					spareCapacityTransformer = JUNGUtils.getEdgeWeightTransformer(netPlan.getLinkSpareCapacityMap(ipLayerId));
//					
//					if (bandwidthInGbps <= lightpathBinaryRate_Gbps)
//					{
//						Pair<Path, Bandwidth> aux = generateMultiLayerPath(netPlan, originLinkId, lightpathId, destinationLinkId, bandwidthInGbps);
//						Path path = aux.getFirst();
//						Bandwidth bw1 = aux.getSecond();
//
//						Response response = new Response();
//						response.addPath(path);
//						response.setBandwidth(bw1);
//						response.setRequestParameters(request.getRequestParameters());
//						responseList.add(response);
//						
//						allocatedConnection = true;
//					}
//					else if (lb != null)
//					{
//						int allowedBifurcationDegree = lb.getMaxLSP();
//						float minBandwidthPerPathInGbps = lb.getMinBandwidth() * 8f / 1E9f;
//						
//						int lp = 1;
//						while(true)
//						{
//							CandidatePathList cpl = new CandidatePathList(netPlan, ipLayerId, "computePaths", "false");
//							
//							graph = JUNGUtils.filterGraph(graph, null, spareCapacityTransformer, minBandwidthPerPathInGbps);
//							List<List<Long>> paths = JUNGUtils.getKLooplessShortestPaths(graph, null, ingressNodeId, egressNodeId, 10);
//							if (!paths.isEmpty())
//								for(List<Long> seqLinks1 : paths)
//									cpl.addPath(demandId, seqLinks1);
//							
//							System.out.println(paths);
//
//							int P = cpl.getNumberOfPaths();
//
//							/* Create the optimization problem object (JOM library) */
//							OptimizationProblem op = new OptimizationProblem();
//
//							/* Add the decision variables to the problem */
//							op.addDecisionVariable("x_p", false, new int[] { 1, P }, 0, 1); /* the amount of traffic of demand d(p) carried by path p */
//							op.addDecisionVariable("xx_p", true, new int[] { 1, P }, 0, 1); /* 1 if the path p carries traffic, 0 otherwise */
//							op.addDecisionVariable("rho", false, new int[] { 1, 1 }, 0, 1); /* amount of traffic in link e */
//
//							/* Set some input parameters */
//							op.setInputParameter("u_e", netPlan.getLinkSpareCapacityVector(ipLayerId), "row");
//							op.setInputParameter("h_d", bandwidthInGbps);
//							op.setInputParameter("tMin", minBandwidthPerPathInGbps);
//							op.setInputParameter("BIFMAX", allowedBifurcationDegree);
//
//							/* Sets the objective function */
//							op.setObjectiveFunction("minimize", "rho");
//
//							/* VECTORIAL FORM OF THE CONSTRAINTS  */
//							op.setInputParameter("A_ep", cpl.computeLink2PathAssignmentMatrix());
//							op.addConstraint("sum(x_p) == 1", "carryAllTraffic"); /* for each demand, the 100% of the traffic is carried (summing the associated paths) */
//							op.addConstraint("A_ep * (h_d * x_p)' <= u_e' * rho", "capacity"); /* for each link, its utilization is below or equal to rho */
//							op.addConstraint("xx_p >= x_p", "usedPath"); /* if a path carries traffic => xx_p = 1 */
//							op.addConstraint("h_d * x_p >= tMin * xx_p", "minTrafficPerPath"); /* if a path does not carry traffic, then xx_p = 0. If it carriers, then x_p >= tMin */
//							op.addConstraint("sum(xx_p) <= BIFMAX", "maxBifurcation"); /* the number of paths carrying traffic of a demand is limited by BIFMAX */
//							
//							/* Call the solver to solve the problem */
//							String solverName = "cplex";
//							String solverLibraryName = "C:\\Program Files\\IBM\\ILOG\\CPLEX_Studio1261\\cplex\\bin\\x64_win64\\cplex1261.dll";
//							try
//							{
//								op.solve(solverName, "solverLibraryName", solverLibraryName);
//								
//								/* If an optimal solution was not found, quit */
//								if (op.solutionIsOptimal())
//								{
//									/* Retrieve the optimum solutions */
//									double[] x_p = op.getPrimalSolution("x_p").to1DArray();
//
//									/* Update netPlan object adding the calculated routes */
//									for (int pathId = 0; pathId < P; pathId++)
//									{
//										if (x_p[pathId] > 1e-3)
//										{
//											Pair<Path, Bandwidth> aux = generateSingleLayerPath(netPlan, ipLayerId, cpl.getPathSequenceOfLinks(pathId), (float) x_p[pathId]);
//											Path path = aux.getFirst();
//											Bandwidth bw1 = aux.getSecond();
//
//											Response response = new Response();
//											response.addPath(path);
//											response.setBandwidth(bw1);
//											response.setRequestParameters(request.getRequestParameters());
//											responseList.add(response);
//										}
//									}
//
//									allocatedConnection = true;
//									break;
//								}
//							}
//							catch(Throwable e)
//							{
//								System.out.println(e.getMessage());
//								System.out.println(op);
//							}
//							
//							if (lp == allowedBifurcationDegree) break;
//							
//							long lightpathId1 = addLightpath(netPlan, ingressNodeId_roadm, egressNodeId_roadm);
//							if (lightpathId1 == -1) break;
//							
//							addedLightpaths.add(lightpathId1);
//							graph.addEdge(lightpathId1, ingressNodeId_roadm, egressNodeId_roadm);
//							spareCapacityTransformer = JUNGUtils.getEdgeWeightTransformer(netPlan.getLinkSpareCapacityMap(ipLayerId));
//							
//							lp++;
//						}
//					}
//				}
//				
//				if (allocatedConnection) continue;
//				
//				System.out.println("addedLightpaths " + addedLightpaths);
//				
//				for(long lightpathId1 : addedLightpaths)
//					removeLightpath(lightpathId1);
				
				if (allocatedConnection) continue;
				
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
		}
		catch(Throwable e)
		{
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}
	
	private void removeLightpath(long lightpathId)
	{
		long lpRouteId = netPlan.getDemandRoutes(wdmLayerId, netPlan.getLinkCoupledLowerLayerDemand(ipLayerId, lightpathId).getSecond()).iterator().next();
		List<Long> seqFibers = netPlan.getRouteSequenceOfLinks(wdmLayerId, lpRouteId);
		int[] seqWavelengths = WDMUtils.getLightpathSeqWavelengths(netPlan, wdmLayerId, lpRouteId);
		
		WDMUtils.releaseResources(seqFibers, seqWavelengths, wavelengthFiberOccupancy);
		netPlan.removeLink(ipLayerId, lightpathId);
	}

	private void updateFiberTopology()
	{
		Set<Long> affectedNodes = netPlan.getNodesDown();
		Set<Long> affectedFibers = netPlan.getLinksDown(wdmLayerId);
		fiberTopology_currentState = GraphUtils.JUNGUtils.filterGraph(fiberTopology, null, affectedNodes, null, affectedFibers);
	}
	
	private static class WavelengthOccupancyTransformer implements Transformer<Long, Double>
	{
		private final Transformer<Long, Double> nev;
		private final Set<Long> alreadyOccupiedFibers;
		public WavelengthOccupancyTransformer(Transformer<Long, Double> nev, Set<Long> alreadyOccupiedFibers)
		{
			if (nev == null) this.nev = JUNGUtils.getEdgeWeightTransformer(null);
			else this.nev = nev;
			
			this.alreadyOccupiedFibers = alreadyOccupiedFibers;
		}
		
		@Override
		public Double transform(Long fiberId)
		{
			return alreadyOccupiedFibers.contains(fiberId) ? Double.MAX_VALUE : nev.transform(fiberId);
		}
	}
}
