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

import com.net2plan.interfaces.networkDesign.NetPlan;
import es.tid.bgp.bgp4.messages.BGP4Message;
import es.tid.pce.pcep.messages.PCEPMessage;
import es.upct.girtel.net2plan.plugins.activepce.pce.bgp.BGPHandler;
import es.upct.girtel.net2plan.plugins.activepce.pce.pcep.PCEPHandler;

import java.util.List;

public abstract class IPCEEntity
{
	protected NetPlan _netPlan;
	protected boolean _isBGPRegistered, _isPCEPRegistered;
	protected BGPHandler  _bgpHandler;
	protected PCEPHandler _pcepHandler;

	public IPCEEntity()
	{
		_netPlan = new NetPlan();
		_isBGPRegistered = false;
		_isPCEPRegistered = false;
	}

	public boolean isValid()
	{
		return _isBGPRegistered && _isPCEPRegistered;
	}

	public BGPHandler getBGPHandler(){ return _bgpHandler; }

	public NetPlan getNetworkState(){ return _netPlan.copy(); }

	public PCEPHandler getPCEPHandler(){ return _pcepHandler; }

	public abstract List handleBGPMessage(BGP4Message message);

	public abstract List handlePCEPMessage(PCEPMessage message);
}
