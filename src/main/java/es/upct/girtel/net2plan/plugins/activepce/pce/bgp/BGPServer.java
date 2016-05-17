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

package es.upct.girtel.net2plan.plugins.activepce.pce.bgp;

import es.upct.girtel.net2plan.plugins.activepce.utils.Constants;
import es.upct.girtel.net2plan.plugins.activepce.utils.GenericServer;

import java.io.IOException;
import java.net.Socket;

public class BGPServer extends GenericServer
{
	public BGPServer()
	{
		super(Constants.BGP_SERVER_PORT);
	}

	@Override
	protected void handleClient(Socket socket) throws IOException
	{
		new BGPHandler(_socket).doService();
	}
}