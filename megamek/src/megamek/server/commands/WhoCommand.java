/*
 * MegaMek - Copyright (C) 2000-2002 Ben Mazur (bmazur@sev.org)
 *
 *  This program is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License as published by the Free
 *  Software Foundation; either version 2 of the License, or (at your option)
 *  any later version.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 *  for more details.
 */

/*
 * WhoCommand.java
 *
 * Created on March 30, 2002, 7:35 PM
 */

package megamek.server.commands;

import java.util.Vector;

import megamek.common.net.IConnection;
import megamek.server.Server;

/**
 * Lists all the players connected and some info about them.
 * 
 * @author Ben
 * @version
 */
public class WhoCommand extends ServerCommand {

    /** Creates new WhoCommand */
    public WhoCommand(Server server) {
        super(server, "who",
                "Lists all of the players connected to the server.");
    }

    @Override
    public void run(int connId, String[] args) {
        server.sendServerChat(connId, "Listing all connections...");
        server.sendServerChat(connId, "[id#] : [name], [address], [pending], [bytes sent], [bytes received]");
        Vector<IConnection> connections = server.getConnections();
        for (IConnection conn : connections) {
            String cb = conn.getId() + " : " + server.getPlayer(conn.getId()).getName() + ", " +
                    conn.getInetAddress() + ", " + conn.hasPending() + ", " + conn.bytesSent() +
                    ", " + conn.bytesReceived();
            server.sendServerChat(connId, cb);
        }
        server.sendServerChat(connId, "end list");
    }
}