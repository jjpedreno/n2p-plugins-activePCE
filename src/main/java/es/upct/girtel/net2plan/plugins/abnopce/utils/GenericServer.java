/*
 * Copyright (c) $year Jose-Juan Pedreno-Manresa Jose-Luis Izquierdo-Zaragoza Pablo Pavon-Marino, .
 *   All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the GNU Lesser Public License v3
 *  which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 *  Contributors:
 */

package es.upct.girtel.net2plan.plugins.abnopce.utils;

import com.net2plan.interfaces.networkDesign.Net2PlanException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Created by Kuranes on 31/03/2015.
 */
public abstract class GenericServer implements Runnable
{
	private int _listenPort;
	private ServerSocket _serverSocket;
	protected Socket _socket;
	private boolean _keepAlive;

	public GenericServer(int port)
	{
		_listenPort = port;
		
		try { _serverSocket = new ServerSocket(_listenPort); }
		catch (IOException e) { throw new Net2PlanException("Unable to create socket at port " + _listenPort + ": " + e.getMessage()); }
	}

	@Override
	public void finalize()
	{
		shutdown();
	}

	@Override
	public void run()
	{
		while (_keepAlive)
		{
			try
			{
				_socket = _serverSocket.accept();
				handleClient(_socket);

			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
		}
	}

	public void acceptConnections()
	{
		if (!_keepAlive)
		{
			_keepAlive= true;
			new Thread(this).start();
		}
	}

	public void shutdown()
	{
		_keepAlive = false;
		try
		{
			if (_serverSocket != null) _serverSocket.close();
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}

	protected abstract void handleClient(Socket socket) throws IOException;
}
