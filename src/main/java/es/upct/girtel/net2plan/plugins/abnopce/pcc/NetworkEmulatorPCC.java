package es.upct.girtel.net2plan.plugins.abnopce.pcc;

import com.net2plan.gui.GUINet2Plan;
import com.net2plan.gui.tools.IGUINetworkViewer;
import com.net2plan.gui.tools.utils.CellRenderers;
import com.net2plan.gui.utils.*;
import com.net2plan.interfaces.networkDesign.Net2PlanException;
import com.net2plan.interfaces.networkDesign.NetPlan;
import com.net2plan.internal.Constants;
import com.net2plan.internal.ErrorHandling;
import com.net2plan.internal.plugins.IGUIModule;
import com.net2plan.internal.plugins.PluginSystem;
import com.net2plan.libraries.WDMUtils;
import com.net2plan.utils.IntUtils;
import com.net2plan.utils.StringUtils;
import com.net2plan.utils.Triple;
import es.tid.bgp.bgp4.messages.*;
import es.tid.bgp.bgp4.open.BGP4CapabilitiesOptionalParameter;
import es.tid.bgp.bgp4.open.BGP4Capability;
import es.tid.bgp.bgp4.open.BGP4OptionalParameter;
import es.tid.bgp.bgp4.open.MultiprotocolExtensionCapabilityAdvertisement;
import es.tid.bgp.bgp4.update.fields.LinkNLRI;
import es.tid.bgp.bgp4.update.fields.NodeNLRI;
import es.tid.bgp.bgp4.update.fields.PathAttribute;
import es.tid.bgp.bgp4.update.fields.pathAttributes.*;
import es.tid.bgp.bgp4.update.tlv.LocalNodeDescriptorsTLV;
import es.tid.bgp.bgp4.update.tlv.ProtocolIDCodes;
import es.tid.bgp.bgp4.update.tlv.RemoteNodeDescriptorsTLV;
import es.tid.bgp.bgp4.update.tlv.RoutingUniverseIdentifierTypes;
import es.tid.bgp.bgp4.update.tlv.node_link_prefix_descriptor_subTLVs.IGPRouterIDNodeDescriptorSubTLV;
import es.tid.bgp.bgp4.update.tlv.node_link_prefix_descriptor_subTLVs.IPv4InterfaceAddressLinkDescriptorsSubTLV;
import es.tid.bgp.bgp4.update.tlv.node_link_prefix_descriptor_subTLVs.IPv4NeighborAddressLinkDescriptorSubTLV;
import es.tid.bgp.bgp4.update.tlv.node_link_prefix_descriptor_subTLVs.LinkLocalRemoteIdentifiersLinkDescriptorSubTLV;
import es.tid.pce.pcep.constructs.*;
import es.tid.pce.pcep.messages.*;
import es.tid.pce.pcep.objects.*;
import es.tid.rsvp.constructs.gmpls.DWDMWavelengthLabel;
import es.tid.rsvp.objects.subobjects.EROSubobject;
import es.tid.rsvp.objects.subobjects.GeneralizedLabelEROSubobject;
import es.tid.rsvp.objects.subobjects.IPv4prefixEROSubobject;
import es.tid.rsvp.objects.subobjects.UnnumberIfIDEROSubobject;
import es.upct.girtel.net2plan.plugins.abnopce.utils.LoadBalancing_UPCT;
import es.upct.girtel.net2plan.plugins.abnopce.utils.Utils;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.util.*;
import java.util.List;
import java.util.Map.Entry;

/**
 *
 * @author Jose Luis
 */
public class NetworkEmulatorPCC extends IGUINetworkViewer implements ActionListener, ItemListener
{
	private final static String NEW_LINE = StringUtils.getLineSeparator();
	private final static String title = "Network Emulator and PCEP/BGP-LS Client";
	private JTextArea log;
	private JButton clearButton;
	private JTextField txt_ip, txt_bandwidth, txt_maxBifurcation, txt_minBandwidthPerPath;
	private JComboBox sourceNode, destinationNode, fiberFailureSelector;
	private JButton gotoServicesButton, makePCERequest, connectToPCE, disconnectFromPCE, failFiber, viewFiber;
	private JCheckBox loadBalancingEnabled;
	private JPanel loadBalancingPanel;
	private Thread pcepThread, bgplsThread;
	private Socket pcepSocket, bgplsSocket;
	private Map<Long, Set<Long>> routeOriginalLinks;
	
	public static void main(String[] args)
	{
		GUINet2Plan.main(args);
		PluginSystem.addPlugin(IGUIModule.class, NetworkEmulatorPCC.class);
		PluginSystem.loadExternalPlugins();
		GUINet2Plan.refreshMenu();
	}
	
	public NetworkEmulatorPCC()
	{
		super(title.toUpperCase(Locale.getDefault()));
	}

	@Override
	public void actionPerformed(ActionEvent e)
	{
		Object src = e.getSource();
		if (src == clearButton)
		{
			log.setText("");
		}
		else if (src == gotoServicesButton)
		{
			selectNetPlanViewItem(Constants.NetworkElementType.DEMAND, null);
		}
		else if (src == makePCERequest)
		{
			performPCERequest();
		}
		else if (src == connectToPCE)
		{
			connect();
		}
		else if (src == disconnectFromPCE)
		{
			shutdown();
		}
	}
	
	@Override
	public void configure(JPanel contentPane)
	{
		routeOriginalLinks = new HashMap<>();

		txt_ip = new JTextField(getCurrentOptions().get("pce.defaultIP"));

		sourceNode = new WiderJComboBox();
		destinationNode = new WiderJComboBox();
		txt_bandwidth = new JTextField();
		loadBalancingEnabled = new JCheckBox("Load-balancing");
		loadBalancingEnabled.addItemListener(this);
		loadBalancingPanel = new JPanel(new MigLayout("", "[][grow]", "[][]"));
		gotoServicesButton = new JButton("Go to existing services");
		txt_maxBifurcation = new JTextField();
		txt_minBandwidthPerPath = new JTextField();
		makePCERequest = new JButton("Make request");
		connectToPCE = new JButton("Connect");
		connectToPCE.setBackground(Color.RED);
		connectToPCE.setContentAreaFilled(false);
		connectToPCE.setOpaque(true);
		disconnectFromPCE = new JButton("Disconnect");
		disconnectFromPCE.setEnabled(false);
		failFiber = new JButton("Simulate failure");
		viewFiber = new JButton("View fiber"); viewFiber.setEnabled(false);
		
		super.configure(contentPane);

		JPanel controllerTab = new JPanel();
		controllerTab.setLayout(new MigLayout("fill, insets 0 0 0 0", "[grow]", "[][][grow]"));
		controllerTab.setBorder(new LineBorder(Color.BLACK));
		
		JPanel connectionHandler = new JPanel(new MigLayout("insets 0 0 0 0", "[][grow]", "[][]"));
		connectionHandler.setBorder(BorderFactory.createTitledBorder(new LineBorder(Color.BLACK), "PCE connection controller"));
		connectionHandler.add(new JLabel("PCE IP"));
		connectionHandler.add(txt_ip, "growx, wrap");
		
		JPanel connectionHandlerButton = new JPanel(new FlowLayout());
		connectionHandlerButton.add(connectToPCE);
		connectionHandlerButton.add(disconnectFromPCE);
		connectionHandler.add(connectionHandlerButton, "center, spanx 2, wrap");
		connectToPCE.addActionListener(this);
		disconnectFromPCE.addActionListener(this);
		
		JPanel serviceProvisioning = new JPanel();
		serviceProvisioning.setLayout(new MigLayout("insets 0 0 0 0", "[][grow]", "[][][][][grow]"));
		serviceProvisioning.setBorder(BorderFactory.createTitledBorder(new LineBorder(Color.BLACK), "Service provisioning"));
		
		serviceProvisioning.add(new JLabel("Source node"));
		serviceProvisioning.add(sourceNode, "growx, wrap");
		serviceProvisioning.add(new JLabel("Destination node"));
		serviceProvisioning.add(destinationNode, "growx, wrap");
		serviceProvisioning.add(new JLabel("Requested bandwidth"));
		serviceProvisioning.add(txt_bandwidth, "growx, wrap");
		serviceProvisioning.add(new JLabel("Additional constraints"), "top");
		
		JPanel additionalConstraints = new JPanel(new MigLayout("fill, insets 0 0 0 0", "[grow]", "[][]"));
		
		loadBalancingPanel.add(new JLabel("Maximum number of paths"));
		loadBalancingPanel.add(txt_maxBifurcation, "growx, wrap");
		loadBalancingPanel.add(new JLabel("Minimum bandwidth per path"));
		loadBalancingPanel.add(txt_minBandwidthPerPath, "growx, wrap");
		
		additionalConstraints.add(loadBalancingEnabled, "growx, wrap");
		additionalConstraints.add(loadBalancingPanel, "growx, wrap");
		serviceProvisioning.add(additionalConstraints, "growx, wrap");
		serviceProvisioning.add(makePCERequest, "center, spanx 2, wrap");
		makePCERequest.addActionListener(this);
		
		gotoServicesButton.addActionListener(this);
		serviceProvisioning.add(gotoServicesButton, "dock south");
		
		JPanel failureReparationSimulator = new JPanel();
		fiberFailureSelector = new WiderJComboBox();
		failureReparationSimulator.setLayout(new MigLayout("insets 0 0 0 0", "[][grow]", "[][grow]"));
		failureReparationSimulator.setBorder(BorderFactory.createTitledBorder(new LineBorder(Color.BLACK), "Fiber failure/reparation simulator"));
		failureReparationSimulator.add(new JLabel("Select fiber to fail"));
		failureReparationSimulator.add(fiberFailureSelector, "growx, wrap");
		
		JPanel failureSimulatorButtonPanel = new JPanel(new FlowLayout());
		failureSimulatorButtonPanel.add(failFiber);
		failureReparationSimulator.add(failureSimulatorButtonPanel, "center, spanx 2, wrap");
		
		final DefaultTableModel model = new ClassAwareTableModel(new Object[1][6], new String[] { "Id", "Origin node", "Destination node", "" })
		{
			@Override
			public boolean isCellEditable(int rowIndex, int columnIndex)
			{
				return columnIndex == 3;
			}
		};
		
		final JTable table = new AdvancedJTable(model);
		table.setEnabled(false);
		
		failFiber.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				long linkId = (Long) ((StringLabeller) fiberFailureSelector.getSelectedItem()).getObject();
				NetPlan netPlan = getDesign();

				netPlan.setLinkDown(0, linkId);
				if (netPlan.getLayerDefaultId() == 0) getTopologyPanel().getCanvas().setLinkDown(linkId);

				updateOperationLog("Simulating failure");

				try
				{
					if (bgplsSocket == null) throw new Net2PlanException("PCC not connected to PCE");
					BGP4Update updateMessage = createLinkMessage(netPlan, 0, linkId, false);

					OutputStream outBGP = bgplsSocket.getOutputStream();
					Utils.writeMessage(outBGP, updateMessage.getBytes());
				}
				catch (Throwable ex)
				{
					ErrorHandling.addErrorOrException(ex, NetworkEmulatorPCC.class);
					ErrorHandling.showErrorDialog(ex.getMessage(), "Unable to simulate failure");
					return;
				}



				if (fiberFailureSelector.getItemCount() == 0) failFiber.setVisible(false);
		
				if (!table.isEnabled()) model.removeRow(0);
				model.addRow(new Object[] { linkId, netPlan.getLinkOriginNode(0, linkId), netPlan.getLinkDestinationNode(0, linkId), "Repair" });

				table.setEnabled(true);
				updateNetPlanView();
			}
		});
		
		Action repair = new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				try
				{
					JTable table = (JTable) e.getSource();
					int modelRow = Integer.parseInt(e.getActionCommand());

					long linkId = (Long) table.getModel().getValueAt(modelRow, 0);
					NetPlan netPlan = getDesign();
					
					try
					{
						if (bgplsSocket == null) throw new Net2PlanException("PCC not connected to PCE");
						BGP4Update updateMessage = createLinkMessage(netPlan, 0, linkId, true);

						OutputStream outBGP = bgplsSocket.getOutputStream();
						Utils.writeMessage(outBGP, updateMessage.getBytes());
					}
					catch (Throwable ex)
					{
						ErrorHandling.addErrorOrException(ex, NetworkEmulatorPCC.class);
						ErrorHandling.showErrorDialog(ex.getMessage(), "Unable to simulate reparation");
						return;
					}

					netPlan.setLinkUp(0, linkId);
					if (netPlan.getLayerDefaultId() == 0) getTopologyPanel().getCanvas().setLinkUp(linkId);
					
					updateNetPlanView();

					long originNodeId = netPlan.getLinkOriginNode(linkId);
					long destinationNodeId = netPlan.getLinkDestinationNode(linkId);
					fiberFailureSelector.addItem(StringLabeller.unmodifiableOf(linkId, "e" + linkId + " [n" + originNodeId + " (" + netPlan.getNodeName(originNodeId) + ") -> n" + destinationNodeId + " (" + netPlan.getNodeName(destinationNodeId) + ")]"));
					failFiber.setVisible(true);
					
					((DefaultTableModel) table.getModel()).removeRow(modelRow);

					table.setEnabled(true);

					if (table.getModel().getRowCount() == 0)
					{
						((DefaultTableModel) table.getModel()).addRow(new Object[6]);
						table.setEnabled(false);
					}
				}
				catch (Throwable e1)
				{
					throw new RuntimeException(e1);
				}
			}
		};

		new ButtonColumn(table, repair, 3);

		final JScrollPane scrollPane = new JScrollPane(table, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setBorder(BorderFactory.createTitledBorder(new LineBorder(Color.BLACK), "Current failed fibers"));
		scrollPane.setAlignmentY(JScrollPane.TOP_ALIGNMENT);
		failureReparationSimulator.add(scrollPane, "spanx, grow, wrap");

		table.setDefaultRenderer(Long.class, new CellRenderers.UnfocusableCellRenderer());
		table.setDefaultRenderer(Integer.class, new CellRenderers.UnfocusableCellRenderer());
		table.setDefaultRenderer(String.class, new CellRenderers.UnfocusableCellRenderer());

		controllerTab.add(connectionHandler, "growx, wrap");
		controllerTab.add(serviceProvisioning, "growx, wrap");
		controllerTab.add(failureReparationSimulator, "grow, wrap");
		
		JPanel eventGenerator = new JPanel(new MigLayout("insets 0 0 0 0", "[][grow]", "[]"));
		controllerTab.add(eventGenerator, "grow, wrap");
		
		addTab("Emulator controller", controllerTab, 2);
	}
	
	@Override
	public JPanel configureLeftBottomPanel()
	{
		clearButton = new JButton("Clear");
		clearButton.addActionListener(this);
		JToolBar toolbar = new JToolBar();
		toolbar.add(clearButton);
		toolbar.setFloatable(false);
		toolbar.setRollover(true);
		
		log = new JTextArea();
		log.setFont(new JLabel().getFont());
		DefaultCaret caret = (DefaultCaret) log.getCaret();
		caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

		JPanel leftBottomPanel = new JPanel(new MigLayout("fill, insets 0 0 0 0"));
		leftBottomPanel.setBorder(BorderFactory.createTitledBorder(new LineBorder(Color.BLACK), "Operation log"));
		leftBottomPanel.add(toolbar, "dock north");
		leftBottomPanel.add(new JScrollPane(log), "grow");
		
		return leftBottomPanel;
	}
	
	@Override
	public String getDescription()
	{
		return null;
	}

	@Override
	public KeyStroke getKeyStroke()
	{
		return null;
	}

	@Override
	public String getMenu()
	{
		return "SDN|" + title;
	}

	@Override
	public String getName()
	{
		return title;
	}
	
	@Override
	public List<Triple<String, String, String>> getParameters()
	{
		List<Triple<String, String, String>> parameters = new LinkedList<Triple<String, String, String>>();
		parameters.add(Triple.of("pce.defaultIP", "192.168.56.101", "Default IP address of the machine where PCE is running"));
		
		return parameters;
	}

	@Override
	public void itemStateChanged(ItemEvent e)
	{
		Object src = e.getSource();
		if (src == loadBalancingEnabled)
			SwingUtils.setEnabled(loadBalancingPanel, ((JCheckBox) loadBalancingEnabled).isSelected());
	}
	
	@Override
	public void loadDesign(NetPlan netPlan)
	{
		super.loadDesign(netPlan);
		
		if (!netPlan.isEmpty())
		{
			updateOperationLog("Design loaded");
			
			Map<Long, String> nodeIPAddress = netPlan.getNodesAttributeMap("ipAddress");
			sourceNode.removeAllItems();
			destinationNode.removeAllItems();

			for(Entry<Long, String> entry : nodeIPAddress.entrySet())
			{
				if (netPlan.getNodeAttribute(entry.getKey(), "type").equals("roadm")) continue;

				sourceNode.addItem(StringLabeller.unmodifiableOf(entry.getKey(), entry.getValue() + " [n" + entry.getKey() + ", " + netPlan.getNodeName(entry.getKey()) + "]"));
				destinationNode.addItem(StringLabeller.unmodifiableOf(entry.getKey(), entry.getValue() + " [n" + entry.getKey() + ", " + netPlan.getNodeName(entry.getKey()) + "]"));
			}

			fiberFailureSelector.removeAllItems();
			for(long linkId : netPlan.getLinkIds())
			{
				long originNodeId = netPlan.getLinkOriginNode(linkId);
				long destinationNodeId = netPlan.getLinkDestinationNode(linkId);

				String sourceNodeType = netPlan.getNodeAttribute(originNodeId, "type");
				String destinationNodeType = netPlan.getNodeAttribute(destinationNodeId, "type");

				if (sourceNodeType.equals(destinationNodeType) && sourceNodeType.equals("roadm"))
					fiberFailureSelector.addItem(StringLabeller.unmodifiableOf(linkId, "e" + linkId + " [n" + originNodeId + " (" + netPlan.getNodeName(originNodeId) + ") -> n" + destinationNodeId + " (" + netPlan.getNodeName(destinationNodeId) + ")]"));
			}
		}
	}
	
	private void connect()
	{
		txt_ip.setEnabled(false);
		connectToPCE.setEnabled(false);
		connectToPCE.setBackground(Color.GREEN);
		disconnectFromPCE.setEnabled(true);
		
		startPCEP();
	}
	
	private BGP4Update createLinkMessage(NetPlan netPlan, long layerId, long linkId, boolean isUp)
	{
		try
		{
			/* Create link NRLI */
			LinkNLRI nlri = new LinkNLRI();
			nlri.setProtocolID(ProtocolIDCodes.Direct_Protocol_ID); /* Direct (see draft-ietf-idr-ls-distribution-10) */
			//nlri.setRoutingUniverseIdentifier(RoutingUniverseIdentifierTypes.Level1Identifier);
			nlri.setIdentifier(RoutingUniverseIdentifierTypes.Level1Identifier);

			/* Source node */
			LocalNodeDescriptorsTLV localNodeDescriptors = new LocalNodeDescriptorsTLV();
			IGPRouterIDNodeDescriptorSubTLV igpRouterIDLNSubTLV = new IGPRouterIDNodeDescriptorSubTLV();
			igpRouterIDLNSubTLV.setIpv4AddressOSPF(Utils.getLinkSourceIPAddress(netPlan, layerId, linkId));	
			igpRouterIDLNSubTLV.setIGP_router_id_type(IGPRouterIDNodeDescriptorSubTLV.IGP_ROUTER_ID_TYPE_OSPF_NON_PSEUDO);
			localNodeDescriptors.setIGPRouterID(igpRouterIDLNSubTLV);

			nlri.setLocalNodeDescriptors(localNodeDescriptors);

			/* Destination node */
			RemoteNodeDescriptorsTLV remoteNodeDescriptors = new RemoteNodeDescriptorsTLV();
			IGPRouterIDNodeDescriptorSubTLV igpRouterIDDNSubTLV = new IGPRouterIDNodeDescriptorSubTLV();
			igpRouterIDDNSubTLV.setIpv4AddressOSPF(Utils.getLinkDestinationIPAddress(netPlan, layerId, linkId));	
			igpRouterIDDNSubTLV.setIGP_router_id_type(IGPRouterIDNodeDescriptorSubTLV.IGP_ROUTER_ID_TYPE_OSPF_NON_PSEUDO);
			remoteNodeDescriptors.setIGPRouterID(igpRouterIDDNSubTLV);

			nlri.setRemoteNodeDescriptorsTLV(remoteNodeDescriptors);

			/* Interface IP addresses */
			IPv4InterfaceAddressLinkDescriptorsSubTLV ipv4InterfaceAddressTLV = new IPv4InterfaceAddressLinkDescriptorsSubTLV();
			ipv4InterfaceAddressTLV.setIpv4Address(Utils.getLinkSourceIPAddress(netPlan, layerId, linkId));
			nlri.setIpv4InterfaceAddressTLV(ipv4InterfaceAddressTLV);
			IPv4NeighborAddressLinkDescriptorSubTLV ipv4NeighborAddressTLV = new IPv4NeighborAddressLinkDescriptorSubTLV();
			ipv4NeighborAddressTLV.setIpv4Address(Utils.getLinkDestinationIPAddress(netPlan, layerId, linkId));
			nlri.setIpv4NeighborAddressTLV(ipv4NeighborAddressTLV);

			/* Interface identifiers */
			String sourceNodeType = netPlan.getNodeAttribute(netPlan.getLinkOriginNode(layerId, linkId), "type");
			String destinationNodeType = netPlan.getNodeAttribute(netPlan.getLinkDestinationNode(layerId, linkId), "type");
			if (sourceNodeType.equals(destinationNodeType))
			{
				LinkLocalRemoteIdentifiersLinkDescriptorSubTLV linkIdentifiersTLV = new LinkLocalRemoteIdentifiersLinkDescriptorSubTLV();
				linkIdentifiersTLV.setLinkLocalIdentifier(Utils.getLinkSourceInterface(netPlan, layerId, linkId));
				linkIdentifiersTLV.setLinkRemoteIdentifier(Utils.getLinkDestinationInterface(netPlan, layerId, linkId));
				nlri.setLinkIdentifiersTLV(linkIdentifiersTLV);
			}

			/* Create path attributes (otherwise, PCE will not be able to decode the message (error?) */
			OriginAttribute or = new OriginAttribute();
			or.setValue(PathAttributesTypeCode.PATH_ATTRIBUTE_ORIGIN_IGP);
			ArrayList<PathAttribute> pathAttributes = new ArrayList<PathAttribute>();
			pathAttributes.add(or);
			PathAttribute mpReachAttribute = isUp ? new Generic_MP_Reach_Attribute() : new Generic_MP_Unreach_Attribute();
			pathAttributes.add(mpReachAttribute);

	//					if (layerId == 10)
	//					{
	//						LabelRangeField_UPCT lambdaSet = new LabelRangeField_UPCT();
	//						lambdaSet.setAction(2); /* Inclusive set (draft-ietf-ccamp-general-constraint-encode-20, 2.6.2) */
	//						lambdaSet.setStartLabel(0);
	//						lambdaSet.setNumLabels(WDMUtils.getFiberNumWavelengths(netPlan, layerId, linkId));
	//
	//						AvailableLabels al = new AvailableLabels();
	//						al.setLabelSet(lambdaSet);
	//						
	//						LinkStateAttribute linkStateAttribute = new LinkStateAttribute();
	//						linkStateAttribute.setAvailableLabels(al);
	//						pathAttributes.add(linkStateAttribute);
	//					}

			/* Create BGP update message */
			BGP4Update updateMsg = new BGP4Update();
			updateMsg.setNlri(nlri);
			updateMsg.setPathAttributes(pathAttributes);
			updateMsg.encode();
			
			return updateMsg;
		}
		catch(Throwable e)
		{
			throw new RuntimeException(e);
		}
	}
	
	private void performPCERequest()
	{
		NetPlan netPlan = getDesign();
		
		try
		{
			if (!(sourceNode.getSelectedItem() instanceof StringLabeller)) throw new Net2PlanException("Bad - No source node was selected");
			if (!(destinationNode.getSelectedItem() instanceof StringLabeller)) throw new Net2PlanException("Bad - No destination node was selected");

			long sourceNodeId = (Long) ((StringLabeller) sourceNode.getSelectedItem()).getObject();
			long destinationNodeId = (Long) ((StringLabeller) destinationNode.getSelectedItem()).getObject();
			if (sourceNodeId == destinationNodeId) throw new Net2PlanException("Bad - End nodes cannot be the same");
			
			float bandwidthInGbps;
			Map<String, String> attributeMap = new LinkedHashMap<String, String>();
			
			Request req = new Request();
			BandwidthRequested bw = new BandwidthRequested();
			try
			{
				String str_bandwidth = txt_bandwidth.getText();
				bandwidthInGbps = Float.parseFloat(str_bandwidth);
				if (bandwidthInGbps <= 0) throw new RuntimeException();
				bw.setBw(bandwidthInGbps * 1E9f / 8f);

				req.setBandwidth(bw);
			}
			catch(Throwable e)
			{
				throw new Net2PlanException("Bad - Requested bandwidth must be greater or equal than zero");
			}
			
			EndPointsIPv4 endpoints = new EndPointsIPv4();
			endpoints.setSourceIP(Utils.getNodeIPAddress(netPlan, sourceNodeId));
			endpoints.setDestIP(Utils.getNodeIPAddress(netPlan, destinationNodeId));
			req.setEndPoints(endpoints);
			
			if (loadBalancingEnabled.isSelected())
			{
				LoadBalancing loadBalancing = new LoadBalancing_UPCT(); //FIXME change to original Load Balancing (and add toString)

				try
				{
					String str_maxBifurcation = txt_maxBifurcation.getText();
					int allowedBifurcationDegree = Integer.parseInt(str_maxBifurcation);
					if (allowedBifurcationDegree <= 0) throw new RuntimeException();
					
					attributeMap.put("allowedBifurcationDegree", Integer.toString(allowedBifurcationDegree));
					loadBalancing.setMaxLSP(allowedBifurcationDegree);
				}
				catch(Throwable e)
				{
					throw new Net2PlanException("Bad - Maximum number of paths must be greater than zero");
				}
				
				try
				{
					String str_minBandwidthPerPath = txt_minBandwidthPerPath.getText();
					float minBandwidthPerPathInGbps = Float.parseFloat(str_minBandwidthPerPath);
					if (minBandwidthPerPathInGbps <= 0) throw new RuntimeException();
					
					attributeMap.put("minBandwidthPerPathInGbps", Float.toString(minBandwidthPerPathInGbps));
					loadBalancing.setMinBandwidth(minBandwidthPerPathInGbps * 1E9f / 8f);
				}
				catch(Throwable e)
				{
					throw new Net2PlanException("Bad - Minimum bandwidth per path must be greater or equal than zero");
				}
				
				req.setLoadBalancing(loadBalancing);
			}

			long requestId = Utils.getNewReqIDCounter();
			attributeMap.put("requestId", Long.toString(requestId));
			netPlan.addDemand(1, sourceNodeId, destinationNodeId, bandwidthInGbps, attributeMap);
			updateNetPlanView();
				
			RequestParameters requestParameters = new RequestParameters();
			requestParameters.setRequestID(requestId);
			
			PCEPRequest pcepRequest = new PCEPRequest();
			pcepRequest.addRequest(req);
			req.setRequestParameters(requestParameters);
			pcepRequest.encode();
			Utils.writeMessage(pcepSocket.getOutputStream(), pcepRequest.getBytes());
			
			updateOperationLog(">>PCEP REQ sent: "+pcepRequest.toString());
		}
		catch(Net2PlanException e)
		{
			if (ErrorHandling.isDebugEnabled()) ErrorHandling.addErrorOrException(e, NetworkEmulatorPCC.class);
			ErrorHandling.showErrorDialog(e.getMessage(), "Unable to make PCERequest");
		}
		catch(Throwable e)
		{
			ErrorHandling.addErrorOrException(e, NetworkEmulatorPCC.class);
			ErrorHandling.showErrorDialog("Unable to make PCERequest");
		}
	}
	
	private void sendNetworkState(OutputStream os)
	{
		List<BGP4Update> topologyMessages = new LinkedList<BGP4Update>();
		
		NetPlan netPlan = getDesign();
		Set<Long> nodeIds = netPlan.getNodeIds();
		for (long nodeId : nodeIds)
		{
			try
			{
				/* Create node IP descriptor */
				Inet4Address nodeIPAddress = Utils.getNodeIPAddress(netPlan, nodeId);
				IGPRouterIDNodeDescriptorSubTLV igpRouterIDLNSubTLV = new IGPRouterIDNodeDescriptorSubTLV();
				igpRouterIDLNSubTLV.setIpv4AddressOSPF(nodeIPAddress);
				igpRouterIDLNSubTLV.setIGP_router_id_type(IGPRouterIDNodeDescriptorSubTLV.IGP_ROUTER_ID_TYPE_OSPF_NON_PSEUDO);

				/* Create global node descriptor */
				LocalNodeDescriptorsTLV nodeDescriptor = new LocalNodeDescriptorsTLV();

				nodeDescriptor.setIGPRouterID(igpRouterIDLNSubTLV);

				/* Create node NRLI */
				NodeNLRI nlri = new NodeNLRI();
				nlri.setProtocolID(ProtocolIDCodes.Direct_Protocol_ID); /* Direct (see draft-ietf-idr-ls-distribution-10) */
				nlri.setLocalNodeDescriptors(nodeDescriptor);
				nlri.setRoutingUniverseIdentifier(netPlan.getNodeAttribute(nodeId, "type").equals("ipRouter") ? RoutingUniverseIdentifierTypes.Level3Identifier : RoutingUniverseIdentifierTypes.Level1Identifier);

				/* Create path attributes (otherwise, PCE will not be able to decode the message (error?) */
				OriginAttribute or = new OriginAttribute();
				or.setValue(PathAttributesTypeCode.PATH_ATTRIBUTE_ORIGIN_IGP);
				ArrayList<PathAttribute> pathAttributes = new ArrayList<PathAttribute>();
				pathAttributes.add(or);
				MP_Reach_Attribute mpReachAttribute = new Generic_MP_Reach_Attribute();
//				BGP_LS_MP_Reach_Attribute mpReachAttribute = new BGP_LS_MP_Reach_Attribute();
//				mpReachAttribute.setLsNLRI(nlri);
				pathAttributes.add(mpReachAttribute);

				/* Create BGP update message */
				BGP4Update updateMsg = new BGP4Update();
				updateMsg.setNlri(nlri);
				updateMsg.setPathAttributes(pathAttributes);
				updateMsg.encode();
				
				topologyMessages.add(updateMsg);
			}
			catch(Throwable e)
			{
				e.printStackTrace(); //DEBUG
				throw new RuntimeException(e);
			}
		}

		Set<Long> layerIds = netPlan.getLayerIds();
		for(long layerId : layerIds)
		{
			try
			{
				Set<Long> linkIds = netPlan.getLinkIds(layerId);
				for(long linkId : linkIds)
					topologyMessages.add(createLinkMessage(netPlan, layerId, linkId, true));
			}
			catch(Throwable e)
			{
				throw new RuntimeException(e);
			}
		}

		for(BGP4Update updateMsg : topologyMessages)
		{
			Utils.writeMessage(os, updateMsg.getBytes());
		}
	}
	
	private void shutdown()
	{
		txt_ip.setEnabled(true);
		connectToPCE.setEnabled(true);
		connectToPCE.setBackground(Color.RED);
		disconnectFromPCE.setEnabled(false);

		if (pcepThread != null)
		{
			try
			{
				PCEPClose pcepClose = new PCEPClose();
				pcepClose.setReason(1);
				pcepClose.encode();
				Utils.writeMessage(pcepSocket.getOutputStream(), pcepClose.getBytes());
			}
			catch(Throwable e)
			{
				try { pcepSocket.close(); }
				catch(Throwable e1) { }
				
				pcepSocket = null;
				pcepThread = null;
			}
		}

		if (bgplsThread != null)
		{
			try { bgplsSocket.close(); }
			catch(Throwable e1) { }
				
			bgplsSocket = null;
			bgplsThread = null;
		}
	}

	private void startBGPLS()
	{
		bgplsThread = new Thread(new BGPThread());
		bgplsThread.setDaemon(true);
		bgplsThread.start();
	}
	
	private void startPCEP()
	{
		pcepThread = new Thread(new PCEPThread());
		pcepThread.setDaemon(true);
		pcepThread.start();
	}
	
	private void updateOperationLog(String message)
	{
		log.append(message);
		log.append(NEW_LINE);
	}

	private class BGPThread implements Runnable
	{
		@Override
		public void run()
		{
			try
			{
				/* Start socket */
				InetAddress pceIPAddress = Inet4Address.getByName(txt_ip.getText());
				updateOperationLog("Connecting to BGP/LS in " + pceIPAddress);
				bgplsSocket = new Socket(pceIPAddress, es.upct.girtel.net2plan.plugins.abnopce.utils.Constants.BGP_SERVER_PORT);
				InputStream inBGP = bgplsSocket.getInputStream();
				OutputStream outBGP = bgplsSocket.getOutputStream();
				
				/* Sending BGP/LS OPEN message */
				BGP4Open bgpopenmessage = new BGP4Open();
				bgpopenmessage.setBGPIdentifier((Inet4Address) bgplsSocket.getLocalAddress()); //DO NOT FORGET TO SET THE IDENTIFIER!

				/* Add Link-State NLRI capability advertisement to BGP OPEN message */
				LinkedList<BGP4OptionalParameter> bgpOpenParameterList = new LinkedList<BGP4OptionalParameter>();
				MultiprotocolExtensionCapabilityAdvertisement linkStateNLRICapabilityAdvertisement = new MultiprotocolExtensionCapabilityAdvertisement();
				linkStateNLRICapabilityAdvertisement.setAFI(AFICodes.AFI_BGP_LS);
				linkStateNLRICapabilityAdvertisement.setSAFI(SAFICodes.SAFI_BGP_LS);
				BGP4CapabilitiesOptionalParameter linkStateNLRICapabilityParameter = new BGP4CapabilitiesOptionalParameter();
				LinkedList<BGP4Capability> capabilityList = new LinkedList<BGP4Capability>();
				capabilityList.add(linkStateNLRICapabilityAdvertisement);
				linkStateNLRICapabilityParameter.setCapabilityList(capabilityList);
				bgpOpenParameterList.add(linkStateNLRICapabilityParameter);
				bgpopenmessage.setParametersList(bgpOpenParameterList);

				/* Encode and send BGP open message */
				bgpopenmessage.encode();
				Utils.writeMessage(outBGP, bgpopenmessage.getBytes());
				updateOperationLog(">> BGP4Open() sent");
				
				boolean sessionOpened = false;
				byte[] msg;
				while((msg = Utils.readBGP4Msg(inBGP)) != null)
				{
					int msgType = BGP4Message.getMessageType(msg);
					switch(msgType)
					{
						case BGP4MessageTypes.MESSAGE_OPEN:
							if (sessionOpened)
							{
								/* If session was opened already, then close */
								shutdown();
							}
							else
							{
								updateOperationLog(">> BGP4Open() received");
								
								/* Sending BGP4 keepalive message */
								BGP4Keepalive keekaliveMsg = new BGP4Keepalive();
								keekaliveMsg.encode();
								Utils.writeMessage(outBGP, keekaliveMsg.getBytes());
								updateOperationLog(">> BGP4Keepalive() sent");
								
								sessionOpened = true;
								
								/* Start BGP/LS session */
								updateOperationLog("Sendind Network State...");
								sendNetworkState(outBGP);
							}
							
							break;							

						case BGP4MessageTypes.MESSAGE_KEEPALIVE:
							updateOperationLog(">> BGP4Keepalive() received");
							break;
							
						default:
							/* No other message can be received */
							shutdown();
							break;
					}
				}
			}
			catch (Throwable e)
			{
				e.printStackTrace();
				System.err.println("Caught exception " + e.toString());
				shutdown();
			}
		}
	}
	
	private class PCEPThread implements Runnable
	{
		@Override
		public void run()
		{
			try
			{
				/* Start socket */
				InetAddress pceIPAddress = Inet4Address.getByName(txt_ip.getText());
				updateOperationLog("Connecting to PCE in " + pceIPAddress);
				pcepSocket = new Socket(pceIPAddress, es.upct.girtel.net2plan.plugins.abnopce.utils.Constants.PCEP_SERVER_PORT);
				InputStream inPCEP = pcepSocket.getInputStream();
				OutputStream outPCEP = pcepSocket.getOutputStream();
				
				/* Sending PCEP OPEN message */
				PCEPOpen openMsg = new PCEPOpen();
				openMsg.encode();
				Utils.writeMessage(outPCEP, openMsg.getBytes());
				updateOperationLog(">> PCEPOpen() sent");
				
				boolean sessionOpened = false;
				byte[] msg;
				while((msg = Utils.readPCEPMsg(inPCEP)) != null)
				{
					int msgType = PCEPMessage.getMessageType(msg);
					
					NetPlan netPlan = getDesign();
					switch(msgType)
					{
						case PCEPMessageTypes.MESSAGE_OPEN:
							if (sessionOpened)
							{
								/* If session was opened already, then close */
								PCEPClose pcepClose = new PCEPClose();
								pcepClose.setReason(1);
								pcepClose.encode();
								Utils.writeMessage(pcepSocket.getOutputStream(), pcepClose.getBytes());
								updateOperationLog(">> Unexpected PCEPOpen() received, PCEPClose() sent");
							}
							else
							{
								updateOperationLog(">> PCEPOpen() received");
								
								/* Sending PCEP keepalive message */
								PCEPKeepalive keepalive = new PCEPKeepalive();
								keepalive.encode();
								Utils.writeMessage(outPCEP, keepalive.getBytes());
								updateOperationLog(">> PCEPKeepalive() sent");
								
								sessionOpened = true;
								
								/* Start BGP/LS session */
								startBGPLS();
							}
							
							break;							

						case PCEPMessageTypes.MESSAGE_KEEPALIVE:
							updateOperationLog(">> PCEPKeepalive() received");
							break;
							
						case PCEPMessageTypes.MESSAGE_UPDATE:
							PCEPUpdate updateMsg = new PCEPUpdate(msg);
							
							Iterator<Long> routeIt = netPlan.getRouteIds(0).iterator();
							List<UpdateRequest> list = updateMsg.getUpdateRequestList();
							
							updateOperationLog(updateMsg.toString());
							
							for(UpdateRequest updateRequest : list)
							{
								long routeId_lowerLayer = routeIt.next();
								
								Path path1 = updateRequest.getPath();
								List<Long> seqFibers = new LinkedList<Long>();
								List<Integer> seqWavelengths = new LinkedList<Integer>();
								
								updateOperationLog(path1.toString());
								
								ExplicitRouteObject ero1 = path1.geteRO();
								for(EROSubobject eroSubobject1 : ero1.getEROSubobjectList())
								{
									if (eroSubobject1 instanceof UnnumberIfIDEROSubobject)
									{
										UnnumberIfIDEROSubobject eroInfo = (UnnumberIfIDEROSubobject) eroSubobject1;
										long nodeId = Utils.getNodeByIPAddress(netPlan, eroInfo.getRouterID());
										if (nodeId == netPlan.getRouteEgressNode(0, routeId_lowerLayer)) break;
										
										long interfaceId = eroInfo.getInterfaceID();
										long fiberId = Utils.getLinkBySourceInterface(netPlan, 0, nodeId, interfaceId);
										seqFibers.add(fiberId);
									}
									else if (eroSubobject1 instanceof GeneralizedLabelEROSubobject)
									{
										DWDMWavelengthLabel wavelengthLabel = ((GeneralizedLabelEROSubobject) eroSubobject1).getDwdmWavelengthLabel();
										int wavelengthId = wavelengthLabel.getN();
										
										seqWavelengths.add(wavelengthId);
									}
								}

								/* Map of original sequence of fibers */
								Set<Long> originalSeqFIbers = new LinkedHashSet<Long>(netPlan.getRouteSequenceOfLinks(0, routeId_lowerLayer));
								routeOriginalLinks.put(routeId_lowerLayer, originalSeqFIbers);

								netPlan.setRouteSequenceOfLinks(0, routeId_lowerLayer, seqFibers);
								WDMUtils.setLightpathSeqWavelengths(netPlan, 0, routeId_lowerLayer, IntUtils.toArray(seqWavelengths));
								netPlan.setRouteUp(0, routeId_lowerLayer);
								
								PCEPReport report = new PCEPReport();
								report.setStateReportList(new LinkedList<StateReport>());
								StateReport stateReport = new StateReport();
								stateReport.setLSP(updateRequest.getLsp());
								stateReport.setPath(updateRequest.getPath());
								report.addStateReport(stateReport);
								report.encode();
								Utils.writeMessage(outPCEP, report.getBytes());
							}
							
							updateNetPlanView();
							
//							PCEPUpdate update = new PCEPUpdate();
//							update.setUpdateRequestList(new LinkedList<UpdateRequest>());
//							
//							UpdateRequest updateRequest1 = new UpdateRequest();
//							update.addStateReport(updateRequest1);
//							
//							LSP lsp = new LSP();
//							lsp.setLspId(0);
//							lsp.setaFlag(true);
//							updateRequest1.setLSP(lsp);
//							
//							Path path = new Path();
//							ExplicitRouteObject ero = new ExplicitRouteObject();
//							path.seteRO(ero);
//							updateRequest1.setPath(path);
							
							break;
							
						case PCEPMessageTypes.MESSAGE_PCREP:
							PCEPResponse responseMsg = new PCEPResponse(msg);
							
							for(Response response : responseMsg.getResponseList())
							{
								updateOperationLog("<<PCEP REP received: "+response.toString());
								
								if (response.getNoPath() != null) continue;
								
								long demandId = netPlan.getDemandByAttribute(1, "requestId", Long.toString(response.getRequestParameters().getRequestID()));
								long egressNodeId = netPlan.getDemandEgressNode(1, demandId);
								
								Path path1 = response.getPath(0);
								float bandwidthInGbps = ((BandwidthRequested) response.getBandwidth()).getBw() * 8f / 1E9f;
								
								List<Long> seqLinks = new LinkedList<Long>();
								List<Long> seqFibers = new LinkedList<Long>();
								List<Integer> seqWavelengths = new LinkedList<Integer>();
								
								ExplicitRouteObject ero1 = path1.geteRO();
								for(EROSubobject eroSubobject1 : ero1.getEROSubobjectList())
								{
									if (eroSubobject1 instanceof IPv4prefixEROSubobject)
									{
										if (!seqFibers.isEmpty()) //!
										{
											long originNodeId = netPlan.getLinkOriginNode(0, seqFibers.get(0));
											long destinationNodeId = netPlan.getLinkDestinationNode(0, seqFibers.get(seqFibers.size() - 1));
											long demandId_lowerLayer = netPlan.addDemand(0, originNodeId, destinationNodeId, 40, null);
											long routeId_lowerLayer = netPlan.addRoute(0, demandId_lowerLayer, 40, 1, seqFibers, null); //FIXME Parametize this
											WDMUtils.setLightpathSeqWavelengths(netPlan, 0, routeId_lowerLayer, IntUtils.toArray(seqWavelengths));

											long linkId = netPlan.createUpperLayerLinkFromDemand(0, demandId_lowerLayer, 1);
											seqLinks.add(linkId);
										}
										
										Inet4Address ipAddress = ((IPv4prefixEROSubobject) eroSubobject1).getIpv4address();
										long nodeId = Utils.getNodeByIPAddress(netPlan, ipAddress);
										if (nodeId == egressNodeId)
										{
											seqLinks.add(Utils.getLinkByDestinationIPAddress(netPlan, 1, ipAddress));
											break;
										}
										else
										{
											seqLinks.add(Utils.getLinkBySourceIPAddress(netPlan, 1, ipAddress));
										}
									}
									else if (eroSubobject1 instanceof UnnumberIfIDEROSubobject)
									{
										UnnumberIfIDEROSubobject eroInfo = (UnnumberIfIDEROSubobject) eroSubobject1;
										long nodeId = Utils.getNodeByIPAddress(netPlan, eroInfo.getRouterID());
										long interfaceId = eroInfo.getInterfaceID();
										long fiberId = Utils.getLinkBySourceInterface(netPlan, 0, nodeId, interfaceId);
										seqFibers.add(fiberId);
									}
									else if (eroSubobject1 instanceof GeneralizedLabelEROSubobject)
									{
										DWDMWavelengthLabel wavelengthLabel = ((GeneralizedLabelEROSubobject) eroSubobject1).getDwdmWavelengthLabel();
										int wavelengthId = wavelengthLabel.getN();
										
										seqWavelengths.add(wavelengthId);
									}
								}

								netPlan.addRoute(1, demandId, bandwidthInGbps, seqLinks, null); //FIXME error al añadir dos demandas seguidas con mismo origen y destino
	
								PCEPReport report1 = new PCEPReport();
								report1.setStateReportList(new LinkedList<StateReport>());
								
								LSP lsp1 = new LSP();
								lsp1.setDFlag(true);
								lsp1.setLspId(0);
								StateReport stateReport = new StateReport();
								stateReport.setLSP(lsp1);
								stateReport.setPath(response.getPath(0));
								report1.addStateReport(stateReport);

								report1.encode();
								Utils.writeMessage(outPCEP, report1.getBytes());
								
								updateNetPlanView();
							}
							
							break;
							
						case PCEPMessageTypes.MESSAGE_REPORT:
							/* ToDo */
							break;
							
						default:
							break;
					}
				}
			}
			catch (Throwable e)
			{
				e.printStackTrace();
				System.err.println("Caught exception " + e.toString());
				shutdown();
			}
		}
	}

	@Override
	public void showRoute(long layerId, long routeId)
	{
		System.out.println("Showing route!"); //DEBUG
		selectNetPlanViewItem(layerId, Constants.NetworkElementType.ROUTE, routeId);

		NetPlan currentState = getDesign();
		NetPlan initialState = getInitialDesign();
		Set<Long> pri_seqLinks = new LinkedHashSet<Long>(currentState.convertSequenceOfLinksAndProtectionSegmentsToSequenceOfLinks(currentState.getRouteSequenceOfLinks(routeId)));
		Set<Long> sec_seqLinks = new LinkedHashSet<Long>();

		if(routeOriginalLinks.get(routeId) != null)
			sec_seqLinks = routeOriginalLinks.get(routeId);

		if (allowShowInitialNetPlan())
		{
			if (initialState.isLayerActive(layerId) && initialState.isRouteActive(layerId, routeId))
				sec_seqLinks = new LinkedHashSet<Long>(initialState.convertSequenceOfLinksAndProtectionSegmentsToSequenceOfLinks(layerId, initialState.getRouteSequenceOfLinks(layerId, routeId)));

			Iterator<Long> linkIt = sec_seqLinks.iterator();
			while(linkIt.hasNext())
			{
				long linkId = linkIt.next();
				if (!currentState.isLinkActive(linkId)) linkIt.remove();
			}
		}

		topologyPanel.getCanvas().showRoutes(pri_seqLinks, sec_seqLinks);
		topologyPanel.getCanvas().refresh();
	}
}