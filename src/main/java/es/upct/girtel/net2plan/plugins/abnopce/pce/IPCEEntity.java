package es.upct.girtel.net2plan.plugins.abnopce.pce;

import com.net2plan.interfaces.networkDesign.NetPlan;
import es.tid.bgp.bgp4.messages.BGP4Message;
import es.tid.pce.pcep.messages.PCEPMessage;
import java.util.List;

import es.upct.girtel.net2plan.plugins.abnopce.pce.pcep.PCEPHandler;
import es.upct.girtel.net2plan.plugins.abnopce.pce.bgp.BGPHandler;

/**
 *
 * @author Jose Luis
 */
public abstract class IPCEEntity
{
	protected NetPlan netPlan;
	protected boolean isBGPRegistered, isPCEPRegistered;
	protected BGPHandler  bgpHandler;
	protected PCEPHandler pcepHandler;

	public IPCEEntity()
	{
		netPlan = new NetPlan();
		isBGPRegistered = false;
		isPCEPRegistered = false;
	}

	public boolean isValid()
	{
		return isBGPRegistered && isPCEPRegistered;
	}
	
	public BGPHandler getBGPHandler() { return bgpHandler; }
	
	public NetPlan getNetworkState() { return netPlan.unmodifiableView(); }

	public PCEPHandler getPCEPHandler() { return pcepHandler; }
	
	public abstract List handleBGPMessage(BGP4Message message);

	public abstract List handlePCEPMessage(PCEPMessage message);
}
