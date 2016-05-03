package es.upct.girtel.net2plan.plugins.activepce.utils;

import com.net2plan.interfaces.networkDesign.NetPlan;
import es.tid.bgp.bgp4.messages.BGP4Message;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.util.Calendar;
import java.util.Set;

public class Utils
{
	private static long reqIDCounter = 0;
	
	private final static String IP_ADDRESS_ATTRIBUTE_NAME = "ipAddress";
	private final static String SOURCE_INTERFACE_ATTRIBUTE_NAME = "srcIf";
	private final static String DESTINATION_INTERFACE_ATTRIBUTE_NAME = "dstIf";
	
	private Utils() { }
	
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
		if (numBytes < 1 || numBytes > 4) throw new RuntimeException("Bad");
		
		byte[] actualByteArray = new byte[] { (byte)(value >>> 24), (byte)(value >>> 16), (byte)(value >>> 8), (byte)value };
		byte[] out = new byte[numBytes];
		System.arraycopy(actualByteArray, 4 - numBytes, out, 0, numBytes);
		
		return out;
	}
	
	public static long getLinkByDestinationIPAddress(NetPlan netPlan, Inet4Address inet4Address) throws UnknownHostException
	{
		long layerId = netPlan.getLayerDefaultId();
		return getLinkByDestinationIPAddress(netPlan, layerId, inet4Address);
	}
	
	public static long getLinkByDestinationIPAddress(NetPlan netPlan, long layerId, Inet4Address inet4Address) throws UnknownHostException
	{
		Set<Long> linkIds = netPlan.getLinkIds(layerId);
		for(long linkId : linkIds)
			if (getLinkDestinationIPAddress(netPlan, layerId, linkId).equals(inet4Address))
				return linkId;
		
		return -1;
	}
	
	public static long getLinkBySourceInterface(NetPlan netPlan, long nodeId, long interfaceId)
	{
		long layerId = netPlan.getLayerDefaultId();
		return getLinkBySourceInterface(netPlan, layerId, nodeId, interfaceId);
	}
	
	public static long getLinkBySourceInterface(NetPlan netPlan, long layerId, long nodeId, long interfaceId)
	{
		Set<Long> outgoingLinkIds_thisNode = netPlan.getNodeOutgoingLinks(layerId, nodeId);
		for(long linkId : outgoingLinkIds_thisNode)
			if (getLinkSourceInterface(netPlan, layerId, linkId) == interfaceId)
				return linkId;
		
		return -1;
	}
	
	public static long getLinkBySourceIPAddress(NetPlan netPlan, Inet4Address inet4Address) throws UnknownHostException
	{
		long layerId = netPlan.getLayerDefaultId();
		return getLinkBySourceIPAddress(netPlan, layerId, inet4Address);
	}
	
	public static long getLinkBySourceIPAddress(NetPlan netPlan, long layerId, Inet4Address inet4Address) throws UnknownHostException
	{
		Set<Long> linkIds = netPlan.getLinkIds(layerId);
		for(long linkId : linkIds)
			if (getLinkSourceIPAddress(netPlan, layerId, linkId).equals(inet4Address))
				return linkId;
		
		return -1;
	}
	
	public static long getLinkDestinationInterface(NetPlan netPlan, long linkId)
	{
		long layerId = netPlan.getLayerDefaultId();
		return getLinkDestinationInterface(netPlan, layerId, linkId);
	}

	public static long getLinkDestinationInterface(NetPlan netPlan, long layerId, long linkId)
	{
		return Long.parseLong(netPlan.getLinkAttribute(layerId, linkId, DESTINATION_INTERFACE_ATTRIBUTE_NAME));
	}

	public static Inet4Address getLinkDestinationIPAddress(NetPlan netPlan, long linkId) throws UnknownHostException
	{
		long layerId = netPlan.getLayerDefaultId();
		return getLinkDestinationIPAddress(netPlan, layerId, linkId);
	}

	public static Inet4Address getLinkDestinationIPAddress(NetPlan netPlan, long layerId, long linkId) throws UnknownHostException
	{
		return getNodeIPAddress(netPlan, netPlan.getLinkDestinationNode(layerId, linkId));
	}

	public static long getLinkSourceInterface(NetPlan netPlan, long linkId)
	{
		long layerId = netPlan.getLayerDefaultId();
		return getLinkSourceInterface(netPlan, layerId, linkId);
	}

	public static long getLinkSourceInterface(NetPlan netPlan, long layerId, long linkId)
	{
		return Long.parseLong(netPlan.getLinkAttribute(layerId, linkId, SOURCE_INTERFACE_ATTRIBUTE_NAME));
	}

	public static Inet4Address getLinkSourceIPAddress(NetPlan netPlan, long linkId) throws UnknownHostException
	{
		long layerId = netPlan.getLayerDefaultId();
		return getLinkSourceIPAddress(netPlan, layerId, linkId);
	}

	public static Inet4Address getLinkSourceIPAddress(NetPlan netPlan, long layerId, long linkId) throws UnknownHostException
	{
		return getNodeIPAddress(netPlan, netPlan.getLinkOriginNode(layerId, linkId));
	}
	
	public static long getNodeByIPAddress(NetPlan netPlan, Inet4Address inet4Address) throws UnknownHostException
	{
		Set<Long> nodeIds = netPlan.getNodeIds();
		for(long nodeId : nodeIds)
			if (getNodeIPAddress(netPlan, nodeId).equals(inet4Address))
				return nodeId;
		
		return -1;
	}

	public static Inet4Address getNodeIPAddress(NetPlan netPlan, long nodeId) throws UnknownHostException
	{
		return (Inet4Address) Inet4Address.getByName(netPlan.getNodeAttribute(nodeId, IP_ADDRESS_ATTRIBUTE_NAME));
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

		while (!endMsg)
		{
			try { r = in.read(endHdr ? temp : hdr, offset, 1); }
			catch (IOException e) { throw new RuntimeException("Error reading BGP data: " + e.getMessage()); }
			catch (Exception e) { throw new RuntimeException(e); }

			if (r > 0)
			{
				if (offset == BGP4Message.getBGPMarkerLength()) length = ((int)hdr[offset]&0xFF) << 8;
				
				if (offset ==  BGP4Message.getBGPMarkerLength() + 1)
				{
					length = length | (((int)hdr[offset]&0xFF));
					temp = new byte[length];
					endHdr = true;
					System.arraycopy(hdr, 0, temp, 0, BGP4Message.getBGPHeaderLength());
				}
				
				if ((length > 0) && (offset == length - 1)) endMsg = true;

				offset++;
			}
			else if (r == -1)
			{
				throw new RuntimeException("End of stream has been reached");
			}
		}

		if (length > 0)
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

		while (!endMsg) {
			try { r = in.read(endHdr ? temp : hdr, offset, 1); }
			catch (IOException e) { throw new RuntimeException("Error reading PCEP data: " + e.getMessage()); }
			catch (Exception e) { throw new RuntimeException(e); }

			if (r > 0)
			{
				if (offset == preambleLength_PCEP) length = ((int)hdr[offset]&0xFF) << 8;

				if (offset == preambleLength_PCEP + 1)
				{
					length = length | (((int)hdr[offset]&0xFF));
					temp = new byte[length];
					endHdr = true;
					System.arraycopy(hdr, 0, temp, 0, headerLength_PCEP);
				}
				
				if ((length > 0) && (offset == length - 1)) endMsg = true;

				offset++;
			}
			else if (r==-1)
			{
				throw new RuntimeException("End of stream has been reached");
			}
		}

		if (length > 0)
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
		try { out.write(msg); }
		catch(IOException e) { throw new RuntimeException(e); }
	}
	
	public synchronized static long getNewReqIDCounter()
	{
		long newReqId;
		if (reqIDCounter == 0)
		{
			Calendar now = Calendar.getInstance();
			newReqId = now.get(Calendar.SECOND);		
			reqIDCounter = newReqId;
		}
		else
		{
			newReqId = reqIDCounter >= 0xFFFFFFFDL ? 1 : (reqIDCounter + 1);
			reqIDCounter = newReqId;
		}	
		return newReqId;
	}
	
}