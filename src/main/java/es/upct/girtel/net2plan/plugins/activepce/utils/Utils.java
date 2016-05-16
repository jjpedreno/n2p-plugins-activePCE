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

package es.upct.girtel.net2plan.plugins.activepce.utils;

import com.net2plan.interfaces.networkDesign.Link;
import com.net2plan.interfaces.networkDesign.NetPlan;
import com.net2plan.interfaces.networkDesign.NetworkLayer;
import com.net2plan.interfaces.networkDesign.Node;
import es.tid.bgp.bgp4.messages.BGP4Message;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class Utils
{
	private static long reqIDCounter = 0;

	private final static String IP_ADDRESS_ATTRIBUTE_NAME            = "ipAddress";
	private final static String SOURCE_INTERFACE_ATTRIBUTE_NAME      = "srcIf";
	private final static String DESTINATION_INTERFACE_ATTRIBUTE_NAME = "dstIf";

	public static int byteArrayToInt(byte[] array)
	{
		switch(array.length)
		{
			case 1:
				return (int) (array[0] & 0x000000FF);

			case 2:
				return (int) ((array[0] & 0x0000FF00) | (array[1] & 0x000000FF));

			case 3:
				return (int) ((array[0] & 0x00FF0000) | (array[1] & 0x0000FF00) | (array[2] & 0x000000FF));

			case 4:
				return (int) ((array[0] & 0xFF000000) | (array[1] & 0x00FF0000) | (array[2] & 0x0000FF00) | (array[3] & 0x000000FF));

			default:
				throw new RuntimeException("Bad");
		}
	}

	public static byte[] intToByteArray(int value, int numBytes)
	{
		if(numBytes < 1 || numBytes > 4) throw new RuntimeException("Bad");

		byte[] actualByteArray = new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
		byte[] out = new byte[numBytes];
		System.arraycopy(actualByteArray, 4 - numBytes, out, 0, numBytes);

		return out;
	}

	public static long getLinkByDestinationIPAddress(NetPlan netPlan, Inet4Address inet4Address) throws UnknownHostException
	{
		NetworkLayer defaultLayer = netPlan.getNetworkLayerDefault();
		return getLinkByDestinationIPAddress(netPlan, defaultLayer, inet4Address);
	}

	public static long getLinkByDestinationIPAddress(NetPlan netPlan, NetworkLayer layer, Inet4Address inet4Address) throws UnknownHostException
	{
		List<Link> links = netPlan.getLinks(layer);
		for(Link link : links)
			if(getLinkDestinationIPAddress(netPlan, link.getId()).equals(inet4Address))
				return link.getId();

		return - 1;
	}

	public static long getLinkBySourceInterface(NetPlan netPlan, long nodeId, long interfaceId)
	{
		return getLinkBySourceInterface(netPlan, netPlan.getNetworkLayerDefault(), nodeId, interfaceId);
	}

	public static long getLinkBySourceInterface(NetPlan netPlan, NetworkLayer layer, long nodeId, long interfaceId)
	{
		Node node = netPlan.getNodeFromId(nodeId);
		Set<Link> links = node.getOutgoingLinks(layer);
		for(Link link : links)
			if(getLinkSourceInterface(netPlan, link.getId()) == interfaceId)
				return link.getId();
		return - 1;
	}

	public static long getLinkBySourceIPAddress(NetPlan netPlan, Inet4Address inet4Address) throws UnknownHostException
	{
		return getLinkBySourceIPAddress(netPlan, netPlan.getNetworkLayerDefault(), inet4Address);
	}

	public static long getLinkBySourceIPAddress(NetPlan netPlan, NetworkLayer layer, Inet4Address inet4Address) throws UnknownHostException
	{
		List<Link> links = netPlan.getLinks(layer);
		for(Link link : links)
			if(getLinkSourceIPAddress(netPlan, link.getId()).equals(inet4Address))
				return link.getId();
		return - 1;
	}

	public static long getLinkDestinationInterface(NetPlan netPlan, long linkId)
	{
		Link link = netPlan.getLinkFromId(linkId);
		return Long.parseLong(link.getAttribute(DESTINATION_INTERFACE_ATTRIBUTE_NAME));
	}

	public static Inet4Address getLinkDestinationIPAddress(NetPlan netPlan, long linkId) throws UnknownHostException
	{
		Link link = netPlan.getLinkFromId(linkId);
		return getNodeIPAddress(netPlan, link.getDestinationNode().getId());
	}

	public static long getLinkSourceInterface(NetPlan netPlan, long linkId)
	{
		Link link = netPlan.getLinkFromId(linkId);
		return Long.parseLong(link.getAttribute(SOURCE_INTERFACE_ATTRIBUTE_NAME));
	}

	public static Set<Link> getLinksBySourceDestinationIPAddresses(NetPlan netPlan, NetworkLayer layer, Inet4Address sourceIP, Inet4Address destinationIP)
	{
		Set<Link> nodesSet = new LinkedHashSet<>();
		List<Link> links = netPlan.getLinks(layer);
		for(Link link : links)
		{
			Node originNode = link.getOriginNode();
			Node destinationNode = link.getDestinationNode();
			if(originNode.getAttribute(Constants.ATTRIBUTE_IP_ADDRESS).equals(sourceIP.getHostAddress()) && destinationNode.getAttribute(Constants.ATTRIBUTE_IP_ADDRESS).equals(destinationIP
					.getHostAddress()))
				nodesSet.add(link);
		}
		return nodesSet;

	}

	public static Inet4Address getLinkSourceIPAddress(NetPlan netPlan, long linkId) throws UnknownHostException
	{
		Link link = netPlan.getLinkFromId(linkId);
		return getNodeIPAddress(netPlan, link.getOriginNode().getId());
	}

	public static long getNodeByIPAddress(NetPlan netPlan, Inet4Address inet4Address)
	{
		try
		{
			List<Node> nodes = netPlan.getNodes();
			for(Node node : nodes)
				if(getNodeIPAddress(netPlan, node.getId()).equals(inet4Address))
					return node.getId();

			return - 1;
		}catch(Throwable e){ return - 1;}
	}

	public static Inet4Address getNodeIPAddress(NetPlan netPlan, long nodeId)
	{
		try
		{
			Node node = netPlan.getNodeFromId(nodeId);
			Inet4Address r = (Inet4Address) Inet4Address.getByName(node.getAttribute(IP_ADDRESS_ATTRIBUTE_NAME));
			return r;
		}catch(Throwable e){return null;}

	}

	/**
	 * Read a BGP-LS message from the given input stream. Due to the way TCP sends packets,
	 * we may have a complete message, multiple messages, a partial message, etc.
	 * Hence, read method code needs to identify when a message has begun and keep
	 * reading until it has found the end of a message.
	 *
	 * @param in Input stream
	 * @return Message
	 * @since 1.0
	 */
	public static byte[] readBGP4Msg(InputStream in)
	{
		byte[] ret = null;
		byte[] hdr = new byte[BGP4Message.getBGPHeaderLength()];
		byte[] temp = null;
		boolean endHdr = false;
		int r = 0;
		int length = 0;
		boolean endMsg = false;
		int offset = 0;

		while(! endMsg)
		{
			try{ r = in.read(endHdr ? temp : hdr, offset, 1); }catch(IOException e){ throw new RuntimeException("Error reading BGP data: " + e.getMessage()); }catch(Exception e)
			{
				throw new RuntimeException(e);
			}

			if(r > 0)
			{
				if(offset == BGP4Message.getBGPMarkerLength()) length = ((int) hdr[offset] & 0xFF) << 8;

				if(offset == BGP4Message.getBGPMarkerLength() + 1)
				{
					length = length | (((int) hdr[offset] & 0xFF));
					temp = new byte[length];
					endHdr = true;
					System.arraycopy(hdr, 0, temp, 0, BGP4Message.getBGPHeaderLength());
				}

				if((length > 0) && (offset == length - 1)) endMsg = true;

				offset++;
			} else if(r == - 1)
			{
				throw new RuntimeException("End of stream has been reached");
			}
		}

		if(length > 0)
		{
			ret = new byte[length];
			System.arraycopy(temp, 0, ret, 0, length);
		}

		return ret;
	}

	/**
	 * Read a PCEP message from the given input stream. Due to the way TCP sends packets,
	 * we may have a complete message, multiple messages, a partial message, etc.
	 * Hence, read method code needs to identify when a message has begun and keep
	 * reading until it has found the end of a message.
	 *
	 * @param in Input stream
	 * @return Message
	 * @since 1.0
	 */
	public static byte[] readPCEPMsg(InputStream in)
	{
		int preambleLength_PCEP = 2;
		int headerLength_PCEP = 4;
		byte[] ret = null;
		byte[] hdr = new byte[headerLength_PCEP];
		byte[] temp = null;
		boolean endHdr = false;
		int r = 0;
		int length = 0;
		boolean endMsg = false;
		int offset = 0;

		while(! endMsg)
		{
			try{ r = in.read(endHdr ? temp : hdr, offset, 1); }catch(IOException e){ throw new RuntimeException("Error reading PCEP data: " + e.getMessage()); }catch(Exception e)
			{
				throw new RuntimeException(e);
			}

			if(r > 0)
			{
				if(offset == preambleLength_PCEP) length = ((int) hdr[offset] & 0xFF) << 8;

				if(offset == preambleLength_PCEP + 1)
				{
					length = length | (((int) hdr[offset] & 0xFF));
					temp = new byte[length];
					endHdr = true;
					System.arraycopy(hdr, 0, temp, 0, headerLength_PCEP);
				}

				if((length > 0) && (offset == length - 1)) endMsg = true;

				offset++;
			} else if(r == - 1)
			{
				throw new RuntimeException("End of stream has been reached");
			}
		}

		if(length > 0)
		{
			ret = new byte[length];
			System.arraycopy(temp, 0, ret, 0, length);
		}

		return ret;
	}

	/**
	 * Send a message through the given output stream.
	 *
	 * @param out Output stream
	 * @param msg Message to be sent
	 * @since 1.0
	 */
	public static void writeMessage(OutputStream out, byte[] msg)
	{
		try{ out.write(msg); }catch(IOException e){ throw new RuntimeException(e); }
	}

	public synchronized static long getNewReqIDCounter()
	{
		long newReqId;
		if(reqIDCounter == 0)
		{
			Calendar now = Calendar.getInstance();
			newReqId = now.get(Calendar.SECOND);
			reqIDCounter = newReqId;
		} else
		{
			newReqId = reqIDCounter >= 0xFFFFFFFDL ? 1 : (reqIDCounter + 1);
			reqIDCounter = newReqId;
		}
		return newReqId;
	}

}