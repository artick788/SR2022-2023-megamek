/*
* MegaMek -
* Copyright (C) 2000, 2001, 2002, 2003, 2004, 2005 Ben Mazur (bmazur@sev.org)
* Copyright (C) 2013 Edward Cullen (eddy@obsessedcomputers.co.uk)
* Copyright (C) 2018, 2020 The MegaMek Team
*
* This program is free software; you can redistribute it and/or modify it under
* the terms of the GNU General Public License as published by the Free Software
* Foundation; either version 2 of the License, or (at your option) any later
* version.
*
* This program is distributed in the hope that it will be useful, but WITHOUT
* ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
* FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
* details.
*/
package megamek.server;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import megamek.MegaMek;
import megamek.common.*;
import megamek.common.Building.BasementType;
import megamek.common.Building.DemolitionCharge;
import megamek.common.IGame.Phase;
import megamek.common.MovePath.MoveStepType;
import megamek.common.actions.AirmechRamAttackAction;
import megamek.common.actions.ArtilleryAttackAction;
import megamek.common.actions.BreakGrappleAttackAction;
import megamek.common.actions.ChargeAttackAction;
import megamek.common.actions.ClearMinefieldAction;
import megamek.common.actions.ClubAttackAction;
import megamek.common.actions.DodgeAction;
import megamek.common.actions.EntityAction;
import megamek.common.actions.FindClubAction;
import megamek.common.actions.FlipArmsAction;
import megamek.common.actions.GrappleAttackAction;
import megamek.common.actions.PushAttackAction;
import megamek.common.actions.RamAttackAction;
import megamek.common.actions.RepairWeaponMalfunctionAction;
import megamek.common.actions.SearchlightAttackAction;
import megamek.common.actions.SpotAction;
import megamek.common.actions.TeleMissileAttackAction;
import megamek.common.actions.TorsoTwistAction;
import megamek.common.actions.TriggerAPPodAction;
import megamek.common.actions.TriggerBPodAction;
import megamek.common.actions.UnjamAction;
import megamek.common.actions.UnjamTurretAction;
import megamek.common.actions.UnloadStrandedAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.containers.PlayerIDandList;
import megamek.common.event.GameListener;
import megamek.common.event.GameVictoryEvent;
import megamek.common.net.ConnectionFactory;
import megamek.common.net.IConnection;
import megamek.common.net.Packet;
import megamek.common.options.GameOptions;
import megamek.common.options.IBasicOption;
import megamek.common.options.IOption;
import megamek.common.options.OptionsConstants;
import megamek.common.util.BoardUtilities;
import megamek.common.util.fileUtils.MegaMekFile;
import megamek.common.verifier.EntityVerifier;
import megamek.common.verifier.TestAero;
import megamek.common.verifier.TestBattleArmor;
import megamek.common.verifier.TestEntity;
import megamek.common.verifier.TestMech;
import megamek.common.verifier.TestSupportVehicle;
import megamek.common.verifier.TestTank;
import megamek.common.weapons.AreaEffectHelper;
import megamek.common.weapons.AreaEffectHelper.DamageFalloff;
import megamek.common.weapons.AreaEffectHelper.NukeStats;
import megamek.common.weapons.ArtilleryBayWeaponIndirectHomingHandler;
import megamek.common.weapons.ArtilleryWeaponIndirectHomingHandler;
import megamek.common.weapons.AttackHandler;
import megamek.common.weapons.CapitalMissileBearingsOnlyHandler;
import megamek.common.weapons.TAGHandler;
import megamek.common.weapons.Weapon;
import megamek.common.weapons.WeaponHandler;
import megamek.common.weapons.infantry.InfantryWeapon;
import megamek.common.weapons.other.TSEMPWeapon;
import megamek.server.victory.VictoryResult;

/**
 * @author Ben Mazur
 */
public class Server implements Runnable {
    // server setup
    private final String password;

    private final String metaServerUrl;

    private final ServerSocket serverSocket;

    private final String motd;

    public GameManager getGamemanager() {
        return gamemanager;
    }

    // TODO (Sam): MAybe packet handler and packet factory can become one
    private GameManager gamemanager;
    private PacketHandler packetHandler;
    private EntityManager entityManager;
    private HandleAttack handleAttack;

    public GameSaveLoader getGameSaveLoader() {
        return gameSaveLoader;
    }

    private GameSaveLoader gameSaveLoader;

    public CommandHash getCommandhash() {
        return commandhash;
    }

    private CommandHash commandhash;

    private class PacketPump implements Runnable {

        boolean shouldStop;

        PacketPump() {
            shouldStop = false;
        }

        void signalEnd() {
            shouldStop = true;
        }

        @Override
        public void run() {
            while (!shouldStop) {
                while (!connectionListener.getPacketQueue().isEmpty()) {
                    ServerConnectionListener.ReceivedPacket rp = connectionListener.pollPacketQueue();
                    synchronized (connectionListener.getServerLock()) {
                        handle(rp.connId, rp.packet);
                    }
                }
                connectionListener.waitPacketQueue();
            }
        }
    }

    /**
     * Special packet queue for client feedback requests.
     */
    private final ConcurrentLinkedQueue< ServerConnectionListener.ReceivedPacket> cfrPacketQueue = new ConcurrentLinkedQueue<>();

    public void addTocfrPacketQueue(ServerConnectionListener.ReceivedPacket packet) {
        synchronized (cfrPacketQueue) {
            cfrPacketQueue.add(packet);
            cfrPacketQueue.notifyAll();
        }
    }

    // listens for and connects players
    private Thread connector;

    private PacketPump packetPump;
    private Thread packetPumpThread;

    private Timer watchdogTimer = new Timer("Watchdog Timer");

    private static Server serverInstance = null;

    private String serverAccessKey = null;

    private Timer serverBrowserUpdateTimer = null;

    public static String ORIGIN = "***Server";

    private ServerConnectionListener connectionListener;

    /**
     * Returns a free connection id.
     */
    public int getFreeConnectionId() {
        return connectionListener.getFreeConnectionId();
    }

    /**
     * Returns a connection, indexed by id
     */
    public Vector<IConnection> getConnections() {return connectionListener.getConnections();}

    /**
     * Returns a connection, indexed by id
     */
    public IConnection getConnection(int connId) {
        return connectionListener.getConnectionIds(connId);
    }

    /**
     * Returns a pending connection
     */
    IConnection getPendingConnection(int connId) {
        return connectionListener.getPendingConnection(connId);
    }

    public Server(String password, int port) throws IOException {
        this(password, port, false, "");
    }

    /**
     * Construct a new GameHost and begin listening for incoming clients.
     *
     * @param password                  the <code>String</code> that is set as a password
     * @param port                      the <code>int</code> value that specifies the port that is
     *                                  used
     * @param registerWithServerBrowser a <code>boolean</code> indicating whether we should register
     *                                  with the master server browser on megamek.info
     */
    public Server(String password, int port, boolean registerWithServerBrowser, String metaServerUrl)
            throws IOException {
        gamemanager = new GameManager();
        reportmanager = new ReportManager();
        entityManager = new EntityManager(game, gamemanager, this);
        packetHandler = new PacketHandler(game);
        commandhash = new CommandHash();
        gameSaveLoader = new GameSaveLoader(game);
        connectionListener = new ServerConnectionListener(game);
        handleAttack = new HandleAttack(this, game, reportmanager, gamemanager);

        this.metaServerUrl = metaServerUrl;
        this.password = password.length() > 0 ? password : null;
        // initialize server socket
        serverSocket = new ServerSocket(port);

        motd = createMotd();

        game.getOptions().initialize();
        game.getOptions().loadOptions();

        changePhase(IGame.Phase.PHASE_LOUNGE);

        // display server start text
        MegaMek.getLogger().info("s: starting a new server...");

        try {
            String host = InetAddress.getLocalHost().getHostName();
            String sb = String.format("s: hostname = '%s' port = %d%n", host, serverSocket.getLocalPort());
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                sb = String.format("%ss: hosting on address = %s%n", sb, address.getHostAddress());
            }

            MegaMek.getLogger().info(sb);
        } catch (UnknownHostException ignored) {
            // oh well.
        }

        MegaMek.getLogger().info("s: password = " + this.password);

        // register commands
        commandhash.registerCommands(this);

        // register terrain processors
        gamemanager.addTerrainProcessor(new FireProcessor(this));
        gamemanager.addTerrainProcessor(new SmokeProcessor(this));
        gamemanager.addTerrainProcessor(new GeyserProcessor(this));
        gamemanager.addTerrainProcessor(new ElevatorProcessor(this));
        gamemanager.addTerrainProcessor(new ScreenProcessor(this));
        gamemanager.addTerrainProcessor(new WeatherProcessor(this));
        gamemanager.addTerrainProcessor(new QuicksandProcessor(this));

        packetPump = new PacketPump();
        packetPumpThread = new Thread(packetPump, "Packet Pump");
        packetPumpThread.start();

        if (registerWithServerBrowser) {
            final TimerTask register = new TimerTask() {
                @Override
                public void run() {
                    registerWithServerBrowser(true,
                            Server.getServerInstance().metaServerUrl);
                }
            };
            serverBrowserUpdateTimer = new Timer("Server Browser Register Timer", true);
            serverBrowserUpdateTimer.schedule(register, 1, 40000);
        }

        // Fully initialised, now accept connections
        connector = new Thread(this, "Connection Listener");
        connector.start();

        serverInstance = this;
    }

    /**
     * Make a default message o' the day containing the version string, and if
     * it was found, the build timestamp
     */
    private String createMotd() {
        StringBuilder motd = new StringBuilder();
        motd.append("Welcome to MegaMek.  Server is running version ")
                .append(MegaMek.VERSION)
                .append(", build date ");
        if (MegaMek.TIMESTAMP > 0L) {
            motd.append(new Date(MegaMek.TIMESTAMP));
        } else {
            motd.append("unknown");
        }
        motd.append('.');

        return motd.toString();
    }

    /**
     * @return true if the server has a password
     */
    public boolean isPassworded() {
        return password != null;
    }

    /**
     * @return true if the password matches
     */
    public boolean isPassword(Object guess) {
        return password.equals(guess);
    }

    /**
     * Shuts down the server.
     */
    public void die() {
        watchdogTimer.cancel();

        // kill thread accepting new connections
        connector = null;
        packetPump.signalEnd();
        packetPumpThread.interrupt();
        packetPumpThread = null;

        // close socket
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }

        connectionListener.killAllConections();

        if (serverBrowserUpdateTimer != null) {
            serverBrowserUpdateTimer.cancel();
        }
        if (!metaServerUrl.equals("")) {
            registerWithServerBrowser(false, metaServerUrl);
        }

        // TODO : Not sure that this still needs to be here after updating to the new logging methods.
        System.out.flush();
    }

    /**
     * Sent when a client attempts to connect.
     */
    void greeting(int cn) {
        // send server greeting -- client should reply with client info.
        sendToPending(cn, new Packet(Packet.COMMAND_SERVER_GREETING));
    }

    private void registerWithServerBrowser(boolean register, String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            DataOutputStream printout = new DataOutputStream(conn.getOutputStream());
            StringBuilder content = new StringBuilder("port=" + URLEncoder.encode(Integer.toString(serverSocket.getLocalPort()), "UTF-8"));
            if (register) {
                for (IConnection iconn : connectionListener.getConnections()) {
                    content.append("&players[]=").append(game.getPlayer(iconn.getId()).getName());
                }
                if ((game.getPhase() != Phase.PHASE_LOUNGE) && (game.getPhase() != Phase.PHASE_UNKNOWN)) {
                    content.append("&close=yes");
                }
                content.append("&version=").append(MegaMek.VERSION);
                if (isPassworded()) {
                    content.append("&pw=yes");
                }
            } else {
                content.append("&delete=yes");
            }
            if (serverAccessKey != null) {
                content.append("&key=").append(serverAccessKey);
            }
            printout.writeBytes(content.toString());
            printout.flush();
            BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line;
            if (conn.getResponseCode() == 200) {
                while ((line = rd.readLine()) != null) {
                    if (serverAccessKey == null) {
                        serverAccessKey = line;
                    }
                }
            }
            rd.close();
            printout.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * @return the current server instance
     */
    public static Server getServerInstance() {
        return serverInstance;
    }

    /**
     * @return the <code>int</code> this server is listening on
     */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    /**
     * @return a <code>String</code> representing the hostname
     */
    public String getHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            e.printStackTrace();
            return "";
        }
    }

    /**
     * Listen for incoming clients.
     */
    public void run() {
        Thread currentThread = Thread.currentThread();
        MegaMek.getLogger().info("s: listening for clients...");
        while (connector == currentThread) {
            try {
                Socket s = serverSocket.accept();
                synchronized (connectionListener.getServerLock()) {
                    int id = getFreeConnectionId();
                    MegaMek.getLogger().info("s: accepting player connection #" + id + "...");

                    IConnection c = ConnectionFactory.getInstance().createServerConnection(s, id);
                    c.addConnectionListener(connectionListener);
                    c.open();

                    connectionListener.addConnectionsPending(c);
                    ConnectionHandler ch = new ConnectionHandler(c);
                    Thread newConnThread = new Thread(ch, "Connection " + id);
                    newConnThread.start();
                    connectionListener.addConnectionHandler(id, ch);

                    greeting(id);
                    ConnectionWatchdog w = new ConnectionWatchdog(this, id);
                    watchdogTimer.schedule(w, 1000, 500);
                }
            } catch (InterruptedIOException ignored) {
                // ignore , just SOTimeout blowing..
            } catch (IOException ignored) { }
        }
    }

    /**
     * Process a packet from a connection.
     *
     * @param connId
     *            - the <code>int</code> ID the connection that received the
     *            packet.
     * @param packet
     *            - the <code>Packet</code> to be processed.
     */
    protected void handle(int connId, Packet packet) {
        IPlayer player = game.getPlayer(connId);
        // Check player. Please note, the connection may be pending.
        if ((null == player) && (null == getPendingConnection(connId))) {
            MegaMek.getLogger().error("Server does not recognize player at connection " + connId);
            return;
        }

        if (packet == null) {
            MegaMek.getLogger().error("Got null packet");
            return;
        }
        // act on it
        switch (packet.getCommand()) {
            case Packet.COMMAND_CLIENT_VERSIONS:
                packetHandler.receivePlayerVersion(packet, connId);
                break;
            case Packet.COMMAND_CLOSE_CONNECTION:
                // We have a client going down!
                IConnection c = getConnection(connId);
                if (c != null) {
                    c.close();
                }
                break;
            case Packet.COMMAND_CLIENT_NAME:
                receivePlayerName(packet, connId);
                break;
            case Packet.COMMAND_PLAYER_UPDATE:
                IPlayer gamePlayer = game.getPlayer(connId);
                gamemanager.receivePlayerInfo(packet, gamePlayer);
                game.validatePlayerInfo(connId);
                send(PacketFactory.createPlayerUpdatePacket(game, connId));
                break;
            case Packet.COMMAND_PLAYER_READY:
                game.receivePlayerDone(packet, connId);
                send(PacketFactory.createPlayerDonePacket(game, connId));
                checkReady();
                break;
            case Packet.COMMAND_REROLL_INITIATIVE:
                receiveInitiativeRerollRequest(connId);
                // send(createPlayerDonePacket(connId));
                break;
            case Packet.COMMAND_FORWARD_INITIATIVE:
                packetHandler.receiveForwardIni(connId);
                break;
            case Packet.COMMAND_CHAT:
                String chat = (String) packet.getObject(0);
                if (chat.startsWith("/")) {
                    commandhash.processCommand(connId, chat);
                } else if (packet.getData().length > 1) {
                    connId = (int) packet.getObject(1);
                    if (connId == IPlayer.PLAYER_NONE) {
                        sendServerChat(chat);
                    } else {
                        sendServerChat(connId, chat);
                    }
                } else {
                    sendChat(player.getName(), chat);
                }
                sendServerChat(EasterEggs.sendEasterEgg(chat));
                break;
            case Packet.COMMAND_BLDG_EXPLODE:
                DemolitionCharge charge = (DemolitionCharge)packet.getData()[0];
                if (charge.playerId == connId) {
                    if (!game.getExplodingCharges().contains(charge)) {
                        game.addExplodingCharge(charge);
                        IPlayer p = game.getPlayer(connId);
                        sendServerChat(p.getName() + " has touched off explosives " + "(handled in end phase)!");
                    }
                }
                break;
            case Packet.COMMAND_ENTITY_MOVE:
                receiveMovement(packet, connId);
                break;
            case Packet.COMMAND_ENTITY_DEPLOY:
                receiveDeployment(packet, connId);
                break;
            case Packet.COMMAND_ENTITY_DEPLOY_UNLOAD:
                receiveDeploymentUnload(packet, connId);
                break;
            case Packet.COMMAND_DEPLOY_MINEFIELDS:
                receiveDeployMinefields(packet);
                break;
            case Packet.COMMAND_ENTITY_ATTACK:
                receiveAttack(packet, connId);
                break;
            case Packet.COMMAND_ENTITY_GTA_HEX_SELECT:
                game.receiveGroundToAirHexSelectPacket(packet, connId);
                break;
            case Packet.COMMAND_ENTITY_ADD:
                receiveEntityAdd(packet, connId);
                resetPlayersDone();
                break;
            case Packet.COMMAND_ENTITY_UPDATE:
                receiveEntityUpdate(packet, connId);
                resetPlayersDone();
                break;
            case Packet.COMMAND_ENTITY_LOAD:
                receiveEntityLoad(packet, connId);
                resetPlayersDone();
                transmitAllPlayerDones();
                break;
            case Packet.COMMAND_ENTITY_MODECHANGE:
                entityManager.receiveEntityModeChange(packet, connId);
                break;
            case Packet.COMMAND_ENTITY_SENSORCHANGE:
                entityManager.receiveEntitySensorChange(packet, connId);
                break;
            case Packet.COMMAND_ENTITY_SINKSCHANGE:
                entityManager.receiveEntitySinksChange(packet, connId);
                break;
            case Packet.COMMAND_ENTITY_ACTIVATE_HIDDEN:
                entityManager.receiveEntityActivateHidden(packet, connId);
                break;
            case Packet.COMMAND_ENTITY_NOVA_NETWORK_CHANGE:
                entityManager.receiveEntityNovaNetworkModeChange(packet, connId);
                break;
            case Packet.COMMAND_ENTITY_MOUNTED_FACINGCHANGE:
                entityManager.receiveEntityMountedFacingChange(packet, connId);
                break;
            case Packet.COMMAND_ENTITY_CALLEDSHOTCHANGE:
                entityManager.receiveEntityCalledShotChange(packet, connId);
                break;
            case Packet.COMMAND_ENTITY_SYSTEMMODECHANGE:
                entityManager.receiveEntitySystemModeChange(packet, connId);
                break;
            case Packet.COMMAND_ENTITY_AMMOCHANGE:
                entityManager.receiveEntityAmmoChange(packet, connId);
                break;
            case Packet.COMMAND_ENTITY_REMOVE:
                entityManager.receiveEntityDelete(packet, connId);
                resetPlayersDone();
                break;
            case Packet.COMMAND_ENTITY_WORDER_UPDATE:
                Object[] data = packet.getData();
                Entity ent = game.getEntity((Integer) data[0]);
                if (ent != null) {
                    Entity.WeaponSortOrder order = (Entity.WeaponSortOrder) data[1];
                    ent.setWeaponSortOrder(order);
                    // Used by the client but is set in setWeaponSortOrder
                    ent.setWeapOrderChanged(false);
                    if (order == Entity.WeaponSortOrder.CUSTOM) {
                        @SuppressWarnings("unchecked")
                        // Unchecked cause of limitations in Java when casting to a collection
                        Map<Integer, Integer> customWeaponOrder = (Map<Integer, Integer>) data[2];
                        ent.setCustomWeaponOrder(customWeaponOrder);
                    }
                }
                break;
            case Packet.COMMAND_SENDING_GAME_SETTINGS:
                if (receiveGameOptions(packet, connId)) {
                    resetPlayersDone();
                    transmitAllPlayerDones();
                    send(PacketFactory.createGameSettingsPacket(game));
                    receiveGameOptionsAux(packet, connId);
                }
                break;
            case Packet.COMMAND_SENDING_MAP_SETTINGS:
                if (game.getPhase().isBefore(Phase.PHASE_DEPLOYMENT)) {
                    MapSettings newSettings = (MapSettings) packet.getObject(0);
                    if (!mapSettings.equalMapGenParameters(newSettings)) {
                        sendServerChat("Player " + player.getName() + " changed map settings");
                    }
                    changeMapsettings(newSettings);
                    resetPlayersDone();
                    transmitAllPlayerDones();
                    send(PacketFactory.createMapSettingsPacket(mapSettings));
                }
                break;
            case Packet.COMMAND_SENDING_MAP_DIMENSIONS:
                if (game.getPhase().isBefore(Phase.PHASE_DEPLOYMENT)) {
                    MapSettings newSettings = (MapSettings) packet.getObject(0);
                    if (!mapSettings.equalMapGenParameters(newSettings)) {
                        sendServerChat("Player " + player.getName() + " changed map dimensions");
                    }
                    changeMapsettings(newSettings);
                    resetPlayersDone();
                    transmitAllPlayerDones();
                    send(PacketFactory.createMapSettingsPacket(mapSettings));
                }
                break;
            case Packet.COMMAND_SENDING_PLANETARY_CONDITIONS:
                if (game.getPhase().isBefore(Phase.PHASE_DEPLOYMENT)) {
                    PlanetaryConditions conditions = (PlanetaryConditions) packet.getObject(0);
                    sendServerChat("Player " + player.getName() + " changed planetary conditions");
                    game.setPlanetaryConditions(conditions);
                    resetPlayersDone();
                    transmitAllPlayerDones();
                    send(PacketFactory.createPlanetaryConditionsPacket(game));
                }
                break;
            case Packet.COMMAND_UNLOAD_STRANDED:
                receiveUnloadStranded(packet, connId);
                break;
            case Packet.COMMAND_SET_ARTYAUTOHITHEXES:
                receiveArtyAutoHitHexes(packet);
                break;
            case Packet.COMMAND_CUSTOM_INITIATIVE:
                packetHandler.receiveCustomInit(packet, connId);
                resetPlayersDone();
                transmitAllPlayerDones();
                break;
            case Packet.COMMAND_LOAD_GAME:
                try {
                    sendServerChat(game.getPlayer(connId).getName() + " loaded a new game.");
                    setGame((IGame) packet.getObject(0));
                    for (IConnection conn : connectionListener.getConnections()) {
                        sendCurrentInfo(conn.getId());
                    }
                } catch (Exception e) {
                    MegaMek.getLogger().error("Error loading save game sent from client", e);
                }
                break;
            case Packet.COMMAND_SQUADRON_ADD:
                receiveSquadronAdd(packet);
                resetPlayersDone();
                transmitAllPlayerDones();
                break;
            case Packet.COMMAND_RESET_ROUND_DEPLOYMENT:
                game.setupRoundDeployment();
                break;
            case Packet.COMMAND_SPECIAL_HEX_DISPLAY_DELETE:
                game.getBoard().removeSpecialHexDisplay((Coords) packet.getObject(0),
                        (SpecialHexDisplay) packet.getObject(1));
                sendSpecialHexDisplayPackets();
                break;
            case Packet.COMMAND_SPECIAL_HEX_DISPLAY_APPEND:
                game.getBoard().addSpecialHexDisplay((Coords) packet.getObject(0),
                        (SpecialHexDisplay) packet.getObject(1));
                sendSpecialHexDisplayPackets();
                break;
        }
    }

    /**
     * Send a packet to a pending connection
     */
    private void sendToPending(int connId, Packet packet) {
        IConnection pendingConn = getPendingConnection(connId);
        if (pendingConn != null) {
            pendingConn.send(packet);
        }
        // What should we do if we've lost this client?
        // For now, nothing.
    }

    /**
     * Send a packet to all connected clients.
     */
    void send(Packet packet) {
        if (connectionListener.getConnections() == null) {
            return;
        }
        for (IConnection conn : connectionListener.getConnections()) {
            conn.send(packet);
        }
    }

    /**
     * Send a packet to a specific connection.
     */
    public void send(int connId, Packet packet) {
        if (getConnection(connId) != null) {
            getConnection(connId).send(packet);
        }
        // What should we do if we've lost this client?
        // For now, nothing.
    }

    /**
     * Transmits a chat message to all players
     */
    public void sendChat(int connId, String origin, String message) {
        send(connId, new Packet(Packet.COMMAND_CHAT, formatChatMessage(origin, message)));
    }

    /**
     * Transmits a chat message to all players
     */
    void sendChat(String origin, String message) {
        String chat = formatChatMessage(origin, message);
        send(new Packet(Packet.COMMAND_CHAT, chat));
    }

    public void sendServerChat(int connId, String message) {
        sendChat(connId, ORIGIN, message);
    }

    public void sendServerChat(String message) {
        sendChat(ORIGIN, message);
    }

    public static String formatChatMessage(String origin, String message) {
        return origin + ": " + message;
    }

    public void requestTeamChange(int team, IPlayer player) {
        gamemanager.setRequestedTeam(team);
        gamemanager.setPlayerChangingTeam(player);
        gamemanager.setChangePlayersTeam(false);
    }

    /**
     * Sends out the player info updates for all players to all connections
     */
    private void transmitAllPlayerUpdates() {
        Vector<IPlayer> players = game.getPlayersVector();
        for (IPlayer player : players) {
            if (player != null) {
                send(PacketFactory.createPlayerUpdatePacket(game, player.getId()));
            }
        }
    }

    /**
     * When the load command is used, there is a list of already connected
     * players which have assigned names and player id numbers with the id
     * numbers matching the connection numbers. When a new game is loaded, this
     * mapping may need to be updated. This method takes a map of player names
     * to their current ids, and uses the list of players to figure out what the
     * current ids should change to.
     *
     * @param nameToIdMap
     *            This maps a player name to the current connection ID
     * @param idToNameMap
     *            This maps a current conn ID to a player name, and is just the
     *            inverse mapping from nameToIdMap
     */
    public void remapConnIds(Map<String, Integer> nameToIdMap, Map<Integer, String> idToNameMap) {
        // Keeps track of connections without Ids
        List<IConnection> unassignedConns = new ArrayList<>();
        // Keep track of which ids are used
        Set<Integer> usedPlayerIds = new HashSet<>();
        Set<String> currentPlayerNames = new HashSet<>();
        for (IPlayer p : game.getPlayersVector()) {
            currentPlayerNames.add(p.getName());
        }
        // Map the old connection Id to new value
        Map<Integer,Integer> connIdRemapping = new HashMap<>();
        for (IPlayer p : game.getPlayersVector()) {
            // Check to see if this player was already connected
            Integer oldId = nameToIdMap.get(p.getName());
            if (oldId != null) {
                if (oldId != p.getId()) {
                    connIdRemapping.put(oldId, p.getId());
                }
                // If the old and new Ids match, make sure we remove ghost status
                else {
                    p.setGhost(false);
                }
            }
            // Check to see if this player's Id is taken
            String oldName = idToNameMap.get(p.getId());
            if ((oldName != null) && !oldName.equals(p.getName())) {
                // If this name doesn't belong to a current player, unassign it
                if (!currentPlayerNames.contains(oldName)) {
                    unassignedConns.add(connectionListener.getConnectionIds(p.getId()));
                    // Make sure we don't add this to unassigned connections twice
                    connectionListener.removeConnectionIds(p.getId());
                }
                // If it does belong to a current player, it'll get handled when that player comes up
            }
            // Keep track of what Ids are used
            usedPlayerIds.add(p.getId());
        }

        // Remap old connection Ids to new ones
        for (Integer currConnId : connIdRemapping.keySet()) {
            Integer newId = connIdRemapping.get(currConnId);
            IConnection conn = connectionListener.getConnectionIds(currConnId);
            conn.setId(newId);
            // If this Id is used, make sure we reassign that connection
            if (connectionListener.getConnectionIds(newId) != null) {
                unassignedConns.add(connectionListener.getConnectionIds(newId));
            }
            // Map the new Id
            connectionListener.addConnectionIds(newId, conn);

            game.getPlayer(newId).setGhost(false);
            send(newId, new Packet(Packet.COMMAND_LOCAL_PN, newId));
        }

        // It's possible we have players not in the saved game, add 'em
        for (IConnection conn : unassignedConns) {
            int newId = 0;
            while (usedPlayerIds.contains(newId)) {
                newId++;
            }
            String name = idToNameMap.get(conn.getId());
            conn.setId(newId);
            IPlayer newPlayer = game.addNewPlayer(newId, name);
            newPlayer.setObserver(true);
            connectionListener.addConnectionIds(newId, conn);
            send(newId, new Packet(Packet.COMMAND_LOCAL_PN, newId));
        }
        // Ensure all clients are up-to-date on player info
        transmitAllPlayerUpdates();
    }

    /**
     * Sends a player the info they need to look at the current phase. This is
     * triggered when a player first connects to the server.
     */
    public void sendCurrentInfo(int connId) {
        // why are these two outside the player != null check below?
        transmitAllPlayerConnects(connId);
        send(connId, PacketFactory.createGameSettingsPacket(game));
        send(connId, PacketFactory.createPlanetaryConditionsPacket(game));

        IPlayer player = game.getPlayer(connId);
        if (null != player) {
            send(connId, new Packet(Packet.COMMAND_SENDING_MINEFIELDS, player.getMinefields()));

            if (game.getPhase() == Phase.PHASE_LOUNGE) {
                send(connId, PacketFactory.createMapSettingsPacket(mapSettings));
                send(PacketFactory.createMapSizesPacket());
                // Send Entities *after* the Lounge Phase Change
                send(connId, new Packet(Packet.COMMAND_PHASE_CHANGE, game.getPhase()));
                if (game.doBlind()) {
                    send(connId, PacketFactory.createFilteredFullEntitiesPacket(player, game, gamemanager));
                } else {
                    send(connId, PacketFactory.createFullEntitiesPacket(game));
                }
            } else {
                send(connId, new Packet(Packet.COMMAND_ROUND_UPDATE, game.getRoundCount()));
                send(connId, PacketFactory.createBoardPacket(game));
                send(connId, PacketFactory.createAllReportsPacket(player, game, reportmanager));

                // Send entities *before* other phase changes.
                if (game.doBlind()) {
                    send(connId, PacketFactory.createFilteredFullEntitiesPacket(player, game, gamemanager));
                } else {
                    send(connId, PacketFactory.createFullEntitiesPacket(game));
                }
                player.setDone(game.getEntitiesOwnedBy(player) <= 0);
                send(connId, new Packet(Packet.COMMAND_PHASE_CHANGE, game.getPhase()));
            }
            if ((game.getPhase() == IGame.Phase.PHASE_FIRING)
                    || (game.getPhase() == IGame.Phase.PHASE_TARGETING)
                    || (game.getPhase() == IGame.Phase.PHASE_OFFBOARD)
                    || (game.getPhase() == IGame.Phase.PHASE_PHYSICAL)) {
                // can't go above, need board to have been sent
                send(connId, PacketFactory.createAttackPacket(game.getActionsVector(), 0));
                send(connId, PacketFactory.createAttackPacket(game.getChargesVector(), 1));
                send(connId, PacketFactory.createAttackPacket(game.getRamsVector(), 1));
                send(connId, PacketFactory.createAttackPacket(game.getTeleMissileAttacksVector(), 1));
            }

            if (game.phaseHasTurns(game.getPhase()) && game.hasMoreTurns()) {
                send(connId, PacketFactory.createTurnVectorPacket(game));
                send(connId, PacketFactory.createTurnIndexPacket(game, connId));
            } else if (game.getPhase() != IGame.Phase.PHASE_LOUNGE) {
                endCurrentPhase();
            }

            send(connId, PacketFactory.createArtilleryPacket(game, player));
            send(connId, PacketFactory.createFlarePacket(game));
            send(connId, PacketFactory.createSpecialHexDisplayPacket(game, connId));
        } // Found the player.
    }

    public void changeMapsettings(MapSettings newSettings) {
        mapSettings = newSettings;
        mapSettings.setBoardsAvailableVector(BoardUtilities.scanForBoards(new BoardDimensions(
                mapSettings.getBoardWidth(), mapSettings.getBoardHeight())));
        mapSettings.removeUnavailable();
        mapSettings.setNullBoards(MapSettings.DEFAULT_BOARD);
        mapSettings.replaceBoardWithRandom(MapSettings.BOARD_RANDOM);
        mapSettings.removeUnavailable();
        // if still only nulls left, use BOARD_GENERATED
        if (mapSettings.getBoardsSelected().next() == null) {
            mapSettings.setNullBoards((MapSettings.BOARD_GENERATED));
        }
    }

    private ReportManager reportmanager;

    public ReportManager getReportmanager() {
        return reportmanager;
    }

    /**
     * The DamageType enumeration is used for the damageEntity function.
     */
    public enum DamageType {
        NONE, FRAGMENTATION, FLECHETTE, ACID, INCENDIARY, IGNORE_PASSENGER, ANTI_TSM, ANTI_INFANTRY, NAIL_RIVET,
        NONPENETRATING
    }

    private IGame game = new Game();

    private MapSettings mapSettings = MapSettings.getInstance();

    // Track buildings that are affected by an entity's movement.
    private Hashtable<Building, Boolean> affectedBldgs = new Hashtable<>();

    private static EntityVerifier entityVerifier;

    /**
     * Sets the game for this server. Restores any transient fields, and sets
     * all players as ghosts. This should only be called during server
     * initialization before any players have connected.
     */
    public void setGame(IGame g) {
        // game listeners are transient so we need to save and restore them
        Vector<GameListener> gameListenersClone = new Vector<>(getGame().getGameListeners());

        game = g;

        for (GameListener listener : gameListenersClone) {
            getGame().addGameListener(listener);
        }

        List<Integer> orphanEntities = new ArrayList<>();
                
        // reattach the transient fields and ghost the players
        for (Entity ent : game.getEntitiesVector()) {
            ent.setGame(game);
            
            if(ent.getOwner() == null) {
                orphanEntities.add(ent.getId());
                continue;
            }
            
            if (ent instanceof Mech) {
                ((Mech) ent).setBAGrabBars();
                ((Mech) ent).setProtomechClampMounts();
            }
            if (ent instanceof Tank) {
                ((Tank) ent).setBAGrabBars();
            }
        }
        
        game.removeEntities(orphanEntities, IEntityRemovalConditions.REMOVE_UNKNOWN);
        
        game.setOutOfGameEntitiesVector(game.getOutOfGameEntitiesVector());
        Vector<IPlayer> players = game.getPlayersVector();
        for (IPlayer player : players) {
            player.setGame(game);
            player.setGhost(true);
        }
        // might need to restore weapon type for some attacks that take multiple
        // turns (like artillery)
        List<AttackHandler> attacks = game.getAttacksVector();
        for (AttackHandler handler : attacks) {
            if (handler instanceof WeaponHandler) {
                ((WeaponHandler) handler).restore();
            }
        }
    }

    /**
     * Returns the current game object
     */
    public IGame getGame() {
        return game;
    }

    /**
     * Returns the entityManager component
     * @return the entityManager
     */
    public EntityManager getEntityManager() {
        return entityManager;
    }

    /**
     * Correct a duplicate player name
     *
     * @param oldName the <code>String</code> old player name, that is a duplicate
     * @return the <code>String</code> new player name
     */
    private String correctDupeName(String oldName) {
        Vector<IPlayer> players = game.getPlayersVector();
        for (IPlayer player : players) {
            if (player.getName().equals(oldName)) {
                // We need to correct it.
                String newName = oldName;
                int dupNum;
                try {
                    dupNum = Integer.parseInt(oldName.substring(oldName.lastIndexOf(".") + 1));
                    dupNum++;
                    newName = oldName.substring(0, oldName.lastIndexOf("."));
                } catch (Exception e) {
                    // If this fails, we don't care much.
                    // Just assume it's the first time for this name.
                    dupNum = 2;
                }
                newName = newName.concat(".").concat(Integer.toString(dupNum));
                return correctDupeName(newName);
            }
        }
        return oldName;
    }

    /**
     * Resend entities to the player called by SeeAll command
     */
    public void sendEntities(int connId) {
        if (game.doBlind()) {
            send(connId, PacketFactory.createFilteredEntitiesPacket(game.getPlayer(connId), null, game, gamemanager));
        } else {
            send(connId, PacketFactory.createEntitiesPacket(game));
        }
    }

    /**
     * Called when it's been determined that an actual player disconnected.
     * Notifies the other players and does the appropriate housekeeping.
     */
    void disconnected(IPlayer player) {
        IGame.Phase phase = game.getPhase();

        // in the lounge, just remove all entities for that player
        if (phase == IGame.Phase.PHASE_LOUNGE) {
            entityManager.removeAllEntitiesOwnedBy(player);
        }

        // if a player has active entities, he becomes a ghost except the VICTORY_PHASE when the disconnected
        // player is most likely the Bot disconnected after receiving the COMMAND_END_OF_GAME command
        // see the Bug 1225949.
        // Ghost players (Bots mostly) are now removed during the resetGame(), so we don't need to do it here.
        // This fixes Bug 3399000 without reintroducing 1225949
        if ((phase == IGame.Phase.PHASE_VICTORY) || (phase == IGame.Phase.PHASE_LOUNGE) || player.isObserver()) {
            game.removePlayer(player.getId());
            send(new Packet(Packet.COMMAND_PLAYER_REMOVE, player.getId()));
            // Prevent situation where all players but the disconnected one
            // are done, and the disconnecting player causes the game to start
            if (phase == IGame.Phase.PHASE_LOUNGE) {
                resetActivePlayersDone();
            }
        } else {
            player.setGhost(true);
            player.setDone(true);
            send(PacketFactory.createPlayerUpdatePacket(game, player.getId()));
        }

        // make sure the game advances
        if (game.phaseHasTurns(game.getPhase()) && (null != game.getTurn())) {
            if (game.getTurn().isValid(player.getId(), game)) {
                gamemanager.sendGhostSkipMessage(player);
            }
        } else {
            checkReady();
        }

        // notify other players
        sendServerChat(player.getName() + " disconnected.");

        // log it
        MegaMek.getLogger().info("s: removed player " + player.getName());

        // Reset the game after Elvis has left the building.
        if (0 == game.getNoOfPlayers()) {
            resetGame();
        }
    }

    /**
     * Reset the game back to the lounge.
     * TODO : couldn't this be a hazard if there are other things executing at the same time?
     */
    public void resetGame() {
        // remove all entities
        game.reset();
        send(PacketFactory.createEntitiesPacket(game));
        send(new Packet(Packet.COMMAND_SENDING_MINEFIELDS, new Vector<>()));

        // remove ghosts
        List<IPlayer> ghosts = new ArrayList<>();
        Vector<IPlayer> players = game.getPlayersVector();
        for (IPlayer p : players) {
            if (p.isGhost()) {
                ghosts.add(p);
            } else {
                // non-ghosts set their starting positions to any
                p.setStartingPos(Board.START_ANY);
                send(PacketFactory.createPlayerUpdatePacket(game, p.getId()));
            }
        }
        for (IPlayer p : ghosts) {
            game.removePlayer(p.getId());
            send(new Packet(Packet.COMMAND_PLAYER_REMOVE, p.getId()));
        }

        // reset all players
        resetPlayersDone();
        transmitAllPlayerDones();

        // Write end of game to stdout so controlling scripts can rotate logs.
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
        MegaMek.getLogger().info(format.format(new Date()) + " END OF GAME");

        changePhase(IGame.Phase.PHASE_LOUNGE);
    }

    /**
     * Shortcut to game.getPlayer(id)
     */
    public IPlayer getPlayer(int id) {
        return game.getPlayer(id);
    }

    /**
     * Called at the beginning of certain phases to make every player not ready.
     */
    private void resetPlayersDone() {
        if (game.isReportingPhase()) {
            return;
        }
        Vector<IPlayer> players = game.getPlayersVector();
        for (IPlayer player : players) {
            player.setDone(false);
        }
        transmitAllPlayerDones();
    }

    /**
     * Called at the beginning of certain phases to make every active player not
     * ready.
     */
    private void resetActivePlayersDone() {
        Vector<IPlayer> players = game.getPlayersVector();
        for (IPlayer player : players) {
            player.setDone(game.getEntitiesOwnedBy(player) <= 0);

        }
        transmitAllPlayerDones();
    }

    /**
     * Writes the victory report
     */
    private void prepareVictoryReport() {
        // remove carcasses to the graveyard
        Vector<Entity> toRemove = new Vector<>();
        for (Entity e : game.getEntitiesVector()) {
            if (e.isCarcass() && !e.isDestroyed()) {
                toRemove.add(e);
            }
        }
        for (Entity e : toRemove) {
            entityManager.destroyEntity(e, "crew death", false, true);
            game.removeEntity(e.getId(), IEntityRemovalConditions.REMOVE_SALVAGEABLE);
            e.setDestroyed(true);
        }

        reportmanager.addReport(ReportFactory.createPublicReport(7000));

        // Declare the victor
        Report r = ReportFactory.createPublicReport(1210);
        if (game.getVictoryTeam() == IPlayer.TEAM_NONE) {
            IPlayer player = game.getPlayer(game.getVictoryPlayerId());
            if (null == player) {
                r.messageId = 7005;
            } else {
                r.messageId = 7010;
                r.add(player.getColorForPlayer());
            }
        } else {
            // Team victory
            r.messageId = 7015;
            r.add(game.getVictoryTeam());
        }
        reportmanager.addReport(r);

        // Show player BVs
        Vector<IPlayer> players = game.getPlayersVector();
        for (IPlayer player : players) {
            // Players who started the game as observers get ignored
            if (player.getInitialBV() == 0) {
                continue;
            }

            r = ReportFactory.createPublicReport(7016, player.getColorForPlayer());
            r.add(player.getBV());
            r.add(Double.toString(Math.round((double) player.getBV() / player.getInitialBV() * 10000.0) / 100.0));
            r.add(player.getInitialBV());
            r.add(player.getFledBV());
            reportmanager.addReport(r);
        }

        // List the survivors
        List<Entity> survivors = game.getEntitiesVector();
        if (!survivors.isEmpty()) {
            reportmanager.addReport(new Report(7020, Report.PUBLIC));
            for (Entity entity : survivors) {
                if (!entity.isDeployed()) {
                    continue;
                }
                reportmanager.addReport(entity.victoryReport());
            }
        }
        // List units that never deployed
        boolean wroteHeader = false;
        for (Entity entity : game.getEntitiesVector()) {
            if (entity.isDeployed()) {
                continue;
            }

            if (!wroteHeader) {
                reportmanager.addReport(new Report(7075, Report.PUBLIC));
                wroteHeader = true;
            }
            reportmanager.addReport(entity.victoryReport());
        }
        // List units that retreated
        reportmanager.addEntitiesToReport(game.getRetreatedEntities(), 7080);
        // List destroyed units
        reportmanager.addEntitiesToReport(game.getGraveyardEntities(), 7085);
        // List devastated units (not salvageable)
        reportmanager.addEntitiesToReport(game.getDevastatedEntities(), 7090);
        // Let player know about entitystatus.txt file
        reportmanager.addReport(new Report(7095, Report.PUBLIC));
    }

    public void allowTeamChange() {
        gamemanager.setChangePlayersTeam(true);
    }

    public boolean isTeamChangeRequestInProgress() {
        return gamemanager.getPlayerChangingTeam() != null;
    }

    public IPlayer getPlayerRequestingTeamChange() {
        return gamemanager.getPlayerChangingTeam();
    }

    public int getRequestedTeam() {
        return gamemanager.getRequestedTeam();
    }

    /**
     * Called when a player declares that he is "done." Checks to see if all
     * players are done, and if so, moves on to the next phase.
     */
    private void checkReady() {
        // check if all active players are done
        Vector<IPlayer> players = game.getPlayersVector();
        for (IPlayer player : players) {
            if (!player.isGhost() && !player.isObserver() && !player.isDone()) {
                return;
            }
        }

        // Tactical Genius pilot special ability (lvl 3)
        if (game.getNoOfInitiativeRerollRequests() > 0) {
            resetActivePlayersDone();
            game.rollInitAndResolveTies();

            determineTurnOrder();
            reportmanager.clearReports();
            reportmanager.writeInitiativeReport(game, true);
            sendReport(true);
            return; // don't end the phase yet, players need to see new report
        }

        // need at least one entity in the game for the lounge phase to end
        if (!game.phaseHasTurns(game.getPhase()) && ((game.getPhase() != IGame.Phase.PHASE_LOUNGE)
                || (game.getNoOfEntities() > 0))) {
            endCurrentPhase();
        }
    }

    private boolean check_MDI(Phase phase) {
        return ((phase == IGame.Phase.PHASE_MOVEMENT) || (phase == IGame.Phase.PHASE_DEPLOYMENT)
                || (phase == IGame.Phase.PHASE_INITIATIVE));
    }

    /**
     * Called when the current player has done his current turn and the turn
     * counter needs to be advanced. Also enforces the "protos_move_multi" and
     * the "protos_move_multi" option. If the player has just moved
     * infantry/protos with a "normal" turn, adds up to
     * Game.INF_AND_PROTOS_MOVE_MULTI - 1 more infantry/proto-specific turns
     * after the current turn.
     */
    public void endCurrentTurn(Entity entityUsed) {
        // Enforce "inf_move_multi" and "protos_move_multi" options.
        // The "isNormalTurn" flag is checking to see if any non-Infantry
        // or non-ProtoMech units can move during the current turn.
        boolean turnsChanged = false;
        boolean outOfOrder = false;
        GameTurn turn = game.getTurn();
        if (game.isPhaseSimultaneous() && (entityUsed != null) && !turn.isValid(entityUsed.getOwnerId(), game)) {
            // turn played out of order
            outOfOrder = true;
            entityUsed.setDone(false);
            GameTurn removed = game.removeFirstTurnFor(entityUsed);
            entityUsed.setDone(true);
            turnsChanged = true;
            if (removed != null) {
                turn = removed;
            }
        }
        final Phase currPhase = game.getPhase();
        final GameOptions gameOpts = game.getOptions();
        final int playerId = null == entityUsed ? IPlayer.PLAYER_NONE : entityUsed.getOwnerId();
        boolean infMoved = entityUsed instanceof Infantry;
        boolean infMoveMulti = gameOpts.booleanOption(OptionsConstants.INIT_INF_MOVE_MULTI) && check_MDI(currPhase);
        boolean protosMoved = entityUsed instanceof Protomech;
        boolean protosMoveMulti = gameOpts.booleanOption(OptionsConstants.INIT_PROTOS_MOVE_MULTI);
        boolean tanksMoved = entityUsed instanceof Tank;
        boolean tanksMoveMulti = gameOpts.booleanOption(OptionsConstants.ADVGRNDMOV_VEHICLE_LANCE_MOVEMENT) && check_MDI(currPhase);
        boolean meksMoved = entityUsed instanceof Mech;
        boolean meksMoveMulti = gameOpts.booleanOption(OptionsConstants.ADVGRNDMOV_MEK_LANCE_MOVEMENT) && check_MDI(currPhase);

        // If infantry or protos move multi see if any
        // other unit types can move in the current turn.
        int multiMask = 0;
        if (infMoveMulti && infMoved) {
            multiMask = GameTurn.CLASS_INFANTRY;
        } else if (protosMoveMulti && protosMoved) {
            multiMask = GameTurn.CLASS_PROTOMECH;
        } else if (tanksMoveMulti && tanksMoved) {
            multiMask = GameTurn.CLASS_TANK;
        } else if (meksMoveMulti && meksMoved) {
            multiMask = GameTurn.CLASS_MECH;
        }

        // In certain cases, a new SpecificEntityTurn could have been added for the Entity whose turn
        // we are ending as the next turn. If this has happened, the remaining entity count will be
        // off and we must ensure that the SpecificEntityTurn for this unit remains the next turn
        List<GameTurn> turnVector = game.getTurnVector();
        int turnIndex = game.getTurnIndex();
        boolean usedEntityNotDone = false;
        if ((turnIndex + 1) < turnVector.size()) {
            GameTurn nextTurn = turnVector.get(turnIndex + 1);
            if (nextTurn instanceof GameTurn.SpecificEntityTurn) {
                GameTurn.SpecificEntityTurn seTurn = (GameTurn.SpecificEntityTurn) nextTurn;
                if ((entityUsed != null) && (seTurn.getEntityNum() == entityUsed.getId())) {
                    turnIndex++;
                    usedEntityNotDone = true;
                }
            }
        }

        // Was the turn we just took added as part of a multi-turn?
        //  This determines if we should add more multi-turns
        boolean isMultiTurn = turn.isMultiTurn();

        // Unless overridden by the "protos_move_multi" option, all ProtoMechs
        // in a unit declare fire, and they don't mix with infantry.
        if (protosMoved && !protosMoveMulti && !isMultiTurn && (entityUsed != null)) {

            // What's the unit number and ID of the entity used?
            final short movingUnit = entityUsed.getUnitNumber();
            final int movingId = entityUsed.getId();

            // How many other ProtoMechs are in the unit that can fire?
            int protoTurns = game.getSelectedEntityCount(new EntitySelector() {
                private final int ownerId = playerId;

                private final int entityId = movingId;

                private final short unitNum = movingUnit;

                public boolean accept(Entity entity) {
                    return (entity instanceof Protomech)
                            && entity.isSelectableThisTurn()
                            && (ownerId == entity.getOwnerId())
                            && (entityId != entity.getId())
                            && (unitNum == entity.getUnitNumber());
                }
            });

            // Add the correct number of turns for the ProtoMech unit number.
            for (int i = 0; i < protoTurns; i++) {
                GameTurn newTurn = new GameTurn.UnitNumberTurn(playerId, movingUnit);
                newTurn.setMultiTurn(true);
                game.insertTurnAfter(newTurn, turnIndex);
                turnsChanged = true;
            }
        }
        // Otherwise, we may need to add turns for the "*_move_multi" options.
        else if (((infMoved && infMoveMulti) || (protosMoved && protosMoveMulti)) && !isMultiTurn) {
            int remaining = 0;

            // Calculate the number of EntityClassTurns need to be added.
            if (infMoveMulti && infMoved) {
                remaining += game.getInfantryLeft(playerId);
            }
            if (protosMoveMulti && protosMoved) {
                remaining += game.getProtomechsLeft(playerId);
            }
            if (usedEntityNotDone) {
                remaining--;
            }
            int moreInfAndProtoTurns = Math.min(gameOpts.intOption(OptionsConstants.INIT_INF_PROTO_MOVE_MULTI) - 1, remaining);

            // Add the correct number of turns for the right unit classes.
            for (int i = 0; i < moreInfAndProtoTurns; i++) {
                GameTurn newTurn = new GameTurn.EntityClassTurn(playerId, multiMask);
                newTurn.setMultiTurn(true);
                game.insertTurnAfter(newTurn, turnIndex);
                turnsChanged = true;
            }
        }

        if (tanksMoved && tanksMoveMulti && !isMultiTurn) {
            int remaining = game.getVehiclesLeft(playerId);
            if (usedEntityNotDone) {
                remaining--;
            }
            int moreVeeTurns = Math.min(gameOpts.intOption(OptionsConstants.ADVGRNDMOV_VEHICLE_LANCE_MOVEMENT_NUMBER) - 1, remaining);

            // Add the correct number of turns for the right unit classes.
            for (int i = 0; i < moreVeeTurns; i++) {
                GameTurn newTurn = new GameTurn.EntityClassTurn(playerId, multiMask);
                newTurn.setMultiTurn(true);
                game.insertTurnAfter(newTurn, turnIndex);
                turnsChanged = true;
            }
        }

        if (meksMoved && meksMoveMulti && !isMultiTurn) {
            int remaining = game.getMechsLeft(playerId);
            if (usedEntityNotDone) {
                remaining--;
            }
            int moreMekTurns = Math.min(gameOpts.intOption(OptionsConstants.ADVGRNDMOV_MEK_LANCE_MOVEMENT_NUMBER) - 1, remaining);

            // Add the correct number of turns for the right unit classes.
            for (int i = 0; i < moreMekTurns; i++) {
                GameTurn newTurn = new GameTurn.EntityClassTurn(playerId, multiMask);
                newTurn.setMultiTurn(true);
                game.insertTurnAfter(newTurn, turnIndex);
                turnsChanged = true;
            }
        }

        // brief everybody on the turn update, if they changed
        if (turnsChanged) {
            send(PacketFactory.createTurnVectorPacket(game));
        }

        // move along
        if (outOfOrder) {
            send(PacketFactory.createTurnIndexPacket(game, playerId));
        } else {
            changeToNextTurn(playerId);
        }
    }

    /**
     * Changes the current phase, does some bookkeeping and then tells the
     * players.
     *
     * @param phase the <code>int</code> id of the phase to change to
     */
    private void changePhase(IGame.Phase phase) {
        game.setLastPhase(game.getPhase());
        game.setPhase(phase);

        // prepare for the phase
        prepareForPhase(phase);

        if (game.isPhasePlayable(phase)) {
            // tell the players about the new phase
            send(new Packet(Packet.COMMAND_PHASE_CHANGE, phase));

            // post phase change stuff
            executePhase(phase);
        } else {
            endCurrentPhase();
        }
    }

    /**
     * Prepares for, presumably, the next phase. This typically involves
     * resetting the states of entities in the game and making sure the client
     * has the information it needs for the new phase.
     *
     * @param phase the <code>int</code> id of the phase to prepare for
     */
    private void prepareForPhase(IGame.Phase phase) {
        switch (phase) {
            case PHASE_LOUNGE:
                reportmanager.clearReports();
                mapSettings.setBoardsAvailableVector(BoardUtilities.scanForBoards(new BoardDimensions(
                        mapSettings.getBoardWidth(), mapSettings.getBoardHeight())));
                mapSettings.setNullBoards(MapSettings.DEFAULT_BOARD);
                send(PacketFactory.createMapSettingsPacket(mapSettings));
                send(PacketFactory.createMapSizesPacket());
                game.checkForObservers();
                transmitAllPlayerUpdates();
                break;
            case PHASE_INITIATIVE:
                // remove the last traces of last round
                game.handleInitiativeCompensation();
                game.resetActions();
                game.resetTagInfo();
                sendTagInfoReset();
                reportmanager.clearReports();
                game.resetEntityRound();
                entityManager.resetEntityPhase(phase);
                game.checkForObservers();
                transmitAllPlayerUpdates();

                // roll 'em
                resetActivePlayersDone();
                rollInitiative();
                //Cockpit command consoles that switched crew on the previous round are ineligible for force
                //commander initiative bonus. Now that initiative is rolled, clear the flag.
                game.getEntities().forEachRemaining(e -> e.getCrew().resetActedFlag());

                if (!game.shouldDeployThisRound()) {
                    incrementAndSendGameRound();
                }

                // setIneligible(phase);
                determineTurnOrder();
                reportmanager.writeInitiativeReport(game, false);

                // checks for environmental survival
                checkForConditionDeath();

                checkForBlueShieldDamage();
                if (game.getBoard().inAtmosphere()) {
                    checkForAtmosphereDeath();
                }
                if (game.getBoard().inSpace()) {
                    checkForSpaceDeath();
                }

                MegaMek.getLogger().info("Round " + game.getRoundCount() + " memory usage: " + MegaMek.getMemoryUsed());
                break;
            case PHASE_DEPLOY_MINEFIELDS:
                game.checkForObservers();
                transmitAllPlayerUpdates();
                resetActivePlayersDone();
                game.setIneligible(phase);
                Vector<GameTurn> turns = new Vector<>();

                Vector<IPlayer> players = game.getPlayersVector();
                for (IPlayer p : players) {
                    if (p.hasMinefields() && game.getBoard().onGround()) {
                        GameTurn gt = new GameTurn(p.getId());
                        turns.addElement(gt);
                    }
                }
                game.setTurnVector(turns);
                game.resetTurnIndex();

                // send turns to all players
                send(PacketFactory.createTurnVectorPacket(game));
                break;
            case PHASE_SET_ARTYAUTOHITHEXES:
                game.deployOffBoardEntities();
                game.checkForObservers();
                transmitAllPlayerUpdates();
                resetActivePlayersDone();
                game.setIneligible(phase);


                Vector<GameTurn> turn = new Vector<>();

                // Walk through the players of the game, and add
                // a turn for all players with artillery weapons.
                players = game.getPlayersVector();
                for (IPlayer p : players) {

                    // Does the player have any artillery-equipped units?
                    EntitySelector playerArtySelector = new EntitySelector() {
                        private final IPlayer owner = p;

                        public boolean accept(Entity entity) {
                            return owner.equals(entity.getOwner()) && entity.isEligibleForArtyAutoHitHexes();
                        }
                    };
                    if (game.getSelectedEntities(playerArtySelector).hasNext()) {
                        // Yes, the player has arty-equipped units.
                        GameTurn gt = new GameTurn(p.getId());
                        turn.addElement(gt);
                    }
                }
                game.setTurnVector(turn);
                game.resetTurnIndex();

                // send turns to all players
                send(PacketFactory.createTurnVectorPacket(game));
                break;
            case PHASE_MOVEMENT:
            case PHASE_DEPLOYMENT:
            case PHASE_FIRING:
            case PHASE_PHYSICAL:
            case PHASE_TARGETING:
            case PHASE_OFFBOARD:
                game.deployOffBoardEntities();

                // Check for activating hidden units
                if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_HIDDEN_UNITS)) {
                    for (Entity ent : game.getEntitiesVector()) {
                        if (ent.getHiddenActivationPhase() == phase) {
                            ent.setHidden(false);
                        }
                    }
                }
                // Update visibility indications if using double blind.
                if (game.doBlind()) {
                    updateVisibilityIndicator(null);
                }
                entityManager.resetEntityPhase(phase);
                game.checkForObservers();
                transmitAllPlayerUpdates();
                resetActivePlayersDone();
                game.setIneligible(phase);
                determineTurnOrder();
                // send(createEntitiesPacket());
                entityManager.entityAllUpdate();
                reportmanager.clearReports();
                doTryUnstuck();
                break;
            case PHASE_END:
                entityManager.resetEntityPhase(phase);
                reportmanager.clearReports();
                resolveHeat();
                if (game.getPlanetaryConditions().isSandBlowing()
                    && (game.getPlanetaryConditions().getWindStrength() > PlanetaryConditions.WI_LIGHT_GALE)) {
                    reportmanager.addReport(resolveBlowingSandDamage());
                }
                reportmanager.addReport(resolveControlRolls());
                reportmanager.addReport(checkForTraitors());
                // write End Phase header
                reportmanager.addReport(new Report(5005, Report.PUBLIC));
                checkLayExplosives();
                reportmanager.resolveHarJelRepairs(game.getEntities());
                resolveEmergencyCoolantSystem();
                checkForSuffocation();
                game.getPlanetaryConditions().determineWind();
                send(PacketFactory.createPlanetaryConditionsPacket(game));

                applyBuildingDamage();
                reportmanager.addReport(game.ageFlares());
                send(PacketFactory.createFlarePacket(game));
                reportmanager.resolveAmmoDumps(game.getEntitiesVector());
                reportmanager.resolveCrewWakeUp(game.getEntities());
                reportmanager.resolveConsoleCrewSwaps(game.getEntities());
                reportmanager.resolveSelfDestruct(game.getEntitiesVector());
                resolveShutdownCrashes();
                checkForIndustrialEndOfTurn();
                resolveMechWarriorPickUp();
                resolveVeeINarcPodRemoval();
                resolveFortify();

                // Moved this to the very end because it makes it difficult to see
                // more important updates when you have 300+ messages of smoke filling
                // whatever hex. Please don't move it above the other things again.
                // Thanks! Ralgith - 2018/03/15
                // TODO (Sam): Check if this change is right (with test)
                //hexUpdateSet.clear();
                gamemanager.setHexUpdateSet(new LinkedHashSet<>());
                Vector<DynamicTerrainProcessor> tps = gamemanager.getTerrainProcessors();
                for (DynamicTerrainProcessor tp : tps) {
                    tp.doEndPhaseChanges(reportmanager.getvPhaseReport());
                }
                // TODO (Sam): also changed here
                gamemanager.sendChangedHexes(game, gamemanager.getHexUpdateSet());

                game.checkForObservers();
                transmitAllPlayerUpdates();
                entityManager.entityAllUpdate();
                break;
            case PHASE_INITIATIVE_REPORT:
                gameSaveLoader.autoSave();
                // Show player BVs
                players = game.getPlayersVector();
                for (IPlayer player : players) {
                    // Players who started the game as observers get ignored
                    if (player.getInitialBV() == 0) {
                        continue;
                    }
                    Report r = ReportFactory.createPublicReport(7016);
                    if (game.doBlind() && game.suppressBlindBV()) {
                        r.type = Report.PLAYER;
                        r.player = player.getId();
                    }
                    r.add(player.getColorForPlayer());
                    r.add(player.getBV());
                    r.add(Double.toString(Math.round((double) player.getBV() / player.getInitialBV() * 10000.0) / 100.0));
                    r.add(player.getInitialBV());
                    r.add(player.getFledBV());
                    reportmanager.addReport(r);
                }
            case PHASE_TARGETING_REPORT:
            case PHASE_MOVEMENT_REPORT:
            case PHASE_OFFBOARD_REPORT:
            case PHASE_FIRING_REPORT:
            case PHASE_PHYSICAL_REPORT:
            case PHASE_END_REPORT:
                resetActivePlayersDone();
                sendReport();
                if (game.getOptions().booleanOption(OptionsConstants.BASE_PARANOID_AUTOSAVE)) {
                    gameSaveLoader.autoSave();
                }
                break;
            case PHASE_VICTORY:
                resetPlayersDone();
                reportmanager.clearReports();
                prepareVictoryReport();
                game.addReports(reportmanager.getvPhaseReport());
                // Before we send the full entities' packet we need to loop
                // through the fighters in squadrons and damage them.
                for (Entity entity : game.getEntitiesVector()) {
                    if ((entity.isFighter()) && !(entity instanceof FighterSquadron)) {
                        if (entity.isPartOfFighterSquadron() || entity.isCapitalFighter()) {
                            ((IAero) entity).doDisbandDamage();
                        }
                    }
                // fix the armor and SI of aeros if using aero sanity rules for the MUL
                if (game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_AERO_SANITY)
                        && (entity instanceof Aero)) {
                    // need to rescale SI and armor
                    int scale = 1;
                    if (entity.isCapitalScale()) {
                        scale = 10;
                    }
                    Aero a = (Aero) entity;
                    int currentSI = a.getSI() / (2 * scale);
                    a.set0SI(a.get0SI() / (2 * scale));
                    if (currentSI > 0) {
                        a.setSI(currentSI);
                    }
                    //Fix for #587. MHQ tracks fighters at standard scale and doesn't (currently)
                    //track squadrons. Squadrons don't save to MUL either, so... only convert armor for JS/WS/SS?
                    //Do we ever need to save capital fighter armor to the final MUL or entityStatus?
                    if (!entity.hasETypeFlag(Entity.ETYPE_JUMPSHIP)) {
                        scale = 1;
                    }
                    if (scale > 1) {
                        for (int loc = 0; loc < entity.locations(); loc++) {
                            int currentArmor = entity.getArmor(loc) / scale;
                            if (entity.getOArmor(loc) > 0) {
                                entity.initializeArmor(entity.getOArmor(loc) / scale, loc);
                            }
                            if (entity.getArmor(loc) > 0) {
                                entity.setArmor(currentArmor, loc);
                            }
                        }
                    }
                }
            }
                send(PacketFactory.createFullEntitiesPacket(game));
                send(PacketFactory.createReportPacket(null, game, reportmanager));
                send(PacketFactory.createEndOfGamePacket(game, reportmanager));
                break;
            default:
        }
    }

    /**
     * Do anything we seed to start the new phase, such as give a turn to the
     * first player to play.
     */
    private void executePhase(IGame.Phase phase) {
        switch (phase) {
            case PHASE_EXCHANGE:
                resetPlayersDone();
                calculatePlayerBVs();
                // Update initial BVs, as things may have been modified in lounge
                for (Entity e : game.getEntitiesVector()) {
                    e.setInitialBV(e.calculateBattleValue(false, false));
                }
                // Build teams vector
                game.setupTeams();
                applyBoardSettings();
                game.getPlanetaryConditions().determineWind();
                send(PacketFactory.createPlanetaryConditionsPacket(game));
                // transmit the board to everybody
                send(PacketFactory.createBoardPacket(game));
                game.setupRoundDeployment();
                game.setVictoryContext(new HashMap<>());
                game.createVictoryConditions();
                // some entities may need to be checked and updated
                entityManager.checkEntityExchange();
                break;
            case PHASE_MOVEMENT:
                // write Movement Phase header to report
                reportmanager.addReport(new Report(2000, Report.PUBLIC));
            case PHASE_SET_ARTYAUTOHITHEXES:
            case PHASE_DEPLOY_MINEFIELDS:
            case PHASE_DEPLOYMENT:
            case PHASE_FIRING:
            case PHASE_PHYSICAL:
            case PHASE_TARGETING:
            case PHASE_OFFBOARD:
                changeToNextTurn(-1);
                if (game.getOptions().booleanOption(OptionsConstants.BASE_PARANOID_AUTOSAVE)) {
                    gameSaveLoader.autoSave();
                }
                break;
            default:
        }
    }

    /**
     * Calculates all players initial BV, should only be called at start of game
     */
    public void calculatePlayerBVs() {
        game.calculatePlayerBVs();
    }

    /**
     * Ends this phase and moves on to the next.
     */
    private void endCurrentPhase() {
        switch (game.getPhase()) {
            case PHASE_LOUNGE:
                game.addReports(reportmanager.getvPhaseReport());
                changePhase(IGame.Phase.PHASE_EXCHANGE);
                break;
            case PHASE_EXCHANGE:
            case PHASE_STARTING_SCENARIO:
                game.addReports(reportmanager.getvPhaseReport());
                changePhase(IGame.Phase.PHASE_SET_ARTYAUTOHITHEXES);
                break;
            case PHASE_SET_ARTYAUTOHITHEXES:
                sendSpecialHexDisplayPackets();
                boolean mines = false;
                Vector<IPlayer> players = game.getPlayersVector();
                for (IPlayer p : players) {
                    if (p.hasMinefields()) {
                        mines = true;
                        break;
                    }
                }
                game.addReports(reportmanager.getvPhaseReport());
                if (mines) {
                    changePhase(IGame.Phase.PHASE_DEPLOY_MINEFIELDS);
                } else {
                    changePhase(IGame.Phase.PHASE_INITIATIVE);
                }
                break;
            case PHASE_DEPLOY_MINEFIELDS:
                changePhase(IGame.Phase.PHASE_INITIATIVE);
                break;
            case PHASE_DEPLOYMENT:
                game.clearDeploymentThisRound();
                game.checkForCompleteDeployment();

                players = game.getPlayersVector();
                for (IPlayer p : players) {
                    p.adjustStartingPosForReinforcements();
                }

                if (game.getRoundCount() < 1) {
                    changePhase(IGame.Phase.PHASE_INITIATIVE);
                } else {
                    changePhase(IGame.Phase.PHASE_TARGETING);
                }
                break;
            case PHASE_INITIATIVE:
                resolveWhatPlayersCanSeeWhatUnits();
                game.detectSpacecraft();
                game.addReports(reportmanager.getvPhaseReport());
                changePhase(IGame.Phase.PHASE_INITIATIVE_REPORT);
                break;
            case PHASE_INITIATIVE_REPORT:
                // NOTE: now that aeros can come and go from the battlefield, I need to update
                // the deployment table every round. I think this it is OK to go here. (Taharqa)
                game.setupRoundDeployment();
                if (game.shouldDeployThisRound()) {
                    changePhase(IGame.Phase.PHASE_DEPLOYMENT);
                } else {
                    changePhase(IGame.Phase.PHASE_TARGETING);
                }
                break;
            case PHASE_MOVEMENT:
                detectHiddenUnits();
                game.updateSpacecraftDetection();
                game.detectSpacecraft();
                resolveWhatPlayersCanSeeWhatUnits();
                entityManager.doAllAssaultDrops();
                game.addMovementHeat();
                applyBuildingDamage();
                checkForPSRFromDamage();
                // Skids cause damage in movement phase
                reportmanager.addReport(resolvePilotingRolls());
                checkForFlamingDamage();
                checkForTeleMissileAttacks();
                game.cleanupDestroyedNarcPods();
                checkForFlawedCooling();
                resolveCallSupport();
                checkPhaseReport(Phase.PHASE_MOVEMENT_REPORT, Phase.PHASE_OFFBOARD);
                break;
            case PHASE_MOVEMENT_REPORT:
                changePhase(IGame.Phase.PHASE_OFFBOARD);
                break;
            case PHASE_FIRING:
                // write Weapon Attack Phase header
                reportmanager.addReport(new Report(3000, Report.PUBLIC));
                resolveWhatPlayersCanSeeWhatUnits();
                resolveAllButWeaponAttacks();
                reportmanager.addReport(resolveSelfDestructions());
                reportGhostTargetRolls();
                reportLargeCraftECCMRolls();
                resolveOnlyWeaponAttacks();
                assignAMS();
                handleAttacks();
                resolveScheduledNukes();
                applyBuildingDamage();
                checkForPSRFromDamage();
                game.cleanupDestroyedNarcPods();
                reportmanager.addReport(resolvePilotingRolls());
                checkForFlawedCooling();
                checkPhaseReport(Phase.PHASE_FIRING_REPORT, Phase.PHASE_PHYSICAL);
                break;
            case PHASE_FIRING_REPORT:
                changePhase(IGame.Phase.PHASE_PHYSICAL);
                break;
            case PHASE_PHYSICAL:
                resolveWhatPlayersCanSeeWhatUnits();
                handleAttack.resolvePhysicalAttacks();
                applyBuildingDamage();
                checkForPSRFromDamage();
                reportmanager.addReport(resolvePilotingRolls());
                resolveSinkVees();
                game.cleanupDestroyedNarcPods();
                checkForFlawedCooling();
                checkForChainWhipGrappleChecks();
                // check phase report
                checkPhaseReport(Phase.PHASE_PHYSICAL_REPORT, Phase.PHASE_END);
                break;
            case PHASE_PHYSICAL_REPORT:
                changePhase(IGame.Phase.PHASE_END);
                break;
            case PHASE_TARGETING:
                reportmanager.addReport(new Report(1035, Report.PUBLIC));
                resolveAllButWeaponAttacks();
                resolveOnlyWeaponAttacks();
                handleAttacks();
                // check reports
                if (reportmanager.getvPhaseReport().size() > 1) {
                    game.addReports(reportmanager.getvPhaseReport());
                    changePhase(IGame.Phase.PHASE_TARGETING_REPORT);
                } else {
                    // just the header, so we'll add the <nothing> label
                    reportmanager.addReport(new Report(1025, Report.PUBLIC));
                    game.addReports(reportmanager.getvPhaseReport());
                    sendReport();
                    changePhase(IGame.Phase.PHASE_MOVEMENT);
                }

                sendSpecialHexDisplayPackets();
                players = game.getPlayersVector();
                for (IPlayer player : players) {
                    int connId = player.getId();
                    send(connId, PacketFactory.createArtilleryPacket(game, player));
                }

                break;
            case PHASE_OFFBOARD:
                // write Offboard Attack Phase header
                reportmanager.addReport(new Report(1100, Report.PUBLIC));
                // torso twist or flip arms possible
                resolveAllButWeaponAttacks();
                // should only be TAG at this point
                resolveOnlyWeaponAttacks();
                handleAttacks();
                players = game.getPlayersVector();
                for (IPlayer player : players) {
                    int connId = player.getId();
                    send(connId, PacketFactory.createArtilleryPacket(game, player));
                }
                applyBuildingDamage();
                checkForPSRFromDamage();
                reportmanager.addReport(resolvePilotingRolls());

                game.cleanupDestroyedNarcPods();
                checkForFlawedCooling();

                sendSpecialHexDisplayPackets();
                sendTagInfoUpdates();

                checkPhaseReport(IGame.Phase.PHASE_OFFBOARD_REPORT, IGame.Phase.PHASE_FIRING);
                break;
            case PHASE_OFFBOARD_REPORT:
                sendSpecialHexDisplayPackets();
                changePhase(IGame.Phase.PHASE_FIRING);
                break;
            case PHASE_TARGETING_REPORT:
                changePhase(IGame.Phase.PHASE_MOVEMENT);
                break;
            case PHASE_END:
                // remove any entities that died in the heat/end phase before checking for victory
                entityManager.resetEntityPhase(IGame.Phase.PHASE_END);
                boolean victory = victory(); // note this may add reports
                // check phase report
                // HACK: hardcoded message ID check
                if ((reportmanager.getvPhaseReport().size() > 3) || ((reportmanager.getvPhaseReport().size() > 1)
                        && (reportmanager.getvPhaseReport().elementAt(1).messageId != 1205))) {
                    game.addReports(reportmanager.getvPhaseReport());
                    changePhase(IGame.Phase.PHASE_END_REPORT);
                } else {
                    // just the heat and end headers, so we'll add the <nothing> label
                    reportmanager.addReport(new Report(1205, Report.PUBLIC));
                    game.addReports(reportmanager.getvPhaseReport());
                    sendReport();
                    if (victory) {
                        changePhase(IGame.Phase.PHASE_VICTORY);
                    } else {
                        changePhase(IGame.Phase.PHASE_INITIATIVE);
                    }
                }
                // Decrement the ASEWAffected counter
                game.decrementASEWTurns();

                break;
            case PHASE_END_REPORT:
                if (gamemanager.isChangePlayersTeam()) {
                    gamemanager.processTeamChange(game);
                }
                if (victory()) {
                    changePhase(IGame.Phase.PHASE_VICTORY);
                } else {
                    changePhase(IGame.Phase.PHASE_INITIATIVE);
                }
                break;
            case PHASE_VICTORY:
                GameVictoryEvent gve = new GameVictoryEvent(this, game);
                game.processGameEvent(gve);
                transmitGameVictoryEventToAll();
                resetGame();
                break;
            default:
        }

        // Any hidden units that activated this phase, should clear their activating phase
        for (Entity ent : game.getEntitiesVector()) {
            if (ent.getHiddenActivationPhase() == game.getPhase()) {
                ent.setHiddeActivationPhase(null);
            }
        }
    }

    private void checkPhaseReport(Phase phase, Phase elsephase) {
        // check phase report
        if (reportmanager.getvPhaseReport().size() > 1) {
            game.addReports(reportmanager.getvPhaseReport());
            changePhase(phase);
        } else {
            // just the header, so we'll add the <nothing> label
            reportmanager.addReport(new Report(1205, Report.PUBLIC));
            game.addReports(reportmanager.getvPhaseReport());
            sendReport();
            changePhase(elsephase);
        }
    }

    private void sendSpecialHexDisplayPackets() {
        if (connectionListener.getConnections() == null) {
            return;
        }
        for (int i = 0; i < connectionListener.getConnections().size(); i++) {
            if (connectionListener.getConnections().get(i) != null) {
                connectionListener.getConnections().get(i).send(PacketFactory.createSpecialHexDisplayPacket(game, i));
            }
        }
    }

    private void sendTagInfoUpdates() {
        if (connectionListener.getConnections() == null) {
            return;
        }
        for (IConnection connection : connectionListener.getConnections()) {
            if (connection != null) {
                connection.send(PacketFactory.createTagInfoUpdatesPacket(game));
            }
        }
    }

    public void sendTagInfoReset() {
        if (connectionListener.getConnections() == null) {
            return;
        }
        for (IConnection connection : connectionListener.getConnections()) {
            if (connection != null) {
                connection.send(new Packet(Packet.COMMAND_RESET_TAGINFO));
            }
        }
    }

    /**
     * Increment's the server's game round and send it to all the clients
     */
    private void incrementAndSendGameRound() {
        game.incrementRoundCount();
        send(new Packet(Packet.COMMAND_ROUND_UPDATE, game.getRoundCount()));
    }

    /**
     * Tries to change to the next turn. If there are no more turns, ends the
     * current phase. If the player whose turn it is next is not connected, we
     * allow the other players to skip that player.
     */
    private void changeToNextTurn(int prevPlayerId) {
        boolean minefieldPhase = game.getPhase() == IGame.Phase.PHASE_DEPLOY_MINEFIELDS;
        boolean artyPhase = game.getPhase() == IGame.Phase.PHASE_SET_ARTYAUTOHITHEXES;

        GameTurn nextTurn = null;
        Entity nextEntity = null;
        while (game.hasMoreTurns() && (null == nextEntity)) {
            nextTurn = game.changeToNextTurn();
            nextEntity = game.getEntity(game.getFirstEntityNum(nextTurn));
            if (minefieldPhase || artyPhase) {
                break;
            }
        }

        // if there aren't any more valid turns, end the phase
        // note that some phases don't use entities
        if (((null == nextEntity) && !minefieldPhase) || ((null == nextTurn) && minefieldPhase)) {
            endCurrentPhase();
            return;
        }

        IPlayer player = game.getPlayer(nextTurn.getPlayerNum());

        if ((player != null) && (game.getEntitiesOwnedBy(player) == 0)) {
            endCurrentTurn(null);
            return;
        }

        if (prevPlayerId != -1) {
            send(PacketFactory.createTurnIndexPacket(game, prevPlayerId));
        } else {
            send(PacketFactory.createTurnIndexPacket(game, player != null ? player.getId() : IPlayer.PLAYER_NONE));
        }

        if ((null != player) && player.isGhost()) {
            gamemanager.sendGhostSkipMessage(player);
        } else if ((null == game.getFirstEntity()) && (null != player) && !minefieldPhase && !artyPhase) {
            gamemanager.sendTurnErrorSkipMessage(player);
        }
    }

    /**
     * Skips the current turn. This only makes sense in phases that have turns.
     * Operates by finding an entity to move and then doing nothing with it.
     */
    public void skipCurrentTurn() {
        // find an entity to skip...
        Entity toSkip = game.getFirstEntity();

        switch (game.getPhase()) {
            case PHASE_DEPLOYMENT:
                // allow skipping during deployment,
                // we need that when someone removes a unit.
                endCurrentTurn(null);
                break;
            case PHASE_MOVEMENT:
                if (toSkip != null) {
                    entityManager.processMovement(toSkip, new MovePath(game, toSkip), null);
                }
                endCurrentTurn(toSkip);
                break;
            case PHASE_FIRING:
            case PHASE_PHYSICAL:
            case PHASE_TARGETING:
            case PHASE_OFFBOARD:
                if (toSkip != null) {
                    processAttack(toSkip, new Vector<>(0));
                }
                endCurrentTurn(toSkip);
                break;
            default:
        }
    }

    /**
     * Returns true if victory conditions have been met. Victory conditions are
     * when there is only one player left with mechs or only one team. will also
     * add some reports to reporting
     */
    public boolean victory() {
        VictoryResult vr = game.getVictory().checkForVictory(game, game.getVictoryContext());
        for (Report r : vr.getReports()) {
            reportmanager.addReport(r);
        }

        if (vr.victory()) {
            boolean draw = vr.isDraw();
            int wonPlayer = vr.getWinningPlayer();
            int wonTeam = vr.getWinningTeam();

            if (wonPlayer != IPlayer.PLAYER_NONE) {
                reportmanager.addReport(ReportFactory.createPublicReport(7200, game.getPlayer(wonPlayer).getColorForPlayer()));
            }
            if (wonTeam != IPlayer.TEAM_NONE) {
                reportmanager.addReport(ReportFactory.createPublicReport(7200, "Team " + wonTeam));
            }
            // multiple-won draw or nobody-won draw or single player won or single team won
            game.setVictoryPlayerId(draw ? IPlayer.PLAYER_NONE : wonPlayer);
            game.setVictoryTeam(draw ? IPlayer.TEAM_NONE : wonTeam);
        } else {
            game.setVictoryPlayerId(IPlayer.PLAYER_NONE);
            game.setVictoryTeam(IPlayer.TEAM_NONE);
            if (game.isForceVictory()) {
                game.cancelVictory();
            }
        }
        return vr.victory();
    }// end victory

    /**
     * Applies board settings. This loads and combines all the boards that were
     * specified into one mega-board and sets that board as current.
     */
    public void applyBoardSettings() {
        mapSettings.replaceBoardWithRandom(MapSettings.BOARD_RANDOM);
        mapSettings.replaceBoardWithRandom(MapSettings.BOARD_SURPRISE);
        IBoard[] sheetBoards = new IBoard[mapSettings.getMapWidth() * mapSettings.getMapHeight()];
        List<Boolean> rotateBoard = new ArrayList<>();
        for (int i = 0; i < (mapSettings.getMapWidth() * mapSettings.getMapHeight()); i++) {
            sheetBoards[i] = new Board();
            String name = mapSettings.getBoardsSelectedVector().get(i);
            boolean isRotated = false;
            if (name.startsWith(Board.BOARD_REQUEST_ROTATION)) {
                // only rotate boards with an even width
                if ((mapSettings.getBoardWidth() % 2) == 0) {
                    isRotated = true;
                }
                name = name.substring(Board.BOARD_REQUEST_ROTATION.length());
            }
            if (name.startsWith(MapSettings.BOARD_GENERATED) || (mapSettings.getMedium() == MapSettings.MEDIUM_SPACE)) {
                sheetBoards[i] = BoardUtilities.generateRandom(mapSettings);
            } else {
                sheetBoards[i].load(new MegaMekFile(Configuration.boardsDir(), name + ".board").getFile());
                BoardUtilities.flip(sheetBoards[i], isRotated, isRotated);
            }
            rotateBoard.add(isRotated);
        }
        IBoard newBoard = BoardUtilities.combine(mapSettings.getBoardWidth(),
                mapSettings.getBoardHeight(), mapSettings.getMapWidth(),
                mapSettings.getMapHeight(), sheetBoards, rotateBoard,
                mapSettings.getMedium());

        if (game.getOptions().getOption(OptionsConstants.BASE_BRIDGECF).intValue() > 0) {
            newBoard.setBridgeCF(game.getOptions().getOption(OptionsConstants.BASE_BRIDGECF).intValue());
        }
        if (!game.getOptions().booleanOption(OptionsConstants.BASE_RANDOM_BASEMENTS)) {
            newBoard.setRandomBasementsOff();
        }
        if (game.getPlanetaryConditions().isTerrainAffected()) {
            BoardUtilities.addWeatherConditions(newBoard, game.getPlanetaryConditions().getWeather(),
                    game.getPlanetaryConditions().getWindStrength());
        }
        game.setBoard(newBoard);
    }

    /**
     * Rolls initiative for all the players.
     */
    private void rollInitiative() {
        if (game.getOptions().booleanOption(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE)) {
            TurnOrdered.rollInitiative(game.getEntitiesVector(), false);
        } else {
            // Roll for initiative on the teams.
            TurnOrdered.rollInitiative(game.getTeamsVector(),
                    game.getOptions().booleanOption(OptionsConstants.INIT_INITIATIVE_STREAK_COMPENSATION)
                    && !game.shouldDeployThisRound());
        }
        transmitAllPlayerUpdates();
    }

    /**
     * Determines the turn oder for a given phase (with individual init)
     */
    private void determineTurnOrderIUI() {
        for (Entity entity : game.getEntitiesVector()) {
            entity.resetOtherTurns();
            if (entity.isSelectableThisTurn()) {
                entity.incrementOtherTurns();
            }
        }

        List<Entity> entities;
        // If the protos move multi option isn't on, protos move as a unit need to
        // adjust entities vector otherwise we'll have too many turns when first proto
        // in a unit moves, new turns get added so rest of the unit will move
        boolean protosMoveMulti = game.getOptions().booleanOption(OptionsConstants.INIT_PROTOS_MOVE_MULTI);
        if (!protosMoveMulti) {
            entities = new ArrayList<>(game.getEntitiesVector().size());
            Set<Short> movedUnits = new HashSet<>();
            for (Entity e : game.getEntitiesVector()) {
                // This only effects Protos for the time being
                if (!(e instanceof Protomech)) {
                    entities.add(e);
                    continue;
                }
                short unitNumber = e.getUnitNumber();
                if ((unitNumber == Entity.NONE) || !movedUnits.contains(unitNumber)) {
                    entities.add(e);
                    if (unitNumber != Entity.NONE) {
                        movedUnits.add(unitNumber);
                    }
                }
            }
        } else {
            entities = game.getEntitiesVector();
        }
        // Now, generate the global order of all teams' turns.
        TurnVectors team_order = TurnOrdered.generateTurnOrder(entities, game);

        // Now, we collect everything into a single vector.
        Vector<GameTurn> turns = game.checkTurnOrderStranded(team_order);

        // add the turns (this is easy)
        while (team_order.hasMoreElements()) {
            Entity e = (Entity) team_order.nextElement();
            if (e.isSelectableThisTurn()) {
                if (!protosMoveMulti && (e instanceof Protomech) && (e.getUnitNumber() != Entity.NONE)) {
                    turns.addElement(new GameTurn.UnitNumberTurn(e.getOwnerId(), e.getUnitNumber()));
                } else {
                    turns.addElement(new GameTurn.SpecificEntityTurn(e.getOwnerId(), e.getId()));
                }
            }
        }

        // set fields in game
        game.setTurnVector(turns);
        game.resetTurnIndex();

        // send turns to all players
        send(PacketFactory.createTurnVectorPacket(game));
    }

    /**
     * Determines the turn order for a given phase
     */
    private void determineTurnOrder() {
        if (game.getOptions().booleanOption(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE)) {
            determineTurnOrderIUI();
            return;
        }
        // and/or deploy even according to game options.
        boolean infMoveEven = (game.getOptions().booleanOption(OptionsConstants.INIT_INF_MOVE_EVEN)
                && ((game.getPhase() == IGame.Phase.PHASE_INITIATIVE)
                || (game.getPhase() == IGame.Phase.PHASE_MOVEMENT)))
                || (game.getOptions().booleanOption(OptionsConstants.INIT_INF_DEPLOY_EVEN)
                && (game.getPhase() == IGame.Phase.PHASE_DEPLOYMENT));
        boolean infMoveMulti = game.getOptions().booleanOption(OptionsConstants.INIT_INF_MOVE_MULTI) && check_MDI(game.getPhase());
        boolean protosMoveEven = (game.getOptions().booleanOption(
                OptionsConstants.INIT_PROTOS_MOVE_EVEN)
                && check_MDI(game.getPhase()))
                || (game.getOptions().booleanOption(OptionsConstants.INIT_PROTOS_MOVE_EVEN)
                && (game.getPhase() == IGame.Phase.PHASE_DEPLOYMENT));
        boolean protosMoveMulti = game.getOptions().booleanOption(
                OptionsConstants.INIT_PROTOS_MOVE_MULTI);
        boolean protosMoveByPoint = !protosMoveMulti;
        boolean tankMoveByLance = game.getOptions().booleanOption(
                OptionsConstants.ADVGRNDMOV_VEHICLE_LANCE_MOVEMENT)
                && check_MDI(game.getPhase());
        boolean mekMoveByLance = game.getOptions().booleanOption(
                OptionsConstants.ADVGRNDMOV_MEK_LANCE_MOVEMENT)
                && check_MDI(game.getPhase());

        int evenMask = 0;
        if (infMoveEven) {
            evenMask += GameTurn.CLASS_INFANTRY;
        }
        if (protosMoveEven) {
            evenMask += GameTurn.CLASS_PROTOMECH;
        }
        // Reset all of the Players' turn category counts
        Vector<IPlayer> players = game.getPlayersVector();
        for (IPlayer player : players) {
            player.resetEvenTurns();
            player.resetMultiTurns();
            player.resetOtherTurns();
            player.resetSpaceStationTurns();
            player.resetJumpshipTurns();
            player.resetWarshipTurns();
            player.resetDropshipTurns();
            player.resetSmallCraftTurns();
            player.resetAeroTurns();

            // Add turns for ProtoMechs weapons declaration.
            if (protosMoveByPoint) {

                // How many ProtoMechs does the player have?
                Iterator<Entity> playerProtos = game.getSelectedEntities(new EntitySelector() {
                            private final int ownerId = player.getId();

                            public boolean accept(Entity entity) {
                                return (entity instanceof Protomech)
                                        && (ownerId == entity.getOwnerId())
                                        && entity.isSelectableThisTurn();
                            }
                        });
                HashSet<Integer> points = new HashSet<>();
                int numPlayerProtos = 0;
                while (playerProtos.hasNext()) {
                    Entity proto = playerProtos.next();
                    numPlayerProtos++;
                    points.add((int) proto.getUnitNumber());
                }
                int numProtoUnits = (int) Math.ceil(numPlayerProtos / 5.0);
                if (!protosMoveEven) {
                    numProtoUnits = points.size();
                }
                for (int unit = 0; unit < numProtoUnits; unit++) {
                    if (protosMoveEven) {
                        player.incrementEvenTurns();
                    } else {
                        player.incrementOtherTurns();
                    }
                }
            } // End handle-proto-firing-turns
        } // Handle the next player

        // Go through all entities, and update the turn categories of the
        // entity's player. The teams get their totals from their players.
        // N.B. ProtoMechs declare weapons fire based on their point.
        for (Entity entity : game.getEntitiesVector()) {
            if (entity.isSelectableThisTurn()) {
                final IPlayer player = entity.getOwner();
                boolean is_md = ((game.getPhase() == IGame.Phase.PHASE_MOVEMENT) || (game.getPhase() == IGame.Phase.PHASE_DEPLOYMENT));
                if ((entity instanceof SpaceStation) && is_md) {
                    player.incrementSpaceStationTurns();
                } else if ((entity instanceof Warship) && is_md) {
                    player.incrementWarshipTurns();
                } else if ((entity instanceof Jumpship) && is_md) {
                    player.incrementJumpshipTurns();
                } else if ((entity instanceof Dropship) && entity.isAirborne() && is_md) {
                    player.incrementDropshipTurns();
                } else if ((entity instanceof SmallCraft) && entity.isAirborne() && is_md) {
                    player.incrementSmallCraftTurns();
                } else if (entity.isAirborne() && is_md) {
                    player.incrementAeroTurns();
                } else if ((entity instanceof Infantry)) {
                    if (infMoveEven) {
                        player.incrementEvenTurns();
                    } else if (infMoveMulti) {
                        player.incrementMultiTurns(GameTurn.CLASS_INFANTRY);
                    } else {
                        player.incrementOtherTurns();
                    }
                } else if (entity instanceof Protomech) {
                    if (!protosMoveByPoint) {
                        if (protosMoveEven) {
                            player.incrementEvenTurns();
                        } else if (protosMoveMulti) {
                            player.incrementMultiTurns(GameTurn.CLASS_PROTOMECH);
                        } else {
                            player.incrementOtherTurns();
                        }
                    }
                } else if ((entity instanceof Tank) && tankMoveByLance) {
                    player.incrementMultiTurns(GameTurn.CLASS_TANK);
                } else if ((entity instanceof Mech) && mekMoveByLance) {
                    player.incrementMultiTurns(GameTurn.CLASS_MECH);
                } else {
                    player.incrementOtherTurns();
                }
            }
        }

        // Generate the turn order for the Players *within* each Team. Map the teams to their turn orders.
        // Count the number of teams moving this turn.
        int nTeams = game.getNoOfTeams();
        Hashtable<Team, TurnVectors> allTeamTurns = new Hashtable<>(nTeams);
        Hashtable<Team, int[]> evenTrackers = new Hashtable<>(nTeams);
        int numTeamsMoving = 0;
        List<Team> teams = game.getTeamsVector();
        for (Team team : teams) {
            allTeamTurns.put(team, team.determineTeamOrder(game));

            // Track both the number of times we've checked the team for
            // "leftover" turns, and the number of "leftover" turns placed.
            int[] evenTracker = new int[2];
            evenTrackers.put(team, evenTracker);

            // Count this team if it has any "normal" moves.
            if (team.getNormalTurns(game) > 0) {
                numTeamsMoving++;
            }
        }

        // Now, generate the global order of all teams' turns.
        TurnVectors team_order = TurnOrdered.generateTurnOrder(game.getTeamsVector(), game);

        // Now, we collect everything into a single vector.
        Vector<GameTurn> turns = game.checkTurnOrderStranded(team_order);

        // Walk through the global order, assigning turns for individual players to the single vector.
        // Keep track of how many turns we've added to the vector.
        Team prevTeam = null;
        int min = team_order.getMin();
        for (int numTurn = 0; team_order.hasMoreElements(); numTurn++) {
            Team team = (Team) team_order.nextElement();
            TurnVectors withinTeamTurns = allTeamTurns.get(team);

            int[] evenTracker = evenTrackers.get(team);
            float teamEvenTurns = team.getEvenTurns();

            // Calculate the number of "even" turns to add for this team.
            int numEven = 0;
            if (1 == numTeamsMoving) {
                // If there's only one team moving, we don't need to bother
                // with the evenTracker, just make sure the even turns are
                // evenly distributed
                numEven += Math.round(teamEvenTurns / min);
            } else if (prevTeam == null) {
                // Increment the number of times we've checked for "leftovers".
                evenTracker[0]++;

                // The first team to move just adds the "baseline" turns.
                numEven += Math.round(teamEvenTurns / min);
            } else if (!team.equals(prevTeam)) {
                // Increment the number of times we've checked for "leftovers".
                evenTracker[0]++;

                // This weird equation attempts to spread the "leftover"
                // turns across the turn's moves in a "fair" manner.
                // It's based on the number of times we've checked for
                // "leftovers" the number of "leftovers" we started with,
                // the number of times we've added a turn for a "leftover",
                // and the total number of times we're going to check.
                numEven += (int) Math.ceil(((evenTracker[0] * (teamEvenTurns % min)) / min) - 0.5) - evenTracker[1];

                // Update the number of turns actually added for "leftovers".
                evenTracker[1] += numEven;

                // Add the "baseline" number of turns.
                numEven += Math.round(teamEvenTurns / min);
            }

            // Record this team for the next move.
            prevTeam = team;

            int aeroMask = GameTurn.CLASS_AERO + GameTurn.CLASS_SMALL_CRAFT
                           + GameTurn.CLASS_DROPSHIP + GameTurn.CLASS_JUMPSHIP
                           + GameTurn.CLASS_WARSHIP + GameTurn.CLASS_SPACE_STATION;
            GameTurn turn;
            IPlayer player;
            if (withinTeamTurns.hasMoreNormalElements()) {
                // Not a placeholder... get the player who moves next.
                player = (IPlayer) withinTeamTurns.nextNormalElement();

                // If we've added all "normal" turns, allocate turns
                // for the infantry and/or ProtoMechs moving even.
                if (numTurn >= team_order.getTotalTurns()) {
                    turn = new GameTurn.EntityClassTurn(player.getId(), evenMask);
                }
                // If either Infantry or ProtoMechs move even, only allow
                // the other classes to move during the "normal" turn.
                else if (infMoveEven || protosMoveEven) {
                    int newMask = evenMask;
                    // if this is the movement phase, then don't allow Aeros on normal turns
                    if ((game.getPhase() == IGame.Phase.PHASE_MOVEMENT) || (game.getPhase() == IGame.Phase.PHASE_DEPLOYMENT)) {
                        newMask += aeroMask;
                    }
                    turn = new GameTurn.EntityClassTurn(player.getId(), ~newMask);
                }
                // Otherwise, let *anybody* move.
                else {
                    // well, almost anybody; Aero don't get normal turns during
                    // the movement phase
                    if ((game.getPhase() == IGame.Phase.PHASE_MOVEMENT) || (game.getPhase() == IGame.Phase.PHASE_DEPLOYMENT)) {
                        turn = new GameTurn.EntityClassTurn(player.getId(), ~aeroMask);
                    } else {
                        turn = new GameTurn(player.getId());
                    }
                }
                turns.addElement(turn);
            } // End team-has-"normal"-turns
            else if (withinTeamTurns.hasMoreSpaceStationElements()) {
                player = (IPlayer) withinTeamTurns.nextSpaceStationElement();
                turn = new GameTurn.EntityClassTurn(player.getId(), GameTurn.CLASS_SPACE_STATION);
                turns.addElement(turn);
            } else if (withinTeamTurns.hasMoreJumpshipElements()) {
                player = (IPlayer) withinTeamTurns.nextJumpshipElement();
                turn = new GameTurn.EntityClassTurn(player.getId(), GameTurn.CLASS_JUMPSHIP);
                turns.addElement(turn);
            } else if (withinTeamTurns.hasMoreWarshipElements()) {
                player = (IPlayer) withinTeamTurns.nextWarshipElement();
                turn = new GameTurn.EntityClassTurn(player.getId(), GameTurn.CLASS_WARSHIP);
                turns.addElement(turn);
            } else if (withinTeamTurns.hasMoreDropshipElements()) {
                player = (IPlayer) withinTeamTurns.nextDropshipElement();
                turn = new GameTurn.EntityClassTurn(player.getId(), GameTurn.CLASS_DROPSHIP);
                turns.addElement(turn);
            } else if (withinTeamTurns.hasMoreSmallCraftElements()) {
                player = (IPlayer) withinTeamTurns.nextSmallCraftElement();
                turn = new GameTurn.EntityClassTurn(player.getId(), GameTurn.CLASS_SMALL_CRAFT);
                turns.addElement(turn);
            } else if (withinTeamTurns.hasMoreAeroElements()) {
                player = (IPlayer) withinTeamTurns.nextAeroElement();
                turn = new GameTurn.EntityClassTurn(player.getId(), GameTurn.CLASS_AERO);
                turns.addElement(turn);
            }

            // Add the calculated number of "even" turns.
            // Allow the player at least one "normal" turn before the
            // "even" turns to help with loading infantry in deployment.
            while ((numEven > 0) && withinTeamTurns.hasMoreEvenElements()) {
                IPlayer evenPlayer = (IPlayer) withinTeamTurns.nextEvenElement();
                turns.addElement(new GameTurn.EntityClassTurn(evenPlayer.getId(), evenMask));
                numEven--;
            }
        }

        // set fields in game
        game.setTurnVector(turns);
        game.resetTurnIndex();

        // send turns to all players
        send(PacketFactory.createTurnVectorPacket(game));
    }

    /**
     * Have the loader load the indicated unit. The unit being loaded loses its
     * turn.
     *
     * @param loader - the <code>Entity</code> that is loading the unit.
     * @param unit   - the <code>Entity</code> being loaded.
     */
    public void loadUnit(Entity loader, Entity unit, int bayNumber) {
        // ProtoMechs share a single turn for a Point. When loading one we don't remove its turn
        // unless it's the last unit in the Point to act.
        int remainingProtos = 0;
        if (unit.hasETypeFlag(Entity.ETYPE_PROTOMECH)) {
            remainingProtos = game.getSelectedEntityCount(en -> en.hasETypeFlag(Entity.ETYPE_PROTOMECH)
                    && en.getId() != unit.getId()
                    && en.isSelectableThisTurn()
                    && en.getOwnerId() == unit.getOwnerId()
                    && en.getUnitNumber() == unit.getUnitNumber());
        }

        if ((game.getPhase() != IGame.Phase.PHASE_LOUNGE) && !unit.isDone() && (remainingProtos == 0)) {
            // Remove the *last* friendly turn (removing the *first* penalizes
            // the opponent too much, and re-calculating moves is too hard).
            game.removeTurnFor(unit);
            send(PacketFactory.createTurnVectorPacket(game));
        }

        // When loading an Aero into a squadron in the lounge, make sure the
        // loaded aero has the same bomb loadout as the squadron
        // We want to do this before the fighter is loaded: when the fighter
        // is loaded into the squadron, the squadrons bombing attacks are
        // adjusted based on the bomb-loadout on the fighter.
        if ((game.getPhase() == Phase.PHASE_LOUNGE) && (loader instanceof FighterSquadron)) {
            ((IBomber) unit).setBombChoices(((FighterSquadron) loader).getBombChoices());
        }

        // Load the unit. Do not check for elevation during deployment
        boolean checkElevation = (game.getPhase() != Phase.PHASE_DEPLOYMENT) && (game.getPhase() != Phase.PHASE_LOUNGE);

        loader.load(unit, checkElevation, bayNumber);

        // The loaded unit is being carried by the loader.
        unit.setTransportId(loader.getId());

        // Remove the loaded unit from the screen.
        unit.setPosition(null);

        // set deployment round of the loadee to equal that of the loader
        unit.setDeployRound(loader.getDeployRound());
        
        //Update the loading unit's passenger count, if it's a large craft
        if (loader instanceof SmallCraft || loader instanceof Jumpship) {
            //Don't add dropship crew to a jumpship or station's passenger list
            if (!unit.isLargeCraft()) {
                loader.setNPassenger(loader.getNPassenger() + unit.getCrew().getSize());
            }
        }

        // Update the loaded unit.
        entityManager.entityUpdate(unit.getId());

        // Taharqa (2/28/13): I am not sure why the loader is not getting
        // updated too - not updating it
        // is causing problem in the chat lounge loading, so I am going to do it
        // here, but if we get
        // weird results for other loading, then the reason is probably this
        entityManager.entityUpdate(loader.getId());
    }

    /**
     * Have the tractor drop the indicated trailer. This will also disconnect all
     * trailers that follow the one dropped.
     *
     * @param tractor
     *            - the <code>Entity</code> that is disconnecting the trailer.
     * @param unloaded
     *            - the <code>Targetable</code> unit being unloaded.
     * @param pos
     *            - the <code>Coords</code> for the unloaded unit.
     * @return <code>true</code> if the unit was successfully unloaded,
     *         <code>false</code> if the trailer isn't carried by tractor.
     */
    public boolean disconnectUnit(Entity tractor, Targetable unloaded, Coords pos) {

        // We can only unload Entities.
        Entity trailer;
        if (unloaded instanceof Entity) {
            trailer = (Entity) unloaded;
        } else {
            return false;
        }
        // disconnectUnit() updates anything behind 'trailer' too, so copy
        // the list of trailers before we alter it so entityUpdate() can be
        // run on all of them. Also, add the entity towing Trailer to the list
        List<Integer> trailerList = new ArrayList<>(trailer.getConnectedUnits());
        trailerList.add(trailer.getTowedBy());

        // Unload the unit.
        tractor.disconnectUnit(trailer.getId());

        // Update the tractor and all affected trailers.
        for (int id : trailerList) {
            entityManager.entityUpdate(id);
        }
        entityManager.entityUpdate(trailer.getId());
        entityManager.entityUpdate(tractor.getId());

        // Unloaded successfully.
        return true;
    }

    public boolean unloadUnit(Entity unloader, Targetable unloaded, Coords pos, int facing, int elevation) {
        return unloadUnit(unloader, unloaded, pos, facing, elevation, false,
                false);
    }

    /**
     * Have the unloader unload the indicated unit. The unit being unloaded may
     * or may not gain a turn
     *
     * @param unloader
     *            - the <code>Entity</code> that is unloading the unit.
     * @param unloaded
     *            - the <code>Targetable</code> unit being unloaded.
     * @param pos
     *            - the <code>Coords</code> for the unloaded unit.
     * @param facing
     *            - the <code>int</code> facing for the unloaded unit.
     * @param elevation
     *            - the <code>int</code> elevation at which to unload, if both
     *            loader and loaded units use VTOL movement.
     * @param evacuation
     *            - a <code>boolean</code> indicating whether this unit is being
     *            unloaded as a result of its carrying units destruction
     * @return <code>true</code> if the unit was successfully unloaded,
     *         <code>false</code> if the unit isn't carried in unloader.
     */
    public boolean unloadUnit(Entity unloader, Targetable unloaded, Coords pos, int facing, int elevation,
                              boolean evacuation, boolean duringDeployment) {

        // We can only unload Entities.
        Entity unit;
        if (unloaded instanceof Entity) {
            unit = (Entity) unloaded;
        } else {
            return false;
        }

        // Unload the unit.
        if (!unloader.unload(unit)) {
            return false;
        }

        // The unloaded unit is no longer being carried.
        unit.setTransportId(Entity.NONE);

        // Place the unloaded unit onto the screen.
        unit.setPosition(pos);

        // Units unloaded onto the screen are deployed.
        if (pos != null) {
            unit.setDeployed(true);
        }

        // Point the unloaded unit in the given direction.
        unit.setFacing(facing);
        unit.setSecondaryFacing(facing);

        IHex hex = game.getBoard().getHex(pos);
        boolean isBridge = (hex != null) && hex.containsTerrain(Terrains.PAVEMENT);

        if (hex == null) {
            unit.setElevation(elevation);
        } else if (unloader.getMovementMode() == EntityMovementMode.VTOL) {
            if (unit.getMovementMode() == EntityMovementMode.VTOL) {
                // Flying units unload to the same elevation as the flying
                // transport
                unit.setElevation(elevation);
            } else if (game.getBoard().getBuildingAt(pos) != null) {
                // non-flying unit unloaded from a flying onto a building
                // -> sit on the roof
                unit.setElevation(hex.terrainLevel(Terrains.BLDG_ELEV));
            } else {
                while (elevation >= -hex.depth()) {
                    if (unit.isElevationValid(elevation, hex)) {
                        unit.setElevation(elevation);
                        break;
                    }
                    elevation--;
                    // If unit is landed, the while loop breaks before here
                    // And unit.moved will be MOVE_NONE
                    // If we can jump, use jump
                    if (unit.getJumpMP() > 0) {
                        unit.moved = EntityMovementType.MOVE_JUMP;
                    } else { // Otherwise, use walk trigger check for ziplines
                        unit.moved = EntityMovementType.MOVE_WALK;
                    }
                }
                if (!unit.isElevationValid(elevation, hex)) {
                    return false;
                }
            }
        } else if (game.getBoard().getBuildingAt(pos) != null) {
            // non flying unit unloading units into a building
            // -> sit in the building at the same elevation
            unit.setElevation(elevation);
        } else if (hex.terrainLevel(Terrains.WATER) > 0) {
            if ((unit.getMovementMode() == EntityMovementMode.HOVER)
                || (unit.getMovementMode() == EntityMovementMode.WIGE)
                || (unit.getMovementMode() == EntityMovementMode.HYDROFOIL)
                || (unit.getMovementMode() == EntityMovementMode.NAVAL)
                || (unit.getMovementMode() == EntityMovementMode.SUBMARINE)
                || (unit.getMovementMode() == EntityMovementMode.INF_UMU)
                || hex.containsTerrain(Terrains.ICE) || isBridge) {
                // units that can float stay on the surface, or we go on the
                // bridge
                // this means elevation 0, because elevation is relative to the
                // surface
                unit.setElevation(0);
            }
        } else {
            // default to the floor of the hex.
            // unit elevation is relative to the surface
            unit.setElevation(hex.floor() - hex.surface());
        }

        // Check for zip lines PSR -- MOVE_WALK implies ziplines
        if (unit.moved == EntityMovementType.MOVE_WALK) {
            if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_TACOPS_ZIPLINES)
                    && (unit instanceof Infantry)
                    && !((Infantry) unit).isMechanized()) {

               // Handle zip lines
                PilotingRollData psr = getEjectModifiers(game, unit, 0, false,
                        unit.getPosition(), "Anti-mek skill");
                // Factor in Elevation
                if (unloader.getElevation() > 0) {
                    psr.addModifier(unloader.getElevation(), "elevation");
                }
                int roll = Compute.d6(2);

                // Report ziplining
                reportmanager.addReport(ReportFactory.createReport(9920, 0, unit));

                // Report TN
                Report r = ReportFactory.createReport(9921, 0, unit, psr.getValue(), roll);
                r.add(psr.getDesc());
                reportmanager.addReport(r);

                if (roll < psr.getValue()) { // Failure!
                    reportmanager.addReport(ReportFactory.createReport(9923, 0, unit, psr.getValue(), roll));

                    HitData hit = unit.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                    hit.setIgnoreInfantryDoubleDamage(true);
                    reportmanager.addReport(damageEntity(unit, hit, 5));
                } else { //  Report success
                    reportmanager.addReport(ReportFactory.createReport(9922, unit, psr.getValue(), roll));
                }
                reportmanager.addNewLines();
            } else {
                return false;
            }
        }

        reportmanager.addReport(doSetLocationsExposure(unit, hex, false, unit.getElevation()));

        // unlike other unloaders, entities unloaded from droppers can still
        // move (unless infantry)
        if (!evacuation && (unloader instanceof SmallCraft) && !(unit instanceof Infantry)) {
            unit.setUnloaded(false);
            unit.setDone(false);

            // unit uses half of walk mp and is treated as moving one hex
            unit.mpUsed = unit.getOriginalWalkMP() / 2;
            unit.delta_distance = 1;
        }

        // If we unloaded during deployment, allow a turn
        if (duringDeployment) {
            unit.setUnloaded(false);
            unit.setDone(false);
        }
        
        //Update the transport unit's passenger count, if it's a large craft
        if (unloader instanceof SmallCraft || unloader instanceof Jumpship) {
            //Don't add dropship crew to a jumpship or station's passenger list
            if (!unit.isLargeCraft()) {
                unloader.setNPassenger(Math.max(0, unloader.getNPassenger() - unit.getCrew().getSize()));
            }
        }

        // Update the unloaded unit.
        entityManager.entityUpdate(unit.getId());

        // Unloaded successfully.
        return true;
    }

    public void dropUnit(Entity drop, Entity entity, Coords curPos, int altitude) {
        // Unload the unit.
        entity.unload(drop);
        // The unloaded unit is no longer being carried.
        drop.setTransportId(Entity.NONE);

        // OK according to Welshman's pending ruling, when on the ground map
        // units should be deployed in the ring two hexes away from the DropShip
        // optimally, we should let people choose here, but that would be
        // complicated
        // so for now I am just going to distribute them. I will give each unit
        // the first
        // emptiest hex that has no water or magma in it.
        // I will start the circle based on the facing of the dropper
        // Spheroid - facing
        // Aerodyne - opposite of facing
        // http://www.classicbattletech.com/forums/index.php?topic=65600.msg1568089#new
        if (game.getBoard().onGround() && (null != curPos)) {
            boolean selected = false;
            int count;
            int max = 0;
            int facing = entity.getFacing();
            if (entity.getMovementMode() == EntityMovementMode.AERODYNE) {
                // no real rule for this but it seems to make sense that units
                // would drop behind an
                // aerodyne rather than in front of it
                facing = (facing + 3) % 6;
            }
            boolean checkDanger = true;
            while (!selected) {
                // we can get caught in an infinite loop if all available hexes
                // are dangerous, so check for this
                boolean allDanger = true;
                for (int i = 0; i < 6; i++) {
                    int dir = (facing + i) % 6;
                    Coords newPos = curPos.translated(dir, 2);
                    count = 0;
                    if (game.getBoard().contains(newPos)) {
                        IHex newHex = game.getBoard().getHex(newPos);
                        Building bldg = game.getBoard().getBuildingAt(newPos);
                        boolean danger = newHex.containsTerrain(Terrains.WATER)
                                         || newHex.containsTerrain(Terrains.MAGMA)
                                         || (null != bldg);
                        for (Entity unit : game.getEntitiesVector(newPos)) {
                            if ((unit.getAltitude() == altitude) && !unit.isAero()) {
                                count++;
                            }
                        }
                        if ((count <= max) && (!danger || !checkDanger)) {
                            selected = true;
                            curPos = newPos;
                            break;
                        }
                        if (!danger) {
                            allDanger = false;
                        }
                    }
                    newPos = newPos.translated((dir + 2) % 6);
                    count = 0;
                    if (game.getBoard().contains(newPos)) {
                        IHex newHex = game.getBoard().getHex(newPos);
                        Building bldg = game.getBoard().getBuildingAt(newPos);
                        boolean danger = newHex.containsTerrain(Terrains.WATER)
                                         || newHex.containsTerrain(Terrains.MAGMA)
                                         || (null != bldg);
                        for (Entity unit : game.getEntitiesVector(newPos)) {
                            if ((unit.getAltitude() == altitude) && !unit.isAero()) {
                                count++;
                            }
                        }
                        if ((count <= max) && (!danger || !checkDanger)) {
                            selected = true;
                            curPos = newPos;
                            break;
                        }
                        if (!danger) {
                            allDanger = false;
                        }
                    }
                }
                if (allDanger && checkDanger) {
                    checkDanger = false;
                } else {
                    max++;
                }
            }
        }

        // Place the unloaded unit onto the screen.
        drop.setPosition(curPos);

        // Units unloaded onto the screen are deployed.
        if (curPos != null) {
            drop.setDeployed(true);
        }

        // Point the unloaded unit in the given direction.
        drop.setFacing(entity.getFacing());
        drop.setSecondaryFacing(entity.getFacing());

        drop.setAltitude(altitude);
        entityManager.entityUpdate(drop.getId());
    }

    /**
     * Record that the given building has been affected by the current entity's
     * movement. At the end of the entity's movement, notify the clients about
     * the updates.
     *
     * @param bldg     - the <code>Building</code> that has been affected.
     * @param collapse - a <code>boolean</code> value that specifies that the
     *                 building collapsed (when <code>true</code>).
     */
    public void addAffectedBldg(Building bldg, boolean collapse) {
        // If the building collapsed, then the clients have already
        // been notified, so remove it from the notification list.
        if (collapse) {
            affectedBldgs.remove(bldg);
        } else { // Otherwise, make sure that this building is tracked.
            affectedBldgs.put(bldg, Boolean.FALSE);
        }
    }

    /**
     * Walk through the building hexes that were affected by the recent entity's
     * movement. Notify the clients about the updates to all affected entities
     * and non-collapsed buildings. The affected hexes is then cleared for the
     * next entity's movement.
     */
    private void applyAffectedBldgs() {
        // Build a list of Building updates.
        Vector<Building> bldgUpdates = new Vector<>();

        // Only send a single turn update.
        boolean bTurnsChanged = false;

        // Walk the set of buildings.
        for (Building bldg : affectedBldgs.keySet()) {
            // Walk through the building's coordinates.
            Vector<Coords> bldgCoords = bldg.getCoordsVector();
            for (Coords coords : bldgCoords) {
                // Walk through the entities at these coordinates.
                for (Entity entity : game.getEntitiesVector(coords)) {
                    // Is the entity infantry?
                    if (entity instanceof Infantry) {
                        // Is the infantry dead?
                        if (entity.isDoomed() || entity.isDestroyed()) {
                            // Has the entity taken a turn?
                            if (!entity.isDone()) {
                                // Dead entities don't take turns.
                                game.removeTurnFor(entity);
                                bTurnsChanged = true;
                            } // End entity-still-to-move

                            // Clean out the dead entity.
                            entity.setDestroyed(true);
                            game.moveToGraveyard(entity.getId());
                            send(PacketFactory.createRemoveEntityPacket(entity.getId()));
                        } else { // Infantry that aren't dead are damaged.
                            entityManager.entityUpdate(entity.getId());
                        }
                    } // End entity-is-infantry
                } // Check the next entity.
            } // Handle the next hex in this building.
            // Add this building to the report.
            bldgUpdates.addElement(bldg);
        } // Handle the next affected building.

        // Did we update the turns?
        if (bTurnsChanged) {
            send(PacketFactory.createTurnVectorPacket(game));
        }

        // Are there any building updates?
        if (!bldgUpdates.isEmpty()) {
            // Send the building updates to the clients.
            sendChangedBuildings(bldgUpdates);

            // Clear the list of affected buildings.
            affectedBldgs.clear();
        }

        // And we're done.
    } // End private void applyAffectedBldgs()

    private int convertHitSideToTable(int hitSide) {
        hitSide %= 6;
        int table = 0;
        // quite hackish...I think it ought to work, though.
        switch (hitSide) {
            case 0:// can this happen?
                table = ToHitData.SIDE_FRONT;
                break;
            case 1:
            case 2:
                table = ToHitData.SIDE_LEFT;
                break;
            case 3:
                table = ToHitData.SIDE_REAR;
                break;
            case 4:
            case 5:
                table = ToHitData.SIDE_RIGHT;
                break;
        }
        return table;
    }

    /**
     * makes a unit skid or sideslip on the board
     *
     * @param entity    the unit which should skid
     * @param start     the coordinates of the hex the unit was in prior to skidding
     * @param elevation the elevation of the unit
     * @param direction the direction of the skid
     * @param distance  the number of hexes skidded
     * @param step      the MoveStep which caused the skid
     * @return true if the entity was removed from play
     */
    public boolean processSkid(Entity entity, Coords start, int elevation, int direction, int distance, MoveStep step,
                                EntityMovementType moveType) {
        return processSkid(entity, start, elevation, direction, distance, step, moveType, false);
    }

    /**
     * makes a unit skid or sideslip on the board
     *
     * @param entity    the unit which should skid
     * @param start     the coordinates of the hex the unit was in prior to skidding
     * @param elevation the elevation of the unit
     * @param direction the direction of the skid
     * @param distance  the number of hexes skidded
     * @param step      the MoveStep which caused the skid
     * @param flip      whether the skid resulted from a failure maneuver result of major skid
     * @return true if the entity was removed from play
     */
    public boolean processSkid(Entity entity, Coords start, int elevation, int direction, int distance, MoveStep step,
                                EntityMovementType moveType, boolean flip) {
        Coords nextPos = start;
        Coords curPos = nextPos;
        IHex curHex = game.getBoard().getHex(start);
        int skidDistance = 0; // actual distance moved
        // Flipping vehicles take tonnage/10 points of damage for every hex they enter.
        int flipDamage = (int) Math.ceil(entity.getWeight() / 10.0);
        while (!entity.isDoomed() && (distance > 0)) {
            nextPos = curPos.translated(direction);
            // Is the next hex off the board?
            if (!game.getBoard().contains(nextPos)) {

                // Can the entity skid off the map?
                if (game.getOptions().booleanOption(OptionsConstants.BASE_PUSH_OFF_BOARD)) {
                    // Yup. One dead entity.
                    game.removeEntity(entity.getId(), IEntityRemovalConditions.REMOVE_PUSHED);
                    send(PacketFactory.createRemoveEntityPacket(entity.getId(), IEntityRemovalConditions.REMOVE_PUSHED));
                    reportmanager.addReport(ReportFactory.createPublicReport(2030, entity));

                    for (Entity e : entity.getLoadedUnits()) {
                        game.removeEntity(e.getId(), IEntityRemovalConditions.REMOVE_PUSHED);
                        send(PacketFactory.createRemoveEntityPacket(e.getId(), IEntityRemovalConditions.REMOVE_PUSHED));
                    }
                    Entity swarmer = game.getEntity(entity.getSwarmAttackerId());
                    if (swarmer != null) {
                        if (!swarmer.isDone()) {
                            game.removeTurnFor(swarmer);
                            swarmer.setDone(true);
                            send(PacketFactory.createTurnVectorPacket(game));
                        }
                        game.removeEntity(swarmer.getId(), IEntityRemovalConditions.REMOVE_PUSHED);
                        send(PacketFactory.createRemoveEntityPacket(swarmer.getId(), IEntityRemovalConditions.REMOVE_PUSHED));
                    }
                    // The entity's movement is completed.
                    return true;

                }
                // Nope. Update the report.
                reportmanager.addReport(ReportFactory.createReport(2035, 1, entity));
                // Stay in the current hex and stop skidding.
                break;
            }

            IHex nextHex = game.getBoard().getHex(nextPos);
            distance -= nextHex.movementCost(entity) + 1;
            // By default, the unit is going to fall to the floor of the next
            // hex
            int curAltitude = elevation + curHex.getLevel();
            int nextAltitude = nextHex.floor();

            // but VTOL keep altitude
            if (entity.getMovementMode() == EntityMovementMode.VTOL) {
                nextAltitude = Math.max(nextAltitude, curAltitude);
            } else {
                // Is there a building to "catch" the unit?
                if (nextHex.containsTerrain(Terrains.BLDG_ELEV)) {
                    // unit will land on the roof, if at a higher level,
                    // otherwise it will skid through the wall onto the same
                    // floor.
                    // don't change this if the building starts at an elevation
                    // higher than the unit
                    // (e.g. the building is on a hill). Otherwise, we skid into
                    // solid earth.
                    if (curAltitude >= nextHex.floor()) {
                        nextAltitude = Math.min(curAltitude, nextHex.getLevel() + nextHex.terrainLevel(Terrains.BLDG_ELEV));
                    }
                }
                // Is there a bridge to "catch" the unit?
                if (nextHex.containsTerrain(Terrains.BRIDGE)) {
                    // unit will land on the bridge, if at a higher level,
                    // and the bridge exits towards the current hex,
                    // otherwise the bridge has no effect
                    int exitDir = (direction + 3) % 6;
                    exitDir = 1 << exitDir;
                    if ((nextHex.getTerrain(Terrains.BRIDGE).getExits() & exitDir) == exitDir) {
                        nextAltitude = Math.min(curAltitude, Math.max(nextAltitude, nextHex.getLevel() + nextHex.terrainLevel(Terrains.BRIDGE_ELEV)));
                    }
                }
                if ((nextAltitude <= nextHex.surface()) && (curAltitude >= curHex.surface())) {
                    // Hovercraft and WiGEs can "skid" over water.
                    // all units can skid over ice.
                    if ((entity.getMovementMode().equals(EntityMovementMode.HOVER)
                            || entity.getMovementMode().equals(EntityMovementMode.WIGE)) && nextHex.containsTerrain(Terrains.WATER)) {
                        nextAltitude = nextHex.surface();
                    } else if (nextHex.containsTerrain(Terrains.ICE)) {
                        nextAltitude = nextHex.surface();
                    }

                }
                if (entity.getMovementMode() == EntityMovementMode.WIGE && elevation > 0 && nextAltitude < curAltitude) {
                    // Airborne WiGEs drop to one level above the surface
                    if (entity.climbMode()) {
                        nextAltitude = curAltitude;
                    } else {
                        nextAltitude++;
                    }
                }
            }

            // The elevation the skidding unit will occupy in next hex
            int nextElevation = nextAltitude - nextHex.surface();

            boolean crashedIntoTerrain = curAltitude < nextAltitude;
            if (entity.getMovementMode() == EntityMovementMode.VTOL
                    && (nextHex.containsTerrain(Terrains.WOODS) || nextHex.containsTerrain(Terrains.JUNGLE))
                    && nextElevation <= nextHex.terrainLevel(Terrains.FOLIAGE_ELEV)) {
                    crashedIntoTerrain = true;
            }

            if (nextHex.containsTerrain(Terrains.BLDG_ELEV)) {
                Building bldg = game.getBoard().getBuildingAt(nextPos);

                if (bldg.getType() == Building.WALL || bldg.getBldgClass() == Building.GUN_EMPLACEMENT) {
                    crashedIntoTerrain = true;
                }
            }

            // however WiGE can gain 1 level to avoid crashing into the terrain.
            if (entity.getMovementMode() == EntityMovementMode.WIGE && (elevation > 0)) {
                if (curAltitude == nextHex.floor()) {
                    nextElevation = 1;
                    crashedIntoTerrain = false;
                } else if ((entity instanceof LandAirMech) && (curAltitude + 1 == nextHex.floor())) {
                    // LAMs in AirMech mode skid across terrain that is two levels higher rather than crashing,
                    // Reset the skid distance for skid damage calculations.
                    nextElevation = 0;
                    skidDistance = 0;
                    crashedIntoTerrain = false;
                    reportmanager.addReport(ReportFactory.createReport(2102, 1, entity));
                }
            }

            Entity crashDropShip = null;
            for (Entity en : game.getEntitiesVector(nextPos)) {
                if ((en instanceof Dropship) && !en.isAirborne() && (nextAltitude <= (en.relHeight()))) {
                    crashDropShip = en;
                }
            }

            if (crashedIntoTerrain) {
                int reportID;
                if (nextHex.containsTerrain(Terrains.BLDG_ELEV)) {
                    Building bldg = game.getBoard().getBuildingAt(nextPos);

                    // If you crash into a wall you want to stop in the hex
                    // before the wall not in the wall
                    // Like a building.
                    if (bldg.getType() == Building.WALL) {
                        reportID = 2047;
                    } else if (bldg.getBldgClass() == Building.GUN_EMPLACEMENT) {
                        reportID = 2049;
                    } else {
                        reportID = 2045;
                    }

                } else {
                    reportID = 2045;
                }
                reportmanager.addReport(ReportFactory.createReport(reportID, 1, entity, nextPos.getBoardNum()));

                if ((entity.getMovementMode() == EntityMovementMode.WIGE)
                        || (entity.getMovementMode() == EntityMovementMode.VTOL)) {
                    int hitSide = (step.getFacing() - direction) + 6;
                    int table = convertHitSideToTable(hitSide);
                    elevation = nextElevation;
                    if (entity instanceof Tank) {
                        reportmanager.addReport(crashVTOLorWiGE((Tank) entity, false, true,
                                distance, curPos, elevation, table));
                    }

                    if ((nextHex.containsTerrain(Terrains.WATER) && !nextHex.containsTerrain(Terrains.ICE))
                            || nextHex.containsTerrain(Terrains.WOODS)
                            || nextHex.containsTerrain(Terrains.JUNGLE)) {
                        reportmanager.addReport(entityManager.destroyEntity(entity, "could not land in crash site"));
                    } else if (elevation < nextHex.terrainLevel(Terrains.BLDG_ELEV)) {
                        Building bldg = game.getBoard().getBuildingAt(nextPos);

                        // If you crash into a wall you want to stop in the hex
                        // before the wall not in the wall
                        // Like a building.
                        if (bldg.getType() == Building.WALL) {
                            reportmanager.addReport(entityManager.destroyEntity(entity, "crashed into a wall"));
                            break;
                        }
                        if (bldg.getBldgClass() == Building.GUN_EMPLACEMENT) {
                            reportmanager.addReport(entityManager.destroyEntity(entity, "crashed into a gun emplacement"));
                            break;
                        }

                        reportmanager.addReport(entityManager.destroyEntity(entity, "crashed into building"));
                    } else {
                        entity.setPosition(nextPos);
                        entity.setElevation(0);
                        reportmanager.addReport(doEntityDisplacementMinefieldCheck(entity, nextPos, nextElevation));
                    }
                    break;

                }
                // skidding into higher terrain does weight/20
                // damage in 5pt clusters to front.
                int damage = ((int) entity.getWeight() + 19) / 20;
                while (damage > 0) {
                    int table = ToHitData.HIT_NORMAL;
                    int side = entity.sideTable(nextPos);
                    if (entity instanceof Protomech) {
                        table = ToHitData.HIT_SPECIAL_PROTO;
                    }
                    HitData hitData = entity.rollHitLocation(table, side);
                    hitData.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                    reportmanager.addReport(damageEntity(entity, hitData, Math.min(5, damage)));
                    damage -= 5;
                }
                // Stay in the current hex and stop skidding.
                break;
            }

            // did we hit a DropShip. Oww!
            // Taharqa: The rules on how to handle this are completely missing, so I am assuming
            // we assign damage as per an accidental charge, but do not displace
            // the DropShip and end the skid
            else if (null != crashDropShip) {
                reportmanager.addReport(ReportFactory.createReport(2050, 1, entity, crashDropShip.getShortName(), nextPos.getBoardNum()));
                ChargeAttackAction caa = new ChargeAttackAction(entity.getId(),
                        crashDropShip.getTargetType(),
                        crashDropShip.getTargetId(),
                        crashDropShip.getPosition());
                ToHitData toHit = caa.toHit(game, true);
                resolveChargeDamage(entity, crashDropShip, toHit, direction);
                if ((entity.getMovementMode() == EntityMovementMode.WIGE)
                        || (entity.getMovementMode() == EntityMovementMode.VTOL)) {
                    int hitSide = (step.getFacing() - direction) + 6;
                    int table = convertHitSideToTable(hitSide);
                    elevation = nextElevation;
                    reportmanager.addReport(crashVTOLorWiGE((VTOL) entity, false, true,
                            distance, curPos, elevation, table));
                    break;
                }
                if (!crashDropShip.isDoomed() && !crashDropShip.isDestroyed() && !game.isOutOfGame(crashDropShip)) {
                    break;
                }
            }

            // Have skidding units suffer falls (off a cliff).
            else if ( (curAltitude > (nextAltitude + entity.getMaxElevationChange())
                    || (curHex.hasCliffTopTowards(nextHex) && curAltitude > nextAltitude) )
                    && !(entity.getMovementMode() == EntityMovementMode.WIGE && elevation > curHex.ceiling())) {
                reportmanager.addReport(doEntityFallsInto(entity, entity.getElevation(), curPos, nextPos,
                        entity.getBasePilotingRoll(moveType), true));
                reportmanager.addReport(doEntityDisplacementMinefieldCheck(entity, nextPos, nextElevation));
                // Stay in the current hex and stop skidding.
                break;
            }

            // Get any building in the hex.
            Building bldg = null;
            if (nextElevation < nextHex.terrainLevel(Terrains.BLDG_ELEV)) {
                // We will only run into the building if its at a higher level,
                // otherwise we skid over the roof
                bldg = game.getBoard().getBuildingAt(nextPos);
            }
            boolean bldgSuffered = false;
            boolean stopTheSkid = false;
            // Does the next hex contain an entities?
            // ASSUMPTION: hurt EVERYONE in the hex.
            List<Entity> avoidedChargeUnits = new ArrayList<>();
            boolean skidChargeHit = false;

            Iterator<Entity> targets = game.getEntities(nextPos);
            while (targets.hasNext()) {
                Entity target = targets.next();

                if ((target.getElevation() > (nextElevation + entity.getHeight()))
                        || (target.relHeight() < nextElevation)) {
                    // target is not in the way
                    continue;
                }

                // Can the target avoid the skid?
                if (!target.isDone()) {
                    if (target instanceof Infantry
                            || (target instanceof Protomech && target != Compute.stackingViolation(game, entity, nextPos, null))) {
                        reportmanager.addReport(ReportFactory.createReport(2420, target));
                        continue;
                    } else {
                        PilotingRollData psr = target.getBasePilotingRoll();
                        psr.addModifier(0, "avoiding collision");
                        if (psr.getValue() == TargetRoll.AUTOMATIC_FAIL || psr.getValue() == TargetRoll.IMPOSSIBLE) {
                            reportmanager.addReport(ReportFactory.createReport(2426, target, psr.getDesc()));
                        } else {
                            int roll = Compute.d6(2);
                            Report r = ReportFactory.createReport(2425, target, psr.getValue(), roll);
                            r.add(psr.getDesc());
                            reportmanager.addReport(r);
                            if (roll >= psr.getValue()) {
                                game.removeTurnFor(target);
                                avoidedChargeUnits.add(target);
                                continue;
                                // TODO : the charge should really be suspended
                                // and resumed after the target moved.
                            }
                        }
                    }
                }

                // Mechs and vehicles get charged, but need to make a to-hit roll
                if ((target instanceof Mech) || (target instanceof Tank) || (target instanceof Aero)) {
                    ChargeAttackAction caa = new ChargeAttackAction(entity.getId(), target.getTargetType(),
                            target.getTargetId(), target.getPosition());
                    ToHitData toHit = caa.toHit(game, true);

                    // roll
                    int roll = Compute.d6(2);
                    // Update report.
                    reportmanager.addReport(ReportFactory.createReport(2050, 1, entity, target.getShortName(), nextPos.getBoardNum()));
                    Report r;
                    if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
                        roll = -12;
                        r = ReportFactory.createReport(2055, entity, toHit.getDesc());
                    } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
                        r = ReportFactory.createReport(2060, entity, toHit.getDesc());
                        roll = Integer.MAX_VALUE;
                    } else {
                        // report the roll
                        r = ReportFactory.createReport(2065, entity, toHit.getValue(), roll);
                    }
                    reportmanager.addReport(r);

                    // Resolve a charge against the target.
                    // ASSUMPTION: buildings block damage for
                    // *EACH* entity charged.
                    if (roll < toHit.getValue()) {
                        reportmanager.addReport(ReportFactory.createReport(2070, entity));
                    } else {
                        // Resolve the charge.
                        resolveChargeDamage(entity, target, toHit, direction);
                        // HACK: set the entity's location
                        // to the original hex again, for the other targets
                        if (targets.hasNext()) {
                            entity.setPosition(curPos);
                        }
                        bldgSuffered = true;
                        skidChargeHit = true;
                        // The skid ends here if the target lives.
                        if (!target.isDoomed() && !target.isDestroyed() && !game.isOutOfGame(target)) {
                            stopTheSkid = true;
                        }
                    }

                    // if we don't do this here, we can have a mech without a leg
                    // standing on the field and moving as if it still had his leg after
                    // getting skid-charged.
                    if (!target.isDone()) {
                        reportmanager.addReport(resolvePilotingRolls(target));
                        game.resetPSRs(target);
                        target.applyDamage();
                        reportmanager.addNewLines();
                    }
                }

                // Resolve "move-through" damage on infantry.
                // Infantry inside of a building don't get a
                // move-through, but suffer "bleed through"
                // from the building.
                else if ((target instanceof Infantry) && (bldg != null)) {
                    // Update report.
                    reportmanager.addReport(ReportFactory.createReport(2075, 1, entity, target.getShortName(), nextPos.getBoardNum()));

                    // Infantry don't have different
                    // tables for punches and kicks
                    HitData hit = target.rollHitLocation(ToHitData.HIT_NORMAL, Compute.targetSideTable(entity, target));
                    hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                    // Damage equals tonnage, divided by 5.
                    // ASSUMPTION: damage is applied in one hit.
                    reportmanager.addReport(damageEntity(target, hit, (int) Math.round(entity.getWeight() / 5)));
                    reportmanager.addNewLines();
                }

                // Has the target been destroyed?
                if (target.isDoomed()) {
                    // Has the target taken a turn?
                    if (!target.isDone()) {
                        // Dead entities don't take turns.
                        game.removeTurnFor(target);
                        send(PacketFactory.createTurnVectorPacket(game));
                    } // End target-still-to-move

                    // Clean out the entity.
                    target.setDestroyed(true);
                    game.moveToGraveyard(target.getId());
                    send(PacketFactory.createRemoveEntityPacket(target.getId()));
                }
                // Update the target's position,
                // unless it is off the game map.
                if (!game.isOutOfGame(target)) {
                    entityManager.entityUpdate(target.getId());
                }
            } // Check the next entity in the hex.

            if (skidChargeHit) {
                // HACK: set the entities position to that
                // hex's coords, because we had to move the entity
                // back earlier for the other targets
                entity.setPosition(nextPos);
            }
            for (Entity e : avoidedChargeUnits) {
                GameTurn newTurn = new GameTurn.SpecificEntityTurn(e.getOwner().getId(), e.getId());
                // Prevents adding extra turns for multi-turns
                newTurn.setMultiTurn(true);
                game.insertNextTurn(newTurn);
                send(PacketFactory.createTurnVectorPacket(game));
            }

            // Handle the building in the hex.
            if (bldg != null) {
                // Report that the entity has entered the bldg.
                reportmanager.addReport(ReportFactory.createReport(2080, 1, entity, bldg.getName(), nextPos.getBoardNum()));

                // If the building hasn't already suffered
                // damage, then apply charge damage to the
                // building and displace the entity inside.
                // ASSUMPTION: you don't charge the building
                // if Tanks or Mechs were charged.
                int chargeDamage = ChargeAttackAction.getDamageFor(entity, game
                        .getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_CHARGE_DAMAGE),
                        entity.delta_distance);
                if (!bldgSuffered) {
                    Vector<Report> reports = damageBuilding(bldg, chargeDamage, nextPos);
                    for (Report report : reports) {
                        report.subject = entity.getId();
                    }
                    reportmanager.addReport(reports);

                    // Apply damage to the attacker.
                    int toAttacker = ChargeAttackAction.getDamageTakenBy(entity, bldg, nextPos);
                    HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, entity.sideTable(nextPos));
                    hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                    reportmanager.addReport(damageEntity(entity, hit, toAttacker));
                    reportmanager.addNewLines();

                    entity.setPosition(nextPos);
                    entity.setElevation(nextElevation);
                    reportmanager.addReport(doEntityDisplacementMinefieldCheck(entity, nextPos, nextElevation));
                    curPos = nextPos;
                } // End buildings-suffer-too

                // Any infantry in the building take damage
                // equal to the building being charged.
                // ASSUMPTION: infantry take no damage from the
                // building absorbing damage from
                // Tanks and Mechs being charged.
                reportmanager.addReport(damageInfantryIn(bldg, chargeDamage, nextPos));

                // If a building still stands, then end the skid,
                // and add it to the list of affected buildings.
                if (bldg.getCurrentCF(nextPos) > 0) {
                    stopTheSkid = true;
                    if (bldg.rollBasement(nextPos, game.getBoard(), reportmanager.getvPhaseReport())) {
                        gamemanager.sendChangedHex(game, nextPos);
                        Vector<Building> buildings = new Vector<>();
                        buildings.add(bldg);
                        sendChangedBuildings(buildings);
                    }
                    addAffectedBldg(bldg, checkBuildingCollapseWhileMoving(bldg, entity, nextPos));
                } else {
                    // otherwise it collapses immediately on our head
                    checkForCollapse(bldg, game.getPositionMap(), nextPos, true, reportmanager.getvPhaseReport());
                }
            } // End handle-building.

            // Do we stay in the current hex and stop skidding?
            if (stopTheSkid) {
                break;
            }

            // Update entity position and elevation
            entity.setPosition(nextPos);
            entity.setElevation(nextElevation);
            reportmanager.addReport(doEntityDisplacementMinefieldCheck(entity, nextPos, nextElevation));
            skidDistance++;

            // Check for collapse of any building the entity might be on
            Building roof = game.getBoard().getBuildingAt(nextPos);
            if (roof != null) {
                if (checkForCollapse(roof, game.getPositionMap(), nextPos, true, reportmanager.getvPhaseReport())) {
                    break; // stop skidding if the building collapsed
                }
            }

            // Can the skidding entity enter the next hex from this?
            // N.B. can skid along roads.
            if ((entity.isLocationProhibited(start) || entity.isLocationProhibited(nextPos))
                    && !Compute.canMoveOnPavement(game, curPos, nextPos, step)) {
                // Update report.
                reportmanager.addReport(ReportFactory.createReport(2040, 1, entity, nextPos.getBoardNum()));

                // If the prohibited terrain is water, entity is destroyed
                if ((nextHex.terrainLevel(Terrains.WATER) > 0)
                        && (entity instanceof Tank)
                        && (entity.getMovementMode() != EntityMovementMode.HOVER)
                        && (entity.getMovementMode() != EntityMovementMode.WIGE)) {
                    reportmanager.addReport(entityManager.destroyEntity(entity,
                            "skidded into a watery grave", false, true));
                }

                // otherwise, damage is weight/5 in 5pt clusters
                int damage = ((int) entity.getWeight() + 4) / 5;
                while (damage > 0) {
                    reportmanager.addReport(damageEntity(entity, entity.rollHitLocation(
                            ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT),
                            Math.min(5, damage)));
                    damage -= 5;
                }
                // and unit is immobile
                if (entity instanceof Tank) {
                    ((Tank) entity).immobilize();
                }

                // Stay in the current hex and stop skidding.
                break;
            }

            if ((nextHex.terrainLevel(Terrains.WATER) > 0)
                    && (entity.getMovementMode() != EntityMovementMode.HOVER)
                    && (entity.getMovementMode() != EntityMovementMode.WIGE)) {
                // water ends the skid
                break;
            }

            // check for breaking magma crust
            // note that this must sequentially occur before the next 'entering liquid magma' check
            // otherwise, magma crust won't have a chance to break
            ServerHelper.checkAndApplyMagmaCrust(nextHex, nextElevation, entity, curPos, false, reportmanager.getvPhaseReport());

            // is the next hex a swamp?
            PilotingRollData rollTarget = entity.checkBogDown(step, moveType, nextHex, curPos, nextPos,
                    step.getElevation(), Compute.canMoveOnPavement(game, curPos, nextPos, step));

            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                // Taharqa: According to TacOps, you automatically stick if you
                // are skidding, (pg. 63)
                // if (0 < doSkillCheckWhileMoving(entity, curPos, nextPos,
                // rollTarget, false)) {
                entity.setStuck(true);
                reportmanager.addReport(ReportFactory.createReport(2081, entity, entity.getDisplayName()));
                // check for quicksand
                reportmanager.addReport(checkQuickSand(nextPos));
                // check for accidental stacking violation
                Entity violation = Compute.stackingViolation(game, entity.getId(), curPos);
                if (violation != null) {
                    // target gets displaced, because of low elevation
                    Coords targetDest = Compute.getValidDisplacement(game, entity.getId(), curPos, direction);
                    reportmanager.addReport(doEntityDisplacement(violation, curPos, targetDest,
                            new PilotingRollData(violation.getId(), 0, "domino effect")));
                    // Update the violating entity's position on the client.
                    entityManager.entityUpdate(violation.getId());
                }
                // stay here and stop skidding, see bug 1115608
                break;
            }

            // Update the position and keep skidding.
            curPos = nextPos;
            curHex = nextHex;
            elevation = nextElevation;
            reportmanager.addReport(ReportFactory.createReport(2085, 1, entity, curPos.getBoardNum()));

            if (flip && entity instanceof Tank) {
                doVehicleFlipDamage((Tank)entity, flipDamage, direction < 3, skidDistance - 1);
            }

        } // Handle the next skid hex.

        // If the skidding entity violates stacking,
        // displace targets until it doesn't.
        curPos = entity.getPosition();
        Entity target = Compute.stackingViolation(game, entity.getId(), curPos);
        while (target != null) {
            nextPos = Compute.getValidDisplacement(game, target.getId(), target.getPosition(), direction);
            // ASSUMPTION
            // There should always be *somewhere* that
            // the target can go... last skid hex if
            // nothing else is available.
            if (null == nextPos) {
                // But I don't trust the assumption fully.
                // Report the error and try to continue.
                MegaMek.getLogger().error("The skid of " + entity.getShortName() + " should displace "
                        + target.getShortName() + " in hex " + curPos.getBoardNum() + " but there is nowhere to go.");
                break;
            }
            // indent displacement
            reportmanager.addReport(ReportFactory.createPublicReport(1210, 1));
            reportmanager.addReport(doEntityDisplacement(target, curPos, nextPos, null));
            reportmanager.addReport(doEntityDisplacementMinefieldCheck(entity, nextPos, entity.getElevation()));
            target = Compute.stackingViolation(game, entity.getId(), curPos);
        }

        // Mechs suffer damage for every hex skidded.
        // For QuadVees in vehicle mode, apply
        // damage only if flipping.
        boolean mechDamage = ((entity instanceof Mech) && !((entity.getMovementMode() == EntityMovementMode.WIGE) && (entity.getElevation() > 0)));
        if (entity instanceof QuadVee && entity.getConversionMode() == QuadVee.CONV_MODE_VEHICLE) {
            mechDamage = flip;
        }
        if (mechDamage) {
            // Calculate one half falling damage times skid length.
            int damage = skidDistance * (int) Math.ceil(Math.round(entity.getWeight() / 10.0) / 2.0);

            // report skid damage
            reportmanager.addReport(ReportFactory.createReport(2090, 1, entity, damage));

            // standard damage loop
            // All skid damage is to the front.
            while (damage > 0) {
                int cluster = Math.min(5, damage);
                HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                reportmanager.addReport(damageEntity(entity, hit, cluster));
                damage -= cluster;
            }
            reportmanager.addNewLines();
        }

        if (flip && entity instanceof Tank) {
            reportmanager.addReport(applyCriticalHit(entity, Entity.NONE, new CriticalSlot(0, Tank.CRIT_CREW_STUNNED),
                    true, 0, false));
        } else if (flip && entity instanceof QuadVee && entity.getConversionMode() == QuadVee.CONV_MODE_VEHICLE) {
            // QuadVees don't suffer stunned crew criticals; require PSR to avoid damage instead.
            PilotingRollData prd = entity.getBasePilotingRoll();
            reportmanager.addReport(checkPilotAvoidFallDamage(entity, 1, prd));
        }

        // Clean up the entity if it has been destroyed.
        if (entity.isDoomed()) {
            entity.setDestroyed(true);
            game.moveToGraveyard(entity.getId());
            send(PacketFactory.createRemoveEntityPacket(entity.getId()));

            // The entity's movement is completed.
            return true;
        }

        // Let the player know the ordeal is over.
        reportmanager.addReport(ReportFactory.createReport(2095, 1, entity));

        return false;
    }

    private void doVehicleFlipDamage(Tank entity, int damage, boolean startRight, int flipCount) {
        HitData hit;

        int index = flipCount % 4;
        // If there is no turret, we do side-side-bottom
        if (entity.hasNoTurret()) {
            index = flipCount % 3;
            if (index > 0) {
                index++;
            }
        }
        switch (index) {
            case 0:
                hit = new HitData(startRight ? Tank.LOC_RIGHT : Tank.LOC_LEFT);
                break;
            case 1:
                hit = new HitData(Tank.LOC_TURRET);
                break;
            case 2:
                hit = new HitData(startRight ? Tank.LOC_LEFT : Tank.LOC_RIGHT);
                break;
            default:
                hit = null; //Motive damage instead
        }
        if (hit != null) {
            hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
            reportmanager.addReport(damageEntity(entity, hit, damage));
            // If the vehicle has two turrets, they both take full damage.
            if ((hit.getLocation() == Tank.LOC_TURRET) && !(entity.hasNoDualTurret())) {
                hit = new HitData(Tank.LOC_TURRET_2);
                hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                reportmanager.addReport(damageEntity(entity, hit, damage));
            }
        } else {
            reportmanager.addReport(vehicleMotiveDamage(entity, 1));
        }
    }

    public Vector<Report> processCrash(Entity entity, int vel, Coords c) {
        Vector<Report> vReport = new Vector<>();
        Report r;
        if (c == null) {
            vReport.add(ReportFactory.createReport(9701, entity));
            vReport.addAll(entityManager.destroyEntity(entity, "crashed off the map", true, true));
            return vReport;
        }

        if (game.getBoard().inAtmosphere()) {
            vReport.add(ReportFactory.createPublicReport(9393, 1, entity));
            entity.setDoomed(true);
        } else {
            ((IAero) entity).land();
        }

        // we might hit multiple hexes, if we're a DropShip, so we do some
        // checks for all of them
        List<Coords> coords = new ArrayList<>();
        coords.add(c);
        IHex h = game.getBoard().getHex(c);
        int crateredElevation;
        boolean containsWater = false;
        if (h.containsTerrain(Terrains.WATER)) {
            crateredElevation = Math.min(2, h.depth() + 1);
            containsWater = true;
        } else {
            crateredElevation = h.getLevel() - 2;
        }
        if (entity instanceof Dropship) {
            for (int i = 0; i < 6; i++) {
                Coords adjCoords = c.translated(i);
                if (!game.getBoard().contains(adjCoords)) {
                    continue;
                }
                IHex adjHex = game.getBoard().getHex(adjCoords);
                coords.add(adjCoords);
                if (adjHex.containsTerrain(Terrains.WATER)) {
                    if (containsWater) {
                        int newDepth = Math.min(2, adjHex.depth() + 1);
                        crateredElevation = Math.max(crateredElevation, newDepth);
                    } else {
                        crateredElevation = Math.min(2, adjHex.depth() + 1);
                        containsWater = true;
                    }
                } else if (!containsWater && (adjHex.getLevel() < crateredElevation)) {
                    crateredElevation = adjHex.getLevel();
                }
            }
        }
        // Units with velocity zero are treated like that had velocity two
        if (vel < 1) {
            vel = 2;
        }

        // deal crash damage only once
        boolean damageDealt = false;
        for (Coords hitCoords : coords) {
            int orig_crash_damage = Compute.d6(2) * 10 * vel;
            int crash_damage = orig_crash_damage;
            int direction = entity.getFacing();
            // first check for buildings
            Building bldg = game.getBoard().getBuildingAt(hitCoords);
            if ((null != bldg)) {
                collapseBuilding(bldg, game.getPositionMap(), hitCoords, true, vReport);
                if ((bldg.getType() == Building.HARDENED)) {
                    crash_damage *= 2;
                }
            }
            if (!damageDealt) {
                vReport.add(ReportFactory.createPublicReport(9700, 1, entity, crash_damage));
                while (crash_damage > 0) {
                    HitData hit;
                    if ((entity instanceof SmallCraft) && ((SmallCraft) entity).isSpheroid()) {
                        hit = entity.rollHitLocation(ToHitData.HIT_SPHEROID_CRASH, ToHitData.SIDE_REAR);
                    } else {
                        hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                    }

                    int damage = Math.min(crash_damage, 10);
                    vReport.addAll(damageEntity(entity, hit, damage));
                    crash_damage -= 10;
                }
                damageDealt = true;
            }

            // ok, now lets cycle through the entities in this spot and
            // potentially
            // damage them
            for (Entity victim : game.getEntitiesVector(hitCoords)) {
                if (victim.getId() == entity.getId()
                        || ((victim.getElevation() > 0) && victim.isAirborneVTOLorWIGE())
                        || (victim.getAltitude() > 0)) {
                    continue;
                }

                // if the crasher is a DropShip and the victim is not a mech,
                // then it is automatically destroyed
                if ((entity instanceof Dropship) && !(victim instanceof Mech)) {
                    vReport.addAll(entityManager.destroyEntity(victim, "hit by crashing DropShip"));
                } else {
                    crash_damage = orig_crash_damage / 2;
                    // roll dice to see if they got hit
                    int target = 2;
                    if (victim instanceof Infantry) {
                        target = 3;
                    }
                    int roll = Compute.d6();
                    r = ReportFactory.createPublicReport(9705, 1, victim, target, crash_damage, roll);
                    if (roll > target) {
                        r.choose(true);
                        vReport.add(r);
                        // apply half the crash damage in 5 point clusters
                        // (check
                        // hit tables)
                        while (crash_damage > 0) {
                            HitData hit = victim.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                            if (victim instanceof Mech) {
                                hit = victim.rollHitLocation(ToHitData.HIT_PUNCH, ToHitData.SIDE_FRONT);
                            }
                            if (victim instanceof Protomech) {
                                hit = victim.rollHitLocation(ToHitData.HIT_SPECIAL_PROTO, ToHitData.SIDE_FRONT);
                            }
                            crash_damage = Math.min(crash_damage, 5);
                            vReport.addAll(damageEntity(victim, hit, crash_damage));
                            crash_damage -= 5;
                        }

                    } else {
                        r.choose(false);
                        vReport.add(r);
                    }
                }

                if (!victim.isDoomed() && !victim.isDestroyed()) {
                    // entity displacement
                    Coords dest = Compute.getValidDisplacement(game, victim.getId(), hitCoords, direction);
                    if (null != dest) {
                        doEntityDisplacement(victim, hitCoords, dest,
                                new PilotingRollData(victim.getId(), 0, "crash"));
                    } else if (!(victim instanceof Dropship)) {
                        // destroy entity - but not dropships which are
                        // immovable
                        reportmanager.addReport(entityManager.destroyEntity(victim,
                                                "impossible displacement",
                                                victim instanceof Mech, victim instanceof Mech));
                    }
                }
            }

            // Initialize on zero because there is no terrain with value zero
            int terrain = 0;
            if (h.containsTerrain(Terrains.WOODS)) {
                terrain = Terrains.WOODS;
            } else if (h.containsTerrain(Terrains.JUNGLE)) {
                terrain = Terrains.JUNGLE;
            }

            // reduce woods
            h = game.getBoard().getHex(hitCoords);
            if (terrain != 0) {
                if (entity instanceof Dropship) {
                    h.removeTerrain(terrain);
                    h.removeTerrain(Terrains.FOLIAGE_ELEV);
                    h.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.ROUGH, 1));
                } else {
                    int level = h.terrainLevel(terrain) - 1;
                    int folEl = h.terrainLevel(Terrains.FOLIAGE_ELEV);
                    h.removeTerrain(terrain);
                    if (level > 0) {
                        h.addTerrain(Terrains.getTerrainFactory().createTerrain(terrain, level));
                        h.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.FOLIAGE_ELEV, folEl == 1 ? 1 : 2));
                    } else {
                        h.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.ROUGH, 1));
                        h.removeTerrain(Terrains.FOLIAGE_ELEV);
                    }
                }
            }
            if (entity instanceof Dropship) {
                if (!containsWater) {
                    h.setLevel(crateredElevation);
                } else {
                    if (!h.containsTerrain(Terrains.WATER)) {
                        h.removeAllTerrains();
                    }
                    h.addTerrain(new Terrain(Terrains.WATER, crateredElevation, false, 0));
                }
            }
            gamemanager.sendChangedHex(game, hitCoords);
        }

        // check for a stacking violation - which should only happen in the
        // case of grounded dropships, because they are not movable
        if (null != Compute.stackingViolation(game, entity.getId(), c)) {
            Coords dest = Compute.getValidDisplacement(game, entity.getId(), c, Compute.d6() - 1);
            if (null != dest) {
                doEntityDisplacement(entity, c, dest, null);
            } else {
                // ack! automatic death! Tanks
                // suffer an ammo/power plant hit.
                // TODO : a Mech suffers a Head Blown Off crit.
                reportmanager.getvPhaseReport().addAll(entityManager.destroyEntity(entity,
                                                  "impossible displacement", entity instanceof Mech,
                                                  entity instanceof Mech));
            }
        }

        // Check for watery death
        h = game.getBoard().getHex(c);
        if (h.containsTerrain(Terrains.WATER) && !entity.isDestroyed() && !entity.isDoomed()) {
            int lethalDepth = 1;
            if (entity instanceof Dropship) {
                lethalDepth = 2;
            }

            if (h.depth() >= lethalDepth) {
                // Oh snap... we is dead
                vReport.addAll(entityManager.destroyEntity(entity, "crashing into deep water", true, true));
            }
        }

        return vReport;
    }

    // TODO (Sam): Nog testen en de andere nog doen
    public int waitForCFRPacketAndRetrieveTarget(int playerId, int command) {
        String packet = "";
        if (command == Packet.COMMAND_CFR_TELEGUIDED_TARGET) {
            packet = "COMMAND_CFR_TELEGUIDED_TARGET";
        } else if (command == Packet.COMMAND_CFR_TAG_TARGET) {
            packet = "COMMAND_CFR_TAG_TARGET";
        }
        else if (command == Packet.COMMAND_CFR_HIDDEN_PBS) {
            packet = "COMMAND_CFR_HIDDEN_PBS";
        }

        // Keep processing until we get a response
        while (true) {
            synchronized (cfrPacketQueue) {
                try {
                    while (cfrPacketQueue.isEmpty()) {
                        cfrPacketQueue.wait();
                    }
                } catch (InterruptedException e) {
                    return 0;
                }
                // Get the packet, if there's something to get
                ServerConnectionListener.ReceivedPacket rp;
                if (cfrPacketQueue.size() > 0) {
                    rp = cfrPacketQueue.poll();
                    int cfrType = rp.packet.getIntValue(0);
                    // Make sure we got the right type of response
                    if (cfrType != command) {
                        MegaMek.getLogger().error("Expected a " + packet + " CFR packet, " + "received: " + cfrType);
                        continue;
                    }
                    // Check packet came from right ID
                    if (rp.connId != playerId) {
                        MegaMek.getLogger().error("Expected a " + packet + " CFR packet " + "from player  " + playerId + " but instead it came from player " + rp.connId);
                        continue;
                    }
                    return (int)rp.packet.getData()[1];
                } // If no packets, wait again
            }
        }
    }

    public int processTeleguidedMissileCFR(int playerId, List<Integer> targetIds, List<Integer> toHitValues) {
        gamemanager.sendTeleguidedMissileCFR(playerId, targetIds, toHitValues);
        return waitForCFRPacketAndRetrieveTarget(playerId, Packet.COMMAND_CFR_TELEGUIDED_TARGET);
    }
    
    public int processTAGTargetCFR(int playerId, List<Integer> targetIds, List<Integer> targetTypes) {
        gamemanager.sendTAGTargetCFR(playerId, targetIds, targetTypes);
        return waitForCFRPacketAndRetrieveTarget(playerId, Packet.COMMAND_CFR_TAG_TARGET);
    }

    /**
     * Creates an artillery flare of the given radius above the target
     */
    public void deliverArtilleryFlare(Coords coords, int radius) {
        Flare flare = new Flare(coords, 5, radius, Flare.F_DRIFTING);
        game.addFlare(flare);
    }

    public void deliverMortarFlare(Coords coords, int duration) {
        Flare flare = new Flare(coords, duration, 1, Flare.F_IGNITED);
        game.addFlare(flare);
    }

    public void addSmokeToTerrain(Coords coords, int smokeType, int duration) {
        createSmoke(coords, smokeType, duration);
        IHex hex = game.getBoard().getHex(coords);
        hex.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.SMOKE, smokeType));
        gamemanager.sendChangedHex(game, coords);
    }

    /**
     * deliver missile smoke
     *
     * @param coords the <code>Coords</code> where to deliver
     */
    public void deliverMissileSmoke(Coords coords, int smokeType, Vector<Report> vPhaseReport) {
        Report r = ReportFactory.createPublicReport(5183, 2);
        //Report either light or heavy smoke, as appropriate
        r.choose(smokeType == SmokeCloud.SMOKE_LIGHT);
        r.add(coords.getBoardNum());
        vPhaseReport.add(r);
        addSmokeToTerrain(coords, smokeType, 3);
    }

    public void deliverSmokeGrenade(Coords coords, Vector<Report> vPhaseReport) {
        vPhaseReport.add(ReportFactory.createPublicReport(5200, 2, coords.getBoardNum()));
        addSmokeToTerrain(coords, SmokeCloud.SMOKE_LIGHT, 3);
    }

    public void deliverSmokeMortar(Coords coords, Vector<Report> vPhaseReport, int duration) {
        vPhaseReport.add(ReportFactory.createPublicReport(5185, 2, coords.getBoardNum()));
        addSmokeToTerrain(coords, SmokeCloud.SMOKE_HEAVY, duration);
    }

    public void deliverChaffGrenade(Coords coords, Vector<Report> vPhaseReport) {
        vPhaseReport.add(ReportFactory.createPublicReport(5187, 2, coords.getBoardNum()));
        addSmokeToTerrain(coords, SmokeCloud.SMOKE_CHAFF_LIGHT, 1);
    }

    /**
     * deliver artillery smoke
     *
     * @param coords the <code>Coords</code> where to deliver
     */
    public void deliverArtillerySmoke(Coords coords, Vector<Report> vPhaseReport) {
        vPhaseReport.add(ReportFactory.createPublicReport(5185, 2, coords.getBoardNum()));
        addSmokeToTerrain(coords, SmokeCloud.SMOKE_HEAVY, 3);
        for (int dir = 0; dir <= 5; dir++) {
            Coords tempcoords = coords.translated(dir);
            if (!game.getBoard().contains(tempcoords)) {
                continue;
            }
            if (coords.equals(tempcoords)) {
                continue;
            }
            vPhaseReport.add(ReportFactory.createPublicReport(5185, 2, tempcoords.getBoardNum()));
            addSmokeToTerrain(tempcoords, SmokeCloud.SMOKE_HEAVY, 3);
        }
    }

    /**
     * deliver LASER inhibiting smoke
     *
     * @param coords the <code>Coords</code> where to deliver
     */
    public void deliverLIsmoke(Coords coords, Vector<Report> vPhaseReport) {
        vPhaseReport.add(ReportFactory.createPublicReport(5186, 2, coords.getBoardNum()));
        addSmokeToTerrain(coords, SmokeCloud.SMOKE_LI_HEAVY, 2);
        for (int dir = 0; dir <= 5; dir++) {
            Coords tempcoords = coords.translated(dir);
            if (!game.getBoard().contains(tempcoords)) {
                continue;
            }
            if (coords.equals(tempcoords)) {
                continue;
            }
            vPhaseReport.add(ReportFactory.createPublicReport(5186, 2, tempcoords.getBoardNum()));
            addSmokeToTerrain(tempcoords, SmokeCloud.SMOKE_LI_HEAVY, 2);
        }
    }

    /**
     * deliver artillery inferno
     *
     * @param coords    the <code>Coords</code> where to deliver
     * @param ae        the attacking <code>entity<code>
     * @param subjectId the <code>int</code> id of the target
     */
    public void deliverArtilleryInferno(Coords coords, Entity ae, int subjectId, Vector<Report> vPhaseReport) {
        IHex h = game.getBoard().getHex(coords);
        // Unless there is a fire in the hex already, start one.
        if (h.terrainLevel(Terrains.FIRE) < Terrains.FIRE_LVL_INFERNO_IV) {
            ignite(coords, Terrains.FIRE_LVL_INFERNO_IV, vPhaseReport);
        }
        // possibly melt ice and snow
        if (h.containsTerrain(Terrains.ICE) || h.containsTerrain(Terrains.SNOW)) {
            vPhaseReport.addAll(meltIceAndSnow(coords, subjectId));
        }
        for (Entity entity : game.getEntitiesVector(coords)) {
            // TacOps, p. 356 - treat as if hit by 5 inferno missiles
            vPhaseReport.add(ReportFactory.createReport(6695, 3, entity, entity.getDisplayName()));
            if (entity instanceof Tank) {
                Report.addNewline(vPhaseReport);
            }
            Vector<Report> vDamageReport = deliverInfernoMissiles(ae, entity, 5, true);
            Report.indentAll(vDamageReport, 2);
            vPhaseReport.addAll(vDamageReport);
        }
        for (int dir = 0; dir <= 5; dir++) {
            Coords tempcoords = coords.translated(dir);
            if (!game.getBoard().contains(tempcoords)) {
                continue;
            }
            if (coords.equals(tempcoords)) {
                continue;
            }
            h = game.getBoard().getHex(tempcoords);
            // Unless there is a fire in the hex already, start one.
            if (h.terrainLevel(Terrains.FIRE) < Terrains.FIRE_LVL_INFERNO_IV) {
                ignite(tempcoords, Terrains.FIRE_LVL_INFERNO_IV, vPhaseReport);
            }
            // possibly melt ice and snow
            if (h.containsTerrain(Terrains.ICE) || h.containsTerrain(Terrains.SNOW)) {
                vPhaseReport.addAll(meltIceAndSnow(tempcoords, subjectId));
            }
            for (Entity entity : game.getEntitiesVector(tempcoords)) {
                vPhaseReport.add(ReportFactory.createReport(6695, 3, entity, entity.getDisplayName()));
                if (entity instanceof Tank) {
                    Report.addNewline(vPhaseReport);
                }
                Vector<Report> vDamageReport = deliverInfernoMissiles(ae,
                        entity, 5, true);
                Report.indentAll(vDamageReport, 2);
                vPhaseReport.addAll(vDamageReport);
            }
        }
    }

    public void deliverScreen(Coords coords, Vector<Report> vPhaseReport) {
        IHex h = game.getBoard().getHex(coords);
        Report.addNewline(vPhaseReport);
        vPhaseReport.add(ReportFactory.createPublicReport(9070, 2, coords.getBoardNum()));
        // use level to count the number of screens (since level does not matter
        // in space)
        int nscreens = h.terrainLevel(Terrains.SCREEN);
        if (nscreens > 0) {
            h.removeTerrain(Terrains.SCREEN);
            h.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.SCREEN, nscreens + 1));
        } else {
            h.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.SCREEN, 1));
        }
        gamemanager.sendChangedHex(game, coords);
    }

    /**
     * deploys a new telemissile entity onto the map
     */
    public void deployTeleMissile(Entity ae, WeaponType wtype, AmmoType atype, int wId,
            int capMisMod, int damage, int armor, Vector<Report> vPhaseReport) {
        vPhaseReport.add(ReportFactory.createReport(9080, 2, ae, wtype.getName()));
        TeleMissile tele = new TeleMissile(ae, damage, armor, atype.getTonnage(ae), atype.getAmmoType(), capMisMod);
        tele.setDeployed(true);
        tele.setId(game.getNextEntityId());
        if (ae instanceof Aero) {
            Aero a = (Aero) ae;
            tele.setCurrentVelocity(a.getCurrentVelocity());
            tele.setNextVelocity(a.getNextVelocity());
            tele.setVectors(a.getVectors());
            tele.setFacing(a.getFacing());
        }
        // set velocity and heading the same as parent entity
        game.addEntity(tele);
        send(PacketFactory.createAddEntityPacket(game, tele.getId()));
        // make him not get a move this turn
        tele.setDone(true);
        // place on board
        tele.setPosition(ae.getPosition());
        // Update the entity
        entityManager.entityUpdate(tele.getId());
        // check to see if the launching of this missile removes control of any
        // prior missiles
        if (ae.getTMTracker().containsLauncher(wId)) {
            Entity priorMissile = game.getEntity(ae.getTMTracker().getMissile(wId));
            if (priorMissile instanceof TeleMissile) {
                ((TeleMissile) priorMissile).setOutContact(true);
                // remove this from the tracker for good measure
                ae.getTMTracker().removeMissile(wId);
            }
        }
        // track this missile on the entity
        ae.getTMTracker().addMissile(wId, tele.getId());
    }

    /**
     * deliver inferno missiles
     *
     * @param ae       the <code>Entity</code> that fired the missiles
     * @param t        the <code>Targetable</code> that is the target
     * @param missiles the <code>int</code> amount of missiles
     */
    public Vector<Report> deliverInfernoMissiles(Entity ae, Targetable t, int missiles) {
        return deliverInfernoMissiles(ae, t, missiles, CalledShot.CALLED_NONE);
    }

    /**
     * deliver inferno missiles
     *
     * @param ae       the <code>Entity</code> that fired the missiles
     * @param t        the <code>Targetable</code> that is the target
     * @param missiles the <code>int</code> amount of missiles
     * @param areaEffect a <code>boolean</code> indicating whether the attack is from an
     *                   area effect weapon such as Arrow IV inferno, and partial cover should
     *                   be ignored.
     */
    public Vector<Report> deliverInfernoMissiles(Entity ae, Targetable t, int missiles, boolean areaEffect) {
        return deliverInfernoMissiles(ae, t, missiles, CalledShot.CALLED_NONE, areaEffect);
    }

    /**
     * deliver inferno missiles
     *
     * @param ae       the <code>Entity</code> that fired the missiles
     * @param t        the <code>Targetable</code> that is the target
     * @param missiles the <code>int</code> amount of missiles
     * @param called   an <code>int</code> indicated the aiming mode used to fire the
     *                 inferno missiles (for called shots)
     */
    public Vector<Report> deliverInfernoMissiles(Entity ae, Targetable t, int missiles, int called) {
        return deliverInfernoMissiles(ae, t, missiles, called, false);
    }

    /**
     * deliver inferno missiles
     *
     * @param ae         the <code>Entity</code> that fired the missiles
     * @param t          the <code>Targetable</code> that is the target
     * @param missiles   the <code>int</code> amount of missiles
     * @param called     an <code>int</code> indicated the aiming mode used to fire the
     *                   inferno missiles (for called shots)
     * @param areaEffect a <code>boolean</code> indicating whether the attack is from an
     *                   area effect weapon such as Arrow IV inferno, and partial cover should
     *                   be ignored.
     */
    public Vector<Report> deliverInfernoMissiles(Entity ae, Targetable t, int missiles, int called, boolean areaEffect) {
        IHex hex = game.getBoard().getHex(t.getPosition());
        Report r;
        Vector<Report> vPhaseReport = new Vector<>();
        int attId = Entity.NONE;
        if (null != ae) {
            attId = ae.getId();
        }
        switch (t.getTargetType()) {
            case Targetable.TYPE_HEX_ARTILLERY:
                // used for BA inferno explosion
                for (Entity e : game.getEntitiesVector(t.getPosition())) {
                    if (e.getElevation() > hex.terrainLevel(Terrains.BLDG_ELEV)) {
                        r = new Report(6685);
                        r.subject = e.getId();
                        r.addDesc(e);
                        vPhaseReport.add(r);
                        vPhaseReport.addAll(deliverInfernoMissiles(ae, e, missiles, called));
                    } else {
                        int roll = Compute.d6();
                        r = new Report(3570);
                        r.subject = e.getId();
                        r.addDesc(e);
                        r.add(roll);
                        vPhaseReport.add(r);
                        if (roll >= 5) {
                            vPhaseReport.addAll(deliverInfernoMissiles(ae, e, missiles, called));
                        }
                    }
                }
                if (game.getBoard().getBuildingAt(t.getPosition()) != null) {
                    Vector<Report> vBuildingReport = damageBuilding(game.getBoard().getBuildingAt(t.getPosition()),
                            2 * missiles, t.getPosition());
                    for (Report report : vBuildingReport) {
                        report.subject = attId;
                    }
                    vPhaseReport.addAll(vBuildingReport);
                }
                // fall through
            case Targetable.TYPE_HEX_CLEAR:
            case Targetable.TYPE_HEX_IGNITE:
                // Report that damage applied to terrain, if there's TF to damage
                IHex h = game.getBoard().getHex(t.getPosition());
                if ((h != null) && h.hasTerrainfactor()) {
                    r = new Report(3384);
                    r.indent(2);
                    r.subject = attId;
                    r.add(t.getPosition().getBoardNum());
                    r.add(missiles * 4);
                    reportmanager.addReport(r);
                }
                vPhaseReport.addAll(tryClearHex(t.getPosition(), missiles * 4, attId));
                tryIgniteHex(t.getPosition(), attId, false, true,
                             new TargetRoll(0, "inferno"), -1, vPhaseReport);
                break;
            case Targetable.TYPE_BLDG_IGNITE:
            case Targetable.TYPE_BUILDING:
                Vector<Report> vBuildingReport = damageBuilding(game.getBoard().getBuildingAt(t.getPosition()),
                        2 * missiles, t.getPosition());
                for (Report report : vBuildingReport) {
                    report.subject = attId;
                }
                vPhaseReport.addAll(vBuildingReport);

                // For each missile, check to see if it hits a unit in this hex
                for (Entity e : game.getEntitiesVector(t.getPosition())) {
                    if (e.getElevation() > hex.terrainLevel(Terrains.BLDG_ELEV)) {
                        continue;
                    }
                    for (int m = 0; m < missiles; m++) {
                        int roll = Compute.d6();
                        vPhaseReport.add(ReportFactory.createReport(3570, 3, e, roll));
                        if (roll >= 5) {
                            Vector<Report> dmgReports = deliverInfernoMissiles(ae, e, 1, called);
                            for (Report rep : dmgReports) {
                                rep.indent(4);
                            }
                            vPhaseReport.addAll(dmgReports);
                        }
                    }
                }

                break;
            case Targetable.TYPE_ENTITY:
                Entity te = (Entity) t;
                if ((te instanceof Mech) && (!areaEffect)) {
                    // Bug #1585497: Check for partial cover
                    int m = missiles;
                    LosEffects le = LosEffects.calculateLos(game, attId, t);
                    int cover = le.getTargetCover();
                    Vector<Report> coverDamageReports = new Vector<>();
                    int heatDamage = 0;
                    boolean heatReduced = false;
                    String reductionCause = "";
                    for (int i = 0; i < m; i++) {
                        int side = Compute.targetSideTable(ae, t, called);
                        HitData hit = te.rollHitLocation(ToHitData.HIT_NORMAL, side);
                        if (te.removePartialCoverHits(hit.getLocation(), cover, side)) {
                            missiles--;
                            // Determine if damageable cover is hit
                            int damageableCoverType;
                            Entity coverDropship;
                            Coords coverLoc;

                            // Determine if there is primary and secondary
                            // cover,
                            // and then determine which one gets hit
                            if (((cover == LosEffects.COVER_75RIGHT) || (cover == LosEffects.COVER_75LEFT))
                                    // 75% cover has a primary and secondary
                                    || ((cover == LosEffects.COVER_HORIZONTAL)
                                    && (le.getDamagableCoverTypeSecondary() != LosEffects.DAMAGABLE_COVER_NONE))) {
                                // Horizontal cover provided by two 25%'s,
                                // so primary and secondary
                                int hitLoc = hit.getLocation();
                                // Primary stores the left side, from the
                                // perspective of the attacker
                                if ((hitLoc == Mech.LOC_RLEG) || (hitLoc == Mech.LOC_RT) || (hitLoc == Mech.LOC_RARM)) {
                                    // Left side is primary
                                    damageableCoverType = le.getDamagableCoverTypePrimary();
                                    coverDropship = le.getCoverDropshipPrimary();
                                    coverLoc = le.getCoverLocPrimary();
                                } else {
                                    // If not left side, then right side,
                                    // which is secondary
                                    damageableCoverType = le.getDamagableCoverTypeSecondary();
                                    coverDropship = le.getCoverDropshipSecondary();
                                    coverLoc = le.getCoverLocSecondary();
                                }
                            } else { // Only primary cover exists
                                damageableCoverType = le.getDamagableCoverTypePrimary();
                                coverDropship = le.getCoverDropshipPrimary();
                                coverLoc = le.getCoverLocPrimary();
                            }

                            // Check if we need to damage the cover that
                            // absorbed
                            // the hit.
                            Vector<Report> coverDamageReport = new Vector<>();
                            if (damageableCoverType == LosEffects.DAMAGABLE_COVER_DROPSHIP) {
                                r = new Report(3465);
                                r.addDesc(coverDropship);
                                r.indent(1);
                                coverDamageReport = deliverInfernoMissiles(ae, coverDropship, 1, CalledShot.CALLED_NONE);
                                coverDamageReport.insertElementAt(r, 0);
                                for (Report report : coverDamageReport) {
                                    report.indent(1);
                                }
                            } else if (damageableCoverType == LosEffects.DAMAGABLE_COVER_BUILDING) {
                                BuildingTarget bldgTrgt = new BuildingTarget(coverLoc, game.getBoard(), false);
                                coverDamageReport = deliverInfernoMissiles(ae, bldgTrgt, 1, CalledShot.CALLED_NONE);
                            }
                            for (Report report : coverDamageReport) {
                                report.indent(1);
                            }
                            coverDamageReports.addAll(coverDamageReport);
                        } else { // No partial cover, missile hits
                            if ((te.getArmor(hit) > 0)
                                && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_HEAT_DISSIPATING)) {
                                heatDamage += 1;
                                heatReduced = true;
                                reductionCause = EquipmentType.armorNames[te.getArmorType(hit.getLocation())];
                            } else {
                                heatDamage += 2;
                            }
                        }
                    }
                    if (heatReduced) {
                        r = ReportFactory.createReport(3406, 2, te, heatDamage, missiles * 2);
                        r.choose(true);
                        r.add(reductionCause);
                    } else {
                        r = ReportFactory.createReport(3400, 2, te, heatDamage);
                        r.choose(true);
                    }
                    vPhaseReport.add(r);
                    Report.addNewline(vPhaseReport);
                    te.heatFromExternal += heatDamage;

                    if (missiles != m) {
                        r = new Report(3403);
                        r.add(m - missiles);
                        r.indent(2);
                        r.subject = te.getId();
                        vPhaseReport.add(r);
                    }
                    vPhaseReport.addAll(coverDamageReports);
                    Report.addNewline(vPhaseReport);
                } else if (te.tracksHeat()) {
                    // ASFs and small craft
                    r = new Report(3400);
                    r.add(2 * missiles);
                    r.subject = te.getId();
                    r.indent(2);
                    r.choose(true);
                    vPhaseReport.add(r);
                    te.heatFromExternal += 2 * missiles;
                    Report.addNewline(vPhaseReport);
                } else if (te instanceof GunEmplacement){
                    int direction = Compute.targetSideTable(ae, te, called);
                    while (missiles-- > 0) {
                        HitData hit = te.rollHitLocation(ToHitData.HIT_NORMAL, direction);
                        vPhaseReport.addAll(damageEntity(te, hit, 2));
                    }
                } else if ((te instanceof Tank) || te.isSupportVehicle()) {
                    int direction = Compute.targetSideTable(ae, te, called);
                    while (missiles-- > 0) {
                        HitData hit = te.rollHitLocation(ToHitData.HIT_NORMAL, direction);
                        int critRollMod = 0;
                        if (!te.isSupportVehicle() || (te.hasArmoredChassis() && (te.getBARRating(hit.getLocation()) > 9))) {
                            critRollMod -= 2;
                        }
                        if ((te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_HARDENED) && (te.getArmor(hit.getLocation()) > 0)) {
                            critRollMod -= 2;
                        }
                        vPhaseReport.addAll(criticalEntity(te, hit.getLocation(), hit.isRear(),
                                critRollMod, 0, true));
                    }
                } else if (te instanceof ConvFighter) {
                    // CFs take a point SI damage for every three missiles that hit.
                    // Use the heatFromExternal field to carry the remainder in case of multiple inferno hits.
                    te.heatFromExternal += missiles;
                    if (te.heatFromExternal >= 3) {
                        int siDamage = te.heatFromExternal / 3;
                        te.heatFromExternal %= 3;
                        final ConvFighter ftr = (ConvFighter) te;
                        int remaining = Math.max(0,  ftr.getSI() - siDamage);
                        r = new Report(9146);
                        r.subject = te.getId();
                        r.indent(2);
                        r.add(siDamage);
                        r.add(remaining);
                        vPhaseReport.add(r);
                        ftr.setSI(remaining);
                        te.damageThisPhase += siDamage;
                        if (remaining <= 0) {
                            // Lets auto-eject if we can!
                            if (ftr.isAutoEject()
                                    && (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                        || (game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION) 
                                                && ftr.isCondEjectSIDest()))) {
                                vPhaseReport.addAll(ejectEntity(te, true, false));
                            }
                            vPhaseReport.addAll(entityManager.destroyEntity(te,"Structural Integrity Collapse"));
                            ftr.setSI(0);
                            if (null != ae) {
                                game.creditKill(te, ae);
                            }
                        }
                    }
                } else if (te.isLargeCraft()) {
                    // Large craft ignore infernos
                    r = new Report(1242);
                    r.subject = te.getId();
                    r.indent(2);
                    vPhaseReport.add(ReportFactory.createReport(1242, 2, te));
                } else if (te instanceof Protomech) {
                    te.heatFromExternal += missiles;
                    while (te.heatFromExternal >= 3) {
                        te.heatFromExternal -= 3;
                        HitData hit = te.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                        if (hit.getLocation() == Protomech.LOC_NMISS) {
                            Protomech proto = (Protomech) te;
                            r = ReportFactory.createReport(6035, 2, te);
                            if (proto.isGlider()) {
                                r.messageId = 6036;
                                proto.setWingHits(proto.getWingHits() + 1);
                            }
                            vPhaseReport.add(r);
                        } else {
                            vPhaseReport.add(ReportFactory.createReport(6690, 2, te, te.getLocationName(hit)));
                            te.destroyLocation(hit.getLocation());
                            // Handle ProtoMech pilot damage
                            // due to location destruction
                            int hits = Protomech.POSSIBLE_PILOT_DAMAGE[hit.getLocation()]
                                       - ((Protomech) te).getPilotDamageTaken(hit.getLocation());
                            if (hits > 0) {
                                vPhaseReport.addAll(damageCrew(te, hits));
                                ((Protomech) te).setPilotDamageTaken(hit.getLocation(),
                                        Protomech.POSSIBLE_PILOT_DAMAGE[hit.getLocation()]);
                            }
                            if (te.getTransferLocation(hit).getLocation() == Entity.LOC_DESTROYED) {
                                vPhaseReport.addAll(entityManager.destroyEntity(te,
                                        "flaming inferno death", false, true));
                                Report.addNewline(vPhaseReport);
                            }
                        }
                    }
                } else if (te instanceof BattleArmor) {
                    if (((BattleArmor) te).isFireResistant()) {
                        vPhaseReport.add(ReportFactory.createReport(3395, 2, te));
                        return vPhaseReport;
                    }
                    te.heatFromExternal += missiles;
                    while (te.heatFromExternal >= 3) {
                        te.heatFromExternal -= 3;
                        HitData hit = te.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                        hit.setEffect(HitData.EFFECT_CRITICAL);
                        vPhaseReport.addAll(damageEntity(te, hit, 1));
                        Report.addNewline(vPhaseReport);
                    }
                } else if (te instanceof Infantry) {
                    HitData hit = new HitData(Infantry.LOC_INFANTRY);
                    if (te.getInternal(hit) > (3 * missiles)) {
                        // internal structure absorbs all damage
                        te.setInternal(te.getInternal(hit) - (3 * missiles), hit);
                        r = ReportFactory.createReport(6065, 2, te, 3 * missiles);
                        r.add(te.getLocationAbbr(hit));
                        vPhaseReport.add(r);
                        Report.addNewline(vPhaseReport);
                        vPhaseReport.add(ReportFactory.createReport(6095, 3, te, te.getInternal(hit)));
                    } else {
                        vPhaseReport.addAll(entityManager.destroyEntity(te, "damage", false));
                        game.creditKill(te, ae);
                        Report.addNewline(vPhaseReport);
                    }
                }
        }
        return vPhaseReport;
    }

    /**
     * Check for any detonations when an entity enters a minefield, except a
     * vibrabomb.
     *
     * @param entity
     *            - the <code>entity</code> who entered the minefield
     * @param c
     *            - the <code>Coords</code> of the minefield
     * @param curElev
     *            - an <code>int</code> for the elevation of the entity entering
     *            the minefield (used for underwater sea mines)
     * @param isOnGround
     *            - <code>true</code> if the entity is not in the middle of a
     *            jump
     * @param vMineReport
     *            - the report vector that reports will be added to
     * @return - <code>true</code> if the entity set off any mines
     */
    public boolean enterMinefield(Entity entity, Coords c, int curElev, boolean isOnGround,
                                   Vector<Report> vMineReport) {
        return enterMinefield(entity, c, curElev, isOnGround, vMineReport, -1);
    }

    /**
     * Check for any detonations when an entity enters a minefield, except a
     * vibrabomb.
     *
     * @param entity
     *            - the <code>entity</code> who entered the minefield
     * @param c
     *            - the <code>Coords</code> of the minefield
     * @param curElev
     *            - an <code>int</code> for the elevation of the entity entering
     *            the minefield (used for underwater sea mines)
     * @param isOnGround
     *            - <code>true</code> if the entity is not in the middle of a
     *            jump
     * @param vMineReport
     *            - the report vector that reports will be added to
     * @param target
     *            - the <code>int</code> target number for detonation. If this
     *            will be determined by density, it should be -1
     * @return - <code>true</code> if the entity set off any mines
     */
    public boolean enterMinefield(Entity entity, Coords c, int curElev, boolean isOnGround,
                                   Vector<Report> vMineReport, int target) {
        Report r;
        boolean trippedMine = false;
        // flying units cannot trip a mine
        if (curElev > 0) {
            return false;
        }

        Mounted minesweeper = gamemanager.checkMineSweeper(entity);

        Vector<Minefield> fieldsToRemove = new Vector<>();
        // loop through mines in this hex
        for (Minefield mf : game.getMinefields(c)) {

            // vibrabombs are handled differently
            if (mf.getType() == Minefield.TYPE_VIBRABOMB) {
                continue;
            }

            // if we are in the water, then the sea mine will only blow up if at
            // the right depth
            if (game.getBoard().getHex(mf.getCoords()).containsTerrain(Terrains.WATER)) {
                if ((Math.abs(curElev) != mf.getDepth())
                    && (Math.abs(curElev + entity.getHeight()) != mf.getDepth())) {
                    continue;
                }
            }

            // Check for mine-sweeping.  Vibramines handled elsewhere
            if ((minesweeper != null)
                    && ((mf.getType() == Minefield.TYPE_CONVENTIONAL)
                            || (mf.getType() == Minefield.TYPE_ACTIVE)
                            || (mf.getType() == Minefield.TYPE_INFERNO))) {
                // Check to see if the minesweeper clears
                int roll = Compute.d6(2);

                // Report minefield roll
                if (game.doBlind()) { // only report if DB, otherwise all players see
                    r = ReportFactory.createPublicReport(2152, mf.getPlayerId(), Minefield.getDisplayableName(mf.getType()), mf.getCoords().getBoardNum());
                    r.add(roll);
                    vMineReport.add(r);
                }

                if (roll >= 6) {
                    // Report hit
                    if (game.doBlind()) {
                        vMineReport.add(ReportFactory.createPlayerReport(5543, mf.getPlayerId()));
                    }

                    // Clear the minefield
                    r = ReportFactory.createReport(2158, 1, entity, entity.getShortName(), Minefield.getDisplayableName(mf.getType()));
                    r.add(mf.getCoords().getBoardNum(), true);
                    vMineReport.add(r);
                    fieldsToRemove.add(mf);

                    // Handle armor value damage
                    int remainingAV = minesweeper.getArmorValue() - 6;
                    minesweeper.setArmorValue(Math.max(remainingAV, 0));

                    r = ReportFactory.createReport(2161, 2, entity, entity.getShortName());
                    r.add(6, Math.max(remainingAV, 0));
                    vMineReport.add(r);

                    if (remainingAV <= 0) {
                        minesweeper.setDestroyed(true);
                    }
                    // Check for damage transfer
                    if (remainingAV < 0) {
                        int damage = Math.abs(remainingAV);
                        vMineReport.add(ReportFactory.createReport(2162, 2, entity, damage));

                        // Damage is dealt to the location of minesweeper
                        HitData hit = new HitData(minesweeper.getLocation());
                        Vector<Report> damageReports = damageEntity(entity, hit, damage);
                        for (Report r1 : damageReports) {
                            r1.indent(1);
                        }
                        vMineReport.addAll(damageReports);
                    }
                    Report.addNewline(vMineReport);
                    // If the minefield is cleared, we're done processing it
                    continue;
                } else {
                    // Report miss
                    if (game.doBlind()) {
                        vMineReport.add(ReportFactory.createPlayerReport(5542, mf.getPlayerId()));
                    }
                }
            }
            // check whether we have an active mine
            if ((mf.getType() == Minefield.TYPE_ACTIVE && isOnGround) || (mf.getType() != Minefield.TYPE_ACTIVE && !isOnGround)) {
                continue;
            }

            // set the target number
            if (target == -1) {
                target = mf.getTrigger();
                if (mf.getType() == Minefield.TYPE_ACTIVE) {
                    target = 9;
                }
                if (entity instanceof Infantry) {
                    target += 1;
                }
                if (entity.hasAbility(OptionsConstants.MISC_EAGLE_EYES)) {
                    target += 2;
                }
                if ((entity.getMovementMode() == EntityMovementMode.HOVER)
                        || (entity.getMovementMode() == EntityMovementMode.WIGE)) {
                    target = 12;
                }
            }

            int roll = Compute.d6(2);

            // Report minefield roll
            if (game.doBlind()) { // Only do if DB, otherwise all players will see
                r = ReportFactory.createPlayerReport(2151, mf.getPlayerId(), Minefield.getDisplayableName(mf.getType()), mf.getCoords().getBoardNum());
                r.add(target, roll);
                vMineReport.add(r);
            }

            if (roll < target) {
                // Report miss
                if (game.doBlind()) {
                    vMineReport.add(ReportFactory.createPlayerReport(2217, mf.getPlayerId()));
                }
                continue;
            }

            // Report hit
            if (game.doBlind()) {
                vMineReport.add(ReportFactory.createPlayerReport(2270, mf.getPlayerId()));
            }

            // apply damage
            trippedMine = true;
            // explodedMines.add(mf);
            mf.setDetonated(true);
            if (mf.getType() == Minefield.TYPE_INFERNO) {
                // report hitting an inferno mine
                vMineReport.add(ReportFactory.createReport(2155, entity, entity.getShortName(), mf.getCoords().getBoardNum()));
                vMineReport.addAll(deliverInfernoMissiles(entity, entity, mf.getDensity() / 2));
            } else {
                vMineReport.add(ReportFactory.createReport(2150, 1, entity, entity.getShortName(), mf.getCoords().getBoardNum()));
                int damage = mf.getDensity();
                while (damage > 0) {
                    int cur_damage = Math.min(5, damage);
                    damage = damage - cur_damage;
                    HitData hit;
                    if (minesweeper == null) {
                        hit = entity.rollHitLocation(Minefield.TO_HIT_TABLE, Minefield.TO_HIT_SIDE);
                    } else { // Minesweepers cause mines to hit minesweeper loc
                        hit = new HitData(minesweeper.getLocation());
                    }
                    vMineReport.addAll(damageEntity(entity, hit, cur_damage));
                }
                if (entity instanceof Tank) {
                    // Tanks check for motive system damage from minefields as
                    // from a side hit even though the damage proper hits the
                    // front above; exact side doesn't matter, though.
                    vMineReport.addAll(vehicleMotiveDamage((Tank)entity,
                            entity.getMotiveSideMod(ToHitData.SIDE_LEFT)));
                }
                Report.addNewline(vMineReport);
            }

            // check the direct reduction
            mf.checkReduction(0, true);
            gamemanager.revealMinefield(game, mf);
        }

        for (Minefield mf : fieldsToRemove) {
            gamemanager.removeMinefield(game, mf);
        }

        return trippedMine;
    }

    /**
     * attempt to clear a minefield
     *
     * @param mf     - a <code>Minefield</code> to clear
     * @param en     - <code>entity</code> doing the clearing
     * @param target - <code>int</code> needed to roll for a successful clearance
     * @return <code>true</code> if clearance successful
     */
    public boolean clearMinefield(Minefield mf, Entity en, int target, Vector<Report> vClearReport) {
        return clearMinefield(mf, en, target, -1, vClearReport, 2);
    }

    public boolean clearMinefield(Minefield mf, Entity en, int target, int botch, Vector<Report> vClearReport) {
        return clearMinefield(mf, en, target, botch, vClearReport, 1);
    }

    /**
     * attempt to clear a minefield We don't actually remove the minefield here,
     * because if this is called up from within a loop, that will cause problems
     *
     * @param mf
     *            - a <code>Minefield</code> to clear
     * @param en
     *            - <code>entity</code> doing the clearing
     * @param target
     *            - <code>int</code> needed to roll for a successful clearance
     * @param botch
     *            - <code>int</code> that indicates an accidental detonation
     * @param vClearReport
     *            - The report collection to report to
     * @param indent
     *            - The number of indents for the report
     * @return <code>true</code> if clearance successful
     */
    public boolean clearMinefield(Minefield mf, Entity en, int target, int botch, Vector<Report> vClearReport,
                                  int indent) {
        Report r;
        int roll = Compute.d6(2);
        if (roll >= target) {
            r = ReportFactory.createReport(2250, indent, en, Minefield.getDisplayableName(mf.getType()));
            r.add(target, roll);
            //vClearReport.add(r);
            reportmanager.addReport(r);
            return true;
        } else if (roll <= botch) {
            // TODO : detonate the minefield
            r = ReportFactory.createReport(2255, indent, en, Minefield.getDisplayableName(mf.getType()));
            r.add(target, roll);
            vClearReport.add(r);
            // The detonation damages any units that were also attempting to
            // clear mines in the same hex
            for (Entity victim : game.getEntitiesVector(mf.getCoords())) {
                Report rVictim;
                if (victim.isClearingMinefield()) {
                    rVictim = new Report(2265);
                    rVictim.subject = victim.getId();
                    rVictim.add(victim.getShortName(), true);
                    rVictim.indent(indent + 1);
                    vClearReport.add(rVictim);
                    int damage = mf.getDensity();
                    while (damage > 0) {
                        int cur_damage = Math.min(5, damage);
                        damage = damage - cur_damage;
                        HitData hit = victim.rollHitLocation(Minefield.TO_HIT_TABLE, Minefield.TO_HIT_SIDE);
                        vClearReport.addAll(damageEntity(victim, hit, cur_damage));
                    }
                }
            }
            // reduction works differently here
            if (mf.getType() == Minefield.TYPE_CONVENTIONAL) {
                mf.setDensity(Math.max(5, mf.getDensity() - 5));
            } else {
                // congratulations, you cleared the mine by blowing yourself up
                return true;
            }
        } else {
            // failure
            r = ReportFactory.createReport(2260, indent, en, Minefield.getDisplayableName(mf.getType()));
            r.add(target, roll);
            vClearReport.add(r);
        }
        return false;
    }

    /**
     * Checks to see if an entity sets off any vibrabombs.
     */
    public boolean checkVibrabombs(Entity entity, Coords coords, boolean displaced, Vector<Report> vMineReport) {
        return checkVibrabombs(entity, coords, displaced, null, null, vMineReport);
    }

    /**
     * Checks to see if an entity sets off any vibrabombs.
     */
    public boolean checkVibrabombs(Entity entity, Coords coords, boolean displaced, Coords lastPos,
                                    Coords curPos, Vector<Report> vMineReport) {
        int mass = (int) entity.getWeight();

        // Check for Mine sweepers
        Mounted minesweeper = gamemanager.checkMineSweeper(entity);

        // Check for minesweepers sweeping VB minefields
        if (minesweeper != null) {
            Vector<Minefield> fieldsToRemove = new Vector<>();
            for (Minefield mf : game.getVibrabombs()) {
                // Ignore mines if they aren't in this position
                if (!mf.getCoords().equals(coords)) {
                    continue;
                }

                // Minesweepers on units within 9 tons of the vibrafield setting
                // automatically clear the minefield
                if (Math.abs(mass - mf.getSetting()) < 10) {
                    // Clear the minefield
                    vMineReport.add(ReportFactory.createReport(2158, 1, entity, entity.getShortName(), Minefield.getDisplayableName(mf.getType()), mf.getCoords().getBoardNum()));
                    fieldsToRemove.add(mf);

                    // Handle armor value damage
                    int remainingAV = minesweeper.getArmorValue() - 10;
                    minesweeper.setArmorValue(Math.max(remainingAV, 0));

                    Report r = ReportFactory.createReport(2161, 2, entity, entity.getShortName());
                    r.add(10, Math.max(remainingAV, 0));
                    vMineReport.add(r);

                    if (remainingAV <= 0) {
                        minesweeper.setDestroyed(true);
                    }
                    // Check for damage transfer
                    if (remainingAV < 0) {
                        int damage = Math.abs(remainingAV);
                        vMineReport.add(ReportFactory.createReport(2162, 2, entity, damage));

                        // Damage is dealt to the location of minesweeper
                        HitData hit = new HitData(minesweeper.getLocation());
                        Vector<Report> damageReports = damageEntity(entity, hit, damage);
                        for (Report r1 : damageReports) {
                            r1.indent(1);
                        }
                        vMineReport.addAll(damageReports);
                        entity.applyDamage();
                    }
                    Report.addNewline(vMineReport);
                }
            }
            for (Minefield mf : fieldsToRemove) {
                gamemanager.removeMinefield(game, mf);
            }
        }

        boolean boom = false;
        // Only mechs can set off vibrabombs. QuadVees should only be able to set off a
        // vibrabomb in Mech mode. Those that are converting to or from Mech mode should
        // are using leg movement and should be able to set them off.
        if (!(entity instanceof Mech) || (entity instanceof QuadVee
                && (entity.getConversionMode() == QuadVee.CONV_MODE_VEHICLE)
                && !entity.isConvertingNow())) {
            return false;
        }

        Vector<Minefield> minefields = game.getVibrabombs();
        for (Minefield mf : minefields) {
            // Bug 954272: Mines shouldn't work underwater, and BMRr says
            // Vibrabombs are mines
            if (game.getBoard().getHex(mf.getCoords()).containsTerrain(Terrains.WATER)
                    && !game.getBoard().getHex(mf.getCoords()).containsTerrain(Terrains.PAVEMENT)
                    && !game.getBoard().getHex(mf.getCoords()).containsTerrain(Terrains.ICE)) {
                continue;
            }

            // Mech weighing 10 tons or less can't set off the bomb
            if (mass <= (mf.getSetting() - 10)) {
                continue;
            }

            int effectiveDistance = (mass - mf.getSetting()) / 10;
            int actualDistance = coords.distance(mf.getCoords());

            if (actualDistance <= effectiveDistance) {
                vMineReport.add(ReportFactory.createReport(2156, entity, entity.getShortName(), mf.getCoords().getBoardNum()));

                // if the moving entity is not actually moving into the vibrabomb
                // hex, it won't get damaged
                Integer excludeEntityID = null;
                if (!coords.equals(mf.getCoords())) {
                    excludeEntityID = entity.getId();
                }

                explodeVibrabomb(mf, vMineReport, false, excludeEntityID);
            }

            // Hack; when moving, the Mech isn't in the hex during
            // the movement.
            if (!displaced && (actualDistance == 0)) {
                // report getting hit by vibrabomb
                vMineReport.add(ReportFactory.createReport(2160, entity, entity.getShortName()));
                int damage = mf.getDensity();
                while (damage > 0) {
                    int cur_damage = Math.min(5, damage);
                    damage = damage - cur_damage;
                    HitData hit = entity.rollHitLocation(Minefield.TO_HIT_TABLE, Minefield.TO_HIT_SIDE);
                    vMineReport.addAll(damageEntity(entity, hit, cur_damage));
                }
                vMineReport.addAll(resolvePilotingRolls(entity, true, lastPos, curPos));
                // we need to apply Damage now, in case the entity lost a leg,
                // otherwise it won't get a leg missing mod if it hasn't yet
                // moved and lost a leg, see bug 1071434 for an example
                entity.applyDamage();
            }

            // don't check for reduction until the end or units in the same hex
            // through movement will get the reduced damage
            if (mf.hasDetonated()) {
                boom = true;
                mf.checkReduction(0, true);
            }
        }
        return boom;
    }

    /**
     * Explodes a vibrabomb.
     *
     * @param mf The <code>Minefield</code> to explode
     */
    private void explodeVibrabomb(Minefield mf, Vector<Report> vBoomReport, boolean reduce, Integer entityToExclude) {
        for (Entity entity : game.getEntitiesVector(mf.getCoords())) {
            // Airborne entities wont get hit by the mines...
            if (entity.isAirborne()) {
                continue;
            }

            // check for the OptionsConstants.ADVGRNDMOV_NO_PREMOVE_VIBRA option
            // If it's set, and the target has not yet moved, it doesn't get damaged.
            if (!entity.isDone() && game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_NO_PREMOVE_VIBRA)) {
                vBoomReport.add(ReportFactory.createReport(2157, entity, entity.getShortName()));
                continue;
            }
            
            // the "currently moving entity" may not be in the same hex, so it needs to be excluded
            if ((entityToExclude != null) && (entity.getId() == entityToExclude)) {
                // report not hitting vibrabomb
                vBoomReport.add(ReportFactory.createReport(2157, entity, entity.getShortName()));
                continue;
            } else {
                // report hitting vibrabomb
                vBoomReport.add(ReportFactory.createReport(2160, entity, entity.getShortName()));
            }
            
            int damage = mf.getDensity();
            while (damage > 0) {
                int cur_damage = Math.min(5, damage);
                damage = damage - cur_damage;
                HitData hit = entity.rollHitLocation(Minefield.TO_HIT_TABLE, Minefield.TO_HIT_SIDE);
                vBoomReport.addAll(damageEntity(entity, hit, cur_damage));
            }
            Report.addNewline(vBoomReport);

            if (entity instanceof Tank) {
                vBoomReport.addAll(vehicleMotiveDamage((Tank)entity, entity.getMotiveSideMod(ToHitData.SIDE_LEFT)));
            }
            vBoomReport.addAll(resolvePilotingRolls(entity, true, entity.getPosition(), entity.getPosition()));
            // we need to apply Damage now, in case the entity lost a leg,
            // otherwise it won't get a leg missing mod if it hasn't yet
            // moved and lost a leg, see bug 1071434 for an example
            game.resetPSRs(entity);
            entity.applyDamage();
            Report.addNewline(vBoomReport);
            entityManager.entityUpdate(entity.getId());
        }

        // check the direct reduction of mine
        if (reduce) {
            mf.checkReduction(0, true);
        }
        mf.setDetonated(true);
    }

    /**
     * Set the LocationsExposure of an entity
     *
     * @param entity
     *            The <code>Entity</code> who's exposure is being set
     * @param hex
     *            The <code>IHex</code> the entity is in
     * @param isJump
     *            a <code>boolean</code> value whether the entity is jumping
     * @param elevation
     *            the elevation the entity should be at.
     */
    public Vector<Report> doSetLocationsExposure(Entity entity, IHex hex, boolean isJump, int elevation) {
        Vector<Report> vPhaseReport = new Vector<>();
        if (hex == null) {
            return vPhaseReport;
        }
        if ((hex.terrainLevel(Terrains.WATER) > 0) && !isJump && (elevation < 0)) {
            int partialWaterLevel = 1;
            if ((entity instanceof Mech) && entity.isSuperHeavy()) {
                partialWaterLevel = 2;
            }
            if ((entity instanceof Mech) && !entity.isProne()
                && (hex.terrainLevel(Terrains.WATER) <= partialWaterLevel)) {
                for (int loop = 0; loop < entity.locations(); loop++) {
                    if (game.getPlanetaryConditions().isVacuum()
                            || ((entity.getEntityType() & Entity.ETYPE_AERO) == 0 && entity.isSpaceborne())) {
                        entity.setLocationStatus(loop, ILocationExposureStatus.VACUUM);
                    } else {
                        entity.setLocationStatus(loop, ILocationExposureStatus.NORMAL);
                    }
                }
                entity.setLocationStatus(Mech.LOC_RLEG, ILocationExposureStatus.WET);
                entity.setLocationStatus(Mech.LOC_LLEG, ILocationExposureStatus.WET);
                vPhaseReport.addAll(breachCheck(entity, Mech.LOC_RLEG, hex));
                vPhaseReport.addAll(breachCheck(entity, Mech.LOC_LLEG, hex));
                if (entity instanceof QuadMech) {
                    entity.setLocationStatus(Mech.LOC_RARM, ILocationExposureStatus.WET);
                    entity.setLocationStatus(Mech.LOC_LARM, ILocationExposureStatus.WET);
                    vPhaseReport.addAll(breachCheck(entity, Mech.LOC_RARM, hex));
                    vPhaseReport.addAll(breachCheck(entity, Mech.LOC_LARM, hex));
                }
                if (entity instanceof TripodMech) {
                    entity.setLocationStatus(Mech.LOC_CLEG, ILocationExposureStatus.WET);
                    vPhaseReport.addAll(breachCheck(entity, Mech.LOC_CLEG, hex));
                }
            } else {
                for (int loop = 0; loop < entity.locations(); loop++) {
                    entity.setLocationStatus(loop, ILocationExposureStatus.WET);
                    vPhaseReport.addAll(breachCheck(entity, loop, hex));
                }
            }
        } else {
            for (int loop = 0; loop < entity.locations(); loop++) {
                if (game.getPlanetaryConditions().isVacuum()
                        || ((entity.getEntityType() & Entity.ETYPE_AERO) == 0 && entity.isSpaceborne())) {
                    entity.setLocationStatus(loop, ILocationExposureStatus.VACUUM);
                } else {
                    entity.setLocationStatus(loop, ILocationExposureStatus.NORMAL);
                }
            }
        }
        return vPhaseReport;
    }

    /**
     * Do a piloting skill check while standing still (during the movement
     * phase).
     *
     * @param entity The <code>Entity</code> that should make the PSR
     * @param roll   The <code>PilotingRollData</code> to be used for this PSR.
     * @return true if check succeeds, false otherwise.
     */
    public boolean doSkillCheckInPlace(Entity entity, PilotingRollData roll) {
        if (roll.getValue() == TargetRoll.AUTOMATIC_SUCCESS || entity.isProne()) {
            return true;
        }

        // okay, print the info
        reportmanager.addReport(ReportFactory.createReport(2180, entity, roll.getLastPlainDesc()));

        // roll
        final int diceRoll = entity.getCrew().rollPilotingSkill();
        Report r = ReportFactory.createReport(2185, entity, roll.getValueAsString(), roll.getDesc());
        r.add(diceRoll);
        boolean suc;
        if (diceRoll < roll.getValue()) {
            r.choose(false);
            reportmanager.addReport(r);
            if ((entity instanceof Mech)
                && game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_TACOPS_FALLING_EXPANDED)
                && (entity.getCrew().getPiloting() < 6)
                && !entity.isHullDown() && entity.canGoHullDown()) {
                if (((entity.getCrew().getPiloting() > 1) && (roll.getValue() - diceRoll) < 2)
                        || ((entity.getCrew().getPiloting() <= 1) && (roll.getValue() - diceRoll) < 3)) {
                    entity.setHullDown(true);
                }
            }
            if (!entity.isHullDown() || (entity.isHullDown() && !entity.canGoHullDown())) {
                reportmanager.addReport(doEntityFall(entity, roll));
            } else {
                ServerHelper.sinkToBottom(entity);
                reportmanager.addReport(ReportFactory.createReport(2317, entity, entity.getDisplayName()));
            }

            suc = false;
            // failed a PSR, possibly check for engine stalling
            entity.doCheckEngineStallRoll(reportmanager.getvPhaseReport());
        } else {
            r.choose(true);
            reportmanager.addReport(r);
            suc = true;
        }
        return suc;
    }

    /**
     * Do a piloting skill check while moving.
     *
     * @param entity          - the <code>Entity</code> that must roll.
     * @param entityElevation The elevation of the supplied Entity above the surface of the
     *                        src hex. This is necessary as the state of the Entity may
     *                        represent the elevation of the entity about the surface of the
     *                        destination hex.
     * @param src             - the <code>Coords</code> the entity is moving from.
     * @param dest            - the <code>Coords</code> the entity is moving to. This value
     *                        can be the same as src for in-place checks.
     * @param roll            - the <code>PilotingRollData</code> that is causing this
     *                        check.
     * @param isFallRoll      - a <code>boolean</code> flag that indicates that failure will
     *                        result in a fall or not. Falls will be processed.
     * @return Margin of Failure if the pilot fails the skill check, 0 if they
     * pass.
     */
    public int doSkillCheckWhileMoving(Entity entity, int entityElevation, Coords src, Coords dest,
                                        PilotingRollData roll, boolean isFallRoll) {
        boolean fallsInPlace;

        // Start the info for this roll.
        Report r = ReportFactory.createReport(1210, entity);
        // Will the entity fall in the source or destination hex?
        if (src.equals(dest)) {
            fallsInPlace = true;
            r.messageId = 2195;
            r.add(src.getBoardNum(), true);
        } else {
            fallsInPlace = false;
            r.messageId = 2200;
            r.add(src.getBoardNum(), true);
            r.add(dest.getBoardNum(), true);
        }

        // Finish the info.
        r.add(roll.getLastPlainDesc(), true);
        reportmanager.addReport(r);

        // roll
        final int diceRoll = entity.getCrew().rollPilotingSkill();
        r = ReportFactory.createReport(2185, entity, roll.getValueAsString(), roll.getDesc());
        r.add(diceRoll);
        if (diceRoll < roll.getValue()) {
            // Does failing the PSR result in a fall.
            if (isFallRoll && entity.canFall()) {
                r.choose(false);
                reportmanager.addReport(r);
                reportmanager.addReport(doEntityFallsInto(entity, entityElevation, fallsInPlace ? dest : src,
                        fallsInPlace ? src : dest, roll, true));
            } else {
                r.messageId = 2190;
                r.choose(false);
                reportmanager.addReport(r);
                entity.setPosition(fallsInPlace ? src : dest);
            }
            // failed a PSR, possibly check for engine stalling
            entity.doCheckEngineStallRoll(reportmanager.getvPhaseReport());
            return roll.getValue() - diceRoll;
        }
        r.choose(true);
        r.newlines = 2;
        reportmanager.addReport(r);
        return 0;
    }

    /**
     * Process a fall when the source and destination hexes are the same.
     * Depending on the elevations of the hexes, the Entity could land in the
     * source or destination hexes. Check for any conflicts and resolve them.
     * Deal damage to faller. Note: the elevation of the entity is used to
     * determine fall distance, so it is important to ensure the Entity's
     * elevation is correct.
     *
     * @param entity    The <code>Entity</code> that is falling.
     * @param src       The <code>Coords</code> of the source hex.
     * @param roll      The <code>PilotingRollData</code> to be used for PSRs induced
     *                  by the falling.
     * @param causeAffa The <code>boolean</code> value whether this fall should be able
     *                  to cause an accidental fall from above
     */
    public Vector<Report> doEntityFallsInto(Entity entity, Coords src, PilotingRollData roll, boolean causeAffa) {
        return doEntityFallsInto(entity, entity.getElevation(), src, src, roll, causeAffa);
    }

    /**
     * Process a fall when moving from the source hex to the destination hex.
     * Depending on the elevations of the hexes, the Entity could land in the
     * source or destination hexes. Check for any conflicts and resolve them.
     * Deal damage to faller. Note: the elevation of the entity is used to
     * determine fall distance, so it is important to ensure the Entity's
     * elevation is correct.
     *
     * @param entity             The <code>Entity</code> that is falling.
     * @param entitySrcElevation The elevation of the supplied Entity above the surface of the
     *                           src hex. This is necessary as the state of the Entity may
     *                           represent the elevation of the entity about the surface of the
     *                           destination hex.
     * @param src                The <code>Coords</code> of the source hex.
     * @param dest               The <code>Coords</code> of the destination hex.
     * @param roll               The <code>PilotingRollData</code> to be used for PSRs induced
     *                           by the falling.
     * @param causeAffa          The <code>boolean</code> value whether this fall should be able
     *                           to cause an accidental fall from above
     */
    public Vector<Report> doEntityFallsInto(Entity entity, int entitySrcElevation, Coords src, Coords dest,
                                             PilotingRollData roll, boolean causeAffa) {
        return doEntityFallsInto(entity, entitySrcElevation, src, dest, roll, causeAffa, 0);
    }

    /**
     * Process a fall when moving from the source hex to the destination hex.
     * Depending on the elevations of the hexes, the Entity could land in the
     * source or destination hexes. Check for any conflicts and resolve them.
     * Deal damage to faller.
     *
     * @param entity             The <code>Entity</code> that is falling.
     * @param entitySrcElevation The elevation of the supplied Entity above the surface of the
     *                           src hex. This is necessary as the state of the Entity may
     *                           represent the elevation of the entity about the surface of the
     *                           destination hex.
     * @param origSrc            The <code>Coords</code> of the original source hex.
     * @param origDest           The <code>Coords</code> of the original destination hex.
     * @param roll               The <code>PilotingRollData</code> to be used for PSRs induced
     *                           by the falling.
     * @param causeAffa          The <code>boolean</code> value whether this fall should be able
     *                           to cause an accidental fall from above
     * @param fallReduction      An integer value to reduce the fall distance by
     */
    public Vector<Report> doEntityFallsInto(Entity entity, int entitySrcElevation, Coords origSrc, Coords origDest,
                                             PilotingRollData roll, boolean causeAffa, int fallReduction) {
        Vector<Report> vPhaseReport = new Vector<>();
        IHex srcHex = game.getBoard().getHex(origSrc);
        IHex destHex = game.getBoard().getHex(origDest);
        Coords src, dest;
        // We need to fall into the lower of the two hexes, TW pg 68
        if (srcHex.getLevel() < destHex.getLevel()) {
            IHex swapHex = destHex;
            destHex = srcHex;
            srcHex = swapHex;
            src = origDest;
            dest = origSrc;
        } else {
            src = origSrc;
            dest = origDest;
        }
        final int srcHeightAboveFloor = entitySrcElevation + srcHex.depth(false);
        int fallElevation = Math.abs((srcHex.floor() + srcHeightAboveFloor)
                - (destHex.containsTerrain(Terrains.ICE) ? destHex.surface() : destHex.floor()))
                - fallReduction;
        if (destHex.containsTerrain(Terrains.BLDG_ELEV)) {
            fallElevation -= destHex.terrainLevel(Terrains.BLDG_ELEV);
        }
        if (destHex.containsTerrain(Terrains.BLDG_BASEMENT_TYPE)) {
            if (entity.getElevation() == 0) { // floor 0 falling into basement
                fallElevation = destHex.depth(true);
            }
        }

        int direction;
        if (src.equals(dest)) {
            direction = Compute.d6() - 1;
        } else {
            direction = src.direction(dest);
        }

        // check entity in target hex
        Entity affaTarget = game.getAffaTarget(dest, entity);
        // falling mech falls
        Report r = ReportFactory.createReport(2205, entity, fallElevation);
        r.add(dest.getBoardNum(), true);
        r.newlines = 0;

        // if hex was empty, deal damage and we're done
        if (affaTarget == null) {
            r.newlines = 1;
            vPhaseReport.add(r);
            // If we rolled for the direction, we want to use that for the fall
            if (src.equals(dest)) {
                vPhaseReport.addAll(doEntityFall(entity, dest, fallElevation,
                                                 direction, roll, false, srcHex.hasCliffTopTowards(destHex)));
            } else {
                // Otherwise, we'll roll for the direction after the fall
                vPhaseReport.addAll(doEntityFall(entity, dest, fallElevation, roll));
            }

            return vPhaseReport;
        }
        vPhaseReport.add(r);

        // hmmm... somebody there... problems.
        if ((fallElevation >= 2) && causeAffa) {
            // accidental fall from above: havoc!
            r = new Report(2210);
            r.subject = entity.getId();
            r.addDesc(affaTarget);
            vPhaseReport.add(r);

            // determine to-hit number
            ToHitData toHit = new ToHitData(7, "base");
            if ((affaTarget instanceof Tank) || (affaTarget instanceof Dropship)) {
                toHit = new ToHitData(TargetRoll.AUTOMATIC_SUCCESS, "Target is a Tank");
            } else {
                toHit.append(Compute.getTargetMovementModifier(game, affaTarget.getId()));
                toHit.append(Compute.getTargetTerrainModifier(game, affaTarget));
            }

            if (toHit.getValue() != TargetRoll.AUTOMATIC_FAIL) {
                // collision roll
                final int diceRoll = Compute.d6(2);
                if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
                    r = new Report(2212);
                    r.add(toHit.getValue());
                } else {
                    r = new Report(2215);
                    r.subject = entity.getId();
                    r.add(toHit.getValue());
                    r.add(diceRoll);
                    r.newlines = 0;
                }
                r.indent();
                vPhaseReport.add(r);
                if (diceRoll >= toHit.getValue()) {
                    // deal damage to target
                    int damage = Compute.getAffaDamageFor(entity);
                    vPhaseReport.add(ReportFactory.createReport(2220, affaTarget, damage));
                    while (damage > 0) {
                        int cluster = Math.min(5, damage);
                        HitData hit = affaTarget.rollHitLocation(ToHitData.HIT_PUNCH, ToHitData.SIDE_FRONT);
                        hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                        vPhaseReport.addAll(damageEntity(affaTarget, hit, cluster));
                        damage -= cluster;
                    }

                    // attacker falls as normal, on his back only given a modifier,
                    // so flesh out into a full piloting roll
                    PilotingRollData pilotRoll = entity.getBasePilotingRoll();
                    pilotRoll.append(roll);
                    vPhaseReport.addAll(doEntityFall(entity, dest,
                            fallElevation, 3, pilotRoll, false, false));
                    vPhaseReport.addAll(doEntityDisplacementMinefieldCheck(entity, dest, entity.getElevation()));

                    // defender pushed away, or destroyed, if there is a stacking violation
                    Entity violation = Compute.stackingViolation(game, entity.getId(), dest);
                    if (violation != null) {
                        PilotingRollData prd = new PilotingRollData(violation.getId(), 2, "fallen on");
                        if (violation instanceof Dropship) {
                            violation = entity;
                            prd = null;
                        }
                        Coords targetDest = Compute.getValidDisplacement(game, violation.getId(), dest, direction);
                        if (targetDest != null) {
                            vPhaseReport.addAll(doEntityDisplacement(violation, dest, targetDest, prd));
                            // Update the violating entity's position on the client.
                            entityManager.entityUpdate(violation.getId());
                        } else {
                            // ack! automatic death! Tanks suffer an ammo/power plant hit.
                            // TODO : a Mech suffers a Head Blown Off crit.
                            vPhaseReport.addAll(entityManager.destroyEntity(violation, "impossible displacement",
                                    violation instanceof Mech, violation instanceof Mech));
                        }
                    }
                    return vPhaseReport;
                }
            } else {
                // automatic miss
                r = new Report(2213);
                r.add(toHit.getDesc());
                vPhaseReport.add(r);
            }
            // ok, we missed, let's fall into a valid other hex and not cause an
            // AFFA while doing so
            Coords targetDest = Compute.getValidDisplacement(game, entity.getId(), dest, direction);
            if (targetDest != null) {
                vPhaseReport.addAll(doEntityFallsInto(entity, entitySrcElevation, src, targetDest,
                        new PilotingRollData(entity.getId(), TargetRoll.IMPOSSIBLE, "pushed off a cliff"),
                        false));
                // Update the entity's position on the client.
                entityManager.entityUpdate(entity.getId());
            } else {
                // ack! automatic death! Tanks
                // suffer an ammo/power plant hit.
                // TODO : a Mech suffers a Head Blown Off crit.
                vPhaseReport.addAll(entityManager.destroyEntity(entity,
                        "impossible displacement", entity instanceof Mech, entity instanceof Mech));
            }
        } else {
            // damage as normal
            vPhaseReport.addAll(doEntityFall(entity, dest, fallElevation, roll));
            Entity violation = Compute.stackingViolation(game, entity.getId(), dest);
            if (violation != null) {
                PilotingRollData prd = new PilotingRollData(violation.getId(), 0, "domino effect");
                if (violation instanceof Dropship) {
                    violation = entity;
                    prd = null;
                }
                // target gets displaced, because of low elevation
                Coords targetDest = Compute.getValidDisplacement(game, violation.getId(), dest, direction);
                vPhaseReport.addAll(doEntityDisplacement(violation, dest, targetDest, prd));
                // Update the violating entity's position on the client.
                if (!game.getOutOfGameEntitiesVector().contains(violation)) {
                    entityManager.entityUpdate(violation.getId());
                }
            }
        }
        return vPhaseReport;
    }

    /**
     * Displace a unit in the direction specified. The unit moves in that
     * direction, and the piloting skill roll is used to determine if it falls.
     * The roll may be unnecessary as certain situations indicate an automatic
     * fall. Rolls are added to the piloting roll list.
     */
    public Vector<Report> doEntityDisplacement(Entity entity, Coords src,
            Coords dest, PilotingRollData roll) {
        Vector<Report> vPhaseReport = new Vector<>();
        Report r;
        if (!game.getBoard().contains(dest)) {
            // set position anyway, for pushes moving through, stuff like that
            entity.setPosition(dest);
            if (!entity.isDoomed()) {
                // Make sure there aren't any specific entity turns for entity
                int turnsRemoved = game.removeSpecificEntityTurnsFor(entity);
                // May need to remove a turn for this Entity
                if ((game.getPhase() == Phase.PHASE_MOVEMENT) && !entity.isDone() && (turnsRemoved == 0)) {
                    game.removeTurnFor(entity);
                    send(PacketFactory.createTurnVectorPacket(game));
                } else if (turnsRemoved > 0) {
                    send(PacketFactory.createTurnVectorPacket(game));
                }
                game.removeEntity(entity.getId(), IEntityRemovalConditions.REMOVE_PUSHED);
                send(PacketFactory.createRemoveEntityPacket(entity.getId(), IEntityRemovalConditions.REMOVE_PUSHED));
                // entity forced from the field
                vPhaseReport.add(ReportFactory.createReport(2230, entity));
                // TODO : remove passengers and swarmers.
            }
            return vPhaseReport;
        }
        final IHex srcHex = game.getBoard().getHex(src);
        final IHex destHex = game.getBoard().getHex(dest);
        final int direction = src.direction(dest);

        // Handle null hexes.
        if ((srcHex == null) || (destHex == null)) {
            MegaMek.getLogger().error("Can not displace " + entity.getShortName() + " from " + src + " to " + dest + ".");
            return vPhaseReport;
        }
        int bldgElev = destHex.containsTerrain(Terrains.BLDG_ELEV) ? destHex.terrainLevel(Terrains.BLDG_ELEV) : 0;
        int fallElevation = srcHex.surface() + entity.getElevation() - (destHex.surface() + bldgElev);
        if (fallElevation > 1) {
            if (roll == null) {
                roll = entity.getBasePilotingRoll();
            }
            if (!(entity.isAirborneVTOLorWIGE())) {
                vPhaseReport.addAll(doEntityFallsInto(entity, entity.getElevation(), src, dest, roll, true));
            } else {
                entity.setPosition(dest);
            }
            return vPhaseReport;
        }
        // unstick the entity if it was stuck in swamp
        boolean wasStuck = entity.isStuck();
        entity.setStuck(false);
        int oldElev = entity.getElevation();
        // move the entity into the new location gently
        entity.setPosition(dest);
        entity.setElevation(entity.calcElevation(srcHex, destHex));
        Building bldg = game.getBoard().getBuildingAt(dest);
        if (bldg != null) {
            if (destHex.terrainLevel(Terrains.BLDG_ELEV) > oldElev) {
                // whoops, into the building we go
                passBuildingWall(entity, game.getBoard().getBuildingAt(dest), src, dest, 1,
                        "displaced into", Math.abs(entity.getFacing() - src.direction(dest)) == 3,
                        entity.moved, true);
            }
            checkBuildingCollapseWhileMoving(bldg, entity, dest);
        }
        
        ServerHelper.checkAndApplyMagmaCrust(destHex, entity.getElevation(), entity, dest, false, vPhaseReport);
        
        Entity violation = Compute.stackingViolation(game, entity.getId(), dest);
        if (violation == null) {
            // move and roll normally
            r = ReportFactory.createReport(2235, 1, entity, dest.getBoardNum());
        } else {
            // domino effect: move & displace target
            r = ReportFactory.createReport(2240, 1, entity, dest.getBoardNum());
            r.addDesc(violation);
        }
        vPhaseReport.add(r);
        // trigger any special things for moving to the new hex
        vPhaseReport.addAll(doEntityDisplacementMinefieldCheck(entity, dest, entity.getElevation()));
        vPhaseReport.addAll(doSetLocationsExposure(entity, destHex, false, entity.getElevation()));
        if (destHex.containsTerrain(Terrains.BLDG_ELEV)
            && (entity.getElevation() == 0)) {
            bldg = game.getBoard().getBuildingAt(dest);
            if (bldg.rollBasement(dest, game.getBoard(), vPhaseReport)) {
                gamemanager.sendChangedHex(game, dest);
                Vector<Building> buildings = new Vector<>();
                buildings.add(bldg);
                sendChangedBuildings(buildings);
            }
        }

        // mechs that were stuck will automatically fall in their new hex
        if (wasStuck && entity.canFall()) {
            if (roll == null) {
                roll = entity.getBasePilotingRoll();
            }
            vPhaseReport.addAll(doEntityFall(entity, dest, 0, roll));
        }
        // check bog-down conditions
        vPhaseReport.addAll(doEntityDisplacementBogDownCheck(entity, dest, entity.getElevation()));

        if (roll != null) {
            if (entity.canFall()) {
                game.addPSR(roll);
            } else if ((entity instanceof LandAirMech) && entity.isAirborneVTOLorWIGE()) {
                game.addControlRoll(roll);
            }
        }

        int waterDepth = destHex.terrainLevel(Terrains.WATER);

        if (destHex.containsTerrain(Terrains.ICE) && destHex.containsTerrain(Terrains.WATER)) {
            if (!(entity instanceof Infantry)) {
                int d6 = Compute.d6(1);
                vPhaseReport.add(ReportFactory.createReport(2118, entity, d6));

                if (d6 == 6) {
                    vPhaseReport.addAll(resolveIceBroken(dest));
                }
            }
        }
        // Falling into water instantly destroys most non-mechs
        else if ((waterDepth > 0)
                && !(entity instanceof Mech)
                && !(entity instanceof Protomech)
                && !((entity.getRunMP() > 0) && (entity.getMovementMode() == EntityMovementMode.HOVER))
                && (entity.getMovementMode() != EntityMovementMode.HYDROFOIL)
                && (entity.getMovementMode() != EntityMovementMode.NAVAL)
                && (entity.getMovementMode() != EntityMovementMode.SUBMARINE)
                && (entity.getMovementMode() != EntityMovementMode.INF_UMU)) {
            vPhaseReport.addAll(entityManager.destroyEntity(entity, "a watery grave", false));
        } else if ((waterDepth > 0) && !(entity.getMovementMode() == EntityMovementMode.HOVER)) {
            PilotingRollData waterRoll = entity.checkWaterMove(waterDepth, entity.moved);
            if (waterRoll.getValue() != TargetRoll.CHECK_FALSE) {
                doSkillCheckInPlace(entity, waterRoll);
            }
        }
        // Update the entity's position on the client.
        entityManager.entityUpdate(entity.getId());

        if (violation != null) {
            // Can the violating unit move out of the way?
            // if the direction comes from a side, Entity didn't jump, and it
            // has MP left to use, it can try to move.
            MovePath stepForward = new MovePath(game, violation);
            MovePath stepBackwards = new MovePath(game, violation);
            stepForward.addStep(MoveStepType.FORWARDS);
            stepBackwards.addStep(MoveStepType.BACKWARDS);
            stepForward.compile(getGame(), violation, false);
            stepBackwards.compile(getGame(), violation, false);
            if ((direction != violation.getFacing())
                    && (direction != ((violation.getFacing() + 3) % 6))
                    && !entity.getIsJumpingNow()
                    && (stepForward.isMoveLegal() || stepBackwards.isMoveLegal())) {
                // First, we need to make a PSR to see if we can step out
                int result = Compute.d6(2);
                roll = entity.getBasePilotingRoll();

                vPhaseReport.add(ReportFactory.createReport(2351, 2, violation, roll.getValue(), result));
                if (result < roll.getValue()) {
                    r.choose(false);
                    Vector<Report> newReports = doEntityDisplacement(violation, dest, dest.translated(direction),
                            new PilotingRollData(violation.getId(), TargetRoll.AUTOMATIC_FAIL,
                                    "failed to step out of a domino effect"));
                    for (Report newReport : newReports) {
                        newReport.indent(3);
                    }
                    vPhaseReport.addAll(newReports);
                } else {
                    r.choose(true);
                    gamemanager.sendDominoEffectCFR(violation);
                    synchronized (cfrPacketQueue) {
                        try {
                            cfrPacketQueue.wait();
                        } catch (InterruptedException ignored) {
                            // Do nothing
                        }
                        if (cfrPacketQueue.size() > 0) {
                            ServerConnectionListener.ReceivedPacket rp = cfrPacketQueue.poll();
                            int cfrType = (int) rp.packet.getData()[0];
                            // Make sure we got the right type of response
                            if (cfrType != Packet.COMMAND_CFR_DOMINO_EFFECT) {
                                MegaMek.getLogger().error("Excepted a COMMAND_CFR_DOMINO_EFFECT CFR packet, "
                                                + "received: " + cfrType);
                                throw new IllegalStateException();
                            }
                            MovePath mp = (MovePath) rp.packet.getData()[1];
                            // Move based on the feedback
                            if (mp != null) {
                                mp.setGame(getGame());
                                mp.setEntity(violation);
                                // Report
                                r = ReportFactory.createReport(2352, 3, violation);
                                r.choose(mp.getLastStep().getType() != MoveStepType.FORWARDS);
                                r.add(mp.getLastStep().getPosition().getBoardNum());
                                vPhaseReport.add(r);
                                // Move unit
                                violation.setPosition(mp.getFinalCoords());
                                violation.mpUsed += mp.getMpUsed();
                                violation.moved = mp.getLastStepMovementType();
                            } else { // User decided to do nothing
                                vPhaseReport.add(ReportFactory.createReport(2358, 3, violation));
                                vPhaseReport.addAll(doEntityDisplacement(violation, dest,
                                        dest.translated(direction), null));
                            }
                        } else { // If no responses, treat as no action
                            vPhaseReport.addAll(doEntityDisplacement(violation,
                                    dest, dest.translated(direction), new PilotingRollData(violation.getId(), 0,
                                            "domino effect")));
                        }
                    }
                }
            } else { // Nope
                vPhaseReport.add(ReportFactory.createReport(2359, 2, violation));
                vPhaseReport.addAll(doEntityDisplacement(violation, dest, dest.translated(direction),
                        new PilotingRollData(violation.getId(), 0, "domino effect")));

            }
            // Update the violating entity's position on the client,
            // if it didn't get displaced off the board.
            if (!game.isOutOfGame(violation)) {
                entityManager.entityUpdate(violation.getId());
            }
        }
        return vPhaseReport;
    }

    public Vector<Report> doEntityDisplacementMinefieldCheck(Entity entity, Coords dest, int elev) {
        Vector<Report> vPhaseReport = new Vector<>();
        boolean boom = checkVibrabombs(entity, dest, true, vPhaseReport);
        if (game.containsMinefield(dest)) {
            boom = enterMinefield(entity, dest, elev, true, vPhaseReport) || boom;
        }
        if (boom) {
            gamemanager.resetMines(game);
        }

        return vPhaseReport;
    }

    private Vector<Report> doEntityDisplacementBogDownCheck(Entity entity, Coords c, int elev) {
        Vector<Report> vReport = new Vector<>();
        IHex destHex = game.getBoard().getHex(c);
        int bgMod = destHex.getBogDownModifier(entity.getMovementMode(),
                entity instanceof LargeSupportTank);
        if ((bgMod != TargetRoll.AUTOMATIC_SUCCESS)
                && (entity.getMovementMode() != EntityMovementMode.HOVER)
                && (entity.getMovementMode() != EntityMovementMode.WIGE)
                && (elev == 0)) {
            PilotingRollData roll = entity.getBasePilotingRoll();
            roll.append(new PilotingRollData(entity.getId(), bgMod, "avoid bogging down"));
            int stuckroll = Compute.d6(2);
            // A DFA-ing mech is "displaced" into the target hex. Since it
            // must be jumping, it will automatically be bogged down
            if (stuckroll < roll.getValue() || entity.isMakingDfa()) {
                entity.setStuck(true);
                vReport.add(ReportFactory.createReport(2081, entity, entity.getDisplayName()));
                // check for quicksand
                vReport.addAll(checkQuickSand(c));
            }

        }
        return vReport;
    }

    /**
     * Process a deployment packet by... deploying the entity! We load any other
     * specified entities inside of it too. Also, check that the deployment is
     * valid.
     */
    private void processDeployment(Entity entity, Coords coords, int nFacing, int elevation, Vector<Entity> loadVector,
            boolean assaultDrop) {
        for (Entity loaded : loadVector) {
            if (loaded.getTransportId() != Entity.NONE) {
                // we probably already loaded this unit in the chat lounge
                continue;
            }
            if (loaded.getPosition() != null) {
                // Something is fishy in Denmark.
                MegaMek.getLogger().error(entity + " can not load entity #" + loaded);
                break;
            }
            // Have the deployed unit load the indicated unit.
            loadUnit(entity, loaded, loaded.getTargetBay());
        }

        /*
         * deal with starting velocity for advanced movement. Probably not the
         * best place to do it, but what are you going to do
         */
        if (entity.isAero() && game.useVectorMove()) {
            IAero a = (IAero) entity;
            int[] v = {0, 0, 0, 0, 0, 0};

            // if this is the entity's first time deploying, we want to respect the "velocity" setting from the lobby
            if(entity.wasNeverDeployed()) {
                if (a.getCurrentVelocityActual() > 0) {
                    v[nFacing] = a.getCurrentVelocityActual();
                    entity.setVectors(v);
                }
            // this means the entity is coming back from off board, so we'll rotate the velocity vector by 180
            // and set it to 1/2 the magnitude
            } else {
                for(int x = 0; x < 6; x++) {
                    v[(x + 3) % 6] = entity.getVector(x) / 2;
                }

                entity.setVectors(v);
            }
        }

        entity.setPosition(coords);
        entity.setFacing(nFacing);
        entity.setSecondaryFacing(nFacing);
        IHex hex = game.getBoard().getHex(coords);
        if (assaultDrop) {
            entity.setAltitude(1);
            // from the sky!
            entity.setAssaultDropInProgress(true);
        } else if ((entity instanceof VTOL) && (entity.getExternalUnits().size() <= 0)) {
            // We should let players pick, but this simplifies a lot.
            // Only do it for VTOLs, though; assume everything else is on the
            // ground.
            entity.setElevation((hex.ceiling() - hex.surface()) + 1);
            while ((Compute.stackingViolation(game, entity, coords, null) != null)
                   && (entity.getElevation() <= 50)) {
                entity.setElevation(entity.getElevation() + 1);
            }
            if (entity.getElevation() > 50) {
                throw new IllegalStateException("Entity #" + entity.getId()
                        + " appears to be in an infinite loop trying to get a legal elevation.");
            }
        } else if (entity.isAero()) {
            // if the entity is airborne, then we don't want to set its
            // elevation below, because that will
            // default to 999
            if (entity.isAirborne()) {
                entity.setElevation(0);
                elevation = 0;
            }
            if (!game.getBoard().inSpace()) {
                // all spheroid craft should have velocity of zero in atmosphere
                // regardless of what was entered
                IAero a = (IAero) entity;
                if (a.isSpheroid() || game.getPlanetaryConditions().isVacuum()) {
                    a.setCurrentVelocity(0);
                    a.setNextVelocity(0);
                }
                // make sure that entity is above the level of the hex if in
                // atmosphere
                if (game.getBoard().inAtmosphere() && (entity.getAltitude() <= hex.ceiling(true))) {
                    // you can't be grounded on low atmosphere map
                    entity.setAltitude(hex.ceiling(true) + 1);
                }
            }
        } else if (entity.getMovementMode() == EntityMovementMode.SUBMARINE) {
            // TODO : Submarines should have a selectable height.
            // TODO : For now, pretend they're regular naval.
            entity.setElevation(0);
        } else if ((entity.getMovementMode() == EntityMovementMode.HOVER)
                || (entity.getMovementMode() == EntityMovementMode.WIGE)
                || (entity.getMovementMode() == EntityMovementMode.NAVAL)
                || (entity.getMovementMode() == EntityMovementMode.HYDROFOIL)) {
            // For now, assume they're on the surface.
            // entity elevation is relative to hex surface
            entity.setElevation(0);
        } else if (hex.containsTerrain(Terrains.ICE)) {
            entity.setElevation(0);
        } else {
            Building bld = game.getBoard().getBuildingAt(entity.getPosition());
            if ((bld != null) && (bld.getType() == Building.WALL)) {
                entity.setElevation(hex.terrainLevel(Terrains.BLDG_ELEV));
            }

        }
        // add the elevation that was passed into this method
        // TODO : currently only used for building placement, we should do this
        // TODO : more systematically with up/down buttons in the deployment display
        entity.setElevation(entity.getElevation() + elevation);
        boolean wigeFlyover = entity.getMovementMode() == EntityMovementMode.WIGE
                && hex.containsTerrain(Terrains.BLDG_ELEV)
                && entity.getElevation() > hex.terrainLevel(Terrains.BLDG_ELEV);


        // when first entering a building, we need to roll what type
        // of basement it has
        Building bldg = game.getBoard().getBuildingAt(entity.getPosition());
        if ((bldg != null)) {
            if (bldg.rollBasement(entity.getPosition(), game.getBoard(), reportmanager.getvPhaseReport())) {
                gamemanager.sendChangedHex(game, entity.getPosition());
                Vector<Building> buildings = new Vector<>();
                buildings.add(bldg);
                sendChangedBuildings(buildings);
            }
            boolean collapse = checkBuildingCollapseWhileMoving(bldg, entity, entity.getPosition());
            if (collapse) {
                addAffectedBldg(bldg, true);
                if (wigeFlyover) {
                // If the building is collapsed by a WiGE flying over it, the WiGE drops one level of elevation.
                    entity.setElevation(entity.getElevation() - 1);
                }
            }
        }

        entity.setDone(true);
        entity.setDeployed(true);
        entityManager.entityUpdate(entity.getId());
        reportmanager.addReport(doSetLocationsExposure(entity, hex, false, entity.getElevation()));
    }

    /**
     * Process a batch of entity attack (or twist) actions by adding them to the
     * proper list to be processed later.
     */
    private void processAttack(Entity entity, Vector<EntityAction> vector) {
        // Convert any null vectors to empty vectors to avoid NPEs.
        if (vector == null) {
            vector = new Vector<>(0);
        }

        // Not **all** actions take up the entity's turn.
        boolean setDone = !((game.getTurn() instanceof GameTurn.TriggerAPPodTurn)
                || (game.getTurn() instanceof GameTurn.TriggerBPodTurn));
        for (EntityAction ea : vector) {
            // is this the right entity?
            if (ea.getEntityId() != entity.getId()) {
                MegaMek.getLogger().error("Attack packet has wrong attacker");
                continue;
            }
            if (ea instanceof PushAttackAction) {
                // push attacks go the end of the displacement attacks
                PushAttackAction paa = (PushAttackAction) ea;
                entity.setDisplacementAttack(paa);
                game.addCharge(paa);
            } else if (ea instanceof DodgeAction) {
                entity.dodging = true;
            } else if (ea instanceof SpotAction) {
                entity.setSpotting(true);
                entity.setSpotTargetId(((SpotAction) ea).getTargetId());
            } else {
                // add to the normal attack list.
                game.addAction(ea);
            }

            // Anti-mech and pointblank attacks from
            // hiding may allow the target to respond.
            if (ea instanceof WeaponAttackAction) {
                final WeaponAttackAction waa = (WeaponAttackAction) ea;
                final String weaponName = entity.getEquipment(waa.getWeaponId()).getType().getInternalName();

                if (Infantry.SWARM_MEK.equals(weaponName) || Infantry.LEG_ATTACK.equals(weaponName)) {

                    // Does the target have any AP Pods available?
                    final Entity target = game.getEntity(waa.getTargetId());
                    for (Mounted equip : target.getMisc()) {
                        if (equip.getType().hasFlag(MiscType.F_AP_POD) && equip.canFire()) {

                            // Yup. Insert a game turn to handle AP pods.
                            // ASSUMPTION : AP pod declarations come
                            // immediately after the attack declaration.
                            game.insertNextTurn(new GameTurn.TriggerAPPodTurn(target.getOwnerId(), target.getId()));
                            send(PacketFactory.createTurnVectorPacket(game));

                            // We can stop looking.
                            break;

                        } // end found-available-ap-pod

                    } // Check the next piece of equipment on the target.

                    for (Mounted weapon : target.getWeaponList()) {
                        if (weapon.getType().hasFlag(WeaponType.F_B_POD) && weapon.canFire()) {

                            // Yup. Insert a game turn to handle B pods.
                            // ASSUMPTION : B pod declarations come
                            // immediately after the attack declaration.
                            game.insertNextTurn(new GameTurn.TriggerBPodTurn(target.getOwnerId(),
                                    target.getId(), weaponName));
                            send(PacketFactory.createTurnVectorPacket(game));

                            // We can stop looking.
                            break;

                        } // end found-available-b-pod
                    } // Check the next piece of equipment on the target.
                } // End check-for-available-ap-pod

                // Keep track of altitude loss for weapon attacks
                if (entity.isAero()) {
                    IAero aero = (IAero) entity;
                    if (waa.getAltitudeLoss(game) > aero.getAltLoss()) {
                        aero.setAltLoss(waa.getAltitudeLoss(game));
                    }
                }
            }

            // If attacker breaks grapple, defender may counter
            if (ea instanceof BreakGrappleAttackAction) {
                final BreakGrappleAttackAction bgaa = (BreakGrappleAttackAction) ea;
                final Entity att = (game.getEntity(bgaa.getEntityId()));
                if (att.isGrappleAttacker()) {
                    final Entity def = (game.getEntity(bgaa.getTargetId()));
                    // Remove existing break grapple by defender (if exists)
                    if (def.isDone()) {
                        game.removeActionsFor(def.getId());
                    } else {
                        game.removeTurnFor(def);
                        def.setDone(true);
                    }
                    // If defender is able, add a turn to declare counterattack
                    if (!def.isImmobile()) {
                        game.insertNextTurn(new GameTurn.CounterGrappleTurn(def.getOwnerId(), def.getId()));
                        send(PacketFactory.createTurnVectorPacket(game));
                    }
                }
            }
            if (ea instanceof ArtilleryAttackAction) {
                final ArtilleryAttackAction aaa = (ArtilleryAttackAction) ea;
                final Entity firingEntity = game.getEntity(aaa.getEntityId());
                Vector<AttackHandler> attacks = game.getAttacksVector();
                for (AttackHandler attackHandler : attacks) {
                    WeaponHandler wh = (WeaponHandler) attackHandler;
                    if (wh.waa instanceof ArtilleryAttackAction) {
                        ArtilleryAttackAction oaaa = (ArtilleryAttackAction) wh.waa;
                        if ((oaaa.getEntityId() == aaa.getEntityId())
                                && !oaaa.getTarget(game).getPosition().equals(aaa.getTarget(game).getPosition())) {
                            game.clearArtillerySpotters(firingEntity.getId(), aaa.getWeaponId());
                            break;
                        }
                    }
                }
                Iterator<Entity> spotters = game.getSelectedEntities(new EntitySelector() {
                            public int player = firingEntity.getOwnerId();
                            public Targetable target = aaa.getTarget(game);

                            public boolean accept(Entity entity) {
                                LosEffects los = LosEffects.calculateLos(game, entity.getId(), target);
                                return ((player == entity.getOwnerId()) && !(los.isBlocked()) && entity.isActive());
                            }
                        });
                Vector<Integer> spotterIds = new Vector<>();
                while (spotters.hasNext()) {
                    Integer id = spotters.next().getId();
                    spotterIds.addElement(id);
                }
                aaa.setSpotterIds(spotterIds);
            }

            // The equipment type of a club needs to be restored.
            if (ea instanceof ClubAttackAction) {
                ClubAttackAction caa = (ClubAttackAction) ea;
                Mounted club = caa.getClub();
                club.restore();
            }

            // Mark any AP Pod as used in this turn.
            if (ea instanceof TriggerAPPodAction) {
                TriggerAPPodAction tapa = (TriggerAPPodAction) ea;
                Mounted pod = entity.getEquipment(tapa.getPodId());
                pod.setUsedThisRound(true);
            }
            // Mark any B Pod as used in this turn.
            if (ea instanceof TriggerBPodAction) {
                TriggerBPodAction tba = (TriggerBPodAction) ea;
                Mounted pod = entity.getEquipment(tba.getPodId());
                pod.setUsedThisRound(true);
            }

            // Mark illuminated hexes, so they can be displayed
            if (ea instanceof SearchlightAttackAction) {
                boolean hexesAdded = ((SearchlightAttackAction) ea).setHexesIlluminated(game);
                // If we added new hexes, send them to all players.
                // These are spotlights at night, you know they're there.
                if (hexesAdded) {
                    send(PacketFactory.createIlluminatedHexesPacket(game));
                }
            }
        }

        // Apply altitude loss
        if (entity.isAero()) {
            IAero aero = (IAero) entity;
            if (aero.getAltLoss() > 0) {
                reportmanager.addReport(ReportFactory.createReport(9095, entity, aero.getAltLoss()));
                entity.setAltitude(entity.getAltitude() - aero.getAltLoss());
                aero.setAltLossThisRound(aero.getAltLoss());
                aero.resetAltLoss();
                entityManager.entityUpdate(entity.getId());
            }
        }

        // Unless otherwise stated,
        // this entity is done for the round.
        if (setDone) {
            entity.setDone(true);
        }
        entityManager.entityUpdate(entity.getId());

        Packet p = PacketFactory.createAttackPacket(vector, 0);
        if (game.isPhaseSimultaneous()) {
            // Update attack only to player who declared it & observers
            for (IPlayer player : game.getPlayersVector()) {
                if (player.canSeeAll() || player.isObserver() || (entity.getOwnerId() == player.getId())) {
                    send(player.getId(), p);
                }
            }
        } else {
            // update all players on the attacks. Don't worry about pushes being
            // a "charge" attack. It doesn't matter to the client.
            send(p);
        }
    }

    /**
     * Determine which missile attack actions could be affected by AMS, and
     * assign AMS (and APDS) to those attacks.
     */
    public void assignAMS() {
        // Get all of the coords that would be protected by APDS
        Hashtable<Coords, List<Mounted>> apdsCoords = game.getAPDSProtectedCoords();
        // Map target to a list of missile attacks directed at it
        Hashtable<Entity, Vector<WeaponHandler>> htAttacks = new Hashtable<>();
        // Keep track of each APDS, and which attacks it could affect
        Hashtable<Mounted, Vector<WeaponHandler>> apdsTargets = new Hashtable<>();

        for (AttackHandler ah : game.getAttacksVector()) {
            WeaponHandler wh = (WeaponHandler) ah;
            WeaponAttackAction waa = wh.waa;

            // for artillery attacks, the attacking entity
            // might no longer be in the game.
            //TODO : Yeah, I know there's an exploit here, but better able to shoot some ArrowIVs than none, right?
            if (game.getEntity(waa.getEntityId()) == null) {
                MegaMek.getLogger().info("Can't Assign AMS: Artillery firer is null!");
                continue;
            }

            Mounted weapon = game.getEntity(waa.getEntityId()).getEquipment(waa.getWeaponId());

            // Only entities can have AMS. Arrow IV doesn't target an entity until later, so we have to ignore them
            if (!(waa instanceof ArtilleryAttackAction) && (Targetable.TYPE_ENTITY != waa.getTargetType())) {
                continue;
            }

            // AMS is only used against attacks that hit (TW p129)
            if (wh.roll < wh.toHit.getValue()) {
                continue;
            }
            
            // Can only use AMS versus missiles. Artillery Bays might be firing Arrow IV homing missiles,
            // but lack the flag
            boolean isHomingMissile = false;
            if (wh instanceof ArtilleryWeaponIndirectHomingHandler
                    || wh instanceof ArtilleryBayWeaponIndirectHomingHandler) {
                Mounted ammoUsed = game.getEntity(waa.getEntityId()).getEquipment(waa.getAmmoId());
                AmmoType atype = ammoUsed == null ? null : (AmmoType) ammoUsed.getType();
                if (atype != null 
                        && (atype.getAmmoType() == AmmoType.T_ARROW_IV || atype.getAmmoType() == BombType.B_HOMING)) {
                    isHomingMissile = true;
                }
            }
            if (!weapon.getType().hasFlag(WeaponType.F_MISSILE) && !isHomingMissile) {
                continue;
            }

            // For Bearings-only Capital Missiles, don't assign during the offboard phase
            if (wh instanceof CapitalMissileBearingsOnlyHandler) {
                ArtilleryAttackAction aaa = (ArtilleryAttackAction) waa;
                if (aaa.getTurnsTilHit() > 0 || game.getPhase() != IGame.Phase.PHASE_FIRING) {
                    continue;
                }
            }

            // For Arrow IV homing artillery
            Entity target;
            if (waa instanceof ArtilleryAttackAction) {
                target = (waa.getTargetType() == Targetable.TYPE_ENTITY) ? (Entity) waa.getTarget(game) : null;

                // In case our target really is null.
                if (target == null) {
                    continue;
                }
            } else {
                target = game.getEntity(waa.getTargetId());
            }
            Vector<WeaponHandler> v = htAttacks.computeIfAbsent(target, k -> new Vector<>());
            v.addElement(wh);
            // Keep track of what weapon attacks could be affected by APDS
            if (apdsCoords.containsKey(target.getPosition())) {
                for (Mounted apds : apdsCoords.get(target.getPosition())) {
                    // APDS only affects attacks against friendly units
                    if (target.isEnemyOf(apds.getEntity())) {
                        continue;
                    }
                    Vector<WeaponHandler> handlerList = apdsTargets.computeIfAbsent(apds, k -> new Vector<>());
                    handlerList.add(wh);
                }
            }
        }
        
        // Let each target assign its AMS
        for (Entity e : htAttacks.keySet()) {
            Vector<WeaponHandler> vAttacks = htAttacks.get(e);
            // Allow MM to automatically assign AMS targets
            if (game.getOptions().booleanOption(OptionsConstants.BASE_AUTO_AMS)) {
                e.assignAMS(vAttacks);
            } else { // Allow user to manually assign targets
                manuallyAssignAMSTarget(e, vAttacks);
            }
        }

        // Let each APDS assign itself to an attack
        Set<WeaponAttackAction> targetedAttacks = new HashSet<>();
        for (Mounted apds : apdsTargets.keySet()) {
            List<WeaponHandler> potentialTargets = apdsTargets.get(apds);
            // Ensure we only target each attack once
            List<WeaponHandler> targetsToRemove = new ArrayList<>();
            for (WeaponHandler wh : potentialTargets) {
                if (targetedAttacks.contains(wh.getWaa())) {
                    targetsToRemove.add(wh);
                }
            }
            potentialTargets.removeAll(targetsToRemove);
            WeaponAttackAction targetedWAA;
            // Assign APDS to an attack
            if (game.getOptions().booleanOption(OptionsConstants.BASE_AUTO_AMS)) {
                targetedWAA = apds.assignAPDS(potentialTargets);
            } else { // Allow user to manually assign targets
                targetedWAA = manuallyAssignAPDSTarget(apds, potentialTargets);
            }
            if (targetedWAA != null) {
                targetedAttacks.add(targetedWAA);
            }
        }
    }

    /**
     * Convenience method for determining which missile attack will be targeted
     * with AMS on the supplied Entity
     *
     * @param apds
     *            The Entity with AMS
     * @param vAttacks
     *            List of missile attacks directed at e
     */
    private WeaponAttackAction manuallyAssignAPDSTarget(Mounted apds, List<WeaponHandler> vAttacks) {
        Entity e = apds.getEntity();
        if (e == null) {
            return null;
        }

        // Create a list of valid assignments for this APDS
        List<WeaponAttackAction> vAttacksInArc = new ArrayList<>(vAttacks.size());
        for (WeaponHandler wr : vAttacks) {
            boolean isInArc = Compute.isInArc(e.getGame(), e.getId(),
                    e.getEquipmentNum(apds),
                    game.getEntity(wr.waa.getEntityId()));
            boolean isInRange = e.getPosition().distance(
                    wr.getWaa().getTarget(game).getPosition()) <= 3;
            if (isInArc && isInRange) {
                vAttacksInArc.add(wr.waa);
            }
        }

        // If there are no valid attacks left, don't bother
        if (vAttacksInArc.size() < 1) {
            return null;
        }

        WeaponAttackAction targetedWAA = null;

        if (apds.curMode().equals("Automatic")) {
            targetedWAA = Compute.getHighestExpectedDamage(game,
                    vAttacksInArc, true);
        } else {
            // Send a client feedback request
            List<Integer> apdsDists = new ArrayList<>();
            for (WeaponAttackAction waa : vAttacksInArc) {
                apdsDists.add(waa.getTarget(game).getPosition().distance(e.getPosition()));
            }
            gamemanager.sendAPDSAssignCFR(e, apdsDists, vAttacksInArc);
            synchronized (cfrPacketQueue) {
                try {
                    cfrPacketQueue.wait();
                } catch (InterruptedException ex) {
                    // Do nothing
                }
                if (cfrPacketQueue.size() > 0) {
                    ServerConnectionListener.ReceivedPacket rp = cfrPacketQueue.poll();
                    int cfrType = (int) rp.packet.getData()[0];
                    // Make sure we got the right type of response
                    if (cfrType != Packet.COMMAND_CFR_APDS_ASSIGN) {
                        MegaMek.getLogger().error("Expected a COMMAND_CFR_AMS_ASSIGN CFR packet, received: " + cfrType);
                        throw new IllegalStateException();
                    }
                    Integer waaIndex = (Integer)rp.packet.getData()[1];
                    if (waaIndex != null) {
                        targetedWAA = vAttacksInArc.get(waaIndex);
                    }
                }
            }
        }

        if (targetedWAA != null) {
            targetedWAA.addCounterEquipment(apds);
            return targetedWAA;
        } else {
            return null;
        }
    }

    /**
     * Convenience method for determining which missile attack will be targeted
     * with AMS on the supplied Entity
     *
     * @param e
     *            The Entity with AMS
     * @param vAttacks
     *            List of missile attacks directed at e
     */
    private void manuallyAssignAMSTarget(Entity e, Vector<WeaponHandler> vAttacks) {
        //Fix for bug #1051 - don't send the targeting nag for a shutdown unit
        if (e.isShutDown()) {
            return;
        }
        // Current AMS targets: each attack can only be targeted once
        HashSet<WeaponAttackAction> amsTargets = new HashSet<>();
        // Pick assignment for each active AMS
        for (Mounted ams : e.getActiveAMS()) {
            // Skip APDS
            if (ams.isAPDS()) {
                continue;
            }
            // Create a list of valid assignments for this AMS
            List<WeaponAttackAction> vAttacksInArc = new ArrayList<>(vAttacks.size());
            for (WeaponHandler wr : vAttacks) {
                if (!amsTargets.contains(wr.waa) && Compute.isInArc(game, e.getId(), e.getEquipmentNum(ams),
                        game.getEntity(wr.waa.getEntityId()))) {
                    vAttacksInArc.add(wr.waa);
                }
            }

            // If there are no valid attacks left, don't bother
            if (vAttacksInArc.size() < 1) {
                continue;
            }

            WeaponAttackAction targetedWAA = null;

            if (ams.curMode().equals("Automatic")) {
                targetedWAA = Compute.getHighestExpectedDamage(game,
                        vAttacksInArc, true);
            } else {
                // Send a client feedback request
                gamemanager.sendAMSAssignCFR(e, ams, vAttacksInArc);
                synchronized (cfrPacketQueue) {
                    try {
                        cfrPacketQueue.wait();
                    } catch (InterruptedException ex) {
                        // Do nothing
                    }
                    if (cfrPacketQueue.size() > 0) {
                        ServerConnectionListener.ReceivedPacket rp = cfrPacketQueue.poll();
                        int cfrType = (int) rp.packet.getData()[0];
                        // Make sure we got the right type of response
                        if (cfrType != Packet.COMMAND_CFR_AMS_ASSIGN) {
                            MegaMek.getLogger().error("Expected a COMMAND_CFR_AMS_ASSIGN CFR packet, received: " + cfrType);
                            throw new IllegalStateException();
                        }
                        Integer waaIndex = (Integer)rp.packet.getData()[1];
                        if (waaIndex != null) {
                            targetedWAA = vAttacksInArc.get(waaIndex);
                        }
                    }
                }
            }

            if (targetedWAA != null) {
                targetedWAA.addCounterEquipment(ams);
                amsTargets.add(targetedWAA);
            }
        }
    }

    /**
     * Checks to see if any units can detected hidden units.
     */
    private void detectHiddenUnits() {
        // If hidden units aren't on, nothing to do
        if (!game.getOptions().booleanOption(OptionsConstants.ADVANCED_HIDDEN_UNITS)) {
            return;
        }
        // Get all hidden units
        List<Entity> hiddenUnits = new ArrayList<>();
        for (Entity ent : game.getEntitiesVector()) {
            if (ent.isHidden()) {
                hiddenUnits.add(ent);
            }
        }

        // If no one is hidden, there's nothing to do
        if (hiddenUnits.size() < 1) {
            return;
        }

        Set<Integer> reportPlayers = new HashSet<>();
        // See if any unit with a probe, detects any hidden units
        for (Entity detector : game.getEntitiesVector()) {
            int probeRange = detector.getBAPRange();

            // Units without a position won't be able to detect
            if (detector.getPosition() == null) {
                continue;
            }

            for (Entity detected : hiddenUnits) {
                // Only detected enemy units
                if (!detector.isEnemyOf(detected)) {
                    continue;
                }
                // Can't detect units without a position
                if (detected.getPosition() == null) {
                    continue;
                }
                // Can only detect units within the probes range
                int dist = detector.getPosition().distance(
                        detected.getPosition());

                // An adjacent enemy unit will detect hidden units, TW pg 259
                if (dist > 1 && dist > probeRange) {
                    continue;
                }

                // Check for Void/Null Sig - only detected by Bloodhound probes
                if (dist > 1 && (detected instanceof Mech)) {
                    Mech m = (Mech) detected;
                    if ((m.isVoidSigActive() || m.isNullSigActive())
                            && !detector.hasWorkingMisc(MiscType.F_BLOODHOUND)) {
                        continue;
                    }
                }

                // Check for Infantry stealth armor
                if (dist > 1 && (detected instanceof BattleArmor)) {
                    BattleArmor ba = (BattleArmor) detected;
                    // Need Bloodhound to detect BA stealth armor
                    if (ba.isStealthy() && !detector.hasWorkingMisc(MiscType.F_BLOODHOUND)) {
                        continue;
                    }
                } else if (dist > 1 && (detected instanceof Infantry)) {
                    Infantry inf = (Infantry) detected;
                    // Can't detect sneaky infantry and need bloodhound to detect non-sneaky inf
                    if (inf.isStealthy() && !detector.hasWorkingMisc(MiscType.F_BLOODHOUND)) {
                        continue;
                    }
                }

                LosEffects los = LosEffects.calculateLos(game, detector.getId(), detected);
                if (los.canSee() || dist <= 1) {
                    detected.setHidden(false);
                    entityManager.entityUpdate(detected.getId());
                    reportmanager.addReport(ReportFactory.createReport(9960, detector, detected.getPosition().getBoardNum()));
                    Report.addNewline(reportmanager.getvPhaseReport());
                    reportPlayers.add(detector.getOwnerId());
                    reportPlayers.add(detected.getOwnerId());
                }
            }
        }

        if (reportmanager.getvPhaseReport().size() > 0 && game.getPhase() == Phase.PHASE_MOVEMENT
                && (game.getTurnIndex() + 1) < game.getTurnVector().size()) {
            for (Integer playerId : reportPlayers) {
                send(playerId, PacketFactory.createSpecialReportPacket(reportmanager));
            }
        }
    }

    /**
     * Called to what players can see what units. This is used to determine who
     * can see what in double blind reports.
     */
    private void resolveWhatPlayersCanSeeWhatUnits() {
        List<ECMInfo> allECMInfo = null;
        if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_SENSORS)) {
            allECMInfo = ComputeECM.computeAllEntitiesECMInfo(game.getEntitiesVector());
        }
        Map<EntityTargetPair, LosEffects> losCache = new HashMap<>();
        for (Entity entity : game.getEntitiesVector()) {
            // We are hidden once again!
            entity.clearSeenBy();
            entity.clearDetectedBy();
            // Handle visual spotting
            for (IPlayer p : whoCanSee(entity, false, losCache)) {
                entity.addBeenSeenBy(p);
            }
            // Handle detection by sensors
            for (IPlayer p : whoCanDetect(entity, allECMInfo, losCache)) {
                    entity.addBeenDetectedBy(p);
            }
        }
    }

    /**
     * Called during the weapons fire phase. Resolves anything other than
     * weapons fire that happens. Torso twists, for example.
     */
    private void resolveAllButWeaponAttacks() {
        Vector<EntityAction> triggerPodActions = new Vector<>();
        // loop through actions and handle everything we expect except attacks
        for (EntityAction ea : game.getActionsVector()) {
            Entity entity = game.getEntity(ea.getEntityId());
            if (ea instanceof TorsoTwistAction) {
                TorsoTwistAction tta = (TorsoTwistAction) ea;
                if (entity.canChangeSecondaryFacing()) {
                    entity.setSecondaryFacing(tta.getFacing());
                    entity.postProcessFacingChange();
                }
            } else if (ea instanceof FlipArmsAction) {
                FlipArmsAction faa = (FlipArmsAction) ea;
                entity.setArmsFlipped(faa.getIsFlipped());
            } else if (ea instanceof FindClubAction) {
                resolveFindClub(entity);
            } else if (ea instanceof UnjamAction) {
                reportmanager.resolveUnjam(entity, game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_UNJAM_UAC));
            } else if (ea instanceof ClearMinefieldAction) {
                resolveClearMinefield(entity, ((ClearMinefieldAction) ea).getMinefield());
            } else if (ea instanceof TriggerAPPodAction) {
                TriggerAPPodAction tapa = (TriggerAPPodAction) ea;

                // Don't trigger the same pod twice.
                if (!triggerPodActions.contains(tapa)) {
                    triggerAPPod(entity, tapa.getPodId());
                    triggerPodActions.addElement(tapa);
                } else {
                    MegaMek.getLogger().error("AP Pod #" + tapa.getPodId() + " on " + entity.getDisplayName()
                                    + " was already triggered this round!!");
                }
            } else if (ea instanceof TriggerBPodAction) {
                TriggerBPodAction tba = (TriggerBPodAction) ea;

                // Don't trigger the same pod twice.
                if (!triggerPodActions.contains(tba)) {
                    triggerBPod(entity, tba.getPodId(), game.getEntity(tba.getTargetId()));
                    triggerPodActions.addElement(tba);
                } else {
                    MegaMek.getLogger().error("B Pod #" + tba.getPodId() + " on " + entity.getDisplayName()
                                    + " was already triggered this round!!");
                }
            } else if (ea instanceof SearchlightAttackAction) {
                SearchlightAttackAction saa = (SearchlightAttackAction) ea;
                reportmanager.addReport(saa.resolveAction(game));
            } else if (ea instanceof UnjamTurretAction) {
                if (entity instanceof Tank) {
                    ((Tank) entity).unjamTurret(((Tank) entity).getLocTurret());
                    ((Tank) entity).unjamTurret(((Tank) entity).getLocTurret2());
                    reportmanager.addReport(ReportFactory.createReport(3033, entity));
                } else {
                    MegaMek.getLogger().error("Non-Tank tried to unjam turret");
                }
            } else if (ea instanceof RepairWeaponMalfunctionAction) {
                if (entity instanceof Tank) {
                    Mounted m = entity.getEquipment(((RepairWeaponMalfunctionAction) ea).getWeaponId());
                    m.setJammed(false);
                    ((Tank) entity).getJammedWeapons().remove(m);
                    reportmanager.addReport(ReportFactory.createReport(3034, entity, m.getName()));
                } else {
                    MegaMek.getLogger().error("Non-Tank tried to repair weapon malfunction");
                }
            }
        }
    }

    /*
     * Called during the weapons firing phase to initiate self destruction.
     */
    private Vector<Report> resolveSelfDestructions() {
        Vector<Report> vDesc = new Vector<>();
        for (Entity e : game.getEntitiesVector()) {
            if (e.getSelfDestructInitiated() && e.hasEngine()) {
                int target = e.getCrew().getPiloting();
                int roll = e.getCrew().rollPilotingSkill();
                Report r = ReportFactory.createPublicReport(6166, 1, e, target, roll);
                r.choose(roll >= target);
                vDesc.add(r);

                // Blow it up...
                if (roll >= target) {
                    int engineRating = e.getEngine().getRating();
                    vDesc.add(ReportFactory.createPublicReport(5400, 2, e));

                    if (e instanceof Mech) {
                        Mech mech = (Mech) e;
                        if (mech.isAutoEject()
                                && (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                || (game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                && mech.isCondEjectEngine()))) {
                            vDesc.addAll(ejectEntity(e, true));
                        }
                    }
                    e.setSelfDestructedThisTurn(true);
                    doFusionEngineExplosion(engineRating, e.getPosition(), vDesc, null);
                    Report.addNewline(vDesc);
                    Report.addNewline(vDesc);
                    vDesc.add(ReportFactory.createPublicReport(5410, 2, e));
                }
                e.setSelfDestructInitiated(false);
            }
        }
        return vDesc;
    }

    private void reportGhostTargetRolls() {
        // run through a vector of deployed game entities. If they have
        // ghost targets, then check the roll and report it
        for (Entity entity : game.getEntitiesVector()) {
            if (entity.isDeployed() && entity.hasGhostTargets(false)) {
                // Ghost target mod is +3 per errata
                int target = entity.getCrew().getPiloting() + 3;
                if (entity.hasETypeFlag(Entity.ETYPE_PROTOMECH)) {
                    target = entity.getCrew().getGunnery() + 3;
                }
                int roll = entity.getGhostTargetRoll();
                Report r = ReportFactory.createReport(3630, entity, target, roll);
                r.choose(roll >= target);
                reportmanager.addReport(r);
            }
        }
        reportmanager.addNewLines();
    }

    private void reportLargeCraftECCMRolls() {
        // run through a vector of deployed game entities. If they are
        // large craft in space, then check the roll and report it
        if (!game.getBoard().inSpace()
            || !game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_STRATOPS_ECM)) {
            return;
        }

        for (Entity entity : game.getEntitiesVector()) {
            if (entity.isDeployed() && entity.isLargeCraft()) {
                int target = ((Aero) entity).getECCMTarget();
                int roll = ((Aero) entity).getECCMRoll();
                int mod = ((Aero) entity).getECCMBonus();
                reportmanager.addReport(ReportFactory.createReport(3635, entity, roll, target, mod));
            }
        }
    }

    private void resolveClearMinefield(Entity ent, Minefield mf) {

        if ((null == mf) || (null == ent) || ent.isDoomed() || ent.isDestroyed()) {
            return;
        }

        Coords pos = mf.getCoords();
        int clear = Minefield.CLEAR_NUMBER_INFANTRY;
        int boom = Minefield.CLEAR_NUMBER_INFANTRY_ACCIDENT;

        int reportID = 2245;
        // Does the entity has a minesweeper?
        if ((ent instanceof BattleArmor)) {
            BattleArmor ba = (BattleArmor)ent;
            String mcmName = BattleArmor.MANIPULATOR_TYPE_STRINGS[BattleArmor.MANIPULATOR_BASIC_MINE_CLEARANCE];
            if (ba.getLeftManipulatorName().equals(mcmName)) {
                clear = Minefield.CLEAR_NUMBER_BA_SWEEPER;
                boom = Minefield.CLEAR_NUMBER_BA_SWEEPER_ACCIDENT;
                reportID = 2246;
            }
        } else if (ent instanceof Infantry) { // Check Minesweeping Engineers
            Infantry inf = (Infantry) ent;
            if (inf.hasSpecialization(Infantry.MINE_ENGINEERS)) {
                clear = Minefield.CLEAR_NUMBER_INF_ENG;
                boom = Minefield.CLEAR_NUMBER_INF_ENG_ACCIDENT;
                reportID = 2247;
            }
        }
        // mine clearing roll
        reportmanager.addReport(ReportFactory.createReport(reportID, ent, ent.getShortName(), Minefield.getDisplayableName(mf.getType()), pos.getBoardNum()));

        if (clearMinefield(mf, ent, clear, boom, reportmanager.getvPhaseReport())) {
            gamemanager.removeMinefield(game, mf);
        }
        // some mines might have blown up
        gamemanager.resetMines(game);

        reportmanager.addNewLines();
    }

    /**
     * Trigger the indicated AP Pod of the entity.
     *
     * @param entity the <code>Entity</code> triggering the AP Pod.
     * @param podId  the <code>int</code> ID of the AP Pod.
     */
    private void triggerAPPod(Entity entity, int podId) {
        // Get the mount for this pod.
        Mounted mount = entity.getEquipment(podId);

        // Confirm that this is, indeed, an AP Pod.
        if (null == mount) {
            MegaMek.getLogger().error("Expecting to find an AP Pod at " + podId + " on the unit, " + entity.getDisplayName()
                            + " but found NO equipment at all!!!");
            return;
        }
        EquipmentType equip = mount.getType();
        if (!(equip instanceof MiscType) || !equip.hasFlag(MiscType.F_AP_POD)) {
            MegaMek.getLogger().error("Expecting to find an AP Pod at " + podId + " on the unit, "+ entity.getDisplayName()
                            + " but found " + equip.getName() + " instead!!!");
            return;
        }

        // Now confirm that the entity can trigger the pod.
        // Ignore the "used this round" flag.
        boolean oldFired = mount.isUsedThisRound();
        mount.setUsedThisRound(false);
        boolean canFire = mount.canFire();
        mount.setUsedThisRound(oldFired);
        if (!canFire) {
            MegaMek.getLogger().error("Can not trigger the AP Pod at " + podId + " on the unit, "
                    + entity.getDisplayName() + "!!!");
            return;
        }

        // Mark the pod as fired and log the action.
        mount.setFired(true);
        reportmanager.addReport(ReportFactory.createReport(3010, entity));

        // Walk through ALL entities in the triggering entity's hex.
        for (Entity target : game.getEntitiesVector(entity.getPosition())) {

            // Is this an unarmored infantry platoon?
            if ((target instanceof Infantry)
                && !(target instanceof BattleArmor)) {

                // Roll d6-1 for damage.
                final int damage = Math.max(1, Compute.d6() - 1);

                // Damage the platoon.
                reportmanager.addReport(damageEntity(target, new HitData(Infantry.LOC_INFANTRY), damage));

                // Damage from AP Pods is applied immediately.
                target.applyDamage();

            } // End target-is-unarmored

            // Nope, the target is immune.
            // Don't make a log entry for the triggering entity.
            else if (!entity.equals(target)) {
                reportmanager.addReport(ReportFactory.createReport(3020, 2, target));
            }

        } // Check the next entity in the triggering entity's hex.
    }

    /**
     * Trigger the indicated B Pod of the entity.
     *
     * @param entity the <code>Entity</code> triggering the B Pod.
     * @param podId  the <code>int</code> ID of the B Pod.
     */
    private void triggerBPod(Entity entity, int podId, Entity target) {
        // Get the mount for this pod.
        Mounted mount = entity.getEquipment(podId);

        // Confirm that this is, indeed, an Anti-BA Pod.
        if (null == mount) {
            MegaMek.getLogger().error("Expecting to find an B Pod at " + podId + " on the unit, "
                    + entity.getDisplayName() + " but found NO equipment at all!!!");
            return;
        }
        EquipmentType equip = mount.getType();
        if (!(equip instanceof WeaponType) || !equip.hasFlag(WeaponType.F_B_POD)) {
            MegaMek.getLogger().error("Expecting to find an B Pod at " + podId + " on the unit, "
                    + entity.getDisplayName() + " but found " + equip.getName() + " instead!!!");
            return;
        }

        // Now confirm that the entity can trigger the pod.
        // Ignore the "used this round" flag.
        boolean oldFired = mount.isUsedThisRound();
        mount.setUsedThisRound(false);
        boolean canFire = mount.canFire();
        mount.setUsedThisRound(oldFired);
        if (!canFire) {
            MegaMek.getLogger().error("Can not trigger the B Pod at " + podId + " on the unit, "
                    + entity.getDisplayName() + "!!!");
            return;
        }

        // Mark the pod as fired and log the action.
        mount.setFired(true);
        reportmanager.addReport(ReportFactory.createReport(3011, entity));

        // Is this an unarmored infantry platoon?
        if ((target instanceof Infantry) && !(target instanceof BattleArmor)) {

            // Roll d6 for damage.
            final int damage = Compute.d6();

            // Damage the platoon.
            reportmanager.addReport(damageEntity(target, new HitData(Infantry.LOC_INFANTRY), damage));

            // Damage from AP Pods is applied immediately.
            target.applyDamage();

            // End target-is-unarmored
        } else if (target instanceof BattleArmor) {
            // 20 damage in 5 point clusters
            final int damage = 5;

            // Damage the squad.
            reportmanager.addReport(damageEntity(target, target.rollHitLocation(0, 0), damage));
            reportmanager.addReport(damageEntity(target, target.rollHitLocation(0, 0), damage));
            reportmanager.addReport(damageEntity(target, target.rollHitLocation(0, 0), damage));
            reportmanager.addReport(damageEntity(target, target.rollHitLocation(0, 0), damage));

            // Damage from B Pods is applied immediately.
            target.applyDamage();
        }

        // Nope, the target is immune.
        // Don't make a log entry for the triggering entity.
        else if (!entity.equals(target)) {
            reportmanager.addReport(ReportFactory.createReport(3020, 2, target));
        }
    }

    /**
     * Resolve an Unjam Action object
     */
    private void resolveUnjam(Entity entity) {
        final int TN = entity.getCrew().getGunnery() + 3;
        int reportID = 3025;
        if (game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_UNJAM_UAC)) {
            reportID = 3026;
        }
        reportmanager.addReport(ReportFactory.createReport(reportID, entity));
        for (Mounted mounted : entity.getTotalWeaponList()) {
            if (mounted.isJammed() && !mounted.isDestroyed()) {
                WeaponType wtype = (WeaponType) mounted.getType();
                if (wtype.getAmmoType() == AmmoType.T_AC_ROTARY) {
                    int roll = Compute.d6(2);
                    Report r = ReportFactory.createReport(3030, 1, entity, wtype.getName());
                    r.add(TN, roll);
                    if (roll >= TN) {
                        r.choose(true);
                        mounted.setJammed(false);
                    } else {
                        r.choose(false);
                    }
                    reportmanager.addReport(r);
                }
                // Unofficial option to unjam UACs, ACs, and LACs like Rotary
                // Autocannons
                if (((wtype.getAmmoType() == AmmoType.T_AC_ULTRA)
                        || (wtype.getAmmoType() == AmmoType.T_AC_ULTRA_THB)
                        || (wtype.getAmmoType() == AmmoType.T_AC)
                        || (wtype.getAmmoType() == AmmoType.T_AC_IMP)
                        || (wtype.getAmmoType() == AmmoType.T_PAC)
                        || (wtype.getAmmoType() == AmmoType.T_LAC))
                        && game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_UNJAM_UAC)) {
                    int roll = Compute.d6(2);
                    Report r = ReportFactory.createReport(3030, 1, entity, wtype.getName());
                    r.add(TN, roll);
                    if (roll >= TN) {
                        r.choose(true);
                        mounted.setJammed(false);
                    } else {
                        r.choose(false);
                    }
                    reportmanager.addReport(r);
                }
            }
        }
    }

    private void resolveFindClub(Entity entity) {
        EquipmentType clubType = null;

        entity.setFindingClub(true);

        // Get the entity's current hex.
        Coords coords = entity.getPosition();
        IHex curHex = game.getBoard().getHex(coords);

        Report r;

        // Is there a blown off arm in the hex?
        if (curHex.terrainLevel(Terrains.ARMS) > 0) {
            clubType = EquipmentType.get(EquipmentTypeLookup.LIMB_CLUB);
            curHex.addTerrain(Terrains.getTerrainFactory().createTerrain(
                    Terrains.ARMS, curHex.terrainLevel(Terrains.ARMS) - 1));
            gamemanager.sendChangedHex(game, entity.getPosition());
            reportmanager.addReport(ReportFactory.createReport(3035, entity));
        }
        // Is there a blown off leg in the hex?
        else if (curHex.terrainLevel(Terrains.LEGS) > 0) {
            clubType = EquipmentType.get(EquipmentTypeLookup.LIMB_CLUB);
            curHex.addTerrain(Terrains.getTerrainFactory().createTerrain(
                    Terrains.LEGS, curHex.terrainLevel(Terrains.LEGS) - 1));
            gamemanager.sendChangedHex(game, entity.getPosition());
            reportmanager.addReport(ReportFactory.createReport(3040, entity));
        }

        // Is there the rubble of a medium, heavy,
        // or hardened building in the hex?
        else if (Building.LIGHT < curHex.terrainLevel(Terrains.RUBBLE)) {

            // Finding a club is not guaranteed. The chances are
            // based on the type of building that produced the
            // rubble.
            boolean found = false;
            int roll = Compute.d6(2);
            switch (curHex.terrainLevel(Terrains.RUBBLE)) {
                case Building.MEDIUM:
                    if (roll >= 7) {
                        found = true;
                    }
                    break;
                case Building.HEAVY:
                    if (roll >= 6) {
                        found = true;
                    }
                    break;
                case Building.HARDENED:
                    if (roll >= 5) {
                        found = true;
                    }
                    break;
                case Building.WALL:
                    if (roll >= 13) {
                        found = true;
                    }
                    break;
                default:
                    // we must be in ultra
                    if (roll >= 4) {
                        found = true;
                    }
            }

            // Let the player know if they found a club.
            int reportID = 3050;
            if (found) {
                clubType = EquipmentType.get(EquipmentTypeLookup.GIRDER_CLUB);
                reportID = 3045;
            }

            reportmanager.addReport(ReportFactory.createReport(reportID, entity));
        }

        // Are there woods in the hex?
        else if (curHex.containsTerrain(Terrains.WOODS) || curHex.containsTerrain(Terrains.JUNGLE)) {
            clubType = EquipmentType.get(EquipmentTypeLookup.TREE_CLUB);
            reportmanager.addReport(ReportFactory.createReport(3055, entity));
        }

        // add the club
        try {
            if (clubType != null) {
                entity.addEquipment(clubType, Entity.LOC_NONE);
            }
        } catch (LocationFullException ex) {
            // unlikely...
            reportmanager.addReport(ReportFactory.createReport(3060, entity));
        }
    }

    /**
     * Try to ignite the hex, taking into account existing fires and the
     * effects of Inferno rounds.
     *
     * @param c
     *            - the <code>Coords</code> of the hex being lit.
     * @param entityId
     *            - the <code>int</code> id of the entity involved.
     * @param bInferno
     *            - <code>true</code> if the weapon igniting the hex is an
     *            Inferno round. If some other weapon or ammo is causing the
     *            roll, this should be <code>false</code>.
     * @param bHotGun
     *            - <code>true</code> if the weapon is plasma/flamer/incendiary
     *            LRM/etc
     * @param nTargetRoll
     *            - the <code>TargetRoll</code> for the ignition roll.
     * @param bReportAttempt
     *            - <code>true</code> if the attempt roll should be added to the
     *            report.
     * @param accidentTarget
     *            - <code>int</code> the target number below which a roll has to
     *            be made in order to try igniting a hex accidentally. -1 for
     *            intentional
     */
    public boolean tryIgniteHex(Coords c, int entityId, boolean bHotGun,
            boolean bInferno, TargetRoll nTargetRoll, boolean bReportAttempt,
            int accidentTarget, Vector<Report> vPhaseReport) {

        IHex hex = game.getBoard().getHex(c);
        Report r;

        // Ignore bad coordinates.
        if (hex == null
                // Ignore if fire is not enabled as a game option
                || !game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_START_FIRE)) {
            return false;
        }

        // is the hex ignitable (how are infernos handled?)
        if (!hex.isIgnitable()) {
            return false;
        }

        // first for accidental ignitions, make the necessary roll
        if (accidentTarget > -1) {
            // if this hex is in snow, then accidental ignitions are not
            // possible
            if (hex.containsTerrain(Terrains.SNOW)) {
                return false;
            }
            nTargetRoll.addModifier(2, "accidental");
            int accidentRoll = Compute.d6(2);
            r = ReportFactory.createReport(3066);
            r.subject = entityId;
            r.add(accidentTarget, accidentRoll);
            r.indent(2);
            if (accidentRoll > accidentTarget) {
                r.choose(false);
                vPhaseReport.add(r);
                return false;
            }
            r.choose(true);
            vPhaseReport.add(r);
        }

        int terrainMod = hex.getIgnitionModifier();
        if (terrainMod != 0) {
            nTargetRoll.addModifier(terrainMod, "terrain");
        }

        // building modifiers
        Building bldg = game.getBoard().getBuildingAt(c);
        if (null != bldg) {
            nTargetRoll.addModifier(bldg.getType() - 3, "building");
        }

        // add in any modifiers for planetary conditions
        int weatherMod = game.getPlanetaryConditions().getIgniteModifiers();
        if (weatherMod != 0) {
            nTargetRoll.addModifier(weatherMod, "conditions");
        }

        // if there is snow on the ground and this a hotgun or inferno, it may
        // melt the snow instead
        if ((hex.containsTerrain(Terrains.SNOW) || hex.containsTerrain(Terrains.ICE)) && (bHotGun || bInferno)) {
            int meltCheck = Compute.d6(2);
            boolean melted = (hex.terrainLevel(Terrains.SNOW) > 1 && meltCheck == 12) ||
                    (hex.containsTerrain(Terrains.ICE) && meltCheck > 9) ||
                    (hex.containsTerrain(Terrains.SNOW) && meltCheck > 7) ||
                    bInferno;

            if (melted) {
                vPhaseReport.addAll(meltIceAndSnow(c, entityId));
                return false;
            }

        }

        // inferno always ignites
        // ERRATA not if targeting clear hexes for ignition is disabled.
        if (bInferno && !game.getOptions().booleanOption(OptionsConstants.ADVANCED_NO_IGNITE_CLEAR)) {
            nTargetRoll = new TargetRoll(0, "inferno");
        }

        // no lighting fires in tornadoes
        if (game.getPlanetaryConditions().getWindStrength() > PlanetaryConditions.WI_STORM) {
            nTargetRoll = new TargetRoll(TargetRoll.AUTOMATIC_FAIL, "tornado");
        }

        // The hex may already be on fire.
        if (hex.containsTerrain(Terrains.FIRE)) {
            if (bReportAttempt) {
                r = new Report(3065);
                r.indent(2);
                r.subject = entityId;
                vPhaseReport.add(r);
            }
        } else if (checkIgnition(c, nTargetRoll, bInferno, entityId, vPhaseReport)) {
            return true;
        }
        return false;
    }

    /**
     * Try to ignite the hex, taking into account existing fires and the
     * effects of Inferno rounds. This version of the method will not report the
     * attempt roll.
     *
     * @param c
     *            - the <code>Coords</code> of the hex being lit.
     * @param entityId
     *            - the <code>int</code> id of the entity involved.
     * @param bInferno
     *            - <code>true</code> if the weapon igniting the hex is an
     *            Inferno round. If some other weapon or ammo is causing the
     *            roll, this should be <code>false</code>.
     * @param nTargetRoll
     *            - the <code>int</code> roll target for the attempt.
     */
    public boolean tryIgniteHex(Coords c, int entityId, boolean bHotGun, boolean bInferno, TargetRoll nTargetRoll,
                                int accidentTarget, Vector<Report> vPhaseReport) {
        return tryIgniteHex(c, entityId, bHotGun, bInferno, nTargetRoll, false, accidentTarget,
                vPhaseReport);
    }

    public Vector<Report> tryClearHex(Coords c, int nDamage, int entityId) {
        Vector<Report> vPhaseReport = new Vector<>();
        IHex h = game.getBoard().getHex(c);
        if (h == null) {
            return vPhaseReport;
        }
        ITerrain woods = h.getTerrain(Terrains.WOODS);
        ITerrain jungle = h.getTerrain(Terrains.JUNGLE);
        ITerrain ice = h.getTerrain(Terrains.ICE);
        ITerrain magma = h.getTerrain(Terrains.MAGMA);
        Report r;
        int reportType = Report.HIDDEN;
        if (entityId == Entity.NONE) {
            reportType = Report.PUBLIC;
        }
        if (woods != null) {
            int tf = woods.getTerrainFactor() - nDamage;
            int level = woods.getLevel();
            int folEl = h.terrainLevel(Terrains.FOLIAGE_ELEV);
            if (tf <= 0) {
                h.removeTerrain(Terrains.WOODS);
                h.removeTerrain(Terrains.FOLIAGE_ELEV);
                h.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.ROUGH, 1));
                // light converted to rough
                r = new Report(3090, reportType);
                r.subject = entityId;
                vPhaseReport.add(r);
            } else if ((tf <= 50) && (level > 1)) {
                h.removeTerrain(Terrains.WOODS);
                h.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.WOODS, 1));
                if (folEl != 1) {
                    h.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.FOLIAGE_ELEV, 2));
                }
                woods = h.getTerrain(Terrains.WOODS);
                // heavy converted to light
                r = new Report(3085, reportType);
                r.subject = entityId;
                vPhaseReport.add(r);
            } else if ((tf <= 90) && (level > 2)) {
                h.removeTerrain(Terrains.WOODS);
                h.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.WOODS, 2));
                if (folEl != 1) {
                    h.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.FOLIAGE_ELEV, 2));
                }
                woods = h.getTerrain(Terrains.WOODS);
                // ultra heavy converted to heavy
                r = new Report(3082, reportType);
                r.subject = entityId;
                vPhaseReport.add(r);
            }
            woods.setTerrainFactor(tf);
        }
        if (jungle != null) {
            int tf = jungle.getTerrainFactor() - nDamage;
            int level = jungle.getLevel();
            int folEl = h.terrainLevel(Terrains.FOLIAGE_ELEV);
            if (tf < 0) {
                h.removeTerrain(Terrains.JUNGLE);
                h.removeTerrain(Terrains.FOLIAGE_ELEV);
                h.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.ROUGH, 1));
                // light converted to rough
                r = new Report(3091, reportType);
                r.subject = entityId;
                vPhaseReport.add(r);
            } else if ((tf <= 50) && (level > 1)) {
                h.removeTerrain(Terrains.JUNGLE);
                h.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.JUNGLE, 1));
                if (folEl != 1) {
                    h.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.FOLIAGE_ELEV, 2));
                }
                jungle = h.getTerrain(Terrains.JUNGLE);
                // heavy converted to light
                r = new Report(3086, reportType);
                r.subject = entityId;
                vPhaseReport.add(r);
            } else if ((tf <= 90) && (level > 2)) {
                h.removeTerrain(Terrains.JUNGLE);
                h.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.JUNGLE, 2));
                if (folEl != 1) {
                    h.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.FOLIAGE_ELEV, 2));
                }
                jungle = h.getTerrain(Terrains.JUNGLE);
                // ultra heavy converted to heavy
                r = new Report(3083, reportType);
                r.subject = entityId;
                vPhaseReport.add(r);
            }
            jungle.setTerrainFactor(tf);
        }
        if (ice != null) {
            int tf = ice.getTerrainFactor() - nDamage;
            if (tf <= 0) {
                // ice melted
                r = new Report(3092, reportType);
                r.subject = entityId;
                vPhaseReport.add(r);
                vPhaseReport.addAll(resolveIceBroken(c));
            } else {
                ice.setTerrainFactor(tf);
            }
        }
        if ((magma != null) && (magma.getLevel() == 1)) {
            int tf = magma.getTerrainFactor() - nDamage;
            if (tf <= 0) {
                // magma crust destroyed
                r = new Report(3093, reportType);
                r.subject = entityId;
                vPhaseReport.add(r);
                h.removeTerrain(Terrains.MAGMA);
                h.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.MAGMA, 2));
                for (Entity en : game.getEntitiesVector(c)) {
                    doMagmaDamage(en, false);
                }
            } else {
                magma.setTerrainFactor(tf);
            }
        }
        gamemanager.sendChangedHex(game, c);

        // any attempt to clear an heavy industrial hex may cause an exposion
        checkExplodeIndustrialZone(c, vPhaseReport);

        return vPhaseReport;
    }

    /**
     * Handle a charge's damage
     */
    public void resolveChargeDamage(Entity ae, Entity te, ToHitData toHit, int direction) {
        resolveChargeDamage(ae, te, toHit, direction, false, true, false);
    }

    public void resolveChargeDamage(Entity ae, Entity te, ToHitData toHit, int direction, boolean glancing,
                                     boolean throughFront, boolean airmechRam) {
        // we hit...
        PilotingRollData chargePSR = null;
        // If we're upright, we may fall down.
        if (!ae.isProne() && !airmechRam) {
            chargePSR = new PilotingRollData(ae.getId(), 2, "charging");
        }

        // Damage To Target
        int damage;

        // Damage to Attacker
        int damageTaken;

        if (airmechRam) {
            damage = AirmechRamAttackAction.getDamageFor(ae);
            damageTaken = AirmechRamAttackAction.getDamageTakenBy(ae, te);
        } else {
            damage = ChargeAttackAction.getDamageFor(ae, te, game.getOptions()
                    .booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_CHARGE_DAMAGE), toHit.getMoS());
            damageTaken = ChargeAttackAction.getDamageTakenBy(ae, te, game
                    .getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_CHARGE_DAMAGE));
        }
        if (ae.hasWorkingMisc(MiscType.F_RAM_PLATE)) {
            damage = (int) Math.ceil(damage * 1.5);
            damageTaken = (int) Math.floor(damageTaken * 0.5);
        }
        if (glancing) {
            // Glancing Blow rule doesn't state whether damage to attacker on charge
            // or DFA is halved as well, assume yes. TODO : Check with PM
            damage = gamemanager.applyInfrantryDamageReduction(te, damage);
            damageTaken = (int) Math.floor(damageTaken / 2.0);
        }

        if (game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW)
                && ((toHit.getMoS() / 3) >= 1)) {
            damage += toHit.getMoS() / 3;
        }

        // Is the target inside a building?
        final boolean targetInBuilding = Compute.isInBuilding(game, te);

        // Which building takes the damage?
        Building bldg = game.getBoard().getBuildingAt(te.getPosition());

        // The building shields all units from a certain amount of damage.
        // The amount is based upon the building's CF at the phase's start.
        int bldgAbsorbs = 0;
        if (targetInBuilding && (bldg != null)) {
            bldgAbsorbs = bldg.getAbsorbtion(te.getPosition());
        }

        // damage to attacker
        Report r = new Report(4240);
        r.subject = ae.getId();
        r.add(damageTaken);
        r.indent();
        reportmanager.addReport(r);

        // Charging vehicles check for possible motive system hits.
        if (ae instanceof Tank) {
            r = new Report(4241);
            r.indent();
            reportmanager.addReport(r);
            int side = Compute.targetSideTable(te, ae);
            int mod = ae.getMotiveSideMod(side);
            reportmanager.addReport(vehicleMotiveDamage((Tank)ae, mod));
        }

        while (damageTaken > 0) {
            int cluster;
            HitData hit;
            // An airmech ramming attack does all damage to attacker's CT
            if (airmechRam) {
                cluster = damageTaken;
                hit = new HitData(Mech.LOC_CT);
            } else {
                cluster = Math.min(5, damageTaken);
                hit = ae.rollHitLocation(toHit.getHitTable(), ae.sideTable(te.getPosition()));
            }
            damageTaken -= cluster;
            hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
            cluster = checkForSpikes(ae, hit.getLocation(), cluster, te, Mech.LOC_CT);
            reportmanager.addReport(damageEntity(ae, hit, cluster, false, Server.DamageType.NONE,
                    false, false, throughFront));
        }

        // Damage to target
        if (ae instanceof Mech) {
            int spikeDamage = 0;
            for (int loc = 0; loc < ae.locations(); loc++) {
                if (((Mech) ae).locationIsTorso(loc) && ae.hasWorkingMisc(MiscType.F_SPIKES, -1, loc)) {
                    spikeDamage += 2;
                }
            }
            if (spikeDamage > 0) {
                r = new Report(4335);
                r.indent(2);
                r.subject = ae.getId();
                r.add(spikeDamage);
                reportmanager.addReport(r);
            }
            damage += spikeDamage;
        }
        r = new Report(4230);
        r.subject = ae.getId();
        r.add(damage);
        r.add(toHit.getTableDesc());
        r.indent();
        reportmanager.addReport(r);

        // Vehicles that have *been* charged check for motive system damage, too...
        // ...though VTOLs don't use that table and should lose their rotor instead,
        // which would be handled as part of the damage already.
        if ((te instanceof Tank) && !(te instanceof VTOL)) {
            r = new Report(4242);
            r.indent();
            reportmanager.addReport(r);

            int side = Compute.targetSideTable(ae, te);
            int mod = te.getMotiveSideMod(side);
            reportmanager.addReport(vehicleMotiveDamage((Tank)te, mod));
        }

        // track any additional damage to the attacker due to the target having spikes
        while (damage > 0) {
            int cluster = Math.min(5, damage);
            // Airmech ramming attacks do all damage to a single location
            if (airmechRam) {
                cluster = damage;
            }
            damage -= cluster;
            if (bldgAbsorbs > 0) {
                int toBldg = Math.min(bldgAbsorbs, cluster);
                cluster -= toBldg;
                reportmanager.addNewLines();
                Vector<Report> buildingReport = damageBuilding(bldg, damage, te.getPosition());
                for (Report report : buildingReport) {
                    report.subject = ae.getId();
                }
                reportmanager.addReport(buildingReport);

                // some buildings scale remaining damage that is not absorbed
                // TODO : this isn't quite right for castles brian
                damage = (int) Math.floor(bldg.getDamageToScale() * damage);
            }

            // A building may absorb the entire shot.
            if (cluster == 0) {
                r = new Report(4235);
                r.subject = ae.getId();
                r.addDesc(te);
                r.indent();
                reportmanager.addReport(r);
            } else {
                HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
                hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                cluster = checkForSpikes(te, hit.getLocation(), cluster, ae, Mech.LOC_CT);
                reportmanager.addReport(damageEntity(te, hit, cluster, false,
                        Server.DamageType.NONE, false, false, throughFront));
            }
        }

        if (airmechRam) {
            if (!ae.isDoomed()) {
                PilotingRollData controlRoll = ae.getBasePilotingRoll();
                Vector<Report> reports = new Vector<>();
                r = new Report(9320);
                r.subject = ae.getId();
                r.addDesc(ae);
                r.add("successful ramming attack");
                reports.add(r);
                int diceRoll = Compute.d6(2);
                // different reports depending on out-of-control status
                r = new Report(9606);
                r.subject = ae.getId();
                r.add(controlRoll.getValueAsString());
                r.add(controlRoll.getDesc());
                r.add(diceRoll);
                r.newlines = 1;
                if (diceRoll < controlRoll.getValue()) {
                    r.choose(false);
                    reports.add(r);
                    crashAirMech(ae, controlRoll);
                } else {
                    r.choose(true);
                    reports.addElement(r);
                    if (ae instanceof LandAirMech) {
                        reports.addAll(landAirMech((LandAirMech)ae, ae.getPosition(), 1, ae.delta_distance));
                    }
                }
                reportmanager.addReport(reports);
            }
        } else {
            // move attacker and target, if possible
            Coords src = te.getPosition();
            Coords dest = src.translated(direction);

            if (Compute.isValidDisplacement(game, te.getId(), te.getPosition(), direction)) {
                reportmanager.addNewLines();
                reportmanager.addReport(doEntityDisplacement(te, src, dest,
                        new PilotingRollData(te.getId(), 2, "was charged")));
                reportmanager.addReport(doEntityDisplacement(ae, ae.getPosition(), src, chargePSR));
            }
            reportmanager.addNewLines();
        }

        // if the target is an industrial mech, it needs to check for crits
        // at the end of turn
        if ((te instanceof Mech) && ((Mech) te).isIndustrial()) {
            ((Mech) te).setCheckForCrit(true);
        }

    } // End private void resolveChargeDamage( Entity, Entity, ToHitData )

    /**
     * Handle a ramming attack's damage
     */
    public void resolveRamDamage(IAero aero, Entity te, ToHitData toHit,
                                  boolean glancing, boolean throughFront) {

        Entity ae = (Entity) aero;

        int damage = RamAttackAction.getDamageFor(aero, te);
        int damageTaken = RamAttackAction.getDamageTakenBy(aero, te);
        if (glancing) {
            damage = gamemanager.applyInfrantryDamageReduction(te, damage);
            damageTaken = (int) Math.floor(damageTaken / 2.0);
        }

        // are they capital scale?
        if (te.isCapitalScale()
                && !game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_AERO_SANITY)) {
            damage = (int) Math.floor(damage / 10.0);
        }
        if (ae.isCapitalScale()
                && !game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_AERO_SANITY)) {
            damageTaken = (int) Math.floor(damageTaken / 10.0);
        }

        Report r;

        if (glancing) {
            r = new Report(9015);
            r.subject = ae.getId();
            r.indent(1);
            reportmanager.addReport(r);
        }

        // damage to attacker
        r = new Report(4240);
        r.subject = ae.getId();
        r.add(damageTaken);
        r.indent();
        reportmanager.addReport(r);

        HitData hit = ae.rollHitLocation(ToHitData.HIT_NORMAL, ae.sideTable(te.getPosition(), true));
        // if the damage is greater than the initial armor then destroy the
        // entity
        if ((2 * ae.getOArmor(hit)) < damageTaken) {
            reportmanager.addReport(entityManager.destroyEntity(ae, "by massive ramming damage", false));
        } else {
            reportmanager.addReport(damageEntity(ae, hit, damageTaken, false,
                    Server.DamageType.NONE, false, false, throughFront));
        }

        r = new Report(4230);
        r.subject = ae.getId();
        r.add(damage);
        r.add(toHit.getTableDesc());
        r.indent();
        reportmanager.addReport(r);

        hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
        if ((2 * te.getOArmor(hit)) < damage) {
            reportmanager.addReport(entityManager.destroyEntity(te, "by massive ramming damage", false));
        } else {
            reportmanager.addReport(damageEntity(te, hit, damage, false, Server.DamageType.NONE,
                    false, false, throughFront));
        }
    }

    /**
     * Checks whether the location has spikes, and if so handles the damage to the
     * attack and returns the reduced damage. Locations without spikes return the
     * original damage amount.
     *
     * @param target            The target of a physical attack
     * @param targetLocation    The location that was hit
     * @param damage            The amount of damage dealt to the target
     * @param attacker          The attacker
     * @param attackerLocation  The location on the attacker that is damaged if the
     *                          target has spikes. Entity.LOC_NONE if the attacker
     *                          can't be damaged by spikes in this attack.
     * @return          The damage after applying any reduction due to spikes
     */
    public int checkForSpikes(Entity target, int targetLocation, int damage, Entity attacker, int attackerLocation) {
        return checkForSpikes(target, targetLocation, damage, attacker, attackerLocation, Entity.LOC_NONE);
    }

    /**
     * Checks whether the location has spikes, and if so handles the damage to the
     * attack and returns the reduced damage. Locations without spikes return the
     * original damage amount.
     *
     * @param target            The target of a physical attack
     * @param targetLocation    The location that was hit
     * @param damage            The amount of damage dealt to the target
     * @param attacker          The attacker
     * @param attackerLocation  The location on the attacker that is damaged if the
     *                          target has spikes. Entity.LOC_NONE if the attacker
     *                          can't be damaged by spikes in this attack.
     * @param attackerLocation2 If not Entity.LOC_NONE, the damage to the attacker
     *                          will be split between two locations.
     * @return          The damage after applying any reduction due to spikes
     */
    public int checkForSpikes(Entity target, int targetLocation, int damage,
                               Entity attacker, int attackerLocation, int attackerLocation2) {
        if (target.hasWorkingMisc(MiscType.F_SPIKES, -1, targetLocation)) {
            Report r;
            if (damage == 0) {
                // Only show damage to attacker (push attack)
                r = new Report(4333);
            } else if (attackerLocation != Entity.LOC_NONE) {
                // Show damage reduction and damage to attacker
                r = new Report(4330);
            } else {
                // Only show damage reduction (club/physical weapon attack)
                r = new Report(4331);
            }
            r.indent(2);
            r.subject = target.getId();
            reportmanager.addReport(r);
            // An attack that deals zero damage can still damage the attacker in the case of a push
            if (attackerLocation != Entity.LOC_NONE) {
                // Spikes also protect from retaliatory spike damage
                if (attacker.hasWorkingMisc(MiscType.F_SPIKES, -1, attackerLocation)) {
                    r = new Report(4332);
                    r.indent(2);
                    r.subject = attacker.getId();
                    reportmanager.addReport(r);
                } else if (attackerLocation2 == Entity.LOC_NONE) {
                    reportmanager.addReport(damageEntity(attacker, new HitData(attackerLocation), 2, false,
                            DamageType.NONE,false, false, false));
                } else {
                    reportmanager.addReport(damageEntity(attacker, new HitData(attackerLocation), 1, false,
                            DamageType.NONE, false, false, false));
                    reportmanager.addReport(damageEntity(attacker, new HitData(attackerLocation2), 1, false,
                            DamageType.NONE, false, false, false));
                }
            }
            return Math.max(1, damage - 4);
        }
        return damage;
    }

    /**
     * End-phase checks for laid explosives; check whether explosives are
     * touched off, or if we should report laying explosives
     */
    private void checkLayExplosives() {
        // Report continuing explosive work
        for (Entity e : game.getEntitiesVector()) {
            if (!(e instanceof Infantry)) {
                continue;
            }
            Infantry inf = (Infantry) e;
            if (inf.turnsLayingExplosives > 0) {
                Report r = new Report(4271);
                r.subject = inf.getId();
                r.addDesc(inf);
                reportmanager.addReport(r);
            }
        }
        // Check for touched-off explosives
        Vector<Building> updatedBuildings = new Vector<>();
        for (DemolitionCharge charge : game.getExplodingCharges()) {
            Building bldg = game.getBoard().getBuildingAt(charge.pos);
            if (bldg == null) { // Shouldn't happen...
                continue;
            }
            bldg.removeDemolitionCharge(charge);
            updatedBuildings.add(bldg);
            Report r = new Report(4272, Report.PUBLIC);
            r.add(bldg.getName());
            reportmanager.addReport(r);
            Vector<Report> dmgReports = damageBuilding(bldg, charge.damage, " explodes for ", charge.pos);
            for (Report rep : dmgReports) {
                rep.indent();
                reportmanager.addReport(rep);
            }
        }
        game.setExplodingCharges(new ArrayList<>());
        sendChangedBuildings(updatedBuildings);
    }

    /**
     * Each mech sinks the amount of heat appropriate to its current heat capacity.
     */
    private void resolveHeat() {
        Report r;
        // Heat phase header
        reportmanager.addReport(new Report(5000, Report.PUBLIC));
        for (Entity entity : game.getEntitiesVector()) {
            if ((null == entity.getPosition()) && !entity.isAero()) {
                continue;
            }
            IHex entityHex = game.getBoard().getHex(entity.getPosition());

            int hotDogMod = entity.hasAbility(OptionsConstants.PILOT_HOT_DOG) ? 1 : 0;

            if (entity.getTaserInterferenceHeat()) {
                entity.heatBuildup += 5;
            }
            if (entity.hasDamagedRHS() && entity.weaponFired()) {
                entity.heatBuildup += 1;
            }
            if ((entity instanceof Mech) && ((Mech)entity).hasDamagedCoolantSystem() && entity.weaponFired()) {
                entity.heatBuildup += 1;
            }

            int radicalHSBonus = 0;
            Vector<Report> rhsReports = new Vector<>();
            if (entity.hasActivatedRadicalHS()) {
                if (entity instanceof Mech) {
                    radicalHSBonus = ((Mech) entity).getActiveSinks();
                } else if (entity instanceof Aero) {
                    radicalHSBonus = ((Aero) entity).getHeatSinks();
                } else {
                    MegaMek.getLogger().error("Radical heat sinks mounted on non-mech, non-aero Entity!");
                }
                int rhsRoll = Compute.d6(2);
                int targetNumber;
                switch (entity.getConsecutiveRHSUses()) {
                    case 0:
                        targetNumber = 2;
                        break;
                    case 1:
                        targetNumber = 3;
                        break;
                    case 2:
                        targetNumber = 5;
                        break;
                    case 3:
                        targetNumber = 7;
                        break;
                    case 4:
                        targetNumber = 10;
                        break;
                    case 5:
                        targetNumber = 11;
                        break;
                    case 6:
                    default:
                        targetNumber = TargetRoll.AUTOMATIC_FAIL;
                        break;
                }
                entity.setConsecutiveRHSUses(entity.getConsecutiveRHSUses() + 1);

                // RHS activation report
                r = new Report(5540);
                r.subject = entity.getId();
                r.indent();
                r.addDesc(entity);
                r.add(radicalHSBonus);
                rhsReports.add(r);

                boolean rhsFailure = rhsRoll < targetNumber;
                r = new Report(5541);
                r.indent(2);
                r.subject = entity.getId();
                r.add(targetNumber);
                r.add(rhsRoll);
                r.choose(rhsFailure);
                rhsReports.add(r);

                if (rhsFailure) {
                    entity.setHasDamagedRHS(true);
                    int loc = Entity.LOC_NONE;
                    for (Mounted m : entity.getEquipment()) {
                        if (m.getType().hasFlag(MiscType.F_RADICAL_HEATSINK)) {
                            loc = m.getLocation();
                            m.setDestroyed(true);
                            break;
                        }
                    }
                    if (loc == Entity.LOC_NONE) {
                        throw new IllegalStateException("Server.resolveHeat(): Could not find Radical Heat Sink mount "
                                + "on unit that used RHS!");
                    }
                    for (int s = 0; s < entity.getNumberOfCriticals(loc); s++) {
                        CriticalSlot slot = entity.getCritical(loc, s);
                        if ((slot.getType() == CriticalSlot.TYPE_EQUIPMENT)
                                && slot.getMount().getType().hasFlag(MiscType.F_RADICAL_HEATSINK)) {
                            slot.setHit(true);
                            break;
                        }
                    }
                }
            }

            // put in ASF heat build-up first because there are few differences
            if (entity instanceof Aero && !(entity instanceof ConvFighter)) {
                ServerHelper.resolveAeroHeat(game, entity, reportmanager.getvPhaseReport(), rhsReports, radicalHSBonus, hotDogMod);
                continue;
            }

            // heat doesn't matter for non-mechs
            if (!(entity instanceof Mech)) {
                entity.heat = 0;
                entity.heatBuildup = 0;
                entity.heatFromExternal = 0;
                entity.coolFromExternal = 0;

                if (entity.infernos.isStillBurning()) {
                    doFlamingDamage(entity);
                }
                if (entity.getTaserShutdownRounds() == 0) {
                    entity.setBATaserShutdown(false);
                    if (entity.isShutDown() && !entity.isManualShutdown()
                            && (entity.getTsempEffect() != TSEMPWeapon.TSEMP_EFFECT_SHUTDOWN)) {
                        entity.setShutDown(false);
                        r = new Report(5045);
                        r.subject = entity.getId();
                        r.addDesc(entity);
                        reportmanager.addReport(r);
                    }
                } else if (entity.isBATaserShutdown()) {
                    // if we're shutdown by a BA taser, we might activate again
                    int roll = Compute.d6(2);
                    if (roll >= 8) {
                        entity.setTaserShutdownRounds(0);
                        if (!(entity.isManualShutdown())) {
                            entity.setShutDown(false);
                        }
                        entity.setBATaserShutdown(false);
                    }
                }
                continue;
            }

            // Only Mechs after this point

            // Meks gain heat from inferno hits.
            if (entity.infernos.isStillBurning()) {
                int infernoHeat = entity.infernos.getHeat();
                entity.heatFromExternal += infernoHeat;
                r = new Report(5010);
                r.subject = entity.getId();
                r.add(infernoHeat);
                reportmanager.addReport(r);
            }

            // should we even bother for this mech?
            if (entity.isDestroyed() || entity.isDoomed() || entity.getCrew().isDoomed() || entity.getCrew().isDead()) {
                continue;
            }

            // engine hits add a lot of heat, provided the engine is on
            entity.heatBuildup += entity.getEngineCritHeat();

            // If a Mek had an active Stealth suite, add 10 heat.
            if (entity.isStealthOn()) {
                entity.heatBuildup += 10;
                r = new Report(5015);
                r.subject = entity.getId();
                reportmanager.addReport(r);
            }

            // Greg: Nova CEWS If a Mek had an active Nova suite, add 2 heat.
            if (entity.hasActiveNovaCEWS()) {
                entity.heatBuildup += 2;
                r = new Report(5013);
                r.subject = entity.getId();
                reportmanager.addReport(r);
            }

            // void sig adds 10 heat
            if (entity.isVoidSigOn()) {
                entity.heatBuildup += 10;
                r = new Report(5016);
                r.subject = entity.getId();
                reportmanager.addReport(r);
            }

            // null sig adds 10 heat
            if (entity.isNullSigOn()) {
                entity.heatBuildup += 10;
                r = new Report(5017);
                r.subject = entity.getId();
                reportmanager.addReport(r);
            }

            // chameleon polarization field adds 6
            if (entity.isChameleonShieldOn()) {
                entity.heatBuildup += 6;
                r = new Report(5014);
                r.subject = entity.getId();
                reportmanager.addReport(r);
            }

            // If a Mek is in extreme Temperatures, add or subtract one
            // heat per 10 degrees (or fraction of 10 degrees) above or
            // below 50 or -30 degrees Celsius
            if (game.getPlanetaryConditions().getTemperatureDifference(50, -30) != 0
                    && !((Mech) entity).hasLaserHeatSinks()) {
                if (game.getPlanetaryConditions().getTemperature() > 50) {
                    int heatToAdd = game.getPlanetaryConditions().getTemperatureDifference(50, -30);
                    if (((Mech) entity).hasIntactHeatDissipatingArmor()) {
                        heatToAdd /= 2;
                    }
                    entity.heatFromExternal += heatToAdd;
                    r = new Report(5020);
                    r.subject = entity.getId();
                    r.add(heatToAdd);
                    reportmanager.addReport(r);
                    if (((Mech) entity).hasIntactHeatDissipatingArmor()) {
                        r = new Report(5550);
                        reportmanager.addReport(r);
                    }
                } else {
                    entity.heatFromExternal -= game.getPlanetaryConditions().getTemperatureDifference(50, -30);
                    r = new Report(5025);
                    r.subject = entity.getId();
                    r.add(game.getPlanetaryConditions().getTemperatureDifference(50, -30));
                    reportmanager.addReport(r);
                }
            }

            // Add +5 Heat if the hex you're in is on fire
            // and was on fire for the full round.
            if (entityHex != null) {
                if (entityHex.containsTerrain(Terrains.FIRE) && (entityHex.getFireTurn() > 0)
                        && (entity.getElevation() <= 1)) {
                    int heatToAdd = 5;
                    if (((Mech) entity).hasIntactHeatDissipatingArmor()) {
                        heatToAdd /= 2;
                    }
                    entity.heatFromExternal += heatToAdd;
                    r = new Report(5030);
                    r.add(heatToAdd);
                    r.subject = entity.getId();
                    reportmanager.addReport(r);
                    if (((Mech) entity).hasIntactHeatDissipatingArmor()) {
                        r = new Report(5550);
                        reportmanager.addReport(r);
                    }
                }
                int magma = entityHex.terrainLevel(Terrains.MAGMA);
                if ((magma > 0) && (entity.getElevation() == 0)) {
                    int heatToAdd = 5 * magma;
                    if (((Mech) entity).hasIntactHeatDissipatingArmor()) {
                        heatToAdd /= 2;
                    }
                    entity.heatFromExternal += heatToAdd;
                    r = new Report(5032);
                    r.subject = entity.getId();
                    r.add(heatToAdd);
                    reportmanager.addReport(r);
                    if (((Mech) entity).hasIntactHeatDissipatingArmor()) {
                        r = new Report(5550);
                        reportmanager.addReport(r);
                    }
                }
            }

            // Check the mech for vibroblades if so then check to see if any
            // are active and what heat they will produce.
            if (entity.hasVibroblades()) {
                int vibroHeat = entity.getActiveVibrobladeHeat(Mech.LOC_RARM);
                vibroHeat += entity.getActiveVibrobladeHeat(Mech.LOC_LARM);

                if (vibroHeat > 0) {
                    r = new Report(5018);
                    r.subject = entity.getId();
                    r.add(vibroHeat);
                    reportmanager.addReport(r);
                    entity.heatBuildup += vibroHeat;
                }
            }

            int capHeat = 0;
            for (Mounted m : entity.getEquipment()) {
                if (!m.isUsedThisRound()) {
                    capHeat += m.hasChargedOrChargingCapacitor() * 5;
                }
            }
            if (capHeat > 0) {
                r = new Report(5019);
                r.subject = entity.getId();
                r.add(capHeat);
                reportmanager.addReport(r);
                entity.heatBuildup += capHeat;
            }

            // Add heat from external sources to the heat buildup
            int max_ext_heat = game.getOptions().intOption(OptionsConstants.ADVCOMBAT_MAX_EXTERNAL_HEAT);
            // Check Game Options
            if (max_ext_heat < 0) {
                max_ext_heat = 15; // standard value specified in TW p.159
            }
            entity.heatBuildup += Math.min(max_ext_heat, entity.heatFromExternal);
            entity.heatFromExternal = 0;
            // remove heat we cooled down
            entity.heatBuildup -= Math.min(9, entity.coolFromExternal);
            entity.coolFromExternal = 0;

            // Combat computers help manage heat
            if (entity.hasQuirk(OptionsConstants.QUIRK_POS_COMBAT_COMPUTER)) {
                int reduce = Math.min(entity.heatBuildup, 4);
                r = new Report(5026);
                r.subject = entity.getId();
                r.add(reduce);
                reportmanager.addReport(r);
                entity.heatBuildup -= reduce;
            }

            if (entity.hasQuirk(OptionsConstants.QUIRK_NEG_FLAWED_COOLING)
                    && ((Mech) entity).isCoolingFlawActive()) {
                int flaw = 5;
                r = new Report(5021);
                r.subject = entity.getId();
                r.add(flaw);
                reportmanager.addReport(r);
                entity.heatBuildup += flaw;
            }
            // if heat build up is negative due to temperature, set it to 0
            // for prettier turn reports
            entity.heatBuildup = Math.max(entity.heatBuildup, 0);

            // add the heat we've built up so far.
            entity.heat += entity.heatBuildup;

            // how much heat can we sink?
            int toSink = entity.getHeatCapacityWithWater() + radicalHSBonus;

            if (entity.getCoolantFailureAmount() > 0) {
                int failureAmount = entity.getCoolantFailureAmount();
                r = new Report(5520);
                r.subject = entity.getId();
                r.add(failureAmount);
                toSink -= failureAmount;
            }

            // should we use a coolant pod?
            int safeHeat = entity.hasInfernoAmmo() ? 9 : 13;
            int possibleSinkage = ((Mech) entity).getNumberOfSinks() - entity.getCoolantFailureAmount();
            for (Mounted m : entity.getEquipment()) {
                if (m.getType() instanceof AmmoType) {
                    AmmoType at = (AmmoType) m.getType();
                    if ((at.getAmmoType() == AmmoType.T_COOLANT_POD) && m.isAmmoUsable()) {
                        EquipmentMode mode = m.curMode();
                        if (mode.equals("dump")) {
                            r = new Report(5260);
                            r.subject = entity.getId();
                            reportmanager.addReport(r);
                            m.setShotsLeft(0);
                            toSink += possibleSinkage;
                            break;
                        }
                        if (mode.equals("safe") && ((entity.heat - toSink) > safeHeat)) {
                            r = new Report(5265);
                            r.subject = entity.getId();
                            reportmanager.addReport(r);
                            m.setShotsLeft(0);
                            toSink += possibleSinkage;
                            break;
                        }
                        if (mode.equals("efficient") && ((entity.heat - toSink) >= possibleSinkage)) {
                            r = new Report(5270);
                            r.subject = entity.getId();
                            reportmanager.addReport(r);
                            m.setShotsLeft(0);
                            toSink += possibleSinkage;
                            break;
                        }
                    }
                }
            }

            toSink = Math.min(toSink, entity.heat);
            entity.heat -= toSink;
            r = new Report(5035);
            r.subject = entity.getId();
            r.addDesc(entity);
            r.add(entity.heatBuildup);
            r.add(toSink);
            r.add(entity.heat);
            reportmanager.addReport(r);
            entity.heatBuildup = 0;
            reportmanager.addReport(rhsReports);

            // Does the unit have inferno ammo?
            if (entity.hasInfernoAmmo()) {
                // Roll for possible inferno ammo explosion.
                if (entity.heat >= 10) {
                    int boom = (4 + (entity.heat >= 14 ? 2 : 0) + (entity.heat >= 19 ? 2 : 0)
                                + (entity.heat >= 23 ? 2 : 0) + (entity.heat >= 28 ? 2 : 0))
                               - hotDogMod;
                    int boomRoll = Compute.d6(2);
                    if (entity.getCrew().hasActiveTechOfficer()) {
                        boomRoll += 2;
                    }
                    r = new Report(5040);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    r.add(boom);
                    if (entity.getCrew().hasActiveTechOfficer()) {
                        r.add(boomRoll + "(" + (boomRoll - 2) + "+2)");
                    } else {
                        r.add(boomRoll);
                    }

                    if (boomRoll >= boom) {
                        // avoided
                        r.choose(true);
                        reportmanager.addReport(r);
                    } else {
                        r.choose(false);
                        reportmanager.addReport(r);
                        reportmanager.addReport(explodeInfernoAmmoFromHeat(entity));
                    }
                }
            } // End avoid-inferno-explosion
            int autoShutDownHeat;
            boolean mtHeat;

            if (game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_HEAT)) {
                autoShutDownHeat = 50;
                mtHeat = true;
            } else {
                autoShutDownHeat = 30;
                mtHeat = false;
            }
            // heat effects: start up
            if ((entity.heat < autoShutDownHeat) && entity.isShutDown() && !entity.isStalled()) {
                if ((entity.getTaserShutdownRounds() == 0)
                       && (entity.getTsempEffect() != TSEMPWeapon.TSEMP_EFFECT_SHUTDOWN)) {
                    if ((entity.heat < 14) && !(entity.isManualShutdown())) {
                        // automatically starts up again
                        entity.setShutDown(false);
                        r = new Report(5045);
                        r.subject = entity.getId();
                        r.addDesc(entity);
                        reportmanager.addReport(r);
                    } else if (!(entity.isManualShutdown())) {
                        // If the pilot is KO and we need to roll, auto-fail.
                        if (!entity.getCrew().isActive()) {
                            r = new Report(5049);
                            r.subject = entity.getId();
                            r.addDesc(entity);
                        } else {
                            // roll for startup
                            int startup = (4 + (((entity.heat - 14) / 4) * 2)) - hotDogMod;
                            if (mtHeat) {
                                startup -= 5;
                                switch (entity.getCrew().getPiloting()) {
                                    case 0:
                                    case 1:
                                        startup -= 2;
                                        break;
                                    case 2:
                                    case 3:
                                        startup -= 1;
                                        break;
                                    case 6:
                                    case 7:
                                        startup += 1;
                                }
                            }
                            int suRoll = Compute.d6(2);
                            r = new Report(5050);
                            r.subject = entity.getId();
                            r.addDesc(entity);
                            r.add(startup);
                            r.add(suRoll);
                            if (suRoll >= startup) {
                                // start 'er back up
                                entity.setShutDown(false);
                                r.choose(true);
                            } else {
                                r.choose(false);
                            }
                        }
                        reportmanager.addReport(r);
                    }
                } else {
                    // if we're shutdown by a BA taser, we might activate
                    // again
                    if (entity.isBATaserShutdown()) {
                        int roll = Compute.d6(2);
                        if (roll >= 7) {
                            entity.setTaserShutdownRounds(0);
                            if (!(entity.isManualShutdown())) {
                                entity.setShutDown(false);
                            }
                            entity.setBATaserShutdown(false);
                        }
                    }
                }
            }

            // heat effects: shutdown!
            // Don't shut down if you just restarted.
            else if ((entity.heat >= 14) && !entity.isShutDown()) {
                if (entity.heat >= autoShutDownHeat) {
                    r = new Report(5055);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    reportmanager.addReport(r);
                    // add a piloting roll and resolve immediately
                    if (entity.canFall()) {
                        game.addPSR(new PilotingRollData(entity.getId(), 3, "reactor shutdown"));
                        reportmanager.addReport(resolvePilotingRolls());
                    }
                    // okay, now mark shut down
                    entity.setShutDown(true);
                } else {
                    // Again, pilot KO means shutdown is automatic.
                    if (!entity.getCrew().isActive()) {
                        r = new Report(5056);
                        r.subject = entity.getId();
                        r.addDesc(entity);
                        reportmanager.addReport(r);
                        entity.setShutDown(true);
                    } else {
                        int shutdown = (4 + (((entity.heat - 14) / 4) * 2)) - hotDogMod;
                        if (mtHeat) {
                            shutdown -= 5;
                            switch (entity.getCrew().getPiloting()) {
                                case 0:
                                case 1:
                                    shutdown -= 2;
                                    break;
                                case 2:
                                case 3:
                                    shutdown -= 1;
                                    break;
                                case 6:
                                case 7:
                                    shutdown += 1;
                            }
                        }
                        int shutdownRoll = Compute.d6(2);
                        r = new Report(5060);
                        r.subject = entity.getId();
                        r.addDesc(entity);
                        r.add(shutdown);
                        if (entity.getCrew().hasActiveTechOfficer()) {
                            r.add((shutdownRoll + 2) + " (" + shutdownRoll + "+2)");
                            shutdownRoll += 2;
                        } else {
                            r.add(shutdownRoll);
                        }
                        if (shutdownRoll >= shutdown) {
                            // avoided
                            r.choose(true);
                            reportmanager.addReport(r);
                        } else {
                            // shutting down...
                            r.choose(false);
                            reportmanager.addReport(r);
                            // add a piloting roll and resolve immediately
                            if (entity.canFall()) {
                                game.addPSR(new PilotingRollData(entity.getId(), 3, "reactor shutdown"));
                                reportmanager.addReport(resolvePilotingRolls());
                            }
                            // okay, now mark shut down
                            entity.setShutDown(true);
                        }
                    }
                }
            }

            // LAMs in fighter mode need to check for random movement due to heat
            checkRandomAeroMovement(entity, hotDogMod);

            // heat effects: ammo explosion!
            if (entity.heat >= 19) {
                int boom = (4 + (entity.heat >= 23 ? 2 : 0) + (entity.heat >= 28 ? 2 : 0))
                           - hotDogMod;
                if (mtHeat) {
                    boom += (entity.heat >= 35 ? 2 : 0) + (entity.heat >= 40 ? 2 : 0) + (entity.heat >= 45 ? 2 : 0);
                    // Last line is a crutch; 45 heat should be no roll
                    // but automatic explosion.
                }
                if (((Mech) entity).hasLaserHeatSinks()) {
                    boom--;
                }
                int boomRoll = Compute.d6(2);
                r = new Report(5065);
                r.subject = entity.getId();
                r.addDesc(entity);
                r.add(boom);
                if (entity.getCrew().hasActiveTechOfficer()) {
                    r.add((boomRoll + 2) + " (" + boomRoll + "+2)");
                    boomRoll += 2;
                } else {
                    r.add(boomRoll);
                }
                if (boomRoll >= boom) {
                    // mech is ok
                    r.choose(true);
                    reportmanager.addReport(r);
                } else {
                    // boom!
                    r.choose(false);
                    reportmanager.addReport(r);
                    reportmanager.addReport(explodeAmmoFromHeat(entity));
                }
            }

            // heat effects: mechwarrior damage
            // N.B. The pilot may already be dead.
            int lifeSupportCritCount;
            boolean torsoMountedCockpit = ((Mech) entity).getCockpitType() == Mech.COCKPIT_TORSO_MOUNTED;
            if (torsoMountedCockpit) {
                lifeSupportCritCount = entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM,
                        Mech.SYSTEM_LIFE_SUPPORT, Mech.LOC_RT);
                lifeSupportCritCount += entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM,
                        Mech.SYSTEM_LIFE_SUPPORT, Mech.LOC_LT);
            } else {
                lifeSupportCritCount = entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM,
                        Mech.SYSTEM_LIFE_SUPPORT, Mech.LOC_HEAD);
            }
            int damageHeat = entity.heat;
            if (entity.hasQuirk(OptionsConstants.QUIRK_POS_IMP_LIFE_SUPPORT)) {
                damageHeat -= 5;
            }
            if (entity.hasQuirk(OptionsConstants.QUIRK_NEG_POOR_LIFE_SUPPORT)) {
                damageHeat += 5;
            }
            if ((lifeSupportCritCount > 0)
                    && ((damageHeat >= 15) || (torsoMountedCockpit && (damageHeat > 0)))
                    && !entity.getCrew().isDead() && !entity.getCrew().isDoomed()
                    && !entity.getCrew().isEjected()) {
                int heatLimitDesc = 1;
                int damageToCrew = 0;
                if ((damageHeat >= 47) && mtHeat) {
                    // mechwarrior takes 5 damage
                    heatLimitDesc = 47;
                    damageToCrew = 5;
                } else if ((damageHeat >= 39) && mtHeat) {
                    // mechwarrior takes 4 damage
                    heatLimitDesc = 39;
                    damageToCrew = 4;
                } else if ((damageHeat >= 32) && mtHeat) {
                    // mechwarrior takes 3 damage
                    heatLimitDesc = 32;
                    damageToCrew = 3;
                } else if (damageHeat >= 25) {
                    // mechwarrior takes 2 damage
                    heatLimitDesc = 25;
                    damageToCrew = 2;
                } else if (damageHeat >= 15) {
                    // mechwarrior takes 1 damage
                    heatLimitDesc = 15;
                    damageToCrew = 1;
                }
                if ((((Mech) entity).getCockpitType() == Mech.COCKPIT_TORSO_MOUNTED)
                        && !entity.hasAbility(OptionsConstants.MD_PAIN_SHUNT)) {
                    damageToCrew += 1;
                }
                r = new Report(5070);
                r.subject = entity.getId();
                r.addDesc(entity);
                r.add(heatLimitDesc);
                r.add(damageToCrew);
                reportmanager.addReport(r);
                reportmanager.addReport(damageCrew(entity, damageToCrew));
            } else if (mtHeat && (entity.heat >= 32) && !entity.getCrew().isDead()
                    && !entity.getCrew().isDoomed()
                    && !entity.hasAbility(OptionsConstants.MD_PAIN_SHUNT)) {
                // Crew may take damage from heat if MaxTech option is set
                int heatRoll = Compute.d6(2);
                int avoidNumber;
                if (entity.heat >= 47) {
                    avoidNumber = 12;
                } else if (entity.heat >= 39) {
                    avoidNumber = 10;
                } else {
                    avoidNumber = 8;
                }
                avoidNumber -= hotDogMod;
                r = new Report(5075);
                r.subject = entity.getId();
                r.addDesc(entity);
                r.add(avoidNumber);
                r.add(heatRoll);
                if (heatRoll >= avoidNumber) {
                    // damage avoided
                    r.choose(true);
                    reportmanager.addReport(r);
                } else {
                    r.choose(false);
                    reportmanager.addReport(r);
                    reportmanager.addReport(damageCrew(entity, 1));
                }
            }

            // The pilot may have just expired.
            if ((entity.getCrew().isDead() || entity.getCrew().isDoomed()) && !entity.getCrew().isEjected()) {
                r = new Report(5080);
                r.subject = entity.getId();
                r.addDesc(entity);
                reportmanager.addReport(r);
                reportmanager.addReport(entityManager.destroyEntity(entity, "crew death", true));
            }

            // With MaxTech Heat Scale, there may occur critical damage
            if (mtHeat) {
                if (entity.heat >= 36) {
                    int damageRoll = Compute.d6(2);
                    int damageNumber;
                    if (entity.heat >= 44) {
                        damageNumber = 10;
                    } else {
                        damageNumber = 8;
                    }
                    damageNumber -= hotDogMod;
                    r = new Report(5085);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    r.add(damageNumber);
                    r.add(damageRoll);
                    r.newlines = 0;
                    if (damageRoll >= damageNumber) {
                        r.choose(true);
                    } else {
                        r.choose(false);
                        reportmanager.addReport(r);
                        reportmanager.addReport(oneCriticalEntity(entity, Compute.randomInt(8), false, 0));
                        // add an empty report, for line breaking
                        r = new Report(1210, Report.PUBLIC);
                    }
                    reportmanager.addReport(r);
                }
            }

            if (game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_COOLANT_FAILURE)
                    && (entity.getHeatCapacity() > entity.getCoolantFailureAmount())
                    && (entity.heat >= 5)) {
                int roll = Compute.d6(2);
                int hitNumber = 10 - Math.max(0, (int) Math.ceil(entity.heat / 5.0) - 2);

                r = new Report(5525);
                r.subject = entity.getId();
                r.add(entity.getShortName());
                r.add(hitNumber);
                r.add(roll);
                r.newlines = 0;
                reportmanager.addReport(r);
                if (roll >= hitNumber) {
                    r = new Report(5052);
                    r.subject = entity.getId();
                    reportmanager.addReport(r);
                    r = new Report(5526);
                    r.subject = entity.getId();
                    r.add(entity.getShortNameRaw());
                    reportmanager.addReport(r);
                    entity.addCoolantFailureAmount(1);
                } else {
                    r = new Report(5041);
                    r.subject = entity.getId();
                    reportmanager.addReport(r);
                }
            }
        }

        if (reportmanager.getvPhaseReport().size() == 1) {
            // I guess nothing happened...
            reportmanager.addReport(new Report(1205, Report.PUBLIC));
        }
    }

    void checkRandomAeroMovement(Entity entity, int hotDogMod) {
        if (!entity.isAero()) {
            return;
        }
        IAero a = (IAero) entity;
        // heat effects: control effects (must make it unless already random moving)
        if ((entity.heat >= 5) && !a.isRandomMove()) {
            int controlAvoid = (5 + (entity.heat >= 10 ? 1 : 0) + (entity.heat >= 15 ? 1 : 0)
                    + (entity.heat >= 20 ? 1 : 0) + (entity.heat >= 25 ? 2 : 0)) - hotDogMod;
            int controlRoll = Compute.d6(2);
            Report r = new Report(9210);
            r.subject = entity.getId();
            r.addDesc(entity);
            r.add(controlAvoid);
            r.add(controlRoll);
            if (controlRoll >= controlAvoid) {
                // in control
                r.choose(true);
                reportmanager.addReport(r);
            } else {
                // out of control
                r.choose(false);
                reportmanager.addReport(r);
                // if not already out of control, this may lead to
                // elevation decline
                if (!a.isOutControl() && !a.isSpaceborne()
                    && a.isAirborne()) {
                    int loss = Compute.d6(1);
                    r = new Report(9366);
                    r.newlines = 0;
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    r.add(loss);
                    reportmanager.addReport(r);
                    entity.setAltitude(entity.getAltitude() - loss);
                    // check for crash
                    if (game.checkCrash(entity, entity.getPosition(), entity.getAltitude())) {
                        reportmanager.addReport(processCrash(entity, a.getCurrentVelocity(), entity.getPosition()));
                    }
                }
                // force unit out of control through heat
                a.setOutCtrlHeat(true);
                a.setRandomMove(true);
            }
        }
    }

    private void resolveEmergencyCoolantSystem() {
        for (Entity e : game.getEntitiesVector()) {
            if ((e instanceof Mech) && e.hasWorkingMisc(MiscType.F_EMERGENCY_COOLANT_SYSTEM) && (e.heat > 13)) {
                Mech mech = (Mech)e;
                Vector<Report> vDesc = new Vector<>();
                HashMap<Integer, List<CriticalSlot>> crits = new HashMap<>();
                if (!(mech.doRISCEmergencyCoolantCheckFor(vDesc, crits))) {
                    mech.heat -= 6 + mech.getCoolantSystemMOS();
                    Report r = new Report(5027);
                    r.add(6+mech.getCoolantSystemMOS());
                    vDesc.add(r);
                }
                reportmanager.addReport(vDesc);
                for (Integer loc : crits.keySet()) {
                    List<CriticalSlot> lcs = crits.get(loc);
                    for (CriticalSlot cs : lcs) {
                        reportmanager.addReport(applyCriticalHit(mech, loc, cs, true, 0, false));
                    }
                }
            }
        }
    }

    /**
     * Resolve Flaming Damage for the given Entity Taharqa: This is now updated
     * to TacOps rules which is much more lenient So I have change the name to
     * Flaming Damage rather than flaming death
     *
     * @param entity The <code>Entity</code> that may experience flaming damage.
     */
    public void doFlamingDamage(Entity entity) {
        Report r;
        int boomRoll = Compute.d6(2);

        if ((entity.getMovementMode() == EntityMovementMode.VTOL) && !entity.infernos.isStillBurning()) {
            // VTOLs don't check as long as they are flying higher than
            // the burning terrain. TODO : Check for rules conformity (ATPM?)
            // according to maxtech, elevation 0 or 1 should be affected,
            // this makes sense for level 2 as well

            if (entity.getElevation() > 1) {
                return;
            }
        }
        // Battle Armor squads equipped with fire protection
        // gear automatically avoid flaming damage
        // TODO : can conventional infantry mount fire-resistant armor?
        if ((entity instanceof BattleArmor) && ((BattleArmor) entity).isFireResistant()) {
            r = new Report(5095);
            r.subject = entity.getId();
            r.indent(1);
            r.addDesc(entity);
            reportmanager.addReport(r);
            return;
        }

        // mechs shouldn't be here, but just in case
        if (entity instanceof Mech
                // fire has no effect on dropships
                || entity instanceof Dropship) {
            return;
        }

        // Must roll 8+ to survive...
        r = new Report(5100);
        r.subject = entity.getId();
        r.newlines = 0;
        r.addDesc(entity);
        r.add(boomRoll);
        if (boomRoll >= 8) {
            // phew!
            r.choose(true);
            reportmanager.addReport(r);
            Report.addNewline(reportmanager.getvPhaseReport());
        } else {
            // eek
            r.choose(false);
            r.newlines = 1;
            reportmanager.addReport(r);
            // gun emplacements have their own critical rules
            if (entity instanceof GunEmplacement) {
                Vector<GunEmplacement> gun = new Vector<>();
                gun.add((GunEmplacement) entity);
                
                Building building = getGame().getBoard().getBuildingAt(entity.getPosition());
                
                Report.addNewline(reportmanager.getvPhaseReport());
                reportmanager.addReport(criticalGunEmplacement(gun, building, entity.getPosition()));            
            // Taharqa: TacOps rules, protos and vees no longer die instantly
            // (hurray!)
            } else if (entity instanceof Tank) {
                int bonus = -2;
                if ((entity instanceof SupportTank) || (entity instanceof SupportVTOL)) {
                    bonus = 0;
                }
                // roll a critical hit
                Report.addNewline(reportmanager.getvPhaseReport());
                reportmanager.addReport(criticalTank((Tank) entity, Tank.LOC_FRONT, bonus, 0, true));
            } else if (entity instanceof Protomech) {
                // this code is taken from inferno hits
                HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                if (hit.getLocation() == Protomech.LOC_NMISS) {
                    Protomech proto = (Protomech) entity;
                    r = new Report(6035);
                    r.subject = entity.getId();
                    r.indent(2);
                    if (proto.isGlider()) {
                        r.messageId = 6036;
                        proto.setWingHits(proto.getWingHits() + 1);
                    }
                    reportmanager.addReport(r);
                } else {
                    r = new Report(6690);
                    r.subject = entity.getId();
                    r.indent(1);
                    r.add(entity.getLocationName(hit));
                    reportmanager.addReport(r);
                    entity.destroyLocation(hit.getLocation());
                    // Handle ProtoMech pilot damage due to location destruction
                    int hits = Protomech.POSSIBLE_PILOT_DAMAGE[hit.getLocation()]
                               - ((Protomech) entity).getPilotDamageTaken(hit.getLocation());
                    if (hits > 0) {
                        reportmanager.addReport(damageCrew(entity, hits));
                        ((Protomech) entity).setPilotDamageTaken(hit.getLocation(),
                                Protomech.POSSIBLE_PILOT_DAMAGE[hit.getLocation()]);
                    }
                    if (entity.getTransferLocation(hit).getLocation() == Entity.LOC_DESTROYED) {
                        reportmanager.addReport(entityManager.destroyEntity(entity, "flaming death", false, true));
                        Report.addNewline(reportmanager.getvPhaseReport());
                    }
                }
            } else {
                // sucks to be you
                reportmanager.addReport(entityManager.destroyEntity(entity, "fire", false, false));
                Report.addNewline(reportmanager.getvPhaseReport());
            }
        }
    }

    private void checkForFlawedCooling() {
        // If we're not using quirks, no need to do this check.
        if (!game.getOptions().booleanOption(OptionsConstants.ADVANCED_STRATOPS_QUIRKS)) {
            return;
        }
        for (Entity entity  : game.getEntitiesVector()) {
            // Only applies to Mechs.
            if (!(entity instanceof Mech)
                    // Check for existence of flawed cooling quirk.
                    || !entity.hasQuirk(OptionsConstants.QUIRK_NEG_FLAWED_COOLING)) {
                continue;
            }

            // Check for active Cooling Flaw
            if (((Mech) entity).isCoolingFlawActive()) {
                continue;
            }

            // Perform the check.
            if (entity.damageThisPhase >= 20) {
                reportmanager.addReport(gamemanager.doFlawedCoolingCheck("20+ damage", entity));
            }
            if (entity.hasFallen()) {
                reportmanager.addReport(gamemanager.doFlawedCoolingCheck("fall", entity));
            }
            if (entity.wasStruck()) {
                reportmanager.addReport(gamemanager.doFlawedCoolingCheck("being struck", entity));
            }
            game.clearFlawedCoolingFlags(entity);
        }
    }

    /**
     * For chain whip grapples, a roll needs to be made at the end of the
     * physical phase to maintain the grapple.
     */
    private void checkForChainWhipGrappleChecks() {
        for (Entity ae : game.getEntitiesVector()) {
            if ((ae.getGrappled() != Entity.NONE) && ae.isChainWhipGrappled()
                    && ae.isGrappleAttacker() && !ae.isGrappledThisRound()) {
                Entity te = game.getEntity(ae.getGrappled());
                ToHitData grappleHit = GrappleAttackAction.toHit(game,
                        ae.getId(), te, ae.getGrappleSide(), true);
                int roll = Compute.d6(2);

                Report r = new Report(4317);
                r.subject = ae.getId();
                r.indent();
                r.addDesc(ae);
                r.addDesc(te);
                r.newlines = 0;
                reportmanager.addReport(r);

                if (grappleHit.getValue() == TargetRoll.IMPOSSIBLE) {
                    r = new Report(4300);
                    r.subject = ae.getId();
                    r.add(grappleHit.getDesc());
                    reportmanager.addReport(r);
                    return;
                }

                // report the roll
                r = new Report(4025);
                r.subject = ae.getId();
                r.add(grappleHit.getValue());
                r.add(roll);
                r.newlines = 0;
                reportmanager.addReport(r);

                // do we hit?
                if (roll >= grappleHit.getValue()) {
                    // hit
                    r = new Report(4040);
                    r.subject = ae.getId();
                    reportmanager.addReport(r);
                    // Nothing else to do
                    return;
                }

                // miss
                r = new Report(4035);
                r.subject = ae.getId();
                reportmanager.addReport(r);

                // Need to break grapple
                ae.setGrappled(Entity.NONE, false);
                te.setGrappled(Entity.NONE, false);
            }
        }
    }

    /**
     * Checks to see if any entity takes enough damage that requires them to
     * make a piloting roll
     */
    private void checkForPSRFromDamage() {
        for (Entity entity : game.getEntitiesVector()) {
            if (entity.canFall()) {
                if (entity.isAirborne()) {
                    // you can't fall over when you are combat dropping because
                    // you are already falling!
                    continue;
                }
                // if this mech has 20+ damage, add another roll to the list.
                // Hulldown 'mechs ignore this rule, TO Errata
                int psrThreshold = 20;
                if (((Mech) entity).getCockpitType() == Mech.COCKPIT_DUAL && entity.getCrew().hasDedicatedPilot()) {
                    psrThreshold = 30;
                }
                if ((entity.damageThisPhase >= psrThreshold) && !entity.isHullDown()) {
                    PilotingRollData damPRD = new PilotingRollData(entity.getId());
                    if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_TACOPS_TAKING_DAMAGE)) {
                        int damMod = entity.damageThisPhase / psrThreshold;
                        damPRD.addModifier(damMod, (damMod * psrThreshold) + "+ damage");
                        int weightMod = 0;
                        if (game.getOptions().booleanOption(
                                OptionsConstants.ADVGRNDMOV_TACOPS_PHYSICAL_PSR)) {
                            switch (entity.getWeightClass()) {
                                case EntityWeightClass.WEIGHT_LIGHT:
                                    weightMod = 1;
                                    break;
                                case EntityWeightClass.WEIGHT_MEDIUM:
                                    weightMod = 0;
                                    break;
                                case EntityWeightClass.WEIGHT_HEAVY:
                                    weightMod = -1;
                                    break;
                                case EntityWeightClass.WEIGHT_ASSAULT:
                                    weightMod = -2;
                                    break;
                            }
                            if ((entity instanceof Mech) && entity.isSuperHeavy()) {
                                weightMod = -4;
                            }
                            // the weight class PSR modifier is not cumulative
                            damPRD.addModifier(weightMod, "weight class modifier", false);
                        }
                    } else {
                        damPRD = new PilotingRollData(entity.getId(), 1, psrThreshold + "+ damage");
                    }
                    if (entity.hasQuirk(OptionsConstants.QUIRK_POS_EASY_PILOT) && (entity.getCrew().getPiloting() > 3)) {
                        damPRD.addModifier(-1, "easy to pilot");
                    }
                    game.addPSR(damPRD);
                }
            }
            if (entity.isAero() && entity.isAirborne() && !game.getBoard().inSpace()) {
                // if this aero has any damage, add another roll to the list.
                if (entity.damageThisPhase > 0) {
                    if (!game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_ATMOSPHERIC_CONTROL)) {
                        int damMod = entity.damageThisPhase / 20;
                        PilotingRollData damPRD = new PilotingRollData(entity.getId(), damMod, entity.damageThisPhase + " damage +" + damMod);
                        if (entity.hasQuirk(OptionsConstants.QUIRK_POS_EASY_PILOT)
                                && (entity.getCrew().getPiloting() > 3)) {
                            damPRD.addModifier(-1, "easy to pilot");
                        }
                        game.addControlRoll(damPRD);
                    } else {
                        // was the damage threshold exceeded this round?
                        if (((IAero) entity).wasCritThresh()) {
                            PilotingRollData damThresh = new PilotingRollData(entity.getId(), 0,
                                    "damage threshold exceeded");
                            if (entity.hasQuirk(OptionsConstants.QUIRK_POS_EASY_PILOT)
                                    && (entity.getCrew().getPiloting() > 3)) {
                                damThresh.addModifier(-1, "easy to pilot");
                            }
                            game.addControlRoll(damThresh);
                        }
                    }
                }
            }
            // Airborne AirMechs that take 20+ damage make a control roll instead of a PSR.
            if (entity instanceof LandAirMech && entity.isAirborneVTOLorWIGE() && entity.damageThisPhase >= 20) {
                PilotingRollData damPRD = new PilotingRollData(entity.getId());
                int damMod = entity.damageThisPhase / 20;
                damPRD.addModifier(damMod, (damMod * 20) + "+ damage");
                game.addControlRoll(damPRD);
            }
        }
    }

    /**
     * Checks to see if any non-mech units are standing in fire. Called at the
     * end of the movement phase
     */
    public void checkForFlamingDamage() {
        for (Entity entity : game.getEntitiesVector()) {
            if ((null == entity.getPosition()) || (entity instanceof Mech)
                    || entity.isDoomed() || entity.isDestroyed() || entity.isOffBoard()) {
                continue;
            }
            final IHex curHex = game.getBoard().getHex(entity.getPosition());
            final boolean underwater = curHex.containsTerrain(Terrains.WATER)
                    && (curHex.depth() > 0)
                    && (entity.getElevation() < curHex.surface());
            final int numFloors = curHex.terrainLevel(Terrains.BLDG_ELEV);
            if (curHex.containsTerrain(Terrains.FIRE) && !underwater && ((entity.getElevation() <= 1)
                    || (entity.getElevation() <= numFloors))) {
                doFlamingDamage(entity);
            }
        }
    }

    /**
     * Checks to see if any telemissiles are in a hex with enemy units. If so,
     * then attack one.
     */
    private void checkForTeleMissileAttacks() {
        for (Entity entity : game.getEntitiesVector()) {
            if (entity instanceof TeleMissile) {
                // check for enemy units
                Vector<Integer> potTargets = new Vector<>();
                for (Entity te : game.getEntitiesVector(entity.getPosition())) {
                    //Telemissiles cannot target fighters or other telemissiles
                    //Fighters don't have a distinctive Etype flag, so we have to do
                    //this by exclusion.
                    if (!(te.hasETypeFlag(Entity.ETYPE_DROPSHIP)
                            || te.hasETypeFlag(Entity.ETYPE_SMALL_CRAFT)
                            || te.hasETypeFlag(Entity.ETYPE_JUMPSHIP)
                            || te.hasETypeFlag(Entity.ETYPE_WARSHIP)
                            || te.hasETypeFlag(Entity.ETYPE_SPACE_STATION))) {
                        continue;
                    }
                    if (te.isEnemyOf(entity)) {
                        // then add it to a vector of potential targets
                        potTargets.add(te.getId());
                    }
                }
                if (potTargets.size() > 0) {
                    // determine randomly
                    Entity target = game.getEntity(potTargets.get(Compute.randomInt(potTargets.size())));
                    // report this and add a new TeleMissileAttackAction
                    Report r = new Report(9085);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    r.addDesc(target);
                    reportmanager.addReport(r);
                    game.addTeleMissileAttack(new TeleMissileAttackAction(entity, target));
                }
            }
        }
    }

    private void checkForBlueShieldDamage() {
        Report r;
        for (Entity entity : game.getEntitiesVector()) {
            if (!(entity instanceof Aero) && entity.hasActiveBlueShield()
                && (entity.getBlueShieldRounds() >= 6)) {
                int roll = Compute.d6(2);
                int target = (3 + entity.getBlueShieldRounds()) - 6;
                r = new Report(1240);
                r.addDesc(entity);
                r.add(target);
                r.add(roll);
                if (roll < target) {
                    for (Mounted m : entity.getMisc()) {
                        if (m.getType().hasFlag(MiscType.F_BLUE_SHIELD)) {
                            m.setBreached(true);
                        }
                    }
                    r.choose(true);
                } else {
                    r.choose(false);
                }
                reportmanager.addReport(r);
            }
        }
    }

    /**
     * Check to see if anyone dies due to being in certain planetary conditions.
     */
    private void checkForConditionDeath() {
        Report r;
        for (Entity entity : game.getEntitiesVector()) {
            if ((null == entity.getPosition()) && !entity.isOffBoard() || (entity.getTransportId() != Entity.NONE)) {
                // Ignore transported units, and units that don't have a position for some unknown reason
                continue;
            }
            String reason = game.getPlanetaryConditions().whyDoomed(entity, game);
            if (null != reason) {
                r = new Report(6015);
                r.subject = entity.getId();
                r.addDesc(entity);
                r.add(reason);
                reportmanager.addReport(r);
                reportmanager.addReport(entityManager.destroyEntity(entity, reason, true, true));
            }
        }
    }

    /**
     * Check to see if anyone dies due to being in atmosphere.
     */
    private void checkForAtmosphereDeath() {
        Report r;
        for (Entity entity : game.getEntitiesVector()) {
            if ((null == entity.getPosition()) || entity.isOffBoard()) {
                // If it's not on the board - aboard something else, for
                // example...
                continue;
            }
            if (entity.doomedInAtmosphere() && (entity.getAltitude() == 0)) {
                r = new Report(6016);
                r.subject = entity.getId();
                r.addDesc(entity);
                reportmanager.addReport(r);
                reportmanager.addReport(entityManager.destroyEntity(entity,
                        "being in atmosphere where it can't survive", true, true));
            }
        }
    }

    /**
     * checks if IndustrialMechs should die because they moved into to-deep
     * water last round
     */
    private void checkForIndustrialWaterDeath() {
        for (Entity entity : game.getEntitiesVector()) {
            if ((null == entity.getPosition()) || entity.isOffBoard()) {
                // If it's not on the board - aboard something else, for
                // example...
                continue;
            }
            if ((entity instanceof Mech) && ((Mech) entity).isIndustrial()
                    && ((Mech) entity).shouldDieAtEndOfTurnBecauseOfWater()) {
                reportmanager.addReport(entityManager.destroyEntity(entity, "being in water without environmental shielding", true, true));
            }
        }
    }

    private void checkForIndustrialEndOfTurn() {
        checkForIndustrialWaterDeath();
        checkForIndustrialUnstall();
        checkForIndustrialCrit(); // This might hit an actuator or gyro, so...
        reportmanager.addReport(resolvePilotingRolls());
    }

    private void checkForIndustrialUnstall() {
        for (Entity entity : game.getEntitiesVector()) {
            entity.checkUnstall(reportmanager.getvPhaseReport());
        }
    }

    /**
     * industrial mechs might need to check for critical damage
     */
    private void checkForIndustrialCrit() {
        for (Entity entity : game.getEntitiesVector()) {
            if ((entity instanceof Mech) && ((Mech) entity).isIndustrial()) {
                Mech mech = (Mech) entity;
                // should we check for critical damage?
                if (mech.isCheckForCrit()) {
                    Report r = new Report(5530);
                    r.addDesc(mech);
                    r.subject = mech.getId();
                    r.newlines = 0;
                    reportmanager.addReport(r);
                    // for being hit by a physical weapon
                    if (mech.getLevelsFallen() == 0) {
                        r = new Report(5531);
                        r.subject = mech.getId();
                        // or for falling
                    } else {
                        r = new Report(5532);
                        r.subject = mech.getId();
                        r.add(mech.getLevelsFallen());
                    }
                    reportmanager.addReport(r);
                    HitData newHit = mech.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                    reportmanager.addReport(criticalEntity(mech, newHit.getLocation(), newHit.isRear(),
                            mech.getLevelsFallen(), 0));
                }
            }
        }
    }

    /**
     * Check to see if anyone dies due to being in space.
     */
    private void checkForSpaceDeath() {
        Report r;
        for (Entity entity : game.getEntitiesVector()) {
            if ((null == entity.getPosition()) || entity.isOffBoard()) {
                // If it's not on the board - aboard something else, for
                // example...
                continue;
            }
            if (entity.doomedInSpace()) {
                r = new Report(6017);
                r.subject = entity.getId();
                r.addDesc(entity);
                reportmanager.addReport(r);
                reportmanager.addReport(entityManager.destroyEntity(entity,
                        "being in space where it can't survive", true, true));
            }
        }
    }

    /**
     * Checks to see if any entities are underwater (or in vacuum) with damaged
     * life support. Called during the end phase.
     */
    private void checkForSuffocation() {
        for (Entity entity : game.getEntitiesVector()) {
            if ((null == entity.getPosition()) || entity.isOffBoard()) {
                continue;
            }
            final IHex curHex = game.getBoard().getHex(entity.getPosition());
            if ((((entity.getElevation() < 0) && ((curHex.terrainLevel(Terrains.WATER) > 1)
                    || ((curHex.terrainLevel(Terrains.WATER) == 1) && entity.isProne())))
                    || game.getPlanetaryConditions().isVacuum())
                    && (entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_LIFE_SUPPORT, Mech.LOC_HEAD) > 0)) {
                Report r = new Report(6020);
                r.subject = entity.getId();
                r.addDesc(entity);
                reportmanager.addReport(r);
                reportmanager.addReport(damageCrew(entity, 1));
            }
        }
    }

    /**
     * Resolves all built up piloting skill rolls. Used at end of weapons,
     * physical phases.
     */
    public Vector<Report> resolvePilotingRolls() {
        Vector<Report> vPhaseReport = new Vector<>();
        for (Entity entity : game.getEntitiesVector()) {
            vPhaseReport.addAll(resolvePilotingRolls(entity));
        }
        game.resetPSRs();
        return vPhaseReport;
    }

    /**
     * Resolves and reports all piloting skill rolls for a single mech.
     */
    public Vector<Report> resolvePilotingRolls(Entity entity) {
        return resolvePilotingRolls(entity, false, entity.getPosition(), entity.getPosition());
    }

    public Vector<Report> resolvePilotingRolls(Entity entity, boolean moving, Coords src, Coords dest) {
        Vector<Report> vPhaseReport = new Vector<>();
        // dead and undeployed and offboard units don't need to.
        if (entity.isDoomed() || entity.isDestroyed() || entity.isOffBoard() || !entity.isDeployed()
                || (entity.getTransportId() != Entity.NONE)) {
            return vPhaseReport;
        }

        // airborne units don't make piloting rolls, they make control rolls
        if (entity.isAirborne()) {
            return vPhaseReport;
        }

        Report r;

        // first, do extreme gravity PSR, because non-mechs do these, too
        PilotingRollData rollTarget = null;
        for (Enumeration<PilotingRollData> i = game.getExtremeGravityPSRs(); i.hasMoreElements(); ) {
            final PilotingRollData roll = i.nextElement();
            if (roll.getEntityId() != entity.getId()) {
                continue;
            }
            // found a roll, use it (there can be only 1 per entity)
            rollTarget = roll;
            game.resetExtremeGravityPSRs(entity);
        }
        if ((rollTarget != null) && (rollTarget.getValue() != TargetRoll.CHECK_FALSE)) {
            // okay, print the info
            r = new Report(2180);
            r.subject = entity.getId();
            r.addDesc(entity);
            r.add(rollTarget.getLastPlainDesc());
            vPhaseReport.add(r);
            // roll
            final int diceRoll = Compute.d6(2);
            r = new Report(2190);
            r.subject = entity.getId();
            r.add(rollTarget.getValueAsString());
            r.add(rollTarget.getDesc());
            r.add(diceRoll);
            if ((diceRoll < rollTarget.getValue())
                    || (game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_FUMBLES)
                    && (diceRoll == 2))) {
                r.choose(false);
                // Report the fumble
                if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_FUMBLES)
                    && (diceRoll == 2)) {
                    r.messageId = 2306;
                }
                vPhaseReport.add(r);
                // walking and running, 1 damage per MP used more than we would
                // have normally
                if ((entity.moved == EntityMovementType.MOVE_WALK)
                        || (entity.moved == EntityMovementType.MOVE_VTOL_WALK)
                        || (entity.moved == EntityMovementType.MOVE_RUN)
                        || (entity.moved == EntityMovementType.MOVE_SPRINT)
                        || (entity.moved == EntityMovementType.MOVE_VTOL_RUN)
                        || (entity.moved == EntityMovementType.MOVE_VTOL_SPRINT)) {
                    if (entity instanceof Mech) {
                        int damage = Math.max(0, entity.mpUsed - entity.getRunningGravityLimit());
                        // Wee, direct internal damage
                        vPhaseReport.addAll(doExtremeGravityDamage(entity, damage));
                    } else if (entity instanceof Tank) {
                        // if we got a pavement bonus, take care of it
                        int k = entity.gotPavementBonus ? 1 : 0;
                        if (!entity.gotPavementBonus) {
                            int targetValue = entity.getRunMP(false, false, false) + k;
                            int damage = Math.max(0, entity.mpUsed - targetValue);
                            vPhaseReport.addAll(doExtremeGravityDamage(entity, damage));
                        }
                    }
                }
                // jumping
                if ((entity.moved == EntityMovementType.MOVE_JUMP)
                    && (entity instanceof Mech)) {
                    // low g, 1 damage for each hex jumped further than
                    // possible normally
                    if (game.getPlanetaryConditions().getGravity() < 1) {
                        // TODO (Sam): Test nog schrijven hiervoor want entity doet vaag
                        int damage = Math.max(0, entity.mpUsed - entity.getJumpMP(false));
                        // Wee, direct internal damage
                        vPhaseReport.addAll(doExtremeGravityDamage(entity, damage));
                    }
                    // high g, 1 damage for each MP we have less than normally
                    else if (game.getPlanetaryConditions().getGravity() > 1) {
                        int damage = entity.getWalkMP(false, false) - entity.getWalkMP();
                        // Wee, direct internal damage
                        vPhaseReport.addAll(doExtremeGravityDamage(entity, damage));
                    }
                }
                // failed a PSR, check for ICE engine stalling
                entity.doCheckEngineStallRoll(vPhaseReport);
            } else {
                r.choose(true);
                vPhaseReport.add(r);
            }
        }

        // Glider ProtoMechs without sufficient movement to stay airborne make forced landings.
        if ((entity instanceof Protomech) && ((Protomech)entity).isGlider()
                && entity.isAirborneVTOLorWIGE() && (entity.getRunMP() < 4)) {
            vPhaseReport.addAll(landGliderPM((Protomech) entity, entity.getPosition(), entity.getElevation(),
                    entity.delta_distance));
        }

        // non mechs and prone mechs can now return
        if (!entity.canFall() || (entity.isHullDown() && entity.canGoHullDown())) {
            return vPhaseReport;
        }

        // Mechs with UMU float and don't have to roll???
        if (entity instanceof Mech) {
            IHex hex = game.getBoard().getHex(dest);
            int water = hex.terrainLevel(Terrains.WATER);
            if ((water > 0) && (entity.getElevation() != -hex.depth(true))
                    && ((entity.getElevation() < 0) || ((entity.getElevation() == 0)
                    && (hex.terrainLevel(Terrains.BRIDGE_ELEV) != 0) && !hex.containsTerrain(Terrains.ICE)))
                    && !entity.isMakingDfa() && !entity.isDropping()) {
                // mech is floating in water....
                if (entity.hasUMU()) {
                    return vPhaseReport;
                }
            }
        }
        // add all cumulative mods from other rolls to each PSR
        // holds all rolls to make
        Vector<PilotingRollData> rolls = new Vector<>();
        // holds the initial reason for each roll
        StringBuilder reasons = new StringBuilder();
        PilotingRollData base = entity.getBasePilotingRoll();
        entity.addPilotingModifierForTerrain(base);
        for (Enumeration<PilotingRollData> i = game.getPSRs(); i.hasMoreElements(); ) {
            PilotingRollData psr = i.nextElement();
            if (psr.getEntityId() != entity.getId()) {
                continue;
            }
            // found a roll
            if (reasons.length() > 0) {
                reasons.append("; ");
            }
            reasons.append(psr.getPlainDesc());
            PilotingRollData toUse = entity.getBasePilotingRoll();
            entity.addPilotingModifierForTerrain(toUse);
            toUse.append(psr);
            // now, append all other roll's cumulative mods, not the
            // non-cumulative ones
            for (Enumeration<PilotingRollData> j = game.getPSRs(); j.hasMoreElements(); ) {
                final PilotingRollData other = j.nextElement();
                if ((other.getEntityId() != entity.getId()) || other.equals(psr)) {
                    continue;
                }
                toUse.append(other, false);
            }
            rolls.add(toUse);
        }
        // any rolls needed?
        if (rolls.size() == 0) {
            return vPhaseReport;
        }
        // is our base roll impossible?
        if ((base.getValue() == TargetRoll.AUTOMATIC_FAIL) || (base.getValue() == TargetRoll.IMPOSSIBLE)) {
            r = new Report(2275);
            r.subject = entity.getId();
            r.addDesc(entity);
            r.add(rolls.size());
            r.add(base.getDesc()); // international issue
            vPhaseReport.add(r);
            if (moving) {
                vPhaseReport.addAll(doEntityFallsInto(entity, entity.getElevation(), src, dest, base, true));
            } else if ((entity instanceof Mech) && game.getOptions().booleanOption(
                    OptionsConstants.ADVGRNDMOV_TACOPS_FALLING_EXPANDED)
                    && (entity.getCrew().getPiloting() < 6)
                    && !entity.isHullDown() && entity.canGoHullDown()) {
                if (entity.isHullDown() && entity.canGoHullDown()) {
                    r = new Report(2317);
                    r.subject = entity.getId();
                    r.add(entity.getDisplayName());
                    vPhaseReport.add(r);
                } else {
                    vPhaseReport.addAll(doEntityFall(entity, base));
                }
            } else {
                vPhaseReport.addAll(doEntityFall(entity, base));
            }
            // failed a PSR, check for ICE engine stalling
            entity.doCheckEngineStallRoll(vPhaseReport);
            return vPhaseReport;
        }
        // loop through rolls we do have to make...
        r = new Report(2280);
        r.subject = entity.getId();
        r.addDesc(entity);
        r.add(rolls.size());
        r.add(reasons.toString()); // international issue
        vPhaseReport.add(r);
        r = new Report(2285);
        r.subject = entity.getId();
        r.add(base.getValueAsString());
        r.add(base.getDesc()); // international issue
        vPhaseReport.add(r);
        for (int i = 0; i < rolls.size(); i++) {
            PilotingRollData roll = rolls.elementAt(i);
            r = new Report(2290);
            r.subject = entity.getId();
            r.indent();
            r.newlines = 0;
            r.add(i + 1);
            r.add(roll.getDesc()); // international issue
            vPhaseReport.add(r);
            if ((roll.getValue() == TargetRoll.AUTOMATIC_FAIL) || (roll.getValue() == TargetRoll.IMPOSSIBLE)) {
                r = new Report(2295);
                r.subject = entity.getId();
                vPhaseReport.add(r);
                if (moving) {
                    vPhaseReport.addAll(doEntityFallsInto(entity, entity.getElevation(), src, dest, roll, true));
                } else {
                    if ((entity instanceof Mech) && game.getOptions().booleanOption(
                                OptionsConstants.ADVGRNDMOV_TACOPS_FALLING_EXPANDED)
                            && (entity.getCrew().getPiloting() < 6)
                            && !entity.isHullDown() && entity.canGoHullDown()) {
                        if (entity.isHullDown() && entity.canGoHullDown()) {
                            r = new Report(2317);
                            r.subject = entity.getId();
                            r.add(entity.getDisplayName());
                            vPhaseReport.add(r);
                        } else {
                            vPhaseReport.addAll(doEntityFall(entity, roll));
                        }
                    } else {
                        vPhaseReport.addAll(doEntityFall(entity, roll));
                    }
                }
                // failed a PSR, check for ICE engine stalling
                entity.doCheckEngineStallRoll(vPhaseReport);
                return vPhaseReport;
            }
            int diceRoll = entity.getCrew().rollPilotingSkill();
            r = new Report(2300);
            r.add(roll.getValueAsString());
            r.add(diceRoll);
            r.subject = entity.getId();
            if ((diceRoll < roll.getValue())
                || (game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_FUMBLES) && (diceRoll == 2))) {
                r.choose(false);
                // Report the fumble
                if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_FUMBLES)
                    && (diceRoll == 2)) {
                    r.messageId = 2306;
                }
                vPhaseReport.add(r);
                if (moving) {
                    vPhaseReport.addAll(doEntityFallsInto(entity, entity.getElevation(), src, dest, roll, true));
                } else {
                    if ((entity instanceof Mech)
                        && game.getOptions().booleanOption(
                            OptionsConstants.ADVGRNDMOV_TACOPS_FALLING_EXPANDED)
                        && (entity.getCrew().getPiloting() < 6)
                        && !entity.isHullDown() && entity.canGoHullDown()) {
                        if (((entity.getCrew().getPiloting() > 1) && ((roll.getValue() - diceRoll) < 2))
                                || ((entity.getCrew().getPiloting() <= 1) && ((roll.getValue() - diceRoll) < 3))) {
                            entity.setHullDown(true);
                        }
                        if (entity.isHullDown() && entity.canGoHullDown()) {
                            ServerHelper.sinkToBottom(entity);
                            
                            r = new Report(2317);
                            r.subject = entity.getId();
                            r.add(entity.getDisplayName());
                            vPhaseReport.add(r);
                        } else {
                            vPhaseReport.addAll(doEntityFall(entity, roll));
                        }
                    } else {
                        vPhaseReport.addAll(doEntityFall(entity, roll));
                    }
                }
                // failed a PSR, check for ICE engine stalling
                entity.doCheckEngineStallRoll(vPhaseReport);
                return vPhaseReport;
            }
            r.choose(true);
            vPhaseReport.add(r);
        }
        return vPhaseReport;
    }

    private Vector<Report> checkForTraitors() {
        Vector<Report> vFullReport = new Vector<>();
        // check for traitors
        for (Iterator<Entity> i = game.getEntities(); i.hasNext(); ) {
            Entity entity = i.next();
            if (entity.isDoomed() || entity.isDestroyed() || entity.isOffBoard() || !entity.isDeployed()) {
                continue;
            }
            if ((entity.getTraitorId() != -1) && (entity.getOwnerId() != entity.getTraitorId())) {
                IPlayer p = game.getPlayer(entity.getTraitorId());
                if (null != p) {
                    Report r = new Report(7305);
                    r.subject = entity.getId();
                    r.add(entity.getDisplayName());
                    r.add(p.getName());
                    entity.setOwner(p);
                    entityManager.entityUpdate(entity.getId());
                    vFullReport.add(r);
                }
                entity.setTraitorId(-1);
            }
        }
        if (!vFullReport.isEmpty()) {
            vFullReport.add(0, new Report(7300));
        }
        return vFullReport;
    }

    /**
     * Resolves all built up control rolls. Used only during end phase
     */
    private Vector<Report> resolveControlRolls() {
        Vector<Report> vFullReport = new Vector<>();
        vFullReport.add(new Report(5001, Report.PUBLIC));
        for (Iterator<Entity> i = game.getEntities(); i.hasNext(); ) {
            vFullReport.addAll(resolveControl(i.next()));
        }
        game.resetControlRolls();
        return vFullReport;
    }

    /**
     * Resolves and reports all control skill rolls for a single aero or airborne LAM in airmech mode.
     */
    private Vector<Report> resolveControl(Entity e) {
        Vector<Report> vReport = new Vector<>();
        if (e.isDoomed() || e.isDestroyed() || e.isOffBoard() || !e.isDeployed()) {
            return vReport;
        }
        Report r;

        /*
         * See forum answers on OOC
         * http://forums.classicbattletech.com/index.php/topic,20424.0.html
         */

        IAero a = null;
        boolean canRecover = false;
        if (e.isAero() && (e.isAirborne() || e.isSpaceborne())) {
            a = (IAero) e;
            // they should get a shot at a recovery roll at the end of all this
            // if they are already out of control
            canRecover = a.isOutControl();
        } else if (!(e instanceof LandAirMech) || !e.isAirborneVTOLorWIGE()) {
            return vReport;
        }

        // if the unit already is moving randomly then it can't get any
        // worse
        if (a == null || !a.isRandomMove()) {

            // find control rolls and make them
            Vector<PilotingRollData> rolls = new Vector<>();
            StringBuilder reasons = new StringBuilder();
            PilotingRollData target = e.getBasePilotingRoll();
            // maneuvering ace
            // TODO : pending rules query
            // http://www.classicbattletech.com/forums/index.php/topic,63552.new.html#new
            // for now I am assuming Man Ace applies to all out-of-control
            // rolls, but not other
            // uses of control rolls (thus it doesn't go in
            // Entity#addEntityBonuses) and
            // furthermore it doesn't apply to recovery rolls
            if (e.isUsingManAce()) {
                target.addModifier(-1, "maneuvering ace");
            }
            for (Enumeration<PilotingRollData> j = game.getControlRolls(); j.hasMoreElements(); ) {
                final PilotingRollData modifier = j.nextElement();
                if (modifier.getEntityId() != e.getId()) {
                    continue;
                }
                // found a roll, add it
                rolls.addElement(modifier);
                if (reasons.length() > 0) {
                    reasons.append("; ");
                }
                reasons.append(modifier.getCumulativePlainDesc());
                target.append(modifier);
            }
            // any rolls needed?
            if (rolls.size() > 0) {
                // loop through rolls we do have to make...
                r = new Report(9310);
                r.subject = e.getId();
                r.addDesc(e);
                r.add(rolls.size());
                r.add(reasons.toString()); // international issue
                vReport.add(r);
                r = new Report(2285);
                r.subject = e.getId();
                r.add(target.getValueAsString());
                r.add(target.getDesc()); // international issue
                vReport.add(r);
                for (int j = 0; j < rolls.size(); j++) {
                    PilotingRollData modifier = rolls.elementAt(j);
                    r = new Report(2290);
                    r.subject = e.getId();
                    r.indent();
                    r.newlines = 0;
                    r.add(j + 1);
                    r.add(modifier.getPlainDesc()); // international issue
                    vReport.add(r);
                    int diceRoll = Compute.d6(2);
                    // different reports depending on out-of-control status
                    if (a != null && a.isOutControl()) {
                        r = new Report(9360);
                        r.subject = e.getId();
                        r.add(target.getValueAsString());
                        r.add(diceRoll);
                        if (diceRoll < (target.getValue() - 5)) {
                            r.choose(false);
                            vReport.add(r);
                            a.setRandomMove(true);
                        } else {
                            r.choose(true);
                            vReport.add(r);
                        }
                    } else {
                        r = new Report(9315);
                        r.subject = e.getId();
                        r.add(target.getValueAsString());
                        r.add(diceRoll);
                        r.newlines = 1;
                        if (diceRoll < target.getValue()) {
                            r.choose(false);
                            vReport.add(r);
                            if (a != null) {
                                a.setOutControl(true);
                                // do we have random movement?
                                if ((target.getValue() - diceRoll) > 5) {
                                    r = new Report(9365);
                                    r.newlines = 0;
                                    r.subject = e.getId();
                                    vReport.add(r);
                                    a.setRandomMove(true);
                                }
                                // if on the atmospheric map, then lose altitude
                                // and check
                                // for crash
                                if (!a.isSpaceborne() && a.isAirborne()) {
                                    int loss = Compute.d6(1);
                                    int origAltitude = e.getAltitude();
                                    e.setAltitude(e.getAltitude() - loss);
                                    //Reroll altitude loss with edge if the new altitude would result in a crash
                                    if (e.getAltitude() <= 0
                                            //Don't waste the edge if it won't help
                                            && origAltitude > 1
                                            && e.getCrew().hasEdgeRemaining()
                                            && e.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_AERO_ALT_LOSS)) {
                                        loss = Compute.d6(1);
                                        //Report the edge use
                                        r = new Report(9367);
                                        r.newlines = 1;
                                        r.subject = e.getId();
                                        vReport.add(r);
                                        e.setAltitude(origAltitude - loss);
                                        // and spend the edge point
                                        e.getCrew().decreaseEdge();
                                    }
                                    //Report the altitude loss
                                    r = new Report(9366);
                                    r.newlines = 0;
                                    r.subject = e.getId();
                                    r.addDesc(e);
                                    r.add(loss);
                                    vReport.add(r);
                                    // check for crash
                                    if (game.checkCrash(e, e.getPosition(), e.getAltitude())) {
                                        vReport.addAll(processCrash(e, a.getCurrentVelocity(), e.getPosition()));
                                        break;
                                    }
                                }
                            } else if (e instanceof LandAirMech && e.isAirborneVTOLorWIGE()) {
                                int loss = target.getValue() - diceRoll;
                                r = new Report(9366);
                                r.subject = e.getId();
                                r.addDesc(e);
                                r.add(loss);
                                vReport.add(r);
                                IHex hex = game.getBoard().getHex(e.getPosition());
                                int elevation = Math.max(0, hex.terrainLevel(Terrains.BLDG_ELEV));
                                if (e.getElevation() - loss <= elevation) {
                                    crashAirMech(e, target);
                                } else {
                                    e.setElevation(e.getElevation() - loss);
                                }
                            }
                        } else {
                            r.choose(true);
                            vReport.add(r);
                        }
                    }
                }
            }
        }

        // if they were out-of-control to start with, give them a chance to
        // regain control
        if (canRecover) {
            PilotingRollData base = e.getBasePilotingRoll();
            // is our base roll impossible?
            if ((base.getValue() == TargetRoll.AUTOMATIC_FAIL) || (base.getValue() == TargetRoll.IMPOSSIBLE)) {
                // report something
                r = new Report(9340);
                r.subject = e.getId();
                r.addDesc(e);
                r.add(base.getDesc()); // international issue
                vReport.add(r);
                return vReport;
            }
            r = new Report(9345);
            r.subject = e.getId();
            r.addDesc(e);
            r.add(base.getDesc()); // international issue
            vReport.add(r);
            int diceRoll = Compute.d6(2);
            r = new Report(9350);
            r.subject = e.getId();
            r.add(base.getValueAsString());
            r.add(diceRoll);
            if (diceRoll < base.getValue()) {
                r.choose(false);
                vReport.add(r);
            } else {
                r.choose(true);
                vReport.add(r);
                a.setOutControl(false);
                a.setOutCtrlHeat(false);
                a.setRandomMove(false);
            }
        }
        return vReport;
    }

    /**
     * Inflict damage on a pilot
     *
     * @param en     The <code>Entity</code> who's pilot gets damaged.
     * @param damage The <code>int</code> amount of damage.
     */
    public Vector<Report> damageCrew(Entity en, int damage) {
        return damageCrew(en, damage, -1);
    }

    /**
     * Inflict damage on a pilot
     *
     * @param en        The <code>Entity</code> who's pilot gets damaged.
     * @param damage    The <code>int</code> amount of damage.
     * @param crewPos   The <code>int</code>position of the crew member in a <code>MultiCrewCockpit</crew>
     *                  that takes the damage. A value < 0 applies the damage to all crew members.
     *                  The basic <crew>Crew</crew> ignores this value.
     */
    public Vector<Report> damageCrew(Entity en, int damage, int crewPos) {
        Vector<Report> vDesc = new Vector<>();
        Crew crew = en.getCrew();
        Report r;
        if (!crew.isDead() && !crew.isEjected() && !crew.isDoomed()) {
            for (int pos = 0; pos < en.getCrew().getSlotCount(); pos++) {
                if (crewPos >= 0 && (crewPos != pos || crew.isDead(crewPos))) {
                    continue;
                }
                boolean wasPilot = crew.getCurrentPilotIndex() == pos;
                boolean wasGunner = crew.getCurrentGunnerIndex() == pos;
                crew.setHits(crew.getHits(pos) + damage, pos);
                if (en.isLargeCraft()) {
                    r = new Report (6028);
                    r.subject = en.getId();
                    r.indent(2);
                    r.addDesc(en);
                    r.add(damage);
                    if (((Aero)en).isEjecting()) {
                        r.add("as crew depart the ship");
                    } else {
                        //Blank data
                        r.add("");
                    }
                    r.add(crew.getHits(pos));
                    vDesc.addElement(r);
                    if (Crew.DEATH > crew.getHits()) {
                        boolean option = game.getOptions().booleanOption(OptionsConstants.RPG_TOUGHNESS);
                        vDesc.addAll(reportmanager.resolveCrewDamage(en, damage, pos, option));
                    } else if (!crew.isDoomed()) {
                        crew.setDoomed(true);
                        //Safety. We might use this logic for large naval vessels later on
                        if (en instanceof Aero && ((Aero)en).isEjecting()) {
                            vDesc.addAll(entityManager.destroyEntity(en, "ejection", true));
                            ((Aero)en).setEjecting(false);
                        } else {
                            vDesc.addAll(entityManager.destroyEntity(en, "crew casualties", true));
                        }
                    }
                } else {
                    if (Crew.DEATH > crew.getHits(pos)) {
                        r = new Report(6025);
                    } else {
                        r = new Report(6026);
                    }
                    r.subject = en.getId();
                    r.indent(2);
                    r.add(crew.getCrewType().getRoleName(pos));
                    r.addDesc(en);
                    r.add(crew.getName(pos));
                    r.add(damage);
                    r.add(crew.getHits(pos));
                    vDesc.addElement(r);
                    if (crew.isDead(pos)) {
                        r = reportmanager.createCrewTakeoverReport(en, pos, wasPilot, wasGunner);
                        if (null != r) {
                            vDesc.addElement(r);
                        }
                    }
                    if (Crew.DEATH > crew.getHits()) {
                        boolean option = game.getOptions().booleanOption(OptionsConstants.RPG_TOUGHNESS);
                        vDesc.addAll(reportmanager.resolveCrewDamage(en, damage, pos, option));
                    } else if (!crew.isDoomed()) {
                        crew.setDoomed(true);
                        vDesc.addAll(entityManager.destroyEntity(en, "pilot death", true));
                    }
                }
            }
        } else {
            boolean isPilot = (en instanceof Mech) || ((en instanceof Aero) && !(en instanceof SmallCraft) && !(en instanceof Jumpship));
            if (crew.isDead() || crew.isDoomed()) {
                r = isPilot ? new Report(6021) : new Report(6022);
            } else {
                r = isPilot ? new Report(6023) : new Report(6024);
            }
            r.subject = en.getId();
            r.addDesc(en);
            r.add(crew.getName());
            r.indent(2);
            vDesc.add(r);
        }
        if (en.isAirborneVTOLorWIGE() && !en.getCrew().isActive()) {
            if (en instanceof LandAirMech) {
                crashAirMech(en, en.getBasePilotingRoll());
            } else if (en instanceof Protomech) {
                vDesc.addAll(landGliderPM((Protomech)en));
            }
        }
        return vDesc;
    }

    /*
     * Resolve any outstanding crashes from shutting down and being airborne
     * VTOL or WiGE...
     */
    private void resolveShutdownCrashes() {
        for (Entity e : game.getEntitiesVector()) {
            if (e.isShutDown() && e.isAirborneVTOLorWIGE() && !(e.isDestroyed() || e.isDoomed())) {
                Tank t = (Tank) e;
                t.immobilize();
                reportmanager.addReport(forceLandVTOLorWiGE(t));
            }
        }
    }

    /**
     * Resolve any potential fatal damage to Capital Fighter after each
     * individual attacker is finished
     */
    private Vector<Report> checkFatalThresholds(int nextAE, int prevAE) {
        Vector<Report> vDesc = new Vector<>();
        for (Iterator<Entity> e = game.getEntities(); e.hasNext();) {
            Entity en = e.next();
            if (!en.isCapitalFighter() || (nextAE == Entity.NONE)) {
                continue;
            }
            IAero ship = (IAero) en;
            int damage = ship.getCurrentDamage();
            double divisor = 2.0;
            if (game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_AERO_SANITY)) {
                divisor = 20.0;
            }
            if (damage >= ship.getFatalThresh()) {
                int roll = Compute.d6(2) + (int) Math.floor((damage - ship.getFatalThresh()) / divisor);
                if (roll > 9) {
                    // Lets auto-eject if we can!
                    if (((ship instanceof LandAirMech) && ((LandAirMech) ship).isAutoEject())
                            || ((ship instanceof Aero) && ((Aero) ship).isAutoEject())) {
                        // LAMs or Aeros eject if the CT destroyed switch is on
                        if (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                || ((game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                && ((ship instanceof LandAirMech) && ((LandAirMech) ship).isCondEjectCTDest()))
                                || ((ship instanceof Aero) && ((Aero) ship).isCondEjectSIDest()))) {
                            reportmanager.addReport(ejectEntity(en, true, false));
                        }
                    }
                    vDesc.addAll(entityManager.destroyEntity((Entity)ship, "fatal damage threshold"));
                    ship.doDisbandDamage();
                    if (prevAE != Entity.NONE) {
                        game.creditKill(en, game.getEntity(prevAE));
                    }
                }
            }
            ship.setCurrentDamage(0);
        }
        return vDesc;
    }

    /**
     * damage an Entity
     *
     * @param te            the <code>Entity</code> to be damaged
     * @param hit           the corresponding <code>HitData</code>
     * @param damage        the <code>int</code> amount of damage
     * @param ammoExplosion a <code>boolean</code> indicating if this is an ammo explosion
     * @return a <code>Vector<Report></code> containing the phase reports
     */
    private Vector<Report> damageEntity(Entity te, HitData hit, int damage, boolean ammoExplosion) {
        return damageEntity(te, hit, damage, ammoExplosion, DamageType.NONE, false, false);
    }

    /**
     * Deals the listed damage to an entity. Returns a vector of Reports for the
     * phase report
     *
     * @param te     the target entity
     * @param hit    the hit data for the location hit
     * @param damage the damage to apply
     * @return a <code>Vector</code> of <code>Report</code>s
     */
    public Vector<Report> damageEntity(Entity te, HitData hit, int damage) {
        return damageEntity(te, hit, damage, false, DamageType.NONE, false, false);
    }

    /**
     * Deals the listed damage to an entity. Returns a vector of Reports for the
     * phase report
     *
     * @param te            the target entity
     * @param hit           the hit data for the location hit
     * @param damage        the damage to apply
     * @param ammoExplosion ammo explosion type damage is applied directly to the IS,
     *                      hurts the pilot, causes auto-ejects, and can blow the unit to
     *                      smithereens
     * @param bFrag         The DamageType of the attack.
     * @param damageIS      Should the target location's internal structure be damaged
     *                      directly?
     * @return a <code>Vector</code> of <code>Report</code>s
     */
    public Vector<Report> damageEntity(Entity te, HitData hit, int damage, boolean ammoExplosion, DamageType bFrag,
                                       boolean damageIS) {
        return damageEntity(te, hit, damage, ammoExplosion, bFrag, damageIS, false);
    }

    /**
     * Deals the listed damage to an entity. Returns a vector of Reports for the
     * phase report
     *
     * @param te            the target entity
     * @param hit           the hit data for the location hit
     * @param damage        the damage to apply
     * @param ammoExplosion ammo explosion type damage is applied directly to the IS,
     *                      hurts the pilot, causes auto-ejects, and can blow the unit to
     *                      smithereens
     * @param bFrag         The DamageType of the attack.
     * @param damageIS      Should the target location's internal structure be damaged
     *                      directly?
     * @param areaSatArty   Is the damage from an area saturating artillery attack?
     * @return a <code>Vector</code> of <code>Report</code>s
     */
    public Vector<Report> damageEntity(Entity te, HitData hit, int damage, boolean ammoExplosion, DamageType bFrag,
                                       boolean damageIS, boolean areaSatArty) {
        return damageEntity(te, hit, damage, ammoExplosion, bFrag, damageIS, areaSatArty, true);
    }

    /**
     * Deals the listed damage to an entity. Returns a vector of Reports for the
     * phase report
     *
     * @param te            the target entity
     * @param hit           the hit data for the location hit
     * @param damage        the damage to apply
     * @param ammoExplosion ammo explosion type damage is applied directly to the IS,
     *                      hurts the pilot, causes auto-ejects, and can blow the unit to
     *                      smithereens
     * @param bFrag         The DamageType of the attack.
     * @param damageIS      Should the target location's internal structure be damaged
     *                      directly?
     * @param areaSatArty   Is the damage from an area saturating artillery attack?
     * @param throughFront  Is the damage coming through the hex the unit is facing?
     * @return a <code>Vector</code> of <code>Report</code>s
     */
    public Vector<Report> damageEntity(Entity te, HitData hit, int damage, boolean ammoExplosion, DamageType bFrag,
                                       boolean damageIS, boolean areaSatArty, boolean throughFront) {
        return damageEntity(te, hit, damage, ammoExplosion, bFrag, damageIS, areaSatArty, throughFront,
                false, false);
    }

    /**
     * Deals the listed damage to an entity. Returns a vector of Reports for the
     * phase report
     *
     * @param te            the target entity
     * @param hit           the hit data for the location hit
     * @param damage        the damage to apply
     * @param ammoExplosion ammo explosion type damage is applied directly to the IS,
     *                      hurts the pilot, causes auto-ejects, and can blow the unit to
     *                      smithereens
     * @param bFrag         The DamageType of the attack.
     * @param damageIS      Should the target location's internal structure be damaged
     *                      directly?
     * @param areaSatArty   Is the damage from an area saturating artillery attack?
     * @param throughFront  Is the damage coming through the hex the unit is facing?
     * @param underWater    Is the damage coming from an underwater attack
     * @return a <code>Vector</code> of <code>Report</code>s
     */
    public Vector<Report> damageEntity(Entity te, HitData hit, int damage, boolean ammoExplosion, DamageType bFrag,
                                       boolean damageIS, boolean areaSatArty, boolean throughFront, boolean underWater) {
        return damageEntity(te, hit, damage, ammoExplosion, bFrag, damageIS, areaSatArty, throughFront, underWater,
                false);
    }

    /**
     * Deals the listed damage to an entity. Returns a vector of Reports for the
     * phase report
     *
     * @param te            the target entity
     * @param hit           the hit data for the location hit
     * @param damage        the damage to apply
     * @param ammoExplosion ammo explosion type damage is applied directly to the IS,
     *                      hurts the pilot, causes auto-ejects, and can blow the unit to
     *                      smithereens
     * @param bFrag         The DamageType of the attack.
     * @param damageIS      Should the target location's internal structure be damaged
     *                      directly?
     * @param areaSatArty   Is the damage from an area saturating artillery attack?
     * @param throughFront  Is the damage coming through the hex the unit is facing?
     * @param underWater    Is the damage coming from an underwater attack?
     * @param nukeS2S       is this a ship-to-ship nuke?
     * @return a <code>Vector</code> of <code>Report</code>s
     */
    public Vector<Report> damageEntity(Entity te, HitData hit, int damage, boolean ammoExplosion, DamageType bFrag,
                                       boolean damageIS, boolean areaSatArty, boolean throughFront, boolean underWater,
                                       boolean nukeS2S) {

        Vector<Report> vDesc = new Vector<>();
        Report r;
        int te_n = te.getId();

        // if this is a fighter squadron then pick an active fighter and pass on the damage
        if (te instanceof FighterSquadron) {
            return damageFighterSquadron(te, hit, damage, ammoExplosion, bFrag, damageIS, areaSatArty, throughFront, underWater, nukeS2S);
        }

        // Battle Armor takes full damage to each trooper from area-effect.
        if (areaSatArty && (te instanceof BattleArmor)) {
            return damageBattleArmor(te, hit, damage, ammoExplosion, bFrag, damageIS, throughFront, underWater, nukeS2S);
        }

        // This is good for shields if a shield absorps the hit it shouldn't effect the pilot.
        // TC SRM's that hit the head do external and internal damage but its one hit and shouldn't cause
        // 2 hits to the pilot.
        boolean isHeadHit = (te instanceof Mech)
                            && (((Mech) te).getCockpitType() != Mech.COCKPIT_TORSO_MOUNTED)
                            && (hit.getLocation() == Mech.LOC_HEAD)
                            && ((hit.getEffect() & HitData.EFFECT_NO_CRITICALS) != HitData.EFFECT_NO_CRITICALS);

        // booleans to indicate criticals for AT2
        boolean critSI = false;
        boolean critThresh = false;

        // get the relevant damage for damage thresholding
        int threshDamage = damage;
        // weapon groups only get the damage of one weapon
        if ((hit.getSingleAV() > -1)
            && !game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_AERO_SANITY)) {
            threshDamage = hit.getSingleAV();
        }

        // is this capital-scale damage
        boolean isCapital = hit.isCapital();

        // check capital/standard damage
        if (isCapital && (!te.isCapitalScale() || game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_AERO_SANITY))) {
            damage = 10 * damage;
            threshDamage = 10 * threshDamage;
        }
        if (!isCapital && te.isCapitalScale() && !game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_AERO_SANITY)) {
            damage = (int) Math.round(damage / 10.0);
            threshDamage = (int) Math.round(threshDamage / 10.0);
        }

        int damage_orig = damage;

        // show Locations which have rerolled with Edge
        HitData undoneLocation = hit.getUndoneLocation();
        while (undoneLocation != null) {
            vDesc.addElement(ReportFactory.createReport(6500, 2, te, te.getLocationAbbr(undoneLocation)));
            undoneLocation = undoneLocation.getUndoneLocation();

            // if edge was uses, give at end overview of remaining
            vDesc.addElement(ReportFactory.createReport(6510, 2, te, te.getCrew().getOptions().intOption(OptionsConstants.EDGE)));
        } // while

        boolean autoEject = false;
        if (ammoExplosion) {
            if ((te instanceof Mech && ((Mech) te).isAutoEject() && (!((Mech) te).isCondEjectAmmo() || game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)))
                    || (te instanceof Aero && ((Aero) te).isAutoEject() && (!((Aero) te).isCondEjectAmmo() || game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)))) {
                autoEject = true;
                vDesc.addAll(ejectEntity(te, true));
            }
        }
        boolean isBattleArmor = te instanceof BattleArmor;
        boolean isPlatoon = !isBattleArmor && (te instanceof Infantry);
        boolean isFerroFibrousTarget = false;
        boolean wasDamageIS = false;
        boolean tookInternalDamage = damageIS;
        IHex te_hex = null;

        boolean hardenedArmor = ((te instanceof Mech) || (te instanceof Tank))
                && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_HARDENED);
        boolean ferroLamellorArmor = ((te instanceof Mech) || (te instanceof Tank) || (te instanceof Aero))
                && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_FERRO_LAMELLOR);
        boolean reflectiveArmor = (((te instanceof Mech) || (te instanceof Tank) || (te instanceof Aero))
                && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_REFLECTIVE))
                || (isBattleArmor && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_BA_REFLECTIVE));
        boolean reactiveArmor = (((te instanceof Mech) || (te instanceof Tank) || (te instanceof Aero))
                && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_REACTIVE))
                || (isBattleArmor && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_BA_REACTIVE));
        boolean ballisticArmor = ((te instanceof Mech) || (te instanceof Tank) || (te instanceof Aero))
                && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_BALLISTIC_REINFORCED);
        boolean impactArmor = (te instanceof Mech)
                && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_IMPACT_RESISTANT);
        boolean bar5 = te.getBARRating(hit.getLocation()) <= 5;

        // TACs from the hit location table
        int crits = (hit.getEffect() & HitData.EFFECT_CRITICAL) == HitData.EFFECT_CRITICAL ? 1 : 0;

        // this is for special crits, like AP and tandem-charge
        int specCrits = 0;

        // the bonus to the crit roll if using the
        // "advanced determining critical hits rule"
        int critBonus = 0;
        if (game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_CRIT_ROLL)
            && (damage_orig > 0)
            && ((te instanceof Mech) || (te instanceof Protomech))) {
            critBonus = Math.min((damage_orig - 1) / 5, 4);
        }

        // Find out if Human TRO plays a part it crit bonus
        Entity ae = game.getEntity(hit.getAttackerId());

        if (ae != null && !areaSatArty) {
            if ((te instanceof Mech || te instanceof Aero || te instanceof Tank || te instanceof BattleArmor)
                    && ae.hasAbility(OptionsConstants.MISC_HUMAN_TRO, Crew.HUMANTRO_MECH)) {
                critBonus += 1;
            }
        }

        HitData nextHit = null;

        // Some "hits" on a ProtoMech are actually misses.
        if ((te instanceof Protomech) && (hit.getLocation() == Protomech.LOC_NMISS)) {
            Protomech proto = (Protomech) te;
            r = ReportFactory.createReport(6035, 2, te);
            if (proto.isGlider()) {
                r.messageId = 6036;
                proto.setWingHits(proto.getWingHits() + 1);
            }
            vDesc.add(r);
            return vDesc;
        }

        // check for critical hit/miss vs. a BA
        if ((crits > 0) && (te instanceof BattleArmor)) {
            // possible critical miss if the rerolled location isn't alive
            if ((hit.getLocation() >= te.locations()) || (te.getInternal(hit.getLocation()) <= 0)) {
                vDesc.addElement(ReportFactory.createReport(6037, 2, te, hit.getLocation()));
                return vDesc;
            }
            // otherwise critical hit
            vDesc.addElement(ReportFactory.createReport(6225, 2, te, te.getLocationAbbr(hit)));

            crits = 0;
            damage = Math.max(te.getInternal(hit.getLocation()) + te.getArmor(hit.getLocation()), damage);
        }

        if ((te.getArmor(hit) > 0) && ((te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_FERRO_FIBROUS)
                || (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_LIGHT_FERRO)
                || (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_HEAVY_FERRO))) {
            isFerroFibrousTarget = true;
        }

        // area effect against infantry is double damage
        if ((isPlatoon && areaSatArty)
                // Is the infantry in the open?
                || ServerHelper.infantryInOpen(te, te_hex, game, isPlatoon, ammoExplosion, hit.isIgnoreInfantryDoubleDamage())
                // Is the infantry in vacuum?
                || ((isPlatoon || isBattleArmor) && !te.isDestroyed() && !te.isDoomed() && game.getPlanetaryConditions().isVacuum())) {
            // PBI. Double damage.
            damage *= 2;
            vDesc.addElement(ReportFactory.createReport(6039, 2, te));
        }

        // If dealing with fragmentation missiles, it does double damage to infantry...
        // We're actually going to abuse this for AX-head warheads, too, so as to not add another parameter.
        int reportID = 0;
        switch (bFrag) {
            case FRAGMENTATION:
                // Fragmentation missiles deal full damage to conventional infantry
                // (only) and no damage to other target types.
                reportID = 6045;
                if (!isPlatoon) {
                    damage = 0;
                    reportID = 6050; // For some reason this report never actually shows up...
                }
                vDesc.addElement(ReportFactory.createReport(reportID, 2, te));
                break;
            case NONPENETRATING:
                if (!isPlatoon) {
                    damage = 0;
                    vDesc.addElement(ReportFactory.createReport(6051, 2, te));
                }
                break;
            case FLECHETTE:
                // Flechette ammo deals full damage to conventional infantry and
                // half damage to other targets (including battle armor).
                reportID = 6055;
                if (!isPlatoon) {
                    damage /= 2;
                    reportID =6060;
                }
                vDesc.addElement(ReportFactory.createReport(reportID, 2, te));
                break;
            case ACID:
                if (isFerroFibrousTarget || reactiveArmor || reflectiveArmor || ferroLamellorArmor || bar5) {
                    if (te.getArmor(hit) <= 0) {
                        break; // hitting IS, not acid-affected armor
                    }
                    damage = Math.min(te.getArmor(hit), 3);
                    vDesc.addElement(ReportFactory.createReport(6061, 2, te, damage));
                } else if (isPlatoon) {
                    damage = (int) Math.ceil(damage * 1.5);
                    vDesc.addElement(ReportFactory.createReport(6062, 2, te));
                }
                break;
            case INCENDIARY:
                // Incendiary AC ammo does +2 damage to unarmoured infantry
                if (isPlatoon) {
                    damage += 2;
                    vDesc.addElement(ReportFactory.createReport(6064, 2, te));
                }
                break;
            case ANTI_TSM:
                te.hitThisRoundByAntiTSM = true;
                break;
            case NAIL_RIVET:
                // no damage against armor of BAR rating >=5
                if ((te.getBARRating(hit.getLocation()) >= 5) && (te.getArmor(hit.getLocation()) > 0)) {
                    damage = 0;
                    vDesc.add(ReportFactory.createReport(6063, 2, te));
                }
                break;
            default:
                // We can ignore this.
                break;
        }

        // adjust VTOL rotor damage
        if ((te instanceof VTOL) && (hit.getLocation() == VTOL.LOC_ROTOR)
            && (hit.getGeneralDamageType() != HitData.DAMAGE_PHYSICAL)
            && !game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_FULL_ROTOR_HITS)) {
            damage = (damage + 9) / 10;
        }

        // save EI status, in case sensors crit destroys it
        final boolean eiStatus = te.hasActiveEiCockpit();
        // BA using EI implants receive +1 damage from attacks
        if (!(te instanceof Mech) && !(te instanceof Protomech) && eiStatus) {
            damage += 1;
        }

        // check for case on Aeros
        if (te instanceof Aero) {
            Aero a = (Aero) te;
            if (ammoExplosion && a.hasCase()) {
                // damage should be reduced by a factor of 2 for ammo explosions
                // according to p. 161, TW
                damage /= 2;
                vDesc.addElement(ReportFactory.createReport(9010, 3, te, damage));
            }
        }

        // infantry armor can reduce damage
        if (isPlatoon && (((Infantry) te).calcDamageDivisor() != 1.0)) {
            int damageNext = (int) Math.ceil((damage) / ((Infantry) te).calcDamageDivisor());
            vDesc.addElement(ReportFactory.createReport(6074, 2, te, damage, damageNext));
        }

        // Allocate the damage
        while (damage > 0) {
            // first check for ammo explosions on aeros separately, because it
            // must be done before standard to capital damage conversions
            if ((te instanceof Aero) && (hit.getLocation() == Aero.LOC_AFT) && !damageIS) {
                for (Mounted mAmmo : te.getAmmo()) {
                    if (mAmmo.isDumping() && !mAmmo.isDestroyed() && !mAmmo.isHit() && !(mAmmo.getType() instanceof BombType)) {
                        // doh. explode it
                        vDesc.addAll(explodeEquipment(te, mAmmo.getLocation(), mAmmo));
                        mAmmo.setHit(true);
                    }
                }
            }

            if (te.isAero()) {
                // chance of a critical if damage greater than threshold
                IAero a = (IAero) te;
                if ((threshDamage > a.getThresh(hit.getLocation()))) {
                    critThresh = true;
                    a.setCritThresh(true);
                }
            }

            // Capital fighters receive damage differently
            if (te.isCapitalFighter()) {
                IAero a = (IAero) te;
                a.setCurrentDamage(a.getCurrentDamage() + damage);
                a.setCapArmor(a.getCapArmor() - damage);
                vDesc.addElement(ReportFactory.createReport(9065, 2, te, damage));
                vDesc.addElement(ReportFactory.createReport(6085, 0, te, Math.max(a.getCapArmor(), 0)));
                // check to see if this destroyed the entity
                if (a.getCapArmor() <= 0) {
                    // Lets auto-eject if we can!
                    if (a instanceof LandAirMech) {
                        // LAMs eject if the CT destroyed switch is on
                        LandAirMech lam = (LandAirMech) a;
                        if (lam.isAutoEject()
                            && (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION) 
                                    || (game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION) 
                                            && lam.isCondEjectCTDest()))) {
                            reportmanager.addReport(ejectEntity(te, true, false));
                        }
                    } else {
                        // Aeros eject if the SI Destroyed switch is on
                        Aero aero = (Aero) a;
                        if (aero.isAutoEject() && (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                    || (game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION) 
                                            && aero.isCondEjectSIDest()))) {
                            reportmanager.addReport(ejectEntity(te, true, false));
                        }
                    }
                    vDesc.addAll(entityManager.destroyEntity(te, "Structural Integrity Collapse"));
                    a.doDisbandDamage();
                    a.setCapArmor(0);
                    if (hit.getAttackerId() != Entity.NONE) {
                        game.creditKill(te, game.getEntity(hit.getAttackerId()));
                    }
                }
                // check for aero crits from natural 12 or threshold; LAMs take damage as mechs
                if (te instanceof Aero) {
                    checkAeroCrits(vDesc, (Aero) te, hit, damage_orig, critThresh, critSI, ammoExplosion, nukeS2S);
                }
                return vDesc;
            }

            if (!((te instanceof Aero) && ammoExplosion)) {
                // report something different for Aero ammo explosions
                r = ReportFactory.createReport(6065, 2, te, damage);
                if (damageIS) {
                    r.messageId = 6070;
                }
                r.add(te.getLocationAbbr(hit));
                vDesc.addElement(r);
            }

            // was the section destroyed earlier this phase?
            if (te.getInternal(hit) == IArmorState.ARMOR_DOOMED) {
                // cannot transfer a through armor crit if so
                crits = 0;
            }

            // Shields take damage first then cowls then armor whee
            // Shield does not protect from ammo explosions or falls.
            if (!ammoExplosion && !hit.isFallDamage() && !damageIS && te.hasShield()
                    && ((hit.getEffect() & HitData.EFFECT_NO_CRITICALS) != HitData.EFFECT_NO_CRITICALS)) {
                Mech me = (Mech) te;
                int damageNew = me.shieldAbsorptionDamage(damage, hit.getLocation(), hit.isRear());
                // if a shield absorbed the damage then lets tell the world about it.
                if (damageNew != damage) {
                    int absorb = damage - damageNew;
                    te.damageThisPhase += absorb;
                    damage = damageNew;

                    vDesc.addElement(ReportFactory.createReport(3530, 3, te, absorb));

                    if (damage <= 0) {
                        crits = 0;
                        specCrits = 0;
                        isHeadHit = false;
                    }
                }
            }

            // Armored Cowl may absorb some damage from hit
            if (te instanceof Mech) {
                Mech me = (Mech) te;
                if (me.hasCowl() && (hit.getLocation() == Mech.LOC_HEAD)
                    && !throughFront) {
                    int damageNew = me.damageCowl(damage);
                    int damageDiff = damage - damageNew;
                    me.damageThisPhase += damageDiff;
                    damage = damageNew;

                    vDesc.addElement(ReportFactory.createReport(3520, 3, te, damageDiff));
                }
            }

            // So might modular armor, if the location mounts any.
            if (!ammoExplosion && !damageIS
                    && ((hit.getEffect() & HitData.EFFECT_NO_CRITICALS) != HitData.EFFECT_NO_CRITICALS)) {
                int damageNew = te.getDamageReductionFromModularArmor(hit, damage, vDesc);
                int damageDiff = damage - damageNew;
                te.damageThisPhase += damageDiff;
                damage = damageNew;
            }

            // Destroy searchlights on 7+ (torso hits on mechs)
            if (te.hasSpotlight()) {
                boolean spotlightHittable = true;
                int loc = hit.getLocation();
                if (te instanceof Mech) {
                    if ((loc != Mech.LOC_CT) && (loc != Mech.LOC_LT) && (loc != Mech.LOC_RT)) {
                        spotlightHittable = false;
                    }
                } else if (te instanceof Tank) {
                    if (te instanceof SuperHeavyTank) {
                        if ((loc != Tank.LOC_FRONT)
                                && (loc != SuperHeavyTank.LOC_FRONTRIGHT)
                                && (loc != SuperHeavyTank.LOC_FRONTLEFT)
                                && (loc != SuperHeavyTank.LOC_REARRIGHT)
                                && (loc != SuperHeavyTank.LOC_REARLEFT)) {
                            spotlightHittable = false;
                        }
                    } else if (te instanceof LargeSupportTank) {
                        if ((loc != Tank.LOC_FRONT)
                                && (loc != LargeSupportTank.LOC_FRONTRIGHT)
                                && (loc != LargeSupportTank.LOC_FRONTLEFT)
                                && (loc != LargeSupportTank.LOC_REARRIGHT)
                                && (loc != LargeSupportTank.LOC_REARLEFT)) {
                            spotlightHittable = false;
                        }
                    } else {
                        if ((loc != Tank.LOC_FRONT) && (loc != Tank.LOC_RIGHT) && (loc != Tank.LOC_LEFT)) {
                            spotlightHittable = false;
                        }
                    }
                }
                if (spotlightHittable) {
                    int spotroll = Compute.d6(2);
                    r = new Report(6072);
                    r.indent(2);
                    r.subject = te_n;
                    r.add("7+");
                    r.add("Searchlight");
                    r.add(spotroll);
                    vDesc.addElement(r);
                    if (spotroll >= 7) {
                        r = new Report(6071);
                        r.subject = te_n;
                        r.indent(2);
                        r.add("Searchlight");
                        vDesc.addElement(r);
                        te.destroyOneSpotlight();
                    }
                }
            }

            // Does an exterior passenger absorb some of the damage?
            if (!damageIS) {
                int nLoc = hit.getLocation();
                Entity passenger = te.getExteriorUnitAt(nLoc, hit.isRear());
                // Does an exterior passenger absorb some of the damage?
                if (!ammoExplosion && (null != passenger) && !passenger.isDoomed()
                        && (bFrag != DamageType.IGNORE_PASSENGER)) {
                    damage = damageExternalPassenger(te, hit, damage, vDesc, passenger);
                }

                boolean bTorso = (nLoc == Mech.LOC_CT) || (nLoc == Mech.LOC_RT) || (nLoc == Mech.LOC_LT);

                // Does a swarming unit absorb damage?
                int swarmer = te.getSwarmAttackerId();
                if ((!(te instanceof Mech) || bTorso) && (swarmer != Entity.NONE)
                        && ((hit.getEffect() & HitData.EFFECT_CRITICAL) == 0) && (Compute.d6() >= 5)
                        && (bFrag != DamageType.IGNORE_PASSENGER) && !ammoExplosion) {
                    Entity swarm = game.getEntity(swarmer);
                    // Yup. Roll up some hit data for that passenger.
                    vDesc.addElement(ReportFactory.createReport(6076, 3, swarm));

                    HitData passHit = swarm.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);

                    // How much damage will the swarm absorb?
                    int absorb = 0;
                    HitData nextPassHit = passHit;
                    do {
                        if (0 < swarm.getArmor(nextPassHit)) {
                            absorb += swarm.getArmor(nextPassHit);
                        }
                        if (0 < swarm.getInternal(nextPassHit)) {
                            absorb += swarm.getInternal(nextPassHit);
                        }
                        nextPassHit = swarm.getTransferLocation(nextPassHit);
                    } while ((damage > absorb) && (nextPassHit.getLocation() >= 0));

                    // Damage the swarm.
                    int absorbedDamage = Math.min(damage, absorb);
                    Vector<Report> newReports = damageEntity(swarm, passHit, absorbedDamage);
                    for (Report newReport : newReports) {
                        newReport.indent(2);
                    }
                    vDesc.addAll(newReports);

                    // Did some damage pass on?
                    if (damage > absorb) {
                        // Yup. Remove the absorbed damage.
                        damage -= absorb;
                        vDesc.addElement(ReportFactory.createReport(6080, 2, te, damage));
                    } else {
                        // Nope. Return our description.
                        return vDesc;
                    }
                }

                // is this a mech/tank dumping ammo being hit in the rear torso?
                if (((te instanceof Mech) && hit.isRear() && bTorso)
                        || ((te instanceof Tank)
                        && (hit.getLocation() == (te instanceof SuperHeavyTank ? SuperHeavyTank.LOC_REAR : Tank.LOC_REAR)))) {
                    for (Mounted mAmmo : te.getAmmo()) {
                        if (mAmmo.isDumping() && !mAmmo.isDestroyed() && !mAmmo.isHit()) {
                            // doh. explode it
                            vDesc.addAll(explodeEquipment(te, mAmmo.getLocation(), mAmmo));
                            mAmmo.setHit(true);
                        }
                    }
                }
            }
            // is there armor in the location hit?
            if (!ammoExplosion && (te.getArmor(hit) > 0) && !damageIS) {
                int tmpDamageHold = -1;
                int origDamage = damage;

                if (isPlatoon) {
                    // infantry armour works differently
                    int armor = te.getArmor(hit);
                    int men = te.getInternal(hit);
                    tmpDamageHold = damage % 2;
                    damage /= 2;
                    if ((tmpDamageHold == 1) && (armor >= men)) {
                        // extra 1 point of damage to armor
                        tmpDamageHold = damage;
                        damage++;
                    } else {
                        // extra 0 or 1 point of damage to men
                        tmpDamageHold += damage;
                    }
                    // If the target has Ferro-Lamellor armor, we need to adjust
                    // damage. (4/5ths rounded down), Also check to eliminate crit
                    // chances for damage reduced to 0
                } else if (ferroLamellorArmor
                           && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING)
                           && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING_MISSILE)
                           && (hit.getGeneralDamageType() != HitData.DAMAGE_IGNORES_DMG_REDUCTION)) {
                    tmpDamageHold = damage;
                    damage = (int) Math.floor((((double) damage) * 4) / 5);
                    if (damage <= 0) {
                        isHeadHit = false;
                        crits = 0;
                    }
                    vDesc.addElement(ReportFactory.createReport(6073, 3, te, damage));
                } else if (ballisticArmor
                           && ((hit.getGeneralDamageType() == HitData.DAMAGE_ARMOR_PIERCING_MISSILE)
                               || (hit.getGeneralDamageType() == HitData.DAMAGE_ARMOR_PIERCING)
                               || (hit.getGeneralDamageType() == HitData.DAMAGE_BALLISTIC)
                               || (hit.getGeneralDamageType() == HitData.DAMAGE_MISSILE))) {
                    tmpDamageHold = damage;
                    damage = Math.max(1, damage / 2);
                    vDesc.addElement(ReportFactory.createReport(6088, 3, te, damage));
                } else if (impactArmor && (hit.getGeneralDamageType() == HitData.DAMAGE_PHYSICAL)) {
                    tmpDamageHold = damage;
                    damage -= (int) Math.ceil((double) damage / 3);
                    damage = Math.max(1, damage);
                    vDesc.addElement(ReportFactory.createReport(6089, 3, te, damage));
                } else if (reflectiveArmor
                           && (hit.getGeneralDamageType() == HitData.DAMAGE_PHYSICAL)
                           && !isBattleArmor) { // BA reflec does not receive extra physical damage
                    tmpDamageHold = damage;
                    int currArmor = te.getArmor(hit);
                    int dmgToDouble = Math.min(damage, currArmor / 2);
                    damage += dmgToDouble;
                    vDesc.addElement(ReportFactory.createReport(6066, 3, te, currArmor, tmpDamageHold, dmgToDouble, damage));
                } else if (reflectiveArmor && areaSatArty && !isBattleArmor) {
                    tmpDamageHold = damage; // BA reflec does not receive extra AE damage
                    int currArmor = te.getArmor(hit);
                    int dmgToDouble = Math.min(damage, currArmor / 2);
                    damage += dmgToDouble;
                    vDesc.addElement(ReportFactory.createReport(6087, 3, te, currArmor, tmpDamageHold, dmgToDouble, damage));
                } else if (reflectiveArmor && (hit.getGeneralDamageType() == HitData.DAMAGE_ENERGY)) {
                    tmpDamageHold = damage;
                    damage = (int) Math.floor(((double) damage) / 2);
                    if (tmpDamageHold == 1) {
                        damage = 1;
                    }
                    vDesc.addElement(ReportFactory.createReport(6067, 3, te, damage));
                } else if (reactiveArmor
                           && ((hit.getGeneralDamageType() == HitData.DAMAGE_MISSILE)
                               || (hit.getGeneralDamageType() == HitData.DAMAGE_ARMOR_PIERCING_MISSILE) ||
                               areaSatArty)) {
                    tmpDamageHold = damage;
                    damage = (int) Math.floor(((double) damage) / 2);
                    if (tmpDamageHold == 1) {
                        damage = 1;
                    }
                    vDesc.addElement(ReportFactory.createReport(6068, 3, te, damage));
                }

                // If we're using optional tank damage thresholds, setup our hit effects now...
                if ((te instanceof Tank)
                        && game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_VEHICLES_THRESHOLD)
                        && !((te instanceof VTOL) || (te instanceof GunEmplacement))) {
                    int thresh = (int) Math.ceil(
                            (game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_VEHICLES_THRESHOLD_VARIABLE)
                                    ? te.getArmor(hit) : te.getOArmor(hit)) / (double) game.getOptions().intOption(
                                            OptionsConstants.ADVCOMBAT_VEHICLES_THRESHOLD_DIVISOR));

                    // adjust for hardened armor
                    if (hardenedArmor
                            && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING)
                            && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING_MISSILE)
                            && (hit.getGeneralDamageType() != HitData.DAMAGE_IGNORES_DMG_REDUCTION)) {
                        thresh *= 2;
                    }

                    if ((damage > thresh) || (te.getArmor(hit) < damage)) {
                        hit.setEffect(((Tank) te).getPotCrit());
                        ((Tank) te).setOverThresh(true);
                        // TACs from the hit location table
                        crits = ((hit.getEffect() & HitData.EFFECT_CRITICAL)
                                == HitData.EFFECT_CRITICAL) ? 1 : 0;
                    } else {
                        ((Tank) te).setOverThresh(false);
                        crits = 0;
                    }
                }

                // if there's a mast mount in the rotor, it and all other equipment
                // on it get destroyed
                if ((te instanceof VTOL)
                    && (hit.getLocation() == VTOL.LOC_ROTOR)
                    && te.hasWorkingMisc(MiscType.F_MAST_MOUNT, -1,
                                         VTOL.LOC_ROTOR)) {
                    vDesc.addElement(ReportFactory.createReport(6081, 2, te));
                    for (Mounted mount : te.getMisc()) {
                        if (mount.getLocation() == VTOL.LOC_ROTOR) {
                            mount.setHit(true);
                        }
                    }
                }
                // Need to account for the possibility of hardened armor here
                int armorThreshold = te.getArmor(hit);
                if (hardenedArmor
                    && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING)
                    && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING_MISSILE)
                    && (hit.getGeneralDamageType() != HitData.DAMAGE_IGNORES_DMG_REDUCTION)) {
                    armorThreshold *= 2;
                    armorThreshold -= (te.isHardenedArmorDamaged(hit)) ? 1 : 0;
                    vDesc.lastElement().newlines = 0;
                    r = new Report(6069);
                    r.subject = te_n;
                    r.indent(3);
                    int reportedDamage = damage / 2;
                    if ((damage % 2) > 0) {
                        r.add(reportedDamage + ".5");
                    } else {
                        r.add(reportedDamage);
                    }

                    vDesc.addElement(r);
                }
                if (armorThreshold >= damage) {
                    // armor absorbs all damage
                    // Hardened armor deals with damage in its own fashion...
                    if (hardenedArmor
                        && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING)
                        && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING_MISSILE)
                        && (hit.getGeneralDamageType() != HitData.DAMAGE_IGNORES_DMG_REDUCTION)) {
                        armorThreshold -= damage;
                        te.setHardenedArmorDamaged(hit, (armorThreshold % 2) > 0);
                        te.setArmor((armorThreshold / 2) + (armorThreshold % 2), hit);
                    } else {
                        te.setArmor(te.getArmor(hit) - damage, hit);
                    }

                    // set "armor damage" flag for HarJel II/III we only care about this if there is armor remaining,
                    // so don't worry about the case where damage exceeds armorThreshold
                    if ((te instanceof Mech) && (damage > 0)) {
                        ((Mech) te).setArmorDamagedThisTurn(hit.getLocation(), true);
                    }

                    // if the armor is hardened, any penetrating crits are rolled at -2
                    if (hardenedArmor) {
                        critBonus -= 2;
                    }

                    if (tmpDamageHold >= 0) {
                        te.damageThisPhase += tmpDamageHold;
                    } else {
                        te.damageThisPhase += damage;
                    }
                    damage = 0;
                    reportID = 6086;
                    if (!te.isHardenedArmorDamaged(hit)) {
                        reportID =6085;
                    }
                    vDesc.addElement(ReportFactory.createReport(reportID, 3, te, te.getArmor(hit)));

                    // telemissiles are destroyed if they lose all armor
                    if ((te instanceof TeleMissile)
                        && (te.getArmor(hit) == damage)) {
                        vDesc.addAll(entityManager.destroyEntity(te, "damage", false));
                    }
                } else {
                    // damage goes on to internal
                    int absorbed = Math.max(te.getArmor(hit), 0);
                    if (hardenedArmor
                        && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING)
                        && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING_MISSILE)) {
                        absorbed = (absorbed * 2) - ((te.isHardenedArmorDamaged(hit)) ? 1 : 0);
                    }
                    if (reflectiveArmor && (hit.getGeneralDamageType() == HitData.DAMAGE_PHYSICAL) && !isBattleArmor) {
                        absorbed = (int) Math.ceil(absorbed / 2.0);
                        damage = tmpDamageHold;
                        tmpDamageHold = 0;
                    }
                    te.setArmor(IArmorState.ARMOR_DESTROYED, hit);
                    if (tmpDamageHold >= 0) {
                        te.damageThisPhase += 2 * absorbed;
                    } else {
                        te.damageThisPhase += absorbed;
                    }
                    damage -= absorbed;
                    vDesc.addElement(ReportFactory.createReport(6090, 3, te));
                    if (te instanceof GunEmplacement) {
                        // gun emplacements have no internal,
                        // destroy the section
                        te.destroyLocation(hit.getLocation());
                        vDesc.addElement(ReportFactory.createReport(6115, 0, te));

                        if (te.getTransferLocation(hit).getLocation() == Entity.LOC_DESTROYED) {
                            vDesc.addAll(entityManager.destroyEntity(te, "damage", false));
                        }
                    }
                }

                // targets with BAR armor get crits, depending on damage and BAR
                // rating
                if (te.hasBARArmor(hit.getLocation())) {
                    if (origDamage > te.getBARRating(hit.getLocation())) {
                        if (te.hasArmoredChassis()) {
                            // crit roll with -1 mod
                            vDesc.addAll(criticalEntity(te, hit.getLocation(), hit.isRear(), -1 + critBonus, damage_orig));
                        } else {
                            vDesc.addAll(criticalEntity(te, hit.getLocation(), hit.isRear(), critBonus, damage_orig));
                        }
                    }
                }
                if ((tmpDamageHold > 0) && isPlatoon) {
                    damage = tmpDamageHold;
                }
            }

            // For optional tank damage thresholds, the overthresh flag won't
            // be set if IS is damaged, so set it here.
            if ((te instanceof Tank)
                    && ((te.getArmor(hit) < 1) || damageIS)
                    && game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_VEHICLES_THRESHOLD)
                    && !((te instanceof VTOL) || (te instanceof GunEmplacement))) {
                ((Tank) te).setOverThresh(true);
            }

            // is there damage remaining?
            if (damage > 0) {
                // if this is an Aero then I need to apply internal damage
                // to the SI after halving it. Return from here to prevent
                // further processing
                if (te instanceof Aero) {
                    Aero a = (Aero) te;

                    // check for large craft ammo explosions here: damage vented through armor, excess
                    // dissipating, much like Tank CASE.
                    if (ammoExplosion && te.isLargeCraft()) {
                        te.damageThisPhase += damage;
                        r = ReportFactory.createReport(6128, 2, te, damage);
                        int loc = hit.getLocation();
                        //Roll for broadside weapons so fore/aft side armor facing takes the damage
                        if (loc == Warship.LOC_LBS) {
                            int locRoll = Compute.d6();
                            if (locRoll < 4) {
                                loc = Jumpship.LOC_FLS;
                            } else {
                                loc = Jumpship.LOC_ALS;
                            }
                        }
                        if (loc == Warship.LOC_RBS) {
                            int locRoll = Compute.d6();
                            if (locRoll < 4) {
                                loc = Jumpship.LOC_FRS;
                            } else {
                                loc = Jumpship.LOC_ARS;
                            }
                        }
                        r.add(te.getLocationAbbr(loc));
                        vDesc.add(r);
                        if (damage > te.getArmor(loc)) {
                            te.setArmor(IArmorState.ARMOR_DESTROYED, loc);
                            r = new Report(6090);
                        } else {
                            te.setArmor(te.getArmor(loc) - damage, loc);
                            r = new Report(6085);
                            r.add(te.getArmor(loc));
                        }
                        r.subject = te_n;
                        r.indent(3);
                        vDesc.add(r);
                        damage = 0;
                    }

                    // check for overpenetration
                    if (game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_STRATOPS_OVER_PENETRATE)) {
                        int opRoll = Compute.d6(1);
                        if ((((te instanceof Jumpship) || (te instanceof SpaceStation))
                                && !(te instanceof Warship) && (opRoll > 3))
                                || ((te instanceof Dropship) && (opRoll > 4))
                                || ((te instanceof Warship) && (a.get0SI() <= 30) && (opRoll > 5))) {
                            // over-penetration happened
                            vDesc.addElement(ReportFactory.createReport(9090, 0, te));
                            int new_loc = a.getOppositeLocation(hit.getLocation());
                            damage = Math.min(damage, te.getArmor(new_loc));
                            // We don't want to deal negative damage
                            damage = Math.max(damage, 0);
                            r = ReportFactory.createReport(6025, 2, te, damage);
                            r.add(te.getLocationAbbr(new_loc));
                            vDesc.addElement(r);
                            te.setArmor(te.getArmor(new_loc) - damage, new_loc);
                            if ((te instanceof Warship) || (te instanceof Dropship)) {
                                damage = 2;
                            } else {
                                damage = 0;
                            }
                        }
                    }

                    // divide damage in half do not divide by half if it is an ammo exposion
                    if (!ammoExplosion && !nukeS2S && !game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_AERO_SANITY)) {
                        damage /= 2;
                    }

                    // this should result in a crit but only if it really did damage after rounding down
                    if (damage > 0) {
                        critSI = true;
                    }

                    // Now apply damage to the structural integrity
                    a.setSI(a.getSI() - damage);
                    te.damageThisPhase += damage;
                    // send the report
                    r = new Report(1210);
                    r.subject = te_n;
                    r.newlines = 1;
                    if (!ammoExplosion) {
                        r.messageId = 9005;
                    }
                    //Only for fighters
                    if (ammoExplosion && !a.isLargeCraft()) {
                        r.messageId = 9006;
                    }
                    r.add(damage);
                    r.add(Math.max(a.getSI(), 0));
                    vDesc.addElement(r);
                    // check to see if this would destroy the ASF
                    if (a.getSI() <= 0) {
                        // Lets auto-eject if we can!
                        if (a.isAutoEject()
                            && (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION) 
                                    || (game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION) 
                                            && a.isCondEjectSIDest()))) {
                            vDesc.addAll(ejectEntity(te, true, false));
                        } else {
                            vDesc.addAll(entityManager.destroyEntity(te,"Structural Integrity Collapse"));
                        }
                        a.setSI(0);
                        if (hit.getAttackerId() != Entity.NONE) {
                            game.creditKill(a, game.getEntity(hit.getAttackerId()));
                        }
                    }
                    checkAeroCrits(vDesc, a, hit, damage_orig, critThresh, critSI, ammoExplosion, nukeS2S);
                    return vDesc;
                }

                // Check for CASE II right away. if so reduce damage to 1 and let it hit the IS.
                // Also remove as much of the rear armor as allowed by the damage. If arm/leg/head
                // Then they lose all their armor if its less then the explosion damage.
                if (ammoExplosion && te.hasCASEII(hit.getLocation())) {
                    // 1 point of damage goes to IS
                    damage--;
                    // Remaining damage prevented by CASE II
                    vDesc.addElement(ReportFactory.createReport(6126, 3, te, damage));
                    int loc = hit.getLocation();

                    boolean isrear = true;
                    if ((te instanceof Mech) && ((loc == Mech.LOC_HEAD) || ((Mech) te).isArm(loc) || te.locationIsLeg(loc))) {
                        int half = (int) Math.ceil(te.getOArmor(loc, false) / 2.0);
                        damage = Math.min(damage, half);
                        isrear = false;
                    }
                    if (damage >= te.getArmor(loc, isrear)) {
                        te.setArmor(IArmorState.ARMOR_DESTROYED, loc, isrear);
                    } else {
                        te.setArmor(te.getArmor(loc, isrear) - damage, loc, isrear);
                    }

                    // Mek takes 1 point of IS damage
                    damage = (te.getInternal(hit) > 0) ? 1 : 0;

                    te.damageThisPhase += damage;

                    int roll = Compute.d6(2);
                    vDesc.add(ReportFactory.createReport(6127, 0, te, roll));
                    if (roll >= 8) {
                        hit.setEffect(HitData.EFFECT_NO_CRITICALS);
                    }
                }
                // check for tank CASE here: damage to rear armor, excess
                // dissipating, and a crew stunned crit
                if (ammoExplosion && (te instanceof Tank)
                    && te.locationHasCase(Tank.LOC_BODY)) {
                    te.damageThisPhase += damage;
                    r = new Report(6124);
                    r.subject = te_n;
                    r.indent(2);
                    r.add(damage);
                    vDesc.add(r);
                    int loc = (te instanceof SuperHeavyTank) ? SuperHeavyTank.LOC_REAR
                            : (te instanceof LargeSupportTank) ? LargeSupportTank.LOC_REAR : Tank.LOC_REAR;
                    if (damage > te.getArmor(loc)) {
                        te.setArmor(IArmorState.ARMOR_DESTROYED, loc);
                        r = new Report(6090);
                    } else {
                        te.setArmor(te.getArmor(loc) - damage, loc);
                        r = new Report(6085);
                        r.add(te.getArmor(loc));
                    }
                    r.subject = te_n;
                    r.indent(3);
                    vDesc.add(r);
                    damage = 0;
                    int critIndex;
                    if (((Tank) te).isCommanderHit() && ((Tank) te).isDriverHit()) {
                        critIndex = Tank.CRIT_CREW_KILLED;
                    } else {
                        critIndex = Tank.CRIT_CREW_STUNNED;
                    }
                    vDesc.addAll(applyCriticalHit(te, Entity.NONE, new CriticalSlot(0, critIndex), true, 0, false));
                }

                // is there internal structure in the location hit?
                if (te.getInternal(hit) > 0) {
                    // Now we need to consider alternate structure types!
                    int tmpDamageHold = -1;
                    if ((te instanceof Mech) && ((Mech) te).hasCompositeStructure()) {
                        tmpDamageHold = damage;
                        damage *= 2;
                        r = new Report(6091);
                        r.subject = te_n;
                        r.indent(3);
                        vDesc.add(r);
                    }
                    if ((te instanceof Mech) && ((Mech) te).hasReinforcedStructure()) {
                        tmpDamageHold = damage;
                        damage /= 2;
                        damage += tmpDamageHold % 2;
                        r = new Report(6092);
                        r.subject = te_n;
                        r.indent(3);
                        vDesc.add(r);
                    }
                    if ((te.getInternal(hit) > damage) && (damage > 0)) {
                        // internal structure absorbs all damage
                        te.setInternal(te.getInternal(hit) - damage, hit);
                        // Triggers a critical hit on Vehicles and Mechs.
                        if (!isPlatoon && !isBattleArmor) {
                            crits++;
                        }
                        tookInternalDamage = true;
                        // Alternate structures don't affect our damage total
                        // for later PSR purposes, so use the previously stored
                        // value here as necessary.
                        te.damageThisPhase += (tmpDamageHold > -1) ? tmpDamageHold : damage;
                        damage = 0;
                        r = new Report(6100);
                        r.subject = te_n;
                        r.indent(3);
                        // Infantry platoons have men not "Internals".
                        if (isPlatoon) {
                            r.messageId = 6095;
                        }
                        r.add(te.getInternal(hit));
                        vDesc.addElement(r);
                    } else if (damage > 0) {
                        // Triggers a critical hit on Vehicles and Mechs.
                        if (!isPlatoon && !isBattleArmor) {
                            crits++;
                        }
                        // damage transfers, maybe
                        int absorbed = Math.max(te.getInternal(hit), 0);

                        // Handle ProtoMech pilot damage
                        // due to location destruction
                        if (te instanceof Protomech) {
                            int hits = Protomech.POSSIBLE_PILOT_DAMAGE[hit.getLocation()]
                                    - ((Protomech) te).getPilotDamageTaken(hit.getLocation());
                            if (hits > 0) {
                                vDesc.addAll(damageCrew(te, hits));
                                ((Protomech) te).setPilotDamageTaken(hit.getLocation(),
                                        Protomech.POSSIBLE_PILOT_DAMAGE[hit.getLocation()]);
                            }
                        }

                        // Platoon, Trooper, or Section destroyed message
                        r = new Report(1210);
                        r.subject = te_n;
                        if (isPlatoon) {
                            // Infantry have only one section, and
                            // are therefore destroyed.
                            if (((Infantry) te).isSquad()) {
                                r.messageId = 6106; // Squad Killed
                            } else {
                                r.messageId = 6105; // Platoon Killed
                            }
                        } else if (isBattleArmor) {
                            r.messageId = 6110;
                        } else {
                            r.messageId = 6115;
                        }
                        r.indent(3);
                        vDesc.addElement(r);

                        // If a sidetorso got destroyed, and the
                        // corresponding arm is not yet destroyed, add
                        // it as a club to that hex (p.35 BMRr)
                        if ((te instanceof Mech)
                                && (((hit.getLocation() == Mech.LOC_RT) && (te.getInternal(Mech.LOC_RARM) > 0))
                                    || ((hit.getLocation() == Mech.LOC_LT) && (te.getInternal(Mech.LOC_LARM) > 0)))) {
                            int blownOffLocation;
                            if (hit.getLocation() == Mech.LOC_RT) {
                                blownOffLocation = Mech.LOC_RARM;
                            } else {
                                blownOffLocation = Mech.LOC_LARM;
                            }
                            te.destroyLocation(blownOffLocation, true);
                            r = new Report(6120);
                            r.subject = te_n;
                            r.add(te.getLocationName(blownOffLocation));
                            vDesc.addElement(r);
                            IHex h = game.getBoard().getHex(te.getPosition());
                            if(null != h) {
                                int terrainType = te instanceof BipedMech ? Terrains.ARMS : Terrains.LEGS;
                                int terrainLevel = h.containsTerrain(terrainType) ? h.terrainLevel(terrainType) + 1 : 1;
                                h.addTerrain(Terrains.getTerrainFactory().createTerrain(terrainType, terrainLevel));
                                gamemanager.sendChangedHex(game, te.getPosition());
                            }
                        }

                        // Troopers riding on a location all die when the location is destroyed.
                        if ((te instanceof Mech) || (te instanceof Tank)) {
                            Entity passenger = te.getExteriorUnitAt(hit.getLocation(), hit.isRear());
                            if ((null != passenger) && !passenger.isDoomed()) {
                                HitData passHit = passenger.getTrooperAtLocation(hit, te);
                                // ensures a kill
                                passHit.setEffect(HitData.EFFECT_CRITICAL);
                                if (passenger.getInternal(passHit) > 0) {
                                    vDesc.addAll(damageEntity(passenger, passHit, damage));
                                }
                                passHit = new HitData(hit.getLocation(), !hit.isRear());
                                passHit = passenger.getTrooperAtLocation(passHit, te);
                                // ensures a kill
                                passHit.setEffect(HitData.EFFECT_CRITICAL);
                                if (passenger.getInternal(passHit) > 0) {
                                    vDesc.addAll(damageEntity(passenger, passHit, damage));
                                }
                            }
                        }

                        // BA inferno explosions
                        if (te instanceof BattleArmor) {
                            int infernos = 0;
                            for (Mounted m : te.getEquipment()) {
                                if (m.getType() instanceof AmmoType) {
                                    AmmoType at = (AmmoType) m.getType();
                                    if (((at.getAmmoType() == AmmoType.T_SRM) || (at.getAmmoType() == AmmoType.T_MML))
                                            && (at.getMunitionType() == AmmoType.M_INFERNO)) {
                                        infernos += at.getRackSize() * m.getHittableShotsLeft();
                                    }
                                } else if (m.getType().hasFlag(MiscType.F_FIRE_RESISTANT)) {
                                    // immune to inferno explosion
                                    infernos = 0;
                                    break;
                                }
                            }
                            if (infernos > 0) {
                                int roll = Compute.d6(2);
                                r = new Report(6680);
                                r.add(roll);
                                vDesc.add(r);
                                if (roll >= 8) {
                                    Coords c = te.getPosition();
                                    if (c == null) {
                                        Entity transport = game.getEntity(te.getTransportId());
                                        if (transport != null) {
                                            c = transport.getPosition();
                                        }
                                        reportmanager.addReport(deliverInfernoMissiles(te, te, infernos));
                                    }
                                    if (c != null) {
                                        reportmanager.addReport(deliverInfernoMissiles(te,
                                                new HexTarget(c, game.getBoard(), Targetable.TYPE_HEX_ARTILLERY),
                                                infernos));
                                    }
                                }
                            }
                        }

                        // Mark off the internal structure here, but *don't*
                        // destroy the location just yet -- there are checks
                        // still to run!
                        te.setInternal(0, hit);
                        te.damageThisPhase += absorbed;
                        damage -= absorbed;

                        // Now we need to consider alternate structure types!
                        if (tmpDamageHold > 0) {
                            if (((Mech) te).hasCompositeStructure()) {
                                // If there's a remainder, we can actually ignore it.
                                damage /= 2;
                            } else if (((Mech) te).hasReinforcedStructure()) {
                                damage *= 2;
                                damage -= tmpDamageHold % 2;
                            }
                        }
                    }
                }
                if (te.getInternal(hit) <= 0) {
                    // internal structure is gone, what are the transfer
                    // potentials?
                    nextHit = te.getTransferLocation(hit);
                    if (nextHit.getLocation() == Entity.LOC_DESTROYED) {
                        if (te instanceof Mech) {
                            // Start with the number of engine crits in this location, if any...
                            te.engineHitsThisPhase += te.getNumberOfCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_ENGINE, hit.getLocation());
                            // ...then deduct the ones destroyed previously or critically hit this round already.
                            // That leaves the ones actually destroyed with the location.
                            te.engineHitsThisPhase -= te.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_ENGINE, hit.getLocation());
                        }

                        boolean engineExploded = checkEngineExplosion(te, vDesc, te.engineHitsThisPhase);

                        if (!engineExploded) {
                            // Entity destroyed. Ammo explosions are neither survivable nor salvageable.
                            // Only ammo explosions in the CT are devastating.
                            vDesc.addAll(entityManager.destroyEntity(te, "damage", !ammoExplosion,
                                    !((ammoExplosion || areaSatArty) && ((te instanceof Tank)
                                            || ((te instanceof Mech) && (hit.getLocation() == Mech.LOC_CT))))));
                            // If the head is destroyed, kill the crew.

                            if ((te instanceof Mech) && (hit.getLocation() == Mech.LOC_HEAD)
                                    && !te.getCrew().isDead() && !te.getCrew().isDoomed()
                                    && game.getOptions().booleanOption(
                                            OptionsConstants.ADVANCED_TACOPS_SKIN_OF_THE_TEETH_EJECTION)) {
                                Mech mech = (Mech) te;
                                if (mech.isAutoEject()
                                        && (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                        || (game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                                && mech.isCondEjectHeadshot()))) {
                                    autoEject = true;
                                    vDesc.addAll(ejectEntity(te, true, true));
                                }
                            }

                            if ((te instanceof Mech) && (hit.getLocation() == Mech.LOC_CT)
                                    && !te.getCrew().isDead() && !te.getCrew().isDoomed()) {
                                Mech mech = (Mech) te;
                                if (mech.isAutoEject()
                                        && game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                        && mech.isCondEjectCTDest()) {
                                    if (mech.getCrew().getHits() < 5) {
                                        Report.addNewline(vDesc);
                                        mech.setDoomed(false);
                                        mech.setDoomed(true);
                                    }
                                    autoEject = true;
                                    vDesc.addAll(ejectEntity(te, true));
                                }
                            }

                            if ((hit.getLocation() == Mech.LOC_HEAD) || ((hit.getLocation() == Mech.LOC_CT)
                                    && ((ammoExplosion && !autoEject) || areaSatArty))) {
                                te.getCrew().setDoomed(true);
                            }
                            if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_AUTO_ABANDON_UNIT)) {
                                vDesc.addAll(abandonEntity(te));
                            }
                        }

                        // nowhere for further damage to go
                        damage = 0;
                    } else if (nextHit.getLocation() == Entity.LOC_NONE) {
                        // Rest of the damage is wasted.
                        damage = 0;
                    } else if (ammoExplosion && te.locationHasCase(hit.getLocation())) {
                        // Remaining damage prevented by CASE
                        r = new Report(6125);
                        r.subject = te_n;
                        r.add(damage);
                        r.indent(3);
                        vDesc.addElement(r);

                        // The target takes no more damage from the explosion.
                        damage = 0;
                    } else if (damage > 0) {
                        // remaining damage transfers
                        r = new Report(6130);
                        r.subject = te_n;
                        r.indent(2);
                        r.add(damage);
                        r.add(te.getLocationAbbr(nextHit));
                        vDesc.addElement(r);

                        // If there are split weapons in this location, mark it
                        // as hit, even if it took no criticals.
                        for (Mounted m : te.getWeaponList()) {
                            if (m.isSplit()) {
                                if ((m.getLocation() == hit.getLocation())
                                    || (m.getLocation() == nextHit.getLocation())) {
                                    te.setWeaponHit(m);
                                }
                            }
                        }
                        // if this is damage from a nail/rivet gun, and we transfer
                        // to a location that has armor, and BAR >=5, no damage
                        if ((bFrag == DamageType.NAIL_RIVET)
                            && (te.getArmor(nextHit.getLocation()) > 0)
                            && (te.getBARRating(nextHit.getLocation()) >= 5)) {
                            damage = 0;
                            r = new Report(6065);
                            r.subject = te_n;
                            r.indent(2);
                            vDesc.add(r);
                        }
                    }
                }
            } else if (hit.getSpecCrit()) {
                // ok, we dealt damage but didn't go on to internal
                // we get a chance of a crit, using Armor Piercing.
                // but only if we don't have hardened, Ferro-Lamellor, or reactive armor
                if (!hardenedArmor && !ferroLamellorArmor && !reactiveArmor) {
                    specCrits++;
                }
            }
            // check for breaching
            vDesc.addAll(breachCheck(te, hit.getLocation(), null, underWater));

            // resolve special results
            if ((hit.getEffect() & HitData.EFFECT_VEHICLE_MOVE_DAMAGED) == HitData.EFFECT_VEHICLE_MOVE_DAMAGED) {
                vDesc.addAll(vehicleMotiveDamage((Tank) te, hit.getMotiveMod()));
            }
            // Damage from any source can break spikes
            if (te.hasWorkingMisc(MiscType.F_SPIKES, -1, hit.getLocation())) {
                vDesc.add(game.checkBreakSpikes(te, hit.getLocation()));
            }

            // roll all critical hits against this location unless the section destroyed in a previous phase?
            // Cause a crit.
            if ((te.getInternal(hit) != IArmorState.ARMOR_DESTROYED)
                    && ((hit.getEffect() & HitData.EFFECT_NO_CRITICALS) != HitData.EFFECT_NO_CRITICALS)) {
                for (int i = 0; i < crits; i++) {
                    vDesc.addAll(criticalEntity(te, hit.getLocation(), hit.isRear(),
                            hit.glancingMod() + critBonus, damage_orig));
                }
                crits = 0;

                for (int i = 0; i < specCrits; i++) {
                    // against BAR or reflective armor, we get a +2 mod
                    int critMod = te.hasBARArmor(hit.getLocation()) ? 2 : 0;
                    critMod += (reflectiveArmor && !isBattleArmor) ? 2 : 0; // BA
                    // against impact armor, we get a +1 mod
                    critMod += impactArmor ? 1 : 0;
                    // hardened armour has no crit penalty
                    if (!hardenedArmor) {
                        // non-hardened armor gets modifiers the -2 for hardened is
                        // handled in the critBonus variable
                        critMod += hit.getSpecCritMod();
                        critMod += hit.glancingMod();
                    }
                    vDesc.addAll(criticalEntity(te, hit.getLocation(), hit.isRear(),
                            critMod + critBonus, damage_orig));
                }
                specCrits = 0;
            }

            // resolve Aero crits
            if (te instanceof Aero) {
                checkAeroCrits(vDesc, (Aero) te, hit, damage_orig, critThresh, critSI, ammoExplosion, nukeS2S);
            }

            if (isHeadHit
                && !te.hasAbility(OptionsConstants.MD_DERMAL_ARMOR)) {
                Report.addNewline(vDesc);
                vDesc.addAll(damageCrew(te, 1));
            }

            // If the location has run out of internal structure, finally actually
            // destroy it here. *EXCEPTION:* Aero units have 0 internal structure
            // in every location by default and are handled elsewhere, so they get a bye.
            if (!(te instanceof Aero) && (te.getInternal(hit) <= 0)) {
                te.destroyLocation(hit.getLocation());

                // Check for possible engine destruction here
                if ((te instanceof Mech)
                        && ((hit.getLocation() == Mech.LOC_RT) || (hit.getLocation() == Mech.LOC_LT))) {

                    int numEngineHits = te.getEngineHits();
                    boolean engineExploded = checkEngineExplosion(te, vDesc, numEngineHits);

                    int hitsToDestroy = 3;
                    if ((te instanceof Mech) && te.isSuperHeavy() && te.hasEngine()
                            && (te.getEngine().getEngineType() == Engine.COMPACT_ENGINE)) {
                        hitsToDestroy = 2;
                    }

                    if (!engineExploded && (numEngineHits >= hitsToDestroy)) {
                        // third engine hit
                        vDesc.addAll(entityManager.destroyEntity(te, "engine destruction"));
                        if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_AUTO_ABANDON_UNIT)) {
                            vDesc.addAll(abandonEntity(te));
                        }
                        te.setSelfDestructing(false);
                        te.setSelfDestructInitiated(false);
                    }

                    // Torso destruction in airborne LAM causes immediate crash.
                    if ((te instanceof LandAirMech) && !te.isDestroyed() && !te.isDoomed()) {
                        r = new Report(9710);
                        r.subject = te.getId();
                        r.addDesc(te);
                        if (te.isAirborneVTOLorWIGE()) {
                            vDesc.add(r);
                            crashAirMech(te, new PilotingRollData(te.getId(), TargetRoll.AUTOMATIC_FAIL,
                                    "side torso destroyed"));
                        } else if (te.isAirborne() && te.isAero()) {
                            vDesc.add(r);
                            vDesc.addAll(processCrash(te, ((IAero)te).getCurrentVelocity(), te.getPosition()));
                        }
                    }
                }

            }

            // If damage remains, loop to next location; if not, be sure to stop
            // here because we may need to refer back to the last *damaged*
            // location again later. (This is safe because at damage <= 0 the
            // loop terminates anyway.)
            if (damage > 0) {
                hit = nextHit;
                // Need to update armor status for the new location
                hardenedArmor = ((te instanceof Mech) || (te instanceof Tank)) && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_HARDENED);
                ferroLamellorArmor = ((te instanceof Mech) || (te instanceof Tank) || (te instanceof Aero)) && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_FERRO_LAMELLOR);
                reflectiveArmor = (((te instanceof Mech) || (te instanceof Tank) || (te instanceof Aero))
                        && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_REFLECTIVE))
                        || (isBattleArmor && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_BA_REFLECTIVE));
                reactiveArmor = (((te instanceof Mech) || (te instanceof Tank) || (te instanceof Aero))
                        && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_REACTIVE))
                        || (isBattleArmor && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_BA_REACTIVE));
                ballisticArmor = ((te instanceof Mech) || (te instanceof Tank) || (te instanceof Aero))
                        && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_BALLISTIC_REINFORCED);
                impactArmor = (te instanceof Mech)
                        && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_IMPACT_RESISTANT);
            }
            if (damageIS) {
                wasDamageIS = true;
                damageIS = false;
            }
        }
        // Mechs using EI implants take pilot damage each time a hit inflicts IS damage
        if (tookInternalDamage
            && ((te instanceof Mech) || (te instanceof Protomech))
            && te.hasActiveEiCockpit()) {
            Report.addNewline(vDesc);
            int roll = Compute.d6(2);
            r = new Report(5075);
            r.subject = te.getId();
            r.addDesc(te);
            r.add(7);
            r.add(roll);
            r.choose(roll >= 7);
            r.indent(2);
            vDesc.add(r);
            if (roll < 7) {
                vDesc.addAll(damageCrew(te, 1));
            }
        }

        // if using VDNI (but not buffered), check for damage on an internal hit
        if (tookInternalDamage
            && te.hasAbility(OptionsConstants.MD_VDNI)
            && !te.hasAbility(OptionsConstants.MD_BVDNI)
            && !te.hasAbility(OptionsConstants.MD_PAIN_SHUNT)) {
            Report.addNewline(vDesc);
            int roll = Compute.d6(2);
            r = new Report(3580);
            r.subject = te.getId();
            r.addDesc(te);
            r.add(7);
            r.add(roll);
            r.choose(roll >= 8);
            r.indent(2);
            vDesc.add(r);
            if (roll >= 8) {
                vDesc.addAll(damageCrew(te, 1));
            }
        }

        // TacOps p.78 Ammo booms can hurt other units in same and adjacent hexes
        // But, this does not apply to CASE'd units and it only applies if the
        // ammo explosion
        // destroyed the unit
        if (ammoExplosion && game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_AMMUNITION)
            // For 'Mechs we care whether there was CASE specifically in the
            // location that went boom...
            && !(te.locationHasCase(hit.getLocation()) || te.hasCASEII(hit.getLocation()))
            // ...but vehicles and ASFs just have one CASE item for the
            // whole unit, so we need to look whether there's CASE anywhere
            // at all.
            && !(((te instanceof Tank) || (te instanceof Aero)) && te.hasCase()) && (te.isDestroyed() || te.isDoomed())
            && (damage_orig > 0) && ((damage_orig / 10) > 0)) {
            Report.addNewline(vDesc);
            r = new Report(5068, Report.PUBLIC);
            r.subject = te.getId();
            r.addDesc(te);
            r.indent(2);
            vDesc.add(r);
            Report.addNewline(vDesc);
            r = new Report(5400, Report.PUBLIC);
            r.subject = te.getId();
            r.indent(2);
            vDesc.add(r);
            int[] damages = {(int) Math.floor(damage_orig / 10.0), (int) Math.floor(damage_orig / 20.0)};
            doExplosion(damages, false, te.getPosition(), true, vDesc,
                    null, 5, te.getId(), false);
            Report.addNewline(vDesc);
            r = new Report(5410, Report.PUBLIC);
            r.subject = te.getId();
            r.indent(2);
            vDesc.add(r);
        }

        // This flag indicates the hit was directly to IS
        if (wasDamageIS) {
            Report.addNewline(vDesc);
        }
        return vDesc;
    }

    private Vector<Report> damageFighterSquadron(Entity te, HitData hit, int damage, boolean ammoExplosion,
                                                 DamageType bFrag, boolean damageIS, boolean areaSatArty,
                                                 boolean throughFront, boolean underWater, boolean nukeS2S){
        if(te.getActiveSubEntities().orElse(Collections.emptyList()).isEmpty()) {
            return new Vector<>();
        }
        List<Entity> fighters = te.getSubEntities().orElse(Collections.emptyList());
        Entity fighter = fighters.get(hit.getLocation());
        HitData new_hit = fighter.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
        new_hit.setBoxCars(hit.rolledBoxCars());
        new_hit.setGeneralDamageType(hit.getGeneralDamageType());
        new_hit.setCapital(hit.isCapital());
        new_hit.setCapMisCritMod(hit.getCapMisCritMod());
        new_hit.setSingleAV(hit.getSingleAV());
        new_hit.setAttackerId(hit.getAttackerId());
        return damageEntity(fighter, new_hit, damage, ammoExplosion, bFrag, damageIS, areaSatArty, throughFront,
                underWater, nukeS2S);
    }

    private Vector<Report> damageBattleArmor(Entity te, HitData hit, int damage, boolean ammoExplosion,
                                             DamageType bFrag, boolean damageIS, boolean throughFront,
                                             boolean underWater, boolean nukeS2S){
        Vector<Report> vDesc = new Vector<>();
        Report r = new Report(6044);
        r.subject = te.getId();
        r.indent(2);
        vDesc.add(r);
        for (int i = 0; i < ((BattleArmor) te).getTroopers(); i++) {
            hit.setLocation(BattleArmor.LOC_TROOPER_1 + i);
            if (te.getInternal(hit) > 0) {
                vDesc.addAll(damageEntity(te, hit, damage, ammoExplosion, bFrag, damageIS, false, throughFront, underWater, nukeS2S));
            }
        }
        return vDesc;
    }

    /**
     * Apply damage to an Entity carrying external Battle Armor or ProtoMech
     * when a location with a trooper present is hit.
     *
     * @param te             The carrying Entity
     * @param hit            The hit to resolve
     * @param damage         The amount of damage to be allocated
     * @param vDesc          The report vector
     * @param passenger      The BA squad
     * @return               The amount of damage remaining
     */
    private int damageExternalPassenger(Entity te, HitData hit, int damage, Vector<Report> vDesc, Entity passenger) {
        Report r;
        int passengerDamage = damage;
        int avoidRoll = Compute.d6();
        HitData passHit = passenger.getTrooperAtLocation(hit, te);
        if (passenger.hasETypeFlag(Entity.ETYPE_PROTOMECH)) {
            passengerDamage -= damage / 2;
            passHit = passenger.rollHitLocation(ToHitData.HIT_SPECIAL_PROTO, ToHitData.SIDE_FRONT);
        } else if (avoidRoll < 5) {
            passengerDamage = 0;
        }
        passHit.setGeneralDamageType(hit.getGeneralDamageType());

        if (passengerDamage > 0) {
            // Yup. Roll up some hit data for that passenger.
            r = new Report(6075);
            r.subject = passenger.getId();
            r.indent(3);
            r.addDesc(passenger);
            vDesc.addElement(r);

            // How much damage will the passenger absorb?
            int absorb = 0;
            HitData nextPassHit = passHit;
            do {
                int armorType = passenger.getArmorType(nextPassHit.getLocation());
                boolean armorDamageReduction = ((armorType == EquipmentType.T_ARMOR_BA_REACTIVE)
                        && ((hit.getGeneralDamageType() == HitData.DAMAGE_MISSILE)))
                        || (hit.getGeneralDamageType() == HitData.DAMAGE_ARMOR_PIERCING_MISSILE);
                // Check for reflective armor
                if ((armorType == EquipmentType.T_ARMOR_BA_REFLECTIVE)
                    && (hit.getGeneralDamageType() == HitData.DAMAGE_ENERGY)) {
                    armorDamageReduction = true;
                }
                if (0 < passenger.getArmor(nextPassHit)) {
                    absorb += passenger.getArmor(nextPassHit);
                    if (armorDamageReduction) {
                        absorb *= 2;
                    }
                }
                if (0 < passenger.getInternal(nextPassHit)) {
                    absorb += passenger.getInternal(nextPassHit);
                    // Armor damage reduction, like for reflective or
                    // reactive armor will divide the whole damage
                    // total by 2 and round down. If we have an odd
                    // damage total, need to add 1 to make this
                    // evenly divisible by 2
                    if (((absorb % 2) != 0) && armorDamageReduction) {
                        absorb++;
                    }
                }
                nextPassHit = passenger.getTransferLocation(nextPassHit);
            } while ((damage > absorb) && (nextPassHit.getLocation() >= 0));

            // Damage the passenger.
            absorb = Math.min(passengerDamage, absorb);
            Vector<Report> newReports = damageEntity(passenger, passHit, absorb);
            for (Report newReport : newReports) {
                newReport.indent(2);
            }
            vDesc.addAll(newReports);

            // Did some damage pass on?
            if (damage > absorb) {
                // Yup. Remove the absorbed damage.
                damage -= absorb;
                r = new Report(6080);
                r.subject = te.getId();
                r.indent(2);
                r.add(damage);
                r.addDesc(te);
                vDesc.addElement(r);
            } else {
                // Nope. Return our description.
                return 0;
            }
        } else {
            // Report that a passenger that could've been missed narrowly avoids damage
            r = new Report(6084);
            r.subject = passenger.getId();
            r.indent(3);
            r.addDesc(passenger);
            vDesc.addElement(r);
        } // End nLoc-has-exterior-passenger
        if (passenger.hasETypeFlag(Entity.ETYPE_PROTOMECH) && (passengerDamage > 0) && !passenger.isDoomed() && !passenger.isDestroyed()) {
            r = new Report(3850);
            r.subject = passenger.getId();
            r.indent(3);
            r.addDesc(passenger);
            vDesc.addElement(r);
            int facing = te.getFacing();
            // We're going to assume that it's mounted facing the mech
            Coords position = te.getPosition();
            if (!hit.isRear()) {
                facing = (facing + 3) % 6;
            }
            unloadUnit(te, passenger, position, facing, te.getElevation(), false, false);
            Entity violation = Compute.stackingViolation(game, passenger.getId(), position);
            if (violation != null) {
                Coords targetDest = Compute.getValidDisplacement(game, passenger.getId(), position, Compute.d6() - 1);
                reportmanager.addReport(doEntityDisplacement(violation, position, targetDest, null));
                // Update the violating entity's position on the client.
                entityManager.entityUpdate(violation.getId());
            }
        }
        return damage;
    }

    /**
     * Check to see if the entity's engine explodes. Rules for ICE explosions
     * are different to fusion engines.
     *
     * @param en    - the <code>Entity</code> in question. This value must not be
     *              <code>null</code>.
     * @param vDesc - the <code>Vector</code> that this function should add its
     *              <code>Report<code>s to.  It may be empty, but not
     *              <code>null</code>.
     * @param hits  - the number of criticals on the engine
     * @return <code>true</code> if the unit's engine exploded,
     * <code>false</code> if not.
     */
    private boolean checkEngineExplosion(Entity en, Vector<Report> vDesc, int hits) {
        if (!(en instanceof Mech) && !(en instanceof Aero) && !(en instanceof Tank)) {
            return false;
        }
        // If this method gets called for an entity that's already destroyed or
        // that hasn't taken any actual engine hits this phase yet, do nothing.
        if (en.isDestroyed() || (en.engineHitsThisPhase <= 0) || en.getSelfDestructedThisTurn() || !en.hasEngine()) {
            return false;
        }
        int explosionBTH = 10;
        int hitsPerRound = 4;
        Engine engine = en.getEngine();

        if (!(en instanceof Mech)) {
            explosionBTH = 12;
            hitsPerRound = 1;
        }

        // Non mechs and mechs that already rolled are safe
        if (en.rolledForEngineExplosion || !(en instanceof Mech)) {
            return false;
        }
        // ICE can always explode and roll every time hit
        if (engine.isFusion() && (!game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_ENGINE_EXPLOSIONS)
                || (en.engineHitsThisPhase < hitsPerRound))) {
            return false;
        }
        if (!engine.isFusion()) {
            switch (hits) {
                case 0:
                    return false;
                case 1:
                    explosionBTH = 10;
                    break;
                case 2:
                    explosionBTH = 7;
                    break;
                case 3:
                default:
                    explosionBTH = 4;
                    break;
            }
        }
        int explosionRoll = Compute.d6(2);
        boolean didExplode = explosionRoll >= explosionBTH;

        Report r = new Report(6150);
        r.subject = en.getId();
        r.indent(2);
        r.addDesc(en);
        r.add(en.engineHitsThisPhase);
        vDesc.addElement(r);
        r = new Report(6155);
        r.subject = en.getId();
        r.indent(2);
        r.add(explosionBTH);
        r.add(explosionRoll);
        vDesc.addElement(r);

        if (!didExplode) {
            // whew!
            if (engine.isFusion()) {
                en.rolledForEngineExplosion = true;
            }
            // fusion engines only roll 1/phase but ICE roll every time damaged
            r = new Report(6160);
            r.subject = en.getId();
            r.indent(2);
            vDesc.addElement(r);
        } else {
            en.rolledForEngineExplosion = true;
            r = new Report(6165, Report.PUBLIC);
            r.subject = en.getId();
            r.indent(2);
            vDesc.addElement(r);
            vDesc.addAll(entityManager.destroyEntity(en, "engine explosion", false, false));
            // kill the crew
            en.getCrew().setDoomed(true);

            // This is a hack so MM.NET marks the mech as not salvageable
            en.destroyLocation(Mech.LOC_CT);

            // ICE explosions don't hurt anyone else, but fusion do
            if (engine.isFusion()) {
                int engineRating = en.getEngine().getRating();
                Report.addNewline(vDesc);
                r = new Report(5400, Report.PUBLIC);
                r.subject = en.getId();
                r.indent(2);
                vDesc.add(r);

                Mech mech = (Mech) en;
                if (mech.isAutoEject() && (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                        || (game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                        && mech.isCondEjectEngine()))) {
                    vDesc.addAll(ejectEntity(en, true));
                }

                doFusionEngineExplosion(engineRating, en.getPosition(), vDesc, null);
                Report.addNewline(vDesc);
                r = new Report(5410, Report.PUBLIC);
                r.subject = en.getId();
                r.indent(2);
                vDesc.add(r);

            }
        }
        return didExplode;
    }

    /**
     * Extract explosion functionality for generalized explosions in areas.
     */
    public void doFusionEngineExplosion(int engineRating, Coords position, Vector<Report> vDesc, Vector<Integer> vUnits) {
        int[] myDamages = { engineRating, (engineRating / 10), (engineRating / 20), (engineRating / 40) };
        doExplosion(myDamages, true, position, false, vDesc, vUnits, 5, -1, true);
    }

    /**
     * General function to cause explosions in areas.
     */
    public void doExplosion(int damage, int degradation, boolean autoDestroyInSameHex, Coords position,
                            boolean allowShelter, Vector<Report> vDesc, Vector<Integer> vUnits, int excludedUnitId) {
        if (degradation < 1) {
            return;
        }

        int[] myDamages = new int[damage / degradation];

        if (myDamages.length < 1) {
            return;
        }

        myDamages[0] = damage;
        for (int x = 1; x < myDamages.length; x++) {
            myDamages[x] = myDamages[x - 1] - degradation;
        }
        doExplosion(myDamages, autoDestroyInSameHex, position, allowShelter, vDesc, vUnits,
                5, excludedUnitId, false);
    }

    /**
     * General function to cause explosions in areas.
     */
    public void doExplosion(int[] damages, boolean autoDestroyInSameHex, Coords position, boolean allowShelter,
                            Vector<Report> vDesc, Vector<Integer> vUnits, int clusterAmt, int excludedUnitId,
                            boolean engineExplosion) {
        if (vDesc == null) {
            vDesc = new Vector<>();
        }

        if (vUnits == null) {
            vUnits = new Vector<>();
        }

        Report r;
        HashSet<Entity> entitiesHit = new HashSet<>();

        // We need to damage buildings.
        Enumeration<Building> buildings = game.getBoard().getBuildings();
        while (buildings.hasMoreElements()) {
            final Building bldg = buildings.nextElement();
            // Lets find the closest hex from the building.
            Vector<Coords> hexes = bldg.getCoordsVector();
            for (Coords coords : hexes) {
                int dist = position.distance(coords);
                if (dist < damages.length) {
                    Vector<Report> buildingReport = damageBuilding(bldg, damages[dist], coords);
                    for (Report report : buildingReport) {
                        report.type = Report.PUBLIC;
                    }
                    vDesc.addAll(buildingReport);
                }
            }
        }

        // We need to damage terrain
        int maxDist = damages.length;
        IHex hex = game.getBoard().getHex(position);
        // Center hex starts on fire for engine explosions
        if (engineExplosion && (hex != null) && !hex.containsTerrain(Terrains.FIRE)) {
            r = new Report(5136, Report.PUBLIC);
            r.indent(2);
            r.add(position.getBoardNum());
            vDesc.add(r);
            Vector<Report> reports = new Vector<>();
            ignite(position, Terrains.FIRE_LVL_NORMAL, reports);
            for (Report report : reports) {
                report.indent();
            }
            vDesc.addAll(reports);
        }
        if ((hex != null) && hex.hasTerrainfactor()) {
            r = new Report(3384, Report.PUBLIC);
            r.indent(2);
            r.add(position.getBoardNum());
            r.add(damages[0]);
            vDesc.add(r);
        }
        Vector<Report> reports = tryClearHex(position, damages[0], Entity.NONE);
        for (Report report : reports) {
            report.indent(3);
        }
        vDesc.addAll(reports);

        // Handle surrounding coords
        for (int dist = 1; dist < maxDist; dist++) {
            List<Coords> coords = position.allAtDistance(dist);
            for (Coords c : coords) {
                hex = game.getBoard().getHex(c);
                if ((hex != null) && hex.hasTerrainfactor()) {
                    r = new Report(3384, Report.PUBLIC);
                    r.indent(2);
                    r.add(c.getBoardNum());
                    r.add(damages[dist]);
                    vDesc.add(r);
                }
                reports = tryClearHex(c, damages[dist], Entity.NONE);
                for (Report report : reports) {
                    report.indent(3);
                }
                vDesc.addAll(reports);
            }
        }

        // Now we damage people near the explosion.
        List<Entity> loaded = new ArrayList<>();
        for (Iterator<Entity> ents = game.getEntities(); ents.hasNext();) {
            Entity entity = ents.next();

            if (entitiesHit.contains(entity) || (entity.getId() == excludedUnitId)) {
                continue;
            }

            if (entity.isDestroyed() || !entity.isDeployed()) {
                // FIXME: Is this the behavior we want? This means, incidentally, that salvage is never affected by
                // explosions as long as it was destroyed before the explosion.
                continue;
            }

            // We are going to assume that explosions are on the ground here so
            // flying entities should be unaffected
            if (entity.isAirborne()
                    // MechWarrior is still up in the air ejecting hence safe from this explosion.
                    || ((entity instanceof MechWarrior) && !((MechWarrior) entity).hasLanded())) {
                continue;
            }

            Coords entityPos = entity.getPosition();
            if (entityPos == null) {
                // maybe its loaded?
                Entity transport = game.getEntity(entity.getTransportId());
                if ((transport != null) && !transport.isAirborne()) {
                    loaded.add(entity);
                }
                continue;
            }
            int range = position.distance(entityPos);

            if (range >= damages.length) {
                // Yeah, this is fine. It's outside the blast radius.
                continue;
            }

            // We might need to nuke everyone in the explosion hex. If so...
            if ((range == 0) && autoDestroyInSameHex) {
                // Add the reports
                vDesc.addAll(entityManager.destroyEntity(entity, "explosion proximity", false, false));
                // Add it to the "blasted units" list
                vUnits.add(entity.getId());
                // Kill the crew
                entity.getCrew().setDoomed(true);

                entitiesHit.add(entity);
                continue;
            }

            int damage = damages[range];

            if (allowShelter && canShelter(entityPos, position, entity.relHeight())) {
                if (gamemanager.isSheltered()) {
                    r = new Report(6545);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    vDesc.addElement(r);
                    continue;
                }
                // If shelter is allowed but didn't work, report that.
                r = new Report(6546);
                r.subject = entity.getId();
                r.addDesc(entity);
                vDesc.addElement(r);
            }

            // Since it's taking damage, add it to the list of units hit.
            vUnits.add(entity.getId());

            AreaEffectHelper.applyExplosionClusterDamageToEntity(entity, damage, clusterAmt, position, vDesc, this);
            
            Report.addNewline(vDesc);
        }

        // now deal with loaded units...
        for (Entity e : loaded) {
            // This can be null, if the transport died from damage
            final Entity transporter = game.getEntity(e.getTransportId());
            if ((transporter == null) || transporter.getExternalUnits().contains(e)) {
                // Its external or transport was destroyed - hit it.
                final Coords entityPos = (transporter == null ? e.getPosition() : transporter.getPosition());
                final int range = position.distance(entityPos);

                if (range >= damages.length) {
                    // Yeah, this is fine. It's outside the blast radius.
                    continue;
                }

                int damage = damages[range];
                if (allowShelter) {
                    final int absHeight = (transporter == null ? e.relHeight() : transporter.relHeight());
                    if (canShelter(entityPos, position, absHeight)) {
                        if (gamemanager.isSheltered()) {
                            r = new Report(6545);
                            r.addDesc(e);
                            r.subject = e.getId();
                            vDesc.addElement(r);
                            continue;
                        }
                        // If shelter is allowed but didn't work, report that.
                        r = new Report(6546);
                        r.subject = e.getId();
                        r.addDesc(e);
                        vDesc.addElement(r);
                    }
                }
                // No shelter
                // Since it's taking damage, add it to the list of units hit.
                vUnits.add(e.getId());

                r = new Report(6175);
                r.subject = e.getId();
                r.indent(2);
                r.addDesc(e);
                r.add(damage);
                vDesc.addElement(r);

                while (damage > 0) {
                    int cluster = Math.min(5, damage);
                    int table = ToHitData.HIT_NORMAL;
                    if (e instanceof Protomech) {
                        table = ToHitData.HIT_SPECIAL_PROTO;
                    }
                    HitData hit = e.rollHitLocation(table, ToHitData.SIDE_FRONT);
                    vDesc.addAll(damageEntity(e, hit, cluster, false,
                            DamageType.IGNORE_PASSENGER, false, true));
                    damage -= cluster;
                }
                Report.addNewline(vDesc);
            }
        }
    }

    /**
     * Check if an Entity of the passed height can find shelter from a nuke blast
     *
     * @param entityPosition  the <code>Coords</code> the Entity is at
     * @param position        the <code>Coords</code> of the explosion
     * @param entityAbsHeight the <code>int</code> height of the entity
     * @return a <code>boolean</code> value indicating if the entity of the
     * given height can find shelter
     */
    public boolean canShelter(Coords entityPosition, Coords position, int entityAbsHeight) {
        // What is the next hex in the direction of the blast?
        Coords shelteringCoords = Coords.nextHex(entityPosition, position);
        IHex shelteringHex = game.getBoard().getHex(shelteringCoords);

        // This is an error condition. It really shouldn't ever happen.
        if (shelteringHex == null) {
            return false;
        }

        // Determine the shelter level based on the terrain and building presence
        int shelterLevel = shelteringHex.containsTerrain(Terrains.BUILDING) ? shelteringHex.ceiling() : shelteringHex.floor();

        // Get the absolute height of the unit relative to level 0.
        entityAbsHeight += game.getBoard().getHex(entityPosition).surface();

        // Now find the height that needs to be sheltered, and compare.
        return entityAbsHeight < shelterLevel;
    }

    /**
     * explode any scheduled nukes
     */
    private void resolveScheduledNukes() {
        for (int[] nuke : game.getScheduledNukes()) {
            if (nuke.length == 3) {
                doNuclearExplosion(new Coords(nuke[0] - 1, nuke[1] - 1), nuke[2], reportmanager.getvPhaseReport());
            }
            if (nuke.length == 6) {
                doNuclearExplosion(new Coords(nuke[0] - 1, nuke[1] - 1), nuke[2], nuke[3], nuke[4], nuke[5], reportmanager.getvPhaseReport());
            }
        }
        game.setScheduledNukes(new ArrayList<>());
    }

    /**
     * do a nuclear explosion
     *
     * @param position the position that will be hit by the nuke
     * @param nukeType the type of nuke
     * @param vDesc    a vector that contains the output report
     */
    public void doNuclearExplosion(Coords position, int nukeType, Vector<Report> vDesc) {
        NukeStats nukeStats = AreaEffectHelper.getNukeStats(nukeType);

        if(nukeStats == null) {
            MegaMek.getLogger().error("Illegal nuke not listed in HS:3070");
        }

        doNuclearExplosion(position, nukeStats.baseDamage, nukeStats.degradation, nukeStats.secondaryRadius,
                nukeStats.craterDepth, vDesc);        
    }

    /**
     * explode a nuke
     *
     * @param position          the position that will be hit by the nuke
     * @param baseDamage        the base damage from the blast
     * @param degradation       how fast the blast's power degrades
     * @param secondaryRadius   the secondary blast radius
     * @param craterDepth       the depth of the crater created by the blast
     * @param vDesc             a vector that contains the output report
     */
    public void doNuclearExplosion(Coords position, int baseDamage, int degradation, int secondaryRadius,
                                   int craterDepth, Vector<Report> vDesc) {
        // Just in case.
        if (vDesc == null) {
            vDesc = new Vector<>();
        }

        // First, crater the terrain.
        // All terrain, units, buildings... EVERYTHING in here is just gone.
        // Gotta love nukes.
        Report r = new Report(1215, Report.PUBLIC);
        r.indent();
        r.add(position.getBoardNum(), true);
        vDesc.add(r);

        int curDepth = craterDepth;
        int range = 0;
        while (range < (2 * craterDepth)) {
            // Get the set of hexes at this range.
            List<Coords> hexSet = position.allAtDistance(range);

            // Iterate through the hexes.
            for (Coords myHexCoords: hexSet) {
                // ignore out of bounds coordinates
                if (!game.getBoard().contains(myHexCoords)) {
                    continue;
                }
                
                IHex myHex = game.getBoard().getHex(myHexCoords);
                // In each hex, first, sink the terrain if necessary.
                myHex.setLevel((myHex.getLevel() - curDepth));

                // Then, remove ANY terrains here.
                // I mean ALL of them; they're all just gone.
                // No ruins, no water, no rough, no nothing.
                if (myHex.containsTerrain(Terrains.WATER)) {
                    myHex.setLevel(myHex.floor());
                }
                myHex.removeAllTerrains();
                myHex.clearExits();

                gamemanager.sendChangedHex(game, myHexCoords);
            }

            // Lastly, if the next distance is a multiple of 2...
            // The crater depth goes down one.
            if ((range > 0) && ((range % 2) == 0)) {
                curDepth--;
            }
            // Now that the hexes are dealt with, increment the distance.
            range++;
        }

        // This is technically part of cratering, but...
        // Now we destroy all the units inside the cratering range.
        for (Entity entity : game.getEntitiesVector()) {
            // loaded units and off board units don't have a position,
            // so we don't count 'em here
            if ((entity.getTransportId() != Entity.NONE)
                    || (entity.getPosition() == null)
                    // If it's too far away for this...
                    || (position.distance(entity.getPosition()) >= range)
                    // If it's already destroyed...
                    || entity.isDestroyed()) {
                continue;
            }

            vDesc.addAll(entityManager.destroyEntity(entity, "nuclear explosion proximity",
                    false, false));
            // Kill the crew
            entity.getCrew().setDoomed(true);
        }

        // Then, do actual blast damage.
        // Use the standard blast function for this.
        Vector<Report> tmpV = new Vector<>();
        Vector<Integer> blastedUnitsVec = new Vector<>();
        doExplosion(baseDamage, degradation, true, position, true, tmpV,
                    blastedUnitsVec, -1);
        Report.indentAll(tmpV, 2);
        vDesc.addAll(tmpV);

        // Everything that was blasted by the explosion has to make a piloting
        // check at +6.
        for (int i : blastedUnitsVec) {
            Entity o = game.getEntity(i);
            if (o.canFall()) {
                // Needs a piloting check at +6 to avoid falling over.
                game.addPSR(new PilotingRollData(o.getId(), 6,
                        "hit by nuclear blast"));
            } else if (o instanceof VTOL) {
                // Needs a piloting check at +6 to avoid crashing.
                // Wheeeeee!
                VTOL vt = (VTOL) o;

                // Check only applies if it's in the air.
                // FIXME: is this actually correct? What about
                // buildings/bridges?
                if (vt.getElevation() > 0) {
                    game.addPSR(new PilotingRollData(vt.getId(), 6, "hit by nuclear blast"));
                }
            } else if (o instanceof Tank) {
                // As per official answer on the rules questions board...
                // Needs a piloting check at +6 to avoid a 1-level fall...
                // But ONLY if a hover-tank.
                // TODO : Fix me
            }
        }

        // This ISN'T part of the blast, but if there's ANYTHING in the ground
        // zero hex, destroy it.
        Building tmpB = game.getBoard().getBuildingAt(position);
        if (tmpB != null) {
            r = new Report(2415);
            r.add(tmpB.getName());
            reportmanager.addReport(r);
            tmpB.setCurrentCF(0, position);
        }
        IHex gzHex = game.getBoard().getHex(position);
        if (gzHex.containsTerrain(Terrains.WATER)) {
            gzHex.setLevel(gzHex.floor());
        }
        gzHex.removeAllTerrains();

        // Next, for whatever's left, do terrain effects
        // such as clearing, roughing, and boiling off water.
        boolean damageFlag = true;
        int damageAtRange = baseDamage - (degradation * range);
        if (damageAtRange > 0) {
            for (int x = range; damageFlag; x++) {
                // Damage terrain as necessary.
                // Get all the hexes, and then iterate through them.
                List<Coords> hexSet = position.allAtDistance(x);

                // Iterate through the hexes.
                for (Coords myHexCoords : hexSet) {
                    // ignore out of bounds coordinates
                    if (!game.getBoard().contains(myHexCoords)) {
                        continue;
                    }
                    
                    IHex myHex = game.getBoard().getHex(myHexCoords);

                    // For each 3000 damage, water level is reduced by 1.
                    if ((damageAtRange >= 3000) && (myHex.containsTerrain(Terrains.WATER))) {
                        int numCleared = damageAtRange / 3000;
                        int oldLevel = myHex.terrainLevel(Terrains.WATER);
                        myHex.removeTerrain(Terrains.WATER);
                        if (oldLevel > numCleared) {
                            myHex.setLevel(myHex.getLevel() - numCleared);
                            myHex.addTerrain(new Terrain(Terrains.WATER, oldLevel - numCleared));
                        } else {
                            myHex.setLevel(myHex.getLevel() - oldLevel);
                        }
                    }

                    // ANY non-water hex that takes 200 becomes rough.
                    if ((damageAtRange >= 200) && (!myHex.containsTerrain(Terrains.WATER))) {
                        myHex.removeAllTerrains();
                        myHex.clearExits();
                        myHex.addTerrain(new Terrain(Terrains.ROUGH, 1));
                    } else if ((damageAtRange >= 20)
                            && ((myHex.containsTerrain(Terrains.WOODS))
                            || (myHex.containsTerrain(Terrains.JUNGLE)))) {
                        // Each 20 clears woods by 1 level.
                        int numCleared = damageAtRange / 20;
                        int terrainType = (myHex.containsTerrain(Terrains.WOODS) ? Terrains.WOODS : Terrains.JUNGLE);
                        int oldLevel = myHex.terrainLevel(terrainType);
                        myHex.removeTerrain(terrainType);
                        if (oldLevel > numCleared) {
                            myHex.addTerrain(new Terrain(terrainType, oldLevel - numCleared));
                            if (myHex.terrainLevel(Terrains.FOLIAGE_ELEV) != 1) {
                                myHex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, oldLevel - numCleared == 3 ? 3 : 2));
                            }
                        } else {
                            myHex.removeTerrain(Terrains.FOLIAGE_ELEV);
                        }
                    }

                    gamemanager.sendChangedHex(game, myHexCoords);
                }

                // Initialize for the next iteration.
                damageAtRange = baseDamage - ((degradation * x) + 1);

                // If the damage is less than 20, it has no terrain effect.
                if (damageAtRange < 20) {
                    damageFlag = false;
                }
            }
        }

        // Lastly, do secondary effects.
        for (Entity entity : game.getEntitiesVector()) {
            // loaded units and off board units don't have a position,
            // so we don't count 'em here
            if (((entity.getTransportId() != Entity.NONE) || (entity.getPosition() == null))
                    // If it's already destroyed...
                    || ((entity.isDoomed()) || (entity.isDestroyed()))
                    // If it's too far away for this...
                    || (position.distance(entity.getPosition()) > secondaryRadius)) {
                continue;
            }

            // Actually do secondary effects against it.
            // Since the effects are unit-dependant, we'll just define it in the entity.
            applySecondaryNuclearEffects(entity, position, vDesc);
        }

        // All right. We're done.
        r = new Report(1216, Report.PUBLIC);
        r.indent();
        r.newlines = 2;
        vDesc.add(r);
    }

    /**
     * Handles secondary effects from nuclear blasts against all units in range.
     *
     * @param entity   The entity to affect.
     * @param position The coordinates of the nuclear blast, for to-hit directions.
     * @param vDesc    a description vector to use for reports.
     */
    public void applySecondaryNuclearEffects(Entity entity, Coords position, Vector<Report> vDesc) {
        // If it's already destroyed, give up. We really don't care.
        if (entity.isDestroyed()) {
            return;
        }

        // Check to see if the infantry is in a protective structure.
        boolean inHardenedBuilding = (Compute.isInBuilding(game, entity)
                && (game.getBoard().getHex(entity.getPosition()).terrainLevel(Terrains.BUILDING) == 4));

        // Roll 2d6.
        int roll = Compute.d6(2);

        Report r = new Report(6555);
        r.subject = entity.getId();
        r.add(entity.getDisplayName());
        r.add(roll);

        // TODO (Sam): An add of "" does nothing normally but test this
        // If they are in protective structure, add 2 to the roll.
        if (inHardenedBuilding) {
            roll += 2;
            r.add(" + 2 (unit is in hardened building)");
        } else {
            r.add("");
        }

        // Also, if the entity is "hardened" against EMI, it gets a +2.
        // For these purposes, I'm going to hand this off to the Entity itself
        // to tell us.
        // Right now, it IS based purely on class, but I won't rule out the idea
        // of
        // "nuclear hardening" as equipment for a support vehicle, for example.
        if (entity.isNuclearHardened()) {
            roll += 2;
            r.add(" + 2 (unit is hardened against EMI)");
        } else {
            r.add("");
        }

        r.indent(2);
        vDesc.add(r);

        // Now, compare it to the table, and apply the effects.
        if (roll <= 4) {
            // The unit is destroyed.
            // Sucks, doesn't it?
            // This applies to all units.
            // Yup, just sucks.
            vDesc.addAll(entityManager.destroyEntity(entity, "nuclear explosion secondary effects", false, false));
            // Kill the crew
            entity.getCrew().setDoomed(true);
        } else if (roll <= 6) {
            if (entity instanceof BattleArmor) {
                // It takes 50% casualties, rounded up.
                BattleArmor myBA = (BattleArmor) entity;
                int numDeaths = (int) (Math.ceil((myBA.getNumberActiverTroopers())) / 2.0);
                for (int x = 0; x < numDeaths; x++) {
                    vDesc.addAll(applyCriticalHit(entity, 0, null, false, 0, false));
                }
            } else if (entity instanceof Infantry) {
                // Standard infantry are auto-killed in this band, unless
                // they're in a building.
                if (game.getBoard().getHex(entity.getPosition()).containsTerrain(Terrains.BUILDING)) {
                    // 50% casualties, rounded up.
                    int damage = (int) (Math.ceil((entity.getInternal(Infantry.LOC_INFANTRY)) / 2.0));
                    vDesc.addAll(damageEntity(entity, new HitData(Infantry.LOC_INFANTRY), damage, true));
                } else {
                    vDesc.addAll(entityManager.destroyEntity(entity, "nuclear explosion secondary effects", false, false));
                    entity.getCrew().setDoomed(true);
                }
            } else if (entity instanceof Tank) {
                // TODO (Sam): Does this need to be defined twice???
                // All vehicles suffer two critical hits...
                HitData hd = entity.rollHitLocation(ToHitData.HIT_NORMAL, entity.sideTable(position));
                vDesc.addAll(oneCriticalEntity(entity, hd.getLocation(), hd.isRear(), 0));
                hd = entity.rollHitLocation(ToHitData.HIT_NORMAL, entity.sideTable(position));
                vDesc.addAll(oneCriticalEntity(entity, hd.getLocation(), hd.isRear(), 0));

                // ...and a Crew Killed hit.
                vDesc.addAll(applyCriticalHit(entity, 0, new CriticalSlot(0, Tank.CRIT_CREW_KILLED), false, 0, false));
            } else if ((entity instanceof Mech) || (entity instanceof Protomech)) {
                // 'Mechs suffer two critical hits...
                HitData hd = entity.rollHitLocation(ToHitData.HIT_NORMAL, entity.sideTable(position));
                vDesc.addAll(oneCriticalEntity(entity, hd.getLocation(), hd.isRear(), 0));
                hd = entity.rollHitLocation(ToHitData.HIT_NORMAL, entity.sideTable(position));
                vDesc.addAll(oneCriticalEntity(entity, hd.getLocation(), hd.isRear(), 0));

                // and four pilot hits.
                vDesc.addAll(damageCrew(entity, 4));
            }
            // Buildings and gun emplacements and such are only affected by the EMI.
            // No auto-crits or anything.
        } else if (roll <= 10) {
            if (entity instanceof BattleArmor) {
                // It takes 25% casualties, rounded up.
                BattleArmor myBA = (BattleArmor) entity;
                int numDeaths = (int) (Math.ceil(((myBA.getNumberActiverTroopers())) / 4.0));
                for (int x = 0; x < numDeaths; x++) {
                    vDesc.addAll(applyCriticalHit(entity, 0, null, false, 0, false));
                }
            } else if (entity instanceof Infantry) {
                int damage;
                if (game.getBoard().getHex(entity.getPosition()).containsTerrain(Terrains.BUILDING)) {
                    // 25% casualties, rounded up.
                    damage = (int) (Math.ceil((entity.getInternal(Infantry.LOC_INFANTRY)) / 4.0));
                } else {
                    // 50% casualties, rounded up.
                    damage = (int) (Math.ceil((entity.getInternal(Infantry.LOC_INFANTRY)) / 2.0));
                }
                vDesc.addAll(damageEntity(entity, new HitData(Infantry.LOC_INFANTRY), damage, true));
            } else if (entity instanceof Tank) {
                // It takes one crit...
                HitData hd = entity.rollHitLocation(ToHitData.HIT_NORMAL, entity.sideTable(position));
                vDesc.addAll(oneCriticalEntity(entity, hd.getLocation(), hd.isRear(), 0));

                // Plus a Crew Stunned critical.
                vDesc.addAll(applyCriticalHit(entity, 0, new CriticalSlot(0, Tank.CRIT_CREW_STUNNED), false, 0, false));
            } else if ((entity instanceof Mech) || (entity instanceof Protomech)) {
                // 'Mechs suffer a critical hit...
                HitData hd = entity.rollHitLocation(ToHitData.HIT_NORMAL, entity.sideTable(position));
                vDesc.addAll(oneCriticalEntity(entity, hd.getLocation(), hd.isRear(), 0));

                // and two pilot hits.
                vDesc.addAll(damageCrew(entity, 2));
            }
            // Buildings and gun emplacements and such are only affected by
            // the EMI.
            // No auto-crits or anything.
        }
        // If it's 11+, there are no secondary effects beyond EMI.
        // Lucky bastards.

        // And lastly, the unit is now affected by electromagnetic interference.
        entity.setEMI(true);
    }

    /**
     * Apply a single critical hit. The following private member of Server are
     * accessed from this function, preventing it from being factored out of the
     * Server class: destroyEntity() destroyLocation() checkEngineExplosion()
     * damageCrew() explodeEquipment() game
     *
     * @param en               the <code>Entity</code> that is being damaged. This value may
     *                         not be <code>null</code>.
     * @param loc              the <code>int</code> location of critical hit. This value may
     *                         be <code>Entity.NONE</code> for hits to <code>Tank</code>s and
     *                         for hits to a <code>Protomech</code> torso weapon.
     * @param cs               the <code>CriticalSlot</code> being damaged. This value may
     *                         not be <code>null</code>. For critical hits on a
     *                         <code>Tank</code>, the index of the slot should be the index
     *                         of the critical hit table.
     * @param secondaryEffects the <code>boolean</code> flag that indicates whether to allow
     *                         critical hits to cause secondary effects (such as triggering
     *                         an ammo explosion, sending hovercraft to watery graves, or
     *                         damaging ProtoMech torso weapons). This value is normally
     *                         <code>true</code>, but it will be <code>false</code> when the
     *                         hit is being applied from a saved game or scenario.
     * @param damageCaused     the amount of damage causing this critical.
     * @param isCapital        whether it was capital scale damage that caused critical
     */
    public Vector<Report> applyCriticalHit(Entity en, int loc, CriticalSlot cs, boolean secondaryEffects,
                                           int damageCaused, boolean isCapital) {
        Vector<Report> vDesc = new Vector<>();
        Report r;

        if (en instanceof Tank) {
            vDesc.addAll(applyTankCritical((Tank)en, loc, cs, damageCaused));
        } else if (en instanceof Aero) {
            vDesc.addAll(applyAeroCritical((Aero)en, loc, cs, damageCaused, isCapital));
        } else if (en instanceof BattleArmor) {
            // We might as well handle this here.
            // However, we're considering a crit against BA as a "crew kill".
            BattleArmor ba = (BattleArmor) en;
            r = new Report(6111);
            int randomTrooper = ba.getRandomTrooper();
            ba.destroyLocation(randomTrooper);
            r.add(randomTrooper);
            r.newlines = 1;
            vDesc.add(r);
        } else if (CriticalSlot.TYPE_SYSTEM == cs.getType()) {
            // Handle critical hits on system slots.
            cs.setHit(true);
            if (en instanceof Protomech) {
                vDesc.addAll(applyProtomechCritical((Protomech)en, loc, cs, secondaryEffects, damageCaused, isCapital));
            } else {
                vDesc.addAll(applyMechSystemCritical(en, loc, cs));
            }
        } else if (CriticalSlot.TYPE_EQUIPMENT == cs.getType()) {
            vDesc.addAll(applyEquipmentCritical(en, loc, cs, secondaryEffects));
        } // End crit-on-equipment-slot
        // mechs with TSM hit by anti-tsm missiles this round get another crit
        if ((en instanceof Mech) && en.hitThisRoundByAntiTSM) {
            Mech mech = (Mech) en;
            if (mech.hasTSM()) {
                r = new Report(6430);
                r.subject = en.getId();
                r.indent(2);
                r.addDesc(en);
                r.newlines = 0;
                vDesc.addElement(r);
                vDesc.addAll(oneCriticalEntity(en, Compute.d6(2), false, damageCaused));
            }
            en.hitThisRoundByAntiTSM = false;
        }

        // if using buffered VDNI then a possible pilot hit
        if (en.hasAbility(OptionsConstants.MD_BVDNI) && !en.hasAbility(OptionsConstants.MD_PAIN_SHUNT)) {
            Report.addNewline(vDesc);
            int roll = Compute.d6(2);
            r = new Report(3580);
            r.subject = en.getId();
            r.addDesc(en);
            r.add(7);
            r.add(roll);
            r.choose(roll >= 8);
            r.indent(2);
            vDesc.add(r);
            if (roll >= 8) {
                vDesc.addAll(damageCrew(en, 1));
            }
        }
        // Return the results of the damage.
        return vDesc;
    }

    /**
     * Apply a single critical hit to an equipment slot.
     *
     * @param en               the <code>Entity</code> that is being damaged. This value may
     *                         not be <code>null</code>.
     * @param loc              the <code>int</code> location of critical hit.
     * @param cs               the <code>CriticalSlot</code> being damaged.
     * @param secondaryEffects the <code>boolean</code> flag that indicates whether to allow
     *                         critical hits to cause secondary effects (such as triggering
     *                         an ammo explosion, sending hovercraft to watery graves, or
     *                         damaging ProtoMech torso weapons). This value is normally
     *                         <code>true</code>, but it will be <code>false</code> when the
     *                         hit is being applied from a saved game or scenario.
     */
    private Vector<Report> applyEquipmentCritical(Entity en, int loc, CriticalSlot cs, boolean secondaryEffects) {
        Vector<Report> reports = new Vector<>();
        cs.setHit(true);
        Mounted mounted = cs.getMount();
        EquipmentType eqType = mounted.getType();
        boolean hitBefore = mounted.isHit();

        Report r = new Report(6225);
        r.subject = en.getId();
        r.indent(3);
        r.add(mounted.getDesc());
        reports.addElement(r);

        // Shield objects are not useless when they take one crit.
        // Shields can be critted and still be usable.
        mounted.setHit((!(eqType instanceof MiscType)) || !((MiscType) eqType).isShield());

        if ((eqType instanceof MiscType) && eqType.hasFlag(MiscType.F_EMERGENCY_COOLANT_SYSTEM)) {
            ((Mech)en).setHasDamagedCoolantSystem(true);
        }

        if ((eqType instanceof MiscType) && eqType.hasFlag(MiscType.F_HARJEL)) {
            reports.addAll(breachLocation(en, loc, null, true));
        }

        // HarJel II/III hits trigger another possible critical hit on
        // the same location
        // it's like an ammunition explosion---a secondary effect
        if (secondaryEffects && (eqType instanceof MiscType)
                && (eqType.hasFlag(MiscType.F_HARJEL_II) || eqType.hasFlag(MiscType.F_HARJEL_III))
                && !hitBefore) {
            r = new Report(9852);
            r.subject = en.getId();
            r.indent(2);
            reports.addElement(r);
            reports.addAll(criticalEntity(en, loc, false, 0, 0));
        }

        // If the item is the ECM suite of a Mek Stealth system
        // then it's destruction turns off the stealth.
        if (!hitBefore && (eqType instanceof MiscType)
            && eqType.hasFlag(MiscType.F_ECM)
            && (mounted.getLinkedBy() != null)) {
            Mounted stealth = mounted.getLinkedBy();
            r = new Report(6255);
            r.subject = en.getId();
            r.indent(2);
            r.add(stealth.getType().getName());
            reports.addElement(r);
            stealth.setMode("Off");
        }

        // Handle equipment explosions.
        // Equipment explosions are secondary effects and
        // do not occur when loading from a scenario.
        if (((secondaryEffects && eqType.isExplosive(mounted))
                || mounted.isHotLoaded() || (mounted.hasChargedCapacitor() != 0)) && !hitBefore) {
            reports.addAll(explodeEquipment(en, loc, mounted));
        }

        // Make sure that ammo in this slot is exhausted.
        if (mounted.getBaseShotsLeft() > 0) {
            mounted.setShotsLeft(0);
        }

        // LAMs that are part of a fighter squadron will need to have the squadron recalculate
        // the bomb load out on a bomb bay critical.
        if (en.isPartOfFighterSquadron() && (mounted.getType() instanceof MiscType) && mounted.getType().hasFlag(MiscType.F_BOMB_BAY)) {
            Entity squadron = game.getEntity(en.getTransportId());
            if (squadron instanceof FighterSquadron) {
                ((FighterSquadron) squadron).computeSquadronBombLoadout();
            }
        }
        return reports;
    }

    /**
     * Apply a single critical hit to a Mech system.
     *
     * @param en   the <code>Entity</code> that is being damaged. This value may
     *             not be <code>null</code>.
     * @param loc  the <code>int</code> location of critical hit.
     * @param cs   the <code>CriticalSlot</code> being damaged. This value may
     *             not be <code>null</code>.
     */
    private Vector<Report> applyMechSystemCritical(Entity en, int loc, CriticalSlot cs) {
        Vector<Report> reports = new Vector<>();
        Report r = new Report(6225);
        r.subject = en.getId();
        r.indent(3);
        r.add(((Mech) en).getSystemName(cs.getIndex()));
        reports.addElement(r);
        switch (cs.getIndex()) {
            case Mech.SYSTEM_COCKPIT:
                // Lets auto-eject if we can!
                Mech mech = (Mech) en;
                if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_SKIN_OF_THE_TEETH_EJECTION)) {
                    if (mech.isAutoEject()
                            && (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                            || (game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                            && mech.isCondEjectHeadshot()))) {
                        reports.addAll(ejectEntity(en, true, true));
                    }
                }

                //First check whether this hit takes out the whole crew; for multi-crew cockpits
                //we need to check the other critical positions (if any).
                boolean allDead = true;
                int crewSlot = ((Mech)en).getCrewForCockpitSlot(loc, cs);
                if (crewSlot >= 0) {
                    for (int i = 0; i < en.getCrew().getSlotCount(); i++) {
                        if (i != crewSlot && !en.getCrew().isDead(i) && !en.getCrew().isMissing(i)) {
                            allDead = false;
                        }
                    }
                }
                if (allDead) {
                    // Don't kill a pilot multiple times.
                    if (Crew.DEATH > en.getCrew().getHits()) {
                        // Single pilot or tripod cockpit; all crew are killed.
                        en.getCrew().setDoomed(true);
                        Report.addNewline(reports);
                        reports.addAll(entityManager.destroyEntity(en, "pilot death", true));
                    }
                } else if (!en.getCrew().isMissing(crewSlot)){
                    boolean wasPilot = en.getCrew().getCurrentPilotIndex() == crewSlot;
                    boolean wasGunner = en.getCrew().getCurrentGunnerIndex() == crewSlot;
                    en.getCrew().setDead(true, crewSlot);
                    r = new Report(6027);
                    r.subject = en.getId();
                    r.indent(2);
                    r.add(en.getCrew().getCrewType().getRoleName(crewSlot));
                    r.addDesc(en);
                    r.add(en.getCrew().getName(crewSlot));
                    reports.addElement(r);
                    r = reportmanager.createCrewTakeoverReport(en, crewSlot, wasPilot, wasGunner);
                    if (null != r) {
                        reports.add(r);
                    }
                }
                break;
            case Mech.SYSTEM_ENGINE:
                // if the slot is missing, the location was previously
                // destroyed and the engine hit was then counted already
                if (!cs.isMissing()) {
                    en.engineHitsThisPhase++;
                }
                int numEngineHits = en.getEngineHits();
                boolean engineExploded = checkEngineExplosion(en, reports, numEngineHits);
                int hitsToDestroy = 3;
                if (en.isSuperHeavy() && en.hasEngine() && (en.getEngine().getEngineType() == Engine.COMPACT_ENGINE)) {
                    hitsToDestroy = 2;
                }

                if (!engineExploded && (numEngineHits >= hitsToDestroy)) {
                    // third engine hit
                    reports.addAll(entityManager.destroyEntity(en, "engine destruction"));
                    if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_AUTO_ABANDON_UNIT)) {
                        reports.addAll(abandonEntity(en));
                    }
                    en.setSelfDestructing(false);
                    en.setSelfDestructInitiated(false);
                }
                break;
            case Mech.SYSTEM_GYRO:
                int gyroHits = en.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_GYRO, loc);
                if (en.getGyroType() != Mech.GYRO_HEAVY_DUTY) {
                    gyroHits++;
                }
                // Automatically falls in AirMech mode, which it seems would indicate a crash if airborne.
                if (gyroHits == 3 && en instanceof LandAirMech && en.isAirborneVTOLorWIGE()) {
                    crashAirMech(en, new PilotingRollData(en.getId(), TargetRoll.AUTOMATIC_FAIL, 1, "gyro destroyed"));
                    break;
                }
                //No PSR for Mechs in non-leg mode
                if (!en.canFall(true)) {
                    break;
                }
                switch (gyroHits) {
                    case 3:
                        // HD 3 hits, standard 2 hits
                        game.addPSR(new PilotingRollData(en.getId(), TargetRoll.AUTOMATIC_FAIL, 1, "gyro destroyed"));
                        // Gyro destroyed entities may not be hull down
                        en.setHullDown(false);
                        break;
                    case 2:
                        // HD 2 hits, standard 1 hit
                        game.addPSR(new PilotingRollData(en.getId(), 3, "gyro hit"));
                        break;
                    case 1:
                        // HD 1 hit
                        game.addPSR(new PilotingRollData(en.getId(), 2, "gyro hit"));
                        break;
                    default:
                        // ignore if >4 hits (don't over do it, the auto fail
                        // already happened.)
                }
                break;
            case Mech.ACTUATOR_UPPER_LEG:
            case Mech.ACTUATOR_LOWER_LEG:
            case Mech.ACTUATOR_FOOT:
                if (en.canFall(true)) {
                    // leg/foot actuator piloting roll
                    game.addPSR(new PilotingRollData(en.getId(), 1, "leg/foot actuator hit"));
                }
                break;
            case Mech.ACTUATOR_HIP:
                if (en.canFall(true)) {
                    // hip piloting roll
                    game.addPSR(new PilotingRollData(en.getId(), 2, "hip actuator hit"));
                }
                break;
            case LandAirMech.LAM_AVIONICS:
                if (en.getConversionMode() == LandAirMech.CONV_MODE_FIGHTER) {
                    if (en.isPartOfFighterSquadron()) {
                        game.addControlRoll(new PilotingRollData(en.getTransportId(), 1, "avionics hit"));
                    } else if (en.isCapitalFighter()){
                        game.addControlRoll(new PilotingRollData(en.getId(), 1, "avionics hit"));
                    } else {
                        game.addControlRoll(new PilotingRollData(en.getId(), 0, "avionics hit"));
                    }
                }
                break;
        }
        return reports;
    }

    /**
     * Apply a single critical hit to a ProtoMech.
     *
     * @param pm               the <code>Protomech</code> that is being damaged. This value may
     *                         not be <code>null</code>.
     * @param loc              the <code>int</code> location of critical hit. This value may
     *                         be <code>Entity.NONE</code> for hits to a <code>Protomech</code>
     *                         torso weapon.
     * @param cs               the <code>CriticalSlot</code> being damaged. This value may
     *                         not be <code>null</code>.
     * @param secondaryEffects the <code>boolean</code> flag that indicates whether to allow
     *                         critical hits to cause secondary effects (such as damaging
     *                         ProtoMech torso weapons). This value is normally
     *                         <code>true</code>, but it will be <code>false</code> when the
     *                         hit is being applied from a saved game or scenario.
     * @param damageCaused     the amount of damage causing this critical.
     * @param isCapital        whether it was capital scale damage that caused critical
     */
    private Vector<Report> applyProtomechCritical(Protomech pm, int loc, CriticalSlot cs, boolean secondaryEffects,
                                                  int damageCaused, boolean isCapital) {
        Vector<Report> reports = new Vector<>();
        Report r;
        int numHit = pm.getCritsHit(loc);
        if ((cs.getIndex() != Protomech.SYSTEM_TORSO_WEAPON_A)
                && (cs.getIndex() != Protomech.SYSTEM_TORSO_WEAPON_B)
                && (cs.getIndex() != Protomech.SYSTEM_TORSO_WEAPON_C)
                && (cs.getIndex() != Protomech.SYSTEM_TORSO_WEAPON_D)
                && (cs.getIndex() != Protomech.SYSTEM_TORSO_WEAPON_E)
                && (cs.getIndex() != Protomech.SYSTEM_TORSO_WEAPON_F)) {
            r = new Report(6225);
            r.subject = pm.getId();
            r.indent(3);
            r.add(Protomech.systemNames[cs.getIndex()]);
            reports.addElement(r);
        }
        switch (cs.getIndex()) {
            case Protomech.SYSTEM_HEADCRIT:
                if (2 == numHit) {
                    r = new Report(6230);
                    r.subject = pm.getId();
                    reports.addElement(r);
                    pm.destroyLocation(loc);
                }
                break;
            case Protomech.SYSTEM_ARMCRIT:
                if (2 == numHit) {
                    r = new Report(6235);
                    r.subject = pm.getId();
                    reports.addElement(r);
                    pm.destroyLocation(loc);
                }
                break;
            case Protomech.SYSTEM_LEGCRIT:
                if (3 == numHit) {
                    r = new Report(6240);
                    r.subject = pm.getId();
                    r.newlines = 0;
                    reports.addElement(r);
                    pm.destroyLocation(loc);
                }
                break;
            case Protomech.SYSTEM_TORSOCRIT:
                if (3 == numHit) {
                    reports.addAll(entityManager.destroyEntity(pm, "torso destruction"));
                }
                // Torso weapon hits are secondary effects and
                // do not occur when loading from a scenario.
                else if (secondaryEffects) {
                    int tweapRoll = Compute.d6(1);
                    CriticalSlot newSlot = null;

                    switch (tweapRoll) {
                        case 1:
                            if (pm.isQuad()) {
                                newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM, Protomech.SYSTEM_TORSO_WEAPON_A);
                                break;
                            }
                        case 2:
                            if (pm.isQuad()) {
                                newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM, Protomech.SYSTEM_TORSO_WEAPON_B);
                            } else {
                                newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM, Protomech.SYSTEM_TORSO_WEAPON_A);
                            }
                            break;
                        case 3:
                            if (pm.isQuad()) {
                                newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM, Protomech.SYSTEM_TORSO_WEAPON_C);
                                break;
                            }
                        case 4:
                            if (pm.isQuad()) {
                                newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM, Protomech.SYSTEM_TORSO_WEAPON_D);
                            } else {
                                newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM, Protomech.SYSTEM_TORSO_WEAPON_B);
                            }
                            break;
                        case 5:
                            if (pm.getWeight() > 9) {
                                if (pm.isQuad()) {
                                    newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM, Protomech.SYSTEM_TORSO_WEAPON_E);
                                } else {
                                    newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM, Protomech.SYSTEM_TORSO_WEAPON_C);
                                }
                                break;
                            }
                        case 6:
                            if (pm.getWeight() > 9) {
                                if (pm.isQuad()) {
                                    newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM, Protomech.SYSTEM_TORSO_WEAPON_F);
                                } else {
                                    newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM, Protomech.SYSTEM_TORSO_WEAPON_C);
                                }
                                break;
                            }
                    }

                    if (newSlot != null) {
                        reports.addAll(applyCriticalHit(pm, Entity.NONE, newSlot, secondaryEffects, damageCaused, isCapital));
                    }

                    // A magnetic clamp system is destroyed by any torso critical.
                    Mounted magClamp = pm.getMisc().stream().filter(m -> m.getType()
                            .hasFlag(MiscType.F_MAGNETIC_CLAMP)).findFirst().orElse(null);
                    if ((magClamp != null) && !magClamp.isHit()) {
                        magClamp.setHit(true);
                        r = new Report(6252);
                        r.subject = pm.getId();
                        reports.addElement(r);
                    }
                }
                break;
            case Protomech.SYSTEM_TORSO_WEAPON_A:
                Mounted weaponA = pm.getTorsoWeapon(cs.getIndex());
                if (null != weaponA) {
                    weaponA.setHit(true);
                    r = new Report(6245);
                    r.subject = pm.getId();
                    r.newlines = 0;
                    reports.addElement(r);
                }
                break;
            case Protomech.SYSTEM_TORSO_WEAPON_B:
                Mounted weaponB = pm.getTorsoWeapon(cs.getIndex());
                if (null != weaponB) {
                    weaponB.setHit(true);
                    r = new Report(6246);
                    r.subject = pm.getId();
                    r.newlines = 0;
                    reports.addElement(r);
                }
                break;
            case Protomech.SYSTEM_TORSO_WEAPON_C:
                Mounted weaponC = pm.getTorsoWeapon(cs.getIndex());
                if (null != weaponC) {
                    weaponC.setHit(true);
                    r = new Report(6247);
                    r.subject = pm.getId();
                    r.newlines = 0;
                    reports.addElement(r);
                }
                break;
            case Protomech.SYSTEM_TORSO_WEAPON_D:
                Mounted weaponD = pm.getTorsoWeapon(cs.getIndex());
                if (null != weaponD) {
                    weaponD.setHit(true);
                    r = new Report(6248);
                    r.subject = pm.getId();
                    r.newlines = 0;
                    reports.addElement(r);
                }
                break;
            case Protomech.SYSTEM_TORSO_WEAPON_E:
                Mounted weaponE = pm.getTorsoWeapon(cs.getIndex());
                if (null != weaponE) {
                    weaponE.setHit(true);
                    r = new Report(6249);
                    r.subject = pm.getId();
                    r.newlines = 0;
                    reports.addElement(r);
                }
                break;
            case Protomech.SYSTEM_TORSO_WEAPON_F:
                Mounted weaponF = pm.getTorsoWeapon(cs.getIndex());
                if (null != weaponF) {
                    weaponF.setHit(true);
                    r = new Report(6250);
                    r.subject = pm.getId();
                    r.newlines = 0;
                    reports.addElement(r);
                }
                break;
        } // End switch( cs.getType() )

        // Shaded hits cause pilot damage.
        if (pm.shaded(loc, numHit)) {
            // Destroyed ProtoMech sections have
            // already damaged the pilot.
            int pHits = Protomech.POSSIBLE_PILOT_DAMAGE[loc] - pm.getPilotDamageTaken(loc);
            if (Math.min(1, pHits) > 0) {
                Report.addNewline(reports);
                reports.addAll(damageCrew(pm, 1));
                pHits = 1 + pm.getPilotDamageTaken(loc);
                pm.setPilotDamageTaken(loc, pHits);
            }
        } // End have-shaded-hit
        return reports;
    }

    /**
     * Apply a single critical hit to an aerospace unit.
     *
     * @param aero             the <code>Aero</code> that is being damaged. This value may
     *                         not be <code>null</code>.
     * @param loc              the <code>int</code> location of critical hit.
     * @param cs               the <code>CriticalSlot</code> being damaged. This value may
     *                         not be <code>null</code>.
     * @param damageCaused     the amount of damage causing this critical.
     * @param isCapital        whether it was capital scale damage that caused critical
     */
    private Vector<Report> applyAeroCritical(Aero aero, int loc, CriticalSlot cs, int damageCaused, boolean isCapital) {
        Vector<Report> reports = new Vector<>();
        Report r;
        Jumpship js = null;
        if (aero instanceof Jumpship) {
            js = (Jumpship)aero;
        }

        switch (cs.getIndex()) {
            case Aero.CRIT_NONE:
                // no effect
                r = new Report(6005);
                r.subject = aero.getId();
                reports.add(r);
                break;
            case Aero.CRIT_FCS:
                // Fire control system
                r = new Report(9105);
                r.subject = aero.getId();
                reports.add(r);
                aero.setFCSHits(aero.getFCSHits() + 1);
                break;
            case Aero.CRIT_SENSOR:
                // sensors
                r = new Report(6620);
                r.subject = aero.getId();
                reports.add(r);
                aero.setSensorHits(aero.getSensorHits() + 1);
                break;
            case Aero.CRIT_AVIONICS:
                // avionics
                r = new Report(9110);
                r.subject = aero.getId();
                reports.add(r);
                aero.setAvionicsHits(aero.getAvionicsHits() + 1);
                if (aero.isPartOfFighterSquadron()) {
                    game.addControlRoll(new PilotingRollData(aero.getTransportId(), 1, "avionics hit"));
                } else if (aero.isCapitalFighter()) {
                    game.addControlRoll(new PilotingRollData(aero.getId(), 1, "avionics hit"));
                } else {
                    game.addControlRoll(new PilotingRollData(aero.getId(), 0, "avionics hit"));
                }
                break;
            case Aero.CRIT_CONTROL:
                // force control roll
                r = new Report(9115);
                r.subject = aero.getId();
                reports.add(r);
                if (aero.isPartOfFighterSquadron()) {
                    game.addControlRoll(new PilotingRollData(aero.getTransportId(), 1, "critical hit"));
                } else if (aero.isCapitalFighter()) {
                    game.addControlRoll(new PilotingRollData(aero.getId(), 1, "critical hit"));
                } else {
                    game.addControlRoll(new PilotingRollData(aero.getId(), 0, "critical hit"));
                }
                break;
            case Aero.CRIT_FUEL_TANK:
                // fuel tank
                int boomTarget = 10;
                if (aero.hasQuirk(OptionsConstants.QUIRK_NEG_FRAGILE_FUEL)) {
                    boomTarget = 8;
                }
                if (aero.isLargeCraft() && aero.isClan() && game.getOptions().booleanOption(
                                OptionsConstants.ADVAERORULES_STRATOPS_HARJEL)) {
                    boomTarget = 12;
                }
                // check for possible explosion
                int fuelroll = Compute.d6(2);
                r = new Report(9120);
                r.subject = aero.getId();
                if (fuelroll >= boomTarget) {
                    // A chance to reroll the explosion with edge
                    if (aero.getCrew().hasEdgeRemaining() && aero.getCrew().getOptions().booleanOption(
                            OptionsConstants.EDGE_WHEN_AERO_EXPLOSION)) {
                        // Reporting this is funky because 9120 only has room for 2 choices. Replace it.
                        r = new Report(9123);
                        r.subject = aero.getId();
                        r.newlines = 0;
                        reports.add(r);
                        aero.getCrew().decreaseEdge();
                        fuelroll = Compute.d6(2);
                        // To explode, or not to explode
                        if (fuelroll >= boomTarget) {
                            r = new Report(9124);
                            r.subject = aero.getId();
                        } else {
                            r = new Report(9122);
                            r.subject = aero.getId();
                            reports.add(r);
                            break;
                        }
                    }
                    r.choose(true);
                    reports.add(r);
                    // Lets auto-eject if we can!
                    if (aero.isFighter()) {
                        if (aero.isAutoEject()
                                && (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                || (game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                && aero.isCondEjectFuel()))) {
                            reports.addAll(ejectEntity(aero, true, false));
                        }
                    }
                    reports.addAll(entityManager.destroyEntity(aero, "fuel explosion", false, false));
                } else {
                    r.choose(false);
                    reports.add(r);
                }
                
                aero.setFuelTankHit(true);
                break;
            case Aero.CRIT_CREW:
                // pilot hit
                r = new Report(6650);
                if (aero.hasAbility(OptionsConstants.MD_DERMAL_ARMOR)) {
                    r = new Report(6651);
                    r.subject = aero.getId();
                    reports.add(r);
                    break;
                } else if (aero.hasAbility(OptionsConstants.MD_TSM_IMPLANT)) {
                    r = new Report(6652);
                    r.subject = aero.getId();
                    reports.add(r);
                    break;
                }
                if ((aero instanceof SmallCraft) || (aero instanceof Jumpship)) {
                    r = new Report(9197);
                }
                if (aero.isLargeCraft() && aero.isClan()
                        && game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_STRATOPS_HARJEL)
                        && (aero.getIgnoredCrewHits() < 2)) {
                    aero.setIgnoredCrewHits(aero.getIgnoredCrewHits() + 1);
                    r = new Report(9198);
                    r.subject = aero.getId();
                    reports.add(r);
                    break;
                }
                r.subject = aero.getId();
                reports.add(r);
                reports.addAll(damageCrew(aero, 1));
                // The pilot may have just expired.
                if ((aero.getCrew().isDead() || aero.getCrew().isDoomed()) && !aero.getCrew().isEjected()) {
                    reports.addAll(entityManager.destroyEntity(aero, "pilot death", true, true));
                }
                break;
            case Aero.CRIT_GEAR:
                // landing gear
                r = new Report(9125);
                r.subject = aero.getId();
                reports.add(r);
                aero.setGearHit(true);
                break;
            case Aero.CRIT_BOMB:
                // bomb destroyed
                // go through bomb list and choose one
                List<Mounted> bombs = new ArrayList<>();
                for (Mounted bomb : aero.getBombs()) {
                    if (bomb.getType().isHittable() && (bomb.getHittableShotsLeft() > 0)) {
                        bombs.add(bomb);
                    }
                }
                if (bombs.size() > 0) {
                    Mounted hitbomb = bombs.get(Compute.randomInt(bombs.size()));
                    hitbomb.setShotsLeft(0);
                    hitbomb.setDestroyed(true);
                    r = new Report(9130);
                    r.subject = aero.getId();
                    r.add(hitbomb.getDesc());
                    reports.add(r);
                    // If we are part of a squadron, we should recalculate
                    // the bomb salvo for the squadron
                    if (aero.getTransportId() != Entity.NONE) {
                        Entity e = game.getEntity(aero.getTransportId());
                        if (e instanceof FighterSquadron) {
                            ((FighterSquadron) e).computeSquadronBombLoadout();
                        }
                    }
                } else {
                    r = new Report(9131);
                    r.subject = aero.getId();
                    reports.add(r);
                }
                break;
            case Aero.CRIT_HEATSINK:
                // heat sink hit
                int sinksLost = isCapital ? 10 : 1;
                r = new Report(9135);
                r.subject = aero.getId();
                r.add(sinksLost);
                reports.add(r);
                aero.setHeatSinks(Math.max(0, aero.getHeatSinks() - sinksLost));
                break;
            case Aero.CRIT_WEAPON_BROAD:
                if (aero instanceof Warship) {
                    if ((loc == Jumpship.LOC_ALS) || (loc == Jumpship.LOC_FLS)) {
                        loc = Warship.LOC_LBS;
                    } else if ((loc == Jumpship.LOC_ARS) || (loc == Jumpship.LOC_FRS)) {
                        loc = Warship.LOC_RBS;
                    }
                }
            case Aero.CRIT_WEAPON:
                if (aero.isCapitalFighter()) {
                    boolean destroyAll = false;
                    // CRIT_WEAPON damages the capital fighter/squadron's weapon groups
                    // Go ahead and map damage for the fighter's weapon criticals for MHQ
                    // resolution.
                    aero.damageCapFighterWeapons(loc);
                    if ((loc == Aero.LOC_NOSE) || (loc == Aero.LOC_AFT)) {
                        destroyAll = true;
                    }
                    
                    // Convert L/R wing location to wings, else wing weapons never get hit
                    if (loc == Aero.LOC_LWING || loc == Aero.LOC_RWING) {
                        loc = Aero.LOC_WINGS;
                    }
                    
                    if (loc == Aero.LOC_WINGS) {
                        if (aero.areWingsHit()) {
                            destroyAll = true;
                        } else {
                            aero.setWingsHit(true);
                        }
                    }
                    for (Mounted weapon : aero.getWeaponList()) {
                        if (weapon.getLocation() == loc) {
                            if (destroyAll) {
                                weapon.setHit(true);
                            } else {
                                weapon.setNWeapons(weapon.getNWeapons() / 2);
                            }
                        }
                    }
                    // also destroy any ECM or BAP in the location hit
                    for (Mounted misc : aero.getMisc()) {
                        if ((misc.getType().hasFlag(MiscType.F_ECM)
                            || misc.getType().hasFlag(MiscType.F_ANGEL_ECM)
                            || misc.getType().hasFlag(MiscType.F_BAP))
                                && misc.getLocation() == loc) {
                            gamemanager.damageWeaponCriticalSlot(aero, misc, loc);
                        }
                    }
                    r = new Report(9152);
                    r.subject = aero.getId();
                    r.add(aero.getLocationName(loc));
                    reports.add(r);
                    break;
                }
                r = new Report(9150);
                r.subject = aero.getId();
                List<Mounted> weapons = new ArrayList<>();
                for (Mounted weapon : aero.getWeaponList()) {
                    if ((weapon.getLocation() == loc) && !weapon.isDestroyed() && weapon.getType().isHittable()) {
                        weapons.add(weapon);
                    }
                }
                // add in in hittable misc equipment
                for (Mounted misc : aero.getMisc()) {
                    if (misc.getType().isHittable() && (misc.getLocation() == loc) && !misc.isDestroyed()) {
                        weapons.add(misc);
                    }
                }
                if (weapons.size() > 0) {
                    Mounted weapon = weapons.get(Compute.randomInt(weapons.size()));
                    // possibly check for an ammo explosion
                    // don't allow ammo explosions on fighter squadrons
                    if (game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_AMMO_EXPLOSIONS)
                        && !(aero instanceof FighterSquadron)
                        && (weapon.getType() instanceof WeaponType)) {
                        //Bay Weapons
                        if (aero.usesWeaponBays()) {
                            //Finish reporting(9150) a hit on the bay
                            r.add(weapon.getName());
                            reports.add(r);
                            //Pick a random weapon in the bay and get the stats
                            int wId = weapon.getBayWeapons().get(Compute.randomInt(weapon.getBayWeapons().size()));
                            Mounted bayW = aero.getEquipment(wId);
                            Mounted bayWAmmo = bayW.getLinked();
                            if (bayWAmmo != null && bayWAmmo.getType().isExplosive(bayWAmmo)) {
                                r = new Report(9156);
                                r.subject = aero.getId();
                                r.newlines = 1;
                                r.indent(2);
                                //On a roll of 10+, the ammo bin explodes
                                int ammoRoll = Compute.d6(2);
                                boomTarget = 10;
                                r.choose(ammoRoll >= boomTarget);
                                // A chance to reroll an explosion with edge
                                if (aero.getCrew().hasEdgeRemaining()
                                        && aero.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_AERO_EXPLOSION)
                                        && ammoRoll >= boomTarget) {
                                    // Report 9156 doesn't offer the right choices. Replace it.
                                    r = new Report(9158);
                                    r.subject = aero.getId();
                                    r.newlines = 0;
                                    r.indent(2);
                                    reports.add(r);
                                    aero.getCrew().decreaseEdge();
                                    ammoRoll = Compute.d6(2);
                                    // To explode, or not to explode
                                    if (ammoRoll >= boomTarget) {
                                        reports.addAll(explodeEquipment(aero, loc, bayWAmmo));
                                    } else {
                                        r = new Report(9157);
                                        r.subject = aero.getId();
                                        reports.add(r);
                                    }
                                } else {
                                    //Finish handling report 9156
                                    reports.add(r);
                                    if (ammoRoll >= boomTarget) {
                                        reports.addAll(explodeEquipment(aero, loc, bayWAmmo));
                                    }
                                }
                            }
                            //Hit the weapon then also hit all the other weapons in the bay
                            weapon.setHit(true);
                            for(int next : weapon.getBayWeapons()) {
                                Mounted bayWeap = aero.getEquipment(next);
                                if(null != bayWeap) {
                                    gamemanager.damageWeaponCriticalSlot(aero, bayWeap, loc);
                                }
                            }
                            break;
                        }
                        // does it use Ammo?
                        WeaponType wtype = (WeaponType) weapon.getType();
                        if (wtype.getAmmoType() != AmmoType.T_NA) {
                            Mounted m = weapon.getLinked();
                            int ammoroll = Compute.d6(2);
                            if (ammoroll >= 10) {
                                // A chance to reroll an explosion with edge
                                if (aero.getCrew().hasEdgeRemaining()
                                        && aero.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_AERO_EXPLOSION)) {
                                    aero.getCrew().decreaseEdge();
                                    r = new Report(6530);
                                    r.subject = aero.getId();
                                    r.add(aero.getCrew().getOptions().intOption(OptionsConstants.EDGE));
                                    reports.add(r);
                                    ammoroll = Compute.d6(2);
                                    if (ammoroll >= 10) {
                                        reports.addAll(explodeEquipment(aero, loc, m));
                                        break;
                                    } else {
                                        //Crisis averted, set report 9150 back up
                                        r = new Report(9150);
                                        r.subject = aero.getId();
                                    }
                                } else {
                                    r = new Report(9151);
                                    r.subject = aero.getId();
                                    r.add(m.getName());
                                    r.newlines = 0;
                                    reports.add(r);
                                    reports.addAll(explodeEquipment(aero, loc, m));
                                    break;
                                }
                            }
                        }
                    }
                    // If the weapon is explosive, use edge to roll up a new one
                    if (aero.getCrew().hasEdgeRemaining()
                            && aero.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_AERO_EXPLOSION)
                            && (weapon.getType().isExplosive(weapon) && !weapon.isHit() && !weapon.isDestroyed())) {
                        aero.getCrew().decreaseEdge();
                        //Try something new for an interrupting report. r is still 9150.
                        Report r1 = new Report(6530);
                        r1.subject = aero.getId();
                        r1.add(aero.getCrew().getOptions().intOption(OptionsConstants.EDGE));
                        reports.add(r1);
                        weapon = weapons.get(Compute.randomInt(weapons.size()));
                    }
                    r.add(weapon.getName());
                    reports.add(r);
                    // explosive weapons e.g. gauss now explode
                    if (weapon.getType().isExplosive(weapon) && !weapon.isHit()
                        && !weapon.isDestroyed()) {
                        reports.addAll(explodeEquipment(aero, loc, weapon));
                    }
                    gamemanager.damageWeaponCriticalSlot(aero, weapon, loc);
                    //if this is a weapons bay then also hit all the other weapons
                    for(int wId : weapon.getBayWeapons()) {
                        Mounted bayWeap = aero.getEquipment(wId);
                        if(null != bayWeap) {
                            gamemanager.damageWeaponCriticalSlot(aero, bayWeap, loc);
                        }
                    }
                } else {
                    r = new Report(9155);
                    r.subject = aero.getId();
                    reports.add(r);
                }
                break;
            case Aero.CRIT_ENGINE:
                // engine hit
                r = new Report(9140);
                r.subject = aero.getId();
                reports.add(r);
                aero.engineHitsThisPhase++;
                boolean engineExploded = checkEngineExplosion(aero, reports, 1);
                aero.setEngineHits(aero.getEngineHits() + 1);
                if ((aero.getEngineHits() >= aero.getMaxEngineHits()) || engineExploded) {
                    // this engine hit puts the ASF out of commission
                    reports.addAll(entityManager.destroyEntity(aero, "engine destruction", true,
                                               true));
                    aero.setSelfDestructing(false);
                    aero.setSelfDestructInitiated(false);
                }
                break;
            case Aero.CRIT_LEFT_THRUSTER:
                // thruster hit
                r = new Report(9160);
                r.subject = aero.getId();
                reports.add(r);
                aero.setLeftThrustHits(aero.getLeftThrustHits() + 1);
                break;
            case Aero.CRIT_RIGHT_THRUSTER:
                // thruster hit
                r = new Report(9160);
                r.subject = aero.getId();
                reports.add(r);
                aero.setRightThrustHits(aero.getRightThrustHits() + 1);
                break;
            case Aero.CRIT_CARGO:
                applyCargoCritical(aero, damageCaused, reports);
                break;
            case Aero.CRIT_DOOR:
                // door hit
                // choose a random bay
                String bayType = aero.damageBayDoor();
                if (!bayType.equals("none")) {
                    r = new Report(9170);
                    r.subject = aero.getId();
                    r.add(bayType);
                } else {
                    r = new Report(9171);
                    r.subject = aero.getId();
                }
                reports.add(r);
                break;
            case Aero.CRIT_DOCK_COLLAR:
                // docking collar hit
                // different effect for DropShips and JumpShips
                if (aero instanceof Dropship) {
                    ((Dropship)aero).setDamageDockCollar(true);
                    r = new Report(9175);
                    r.subject = aero.getId();
                    reports.add(r);
                }
                if (aero instanceof Jumpship) {
                    // damage a random docking collar
                    if (aero.damageDockCollar()) {
                        r = new Report(9176);
                    } else {
                        r = new Report(9177);
                    }
                    r.subject = aero.getId();
                    reports.add(r);
                }
                break;
            case Aero.CRIT_KF_BOOM:
                // KF boom hit, no real effect yet
                if (aero instanceof Dropship) {
                    ((Dropship)aero).setDamageKFBoom(true);
                    r = new Report(9180);
                    r.subject = aero.getId();
                    reports.add(r);
                }
                break;
            case Aero.CRIT_CIC:
                if (js == null) {
                    break;
                }
                // CIC hit
                r = new Report(9185);
                r.subject = aero.getId();
                reports.add(r);
                js.setCICHits(js.getCICHits() + 1);
                break;
            case Aero.CRIT_KF_DRIVE:
                //Per SO construction rules, stations have no KF drive, therefore they can't take a hit to it...
                if (js == null || js instanceof SpaceStation) {
                    break;
                }
                // KF Drive hit - damage the drive integrity
                js.setKFIntegrity(Math.max(0, (js.getKFIntegrity() - 1)));
                if (game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_EXPANDED_KF_DRIVE_DAMAGE)) {
                    //Randomize the component struck - probabilities taken from the old BattleSpace record sheets
                    switch (Compute.d6(2)) {
                    case 2:
                        //Drive Coil Hit
                        r = new Report(9186);
                        r.subject = aero.getId();
                        reports.add(r);
                        js.setKFDriveCoilHit(true);
                        break;
                    case 3:
                    case 11:
                        //Charging System Hit
                        r = new Report(9187);
                        r.subject = aero.getId();
                        reports.add(r);
                        js.setKFChargingSystemHit(true);
                        break;
                    case 5:
                        //Field Initiator Hit
                        r = new Report(9190);
                        r.subject = aero.getId();
                        reports.add(r);
                        js.setKFFieldInitiatorHit(true);
                        break;
                    case 4:
                    case 6:
                    case 7:
                    case 8:
                        //Helium Tank Hit
                        r = new Report(9189);
                        r.subject = aero.getId();
                        reports.add(r);
                        js.setKFHeliumTankHit(true);
                        break;
                    case 9:
                        //Drive Controller Hit
                        r = new Report(9191);
                        r.subject = aero.getId();
                        reports.add(r);
                        js.setKFDriveControllerHit(true);
                        break;
                    case 10:
                    case 12:
                        //LF Battery Hit - if you don't have one, treat as helium tank
                        if (js.hasLF()) {
                            r = new Report(9188);
                            r.subject = aero.getId();
                            reports.add(r);
                            js.setLFBatteryHit(true);
                        } else {
                            r = new Report(9189);
                            r.subject = aero.getId();
                            reports.add(r);
                            js.setKFHeliumTankHit(true);
                        }
                        break;
                    }
                } else {
                    //Just report the standard KF hit, per SO rules
                    r = new Report(9194);
                    r.subject = aero.getId();
                    reports.add(r);
                }
                break;
            case Aero.CRIT_GRAV_DECK:
                if (js == null) {
                    break;
                }
                int choice = Compute.randomInt(js.getTotalGravDeck());
                // Grav Deck hit
                r = new Report(9195);
                r.subject = aero.getId();
                reports.add(r);
                js.setGravDeckDamageFlag(choice, 1);
                break;
            case Aero.CRIT_LIFE_SUPPORT:
                // Life Support hit
                aero.setLifeSupport(false);
                r = new Report(9196);
                r.subject = aero.getId();
                reports.add(r);
                break;
        }
        return reports;
    }

    // TODO (Sam): Only entity and game to remove here
    /**
     * Selects random undestroyed bay and applies damage, destroying loaded units where applicable.
     *
     * @param aero           The unit that received the cargo critical.
     * @param damageCaused   The amount of damage applied by the hit that resulted in the cargo critical.
     * @param reports        Used to return any report generated while applying the critical.
     */
    private void applyCargoCritical(Aero aero, int damageCaused, Vector<Report> reports) {
        Report r;
        // cargo hit
        // First what percentage of the cargo did the hit destroy?
        double percentDestroyed = 0.0;
        double mult = 2.0;
        if (aero.isLargeCraft() && aero.isClan()
            && game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_STRATOPS_HARJEL)) {
            mult = 4.0;
        }
        if (damageCaused > 0) {
            percentDestroyed = Math.min(damageCaused / (mult * aero.getSI()), 1.0);
        }
        List<Bay> bays;
        double destroyed = 0;
        // did it hit cargo or units
        int roll = Compute.d6(1);
        // A hit on a bay filled with transported units is devastating
        // allow a reroll with edge
        if (aero.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_AERO_UNIT_CARGO_LOST)
                && aero.getCrew().hasEdgeRemaining() && roll > 3) {
            aero.getCrew().decreaseEdge();
            r = new Report(9172);
            r.subject = aero.getId();
            r.add(aero.getCrew().getOptions().intOption(OptionsConstants.EDGE));
            reports.add(r);
            //Reroll. Maybe we'll hit cargo.
            roll = Compute.d6(1);
        }
        if (roll < 4) {
            bays = aero.getTransportBays().stream().filter(Bay::isCargo).collect(Collectors.toList());
        } else {
            bays = aero.getTransportBays().stream().filter(b -> !b.isCargo() && !b.isQuarters()).collect(Collectors.toList());
        }
        Bay hitBay = null;
        while ((null == hitBay) && !bays.isEmpty()) {
            hitBay = bays.remove(Compute.randomInt(bays.size()));
            if (hitBay.getBayDamage() < hitBay.getCapacity()) {
                if (hitBay.isCargo()) {
                    destroyed = (hitBay.getCapacity() * percentDestroyed * 2.0) / 2.0;
                } else {
                    destroyed = Math.ceil(hitBay.getCapacity() * percentDestroyed);
                }
            } else {
                hitBay = null;
            }
        }
        if (null != hitBay) {
            destroyed = Math.min(destroyed, hitBay.getCapacity() - hitBay.getBayDamage());
            if (hitBay.isCargo()) {
                r = new Report(9165);
            } else {
                r = new Report(9166);
            }
            r.subject = aero.getId();
            r.add(hitBay.getBayNumber());
            if (destroyed == (int) destroyed) {
                r.add((int) destroyed);
            } else {
                r.add(String.valueOf(Math.ceil(destroyed * 2.0) / 2.0));
            }
            reports.add(r);
            if (!hitBay.isCargo()) {
                List<Entity> units = new ArrayList<>(hitBay.getLoadedUnits());
                List<Entity> toRemove = new ArrayList<>();
                //We're letting destroyed units stay in the bay now, but take them off the targets list
                for (Entity en : units) {
                    if (en.isDestroyed() || en.isDoomed()) {
                        toRemove.add(en);
                    }
                }
                units.removeAll(toRemove);
                while ((destroyed > 0) && !units.isEmpty()) {
                    Entity target = units.remove(Compute.randomInt(units.size()));
                    reports.addAll(entityManager.destroyEntity(target, "cargo damage", false, true));
                    destroyed--;
                }
            }
        } else {
            r = new Report(9167);
            r.subject = aero.getId();
            r.choose(roll < 4); // cargo or transport
            reports.add(r);
        }
    }

    /**
     * Apply a single critical hit to a vehicle.
     *
     * @param tank             the <code>Tank</code> that is being damaged. This value may
     *                         not be <code>null</code>.
     * @param loc              the <code>int</code> location of critical hit. This value may
     *                         be <code>Entity.NONE</code> for hits to <code>Tank</code>s and
     *                         for hits to a <code>Protomech</code> torso weapon.
     * @param cs               the <code>CriticalSlot</code> being damaged. This value may
     *                         not be <code>null</code>. The index of the slot should be the index
     *                         of the critical hit table.
     * @param damageCaused     the amount of damage causing this critical.
     */
    private Vector<Report> applyTankCritical(Tank tank, int loc, CriticalSlot cs, int damageCaused) {
        Vector<Report> reports = new Vector<>();
        Report r;
        HitData hit;
        switch (cs.getIndex()) {
            case Tank.CRIT_NONE:
                // no effect
                r = new Report(6005);
                r.subject = tank.getId();
                reports.add(r);
                break;
            case Tank.CRIT_AMMO:
                // ammo explosion
                r = new Report(6610);
                r.subject = tank.getId();
                reports.add(r);
                int damage = 0;
                for (Mounted m : tank.getAmmo()) {
                    // Don't include ammo of one-shot weapons.
                    if (m.getLocation() == Entity.LOC_NONE) {
                        continue;
                    }
                    m.setHit(true);
                    int tmp = m.getHittableShotsLeft()
                              * ((AmmoType) m.getType()).getDamagePerShot()
                              * ((AmmoType) m.getType()).getRackSize();
                    m.setShotsLeft(0);
                    // non-explosive ammo can't explode
                    if (!m.getType().isExplosive(m)) {
                        continue;
                    }
                    damage += tmp;
                    r = new Report(6390);
                    r.subject = tank.getId();
                    r.add(m.getName());
                    r.add(tmp);
                    reports.add(r);
                }
                hit = new HitData(loc);
                reports.addAll(damageEntity(tank, hit, damage, true));
                break;
            case Tank.CRIT_CARGO:
                // Cargo/infantry damage
                r = new Report(6615);
                r.subject = tank.getId();
                reports.add(r);
                List<Entity> passengers = tank.getLoadedUnits();
                if (passengers.size() > 0) {
                    Entity target = passengers.get(Compute.randomInt(passengers.size()));
                    hit = target.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                    reports.addAll(damageEntity(target, hit, damageCaused));
                }
                break;
            case Tank.CRIT_COMMANDER:
                if (tank.hasAbility(OptionsConstants.MD_VDNI) || tank.hasAbility(OptionsConstants.MD_BVDNI)) {
                    r = new Report(6191);
                    r.subject = tank.getId();
                    reports.add(r);
                    reports.addAll(damageCrew(tank, 1));
                } else {
                    if (tank.hasAbility(OptionsConstants.MD_PAIN_SHUNT)
                        && !tank.isCommanderHitPS()) {
                        r = new Report(6606);
                        r.subject = tank.getId();
                        reports.add(r);
                        tank.setCommanderHitPS(true);
                    } else if (tank.hasWorkingMisc(MiscType.F_COMMAND_CONSOLE) && !tank.isUsingConsoleCommander()) {
                        r = new Report(6607);
                        r.subject = tank.getId();
                        reports.add(r);
                        tank.setUsingConsoleCommander(true);
                    } else {
                        r = new Report(6605);
                        r.subject = tank.getId();
                        reports.add(r);
                        tank.setCommanderHit(true);
                    }
                }
                // fall through here, because effects of crew stunned also
                // apply
            case Tank.CRIT_CREW_STUNNED:
                if (tank.hasAbility(OptionsConstants.MD_VDNI) || tank.hasAbility(OptionsConstants.MD_BVDNI)) {
                    r = new Report(6191);
                    r.subject = tank.getId();
                    reports.add(r);
                    reports.addAll(damageCrew(tank, 1));
                } else {
                    if (tank.hasAbility(OptionsConstants.MD_PAIN_SHUNT)
                            || tank.hasAbility(OptionsConstants.MD_DERMAL_ARMOR)) {
                        r = new Report(6186);
                    } else {
                        tank.stunCrew();
                        r = new Report(6185);
                        r.add(tank.getStunnedTurns() - 1);
                    }
                    r.subject = tank.getId();
                    reports.add(r);
                }
                break;
            case Tank.CRIT_DRIVER:
                if (tank.hasAbility(OptionsConstants.MD_VDNI) || tank.hasAbility(OptionsConstants.MD_BVDNI)) {
                    r = new Report(6191);
                    r.subject = tank.getId();
                    reports.add(r);
                    reports.addAll(damageCrew(tank, 1));
                } else {
                    if (tank.hasAbility(OptionsConstants.MD_PAIN_SHUNT)
                        && !tank.isDriverHitPS()) {
                        r = new Report(6601);
                        r.subject = tank.getId();
                        reports.add(r);
                        tank.setDriverHitPS(true);
                    } else {
                        r = new Report(6600);
                        r.subject = tank.getId();
                        reports.add(r);
                        tank.setDriverHit(true);
                    }
                }
                break;
            case Tank.CRIT_CREW_KILLED:
                if (tank.hasAbility(OptionsConstants.MD_VDNI) || tank.hasAbility(OptionsConstants.MD_BVDNI)) {
                    r = new Report(6191);
                    r.subject = tank.getId();
                    reports.add(r);
                    reports.addAll(damageCrew(tank, 1));
                } else {
                    if (tank.hasAbility(OptionsConstants.MD_PAIN_SHUNT) && !tank.isCrewHitPS()) {
                        r = new Report(6191);
                        r.subject = tank.getId();
                        reports.add(r);
                        tank.setCrewHitPS(true);
                    } else {
                        r = new Report(6190);
                        r.subject = tank.getId();
                        reports.add(r);
                        tank.getCrew().setDoomed(true);
                        if (tank.isAirborneVTOLorWIGE()) {
                            reports.addAll(crashVTOLorWiGE(tank));
                        }
                    }
                }
                break;
            case Tank.CRIT_ENGINE:
                r = new Report(6210);
                r.subject = tank.getId();
                reports.add(r);
                tank.engineHit();
                tank.engineHitsThisPhase++;
                boolean engineExploded = checkEngineExplosion(tank, reports, 1);
                if (engineExploded) {
                    reports.addAll(entityManager.destroyEntity(tank, "engine destruction", true, true));
                    tank.setSelfDestructing(false);
                    tank.setSelfDestructInitiated(false);
                }
                if (tank.isAirborneVTOLorWIGE() && !(tank.isDestroyed() || tank.isDoomed())) {
                    tank.immobilize();
                    reports.addAll(forceLandVTOLorWiGE(tank));
                }
                break;
            case Tank.CRIT_FUEL_TANK:
                r = new Report(6215);
                r.subject = tank.getId();
                reports.add(r);
                reports.addAll(entityManager.destroyEntity(tank, "fuel explosion", false, false));
                break;
            case Tank.CRIT_SENSOR:
                r = new Report(6620);
                r.subject = tank.getId();
                reports.add(r);
                tank.setSensorHits(tank.getSensorHits() + 1);
                break;
            case Tank.CRIT_STABILIZER:
                r = new Report(6625);
                r.subject = tank.getId();
                reports.add(r);
                tank.setStabiliserHit(loc);
                break;
            case Tank.CRIT_TURRET_DESTROYED:
                r = new Report(6630);
                r.subject = tank.getId();
                reports.add(r);
                tank.destroyLocation(tank.getLocTurret());
                reports.addAll(entityManager.destroyEntity(tank, "turret blown off", true, true));
                break;
            case Tank.CRIT_TURRET_JAM:
                if (tank.isTurretEverJammed(loc)) {
                    r = new Report(6640);
                    r.subject = tank.getId();
                    reports.add(r);
                    tank.lockTurret(loc);
                    break;
                }
                r = new Report(6635);
                r.subject = tank.getId();
                reports.add(r);
                tank.jamTurret(loc);
                break;
            case Tank.CRIT_TURRET_LOCK:
                r = new Report(6640);
                r.subject = tank.getId();
                reports.add(r);
                tank.lockTurret(loc);
                break;
            case Tank.CRIT_WEAPON_DESTROYED: {
                r = new Report(6305);
                r.subject = tank.getId();
                List<Mounted> weapons = new ArrayList<>();
                for (Mounted weapon : tank.getWeaponList()) {
                    if ((weapon.getLocation() == loc) && !weapon.isHit() && !weapon.isDestroyed()) {
                        weapons.add(weapon);
                    }
                }
                // sort weapons by BV
                weapons.sort(new WeaponComparatorBV());
                int roll = Compute.d6();
                Mounted weapon;
                if (roll < 4) {
                    // defender should choose, we'll just use the lowest BV
                    // weapon
                    weapon = weapons.get(weapons.size() - 1);
                } else {
                    // attacker chooses, we'll use the highest BV weapon
                    weapon = weapons.get(0);
                }
                r.add(weapon.getName());
                reports.add(r);
                // explosive weapons e.g. gauss now explode
                if (weapon.getType().isExplosive(weapon) && !weapon.isHit() && !weapon.isDestroyed()) {
                    reports.addAll(explodeEquipment(tank, loc, weapon));
                }
                weapon.setHit(true);
                //Taharqa: We should also damage the critical slot, or
                //MM and MHQ won't remember that this weapon is damaged on the MUL
                //file
                for (int i = 0; i < tank.getNumberOfCriticals(loc); i++) {
                    CriticalSlot slot1 = tank.getCritical(loc, i);
                    if ((slot1 == null) || (slot1.getType() == CriticalSlot.TYPE_SYSTEM)) {
                        continue;
                    }
                    Mounted mounted = slot1.getMount();
                    if (mounted.equals(weapon)) {
                        tank.hitAllCriticals(loc, i);
                        break;
                    }
                }
                break;
            }
            case Tank.CRIT_WEAPON_JAM: {
                r = new Report(6645);
                r.subject = tank.getId();
                ArrayList<Mounted> weapons = new ArrayList<>();
                for (Mounted weapon : tank.getWeaponList()) {
                    if ((weapon.getLocation() == loc) && !weapon.isJammed()
                        && !weapon.jammedThisPhase() && !weapon.isHit()
                        && !weapon.isDestroyed()) {
                        weapons.add(weapon);
                    }
                }
                if (weapons.size() > 0) {
                    Mounted weapon = weapons.get(Compute.randomInt(weapons.size()));
                    weapon.setJammed(true);
                    tank.addJammedWeapon(weapon);
                    r.add(weapon.getName());
                    reports.add(r);
                }
                break;
            }
            case VTOL.CRIT_PILOT:
                r = new Report(6650);
                r.subject = tank.getId();
                reports.add(r);
                tank.setDriverHit(true);
                PilotingRollData psr = tank.getBasePilotingRoll();
                psr.addModifier(0, "pilot injury");
                if (!doSkillCheckInPlace(tank, psr)) {
                    r = new Report(6675);
                    r.subject = tank.getId();
                    r.addDesc(tank);
                    reports.add(r);
                    boolean crash = true;
                    if (tank.canGoDown()) {
                        tank.setElevation(tank.getElevation() - 1);
                        crash = !tank.canGoDown();
                    }
                    if (crash) {
                        reports.addAll(crashVTOLorWiGE(tank));
                    }
                }
                break;
            case VTOL.CRIT_COPILOT:
                r = new Report(6655);
                r.subject = tank.getId();
                reports.add(r);
                tank.setCommanderHit(true);
                break;
            case VTOL.CRIT_ROTOR_DAMAGE: {
                // Only resolve rotor crits if the rotor was actually still
                // there.
                if (!(tank.isLocationBad(VTOL.LOC_ROTOR) || tank.isLocationDoomed(VTOL.LOC_ROTOR))) {
                    r = new Report(6660);
                    r.subject = tank.getId();
                    reports.add(r);
                    tank.setMotiveDamage(tank.getMotiveDamage() + 1);
                    if (tank.getMotiveDamage() >= tank.getOriginalWalkMP()) {
                        tank.immobilize();
                        if (tank.isAirborneVTOLorWIGE()
                            // Don't bother with forcing a landing if
                            // we're already otherwise destroyed.
                            && !(tank.isDestroyed() || tank.isDoomed())) {
                            reports.addAll(forceLandVTOLorWiGE(tank));
                        }
                    }
                }
                break;
            }
            case VTOL.CRIT_ROTOR_DESTROYED:
                // Only resolve rotor crits if the rotor was actually still
                // there. Note that despite the name this critical hit does
                // not in itself physically destroy the rotor *location*
                // (which would simply kill the VTOL).
                if (!(tank.isLocationBad(VTOL.LOC_ROTOR) || tank.isLocationDoomed(VTOL.LOC_ROTOR))) {
                    r = new Report(6670);
                    r.subject = tank.getId();
                    reports.add(r);
                    tank.immobilize();
                    reports.addAll(crashVTOLorWiGE(tank, true));
                }
                break;
            case VTOL.CRIT_FLIGHT_STABILIZER:
                // Only resolve rotor crits if the rotor was actually still there.
                if (!(tank.isLocationBad(VTOL.LOC_ROTOR) || tank.isLocationDoomed(VTOL.LOC_ROTOR))) {
                    r = new Report(6665);
                    r.subject = tank.getId();
                    reports.add(r);
                    tank.setStabiliserHit(VTOL.LOC_ROTOR);
                }
                break;
        }
        return reports;
    }

    /**
     * Rolls and resolves critical hits with a die roll modifier.
     */

    public Vector<Report> criticalEntity(Entity en, int loc, boolean isRear, int critMod, int damage) {
        return criticalEntity(en, loc, isRear, critMod, true, false, damage);
    }

    /**
     * Rolls and resolves critical hits with a die roll modifier.
     */

    public Vector<Report> criticalEntity(Entity en, int loc, boolean isRear, int critMod, int damage,
                                         boolean damagedByFire) {
        return criticalEntity(en, loc, isRear, critMod, true, false, damage, damagedByFire);
    }

    /**
     * Rolls one critical hit
     */
    public Vector<Report> oneCriticalEntity(Entity en, int loc, boolean isRear, int damage) {
        return criticalEntity(en, loc, isRear, 0, false, false, damage);
    }

    /**
     * Makes any roll required when an AirMech lands and resolve any damage or
     * skidding resulting from a failed roll. Updates final position and elevation.
     *
     * @param lam       the landing LAM
     * @param pos       the <code>Coords</code> of the landing hex
     * @param elevation the elevation from which the landing is attempted (usually 1, but may be higher
     *                          if the unit is forced to land due to insufficient movement
     * @param distance  the distance the unit moved in the turn prior to landing
     */
    public Vector<Report> landAirMech(LandAirMech lam, Coords pos, int elevation, int distance) {
        Vector<Report> vDesc = new Vector<>();

        lam.setPosition(pos);
        IHex hex = game.getBoard().getHex(pos);
        if (hex.containsTerrain(Terrains.BLDG_ELEV)) {
            lam.setElevation(hex.terrainLevel(Terrains.BLDG_ELEV));
        } else {
            lam.setElevation(0);
        }
        PilotingRollData psr = lam.checkAirMechLanding();
        if (psr.getValue() != TargetRoll.CHECK_FALSE
                && (0 > doSkillCheckWhileMoving(lam, elevation, pos, pos, psr, false))) {
            crashAirMech(lam, pos, elevation, distance, psr);
        }
        return vDesc;
    }

    public boolean crashAirMech(Entity en, PilotingRollData psr) {
        return crashAirMech(en, en.getPosition(), en.getElevation(), en.delta_distance, psr);
    }

    public boolean crashAirMech(Entity en, Coords pos, int elevation, int distance, PilotingRollData psr) {
        MoveStep step = new MoveStep(null, MoveStepType.DOWN);
        step.setFromEntity(en, game);
        return crashAirMech(en, pos, elevation, distance, psr, step);
    }

    public boolean crashAirMech(Entity en, Coords pos, int elevation, int distance,
                                 PilotingRollData psr, MoveStep lastStep) {
        reportmanager.addReport(doEntityFallsInto(en, elevation, pos, pos, psr, true, 0));
        return en.isDoomed() || processSkid(en, pos, 0, 0, distance, lastStep, en.moved, false);
    }

    /**
     * Makes the landing roll required for a glider ProtoMech and resolves any damage
     * resulting from a failed roll. Updates final position and elevation.
     *
     * @param en    the landing glider ProtoMech
     */
    public Vector<Report> landGliderPM(Protomech en) {
        return landGliderPM(en, en.getPosition(), en.getElevation(), en.delta_distance);
    }

    /**
     * Makes the landing roll required for a glider ProtoMech and resolves any damage
     * resulting from a failed roll. Updates final position and elevation.
     *
     * @param en    the landing glider ProtoMech
     * @param pos   the <code>Coords</code> of the landing hex
     * @param startElevation    the elevation from which the landing is attempted (usually 1, but may be higher
     *                          if the unit is forced to land due to insufficient movement
     * @param distance  the distance the unit moved in the turn prior to landing
     */
    public Vector<Report> landGliderPM(Protomech en, Coords pos, int startElevation, int distance) {
        Vector<Report> vDesc = new Vector<>();

        en.setPosition(pos);
        IHex hex = game.getBoard().getHex(pos);
        if (hex.containsTerrain(Terrains.BLDG_ELEV)) {
            en.setElevation(hex.terrainLevel(Terrains.BLDG_ELEV));
        } else {
            en.setElevation(0);
        }
        PilotingRollData psr = en.checkGliderLanding();
        if ((psr.getValue() != TargetRoll.CHECK_FALSE) && (0 > doSkillCheckWhileMoving(en, startElevation, pos, pos, psr, false))) {
            for (int i = 0; i < en.getNumberOfCriticals(Protomech.LOC_LEG); i++) {
                en.getCritical(Protomech.LOC_LEG, i).setHit(true);
            }
            HitData hit = new HitData(Protomech.LOC_LEG);
            vDesc.addAll(damageEntity(en, hit, 2 * startElevation));
        }
        return vDesc;
    }

    /**
     * Resolves the forced landing of one airborne {@code VTOL} or {@code WiGE}
     * in its current hex. As this method is only for internal use and not part
     * of the exported public API, it simply relies on its client code to only
     * ever hand it a valid airborne vehicle and does not run any further checks
     * of its own.
     *
     * @param en The {@code VTOL} or {@code WiGE} in question.
     * @return The resulting {@code Vector} of {@code Report}s.
     */
    private Vector<Report> forceLandVTOLorWiGE(Tank en) {
        Vector<Report> vDesc = new Vector<>();
        PilotingRollData psr = en.getBasePilotingRoll();
        IHex hex = game.getBoard().getHex(en.getPosition());
        if (en instanceof VTOL) {
            psr.addModifier(4, "VTOL making forced landing");
        } else {
            psr.addModifier(0, "WiGE making forced landing");
        }
        int elevation = Math.max(hex.terrainLevel(Terrains.BLDG_ELEV), hex.terrainLevel(Terrains.BRIDGE_ELEV));
        elevation = Math.max(elevation, 0);
        elevation = Math.min(elevation, en.getElevation());
        if (en.getElevation() > elevation) {
            if (!hex.containsTerrain(Terrains.FUEL_TANK)
                    && !hex.containsTerrain(Terrains.JUNGLE)
                    && !hex.containsTerrain(Terrains.MAGMA)
                    && !hex.containsTerrain(Terrains.MUD)
                    && !hex.containsTerrain(Terrains.RUBBLE)
                    && !hex.containsTerrain(Terrains.WATER)
                    && !hex.containsTerrain(Terrains.WOODS)) {
                Report r = new Report(2180);
                r.subject = en.getId();
                r.addDesc(en);
                r.add(psr.getLastPlainDesc(), true);
                vDesc.add(r);

                // roll
                final int diceRoll = Compute.d6(2);
                r = new Report(2185);
                r.subject = en.getId();
                r.add(psr.getValueAsString());
                r.add(psr.getDesc());
                r.add(diceRoll);
                if (diceRoll < psr.getValue()) {
                    r.choose(false);
                    vDesc.add(r);
                    vDesc.addAll(crashVTOLorWiGE(en, true));
                } else {
                    r.choose(true);
                    vDesc.add(r);
                    en.setElevation(elevation);
                }
            } else {
                vDesc.addAll(crashVTOLorWiGE(en, true));
            }
        }
        return vDesc;
    }

    /**
     * Crash a VTOL
     *
     * @param en the <code>VTOL</code> to be crashed
     * @return the <code>Vector<Report></code> containing phase reports
     */
    public Vector<Report> crashVTOLorWiGE(Tank en) {
        return crashVTOLorWiGE(en, false, false, 0, en.getPosition(),
                               en.getElevation(), 0);
    }

    /**
     * Crash a VTOL or WiGE.
     *
     * @param en              The {@code VTOL} or {@code WiGE} to crash.
     * @param rerollRotorHits Whether any rotor hits from the crash should be rerolled,
     *                        typically after a "rotor destroyed" critical hit.
     * @return The {@code Vector<Report>} of resulting reports.
     */
    public Vector<Report> crashVTOLorWiGE(Tank en, boolean rerollRotorHits) {
        return crashVTOLorWiGE(en, rerollRotorHits, false, 0, en.getPosition(),
                               en.getElevation(), 0);
    }

    /**
     * Crash a VTOL or WiGE.
     *
     * @param en              The {@code VTOL} or {@code WiGE} to crash.
     * @param rerollRotorHits Whether any rotor hits from the crash should be rerolled,
     *                        typically after a "rotor destroyed" critical hit.
     * @param sideSlipCrash   A <code>boolean</code> value indicating whether this is a
     *                        sideslip crash or not.
     * @param hexesMoved      The <code>int</code> number of hexes moved.
     * @param crashPos        The <code>Coords</code> of the crash
     * @param crashElevation  The <code>int</code> elevation of the VTOL
     * @param impactSide      The <code>int</code> describing the side on which the VTOL
     *                        falls
     * @return a <code>Vector<Report></code> of Reports.
     */
    public Vector<Report> crashVTOLorWiGE(Tank en, boolean rerollRotorHits, boolean sideSlipCrash, int hexesMoved,
                                           Coords crashPos, int crashElevation, int impactSide) {
        Vector<Report> vDesc = new Vector<>();
        Report r;

        // we might be off the board after a DFA, so return then
        if (!game.getBoard().contains(crashPos)) {
            return vDesc;
        }

        if (!sideSlipCrash) {
            // report lost movement and crashing
            r = new Report(6260);
            r.subject = en.getId();
            r.newlines = 0;
            r.addDesc(en);
            vDesc.addElement(r);
            int newElevation = 0;
            IHex fallHex = game.getBoard().getHex(crashPos);

            // May land on roof of building or bridge
            if (fallHex.containsTerrain(Terrains.BLDG_ELEV)) {
                newElevation = fallHex.terrainLevel(Terrains.BLDG_ELEV);
            } else if (fallHex.containsTerrain(Terrains.BRIDGE_ELEV)) {
                newElevation = fallHex.terrainLevel(Terrains.BRIDGE_ELEV);
                if (newElevation > crashElevation) {
                    newElevation = 0; // vtol was under bridge already
                }
            }

            int fall = crashElevation - newElevation;
            if (fall == 0) {
                // already on ground, no harm done
                r = new Report(6265);
                r.subject = en.getId();
                vDesc.addElement(r);
                return vDesc;
            }
            // set elevation 1st to avoid multiple crashes
            en.setElevation(newElevation);

            // plummets to ground
            r = new Report(6270);
            r.subject = en.getId();
            r.add(fall);
            vDesc.addElement(r);

            // facing after fall
            String side;
            int table;
            int facing = Compute.d6() - 1;
            switch (facing) {
                case 1:
                case 2:
                    side = "right side";
                    table = ToHitData.SIDE_RIGHT;
                    break;
                case 3:
                    side = "rear";
                    table = ToHitData.SIDE_REAR;
                    break;
                case 4:
                case 5:
                    side = "left side";
                    table = ToHitData.SIDE_LEFT;
                    break;
                case 0:
                default:
                    side = "front";
                    table = ToHitData.SIDE_FRONT;
            }

            if (newElevation <= 0) {
                boolean waterFall = fallHex.containsTerrain(Terrains.WATER);
                if (waterFall && fallHex.containsTerrain(Terrains.ICE)) {
                    int roll = Compute.d6(1);
                    r = new Report(2119);
                    r.subject = en.getId();
                    r.addDesc(en);
                    r.add(roll);
                    r.subject = en.getId();
                    vDesc.add(r);
                    if (roll > 3) {
                        vDesc.addAll(resolveIceBroken(crashPos));
                    } else {
                        waterFall = false; // saved by ice
                    }
                }
                if (waterFall) {
                    // falls into water and is destroyed
                    r = new Report(6275);
                    r.subject = en.getId();
                    vDesc.addElement(r);
                    vDesc.addAll(entityManager.destroyEntity(en, "Fell into water", false, false));
                    // not sure, is this salvageable?
                }
            }

            // calculate damage for hitting the surface
            int damage = (int) Math.round(en.getWeight() / 10.0) * (fall + 1);

            // adjust damage for gravity
            damage = Math.round(damage * game.getPlanetaryConditions().getGravity());
            // report falling
            r = new Report(6280);
            r.subject = en.getId();
            r.indent();
            r.addDesc(en);
            r.add(side);
            r.add(damage);
            vDesc.addElement(r);

            en.setFacing((en.getFacing() + (facing)) % 6);

            boolean exploded = false;

            // standard damage loop
            while (damage > 0) {
                int cluster = Math.min(5, damage);
                HitData hit = en.rollHitLocation(ToHitData.HIT_NORMAL, table);
                if ((en instanceof VTOL) && (hit.getLocation() == VTOL.LOC_ROTOR) && rerollRotorHits) {
                    continue;
                }
                hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                int[] isBefore = {en.getInternal(Tank.LOC_FRONT), en.getInternal(Tank.LOC_RIGHT),
                                  en.getInternal(Tank.LOC_LEFT), en.getInternal(Tank.LOC_REAR)};
                vDesc.addAll(damageEntity(en, hit, cluster));
                int[] isAfter = {en.getInternal(Tank.LOC_FRONT), en.getInternal(Tank.LOC_RIGHT),
                                 en.getInternal(Tank.LOC_LEFT), en.getInternal(Tank.LOC_REAR)};
                for (int x = 0; x <= 3; x++) {
                    if (isBefore[x] != isAfter[x]) {
                        exploded = true;
                        break;
                    }
                }
                damage -= cluster;
            }
            if (exploded) {
                r = new Report(6285);
                r.subject = en.getId();
                r.addDesc(en);
                vDesc.addElement(r);
                vDesc.addAll(explodeVTOLorWiGE(en));
            }

            // check for location exposure
            vDesc.addAll(doSetLocationsExposure(en, fallHex, false, newElevation));
        } else {
            en.setElevation(0);// considered landed in the hex.
            // crashes into ground thanks to sideslip
            r = new Report(6290);
            r.subject = en.getId();
            r.addDesc(en);
            vDesc.addElement(r);
            int damage = (int) Math.round(en.getWeight() / 10.0) * (hexesMoved + 1);
            boolean exploded = false;

            // standard damage loop
            while (damage > 0) {
                int cluster = Math.min(5, damage);
                HitData hit = en.rollHitLocation(ToHitData.HIT_NORMAL, impactSide);
                hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                int[] isBefore = {en.getInternal(Tank.LOC_FRONT), en.getInternal(Tank.LOC_RIGHT),
                                  en.getInternal(Tank.LOC_LEFT), en.getInternal(Tank.LOC_REAR)};
                vDesc.addAll(damageEntity(en, hit, cluster));
                int[] isAfter = {en.getInternal(Tank.LOC_FRONT), en.getInternal(Tank.LOC_RIGHT),
                                 en.getInternal(Tank.LOC_LEFT), en.getInternal(Tank.LOC_REAR)};
                for (int x = 0; x <= 3; x++) {
                    if (isBefore[x] != isAfter[x]) {
                        exploded = true;
                        break;
                    }
                }
                damage -= cluster;
            }
            if (exploded) {
                r = new Report(6295);
                r.subject = en.getId();
                r.addDesc(en);
                vDesc.addElement(r);
                vDesc.addAll(explodeVTOLorWiGE(en));
            }
        }

        if (game.containsMinefield(crashPos)) {
            // may set off any minefields in the hex
            enterMinefield(en, crashPos, 0, true, vDesc, 7);
            // it may also clear any minefields that it detonated
            gamemanager.clearDetonatedMines(game, crashPos, 5);
            gamemanager.resetMines(game);
        }
        return vDesc;
    }

    /**
     * Explode a VTOL
     *
     * @param en The <code>VTOL</code> to explode.
     * @return a <code>Vector</code> of reports
     */
    private Vector<Report> explodeVTOLorWiGE(Tank en) {
        Vector<Report> vDesc = new Vector<>();
        Report r;

        if(en.hasEngine() && en.getEngine().isFusion()) {
            // fusion engine, no effect
            r = new Report(6300);
            r.subject = en.getId();
            vDesc.addElement(r);
        } else {
            Coords pos = en.getPosition();
            IHex hex = game.getBoard().getHex(pos);
            if (hex.containsTerrain(Terrains.WOODS) || hex.containsTerrain(Terrains.JUNGLE)) {
                ignite(pos, Terrains.FIRE_LVL_NORMAL, vDesc);
            } else {
                ignite(pos, Terrains.FIRE_LVL_INFERNO, vDesc);
            }
            vDesc.addAll(entityManager.destroyEntity(en, "crashed and burned", false, false));
        }
        return vDesc;
    }

    /**
     * rolls and resolves one tank critical hit
     *
     * @param t       the <code>Tank</code> to be critted
     * @param loc     the <code>int</code> location of the Tank to be critted
     * @param critMod the <code>int</code> modifier to the crit roll
     * @return a <code>Vector<Report></code> containing the phase reports
     */
    private Vector<Report> criticalTank(Tank t, int loc, int critMod, int damage, boolean damagedByFire) {
        Vector<Report> vDesc = new Vector<>();

        // roll the critical
        Report r = new Report(6305);
        r.subject = t.getId();
        r.indent(3);
        r.add(t.getLocationAbbr(loc));
        r.newlines = 0;
        vDesc.add(r);
        int roll = Compute.d6(2);
        r = new Report(6310);
        r.subject = t.getId();
        r.add(ServerHelper.rollToString(critMod, roll));
        r.newlines = 0;
        vDesc.add(r);

        // now look up on vehicle crits table
        int critType = t.getCriticalEffect(roll, loc, damagedByFire);
        if ((critType == Tank.CRIT_NONE)
                && game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_VEHICLES_THRESHOLD)
                && !((t instanceof VTOL) || (t instanceof GunEmplacement))
                && !t.getOverThresh()) {
            r = new Report(6006);
            r.subject = t.getId();
            r.newlines = 0;
            vDesc.add(r);
        }
        vDesc.addAll(applyCriticalHit(t, loc, new CriticalSlot(0, critType),
                                      true, damage, false));
        if ((critType != Tank.CRIT_NONE) && t.hasEngine() && !t.getEngine().isFusion()
                && t.hasQuirk(OptionsConstants.QUIRK_NEG_FRAGILE_FUEL) && (Compute.d6(2) > 9)) {
            // BOOM!!
            vDesc.addAll(applyCriticalHit(t, loc, new CriticalSlot(0,
                    Tank.CRIT_FUEL_TANK), true, damage, false));
        }
        return vDesc;
    }

    /**
     * Checks for aero criticals
     *
     * @param vDesc         - report vector
     * @param a             - the entity being critted
     * @param hit           - the hitdata for the attack
     * @param damage_orig   - the original damage of the attack
     * @param critThresh    - did the attack go over the damage threshold
     * @param critSI        - did the attack damage SI
     * @param ammoExplosion - was the damage from an ammo explosion
     * @param nukeS2S       - was this a ship 2 ship nuke attack
     */
    private void checkAeroCrits(Vector<Report> vDesc, Aero a, HitData hit, int damage_orig, boolean critThresh,
                                boolean critSI, boolean ammoExplosion, boolean nukeS2S) {
        Report r;

        boolean isCapital = hit.isCapital();
        // get any capital missile critical mods
        int capitalMissile = hit.getCapMisCritMod();

        // check for nuclear critical
        if (nukeS2S) {
            // add a control roll
            PilotingRollData nukePSR = new PilotingRollData(a.getId(), 4, "Nuclear attack", false);
            game.addControlRoll(nukePSR);

            Report.addNewline(vDesc);
            // need some kind of report
            int nukeroll = Compute.d6(2);
            r = new Report(9145);
            r.subject = a.getId();
            r.indent(3);
            r.add(capitalMissile);
            r.add(nukeroll);
            vDesc.add(r);
            if (nukeroll >= capitalMissile) {
                // Allow a reroll with edge
                if (a.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_AERO_NUKE_CRIT)
                        && a.getCrew().hasEdgeRemaining()) {
                    a.getCrew().decreaseEdge();
                    r = new Report(9148);
                    r.subject = a.getId();
                    r.indent(3);
                    r.add(a.getCrew().getOptions().intOption(OptionsConstants.EDGE));
                    vDesc.add(r);
                    // Reroll
                    nukeroll = Compute.d6(2);
                    // and report the new results
                    r = new Report(9149);
                    r.subject = a.getId();
                    r.indent(3);
                    r.add(capitalMissile);
                    r.add(nukeroll);
                    r.choose(nukeroll >= capitalMissile);
                    vDesc.add(r);
                    if (nukeroll < capitalMissile) {
                        // We might be vaporized by the damage itself, but no additional effect
                        return;
                    }
                }
                a.setSI(a.getSI() - (damage_orig * 10));
                a.damageThisPhase += (damage_orig * 10);
                r = new Report(9146);
                r.subject = a.getId();
                r.add((damage_orig * 10));
                r.indent(4);
                r.add(Math.max(a.getSI(), 0));
                vDesc.addElement(r);
                if (a.getSI() <= 0) {
                    //No auto-ejection chance here. Nuke would vaporize the pilot.
                    vDesc.addAll(entityManager.destroyEntity(a, "Structural Integrity Collapse"));
                    a.setSI(0);
                    if (hit.getAttackerId() != Entity.NONE) {
                        game.creditKill(a, game.getEntity(hit.getAttackerId()));
                    }
                } else if (!critSI) {
                    critSI = true;
                }
            } else {
                r = new Report(9147);
                r.subject = a.getId();
                r.indent(4);
                vDesc.addElement(r);
            }
        }

        // apply crits
        if (hit.rolledBoxCars()) {
            if (hit.isFirstHit()) {
                // Allow edge use to ignore the critical roll
                if (a.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_AERO_LUCKY_CRIT)
                        && a.getCrew().hasEdgeRemaining()) {
                    a.getCrew().decreaseEdge();
                    r = new Report(9103);
                    r.subject = a.getId();
                    r.indent(3);
                    r.add(a.getCrew().getOptions().intOption(OptionsConstants.EDGE));
                    vDesc.addElement(r);
                    // Skip the critical roll
                    return;
                }
                vDesc.addAll(criticalAero(a, hit.getLocation(), hit.glancingMod(), "12 to hit",
                        8, damage_orig, isCapital));
            } else { // Let the user know why the lucky crit doesn't apply
                r = new Report(9102);
                r.subject = a.getId();
                r.indent(3);
                vDesc.addElement(r);
            }
        }
        // ammo explosions shouldn't affect threshold because they go right to SI
        if (critThresh && !ammoExplosion) {
            vDesc.addAll(criticalAero(a, hit.getLocation(), hit.glancingMod(), "Damage threshold exceeded", 8, damage_orig, isCapital));
        }
        if (critSI && !ammoExplosion) {
            vDesc.addAll(criticalAero(a, hit.getLocation(), hit.glancingMod(), "SI damaged", 8, damage_orig, isCapital));
        }
        if ((capitalMissile > 0) && !nukeS2S) {
            vDesc.addAll(criticalAero(a, hit.getLocation(), hit.glancingMod(), "Capital Missile", capitalMissile, damage_orig, isCapital));
        }
    }

    private Vector<Report> criticalAero(Aero a, int loc, int critMod, String reason, int target, int damage,
                                        boolean isCapital) {
        Vector<Report> vDesc = new Vector<>();

        //Telemissiles don't take critical hits
        if (a instanceof TeleMissile) {
            return vDesc;
        }

        // roll the critical
        Report r = new Report(9100);
        r.subject = a.getId();
        r.add(reason);
        r.indent(3);
        r.newlines = 0;
        vDesc.add(r);
        int roll = Compute.d6(2);
        r = new Report(9101);
        r.subject = a.getId();
        r.add(target);
        r.add(ServerHelper.rollToString(critMod, roll));
        r.newlines = 0;
        vDesc.add(r);

        // now look up on vehicle crits table
        int critType = a.getCriticalEffect(roll, target);
        vDesc.addAll(applyCriticalHit(a, loc, new CriticalSlot(0, critType),
                true, damage, isCapital));
        return vDesc;
    }

    /**
     * Rolls and resolves critical hits on mechs or vehicles. if rollNumber is
     * false, a single hit is applied - needed for MaxTech Heat Scale rule.
     */
    public Vector<Report> criticalEntity(Entity en, int loc, boolean isRear, int critMod, boolean rollNumber,
                                         boolean isCapital, int damage) {
        return criticalEntity(en, loc, isRear, critMod, rollNumber, isCapital, damage, false);
    }

    /**
     * Rolls and resolves critical hits on mechs or vehicles. if rollNumber is
     * false, a single hit is applied - needed for MaxTech Heat Scale rule.
     */
    public Vector<Report> criticalEntity(Entity en, int loc, boolean isRear,
            int critMod, boolean rollNumber, boolean isCapital, int damage,
            boolean damagedByFire) {

        if (en.hasQuirk("poor_work")) {
            critMod += 1;
        }
        if (en.hasQuirk(OptionsConstants.QUIRK_NEG_PROTOTYPE)) {
            critMod += 2;
        }

        // Apply modifiers for Anti-penetrative ablation armor
        if ((en.getArmor(loc, isRear) > 0) && (en.getArmorType(loc) == EquipmentType.T_ARMOR_ANTI_PENETRATIVE_ABLATION)) {
            critMod -= 2;
        }

        if (en instanceof Tank) {
            return criticalTank((Tank) en, loc, critMod, damage, damagedByFire);
        }

        if (en instanceof Aero) {
            return criticalAero((Aero) en, loc, critMod, "unknown", 8, damage, isCapital);
        }
        CriticalSlot slot;
        Vector<Report> vDesc = new Vector<>();
        Report r;
        Coords coords = en.getPosition();
        IHex hex = null;
        int hits;
        if (rollNumber) {
            if (null != coords) {
                hex = game.getBoard().getHex(coords);
            }
            r = new Report(6305);
            r.subject = en.getId();
            r.indent(3);
            r.add(en.getLocationAbbr(loc));
            r.newlines = 0;
            vDesc.addElement(r);
            hits = 0;
            int roll = Compute.d6(2);
            r = new Report(6310);
            r.subject = en.getId();
            String rollString = "";
            // industrials get a +2 bonus on the roll
            if ((en instanceof Mech) && ((Mech) en).isIndustrial()) {
                critMod += 2;
            }
            // reinforced structure gets a -1 mod
            if ((en instanceof Mech) && ((Mech) en).hasReinforcedStructure()) {
                critMod -= 1;
            }
            if (critMod != 0) {
                rollString = ServerHelper.rollToString(critMod, roll);
            } else {
                rollString += roll;
            }
            r.add(rollString);
            r.newlines = 0;
            vDesc.addElement(r);
            boolean advancedCrit = game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_CRIT_ROLL);
            if ((!advancedCrit && (roll <= 7)) || (advancedCrit && (roll <= 8))) {
                // no effect
                r = new Report(6005);
                r.subject = en.getId();
                vDesc.addElement(r);
                return vDesc;
            } else if (!advancedCrit && roll <= 9 || advancedCrit && roll <= 10) {
                hits = 1;
                r = new Report(6315);
                r.subject = en.getId();
                vDesc.addElement(r);
            } else if (!advancedCrit && roll <= 11 || advancedCrit && roll <= 12) {
                hits = 2;
                r = new Report(6320);
                r.subject = en.getId();
                vDesc.addElement(r);
            } else if (advancedCrit && roll <= 14) {
                hits = 3;
                r = new Report(6325);
                r.subject = en.getId();
                vDesc.addElement(r);
            } else {
                if (en instanceof Protomech) {
                    hits = 3;
                    r = new Report(6325);
                    r.subject = en.getId();
                    vDesc.addElement(r);
                } else if (en.locationIsLeg(loc)) {
                    CriticalSlot cs = en.getCritical(loc, 0);
                    if ((cs != null) && cs.isArmored()) {
                        r = new Report(6700);
                        r.subject = en.getId();
                        r.add(en.getLocationName(loc));
                        r.newlines = 0;
                        vDesc.addElement(r);
                        cs.setArmored(false);
                        return vDesc;
                    }
                    // limb blown off
                    r = new Report(6120);
                    r.subject = en.getId();
                    r.add(en.getLocationName(loc));
                    vDesc.addElement(r);
                    if (en.getInternal(loc) > 0) {
                        en.destroyLocation(loc, true);
                    }
                    if (null != hex) {
                        if (!hex.containsTerrain(Terrains.LEGS)) {
                            hex.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.LEGS, 1));
                        } else {
                            hex.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.LEGS,
                                    hex.terrainLevel(Terrains.LEGS) + 1));
                        }
                    }
                    gamemanager.sendChangedHex(game, en.getPosition());
                    return vDesc;
                } else if ((loc == Mech.LOC_RARM) || (loc == Mech.LOC_LARM)) {
                    CriticalSlot cs = en.getCritical(loc, 0);
                    if ((cs != null) && cs.isArmored()) {
                        r = new Report(6700);
                        r.subject = en.getId();
                        r.add(en.getLocationName(loc));
                        r.newlines = 0;
                        vDesc.addElement(r);
                        cs.setArmored(false);
                        return vDesc;
                    }

                    // limb blown off
                    r = new Report(6120);
                    r.subject = en.getId();
                    r.add(en.getLocationName(loc));
                    vDesc.addElement(r);
                    en.destroyLocation(loc, true);
                    if (null != hex) {
                        if (!hex.containsTerrain(Terrains.ARMS)) {
                            hex.addTerrain(Terrains.getTerrainFactory()
                                    .createTerrain(Terrains.ARMS, 1));
                        } else {
                            hex.addTerrain(Terrains.getTerrainFactory()
                                    .createTerrain(Terrains.ARMS,
                                            hex.terrainLevel(Terrains.ARMS) + 1));
                        }
                    }
                    gamemanager.sendChangedHex(game, en.getPosition());
                    return vDesc;
                } else if (loc == Mech.LOC_HEAD) {
                    // head blown off
                    r = new Report(6330);
                    r.subject = en.getId();
                    r.add(en.getLocationName(loc));
                    vDesc.addElement(r);
                    en.destroyLocation(loc, true);
                    if (((Mech) en).getCockpitType() != Mech.COCKPIT_TORSO_MOUNTED) {
                        // Don't kill a pilot multiple times.
                        if (Crew.DEATH > en.getCrew().getHits()) {
                            en.getCrew().setDoomed(true);
                            Report.addNewline(vDesc);
                            vDesc.addAll(entityManager.destroyEntity(en, "pilot death", true));
                        }
                    }
                    return vDesc;
                } else {
                    // torso hit
                    hits = 3;
                    // industrials get 4 crits on a modified result of 14
                    if ((roll >= 14) && (en instanceof Mech) && ((Mech) en).isIndustrial()) {
                        hits = 4;
                    }
                    r = new Report(6325);
                    r.subject = en.getId();
                    vDesc.addElement(r);
                }
            }
        } else {
            hits = 1;
        }

        // Check if there is the potential for a reactive armor crit
        // Because reactive armor isn't hittable, the transfer check doesn't
        // consider it
        boolean possibleReactiveCrit = (en.getArmor(loc) > 0) && (en.getArmorType(loc) == EquipmentType.T_ARMOR_REACTIVE);
        boolean locContainsReactiveArmor = false;
        for (int i = 0; (i < en.getNumberOfCriticals(loc)) && possibleReactiveCrit; i++) {
            CriticalSlot crit = en.getCritical(loc, i);
            if ((crit != null) && (crit.getType() == CriticalSlot.TYPE_EQUIPMENT) && (crit.getMount() != null) && crit.getMount().getType().hasFlag(MiscType.F_REACTIVE)) {
                locContainsReactiveArmor = true;
                break;
            }
        }
        possibleReactiveCrit &= locContainsReactiveArmor;

        // transfer criticals, if needed
        while ((en.canTransferCriticals(loc) && !possibleReactiveCrit)
                && (en.getTransferLocation(loc) != Entity.LOC_DESTROYED)
                && (en.getTransferLocation(loc) != Entity.LOC_NONE)) {
            loc = en.getTransferLocation(loc);
            r = new Report(6335);
            r.subject = en.getId();
            r.indent(3);
            r.add(en.getLocationAbbr(loc));
            vDesc.addElement(r);
        }

        // Roll critical hits in this location.
        while (hits > 0) {
            // Have we hit all available slots in this location?
            if (en.getHittableCriticals(loc) <= 0) {
                r = new Report(6340);
                r.subject = en.getId();
                r.indent(3);
                vDesc.addElement(r);
                break;
            }

            // Randomly pick a slot to be hit.
            int slotIndex = Compute.randomInt(en.getNumberOfCriticals(loc));
            slot = en.getCritical(loc, slotIndex);

            // There are certain special cases, like reactive armor
            // some crits aren't normally hittable, except in certain cases
            boolean reactiveArmorCrit = false;
            if ((slot != null) && (slot.getType() == CriticalSlot.TYPE_EQUIPMENT) && (slot.getMount() != null)) {
                Mounted eq = slot.getMount();
                if (eq.getType().hasFlag(MiscType.F_REACTIVE) && (en.getArmor(loc) > 0)) {
                    reactiveArmorCrit = true;
                }
            }

            // Ignore empty or unhitable slots (this includes all previously hit slots).
            if ((slot != null) && (slot.isHittable() || reactiveArmorCrit)) {
                if (slot.isArmored()) {
                    r = new Report(6710);
                    r.subject = en.getId();
                    if (slot.getType() == CriticalSlot.TYPE_SYSTEM) {
                        // Pretty sure that only 'mechs have system crits,
                        // but just in case....
                        if (en instanceof Mech) {
                            r.add(((Mech) en).getSystemName(slot.getIndex()));
                        }
                    } else {
                        // Shouldn't be null, but we'll be careful...
                        if (slot.getMount() != null) {
                            r.add(slot.getMount().getName());
                        }
                    }
                    vDesc.addElement(r);
                    slot.setArmored(false);
                    hits--;
                    continue;
                }
                // if explosive use edge
                if ((en instanceof Mech)
                        && (en.getCrew().hasEdgeRemaining() && en.getCrew().getOptions()
                                .booleanOption(OptionsConstants.EDGE_WHEN_EXPLOSION))
                        && (slot.getType() == CriticalSlot.TYPE_EQUIPMENT)
                        && slot.getMount().getType().isExplosive(slot.getMount())) {
                    en.getCrew().decreaseEdge();
                    r = new Report(6530);
                    r.subject = en.getId();
                    r.indent(3);
                    r.add(en.getCrew().getOptions().intOption(OptionsConstants.EDGE));
                    vDesc.addElement(r);
                    continue;
                }

                // check for reactive armor exploding
                if (reactiveArmorCrit) {
                    Mounted mount = slot.getMount();
                    if ((mount != null) && mount.getType().hasFlag(MiscType.F_REACTIVE)) {
                        int roll = Compute.d6(2);
                        r = new Report(6082);
                        r.subject = en.getId();
                        r.indent(3);
                        r.add(roll);
                        vDesc.addElement(r);
                        // big budda boom
                        if (roll == 2) {
                            r = new Report(6083);
                            r.subject = en.getId();
                            r.indent(4);
                            vDesc.addElement(r);
                            Vector<Report> newReports = new Vector<>(damageEntity(en,
                                    new HitData(loc), en.getArmor(loc)));
                            if (en.hasRearArmor(loc)) {
                                newReports.addAll(damageEntity(en, new HitData(loc, true),
                                        en.getArmor(loc, true)));
                            }
                            newReports.addAll(damageEntity(en, new HitData(loc), 1));
                            for (Report rep : newReports) {
                                rep.indent(4);
                            }
                            vDesc.addAll(newReports);
                        } else {
                            // If only hittable crits are reactive,
                            // this crit is absorbed
                            boolean allHittableCritsReactive = true;
                            for (int i = 0; i < en.getNumberOfCriticals(loc); i++) {
                                CriticalSlot crit = en.getCritical(loc, i);
                                if (crit.isHittable()) {
                                    allHittableCritsReactive = false;
                                    break;
                                }
                                // We must have reactive crits to get to this
                                // point, so if nothing else is hittable, we
                                // must only have reactive crits
                            }
                            if (allHittableCritsReactive) {
                                hits--;
                            }
                            continue;
                        }
                    }
                }
                vDesc.addAll(applyCriticalHit(en, loc, slot, true, damage, isCapital));
                hits--;
            }
        } // Hit another slot in this location.

        return vDesc;
    }

    /**
     * Checks for location breach and returns phase logging.
     * <p/>
     *
     * @param entity the <code>Entity</code> that needs to be checked.
     * @param loc    the <code>int</code> location on the entity that needs to be
     *               checked for a breach.
     * @param hex    the <code>IHex</code> the entity occupies when checking. This
     *               value will be <code>null</code> if the check is the result of
     *               an attack, and non-null if it occurs during movement.
     */
    private Vector<Report> breachCheck(Entity entity, int loc, IHex hex) {
        return breachCheck(entity, loc, hex, false);
    }

    /**
     * Checks for location breach and returns phase logging.
     * <p/>
     *
     * @param entity     the <code>Entity</code> that needs to be checked.
     * @param loc        the <code>int</code> location on the entity that needs to be
     *                   checked for a breach.
     * @param hex        the <code>IHex</code> the entity occupies when checking. This
     *                   value will be <code>null</code> if the check is the result of
     *                   an attack, and non-null if it occurs during movement.
     * @param underWater Is the breach check a result of an underwater attack?
     */
    private Vector<Report> breachCheck(Entity entity, int loc, IHex hex, boolean underWater) {
        Vector<Report> vDesc = new Vector<>();
        Report r;

        // Infantry do not suffer breaches, nor do Telemissiles
        // VTOLs can't operate in vacuum or underwater, so no breaches
        if (entity instanceof Infantry || entity instanceof TeleMissile || entity instanceof VTOL) {
            return vDesc;
        }

        boolean dumping = false;
        for (Mounted m : entity.getAmmo()) {
            if (m.isDumping()) {
                // dumping ammo underwater is very stupid thing to do
                dumping = true;
                break;
            }
        }
        // This handles both water and vacuum breaches.
        // Also need to account for hull breaches on surface naval vessels which
        // are technically not "wet"
        if ((entity.getLocationStatus(loc) > ILocationExposureStatus.NORMAL)
                || (entity.isSurfaceNaval() && (loc != ((Tank) entity).getLocTurret()))) {
            // Does the location have armor (check rear armor on Mek)
            // and is the check due to damage?
            int breachroll = 0;
            // set the target roll for the breach
            int target = 10;
            // if this is a vacuum check and we are in trace atmosphere then
            // adjust target
            if ((entity.getLocationStatus(loc) == ILocationExposureStatus.VACUUM)
                    && (game.getPlanetaryConditions().getAtmosphere() == PlanetaryConditions.ATMO_TRACE)) {
                target = 12;
            }
            // if this is a surface naval vessel and the attack is not from
            // underwater
            // then the breach should only occur on a roll of 12
            if (entity.isSurfaceNaval() && !underWater) {
                target = 12;
            }
            if ((entity.getArmor(loc) > 0)
                    && (!(entity instanceof Mech) || entity.getArmor(loc, true) > 0) && (null == hex)) {
                // functional HarJel prevents breach
                if (entity.hasHarJelIn(loc)) {
                    r = new Report(6342);
                    r.subject = entity.getId();
                    r.indent(3);
                    vDesc.addElement(r);
                    return vDesc;
                }
                if ((entity instanceof Mech) && (((Mech) entity).hasHarJelIIIn(loc)
                        || ((Mech) entity).hasHarJelIIIIn(loc))) {
                    r = new Report(6343);
                    r.subject = entity.getId();
                    r.indent(3);
                    vDesc.addElement(r);
                    target -= 2;
                }
                // Impact-resistant armor easier to breach
                if ((entity.getArmorType(loc) == EquipmentType.T_ARMOR_IMPACT_RESISTANT)) {
                    r = new Report(6344);
                    r.subject = entity.getId();
                    r.indent(3);
                    vDesc.addElement(r);
                    target += 1;
                }
                breachroll = Compute.d6(2);
                r = new Report(6345);
                r.subject = entity.getId();
                r.indent(3);
                r.add(entity.getLocationAbbr(loc));
                r.add(breachroll);
                r.newlines = 0;
                r.choose(breachroll < target);
                vDesc.addElement(r);
            }
            // Breach by damage or lack of armor.
            if ((breachroll >= target) || !(entity.getArmor(loc) > 0)
                    || (dumping && (!(entity instanceof Mech)
                    || (loc == Mech.LOC_CT) || (loc == Mech.LOC_RT) || (loc == Mech.LOC_LT)))
                    || !(!(entity instanceof Mech) || entity.getArmor(loc, true) > 0)) {
                // Functional HarJel prevents breach as long as armor remains
                // (and, presumably, as long as you don't open your chassis on
                // purpose, say to dump ammo...).
                if ((entity.hasHarJelIn(loc)) && (entity.getArmor(loc) > 0)
                    && (!(entity instanceof Mech) || entity.getArmor(loc, true) > 0) && !dumping) {
                    r = new Report(6342);
                    r.subject = entity.getId();
                    r.indent(3);
                    vDesc.addElement(r);
                    return vDesc;
                }
                vDesc.addAll(breachLocation(entity, loc, hex, false));
            }
        }
        return vDesc;
    }

    /**
     * Marks all equipment in a location on an entity as useless.
     *
     * @param entity the <code>Entity</code> that needs to be checked.
     * @param loc    the <code>int</code> location on the entity that needs to be
     *               checked for a breach.
     * @param hex    the <code>IHex</code> the entity occupies when checking. This
     *               value will be <code>null</code> if the check is the result of
     *               an attack, and non-null if it occurs during movement.
     * @param harJel a <code>boolean</code> value indicating if the uselessness is
     *               the cause of a critically hit HarJel system
     */
    private Vector<Report> breachLocation(Entity entity, int loc, IHex hex, boolean harJel) {
        Vector<Report> vDesc = new Vector<>();

        if ((entity.getInternal(loc) < 0) || (entity.getLocationStatus(loc) < ILocationExposureStatus.NORMAL)) {
            // already destroyed or breached? don't bother
            return vDesc;
        }

        Report r = new Report(6350);
        if (harJel) {
            r.messageId = 6351;
        }
        r.subject = entity.getId();
        r.add(entity.getShortName());
        r.add(entity.getLocationAbbr(loc));
        vDesc.addElement(r);

        if (entity instanceof Tank) {
            vDesc.addAll(entityManager.destroyEntity(entity, "hull breach", true, true));
            return vDesc;
        }
        if (entity instanceof Mech) {
            Mech mech = (Mech) entity;
            // equipment and crits will be marked in applyDamage?

            // equipment marked missing
            for (Mounted mounted : entity.getEquipment()) {
                if (mounted.getLocation() == loc) {
                    mounted.setBreached(true);
                }
            }
            // all critical slots set as useless
            for (int i = 0; i < entity.getNumberOfCriticals(loc); i++) {
                final CriticalSlot cs = entity.getCritical(loc, i);
                if (cs != null) {
                    // for every undamaged actuator destroyed by breaching,
                    // we make a PSR (see bug 1040858)
                    if (entity.locationIsLeg(loc) && entity.canFall(true)) {
                        if (cs.isHittable()) {
                            switch (cs.getIndex()) {
                                case Mech.ACTUATOR_UPPER_LEG:
                                case Mech.ACTUATOR_LOWER_LEG:
                                case Mech.ACTUATOR_FOOT:
                                    // leg/foot actuator piloting roll
                                    game.addPSR(new PilotingRollData(entity.getId(), 1,
                                            "leg/foot actuator hit"));
                                    break;
                                case Mech.ACTUATOR_HIP:
                                    // hip piloting roll at +0, because we get the +2 anyway
                                    // because the location is breached.
                                    // The phase report will look a bit weird, but the
                                    // roll is correct
                                    game.addPSR(new PilotingRollData(entity.getId(), 0,
                                            "hip actuator hit"));
                                    break;
                            }
                        }
                    }
                    cs.setBreached(true);
                }
            }

            // Check location for engine/cockpit breach and report accordingly
            if (loc == Mech.LOC_CT) {
                vDesc.addAll(entityManager.destroyEntity(entity, "hull breach"));
                if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_AUTO_ABANDON_UNIT)) {
                    vDesc.addAll(abandonEntity(entity));
                }
            }
            if (loc == Mech.LOC_HEAD) {
                entity.getCrew().setDoomed(true);
                vDesc.addAll(entityManager.destroyEntity(entity, "hull breach"));
                if (entity.getLocationStatus(loc) == ILocationExposureStatus.WET) {
                    r = new Report(6355);
                } else {
                    r = new Report(6360);
                }
                r.subject = entity.getId();
                r.addDesc(entity);
                vDesc.addElement(r);
            }

            // Set the status of the location.
            // N.B. if we set the status before rolling water PSRs, we get a
            // "LEG DESTROYED" modifier; setting the status after gives a hip
            // actuator modifier.
            entity.setLocationStatus(loc, ILocationExposureStatus.BREACHED);

            // Did the hull breach destroy the engine?
            int hitsToDestroy = 3;
            if (mech.isSuperHeavy() && mech.hasEngine() && (mech.getEngine().getEngineType() == Engine.COMPACT_ENGINE)) {
                hitsToDestroy = 2;
            }
            if ((entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_ENGINE, Mech.LOC_LT)
                    + entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_ENGINE, Mech.LOC_CT)
                    + entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_ENGINE, Mech.LOC_RT))
                    >= hitsToDestroy) {
                vDesc.addAll(entityManager.destroyEntity(entity, "engine destruction"));
                if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_AUTO_ABANDON_UNIT)) {
                    vDesc.addAll(abandonEntity(entity));
                }
            }

            if (loc == Mech.LOC_LT) {
                vDesc.addAll(breachLocation(entity, Mech.LOC_LARM, hex, false));
            }
            if (loc == Mech.LOC_RT) {
                vDesc.addAll(breachLocation(entity, Mech.LOC_RARM, hex, false));
            }
        }
        return vDesc;
    }

    /**
     * Marks a unit as destroyed! Units transported inside the destroyed unit
     * will get a chance to escape unless the destruction was not survivable.
     *
     * @param entity     - the <code>Entity</code> that has been destroyed.
     * @param reason     - a <code>String</code> detailing why the entity was
     *                   destroyed.
     * @param survivable - a <code>boolean</code> that identifies the destruction as
     *                   unsurvivable for transported units.
     * @return a <code>Vector</code> of <code>Report</code> objects that can be
     * sent to the output log.
     */
    public Vector<Report> destroyEntity(Entity entity, String reason, boolean survivable) {
        // Generally, the entity can still be salvaged.
        return destroyEntity(entity, reason, survivable, true);
    }

    /**
     * Marks a unit as destroyed! Units transported inside the destroyed unit
     * will get a chance to escape unless the destruction was not survivable.
     *
     * @param entity     - the <code>Entity</code> that has been destroyed.
     * @param reason     - a <code>String</code> detailing why the entity was
     *                   destroyed.
     * @param survivable - a <code>boolean</code> that identifies the destruction as
     *                   unsurvivable for transported units.
     * @param canSalvage - a <code>boolean</code> that indicates if the unit can be
     *                   salvaged (or cannibalized for spare parts). If
     *                   <code>true</code>, salvage operations are possible, if
     *                   <code>false</code>, the unit is too badly damaged.
     * @return a <code>Vector</code> of <code>Report</code> objects that can be
     * sent to the output log.
     */
    public Vector<Report> destroyEntity(Entity entity, String reason, boolean survivable, boolean canSalvage){
        return entityManager.destroyEntity(entity, reason, survivable, canSalvage);
    }

    /**
     * Makes a piece of equipment on a mech explode! POW! This expects either
     * ammo, or an explosive weapon. Returns a vector of Report objects.
     */
    private Vector<Report> explodeEquipment(Entity en, int loc, int slot) {
        CriticalSlot critSlot = en.getCritical(loc, slot);
        Vector<Report> reports = explodeEquipment(en, loc, critSlot.getMount());
        if (critSlot.getMount2() != null) {
            reports.addAll(explodeEquipment(en, loc, critSlot.getMount2()));
        }
        return reports;
    }

    /**
     * Explodes a piece of equipment on the unit.
     */
    public Vector<Report> explodeEquipment(Entity en, int loc, Mounted mounted) {
        return explodeEquipment(en, loc, mounted, false);
    }

    /**
     * Makes a piece of equipment on a mech explode! POW! This expects either
     * ammo, or an explosive weapon. Returns a vector of Report objects.
     * Possible to override 'is explosive' check
     */
    public Vector<Report> explodeEquipment(Entity en, int loc, Mounted mounted, boolean overrideExplosiveCheck) {
        Vector<Report> vDesc = new Vector<>();
        // is this already destroyed?
        if (mounted.isDestroyed()) {
            MegaMek.getLogger().error("Called on destroyed equipment(" + mounted.getName() + ")");
            return vDesc;
        }

        // Special case: LAM bomb bays explode the bomb stored there, which may involve going through a
        // launch weapon to the bomb ammo.
        if ((mounted.getType() instanceof MiscType) && mounted.getType().hasFlag(MiscType.F_BOMB_BAY)) {
            while (mounted.getLinked() != null) {
                mounted = mounted.getLinked();
            }
            // Fuel tank explodes on 2d6 roll of 10+
            if ((mounted.getType() instanceof MiscType) && mounted.getType().hasFlag(MiscType.F_FUEL)) {
                Report r = new Report(9120);
                r.subject = en.getId();
                int boomTarget = 10;
                // check for possible explosion
                int fuelRoll = Compute.d6(2);
                r.choose(fuelRoll >= boomTarget);
                if (fuelRoll >= boomTarget) {
                    r.choose(true);
                    vDesc.add(r);
                } else {
                    r.choose(false);
                    vDesc.add(r);
                    return vDesc;
                }
            }
        }

        if(!overrideExplosiveCheck && !mounted.getType().isExplosive(mounted, false)) {
            return vDesc;
        }

        // Inferno ammo causes heat buildup as well as the damage
        if ((mounted.getType() instanceof AmmoType)
                && ((((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_SRM)
                        || (((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_SRM_IMP)
                        || (((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_IATM)
                        || (((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_MML))
                && (((AmmoType) mounted.getType()).getMunitionType() == AmmoType.M_INFERNO)
                && (mounted.getHittableShotsLeft() > 0)) {
            en.heatBuildup += Math.min(mounted.getExplosionDamage(), 30);
        }

        // Inferno bombs in LAM bomb bays
        if ((mounted.getType() instanceof BombType)
                && (((BombType)mounted.getType()).getBombType() == BombType.B_INFERNO)) {
            en.heatBuildup += Math.min(mounted.getExplosionDamage(), 30);
        }

        // determine and deal damage
        int damage = mounted.getExplosionDamage();

        // Smoke ammo halves damage
        if ((mounted.getType() instanceof AmmoType)
                && ((((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_SRM)
                        || (((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_SRM_IMP)
                        || (((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_LRM)
                        || (((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_LRM_IMP))
                && (((AmmoType) mounted.getType()).getMunitionType() == AmmoType.M_SMOKE_WARHEAD)
                && (mounted.getHittableShotsLeft() > 0)) {
            damage = ((mounted.getExplosionDamage()) / 2);
        }
        // coolant explodes for 2 damage and reduces heat by 3
        if ((mounted.getType() instanceof AmmoType)
                && ((((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_VEHICLE_FLAMER)
                || (((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_HEAVY_FLAMER))
                && (((AmmoType) mounted.getType()).getMunitionType() == AmmoType.M_COOLANT)
                && (mounted.getHittableShotsLeft() > 0)) {
            damage = 2;
            en.coolFromExternal += 3;
        }

        // divide damage by 10 for aeros, per TW rules on pg. 161
        if (en instanceof Aero) {
            int newDamage = (int) Math.floor(damage / 10.0);
            if ((newDamage == 0) && (damage > 0)) {
                damage = 1;
            } else {
                damage = newDamage;
            }
        }

        if (damage <= 0) {
            return vDesc;
        }

        Report r = new Report(6390);
        r.subject = en.getId();
        r.add(mounted.getName());
        r.add(damage);
        r.indent(3);
        vDesc.addElement(r);
        // Mounted is a weapon and has Hot-Loaded ammo in it and it exploded now
        // we need to roll for chain reaction
        if ((mounted.getType() instanceof WeaponType) && mounted.isHotLoaded()) {
            int roll = Compute.d6(2);
            int ammoExploded = 0;
            r = new Report(6077);
            r.subject = en.getId();
            r.add(roll);
            r.indent(2);
            vDesc.addElement(r);

            // roll of 2-5 means a chain reaction happened
            if (roll < 6) {
                for (Mounted ammo : en.getAmmo()) {
                    if ((ammo.getLocation() == loc) && (ammo.getExplosionDamage() > 0)
                            // Dead-Fire ammo bins are designed not to explode from the chain reaction
                            // Of Critted Launchers with DFM or HotLoaded ammo.
                            && (((AmmoType) ammo.getType()).getMunitionType() != AmmoType.M_DEAD_FIRE)) {
                        ammoExploded++;
                        vDesc.addAll(this.explodeEquipment(en, loc, ammo));
                        break;
                    }
                }
                if (ammoExploded == 0) {
                    r = new Report(6078);
                    r.subject = en.getId();
                    r.indent(2);
                    vDesc.addElement(r);
                }
            } else {
                r = new Report(6079);
                r.subject = en.getId();
                r.indent(2);
                vDesc.addElement(r);
            }
        }

        HitData hit = new HitData(loc);
        // check to determine whether this is capital scale if we have a capital
        // scale entity
        if (mounted.getType() instanceof AmmoType) {
            if (((AmmoType) mounted.getType()).isCapital()) {
                hit.setCapital(true);
            }
        }

        // exploding RISC laser pulse module should cause no normal crits, just
        // automatically crit the first uncritted crit of the laser it's attached to
        if ((mounted.getType() instanceof MiscType)  && mounted.getType().hasFlag(MiscType.F_RISC_LASER_PULSE_MODULE)) {
            hit.setEffect(HitData.EFFECT_NO_CRITICALS);
            Mounted laser = mounted.getLinkedBy();
            if (en instanceof Mech) {
                for (int slot = 0; slot < en.getNumberOfCriticals(laser.getLocation()); slot++) {
                    CriticalSlot cs = en.getCritical(laser.getLocation(), slot);
                    if ((cs.getType() == CriticalSlot.TYPE_EQUIPMENT) && cs.getMount().equals(laser)
                            && cs.isHittable()) {
                        cs.setHit(true);
                        cs.setRepairable(true);
                        break;
                    }
                }
            }
            laser.setHit(true);
        }

        mounted.setShotsLeft(0);

        int pilotDamage = 2;
        if (en instanceof Aero) {
            pilotDamage = 1;
        }
        if (game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_CASE_PILOT_DAMAGE)
                && (en.locationHasCase(hit.getLocation()) || en.hasCASEII(hit.getLocation()))) {
            pilotDamage = 1;
        }
        if (en.hasAbility(OptionsConstants.MISC_PAIN_RESISTANCE)
                || en.hasAbility(OptionsConstants.MISC_IRON_MAN)) {
            pilotDamage -= 1;
        }
        // tanks only take pilot damage when using BVDNI or VDNI
        if ((en instanceof Tank) && !(en.hasAbility(OptionsConstants.MD_VDNI)
                || en.hasAbility(OptionsConstants.MD_BVDNI))) {
            pilotDamage = 0;
        }
        if (!en.hasAbility(OptionsConstants.MD_PAIN_SHUNT)) {
            vDesc.addAll(damageCrew(en, pilotDamage, en.getCrew().getCurrentPilotIndex()));
        }
        if (en.getCrew().isDoomed() || en.getCrew().isDead()) {
            vDesc.addAll(entityManager.destroyEntity(en, "crew death", true));
        } else {
            Report.addNewline(vDesc);
        }

        Vector<Report> newReports = damageEntity(en, hit, damage, true);
        for (Report rep : newReports) {
            rep.indent(2);
        }
        vDesc.addAll(newReports);
        Report.addNewline(vDesc);

        return vDesc;
    }

    /**
     * Makes one slot of ammo, determined by certain rules, explode on a mech.
     */
    Vector<Report> explodeAmmoFromHeat(Entity entity) {
        int damage = 0;
        int rack = 0;
        int boomloc = -1;
        int boomslot = -1;
        Vector<Report> vDesc = new Vector<>();

        for (int j = 0; j < entity.locations(); j++) {
            for (int k = 0; k < entity.getNumberOfCriticals(j); k++) {
                CriticalSlot cs = entity.getCritical(j, k);
                if ((cs == null) || cs.isDestroyed() || cs.isHit()
                        || (cs.getType() != CriticalSlot.TYPE_EQUIPMENT)) {
                    continue;
                }
                Mounted mounted = cs.getMount();
                if ((mounted == null) || (!(mounted.getType() instanceof AmmoType))) {
                    continue;
                }
                AmmoType atype = (AmmoType) mounted.getType();
                if (!atype.isExplosive(mounted)) {
                    continue;
                }
                // coolant pods and flamer coolant ammo don't explode from heat
                if ((atype.getAmmoType() == AmmoType.T_COOLANT_POD)
                        || (((atype.getAmmoType() == AmmoType.T_VEHICLE_FLAMER)
                        || (atype.getAmmoType() == AmmoType.T_HEAVY_FLAMER))
                        && (atype.getMunitionType() == AmmoType.M_COOLANT))) {
                    continue;
                }
                // ignore empty, destroyed, or missing bins
                if (mounted.getHittableShotsLeft() == 0) {
                    continue;
                }
                // TW page 160, compare one rack's damage. Ties go to most rounds.
                int newRack = atype.getDamagePerShot() * atype.getRackSize();
                int newDamage = mounted.getExplosionDamage();
                Mounted mount2 = cs.getMount2();
                if ((mount2 != null) && (mount2.getType() instanceof AmmoType)
                        && (mount2.getHittableShotsLeft() > 0)) {
                    // must be for same weaponType, so rackSize stays
                    atype = (AmmoType) mount2.getType();
                    newRack += atype.getDamagePerShot() * atype.getRackSize();
                    newDamage += mount2.getExplosionDamage();
                }
                if (!mounted.isHit() && ((rack < newRack) || ((rack == newRack) && (damage < newDamage)))) {
                    rack = newRack;
                    damage = newDamage;
                    boomloc = j;
                    boomslot = k;
                }
            }
        }
        if ((boomloc != -1) && (boomslot != -1)) {
            CriticalSlot slot = entity.getCritical(boomloc, boomslot);
            slot.setHit(true);
            slot.getMount().setHit(true);
            if (slot.getMount2() != null) {
                slot.getMount2().setHit(true);
            }
            vDesc.addAll(explodeEquipment(entity, boomloc, boomslot));
        } else {
            // Luckily, there is no ammo to explode.
            Report r = new Report(5105);
            r.subject = entity.getId();
            r.indent();
            vDesc.addElement(r);
        }
        return vDesc;
    }

    /**
     * Makes a mech fall.
     *
     * @param entity
     *            The Entity that is falling. It is expected that the Entity's
     *            position and elevation reflect the state prior to the fall
     * @param fallPos
     *            The location that the Entity is falling into.
     * @param fallHeight
     *            The height that Entity is falling.
     * @param facing
     *            The facing of the fall. Used to determine the the hit location
     *            and also determines facing after the fall (used as an offset
     *            of the Entity's current facing).
     * @param roll
     *            The PSR required to avoid damage to the pilot/crew.
     * @param intoBasement
     *            Flag that determines whether this is a fall into a basement or
     *            not.
     */
    public Vector<Report> doEntityFall(Entity entity, Coords fallPos, int fallHeight, int facing,
                                        PilotingRollData roll, boolean intoBasement, boolean fromCliff) {
        entity.setFallen(true);

        Vector<Report> vPhaseReport = new Vector<>();
        Report r;

        IHex fallHex = game.getBoard().getHex(fallPos);

        boolean handlingBasement = false;

        // we don't need to deal damage yet, if the entity is doing DFA
        if (entity.isMakingDfa()) {
            r = new Report(2305);
            r.subject = entity.getId();
            vPhaseReport.add(r);
            entity.setProne(true);
            return vPhaseReport;
        }

        // facing after fall
        String side;
        int table;
        switch (facing) {
            case 1:
            case 2:
                side = "right side";
                table = ToHitData.SIDE_RIGHT;
                break;
            case 3:
                side = "rear";
                table = ToHitData.SIDE_REAR;
                break;
            case 4:
            case 5:
                side = "left side";
                table = ToHitData.SIDE_LEFT;
                break;
            case 0:
            default:
                side = "front";
                table = ToHitData.SIDE_FRONT;
        }

        int waterDepth = 0;
        if (fallHex.containsTerrain(Terrains.WATER)) {
            // *Only* use this if there actually is water in the hex, otherwise
            // we get ITerrain.LEVEL_NONE, i.e. Integer.minValue...
            waterDepth = fallHex.terrainLevel(Terrains.WATER);
        }
        boolean fallOntoBridge = (entity.climbMode() && (entity.getPosition() != fallPos)
                && fallHex.containsTerrain(Terrains.BRIDGE)
                && fallHex.containsTerrainExit(Terrains.BRIDGE, fallPos.direction(entity.getPosition())))
                || (entity.getElevation() == fallHex.terrainLevel(Terrains.BRIDGE_ELEV));
        // only fall onto the bridge if we were in the hex and on it,
        // or we fell from a hex that the bridge exits to
        int bridgeElev = fallHex.terrainLevel(Terrains.BRIDGE_ELEV);
        int buildingElev = fallHex.terrainLevel(Terrains.BLDG_ELEV);
        int damageHeight = fallHeight;
        int newElevation = 0;

        // we might have to check if the building/bridge we are falling onto collapses
        boolean checkCollapse = false;

        if ((entity.getElevation() >= buildingElev) && (buildingElev >= 0)) {
            // fallHeight should already reflect this
            newElevation = buildingElev;
            checkCollapse = true;
        } else if (fallOntoBridge && (entity.getElevation() >= bridgeElev) && (bridgeElev >= 0)) {
            // fallHeight should already reflect this
            waterDepth = 0;
            newElevation = fallHex.terrainLevel(Terrains.BRIDGE_ELEV);
            checkCollapse = true;
        } else if (fallHex.containsTerrain(Terrains.ICE) && (entity.getElevation() == 0)) {
            waterDepth = 0;
            // If we are in a basement, we are at a negative elevation, and so
            // setting newElevation = 0 will cause us to "fall up"
        } else if ((entity.getMovementMode() != EntityMovementMode.VTOL)
                   && (game.getBoard().getBuildingAt(fallPos) != null)) {
            newElevation = entity.getElevation();
        }
        // HACK: if the destination hex is water, assume that the fall height given is
        // to the floor of the hex, and modify it so that it's to the surface
        else if (waterDepth > 0) {
            damageHeight = fallHeight - waterDepth;
            newElevation = -waterDepth;
        }
        int damageTable = ToHitData.HIT_NORMAL;
        // only do these basement checks if we didn't fall onto the building from above
        if (intoBasement) {
            Building bldg = game.getBoard().getBuildingAt(fallPos);
            BasementType basement = bldg.getBasement(fallPos);
            if ((basement != BasementType.NONE) && (basement != BasementType.ONE_DEEP_NORMALINFONLY)
                    && (entity.getElevation() == 0) && (bldg.getBasementCollapsed(fallPos))) {

                if (fallHex.depth(true) == 0) {
                    MegaMek.getLogger().error("Entity " + entity.getDisplayName() + " is falling into a depth "
                            + fallHex.depth(true) + " basement -- not allowed!!");
                    return vPhaseReport;
                }
                damageHeight = basement.getDepth();
                newElevation = newElevation - damageHeight;

                handlingBasement = true;
                // May have to adjust hit table for 'mechs
                if (entity instanceof Mech) {
                    if ((basement == BasementType.TWO_DEEP_FEET) || (basement == BasementType.ONE_DEEP_FEET)) {
                        damageTable = ToHitData.HIT_KICK;
                    } else if ((basement == BasementType.TWO_DEEP_HEAD) || (basement == BasementType.ONE_DEEP_HEAD)) {
                        damageTable = ToHitData.HIT_PUNCH;
                    }
                }
            }
        }
        if (entity instanceof Protomech) {
            damageTable = ToHitData.HIT_SPECIAL_PROTO;
        }
        // Falling into water instantly destroys most non-mechs
        if ((waterDepth > 0)
            && !(entity instanceof Mech)
            && !(entity instanceof Protomech)
            && !((entity.getRunMP() > 0) && (entity.getMovementMode() == EntityMovementMode.HOVER))
            && (entity.getMovementMode() != EntityMovementMode.HYDROFOIL)
            && (entity.getMovementMode() != EntityMovementMode.NAVAL)
            && (entity.getMovementMode() != EntityMovementMode.SUBMARINE)
            && (entity.getMovementMode() != EntityMovementMode.INF_UMU)) {
            vPhaseReport.addAll(entityManager.destroyEntity(entity, "a watery grave", false));
            return vPhaseReport;
        }

        // set how deep the mech has fallen
        if (entity instanceof Mech) {
            Mech mech = (Mech) entity;
            mech.setLevelsFallen(damageHeight + waterDepth + 1);
            // an industrial mech now needs to check for a crit at the end of the turn
            if (mech.isIndustrial()) {
                mech.setCheckForCrit(true);
            }
        }

        // calculate damage for hitting the surface
        int damage = (int) Math.round(entity.getWeight() / 10.0) * (damageHeight + 1);
        // different rules (pg. 151 of TW) for battle armor and infantry
        if (entity instanceof Infantry) {
            damage = (int) Math.ceil(damageHeight / 2.0);
            // no damage for fall from less than 2 levels
            if (damageHeight < 2) {
                damage = 0;
            }
            if (!(entity instanceof BattleArmor)) {
                int dice = 3;
                if (entity.getMovementMode() == EntityMovementMode.INF_MOTORIZED) {
                    dice = 2;
                } else if ((entity.getMovementMode() == EntityMovementMode.INF_JUMP)
                           || ((Infantry) entity).isMechanized()) {
                    dice = 1;
                }
                damage = damage * Compute.d6(dice);
            }
        }
        // Different rules (pg 62/63/152 of TW) for Tanks
        if (entity instanceof Tank) {
            // Falls from less than 2 levels don't damage combat vehicles
            // except if they fall off a sheer cliff
            if (damageHeight < 2 && !fromCliff) {
                damage = 0; 
            }
            // Falls from >= 2 elevations damage like crashing VTOLs
            // Ends up being the regular damage: weight / 10 * (height + 1)
            // And this was already computed
        }
        // calculate damage for hitting the ground, but only if we actually fell into water
        // if we fell onto the water surface, that damage is halved.
        int waterDamage = 0;
        if (waterDepth > 0) {
            damage /= 2;
            waterDamage = ((int) Math.round(entity.getWeight() / 10.0) * (waterDepth + 1)) / 2;
        }

        // If the waterDepth is larger than the fall height, we fell underwater
        if ((waterDepth >= fallHeight) && ((waterDepth != 0) || (fallHeight != 0))) {
            damage = 0;
            waterDamage = ((int) Math.round(entity.getWeight() / 10.0) * (fallHeight + 1)) / 2;
        }
        // adjust damage for gravity
        damage = Math.round(damage * game.getPlanetaryConditions().getGravity());
        waterDamage = Math.round(waterDamage * game.getPlanetaryConditions().getGravity());

        // report falling
        if (waterDamage == 0) {
            r = new Report(2310);
            r.subject = entity.getId();
            r.indent();
            r.addDesc(entity);
            r.add(side); // international issue
            r.add(damage);
        } else if (damage > 0) {
            r = new Report(2315);
            r.subject = entity.getId();
            r.indent();
            r.addDesc(entity);
            r.add(side); // international issue
            r.add(damage);
            r.add(waterDamage);
        } else {
            r = new Report(2310);
            r.subject = entity.getId();
            r.indent();
            r.addDesc(entity);
            r.add(side); // international issue
            r.add(waterDamage);
        }
        vPhaseReport.add(r);

        // Any swarming infantry will be dislodged, but we don't want to
        // interrupt the fall's report. We have to get the ID now because
        // the fall may kill the entity which will reset the attacker ID.
        final int swarmerId = entity.getSwarmAttackerId();

        // Positioning must be prior to damage for proper handling of breaches
        // Only Mechs can fall prone.
        if (entity instanceof Mech) {
            entity.setProne(true);
        }
        entity.setPosition(fallPos);
        entity.setElevation(newElevation);
        // Only 'mechs change facing when they fall
        if (entity instanceof Mech) {
            entity.setFacing((entity.getFacing() + (facing)) % 6);
            entity.setSecondaryFacing(entity.getFacing());
        }

        // if falling into a bog-down hex, the entity automatically gets stuck (except when on a bridge or building)
        // but avoid reporting this twice in the case of DFAs
        if (!entity.isStuck() && (entity.getElevation() == 0)) {
            if (fallHex.getBogDownModifier(entity.getMovementMode(),
                    entity instanceof LargeSupportTank) != TargetRoll.AUTOMATIC_SUCCESS) {
                entity.setStuck(true);
                r = new Report(2081);
                r.subject = entity.getId();
                r.add(entity.getDisplayName(), true);
                vPhaseReport.add(r);
                // check for quicksand
                vPhaseReport.addAll(checkQuickSand(fallPos));
            }
        }

        // standard damage loop
        if ((entity instanceof Infantry) && (damage > 0)) {
            if (entity instanceof BattleArmor) {
                for (int i = 1; i < entity.locations(); i++) {
                    HitData h = new HitData(i);
                    vPhaseReport.addAll(damageEntity(entity, h, damage));
                    reportmanager.addNewLines();
                }
            } else {
                HitData h = new HitData(Infantry.LOC_INFANTRY);
                vPhaseReport.addAll(damageEntity(entity, h, damage));
            }
        } else {
            while (damage > 0) {
                int cluster = Math.min(5, damage);
                HitData hit = entity.rollHitLocation(damageTable, table);
                hit.makeFallDamage(true);
                vPhaseReport.addAll(damageEntity(entity, hit, cluster));
                damage -= cluster;
            }
        }

        if (waterDepth > 0) {
            for (int loop = 0; loop < entity.locations(); loop++) {
                entity.setLocationStatus(loop, ILocationExposureStatus.WET);
            }
        }
        // Water damage
        while (waterDamage > 0) {
            int cluster = Math.min(5, waterDamage);
            HitData hit = entity.rollHitLocation(damageTable, table);
            hit.makeFallDamage(true);
            vPhaseReport.addAll(damageEntity(entity, hit, cluster));
            waterDamage -= cluster;
        }

        // check for location exposure
        vPhaseReport.addAll(doSetLocationsExposure(entity, fallHex, false, - waterDepth));

        // only mechs should roll to avoid pilot damage
        // vehicles may fall due to sideslips
        if (entity instanceof Mech) {
            vPhaseReport.addAll(checkPilotAvoidFallDamage(entity, fallHeight, roll));
        }

        // Now dislodge any swarming infantry.
        if (Entity.NONE != swarmerId) {
            final Entity swarmer = game.getEntity(swarmerId);
            entity.setSwarmAttackerId(Entity.NONE);
            swarmer.setSwarmTargetId(Entity.NONE);
            // Did the infantry fall into water?
            if ((waterDepth > 0) && (swarmer.getMovementMode() != EntityMovementMode.INF_UMU)) {
                // Swarming infantry die.
                swarmer.setPosition(fallPos);
                r = new Report(2330);
                r.newlines = 0;
                r.subject = swarmer.getId();
                r.addDesc(swarmer);
                vPhaseReport.add(r);
                vPhaseReport.addAll(entityManager.destroyEntity(swarmer, "a watery grave", false));
            } else {
                // Swarming infantry take a 2d6 point hit.
                // ASSUMPTION : damage should not be doubled.
                r = new Report(2335);
                r.newlines = 0;
                r.subject = swarmer.getId();
                r.addDesc(swarmer);
                vPhaseReport.add(r);
                vPhaseReport.addAll(damageEntity(swarmer, swarmer.rollHitLocation(ToHitData.HIT_NORMAL,
                        ToHitData.SIDE_FRONT), Compute.d6(2)));
                Report.addNewline(vPhaseReport);
            }
            swarmer.setPosition(fallPos);
            entityManager.entityUpdate(swarmerId);
            if (!swarmer.isDone()) {
                game.removeTurnFor(swarmer);
                swarmer.setDone(true);
                send(PacketFactory.createTurnVectorPacket(game));
            }
        } // End dislodge-infantry

        // clear all PSRs after a fall -- the Mek has already failed ONE and
        // fallen, it'd be cruel to make it fail some more!
        game.resetPSRs(entity);

        // if there is a minefield in this hex, then the mech may set it off
        if (game.containsMinefield(fallPos)
            && enterMinefield(entity, fallPos, newElevation, true, vPhaseReport, 12)) {
            gamemanager.resetMines(game);
        }
        // if we have to, check if the building/bridge we fell on collapses -
        // unless it's a fall into a basement,
        // then we're already gonna check that in building collapse, where we
        // came from
        if (checkCollapse && !handlingBasement) {
            checkForCollapse(game.getBoard().getBuildingAt(fallPos),
                             game.getPositionMap(), fallPos, false, vPhaseReport);
        }

        return vPhaseReport;
    }

    private Vector<Report> checkPilotAvoidFallDamage(Entity entity, int fallHeight, PilotingRollData roll) {
        Vector<Report> reports = new Vector<>();

        if (entity.hasAbility(OptionsConstants.MD_DERMAL_ARMOR) || entity.hasAbility(OptionsConstants.MD_TSM_IMPLANT)) {
            return reports;
        }
        // we want to be able to avoid pilot damage even when it was
        // an automatic fall, only unconsciousness should cause auto-damage
        roll.removeAutos();

        if (fallHeight > 1) {
            roll.addModifier(fallHeight - 1, "height of fall");
        }

        if (entity.getCrew().getSlotCount() > 1) {
            //Extract the base from the list of modifiers so we can replace it with the piloting
            //skill of each crew member.
            List<TargetRollModifier> modifiers = new ArrayList<>(roll.getModifiers());
            if (modifiers.size() > 0) {
                modifiers.remove(0);
            }
            for (int pos = 0; pos < entity.getCrew().getSlotCount(); pos++) {
                if (entity.getCrew().isMissing(pos) || entity.getCrew().isDead(pos)) {
                    continue;
                }
                PilotingRollData prd;
                if (entity.getCrew().isDead(pos)) {
                    continue;
                } else if (entity.getCrew().isUnconscious(pos)) {
                    prd = new PilotingRollData(entity.getId(), TargetRoll.AUTOMATIC_FAIL, "Crew member unconscious");
                } else {
                    prd = new PilotingRollData(entity.getId(), entity.getCrew().getPiloting(pos), "Base piloting skill");
                    modifiers.forEach(prd::addModifier);
                }
                reports.addAll(resolvePilotDamageFromFall(entity, prd, pos));
            }
        } else {
            reports.addAll(resolvePilotDamageFromFall(entity, roll, 0));
        }
        return reports;
    }

    private Vector<Report> resolvePilotDamageFromFall(Entity entity, PilotingRollData roll, int crewPos) {
        Vector<Report> reports = new Vector<>();
        Report r;
        if (roll.getValue() == TargetRoll.IMPOSSIBLE) {
            r = new Report(2320);
            r.subject = entity.getId();
            r.add(entity.getCrew().getCrewType().getRoleName(crewPos));
            r.addDesc(entity);
            r.add(entity.getCrew().getName(crewPos));
            r.indent();
            reports.add(r);
            reports.addAll(damageCrew(entity, 1, crewPos));
        } else {
            int diceRoll = entity.getCrew().rollPilotingSkill();
            r = new Report(2325);
            r.subject = entity.getId();
            r.add(entity.getCrew().getCrewType().getRoleName(crewPos));
            r.addDesc(entity);
            r.add(entity.getCrew().getName(crewPos));
            r.add(roll.getValueAsString());
            r.add(diceRoll);
            if (diceRoll >= roll.getValue()) {
                r.choose(true);
                reports.add(r);
            } else {
                r.choose(false);
                reports.add(r);
                reports.addAll(damageCrew(entity, 1, crewPos));
            }
        }
        Report.addNewline(reports);
        return reports;
    }

    /**
     * The mech falls into an unoccupied hex from the given height above
     */
    public Vector<Report> doEntityFall(Entity entity, Coords fallPos, int height, PilotingRollData roll) {
        return doEntityFall(entity, fallPos, height, Compute.d6(1) - 1, roll,
                            false, false);
    }

    /**
     * The mech falls down in place
     */
    public Vector<Report> doEntityFall(Entity entity, PilotingRollData roll) {
        boolean fallToSurface = false;
        // on ice
        int toSubtract = 0;
        IHex currHex = game.getBoard().getHex(entity.getPosition());
        if (currHex.containsTerrain(Terrains.ICE) && (entity.getElevation() != -currHex.depth())) {
            fallToSurface = true;
        }
        // on a bridge
        if (currHex.containsTerrain(Terrains.BRIDGE_ELEV) && (entity.getElevation() >= currHex.terrainLevel(Terrains.BRIDGE_ELEV))) {
            fallToSurface = true;
            toSubtract = currHex.terrainLevel(Terrains.BRIDGE_ELEV);
        }
        // on a building
        if (currHex.containsTerrain(Terrains.BLDG_ELEV) && (entity.getElevation() >= currHex.terrainLevel(Terrains.BLDG_ELEV))) {
            fallToSurface = true;
            toSubtract = currHex.terrainLevel(Terrains.BLDG_ELEV);
        }
        return doEntityFall(entity, entity.getPosition(), entity.getElevation() + (!fallToSurface ? currHex.depth(true) : -toSubtract), roll);
    }

    /**
     * Checks for fire ignition based on a given target roll. If successful,
     * lights a fire also checks to see that fire is possible in the specified
     * hex.
     *
     * @param c        - the <code>Coords</code> to be lit.
     * @param roll     - the <code>TargetRoll</code> for the ignition roll
     * @param bInferno - <code>true</code> if the fire is an inferno fire. If this
     *                 value is <code>false</code> the hex will be lit only if it
     *                 contains Woods,jungle or a Building.
     * @param entityId - the entityId responsible for the ignite attempt. If the
     *                 value is Entity.NONE, then the roll attempt will not be
     *                 included in the report.
     */
    public boolean checkIgnition(Coords c, TargetRoll roll, boolean bInferno, int entityId,
                                 Vector<Report> vPhaseReport) {

        IHex hex = game.getBoard().getHex(c);

        // The hex might be null due to spreadFire translation
        // goes outside of the board limit.
        if (null == hex) {
            return false;
        }

        // The hex may already be on fire.
        if (hex.containsTerrain(Terrains.FIRE)) {
            return false;
        }

        if (!bInferno && !hex.isIgnitable()) {
            return false;
        }

        int fireRoll = Compute.d6(2);

        if (entityId != Entity.NONE) {
            Report r = new Report(3430);
            r.indent(2);
            r.subject = entityId;
            r.add(roll.getValueAsString());
            r.add(roll.getDesc());
            r.add(fireRoll);
            vPhaseReport.add(r);
        }
        if (fireRoll >= roll.getValue()) {
            ignite(c, Terrains.FIRE_LVL_NORMAL, vPhaseReport);
            return true;
        }
        return false;
    }

    /**
     * Returns true if the hex is set on fire with the specified roll. Of
     * course, also checks to see that fire is possible in the specified hex.
     * This version of the method will not report the attempt roll.
     *
     * @param c        - the <code>Coords</code> to be lit.
     * @param roll     - the <code>int</code> target number for the ignition roll
     * @param bInferno - <code>true</code> if the fire can be lit in any terrain. If
     *                 this value is <code>false</code> the hex will be lit only if
     *                 it contains Woods, jungle or a Building.
     */
    public boolean checkIgnition(Coords c, TargetRoll roll, boolean bInferno) {
        return checkIgnition(c, roll, bInferno, Entity.NONE, null);
    }

    /**
     * Returns true if the hex is set on fire with the specified roll. Of
     * course, also checks to see that fire is possible in the specified hex.
     * This version of the method will not report the attempt roll.
     *
     * @param c    - the <code>Coords</code> to be lit.
     * @param roll - the <code>int</code> target number for the ignition roll
     */
    public boolean checkIgnition(Coords c, TargetRoll roll) {
        // default signature, assuming only woods can burn
        return checkIgnition(c, roll, false, Entity.NONE, null);
    }

    /**
     * add fire to a hex
     *
     * @param c         - the <code>Coords</code> of the hex to be set on fire
     * @param fireLevel - The level of fire, see Terrains
     */
    public void ignite(Coords c, int fireLevel, Vector<Report> vReport) {
        // you can't start fires in some planetary conditions!
        if (null != game.getPlanetaryConditions().cannotStartFire()) {
            if (null != vReport) {
                Report r = new Report(3007, Report.PUBLIC);
                r.indent(2);
                r.add(game.getPlanetaryConditions().cannotStartFire());
                vReport.add(r);
            }
            return;
        }

        if (!game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_START_FIRE)) {
            if (null != vReport) {
                Report r = new Report(3008, Report.PUBLIC);
                r.indent(2);
                vReport.add(r);
            }
            return;
        }

        IHex hex = game.getBoard().getHex(c);
        if (null == hex) {
            return;
        }

        Report r = new Report(3005, Report.PUBLIC);
        r.indent(2);
        r.add(c.getBoardNum());

        // Adjust report message for inferno types
        switch (fireLevel) {
            case Terrains.FIRE_LVL_INFERNO:
                r.messageId = 3006;
                break;
            case Terrains.FIRE_LVL_INFERNO_BOMB:
                r.messageId = 3003;
                break;
            case Terrains.FIRE_LVL_INFERNO_IV:
                r.messageId = 3004;
                break;
        }

        // report it
        if (null != vReport) {
            vReport.add(r);
        }
        hex.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.FIRE, fireLevel));
        gamemanager.sendChangedHex(game, c);
    }

    /**
     * remove fire from a hex
     *
     * @param fireCoords
     * @param reason
     */
    public void removeFire(Coords fireCoords, String reason) {
        IHex hex = game.getBoard().getHex(fireCoords);
        if (null == hex) {
            return;
        }
        hex.removeTerrain(Terrains.FIRE);
        hex.resetFireTurn();
        gamemanager.sendChangedHex(game, fireCoords);
        // fire goes out
        Report r = new Report(5170, Report.PUBLIC);
        r.add(fireCoords.getBoardNum());
        r.add(reason);
        reportmanager.addReport(r);
    }

    /**
     * Called when a fire is burning. Called 3 times per fire hex.
     *
     * @param coords The <code>Coords</code> x-coordinate of the hex
     */
    public void addSmoke(ArrayList<Coords> coords, int windDir, boolean bInferno) {
        // if a tornado, then no smoke!
        if (game.getPlanetaryConditions().getWindStrength() > PlanetaryConditions.WI_STORM) {
            return;
        }

        int smokeLevel = 0;
        for (Coords smokeCoords : coords) {
            IHex smokeHex = game.getBoard().getHex(smokeCoords);
            Report r;
            if (smokeHex == null) {
                continue;
            }
            // Have to check if it's inferno smoke or from a heavy/hardened
            // building
            // - heavy smoke from those
            if (bInferno || (Building.MEDIUM < smokeHex.terrainLevel(Terrains.FUEL_TANK))
                    || (Building.MEDIUM < smokeHex.terrainLevel(Terrains.BUILDING))) {
                if (smokeHex.terrainLevel(Terrains.SMOKE) == SmokeCloud.SMOKE_HEAVY) {
                    // heavy smoke fills hex
                    r = new Report(5180, Report.PUBLIC);
                } else {
                    r = new Report(5185, Report.PUBLIC);
                }
                smokeLevel = SmokeCloud.SMOKE_HEAVY;
            } else {
                if (smokeHex.terrainLevel(Terrains.SMOKE) == SmokeCloud.SMOKE_HEAVY) {
                    // heavy smoke overpowers light
                    r = new Report(5190, Report.PUBLIC);
                } else if (smokeHex.terrainLevel(Terrains.SMOKE) == SmokeCloud.SMOKE_LIGHT) {
                    // light smoke continue to fill hex
                    r = new Report(5195, Report.PUBLIC);
                } else {
                    // light smoke fills hex
                    r = new Report(5200, Report.PUBLIC);
                }
                smokeLevel = Math.max(smokeLevel, SmokeCloud.SMOKE_LIGHT);
            }
            r.add(smokeCoords.getBoardNum());
            reportmanager.addReport(r);
        }
        createSmoke(coords, smokeLevel, 0);
    }

    /**
     * Returns a vector of which players can see this entity, always allowing
     * for sensor detections.
     */
    public Vector<IPlayer> whoCanSee(Entity entity) {
        return whoCanSee(entity, true, null);
    }

    /**
     * Returns a vector of which players can see the given entity, optionally
     * allowing for sensors to count.
     *
     * @param entity     The entity to check visibility for
     * @param useSensors A flag that determines whether sensors are allowed
     * @return A vector of the players who can see the entity
     */
    public Vector<IPlayer> whoCanSee(Entity entity, boolean useSensors, Map<EntityTargetPair, LosEffects> losCache) {
        if (losCache == null) {
            losCache = new HashMap<>();
        }
        // Some times Null entities are sent to this
        if (entity == null) {
            return new Vector<>();
        }

        List<ECMInfo> allECMInfo = null;
        if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_SENSORS) && useSensors) {
            allECMInfo = ComputeECM.computeAllEntitiesECMInfo(game.getEntitiesVector());
        }

        boolean bTeamVision = game.getOptions().booleanOption(OptionsConstants.ADVANCED_TEAM_VISION);
        List<Entity> vEntities = game.getEntitiesVector();

        Vector<IPlayer> vCanSee = new Vector<>();
        vCanSee.addElement(entity.getOwner());
        if (bTeamVision) {
            game.addTeammates(vCanSee, entity.getOwner());
        }

        // Deal with players who can see all.
        Vector<IPlayer> players = game.getPlayersVector();
        for (IPlayer player : players) {
            if (player.canSeeAll() && !vCanSee.contains(player)) {
                vCanSee.addElement(player);
            }
        }

        // If the entity is hidden, skip; no one else will be able to see it.
        if (entity.isHidden()) {
            return vCanSee;
        }
        for (Entity spotter : vEntities) {
            // Certain conditions make the spotter ineligible
            if (!spotter.isActive() || spotter.isOffBoard() || vCanSee.contains(spotter.getOwner())) {
                continue;
            }
            // See if the LosEffects is cached, and if not cache it
            EntityTargetPair etp = new EntityTargetPair(spotter, entity);
            LosEffects los = losCache.get(etp);
            if (los == null) {
                los = LosEffects.calculateLos(game, spotter.getId(), entity);
                losCache.put(etp, los);
            }
            if (Compute.canSee(game, spotter, entity, useSensors, los, allECMInfo)) {
                if (!vCanSee.contains(spotter.getOwner())) {
                    vCanSee.addElement(spotter.getOwner());
                }
                if (bTeamVision) {
                    game.addTeammates(vCanSee, spotter.getOwner());
                }
                game.addObservers(vCanSee);
            }
        }
        return vCanSee;
    }

    /**
     * Determine which players can detect the given entity with sensors.
     * Because recomputing ECM and LosEffects frequently can get expensive, this
     * data can be cached and passed in.
     *
     * @param entity        The Entity being detected.
     * @param allECMInfo    Cached ECMInfo for all Entities in the game.
     * @param losCache      Cached LosEffects for particular Entity/Targetable
     *                      pairs.  Can be passed in null.
     * @return
     */
    private Vector<IPlayer> whoCanDetect(Entity entity, List<ECMInfo> allECMInfo, Map<EntityTargetPair, LosEffects> losCache) {
        if (losCache == null) {
            losCache = new HashMap<>();
        }

        boolean bTeamVision = game.getOptions().booleanOption(OptionsConstants.ADVANCED_TEAM_VISION);
        List<Entity> vEntities = game.getEntitiesVector();

        Vector<IPlayer> vCanDetect = new Vector<>();

        // If the entity is hidden, skip; no one else will be able to detect it
        if (entity.isHidden() || entity.isOffBoard()) {
            return vCanDetect;
        }

        for (Entity spotter : vEntities) {
            if (!spotter.isActive() || spotter.isOffBoard() || vCanDetect.contains(spotter.getOwner())) {
                continue;
            }
            // See if the LosEffects is cached, and if not cache it
            EntityTargetPair etp = new EntityTargetPair(spotter, entity);
            LosEffects los = losCache.get(etp);
            if (los == null) {
                los = LosEffects.calculateLos(game, spotter.getId(), entity);
                losCache.put(etp, los);
            }
            if (Compute.inSensorRange(game, los, spotter, entity, allECMInfo)) {
                if (!vCanDetect.contains(spotter.getOwner())) {
                    vCanDetect.addElement(spotter.getOwner());
                }
                if (bTeamVision) {
                    game.addTeammates(vCanDetect, spotter.getOwner());
                }
                game.addObservers(vCanDetect);
            }
        }
        return vCanDetect;
    }

    /**
     * Updates entities graphical "visibility indications" which are used in
     * double-blind games.
     *
     * @param losCache  It can be expensive to have to recompute LoSEffects
     *                  again and again, so in some cases where this may happen,
     *                  the LosEffects are cached.   This can safely be null.
     */
    private void updateVisibilityIndicator(Map<EntityTargetPair, LosEffects> losCache) {
        if (losCache == null) {
            losCache = new HashMap<>();
        }
        List<ECMInfo> allECMInfo = null;
        if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_SENSORS)) {
            allECMInfo = ComputeECM.computeAllEntitiesECMInfo(game.getEntitiesVector());
        }

        List<Entity> vAllEntities = game.getEntitiesVector();
        for (Entity e : vAllEntities) {
            Vector<IPlayer> whoCouldSee = new Vector<>(e.getWhoCanSee());
            Vector<IPlayer> whoCouldDetect = new Vector<>(e.getWhoCanDetect());
            e.setVisibleToEnemy(false);
            e.setDetectedByEnemy(false);
            e.clearSeenBy();
            e.clearDetectedBy();
            Vector<IPlayer> vCanSee = whoCanSee(e, false, losCache);
            // Who can See this unit?
            for (IPlayer p : vCanSee) {
                if (e.getOwner().isEnemyOf(p) && !p.isObserver()) {
                    e.setVisibleToEnemy(true);
                    e.setEverSeenByEnemy(true);
                    // If we can see it, it's detected
                    e.setDetectedByEnemy(true);
                }
                e.addBeenSeenBy(p);
            }
            // Who can Detect this unit?
            Vector<IPlayer> vCanDetect = whoCanDetect(e, allECMInfo, losCache);
            for (IPlayer p : vCanDetect) {
                if (e.getOwner().isEnemyOf(p) && !p.isObserver()) {
                    e.setDetectedByEnemy(true);
                }
                e.addBeenDetectedBy(p);
            }

            // If a client can now see/detect this entity, but couldn't before,
            // then the client needs to be updated with the Entity
            boolean hasClientWithoutEntity = false;
            for (IPlayer p : vCanSee) {
                if (!whoCouldSee.contains(p) && !whoCouldDetect.contains(p)) {
                    hasClientWithoutEntity = true;
                    break;
                }
            }
            if (!hasClientWithoutEntity) {
                for (IPlayer p : vCanDetect) {
                    if (!whoCouldSee.contains(p) && !whoCouldDetect.contains(p)) {
                        hasClientWithoutEntity = true;
                        break;
                    }
                }
            }
            if (hasClientWithoutEntity) {
                entityManager.entityUpdate(e.getId(), new Vector<>(), false, losCache);
            } else {
                gamemanager.sendVisibilityIndicator(e);
            }
        }
    }

    private boolean checkAndSetC3Network(Entity entity, Entity e) {
        entity.setC3NetIdSelf();
        int pos = 0;
        while (pos < Entity.MAX_C3i_NODES) {
            if (entity.getC3iNextUUIDAsString(pos) != null && e.getC3UUIDAsString() != null && entity.getC3iNextUUIDAsString(pos).equals(e.getC3UUIDAsString())) {
                entity.setC3NetId(e);
                return true;
            }
            pos++;
        }
        return false;
    }

    private void relinkC3(List<Entity> entities, Entity entity) {
        // Now we relink C3/NC3/C3i to our guys! Yes, this is hackish... but, we
        // do what we must. Its just too bad we have to loop over the entire entities array..
        if (entity.hasC3() || entity.hasC3i() || entity.hasNavalC3()) {
            boolean C3iSet = false;

            for (Entity e : game.getEntitiesVector()) {
                // C3 Checks
                if (entity.hasC3()) {
                    if ((entity.getC3MasterIsUUIDAsString() != null) && entity.getC3MasterIsUUIDAsString().equals(e.getC3UUIDAsString())) {
                        entity.setC3Master(e, false);
                        entity.setC3MasterIsUUIDAsString(null);
                    } else if ((e.getC3MasterIsUUIDAsString() != null)
                            && e.getC3MasterIsUUIDAsString().equals(entity.getC3UUIDAsString())) {
                        e.setC3Master(entity, false);
                        e.setC3MasterIsUUIDAsString(null);
                        // Taharqa: we need to update the other entity for the client or it won't show up right.
                        // I am not sure if I like the idea of updating other entities in this method, but it
                        // will work for now.
                        if (!entities.contains(e)) {
                            entityManager.entityUpdate(e.getId());
                        }
                    }
                }

                // C3i Checks
                if (entity.hasC3i() && !C3iSet && checkAndSetC3Network(entity, e)) {
                    C3iSet = true;
                }

                // NC3 Checks
                if (entity.hasNavalC3() && !C3iSet && checkAndSetC3Network(entity, e)) {
                    C3iSet = true;
                }
            }
        }
    }

    /**
     * Sends out the game victory event to all connections
     */
    private void transmitGameVictoryEventToAll() {
        for (IConnection conn : connectionListener.getConnections()) {
            send(conn.getId(), new Packet(Packet.COMMAND_GAME_VICTORY_EVENT));
        }
    }

    /**
     * Sends out all player info to the specified connection
     */
    private void transmitAllPlayerConnects(int connId) {
        Vector<IPlayer> players = game.getPlayersVector();
        for (IPlayer player : players) {
            send(connId, PacketFactory.createPlayerConnectPacket(game, player.getId()));
        }
    }

    /**
     * Sends out the player ready stats for all players to all connections
     */
    private void transmitAllPlayerDones() {
        Vector<IPlayer> players = game.getPlayersVector();
        for (IPlayer player : players) {
            send(PacketFactory.createPlayerDonePacket(game, player.getId()));
        }
    }

    // WOR
    private void sendReport() {
        sendReport(false);
    }

    /**
     * Send the round report to all connected clients.
     */
    private void sendReport(boolean tacticalGeniusReport) {
        if (connectionListener.getConnections() == null) {
            return;
        }

        for (IConnection conn : connectionListener.getConnections()) {
            IPlayer p = game.getPlayer(conn.getId());
            Packet packet;
            if (tacticalGeniusReport) {
                packet = PacketFactory.createTacticalGeniusReportPacket(reportmanager);
            } else {
                packet = PacketFactory.createReportPacket(p, game, reportmanager);
            }
            conn.send(packet);
        }
    }

    /**
     * Makes one slot of inferno ammo, determined by certain rules, explode on a
     * mech.
     *
     * @param entity
     *            The <code>Entity</code> that should suffer an inferno ammo
     *            explosion.
     */
    private Vector<Report> explodeInfernoAmmoFromHeat(Entity entity) {
        int damage = 0;
        int rack = 0;
        int boomloc = -1;
        int boomslot = -1;
        Vector<Report> vDesc = new Vector<>();
        Report r;

        // Find the most destructive Inferno ammo.
        for (int j = 0; j < entity.locations(); j++) {
            for (int k = 0; k < entity.getNumberOfCriticals(j); k++) {
                CriticalSlot cs = entity.getCritical(j, k);
                // Ignore empty, destroyed, hit, and structure slots.
                if ((cs == null) || cs.isDestroyed() || cs.isHit() || (cs.getType() != CriticalSlot.TYPE_EQUIPMENT)) {
                    continue;
                }
                // Ignore everything but ammo or LAM bomb bay slots.
                Mounted mounted = cs.getMount();
                int newRack;
                int newDamage;
                if (mounted.getType() instanceof AmmoType) {
                    AmmoType atype = (AmmoType) mounted.getType();
                    if (!atype.isExplosive(mounted)
                            || ((atype.getMunitionType() != AmmoType.M_INFERNO)
                            && (atype.getMunitionType() != AmmoType.M_IATM_IIW))) {
                        continue;
                    }
                    // ignore empty, destroyed, or missing bins
                    if (mounted.getHittableShotsLeft() == 0) {
                        continue;
                    }
                    // Find the most destructive undamaged ammo.
                    // TW page 160, compare one rack's
                    // damage. Ties go to most rounds.
                    newRack = atype.getDamagePerShot() * atype.getRackSize();
                    newDamage = mounted.getExplosionDamage();
                    Mounted mount2 = cs.getMount2();
                    if ((mount2 != null) && (mount2.getType() instanceof AmmoType) && (mount2.getHittableShotsLeft() > 0)) {
                        // must be for same weaponType, so rackSize stays
                        atype = (AmmoType) mount2.getType();
                        newRack += atype.getDamagePerShot() * atype.getRackSize();
                        newDamage += mount2.getExplosionDamage();
                    }
                } else if ((mounted.getType() instanceof MiscType) && mounted.getType().hasFlag(MiscType.F_BOMB_BAY)) {
                    while (mounted.getLinked() != null) {
                        mounted = mounted.getLinked();
                    }
                    if (mounted.getExplosionDamage() == 0) {
                        continue;
                    }
                    newRack = 1;
                    newDamage = mounted.getExplosionDamage();
                } else {
                    continue;
                }

                if (!mounted.isHit() && ((rack < newRack) || ((rack == newRack) && (damage < newDamage)))) {
                    rack = newRack;
                    damage = newDamage;
                    boomloc = j;
                    boomslot = k;
                }
            }
        }
        // Did we find anything to explode?
        if ((boomloc != -1) && (boomslot != -1)) {
            CriticalSlot slot = entity.getCritical(boomloc, boomslot);
            slot.setHit(true);
            Mounted equip = slot.getMount();
            equip.setHit(true);
            // We've allocated heatBuildup to heat in resolveHeat(),
            // so need to add to the entity's heat instead.
            if ((equip.getType() instanceof AmmoType)
                    || (equip.getLinked() != null
                        && equip.getLinked().getType() instanceof BombType
                        && ((BombType)equip.getLinked().getType()).getBombType() == BombType.B_INFERNO)) {
                entity.heat += Math.min(equip.getExplosionDamage(), 30);
            }
            vDesc.addAll(explodeEquipment(entity, boomloc, boomslot));
            r = new Report(5155);
            r.indent();
            r.subject = entity.getId();
            r.add(entity.heat);
            vDesc.addElement(r);
            entity.heatBuildup = 0;
        } else { // no ammo to explode
            r = new Report(5160);
            r.indent();
            r.subject = entity.getId();
            vDesc.addElement(r);
        }
        return vDesc;
    }

    /**
     * checks for unintended explosion of heavy industrial zone hex and applies
     * damage to entities occupying the hex
     */
    public void checkExplodeIndustrialZone(Coords c, Vector<Report> vDesc) {
        IHex hex = game.getBoard().getHex(c);
        if (null == hex) {
            return;
        }

        if (!hex.containsTerrain(Terrains.INDUSTRIAL)) {
            return;
        }

        Report r = new Report(3590, Report.PUBLIC);
        r.add(c.getBoardNum());
        r.indent(2);
        int effect = Compute.d6(2);
        r.add(8);
        r.add(effect);
        if (effect > 7) {
            r.choose(true);
            r.newlines = 0;
            vDesc.add(r);
            boolean onFire = false;
            boolean powerLine = false;
            boolean minorExp = false;
            boolean elecExp = false;
            boolean majorExp = false;
            if (effect == 8) {
                onFire = true;
                r = new Report(3600, Report.PUBLIC);
            } else if (effect == 9) {
                powerLine = true;
                r = new Report(3605, Report.PUBLIC);
            } else if (effect == 10) {
                minorExp = true;
                onFire = true;
                r = new Report(3610, Report.PUBLIC);
            } else if (effect == 11) {
                elecExp = true;
                r = new Report(3615, Report.PUBLIC);
            } else {
                onFire = true;
                majorExp = true;
                r = new Report(3620, Report.PUBLIC);
            }
            r.newlines = 0;
            vDesc.add(r);
            // apply damage here
            if (powerLine || minorExp || elecExp || majorExp) {
                // cycle through the entities in the hex and apply damage
                for (Entity en : game.getEntitiesVector(c)) {
                    int damage = 3;
                    if (minorExp) {
                        damage = 5;
                    }
                    if (elecExp) {
                        damage = Compute.d6(1) + 3;
                    }
                    if (majorExp) {
                        damage = Compute.d6(2);
                    }
                    HitData hit = en.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                    if (en instanceof BattleArmor) {
                        // ugly - I have to apply damage to each trooper separately
                        for (int loc = 0; loc < en.locations(); loc++) {
                            if ((IArmorState.ARMOR_NA != en.getInternal(loc))
                                    && (IArmorState.ARMOR_DESTROYED != en.getInternal(loc))
                                    && (IArmorState.ARMOR_DOOMED != en.getInternal(loc))) {
                                vDesc.addAll(damageEntity(en, new HitData(loc), damage));
                            }
                        }
                    } else {
                        vDesc.addAll(damageEntity(en, hit, damage));
                    }
                    if (majorExp) {
                        // lets pretend that the infernos came from the entity
                        // itself (should give us side_front)
                        vDesc.addAll(deliverInfernoMissiles(en, en, Compute.d6(2)));
                    }
                }
            }
            Report.addNewline(vDesc);
            if (onFire && !hex.containsTerrain(Terrains.FIRE)) {
                ignite(c, Terrains.FIRE_LVL_NORMAL, vDesc);
            }
        } else {
            // report no explosion
            r.choose(false);
            vDesc.add(r);
        }
    }

    /**
     * Determine the results of an entity moving through a wall of a building
     * after having moved a certain distance. This gets called when a Mech or a
     * Tank enters a building, leaves a building, or travels from one hex to
     * another inside a multi-hex building.
     *
     * @param entity
     *            - the <code>Entity</code> that passed through a wall. Don't
     *            pass <code>Infantry</code> units to this method.
     * @param bldg
     *            - the <code>Building</code> the entity is passing through.
     * @param lastPos
     *            - the <code>Coords</code> of the hex the entity is exiting.
     * @param curPos
     *            - the <code>Coords</code> of the hex the entity is entering
     * @param distance
     *            - the <code>int</code> number of hexes the entity has moved
     *            already this phase.
     * @param why
     *            - the <code>String</code> explanation for this action.
     * @param backwards
     *            - the <code>boolean</code> indicating if the entity is
     *            entering the hex backwards
     * @param entering
     *            - a <code>boolean</code> if the entity is entering or exiting
     *            a building
     */
    public void passBuildingWall(Entity entity, Building bldg, Coords lastPos, Coords curPos, int distance, String why,
                                  boolean backwards, EntityMovementType overallMoveType, boolean entering) {
        Report r;

        if (entity instanceof Protomech) {
            Vector<Report> vBuildingReport = damageBuilding(bldg, 1, curPos);
            for (Report report : vBuildingReport) {
                report.subject = entity.getId();
            }
            reportmanager.addReport(vBuildingReport);
        } else {
            // Need to roll based on building type.
            PilotingRollData psr = entity.rollMovementInBuilding(bldg, distance, why, overallMoveType);

            // Did the entity make the roll?
            if (0 < doSkillCheckWhileMoving(entity, entity.getElevation(), lastPos, curPos, psr, false)) {

                // Divide the building's current CF by 10, round up.
                int damage = (int) Math.floor(bldg.getDamageFromScale()
                        * Math.ceil(bldg.getCurrentCF(entering ? curPos : lastPos) / 10.0));

                // Infantry and Battle armor take different amounts of damage
                // then Meks and vehicles.
                if (entity instanceof Infantry) {
                    damage = bldg.getType() + 1;
                }
                // It is possible that the unit takes no damage.
                if (damage == 0) {
                    r = new Report(6440);
                    r.add(entity.getDisplayName());
                    r.subject = entity.getId();
                    r.indent(2);
                    reportmanager.addReport(r);
                } else {
                    // TW, pg. 268: if unit moves forward, damage from front,
                    // if backwards, damage from rear.
                    int side = ToHitData.SIDE_FRONT;
                    if (backwards) {
                        side = ToHitData.SIDE_REAR;
                    }
                    HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, side);
                    hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                    reportmanager.addReport(damageEntity(entity, hit, damage));
                }
            }

            // Infantry and BA are damaged by buildings but do not damage them
            if (entity instanceof Infantry) {
                return;
            }
            // Damage the building. The CF can never drop below 0.
            int toBldg = (int) Math.floor(bldg.getDamageToScale() * Math.ceil(entity.getWeight() / 10.0));
            int curCF = bldg.getCurrentCF(entering ? curPos : lastPos);
            curCF -= Math.min(curCF, toBldg);
            bldg.setCurrentCF(curCF, entering ? curPos : lastPos);

            // Apply the correct amount of damage to infantry in the building.
            // ASSUMPTION: We inflict toBldg damage to infantry and
            // not the amount to bring building to 0 CF.
            reportmanager.addReport(damageInfantryIn(bldg, toBldg, entering ? curPos : lastPos));
        }
    }

    /**
     * check if a building collapses because of a moving entity
     *
     * @param bldg
     *            the <code>Building</code>
     * @param entity
     *            the <code>Entity</code>
     * @param curPos
     *            the <code>Coords</code> of the position of the entity
     * @return a <code>boolean</code> value indicating if the building collapses
     */
    public boolean checkBuildingCollapseWhileMoving(Building bldg, Entity entity, Coords curPos) {
        Coords oldPos = entity.getPosition();
        // Count the moving entity in its current position, not
        // its pre-move position. Be sure to handle nulls.
        entity.setPosition(curPos);

        // Get the position map of all entities in the game.
        Hashtable<Coords, Vector<Entity>> positionMap = game.getPositionMap();

        // Check for collapse of this building due to overloading, and return.
        boolean rv = checkForCollapse(bldg, positionMap, curPos, true, reportmanager.getvPhaseReport());

        // If the entity was not displaced and didn't fall, move it back where it was
        if (curPos.equals(entity.getPosition()) && !entity.isProne()) {
            entity.setPosition(oldPos);
        }
        return rv;
    }

    public Vector<Report> damageInfantryIn(Building bldg, int damage, Coords hexCoords) {
        return damageInfantryIn(bldg, damage, hexCoords, WeaponType.WEAPON_NA);
    }

    /**
     * Apply the correct amount of damage that passes on to any infantry unit in
     * the given building, based upon the amount of damage the building just
     * sustained. This amount is a percentage dictated by pg. 172 of TW.
     *
     * @param bldg   - the <code>Building</code> that sustained the damage.
     * @param damage - the <code>int</code> amount of damage.
     */
    public Vector<Report> damageInfantryIn(Building bldg, int damage, Coords hexCoords, int infDamageClass) {
        Vector<Report> vDesc = new Vector<>();

        if (bldg == null) {
            return vDesc;
        }
        // Calculate the amount of damage the infantry will sustain.
        float percent = bldg.getDamageReductionFromOutside();
        Report r;

        // Round up at .5 points of damage.
        int toInf = Math.round(damage * percent);

        // some buildings scale remaining damage
        toInf = (int) Math.floor(bldg.getDamageToScale() * toInf);

        // Walk through the entities in the game.
        for (Entity entity : game.getEntitiesVector()) {
            final Coords coords = entity.getPosition();

            // If the entity is infantry in the affected hex?
            if ((entity instanceof Infantry) && bldg.isIn(coords) && coords.equals(hexCoords)) {
                // Is the entity is inside of the building
                // (instead of just on top of it)?
                if (Compute.isInBuilding(game, entity, coords)) {

                    // Report if the infantry receive no points of damage.
                    if (toInf == 0) {
                        r = new Report(6445);
                        r.indent(3);
                        r.subject = entity.getId();
                        r.add(entity.getDisplayName());
                        vDesc.addElement(r);
                    } else {
                        // Yup. Damage the entity.
                        r = new Report(6450);
                        r.indent(3);
                        r.subject = entity.getId();
                        r.add(toInf);
                        r.add(entity.getDisplayName());
                        vDesc.addElement(r);
                        // need to adjust damage to conventional infantry
                        // TW page 217 says left over damage gets treated as
                        // direct fire ballistic damage
                        if (!(entity instanceof BattleArmor)) {
                            toInf = Compute.directBlowInfantryDamage(toInf, 0,
                                    WeaponType.WEAPON_DIRECT_FIRE, false, false);
                        }
                        int remaining = toInf;
                        int cluster = toInf;
                        // Battle Armor units use 5 point clusters.
                        if (entity instanceof BattleArmor) {
                            cluster = 5;
                        }
                        while (remaining > 0) {
                            int next = Math.min(cluster, remaining);
                            HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                            vDesc.addAll((damageEntity(entity, hit, next)));
                            remaining -= next;
                        }
                    }

                    Report.addNewline(vDesc);
                } // End infantry-inside-building
            } // End entity-is-infantry-in-building-hex
        } // Handle the next entity

        return vDesc;
    } // End private void damageInfantryIn( Building, int )

    /**
     * Determine if the given building should collapse. If so, inflict the
     * appropriate amount of damage on each entity in the building and update
     * the clients. If the building does not collapse, determine if any entities
     * crash through its floor into its basement. Again, apply appropriate
     * damage.
     *
     * @param bldg
     *            - the <code>Building</code> being checked. This value should
     *            not be <code>null</code>.
     * @param positionMap
     *            - a <code>Hashtable</code> that maps the <code>Coords</code>
     *            positions or each unit in the game to a <code>Vector</code> of
     *            <code>Entity</code>s at that position. This value should not
     *            be <code>null</code>.
     * @param coords
     *            - the <code>Coords</code> of the building hex to be checked
     * @return <code>true</code> if the building collapsed.
     */
    public boolean checkForCollapse(Building bldg, Hashtable<Coords, Vector<Entity>> positionMap,
                                    Coords coords, boolean checkBecauseOfDamage, Vector<Report> vPhaseReport) {

        // If the input is meaningless, do nothing and throw no exception.
        if ((bldg == null) || (positionMap == null) || positionMap.isEmpty() || (coords == null) || !bldg.isIn(coords) || !bldg.hasCFIn(coords)) {
            return false;
        }

        // Get the building's current CF.
        int currentCF = bldg.getCurrentCF(coords);

        // Track all units that fall into the building's basement by Coords.
        Hashtable<Coords, Vector<Entity>> basementMap = new Hashtable<>();

        // look for a collapse.
        boolean collapse = (checkBecauseOfDamage && (currentCF <= 0));
        boolean basementCollapse = false;
        boolean topFloorCollapse = false;

        // Get the Vector of Entities at these coordinates.
        final Vector<Entity> vector = positionMap.get(coords);

        // Are there any Entities at these coords?
        if (vector != null) {
            // How many levels does this building have in this hex?
            final IHex curHex = game.getBoard().getHex(coords);
            final int numFloors = Math.max(0, curHex.terrainLevel(Terrains.BLDG_ELEV));
            final int bridgeEl = curHex.terrainLevel(Terrains.BRIDGE_ELEV);
            int numLoads = numFloors;
            if (bridgeEl != ITerrain.LEVEL_NONE) {
                numLoads++;
            }
            if (numLoads < 1) {
                MegaMek.getLogger().error("Check for collapse: hex " + coords.toString() 
                        + " has no bridge or building");
                return false;
            }

            // Track the load of each floor (and of the roof) separately.
            // Track all units that fall into the basement in this hex.
            // track all floors, ground at index 0, the first floor is at
            // index 1, the second is at index 1, etc., and the roof is
            // at index (numFloors).
            // if bridge is present, bridge will be numFloors+1
            double[] loads = new double[numLoads + 1];
            // WiGEs flying over the building are also tracked, but can only collapse the top floor
            // and only count 25% of their tonnage.
            double wigeLoad = 0;
            // track all units that might fall into the basement
            Vector<Entity> basement = new Vector<>();

            boolean recheckLoop = true;
            for (int i = 0; (i < 2) && recheckLoop; i++) {
                recheckLoop = false;
                Arrays.fill(loads, 0);

                // Walk through the entities in this position.
                Enumeration<Entity> entities = vector.elements();
                while (!collapse && entities.hasMoreElements()) {
                    final Entity entity = entities.nextElement();
                    // WiGEs can collapse the top floor of a building by flying over it.
                    int entityElev = entity.getElevation();
                    final boolean wigeFlyover = entity.getMovementMode() == EntityMovementMode.WIGE
                            && entityElev == numFloors + 1;

                    if (entityElev != bridgeEl) {
                        // Ignore entities not *inside* the building
                        if (entityElev > numFloors && !wigeFlyover) {
                            continue;
                        }
                        // if we're under a bridge, we can't collapse the bridge
                        if (entityElev < bridgeEl) {
                            continue;
                        }
                    }

                    if ((entity.getMovementMode() == EntityMovementMode.HYDROFOIL)
                            || (entity.getMovementMode() == EntityMovementMode.NAVAL)
                            || (entity.getMovementMode() == EntityMovementMode.SUBMARINE)
                            || (entity.getMovementMode() == EntityMovementMode.INF_UMU)
                            || entity.hasWorkingMisc(MiscType.F_FULLY_AMPHIBIOUS)) {
                        continue; // under the bridge even at same level
                    }

                    if (entityElev == 0) {
                        basement.add(entity);
                    }

                    // units already in the basement
                    if (entityElev < 0) {
                        continue;
                    }

                    // Add the weight to the correct floor.
                    double load = entity.getWeight();
                    if (entityElev == bridgeEl) {
                        entityElev = numLoads;
                    }
                    // Entities on the roof fall to the previous top floor/new roof
                    if (topFloorCollapse && entityElev == numFloors) {
                        entityElev--;
                    }

                    if (wigeFlyover) {
                        wigeLoad += load;
                        if (wigeLoad > currentCF * 4) {
                            topFloorCollapse = true;
                            loads[numFloors - 1] += loads[numFloors];
                            loads[numFloors] = 0;
                        }
                    } else {
                        loads[entityElev] += load;
                        if (loads[entityElev] > currentCF) {
                            // If the load on any floor but the ground floor
                            // exceeds the building's current CF it collapses.
                            if (entityElev != 0) {
                                collapse = true;
                            } else if (!bldg.getBasementCollapsed(coords)) {
                                basementCollapse = true;
                            }
                        }
                    } // End increase-load
                } // Handle the next entity.

                // Track all entities that fell into the basement.
                if (basementCollapse) {
                    basementMap.put(coords, basement);
                }

                // did anyone fall into the basement?
                if (!basementMap.isEmpty() && (bldg.getBasement(coords) != BasementType.NONE) && !collapse) {
                    collapseBasement(bldg, basementMap, coords, vPhaseReport);
                    if (currentCF == 0) {
                        collapse = true;
                    } else {
                        recheckLoop = true; // basement collapse might cause a further collapse
                    }
                }
            } // End have-entities-here
        }

        // Collapse the building if the flag is set.
        if (collapse) {
            Report r = new Report(2375, Report.PUBLIC);
            r.add(bldg.getName());
            vPhaseReport.add(r);

            collapseBuilding(bldg, positionMap, coords, false, vPhaseReport);
        } else if (topFloorCollapse) {
                Report r = new Report(2376, Report.PUBLIC);
                r.add(bldg.getName());
                vPhaseReport.add(r);

                collapseBuilding(bldg, positionMap, coords, false, true, vPhaseReport);
        }

        // Return true if the building collapsed.
        return collapse || topFloorCollapse;

    } // End private boolean checkForCollapse( Building, Hashtable )

    public void collapseBuilding(Building bldg, Hashtable<Coords, Vector<Entity>> positionMap,
                                 Coords coords, Vector<Report> vPhaseReport) {
        collapseBuilding(bldg, positionMap, coords, true, false, vPhaseReport);
    }

    public void collapseBuilding(Building bldg, Hashtable<Coords, Vector<Entity>> positionMap,
                                 Coords coords, boolean collapseAll, Vector<Report> vPhaseReport) {
        collapseBuilding(bldg, positionMap, coords, collapseAll, false, vPhaseReport);
    }

    /**
     * Collapse a building basement. Inflict the appropriate amount of damage on
     * all entities that fell to the basement. Update all clients.
     *
     * @param bldg
     *            - the <code>Building</code> that has collapsed.
     * @param positionMap
     *            - a <code>Hashtable</code> that maps the <code>Coords</code>
     *            positions or each unit in the game to a <code>Vector</code> of
     *            <code>Entity</code>s at that position. This value should not
     *            be <code>null</code>.
     * @param coords
     *            - The <code>Coords></code> of the building basement hex that
     *            has collapsed
     */
    public void collapseBasement(Building bldg, Hashtable<Coords, Vector<Entity>> positionMap,
                                 Coords coords, Vector<Report> vPhaseReport) {
        if (!bldg.hasCFIn(coords)) {
            return;
        }
        int runningCFTotal = bldg.getCurrentCF(coords);

        // Get the Vector of Entities at these coordinates.
        final Vector<Entity> entities = positionMap.get(coords);

        if (bldg.getBasement(coords) == BasementType.NONE) {
            return;
        } else {
            bldg.collapseBasement(coords, game.getBoard(), vPhaseReport);
        }

        // Are there any Entities at these coords?
        if (entities != null) {

            // Sort in elevation order
            entities.sort((a, b) -> {
                if (a.getElevation() > b.getElevation()) {
                    return -1;
                } else if (a.getElevation() > b.getElevation()) {
                    return 1;
                }
                return 0;
            });
            // Walk through the entities in this position.
            for (Entity entity : entities) {
                // int floor = entity.getElevation();
                int cfDamage = (int) Math.ceil(Math.round(entity.getWeight() / 10.0));

                // all entities should fall
                // ASSUMPTION: PSR to avoid pilot damage
                PilotingRollData psr = entity.getBasePilotingRoll();
                entity.addPilotingModifierForTerrain(psr, coords);

                // fall into basement
                if ((bldg.getBasement(coords) == BasementType.TWO_DEEP_HEAD)
                        || (bldg.getBasement(coords) == BasementType.TWO_DEEP_FEET)) {
                    MegaMek.getLogger().error(entity.getDisplayName() + " is falling 2 floors into " + coords.toString());
                    // Damage is determined by the depth of the basement, so a
                    //  fall of 0 elevation is correct in this case
                    vPhaseReport.addAll(doEntityFall(entity, coords, 0, Compute.d6(), psr, true, false));
                    runningCFTotal -= cfDamage * 2;
                } else if ((bldg.getBasement(coords) != BasementType.NONE)
                           && (bldg.getBasement(coords) != BasementType.ONE_DEEP_NORMALINFONLY)) {
                    MegaMek.getLogger().error(entity.getDisplayName() + " is falling 1 floor into " + coords.toString());
                    // Damage is determined by the depth of the basement, so a
                    //  fall of 0 elevation is correct in this case
                    vPhaseReport.addAll(doEntityFall(entity, coords, 0, Compute.d6(), psr, true, false));
                    runningCFTotal -= cfDamage;
                } else {
                    MegaMek.getLogger().error(entity.getDisplayName() + " is not falling into " + coords.toString());
                }

                // Update this entity.
                // ASSUMPTION: this is the correct thing to do.
                entityManager.entityUpdate(entity.getId());

            } // Handle the next entity.

        } // End have-entities-here.

        // Update the building (cap on zero)
        runningCFTotal = Math.max(0, runningCFTotal);
        bldg.setCurrentCF(runningCFTotal, coords);
        bldg.setPhaseCF(runningCFTotal, coords);
        gamemanager.sendChangedHex(game, coords);
        Vector<Building> buildings = new Vector<>();
        buildings.add(bldg);
        sendChangedBuildings(buildings);
    }

    /**
     * Collapse a building hex. Inflict the appropriate amount of damage on all
     * entities in the building. Update all clients.
     *
     * @param bldg
     *            - the <code>Building</code> that has collapsed.
     * @param positionMap
     *            - a <code>Hashtable</code> that maps the <code>Coords</code>
     *            positions or each unit in the game to a <code>Vector</code> of
     *            <code>Entity</code>s at that position. This value should not
     *            be <code>null</code>.
     * @param coords
     *            - The <code>Coords></code> of the building hex that has
     *            collapsed
     * @param collapseAll
     *            - A <code>boolean</code> indicating whether or not this
     *            collapse of a hex should be able to collapse the whole
     *            building
     * @param topFloor
     *            - A <code>boolean</code> indicating that only the top floor collapses
     *              (from a WiGE flying over the top).
     *
     */
    public void collapseBuilding(Building bldg, Hashtable<Coords, Vector<Entity>> positionMap, Coords coords,
                                 boolean collapseAll, boolean topFloor, Vector<Report> vPhaseReport) {
        // sometimes, buildings that reach CF 0 decide against collapsing
        // but we want them to go away anyway, as a building with CF 0 cannot stand
        final int phaseCF = bldg.hasCFIn(coords) ? bldg.getPhaseCF(coords) : 0;

        // Loop through the hexes in the building, and apply
        // damage to all entities inside or on top of the building.
        Report r;
        
        // Get the Vector of Entities at these coordinates.
        final Vector<Entity> vector = positionMap.get(coords);

        // Are there any Entities at these coords?
        if (vector != null) {
            // How many levels does this building have in this hex?
            final IHex curHex = game.getBoard().getHex(coords);
            final int bridgeEl = curHex.terrainLevel(Terrains.BRIDGE_ELEV);
            final int numFloors = Math.max(bridgeEl, curHex.terrainLevel(Terrains.BLDG_ELEV));

            // Now collapse the building in this hex, so entities fall to
            // the ground
            if (topFloor && numFloors > 1) {
                curHex.removeTerrain(Terrains.BLDG_ELEV);
                curHex.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.BLDG_ELEV, numFloors - 1));
                gamemanager.sendChangedHex(game, coords);
            } else {
                bldg.setCurrentCF(0, coords);
                bldg.setPhaseCF(0, coords);
                send(PacketFactory.createCollapseBuildingPacket(coords));
                game.getBoard().collapseBuilding(coords);
            }

            // Sort in elevation order
            vector.sort((a, b) -> {
                if (a.getElevation() > b.getElevation()) {
                    return -1;
                } else if (a.getElevation() > b.getElevation()) {
                    return 1;
                }
                return 0;
            });
            // Walk through the entities in this position.
            Vector<Entity> entities = vector;
            for (Entity entity : entities) {
                // all gun emplacements are simply destroyed
                if (entity instanceof GunEmplacement) {
                    vPhaseReport.addAll(entityManager.destroyEntity(entity, "building collapse"));
                    reportmanager.addNewLines();
                    continue;
                }

                int floor = entity.getElevation();
                // If only the top floor collapses, we only care about units on the top level
                // or on the roof.
                if (topFloor && floor < numFloors - 1) {
                    continue;
                }
                // units trapped in a basement under a collapsing building are
                // destroyed
                if (floor < 0) {
                    vPhaseReport.addAll(entityManager.destroyEntity(entity,
                            "Crushed under building rubble", false, false));
                }

                // Ignore units above the building / bridge.
                if (floor > numFloors) {
                    continue;
                }

                // Treat units on the roof like
                // they were in the top floor.
                if (floor == numFloors) {
                    floor--;
                }

                // Calculate collapse damage for this entity.
                int damage = (int) Math.floor(bldg.getDamageFromScale()
                        * Math.ceil((phaseCF * (numFloors - floor)) / 10.0));

                // Infantry suffer more damage.
                if (entity instanceof Infantry) {
                    if ((entity instanceof BattleArmor) || ((Infantry) entity).isMechanized()) {
                        damage *= 2;
                    } else {
                        damage *= 3;
                    }
                }

                // Apply collapse damage the entity.
                r = new Report(6455);
                r.indent();
                r.subject = entity.getId();
                r.add(entity.getDisplayName());
                r.add(damage);
                vPhaseReport.add(r);
                int remaining = damage;
                int cluster = damage;
                if ((entity instanceof BattleArmor) || (entity instanceof Mech) || (entity instanceof Tank)) {
                    cluster = 5;
                }
                while (remaining > 0) {
                    int next = Math.min(cluster, remaining);
                    int table;
                    if (entity instanceof Protomech) {
                        table = ToHitData.HIT_SPECIAL_PROTO;
                    } else if (entity.getElevation() == numFloors) {
                        table = ToHitData.HIT_NORMAL;
                    } else {
                        table = ToHitData.HIT_PUNCH;
                    }
                    HitData hit = entity.rollHitLocation(table, ToHitData.SIDE_FRONT);
                    hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                    vPhaseReport.addAll(damageEntity(entity, hit, next));
                    remaining -= next;
                }
                vPhaseReport.add(new Report(1210, Report.PUBLIC));

                // all entities should fall
                floor = entity.getElevation();
                if ((floor > 0) || (floor == bridgeEl)) {
                    // ASSUMPTION: PSR to avoid pilot damage
                    // should use mods for entity damage and
                    // 20+ points of collapse damage (if any).
                    PilotingRollData psr = entity.getBasePilotingRoll();
                    entity.addPilotingModifierForTerrain(psr, coords);
                    if (damage >= 20) {
                        psr.addModifier(1, "20+ damage");
                    }
                    vPhaseReport.addAll(doEntityFallsInto(entity, coords, psr, true));
                }
                // Update this entity.
                // ASSUMPTION: this is the correct thing to do.
                entityManager.entityUpdate(entity.getId());

            } // Handle the next entity.

        } // End have-entities-here.

        else {
            // Update the building.
            bldg.setCurrentCF(0, coords);
            bldg.setPhaseCF(0, coords);
            send(PacketFactory.createCollapseBuildingPacket(coords));
            game.getBoard().collapseBuilding(coords);
        }
        // if more than half of the hexes are gone, collapse all
        if (bldg.getCollapsedHexCount() > (bldg.getOriginalHexCount() / 2)) {
            Vector<Coords> coordsVec = bldg.getCoordsVector();
            for (Coords coords1 : coordsVec) {
                collapseBuilding(bldg, game.getPositionMap(), coords1, false, vPhaseReport);
            }
        }

    } // End private void collapseBuilding( Building )

    /**
     * Apply this phase's damage to all buildings. Buildings may collapse due to
     * damage.
     */
    private void applyBuildingDamage() {
        // Walk through the buildings in the game.
        // Build the collapse and update vectors as you go.
        // N.B. never, NEVER, collapse buildings while you are walking through
        // the Enumeration from megamek.common.Board#getBuildings.
        Map<Building, Vector<Coords>> collapse = new HashMap<>();
        Map<Building, Vector<Coords>> update = new HashMap<>();
        Enumeration<Building> buildings = game.getBoard().getBuildings();
        while (buildings.hasMoreElements()) {
            Building bldg = buildings.nextElement();
            Vector<Coords> collapseCoords = new Vector<>();
            Vector<Coords> updateCoords = new Vector<>();
            Vector<Coords> buildingCoords = bldg.getCoordsVector();
            for (Coords coords : buildingCoords) {
                // If the CF is zero, the building should fall.
                if (bldg.getCurrentCF(coords) == 0) {
                    collapseCoords.addElement(coords);
                }
                // If the building took damage this round, update it.
                else if (bldg.getPhaseCF(coords) != bldg.getCurrentCF(coords)) {
                    bldg.setPhaseCF(bldg.getCurrentCF(coords), coords);
                    updateCoords.addElement(coords);
                }
            }
            collapse.put(bldg, collapseCoords);
            update.put(bldg, updateCoords);
        } // Handle the next building

        // If we have any buildings to collapse, collapse them now.
        if (!collapse.isEmpty()) {
            // Get the position map of all entities in the game.
            Hashtable<Coords, Vector<Entity>> positionMap = game.getPositionMap();

            // Walk through the hexes that have collapsed.
            for (Building bldg : collapse.keySet()) {
                Vector<Coords> coordsVector = collapse.get(bldg);
                for (Coords coords : coordsVector) {
                    Report r = new Report(6460, Report.PUBLIC);
                    r.add(bldg.getName());
                    reportmanager.addReport(r);
                    collapseBuilding(bldg, positionMap, coords, reportmanager.getvPhaseReport());
                }
            }
        }

        // check for buildings which should collapse due to being overloaded now
        // CF is reduced
        if (!update.isEmpty()) {
            Hashtable<Coords, Vector<Entity>> positionMap = game.getPositionMap();
            for (Building bldg : update.keySet()) {
                Vector<Coords> updateCoords = update.get(bldg);
                Vector<Coords> coordsToRemove = new Vector<>();
                for (Coords coords : updateCoords) {
                    if (checkForCollapse(bldg, positionMap, coords, false,
                                         reportmanager.getvPhaseReport())) {
                        coordsToRemove.add(coords);
                    }
                }
                updateCoords.removeAll(coordsToRemove);
                update.put(bldg, updateCoords);
            }
        }

        // If we have any buildings to update, send the message.
        if (!update.isEmpty()) {
            sendChangedBuildings(new Vector<>(update.keySet()));
        }
    }

    /**
     * Apply the given amount of damage to the building. Please note, this
     * method does <b>not</b> apply any damage to units inside the building,
     * update the clients, or check for the building's collapse.
     * <p/>
     * A default message will be used to describe why the building took the
     * damage.
     *
     * @param bldg   - the <code>Building</code> that has been damaged. This value
     *               should not be <code>null</code>, but no exception will occur.
     * @param damage - the <code>int</code> amount of damage.
     * @param coords - the <code>Coords</code> of the building hex to be damaged
     * @return a <code>Report</code> to be shown to the players.
     */
    public Vector<Report> damageBuilding(Building bldg, int damage, Coords coords) {
        return damageBuilding(bldg, damage, " absorbs ", coords);
    }

    /**
     * Apply the given amount of damage to the building. Please note, this
     * method does <b>not</b> apply any damage to units inside the building,
     * update the clients, or check for the building's collapse.
     *
     * @param bldg   - the <code>Building</code> that has been damaged. This value
     *               should not be <code>null</code>, but no exception will occur.
     * @param damage - the <code>int</code> amount of damage.
     * @param why    - the <code>String</code> message that describes why the
     *               building took the damage.
     * @param coords - the <code>Coords</code> of the building hex to be damaged
     * @return a <code>Report</code> to be shown to the players.
     */
    public Vector<Report> damageBuilding(Building bldg, int damage, String why, Coords coords) {
        Vector<Report> vPhaseReport = new Vector<>();
        Report r = new Report(1210, Report.PUBLIC);

        // Do nothing if no building or no damage was passed.
        if ((bldg != null) && (damage > 0)) {
            r.messageId = 3435;
            r.add(bldg.toString());
            r.add(why);
            r.add(damage);
            vPhaseReport.add(r);
            int curArmor = bldg.getArmor(coords);
            if (curArmor >= damage) {
                curArmor -= Math.min(curArmor, damage);
                bldg.setArmor(curArmor, coords);
                r = new Report(3436, Report.PUBLIC);
                r.indent(0);
                r.add(damage);
                r.add(curArmor);
                vPhaseReport.add(r);
            } else {
                r.add(damage);
                if (curArmor > 0) {
                    bldg.setArmor(0, coords);
                    damage = damage - curArmor;
                    r = new Report(3436, Report.PUBLIC);
                    r.indent(0);
                    r.add(curArmor);
                    r.add(0);
                    vPhaseReport.add(r);
                }
                // TODO (Sam): What if equal to one?
                damage = (int) Math.floor(bldg.getDamageToScale() * damage);
                if (bldg.getDamageToScale() < 1.0) {
                    r = new Report(3437, Report.PUBLIC);
                    r.indent(0);
                    r.add(damage);
                    vPhaseReport.add(r);
                }
                if (bldg.getDamageToScale() > 1.0) {
                    r = new Report(3438, Report.PUBLIC);
                    r.indent(0);
                    r.add(damage);
                    vPhaseReport.add(r);
                }
                int curCF = bldg.getCurrentCF(coords);
                final int startingCF = curCF;
                curCF -= Math.min(curCF, damage);
                bldg.setCurrentCF(curCF, coords);

                r = new Report(6436, Report.PUBLIC);
                r.indent(1);
                String fontColorOpen = curCF <= 0 ? "<font color='C00000'>" : "";
                String fontColorClose = curCF <= 0 ? "</font>" : "";
                r.add(String.format("%s%s%s", fontColorOpen, curCF, fontColorClose));
                vPhaseReport.add(r);

                final int damageThresh = (int) Math.ceil(bldg.getPhaseCF(coords) / 10.0);

                // If the CF is zero, the building should fall.
                if ((curCF == 0) && (startingCF != 0)) {
                    if (bldg instanceof FuelTank) {
                        // If this is a fuel tank, we'll give it its own
                        // message.
                        r = new Report(3441, Report.PUBLIC);
                        r.indent(0);
                        vPhaseReport.add(r);
                        // ...But we ALSO need to blow up everything nearby.
                        // Bwahahahahaha...
                        r = new Report(3560, Report.PUBLIC);
                        r.newlines = 1;
                        vPhaseReport.add(r);
                        Vector<Report> vRep = new Vector<>();
                        doExplosion(((FuelTank) bldg).getMagnitude(), 10, false,
                                bldg.getCoords().nextElement(), true, vRep, null, -1);
                        Report.indentAll(vRep, 2);
                        vPhaseReport.addAll(vRep);
                        return vPhaseReport;
                    }
                    if (bldg.getType() == Building.WALL) {
                        r = new Report(3442, Report.PUBLIC);
                    } else {
                        r = new Report(3440, Report.PUBLIC);
                    }
                    r.indent(0);
                    vPhaseReport.add(r);
                } else if ((curCF < startingCF) && (damage > damageThresh)) {
                    // need to check for crits
                    // don't bother unless we have some gun emplacements
                    Vector<GunEmplacement> guns = game.getGunEmplacements(coords);
                    if (guns.size() > 0) {
                        vPhaseReport.addAll(criticalGunEmplacement(guns, bldg, coords));
                    }
                }
            }
        }
        Report.indentAll(vPhaseReport, 2);
        return vPhaseReport;
    }

    private Vector<Report> criticalGunEmplacement(Vector<GunEmplacement> guns, Building bldg, Coords coords) {
        Vector<Report> vDesc = new Vector<>();
        Report r = new Report(3800, Report.PUBLIC);
        r.indent(0);
        vDesc.add(r);

        int critRoll = Compute.d6(2);
        if (critRoll < 6) {
            r = new Report(3805, Report.PUBLIC);
            r.indent(1);
            vDesc.add(r);
        } else if (critRoll == 6) {
            // weapon malfunction
            // lets just randomly determine which weapon gets hit
            Vector<Mounted> wpns = new Vector<>();
            for (GunEmplacement gun : guns) {
                for (Mounted wpn : gun.getWeaponList()) {
                    if (!wpn.isHit() && !wpn.isJammed() && !wpn.jammedThisPhase()) {
                        wpns.add(wpn);
                    }
                }
            }
            if (wpns.size() > 0) {
                Mounted weapon = wpns.elementAt(Compute.randomInt(wpns.size()));
                weapon.setJammed(true);
                ((GunEmplacement) weapon.getEntity()).addJammedWeapon(weapon);
                r = new Report(3845, Report.PUBLIC);
                r.indent(1);
                r.add(weapon.getDesc());
            } else {
                r = new Report(3846, Report.PUBLIC);
                r.indent(1);
            }
            vDesc.add(r);
        } else if (critRoll == 7) {
            // gunners stunned
            for (GunEmplacement gun : guns) {
                gun.stunCrew();
                r = new Report(3810, Report.PUBLIC);
                r.indent(1);
                vDesc.add(r);
            }
        } else if (critRoll == 8) {
            // weapon destroyed
            // lets just randomly determine which weapon gets hit
            Vector<Mounted> wpns = new Vector<>();
            for (GunEmplacement gun : guns) {
                for (Mounted wpn : gun.getWeaponList()) {
                    if (!wpn.isHit()) {
                        wpns.add(wpn);
                    }
                }
            }
            if (wpns.size() > 0) {
                Mounted weapon = wpns.elementAt(Compute.randomInt(wpns.size()));
                weapon.setHit(true);
                r = new Report(3840, Report.PUBLIC);
                r.indent(1);
                r.add(weapon.getDesc());
            } else {
                r = new Report(3841, Report.PUBLIC);
                r.indent(1);
            }
            vDesc.add(r);
        } else if (critRoll == 9) {
            // gunners killed
            r = new Report(3815, Report.PUBLIC);
            r.indent(1);
            vDesc.add(r);
            for (GunEmplacement gun : guns) {
                gun.getCrew().setDoomed(true);
            }
        } else if (critRoll == 10) {
            if (Compute.d6() > 3) {
                // turret lock
                r = new Report(3820, Report.PUBLIC);
                r.indent(1);
                vDesc.add(r);
                for (GunEmplacement gun : guns) {
                    gun.lockTurret(gun.getLocTurret());
                }
            } else {
                // turret jam
                r = new Report(3825, Report.PUBLIC);
                r.indent(1);
                vDesc.add(r);
                for (GunEmplacement gun : guns) {
                    if (gun.isTurretEverJammed(gun.getLocTurret())) {
                        gun.lockTurret(gun.getLocTurret());
                    } else {
                        gun.jamTurret(gun.getLocTurret());
                    }
                }
            }
        } else if (critRoll == 11) {
            r = new Report(3830, Report.PUBLIC);
            r.indent(1);
            r.add(bldg.getName());
            int boom = 0;
            for (GunEmplacement gun : guns) {
                for (Mounted ammo : gun.getAmmo()) {
                    ammo.setHit(true);
                    if (ammo.getType().isExplosive(ammo)) {
                        boom += ammo.getHittableShotsLeft() * ((AmmoType) ammo.getType()).getDamagePerShot()
                                * ((AmmoType) ammo.getType()).getRackSize();
                    }
                }
            }
            boom = (int) Math.floor(bldg.getDamageToScale() * boom);
            
            if (boom == 0) {
                Report rNoAmmo = new Report(3831);
                rNoAmmo.type = Report.PUBLIC;
                rNoAmmo.indent(1);
                vDesc.add(rNoAmmo);
                return vDesc;
            }
            
            r.add(boom);
            int curCF = bldg.getCurrentCF(coords);
            curCF -= Math.min(curCF, boom);
            bldg.setCurrentCF(curCF, coords);
            r.add(bldg.getCurrentCF(coords));
            vDesc.add(r);
            // If the CF is zero, the building should fall.
            if ((curCF == 0) && (bldg.getPhaseCF(coords) != 0)) {
                
                // when a building collapses due to an ammo explosion, we can consider
                // that turret annihilated for the purposes of salvage.
                for (GunEmplacement gun : guns) {
                    vDesc.addAll(entityManager.destroyEntity(gun, "ammo explosion", false, false));
                }
                
                if (bldg instanceof FuelTank) {
                    // If this is a fuel tank, we'll give it its own
                    // message.
                    r = new Report(3441, Report.PUBLIC);
                    r.indent(0);
                    vDesc.add(r);
                    // ...But we ALSO need to blow up everything nearby.
                    // Bwahahahahaha...
                    r = new Report(3560, Report.PUBLIC);
                    r.newlines = 1;
                    vDesc.add(r);
                    Vector<Report> vRep = new Vector<>();
                    doExplosion(((FuelTank) bldg).getMagnitude(), 10, false,
                                bldg.getCoords().nextElement(), true, vRep, null, -1);
                    Report.indentAll(vRep, 2);
                    vDesc.addAll(vRep);
                    return reportmanager.getvPhaseReport();
                }
                if (bldg.getType() == Building.WALL) {
                    r = new Report(3442);
                } else {
                    r = new Report(3440);
                }
                r.type = Report.PUBLIC;
                r.indent(0);
                vDesc.add(r);
            }
        } else if (critRoll == 12) {
            // non-weapon equipment is hit
            Vector<Mounted> equipmentList = new Vector<>();
            for (GunEmplacement gun : guns) {
                for (Mounted equipment : gun.getMisc()) {
                    if (!equipment.isHit()) {
                        equipmentList.add(equipment);
                    }
                }
            }
            
            if (equipmentList.size() > 0) {
                Mounted equipment = equipmentList.elementAt(Compute.randomInt(equipmentList.size()));
                equipment.setHit(true);
                r = new Report(3840, Report.PUBLIC);
                r.indent(1);
                r.add(equipment.getDesc());
            } else {
                r = new Report(3835, Report.PUBLIC);
                r.indent(1);
            }
            vDesc.add(r);
        }
        return vDesc;
    }

    public void sendChangedBuildings(Vector<Building> buildings) {
        send(PacketFactory.createUpdateBuildingPacket(buildings));
    }

    /**
     * Damage the inner structure of a mech's leg / a tank's front. This only
     * happens when the Entity fails an extreme Gravity PSR.
     *
     * @param entity The <code>Entity</code> to damage.
     * @param damage The <code>int</code> amount of damage.
     */
    private Vector<Report> doExtremeGravityDamage(Entity entity, int damage) {
        Vector<Report> vPhaseReport = new Vector<>();
        HitData hit;
        if (entity instanceof BipedMech) {
            for (int i = 6; i <= 7; i++) {
                hit = new HitData(i);
                vPhaseReport.addAll(damageEntity(entity, hit, damage, false, DamageType.NONE, true));
            }
        }
        if (entity instanceof QuadMech) {
            for (int i = 4; i <= 7; i++) {
                hit = new HitData(i);
                vPhaseReport.addAll(damageEntity(entity, hit, damage, false, DamageType.NONE, true));
            }
        } else if (entity instanceof Tank) {
            hit = new HitData(Tank.LOC_FRONT);
            vPhaseReport.addAll(damageEntity(entity, hit, damage, false, DamageType.NONE, true));
            vPhaseReport.addAll(vehicleMotiveDamage((Tank)entity, 0));
        }
        return vPhaseReport;
    }

    /**
     * Eject an Entity.
     *
     * @param entity    The <code>Entity</code> to eject.
     * @param autoEject The <code>boolean</code> state of the entity's auto- ejection
     *                  system
     * @return a <code>Vector</code> of report objects for the game log.
     */
    public Vector<Report> ejectEntity(Entity entity, boolean autoEject) {
        return entityManager.ejectEntity(entity, autoEject, false);
    }

    /**
     * Eject an Entity.
     *
     * @param entity            The <code>Entity</code> to eject.
     * @param autoEject         The <code>boolean</code> state of the entity's auto- ejection
     *                          system
     * @param skin_of_the_teeth Perform a skin of the teeth ejection
     * @return a <code>Vector</code> of report objects for the game log.
     */
    public Vector<Report> ejectEntity(Entity entity, boolean autoEject, boolean skin_of_the_teeth) {
        return entityManager.ejectEntity(entity, autoEject, skin_of_the_teeth);
    }

    public static PilotingRollData getEjectModifiers(IGame game,
            Entity entity, int crewPos, boolean autoEject) {
        int facing = entity.getFacing();
        if (entity.isPartOfFighterSquadron()) {
            // Because the components of a squadron have no position and will pass the next test
            Entity squadron = game.getEntity(entity.getTransportId());
            return getEjectModifiers(game, entity, crewPos, autoEject, squadron.getPosition(), "ejecting");
        }
        if(null == entity.getPosition()) {
            // Off-board unit?
            return new PilotingRollData(entity.getId(), entity.getCrew().getPiloting(), "ejecting");
        }
        Coords targetCoords = entity.getPosition().translated((facing + 3) % 6);
        return getEjectModifiers(game, entity, crewPos, autoEject, targetCoords, "ejecting");
    }

    public static PilotingRollData getEjectModifiers(IGame game, Entity entity, int crewPos,
            boolean autoEject, Coords targetCoords, String desc) {
        PilotingRollData rollTarget = new PilotingRollData(entity.getId(),
                entity.getCrew().getPiloting(crewPos), desc);
        // Per SO p26, fighters can eject as per TO rules on 196 with some exceptions
        if (entity.isProne()) {
            rollTarget.addModifier(5, "Mech is prone");
        }
        if (entity.getCrew().isUnconscious(crewPos)) {
            rollTarget.addModifier(3, "pilot unconscious");
        }
        if (autoEject) {
            rollTarget.addModifier(1, "automatic ejection");
        }
        // Per SO p27, Large Craft roll too, to see how many escape pods launch successfully
        if ((entity.isAero() && ((IAero)entity).isOutControl())
                || (entity.isPartOfFighterSquadron() && ((IAero)game.getEntity(entity.getTransportId())).isOutControl())) {
            rollTarget.addModifier(5, "Out of Control");
        }
        // A decreased large craft crew makes it harder to eject large numbers of pods
        if (entity.isLargeCraft() && entity.getCrew().getHits() > 0) {
            rollTarget.addModifier(entity.getCrew().getHits(), "Crew hits");
        }
        if ((entity instanceof Mech)
                && (entity.getInternal(Mech.LOC_HEAD) < entity.getOInternal(Mech.LOC_HEAD))) {
            rollTarget.addModifier(entity.getOInternal(Mech.LOC_HEAD) - entity.getInternal(Mech.LOC_HEAD),
                    "Head Internal Structure Damage");
        }
        IHex targetHex = game.getBoard().getHex(targetCoords);
        //Terrain modifiers should only apply if the unit is on the ground...
        if (!entity.isSpaceborne() && !entity.isAirborne()) {
            if (targetHex != null) {
                if ((targetHex.terrainLevel(Terrains.WATER) > 0) && !targetHex.containsTerrain(Terrains.ICE)) {
                    rollTarget.addModifier(-1, "landing in water");
                } else if (targetHex.containsTerrain(Terrains.ROUGH)) {
                    rollTarget.addModifier(0, "landing in rough");
                } else if (targetHex.containsTerrain(Terrains.RUBBLE)) {
                    rollTarget.addModifier(0, "landing in rubble");
                } else if (targetHex.terrainLevel(Terrains.WOODS) == 1) {
                    rollTarget.addModifier(2, "landing in light woods");
                } else if (targetHex.terrainLevel(Terrains.WOODS) == 2) {
                    rollTarget.addModifier(3, "landing in heavy woods");
                } else if (targetHex.terrainLevel(Terrains.WOODS) == 3) {
                    rollTarget.addModifier(4, "landing in ultra heavy woods");
                } else if (targetHex.terrainLevel(Terrains.JUNGLE) == 1) {
                    rollTarget.addModifier(3, "landing in light jungle");
                } else if (targetHex.terrainLevel(Terrains.JUNGLE) == 2) {
                    rollTarget.addModifier(5, "landing in heavy jungle");
                } else if (targetHex.terrainLevel(Terrains.JUNGLE) == 3) {
                    rollTarget.addModifier(7, "landing in ultra heavy jungle");
                } else if (targetHex.terrainLevel(Terrains.BLDG_ELEV) > 0) {
                    rollTarget.addModifier(targetHex.terrainLevel(Terrains.BLDG_ELEV), "landing in a building");
                } else {
                    rollTarget.addModifier(-2, "landing in clear terrain");
                }
            } else {
                rollTarget.addModifier(-2, "landing off the board");
            }
        }
        if (!entity.isSpaceborne()) {
            //At present, the UI lets you set these atmospheric conditions for a space battle, but it shouldn't
            //That's a fix for another day, probably when I get around to space terrain and 'weather'
            if (game.getPlanetaryConditions().getGravity() == 0) {
                rollTarget.addModifier(3, "Zero-G");
            } else if (game.getPlanetaryConditions().getGravity() < .8) {
                rollTarget.addModifier(2, "Low-G");
            } else if (game.getPlanetaryConditions().getGravity() > 1.2) {
                rollTarget.addModifier(2, "High-G");
            }

            //Vacuum shouldn't apply to ASF ejection since they're designed for it, but the rules don't specify
            //High and low pressures make more sense to apply to all
            if (game.getPlanetaryConditions().getAtmosphere() == PlanetaryConditions.ATMO_VACUUM) {
                rollTarget.addModifier(3, "Vacuum");
            } else if (game.getPlanetaryConditions().getAtmosphere() == PlanetaryConditions.ATMO_VHIGH) {
                rollTarget.addModifier(2, "Very High Atmosphere Pressure");
            } else if (game.getPlanetaryConditions().getAtmosphere() == PlanetaryConditions.ATMO_TRACE) {
                rollTarget.addModifier(2, "Trace atmosphere");
            }
        }

        if ((game.getPlanetaryConditions().getWeather() == PlanetaryConditions.WE_HEAVY_SNOW)
                || (game.getPlanetaryConditions().getWeather() == PlanetaryConditions.WE_ICE_STORM)
                || (game.getPlanetaryConditions().getWeather() == PlanetaryConditions.WE_DOWNPOUR)
                || (game.getPlanetaryConditions().getWindStrength() == PlanetaryConditions.WI_STRONG_GALE)) {
            rollTarget.addModifier(2, "Bad Weather");
        }

        if ((game.getPlanetaryConditions().getWindStrength() >= PlanetaryConditions.WI_STORM)
                || (game.getPlanetaryConditions().getWeather() == PlanetaryConditions.WE_BLIZZARD)
                || ((game.getPlanetaryConditions().getWeather() == PlanetaryConditions.WE_HEAVY_SNOW) && (game
                        .getPlanetaryConditions().getWindStrength() == PlanetaryConditions.WI_STRONG_GALE))) {
            rollTarget.addModifier(3, "Really Bad Weather");
        }
        return rollTarget;
    }

    /**
     * Creates a new Ballistic Infantry unit at the end of the movement phase
     */
    public void resolveCallSupport() {
        for (Entity e : game.getEntitiesVector()) {
            if ((e instanceof Infantry) && ((Infantry) e).getIsCallingSupport()) {
                // Now lets create a new foot platoon
                Infantry guerrilla = new Infantry();
                guerrilla.setChassis("Insurgents");
                guerrilla.setModel("(Rifle)");
                guerrilla.setSquadN(4);
                guerrilla.setSquadSize(7);
                guerrilla.autoSetInternal();
                guerrilla.getCrew().setGunnery(5, 0);
                try {
                    guerrilla.addEquipment(EquipmentType.get(EquipmentTypeLookup.INFANTRY_ASSAULT_RIFLE),
                            Infantry.LOC_INFANTRY);
                    guerrilla.setPrimaryWeapon((InfantryWeapon) InfantryWeapon
                            .get(EquipmentTypeLookup.INFANTRY_ASSAULT_RIFLE));
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                guerrilla.setDeployed(true);
                guerrilla.setDone(true);
                guerrilla.setId(game.getNextEntityId());
                guerrilla.setOwner(e.getOwner());
                game.addEntity(guerrilla);

                // Add the infantry unit on the battlefield. Should spawn within 3 hexes
                // First get coords then loop over some targets
                Coords tmpCoords = e.getPosition();
                Coords targetCoords = null;
                while (!game.getBoard().contains(targetCoords)) {
                    targetCoords = Compute.scatter(tmpCoords, (Compute.d6(1) / 2));
                    if (game.getBoard().contains(targetCoords)) {
                        guerrilla.setPosition(targetCoords);
                        break;
                    }
                }
                send(PacketFactory.createAddEntityPacket(game, guerrilla.getId()));
                ((Infantry) e).setIsCallingSupport(false);
            }
        }
    }

    /**
     * Abandon an Entity.
     *
     * @param entity The <code>Entity</code> to abandon.
     * @return a <code>Vector</code> of report objects for the game log.
     */
    public Vector<Report> abandonEntity(Entity entity) {
        Vector<Report> vDesc = new Vector<>();

        // An entity can only eject it's crew once.
        if (entity.getCrew().isEjected() || entity.getCrew().isDoomed() || entity.getCrew().isDead()) {
            return vDesc;
        }

        Coords targetCoords = entity.getPosition();

        if (entity instanceof Mech || (entity.isAero() && !entity.isAirborne())) {
            // okay, print the info
            vDesc.addElement(ReportFactory.createReport(2027, 3, entity, entity.getCrew().getName()));
            // Don't make ill-equipped pilots abandon into vacuum
            if (game.getPlanetaryConditions().isVacuum() && !entity.isAero()) {
                return vDesc;
            }

            // create the MechWarrior in any case, for campaign tracking
            MechWarrior pilot = new MechWarrior(entity);
            pilot.getCrew().setUnconscious(entity.getCrew().isUnconscious());
            pilot.setDeployed(true);
            pilot.setId(game.getNextEntityId());
            //Pilot flight suits are vacuum-rated. MechWarriors wear shorts...
            pilot.setSpaceSuit(entity.isAero());
            if (entity.isSpaceborne()) {
                //In space, ejected pilots retain the heading and velocity of the unit they eject from
                pilot.setVectors(entity.getVectors());
                pilot.setFacing(entity.getFacing());
                pilot.setCurrentVelocity(entity.getVelocity());
                //If the pilot ejects, he should no longer be accelerating
                pilot.setNextVelocity(entity.getVelocity());
            }
            game.addEntity(pilot);
            send(PacketFactory.createAddEntityPacket(game, pilot.getId()));
            // make him not get a move this turn
            pilot.setDone(true);
            // Add the pilot as an infantry unit on the battlefield.
            if (game.getBoard().contains(targetCoords)) {
                pilot.setPosition(targetCoords);
            }
            pilot.setCommander(entity.isCommander());
            // Update the entity
            entityManager.entityUpdate(pilot.getId());
            // check if the pilot lands in a minefield
            vDesc.addAll(doEntityDisplacementMinefieldCheck(pilot, targetCoords, entity.getElevation()));
            if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_EJECTED_PILOTS_FLEE)) {
                game.removeEntity(pilot.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
                send(PacketFactory.createRemoveEntityPacket(pilot.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT));
            }
        } // End entity-is-Mek or Aero
        else if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_VEHICLES_CAN_EJECT)
                && (entity instanceof Tank)) {
            // Don't make them abandon into vacuum
            if (game.getPlanetaryConditions().isVacuum()) {
                return vDesc;
            }
            EjectedCrew crew = new EjectedCrew(entity);
            crew.setDeployed(true);
            crew.setId(game.getNextEntityId());
            game.addEntity(crew);
            send(PacketFactory.createAddEntityPacket(game, crew.getId()));
            // Make them not get a move this turn
            crew.setDone(true);
            // Place on board
            if(game.getBoard().contains(entity.getPosition())) {
                crew.setPosition(entity.getPosition());
            }
            // Update the entity
            entityManager.entityUpdate(crew.getId());
            // Check if the crew lands in a minefield
            vDesc.addAll(doEntityDisplacementMinefieldCheck(crew, entity.getPosition(), entity.getElevation()));
            if(game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_EJECTED_PILOTS_FLEE)) {
                game.removeEntity(crew.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
                send(PacketFactory.createRemoveEntityPacket(crew.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT));
            }
        }

        // Mark the entity's crew as "ejected".
        entity.getCrew().setEjected(true);

        return vDesc;
    }

    /**
     * Checks if ejected MechWarriors are eligible to be picked up, and if so,
     * captures them or picks them up
     */
    private void resolveMechWarriorPickUp() {
        Report r;

        // fetch all mechWarriors that are not picked up
        Iterator<Entity> mechWarriors = game.getSelectedEntities(entity -> {
                    if (entity instanceof MechWarrior) {
                        MechWarrior mw = (MechWarrior) entity;
                        return (mw.getPickedUpById() == Entity.NONE) && !mw.isDoomed() && (mw.getTransportId() == Entity.NONE);
                    }
                    return false;
                });
        // loop through them, check if they are in a hex occupied by another
        // unit
        while (mechWarriors.hasNext()) {
            boolean pickedUp = false;
            MechWarrior e = (MechWarrior) mechWarriors.next();
            // Check for owner entities first...
            for (Entity pe : game.getEntitiesVector(e.getPosition())) {
                if (pe.isDoomed() || pe.isShutDown() || pe.getCrew().isUnconscious()
                        || (pe.isAirborne() && !pe.isSpaceborne())
                        || (pe.getElevation() != e.getElevation())
                        || (pe.getOwnerId() != e.getOwnerId())
                        || (pe.getId() == e.getId())) {
                    continue;
                }
                if (pe instanceof MechWarrior) {
                    // MWs have a beer together
                    r = new Report(6415, Report.PUBLIC);
                    r.add(pe.getDisplayName());
                    reportmanager.addReport(r);
                    continue;
                }
                // Pick up the unit.
                pe.pickUp(e);
                // The picked unit is being carried by the loader.
                e.setPickedUpById(pe.getId());
                e.setPickedUpByExternalId(pe.getExternalIdAsString());
                pickedUp = true;
                r = new Report(6420, Report.PUBLIC);
                r.add(e.getDisplayName());
                r.addDesc(pe);
                reportmanager.addReport(r);
                break;
            }
            // Check for allied entities next...
            if (!pickedUp) {
                for (Entity pe : game.getEntitiesVector(e.getPosition())) {
                    if (pe.isDoomed() || pe.isShutDown() || pe.getCrew().isUnconscious()
                            || (pe.isAirborne() && !pe.isSpaceborne())
                            || (pe.getElevation() != e.getElevation())
                            || (pe.getOwnerId() == e.getOwnerId()) || (pe.getId() == e.getId())
                            || (pe.getOwner().getTeam() == IPlayer.TEAM_NONE)
                            || (pe.getOwner().getTeam() != e.getOwner().getTeam())) {
                        continue;
                    }
                    if (pe instanceof MechWarrior) {
                        // MWs have a beer together
                        r = new Report(6415, Report.PUBLIC);
                        r.add(pe.getDisplayName());
                        reportmanager.addReport(r);
                        continue;
                    }
                    // Pick up the unit.
                    pe.pickUp(e);
                    // The picked unit is being carried by the loader.
                    e.setPickedUpById(pe.getId());
                    e.setPickedUpByExternalId(pe.getExternalIdAsString());
                    pickedUp = true;
                    r = new Report(6420, Report.PUBLIC);
                    r.add(e.getDisplayName());
                    r.addDesc(pe);
                    reportmanager.addReport(r);
                    break;
                }
            }
            // Now check for anyone else...
            if (!pickedUp) {
                Iterator<Entity> pickupEnemyEntities = game.getEnemyEntities(e.getPosition(), e);
                while (pickupEnemyEntities.hasNext()) {
                    Entity pe = pickupEnemyEntities.next();
                    if (pe.isDoomed() || pe.isShutDown() || pe.getCrew().isUnconscious()
                            || pe.isAirborne() || (pe.getElevation() != e.getElevation())) {
                        continue;
                    }
                    if (pe instanceof MechWarrior) {
                        // MWs have a beer together
                        r = new Report(6415, Report.PUBLIC);
                        r.add(pe.getDisplayName());
                        reportmanager.addReport(r);
                        continue;
                    }
                    // Capture the unit.
                    pe.pickUp(e);
                    // The captured unit is being carried by the loader.
                    e.setCaptured(true);
                    e.setPickedUpById(pe.getId());
                    e.setPickedUpByExternalId(pe.getExternalIdAsString());
                    pickedUp = true;
                    r = new Report(6420, Report.PUBLIC);
                    r.add(e.getDisplayName());
                    r.addDesc(pe);
                    reportmanager.addReport(r);
                    break;
                }
            }
            if (pickedUp) {
                // Remove the picked-up unit from the screen.
                e.setPosition(null);
                // Update the loaded unit.
                entityManager.entityUpdate(e.getId());
            }
        }
    }

    /**
     * destroy all wheeled and tracked Tanks that got displaced into water
     */
    private void resolveSinkVees() {
        Iterator<Entity> sinkableTanks = game.getSelectedEntities(entity -> {
                    if (entity.isOffBoard() || (entity.getPosition() == null) || !(entity instanceof Tank)) {
                        return false;
                    }
                    final IHex hex = game.getBoard().getHex(entity.getPosition());
                    final boolean onBridge = (hex.terrainLevel(Terrains.BRIDGE) > 0)
                            && (entity.getElevation() == hex.terrainLevel(Terrains.BRIDGE_ELEV));
                    return ((entity.getMovementMode() == EntityMovementMode.TRACKED)
                            || (entity.getMovementMode() == EntityMovementMode.WHEELED)
                            || ((entity.getMovementMode() == EntityMovementMode.HOVER)))
                            && entity.isImmobile() && (hex.terrainLevel(Terrains.WATER) > 0)
                            && !onBridge && !(entity.hasWorkingMisc(MiscType.F_FULLY_AMPHIBIOUS))
                            && !(entity.hasWorkingMisc(MiscType.F_FLOTATION_HULL));
                });
        while (sinkableTanks.hasNext()) {
            Entity e = sinkableTanks.next();
            reportmanager.addReport(entityManager.destroyEntity(e, "a watery grave", false));
        }
    }

    /**
     * let all Entities make their "break-free-of-swamp-stickyness" PSR
     */
    private void doTryUnstuck() {
        if (game.getPhase() != IGame.Phase.PHASE_MOVEMENT) {
            return;
        }

        Report r;

        Iterator<Entity> stuckEntities = game.getSelectedEntities(Entity::isStuck);
        PilotingRollData rollTarget;
        while (stuckEntities.hasNext()) {
            Entity entity = stuckEntities.next();
            if (entity.getPosition() == null) {
                if (entity.isDeployed()) {
                    MegaMek.getLogger().info("Entity #" + entity.getId() + " does not know its position.");
                } else {
                    // If the Entity isn't deployed, then something goofy happened.  We'll just unstuck the Entity
                    entity.setStuck(false);
                    MegaMek.getLogger().info("Entity #" + entity.getId() + " was stuck in a swamp, but not deployed. Stuck state reset");
                }
                continue;
            }
            rollTarget = entity.getBasePilotingRoll();
            entity.addPilotingModifierForTerrain(rollTarget);
            // apart from swamp & liquid magma, -1 modifier
            IHex hex = game.getBoard().getHex(entity.getPosition());
            hex.getUnstuckModifier(entity.getElevation(), rollTarget);
            // okay, print the info
            reportmanager.addReport(ReportFactory.createReport(2340, entity));

            // roll
            final int diceRoll = entity.getCrew().rollPilotingSkill();
            r = ReportFactory.createReport(2190, entity, rollTarget.getValueAsString(), rollTarget.getDesc());
            r.add(diceRoll);
            if (diceRoll < rollTarget.getValue()) {
                r.choose(false);
            } else {
                r.choose(true);
                entity.setStuck(false);
                entity.setCanUnstickByJumping(false);
                entity.setElevation(0);
                entityManager.entityUpdate(entity.getId());
            }
            reportmanager.addReport(r);
        }
    }

    /**
     * Remove all iNarc pods from all vehicles that did not move and shoot this
     * round NOTE: this is not quite what the rules say, the player should be
     * able to choose whether or not to remove all iNarc Pods that are attached.
     */
    private void resolveVeeINarcPodRemoval() {
        Iterator<Entity> vees = game.getSelectedEntities(entity -> (entity instanceof Tank) && (entity.mpUsed == 0));
        boolean canSwipePods;
        while (vees.hasNext()) {
            canSwipePods = true;
            Entity entity = vees.next();
            for (int i = 0; i <= 5; i++) {
                if (entity.weaponFiredFrom(i)) {
                    canSwipePods = false;
                }
            }
            if (((Tank) entity).getStunnedTurns() > 0) {
                canSwipePods = false;
            }
            if (canSwipePods && entity.hasINarcPodsAttached() && entity.getCrew().isActive()) {
                entity.removeAllINarcPods();
                reportmanager.addReport(ReportFactory.createReport(2345, entity));
            }
        }
    }

    /**
     * remove Ice in the hex that's at the passed coords, and let entities fall
     * into water below it, if there is water
     *
     * @param c the <code>Coords</code> of the hex where ice should be removed
     * @return a <code>Vector<Report></code> for the phase report
     */
    public Vector<Report> resolveIceBroken(Coords c) {
        Vector<Report> vPhaseReport = new Vector<>();
        IHex hex = game.getBoard().getHex(c);
        hex.removeTerrain(Terrains.ICE);
        sendChangedHex(c);
        // if there is water below the ice
        if (hex.terrainLevel(Terrains.WATER) > 0) {
            // drop entities on the surface into the water
            for (Entity e : game.getEntitiesVector(c)) {
                // If the unit is on the surface, and is no longer allowed in
                // the hex
                boolean isHoverOrWiGE = (e.getMovementMode() == EntityMovementMode.HOVER)
                        || (e.getMovementMode() == EntityMovementMode.WIGE);
                if ((e.getElevation() == 0)
                        && !(hex.containsTerrain(Terrains.BLDG_ELEV, 0))
                        && !(isHoverOrWiGE && (e.getRunMP() >= 0))
                        && (e.getMovementMode() != EntityMovementMode.INF_UMU)
                        && !e.hasUMU()
                        && !(e instanceof QuadVee && e.getConversionMode() == QuadVee.CONV_MODE_VEHICLE)) {
                    vPhaseReport.addAll(doEntityFallsInto(e, c, new PilotingRollData(TargetRoll.AUTOMATIC_FAIL),
                            true));
                }
            }
        }
        return vPhaseReport;
    }

    /**
     * melt any snow or ice in a hex, including checking for the effects of
     * breaking through ice
     */
    private Vector<Report> meltIceAndSnow(Coords c, int entityId) {
        Vector<Report> vDesc = new Vector<>();
        IHex hex = game.getBoard().getHex(c);
        Report r = new Report(3069);
        r.indent(2);
        r.subject = entityId;
        vDesc.add(r);
        if (hex.containsTerrain(Terrains.SNOW)) {
            hex.removeTerrain(Terrains.SNOW);
            gamemanager.sendChangedHex(game, c);
        }
        if (hex.containsTerrain(Terrains.ICE)) {
            vDesc.addAll(resolveIceBroken(c));
        }
        // if we were not in water, then add mud
        if (!hex.containsTerrain(Terrains.MUD) && !hex.containsTerrain(Terrains.WATER)) {
            hex.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.MUD, 1));
            gamemanager.sendChangedHex(game, c);
        }
        return vDesc;
    }

    /**
     * check to see if a swamp hex becomes quicksand
     */
    public Vector<Report> checkQuickSand(Coords c) {
        Vector<Report> vDesc = new Vector<>();
        IHex hex = game.getBoard().getHex(c);
        if (hex.terrainLevel(Terrains.SWAMP) == 1 && Compute.d6(2) == 12) {
            // better find a rope
            hex.removeTerrain(Terrains.SWAMP);
            hex.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.SWAMP, 2));
            gamemanager.sendChangedHex(game, c);
            Report r = new Report(2440);
            r.indent(1);
            vDesc.add(r);
        }
        return vDesc;
    }

    /**
     * check for vehicle fire, according to the MaxTech rules
     *
     * @param tank    the <code>Tank</code> to be checked
     * @param inferno a <code>boolean</code> parameter whether or not this check is
     *                because of inferno fire
     */
    public Vector<Report> checkForVehicleFire(Tank tank, boolean inferno) {
        Vector<Report> vPhaseReport = new Vector<>();
        int boomRoll = Compute.d6(2);
        int penalty = 0;
        switch (tank.getMovementMode()) {
            case HOVER:
                penalty = 4;
                break;
            case VTOL:
            case WHEELED:
                penalty = 2;
                break;
            default:
                break;
        }
        if (inferno) {
            boomRoll = 12;
        }
        Report r = ReportFactory.createReport(5250, tank, 8 - penalty, boomRoll);
        if ((boomRoll + penalty) < 8) {
            // phew!
            r.choose(true);
            vPhaseReport.add(r);
        } else {
            // eek
            if (!inferno) {
                r.choose(false);
                vPhaseReport.add(r);
            }
            if ((boomRoll + penalty) < 10) {
                reportmanager.addReport(vehicleMotiveDamage(tank, penalty - 1));
            } else {
                vPhaseReport.addAll(resolveVehicleFire(tank, false));
                if ((boomRoll + penalty) >= 12) {
                    vPhaseReport.add(ReportFactory.createReport(5255, 3, tank));
                    tank.setOnFire(inferno);
                }
            }
        }
        return vPhaseReport;
    }

    private Vector<Report> resolveVehicleFire(Tank tank, boolean existingStatus) {
        Vector<Report> vPhaseReport = new Vector<>();
        if (existingStatus && !tank.isOnFire()) {
            return vPhaseReport;
        }
        for (int i = 0; i < tank.locations(); i++) {
            if ((i == Tank.LOC_BODY)
                    || ((tank instanceof VTOL) && (i == VTOL.LOC_ROTOR))
                    || (existingStatus && !tank.isLocationBurning(i))) {
                continue;
            }

            HitData hit = new HitData(i);
            int damage = Compute.d6(1);
            vPhaseReport.addAll(damageEntity(tank, hit, damage));
            if ((damage == 1) && existingStatus) {
                tank.extinguishLocation(i);
            }
        }
        return vPhaseReport;
    }

    public Vector<Report> vehicleMotiveDamage(Tank te, int modifier) {
        return vehicleMotiveDamage(te, modifier, false, -1, false);
    }

    public Vector<Report> vehicleMotiveDamage(Tank te, int modifier, boolean noRoll, int damageType) {
        return vehicleMotiveDamage(te, modifier, noRoll, damageType, false);
    }

    /**
     * do vehicle movement damage
     *
     * @param te         the Tank to damage
     * @param modifier   the modifier to the roll
     * @param noRoll     don't roll, immediately deal damage
     * @param damageType the type to deal (1 = minor, 2 = moderate, 3 = heavy
     * @param jumpDamage is this a movement damage roll from using vehicular JJs
     * @return a <code>Vector<Report></code> containing what to add to the turn log
     */
    public Vector<Report> vehicleMotiveDamage(Tank te, int modifier, boolean noRoll,
                                               int damageType, boolean jumpDamage) {
        Vector<Report> vDesc = new Vector<>();
        switch (te.getMovementMode()) {
            case HOVER:
            case HYDROFOIL:
                modifier += jumpDamage ? -1 : 3;
                break;
            case WHEELED:
                modifier += jumpDamage ? 1 : 2;
                break;
            case WIGE:
                modifier += jumpDamage ? -2 : 4;
                break;
            case TRACKED:
                modifier += jumpDamage ? 2 : 0;
                break;
            case VTOL:
                // VTOL don't roll, auto -1 MP as long as the rotor location
                // still exists (otherwise don't bother reporting).
                if (!(te.isLocationBad(VTOL.LOC_ROTOR) || te.isLocationDoomed(VTOL.LOC_ROTOR))) {
                    te.setMotiveDamage(te.getMotiveDamage() + 1);
                    if (te.getOriginalWalkMP() > te.getMotiveDamage()) {
                        vDesc.add(ReportFactory.createReport(6660, 3, te));
                    } else {
                        vDesc.add(ReportFactory.createReport(6670, te));
                        te.immobilize();
                        // Being reduced to 0 MP by rotor damage forces a
                        // landing like an engine hit...
                        if (te.isAirborneVTOLorWIGE()
                            // ...but don't bother to resolve that if we're
                            // already otherwise destroyed.
                            && !(te.isDestroyed() || te.isDoomed())) {
                            vDesc.addAll(forceLandVTOLorWiGE(te));
                        }
                    }
                }
                // This completes our handling of VTOLs; the rest of the method
                // doesn't need to worry about them anymore.
                return vDesc;
            default:
                break;
        }
        // Apply vehicle effectiveness...except for jumps.
        if (game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_VEHICLE_EFFECTIVE) && !jumpDamage) {
            modifier = Math.max(modifier - 1, 0);
        }

        if (te.hasWorkingMisc(MiscType.F_ARMORED_MOTIVE_SYSTEM)) {
            modifier -= 2;
        }
        int roll = Compute.d6(2) + modifier;
        vDesc.add(ReportFactory.createReport(6306, 3, te));
        if (!noRoll) {
            vDesc.add(ReportFactory.createReport(6310, te, roll));
            vDesc.add(ReportFactory.createReport(3340, te, modifier));
        }

        if ((noRoll && (damageType == 0)) || (!noRoll && (roll <= 5))) {
            // no effect
            vDesc.add(ReportFactory.createReport(6005, 3, te));
        } else if ((noRoll && (damageType == 1)) || (!noRoll && (roll <= 7))) {
            // minor damage
            vDesc.add(ReportFactory.createReport(6470, 3, te));
            te.addMovementDamage(1);
        } else if ((noRoll && (damageType == 2)) || (!noRoll && (roll <= 9))) {
            // moderate damage
            vDesc.add(ReportFactory.createReport(6471, 3, te));
            te.addMovementDamage(2);
        } else if ((noRoll && (damageType == 3)) || (!noRoll && (roll <= 11))) {
            // heavy damage
            vDesc.add(ReportFactory.createReport(6472, 3, te));
            te.addMovementDamage(3);
        } else {
            vDesc.add(ReportFactory.createReport(6473, 3, te));
            te.addMovementDamage(4);
        }
        // These checks should perhaps be moved to Tank.applyDamage(), but I'm
        // unsure how to *report* any outcomes from there. Note that these treat
        // being reduced to 0 MP and being actually immobilized as the same thing,
        // which for these particular purposes may or may not be the intent of
        // the rules in all cases.
        // Immobile hovercraft on water sink...
        if (((te.getMovementMode() == EntityMovementMode.HOVER)
                || ((te.getMovementMode() == EntityMovementMode.WIGE) && (te.getElevation() == 0)))
                && (te.isMovementHitPending() || (te.getWalkMP() <= 0))
                // HACK: Have to check for *pending* hit here and below.
                && (game.getBoard().getHex(te.getPosition()).terrainLevel(Terrains.WATER) > 0)
                && !game.getBoard().getHex(te.getPosition()).containsTerrain(Terrains.ICE)) {
            vDesc.addAll(entityManager.destroyEntity(te, "a watery grave", false));
        }
        // ...while immobile WiGEs crash.
        if (((te.getMovementMode() == EntityMovementMode.WIGE) && (te.isAirborneVTOLorWIGE()))
                && (te.isMovementHitPending() || (te.getWalkMP() <= 0))) {
            // report problem: add tab
            vDesc.addAll(crashVTOLorWiGE(te));
        }
        return vDesc;
    }

    /**
     * do damage from magma
     *
     * @param en       the affected <code>Entity</code>
     * @param eruption <code>boolean</code> indicating whether or not this is because
     *                 of an eruption
     */
    void doMagmaDamage(Entity en, boolean eruption) {
        if ((((en.getMovementMode() == EntityMovementMode.VTOL) && (en.getElevation() > 0))
                || (en.getMovementMode() == EntityMovementMode.HOVER)
                || ((en.getMovementMode() == EntityMovementMode.WIGE)
                && (en.getOriginalWalkMP() > 0) && !eruption)) && !en.isImmobile()) {
            return;
        }
        boolean isMech = en instanceof Mech;
        int reportID = 2400;
        if (isMech) {
            reportID =2405;
        }
        reportmanager.addReport(ReportFactory.createReport(reportID, en));
        if (isMech) {
            HitData h;
            for (int i = 0; i < en.locations(); i++) {
                if (eruption || en.locationIsLeg(i) || en.isProne()) {
                    h = new HitData(i);
                    reportmanager.addReport(damageEntity(en, h, Compute.d6(2)));
                }
            }
        } else {
            reportmanager.addReport(entityManager.destroyEntity(en, "fell into magma", false, false));
        }
        reportmanager.addNewLines();
    }

    /**
     * sink any entities in quicksand in the current hex
     */
    public void doSinkEntity(Entity en) {
        reportmanager.addReport(ReportFactory.createReport(2445, en));
        en.setElevation(en.getElevation() - 1);
        // if this means the entity is below the ground, then bye-bye!
        if (Math.abs(en.getElevation()) > en.getHeight()) {
            reportmanager.addReport(entityManager.destroyEntity(en, "quicksand"));
        }
    }

    /**
     * deal area saturation damage to an individual hex
     *
     * @param coords         The hex being hit
     * @param attackSource   The location the attack came from. For hit table resolution
     * @param damage         Amount of damage to deal to each entity
     * @param ammo           The ammo type being used
     * @param subjectId      Subject for reports
     * @param killer         Who should be credited with kills
     * @param exclude        Entity that should take no damage (used for homing splash)
     * @param flak           Flak, hits flying units only, instead of flyers being immune
     * @param altitude       Absolute altitude for flak attack
     * @param vPhaseReport   The Vector of Reports for the phase report
     * @param asfFlak        Is this flak against ASF?
     * @param alreadyHit     a vector of unit ids for units that have already been hit that
     *                       will be ignored
     * @param variableDamage if true, treat damage as the number of six-sided dice to roll
     */
    public Vector<Integer> artilleryDamageHex(Coords coords, Coords attackSource, int damage, AmmoType ammo,
                                              int subjectId, Entity killer, Entity exclude, boolean flak, int altitude,
                                              Vector<Report> vPhaseReport, boolean asfFlak, Vector<Integer> alreadyHit,
                                              boolean variableDamage) {

        IHex hex = game.getBoard().getHex(coords);
        if (hex == null) {
            return alreadyHit; // not on board.
        }


        // Non-flak artillery damages terrain
        if (!flak) {
            // Report that damage applied to terrain, if there's TF to damage
            IHex h = game.getBoard().getHex(coords);
            if ((h != null) && h.hasTerrainfactor()) {
                Report r = ReportFactory.createReport(3384);
                r.indent(2);
                r.subject = subjectId;
                r.add(coords.getBoardNum());
                r.add(damage * 2);
                reportmanager.addReport(r);
            }
            // Update hex and report any changes
            Vector<Report> newReports = tryClearHex(coords, damage * 2, subjectId);
            for (Report nr : newReports) {
                nr.indent(3);
            }
            vPhaseReport.addAll(newReports);
        }
        
        boolean isFuelAirBomb = ammo != null &&
                (BombType.getBombTypeFromInternalName(ammo.getInternalName()) == BombType.B_FAE_SMALL ||
                BombType.getBombTypeFromInternalName(ammo.getInternalName()) == BombType.B_FAE_LARGE);
        
        Building bldg = game.getBoard().getBuildingAt(coords);
        int bldgAbsorbs = 0;
        if ((bldg != null)
                && !(flak && (((altitude > hex.terrainLevel(Terrains.BLDG_ELEV))
                || (altitude > hex.terrainLevel(Terrains.BRIDGE_ELEV)))))) {
            bldgAbsorbs = bldg.getAbsorbtion(coords);
            if (!((ammo != null) && (ammo.getMunitionType() == AmmoType.M_FLECHETTE))) {
                int actualDamage = damage;
                
                if(isFuelAirBomb) {
                    // light buildings take 1.5x damage from fuel-air bombs
                    if(bldg.getType() == Building.LIGHT) {
                        actualDamage = (int) Math.ceil(actualDamage * 1.5);
                        reportmanager.addReport(ReportFactory.createReport(9991, 1, killer));
                    } 

                    // armored and "castle brian" buildings take .5 damage from fuel-air bombs
                    // but I have no idea how to determine if a building is a castle or a brian
                    // note that being armored and being "light" are not mutually exclusive
                    if(bldg.getArmor(coords) > 0) {
                        actualDamage = (int) Math.floor(actualDamage * .5);
                        reportmanager.addReport(ReportFactory.createReport(9992, 1, killer));
                    }
                }
                
                // damage the building
                Vector<Report> buildingReport = damageBuilding(bldg, actualDamage, coords);
                for (Report report : buildingReport) {
                    report.subject = subjectId;
                }
                vPhaseReport.addAll(buildingReport);
            }
        }

        if (flak && ((altitude <= 0)
                || (altitude <= hex.terrainLevel(Terrains.BLDG_ELEV))
                || (altitude == hex.terrainLevel(Terrains.BRIDGE_ELEV)))) {
            // Flak in this hex would only hit landed units
            return alreadyHit;
        }

        // get units in hex
        for (Entity entity : game.getEntitiesVector(coords)) {  
            // Check: is entity excluded?
            if ((entity == exclude) || alreadyHit.contains(entity.getId())) {
                continue;
            } else {
                alreadyHit.add(entity.getId());
            }
            
            AreaEffectHelper.artilleryDamageEntity(entity, damage, bldg, bldgAbsorbs, variableDamage, asfFlak, flak,
                    altitude, attackSource, ammo, coords, isFuelAirBomb, killer, hex, subjectId, vPhaseReport, this);
        }
        return alreadyHit;
    }

    /**
     * deal area saturation damage to the map, used for artillery
     *
     * @param centre       The hex on which damage is centred
     * @param attackSource The position the attack came from
     * @param ammo         The ammo type doing the damage
     * @param subjectId    Subject for reports
     * @param killer       Who should be credited with kills
     * @param flak         Flak, hits flying units only, instead of flyers being immune
     * @param altitude     Absolute altitude for flak attack
     * @param mineClear    Does this clear mines?
     * @param vPhaseReport The Vector of Reports for the phase report
     * @param asfFlak      Is this flak against ASF?
     * @param attackingBA  How many BA suits are in the squad if this is a BA Tube arty
     *                     attack, -1 otherwise
     */
    public void artilleryDamageArea(Coords centre, Coords attackSource, AmmoType ammo, int subjectId,
                                    Entity killer, boolean flak, int altitude, boolean mineClear,
                                    Vector<Report> vPhaseReport, boolean asfFlak, int attackingBA) {
        DamageFalloff damageFalloff = AreaEffectHelper.calculateDamageFallOff(ammo, attackingBA, mineClear);
        
        int damage = damageFalloff.damage;
        int falloff = damageFalloff.falloff;
        if(damageFalloff.clusterMunitionsFlag) {
            attackSource = centre;
        }
        
        artilleryDamageArea(centre, attackSource, ammo, subjectId, killer, damage, falloff, flak, altitude,
                vPhaseReport, asfFlak);
    }

    /**
     * Deals area-saturation damage to an area of the board. Used for artillery,
     * bombs, or anything else with linear decrease in damage
     *
     * @param centre
     *            The hex on which damage is centred
     * @param attackSource
     *            The position the attack came from
     * @param ammo
     *            The ammo type doing the damage
     * @param subjectId
     *            Subject for reports
     * @param killer
     *            Who should be credited with kills
     * @param damage
     *            Damage at ground zero
     * @param falloff
     *            Reduction in damage for each hex of distance
     * @param flak
     *            Flak, hits flying units only, instead of flyers being immune
     * @param altitude
     *            Absolute altitude for flak attack
     * @param vPhaseReport
     *            The Vector of Reports for the phase report
     * @param asfFlak
     *            Is this flak against ASF?
     */
    public void artilleryDamageArea(Coords centre, Coords attackSource, AmmoType ammo, int subjectId,
                                    Entity killer, int damage, int falloff, boolean flak, int altitude,
                                    Vector<Report> vPhaseReport, boolean asfFlak) {
        Vector<Integer> alreadyHit = new Vector<>();
        for (int ring = 0; damage > 0; ring++, damage -= falloff) {
            List<Coords> hexes = centre.allAtDistance(ring);
            for (Coords c : hexes) {
                alreadyHit = artilleryDamageHex(c, attackSource, damage, ammo, subjectId, killer, null,
                        flak, altitude, vPhaseReport, asfFlak, alreadyHit, false);
            }
            attackSource = centre; // all splash comes from ground zero
        }
    }

    public void deliverBombDamage(Coords centre, int type, int subjectId, Entity killer, Vector<Report> vPhaseReport) {
        int range = (type == BombType.B_CLUSTER) ? 1 : 0;
        int damage = (type == BombType.B_CLUSTER) ? 5 : 10;
        Vector<Integer> alreadyHit = new Vector<>();

        alreadyHit = artilleryDamageHex(centre, centre, damage, null, subjectId, killer, null, false, 0, vPhaseReport, false, alreadyHit, false);
        if (range > 0) {
            List<Coords> hexes = centre.allAtDistance(range);
            for (Coords c : hexes) {
                alreadyHit = artilleryDamageHex(c, centre, damage, null, subjectId, killer, null, false, 0, vPhaseReport, false, alreadyHit, false);
            }
        }
    }

    /**
     * deliver inferno bomb
     *
     * @param coords    the <code>Coords</code> where to deliver
     * @param ae        the attacking <code>entity<code>
     * @param subjectId the <code>int</code> id of the target
     */
    public void deliverBombInferno(Coords coords, Entity ae, int subjectId, Vector<Report> vPhaseReport) {
        IHex h = game.getBoard().getHex(coords);

        // Unless there is a fire in the hex already, start one.
        if (h.terrainLevel(Terrains.FIRE) < Terrains.FIRE_LVL_INFERNO_BOMB) {
            ignite(coords, Terrains.FIRE_LVL_INFERNO_BOMB, vPhaseReport);
        }
        // possibly melt ice and snow
        if (h.containsTerrain(Terrains.ICE) || h.containsTerrain(Terrains.SNOW)) {
            vPhaseReport.addAll(meltIceAndSnow(coords, subjectId));
        }
        for (Entity entity : game.getEntitiesVector(coords)) {
            if (entity.isAirborne() || entity.isAirborneVTOLorWIGE()) {
                continue;
            }
            // TacOps, p. 359 - treat as if hit by 5 inferno missiles
            vPhaseReport.add(ReportFactory.createReport(6696, 3, entity, entity.getDisplayName()));
            if (entity instanceof Tank) {
                Report.addNewline(vPhaseReport);
            }
            Vector<Report> vDamageReport = deliverInfernoMissiles(ae, entity, 5);
            Report.indentAll(vDamageReport, 2);
            vPhaseReport.addAll(vDamageReport);
        }
    }

    /**
     * Resolve any Infantry units which are fortifying hexes
     */
    void resolveFortify() {
        for (Entity ent : game.getEntitiesVector()) {
            if (ent instanceof Infantry) {
                Infantry inf = (Infantry) ent;
                int dig = inf.getDugIn();
                if (dig == Infantry.DUG_IN_WORKING) {
                    reportmanager.addReport(ReportFactory.createReport(5300, inf));
                } else if (dig == Infantry.DUG_IN_FORTIFYING2) {
                    Coords c = inf.getPosition();
                    reportmanager.addReport(ReportFactory.createReport(5305, inf, c.getBoardNum()));
                    // fortification complete - add to map
                    IHex hex = game.getBoard().getHex(c);
                    hex.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.FORTIFIED, 1));
                    gamemanager.sendChangedHex(game, c);
                    // Clear the dig in for any units in same hex, since they
                    // get it for free by fort
                    for (Entity ent2 : game.getEntitiesVector(c)) {
                        if (ent2 instanceof Infantry) {
                            Infantry inf2 = (Infantry) ent;
                            inf2.setDugIn(Infantry.DUG_IN_NONE);
                        }
                    }
                }
            }
        }
    }

    /**
     * Handles a pointblank shot for hidden units, which must request feedback
     * from the client of the player who owns the hidden unit.
     * @return Returns true if a point-blank shot was taken, otherwise false
     */
    public boolean processPointblankShotCFR(Entity hidden, Entity target) {
        gamemanager.sendPointBlankShotCFR(hidden, target);
        boolean firstPacket = true;
        // Keep processing until we get a response
        while (true) {
            synchronized (cfrPacketQueue) {
                try {
                    while (cfrPacketQueue.isEmpty()) {
                        cfrPacketQueue.wait();
                    }
                } catch (InterruptedException e) {
                    return false;
                }
                // Get the packet, if there's something to get
                ServerConnectionListener.ReceivedPacket rp;
                if (cfrPacketQueue.size() > 0) {
                    rp = cfrPacketQueue.poll();
                    int cfrType = rp.packet.getIntValue(0);
                    // Make sure we got the right type of response
                    if (cfrType != Packet.COMMAND_CFR_HIDDEN_PBS) {
                        MegaMek.getLogger().error("Expected a " + "COMMAND_CFR_HIDDEN_PBS CFR packet, "
                                + "received: " + cfrType);
                        continue;
                    }
                    // Check packet came from right ID
                    if (rp.connId != hidden.getOwnerId()) {
                        MegaMek.getLogger().error("Expected a " + "COMMAND_CFR_HIDDEN_PBS CFR packet "
                                + "from player  " + hidden.getOwnerId()
                                + " but instead it came from player " + rp.connId);
                        continue;
                    }
                } else { // If no packets, wait again
                    continue;
                }
                // First packet indicates whether the PBS is taken or declined
                if (firstPacket) {
                    // Check to see if the client declined the PBS
                    if (rp.packet.getObject(1) == null) {
                        return false;
                    } else {
                        firstPacket = false;
                        // Notify other clients, so they can display a message
                        for (IPlayer p : game.getPlayersVector()) {
                            if (p.getId() == hidden.getOwnerId()) {
                                continue;
                            }
                            send(p.getId(), new Packet(
                                    Packet.COMMAND_CLIENT_FEEDBACK_REQUEST,
                                    new Object[] {
                                            Packet.COMMAND_CFR_HIDDEN_PBS,
                                            Entity.NONE, Entity.NONE }));
                        }
                        // Update all clients with the position of the PBS
                        entityManager.entityUpdate(target.getId());
                        continue;
                    }
                }
                // The second packet contains the attacks to process
                @SuppressWarnings("unchecked")
                Vector<EntityAction> attacks = (Vector<EntityAction>) rp.packet.getObject(1);
                // Mark the hidden unit as having taken a PBS
                hidden.setMadePointblankShot(true);
                // Process the Actions
                for (EntityAction ea : attacks) {
                    Entity entity = game.getEntity(ea.getEntityId());
                    if (ea instanceof TorsoTwistAction) {
                        TorsoTwistAction tta = (TorsoTwistAction) ea;
                        if (entity.canChangeSecondaryFacing()) {
                            entity.setSecondaryFacing(tta.getFacing());
                        }
                    } else if (ea instanceof FlipArmsAction) {
                        FlipArmsAction faa = (FlipArmsAction) ea;
                        entity.setArmsFlipped(faa.getIsFlipped());
                    } else if (ea instanceof SearchlightAttackAction) {
                        boolean hexesAdded = ((SearchlightAttackAction) ea).setHexesIlluminated(game);
                        // If we added new hexes, send them to all players.
                        // These are spotlights at night, you know they're
                        // there.
                        if (hexesAdded) {
                            send(PacketFactory.createIlluminatedHexesPacket(game));
                        }
                        SearchlightAttackAction saa = (SearchlightAttackAction) ea;
                        reportmanager.addReport(saa.resolveAction(game));
                    } else if (ea instanceof WeaponAttackAction) {
                        WeaponAttackAction waa = (WeaponAttackAction) ea;
                        Entity ae = game.getEntity(waa.getEntityId());
                        Mounted m = ae.getEquipment(waa.getWeaponId());
                        Weapon w = (Weapon) m.getType();
                        // Track attacks original target, for things like swarm LRMs
                        waa.setOriginalTargetId(waa.getTargetId());
                        waa.setOriginalTargetType(waa.getTargetType());
                        AttackHandler ah = w.fire(waa, game, this);
                        if (ah != null) {
                            ah.setStrafing(waa.isStrafing());
                            ah.setStrafingFirstShot(waa.isStrafingFirstShot());
                            game.addAttack(ah);
                        }
                    }
                }
                // Now handle the attacks
                // Set to the firing phase, so the attacks handle
                IGame.Phase currentPhase = game.getPhase();
                game.setPhase(IGame.Phase.PHASE_FIRING);
                // Handle attacks
                handleAttacks(true);
                // Restore Phase
                game.setPhase(currentPhase);
                return true;
            }
        }
    }

    /**
     * Loops through all the attacks the game has. Checks if they care about
     * current phase, if so, runs them, and removes them if they don't want to
     * stay. TODO : Refactor the new entity announcement out of here.
     */
    public void handleAttacks() {
        handleAttacks(false);
    }

    public void handleAttacks(boolean pointblankShot) {
        Report r;
        int lastAttackerId = -1;
        Vector<AttackHandler> currentAttacks, keptAttacks;
        currentAttacks = game.getAttacksVector();
        keptAttacks = new Vector<>();
        Vector<Report> handleAttackReports = new Vector<>();
        // first, do any TAGs, so homing arty will have TAG
        for (AttackHandler ah : currentAttacks) {
            if (!(ah instanceof TAGHandler)) {
                continue;
            }
            if (ah.cares(game.getPhase())) {
                int aId = ah.getAttackerId();
                if ((aId != lastAttackerId) && !ah.announcedEntityFiring()) {
                    // report who is firing
                    if (pointblankShot) {
                        r = new Report(3102);
                    } else {
                        r = new Report(3100);
                    }
                    r.subject = aId;
                    Entity ae = game.getEntity(aId);
                    r.addDesc(ae);
                    handleAttackReports.addElement(r);
                    ah.setAnnouncedEntityFiring(true);
                    lastAttackerId = aId;
                }
                boolean keep = ah.handle(game.getPhase(), handleAttackReports);
                if (keep) {
                    keptAttacks.add(ah);
                }
                Report.addNewline(handleAttackReports);
            }
        }
        // now resolve everything but TAG
        for (AttackHandler ah : currentAttacks) {
            if (ah instanceof TAGHandler) {
                continue;
            }
            if (ah.cares(game.getPhase())) {
                int aId = ah.getAttackerId();
                if ((aId != lastAttackerId) && !ah.announcedEntityFiring()) {
                    // if this is a new attacker then resolve any
                    // standard-to-cap damage
                    // from previous
                    handleAttackReports.addAll(checkFatalThresholds(aId, lastAttackerId));
                    // report who is firing
                    if (pointblankShot) {
                        r = new Report(3102);
                    } else if (ah.isStrafing()) {
                        r = new Report(3101);
                    } else {
                        r = new Report(3100);
                    }
                    r.subject = aId;
                    Entity ae = game.getEntity(aId);
                    // for arty, attacker may be dead, or fled, so check out-of-
                    // game entities
                    if (ae == null) {
                        ae = game.getOutOfGameEntity(aId);
                    }
                    r.addDesc(ae);
                    handleAttackReports.addElement(r);
                    ah.setAnnouncedEntityFiring(true);
                    lastAttackerId = aId;
                }
                boolean keep = ah.handle(game.getPhase(), handleAttackReports);
                if (keep) {
                    keptAttacks.add(ah);
                }
                Report.addNewline(handleAttackReports);
            } else {
                keptAttacks.add(ah);
            }
        }
        // resolve standard to capital one more time
        handleAttackReports.addAll(checkFatalThresholds(lastAttackerId, lastAttackerId));
        if (handleAttackReports.size() > 0) {
            Report.addNewline(handleAttackReports);
        }
        reportmanager.addReport(handleAttackReports);
        // HACK, but anything else seems to run into weird problems.
        game.setAttacksVector(keptAttacks);
    }

    /**
     * create a <code>SmokeCloud</code> object and add it to the server list
     *
     * @param coords   the location to create the smoke
     * @param level    1=Light 2=Heavy Smoke 3:light LI smoke 4: Heavy LI smoke
     * @param duration How long the smoke will last.
     */
    public void createSmoke(Coords coords, int level, int duration) {
        SmokeCloud cloud = new SmokeCloud(coords, level, duration);
        game.addSmokeCloud(cloud);
        gamemanager.sendSmokeCloudAdded(cloud);
    }

    /**
     * create a <code>SmokeCloud</code> object and add it to the server list
     *
     * @param coords   the location to create the smoke
     * @param level    1=Light 2=Heavy Smoke 3:light LI smoke 4: Heavy LI smoke
     * @param duration duration How long the smoke will last.
     */
    public void createSmoke(ArrayList<Coords> coords, int level, int duration) {
        SmokeCloud cloud = new SmokeCloud(coords, level, duration);
        game.addSmokeCloud(cloud);
        gamemanager.sendSmokeCloudAdded(cloud);
    }

    /**
     * Update the map with a new set of coords.
     *
     * @param newCoords the location to move the smoke to
     */
    public void updateSmoke(SmokeCloud cloud, ArrayList<Coords> newCoords) {
        removeSmokeTerrain(cloud);
        cloud.getCoordsList().clear();
        cloud.getCoordsList().addAll(newCoords);
    }

    /**
     * remove a cloud from the map
     *
     * @param cloud the location to remove the smoke from
     */
    public void removeSmokeTerrain(SmokeCloud cloud) {
        for (Coords coords : cloud.getCoordsList()) {
            IHex nextHex = game.getBoard().getHex(coords);
            if ((nextHex != null) && nextHex.containsTerrain(Terrains.SMOKE)) {
                nextHex.removeTerrain(Terrains.SMOKE);
                gamemanager.sendChangedHex(game, coords);
            }
        }
    }

    public List<SmokeCloud> getSmokeCloudList() {
        return game.getSmokeCloudList();
    }

    /**
     * Check to see if blowing sand caused damage to airborne VTOL/WIGEs
     */
    private Vector<Report> resolveBlowingSandDamage() {
        Vector<Report> vFullReport = new Vector<>();
        vFullReport.add(new Report(5002, Report.PUBLIC));
        int damage_bonus = Math.max(0, game.getPlanetaryConditions().getWindStrength()
                - PlanetaryConditions.WI_MOD_GALE);
        // cycle through each team and damage 1d6 airborne VTOL/WiGE

        List<Team> teams = game.getTeamsVector();
        for (Team team : teams) {
            Vector<Integer> airborne = team.getAirborneVTOL();
            if (airborne.size() > 0) {
                // how many units are affected
                int unitsAffected = Math.min(Compute.d6(), airborne.size());
                while ((unitsAffected > 0) && (airborne.size() > 0)) {
                    int loc = Compute.randomInt(airborne.size());
                    Entity en = game.getEntity(airborne.get(loc));
                    int damage = Math.max(1, Compute.d6() / 2) + damage_bonus;
                    while (damage > 0) {
                        HitData hit = en.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_RANDOM);
                        vFullReport.addAll(damageEntity(en, hit, 1));
                        damage--;
                    }
                    unitsAffected--;
                    airborne.remove(loc);
                }
            }
        }
        Report.addNewline(reportmanager.getvPhaseReport());
        return vFullReport;
    }

    /**
     * let an entity lay a mine
     *
     * @param entity the <code>Entity</code> that should lay a mine
     * @param mineId an <code>int</code> pointing to the mine
     */
    private void layMine(Entity entity, int mineId, Coords coords) {
        Mounted mine = entity.getEquipment(mineId);
        if (!mine.isMissing()) {
            int reportId = 0;
            switch (mine.getMineType()) {
                case Mounted.MINE_CONVENTIONAL:
                    gamemanager.deliverMinefield(game, coords, entity.getOwnerId(), 10, entity.getId(), Minefield.TYPE_CONVENTIONAL);
                    reportId = 3500;
                    break;
                case Mounted.MINE_VIBRABOMB:
                    gamemanager.deliverMinefield(game, coords, entity.getOwnerId(), 10, entity.getId(), Minefield.TYPE_VIBRABOMB);
                    reportId = 3505;
                    break;
                case Mounted.MINE_ACTIVE:
                    gamemanager.deliverMinefield(game, coords, entity.getOwnerId(), 10, entity.getId(), Minefield.TYPE_ACTIVE);
                    reportId = 3510;
                    break;
                case Mounted.MINE_INFERNO:
                    gamemanager.deliverMinefield(game, coords, entity.getOwnerId(), 10, entity.getId(), Minefield.TYPE_INFERNO);
                    reportId = 3515;
                    break;
                // TODO : command-detonated mines
                // case 2:
            }
            mine.setShotsLeft(mine.getUsableShotsLeft() - 1);
            if (mine.getUsableShotsLeft() <= 0) {
                mine.setMissing(true);
            }
            reportmanager.addReport(ReportFactory.createReport(reportId, entity, coords.getBoardNum()));
            entity.setLayingMines(true);
        }
    }

    /**
     * Called during the fire phase to resolve all (and only) weapon attacks
     */
    public void resolveOnlyWeaponAttacks() {
        // loop through received attack actions, getting attack handlers
        for (EntityAction ea : game.getActionsVector()) {
            if (ea instanceof WeaponAttackAction) {
                WeaponAttackAction waa = (WeaponAttackAction) ea;
                Entity ae = game.getEntity(waa.getEntityId());
                Mounted m = ae.getEquipment(waa.getWeaponId());
                Weapon w = (Weapon) m.getType();
                // Track attacks original target, for things like swarm LRMs
                waa.setOriginalTargetId(waa.getTargetId());
                waa.setOriginalTargetType(waa.getTargetType());
                AttackHandler ah = w.fire(waa, game, this);
                if (ah != null) {
                    ah.setStrafing(waa.isStrafing());
                    ah.setStrafingFirstShot(waa.isStrafingFirstShot());
                    game.addAttack(ah);
                }
            }
        }
        // and clear the attacks Vector
        game.resetActions();
    }

    public Set<Coords> getHexUpdateSet() {
        return gamemanager.getHexUpdateSet();
    }

    public void sendChangedHex(Coords coords) {
        gamemanager.sendChangedHex(game, coords);
    }

    public void sendChangedMines(Coords coords) {
        gamemanager.sendChangedMines(game, coords);
    }

    /**
     * Receives a player name, sent from a pending connection, and connects that
     * connection.
     */
    private void receivePlayerName(Packet packet, int connId) {
        final IConnection conn = getPendingConnection(connId);
        String name = (String) packet.getObject(0);
        boolean returning = false;

        // this had better be from a pending connection
        if (conn == null) {
            MegaMek.getLogger().warning("Got a client name from a non-pending connection");
            return;
        }

        // check if they're connecting with the same name as a ghost player
        Vector<IPlayer> players = game.getPlayersVector();
        for (IPlayer player : players) {
            if (player.getName().equals(name)) {
                if (player.isGhost()) {
                    returning = true;
                    player.setGhost(false);
                    // switch id
                    connId = player.getId();
                    conn.setId(connId);
                }
            }
        }

        if (!returning) {
            // Check to avoid duplicate names...
            name = correctDupeName(name);
            sendToPending(connId, new Packet(Packet.COMMAND_SERVER_CORRECT_NAME, name));
        }

        // right, switch the connection into the "active" bin
        connectionListener.removeConnectionPending(conn);
        connectionListener.addConnections(conn);
        connectionListener.addConnectionIds(conn.getId(), conn);

        // add and validate the player info
        if (!returning) {
            game.addNewPlayer(connId, name);
        }

        // if it is not the lounge phase, this player becomes an observer
        IPlayer player = game.getPlayer(connId);
        if ((game.getPhase() != IGame.Phase.PHASE_LOUNGE) && (null != player) && (game.getEntitiesOwnedBy(player) < 1)) {
            player.setObserver(true);
        }

        // send the player the motd
        sendServerChat(connId, motd);

        // send info that the player has connected
        send(PacketFactory.createPlayerConnectPacket(game, connId));

        // tell them their local playerId
        send(connId, new Packet(Packet.COMMAND_LOCAL_PN, connId));

        // send current game info
        sendCurrentInfo(connId);

        try {
            InetAddress[] addresses = InetAddress.getAllByName(InetAddress.getLocalHost().getHostName());
            for (InetAddress addresse : addresses) {
                sendServerChat(connId, "Machine IP is " + addresse.getHostAddress());
            }
        } catch (UnknownHostException e) {
            // oh well.
        }

        // Send the port we're listening on. Only useful for the player
        // on the server machine to check.
        sendServerChat(connId, "Listening on port " + serverSocket.getLocalPort());

        // Get the player *again*, because they may have disconnected.
        player = game.getPlayer(connId);
        if (null != player) {
            String who = player.getName() + " connected from " + getConnection(connId).getInetAddress();
            MegaMek.getLogger().info("s: player #" + connId + ", " + who);
            sendServerChat(who);
        } // Found the player
    }

    private boolean checkPlayerEntityAction(int connId, Entity entity, String packetType) {
        // can this player/entity act right now?
        GameTurn turn = game.getTurn();
        if (game.isPhaseSimultaneous()) {
            turn = game.getTurnForPlayer(connId);
        }
        if ((turn == null) || !turn.isValid(connId, entity, game)) {
            String msg = "error: server got invalid" + packetType + "packet from connection " + connId;
            if (entity != null) {
                msg += ", Entity: " + entity.getShortName();
            } else {
                msg += ", Entity was null!";
            }
            MegaMek.getLogger().error(msg);
            send(connId, PacketFactory.createTurnVectorPacket(game));
            send(connId, PacketFactory.createTurnIndexPacket(game, turn.getPlayerNum()));
            return true;
        }
        return false;
    }

    /**
     * Receives an entity movement packet, and if valid, executes it and ends
     * the current turn.
     */
    private void receiveMovement(Packet packet, int connId) {
        Map<EntityTargetPair, LosEffects> losCache = new HashMap<>();
        Entity entity = game.getEntity(packet.getIntValue(0));
        MovePath md = (MovePath) packet.getObject(1);
        md.setGame(getGame());
        md.setEntity(entity);

        // is this the right phase?
        if (game.getPhase() != IGame.Phase.PHASE_MOVEMENT) {
            MegaMek.getLogger().error("Server got movement packet in wrong phase");
            return;
        }

        // can this player/entity act right now?
        GameTurn turn = game.getTurn();
        if (game.isPhaseSimultaneous()) {
            turn = game.getTurnForPlayer(connId);
        }
        if ((turn == null) || !turn.isValid(connId, entity, game)) {
            String msg = "error: server got invalid movement packet from " + "connection " + connId;
            if (entity != null) {
                msg += ", Entity: " + entity.getShortName();
            } else {
                msg += ", Entity was null!";
            }
            MegaMek.getLogger().error(msg);
            return;
        }

        // looks like mostly everything's okay
        entityManager.processMovement(entity, md, losCache);

        // The attacker may choose to break a chain whip grapple by expending MP
        if ((entity.getGrappled() != Entity.NONE)
                && entity.isChainWhipGrappled() && entity.isGrappleAttacker()
                && (md.getMpUsed() > 0)) {

            Entity te = game.getEntity(entity.getGrappled());
            Report r = ReportFactory.createReport(4316, entity);
            r.addDesc(te);
            reportmanager.addReport(r);

            entity.setGrappled(Entity.NONE, false);
            te.setGrappled(Entity.NONE, false);

            entityManager.entityUpdate(entity.getId());
            entityManager.entityUpdate(te.getId());
        }

        // check the LOS of any telemissiles owned by this entity
        for (int missileId : entity.getTMTracker().getMissiles()) {
            Entity tm = game.getEntity(missileId);
            if ((null != tm) && !tm.isDestroyed() && (tm instanceof TeleMissile)) {
                ((TeleMissile) tm).setOutContact(!LosEffects.calculateLos(game, entity.getId(), tm).canSee());
                entityManager.entityUpdate(tm.getId());
            }
        }

        // Notify the clients about any building updates.
        applyAffectedBldgs();

        // Unit movement may detect hidden units
        detectHiddenUnits();

        // Update visibility indications if using double blind.
        if (game.doBlind()) {
            updateVisibilityIndicator(losCache);
        }

        // This entity's turn is over.
        // N.B. if the entity fell, a *new* turn has already been added.
        endCurrentTurn(entity);
    }

    /**
     * Receive a deployment packet. If valid, execute it and end the current
     * turn.
     */
    private void receiveDeployment(Packet packet, int connId) {
        Entity entity = game.getEntity(packet.getIntValue(0));
        Coords coords = (Coords) packet.getObject(1);
        int nFacing = packet.getIntValue(2);
        int elevation = packet.getIntValue(3);

        // Handle units that deploy loaded with other units.
        int loadedCount = packet.getIntValue(4);
        Vector<Entity> loadVector = new Vector<>();
        for (int i = 0; i < loadedCount; i++) {
            int loadedId = packet.getIntValue(6 + i);
            loadVector.addElement(game.getEntity(loadedId));
        }

        // is this the right phase?
        if (game.getPhase() != IGame.Phase.PHASE_DEPLOYMENT) {
            MegaMek.getLogger().error("Server got deployment packet in wrong phase");
            return;
        }

        final boolean assaultDrop = packet.getBooleanValue(5);
        if(checkPlayerEntityAction(connId, entity, "attack")) {
            return;
        }

        // looks like mostly everything's okay
        processDeployment(entity, coords, nFacing, elevation, loadVector, assaultDrop);

        //Update Aero sensors for a space or atmospheric game
        if (entity.isAero()) {
            IAero a = (IAero) entity;
            a.updateSensorOptions();
        }

        // Update visibility indications if using double blind.
        if (game.doBlind()) {
            updateVisibilityIndicator(null);
        }

        endCurrentTurn(entity);
    }

    /**
     * Used when an Entity that was loaded in another Entity in the Lounge is
     * unloaded during deployment.
     * @param packet the packet to be processed
     * @param connId the id for connection that received the packet.
     */
    private void receiveDeploymentUnload(Packet packet, int connId) {
        Entity loader = game.getEntity(packet.getIntValue(0));
        Entity loaded = game.getEntity(packet.getIntValue(1));

        if (game.getPhase() != Phase.PHASE_DEPLOYMENT) {
            String msg = "server received deployment unload packet outside of deployment phase from connection "
                    + connId;
            if (loader != null) {
                msg += ", Entity: " + loader.getShortName();
            } else {
                msg += ", Entity was null!";
            }
            MegaMek.getLogger().error(msg);
            return;
        }

        if (checkPlayerEntityAction(connId, loader, "deployment")) {
            return;
        }

        // Unload and call entityUpdate
        unloadUnit(loader, loaded, null, 0, 0, false, true);

        // Need to update the loader
        entityManager.entityUpdate(loader.getId());

        // Now need to add a turn for the unloaded unit, to be taken immediately
        // Turn forced to be immediate to avoid messy turn ordering issues
        // (aka, how do we add the turn with individual initiative?)
        game.insertTurnAfter(new GameTurn.SpecificEntityTurn(loaded.getOwnerId(), loaded.getId()),
                game.getTurnIndex() - 1);
        send(PacketFactory.createTurnVectorPacket(game));
    }

    /**
     * receive a packet that contains hexes that are automatically hit by
     * artillery
     *
     * @param packet the packet to be processed
     */
    @SuppressWarnings("unchecked")
    private void receiveArtyAutoHitHexes(Packet packet) {
        PlayerIDandList<Coords> artyAutoHitHexes = (PlayerIDandList<Coords>) packet.getObject(0);

        int playerId = artyAutoHitHexes.getPlayerID();

        // is this the right phase?
        if (game.getPhase() != IGame.Phase.PHASE_SET_ARTYAUTOHITHEXES) {
            MegaMek.getLogger().error("Server got set artyautohithexespacket in wrong phase");
            return;
        }
        game.getPlayer(playerId).setArtyAutoHitHexes(artyAutoHitHexes);

        for (Coords coord : artyAutoHitHexes) {
            game.getBoard().addSpecialHexDisplay(coord,
                    new SpecialHexDisplay(
                            SpecialHexDisplay.Type.ARTILLERY_AUTOHIT,
                            SpecialHexDisplay.NO_ROUND, game.getPlayer(playerId),
                            "Artillery auto hit hex, for " + game.getPlayer(playerId).getName(),
                            SpecialHexDisplay.SHD_OBSCURED_TEAM));
        }
        endCurrentTurn(null);
    }

    /**
     * receive a packet that contains minefields
     *
     * @param packet the packet to be processed
     */
    @SuppressWarnings("unchecked")
    private void receiveDeployMinefields(Packet packet) {
        Vector<Minefield> minefields = (Vector<Minefield>) packet.getObject(0);

        // is this the right phase?
        if (game.getPhase() != IGame.Phase.PHASE_DEPLOY_MINEFIELDS) {
            MegaMek.getLogger().error("Server got deploy minefields packet in wrong phase");
            return;
        }

        // looks like mostly everything's okay
        gamemanager.processDeployMinefields(game, minefields);
        endCurrentTurn(null);
    }

    /**
     * Gets a bunch of entity attacks from the packet. If valid, processes them
     * and ends the current turn.
     */
    @SuppressWarnings("unchecked")
    private void receiveAttack(Packet packet, int connId) {
        Entity entity = game.getEntity(packet.getIntValue(0));
        Vector<EntityAction> vector = (Vector<EntityAction>) packet.getObject(1);

        // is this the right phase?
        if ((game.getPhase() != IGame.Phase.PHASE_FIRING)
                && (game.getPhase() != IGame.Phase.PHASE_PHYSICAL)
                && (game.getPhase() != IGame.Phase.PHASE_TARGETING)
                && (game.getPhase() != IGame.Phase.PHASE_OFFBOARD)) {
            MegaMek.getLogger().error("Server got attack packet in wrong phase");
            return;
        }

        if (checkPlayerEntityAction(connId, entity, "attack")) {
            return;
        }

        // looks like mostly everything's okay
        processAttack(entity, vector);

        // Update visibility indications if using double blind.
        if (game.doBlind()) {
            updateVisibilityIndicator(null);
        }

        endCurrentTurn(entity);
    }

    /**
     * Checks if an entity added by the client is valid and if so, adds it to
     * the list
     *
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     */
    private void receiveEntityAdd(Packet c, int connIndex) {
        @SuppressWarnings("unchecked")
        final List<Entity> entities = (List<Entity>) c.getObject(0);
        List<Integer> entityIds = new ArrayList<>(entities.size());
        // Map client-received to server-given IDs:
        Map<Integer, Integer> idMap = new HashMap<>();

        for (final Entity entity : entities) {

            // Verify the entity's design
            if (Server.entityVerifier == null) {
                Server.entityVerifier = EntityVerifier.getInstance(new MegaMekFile(
                        Configuration.unitsDir(), EntityVerifier.CONFIG_FILENAME).getFile());
            }

            // Create a TestEntity instance for supported unit types
            TestEntity testEntity = null;
            entity.restore();
            if (entity instanceof Mech) {
                testEntity = new TestMech((Mech) entity, entityVerifier.mechOption, null);
            } else if ((entity.getEntityType() == Entity.ETYPE_TANK)
                    && (entity.getEntityType() != Entity.ETYPE_GUN_EMPLACEMENT)) {
                if (entity.isSupportVehicle()) {
                    testEntity = new TestSupportVehicle(entity, entityVerifier.tankOption, null);
                } else {
                    testEntity = new TestTank((Tank) entity, entityVerifier.tankOption, null);
                }
            } else if ((entity.getEntityType() == Entity.ETYPE_AERO)
                    && (entity.getEntityType() != Entity.ETYPE_DROPSHIP)
                    && (entity.getEntityType() != Entity.ETYPE_SMALL_CRAFT)
                    && (entity.getEntityType() != Entity.ETYPE_FIGHTER_SQUADRON)
                    && (entity.getEntityType() != Entity.ETYPE_JUMPSHIP)
                    && (entity.getEntityType() != Entity.ETYPE_SPACE_STATION)) {
                testEntity = new TestAero((Aero) entity, entityVerifier.aeroOption, null);
            } else if (entity instanceof BattleArmor) {
                testEntity = new TestBattleArmor((BattleArmor) entity, entityVerifier.baOption, null);
            }

            if (testEntity != null) {
                StringBuffer sb = new StringBuffer();
                if (testEntity.correctEntity(sb, TechConstants.getGameTechLevel(game, entity.isClan()))) {
                    entity.setDesignValid(true);
                } else {
                    MegaMek.getLogger().error(sb.toString());
                    if (game.getOptions().booleanOption(OptionsConstants.ALLOWED_ALLOW_ILLEGAL_UNITS)) {
                        entity.setDesignValid(false);
                    } else {
                        IPlayer cheater = game.getPlayer(connIndex);
                        sendServerChat("Player " + cheater.getName() + " attempted to add an illegal unit design ("
                                + entity.getShortNameRaw() + "), the unit was rejected.");
                        return;
                    }
                }
            }

            // If we're adding a ProtoMech, calculate it's unit number.
            if (entity instanceof Protomech) {
                // How many ProtoMechs does the player already have?
                int numPlayerProtos = game.getSelectedEntityCount(new EntitySelector() {
                    private final int ownerId = entity.getOwnerId();

                    public boolean accept(Entity entity) {
                        return (entity instanceof Protomech) && (ownerId == entity.getOwnerId());
                    }
                });

                // According to page 54 of the BMRr, ProtoMechs must be
                // deployed in full Points of five, unless circumstances have
                // reduced the number to less than that.
                entity.setUnitNumber((short) (numPlayerProtos / 5));
            } // End added-ProtoMech

            // Only assign an entity ID when the client hasn't.
            if (Entity.NONE == entity.getId()) {
                entity.setId(game.getNextEntityId());
            }

            int clientSideId = entity.getId();
            game.addEntity(entity);

            // Remember which received ID corresponds to which actual ID
            idMap.put(clientSideId, entity.getId());

            relinkC3(entities, entity);

            // Give the unit a spotlight, if it has the spotlight quirk
            entity.setExternalSpotlight(entity.hasExternaSpotlight()
                    || entity.hasQuirk(OptionsConstants.QUIRK_POS_SEARCHLIGHT));
            entityIds.add(entity.getId());

            if (game.getPhase() != Phase.PHASE_LOUNGE) {
                entity.getOwner().increaseInitialBV(entity.calculateBattleValue(false, false));
            }
        }

        // Cycle through the entities again and update any carried units
        // and carrier units to use the correct server-given IDs.
        // Typically necessary when loading a MUL containing transported units.

        // First, deal with units loaded into bays. These are saved for the carrier
        // in MULs and must be restored exactly to recreate the bay loading.
        Set<Entity> transportCorrected = new HashSet<>();
        for (final Entity carrier: entities) {
            for (int carriedId: carrier.getBayLoadedUnitIds()) {
                // First, see if a bay loaded unit can be found and unloaded,
                // because it might be the wrong unit
                Entity carried = game.getEntity(carriedId);
                if (carried == null) {
                    continue;
                }
                int bay = carrier.getBay(carried).getBayNumber();
                carrier.unload(carried);
                // Now, load the correct unit if there is one
                if (idMap.containsKey(carriedId)) {
                    Entity newCarried = game.getEntity(idMap.get(carriedId));
                    if (carrier.canLoad(newCarried, false)) {
                        carrier.load(newCarried, false, bay);
                        newCarried.setTransportId(carrier.getId());
                        // Remember that the carried unit should not be treated again below
                        transportCorrected.add(newCarried);
                    }
                }
            }
        }

        // Now restore the transport settings from the entities' transporter IDs
        // With anything other than bays, MULs only show the carrier, not the carried units
        for (final Entity entity: entities) {
            // Don't correct those that are already corrected
            if (transportCorrected.contains(entity)) {
                continue;
            }
            // Get the original (client side) ID of the transporter
            int origTrsp = entity.getTransportId();
            // Only act if the unit thinks it is transported
            if (origTrsp != Entity.NONE) {
                // If the transporter is among the new units, go on with loading
                if (idMap.containsKey(origTrsp)) {
                    // The wrong transporter doesn't know of anything and does not need an update
                    Entity carrier = game.getEntity(idMap.get(origTrsp));
                    if (carrier.canLoad(entity, false)) {
                        // The correct transporter must be told it's carrying something and
                        // the carried unit must be told where it is embarked
                        carrier.load(entity, false);
                        entity.setTransportId(idMap.get(origTrsp));
                    } else {
                        // This seems to be an invalid carrier; update the entity accordingly
                        entity.setTransportId(Entity.NONE);
                    }
                } else {
                    // this transporter does not exist; update the entity accordingly
                    entity.setTransportId(Entity.NONE);
                }
            }
        }

        // Set the "loaded keepers" which is apparently used for deployment unloading to
        // differentiate between units loaded in the lobby and other carried units
        // When entering a game from the lobby, this list is generated again, but not when
        // the added entities are loaded during a game. When getting loaded units from a MUL,
        // act as if they were loaded in the lobby.
        for (final Entity entity: entities) {
            if (entity.getLoadedUnits().size() > 0) {
                Vector<Integer> v = new Vector<>();
                for (Entity en : entity.getLoadedUnits()) {
                    v.add(en.getId());
                }
                entity.setLoadedKeepers(v);
            }
        }
        send(PacketFactory.createAddEntityPacket(game, entityIds));
    }

    /**
     * adds a squadron to the game
     * @param c the packet to be processed
     */
    @SuppressWarnings("unchecked")
    private void receiveSquadronAdd(Packet c) {
        final FighterSquadron fs = (FighterSquadron) c.getObject(0);
        final Vector<Integer> fighters = (Vector<Integer>) c.getObject(1);
        if (fighters.size() < 1) {
            return;
        }
        // Only assign an entity ID when the client hasn't.
        if (Entity.NONE == fs.getId()) {
            fs.setId(game.getNextEntityId());
        }
        game.addEntity(fs);
        for (int id : fighters) {
            Entity fighter = game.getEntity(id);
            if (null != fighter) {
                fs.load(fighter, false);
                fs.autoSetMaxBombPoints();
                fighter.setTransportId(fs.getId());
                // If this is the lounge, we want to configure bombs
                if (game.getPhase() == Phase.PHASE_LOUNGE) {
                    ((IBomber)fighter).setBombChoices(fs.getBombChoices());
                }
                entityManager.entityUpdate(fighter.getId());
            }
        }
        send(PacketFactory.createAddEntityPacket(game, fs.getId()));
    }

    /**
     * Updates an entity with the info from the client. Only valid to do this
     * during the lounge phase, except for heat sink changing.
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     */
    private void receiveEntityUpdate(Packet c, int connIndex) {
        Entity entity = (Entity) c.getObject(0);
        Entity oldEntity = game.getEntity(entity.getId());
        if ((oldEntity != null) && ((oldEntity.getOwner() == game.getPlayer(connIndex)) || (oldEntity.getOwner().getTeam() == game.getPlayer(connIndex).getTeam()))) {
            game.setEntity(entity.getId(), entity);
            entityManager.entityUpdate(entity.getId());
            // In the chat lounge, notify players of customizing of unit
            if (game.getPhase() == IGame.Phase.PHASE_LOUNGE) {
                StringBuilder message = new StringBuilder();
                if (game.getOptions().booleanOption(OptionsConstants.BASE_REAL_BLIND_DROP)) {
                    message.append("A Unit ");
                    message.append('(').append(entity.getOwner().getName()).append(')');
                } else if (game.getOptions().booleanOption(OptionsConstants.BASE_BLIND_DROP)) {
                    message.append("Unit ");
                    if (!entity.getExternalIdAsString().equals("-1")) {
                        message.append('[')
                                .append(entity.getExternalIdAsString())
                                .append("] ");
                    }
                    message.append(entity.getId()).append(" (")
                            .append(entity.getOwner().getName()).append(')');
                } else {
                    message.append("Unit ");
                    message.append(entity.getDisplayName());
                }
                message.append(" has been customized.");
                sendServerChat(message.toString());
            }
        }
    }

    /**
     * loads an entity into another one. Meant to be called from the chat lounge
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     */
    private void receiveEntityLoad(Packet c, int connIndex) {
        int loadeeId = (Integer) c.getObject(0);
        int loaderId = (Integer) c.getObject(1);
        int bayNumber = (Integer) c.getObject(2);
        Entity loadee = game.getEntity(loadeeId);
        Entity loader = game.getEntity(loaderId);

        if ((loadee != null) && (loader != null)) {
            loadUnit(loader, loadee, bayNumber);
            // In the chat lounge, notify players of customizing of unit
            if (game.getPhase() == IGame.Phase.PHASE_LOUNGE) {
                // Set this so units can be unloaded in the first movement phase
                loadee.setLoadedThisTurn(false);
            }
        }
    }

    private void receiveInitiativeRerollRequest(int connIndex) {
        IPlayer player = game.getPlayer(connIndex);
        if (IGame.Phase.PHASE_INITIATIVE_REPORT != game.getPhase()) {
            StringBuilder message = new StringBuilder();
            if (null == player) {
                message.append("Player #").append(connIndex);
            } else {
                message.append(player.getName());
            }
            message.append(" is not allowed to ask for a reroll at this time.");
            MegaMek.getLogger().error(message.toString());
            sendServerChat(message.toString());
            return;
        }
        if (game.hasTacticalGenius(player)) {
            game.addInitiativeRerollRequest(game.getTeamForPlayer(player));
        }
        if (null != player) {
            player.setDone(true);
        }
        checkReady();
    }

    /**
     * Sets game options, providing that the player has specified the password
     * correctly.
     *
     * @return true if any options have been successfully changed.
     */
    private boolean receiveGameOptions(Packet packet, int connId) {
        IPlayer player = game.getPlayer(connId);
        // Check player
        if (null == player) {
            MegaMek.getLogger().error("Server does not recognize player at connection " + connId);
            return false;
        }

        // check password
        if ((password != null) && (password.length() > 0) && !password.equals(packet.getObject(0))) {
            sendServerChat(connId, "The password you specified to change game options is incorrect.");
            return false;
        }

        if (game.getPhase().isDuringOrAfter(Phase.PHASE_DEPLOYMENT)) {
            return false;
        }

        int changed = 0;
        for (Enumeration<?> i = ((Vector<?>) packet.getObject(1)).elements(); i.hasMoreElements(); ) {
            IBasicOption option = (IBasicOption) i.nextElement();
            IOption originalOption = game.getOptions().getOption(option.getName());

            if (originalOption != null) {
                String message = "Player " + player.getName() + " changed option \""
                        + originalOption.getDisplayableName() + "\" to " + option.getValue().toString() + '.';
                sendServerChat(message);
                originalOption.setValue(option.getValue());
                changed++;
            }
        }

        // Set proper RNG
        Compute.setRNG(game.getOptions().intOption(OptionsConstants.BASE_RNG_TYPE));

        if (changed > 0) {
            for (Entity en : game.getEntitiesVector()) {
                en.setGameOptions();
            }
            entityManager.entityAllUpdate();
            return true;
        }
        return false;
    }

    /**
     * Performs the additional processing of the received options after the the
     * <code>receiveGameOptions<code> done its job; should be called after
     * <code>receiveGameOptions<code> only if the <code>receiveGameOptions<code>
     * returned <code>true</code>
     *
     * @param packet the packet to be processed
     * @param connId the id for connection that received the packet.
     */
    private void receiveGameOptionsAux(Packet packet, int connId) {
        for (Enumeration<?> i = ((Vector<?>) packet.getObject(1)).elements(); i.hasMoreElements(); ) {
            IBasicOption option = (IBasicOption) i.nextElement();
            IOption originalOption = game.getOptions().getOption(option.getName());
            if (originalOption != null) {
                if ("maps_include_subdir".equals(originalOption.getName())) {
                    mapSettings.setBoardsAvailableVector(BoardUtilities.scanForBoards(new BoardDimensions(
                            mapSettings.getBoardWidth(), mapSettings.getBoardHeight())));
                    mapSettings.removeUnavailable();
                    mapSettings.setNullBoards(MapSettings.DEFAULT_BOARD);
                    send(PacketFactory.createMapSettingsPacket(mapSettings));
                }
            }
        }
    }

    /**
     * Receives an packet to unload entity is stranded on immobile transports,
     * and queue all valid requests for execution. If all players that have
     * stranded entities have answered, executes the pending requests and end
     * the current turn.
     */
    private void receiveUnloadStranded(Packet packet, int connId) {
        GameTurn.UnloadStrandedTurn turn;
        final IPlayer player = game.getPlayer(connId);
        int[] entityIds = (int[]) packet.getObject(0);
        IPlayer other;
        UnloadStrandedAction action;
        Entity entity;

        // Is this the right phase?
        if (game.getPhase() != IGame.Phase.PHASE_MOVEMENT) {
            MegaMek.getLogger().error("Server got unload stranded packet in wrong phase");
            return;
        }

        // Are we in an "unload stranded entities" turn?
        if (game.getTurn() instanceof GameTurn.UnloadStrandedTurn) {
            turn = (GameTurn.UnloadStrandedTurn) game.getTurn();
        } else {
            MegaMek.getLogger().error("Server got unload stranded packet out of sequence");
            sendServerChat(player.getName() + " should not be sending 'unload stranded entity' packets at this time.");
            return;
        }

        // Can this player act right now?
        if (!turn.isValid(connId, game)) {
            MegaMek.getLogger().error("Server got unload stranded packet from invalid player");
            sendServerChat(player.getName() + " should not be sending 'unload stranded entity' packets.");
            return;
        }

        // Did the player already send an 'unload' request?
        // N.B. we're also building the list of players who
        // have declared their "unload stranded" actions.
        Vector<IPlayer> declared = new Vector<>();
        List<EntityAction> actions = game.getActionsVector();
        for (EntityAction action1 : actions) {
            action = (UnloadStrandedAction) action1;
            if (action.getPlayerId() == connId) {
                MegaMek.getLogger().error("Server got multiple unload stranded packets from player");
                sendServerChat(player.getName() + " should not send multiple 'unload stranded entity' packets.");
                return;
            }
            // This player is not from the current connection.
            // Record this player to determine if this turn is done.
            other = game.getPlayer(action.getPlayerId());
            if (!declared.contains(other)) {
                declared.addElement(other);
            }
        } // Handle the next "unload stranded" action.

        // Make sure the player selected at least *one* valid entity ID.
        boolean foundValid = false;
        for (int index = 0; (null != entityIds) && (index < entityIds.length); index++) {
            entity = game.getEntity(entityIds[index]);
            if (!game.getTurn().isValid(connId, entity, game)) {
                MegaMek.getLogger().error("Server got unload stranded packet for invalid entity");
                StringBuilder message = new StringBuilder();
                message.append(player.getName()).append(" can not unload stranded entity ");
                if (null == entity) {
                    message.append('#').append(entityIds[index]);
                } else {
                    message.append(entity.getDisplayName());
                }
                message.append(" at this time.");
                sendServerChat(message.toString());
            } else {
                foundValid = true;
                game.addAction(new UnloadStrandedAction(connId, entityIds[index]));
            }
        }

        // Did the player choose not to unload any valid stranded entity?
        if (!foundValid) {
            game.addAction(new UnloadStrandedAction(connId, Entity.NONE));
        }

        // Either way, the connection's player has now declared.
        declared.addElement(player);

        // Are all players who are unloading entities done? Walk
        // through the turn's stranded entities, and look to see
        // if their player has finished their turn.
        entityIds = turn.getEntityIds();
        for (int entityId : entityIds) {
            entity = game.getEntity(entityId);
            other = entity.getOwner();
            if (!declared.contains(other)) {
                // At least one player still needs to declare.
                return;
            }
        }

        // All players have declared whether they're unloading stranded units.
        // Walk the list of pending actions and unload the entities.
        for (EntityAction pending : game.getActionsVector()) {
            action = (UnloadStrandedAction) pending;

            // Some players don't want to unload any stranded units.
            if (Entity.NONE != action.getEntityId()) {
                entity = game.getEntity(action.getEntityId());
                if (null == entity) {
                    // After all this, we couldn't find the entity!!!
                    MegaMek.getLogger().error("Server could not find stranded entity #"
                            + action.getEntityId() + " to unload!!!");
                } else {
                    // Unload the entity. Get the unit's transporter.
                    Entity transporter = game.getEntity(entity.getTransportId());
                    unloadUnit(transporter, entity, transporter.getPosition(), transporter.getFacing(),
                            transporter.getElevation());
                }
            }
        } // Handle the next pending unload action

        // Clear the list of pending units and move to the next turn.
        game.resetActions();
        changeToNextTurn(connId);
    }
}