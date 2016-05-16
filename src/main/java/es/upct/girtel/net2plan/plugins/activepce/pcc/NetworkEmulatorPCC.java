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

package es.upct.girtel.net2plan.plugins.activepce.pcc;

import com.net2plan.gui.GUINet2Plan;
import com.net2plan.gui.tools.IGUINetworkViewer;
import com.net2plan.gui.utils.*;
import com.net2plan.interfaces.networkDesign.*;
import com.net2plan.internal.Constants;
import com.net2plan.internal.ErrorHandling;
import com.net2plan.internal.plugins.IGUIModule;
import com.net2plan.internal.plugins.PluginSystem;
import com.net2plan.libraries.WDMUtils;
import com.net2plan.utils.IntUtils;
import com.net2plan.utils.Pair;
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
import es.upct.girtel.net2plan.plugins.activepce.utils.Utils;
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
import java.net.UnknownHostException;
import java.util.*;
import java.util.List;

import static es.upct.girtel.net2plan.plugins.activepce.utils.Utils.getLinkSourceInterface;

public class NetworkEmulatorPCC extends IGUINetworkViewer implements ActionListener, ItemListener
{
	private final static String NEW_LINE = StringUtils.getLineSeparator();
	private final static String title    = "Network Emulator and PCEP/BGP-LS Client";
	private JTextArea  log;
	private JButton    _clearButton;
	private JTextField _txt_ip, _txt_bandwidth, _txt_maxBifurcation, _txt_minBandwidthPerPath;
	private JComboBox _sourceNode, _destinationNode, _fiberFailureSelector;
	private JButton _buttonGotoServices, _buttonMakePCERequest, _buttonConnectToPCE, _buttonDisconnectFromPCE, _buttonFailFiber, _buttonViewFiber;
	private JCheckBox _loadBalancingEnabled;
	private JPanel    _loadBalancingPanel;
	private Thread    _pcepThread, _bgplsThread;
	private Socket _pcepSocket, _bgplsSocket;
	private NetworkLayer _wdmLayer, _ipLayer;
	private Map<Long, Set<Long>> _routeOriginalLinks;

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
		if(src == _clearButton)
		{
			log.setText("");
		} else if(src == _buttonGotoServices)
		{
			selectNetPlanViewItem(Constants.NetworkElementType.DEMAND, null);
		} else if(src == _buttonMakePCERequest)
		{
			performPCERequest();
		} else if(src == _buttonConnectToPCE)
		{
			connect();
		} else if(src == _buttonDisconnectFromPCE)
		{
			shutdown();
		}
	}

	@Override
	public void configure(JPanel contentPane)
	{
		_routeOriginalLinks = new HashMap<>();

		_txt_ip = new JTextField(getCurrentOptions().get("pce.defaultIP"));

		_sourceNode = new WiderJComboBox();
		_destinationNode = new WiderJComboBox();
		_txt_bandwidth = new JTextField();
		_loadBalancingEnabled = new JCheckBox("Load-balancing");
		_loadBalancingEnabled.addItemListener(this);
		_loadBalancingPanel = new JPanel(new MigLayout("", "[][grow]", "[][]"));
		_buttonGotoServices = new JButton("Go to existing services");
		_txt_maxBifurcation = new JTextField();
		_txt_minBandwidthPerPath = new JTextField();
		_buttonMakePCERequest = new JButton("Make request");
		_buttonConnectToPCE = new JButton("Connect");
		_buttonConnectToPCE.setBackground(Color.RED);
		_buttonConnectToPCE.setContentAreaFilled(false);
		_buttonConnectToPCE.setOpaque(true);
		_buttonDisconnectFromPCE = new JButton("Disconnect");
		_buttonDisconnectFromPCE.setEnabled(false);
		_buttonFailFiber = new JButton("Simulate failure");
		_buttonViewFiber = new JButton("View fiber"); _buttonViewFiber.setEnabled(false);

		super.configure(contentPane);

		JPanel controllerTab = new JPanel();
		controllerTab.setLayout(new MigLayout("fill, insets 0 0 0 0", "[grow]", "[][][grow]"));
		controllerTab.setBorder(new LineBorder(Color.BLACK));

		JPanel connectionHandler = new JPanel(new MigLayout("insets 0 0 0 0", "[][grow]", "[][]"));
		connectionHandler.setBorder(BorderFactory.createTitledBorder(new LineBorder(Color.BLACK), "PCE connection controller"));
		connectionHandler.add(new JLabel("PCE IP"));
		connectionHandler.add(_txt_ip, "growx, wrap");

		JPanel connectionHandlerButton = new JPanel(new FlowLayout());
		connectionHandlerButton.add(_buttonConnectToPCE);
		connectionHandlerButton.add(_buttonDisconnectFromPCE);
		connectionHandler.add(connectionHandlerButton, "center, spanx 2, wrap");
		_buttonConnectToPCE.addActionListener(this);
		_buttonDisconnectFromPCE.addActionListener(this);

		JPanel serviceProvisioning = new JPanel();
		serviceProvisioning.setLayout(new MigLayout("insets 0 0 0 0", "[][grow]", "[][][][][grow]"));
		serviceProvisioning.setBorder(BorderFactory.createTitledBorder(new LineBorder(Color.BLACK), "Service provisioning"));

		serviceProvisioning.add(new JLabel("Source node"));
		serviceProvisioning.add(_sourceNode, "growx, wrap");
		serviceProvisioning.add(new JLabel("Destination node"));
		serviceProvisioning.add(_destinationNode, "growx, wrap");
		serviceProvisioning.add(new JLabel("Requested bandwidth"));
		serviceProvisioning.add(_txt_bandwidth, "growx, wrap");
		serviceProvisioning.add(new JLabel("Additional constraints"), "top");

		JPanel additionalConstraints = new JPanel(new MigLayout("fill, insets 0 0 0 0", "[grow]", "[][]"));

		_loadBalancingPanel.add(new JLabel("Maximum number of paths"));
		_loadBalancingPanel.add(_txt_maxBifurcation, "growx, wrap");
		_loadBalancingPanel.add(new JLabel("Minimum bandwidth per path"));
		_loadBalancingPanel.add(_txt_minBandwidthPerPath, "growx, wrap");

		additionalConstraints.add(_loadBalancingEnabled, "growx, wrap");
		additionalConstraints.add(_loadBalancingPanel, "growx, wrap");
		serviceProvisioning.add(additionalConstraints, "growx, wrap");
		serviceProvisioning.add(_buttonMakePCERequest, "center, spanx 2, wrap");
		_buttonMakePCERequest.addActionListener(this);

		_buttonGotoServices.addActionListener(this);
		serviceProvisioning.add(_buttonGotoServices, "dock south");

		JPanel failureReparationSimulator = new JPanel();
		_fiberFailureSelector = new WiderJComboBox();
		failureReparationSimulator.setLayout(new MigLayout("insets 0 0 0 0", "[][grow]", "[][grow]"));
		failureReparationSimulator.setBorder(BorderFactory.createTitledBorder(new LineBorder(Color.BLACK), "Fiber failure/reparation simulator"));
		failureReparationSimulator.add(new JLabel("Select fiber to fail"));
		failureReparationSimulator.add(_fiberFailureSelector, "growx, wrap");

		JPanel failureSimulatorButtonPanel = new JPanel(new FlowLayout());
		failureSimulatorButtonPanel.add(_buttonFailFiber);
		failureReparationSimulator.add(failureSimulatorButtonPanel, "center, spanx 2, wrap");

		final DefaultTableModel model = new ClassAwareTableModel(new Object[1][6], new String[]{"Id", "Origin node", "Destination node", ""})
		{
			@Override
			public boolean isCellEditable(int rowIndex, int columnIndex)
			{
				return columnIndex == 3;
			}
		};

		final JTable table = new AdvancedJTable(model);
		table.setEnabled(false);

		_buttonFailFiber.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				int linkIndex = (int) ((StringLabeller) _fiberFailureSelector.getSelectedItem()).getObject();
				NetPlan netPlan = getDesign();

				Link link = netPlan.getLink(linkIndex, _wdmLayer);
				link.setFailureState(false);
				if(netPlan.getNetworkLayerDefault().getIndex() == 0) getTopologyPanel().getCanvas().showLink(link, Color.RED, false);

				updateOperationLog("Simulating failure");

				try
				{
					if(_bgplsSocket == null) throw new Net2PlanException("PCC not connected to PCE");
					BGP4Update updateMessage = createLinkMessage(netPlan, netPlan.getLink(linkIndex, _wdmLayer).getId(), false);

					OutputStream outBGP = _bgplsSocket.getOutputStream();
					Utils.writeMessage(outBGP, updateMessage.getBytes());
				}catch(Throwable ex)
				{
					ErrorHandling.addErrorOrException(ex, NetworkEmulatorPCC.class);
					ErrorHandling.showErrorDialog(ex.getMessage(), "Unable to simulate failure");
					return;
				}

				if(_fiberFailureSelector.getItemCount() == 0) _buttonFailFiber.setVisible(false);

				if(! table.isEnabled()) model.removeRow(0);
				model.addRow(new Object[]{linkIndex, link.getOriginNode().getIndex(), link.getDestinationNode().getIndex(), "Repair"});

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

					int linkIndex = (int) table.getModel().getValueAt(modelRow, 0);
					NetPlan netPlan = getDesign();

					try
					{
						if(_bgplsSocket == null) throw new Net2PlanException("PCC not connected to PCE");
						BGP4Update updateMessage = createLinkMessage(netPlan, netPlan.getLink(linkIndex, _wdmLayer).getId(), true);

						OutputStream outBGP = _bgplsSocket.getOutputStream();
						Utils.writeMessage(outBGP, updateMessage.getBytes());
					}catch(Throwable ex)
					{
						ErrorHandling.addErrorOrException(ex, NetworkEmulatorPCC.class);
						ErrorHandling.showErrorDialog(ex.getMessage(), "Unable to simulate reparation");
						return;
					}

					Link link = netPlan.getLink(linkIndex, _wdmLayer);
					link.setFailureState(true);
					if(netPlan.getNetworkLayerDefault().getIndex() == 0) getTopologyPanel().getCanvas().showLink(link, Color.BLACK, false);

					updateNetPlanView();

					Node originNode = link.getOriginNode();
					Node destinationNode = link.getDestinationNode();
					_fiberFailureSelector.addItem(StringLabeller.unmodifiableOf(linkIndex, "e" + linkIndex + " [n" + originNode.getIndex() + " (" + originNode.getName() + ") -> n" + destinationNode
							.getIndex() + " (" + destinationNode.getName() + ")]"));
					_buttonFailFiber.setVisible(true);

					((DefaultTableModel) table.getModel()).removeRow(modelRow);

					table.setEnabled(true);

					if(table.getModel().getRowCount() == 0)
					{
						((DefaultTableModel) table.getModel()).addRow(new Object[6]);
						table.setEnabled(false);
					}
				}catch(Throwable e1)
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
		_clearButton = new JButton("Clear");
		_clearButton.addActionListener(this);
		JToolBar toolbar = new JToolBar();
		toolbar.add(_clearButton);
		toolbar.setFloatable(false);
		toolbar.setRollover(true);

		log = new JTextArea();
		log.setFont(new JLabel().getFont());
		DefaultCaret caret = (DefaultCaret) log.getCaret();
		caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

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

		List<Triple<String, String, String>> parameters = new LinkedList<>();
		String ip = "127.0.0.1";
		//try{ip = Inet4Address.getLocalHost().getHostAddress();}catch(Throwable e){ ip = "127.0.0.1";}
		parameters.add(Triple.of("pce.defaultIP", ip, "Default IP address of the machine where PCE is running"));

		return parameters;
	}

	@Override
	public void itemStateChanged(ItemEvent e)
	{
		Object src = e.getSource();
		if(src == _loadBalancingEnabled)
			SwingUtils.setEnabled(_loadBalancingPanel, ((JCheckBox) _loadBalancingEnabled).isSelected());
	}

	@Override
	public void loadDesign(NetPlan netPlan)
	{
		super.loadDesign(netPlan);

		if(netPlan.hasNodes() && netPlan.getNumberOfLayers() == 2)
		{
			updateOperationLog("Design loaded");
			if(netPlan.getNetworkLayers().size() != 2) throw new Net2PlanException("Design should have 2 layers");

			_wdmLayer = netPlan.getNetworkLayer(0);
			_ipLayer = netPlan.getNetworkLayer(1);

			List<Node> nodes = netPlan.getNodes();
			_sourceNode.removeAllItems();
			_destinationNode.removeAllItems();

			WDMUtils.setFibersNumWavelengths(netPlan, es.upct.girtel.net2plan.plugins.activepce.utils.Constants.W, _wdmLayer);

			for(Node node : nodes)
			{
				String ipAddress = Utils.getNodeIPAddress(netPlan, node.getId()).getHostAddress(); //FIXME may throw NullPointerException (also several methods below)
				if(ipAddress != null && node.getAttribute(es.upct.girtel.net2plan.plugins.activepce.utils.Constants.ATTRIBUTE_NODE_TYPE).equals(es.upct.girtel.net2plan.plugins.activepce.utils
						.Constants.NODE_TYPE_ROADM)) continue;

				_sourceNode.addItem(StringLabeller.unmodifiableOf(node.getIndex(), ipAddress + " [n" + node.getIndex() + ", " + node.getName() + "]"));
				_destinationNode.addItem(StringLabeller.unmodifiableOf(node.getIndex(), ipAddress + " [n" + node.getIndex() + ", " + node.getName() + "]"));
			}

			_fiberFailureSelector.removeAllItems();
			for(Link link : netPlan.getLinks(_wdmLayer))
			{
				Node originNode = link.getOriginNode();
				Node destinationNode = link.getDestinationNode();

				String sourceNodeType = originNode.getAttribute(es.upct.girtel.net2plan.plugins.activepce.utils.Constants.ATTRIBUTE_NODE_TYPE);
				String destinationNodeType = destinationNode.getAttribute(es.upct.girtel.net2plan.plugins.activepce.utils.Constants.ATTRIBUTE_NODE_TYPE);

				if(sourceNodeType.equals(destinationNodeType) && sourceNodeType.equals("roadm"))
					_fiberFailureSelector.addItem(StringLabeller.unmodifiableOf(link.getIndex(), "e" + link.getIndex() + " [n" + originNode.getIndex() + " (" + originNode.getName() + ") -> n" +
							destinationNode.getIndex() + " (" + destinationNode.getName() + ")]"));
			}
		}
	}

	private void connect()
	{
		_txt_ip.setEnabled(false);
		_buttonConnectToPCE.setEnabled(false);
		_buttonConnectToPCE.setBackground(Color.GREEN);
		_buttonDisconnectFromPCE.setEnabled(true);

		startPCEP();
	}

	private BGP4Update createLinkMessage(NetPlan netPlan, long linkId, boolean isUp)
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
			igpRouterIDLNSubTLV.setIpv4AddressOSPF(Utils.getLinkSourceIPAddress(netPlan, linkId));
			igpRouterIDLNSubTLV.setIGP_router_id_type(IGPRouterIDNodeDescriptorSubTLV.IGP_ROUTER_ID_TYPE_OSPF_NON_PSEUDO);
			localNodeDescriptors.setIGPRouterID(igpRouterIDLNSubTLV);

			nlri.setLocalNodeDescriptors(localNodeDescriptors);

			/* Destination node */
			RemoteNodeDescriptorsTLV remoteNodeDescriptors = new RemoteNodeDescriptorsTLV();
			IGPRouterIDNodeDescriptorSubTLV igpRouterIDDNSubTLV = new IGPRouterIDNodeDescriptorSubTLV();
			igpRouterIDDNSubTLV.setIpv4AddressOSPF(Utils.getLinkDestinationIPAddress(netPlan, linkId));
			igpRouterIDDNSubTLV.setIGP_router_id_type(IGPRouterIDNodeDescriptorSubTLV.IGP_ROUTER_ID_TYPE_OSPF_NON_PSEUDO);
			remoteNodeDescriptors.setIGPRouterID(igpRouterIDDNSubTLV);

			nlri.setRemoteNodeDescriptorsTLV(remoteNodeDescriptors);

			/* Interface IP addresses */
			IPv4InterfaceAddressLinkDescriptorsSubTLV ipv4InterfaceAddressTLV = new IPv4InterfaceAddressLinkDescriptorsSubTLV();
			ipv4InterfaceAddressTLV.setIpv4Address(Utils.getLinkSourceIPAddress(netPlan, linkId));
			nlri.setIpv4InterfaceAddressTLV(ipv4InterfaceAddressTLV);
			IPv4NeighborAddressLinkDescriptorSubTLV ipv4NeighborAddressTLV = new IPv4NeighborAddressLinkDescriptorSubTLV();
			ipv4NeighborAddressTLV.setIpv4Address(Utils.getLinkDestinationIPAddress(netPlan, linkId));
			nlri.setIpv4NeighborAddressTLV(ipv4NeighborAddressTLV);

			/* Interface identifiers */
			String sourceNodeType = netPlan.getLinkFromId(linkId).getOriginNode().getAttribute(es.upct.girtel.net2plan.plugins.activepce.utils.Constants.ATTRIBUTE_NODE_TYPE);
			String destinationNodeType = netPlan.getLinkFromId(linkId).getDestinationNode().getAttribute(es.upct.girtel.net2plan.plugins.activepce.utils.Constants.ATTRIBUTE_NODE_TYPE);
			if(sourceNodeType.equals(destinationNodeType)) // That is, both nodes are ROADM
			{
				LinkLocalRemoteIdentifiersLinkDescriptorSubTLV linkIdentifiersTLV = new LinkLocalRemoteIdentifiersLinkDescriptorSubTLV();
				linkIdentifiersTLV.setLinkLocalIdentifier(getLinkSourceInterface(netPlan, linkId));
				linkIdentifiersTLV.setLinkRemoteIdentifier(Utils.getLinkDestinationInterface(netPlan, linkId));
				nlri.setLinkIdentifiersTLV(linkIdentifiersTLV);
			}

			/* Create path attributes (otherwise, PCE will not be able to decode the message (error?) */
			OriginAttribute or = new OriginAttribute();
			or.setValue(PathAttributesTypeCode.PATH_ATTRIBUTE_ORIGIN_IGP);
			ArrayList<PathAttribute> pathAttributes = new ArrayList<PathAttribute>();
			pathAttributes.add(or);
			PathAttribute mpReachAttribute = isUp ? new Generic_MP_Reach_Attribute() : new Generic_MP_Unreach_Attribute();
			pathAttributes.add(mpReachAttribute);

			/* Create BGP update message */
			BGP4Update updateMsg = new BGP4Update();
			updateMsg.setNlri(nlri);
			updateMsg.setPathAttributes(pathAttributes);
			updateMsg.encode();

			return updateMsg;
		}catch(Throwable e)
		{
			throw new RuntimeException(e);
		}
	}

	private void performPCERequest()
	{
		NetPlan netPlan = getDesign();

		try
		{
			if(! (_sourceNode.getSelectedItem() instanceof StringLabeller)) throw new Net2PlanException("Bad - No source node was selected");
			if(! (_destinationNode.getSelectedItem() instanceof StringLabeller)) throw new Net2PlanException("Bad - No destination node was selected");

			int sourceNodeIndex = (int) ((StringLabeller) _sourceNode.getSelectedItem()).getObject();
			int destinationNodeIndex = (int) ((StringLabeller) _destinationNode.getSelectedItem()).getObject();
			if(sourceNodeIndex == destinationNodeIndex) throw new Net2PlanException("Bad - End nodes cannot be the same");

			Node sourceNode = netPlan.getNode(sourceNodeIndex);
			Node destinationNode = netPlan.getNode(destinationNodeIndex);

			float bandwidthInGbps;
			Map<String, String> attributeMap = new LinkedHashMap<String, String>();

			Request req = new Request();
			BandwidthRequested bw = new BandwidthRequested();
			try
			{
				String str_bandwidth = _txt_bandwidth.getText();
				bandwidthInGbps = Float.parseFloat(str_bandwidth);
				if(bandwidthInGbps <= 0) throw new RuntimeException();
				bw.setBw(bandwidthInGbps * 1E9f / 8f);

				req.setBandwidth(bw);
			}catch(Throwable e)
			{
				throw new Net2PlanException("Bad - Requested bandwidth must be greater or equal than zero");
			}

			EndPointsIPv4 endpoints = new EndPointsIPv4();
			endpoints.setSourceIP(Utils.getNodeIPAddress(netPlan, sourceNode.getId()));
			endpoints.setDestIP(Utils.getNodeIPAddress(netPlan, destinationNode.getId()));
			req.setEndPoints(endpoints);

			if(_loadBalancingEnabled.isSelected())
			{
				LoadBalancing loadBalancing = new LoadBalancing();

				try
				{
					String str_maxBifurcation = _txt_maxBifurcation.getText();
					int allowedBifurcationDegree = Integer.parseInt(str_maxBifurcation);
					if(allowedBifurcationDegree <= 0) throw new RuntimeException();

					attributeMap.put(es.upct.girtel.net2plan.plugins.activepce.utils.Constants.ATTRIBUTE_MAX_BIFURCATION, Integer.toString(allowedBifurcationDegree));
					loadBalancing.setMaxLSP(allowedBifurcationDegree);
				}catch(Throwable e)
				{
					throw new Net2PlanException("Bad - Maximum number of paths must be greater than zero");
				}

				try
				{
					String str_minBandwidthPerPath = _txt_minBandwidthPerPath.getText();
					float minBandwidthPerPathInGbps = Float.parseFloat(str_minBandwidthPerPath);
					if(minBandwidthPerPathInGbps <= 0) throw new RuntimeException();

					attributeMap.put(es.upct.girtel.net2plan.plugins.activepce.utils.Constants.ATTRIBUTE_MIN_BANDWIDTH, Float.toString(minBandwidthPerPathInGbps));
					loadBalancing.setMinBandwidth(minBandwidthPerPathInGbps * 1E9f / 8f);
				}catch(Throwable e)
				{
					throw new Net2PlanException("Bad - Minimum bandwidth per path must be greater or equal than zero");
				}

				req.setLoadBalancing(loadBalancing);
			}

			long requestId = Utils.getNewReqIDCounter();
			attributeMap.put(es.upct.girtel.net2plan.plugins.activepce.utils.Constants.ATTRIBUTE_REQUEST_ID, Long.toString(requestId));
			netPlan.addDemand(sourceNode, destinationNode, bandwidthInGbps, attributeMap, _ipLayer);
			updateNetPlanView();

			RequestParameters requestParameters = new RequestParameters();
			requestParameters.setRequestID(requestId);

			PCEPRequest pcepRequest = new PCEPRequest();
			pcepRequest.addRequest(req);
			req.setRequestParameters(requestParameters);
			pcepRequest.encode();
			Utils.writeMessage(_pcepSocket.getOutputStream(), pcepRequest.getBytes());

			updateOperationLog(">>PCEP REQ sent: " + pcepRequest.toString());
		}catch(Net2PlanException e)
		{
			if(ErrorHandling.isDebugEnabled()) ErrorHandling.addErrorOrException(e, NetworkEmulatorPCC.class);
			ErrorHandling.showErrorDialog(e.getMessage(), "Unable to make PCERequest");
		}catch(Throwable e)
		{
			ErrorHandling.addErrorOrException(e, NetworkEmulatorPCC.class);
			ErrorHandling.showErrorDialog("Unable to make PCERequest");
		}
	}

	private void sendNetworkState(OutputStream os)
	{
		List<BGP4Update> topologyMessages = new LinkedList<BGP4Update>();

		NetPlan netPlan = getDesign();
		List<Node> nodes = netPlan.getNodes();
		for(Node node : nodes)
		{
			try
			{
				/* Create node IP descriptor */
				Inet4Address nodeIPAddress = Utils.getNodeIPAddress(netPlan, node.getId());
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
				nlri.setRoutingUniverseIdentifier(node.getAttribute(es.upct.girtel.net2plan.plugins.activepce.utils.Constants.ATTRIBUTE_NODE_TYPE).equals(es.upct.girtel.net2plan.plugins.activepce
						.utils.Constants.NODE_TYPE_IPROUTER) ?
						RoutingUniverseIdentifierTypes.Level3Identifier :
						RoutingUniverseIdentifierTypes.Level1Identifier);

				/* Create path attributes (otherwise, PCE will not be able to decode the message (error?) */
				OriginAttribute or = new OriginAttribute();
				or.setValue(PathAttributesTypeCode.PATH_ATTRIBUTE_ORIGIN_IGP);
				ArrayList<PathAttribute> pathAttributes = new ArrayList<PathAttribute>();
				pathAttributes.add(or);
				PathAttribute reachAttribute = node.isUp() ? new Generic_MP_Reach_Attribute() : new Generic_MP_Unreach_Attribute();
				pathAttributes.add(reachAttribute);

				/* Create BGP update message */
				BGP4Update updateMsg = new BGP4Update();
				updateMsg.setNlri(nlri);
				updateMsg.setPathAttributes(pathAttributes);
				updateMsg.encode();

				topologyMessages.add(updateMsg);
			}catch(Throwable e)
			{
				e.printStackTrace();
				throw new RuntimeException(e);
			}
		}

		List<NetworkLayer> layers = netPlan.getNetworkLayers();

		for(NetworkLayer layer : layers)
		{
			try
			{
				for(Link link : netPlan.getLinks(layer))
					topologyMessages.add(createLinkMessage(netPlan, link.getId(), true));
			}catch(Throwable e)
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
		_txt_ip.setEnabled(true);
		_buttonConnectToPCE.setEnabled(true);
		_buttonConnectToPCE.setBackground(Color.RED);
		_buttonDisconnectFromPCE.setEnabled(false);

		if(_pcepThread != null)
		{
			try
			{
				PCEPClose pcepClose = new PCEPClose();
				pcepClose.setReason(1);
				pcepClose.encode();
				Utils.writeMessage(_pcepSocket.getOutputStream(), pcepClose.getBytes());
			}catch(Throwable e)
			{
				try{ _pcepSocket.close(); }catch(Throwable e1){ }

				_pcepSocket = null;
				_pcepThread = null;
			}
		}

		if(_bgplsThread != null)
		{
			try{ _bgplsSocket.close(); }catch(Throwable e1){ }

			_bgplsSocket = null;
			_bgplsThread = null;
		}
	}

	private void startBGPLS()
	{
		_bgplsThread = new Thread(new BGPThread());
		_bgplsThread.setDaemon(true);
		_bgplsThread.start();
	}

	private void startPCEP()
	{
		_pcepThread = new Thread(new PCEPThread());
		_pcepThread.setDaemon(true);
		_pcepThread.start();
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
				InetAddress pceIPAddress = Inet4Address.getByName(_txt_ip.getText());
				updateOperationLog("Connecting to BGP/LS in " + pceIPAddress);
				_bgplsSocket = new Socket(pceIPAddress, es.upct.girtel.net2plan.plugins.activepce.utils.Constants.BGP_SERVER_PORT);
				InputStream inBGP = _bgplsSocket.getInputStream();
				OutputStream outBGP = _bgplsSocket.getOutputStream();
				
				/* Sending BGP/LS OPEN message */
				BGP4Open bgpopenmessage = new BGP4Open();
				bgpopenmessage.setBGPIdentifier((Inet4Address) _bgplsSocket.getLocalAddress()); //DO NOT FORGET TO SET THE IDENTIFIER!

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
							if(sessionOpened)
							{
								/* If session was opened already, then close */
								shutdown();
							} else
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
			}catch(Throwable e)
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
				InetAddress pceIPAddress = Inet4Address.getByName(_txt_ip.getText());
				updateOperationLog("Connecting to PCE in " + pceIPAddress);
				_pcepSocket = new Socket(pceIPAddress, es.upct.girtel.net2plan.plugins.activepce.utils.Constants.PCEP_SERVER_PORT);
				InputStream inPCEP = _pcepSocket.getInputStream();
				OutputStream outPCEP = _pcepSocket.getOutputStream();
				
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
							if(sessionOpened)
							{
								/* If session was opened already, then close */
								PCEPClose pcepClose = new PCEPClose();
								pcepClose.setReason(1);
								pcepClose.encode();
								Utils.writeMessage(_pcepSocket.getOutputStream(), pcepClose.getBytes());
								updateOperationLog("<< Unexpected PCEPOpen() received, PCEPClose() sent");
							} else
							{
								updateOperationLog("<< PCEPOpen() received");
								
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
							updateOperationLog("<< PCEPKeepalive() received");
							break;

						case PCEPMessageTypes.MESSAGE_UPDATE:
							PCEPUpdate updateMsg = new PCEPUpdate(msg);
							updateOperationLog(">> PCEP UPDATE received ");
							System.out.println("PCEP Message received after operation log"); //DEBUG
							List<UpdateRequest> list = updateMsg.getUpdateRequestList();
							updateOperationLog(updateMsg.toString());

							for(UpdateRequest updateRequest : list)
							{
								int routeOriginalWavelength = updateRequest.getLsp().getLspId();
								long routeToRepair = - 1;

								Path path1 = updateRequest.getPath();
								List<Long> seqFibers = new LinkedList<>();
								List<Integer> seqWavelengths = new LinkedList<>();
								List<Node> seqRouteNodes;
								List<Node> seqRequestNodes;

								updateOperationLog(path1.toString());

								ExplicitRouteObject ero1 = path1.geteRO();
								for(Route affectedRoute : netPlan.getRoutesDown(_wdmLayer))
								{
									seqRouteNodes = affectedRoute.getSeqNodesRealPath();
									seqRequestNodes = new LinkedList<>();
									int[] affectedRouteSeqWavelengths = WDMUtils.getLightpathSeqWavelengths(affectedRoute);

									for(EROSubobject eroSubobject1 : ero1.getEROSubobjectList())
									{
										if(eroSubobject1 instanceof UnnumberIfIDEROSubobject)
										{
											UnnumberIfIDEROSubobject eroInfo = (UnnumberIfIDEROSubobject) eroSubobject1;
											long nodeId = Utils.getNodeByIPAddress(netPlan, eroInfo.getRouterID());
											seqRequestNodes.add(netPlan.getNodeFromId(nodeId));
											if(nodeId == seqRouteNodes.get(seqRouteNodes.size() - 1).getId()) break; //Last node

											long interfaceId = eroInfo.getInterfaceID();
											long fiberId = Utils.getLinkBySourceInterface(netPlan, _wdmLayer, nodeId, interfaceId);
											seqFibers.add(fiberId);
										} else if(eroSubobject1 instanceof GeneralizedLabelEROSubobject)
										{
											DWDMWavelengthLabel wavelengthLabel = ((GeneralizedLabelEROSubobject) eroSubobject1).getDwdmWavelengthLabel();
											int wavelengthId = wavelengthLabel.getN();
											seqWavelengths.add(wavelengthId);
										}
									}

									Node originalOrginNode = affectedRoute.getIngressNode();
									Node originalDestinaNode = affectedRoute.getEgressNode();
									Node requestedOriginNode = seqRequestNodes.get(0);
									Node requestedDestinationNode = seqRequestNodes.get(seqRequestNodes.size() - 1);
									if(originalOrginNode == requestedOriginNode && originalDestinaNode == requestedDestinationNode && seqWavelengths.get(0) == affectedRouteSeqWavelengths[0])
									{
										routeToRepair = affectedRoute.getId();
										break;
									}
								}
								if(routeToRepair == - 1) throw new Net2PlanException("Request to reapair a route that doesn't exist");

								/* Create the sequence of link fibers*/
								List<Link> seqLinkFibers = new LinkedList<>();
								for(long fiberId : seqFibers) seqLinkFibers.add(netPlan.getLinkFromId(fiberId));

								Route route = netPlan.getRouteFromId(routeToRepair);
								route.setSeqLinksAndProtectionSegments(seqLinkFibers);
								WDMUtils.setLightpathSeqWavelengths(route, IntUtils.toArray(seqWavelengths));

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

							break;

						case PCEPMessageTypes.MESSAGE_PCREP:
							PCEPResponse responseMsg = new PCEPResponse(msg);

							for(Response response : responseMsg.getResponseList())
							{
								updateOperationLog("<<PCEP REP received: " + response.toString());

								if(response.getNoPath() != null) continue;

								List<Demand> demands = netPlan.getDemands(_ipLayer);
								Demand ipDemand = null;
								for(Demand demand : demands)
								{
									if(demand.getAttribute(es.upct.girtel.net2plan.plugins.activepce.utils.Constants.ATTRIBUTE_REQUEST_ID) != null)
									{
										String requestId = demand.getAttribute(es.upct.girtel.net2plan.plugins.activepce.utils.Constants.ATTRIBUTE_REQUEST_ID);
										if(Long.toString(response.getRequestParameters().getRequestID()).equals(requestId))
										{
											ipDemand = demand;
											break;
										}
									}
								}
								if(ipDemand == null) throw new Net2PlanException("The request ID received does not exist");

								long egressNodeId = ipDemand.getEgressNode().getId();

								Path path1 = response.getPath(0);
								float bandwidthInGbps = ((BandwidthRequested) response.getBandwidth()).getBw() * 8f / 1E9f;

								List<Link> seqLinks = new LinkedList<>();
								List<Link> seqFibers = new LinkedList<>();
								List<Integer> seqWavelengths = new LinkedList<Integer>();

								ExplicitRouteObject ero1 = path1.geteRO();
								boolean isMultilayer = false;
								for(EROSubobject subEro : ero1.getEROSubobjectList())
									if(subEro instanceof UnnumberIfIDEROSubobject)
									{
										isMultilayer = true;
										break;
									}

								if(isMultilayer) handleResponseMultiLayer(netPlan, egressNodeId, seqLinks, seqFibers, seqWavelengths, ero1);
								else handleResponseSingleLayer(netPlan, seqLinks, ero1);

								System.out.println();
								netPlan.addRoute(ipDemand, bandwidthInGbps, bandwidthInGbps, seqLinks, null); //FIXME error when adding two consecutive routes between the same pair of
								// nodes

								PCEPReport report1 = new PCEPReport();
								report1.setStateReportList(new LinkedList<StateReport>());

								LSP lsp1 = new LSP();
								lsp1.setDFlag(true); //Set DELEGATE to true
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
			}catch(Throwable e)
			{
				e.printStackTrace();
				System.err.println("Caught exception " + e.toString());
				shutdown();
			}
		}

		private void handleResponseSingleLayer(NetPlan netPlan, List<Link> seqLinks, ExplicitRouteObject ero1) throws UnknownHostException
		{
			List<EROSubobject> eroSubobjects = ero1.getEROSubobjectList();
			for(int i = 1; i < eroSubobjects.size(); i++) //FIXME may throw exception if path is less than size 2!
			{

				if(! (eroSubobjects.get(i - 1) instanceof IPv4prefixEROSubobject) || ! (eroSubobjects.get(i) instanceof IPv4prefixEROSubobject))
					throw new Net2PlanException("Bad. All ERO Subobjects should be of type IPv4");
				Inet4Address sourceIPAddress = ((IPv4prefixEROSubobject) eroSubobjects.get(i - 1)).getIpv4address();
				Inet4Address destinationIPAddress = ((IPv4prefixEROSubobject) eroSubobjects.get(i)).getIpv4address();

				Set<Link> possibleLinks = Utils.getLinksBySourceDestinationIPAddresses(netPlan, _ipLayer, sourceIPAddress, destinationIPAddress); //FIXME fix wehn more than 1 possible link!
				seqLinks.add(possibleLinks.iterator().next());
				System.out.println("added =" + possibleLinks.iterator().next());
			}

			//Last links has to be retrieved from the outside
			EROSubobject aux = eroSubobjects.get(eroSubobjects.size() - 1);
			Inet4Address lastAddress = ((IPv4prefixEROSubobject) aux).getIpv4address();
			long linkId = Utils.getLinkBySourceIPAddress(netPlan, _ipLayer, lastAddress);
			seqLinks.add(netPlan.getLinkFromId(linkId));
		}

		private void handleResponseMultiLayer(NetPlan netPlan, long egressNodeId, List<Link> seqLinks, List<Link> seqFibers, List<Integer> seqWavelengths, ExplicitRouteObject ero1) throws
				UnknownHostException
		{
			for(EROSubobject eroSubobject1 : ero1.getEROSubobjectList())
			{
				if(eroSubobject1 instanceof IPv4prefixEROSubobject)
				{
					if(! seqFibers.isEmpty()) //!
					{
						Node originNode = seqFibers.get(0).getOriginNode();
						Node destinationNode = seqFibers.get(seqFibers.size() - 1).getDestinationNode();
						Demand wdmDemand = netPlan.addDemand(originNode, destinationNode, es.upct.girtel.net2plan.plugins.activepce.utils.Constants.LIGHTPATH_BINARY_RATE_GBPS,
								null, _wdmLayer);
						Route wdmRoute = netPlan.addRoute(wdmDemand, es.upct.girtel.net2plan.plugins.activepce.utils.Constants.LIGHTPATH_BINARY_RATE_GBPS, es.upct.girtel.net2plan
								.plugins.activepce.utils.Constants.LIGHTPATH_BINARY_RATE_GBPS, seqFibers, null);
						WDMUtils.setLightpathSeqWavelengths(wdmRoute, IntUtils.toArray(seqWavelengths));
						WDMUtils.setLightpathSeqWavelengthsInitialRoute(wdmRoute, IntUtils.toArray(seqWavelengths));

						Link ipLink = netPlan.addLink(originNode, destinationNode, es.upct.girtel.net2plan.plugins.activepce.utils.Constants.LIGHTPATH_BINARY_RATE_GBPS, 0, Double
								.MAX_VALUE, null, _ipLayer);
						ipLink.coupleToLowerLayerDemand(wdmDemand);
						seqLinks.add(ipLink);
					}

					Inet4Address ipAddress = ((IPv4prefixEROSubobject) eroSubobject1).getIpv4address();
					long nodeId = Utils.getNodeByIPAddress(netPlan, ipAddress);
					if(nodeId == egressNodeId)
					{
						Long linkId = Utils.getLinkByDestinationIPAddress(netPlan, _ipLayer, ipAddress);
						seqLinks.add(netPlan.getLinkFromId(linkId));
						break;
					} else
					{
						long linkId = Utils.getLinkBySourceIPAddress(netPlan, _ipLayer, ipAddress);
						seqLinks.add(netPlan.getLinkFromId(linkId));
					}
				} else if(eroSubobject1 instanceof UnnumberIfIDEROSubobject)
				{
					UnnumberIfIDEROSubobject eroInfo = (UnnumberIfIDEROSubobject) eroSubobject1;
					long nodeId = Utils.getNodeByIPAddress(netPlan, eroInfo.getRouterID());
					long interfaceId = eroInfo.getInterfaceID();

					long fiberId = Utils.getLinkBySourceInterface(netPlan, _wdmLayer, nodeId, interfaceId);
					seqFibers.add(netPlan.getLinkFromId(fiberId));
				} else if(eroSubobject1 instanceof GeneralizedLabelEROSubobject)
				{
					DWDMWavelengthLabel wavelengthLabel = ((GeneralizedLabelEROSubobject) eroSubobject1).getDwdmWavelengthLabel();
					int wavelengthId = wavelengthLabel.getN();

					seqWavelengths.add(wavelengthId);
				}
			}
		}
	}

	@Override
	public void showRoute(long routeId)
	{

		NetPlan netPlan = getDesign();
		Route route = netPlan.getRouteFromId(routeId);
		NetworkLayer layer = route.getLayer();
		selectNetPlanViewItem(layer.getId(), Constants.NetworkElementType.ROUTE, routeId);
		Map<Link, Pair<Color, Boolean>> coloredLinks = new HashMap<>();

		for(Link link : route.getSeqLinksRealPath())
			coloredLinks.put(link, Pair.of(Color.BLUE, false));
		if(! route.getInitialSequenceOfLinks().equals(route.getSeqLinksRealPath()))
		{
			for(Link link : route.getInitialSequenceOfLinks())
				if(link.isDown())
					coloredLinks.put(link, Pair.of(Color.RED, true));
		}
		topologyPanel.getCanvas().showAndPickNodesAndLinks(null, coloredLinks);
		topologyPanel.getCanvas().refresh();
	}
}