/* ESTE ES EL USADO PARA EL ECOC */

package es.upct.girtel.net2plan.plugins.abnopce.pce;

import com.net2plan.interfaces.networkDesign.NetPlan;
import com.net2plan.interfaces.simulation.IEventProcessor;
import com.net2plan.interfaces.simulation.SimAction;
import com.net2plan.interfaces.simulation.SimEvent;
import com.net2plan.libraries.GraphUtils;
import com.net2plan.libraries.GraphUtils.JUNGUtils;
import com.net2plan.libraries.WDMUtils;
import com.net2plan.utils.Triple;
import edu.uci.ics.jung.algorithms.shortestpath.DijkstraShortestPath;
import edu.uci.ics.jung.graph.Graph;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.collections15.Transformer;

/**
 *
 * @author Jose Luis
 */
public class BasicAlgorithm extends IEventProcessor
{
	private long wdmLayerId, ipLayerId;
	private int W;
	private double lightpathBinaryRate_Gbps;
	private List<Set<Long>> wavelengthFiberOccupancy;
	private Graph<Long, Long> fiberTopology, fiberTopology_currentState;

	@Override
	public String getDescription()
	{
		return null;
	}

	@Override
	public List<Triple<String, String, String>> getParameters()
	{
		return null;
	}

	@Override
	public void initialize(NetPlan initialNetPlan, NetPlan currentNetPlan, Map<String, String> algorithmParameters, Map<String, String> simulationParameters, Map<String, String> net2planParameters)
	{
		lightpathBinaryRate_Gbps = 100;
		W = 40;
		wdmLayerId = 0;
		ipLayerId = 1;
		
		wavelengthFiberOccupancy = new ArrayList<Set<Long>>(W);
		for(int wavelengthId = 0; wavelengthId < W; wavelengthId++)
			wavelengthFiberOccupancy.add(new HashSet<Long>());
	}

	@Override
	public void processEvent(NetPlan currentNetPlan, SimEvent event)
	{
		double simTime = event.getEventTime();
		
		List<SimAction> actionList = event.getEventActionList();
		for(SimAction action : actionList)
		{
			switch(action.getActionType())
			{
				case NODE_DOWN:
				case NODE_UP:
				case LINK_DOWN:
				case LINK_UP:
					handleFailureReparationEvent(currentNetPlan, action, simTime);
					break;
					
				case DEMAND_ADDED:
					handleDemandAdded(currentNetPlan, action, simTime);
					break;
					
				case ROUTE_REMOVED:
					handleRouteRemoved(currentNetPlan, action, simTime);
					break;

				default:
					break;
			}
		}
	}
	
	private long addLightpath(NetPlan netPlan, long originNodeId, long destinationNodeId)
	{
		if (!fiberTopology_currentState.containsVertex(originNodeId) || !fiberTopology_currentState.containsVertex(destinationNodeId))
			return -1;
		
		for(int wavelengthId = 0; wavelengthId < W; wavelengthId++)
		{
			Set<Long> fibersToOmit_thisWavelength = new HashSet<Long>(wavelengthFiberOccupancy.get(wavelengthId));
			Transformer<Long, Double> fiberWeightTransformer_thisWavelength = new WavelengthOccupancyTransformer(null, fibersToOmit_thisWavelength);
			DijkstraShortestPath<Long, Long> dsp = new DijkstraShortestPath<Long, Long>(fiberTopology_currentState, fiberWeightTransformer_thisWavelength);

			Number pathCost = dsp.getDistance(originNodeId, destinationNodeId);
			if (pathCost.doubleValue() == Double.MAX_VALUE) continue;

			List<Long> seqFibers = dsp.getPath(originNodeId, destinationNodeId);
			long lpDemandId = netPlan.addDemand(wdmLayerId, originNodeId, destinationNodeId, lightpathBinaryRate_Gbps, null);
			long lpRouteId = netPlan.addRoute(wdmLayerId, lpDemandId, lightpathBinaryRate_Gbps, 1, seqFibers, null);
			WDMUtils.allocateResources(seqFibers, wavelengthId, wavelengthFiberOccupancy);
			WDMUtils.setLightpathSeqWavelengths(netPlan, wdmLayerId, lpRouteId, wavelengthId);

			long lightpathId = netPlan.createUpperLayerLinkFromDemand(wdmLayerId, lpDemandId, ipLayerId);
			return lightpathId;
		}
		
		return -1;
	}

	private void handleDemandAdded(NetPlan netPlan, SimAction action, double simTime)
	{
		long layerId = action.getDemandAdded_layerId();
		
		if (layerId == ipLayerId)
		{
			long demandId = action.getDemandAdded_demandId();
			long ingressNodeId = netPlan.getDemandIngressNode(ipLayerId, demandId);
			long egressNodeId = netPlan.getDemandEgressNode(ipLayerId, demandId);
			double h_d = netPlan.getDemandOfferedTraffic(ipLayerId, demandId);
			
			/* First, try to allocate over an existing lightpath */
			
			List<Long> seqLightpaths = GraphUtils.getCapacitatedShortestPath(netPlan.getLinkMap(ipLayerId), ingressNodeId, egressNodeId, null, netPlan.getLinkSpareCapacityMap(ipLayerId), h_d);
			
			/* If fails, try to use a load
			
			/* If fails, try to allocate a new point-to-point lightpath */
			if (seqLightpaths.isEmpty())
			{
				long lightpathId = addLightpath(netPlan, ingressNodeId, egressNodeId);
				if (lightpathId != -1) seqLightpaths.add(lightpathId);
			}

			if (seqLightpaths.isEmpty()) /* If no route is found, then block the request */
			{
				netPlan.removeDemand(ipLayerId, demandId);
			}
			else /* Otherwise, allocate the request */
			{
				netPlan.addRoute(ipLayerId, demandId, h_d, seqLightpaths, null);
			}
		}
	}
	
	private void handleFailureReparationEvent(NetPlan netPlan, SimAction action, double simTime)
	{
		switch(action.getActionType())
		{
			case LINK_UP:
				long linkUp_layerId = action.getLinkUp_layerId();
				if (linkUp_layerId != wdmLayerId) return;
				
			case LINK_DOWN:
				long linkDown_layerId = action.getLinkDown_layerId();
				if (linkDown_layerId != wdmLayerId) return;
						
			case NODE_DOWN:
			case NODE_UP:
				break;
				
			default:
				throw new RuntimeException("Bad");
		}
		
		Set<Long> affectedNodes = netPlan.getNodesDown();
		Set<Long> affectedFibers = netPlan.getLinksDown(wdmLayerId);
		fiberTopology_currentState = GraphUtils.JUNGUtils.filterGraph(fiberTopology, null, affectedNodes, null, affectedFibers);
	}
	
	private void handleRouteRemoved(NetPlan netPlan, SimAction action, double simTime)
	{
		long layerId = action.getRouteRemoved_layerId();
		
		if (layerId == ipLayerId)
		{
			/* Tear-down unused lightpaths (and release wavelength resources) */
			List<Long> seqLightpaths = action.getRouteRemoved_sequenceOfLinks();
			for(long lightpathId : seqLightpaths)
			{
				if (netPlan.getLinkAssociatedRoutes(layerId, lightpathId).isEmpty())
				{
					long lpDemandId = netPlan.getLinkCoupledLowerLayerDemand(ipLayerId, lightpathId).getSecond();
					Set<Long> lpRouteIds = netPlan.getDemandRoutes(wdmLayerId, lpDemandId);
					for(long lpRouteId : lpRouteIds)
					{
						List<Long> seqFibers = netPlan.getRouteSequenceOfLinks(wdmLayerId, lpRouteId);
						int[] seqWavelengths = WDMUtils.getLightpathSeqWavelengths(netPlan, wdmLayerId, lpRouteId);
						WDMUtils.releaseResources(seqFibers, seqWavelengths, wavelengthFiberOccupancy);
					}
					
					netPlan.removeLink(layerId, lightpathId);
				}
			}
		}
		else if (layerId == wdmLayerId)
		{
			/* Release wavelength resources */
			Map<String, String> attributeMap = action.getRouteRemoved_attributes();
			List<Long> seqFibers = action.getRouteRemoved_sequenceOfLinks();
			int[] seqWavelengths = WDMUtils.parseSeqWavelengths(attributeMap);
			WDMUtils.releaseResources(seqFibers, seqWavelengths, wavelengthFiberOccupancy);
		}
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
