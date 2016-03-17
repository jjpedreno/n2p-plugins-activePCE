package es.upct.girtel.net2plan.plugins.abnopce.utils;

import es.tid.ospf.ospfv2.lsa.tlv.subtlv.MalformedOSPFSubTLVException;
import es.tid.ospf.ospfv2.lsa.tlv.subtlv.complexFields.LabelSetField;

public class LabelRangeField_UPCT extends LabelSetField
{
	private final static int headerSize = 4;
	int startLabel, endLabel;
	
	public LabelRangeField_UPCT()
	{
		action = 2; /* Allowed values: 2 or 3. See draft-ietf-ccamp-general-constraint-encode-20, Section 2.6.2 */
		startLabel = 0;
		endLabel = 0;
	}

	public LabelRangeField_UPCT(byte[] bytes, int offset) throws MalformedOSPFSubTLVException
	{
		this.bytes = bytes;
		decode();
	}

	@Override
	public void encode()
	{
		bytes = new byte[headerSize + 8];
		encodeHeader();
		
		byte[] startLabel_byte = Utils.intToByteArray(startLabel, 4);
		System.arraycopy(startLabel_byte, 0, bytes, headerSize, startLabel_byte.length);
		
		byte[] endLabel_byte = Utils.intToByteArray(endLabel, 4);
		System.arraycopy(endLabel_byte, 0, bytes, headerSize + 4, endLabel_byte.length);
	}

	@Override
	public void decode() throws MalformedOSPFSubTLVException
	{
		decodeHeader();
		
		byte[] startLabel_byte = new byte[4]; 
		System.arraycopy(bytes, headerSize, startLabel_byte, 0, 4);

		byte[] endLabel_byte = new byte[4]; 
		System.arraycopy(bytes, headerSize + 4, endLabel_byte, 0, 4);
		
		setNumLabels(endLabel - startLabel + 1);
	}
	
	@Override
	public void setNumLabels(int numLabels)
	{
		super.setNumLabels(numLabels);
		
		endLabel = startLabel + numLabels - 1;
	}
	
	public int getStartLabel()
	{
		return startLabel;
	}

	public void setStartLabel(int startLabel)
	{
		this.startLabel = startLabel;
		endLabel = startLabel + numLabels - 1;
	}

	@Override
	public String toString()
	{
		return super.toString() + ", startLabel = " + startLabel + ", endLabel = " + endLabel;
	}

	@Override
	public LabelSetField duplicate()
	{
		return null;
	}
}
