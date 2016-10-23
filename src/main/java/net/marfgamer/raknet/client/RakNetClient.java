package net.marfgamer.raknet.client;

import static net.marfgamer.raknet.protocol.MessageIdentifier.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import net.marfgamer.raknet.RakNet;
import net.marfgamer.raknet.RakNetPacket;
import net.marfgamer.raknet.client.discovery.DiscoveredServer;
import net.marfgamer.raknet.client.discovery.DiscoveryMode;
import net.marfgamer.raknet.client.discovery.DiscoveryThread;
import net.marfgamer.raknet.exception.NoListenerException;
import net.marfgamer.raknet.exception.RakNetException;
import net.marfgamer.raknet.exception.client.NettyHandlerException;
import net.marfgamer.raknet.exception.client.ServerOfflineException;
import net.marfgamer.raknet.identifier.Identifier;
import net.marfgamer.raknet.identifier.MCPEIdentifier;
import net.marfgamer.raknet.protocol.Reliability;
import net.marfgamer.raknet.protocol.login.ConnectionRequest;
import net.marfgamer.raknet.protocol.login.OpenConnectionRequestOne;
import net.marfgamer.raknet.protocol.login.OpenConnectionRequestTwo;
import net.marfgamer.raknet.protocol.message.CustomPacket;
import net.marfgamer.raknet.protocol.message.acknowledge.Acknowledge;
import net.marfgamer.raknet.protocol.status.UnconnectedPing;
import net.marfgamer.raknet.protocol.status.UnconnectedPingOpenConnections;
import net.marfgamer.raknet.protocol.status.UnconnectedPong;
import net.marfgamer.raknet.session.RakNetServerSession;
import net.marfgamer.raknet.session.RakNetSession;
import net.marfgamer.raknet.util.RakNetUtils;

/**
 * This class is used to connection to servers using the RakNet protocol
 *
 * @author MarfGamer
 */
public class RakNetClient {

	// JRakNet plans to use it's own dynamic MTU system later
	protected static int PHYSICAL_MAXIMUM_TRANSFER_UNIT = -1;
	protected static final MaximumTransferUnit[] units = new MaximumTransferUnit[] { new MaximumTransferUnit(1172, 4),
			new MaximumTransferUnit(548, 5) };

	// Attempt to detect the MTU
	static {
		try {
			PHYSICAL_MAXIMUM_TRANSFER_UNIT = NetworkInterface.getByInetAddress(InetAddress.getLocalHost()).getMTU();
		} catch (IOException e) {
			System.err.println("Warning: Failed to locate the physical maximum transfer unit! Defaulting to "
					+ units[0].getMaximumTransferUnit() + "...");
			PHYSICAL_MAXIMUM_TRANSFER_UNIT = units[0].getMaximumTransferUnit();
		}
	}

	// Used to discover systems without relying on the main thread
	private static final DiscoveryThread discoverySystem = new DiscoveryThread();

	// Client data
	private final long guid;
	private final long timestamp;
	private final boolean threaded;

	private final int discoveryPort;
	private DiscoveryMode mode;
	private final ConcurrentHashMap<InetSocketAddress, DiscoveredServer> discovered;

	// Networking data
	private final Bootstrap bootstrap;
	private final EventLoopGroup group;
	private final RakNetClientHandler handler;

	// Session management
	private Channel channel;
	private RakNetClientListener listener;
	private SessionPreparation preparation;
	private volatile RakNetServerSession session; // Allow other threads to
													// modify this

	public RakNetClient(int discoveryPort, boolean threaded) {
		// Set client data
		this.guid = RakNet.UNIQUE_ID_BITS.getLeastSignificantBits();
		this.timestamp = System.currentTimeMillis();
		this.threaded = threaded;

		// Set discovery data
		this.discoveryPort = discoveryPort;
		this.mode = (discoveryPort > -1 ? DiscoveryMode.ALL_CONNECTIONS : DiscoveryMode.NONE);
		this.discovered = new ConcurrentHashMap<InetSocketAddress, DiscoveredServer>();

		// Set networking data
		this.bootstrap = new Bootstrap();
		this.group = new NioEventLoopGroup();
		this.handler = new RakNetClientHandler(this);

		// Initiate bootstrap data
		bootstrap.channel(NioDatagramChannel.class).group(group).handler(handler);
		bootstrap.option(ChannelOption.SO_BROADCAST, true).option(ChannelOption.SO_REUSEADDR, false);
		this.channel = bootstrap.bind(0).channel();

		// Initiate discovery system if it is not yet started
		if (discoverySystem.isRunning() == false)
			discoverySystem.start();
		discoverySystem.addClient(this);
	}

	public RakNetClient(int discoveryPort) {
		this(discoveryPort, true);
	}

	public RakNetClient() {
		this(-1);
	}

	/**
	 * Returns the client's timestamp (how long ago it started in milliseconds)
	 * 
	 * @return The client's timestamp
	 */
	public long getTimestamp() {
		return (System.currentTimeMillis() - this.timestamp);
	}

	/**
	 * Sets the client's discovery mode
	 * 
	 * @param mode
	 *            - How the client will discover servers on the local network
	 */
	public void setDiscoveryMode(DiscoveryMode mode) {
		this.mode = (mode != null ? mode : DiscoveryMode.NONE);
	}

	/**
	 * Returns the client's discovery mode
	 * 
	 * @return The client's discovery mode
	 */
	public DiscoveryMode getDiscoveryMode() {
		return this.mode;
	}

	/**
	 * Returns the client's listener
	 * 
	 * @return The client's listener
	 */
	public RakNetClientListener getListener() {
		return this.listener;
	}

	/**
	 * Sets the client's listener
	 * 
	 * @param listener
	 *            - The client's new listener
	 */
	public void setListener(RakNetClientListener listener) {
		this.listener = listener;
	}

	/**
	 * Returns the session the client is connected to
	 * 
	 * @return The session the client is connected to
	 */
	public RakNetServerSession getSession() {
		return this.session;
	}

	/**
	 * Called whenever the handler catches an exception in Netty
	 * 
	 * @param causeAddress
	 *            - The address that caused the exception
	 * @param cause
	 *            - The exception caught by the handler
	 */
	protected void handleHandlerException(InetSocketAddress causeAddress, Throwable cause) {
		if (preparation != null) {
			preparation.cancelReason = new NettyHandlerException(this, handler, cause);
			preparation.cancelled = true;
		} else {
			cause.printStackTrace();
		}
	}

	/**
	 * Handles a packet received by the handler
	 * 
	 * @param packet
	 *            - The received packet to handler
	 * @param sender
	 *            - The address of the sender
	 */
	public void handleMessage(RakNetPacket packet, InetSocketAddress sender) {
		short packetId = packet.getId();

		// This packet has to do with server discovery so it isn't handled here
		if (packetId == ID_UNCONNECTED_PONG) {
			UnconnectedPong pong = new UnconnectedPong(packet);
			pong.decode();
			this.updateDiscoveryData(sender, pong);
		}

		// Are we still logging in?
		if (preparation != null) {
			if (!sender.equals(preparation.address)) {
				preparation.handlePacket(packet);
				return;
			}
		}

		// Only handle these from the server we're connected to!
		if (session != null) {
			if (sender.equals(session.getAddress())) {
				if (packetId >= ID_RESERVED_3 && packetId <= ID_RESERVED_9) {
					CustomPacket custom = new CustomPacket(packet);
					custom.decode();

					session.handleCustom0(custom);
				} else if (packetId == Acknowledge.ACKNOWLEDGED || packetId == Acknowledge.NOT_ACKNOWLEDGED) {
					Acknowledge acknowledge = new Acknowledge(packet);
					acknowledge.decode();

					session.handleAcknowledge(acknowledge);
				}
			}
		}
	}

	/**
	 * Sends a raw packet to the specified address
	 * 
	 * @param packet
	 *            - The packet to send
	 * @param address
	 *            - The address to send the packet to
	 */
	private void sendRawMessage(RakNetPacket packet, InetSocketAddress address) {
		channel.writeAndFlush(new DatagramPacket(packet.buffer(), address));
	}

	/**
	 * Updates the discovery data in the client by sending pings and removing
	 * servers that have taken too long to respond to a ping
	 */
	public void updateDiscoveryData() {
		// Make sure we have a listener
		if (listener == null)
			throw new NoListenerException("There must be a client to start the listener!");

		// Remove all servers that have timed out
		ArrayList<InetSocketAddress> forgottenServers = new ArrayList<InetSocketAddress>();
		for (InetSocketAddress discoveredServerAddress : discovered.keySet()) {
			DiscoveredServer discoveredServer = discovered.get(discoveredServerAddress);
			if (System.currentTimeMillis()
					- discoveredServer.getDiscoveryTimestamp() >= DiscoveredServer.SERVER_TIMEOUT_MILLI) {
				forgottenServers.add(discoveredServerAddress);
				listener.onServerForgotten(discoveredServerAddress);
			}
		}
		discovered.keySet().removeAll(forgottenServers);

		// Broadcast ping
		if (mode != DiscoveryMode.NONE && discoveryPort > -1) {
			UnconnectedPing ping = new UnconnectedPing();
			if (mode == DiscoveryMode.OPEN_CONNECTIONS)
				ping = new UnconnectedPingOpenConnections();

			ping.timestamp = this.getTimestamp();
			ping.encode();

			this.sendRawMessage(ping, new InetSocketAddress("255.255.255.255", discoveryPort));
		}
	}

	/**
	 * This method handles the specified pong packet and updates the discovery
	 * data accordingly
	 * 
	 * @param sender
	 *            - The sender of the pong packet
	 * @param pong
	 *            - The pong packet to handle
	 */
	public void updateDiscoveryData(InetSocketAddress sender, UnconnectedPong pong) {
		if (!discovered.containsKey(sender)) {
			// Server discovered
			discovered.put(sender, new DiscoveredServer(sender, System.currentTimeMillis(), pong.identifier));
			if (listener != null)
				listener.onServerDiscovered(sender, pong.identifier);
		} else {
			// Server already discovered, but data has changed
			DiscoveredServer server = discovered.get(sender);
			server.setDiscoveryTimestamp(System.currentTimeMillis());
			if (server.getIdentifier().equals(pong.identifier) == false) {
				server.setIdentifier(pong.identifier);
				if (listener != null)
					listener.onServerIdentifierUpdate(sender, pong.identifier);
			}
		}
	}

	/**
	 * Connects the client to a server with the specified address
	 * 
	 * @param address
	 *            - The address of the server to connect to
	 * @throws RakNetException
	 *             - Thrown if an error occurs during connection or login
	 */
	public void connect(InetSocketAddress address) throws RakNetException {
		// Make sure we have a listener
		if (this.listener == null)
			throw new NoListenerException("Unable to start client, there is no listener!");

		// Reset client data
		this.preparation = new SessionPreparation(this);
		preparation.address = address;

		// Send OPEN_CONNECTION_REQUEST_ONE with a decreasing MTU
		int retries = 0;
		for (MaximumTransferUnit unit : units) {
			retries += unit.getRetries();
			while (unit.retry() > 0 && preparation.loginPackets[0] == false && preparation.cancelled == false) {
				OpenConnectionRequestOne connectionRequestOne = new OpenConnectionRequestOne();
				connectionRequestOne.maximumTransferUnit = unit.getMaximumTransferUnit();
				connectionRequestOne.protocolVersion = RakNet.CLIENT_NETWORK_PROTOCOL;
				connectionRequestOne.encode();
				this.sendRawMessage(connectionRequestOne, address);

				RakNetUtils.passiveSleep(500);
			}
		}

		// If the server didn't respond then it is offline
		if (retries <= 0 && preparation.cancelled == false) {
			preparation.cancelReason = new ServerOfflineException(this, preparation.address);
			preparation.cancelled = true;
		}

		// Send OPEN_CONNECTION_REQUEST_TWO until a response is received
		while (retries > 0 && preparation.loginPackets[1] == false && preparation.cancelled == false) {
			OpenConnectionRequestTwo connectionRequestTwo = new OpenConnectionRequestTwo();
			connectionRequestTwo.clientGuid = this.guid;
			connectionRequestTwo.address = preparation.address;
			connectionRequestTwo.maximumTransferUnit = preparation.maximumTransferUnit;
			connectionRequestTwo.encode();
			this.sendRawMessage(connectionRequestTwo, address);

			RakNetUtils.passiveSleep(500);
		}

		// If the session was set we are connected
		if (preparation.readyForSession()) {
			// Set session and delete preparation data
			this.session = preparation.createSession(channel);
			this.preparation = null;

			// Send connection packet
			ConnectionRequest connectionRequest = new ConnectionRequest();
			connectionRequest.clientGuid = this.guid;
			connectionRequest.timestamp = (System.currentTimeMillis() - this.timestamp);
			connectionRequest.encode();
			session.sendPacket(Reliability.RELIABLE_ORDERED, connectionRequest);

			// Initiate connection loop required for the session to function
			if (this.threaded == true)
				this.initConnectionThreaded();
			else
				this.initConnection();
		} else {
			// Reset the connection data, the connection failed
			InetSocketAddress preparationAddress = preparation.address;
			RakNetException preparationCancelReason = preparation.cancelReason;
			this.preparation = null;

			// Why was the exception cancelled?
			if (preparationCancelReason != null)
				throw preparationCancelReason;
			else
				throw new ServerOfflineException(this, preparationAddress);

		}
	}

	/**
	 * Connects the client to a server with the specified address
	 * 
	 * @param address
	 *            - The address of the server to connect to
	 * @param port
	 *            - The port of the server to connect to
	 * @throws RakNetException
	 *             - Thrown if an error occurs during connection or login
	 */
	public void connect(InetAddress address, int port) throws RakNetException {
		this.connect(new InetSocketAddress(address, port));
	}

	/**
	 * Connects the client to a server with the specified address
	 * 
	 * @param address
	 *            - The address of the server to connect to
	 * @param port
	 *            - The port of the server to connect to
	 * @throws RakNetException
	 *             - Thrown if an error occurs during connection or login
	 * @throws UnknownHostException
	 *             - Thrown if the specified address is an unknown host
	 */
	public void connect(String address, int port) throws RakNetException, UnknownHostException {
		this.connect(InetAddress.getByName(address), port);
	}

	/**
	 * Connects the the client to the specified discovered server
	 * 
	 * @param server
	 *            - The discovered server to connect to
	 * @throws RakNetException
	 *             - Thrown if an error occurs during connection or login
	 */
	public void connect(DiscoveredServer server) throws RakNetException {
		this.connect(server.getAddress());
	}

	/**
	 * Starts the loop needed for the client to stay connected to the server
	 */
	private void initConnection() {
		while (session != null) {
			try {
				session.update();
				if (System.currentTimeMillis() - session.getLastPacketReceiveTime() > RakNetSession.SESSION_TIMEOUT)
					this.disconnect();
			} catch (Exception e) {
				session.closeConnection(e.getMessage());
			}
		}
	}

	/**
	 * Starts the loop needed for the client to stay connected to the server on
	 * it's own thread
	 */
	private void initConnectionThreaded() {
		// Give the thread a reference
		RakNetClient client = this;

		// Create and start the thread
		Thread thread = new Thread() {
			@Override
			public synchronized void run() {
				client.initConnection();
			}
		};
		thread.start();
	}

	/**
	 * Disconnects the client from the server if it is connected to one
	 * 
	 * @param reason
	 *            - The reason the client disconnected from the server
	 */
	public void disconnect(String reason) {
		if (session != null) {
			session.closeConnection(reason);
			listener.onDisconnect(session, reason);
		}
		this.session = null;
		this.preparation = null;
	}

	/**
	 * Disconnects the client from the server if it is connected to one
	 */
	public void disconnect() {
		this.disconnect("Client disconnected");
	}

	@Override
	public void finalize() {
		discoverySystem.removeClient(this);
	}

	public static void main(String[] args) throws Exception {
		RakNetClient client = new RakNetClient(19132);
		client.setListener(new RakNetClientListener() {
			@Override
			public void onServerDiscovered(InetSocketAddress address, Identifier identifier) {
				MCPEIdentifier mcpeIdentifier = new MCPEIdentifier(identifier);
				System.out.println(identifier.toString());
				System.out.println("Discovered server \"" + mcpeIdentifier.getServerName() + "\" on version "
						+ mcpeIdentifier.getVersionTag());
			}
		});
		client.connect("127.0.0.1", 19132);
	}

}