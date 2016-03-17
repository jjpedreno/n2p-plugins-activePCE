/*
 * Copyright (c) $year Jose-Juan Pedreno-Manresa Jose-Luis Izquierdo-Zaragoza Pablo Pavon-Marino, .
 *   All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the GNU Lesser Public License v3
 *  which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 *  Contributors:
 */

package es.upct.girtel.net2plan.plugins.abnopce.pce.pcep;

import es.upct.girtel.net2plan.plugins.abnopce.utils.Constants;
import es.upct.girtel.net2plan.plugins.abnopce.utils.GenericServer;

import java.io.IOException;
import java.net.Socket;

/**
 * Created by Kuranes on 31/03/2015.
 */
public class PCEPServer extends GenericServer
{
	public static Socket socket;
	public PCEPServer()
	{
		super(Constants.PCEP_SERVER_PORT);
	}

	@Override
	protected void handleClient(Socket socket) throws IOException
	{
		this.socket = socket;
		new PCEPHandler(_socket).doService();
	}
}
