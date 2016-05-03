/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package es.upct.girtel.net2plan.plugins.activepce.utils;

import com.net2plan.interfaces.networkDesign.IAlgorithm;
import com.net2plan.interfaces.networkDesign.NetPlan;
import com.net2plan.libraries.WDMUtils;
import com.net2plan.utils.IntUtils;
import com.net2plan.utils.Triple;
import java.awt.geom.Point2D;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 * @author Jose Luis
 */
public class CreateMLNetwork implements IAlgorithm
{
	public static void main(String[] args)
	{
//		NetPlan netPlan = new NetPlan(new File("C:\\Users\\Jose Luis\\Documents\\NSFNet_N14_E42_multilayerPCE.n2p"));
//		IAlgorithm algorithm = new CreateMLNetwork();
//		
//		algorithm.executeAlgorithm(netPlan, null, null);
//		netPlan.saveToFile(new File("C:\\Users\\Jose Luis\\Documents\\NSFNet_N14_E42_multilayerPCE.n2p"));

//		NetPlan netPlan = new NetPlan(new File("C:\\Net2Plan\\releases\\Net2Plan-0.3.0\\workspace\\data\\networkTopologies\\NSFNet_N14_E42.n2p"));
//		IAlgorithm algorithm = new CreateMLNetwork();
//		Map<String, String> parameters = new HashMap<String, String>();
//		parameters.put("numWavelengthsPerFiber", "40");
//		
//		algorithm.executeAlgorithm(netPlan, parameters, null);
//		netPlan.saveToFile(new File("C:\\Users\\Jose Luis\\Documents\\NSFNet_N14_E42_multilayerPCE.n2p"));
//		netPlan.saveToFile(new File("C:\\Net2Plan\\releases\\Net2Plan-0.3.0\\workspace\\data\\networkTopologies\\NSFNet_N14_E42_multilayerPCE.n2p"));
	}
	
	@Override
	public String executeAlgorithm(NetPlan netPlan, Map<String, String> algorithmParameters, Map<String, String> net2planParameters)
	{
		int W = Integer.parseInt(algorithmParameters.get("numWavelengthsPerFiber"));
		WDMUtils.setFibersNumWavelengths(netPlan, W);
		WDMUtils.checkConsistency(netPlan);
		
		Set<Long> nodeIds = new LinkedHashSet<Long>(netPlan.getNodeIds());
		for(long nodeId : nodeIds)
		{
			netPlan.setNodeAttribute(nodeId, "type", "roadm");
			netPlan.setNodeAttribute(nodeId, "ipAddress", "192.168.201." + (nodeId + 1));
			Point2D xyPosition = netPlan.getNodeXYPosition(nodeId);
			String name = netPlan.getNodeName(nodeId);
			
			long newNodeId = netPlan.addNode(xyPosition.getX(), xyPosition.getY(), name, null);
			netPlan.setNodeAttribute(newNodeId, "type", "ipRouter");
			netPlan.setNodeAttribute(newNodeId, "ipAddress", "192.168.101." + (nodeId + 1));
			
			netPlan.addLinkBidirectional(nodeId, newNodeId, Integer.MAX_VALUE, 0, null); /* Assume infinite transponders */
		}
		
		Map<Long, Integer> nextIfId = IntUtils.constantMap(netPlan.getNodeIds(), 1);
		for(long nodeId_1 : netPlan.getNodeIds())
		{
			if (netPlan.getNodeAttribute(nodeId_1, "type").equals("ipRouter")) continue;
			
			for(long nodeId_2 : netPlan.getNodeIds())
			{
				if (nodeId_2 <= nodeId_1) continue;
				if (netPlan.getNodeAttribute(nodeId_2, "type").equals("ipRouter")) continue;
				if (netPlan.getNodePairLinks(nodeId_1, nodeId_2).isEmpty()) continue;
				
				long link1to2 = netPlan.getNodePairLinks(nodeId_1, nodeId_2).iterator().next();
				long link2to1 = netPlan.getNodePairLinks(nodeId_2, nodeId_1).iterator().next();
				
				netPlan.setLinkAttribute(link1to2, "srcIf", Integer.toString(nextIfId.get(nodeId_1)));
				netPlan.setLinkAttribute(link1to2, "dstIf", Integer.toString(nextIfId.get(nodeId_2)));

				netPlan.setLinkAttribute(link2to1, "srcIf", Integer.toString(nextIfId.get(nodeId_2)));
				netPlan.setLinkAttribute(link2to1, "dstIf", Integer.toString(nextIfId.get(nodeId_1)));
				
				nextIfId.put(nodeId_1, nextIfId.get(nodeId_1) + 1);
				nextIfId.put(nodeId_2, nextIfId.get(nodeId_2) + 1);
			}
		}
		
		netPlan.setLayerName(netPlan.getLayerDefaultId(), "WDM");
		netPlan.setLinkCapacityUnitsName("wavelengths");
		netPlan.setDemandTrafficUnitsName("Gbps");
		
		netPlan.addLayer("IP", null, "Gbps", "Gbps", null);	
		
		return "Ok";
	}

	@Override
	public String getDescription()
	{
		return "";
	}

	@Override
	public List<Triple<String, String, String>> getParameters()
	{
		List<Triple<String, String, String>> algorithmParameters = new LinkedList<Triple<String, String, String>>();
		algorithmParameters.add(Triple.of("numWavelengthsPerFiber", "40", "Number of wavelengths per link"));
		
		return algorithmParameters;
	}
	
}
