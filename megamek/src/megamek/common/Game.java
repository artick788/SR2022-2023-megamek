/*
 * MegaMek -
 * Copyright (C) 2000,2001,2002,2003,2004,2005 Ben Mazur (bmazur@sev.org)
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

package megamek.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;

import com.thoughtworks.xstream.XStream;
import megamek.MegaMek;
import megamek.client.ui.swing.util.PlayerColour;
import megamek.common.GameTurn.SpecificEntityTurn;
import megamek.common.actions.*;
import megamek.common.event.GameBoardChangeEvent;
import megamek.common.event.GameBoardNewEvent;
import megamek.common.event.GameEndEvent;
import megamek.common.event.GameEntityChangeEvent;
import megamek.common.event.GameEntityNewEvent;
import megamek.common.event.GameEntityNewOffboardEvent;
import megamek.common.event.GameEntityRemoveEvent;
import megamek.common.event.GameEvent;
import megamek.common.event.GameListener;
import megamek.common.event.GameNewActionEvent;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.event.GamePlayerChangeEvent;
import megamek.common.event.GameSettingsChangeEvent;
import megamek.common.event.GameTurnChangeEvent;
import megamek.common.icons.Camouflage;
import megamek.common.net.Packet;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.weapons.AttackHandler;
import megamek.common.weapons.Weapon;
import megamek.common.weapons.WeaponHandler;
import megamek.server.SmokeCloud;
import megamek.server.victory.Victory;

/**
 * The game class is the root of all data about the game in progress. Both the
 * Client and the Server should have one of these objects and it is their job to
 * keep it synched.
 */
public class Game implements Serializable, IGame {
    /**
     *
     */
    private static final long serialVersionUID = 8376320092671792532L;

    /**
     * A UUID to identify this game instance.
     */
    public UUID uuid = UUID.randomUUID();

    /**
     * Stores the version of MM, so that it can be serialized in saved games.
     */
    public String mmVersion = MegaMek.VERSION;

    /**
     * Define constants to describe the condition a unit was in when it wass
     * removed from the game.
     */

    private GameOptions options = new GameOptions();

    public IBoard board = new Board();

    private final List<Entity> entities = new CopyOnWriteArrayList<>();
    private Hashtable<Integer, Entity> entityIds = new Hashtable<Integer, Entity>();

    /**
     * Track entities removed from the game (probably by death)
     */
    Vector<Entity> vOutOfGame = new Vector<Entity>();

    private Vector<IPlayer> players = new Vector<IPlayer>();
    private Vector<Team> teams = new Vector<Team>(); // DES

    private Hashtable<Integer, IPlayer> playerIds = new Hashtable<Integer, IPlayer>();

    private final Map<Coords, HashSet<Integer>> entityPosLookup = new HashMap<>();

    /**
     * have the entities been deployed?
     */
    private boolean deploymentComplete = false;

    /**
     * how's the weather?
     */
    private PlanetaryConditions planetaryConditions = new PlanetaryConditions();

    /**
     * what round is it?
     */
    private int roundCount = 0;

    /**
     * The current turn list
     */
    private Vector<GameTurn> turnVector = new Vector<GameTurn>();
    private int turnIndex = 0;

    /**
     * The present phase
     */
    private Phase phase = Phase.PHASE_UNKNOWN;

    /**
     * The past phase
     */
    private Phase lastPhase = Phase.PHASE_UNKNOWN;

    // phase state
    private Vector<EntityAction> actions = new Vector<EntityAction>();
    private Vector<AttackAction> pendingCharges = new Vector<AttackAction>();
    private Vector<AttackAction> pendingRams = new Vector<AttackAction>();
    private Vector<AttackAction> pendingTeleMissileAttacks = new Vector<AttackAction>();
    private Vector<PilotingRollData> pilotRolls = new Vector<PilotingRollData>();
    private Vector<PilotingRollData> extremeGravityRolls = new Vector<PilotingRollData>();
    private Vector<PilotingRollData> controlRolls = new Vector<PilotingRollData>();
    private Vector<Team> initiativeRerollRequests = new Vector<Team>();

    // reports
    private GameReports gameReports = new GameReports();

    private boolean forceVictory = false;
    private int victoryPlayerId = Player.PLAYER_NONE;
    private int victoryTeam = Player.TEAM_NONE;

    private Hashtable<Integer, Vector<Entity>> deploymentTable = new Hashtable<Integer, Vector<Entity>>();
    private int lastDeploymentRound = 0;

    private Hashtable<Coords, Vector<Minefield>> minefields = new Hashtable<Coords, Vector<Minefield>>();
    private Vector<Minefield> vibrabombs = new Vector<Minefield>();
    private Vector<AttackHandler> attacks = new Vector<AttackHandler>();
    private Vector<ArtilleryAttackAction> offboardArtilleryAttacks = new Vector<ArtilleryAttackAction>();

    private int lastEntityId;

    private Vector<TagInfo> tagInfoForTurn = new Vector<TagInfo>();
    private Vector<Flare> flares = new Vector<Flare>();
    private HashSet<Coords> illuminatedPositions =
            new HashSet<Coords>();

    private HashMap<String, Object> victoryContext = null;

    // internal integer value for an external game id link
    private int externalGameId = 0;

    // victory condition related stuff
    private Victory victory = null;

    // smoke clouds
    private List<SmokeCloud> smokeCloudList = new CopyOnWriteArrayList<>();

    transient private Vector<GameListener> gameListeners = new Vector<GameListener>();

    /**
     * Constructor
     */
    public Game() {
        // empty
    }

    // Added public accessors for external game id
    public int getExternalGameId() {
        return externalGameId;
    }

    public void setExternalGameId(int value) {
        externalGameId = value;
    }

    public IBoard getBoard() {
        return board;
    }

    public void setBoard(IBoard board) {
        IBoard oldBoard = this.board;
        this.board = board;
        processGameEvent(new GameBoardNewEvent(this, oldBoard, board));
    }

    public boolean containsMinefield(Coords coords) {
        return minefields.containsKey(coords);
    }

    public Vector<Minefield> getMinefields(Coords coords) {
        Vector<Minefield> mfs = minefields.get(coords);
        if (mfs == null) {
            return new Vector<Minefield>();
        }
        return mfs;
    }

    public int getNbrMinefields(Coords coords) {
        Vector<Minefield> mfs = minefields.get(coords);
        if (mfs == null) {
            return 0;
        }

        return mfs.size();
    }

    /**
     * Get the coordinates of all mined hexes in the game.
     *
     * @return an <code>Enumeration</code> of the <code>Coords</code> containing
     * minefields. This will not be <code>null</code>.
     */
    public Enumeration<Coords> getMinedCoords() {
        return minefields.keys();
    }

    public void addMinefield(Minefield mf) {
        addMinefieldHelper(mf);
        processGameEvent(new GameBoardChangeEvent(this));
    }

    public void addMinefields(Vector<Minefield> mines) {
        for (int i = 0; i < mines.size(); i++) {
            Minefield mf = mines.elementAt(i);
            addMinefieldHelper(mf);
        }
        processGameEvent(new GameBoardChangeEvent(this));
    }

    public void setMinefields(Vector<Minefield> minefields) {
        clearMinefieldsHelper();
        for (int i = 0; i < minefields.size(); i++) {
            Minefield mf = minefields.elementAt(i);
            addMinefieldHelper(mf);
        }
        processGameEvent(new GameBoardChangeEvent(this));
    }

    public void resetMinefieldDensity(Vector<Minefield> newMinefields) {
        if (newMinefields.size() < 1) {
            return;
        }
        Vector<Minefield> mfs = minefields.get(newMinefields.firstElement()
                                                            .getCoords());
        mfs.clear();
        for (int i = 0; i < newMinefields.size(); i++) {
            Minefield mf = newMinefields.elementAt(i);
            addMinefieldHelper(mf);
        }
        processGameEvent(new GameBoardChangeEvent(this));
    }

    protected void addMinefieldHelper(Minefield mf) {
        Vector<Minefield> mfs = minefields.get(mf.getCoords());
        if (mfs == null) {
            mfs = new Vector<Minefield>();
            mfs.addElement(mf);
            minefields.put(mf.getCoords(), mfs);
            return;
        }
        mfs.addElement(mf);
    }

    public void removeMinefield(Minefield mf) {
        removeMinefieldHelper(mf);
        processGameEvent(new GameBoardChangeEvent(this));
    }

    public void removeMinefieldHelper(Minefield mf) {
        Vector<Minefield> mfs = minefields.get(mf.getCoords());
        if (mfs == null) {
            return;
        }

        Enumeration<Minefield> e = mfs.elements();
        while (e.hasMoreElements()) {
            Minefield mftemp = e.nextElement();
            if (mftemp.equals(mf)) {
                mfs.removeElement(mftemp);
                break;
            }
        }
        if (mfs.isEmpty()) {
            minefields.remove(mf.getCoords());
        }
    }

    public void clearMinefields() {
        clearMinefieldsHelper();
        processGameEvent(new GameBoardChangeEvent(this));
    }

    protected void clearMinefieldsHelper() {
        minefields.clear();
        vibrabombs.removeAllElements();

        Enumeration<IPlayer> iter = getPlayers();
        while (iter.hasMoreElements()) {
            IPlayer player = iter.nextElement();
            player.removeMinefields();
        }
    }

    public Vector<Minefield> getVibrabombs() {
        return vibrabombs;
    }

    public void addVibrabomb(Minefield mf) {
        vibrabombs.addElement(mf);
    }

    public void removeVibrabomb(Minefield mf) {
        vibrabombs.removeElement(mf);
    }

    public boolean containsVibrabomb(Minefield mf) {
        return vibrabombs.contains(mf);
    }

    public GameOptions getOptions() {
        return options;
    }

    public void setOptions(GameOptions options) {
        if (null == options) {
            System.err.println("Can't set the game options to null!");
        } else {
            this.options = options;
            processGameEvent(new GameSettingsChangeEvent(this));
        }
    }

    /**
     * Return an enumeration of teams in the game
     */
    public Enumeration<Team> getTeams() {
        return teams.elements();
    }

    /**
     * Return the current number of teams in the game.
     */
    public int getNoOfTeams() {
        return teams.size();
    }

    /**
     * This returns a clone of the vector of teams. Each element is one of the
     * teams in the game.
     */
    public List<Team> getTeamsVector() {
        return Collections.unmodifiableList(teams);
    }

    /**
     * Return a players team Note: may return null if player has no team
     */
    public Team getTeamForPlayer(IPlayer p) {
        for (Team team : teams) {
            for (Enumeration<IPlayer> j = team.getPlayers(); j.hasMoreElements(); ) {
                final IPlayer player = j.nextElement();
                if (p == player) {
                    return team;
                }
            }
        }
        return null;
    }

    /**
     * Set up the teams vector. Each player on a team (Team 1 .. Team X) is
     * placed in the appropriate vector. Any player on 'No Team', is placed in
     * their own object
     */
    public void setupTeams() {
        Vector<Team> initTeams = new Vector<Team>();
        boolean useTeamInit = getOptions().getOption(OptionsConstants.BASE_TEAM_INITIATIVE)
                                          .booleanValue();

        // Get all NO_TEAM players. If team_initiative is false, all
        // players are on their own teams for initiative purposes.
        for (Enumeration<IPlayer> i = getPlayers(); i.hasMoreElements(); ) {
            final IPlayer player = i.nextElement();
            // Ignore players not on a team
            if (player.getTeam() == IPlayer.TEAM_UNASSIGNED) {
                continue;
            }
            if (!useTeamInit || (player.getTeam() == IPlayer.TEAM_NONE)) {
                Team new_team = new Team(IPlayer.TEAM_NONE);
                new_team.addPlayer(player);
                initTeams.addElement(new_team);
            }
        }

        if (useTeamInit) {
            // Now, go through all the teams, and add the appropriate player
            for (int t = IPlayer.TEAM_NONE + 1; t < IPlayer.MAX_TEAMS; t++) {
                Team new_team = null;
                for (Enumeration<IPlayer> i = getPlayers(); i.hasMoreElements(); ) {
                    final IPlayer player = i.nextElement();
                    if (player.getTeam() == t) {
                        if (new_team == null) {
                            new_team = new Team(t);
                        }
                        new_team.addPlayer(player);
                    }
                }

                if (new_team != null) {
                    initTeams.addElement(new_team);
                }
            }
        }

        // May need to copy state over from previous teams, such as initiative
        if ((teams != null) && (getPhase() != Phase.PHASE_LOUNGE)) {
            for (Team newTeam : initTeams) {
                for (Team oldTeam : teams) {
                    if (newTeam.equals(oldTeam)) {
                        newTeam.setInitiative(oldTeam.getInitiative());
                    }
                }
            }
        }
        teams = initTeams;
    }

    /**
     * Return an enumeration of player in the game
     */
    public Enumeration<IPlayer> getPlayers() {
        return players.elements();
    }

    /**
     * Return the players vector
     */
    public Vector<IPlayer> getPlayersVector() {
        return players;
    }

    /**
     * Return the current number of active players in the game.
     */
    public int getNoOfPlayers() {
        return players.size();
    }

    /**
     * Returns the individual player assigned the id parameter.
     */
    public IPlayer getPlayer(int id) {
        if (IPlayer.PLAYER_NONE == id) {
            return null;
        }
        return playerIds.get(Integer.valueOf(id));
    }

    public void addPlayer(int id, IPlayer player) {
        player.setGame(this);
        players.addElement(player);
        playerIds.put(Integer.valueOf(id), player);
        setupTeams();
        updatePlayer(player);
    }

    public void setPlayer(int id, IPlayer player) {
        final IPlayer oldPlayer = getPlayer(id);
        player.setGame(this);
        players.setElementAt(player, players.indexOf(oldPlayer));
        playerIds.put(Integer.valueOf(id), player);
        setupTeams();
        updatePlayer(player);
    }

    protected void updatePlayer(IPlayer player) {
        processGameEvent(new GamePlayerChangeEvent(this, player));
    }

    public void removePlayer(int id) {
        IPlayer playerToRemove = getPlayer(id);
        players.removeElement(playerToRemove);
        playerIds.remove(Integer.valueOf(id));
        setupTeams();
        processGameEvent(new GamePlayerChangeEvent(this, playerToRemove));
    }

    /**
     * Returns the number of entities owned by the player, regardless of their
     * status, as long as they are in the game.
     */
    public int getEntitiesOwnedBy(IPlayer player) {
        int count = 0;
        for (Entity entity : entities) {
            if (entity.getOwner().equals(player)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the number of entities owned by the player, regardless of their
     * status.
     */
    public int getAllEntitiesOwnedBy(IPlayer player) {
        int count = 0;
        for (Entity entity : entities) {
            if (entity.getOwner().equals(player)) {
                count++;
            }
        }
        for (Entity entity : vOutOfGame) {
            if (entity.getOwner().equals(player)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the number of non-destroyed entityes owned by the player
     */
    public int getLiveEntitiesOwnedBy(IPlayer player) {
        int count = 0;
        for (Entity entity : entities) {
            if (entity.getOwner().equals(player) && !entity.isDestroyed()
                    && !entity.isCarcass()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the number of non-destroyed entities owned by the player,
     * including entities not yet deployed. Ignore offboard units and captured
     * Mek pilots.
     */
    public int getLiveDeployedEntitiesOwnedBy(IPlayer player) {
        int count = 0;
        for (Entity entity : entities) {
            if (entity.getOwner().equals(player) && !entity.isDestroyed()
                && !entity.isCarcass()
                && !entity.isOffBoard() && !entity.isCaptured()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the number of non-destroyed deployed entities owned by the
     * player. Ignore offboard units and captured Mek pilots.
     */
    public int getLiveCommandersOwnedBy(IPlayer player) {
        int count = 0;
        for (Entity entity : entities) {
            if (entity.getOwner().equals(player) && !entity.isDestroyed()
                && !entity.isCarcass()
                && entity.isCommander() && !entity.isOffBoard()
                && !entity.isCaptured()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns true if the player has a valid unit with the Tactical Genius
     * pilot special ability.
     */
    public boolean hasTacticalGenius(IPlayer player) {
        for (Entity entity : entities) {
            if (entity.hasAbility(OptionsConstants.MISC_TACTICAL_GENIUS)
                    && entity.getOwner().equals(player) && !entity.isDestroyed() && entity.isDeployed()
                    && !entity.isCarcass() && !entity.getCrew().isUnconscious()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get a vector of entity objects that are "acceptable" to attack with this
     * entity
     */
    public List<Entity> getValidTargets(Entity entity) {
        List<Entity> ents = new ArrayList<Entity>();

        boolean friendlyFire = getOptions().booleanOption(OptionsConstants.BASE_FRIENDLY_FIRE);

        for (Entity otherEntity : entities) {
            // Even if friendly fire is acceptable, do not shoot yourself
            // Enemy units not on the board can not be shot.
            if ((otherEntity.getPosition() != null)
                    && !otherEntity.isOffBoard()
                    && otherEntity.isTargetable()
                    && !otherEntity.isHidden()
                    && !otherEntity.isSensorReturn(entity.getOwner())
                    && otherEntity.hasSeenEntity(entity.getOwner())
                    && (entity.isEnemyOf(otherEntity) || (friendlyFire && (entity
                            .getId() != otherEntity.getId())))) {
                // Air to Ground - target must be on flight path
                if (Compute.isAirToGround(entity, otherEntity)) {
                    if (entity.getPassedThrough().contains(
                            otherEntity.getPosition())) {
                        ents.add(otherEntity);
                    }                
                } else {
                    ents.add(otherEntity);
                }
            }
        }

        return Collections.unmodifiableList(ents);
    }

    /**
     * Returns true if this phase has turns. If false, the phase is simply
     * waiting for everybody to declare "done".
     */
    public boolean phaseHasTurns(IGame.Phase thisPhase) {
        switch (thisPhase) {
            case PHASE_SET_ARTYAUTOHITHEXES:
            case PHASE_DEPLOY_MINEFIELDS:
            case PHASE_DEPLOYMENT:
            case PHASE_MOVEMENT:
            case PHASE_FIRING:
            case PHASE_PHYSICAL:
            case PHASE_TARGETING:
            case PHASE_OFFBOARD:
                return true;
            default:
                return false;
        }
    }

    public boolean isPhaseSimultaneous() {
        return phase.isPhaseSimultaneous(this);
    }

    /**
     * Returns the current GameTurn object
     */
    public GameTurn getTurn() {
        if ((turnIndex < 0) || (turnIndex >= turnVector.size())) {
            return null;
        }
        return turnVector.elementAt(turnIndex);
    }

    public GameTurn getTurnForPlayer(int pn) {
        for (int i = turnIndex; i < turnVector.size(); i++) {
            GameTurn gt = turnVector.get(i);
            if (gt.isValid(pn, this)) {
                return gt;
            }
        }
        return null;
    }

    /**
     * Changes to the next turn, returning it.
     */
    public GameTurn changeToNextTurn() {
        turnIndex++;
        return getTurn();
    }

    /**
     * Resets the turn index to -1 (awaiting first turn)
     */
    public void resetTurnIndex() {
        turnIndex = -1;
    }

    /**
     * Returns true if there is a turn after the current one
     */
    public boolean hasMoreTurns() {
        return turnVector.size() > turnIndex;
    }

    /**
     * Inserts a turn that will come directly after the current one
     */
    public void insertNextTurn(GameTurn turn) {
        turnVector.insertElementAt(turn, turnIndex + 1);
    }

    /**
     * Inserts a turn after the specific index
     */
    public void insertTurnAfter(GameTurn turn, int index) {
        if ((index + 1) >= turnVector.size()) {
            turnVector.add(turn);
        } else {
            turnVector.insertElementAt(turn, index + 1);
        }
    }

    public void swapTurnOrder(int index1, int index2) {
        GameTurn turn1 = turnVector.get(index1);
        GameTurn turn2 = turnVector.get(index2);
        turnVector.set(index2, turn1);
        turnVector.set(index1, turn2);
    }

    /**
     * Returns an Enumeration of the current turn list
     */
    public Enumeration<GameTurn> getTurns() {
        return turnVector.elements();
    }

    /**
     * Returns the current turn index
     */
    public int getTurnIndex() {
        return turnIndex;
    }

    /**
     * Sets the current turn index
     */
    public void setTurnIndex(int turnIndex, int prevPlayerId) {
        // FIXME: occasionally getTurn() returns null. Handle that case
        // intelligently.
        this.turnIndex = turnIndex;
        processGameEvent(new GameTurnChangeEvent(this, getPlayer(getTurn()
                .getPlayerNum()), prevPlayerId));
    }

    /**
     * Returns the current turn vector
     */
    public List<GameTurn> getTurnVector() {
        return Collections.unmodifiableList(turnVector);
    }

    /**
     * Sets the current turn vector
     */
    public void setTurnVector(List<GameTurn> turnVector) {
        this.turnVector.clear();
        for (GameTurn turn : turnVector) {
            this.turnVector.add(turn);
        }
    }

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        final Phase oldPhase = this.phase;
        this.phase = phase;
        // Handle phase-specific items.
        switch (phase) {
            case PHASE_LOUNGE:
                reset();
                break;
            case PHASE_TARGETING:
                resetActions();
                break;
            case PHASE_MOVEMENT:
                resetActions();
                break;
            case PHASE_FIRING:
                resetActions();
                break;
            case PHASE_PHYSICAL:
                resetActions();
                break;
            case PHASE_DEPLOYMENT:
                resetActions();
                break;
            case PHASE_INITIATIVE:
                resetActions();
                resetCharges();
                resetRams();
                break;
            // TODO Is there better solution to handle charges?
            case PHASE_PHYSICAL_REPORT:
            case PHASE_END:
                resetCharges();
                resetRams();
                break;
            default:
        }

        processGameEvent(new GamePhaseChangeEvent(this, oldPhase, phase));
    }

    public Phase getLastPhase() {
        return lastPhase;
    }

    public void setLastPhase(Phase lastPhase) {
        this.lastPhase = lastPhase;
    }

    public void setDeploymentComplete(boolean deploymentComplete) {
        this.deploymentComplete = deploymentComplete;
    }

    public boolean isDeploymentComplete() {
        return deploymentComplete;
    }

    /**
     * Sets up up the hashtable of who deploys when
     */
    public void setupRoundDeployment() {
        deploymentTable = new Hashtable<Integer, Vector<Entity>>();

        for (Entity ent : entities) {
            if (ent.isDeployed()) {
                continue;
            }

            Vector<Entity> roundVec = deploymentTable.get(Integer.valueOf(ent.getDeployRound()));

            if (null == roundVec) {
                roundVec = new Vector<Entity>();
                deploymentTable.put(ent.getDeployRound(), roundVec);
            }

            roundVec.addElement(ent);
            lastDeploymentRound = Math.max(lastDeploymentRound,
                                           ent.getDeployRound());
        }
    }

    /**
     * Checks to see if we've past our deployment completion
     */
    public void checkForCompleteDeployment() {
        setDeploymentComplete(lastDeploymentRound < getRoundCount());
    }

    /**
     * Check to see if we should deploy this round
     */
    public boolean shouldDeployThisRound() {
        return shouldDeployForRound(getRoundCount());
    }

    public boolean shouldDeployForRound(int round) {
        Vector<Entity> vec = getEntitiesToDeployForRound(round);

        return (((null == vec) || (vec.size() == 0)) ? false : true);
    }

    private Vector<Entity> getEntitiesToDeployForRound(int round) {
        return deploymentTable.get(Integer.valueOf(round));
    }

    /**
     * Clear this round from this list of entities to deploy
     */
    public void clearDeploymentThisRound() {
        deploymentTable.remove(Integer.valueOf(getRoundCount()));
    }

    /**
     * Returns a vector of entities that have not yet deployed
     */
    public List<Entity> getUndeployedEntities() {
        List<Entity> entList = new ArrayList<Entity>();
        Enumeration<Vector<Entity>> iter = deploymentTable.elements();

        while (iter.hasMoreElements()) {
            Vector<Entity> vecTemp = iter.nextElement();

            for (int i = 0; i < vecTemp.size(); i++) {
                entList.add(vecTemp.elementAt(i));
            }
        }

        return Collections.unmodifiableList(entList);
    }

    /**
     * Returns an enumeration of all the entites in the game.
     */
    public Iterator<Entity> getEntities() {
        return entities.iterator();
    }

    public Entity getPreviousEntityFromList(Entity current) {
        if ((current != null) && entities.contains(current)) {
            int prev = entities.indexOf(current) - 1;
            if (prev < 0) {
                prev = entities.size() - 1; // wrap around to end
            }
            return entities.get(prev);
        }
        return null;
    }

    public Entity getNextEntityFromList(Entity current) {
        if ((current != null) && entities.contains(current)) {
            int next = entities.indexOf(current) + 1;
            if (next >= entities.size()) {
                next = 0; // wrap-around to begining
            }
            return entities.get(next);
        }
        return null;
    }

    /**
     * Returns the actual vector for the entities
     */
    public List<Entity> getEntitiesVector() {
        return Collections.unmodifiableList(entities);
    }

    public synchronized void setEntitiesVector(List<Entity> entities) {
        //checkPositionCacheConsistency();
        this.entities.clear();
        this.entities.addAll(entities);
        reindexEntities();
        resetEntityPositionLookup();
        processGameEvent(new GameEntityNewEvent(this, entities));
    }

    /**
     * Returns the actual vector for the out-of-game entities
     */
    public Vector<Entity> getOutOfGameEntitiesVector() {
        return vOutOfGame;
    }

    /**
     * Swap out the current list of dead (or fled) units for a new one.
     *
     * @param vOutOfGame - the new <code>Vector</code> of dead or fled units. This
     *                   value should <em>not</em> be <code>null</code>.
     * @throws IllegalArgumentException if the new list is <code>null</code>.
     */
    public void setOutOfGameEntitiesVector(List<Entity> vOutOfGame) {
        assert (vOutOfGame != null) : "New out-of-game list should not be null.";
        Vector<Entity> newOutOfGame = new Vector<Entity>();

        // Add entities for the existing players to the game.
        for (Entity entity : vOutOfGame) {
            int ownerId = entity.getOwnerId();
            if ((ownerId != Entity.NONE) && (getPlayer(ownerId) != null)) {
                entity.setGame(this);
                newOutOfGame.addElement(entity);
            }
        }
        this.vOutOfGame = newOutOfGame;
        processGameEvent(new GameEntityNewOffboardEvent(this));
    }

    /**
     * Returns an out-of-game entity.
     *
     * @param id the <code>int</code> ID of the out-of-game entity.
     * @return the out-of-game <code>Entity</code> with that ID. If no
     * out-of-game entity has that ID, returns a <code>null</code>.
     */
    public Entity getOutOfGameEntity(int id) {
        Entity match = null;
        Enumeration<Entity> iter = vOutOfGame.elements();
        while ((null == match) && iter.hasMoreElements()) {
            Entity entity = iter.nextElement();
            if (id == entity.getId()) {
                match = entity;
            }
        }
        return match;
    }

    /**
     * Returns a <code>Vector</code> containing the <code>Entity</code>s that
     * are in the same C3 network as the passed-in unit. The output will contain
     * the passed-in unit, if the unit has a C3 computer. If the unit has no C3
     * computer, the output will be empty (but it will never be
     * <code>null</code>).
     *
     * @param entity - the <code>Entity</code> whose C3 network co- members is
     *               required. This value may be <code>null</code>.
     * @return a <code>Vector</code> that will contain all other
     * <code>Entity</code>s that are in the same C3 network as the
     * passed-in unit. This <code>Vector</code> may be empty, but it
     * will not be <code>null</code>.
     * @see #getC3SubNetworkMembers(Entity)
     */
    public Vector<Entity> getC3NetworkMembers(Entity entity) {
        Vector<Entity> members = new Vector<Entity>();
        //WOR
        // Does the unit have a C3 computer?
        if ((entity != null) && (entity.hasC3() || entity.hasC3i() || entity.hasActiveNovaCEWS() || entity.hasNavalC3())) {

            // Walk throught the entities in the game, and add all
            // members of the C3 network to the output Vector.
            for (Entity unit : entities) {
                if (entity.equals(unit) || entity.onSameC3NetworkAs(unit)) {
                    members.addElement(unit);
                }
            }

        } // End entity-has-C3

        return members;
    }

    /**
     * Returns a <code>Vector</code> containing the <code>Entity</code>s that
     * are in the C3 sub-network under the passed-in unit. The output will
     * contain the passed-in unit, if the unit has a C3 computer. If the unit
     * has no C3 computer, the output will be empty (but it will never be
     * <code>null</code>). If the passed-in unit is a company commander or a
     * member of a C3i network, this call is the same as
     * <code>getC3NetworkMembers</code>.
     *
     * @param entity - the <code>Entity</code> whose C3 network sub- members is
     *               required. This value may be <code>null</code>.
     * @return a <code>Vector</code> that will contain all other
     * <code>Entity</code>s that are in the same C3 network under the
     * passed-in unit. This <code>Vector</code> may be empty, but it
     * will not be <code>null</code>.
     * @see #getC3NetworkMembers(Entity)
     */
    public Vector<Entity> getC3SubNetworkMembers(Entity entity) {
        //WOR
        // Handle null, C3i, NC3, and company commander units.
        if ((entity == null) || entity.hasC3i() || entity.hasNavalC3() || entity.hasActiveNovaCEWS() || entity.C3MasterIs(entity)) {
            return getC3NetworkMembers(entity);
        }

        Vector<Entity> members = new Vector<Entity>();

        // Does the unit have a C3 computer?
        if (entity.hasC3()) {

            // Walk throught the entities in the game, and add all
            // sub-members of the C3 network to the output Vector.
            for (Entity unit : entities) {
                if (entity.equals(unit) || unit.C3MasterIs(entity)) {
                    members.addElement(unit);
                }
            }

        } // End entity-has-C3

        return members;
    }

    /**
     * Returns a <code>Hashtable</code> that maps the <code>Coords</code> of
     * each unit in this <code>Game</code> to a <code>Vector</code> of
     * <code>Entity</code>s at that positions. Units that have no position (e.g.
     * loaded units) will not be in the map.
     *
     * @return a <code>Hashtable</code> that maps the <code>Coords</code>
     * positions or each unit in the game to a <code>Vector</code> of
     * <code>Entity</code>s at that position.
     */
    public Hashtable<Coords, Vector<Entity>> getPositionMap() {
        Hashtable<Coords, Vector<Entity>> positionMap = new Hashtable<Coords, Vector<Entity>>();
        Vector<Entity> atPos = null;

        // Walk through the entities in this game.
        for (Entity entity : entities) {
            // Get the vector for this entity's position.
            final Coords coords = entity.getPosition();
            if (coords != null) {
                atPos = positionMap.get(coords);

                // If this is the first entity at this position,
                // create the vector and add it to the map.
                if (atPos == null) {
                    atPos = new Vector<Entity>();
                    positionMap.put(coords, atPos);
                }

                // Add the entity to the vector for this position.
                atPos.addElement(entity);

            }
        } // Handle the next entity.

        // Return the map.
        return positionMap;
    }

    /**
     * Returns an enumeration of salvagable entities.
     */
    public Enumeration<Entity> getGraveyardEntities() {
        Vector<Entity> graveyard = new Vector<Entity>();

        for (Entity entity : vOutOfGame) {
            if ((entity.getRemovalCondition() == IEntityRemovalConditions.REMOVE_SALVAGEABLE)
                || (entity.getRemovalCondition() == IEntityRemovalConditions.REMOVE_EJECTED)) {
                graveyard.addElement(entity);
            }
        }

        return graveyard.elements();
    }

    /**
     * Returns an enumeration of wrecked entities.
     */
    public Enumeration<Entity> getWreckedEntities() {
        Vector<Entity> wrecks = new Vector<Entity>();
        for (Entity entity : vOutOfGame) {
            if ((entity.getRemovalCondition() == IEntityRemovalConditions.REMOVE_SALVAGEABLE)
                || (entity.getRemovalCondition() == IEntityRemovalConditions.REMOVE_EJECTED)
                || (entity.getRemovalCondition() == IEntityRemovalConditions.REMOVE_DEVASTATED)) {
                wrecks.addElement(entity);
            }
        }
        
        return wrecks.elements();
    }

    /**
     * Returns an enumeration of entities that have retreated
     */
 // TODO: Correctly implement "Captured" Entities
    public Enumeration<Entity> getRetreatedEntities() {
        Vector<Entity> sanctuary = new Vector<Entity>();

        for (Entity entity : vOutOfGame) {
            if ((entity.getRemovalCondition() == IEntityRemovalConditions.REMOVE_IN_RETREAT)
                || (entity.getRemovalCondition() == IEntityRemovalConditions.REMOVE_CAPTURED)
                || (entity.getRemovalCondition() == IEntityRemovalConditions.REMOVE_PUSHED)) {
                sanctuary.addElement(entity);
            }
        }

        return sanctuary.elements();
    }

    /**
     * Returns an enumeration of entities that were utterly destroyed
     */
    public Enumeration<Entity> getDevastatedEntities() {
        Vector<Entity> smithereens = new Vector<Entity>();

        for (Entity entity : vOutOfGame) {
            if (entity.getRemovalCondition() == IEntityRemovalConditions.REMOVE_DEVASTATED) {
                smithereens.addElement(entity);
            }
        }

        return smithereens.elements();
    }
    
    /**
     * Returns an enumeration of "carcass" entities, i.e., vehicles with dead
     * crews that are still on the map.
     */
    public Enumeration<Entity> getCarcassEntities() {
        Vector<Entity> carcasses = new Vector<Entity>();
        
        for (Entity entity : entities) {
            if (entity.isCarcass()) {
                carcasses.addElement(entity);
            }
        }
        
        return carcasses.elements();
    }

    /**
     * Return the current number of entities in the game.
     */
    public int getNoOfEntities() {
        return entities.size();
    }

    /**
     * Returns the appropriate target for this game given a type and id
     */
    public Targetable getTarget(int nType, int nID) {
        try {
            switch (nType) {
                case Targetable.TYPE_ENTITY:
                    return getEntity(nID);
                case Targetable.TYPE_HEX_CLEAR:
                case Targetable.TYPE_HEX_IGNITE:
                case Targetable.TYPE_HEX_BOMB:
                case Targetable.TYPE_MINEFIELD_DELIVER:
                case Targetable.TYPE_FLARE_DELIVER:
                case Targetable.TYPE_HEX_EXTINGUISH:
                case Targetable.TYPE_HEX_ARTILLERY:
                case Targetable.TYPE_HEX_SCREEN:
                case Targetable.TYPE_HEX_AERO_BOMB:
                case Targetable.TYPE_HEX_TAG:
                    return new HexTarget(HexTarget.idToCoords(nID), board,
                                         nType);
                case Targetable.TYPE_FUEL_TANK:
                case Targetable.TYPE_FUEL_TANK_IGNITE:
                case Targetable.TYPE_BUILDING:
                case Targetable.TYPE_BLDG_IGNITE:
                case Targetable.TYPE_BLDG_TAG:
                    return new BuildingTarget(BuildingTarget.idToCoords(nID),
                                              board, nType);
                case Targetable.TYPE_MINEFIELD_CLEAR:
                    return new MinefieldTarget(MinefieldTarget.idToCoords(nID),
                                               board);
                case Targetable.TYPE_INARC_POD:
                    return INarcPod.idToInstance(nID);
                default:
                    return null;
            }
        } catch (IllegalArgumentException t) {
            return null;
        }
    }

    /**
     * Returns the entity with the given id number, if any.
     */

    public Entity getEntity(int id) {
        return entityIds.get(Integer.valueOf(id));
    }

    /**
     * looks for an entity by id number even if out of the game
     */
    public Entity getEntityFromAllSources(int id) {
        Entity en = getEntity(id);
        if(null == en) {
            for (Entity entity : vOutOfGame) {
                if(entity.getId() == id) {
                    return entity;
                }
            }
        }
        return en;
    }
    
    public void addEntities(List<Entity> entities) {
        for (int i = 0; i < entities.size(); i++) {
            addEntity(entities.get(i), false);
        }
        // We need to delay calculating BV until all units have been added because
        // C3 network connections will be cleared if the master is not in the game yet.
        entities.forEach(e -> e.setInitialBV(e.calculateBattleValue(false, false)));
        processGameEvent(new GameEntityNewEvent(this, entities));
    }

    public void addEntity(int id, Entity entity) {
        // Disregard the passed id, addEntity(Entity) pulls the id from the
        //  Entity instance.
        addEntity(entity);
    }

    public void addEntity(Entity entity) {
        addEntity(entity, true);
    }

    public synchronized void addEntity(Entity entity, boolean genEvent) {
        entity.setGame(this);
        if (entity instanceof Mech) {
            ((Mech) entity).setBAGrabBars();
            ((Mech) entity).setProtomechClampMounts();
        }
        if (entity instanceof Tank) {
            ((Tank) entity).setBAGrabBars();
            ((Tank) entity).setTrailerHitches();
        }

        // Add magnetic clamp mounts
        if ((entity instanceof Mech) && !entity.isOmni()
                && !entity.hasBattleArmorHandles()) {
            entity.addTransporter(new ClampMountMech());
        } else if ((entity instanceof Tank) && !entity.isOmni()
                && !entity.hasBattleArmorHandles()) {
            entity.addTransporter(new ClampMountTank());
        }

        entity.setGameOptions();
        if (entity.getC3UUIDAsString() == null) { // We don't want to be
            // resetting a UUID that
            // exists already!
            entity.setC3UUID();
        }
        // Add this Entity, ensuring that it's id is unique
        int id = entity.getId();
        if (!entityIds.containsKey(id)) {
            entityIds.put(Integer.valueOf(id), entity);
        } else {
            id = getNextEntityId();
            entity.setId(id);
            entityIds.put(id, entity);
        }
        entities.add(entity);
        updateEntityPositionLookup(entity, null);

        if (id > lastEntityId) {
            lastEntityId = id;
        }

        // And... lets get this straight now.
        if ((entity instanceof Mech)
            && getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)) {
            ((Mech) entity).setAutoEject(true);
            if (((Mech) entity).hasCase()
                || ((Mech) entity).hasCASEIIAnywhere()) {
                ((Mech) entity).setCondEjectAmmo(false);
            } else {
                ((Mech) entity).setCondEjectAmmo(true);
            }
            ((Mech) entity).setCondEjectEngine(true);
            ((Mech) entity).setCondEjectCTDest(true);
            ((Mech) entity).setCondEjectHeadshot(true);
        }

        assert (entities.size() == entityIds.size()) : "Add Entity failed";
        if (genEvent) {
            entity.setInitialBV(entity.calculateBattleValue(false, false));
            processGameEvent(new GameEntityNewEvent(this, entity));
        }
    }

    public void setEntity(int id, Entity entity) {
        setEntity(id, entity, null);
    }

    public synchronized void setEntity(int id, Entity entity, Vector<UnitLocation> movePath) {
        final Entity oldEntity = getEntity(id);
        if (oldEntity == null) {
            addEntity(entity);
        } else {
            entity.setGame(this);
            entities.set(entities.indexOf(oldEntity), entity);
            entityIds.put(id, entity);
            // Get the collection of positions
            HashSet<Coords> oldPositions = oldEntity.getOccupiedCoords();
            // Update position lookup table
            updateEntityPositionLookup(entity, oldPositions);

            // Not sure if this really required
            if (id > lastEntityId) {
                lastEntityId = id;
            }

            processGameEvent(
                    new GameEntityChangeEvent(this, entity, movePath, oldEntity));
        }
        assert (entities.size() == entityIds.size()) : "Set Entity Failed";
    }

    /**
     * @return int containing an unused entity id
     */
    public int getNextEntityId() {
        return lastEntityId + 1;
    }

    /**
     * Returns true if an entity with the specified id number exists in this
     * game.
     */
    public boolean hasEntity(int entityId) {
        return entityIds.containsKey(Integer.valueOf(entityId));
    }

    /**
     * Remove an entity from the master list. If we can't find that entity,
     * (probably due to double-blind) ignore it.
     */
    public synchronized void removeEntity(int id, int condition) {
        // always attempt to remove the entity with this ID from the entities collection
        // as it may have gotten stuck there.
        entities.removeIf(ent -> (ent.getId() == id));
        
        Entity toRemove = getEntity(id);
        if (toRemove == null) {
            // This next statement has been cluttering up double-blind
            // logs for quite a while now. I'm assuming it's no longer
            // useful.
            // System.err.println("Game#removeEntity: could not find entity to
            // remove");
            return;
        }

        entityIds.remove(Integer.valueOf(id));
        removeEntityPositionLookup(toRemove);

        toRemove.setRemovalCondition(condition);

        // do not keep never-joined entities
        if ((vOutOfGame != null)
            && (condition != IEntityRemovalConditions.REMOVE_NEVER_JOINED)) {
            vOutOfGame.addElement(toRemove);
        }

        // We also need to remove it from the list of things to be deployed...
        // we might still be in this list if we never joined the game
        if (deploymentTable.size() > 0) {
            Enumeration<Vector<Entity>> iter = deploymentTable.elements();

            while (iter.hasMoreElements()) {
                Vector<Entity> vec = iter.nextElement();

                for (int i = vec.size() - 1; i >= 0; i--) {
                    Entity en = vec.elementAt(i);

                    if (en.getId() == id) {
                        vec.removeElementAt(i);
                    }
                }
            }
        }
        processGameEvent(new GameEntityRemoveEvent(this, toRemove));
    }

    public void removeEntities(List<Integer> ids, int condition) {
        for (int i = 0; i < ids.size(); i++) {
            removeEntity(ids.get(i), condition);
        }
    }

    /**
     * Resets this game.
     */
    public synchronized void reset() {
        uuid = UUID.randomUUID();

        roundCount = 0;

        entities.clear();
        entityIds.clear();
        entityPosLookup.clear();

        vOutOfGame.removeAllElements();

        turnVector.clear();
        turnIndex = 0;

        resetActions();
        resetCharges();
        resetRams();
        resetPSRs();
        resetArtilleryAttacks();
        resetAttacks();
        // removeMinefields();  Broken and bad!
        clearMinefields();
        removeArtyAutoHitHexes();
        flares.removeAllElements();
        illuminatedPositions.clear();
        clearAllReports();
        smokeCloudList.clear();

        forceVictory = false;
        victoryPlayerId = Player.PLAYER_NONE;
        victoryTeam = Player.TEAM_NONE;
        lastEntityId = 0;
        planetaryConditions = new PlanetaryConditions();
    }

    private void removeArtyAutoHitHexes() {
        Enumeration<IPlayer> iter = getPlayers();
        while (iter.hasMoreElements()) {
            IPlayer player = iter.nextElement();
            player.removeArtyAutoHitHexes();
        }
    }

//    private void removeMinefields() {
//        minefields.clear();
//        vibrabombs.removeAllElements();
//
//        Enumeration<IPlayer> iter = getPlayers();
//        while (iter.hasMoreElements()) {
//            IPlayer player = iter.nextElement();
//            player.removeMinefields();
//        }
//    }

    /**
     * Regenerates the entities by id hashtable by going thru all entities in
     * the Vector
     */
    private void reindexEntities() {
        entityIds.clear();
        lastEntityId = 0;

        if (entities != null) {
            // Add these entities to the game.
            for (Entity entity : entities) {
                final int id = entity.getId();
                entityIds.put(Integer.valueOf(id), entity);

                if (id > lastEntityId) {
                    lastEntityId = id;
                }
            }
            // We need to ensure that each entity has the propery Game reference
            //  however, the entityIds Hashmap must be fully formed before this
            //  is called, since setGame also calls setGame for loaded Entities
            for (Entity entity : entities) {
                entity.setGame(this);
            }
        }
    }

    /**
     * Returns the first entity at the given coordinate, if any. Only returns
     * targetable (non-dead) entities.
     *
     * @param c the coordinates to search at
     */
    public Entity getFirstEntity(Coords c) {
        for (Entity entity : entities) {
            if (c.equals(entity.getPosition()) && entity.isTargetable()) {
                return entity;
            }
        }
        return null;
    }

    /**
     * Returns the first enemy entity at the given coordinate, if any. Only
     * returns targetable (non-dead) entities.
     *
     * @param c             the coordinates to search at
     * @param currentEntity the entity that is firing
     */
    public Entity getFirstEnemyEntity(Coords c, Entity currentEntity) {
        for (Entity entity : entities) {
            if (c.equals(entity.getPosition()) && entity.isTargetable()
                && entity.isEnemyOf(currentEntity)) {
                return entity;
            }
        }
        return null;
    }

    /**
     * Returns an Enumeration of the active entities at the given coordinates.
     */
    public Iterator<Entity> getEntities(Coords c) {
        return getEntities(c, false);
    }

    /**
     * Returns an Enumeration of the active entities at the given coordinates.
     */
    public Iterator<Entity> getEntities(Coords c, boolean ignore) {
        return getEntitiesVector(c,ignore).iterator();
    }

    /**
     * Return a List of Entities at Coords <code>c</code>
     *
     * @param c The coordinates to check
     * @return <code>List<Entity></code>
     */
    public List<Entity> getEntitiesVector(Coords c) {
        return getEntitiesVector(c, false);
    }

    /**
     * Return a List of Entities at Coords <code>c</code>
     *
     * @param c The coordinates to check
     * @param ignore
     *            Flag that determines whether the ability to target is ignored
     * @return <code>List<Entity></code>
     */
    public synchronized List<Entity> getEntitiesVector(Coords c, boolean ignore) {
        //checkPositionCacheConsistency();
        // Make sure the look-up is initialized
        if (entityPosLookup == null
                || (entityPosLookup.size() < 1 && entities.size() > 0)) {
            resetEntityPositionLookup();
        }
        Set<Integer> posEntities = entityPosLookup.get(c);
        List<Entity> vector = new ArrayList<Entity>();
        if (posEntities != null) {
            for (Integer eId : posEntities) {
                Entity e = getEntity(eId);
                
                // if the entity with the given ID doesn't exist, we will update the lookup table
                // and move on
                if(e == null) {
                    posEntities.remove(eId);
                    continue;
                }
                
                if (e.isTargetable() || ignore) {
                    vector.add(e);

                    // Sanity check
                    HashSet<Coords> positions = e.getOccupiedCoords();
                    if (!positions.contains(c)) {
                        System.out.println("Game.getEntitiesVector(1) Error! "
                                + e.getDisplayName() + " is not in " + c + "!");
                    }
                }
            }
        }
        return Collections.unmodifiableList(vector);
    }
    
    /**
     * Convenience function that gets a list of all off-board enemy entities.
     * @param player
     * @return
     */
    public synchronized List<Entity> getAllOffboardEnemyEntities(IPlayer player) {
        List<Entity> vector = new ArrayList<Entity>();
        for(Entity e : entities) {
            if(e.getOwner().isEnemyOf(player) && e.isOffBoard() && !e.isDestroyed() && e.isDeployed()) {
                vector.add(e);
            }
        }
        
        return Collections.unmodifiableList(vector);
    }

    /**
     * Return a Vector of gun emplacements at Coords <code>c</code>
     *
     * @param c The coordinates to check
     * @return <code>Vector<Entity></code>
     */
    public Vector<GunEmplacement> getGunEmplacements(Coords c) {
        Vector<GunEmplacement> vector = new Vector<GunEmplacement>();

        // Only build the list if the coords are on the board.
        if (board.contains(c)) {
            for (Entity entity : getEntitiesVector(c, true)) {
                if (entity.hasETypeFlag(Entity.ETYPE_GUN_EMPLACEMENT)) {
                    vector.addElement((GunEmplacement) entity);
                }
            }
        }

        return vector;
    }
    
    /**
     * Determine if the given set of coordinates has a gun emplacement on the roof of a building.
     * @param c The coordinates to check
     */
    public boolean hasRooftopGunEmplacement(Coords c) {
        Building building = getBoard().getBuildingAt(c);
        if(building == null) {
            return false;
        }
        
        IHex hex = getBoard().getHex(c);
        
        for (Entity entity : getEntitiesVector(c, true)) {
            if (entity.hasETypeFlag(Entity.ETYPE_GUN_EMPLACEMENT) && entity.getElevation() == hex.ceiling()) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Returns a Target for an Accidental Fall From above, or null if no
     * possible target is there
     *
     * @param c      The <code>Coords</code> of the hex in which the accidental
     *               fall from above happens
     * @param ignore The entity who is falling
     * @return The <code>Entity</code> that should be an AFFA target.
     */
    public Entity getAffaTarget(Coords c, Entity ignore) {
        Vector<Entity> vector = new Vector<Entity>();
        if (board.contains(c)) {
            IHex hex = board.getHex(c);
            for (Entity entity : getEntitiesVector(c)) {
                if (entity.isTargetable()
                    && ((entity.getElevation() == 0) // Standing on hex surface 
                            || (entity.getElevation() == -hex.depth())) // Standing on hex floor
                    && (entity.getAltitude() == 0)
                    && !(entity instanceof Infantry) && (entity != ignore)) {
                    vector.addElement(entity);
                }
            }
        }
        if (!vector.isEmpty()) {
            int count = vector.size();
            int random = Compute.randomInt(count);
            return vector.elementAt(random);
        }
        return null;
    }

    /**
     * Returns an <code>Enumeration</code> of the enemy's active entities at the
     * given coordinates.
     *
     * @param c
     *            the <code>Coords</code> of the hex being examined.
     * @param currentEntity
     *            the <code>Entity</code> whose enemies are needed.
     * @return an <code>Enumeration</code> of <code>Entity</code>s at the given
     *         coordinates who are enemies of the given unit.
     */
    public Iterator<Entity> getEnemyEntities(final Coords c,
            final Entity currentEntity) {
        // Use an EntitySelector to avoid walking the entities vector twice.
        return getSelectedEntities(new EntitySelector() {
            private Coords coords = c;
            private Entity friendly = currentEntity;

            public boolean accept(Entity entity) {
                if (coords.equals(entity.getPosition())
                        && entity.isTargetable() && entity.isEnemyOf(friendly)) {
                    return true;
                }
                return false;
            }
        });
    }
    
    /**
     * Returns an <code>Enumeration</code> of all active enemy entities.
     *
     * @param currentEntity
     *            the <code>Entity</code> whose enemies are needed.
     * @return an <code>Enumeration</code> of <code>Entity</code>s at the given
     *         coordinates who are enemies of the given unit.
     */
    public Iterator<Entity> getAllEnemyEntities(final Entity currentEntity) {
    	return getSelectedEntities(new EntitySelector() {
    		private Entity friendly = currentEntity;
    		
    		public boolean accept(Entity entity) {
    			return entity.isTargetable() && entity.isEnemyOf(friendly);
    		}
    	});
    }

    /**
     * Returns an <code>Enumeration</code> of friendly active entities at the
     * given coordinates.
     *
     * @param c
     *            the <code>Coords</code> of the hex being examined.
     * @param currentEntity
     *            the <code>Entity</code> whose friends are needed.
     * @return an <code>Enumeration</code> of <code>Entity</code>s at the given
     *         coordinates who are friends of the given unit.
     */
    public Iterator<Entity> getFriendlyEntities(final Coords c,
            final Entity currentEntity) {
        // Use an EntitySelector to avoid walking the entities vector twice.
        return getSelectedEntities(new EntitySelector() {
            private Coords coords = c;
            private Entity friendly = currentEntity;

            public boolean accept(Entity entity) {
                if (coords.equals(entity.getPosition())
                        && entity.isTargetable() && !entity.isEnemyOf(friendly)) {
                    return true;
                }
                return false;
            }
        });
    }

    /**
     * Moves an entity into the graveyard so it stops getting sent out every
     * phase.
     */
    public void moveToGraveyard(int id) {
        removeEntity(id, IEntityRemovalConditions.REMOVE_SALVAGEABLE);
    }

    /**
     * See if the <code>Entity</code> with the given ID is out of the game.
     *
     * @param id - the ID of the <code>Entity</code> to be checked.
     * @return <code>true</code> if the <code>Entity</code> is in the graveyard,
     * <code>false</code> otherwise.
     */
    public boolean isOutOfGame(int id) {
        for (Entity entity : vOutOfGame) {
            if (entity.getId() == id) {
                return true;
            }
        }

        return false;
    }

    /**
     * See if the <code>Entity</code> is out of the game.
     *
     * @param entity - the <code>Entity</code> to be checked.
     * @return <code>true</code> if the <code>Entity</code> is in the graveyard,
     * <code>false</code> otherwise.
     */
    public boolean isOutOfGame(Entity entity) {
        return isOutOfGame(entity.getId());
    }

    /**
     * Returns the first entity that can act in the present turn, or null if
     * none can.
     */
    public Entity getFirstEntity() {
        return getFirstEntity(getTurn());
    }

    /**
     * Returns the first entity that can act in the specified turn, or null if
     * none can.33
     */
    public Entity getFirstEntity(GameTurn turn) {
        return getEntity(getFirstEntityNum(turn));
    }

    /**
     * Returns the id of the first entity that can act in the current turn, or
     * -1 if none can.
     */
    public int getFirstEntityNum() {
        return getFirstEntityNum(getTurn());
    }

    /**
     * Returns the id of the first entity that can act in the specified turn, or
     * -1 if none can.
     */
    public int getFirstEntityNum(GameTurn turn) {
        if (turn == null) {
            return -1;
        }
        for (Entity entity : entities) {
            if (turn.isValidEntity(entity, this)) {
                return entity.getId();
            }
        }
        return -1;
    }

    /**
     * Returns the next selectable entity that can act this turn, or null if
     * none can.
     *
     * @param start the index number to start at (not an Entity Id)
     */
    public Entity getNextEntity(int start) {
        if (entities.size() == 0) {
            return null;
        }
        start = start % entities.size();
        int entityId = entities.get(start).getId();
        return getEntity(getNextEntityNum(getTurn(), entityId));
    }

    /**
     * Returns the entity id of the next entity that can move during the
     * specified
     *
     * @param turn  the turn to use
     * @param start the entity id to start at
     */
    public int getNextEntityNum(GameTurn turn, int start) {
        // If we don't have a turn, return ENTITY_NONE
        if (turn == null) {
            return Entity.NONE;
        }
        boolean hasLooped = false;
        int i = (entities.indexOf(entityIds.get(start)) + 1) % entities.size();
        if (i == -1) {
            //This means we were given an invalid entity ID, punt
            return Entity.NONE;
        }
        int startingIndex = i;
        while (!((hasLooped == true) && (i == startingIndex))) {
            final Entity entity = entities.get(i);
            if (turn.isValidEntity(entity, this)) {
                return entity.getId();
            }
            i++;
            if (i == entities.size()) {
                i = 0;
                hasLooped = true;
            }
        }
        // return getFirstEntityNum(turn);
        return Entity.NONE;
    }

    /**
     * Returns the entity id of the previous entity that can move during the
     * specified
     *
     * @param turn  the turn to use
     * @param start the entity id to start at
     */
    public int getPrevEntityNum(GameTurn turn, int start) {
        boolean hasLooped = false;
        int i = (entities.indexOf(entityIds.get(start)) - 1) % entities.size();
        if (i == -2) {
            //This means we were given an invalid entity ID, punt
            return -1;
        }
        if (i == -1) {
            //This means we were given an invalid entity ID, punt
            i = entities.size() - 1;
        }
        int startingIndex = i;
        while (!((hasLooped == true) && (i == startingIndex))) {
            final Entity entity = entities.get(i);
            if (turn.isValidEntity(entity, this)) {
                return entity.getId();
            }
            i--;
            if (i < 0) {
                i = entities.size() - 1;
                hasLooped = true;
            }
        }
        // return getFirstEntityNum(turn);
        return -1;
    }

    public int getFirstDeployableEntityNum(GameTurn turn) {
        // Repeat the logic from getFirstEntityNum.
        if (turn == null) {
            return -1;
        }
        for (Entity entity : entities) {
            if (turn.isValidEntity(entity, this)
                && entity.shouldDeploy(getRoundCount())) {
                return entity.getId();
            }
        }
        return -1;
    }

    public int getNextDeployableEntityNum(GameTurn turn, int start) {
        if (start >= 0) {
            for (int i = start; i < entities.size(); i++) {
                final Entity entity = entities.get(i);
                if (turn.isValidEntity(entity, this)
                    && entity.shouldDeploy(getRoundCount())) {
                    return entity.getId();
                }
            }
        }
        return getFirstDeployableEntityNum(turn);
    }

    /**
     * Get the entities for the player.
     *
     * @param player - the <code>Player</code> whose entities are required.
     * @param hide   - should fighters loaded into squadrons be excluded?
     * @return a <code>Vector</code> of <code>Entity</code>s.
     */
    public ArrayList<Entity> getPlayerEntities(IPlayer player, boolean hide) {
        ArrayList<Entity> output = new ArrayList<Entity>();
        for (Entity entity : entities) {
            if (entity.isPartOfFighterSquadron() && hide) {
                continue;
            }
            if (player.equals(entity.getOwner())) {
                output.add(entity);
            }
        }
        return output;
    }

    /**
     * Get the entities for the player.
     *
     * @param player - the <code>Player</code> whose entities are required.
     * @param hide   - should fighters loaded into squadrons be excluded from this list?
     * @return a <code>Vector</code> of <code>Entity</code>s.
     */
    public ArrayList<Integer> getPlayerEntityIds(IPlayer player, boolean hide) {
        ArrayList<Integer> output = new ArrayList<Integer>();
        for (Entity entity : entities) {
            if (entity.isPartOfFighterSquadron() && hide) {
                continue;
            }
            if (player.equals(entity.getOwner())) {
                output.add(entity.getId());
            }
        }
        return output;
    }

    /**
     * Determines if the indicated entity is stranded on a transport that can't
     * move.
     * <p/>
     * According to <a href=
     * "http://www.classicbattletech.com/w3t/showflat.php?Cat=&Board=ask&Number=555466&page=2&view=collapsed&sb=5&o=0&fpart="
     * > Randall Bills</a>, the "minimum move" rule allow stranded units to
     * dismount at the start of the turn.
     *
     * @param entity the <code>Entity</code> that may be stranded
     * @return <code>true</code> if the entity is stranded <code>false</code>
     * otherwise.
     */
    public boolean isEntityStranded(Entity entity) {

        // Is the entity being transported?
        final int transportId = entity.getTransportId();
        Entity transport = getEntity(transportId);
        if ((Entity.NONE != transportId) && (null != transport)) {

            // aero units don't count here
            if (transport instanceof Aero) {
                return false;
            }

            // Can that transport unload the unit?
            if (transport.isImmobile() || (0 == transport.getWalkMP())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the number of remaining selectable infantry owned by a player.
     */
    public int getInfantryLeft(int playerId) {
        IPlayer player = getPlayer(playerId);
        int remaining = 0;

        for (Entity entity : entities) {
            if (player.equals(entity.getOwner())
                && entity.isSelectableThisTurn()
                && (entity instanceof Infantry)) {
                remaining++;
            }
        }

        return remaining;
    }

    /**
     * Returns the number of remaining selectable Protomechs owned by a player.
     */
    public int getProtomechsLeft(int playerId) {
        IPlayer player = getPlayer(playerId);
        int remaining = 0;

        for (Entity entity : entities) {
            if (player.equals(entity.getOwner())
                && entity.isSelectableThisTurn()
                && (entity instanceof Protomech)) {
                remaining++;
            }
        }

        return remaining;
    }

    /**
     * Returns the number of Vehicles that <code>playerId</code> has not moved
     * yet this turn.
     *
     * @param playerId
     * @return number of vehicles <code>playerId</code> has not moved yet this
     * turn
     */
    public int getVehiclesLeft(int playerId) {
        IPlayer player = getPlayer(playerId);
        int remaining = 0;

        for (Entity entity : entities) {
            if (player.equals(entity.getOwner())
                && entity.isSelectableThisTurn()
                && (entity instanceof Tank)) {
                remaining++;
            }
        }

        return remaining;
    }

    /**
     * Returns the number of Mechs that <code>playerId</code> has not moved
     * yet this turn.
     *
     * @param playerId
     * @return number of vehicles <code>playerId</code> has not moved yet this
     * turn
     */
    public int getMechsLeft(int playerId) {
        IPlayer player = getPlayer(playerId);
        int remaining = 0;

        for (Entity entity : entities) {
            if (player.equals(entity.getOwner())
                && entity.isSelectableThisTurn()
                && (entity instanceof Mech)) {
                remaining++;
            }
        }

        return remaining;
    }

    /**
     * Removes the first turn found that the specified entity can move in. Used
     * when a turn is played out of order
     */
    public GameTurn removeFirstTurnFor(Entity entity) {
        assert (phase != Phase.PHASE_MOVEMENT); // special move multi cases
        // ignored
        for (int i = turnIndex; i < turnVector.size(); i++) {
            GameTurn turn = turnVector.elementAt(i);
            if (turn.isValidEntity(entity, this)) {
                turnVector.removeElementAt(i);
                return turn;
            }
        }
        return null;
    }

    /**
     * Removes the last, next turn found that the specified entity can move in.
     * Used when, say, an entity dies mid-phase.
     */
    public void removeTurnFor(Entity entity) {
        if (turnVector.size() == 0) {
            return;
        }
        // If the game option "move multiple infantry per mech" is selected,
        // then we might not need to remove a turn at all.
        // A turn only needs to be removed when going from 4 inf (2 turns) to
        // 3 inf (1 turn)
        if (getOptions().booleanOption(OptionsConstants.INIT_INF_MOVE_MULTI)
            && (entity instanceof Infantry)
            && (phase == Phase.PHASE_MOVEMENT)) {
            if ((getInfantryLeft(entity.getOwnerId()) % getOptions().intOption(
                    OptionsConstants.INIT_INF_PROTO_MOVE_MULTI)) != 1) {
                // exception, if the _next_ turn is an infantry turn, remove
                // that
                // contrived, but may come up e.g. one inf accidently kills
                // another
                if (hasMoreTurns()) {
                    GameTurn nextTurn = turnVector.elementAt(turnIndex + 1);
                    if (nextTurn instanceof GameTurn.EntityClassTurn) {
                        GameTurn.EntityClassTurn ect =
                                (GameTurn.EntityClassTurn) nextTurn;
                        if (ect.isValidClass(GameTurn.CLASS_INFANTRY)
                            && !ect.isValidClass(~GameTurn.CLASS_INFANTRY)) {
                            turnVector.removeElementAt(turnIndex + 1);
                        }
                    }
                }
                return;
            }
        }
        // Same thing but for protos
        if (getOptions().booleanOption(OptionsConstants.INIT_PROTOS_MOVE_MULTI)
            && (entity instanceof Protomech)
            && (phase == Phase.PHASE_MOVEMENT)) {
            if ((getProtomechsLeft(entity.getOwnerId()) % getOptions()
                    .intOption(OptionsConstants.INIT_INF_PROTO_MOVE_MULTI)) != 1) {
                // exception, if the _next_ turn is an protomek turn, remove
                // that
                // contrived, but may come up e.g. one inf accidently kills
                // another
                if (hasMoreTurns()) {
                    GameTurn nextTurn = turnVector.elementAt(turnIndex + 1);
                    if (nextTurn instanceof GameTurn.EntityClassTurn) {
                        GameTurn.EntityClassTurn ect =
                                (GameTurn.EntityClassTurn) nextTurn;
                        if (ect.isValidClass(GameTurn.CLASS_PROTOMECH)
                            && !ect.isValidClass(~GameTurn.CLASS_PROTOMECH)) {
                            turnVector.removeElementAt(turnIndex + 1);
                        }
                    }
                }
                return;
            }
        }

        // Same thing but for vehicles
        if (getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_VEHICLE_LANCE_MOVEMENT)
            && (entity instanceof Tank) && (phase == Phase.PHASE_MOVEMENT)) {
            if ((getVehiclesLeft(entity.getOwnerId()) % getOptions()
                    .intOption(OptionsConstants.ADVGRNDMOV_VEHICLE_LANCE_MOVEMENT_NUMBER)) != 1) {
                // exception, if the _next_ turn is a tank turn, remove that
                // contrived, but may come up e.g. one tank accidently kills
                // another
                if (hasMoreTurns()) {
                    GameTurn nextTurn = turnVector.elementAt(turnIndex + 1);
                    if (nextTurn instanceof GameTurn.EntityClassTurn) {
                        GameTurn.EntityClassTurn ect =
                                (GameTurn.EntityClassTurn) nextTurn;
                        if (ect.isValidClass(GameTurn.CLASS_TANK)
                            && !ect.isValidClass(~GameTurn.CLASS_TANK)) {
                            turnVector.removeElementAt(turnIndex + 1);
                        }
                    }
                }
                return;
            }
        }

        // Same thing but for meks
        if (getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_MEK_LANCE_MOVEMENT)
            && (entity instanceof Mech) && (phase == Phase.PHASE_MOVEMENT)) {
            if ((getMechsLeft(entity.getOwnerId()) % getOptions()
                    .intOption(OptionsConstants.ADVGRNDMOV_MEK_LANCE_MOVEMENT_NUMBER)) != 1) {
                // exception, if the _next_ turn is a mech turn, remove that
                // contrived, but may come up e.g. one mech accidently kills
                // another
                if (hasMoreTurns()) {
                    GameTurn nextTurn = turnVector.elementAt(turnIndex + 1);
                    if (nextTurn instanceof GameTurn.EntityClassTurn) {
                        GameTurn.EntityClassTurn ect =
                                (GameTurn.EntityClassTurn) nextTurn;
                        if (ect.isValidClass(GameTurn.CLASS_MECH)
                            && !ect.isValidClass(~GameTurn.CLASS_MECH)) {
                            turnVector.removeElementAt(turnIndex + 1);
                        }
                    }
                }
                return;
            }
        }


        boolean useInfantryMoveLaterCheck = true;
        // If we have the "infantry move later" or "protos move later" optional
        //  rules, then we may be removing an infantry unit that would be
        //  considered invalid unless we don't consider the extra validity
        //  checks.
        if ((getOptions().booleanOption(OptionsConstants.INIT_INF_MOVE_LATER) &&
             (entity instanceof Infantry)) ||
            (getOptions().booleanOption(OptionsConstants.INIT_PROTOS_MOVE_LATER) &&
             (entity instanceof Protomech))) {
            useInfantryMoveLaterCheck = false;
        }

        for (int i = turnVector.size() - 1; i >= turnIndex; i--) {
            GameTurn turn = turnVector.elementAt(i);

            if (turn.isValidEntity(entity, this, useInfantryMoveLaterCheck)) {
                turnVector.removeElementAt(i);
                break;
            }
        }
    }
    
    public int removeSpecificEntityTurnsFor(Entity entity) {
        List<GameTurn> turnsToRemove = new ArrayList<GameTurn>();
        
        for (GameTurn turn : turnVector) {
            if (turn instanceof SpecificEntityTurn) {
                int turnOwner = ((SpecificEntityTurn) turn).getEntityNum();
                if (entity.getId() == turnOwner) {
                    turnsToRemove.add(turn);
                }
            }
        }
        turnVector.removeAll(turnsToRemove);
        return turnsToRemove.size();
    }

    /**
     * Check each player for the presence of a Battle Armor squad equipped with
     * a Magnetic Clamp. If one unit is found, update that player's units to
     * allow the squad to be transported.
     * <p/>
     * This method should be called </b>*ONCE*</b> per game, after all units for
     * all players have been loaded.
     *
     * @return <code>true</code> if a unit was updated, <code>false</code> if no
     *         player has a Battle Armor squad equipped with a Magnetic Clamp.
     */
    /* Taharqa: I am removing this function and instead I am simply adding clamp mounts to all
     * non omni/ none BA handled mechs in the game.addEntity routine - It should not be too much memory to
     * do this and it allows us to load these units in the lobby
    public boolean checkForMagneticClamp() {

        // Declare local variables.
        Player player = null;
        Entity unit = null;
        boolean result;
        Hashtable<Player, Boolean> playerFlags = null;

        // Assume that we don't need new transporters.
        result = false;

        // Create a map of flags for the players.
        playerFlags = new Hashtable<Player, Boolean>(getNoOfPlayers());

        // Walk through the game's entities.
        for (Enumeration<Entity> i = entities.elements(); i.hasMoreElements();) {

            // Is the next unit a Battle Armor squad?
            unit = i.nextElement();
            if (unit instanceof BattleArmor) {

                if (unit.countWorkingMisc(MiscType.F_MAGNETIC_CLAMP) > 0) {
                    // The unit's player needs new transporters.
                    result = true;
                    playerFlags.put(unit.getOwner(), Boolean.TRUE);

                }

            } // End unit-is-BattleArmor

        } // Handle the next entity.

        // Do we need to add any Magnetic Clamp transporters?
        if (result) {

            // Walk through the game's entities again.
            for (Enumeration<Entity> i = entities.elements(); i
                    .hasMoreElements();) {

                // Get this unit's player.
                unit = i.nextElement();
                player = unit.getOwner();

                // Does this player need updated transporters?
                if (Boolean.TRUE.equals(playerFlags.get(player))) {

                    // Add the appropriate transporter to the unit.
                    if (!unit.isOmni() && !unit.hasBattleArmorHandles() && (unit instanceof Mech)) {
                        unit.addTransporter(new ClampMountMech());
                    } else if (!unit.isOmni() && !unit.hasBattleArmorHandles() && (unit instanceof Tank)
                            && !(unit instanceof VTOL)) {
                        unit.addTransporter(new ClampMountTank());
                    }

                }
            } // End player-needs-transports

        } // Handle the next unit.

        // Return the result.
        return result;

    } // End private boolean checkForMagneticClamp()
     */

    /**
     * Adds the specified action to the actions list for this phase.
     */
    public void addAction(EntityAction ea) {
        actions.addElement(ea);
        processGameEvent(new GameNewActionEvent(this, ea));
    }

    public void setArtilleryVector(Vector<ArtilleryAttackAction> v) {
        offboardArtilleryAttacks = v;
        processGameEvent(new GameBoardChangeEvent(this));
    }

    public void resetArtilleryAttacks() {
        offboardArtilleryAttacks.removeAllElements();
    }

    public Enumeration<ArtilleryAttackAction> getArtilleryAttacks() {
        return offboardArtilleryAttacks.elements();
    }

    public int getArtillerySize() {
        return offboardArtilleryAttacks.size();
    }

    /**
     * Returns an Enumeration of actions scheduled for this phase.
     */
    public Enumeration<EntityAction> getActions() {
        return actions.elements();
    }

    /**
     * Resets the actions list.
     */
    public void resetActions() {
        actions.removeAllElements();
    }

    /**
     * Removes all actions by the specified entity
     */
    public void removeActionsFor(int entityId) {
        // or rather, only keeps actions NOT by that entity
        Vector<EntityAction> toKeep = new Vector<EntityAction>(actions.size());
        for (EntityAction ea : actions) {
            if (ea.getEntityId() != entityId) {
                toKeep.addElement(ea);
            }
        }
        actions = toKeep;
    }

    /**
     * Remove a specified action
     *
     * @param o The action to remove.
     */
    public void removeAction(Object o) {
        actions.removeElement(o);
    }

    public int actionsSize() {
        return actions.size();
    }

    /**
     * Returns the actions vector. Do not use to modify the actions; I will be
     * angry. >:[ Used for sending all actions to the client.
     */
    public List<EntityAction> getActionsVector() {
        return Collections.unmodifiableList(actions);
    }

    public void addInitiativeRerollRequest(Team t) {
        initiativeRerollRequests.addElement(t);
    }

    public void rollInitAndResolveTies() {
        if (getOptions().booleanOption(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE)) {
            Vector<TurnOrdered> vRerolls = new Vector<TurnOrdered>();
            for (int i = 0; i < entities.size(); i++) {
                Entity e = entities.get(i);
                if (initiativeRerollRequests.contains(getTeamForPlayer(e.getOwner()))) {
                    vRerolls.add(e);
                }
            }
            TurnOrdered.rollInitAndResolveTies(getEntitiesVector(), vRerolls, false);
        } else {
            TurnOrdered.rollInitAndResolveTies(teams, initiativeRerollRequests,
                    getOptions().booleanOption(OptionsConstants.INIT_INITIATIVE_STREAK_COMPENSATION));
        }
        initiativeRerollRequests.removeAllElements();

    }
    
    public void handleInitiativeCompensation() {
        if (getOptions().booleanOption(OptionsConstants.INIT_INITIATIVE_STREAK_COMPENSATION)) {
            TurnOrdered.resetInitiativeCompensation(teams, getOptions().booleanOption(OptionsConstants.INIT_INITIATIVE_STREAK_COMPENSATION));
        }
    }

    public int getNoOfInitiativeRerollRequests() {
        return initiativeRerollRequests.size();
    }

    /**
     * Adds a pending displacement attack to the list for this phase.
     */
    public void addCharge(AttackAction ea) {
        pendingCharges.addElement(ea);
        processGameEvent(new GameNewActionEvent(this, ea));
    }

    /**
     * Returns an Enumeration of displacement attacks scheduled for the end of
     * the physical phase.
     */
    public Enumeration<AttackAction> getCharges() {
        return pendingCharges.elements();
    }

    /**
     * Resets the pending charges list.
     */
    public void resetCharges() {
        pendingCharges.removeAllElements();
    }

    /**
     * Returns the charges vector. Do not modify. >:[ Used for sending all
     * charges to the client.
     */
    public List<AttackAction> getChargesVector() {
        return Collections.unmodifiableList(pendingCharges);
    }

    /**
     * Adds a pending ramming attack to the list for this phase.
     */
    public void addRam(AttackAction ea) {
        pendingRams.addElement(ea);
        processGameEvent(new GameNewActionEvent(this, ea));
    }

    /**
     * Returns an Enumeration of ramming attacks scheduled for the end of the
     * physical phase.
     */
    public Enumeration<AttackAction> getRams() {
        return pendingRams.elements();
    }

    /**
     * Resets the pending rams list.
     */
    public void resetRams() {
        pendingRams.removeAllElements();
    }

    /**
     * Returns the rams vector. Do not modify. >:[ Used for sending all charges
     * to the client.
     */
    public List<AttackAction> getRamsVector() {
        return Collections.unmodifiableList(pendingRams);
    }

    /**
     * Adds a pending ramming attack to the list for this phase.
     */
    public void addTeleMissileAttack(AttackAction ea) {
        pendingTeleMissileAttacks.addElement(ea);
        processGameEvent(new GameNewActionEvent(this, ea));
    }

    /**
     * Returns an Enumeration of ramming attacks scheduled for the end of the
     * physical phase.
     */
    public Enumeration<AttackAction> getTeleMissileAttacks() {
        return pendingTeleMissileAttacks.elements();
    }

    /**
     * Resets the pending rams list.
     */
    public void resetTeleMissileAttacks() {
        pendingTeleMissileAttacks.removeAllElements();
    }

    /**
     * Returns the rams vector. Do not modify. >:[ Used for sending all charges
     * to the client.
     */
    public List<AttackAction> getTeleMissileAttacksVector() {
        return Collections.unmodifiableList(pendingTeleMissileAttacks);
    }

    /**
     * Adds a pending PSR to the list for this phase.
     */
    public void addPSR(PilotingRollData psr) {
        pilotRolls.addElement(psr);
    }

    /**
     * Returns an Enumeration of pending PSRs.
     */
    public Enumeration<PilotingRollData> getPSRs() {
        return pilotRolls.elements();
    }

    /**
     * Adds a pending extreme Gravity PSR to the list for this phase.
     */
    public void addExtremeGravityPSR(PilotingRollData psr) {
        extremeGravityRolls.addElement(psr);
    }

    /**
     * Returns an Enumeration of pending extreme GravityPSRs.
     */
    public Enumeration<PilotingRollData> getExtremeGravityPSRs() {
        return extremeGravityRolls.elements();
    }

    /**
     * Resets the PSR list for a given entity.
     */
    public void resetPSRs(Entity entity) {
        PilotingRollData roll;
        Vector<Integer> rollsToRemove = new Vector<Integer>();
        int i = 0;

        // first, find all the rolls belonging to the target entity
        for (i = 0; i < pilotRolls.size(); i++) {
            roll = pilotRolls.elementAt(i);
            if (roll.getEntityId() == entity.getId()) {
                rollsToRemove.addElement(Integer.valueOf(i));
            }
        }

        // now, clear them out
        for (i = rollsToRemove.size() - 1; i > -1; i--) {
            pilotRolls.removeElementAt(rollsToRemove.elementAt(i).intValue());
        }
    }

    /**
     * Resets the extreme Gravity PSR list.
     */
    public void resetExtremeGravityPSRs() {
        extremeGravityRolls.removeAllElements();
    }

    /**
     * Resets the extreme Gravity PSR list for a given entity.
     */
    public void resetExtremeGravityPSRs(Entity entity) {
        PilotingRollData roll;
        Vector<Integer> rollsToRemove = new Vector<Integer>();
        int i = 0;

        // first, find all the rolls belonging to the target entity
        for (i = 0; i < extremeGravityRolls.size(); i++) {
            roll = extremeGravityRolls.elementAt(i);
            if (roll.getEntityId() == entity.getId()) {
                rollsToRemove.addElement(Integer.valueOf(i));
            }
        }

        // now, clear them out
        for (i = rollsToRemove.size() - 1; i > -1; i--) {
            extremeGravityRolls.removeElementAt(rollsToRemove.elementAt(i)
                    .intValue());
        }
    }

    /**
     * Resets the PSR list.
     */
    public void resetPSRs() {
        pilotRolls.removeAllElements();
    }

    /**
     * add an AttackHandler to the attacks list
     *
     * @param ah - The <code>AttackHandler</code> to add
     */
    public void addAttack(AttackHandler ah) {
        attacks.add(ah);
    }

    /**
     * remove an AttackHandler from the attacks list
     *
     * @param ah - The <code>AttackHandler</code> to remove
     */
    public void removeAttack(AttackHandler ah) {
        attacks.removeElement(ah);
    }

    /**
     * get the attacks
     *
     * @return a <code>Enumeration</code> of all <code>AttackHandler</code>s
     */
    public Enumeration<AttackHandler> getAttacks() {
        return attacks.elements();
    }

    /**
     * get the attacks vector
     *
     * @return the <code>Vector</code> containing the attacks
     */
    public Vector<AttackHandler> getAttacksVector() {
        return attacks;
    }

    /**
     * reset the attacks vector
     */
    public void resetAttacks() {
        attacks = new Vector<AttackHandler>();
    }

    /**
     * set the attacks vector
     *
     * @param v - the <code>Vector</code> that should be the new attacks
     *          vector
     */
    public void setAttacksVector(Vector<AttackHandler> v) {
        attacks = v;
    }

    /**
     * Getter for property roundCount.
     *
     * @return Value of property roundCount.
     */
    public int getRoundCount() {
        return roundCount;
    }

    public void setRoundCount(int roundCount) {
        this.roundCount = roundCount;
    }

    /**
     * Increments the round counter
     */
    public void incrementRoundCount() {
        roundCount++;
    }

    /**
     * Getter for property forceVictory.
     *
     * @return Value of property forceVictory.
     */
    public boolean isForceVictory() {
        return forceVictory;
    }

    /**
     * Setter for property forceVictory.
     *
     * @param forceVictory New value of property forceVictory.
     */
    public void setForceVictory(boolean forceVictory) {
        this.forceVictory = forceVictory;
    }

    public void addReports(Vector<Report> v) {
        if (v.size() == 0) {
            return;
        }
        gameReports.add(roundCount, v);
    }

    public Vector<Report> getReports(int r) {
        return gameReports.get(r);
    }

    public Vector<Vector<Report>> getAllReports() {
        return gameReports.get();
    }

    public void setAllReports(Vector<Vector<Report>> v) {
        gameReports.set(v);
    }

    public void clearAllReports() {
        gameReports.clear();
    }

    public void end(int winner, int winnerTeam) {
        setVictoryPlayerId(winner);
        setVictoryTeam(winnerTeam);
        processGameEvent(new GameEndEvent(this));

    }

    /**
     * Getter for property victoryPlayerId.
     *
     * @return Value of property victoryPlayerId.
     */
    public int getVictoryPlayerId() {
        return victoryPlayerId;
    }

    /**
     * Setter for property victoryPlayerId.
     *
     * @param victoryPlayerId New value of property victoryPlayerId.
     */
    public void setVictoryPlayerId(int victoryPlayerId) {
        this.victoryPlayerId = victoryPlayerId;
    }

    /**
     * Getter for property victoryTeam.
     *
     * @return Value of property victoryTeam.
     */
    public int getVictoryTeam() {
        return victoryTeam;
    }

    /**
     * Setter for property victoryTeam.
     *
     * @param victoryTeam New value of property victoryTeam.
     */
    public void setVictoryTeam(int victoryTeam) {
        this.victoryTeam = victoryTeam;
    }

    /**
     * Returns true if the specified player is either the victor, or is on the
     * winning team. Best to call during PHASE_VICTORY.
     */
    public boolean isPlayerVictor(IPlayer player) {
        if (player.getTeam() == IPlayer.TEAM_NONE) {
            return player.getId() == victoryPlayerId;
        }
        return player.getTeam() == victoryTeam;
    }

    public HashMap<String, Object> getVictoryContext() {
        return victoryContext;
    }

    public void setVictoryContext(HashMap<String, Object> ctx) {
        victoryContext = ctx;
    }

    /**
     * Shortcut to isPlayerVictor(Player player)
     */
    public boolean isPlayerVictor(int playerId) {
        return isPlayerVictor(getPlayer(playerId));
    }

    /**
     * Get all <code>Entity</code>s that pass the given selection criteria.
     *
     * @param selector the <code>EntitySelector</code> that implements test that an
     *                 entity must pass to be included. This value may be
     *                 <code>null</code> (in which case all entities in the game will
     *                 be returned).
     * @return an <code>Enumeration</code> of all entities that the selector
     * accepts. This value will not be <code>null</code> but it may be
     * empty.
     */
    public Iterator<Entity> getSelectedEntities(EntitySelector selector) {
        Iterator<Entity> retVal;

        // If no selector was supplied, return all entities.
        if (null == selector) {
            retVal = this.getEntities();
        }

        // Otherwise, return an anonymous Enumeration
        // that selects entities in this game.
        else {
            final EntitySelector entry = selector;
            retVal = new Iterator<Entity>() {
                private EntitySelector entitySelector = entry;
                private Entity current = null;
                private Iterator<Entity> iter = getEntities();

                // Do any more entities meet the selection criteria?
                public boolean hasNext() {
                    // See if we have a pre-approved entity.
                    if (null == current) {

                        // Find the first acceptable entity
                        while ((null == current) && iter.hasNext()) {
                            current = iter.next();
                            if (!entitySelector.accept(current)) {
                                current = null;
                            }
                        }
                    }
                    return (null != current);
                }

                // Get the next entity that meets the selection criteria.
                public Entity next() {
                    // Pre-approve an entity.
                    if (!hasNext()) {
                        return null;
                    }

                    // Use the pre-approved entity, and null out our reference.
                    Entity next = current;
                    current = null;
                    return next;
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };

        } // End use-selector

        // Return the selected entities.
        return retVal;

    }

    /**
     * Count all <code>Entity</code>s that pass the given selection criteria.
     *
     * @param selector the <code>EntitySelector</code> that implements test that an
     *                 entity must pass to be included. This value may be
     *                 <code>null</code> (in which case the count of all entities in
     *                 the game will be returned).
     * @return the <code>int</code> count of all entities that the selector
     * accepts. This value will not be <code>null</code> but it may be
     * empty.
     */
    public int getSelectedEntityCount(EntitySelector selector) {
        int retVal = 0;

        // If no selector was supplied, return the count of all game entities.
        if (null == selector) {
            retVal = getNoOfEntities();
        }

        // Otherwise, count the entities that meet the selection criteria.
        else {
            Iterator<Entity> iter = this.getEntities();
            while (iter.hasNext()) {
                if (selector.accept(iter.next())) {
                    retVal++;
                }
            }

        } // End use-selector

        // Return the number of selected entities.
        return retVal;
    }

    /**
     * Get all out-of-game <code>Entity</code>s that pass the given selection
     * criteria.
     *
     * @param selector the <code>EntitySelector</code> that implements test that an
     *                 entity must pass to be included. This value may be
     *                 <code>null</code> (in which case all entities in the game will
     *                 be returned).
     * @return an <code>Enumeration</code> of all entities that the selector
     * accepts. This value will not be <code>null</code> but it may be
     * empty.
     */
    public Enumeration<Entity> getSelectedOutOfGameEntities(
            EntitySelector selector) {
        Enumeration<Entity> retVal;

        // If no selector was supplied, return all entities.
        if (null == selector) {
            retVal = vOutOfGame.elements();
        }

        // Otherwise, return an anonymous Enumeration
        // that selects entities in this game.
        else {
            final EntitySelector entry = selector;
            retVal = new Enumeration<Entity>() {
                private EntitySelector entitySelector = entry;
                private Entity current = null;
                private Enumeration<Entity> iter = vOutOfGame.elements();

                // Do any more entities meet the selection criteria?
                public boolean hasMoreElements() {
                    // See if we have a pre-approved entity.
                    if (null == current) {

                        // Find the first acceptable entity
                        while ((null == current) && iter.hasMoreElements()) {
                            current = iter.nextElement();
                            if (!entitySelector.accept(current)) {
                                current = null;
                            }
                        }
                    }
                    return (null != current);
                }

                // Get the next entity that meets the selection criteria.
                public Entity nextElement() {
                    // Pre-approve an entity.
                    if (!hasMoreElements()) {
                        return null;
                    }

                    // Use the pre-approved entity, and null out our reference.
                    Entity next = current;
                    current = null;
                    return next;
                }
            };

        } // End use-selector

        // Return the selected entities.
        return retVal;

    }

    /**
     * Count all out-of-game<code>Entity</code>s that pass the given selection
     * criteria.
     *
     * @param selector the <code>EntitySelector</code> that implements test that an
     *                 entity must pass to be included. This value may be
     *                 <code>null</code> (in which case the count of all out-of-game
     *                 entities will be returned).
     * @return the <code>int</code> count of all entities that the selector
     * accepts. This value will not be <code>null</code> but it may be
     * empty.
     */
    public int getSelectedOutOfGameEntityCount(EntitySelector selector) {
        int retVal = 0;

        // If no selector was supplied, return the count of all game entities.
        if (null == selector) {
            retVal = vOutOfGame.size();
        }

        // Otherwise, count the entities that meet the selection criteria.
        else {
            Enumeration<Entity> iter = vOutOfGame.elements();
            while (iter.hasMoreElements()) {
                if (selector.accept(iter.nextElement())) {
                    retVal++;
                }
            }

        } // End use-selector

        // Return the number of selected entities.
        return retVal;
    }

    /**
     * Returns true if the player has any valid units this turn that are not
     * infantry, not protomechs, or not either of those. This method is
     * utitilized by the "A players Infantry moves after that players other
     * units", and "A players Protomechs move after that players other units"
     * options.
     */
    public boolean checkForValidNonInfantryAndOrProtomechs(int playerId) {
        Iterator<Entity> iter = getPlayerEntities(getPlayer(playerId), false)
                .iterator();
        while (iter.hasNext()) {
            Entity entity = iter.next();
            boolean excluded = false;
            if ((entity instanceof Infantry)
                && getOptions().booleanOption(OptionsConstants.INIT_INF_MOVE_LATER)) {
                excluded = true;
            } else if ((entity instanceof Protomech)
                       && getOptions().booleanOption(OptionsConstants.INIT_PROTOS_MOVE_LATER)) {
                excluded = true;
            }

            if (!excluded && getTurn().isValidEntity(entity, this)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get Entities that have have a iNarc Nemesis pod attached and are situated
     * between two Coords
     *
     * @param attacker The attacking <code>Entity</code>.
     * @param target   The <code>Coords</code> of the original target.
     * @return a <code>Enumeration</code> of entities that have nemesis pods
     * attached and are located between attacker and target and are
     * friendly with the attacker.
     */
    public Enumeration<Entity> getNemesisTargets(Entity attacker, Coords target) {
        final Coords attackerPos = attacker.getPosition();
        final ArrayList<Coords> in = Coords.intervening(attackerPos, target);
        Vector<Entity> nemesisTargets = new Vector<Entity>();
        for (Coords c : in) {
            for (Entity entity : getEntitiesVector(c)) {
                if (entity.isINarcedWith(INarcPod.NEMESIS)
                    && !entity.isEnemyOf(attacker)) {
                    nemesisTargets.addElement(entity);
                }
            }
        }
        return nemesisTargets.elements();
    }

    /**
     * Adds the specified game listener to receive board events from this board.
     *
     * @param listener the game listener.
     */
    public void addGameListener(GameListener listener) {
        // Since gameListeners is transient, it could be null
        if (gameListeners == null) {
            gameListeners = new Vector<GameListener>();
        }
        gameListeners.addElement(listener);
    }

    /**
     * Removes the specified game listener.
     *
     * @param listener the game listener.
     */
    public void removeGameListener(GameListener listener) {
        // Since gameListeners is transient, it could be null
        if (gameListeners == null) {
            gameListeners = new Vector<GameListener>();
        }
        gameListeners.removeElement(listener);
    }

    /**
     * Returns all the GameListeners.
     *
     * @return
     */
    public List<GameListener> getGameListeners() {
        // Since gameListeners is transient, it could be null
        if (gameListeners == null) {
            gameListeners = new Vector<GameListener>();
        }
        return Collections.unmodifiableList(gameListeners);
    }

    /**
     * purges all Game Listener objects.
     */
    public void purgeGameListeners() {
        // Since gameListeners is transient, it could be null
        if (gameListeners == null) {
            gameListeners = new Vector<GameListener>();
        }
        gameListeners.clear();
    }

    /**
     * Processes game events occurring on this connection by dispatching them to
     * any registered GameListener objects.
     *
     * @param event the game event.
     */
    public void processGameEvent(GameEvent event) {
        // Since gameListeners is transient, it could be null
        if (gameListeners == null) {
            gameListeners = new Vector<GameListener>();
        }
        for (Enumeration<GameListener> e = gameListeners.elements(); e.hasMoreElements(); ) {
            event.fireEvent(e.nextElement());
        }
    }

    /**
     * Returns this turn's tag information
     */
    public Vector<TagInfo> getTagInfo() {
        return tagInfoForTurn;
    }

    public void addTagInfo(TagInfo info) {
        tagInfoForTurn.addElement(info);
    }

    public void resetTagInfo() {
        tagInfoForTurn.removeAllElements();
    }

    public void clearTagInfoShots(Entity ae, Coords tc) {
        for (int i = 0; i < tagInfoForTurn.size(); i++) {
            TagInfo info = tagInfoForTurn.elementAt(i);
            Entity attacker = getEntity(info.attackerId);
            Targetable target = info.target;
            if (!ae.isEnemyOf(attacker) && isIn8HexRadius(target.getPosition(), tc)) {
                info.shots = info.priority;
                tagInfoForTurn.setElementAt(info, i);
            }
        }
    }

    public boolean isIn8HexRadius(Coords c1, Coords c2) {

        // errata says we now always use 8 hex radius
        if (c2.distance(c1) <= 8) {
            return true;
        }
        return false;

    }

    /**
     * Get a list of flares
     */
    public Vector<Flare> getFlares() {
        return flares;
    }

    /**
     * Set the list of flares
     */
    public void setFlares(Vector<Flare> flares) {
        this.flares = flares;
        processGameEvent(new GameBoardChangeEvent(this));
    }

    /**
     * Add a new flare
     */
    public void addFlare(Flare flare) {
        flares.addElement(flare);
        processGameEvent(new GameBoardChangeEvent(this));
    }

    /**
     * Get a set of Coords illuminated by searchlights.
     * 
     * Note: coords could be illuminated by other sources as well, it's likely
     * that IGame.isPositionIlluminated is desired unless the searchlighted hex
     * set is being sent to the client or server.
     */
    public HashSet<Coords> getIlluminatedPositions() {
        return illuminatedPositions;
    }

    /**
     * Clear the set of searchlight illuminated hexes.
     */
    public void clearIlluminatedPositions() {
        if (illuminatedPositions == null) {
            return;
        }
        illuminatedPositions.clear();
    }

    /**
     * Setter for the list of Coords illuminated by search lights.
     */
    public void setIlluminatedPositions(HashSet<Coords> ip) {
        if (ip == null) {
            new RuntimeException("Illuminated Positions is null.").printStackTrace();
        }
        illuminatedPositions = ip;
        processGameEvent(new GameBoardChangeEvent(this));
    }

    /**
     * Add a new hex to the collection of Coords illuminated by searchlights.
     *
     * @return True if a new hex was added, else false if the set already
     * contained the input hex.
     */
    public boolean addIlluminatedPosition(Coords c) {
        boolean rv = illuminatedPositions.add(c);
        processGameEvent(new GameBoardChangeEvent(this));
        return rv;
    }

    /**
     * Returns the level of illumination for a given coords.  Different light
     * sources affect how much the night-time penalties are reduced. Note: this
     * method should be used for determining is a Coords/Hex is illuminated, not
     * IGame. getIlluminatedPositions(), as that just returns the hexes that
     * are effected by spotlights, whereas this one considers searchlights as
     * well as other light sources.
     */
    public int isPositionIlluminated(Coords c) {
    	// fix for NPE when recovering spacecraft while in visual range of enemy
    	if (getBoard().inSpace()) {
    		return ILLUMINATED_NONE;
    	}
        // Flares happen first, because they totally negate nighttime penalties
        for (Flare flare : flares) {
            if (flare.illuminates(c)) {
                return ILLUMINATED_FLARE;
            }
        }
        IHex hex = getBoard().getHex(c);

        // Searchlights reduce nighttime penalties by up to 3 points.
        if (illuminatedPositions.contains(c)) {
            return ILLUMINATED_LIGHT;
        }

        // Fires can reduce nighttime penalties by up to 2 points.
        if (hex != null && hex.containsTerrain(Terrains.FIRE)) {
            return ILLUMINATED_FIRE;
        }
        // If we are adjacent to a burning hex, we are also illuminated
        for (int dir = 0; dir < 6; dir++) {
            Coords adj = c.translated(dir);
            hex = getBoard().getHex(adj);
            if (hex != null && hex.containsTerrain(Terrains.FIRE)) {
                return ILLUMINATED_FIRE;
            }
        }
        return ILLUMINATED_NONE;
    }

    /**
     * Age the flare list and remove any which have burnt out Artillery flares
     * drift with wind. (called at end of turn)
     */
    public Vector<Report> ageFlares() {
        Vector<Report> reports = new Vector<Report>();
        Report r;
        for (int i = flares.size() - 1; i >= 0; i--) {
            Flare flare = flares.elementAt(i);
            r = new Report(5235);
            r.add(flare.position.getBoardNum());
            r.newlines = 0;
            reports.addElement(r);
            if ((flare.flags & Flare.F_IGNITED) != 0) {
                flare.turnsToBurn--;
                if ((flare.flags & Flare.F_DRIFTING) != 0) {
                    int dir = planetaryConditions.getWindDirection();
                    int str = planetaryConditions.getWindStrength();

                    // strength 1 and 2: drift 1 hex
                    // strength 3: drift 2 hexes
                    // strength 4: drift 3 hexes
                    // for each above strenght 4 (storm), drift one more
                    if (str > 0) {
                        flare.position = flare.position.translated(dir);
                        if (str > 2) {
                            flare.position = flare.position.translated(dir);
                        }
                        if (str > 3) {
                            flare.position = flare.position.translated(dir);
                        }
                        if (str > 4) {
                            flare.position = flare.position.translated(dir);
                        }
                        if (str > 5) {
                            flare.position = flare.position.translated(dir);
                        }
                        r = new Report(5236);
                        r.add(flare.position.getBoardNum());
                        r.newlines = 0;
                        reports.addElement(r);
                    }
                }
            } else {
                r = new Report(5237);
                r.newlines = 0;
                reports.addElement(r);
                flare.flags |= Flare.F_IGNITED;
            }
            if (flare.turnsToBurn <= 0) {
                r = new Report(5238);
                reports.addElement(r);
                flares.removeElementAt(i);
            } else {
                r = new Report(5239);
                r.add(flare.turnsToBurn);
                reports.addElement(r);
                flares.setElementAt(flare, i);
            }
        }
        processGameEvent(new GameBoardChangeEvent(this));
        return reports;
    }

    public boolean gameTimerIsExpired() {
        return ((getOptions().booleanOption(OptionsConstants.VICTORY_USE_GAME_TURN_LIMIT)) && (getRoundCount() == getOptions()
                .intOption(OptionsConstants.VICTORY_GAME_TURN_LIMIT)));
    }

    public void createVictoryConditions() {
        victory = new Victory(getOptions());
    }

    public Victory getVictory() {
        return victory;
    }

    // a shortcut function for determining whether vectored movement is
    // applicable
    public boolean useVectorMove() {
        return getOptions().booleanOption(OptionsConstants.ADVAERORULES_ADVANCED_MOVEMENT)
               && board.inSpace();
    }

    /**
     * Adds a pending Control roll to the list for this phase.
     */
    public void addControlRoll(PilotingRollData control) {
        controlRolls.addElement(control);
    }

    /**
     * Returns an Enumeration of pending Control rolls.
     */
    public Enumeration<PilotingRollData> getControlRolls() {
        return controlRolls.elements();
    }

    /**
     * Resets the Control Roll list for a given entity.
     */
    public void resetControlRolls(Entity entity) {
        PilotingRollData roll;
        Vector<Integer> rollsToRemove = new Vector<Integer>();
        int i = 0;

        // first, find all the rolls belonging to the target entity
        for (i = 0; i < controlRolls.size(); i++) {
            roll = controlRolls.elementAt(i);
            if (roll.getEntityId() == entity.getId()) {
                rollsToRemove.addElement(Integer.valueOf(i));
            }
        }

        // now, clear them out
        for (i = rollsToRemove.size() - 1; i > -1; i--) {
            controlRolls.removeElementAt(rollsToRemove.elementAt(i).intValue());
        }
    }

    /**
     * Resets the PSR list.
     */
    public void resetControlRolls() {
        controlRolls.removeAllElements();
    }

    /**
     * A set of checks for aero units to make sure that the movement order is
     * maintained
     */
    public boolean checkForValidSpaceStations(int playerId) {
        Iterator<Entity> iter = getPlayerEntities(getPlayer(playerId), false)
                .iterator();
        while (iter.hasNext()) {
            Entity entity = iter.next();
            if ((entity instanceof SpaceStation)
                && getTurn().isValidEntity(entity, this)) {
                return true;
            }
        }
        return false;
    }

    public boolean checkForValidJumpships(int playerId) {
        Iterator<Entity> iter = getPlayerEntities(getPlayer(playerId), false)
                .iterator();
        while (iter.hasNext()) {
            Entity entity = iter.next();
            if ((entity instanceof Jumpship) && !(entity instanceof Warship)
                && getTurn().isValidEntity(entity, this)) {
                return true;
            }
        }
        return false;
    }

    public boolean checkForValidWarships(int playerId) {
        Iterator<Entity> iter = getPlayerEntities(getPlayer(playerId), false)
                .iterator();
        while (iter.hasNext()) {
            Entity entity = iter.next();
            if ((entity instanceof Warship)
                && getTurn().isValidEntity(entity, this)) {
                return true;
            }
        }
        return false;
    }

    public boolean checkForValidDropships(int playerId) {
        Iterator<Entity> iter = getPlayerEntities(getPlayer(playerId), false)
                .iterator();
        while (iter.hasNext()) {
            Entity entity = iter.next();
            if ((entity instanceof Dropship)
                && getTurn().isValidEntity(entity, this)) {
                return true;
            }
        }
        return false;
    }

    public boolean checkForValidSmallCraft(int playerId) {
        Iterator<Entity> iter = getPlayerEntities(getPlayer(playerId), false)
                .iterator();
        while (iter.hasNext()) {
            Entity entity = iter.next();
            if ((entity instanceof SmallCraft)
                && getTurn().isValidEntity(entity, this)) {
                return true;
            }
        }
        return false;
    }

    public PlanetaryConditions getPlanetaryConditions() {
        return planetaryConditions;
    }

    public void setPlanetaryConditions(PlanetaryConditions conditions) {
        if (null == conditions) {
            System.err.println("Can't set the planetary conditions to null!");
        } else {
            planetaryConditions.alterConditions(conditions);
            processGameEvent(new GameSettingsChangeEvent(this));
        }
    }

    public void addSmokeCloud(SmokeCloud cloud) {
        smokeCloudList.add(cloud);
    }

    public List<SmokeCloud> getSmokeCloudList() {
        return smokeCloudList;
    }
    
    public void removeSmokeClouds(List<SmokeCloud> cloudsToRemove) {
        for (SmokeCloud cloud : cloudsToRemove) {
            smokeCloudList.remove(cloud);
        }
    }

    /**
     * Updates the map that maps a position to the list of Entity's in that
     * position.
     *
     * @param e
     */
    public synchronized void updateEntityPositionLookup(Entity e,
            HashSet<Coords> oldPositions) {
        HashSet<Coords> newPositions = e.getOccupiedCoords();
        // Check to see that the position has actually changed
        if (newPositions.equals(oldPositions)) {
            return;
        }

        // Remove the old cached location(s)
        if (oldPositions != null) {
            for (Coords pos : oldPositions) {
                HashSet<Integer> posEntities = entityPosLookup.get(pos);
                if (posEntities != null) {
                    posEntities.remove(e.getId());
                }
            }
        }

        // Add Entity for each position
        for (Coords pos : newPositions) {
            HashSet<Integer> posEntities = entityPosLookup.get(pos);
            if (posEntities == null) {
                posEntities = new HashSet<Integer>();
                posEntities.add(e.getId());
                entityPosLookup.put(pos, posEntities);
            } else {
                posEntities.add(e.getId());
            }
        }
    }

    private void removeEntityPositionLookup(Entity e) {
        // Remove Entity from cache
        for (Coords pos : e.getOccupiedCoords()) {
            HashSet<Integer> posEntities = entityPosLookup.get(pos);
            if (posEntities != null) {
                posEntities.remove(e.getId());
            }
        }
    }

    private void resetEntityPositionLookup() {
        entityPosLookup.clear();
        for (Entity e : entities) {
            updateEntityPositionLookup(e, null);
        }
    }

    private int countEntitiesInCache(List<Integer> entitiesInCache) {
        int count = 0;
        for (Coords c : entityPosLookup.keySet()) {
            count += entityPosLookup.get(c).size();
            entitiesInCache.addAll(entityPosLookup.get(c));
        }
        return count;
    }
    
    /**
     * A check to ensure that the position cache is properly updated.  This 
     * is only used for debugging purposes, and will cause a number of things
     * to slow down.
     */
    @SuppressWarnings("unused")
    private void checkPositionCacheConsistency() {
        // Sanity check on the position cache
        //  This could be removed once we are confident the cache is working
        List<Integer> entitiesInCache = new ArrayList<Integer>();
        List<Integer> entitiesInVector = new ArrayList<Integer>();
        int entitiesInCacheCount = countEntitiesInCache(entitiesInCache);
        int entityVectorSize = 0;
        for (Entity e : entities) {
            if (e.getPosition() != null) {
                entityVectorSize++;
                entitiesInVector.add(e.getId());
            }
        }
        Collections.sort(entitiesInCache);
        Collections.sort(entitiesInVector);
        if ((entitiesInCacheCount != entityVectorSize)
                && (getPhase() != Phase.PHASE_DEPLOYMENT)
                && (getPhase() != Phase.PHASE_EXCHANGE)
                && (getPhase() != Phase.PHASE_LOUNGE)
                && (getPhase() != Phase.PHASE_INITIATIVE_REPORT)
                && (getPhase() != Phase.PHASE_INITIATIVE)) {
            System.out.println("Entities vector has " + entities.size()
                    + " but pos lookup cache has " + entitiesInCache.size()
                    + " entities!");
            List<Integer> missingIds = new ArrayList<Integer>();
            for (Integer id : entitiesInVector) {
                if (!entitiesInCache.contains(id)) {
                    missingIds.add(id);
                }
            }
            System.out.println("Missing ids: " + missingIds);
        }
        for (Entity e : entities) {
            HashSet<Coords> positions = e.getOccupiedCoords();
            for (Coords c : positions) {
                HashSet<Integer> ents = entityPosLookup.get(c);
                if ((ents != null) && !ents.contains(e.getId())) {
                    System.out.println("Entity " + e.getId() + " is in "
                            + e.getPosition() + " however the position cache "
                            + "does not have it in that position!");
                }
            }
        }
        for (Coords c : entityPosLookup.keySet()) {
            for (Integer eId : entityPosLookup.get(c)) {
                Entity e = getEntity(eId);
                if (e == null) {
                    continue;
                }
                HashSet<Coords> positions = e.getOccupiedCoords();
                if (!positions.contains(c)) {
                    System.out.println("Entity Position Cache thinks Entity "
                            + eId + "is in " + c
                            + " but the Entity thinks it's in "
                            + e.getPosition());
                }
            }
        }
    }

    /**
     * Get a string representation of the UUId for this game.
     *
     * @return
     */
    public String getUUIDString() {
        if (uuid == null) {
            uuid = UUID.randomUUID();
        }
        return uuid.toString();

    }

    /**
     * Calculates all players initial BV, should only be called at start of game
     */
    public void calculatePlayerBVs() {
        for (Enumeration<IPlayer> players = getPlayers(); players.hasMoreElements(); ) {
            players.nextElement().setInitialBV();
        }
    }

    /**
     * Called at the end of movement. Determines if an entity
     * has moved beyond sensor range
     */
    public void updateSpacecraftDetection() {
        // Don't bother if we're not in space or if the game option isn't on
        if (!getBoard().inSpace()
                || !getOptions().booleanOption(OptionsConstants.ADVAERORULES_STRATOPS_ADVANCED_SENSORS)) {
            return;
        }
        //Run through our list of units and remove any entities from the plotting board that have moved out of range
        for (Entity detector : getEntitiesVector()) {
            Compute.updateFiringSolutions(this, detector);
            Compute.updateSensorContacts(this, detector);
        }
    }

    /**
     * Called at the beginning of each game round to reset values on this entity
     * that are reset every round
     */
    public void resetEntityRound() {
        for (Iterator<Entity> e = getEntities(); e.hasNext(); ) {
            Entity entity = e.next();

            entity.newRound(getRoundCount());
        }
    }

    /**
     * Deploys elligible offboard entities.
     */
    public void deployOffBoardEntities() {
        // place off board entities actually off-board
        Iterator<Entity> entities = getEntities();
        while (entities.hasNext()) {
            Entity en = entities.next();
            if (en.isOffBoard() && !en.isDeployed()) {
                en.deployOffBoard(getRoundCount());
            }
        }
    }

    /**
     * Cancels the force victory
     */
    public void cancelVictory() {
        setForceVictory(false);
        setVictoryPlayerId(IPlayer.PLAYER_NONE);
        setVictoryTeam(IPlayer.TEAM_NONE);
    }

    /**
     * are we currently in a reporting phase
     *
     * @return <code>true</code> if we are or <code>false</code> if not.
     */
    public boolean isReportingPhase() {
        return (getPhase() == IGame.Phase.PHASE_FIRING_REPORT)
                || (getPhase() == IGame.Phase.PHASE_INITIATIVE_REPORT)
                || (getPhase() == IGame.Phase.PHASE_MOVEMENT_REPORT)
                || (getPhase() == IGame.Phase.PHASE_OFFBOARD_REPORT)
                || (getPhase() == IGame.Phase.PHASE_PHYSICAL_REPORT);
    }

    /*
     *  Called during the end phase. Checks each entity for ASEW effects counters and decrements them by 1 if > 0
     */

    public void decrementASEWTurns() {
        for (Iterator<Entity> e = getEntities(); e.hasNext(); ) {
            final Entity entity = e.next();
            // Decrement ASEW effects
            if ((entity.getEntityType() & Entity.ETYPE_DROPSHIP) == Entity.ETYPE_DROPSHIP) {
                Dropship d = (Dropship) entity;
                for (int loc = 0; loc < d.locations(); loc++) {
                    if (d.getASEWAffected(loc) > 0) {
                        d.setASEWAffected(loc, d.getASEWAffected(loc) - 1);
                    }
                }
            } else if ((entity.getEntityType() & Entity.ETYPE_JUMPSHIP) != 0) {
                Jumpship j = (Jumpship) entity;
                for (int loc = 0; loc < j.locations(); loc++) {
                    if (j.getASEWAffected(loc) > 0) {
                        j.setASEWAffected(loc, j.getASEWAffected(loc) - 1);
                    }
                }
            } else {
                if (entity.getASEWAffected() > 0) {
                    entity.setASEWAffected(entity.getASEWAffected() - 1);
                }
            }
        }
    }

    public boolean isPlayerForcedVictory() {
        // check game options
        if (!getOptions().booleanOption(OptionsConstants.VICTORY_SKIP_FORCED_VICTORY)) {
            return false;
        }

        if (!isForceVictory()) {
            return false;
        }

        for (IPlayer player : getPlayersVector()) {
            if ((player.getId() == getVictoryPlayerId()) || ((player.getTeam() == getVictoryTeam())
                    && (getVictoryTeam() != IPlayer.TEAM_NONE))) {
                continue;
            }

            if (!player.admitsDefeat()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Marks ineligible entities as not ready for this phase
     */
    public void setIneligible(IGame.Phase phase) {
        Vector<Entity> assistants = new Vector<>();
        boolean assistable = false;

        if (isPlayerForcedVictory()) {
            assistants.addAll(getEntitiesVector());
        } else {
            for (Entity entity : getEntitiesVector()) {
                if (entity.isEligibleFor(phase)) {
                    assistable = true;
                } else {
                    assistants.addElement(entity);
                }
            }
        }
        for (Entity assistant : assistants) {
            if (!assistable || !assistant.canAssist(phase)) {
                assistant.setDone(true);
            }
        }
    }

    public boolean checkCrash(Entity entity, Coords pos, int altitude) {
        // only Aeros can crash and no crashing in space
        if (!entity.isAero() || getBoard().inSpace()) {
            return false;
        }

        // if aero on the ground map, then only crash if elevation is zero
        else if (getBoard().onGround()) {
            return altitude <= 0;
        }
        // we must be in atmosphere
        // if we're off the map, assume hex ceiling 0
        // Hexes with elevations < 0 are treated as 0 altitude
        int ceiling = 0;
        if (getBoard().getHex(pos) != null) {
            ceiling = Math.max(0, getBoard().getHex(pos).ceiling(true));
        }
        return ceiling >= altitude;
    }

    /**
     * Flips the order of a tractor's towed trailers list by index and
     * adds their starting coordinates to a list of hexes the tractor passed through
     *
     * @return  Returns the properly sorted list of all train coordinates
     */
    public List<Coords> initializeTrailerCoordinates(Entity tractor, List<Integer> allTowedTrailers) {
        List<Coords> trainCoords = new ArrayList<>();
        for (int trId : allTowedTrailers) {
            Entity trailer = getEntity(trId);
            Coords position = trailer.getPosition();
            //Duplicates foul up the works...
            if (!trainCoords.contains(position)) {
                trainCoords.add(position);
            }
        }
        for (Coords c : tractor.getPassedThrough() ) {
            if (!trainCoords.contains(c)) {
                trainCoords.add(c);
            }
        }
        return trainCoords;
    }

    /**
     * Add heat from the movement phase
     */
    public void addMovementHeat() {
        for (Iterator<Entity> i = getEntities(); i.hasNext(); ) {
            Entity entity = i.next();

            if (entity.hasDamagedRHS()) {
                entity.heatBuildup += 1;
            }

            if ((entity.getMovementMode() == EntityMovementMode.BIPED_SWIM)
                    || (entity.getMovementMode() == EntityMovementMode.QUAD_SWIM)) {
                // UMU heat
                entity.heatBuildup += 1;
                continue;
            }

            // build up heat from movement
            if (entity.isEvading() && !entity.isAero()) {
                entity.heatBuildup += entity.getRunHeat() + 2;
            } else if (entity.moved == EntityMovementType.MOVE_NONE) {
                entity.heatBuildup += entity.getStandingHeat();
            } else if ((entity.moved == EntityMovementType.MOVE_WALK)
                    || (entity.moved == EntityMovementType.MOVE_VTOL_WALK)
                    || (entity.moved == EntityMovementType.MOVE_CAREFUL_STAND)) {
                entity.heatBuildup += entity.getWalkHeat();
            } else if ((entity.moved == EntityMovementType.MOVE_RUN)
                    || (entity.moved == EntityMovementType.MOVE_VTOL_RUN)
                    || (entity.moved == EntityMovementType.MOVE_SKID)) {
                entity.heatBuildup += entity.getRunHeat();
            } else if (entity.moved == EntityMovementType.MOVE_JUMP) {
                entity.heatBuildup += entity.getJumpHeat(entity.delta_distance);
            } else if (entity.moved == EntityMovementType.MOVE_SPRINT
                    || entity.moved == EntityMovementType.MOVE_VTOL_SPRINT) {
                entity.heatBuildup += entity.getSprintHeat();
            }
        }
    }

    /**
     * Client has sent an update indicating that a ground unit is firing at
     * an airborne unit and is overriding the default select for the position
     * in the flight path.
     * @param packet the packet to be processed
     * @param connId the id for connection that received the packet.
     */
    public void receiveGroundToAirHexSelectPacket(Packet packet, int connId) {
        Integer targetId = (Integer)packet.getObject(0);
        Integer attackerId = (Integer)packet.getObject(1);
        Coords pos = (Coords)packet.getObject(2);
        getEntity(targetId).setPlayerPickedPassThrough(attackerId, pos);
    }

    /**
     * Determine which telemissile attack actions could be affected by AMS, and
     * assign AMS to those attacks.
     */
    public void assignTeleMissileAMS(TeleMissileAttackAction taa) {
        // Map target to a list of telemissile attacks directed at entities
        Hashtable<Entity, Vector<AttackAction>> htTMAttacks = new Hashtable<>();

        //This should be impossible but just in case...
        if (taa == null) {
            MegaMek.getLogger().error("Null TeleMissileAttackAction!");
        }

        Entity target = (taa.getTargetType() == Targetable.TYPE_ENTITY)
                ? (Entity) taa.getTarget(this) : null;

        //If a telemissile is still on the board and its original target is not....
        if (target == null) {
            MegaMek.getLogger().info("Telemissile has no target. AMS not assigned.");
            return;
        }

        Vector<AttackAction> v = htTMAttacks.computeIfAbsent(target, k -> new Vector<>());
        v.addElement(taa);
        // Let each target assign its AMS
        for (Entity e : htTMAttacks.keySet()) {
            Vector<AttackAction> vTMAttacks = htTMAttacks.get(e);
            // Allow MM to automatically assign AMS targets
            // AMS bays can fire multiple times, so manual target assignment is kind of pointless
            e.assignTMAMS(vTMAttacks);
        }
    }

    /**
     * Add any extreme gravity PSRs the entity gets due to its movement
     *
     * @param entity
     *            The <code>Entity</code> to check.
     * @param step
     *            The last <code>MoveStep</code> of this entity
     * @param moveType
     *            The movement type for the MovePath the supplied MoveStep comes
     *            from. This generally comes from the last step in the move
     *            path.
     * @param curPos
     *            The current <code>Coords</code> of this entity
     * @param cachedMaxMPExpenditure
     *            Server checks run/jump MP at start of move, as appropriate,
     *            caches to avoid mid-move change in MP causing erroneous grav
     *            check
     */
    public void checkExtremeGravityMovement(Entity entity, MoveStep step,
                                             EntityMovementType moveType, Coords curPos,
                                             int cachedMaxMPExpenditure) {
        PilotingRollData rollTarget;
        if (getPlanetaryConditions().getGravity() != 1) {
            if ((entity instanceof Mech) || (entity instanceof Tank)) {
                if ((moveType == EntityMovementType.MOVE_WALK)
                        || (moveType == EntityMovementType.MOVE_VTOL_WALK)
                        || (moveType == EntityMovementType.MOVE_RUN)
                        || (moveType == EntityMovementType.MOVE_SPRINT)
                        || (moveType == EntityMovementType.MOVE_VTOL_RUN)
                        || (moveType == EntityMovementType.MOVE_VTOL_SPRINT)) {
                    int limit = cachedMaxMPExpenditure;
                    if (step.isOnlyPavement() && entity.isEligibleForPavementBonus()) {
                        limit++;
                    }
                    if (step.getMpUsed() > limit) {
                        // We moved too fast, let's make PSR to see if we get
                        // damage
                        addExtremeGravityPSR(entity.checkMovedTooFast(
                                step, moveType));
                    }
                } else if (moveType == EntityMovementType.MOVE_JUMP) {
                    MegaMek.getLogger().debug("Gravity move check jump: "
                            + step.getMpUsed() + "/" + cachedMaxMPExpenditure);
                    int origWalkMP = entity.getWalkMP(false, false);
                    int gravWalkMP = entity.getWalkMP();
                    if (step.getMpUsed() > cachedMaxMPExpenditure) {
                        // Jumped too far, make PSR to see if we get damaged
                        addExtremeGravityPSR(entity.checkMovedTooFast(
                                step, moveType));
                    } else if ((getPlanetaryConditions().getGravity() > 1)
                            && ((origWalkMP - gravWalkMP) > 0)) {
                        // jumping in high g is bad for your legs
                        // Damage dealt = 1 pt for each MP lost due to gravity
                        // Ignore this if no damage would be dealt
                        rollTarget = entity.getBasePilotingRoll(moveType);
                        entity.addPilotingModifierForTerrain(rollTarget, step);
                        int gravMod = getPlanetaryConditions()
                                .getGravityPilotPenalty();
                        if ((gravMod != 0) && !getBoard().inSpace()) {
                            rollTarget.addModifier(gravMod,
                                    getPlanetaryConditions().getGravity()
                                    + "G gravity");
                        }
                        rollTarget.append(new PilotingRollData(entity.getId(),
                                0, "jumped in high gravity"));
                        addExtremeGravityPSR(rollTarget);
                    }
                }
            }
        }
    }

    /**
     * Called at the start and end of movement. Determines if an entity
     * has been detected and/or had a firing solution calculated
     */
    public void detectSpacecraft() {
        // Don't bother if we're not in space or if the game option isn't on
        if (!getBoard().inSpace()
                || !getOptions().booleanOption(OptionsConstants.ADVAERORULES_STRATOPS_ADVANCED_SENSORS)) {
            return;
        }

        //Now, run the detection rolls
        for (Entity detector : getEntitiesVector()) {
            //Don't process for invalid units
            //in the case of squadrons and transports, we want the 'host'
            //unit, not the component entities
            if (detector.getPosition() == null
                    || detector.isDestroyed()
                    || detector.isDoomed()
                    || detector.isOffBoard()
                    || detector.isPartOfFighterSquadron()
                    || detector.getTransportId() != Entity.NONE) {
                continue;
            }
            for (Entity target : getEntitiesVector()) {
                //Once a target is detected, we don't need to detect it again
                if (detector.hasSensorContactFor(target.getId())) {
                    continue;
                }
                //Don't process for invalid units
                //in the case of squadrons and transports, we want the 'host'
                //unit, not the component entities
                if (target.getPosition() == null
                        || target.isDestroyed()
                        || target.isDoomed()
                        || target.isOffBoard()
                        || target.isPartOfFighterSquadron()
                        || target.getTransportId() != Entity.NONE) {
                    continue;
                }
                // Only process for enemy units
                if (!detector.isEnemyOf(target)) {
                    continue;
                }
                //If we successfully detect the enemy, add it to the appropriate detector's sensor contacts list
                if (Compute.calcSensorContact(this, detector, target)) {
                    getEntity(detector.getId()).addSensorContact(target.getId());
                    //If detector is part of a C3 network, share the contact
                    if (detector.hasNavalC3()) {
                        for (Entity c3NetMate : getC3NetworkMembers(detector)) {
                            getEntity(c3NetMate.getId()).addSensorContact(target.getId());
                        }
                    }
                }
            }
        }
        //Now, run the firing solution calculations
        for (Entity detector : getEntitiesVector()) {
            //Don't process for invalid units
            //in the case of squadrons and transports, we want the 'host'
            //unit, not the component entities
            if (detector.getPosition() == null
                    || detector.isDestroyed()
                    || detector.isDoomed()
                    || detector.isOffBoard()
                    || detector.isPartOfFighterSquadron()
                    || detector.getTransportId() != Entity.NONE) {
                continue;
            }
            for (int targetId : detector.getSensorContacts()) {
                Entity target = getEntity(targetId);
                //if we already have a firing solution, no need to process a new one
                if (detector.hasFiringSolutionFor(targetId)) {
                    continue;
                }
                //Don't process for invalid units
                //in the case of squadrons and transports, we want the 'host'
                //unit, not the component entities
                if (target == null
                        || target.getPosition() == null
                        || target.isDestroyed()
                        || target.isDoomed()
                        || target.isOffBoard()
                        || target.isPartOfFighterSquadron()
                        || target.getTransportId() != Entity.NONE) {
                    continue;
                }
                // Only process for enemy units
                if (!detector.isEnemyOf(target)) {
                    continue;
                }
                //If we successfully lock up the enemy, add it to the appropriate detector's firing solutions list
                if (Compute.calcFiringSolution(this, detector, target)) {
                    getEntity(detector.getId()).addFiringSolution(targetId);
                }
            }
        }
    }

    /**
     * Check if spikes get broken in the given location
     *
     * @param e   The {@link Entity} to check
     * @param loc The location index
     * @return    A report showing the results of the roll
     */
    public Report checkBreakSpikes(Entity e, int loc) {
        int roll = Compute.d6(2);
        Report r;
        if (roll < 9) {
            r = new Report(4445);
            r.indent(2);
            r.add(roll);
            r.subject = e.getId();
        } else {
            r = new Report(4440);
            r.indent(2);
            r.add(roll);
            r.subject = e.getId();
            for (Mounted m : e.getMisc()) {
                if (m.getType().hasFlag(MiscType.F_SPIKES)
                        && (m.getLocation() == loc)) {
                    m.setHit(true);
                }
            }
        }
        return r;
    }

    /**
     * pre-treats a physical attack
     *
     * @param aaa The <code>AbstractAttackAction</code> of the physical attack
     *            to pre-treat
     * @return The <code>PhysicalResult</code> of that action, including
     * possible damage.
     */
    public PhysicalResult preTreatPhysicalAttack(AbstractAttackAction aaa) {
        final Entity ae = getEntity(aaa.getEntityId());
        int damage = 0;
        PhysicalResult pr = new PhysicalResult();
        ToHitData toHit = new ToHitData();
        pr.roll = Compute.d6(2);
        pr.aaa = aaa;
        if (aaa instanceof BrushOffAttackAction) {
            BrushOffAttackAction baa = (BrushOffAttackAction) aaa;
            int arm = baa.getArm();
            baa.setArm(BrushOffAttackAction.LEFT);
            toHit = BrushOffAttackAction.toHit(this, aaa.getEntityId(),
                    aaa.getTarget(this), BrushOffAttackAction.LEFT);
            baa.setArm(BrushOffAttackAction.RIGHT);
            pr.toHitRight = BrushOffAttackAction.toHit(this, aaa.getEntityId(),
                    aaa.getTarget(this), BrushOffAttackAction.RIGHT);
            damage = BrushOffAttackAction.getDamageFor(ae, BrushOffAttackAction.LEFT);
            pr.damageRight = BrushOffAttackAction.getDamageFor(ae, BrushOffAttackAction.RIGHT);
            baa.setArm(arm);
            pr.rollRight = Compute.d6(2);
        } else if (aaa instanceof ChargeAttackAction) {
            ChargeAttackAction caa = (ChargeAttackAction) aaa;
            toHit = caa.toHit(this);
            if (caa.getTarget(this) instanceof Entity) {
                Entity target = (Entity) caa.getTarget(this);
                damage = ChargeAttackAction.getDamageFor(ae, target,
                        getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_CHARGE_DAMAGE),
                        toHit.getMoS());
            } else {
                damage = ChargeAttackAction.getDamageFor(ae);
            }
        } else if (aaa instanceof AirmechRamAttackAction) {
            AirmechRamAttackAction raa = (AirmechRamAttackAction) aaa;
            toHit = raa.toHit(this);
            damage = AirmechRamAttackAction.getDamageFor(ae);
        } else if (aaa instanceof ClubAttackAction) {
            ClubAttackAction caa = (ClubAttackAction) aaa;
            toHit = caa.toHit(this);
            damage = ClubAttackAction.getDamageFor(ae, caa.getClub(),
                    (caa.getTarget(this) instanceof Infantry)
                            && !(caa.getTarget(this) instanceof BattleArmor),
                    caa.isZweihandering());
            if (caa.getTargetType() == Targetable.TYPE_BUILDING) {
                EquipmentType clubType = caa.getClub().getType();
                if (clubType.hasSubType(MiscType.S_BACKHOE)
                        || clubType.hasSubType(MiscType.S_CHAINSAW)
                        || clubType.hasSubType(MiscType.S_MINING_DRILL)
                        || clubType.hasSubType(MiscType.S_PILE_DRIVER)) {
                    damage += Compute.d6(1);
                } else if (clubType.hasSubType(MiscType.S_DUAL_SAW)) {
                    damage += Compute.d6(2);
                } else if (clubType.hasSubType(MiscType.S_ROCK_CUTTER)) {
                    damage += Compute.d6(3);
                }
                else if (clubType.hasSubType(MiscType.S_WRECKING_BALL)) {
                    damage += Compute.d6(4);
                }
            }
        } else if (aaa instanceof DfaAttackAction) {
            DfaAttackAction daa = (DfaAttackAction) aaa;
            toHit = daa.toHit(this);
            damage = DfaAttackAction.getDamageFor(ae,
                    (daa.getTarget(this) instanceof Infantry)
                            && !(daa.getTarget(this) instanceof BattleArmor));
        } else if (aaa instanceof KickAttackAction) {
            KickAttackAction kaa = (KickAttackAction) aaa;
            toHit = kaa.toHit(this);
            damage = KickAttackAction.getDamageFor(ae, kaa.getLeg(),
                    (kaa.getTarget(this) instanceof Infantry)
                            && !(kaa.getTarget(this) instanceof BattleArmor));
        } else if (aaa instanceof ProtomechPhysicalAttackAction) {
            ProtomechPhysicalAttackAction paa = (ProtomechPhysicalAttackAction) aaa;
            toHit = paa.toHit(this);
            damage = ProtomechPhysicalAttackAction.getDamageFor(ae, paa.getTarget(this));
        } else if (aaa instanceof PunchAttackAction) {
            PunchAttackAction paa = (PunchAttackAction) aaa;
            int arm = paa.getArm();
            int damageRight;
            paa.setArm(PunchAttackAction.LEFT);
            toHit = paa.toHit(this);
            paa.setArm(PunchAttackAction.RIGHT);
            ToHitData toHitRight = paa.toHit(this);
            damage = PunchAttackAction.getDamageFor(ae, PunchAttackAction.LEFT,
                    (paa.getTarget(this) instanceof Infantry)
                            && !(paa.getTarget(this) instanceof BattleArmor),
                    paa.isZweihandering());
            damageRight = PunchAttackAction.getDamageFor(ae, PunchAttackAction.RIGHT,
                    (paa.getTarget(this) instanceof Infantry)
                            && !(paa.getTarget(this) instanceof BattleArmor),
                    paa.isZweihandering());
            paa.setArm(arm);
            // If we're punching while prone (at a Tank,
            // duh), then we can only use one arm.
            if (ae.isProne()) {
                double oddsLeft = Compute.oddsAbove(toHit.getValue(),
                        ae.hasAbility(OptionsConstants.PILOT_APTITUDE_PILOTING));
                double oddsRight = Compute.oddsAbove(toHitRight.getValue(),
                        ae.hasAbility(OptionsConstants.PILOT_APTITUDE_PILOTING));
                // Use the best attack.
                if ((oddsLeft * damage) > (oddsRight * damageRight)) {
                    paa.setArm(PunchAttackAction.LEFT);
                } else {
                    paa.setArm(PunchAttackAction.RIGHT);
                }
            }
            pr.damageRight = damageRight;
            pr.toHitRight = toHitRight;
            pr.rollRight = Compute.d6(2);
        } else if (aaa instanceof PushAttackAction) {
            PushAttackAction paa = (PushAttackAction) aaa;
            toHit = paa.toHit(this);
        } else if (aaa instanceof TripAttackAction) {
            TripAttackAction paa = (TripAttackAction) aaa;
            toHit = paa.toHit(this);
        } else if (aaa instanceof LayExplosivesAttackAction) {
            LayExplosivesAttackAction leaa = (LayExplosivesAttackAction) aaa;
            toHit = leaa.toHit(this);
            damage = LayExplosivesAttackAction.getDamageFor(ae);
        } else if (aaa instanceof ThrashAttackAction) {
            ThrashAttackAction taa = (ThrashAttackAction) aaa;
            toHit = taa.toHit(this);
            damage = ThrashAttackAction.getDamageFor(ae);
        } else if (aaa instanceof JumpJetAttackAction) {
            JumpJetAttackAction jaa = (JumpJetAttackAction) aaa;
            toHit = jaa.toHit(this);
            if (jaa.getLeg() == JumpJetAttackAction.BOTH) {
                damage = JumpJetAttackAction.getDamageFor(ae, JumpJetAttackAction.LEFT);
                pr.damageRight = JumpJetAttackAction.getDamageFor(ae, JumpJetAttackAction.LEFT);
            } else {
                damage = JumpJetAttackAction.getDamageFor(ae, jaa.getLeg());
                pr.damageRight = 0;
            }
            ae.heatBuildup += (damage + pr.damageRight) / 3;
        } else if (aaa instanceof GrappleAttackAction) {
            GrappleAttackAction taa = (GrappleAttackAction) aaa;
            toHit = taa.toHit(this);
        } else if (aaa instanceof BreakGrappleAttackAction) {
            BreakGrappleAttackAction taa = (BreakGrappleAttackAction) aaa;
            toHit = taa.toHit(this);
        } else if (aaa instanceof RamAttackAction) {
            RamAttackAction raa = (RamAttackAction) aaa;
            toHit = raa.toHit(this);
            damage = RamAttackAction.getDamageFor((IAero) ae, (Entity) aaa.getTarget(this));
        } else if (aaa instanceof TeleMissileAttackAction) {
            TeleMissileAttackAction taa = (TeleMissileAttackAction) aaa;
            assignTeleMissileAMS(taa);
            taa.calcCounterAV(this, taa.getTarget(this));
            toHit = taa.toHit(this);
            damage = TeleMissileAttackAction.getDamageFor(ae);
        } else if (aaa instanceof BAVibroClawAttackAction) {
            BAVibroClawAttackAction bvca = (BAVibroClawAttackAction) aaa;
            toHit = bvca.toHit(this);
            damage = BAVibroClawAttackAction.getDamageFor(ae);
        }
        pr.toHit = toHit;
        pr.damage = damage;
        return pr;
    }


    /**
     * Validates the player info.
     */
    public void validatePlayerInfo(int playerId) {
        final IPlayer player = getPlayer(playerId);

        if (player != null) {
            // TODO : check for duplicate or reserved names

            // Colour Assignment
            final PlayerColour[] playerColours = PlayerColour.values();
            boolean allUsed = true;
            Set<PlayerColour> colourUtilization = new HashSet<>();
            for (Enumeration<IPlayer> i = getPlayers(); i.hasMoreElements(); ) {
                final IPlayer otherPlayer = i.nextElement();
                if (otherPlayer.getId() != playerId) {
                    colourUtilization.add(otherPlayer.getColour());
                } else {
                    allUsed = false;
                }
            }

            if (!allUsed && colourUtilization.contains(player.getColour())) {
                for (PlayerColour colour : playerColours) {
                    if (!colourUtilization.contains(colour)) {
                        player.setColour(colour);
                        break;
                    }
                }
            }
        }
    }

    /**
     * Adds a new player to the game
     */
    public IPlayer addNewPlayer(int connId, String name) {
        int team = IPlayer.TEAM_UNASSIGNED;
        if (getPhase() == Phase.PHASE_LOUNGE) {
            team = IPlayer.TEAM_NONE;
            for (IPlayer p : getPlayersVector()) {
                if (p.getTeam() > team) {
                    team = p.getTeam();
                }
            }
            team++;
        }
        IPlayer newPlayer = new Player(connId, name);
        PlayerColour colour = newPlayer.getColour();
        Enumeration<IPlayer> players = getPlayers();
        final PlayerColour[] colours = PlayerColour.values();
        while (players.hasMoreElements()) {
            final IPlayer p = players.nextElement();
            if (p.getId() == newPlayer.getId()) {
                continue;
            }

            if ((p.getColour() == colour) && (colours.length > (colour.ordinal() + 1))) {
                colour = colours[colour.ordinal() + 1];
            }
        }
        newPlayer.setColour(colour);
        newPlayer.setCamoCategory(Camouflage.COLOUR_CAMOUFLAGE);
        newPlayer.setCamoFileName(colour.name());
        newPlayer.setTeam(Math.min(team, 5));
        addPlayer(connId, newPlayer);
        validatePlayerInfo(connId);
        return newPlayer;
    }

    /**
     * Forces victory for the specified player, or his/her team at the end of
     * the round.
     */
    public void forceVictory(IPlayer victor) {
        setForceVictory(true);
        if (victor.getTeam() == IPlayer.TEAM_NONE) {
            setVictoryPlayerId(victor.getId());
            setVictoryTeam(IPlayer.TEAM_NONE);
        } else {
            setVictoryPlayerId(IPlayer.PLAYER_NONE);
            setVictoryTeam(victor.getTeam());
        }

        Vector<IPlayer> playersVector = getPlayersVector();
        for (int i = 0; i < playersVector.size(); i++) {
            IPlayer player = playersVector.elementAt(i);
            player.setAdmitsDefeat(false);
        }
    }

    /**
     * Returns true if the current turn may be skipped. Ghost players' turns are
     * skippable, and a turn should be skipped if there's nothing to move.
     */
    public boolean isTurnSkippable() {
        GameTurn turn = getTurn();
        if (null == turn) {
            return false;
        }
        IPlayer player = getPlayer(turn.getPlayerNum());
        return (null == player) || player.isGhost() || (getFirstEntity() == null);
    }

    /**
     * Sets a player's ready status
     */
    public void receivePlayerDone(Packet pkt, int connIndex) {
        boolean ready = pkt.getBooleanValue(0);
        IPlayer player = getPlayer(connIndex);
        if (null != player) {
            player.setDone(ready);
        }
    }

    /**
     * @return whether this game is double blind or not and we should be blind in
     * the current phase
     */
    public boolean doBlind() {
        return getOptions().booleanOption(OptionsConstants.ADVANCED_DOUBLE_BLIND)
                && getPhase().isDuringOrAfter(IGame.Phase.PHASE_DEPLOYMENT);
    }

    public boolean suppressBlindBV() {
        return getOptions().booleanOption(OptionsConstants.ADVANCED_SUPPRESS_DB_BV);
    }

    /**
     * Iterates over all entities and gets rid of Narc pods attached to destroyed
     * or lost locations.
     */
    public void cleanupDestroyedNarcPods() {
        for (Iterator<Entity> i = getEntities(); i.hasNext(); ) {
            i.next().clearDestroyedNarcPods();
        }
    }

    public void clearFlawedCoolingFlags(Entity entity) {
        // If we're not using quirks, no need to do this check.
        if (!getOptions().booleanOption(OptionsConstants.ADVANCED_STRATOPS_QUIRKS)) {
            return;
        }
        // Only applies to Mechs.
        if (!(entity instanceof Mech)) {
            return;
        }

        // Check for existence of flawed cooling quirk.
        if (!entity.hasQuirk(OptionsConstants.QUIRK_NEG_FLAWED_COOLING)) {
            return;
        }
        entity.setFallen(false);
        entity.setStruck(false);
    }

    /**
     * Get the Kick or Push PSR, modified by weight class
     *
     * @param psrEntity The <code>Entity</code> that should make a PSR
     * @param attacker  The attacking <code>Entity></code>
     * @param target    The target <code>Entity</code>
     * @return The <code>PilotingRollData</code>
     */
    public PilotingRollData getKickPushPSR(Entity psrEntity, Entity attacker,
                                            Entity target, String reason) {
        int mod = 0;
        PilotingRollData psr = new PilotingRollData(psrEntity.getId(), mod,
                reason);
        if (psrEntity.hasQuirk(OptionsConstants.QUIRK_POS_STABLE)) {
            psr.addModifier(-1, "stable", false);
        }
        if (getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_TACOPS_PHYSICAL_PSR)) {

            switch (target.getWeightClass()) {
                case EntityWeightClass.WEIGHT_LIGHT:
                    mod = 1;
                    break;
                case EntityWeightClass.WEIGHT_MEDIUM:
                    mod = 0;
                    break;
                case EntityWeightClass.WEIGHT_HEAVY:
                    mod = -1;
                    break;
                case EntityWeightClass.WEIGHT_ASSAULT:
                    mod = -2;
                    break;
            }
            String reportStr;
            if (mod > 0) {
                reportStr = ("weight class modifier +") + mod;
            } else {
                reportStr = ("weight class modifier ") + mod;
            }
            psr.addModifier(mod, reportStr, false);
        }
        return psr;
    }

    /**
     * Removes all attacks by any dead entities. It does this by going through
     * all the attacks and only keeping ones from active entities. DFAs are kept
     * even if the pilot is unconscious, so that he can fail.
     */
    public void removeDeadAttacks() {
        Vector<EntityAction> toKeep = new Vector<>(actionsSize());

        for (Enumeration<EntityAction> i = getActions(); i.hasMoreElements(); ) {
            EntityAction action = i.nextElement();
            Entity entity = getEntity(action.getEntityId());
            if ((entity != null) && !entity.isDestroyed()
                    && (entity.isActive() || (action instanceof DfaAttackAction))) {
                toKeep.addElement(action);
            }
        }

        // reset actions and re-add valid elements
        resetActions();
        for (EntityAction entityAction : toKeep) {
            addAction(entityAction);
        }
    }

    /**
     * Removes any actions in the attack queue beyond the first by the specified
     * entity, unless that entity has melee master in which case it allows two
     * attacks.
     */
    public void removeDuplicateAttacks(int entityId) {
        int allowed = 1;
        Entity en = getEntity(entityId);
        if (null != en) {
            allowed = en.getAllowedPhysicalAttacks();
        }
        Vector<EntityAction> toKeep = new Vector<>();

        for (Enumeration<EntityAction> i = getActions(); i.hasMoreElements(); ) {
            EntityAction action = i.nextElement();
            if (action.getEntityId() != entityId) {
                toKeep.addElement(action);
            } else if (allowed > 0) {
                toKeep.addElement(action);
                if (!(action instanceof SearchlightAttackAction)) {
                    allowed--;
                }
            } else {
                MegaMek.getLogger().error("Removing duplicate phys attack for id#" + entityId
                        + "\n\t\taction was " + action.toString());
            }
        }

        // reset actions and re-add valid elements
        resetActions();
        for (EntityAction entityAction : toKeep) {
            addAction(entityAction);
        }
    }

    /**
     * Cleans up the attack declarations for the physical phase by removing all
     * attacks past the first for any one mech. Also clears out attacks by dead
     * or disabled mechs.
     */
    public void cleanupPhysicalAttacks() {
        for (Iterator<Entity> i = getEntities(); i.hasNext(); ) {
            Entity entity = i.next();
            removeDuplicateAttacks(entity.getId());
        }
        removeDeadAttacks();
    }

    /**
     * Checks each player to see if he has no entities, and if true, sets the
     * observer flag for that player. An exception is that there are no
     * observers during the lounge phase.
     */
    public void checkForObservers() {
        for (Enumeration<IPlayer> e = getPlayers(); e.hasMoreElements(); ) {
            IPlayer p = e.nextElement();
            p.setObserver((getEntitiesOwnedBy(p) < 1)
                    && (getPhase() != IGame.Phase.PHASE_LOUNGE));
        }
    }

    /**
     * Adds teammates of a player to the Vector. Utility function for whoCanSee.
     */
    public void addTeammates(Vector<IPlayer> vector, IPlayer player) {
        Vector<IPlayer> playersVector = getPlayersVector();
        for (int j = 0; j < playersVector.size(); j++) {
            IPlayer p = playersVector.elementAt(j);
            if (!player.isEnemyOf(p) && !vector.contains(p)) {
                vector.addElement(p);
            }
        }
    }

    /**
     * Adds observers to the Vector. Utility function for whoCanSee.
     */
    public void addObservers(Vector<IPlayer> vector) {
        Vector<IPlayer> playersVector = getPlayersVector();
        for (int j = 0; j < playersVector.size(); j++) {
            IPlayer p = playersVector.elementAt(j);
            if (p.isObserver() && !vector.contains(p)) {
                vector.addElement(p);
            }
        }
    }

    /**
     * Convenience method for computing a mapping of which Coords are
     * "protected" by an APDS. Protection implies that the coords is within the
     * range/arc of an active APDS.
     *
     * @return
     */
    public Hashtable<Coords, List<Mounted>> getAPDSProtectedCoords() {
        // Get all of the coords that would be protected by APDS
        Hashtable<Coords, List<Mounted>> apdsCoords = new Hashtable<>();
        for (Entity e : getEntitiesVector()) {
            // Ignore Entitys without positions
            if (e.getPosition() == null) {
                continue;
            }
            Coords origPos = e.getPosition();
            for (Mounted ams : e.getActiveAMS()) {
                // Ignore non-APDS AMS
                if (!ams.isAPDS()) {
                    continue;
                }
                // Add the current hex as a defended location
                List<Mounted> apdsList = apdsCoords.computeIfAbsent(origPos, k -> new ArrayList<>());
                apdsList.add(ams);
                // Add each coords that is within arc/range as protected
                int maxDist = 3;
                if (e instanceof BattleArmor) {
                    int numTroopers = ((BattleArmor) e)
                            .getNumberActiverTroopers();
                    switch (numTroopers) {
                        case 1:
                            maxDist = 1;
                            break;
                        case 2:
                        case 3:
                            maxDist = 2;
                            break;
                        // Anything above is the same as the default
                    }
                }
                for (int dist = 1; dist <= maxDist; dist++) {
                    List<Coords> coords = e.getPosition().allAtDistance(dist);
                    for (Coords pos : coords) {
                        // Check that we're in the right arc
                        if (Compute.isInArc(this, e.getId(), e.getEquipmentNum(ams),
                                new HexTarget(pos, getBoard(), HexTarget.TYPE_HEX_CLEAR))) {
                            apdsList = apdsCoords.computeIfAbsent(pos, k -> new ArrayList<>());
                            apdsList.add(ams);
                        }
                    }
                }

            }
        }
        return apdsCoords;
    }

    /**
     * For all current artillery attacks in the air from this entity with this
     * weapon, clear the list of spotters. Needed because firing another round
     * before first lands voids spotting.
     *
     * @param entityID the <code>int</code> id of the entity
     * @param weaponID the <code>int</code> id of the weapon
     */
    public void clearArtillerySpotters(int entityID, int weaponID) {
        for (Enumeration<AttackHandler> i = getAttacks(); i.hasMoreElements(); ) {
            WeaponHandler wh = (WeaponHandler) i.nextElement();
            if ((wh.waa instanceof ArtilleryAttackAction)
                    && (wh.waa.getEntityId() == entityID)
                    && (wh.waa.getWeaponId() == weaponID)) {
                ArtilleryAttackAction aaa = (ArtilleryAttackAction) wh.waa;
                aaa.setSpotterIds(null);
            }
        }
    }

    /**
     * Credits a Kill for an entity, if the target got killed.
     *
     * @param target   The <code>Entity</code> that got killed.
     * @param attacker The <code>Entity</code> that did the killing.
     */
    public void creditKill(Entity target, Entity attacker) {
        // Kills should be credited for each individual fighter, instead of the
        // squadron
        if (target instanceof FighterSquadron) {
            return;
        }
        // If a squadron scores a kill, assign it randomly to one of the member fighters
        if (attacker instanceof FighterSquadron) {
            Entity killer = attacker.getLoadedUnits().get(Compute.randomInt(attacker.getLoadedUnits().size()));
            if (killer != null) {
                attacker = killer;
            }
        }
        if ((target.isDoomed() || target.getCrew().isDoomed())
                && !target.getGaveKillCredit() && (attacker != null)) {
            attacker.addKill(target);
        }
    }

    public Vector<GameTurn> checkTurnOrderStranded(TurnVectors team_order) {
        Vector<GameTurn> turns = new Vector<>(team_order.getTotalTurns()  + team_order.getEvenTurns());
        // Stranded units only during movement phases, rebuild the turns vector
        if (getPhase() == IGame.Phase.PHASE_MOVEMENT) {
            // See if there are any loaded units stranded on immobile transports.
            Iterator<Entity> strandedUnits = getSelectedEntities(
                    entity -> isEntityStranded(entity));
            if (strandedUnits.hasNext()) {
                // Add a game turn to unload stranded units, if this
                // is the movement phase.
                turns = new Vector<>(team_order.getTotalTurns()
                        + team_order.getEvenTurns() + 1);
                turns.addElement(new GameTurn.UnloadStrandedTurn(strandedUnits));
            }
        }
        return turns;
    }

    /**
     * Skip offboard phase, if there is no homing / semiguided ammo in play
     */
    public boolean isOffboardPlayable() {
        if (!hasMoreTurns()) {
            return false;
        }

        for (Iterator<Entity> e = getEntities(); e.hasNext();) {
            Entity entity = e.next();
            for (Mounted mounted : entity.getAmmo()) {
                AmmoType ammoType = (AmmoType) mounted.getType();

                // per errata, TAG will spot for LRMs and such
                if ((ammoType.getAmmoType() == AmmoType.T_LRM)
                        || (ammoType.getAmmoType() == AmmoType.T_LRM_IMP)
                        || (ammoType.getAmmoType() == AmmoType.T_MML)
                        || (ammoType.getAmmoType() == AmmoType.T_NLRM)
                        || (ammoType.getAmmoType() == AmmoType.T_MEK_MORTAR)) {
                    return true;
                }

                if (((ammoType.getAmmoType() == AmmoType.T_ARROW_IV)
                        || (ammoType.getAmmoType() == AmmoType.T_LONG_TOM)
                        || (ammoType.getAmmoType() == AmmoType.T_SNIPER)
                        || (ammoType.getAmmoType() == AmmoType.T_THUMPER))
                        && (ammoType.getMunitionType() == AmmoType.M_HOMING)) {
                    return true;
                }
            }

            for (Mounted b : entity.getBombs()) {
                if (!b.isDestroyed() && (b.getUsableShotsLeft() > 0)
                        && (((BombType) b.getType()).getBombType() == BombType.B_LG)) {
                    return true;
                }
            }
        }

        // loop through all current attacks
        // if there are any that use homing ammo, we are playable
        // we need to do this because we might have a homing arty shot in flight
        // when the unit that mounted that ammo is no longer on the field
        for (Enumeration<AttackHandler> attacks = getAttacks(); attacks.hasMoreElements(); ) {
            AttackHandler attackHandler = attacks.nextElement();
            Mounted ammo = attackHandler.getWaa().getEntity(this)
                    .getEquipment(attackHandler.getWaa().getAmmoId());
            if (ammo != null) {
                AmmoType ammoType = (AmmoType) ammo.getType();
                if (ammoType.getMunitionType() == AmmoType.M_HOMING) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Should we play this phase or skip it?
     */
    public boolean isPhasePlayable(IGame.Phase phase) {
        switch (phase) {
            case PHASE_INITIATIVE:
            case PHASE_END:
                return false;
            case PHASE_SET_ARTYAUTOHITHEXES:
            case PHASE_DEPLOY_MINEFIELDS:
            case PHASE_DEPLOYMENT:
            case PHASE_MOVEMENT:
            case PHASE_FIRING:
            case PHASE_PHYSICAL:
            case PHASE_TARGETING:
                return hasMoreTurns();
            case PHASE_OFFBOARD:
                return isOffboardPlayable();
            default:
                return true;
        }
    }

    /**
     * save the game
     *
     * @param sFile    The <code>String</code> filename to use
     * @return A <code>String</code> of the path to store the game
     */
    public String saveGame(String sFile) {
        // We need to strip the .gz if it exists,
        // otherwise we'll double up on it.
        if (sFile.endsWith(".gz")) {
            sFile = sFile.replace(".gz", "");
        }
        XStream xstream = new XStream();

        // This will make save games much smaller
        // by using a more efficient means of referencing
        // objects in the XML graph
        xstream.setMode(XStream.ID_REFERENCES);

        String sFinalFile = sFile;
        if (!sFinalFile.endsWith(".sav")) {
            sFinalFile = sFile + ".sav";
        }
        File sDir = new File("savegames");
        if (!sDir.exists()) {
            sDir.mkdir();
        }

        sFinalFile = sDir + File.separator + sFinalFile;

        try (OutputStream os = new FileOutputStream(sFinalFile + ".gz");
             OutputStream gzo = new GZIPOutputStream(os);
             Writer writer = new OutputStreamWriter(gzo, StandardCharsets.UTF_8)) {

            xstream.toXML(this, writer);
        } catch (Exception e) {
            MegaMek.getLogger().error("Unable to save file: " + sFinalFile, e);
        }
        return sFinalFile;
    }

    public List<Building.DemolitionCharge> getExplodingCharges() {
        return explodingCharges;
    }

    public void setExplodingCharges(List<Building.DemolitionCharge> explodingCharges) {
        this.explodingCharges = explodingCharges;
    }

    public void addExplodingCharge(Building.DemolitionCharge charge) {
        this.explodingCharges.add(charge);
    }

    private List<Building.DemolitionCharge> explodingCharges = new ArrayList<>();

    public ArrayList<int[]> getScheduledNukes() {
        return scheduledNukes;
    }

    public void setScheduledNukes(ArrayList<int[]> scheduledNukes) {
        this.scheduledNukes = scheduledNukes;
    }

    private ArrayList<int[]> scheduledNukes = new ArrayList<>();

    /**
     * add a nuke to be exploded in the next weapons attack phase
     *
     * @param nuke this is an int[] with i=0 and i=1 being X and Y coordinates respectively,
     *             If the input array is length 3, then i=2 is NukeType (from HS:3070)
     *             If the input array is length 6, then i=2 is the base damage dealt,
     *             i=3 is the degradation, i=4 is the secondary radius, and i=5 is the crater depth
     */
    public void addScheduledNuke(int[] nuke) {
        scheduledNukes.add(nuke);
    }







}
