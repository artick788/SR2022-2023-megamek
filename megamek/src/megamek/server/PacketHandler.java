package megamek.server;

import megamek.MegaMek;
import megamek.common.*;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.net.Packet;
import megamek.common.options.OptionsConstants;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class PacketHandler {
    private IGame game;

    PacketHandler(IGame game) {
        this.game = game;
    }

    public void send(Packet p) {
        Server.getServerInstance().send(p);
    }

    public void send(int connId, Packet p) {
        Server.getServerInstance().send(connId, p);
    }

    public void sendServerChat(String message) {
        Server.getServerInstance().sendServerChat(message);
    }

    public void sendServerChat(int connId, String message) {
        Server.getServerInstance().sendServerChat(connId, message);
    }

    public void sendChat(String origin, String message) {
        Server.getServerInstance().sendChat(origin, message);
    }

    public void sendChat(int connId, String origin, String message) {
        Server.getServerInstance().sendChat(connId, origin, message);
    }

    ////////////////
    /**
     *
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     */
    public void receiveCustomInit(Packet c, int connIndex) {
        // In the chat lounge, notify players of customizing of unit
        if (game.getPhase() == IGame.Phase.PHASE_LOUNGE) {
            IPlayer p = (IPlayer) c.getObject(0);
            sendServerChat("" + p.getName() + " has customized initiative.");
        }
    }

    public void receivePlayerVersion(Packet packet, int connId) {
        String version = (String) packet.getObject(0);
        String clientChecksum = (String) packet.getObject(1);
        String serverChecksum = MegaMek.getMegaMekSHA256();
        StringBuilder buf = new StringBuilder();
        boolean needs = false;
        if (!version.equals(MegaMek.VERSION)) {
            buf.append("Client/Server version mismatch. Server reports: ").append(MegaMek.VERSION)
                    .append(", Client reports: ").append(version);
            MegaMek.getLogger().error("Client/Server Version Mismatch -- Client: "
                    + version + " Server: " + MegaMek.VERSION);
            needs = true;
        }
        // print a message indicating client doesn't have jar file
        if (clientChecksum == null) {
            if (!version.equals(MegaMek.VERSION)) {
                buf.append(System.lineSeparator()).append(System.lineSeparator());
            }
            buf.append("Client Checksum is null. Client may not have a jar file");
            MegaMek.getLogger().info("Client does not have a jar file");
            needs = true;
            // print message indicating server doesn't have jar file
        } else if (serverChecksum == null) {
            if (!version.equals(MegaMek.VERSION)) {
                buf.append(System.lineSeparator()).append(System.lineSeparator());
            }
            buf.append("Server Checksum is null. Server may not have a jar file");
            MegaMek.getLogger().info("Server does not have a jar file");
            needs = true;
            // print message indicating a client/server checksum mismatch
        } else if (!clientChecksum.equals(serverChecksum)) {
            if (!version.equals(MegaMek.VERSION)) {
                buf.append(System.lineSeparator()).append(System.lineSeparator());
            }
            buf.append("Client/Server checksum mismatch. Server reports: ").append(serverChecksum)
                    .append(", Client reports: ").append(clientChecksum);
            MegaMek.getLogger().error("Client/Server Checksum Mismatch -- Client: " + clientChecksum + " Server: " + serverChecksum);
            needs = true;
        }

        // Now, if we need to, send message!
        if (needs) {
            IPlayer player = game.getPlayer(connId);
            if (null != player) {
                sendServerChat("For " + player.getName() + " Server reports:" + System.lineSeparator() + buf.toString());
            }
        } else {
            MegaMek.getLogger().info("SUCCESS: Client/Server Version (" + version + ") and Checksum ("
                    + clientChecksum + ") matched");
        }
    }

    /**
     * Hand over a turn to the next player. This is only possible if you haven't
     * yet started your turn (i.e. not yet moved anything like infantry where
     * you have to move multiple units)
     *
     * @param connectionId - connection id of the player sending the packet
     */
    public void receiveForwardIni(int connectionId) {
        // this is the player sending the packet
        IPlayer current = game.getPlayer(connectionId);

        if (game.getTurn().getPlayerNum() != current.getId()) {
            // this player is not the current player, so just ignore this
            // command!
            return;
        }
        // if individual initiative is active we cannot forward our initiative
        // ever!
        if (game.getOptions().booleanOption(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE)) {
            return;
        }

        // if the player isn't on a team, there is no next team by definition. Skip the rest.
        Team currentPlayerTeam = game.getTeamForPlayer(current);
        if (currentPlayerTeam == null) {
            return;
        }

        // get the next player from the team this player is on.
        IPlayer next = currentPlayerTeam.getNextValidPlayer(current, game);

        while (!next.equals(current)) {
            // if the chosen player is a valid player, we change the turn order and
            // inform the clients.
            if (game.getEntitiesOwnedBy(next) != 0 && game.getTurnForPlayer(next.getId()) != null) {
                int currentTurnIndex = game.getTurnIndex();
                // now look for the next occurrence of player next in the turn order
                List<GameTurn> turns = game.getTurnVector();
                GameTurn turn = game.getTurn();
                // not entirely necessary. As we will also check this for the
                // activity of the button but to be sure do it on the server too.
                boolean isGeneralMoveTurn = !(turn instanceof GameTurn.SpecificEntityTurn)
                        && !(turn instanceof GameTurn.UnitNumberTurn)
                        && !(turn instanceof GameTurn.UnloadStrandedTurn);
                if (!isGeneralMoveTurn) {
                    // if this is not a general turn the player cannot forward his turn.
                    return;
                }

                // if it is an EntityClassTurn we have to check make sure, that the
                // turn it is exchanged with is the same kind of turn!
                // in fact this requires an access function to the mask of an
                // EntityClassTurn.
                boolean isEntityClassTurn = (turn instanceof GameTurn.EntityClassTurn);
                int classMask = 0;
                if (isEntityClassTurn) {
                    classMask = ((GameTurn.EntityClassTurn) turn).getTurnCode();
                }

                boolean switched = false;
                int nextTurnId = 0;
                for (int i = currentTurnIndex; i < turns.size(); i++) {
                    // if we find a turn for the specific player, swap the current
                    // player with the player noted there
                    // and stop
                    if (turns.get(i).isValid(next.getId(), game)) {
                        nextTurnId = i;
                        if (isEntityClassTurn && !(turns.get(i) instanceof GameTurn.EntityClassTurn)) {
                            continue;
                        }
                        if (isEntityClassTurn && ((GameTurn.EntityClassTurn) turns.get(i)).getTurnCode() != classMask) {
                            continue;
                        }
                        switched = true;
                        break;
                    }
                }

                // update turn order
                if (switched) {
                    game.swapTurnOrder(currentTurnIndex, nextTurnId);
                    // update the turn packages for all players.
                    send(PacketFactory.createTurnVectorPacket(game));
                    send(PacketFactory.createTurnIndexPacket(game, connectionId));
                    return;
                }
                // if nothing changed return without doing anything
            }

            next = currentPlayerTeam.getNextValidPlayer(next, game);
        }
    }
}
