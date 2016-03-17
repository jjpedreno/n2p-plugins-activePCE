/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package es.upct.girtel.net2plan.plugins.abnopce.utils;

import es.tid.pce.pcep.objects.LoadBalancing;

/**
 *
 * @author Jose Luis
 */
public class LoadBalancing_UPCT extends LoadBalancing
{
	@Override
	public String toString()
	{
		return "Max. bifurcation: " + getMaxLSP() + ", min. BW per path: " + getMinBandwidth();
	}
}
