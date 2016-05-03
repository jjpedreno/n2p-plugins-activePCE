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

import com.net2plan.interfaces.networkDesign.Net2PlanException;
import com.net2plan.internal.plugins.ICLIModule;
import com.net2plan.utils.Triple;
import es.upct.girtel.net2plan.plugins.activepce.pce.bgp.BGPServer;
import es.upct.girtel.net2plan.plugins.activepce.pce.pcep.PCEPServer;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.net.InetAddress;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class PCEMainServer extends ICLIModule
{
	public static void main(String args[]) throws InstantiationException, IllegalAccessException, ParseException
	{
		new PCEMainServer().executeFromCommandLine(new String[0]);
	}

	@Override
	public void executeFromCommandLine(String[] args) throws ParseException
	{
		System.out.println("PCE MAIN SERVER");
		System.out.println("==============================");

		Scanner scanner = new Scanner(System.in);
		
		System.out.println("Starting PCEP & BGP-LS servers...");
		
		PCEPServer serverPCEP;
		BGPServer serverBGP;
		
		try
		{
			serverPCEP = new PCEPServer();
			serverBGP = new BGPServer();
		}
		catch(Net2PlanException e)
		{
			throw(e);
		}
		catch(Throwable e)
		{
			throw new RuntimeException(e);
		}

		/* Create and start PCE Server */
		serverPCEP.acceptConnections();

		/* Create and start BGP server */
		serverBGP.acceptConnections();
		
		System.out.println("PCE successfully started");
		
		while(true)
		{
			String option;
			
			System.out.println("============================");
			System.out.println("|    MULTILAYER AS-PCE     |");
			System.out.println("============================");
			System.out.println("| Options:                 |");
			System.out.println("| 1. View current sessions |");
			System.out.println("| 2. Print TEDB and LSPDB  |");
			System.out.println("| 3. Exit                  |");
			System.out.println("============================");
			System.out.println("");
			System.out.print("Choose an option (1-3): ");
			
			option = scanner.nextLine();

			Set<InetAddress> activeSessions = PCEMasterController.getInstance().getActiveSessions();
			switch(option)
			{
				case "1":
					if (activeSessions.isEmpty()) System.out.println("No active session");
					else System.out.println("Active sessions: " + activeSessions);
					break;
					
				case "2":
					switch(activeSessions.size())
					{
						case 0:
							System.out.println("No active session");
							break;
							
						case 1:
							System.out.println("Network state: " + PCEMasterController.getInstance().getPCEEntity(activeSessions.iterator().next()).getNetworkState());
							break;
							
						default:
							System.out.println(">> Active sessions: " + activeSessions);
							System.out.print(">> Choose a session: ");
							try
							{
								String session = scanner.nextLine();
								IPCEEntity pceEntity = PCEMasterController.getInstance().getPCEEntity(InetAddress.getByName(session));
								System.out.println("Network state: " + pceEntity.getNetworkState());
							}
							catch(Throwable e)
							{
								e.printStackTrace();
							}
							break;
					}
					break;
					
				case "3":
					System.exit(0);
			
				default:
					System.out.println("Wrong option");
			}
		}
	}

	@Override
	public String getCommandLineHelp()
	{
		return null;
	}

	@Override
	public Options getCommandLineOptions()
	{
		return null;
	}

	@Override
	public String getDescription()
	{
		return null;
	}

	@Override
	public String getModeName()
	{
		return "pce";
	}

	@Override
	public String getName()
	{
		return "Active Stateful BGP/LS-enabled Multilayer Path Computation Element";
	}

	@Override
	public List<Triple<String, String, String>> getParameters()
	{
		return null;
	}
}
