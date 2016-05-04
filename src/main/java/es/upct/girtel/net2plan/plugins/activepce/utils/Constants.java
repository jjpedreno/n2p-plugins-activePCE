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

public class Constants
{
	public final static boolean DEBUG                      = true; //In true, some trace prints will occur in the PCE screen
	public final static int     PCEP_SERVER_PORT           = 4189;
	public final static int     PCEP_MAX_LENGTH            = 65535; // Length field has 16 bits, indicates total length including Header, expressed in bytes.
	public final static int     BGP_SERVER_PORT            = 179;
	public final static int     BGP_MAX_LENGTH             = 65535; // Length field has 16 bits, indicates total length including Header, expressed in bytes.
	public final static int     W                          = 40; //Wavelengths per fiber
	public final static int     LIGHTPATH_BINARY_RATE_GBPS = 40; //Binary rate per lightpath in Gbs
	public final static int     WDM_LAYER_INDEX            = 0;
	public final static int     IP_LAYER_INDEX             = 1;

	public final static String ATTRIBUTE_IP_ADDRESS            = "ipAddress";
	public final static String ATTRIBUTE_NODE_TYPE             = "type";
	public final static String ATTRIBUTE_REQUEST_ID            = "requestId";
	public final static String ATTRIBUTE_SOURCE_INTERFACE      = "srcIf";
	public final static String ATTRIBUTE_DESTINATION_INTERFACE = "dstIf";
	public final static String ATTRIBUTE_LSP_ID                = "lspId";

	public final static String NODE_TYPE_ROUTER = "ipRouter";
	public final static String NODE_TYPE_ROADM  = "roadm";
}
