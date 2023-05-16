package megamek.server;

import megamek.MegaMek;
import megamek.client.ui.swing.util.PlayerColour;
import megamek.common.*;
import megamek.common.actions.ArtilleryAttackAction;
import megamek.common.actions.EntityAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.net.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.util.BoardUtilities;
import megamek.common.util.StringUtil;
import megamek.common.util.fileUtils.MegaMekFile;
import megamek.common.weapons.AttackHandler;
import megamek.common.weapons.WeaponHandler;

import java.io.File;
import java.util.*;

public class GameManager {
    /**
     * Keeps track of what team a player requested to join.
     */
    private int requestedTeam = IPlayer.TEAM_NONE;

    /**
     * Keeps track of what player made a request to change teams.
     */
    private IPlayer playerChangingTeam = null;

    /**
     * Flag that is set to true when all players have voted to allow another
     * player to change teams.
     */
    private boolean changePlayersTeam = false;

    /**
     * Stores a set of <code>Coords</code> that have changed during this phase.
     */
    private Set<Coords> hexUpdateSet = new LinkedHashSet<>();

    public int getRequestedTeam() {
        return requestedTeam;
    }

    public void setRequestedTeam(int requestedTeam) {
        this.requestedTeam = requestedTeam;
    }

    public IPlayer getPlayerChangingTeam() {
        return playerChangingTeam;
    }

    public void setPlayerChangingTeam(IPlayer playerChangingTeam) {
        this.playerChangingTeam = playerChangingTeam;
    }

    public boolean isChangePlayersTeam() {
        return changePlayersTeam;
    }

    public void setChangePlayersTeam(boolean changePlayersTeam) {
        this.changePlayersTeam = changePlayersTeam;
    }

    public Set<Coords> getHexUpdateSet() {
        return hexUpdateSet;
    }

    public void setHexUpdateSet(Set<Coords> hexUpdateSet) {
        this.hexUpdateSet = hexUpdateSet;
    }

    /**
     * Checks to see if Flawed Cooling is triggered and generates a report of
     * the result.
     *
     * @return
     */
    public Vector<Report> doFlawedCoolingCheck(String reason, Entity entity) {
        Vector<Report> out = new Vector<>();
        Report r = new Report(9800);
        r.addDesc(entity);
        r.add(reason);
        int roll = Compute.d6(2);
        r.add(roll);
        out.add(r);
        if (roll >= 10) {
            Report s = new Report(9805);
            ((Mech) entity).setCoolingFlawActive(true);
            out.add(s);
        }

        return out;
    }

    public void sendSmokeCloudAdded(SmokeCloud cloud) {
        final Object[] data = new Object[1];
        data[0] = cloud;
        send(new Packet(Packet.COMMAND_ADD_SMOKE_CLOUD, data));
    }

    public void sendVisibilityIndicator(Entity e) {
        final Object[] data = new Object[6];
        data[0] = e.getId();
        data[1] = e.isEverSeenByEnemy();
        data[2] = e.isVisibleToEnemy();
        data[3] = e.isDetectedByEnemy();
        data[4] = e.getWhoCanSee();
        data[5] = e.getWhoCanDetect();
        send(new Packet(Packet.COMMAND_ENTITY_VISIBILITY_INDICATOR, data));
    }

    /**
     * Recursive method to add an <code>Entity</code> and all of its transported
     * units to the list of units visible to a particular player. It is
     * important to ensure that if a unit is in the list of visible units then
     * all of its transported units (and their transported units, and so on) are
     * also considered visible, otherwise it can lead to issues. This method
     * also ensures that no duplicate Entities are added.
     *
     * @param vCanSee A collection of units that can be see
     * @param e       An Entity that is seen and needs to be added to the collection
     *                of seen entities. All of
     */
    public void addVisibleEntity(Vector<Entity> vCanSee, Entity e) {
        if (!vCanSee.contains(e)) {
            vCanSee.add(e);
        }
        for (Entity transported : e.getLoadedUnits()) {
            addVisibleEntity(vCanSee, transported);
        }
    }

    /**
     * add fire to a hex
     *
     * @param c         - the <code>Coords</code> of the hex to be set on fire
     * @param fireLevel - The level of fire, see Terrains
     */
    public void ignite(IGame game, Coords c, int fireLevel, Vector<Report> vReport) {
        // you can't start fires in some planetary conditions!
        if (null != game.getPlanetaryConditions().cannotStartFire()) {
            if (null != vReport) {
                Report r = new Report(3007);
                r.indent(2);
                r.add(game.getPlanetaryConditions().cannotStartFire());
                r.type = Report.PUBLIC;
                vReport.add(r);
            }
            return;
        }

        if (!game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_START_FIRE)) {
            if (null != vReport) {
                Report r = new Report(3008);
                r.indent(2);
                r.type = Report.PUBLIC;
                vReport.add(r);
            }
            return;
        }

        IHex hex = game.getBoard().getHex(c);
        if (null == hex) {
            return;
        }

        Report r = new Report(3005);
        r.indent(2);
        r.add(c.getBoardNum());
        r.type = Report.PUBLIC;

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
        sendChangedHex(game, c);
    }



    /**
     * Reveals a minefield for all players on a team.
     *
     * @param team The <code>team</code> whose minefield should be revealed
     * @param mf   The <code>Minefield</code> to be revealed
     */
    public void revealMinefield(Team team, Minefield mf) {
        Enumeration<IPlayer> players = team.getPlayers();
        while (players.hasMoreElements()) {
            IPlayer player = players.nextElement();
            if (!player.containsMinefield(mf)) {
                player.addMinefield(mf);
                send(player.getId(), new Packet(Packet.COMMAND_REVEAL_MINEFIELD, mf));
            }
        }
    }

    /**
     * Removes the minefield from a player.
     *
     * @param player The <code>Player</code> whose minefield should be removed
     * @param mf     The <code>Minefield</code> to be removed
     */
    public void removeMinefield(IPlayer player, Minefield mf) {
        if (player.containsMinefield(mf)) {
            player.removeMinefield(mf);
            send(player.getId(), new Packet(Packet.COMMAND_REMOVE_MINEFIELD, mf));
        }
    }
    //////////////////////////////
    // TODO (Sam): Send (set somewhere else??)
    //////////////////////////////
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

    /**
     * Sends notification to clients that the specified hex has changed.
     */
    public void sendChangedHex(IGame game, Coords coords) {
        send(PacketFactory.createHexChangePacket(coords, game.getBoard().getHex(coords)));
    }

    /**
     * Sends notification to clients that the specified hex has changed.
     */
    public void sendChangedHexes(IGame game, Set<Coords> coords) {
        Set<IHex> hexes = new LinkedHashSet<>();
        for (Coords coord : coords) {
            hexes.add(game.getBoard().getHex(coord));
        }
        send(PacketFactory.createHexesChangePacket(coords, hexes));
    }

    /**
     * Sends notification to clients that the specified hex has changed.
     */
    public void sendChangedMines(IGame game, Coords coords) {
        send(PacketFactory.createMineChangePacket(game, coords));
    }

    /**
     * Sends out a notification message indicating that a ghost player may be
     * skipped.
     *
     * @param ghost - the <code>Player</code> who is ghosted. This value must not
     *              be <code>null</code>.
     */
    public void sendGhostSkipMessage(IPlayer ghost) {
        String message = "Player '" + ghost.getName() +
                "' is disconnected.  You may skip his/her current turn with the /skip command.";
        sendServerChat(message);
    }

    /**
     * Sends out a notification message indicating that the current turn is an
     * error and should be skipped.
     *
     * @param skip - the <code>Player</code> who is to be skipped. This value
     *             must not be <code>null</code>.
     */
    public void sendTurnErrorSkipMessage(IPlayer skip) {
        String message = "Player '" + skip.getName() +
                "' has no units to move.  You should skip his/her/your current turn with the /skip command. " +
                "You may want to report this error at https://github.com/MegaMek/megamek/issues";
        sendServerChat(message);
    }

    /**
     * send a packet to the connection tells it load a locally saved game
     *
     * @param connId The <code>int</code> connection id to send to
     * @param sFile  The <code>String</code> filename to use
     */
    public void sendLoadGame(int connId, String sFile) {
        String sFinalFile = sFile;
        if (!sFinalFile.endsWith(".sav") && !sFinalFile.endsWith(".sav.gz")) {
            sFinalFile = sFile + ".sav";
        }
        if (!sFinalFile.endsWith(".gz")) {
            sFinalFile = sFinalFile + ".gz";
        }
        send(connId, new Packet(Packet.COMMAND_LOAD_SAVEGAME, new Object[]{sFinalFile}));
    }

    public void sendDominoEffectCFR(Entity e) {
        send(e.getOwnerId(), new Packet(Packet.COMMAND_CLIENT_FEEDBACK_REQUEST,
                new Object[] { Packet.COMMAND_CFR_DOMINO_EFFECT, e.getId() }));
    }

    public void sendAMSAssignCFR(Entity e, Mounted ams, List<WeaponAttackAction> waas) {
        send(e.getOwnerId(),
                new Packet(Packet.COMMAND_CLIENT_FEEDBACK_REQUEST,
                        new Object[] { Packet.COMMAND_CFR_AMS_ASSIGN,
                                e.getId(), e.getEquipmentNum(ams), waas }));
    }

    public void sendAPDSAssignCFR(Entity e, List<Integer> apdsDists,
                                   List<WeaponAttackAction> waas) {
        send(e.getOwnerId(), new Packet(Packet.COMMAND_CLIENT_FEEDBACK_REQUEST,
                new Object[] { Packet.COMMAND_CFR_APDS_ASSIGN, e.getId(),
                        apdsDists, waas }));
    }

    public void sendPointBlankShotCFR(Entity hidden, Entity target) {
        // Send attacker/target IDs to PBS Client
        send(hidden.getOwnerId(),
                new Packet(Packet.COMMAND_CLIENT_FEEDBACK_REQUEST,
                        new Object[] { Packet.COMMAND_CFR_HIDDEN_PBS,
                                hidden.getId(), target.getId() }));
    }

    public void sendTeleguidedMissileCFR(int playerId, List<Integer> targetIds, List<Integer> toHitValues) {
        // Send target id numbers and to-hit values to Client
        send(playerId, new Packet(Packet.COMMAND_CLIENT_FEEDBACK_REQUEST,
                new Object[] { Packet.COMMAND_CFR_TELEGUIDED_TARGET, targetIds, toHitValues}));
    }

    public void sendTAGTargetCFR(int playerId, List<Integer> targetIds, List<Integer> targetTypes) {
        // Send target id numbers and type identifiers to Client
        send(playerId, new Packet(Packet.COMMAND_CLIENT_FEEDBACK_REQUEST,
                new Object[] { Packet.COMMAND_CFR_TAG_TARGET, targetIds, targetTypes}));
    }

}
