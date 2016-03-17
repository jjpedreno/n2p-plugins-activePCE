/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package es.upct.girtel.net2plan.plugins.abnopce.utils;

import es.tid.ospf.ospfv2.lsa.tlv.subtlv.AvailableLabels;
import es.tid.ospf.ospfv2.lsa.tlv.subtlv.MalformedOSPFSubTLVException;
import es.tid.ospf.ospfv2.lsa.tlv.subtlv.complexFields.BitmapLabelSet;
import es.tid.ospf.ospfv2.lsa.tlv.subtlv.complexFields.LabelListField;
import es.tid.ospf.ospfv2.lsa.tlv.subtlv.complexFields.LabelSetParameters;

/**
 *
 * @author Jose Luis
 */
public class AvailableLabels_UPCT extends AvailableLabels
{
	@Override
	public void decode() throws MalformedOSPFSubTLVException
	{
		int offset = 4; //cabecera de OSPFSubTLV
		int type = (int) (((this.getTlv_bytes()[offset]) & 0xF0)>>4);
		
		switch(type)
		{
			case LabelSetParameters.InclusiveLabelLists:
			case LabelSetParameters.ExclusiveLabelLists:
				setLabelSet(new LabelListField(this.getTlv_bytes(), offset));
				break;
				
			case LabelSetParameters.InclusiveLabelRanges:
			case LabelSetParameters.ExclusiveLabelRanges:
				setLabelSet(new LabelRangeField_UPCT(this.getTlv_bytes(), offset));
				break;
				
			case LabelSetParameters.BitmapLabelSet:
				setLabelSet(new BitmapLabelSet(this.getTlv_bytes(),offset));
				break;
		}
	}
	
}
