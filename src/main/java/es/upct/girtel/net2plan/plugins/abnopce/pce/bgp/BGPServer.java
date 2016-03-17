package es.upct.girtel.net2plan.plugins.abnopce.pce.bgp;

import es.upct.girtel.net2plan.plugins.abnopce.utils.Constants;
import es.upct.girtel.net2plan.plugins.abnopce.utils.GenericServer;

import java.io.IOException;
import java.net.Socket;

/**
 * Created by Kuranes on 03/04/2015.
 */
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