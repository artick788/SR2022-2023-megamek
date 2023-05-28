package megamek.server;

import megamek.MegaMek;
import megamek.common.IGame;
import megamek.common.IPlayer;
import megamek.common.net.*;

import java.util.Hashtable;
import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;


public class ServerConnectionListener extends ConnectionListenerAdapter {
    public static class ReceivedPacket {
        public int connId;
        public Packet packet;

        ReceivedPacket(int cid, Packet p) {
            packet = p;
            connId = cid;
        }
    }

    private IGame game;

    public Object getServerLock() {
        return serverLock;
    }

    /**
     * Used to ensure only one thread at a time is accessing this particular
     * instance of the server.
     */
    private final Object serverLock = new Object();
    private int connectionCounter;
    private final Vector<IConnection> connections = new Vector<>(4);
    private final Vector<IConnection> connectionsPending = new Vector<>(4);
    private final Hashtable<Integer, IConnection> connectionIds = new Hashtable<>();
    private final Hashtable<Integer, ConnectionHandler> connectionHandlers = new Hashtable<>();
    private final ConcurrentLinkedQueue<ReceivedPacket> packetQueue = new ConcurrentLinkedQueue<>();
    /**
     * Special packet queue for client feedback requests.
     */
    //private final ConcurrentLinkedQueue<ReceivedPacket> cfrPacketQueue = new ConcurrentLinkedQueue<>();

    public ConcurrentLinkedQueue<ReceivedPacket> getPacketQueue() {
        return packetQueue;
    }

    public ReceivedPacket pollPacketQueue() {
        return packetQueue.poll();
    }

    public void addToPacketQueue(ReceivedPacket packet) {
        synchronized (packetQueue) {
            packetQueue.add(packet);
            packetQueue.notifyAll();
        }
    }

    public void waitPacketQueue() {
        try {
            synchronized (packetQueue) {
                packetQueue.wait();
            }
        } catch (InterruptedException e) {
            // If we are interrupted, just keep going, generally
            // this happens after we are signalled to stop.
        }
    }

    public Vector<IConnection> getConnections() {
        return connections;
    }

    public void addConnections(IConnection conn) {
        connections.add(conn);
    }

    public void removeConnection(IConnection conn) {
        connections.removeElement(conn);
    }

    public void removeAllConnections() {
        connections.removeAllElements();
    }

    public Vector<IConnection> getConnectionsPending() {
        return connectionsPending;
    }

    public void addConnectionsPending(IConnection conn) {
        connectionsPending.add(conn);
    }

    public void removeConnectionPending(IConnection conn) {
        connectionsPending.removeElement(conn);
    }

    public void removeAllConnectionsPending() {
        connectionsPending.removeAllElements();
    }

    public IConnection getConnectionIds(int id) {
        return connectionIds.get(id);
    }

    public void addConnectionIds(int id, IConnection conn) {
        connectionIds.put(id, conn);
    }

    public void removeConnectionIds(int id) {
        connectionIds.remove(id);
    }

    public void removeAllConnectionIds() {
        connectionIds.clear();
    }

    public void addConnectionHandler(int id, ConnectionHandler conn) {
        connectionHandlers.put(id, conn);
    }

    public ConnectionHandler getConnectionHandler(int id) {
        return connectionHandlers.get(id);
    }

    public void removeConnectionHandler(int id) {
        connectionHandlers.remove(id);
    }

    ServerConnectionListener(IGame game) {
        this.game = game;
    }

    @Override
    public void disconnected(DisconnectedEvent e) {
        synchronized (serverLock) {
            IConnection conn = e.getConnection();

            // write something in the log
            MegaMek.getLogger().info("s: connection " + conn.getId() + " disconnected");

            connections.removeElement(conn);
            connectionsPending.removeElement(conn);
            connectionIds.remove(conn.getId());
            ConnectionHandler ch = connectionHandlers.get(conn.getId());
            if (ch != null) {
                ch.signalStop();
                connectionHandlers.remove(conn.getId());
            }

            // if there's a player for this connection, remove it too
            IPlayer player = game.getPlayer(conn.getId());
            if (null != player) {
                Server.getServerInstance().disconnected(player);
            }
        }
    }

    @Override
    public void packetReceived(PacketReceivedEvent e) {
        ReceivedPacket rp = new ReceivedPacket(e.getConnection().getId(), e.getPacket());
        int cmd = e.getPacket().getCommand();
        // Handled CFR packets specially
        if (cmd == Packet.COMMAND_CLIENT_FEEDBACK_REQUEST) {
            // TODO (Sam): Quick fix, can be better
            Server.getServerInstance().addTocfrPacketQueue(rp);
            /*
            synchronized (cfrPacketQueue) {
                cfrPacketQueue.add(rp);
                cfrPacketQueue.notifyAll();
            }

             */
            // Some packets should be handled immediately
        } else if ((cmd == Packet.COMMAND_CLOSE_CONNECTION)
                || (cmd == Packet.COMMAND_CLIENT_NAME)
                || (cmd == Packet.COMMAND_CLIENT_VERSIONS)
                || (cmd == Packet.COMMAND_CHAT)) {
            Server.getServerInstance().handle(rp.connId, rp.packet);
        } else {
            synchronized (packetQueue) {
                packetQueue.add(rp);
                packetQueue.notifyAll();
            }
        }
    }

    IConnection getPendingConnection(int connId) {
        for (IConnection conn : getConnectionsPending()) {
            if (conn.getId() == connId) {
                return conn;
            }
        }
        return null;
    }

    /**
     * Returns a free connection id.
     */
    public int getFreeConnectionId() {
        while ((getPendingConnection(connectionCounter) != null)
                || (getConnectionIds(connectionCounter) != null)
                || (game.getPlayer(connectionCounter) != null)) {
            connectionCounter++;
        }
        return connectionCounter;
    }
}
