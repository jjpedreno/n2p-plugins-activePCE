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

package es.upct.girtel.net2plan.plugins.activepce.pce.pcep;

import com.net2plan.internal.ErrorHandling;
import es.tid.bgp.bgp4.messages.BGP4Message;
import es.tid.pce.pcep.messages.*;
import es.upct.girtel.net2plan.plugins.activepce.pce.PCEMasterController;
import es.upct.girtel.net2plan.plugins.activepce.utils.Constants;
import es.upct.girtel.net2plan.plugins.activepce.utils.RequestHandler;
import es.upct.girtel.net2plan.plugins.activepce.utils.Utils;

import java.io.IOException;
import java.net.Socket;
import java.util.List;

public class PCEPHandler extends RequestHandler
{
	private boolean _keepAlive;

	PCEPHandler(Socket socket) throws IOException
	{
		super(socket);
		_keepAlive = true;
		_ipAddress = _socket.getInetAddress();
	}

	@Override
	public void doService()
	{
		/* Receive first message and check if it's PCEPOpen */
		try
		{
			byte[] receivedBytes = Utils.readPCEPMsg(_input);

			int messageType = PCEPMessage.getMessageType(receivedBytes);
			if(messageType != PCEPMessageTypes.MESSAGE_OPEN)
			{
				_socket.close();
				System.out.println("<< PCEP Wrong first message, quitting...");
				return;
			}

			/* Registering with Master Controller */
			PCEMasterController.getInstance().registerPCEP(this, _ipAddress);

			System.out.println("<< PCEP OPEN First message received");

			/* Returning open message */
			PCEPOpen response = new PCEPOpen(); //TODO negotiate values
			response.encode();
			_output.write(response.getBytes());
			System.out.println(">> PCEP OPEN Sending message to " + _ipAddress.getHostAddress());

			receivedBytes = Utils.readPCEPMsg(_input);
			messageType = PCEPMessage.getMessageType(receivedBytes);
			if(messageType != PCEPMessageTypes.MESSAGE_KEEPALIVE)
			{
				_socket.close();
				System.out.println("<< PCEP KEEPALIVE Wrong first message, quitting...");
				return;
			}

			System.out.println("<< PCEP KEEPALIVE First message received");

			PCEPKeepalive keepaliveMsg = new PCEPKeepalive();
			keepaliveMsg.encode();
			Utils.writeMessage(_output, keepaliveMsg.getBytes());
			System.out.println(">> PCEP KEEPALIVE Sending message to " + _ipAddress.getHostAddress());

		}catch(Exception e)
		{
			if(Constants.DEBUG) ErrorHandling.addErrorOrException(e, PCEPHandler.class);
		}

		/* Infinite loop now */
		boolean keepAlive = true;
		while(keepAlive)
		{
			/* Receive message and check its type */
			try
			{
				byte[] receivedBytes = Utils.readPCEPMsg(_input);
				if(receivedBytes == null) shutdown();

				int messageType = PCEPMessage.getMessageType(receivedBytes);
				switch(messageType)
				{
					case PCEPMessageTypes.MESSAGE_CLOSE: //If CLOSE type, close the socket and exit.
						_socket.close();
						keepAlive = false;
						System.out.println(">> PCEP CLOSE. CLosing connection with " + _ipAddress.getHostAddress());
						break;

					case PCEPMessageTypes.MESSAGE_PCREQ:
						PCEPRequest message = new PCEPRequest(receivedBytes);
						message.decode();
						System.out.println("<< PCEP REQ from " + _ipAddress.getHostAddress());

						List outMsg = PCEMasterController.getInstance().getPCEEntity(_ipAddress).handlePCEPMessage(message);
						for(Object msg : outMsg)
							if(msg instanceof PCEPMessage)
								Utils.writeMessage(_output, ((PCEPMessage) msg).getBytes());
							else if(msg instanceof BGP4Message)
								Utils.writeMessage((PCEMasterController.getInstance().getPCEEntity(_ipAddress).getBGPHandler().getOutputStream()), ((BGP4Message) msg).getBytes());
						break;

					default:
						System.out.println("<< Message of type " + messageType + " received! Nothing to do for the moment...");
				}

			}catch(Exception e)
			{
				e.printStackTrace();
				keepAlive = false;
			}
		}//End of infinite while
		System.out.println("Ending PCEPHandler");
	}

	private void shutdown()
	{
		_keepAlive = false;
		try
		{
			_socket.close();
		}catch(IOException e)
		{
			e.printStackTrace();
		}

	}

}
