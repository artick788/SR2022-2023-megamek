package megamek.server;

import megamek.MegaMek;
import megamek.client.ui.swing.util.PlayerColour;
import megamek.common.*;
import megamek.common.actions.ArtilleryAttackAction;
import megamek.common.actions.EntityAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.net.Packet;
import megamek.common.options.GameOptions;
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
     * @return true if the unit succeeds a shelter roll
     */
    public boolean isSheltered() {
        return Compute.d6(2) >= 9;
    }

    public Mounted checkMineSweeper(Entity entity) {
        // Check for Mine sweepers
        Mounted minesweeper = null;
        for (Mounted m : entity.getMisc()) {
            if (m.getType().hasFlag(MiscType.F_MINESWEEPER) && m.isReady() && (m.getArmorValue() > 0)) {
                minesweeper = m;
                break; // Can only have one minesweeper
            }
        }
        return minesweeper;
    }

    //////////////////////////////
    // TODO (Sam): Receives (set somewhere else??)
    //////////////////////////////

    /**
     * Allow the player to set whatever parameters he is able to
     */
    public void receivePlayerInfo(Packet packet, IPlayer gamePlayer) {
        IPlayer player = (IPlayer) packet.getObject(0);
        if (null != gamePlayer) {
            gamePlayer.setColour(player.getColour());
            gamePlayer.setStartingPos(player.getStartingPos());
            gamePlayer.setTeam(player.getTeam());
            gamePlayer.setCamoCategory(player.getCamoCategory());
            gamePlayer.setCamoFileName(player.getCamoFileName());
            gamePlayer.setNbrMFConventional(player.getNbrMFConventional());
            gamePlayer.setNbrMFCommand(player.getNbrMFCommand());
            gamePlayer.setNbrMFVibra(player.getNbrMFVibra());
            gamePlayer.setNbrMFActive(player.getNbrMFActive());
            gamePlayer.setNbrMFInferno(player.getNbrMFInferno());
            if (gamePlayer.getConstantInitBonus() != player.getConstantInitBonus()) {
                sendServerChat("Player " + gamePlayer.getName() + " changed their initiative bonus from "
                        + gamePlayer.getConstantInitBonus() + " to " + player.getConstantInitBonus() + ".");
            }
            gamePlayer.setConstantInitBonus(player.getConstantInitBonus());
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

    public void send_Nova_Change(int Id, String net) {
        Object[] data = {Id, net};
        Packet packet = new Packet(Packet.COMMAND_ENTITY_NOVA_NETWORK_CHANGE, data);
        send(packet);
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

    /**
     * cycle through all mines on the board, check to see whether they should do
     * collateral damage to other mines due to detonation, resets detonation to
     * false, and removes any mines whose density has been reduced to zero.
     */
    public void resetMines(IGame game) {
        Enumeration<Coords> mineLoc = game.getMinedCoords();
        while (mineLoc.hasMoreElements()) {
            Coords c = mineLoc.nextElement();
            Enumeration<Minefield> minefields = game.getMinefields(c).elements();
            while (minefields.hasMoreElements()) {
                Minefield minefield = minefields.nextElement();
                if (minefield.hasDetonated()) {
                    minefield.setDetonated(false);
                    Enumeration<Minefield> otherMines = game.getMinefields(c).elements();
                    while (otherMines.hasMoreElements()) {
                        Minefield otherMine = otherMines.nextElement();
                        if (otherMine.equals(minefield)) {
                            continue;
                        }
                        int bonus = 0;
                        if (otherMine.getDensity() > minefield.getDensity()) {
                            bonus = 1;
                        }
                        if (otherMine.getDensity() < minefield.getDensity()) {
                            bonus = -1;
                        }
                        otherMine.checkReduction(bonus, false);
                    }
                }
            }
            // cycle through a second time to see if any mines at these coords
            // need to be removed
            List<Minefield> mfRemoved = new ArrayList<>();
            Enumeration<Minefield> mines = game.getMinefields(c).elements();
            while (mines.hasMoreElements()) {
                Minefield mine = mines.nextElement();
                if (mine.getDensity() < 5) {
                    mfRemoved.add(mine);
                }
            }
            // we have to do it this way to avoid a concurrent error problem
            for (Minefield mf : mfRemoved) {
                removeMinefield(game, mf);
            }
            // update the mines at these coords
            sendChangedMines(game, c);
        }
    }

    /**
     * Removes the minefield from the game.
     *
     * @param mf The <code>Minefield</code> to remove
     */
    public void removeMinefield(IGame game, Minefield mf) {
        if (game.containsVibrabomb(mf)) {
            game.removeVibrabomb(mf);
        }
        game.removeMinefield(mf);

        Enumeration<IPlayer> players = game.getPlayers();
        while (players.hasMoreElements()) {
            IPlayer player = players.nextElement();
            removeMinefield(player, mf);
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

    /**
     * Reveals a minefield for all players.
     *
     * @param mf The <code>Minefield</code> to be revealed
     */
    public void revealMinefield(IGame game, Minefield mf) {
        Enumeration<Team> teams = game.getTeams();
        while (teams.hasMoreElements()) {
            Team team = teams.nextElement();
            revealMinefield(team, mf);
        }
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
     * checks whether a newly set mine should be revealed to players based on
     * LOS. If so, then it reveals the mine
     */
    public void checkForRevealMinefield(IGame game, Minefield mf, Entity layer) {
        Enumeration<Team> teams = game.getTeams();
        // loop through each team and determine if they can see the mine, then
        // loop through players on team
        // and reveal the mine
        while (teams.hasMoreElements()) {
            Team team = teams.nextElement();
            boolean canSee = false;

            // the players own team can always see the mine
            if (team.equals(game.getTeamForPlayer(game.getPlayer(mf.getPlayerId())))) {
                canSee = true;
            } else {
                // need to loop through all entities on this team and find the
                // one with the best shot of seeing
                // the mine placement
                int target = Integer.MAX_VALUE;
                Iterator<Entity> entities = game.getEntities();
                while (entities.hasNext()) {
                    Entity en = entities.next();
                    // are we on the right team?
                    if (!team.equals(game.getTeamForPlayer(en.getOwner()))) {
                        continue;
                    }
                    if (LosEffects.calculateLos(game, en.getId(),
                            new HexTarget(mf.getCoords(), game.getBoard(), Targetable.TYPE_HEX_CLEAR)).canSee()) {
                        target = 0;
                        break;
                    }
                    LosEffects los = LosEffects.calculateLos(game, en.getId(), layer);
                    if (los.canSee()) {
                        // TODO : need to add mods
                        ToHitData current = new ToHitData(4, "base");
                        current.append(Compute.getAttackerMovementModifier(game, en.getId()));
                        current.append(Compute.getTargetMovementModifier(game, layer.getId()));
                        current.append(los.losModifiers(game));
                        if (current.getValue() < target) {
                            target = current.getValue();
                        }
                    }
                }

                if (Compute.d6(2) >= target) {
                    canSee = true;
                }
            }
            if (canSee) {
                revealMinefield(team, mf);
            }
        }
    }

    /**
     * Clear any detonated mines at these coords
     */
    public void clearDetonatedMines(IGame game, Coords c, int target) {
        Enumeration<Minefield> minefields = game.getMinefields(c).elements();
        List<Minefield> mfRemoved = new ArrayList<>();
        while (minefields.hasMoreElements()) {
            Minefield minefield = minefields.nextElement();
            if (minefield.hasDetonated() && (Compute.d6(2) >= target)) {
                mfRemoved.add(minefield);
            }
        }
        // we have to do it this way to avoid a concurrent error problem
        for (Minefield mf : mfRemoved) {
            removeMinefield(game, mf);
        }
    }

    /**
     * process deployment of minefields
     *
     * @param minefields
     */
    public void processDeployMinefields(IGame game, Vector<Minefield> minefields) {
        int playerId = IPlayer.PLAYER_NONE;
        for (int i = 0; i < minefields.size(); i++) {
            Minefield mf = minefields.elementAt(i);
            playerId = mf.getPlayerId();

            game.addMinefield(mf);
            if (mf.getType() == Minefield.TYPE_VIBRABOMB) {
                game.addVibrabomb(mf);
            }
        }

        IPlayer player = game.getPlayer(playerId);
        if (null != player) {
            int teamId = player.getTeam();

            if (teamId != IPlayer.TEAM_NONE) {
                Enumeration<Team> teams = game.getTeams();
                while (teams.hasMoreElements()) {
                    Team team = teams.nextElement();
                    if (team.getId() == teamId) {
                        Enumeration<IPlayer> players = team.getPlayers();
                        while (players.hasMoreElements()) {
                            IPlayer teamPlayer = players.nextElement();
                            if (teamPlayer.getId() != player.getId()) {
                                send(teamPlayer.getId(), new Packet(Packet.COMMAND_DEPLOY_MINEFIELDS, minefields));
                            }
                            teamPlayer.addMinefields(minefields);
                        }
                        break;
                    }
                }
            } else {
                player.addMinefields(minefields);
            }
        }
    }

    public void createThunderMinefield(IGame game, Minefield minefield, Coords coords, int playerId, int damage, int entityId, int type) {
        // TODO (Sam): Nog enkel chekcen of dit een vibrabomb is
        createThunderMinefield(game, minefield, coords, playerId, damage, entityId, type, 0);
    }

    public void createThunderMinefield(IGame game, Minefield minefield, Coords coords, int playerId, int damage, int entityId, int type, int sensitivity) {
        // Create a new Thunder minefield
        if (minefield == null) {
            if (type == Minefield.TYPE_VIBRABOMB) {
                minefield = Minefield.createMinefield(coords, playerId, type, damage, sensitivity);
            } else {
                minefield = Minefield.createMinefield(coords, playerId, type, damage);
            }
            game.addMinefield(minefield);
            if (type == Minefield.TYPE_VIBRABOMB) {
                game.addVibrabomb(minefield);
            }
            checkForRevealMinefield(game, minefield, game.getEntity(entityId));
        } else if (minefield.getDensity() < Minefield.MAX_DAMAGE) {
            // Add to the old one
            removeMinefield(game, minefield);
            int oldDamage = minefield.getDensity();
            damage += oldDamage;
            damage = Math.min(damage, Minefield.MAX_DAMAGE);
            minefield.setDensity(damage);
            game.addMinefield(minefield);
            if (type == Minefield.TYPE_VIBRABOMB) {
                game.addVibrabomb(minefield);
            }
            checkForRevealMinefield(game, minefield, game.getEntity(entityId));
        }
    }

    public void deliverMinefield(IGame game, Coords coords, int playerId, int damage, int entityId, int type) {
        if (type == Minefield.TYPE_VIBRABOMB) {
            throw new RuntimeException("This function call should not be used for type VIBRABOMB");
        }
        deliverMinefield(game, coords, playerId, damage, entityId, 0, type);
    }

    /**
     * Adds a minefield of a certain type to the hex.
     * @param coords   the minefield's coordinates
     * @param playerId the deploying player's id
     * @param damage   the amount of damage the minefield does
     * @param entityId an entity that might spot the minefield
     * @param type the type of the minefield:
     *             - TYPE_CONVENTIONAL: Thunder minefield
     *             - TYPE_INFERNO: Thunder-inferno minefield
     *             - TYPE_ACTIVE: Thunder-active minefield
     *             - TYPE_VIBRABOMB: Thunder-vibra minefield
     */
    public void deliverMinefield(IGame game, Coords coords, int playerId, int damage, int entityId, int sensitivity, int type) {
        Minefield minefield = null;
        // Check if there already are minefields of that type in the hex.
        for (Minefield mf : game.getMinefields(coords)) {
            if (mf.getType() == type) {
                minefield = mf;
                break;
            }
        }

        // Create a new thunder minefield
        if (type == Minefield.TYPE_CONVENTIONAL || type == Minefield.TYPE_ACTIVE || type == Minefield.TYPE_INFERNO) {
            createThunderMinefield(game, minefield, coords, playerId, damage, entityId, type);
        } else if (type == Minefield.TYPE_VIBRABOMB) {
            createThunderMinefield(game, minefield, coords, playerId, damage, entityId, type, sensitivity);
        }
    }

    /**
     * Delivers a thunder-aug shot to the targeted hex area. Thunder-Augs are 7
     * hexes, though, so...
     *
     * @param damage
     *            The per-hex density of the incoming minefield; that is, the
     *            final value with any modifiers (such as halving and rounding
     *            just for <em>being</em> T-Aug) already applied.
     */
    public void deliverThunderAugMinefield(IGame game, Coords coords, int playerId, int damage, int entityId) {
        Coords mfCoord;
        for (int dir = 0; dir < 7; dir++) {
            // May need to reset here for each new hex.
            int hexDamage = damage;
            if (dir == 6) {// The targeted hex.
                mfCoord = coords;
            } else {// The hex in the dir direction from the targeted hex.
                mfCoord = coords.translated(dir);
            }

            // Only if this is on the board...
            if (game.getBoard().contains(mfCoord)) {
                deliverMinefield(game, mfCoord, playerId, hexDamage, entityId, Minefield.TYPE_CONVENTIONAL);
            } // End coords-on-board
        } // Handle the next coords
    }

    /**
     * Delivers an artillery FASCAM shot to the targeted hex area.
     */
    public void deliverFASCAMMinefield(IGame game, Coords coords, int playerId, int damage, int entityId) {
        // Only if this is on the board...
        if (game.getBoard().contains(coords)) {
            deliverMinefield(game, coords, playerId, damage, entityId, Minefield.TYPE_CONVENTIONAL);
        } // End coords-on-board
    }


}
