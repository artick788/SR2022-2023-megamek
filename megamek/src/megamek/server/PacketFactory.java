package megamek.server;

import megamek.common.*;
import megamek.common.actions.ArtilleryAttackAction;
import megamek.common.actions.EntityAction;
import megamek.common.net.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.util.BoardUtilities;
import megamek.common.weapons.AttackHandler;
import megamek.common.weapons.WeaponHandler;

import java.util.*;

public class PacketFactory {

    /**
     * Creates a packet detailing the removal of an entity. Maintained for
     * backwards compatibility.
     *
     * @param entityId - the <code>int</code> ID of the entity being removed.
     * @return A <code>Packet</code> to be sent to clients.
     */
    static public Packet createRemoveEntityPacket(int entityId) {
        return createRemoveEntityPacket(entityId, IEntityRemovalConditions.REMOVE_SALVAGEABLE);
    }

    /**
     * Creates a packet detailing the removal of an entity.
     *
     * @param entityId  - the <code>int</code> ID of the entity being removed.
     * @param condition - the <code>int</code> condition the unit was in. This value
     *                  must be one of constants in
     *                  <code>IEntityRemovalConditions</code>, or an
     *                  <code>IllegalArgumentException</code> will be thrown.
     * @return A <code>Packet</code> to be sent to clients.
     */
    static public Packet createRemoveEntityPacket(int entityId, int condition) {
        List<Integer> ids = new ArrayList<>(1);
        ids.add(entityId);
        return createRemoveEntityPacket(ids, condition);
    }

    /**
     * Creates a packet detailing the removal of a list of entities.
     *
     * @param entityIds - the <code>int</code> ID of each entity being removed.
     * @param condition - the <code>int</code> condition the units were in. This value
     *                  must be one of constants in
     *                  <code>IEntityRemovalConditions</code>, or an
     *                  <code>IllegalArgumentException</code> will be thrown.
     * @return A <code>Packet</code> to be sent to clients.
     */
    static public Packet createRemoveEntityPacket(List<Integer> entityIds, int condition) {
        if ((condition != IEntityRemovalConditions.REMOVE_UNKNOWN)
                && (condition != IEntityRemovalConditions.REMOVE_IN_RETREAT)
                && (condition != IEntityRemovalConditions.REMOVE_PUSHED)
                && (condition != IEntityRemovalConditions.REMOVE_SALVAGEABLE)
                && (condition != IEntityRemovalConditions.REMOVE_EJECTED)
                && (condition != IEntityRemovalConditions.REMOVE_CAPTURED)
                && (condition != IEntityRemovalConditions.REMOVE_DEVASTATED)
                && (condition != IEntityRemovalConditions.REMOVE_NEVER_JOINED)) {
            throw new IllegalArgumentException("Unknown unit condition: " + condition);
        }
        Object[] array = new Object[2];
        array[0] = entityIds;
        array[1] = condition;
        return new Packet(Packet.COMMAND_ENTITY_REMOVE, array);
    }

    /**
     * Creates a packet containing a hex, and the coordinates it goes at.
     */
    static public Packet createHexChangePacket(Coords coords, IHex hex) {
        final Object[] data = new Object[2];
        data[0] = coords;
        data[1] = hex;
        return new Packet(Packet.COMMAND_CHANGE_HEX, data);
    }

    /**
     * Creates a packet containing a hex, and the coordinates it goes at.
     */
    static public Packet createHexesChangePacket(Set<Coords> coords, Set<IHex> hex) {
        final Object[] data = new Object[2];
        data[0] = coords;
        data[1] = hex;
        return new Packet(Packet.COMMAND_CHANGE_HEXES, data);
    }

    /**
     * Creates a packet for an attack
     */
    static public Packet createAttackPacket(List<?> vector, int charges) {
        final Object[] data = new Object[2];
        data[0] = vector;
        data[1] = charges;
        return new Packet(Packet.COMMAND_ENTITY_ATTACK, data);
    }

    /**
     * Creates a packet for an attack
     */
    static public Packet createAttackPacket(EntityAction ea, int charge) {
        Vector<EntityAction> vector = new Vector<>(1);
        vector.addElement(ea);
        Object[] data = new Object[2];
        data[0] = vector;
        data[1] = charge;
        return new Packet(Packet.COMMAND_ENTITY_ATTACK, data);
    }

    /**
     * Tell the clients to replace the given building with rubble hexes.
     *
     * @param coords - the <code>Coords</code> that has collapsed.
     * @return a <code>Packet</code> for the command.
     */
    static public Packet createCollapseBuildingPacket(Coords coords) {
        Vector<Coords> coordsV = new Vector<>();
        coordsV.addElement(coords);
        return createCollapseBuildingPacket(coordsV);
    }

    /**
     * Tell the clients to replace the given building hexes with rubble hexes.
     *
     * @param coords - a <code>Vector</code> of <code>Coords</code>s that has
     *               collapsed.
     * @return a <code>Packet</code> for the command.
     */
    static public Packet createCollapseBuildingPacket(Vector<Coords> coords) {
        return new Packet(Packet.COMMAND_BLDG_COLLAPSE, coords);
    }

    /**
     * Tell the clients to update the CFs of the given buildings.
     *
     * @param buildings - a <code>Vector</code> of <code>Building</code>s that need to
     *                  be updated.
     * @return a <code>Packet</code> for the command.
     */
    static public Packet createUpdateBuildingPacket(Vector<Building> buildings) {
        return new Packet(Packet.COMMAND_BLDG_UPDATE, buildings);
    }

    /**
     * Creates a packet containing the game settings
     */
    static public Packet createGameSettingsPacket(IGame game) {
        return new Packet(Packet.COMMAND_SENDING_GAME_SETTINGS, game.getOptions());
    }

    /**
     * Creates a packet containing the game board
     */
    static public Packet createBoardPacket(IGame game) {
        return new Packet(Packet.COMMAND_SENDING_BOARD, game.getBoard());
    }

    /**
     * Creates a packet containing a single entity, for update
     */
    static public Packet createEntityPacket(IGame game, int entityId, Vector<UnitLocation> movePath) {
        final Entity entity = game.getEntity(entityId);
        final Object[] data = new Object[3];
        data[0] = entityId;
        data[1] = entity;
        data[2] = movePath;
        return new Packet(Packet.COMMAND_ENTITY_UPDATE, data);
    }

    /**
     * Creates a packet containing the planetary conditions
     */
    static public Packet createPlanetaryConditionsPacket(IGame game) {
        return new Packet(Packet.COMMAND_SENDING_PLANETARY_CONDITIONS, game.getPlanetaryConditions());
    }

    static public Packet createFullEntitiesPacket(IGame game) {
        final Object[] data = new Object[2];
        data[0] = game.getEntitiesVector();
        data[1] = game.getOutOfGameEntitiesVector();
        return new Packet(Packet.COMMAND_SENDING_ENTITIES, data);
    }

    static public Packet createAddEntityPacket(IGame game, int entityId) {
        ArrayList<Integer> entityIds = new ArrayList<>(1);
        entityIds.add(entityId);
        return createAddEntityPacket(game, entityIds);
    }

    /**
     * Creates a packet detailing the addition of an entity
     */
    static public Packet createAddEntityPacket(IGame game, List<Integer> entityIds) {
        ArrayList<Entity> entities = new ArrayList<>(entityIds.size());
        for(Integer id : entityIds) {
            entities.add(game.getEntity(id));
        }

        final Object[] data = new Object[2];
        data[0] = entityIds;
        data[1] = entities;
        return new Packet(Packet.COMMAND_ENTITY_ADD, data);
    }

    /**
     * Creates a packet containing all current entities
     */
    static public Packet createEntitiesPacket(IGame game) {
        return new Packet(Packet.COMMAND_SENDING_ENTITIES, game.getEntitiesVector());
    }

    /**
     * Creates a packet containing flares
     */
    static public Packet createFlarePacket(IGame game) {
        return new Packet(Packet.COMMAND_SENDING_FLARES, game.getFlares());
    }

    static public Packet createIlluminatedHexesPacket(IGame game) {
        HashSet<Coords> illuminateHexes = game.getIlluminatedPositions();
        return new Packet(Packet.COMMAND_SENDING_ILLUM_HEXES, illuminateHexes);
    }

    /**
     * Creates a packet containing off board artillery attacks
     */
    static public Packet createArtilleryPacket(IGame game, IPlayer p) {
        Vector<ArtilleryAttackAction> v = new Vector<>();
        int team = p.getTeam();
        for (Enumeration<AttackHandler> i = game.getAttacks(); i.hasMoreElements(); ) {
            WeaponHandler wh = (WeaponHandler) i.nextElement();
            if (wh.waa instanceof ArtilleryAttackAction) {
                ArtilleryAttackAction aaa = (ArtilleryAttackAction) wh.waa;
                if ((aaa.getPlayerId() == p.getId())
                        || ((team != IPlayer.TEAM_NONE)
                        && (team == game.getPlayer(aaa.getPlayerId()).getTeam()))
                        || p.getSeeAll()) {
                    v.addElement(aaa);
                }
            }
        }
        return new Packet(Packet.COMMAND_SENDING_ARTILLERYATTACKS, v);
    }

    static public Packet createTagInfoUpdatesPacket(IGame game) {
        return new Packet(Packet.COMMAND_SENDING_TAGINFO, game.getTagInfo());
    }

    static public Packet createSpecialHexDisplayPacket(IGame game, int toPlayer) {
        Hashtable<Coords, Collection<SpecialHexDisplay>> shdTable = game
                .getBoard().getSpecialHexDisplayTable();
        Hashtable<Coords, Collection<SpecialHexDisplay>> shdTable2 = new Hashtable<>();
        LinkedList<SpecialHexDisplay> tempList;
        IPlayer player = game.getPlayer(toPlayer);
        if (player != null) {
            for (Coords coord : shdTable.keySet()) {
                tempList = new LinkedList<>();
                for (SpecialHexDisplay shd : shdTable.get(coord)) {
                    if (!shd.isObscured(player)) {
                        tempList.add(0, shd);
                    }
                }
                if (!tempList.isEmpty()) {
                    shdTable2.put(coord, tempList);
                }
            }
        }
        return new Packet(Packet.COMMAND_SENDING_SPECIAL_HEX_DISPLAY, shdTable2);
    }

    /**
     * Creates a packet containing a vector of mines.
     */
    static public Packet createMineChangePacket(IGame game, Coords coords) {
        return new Packet(Packet.COMMAND_UPDATE_MINEFIELDS, game.getMinefields(coords));
    }

    static public Packet createMapSizesPacket() {
        Set<BoardDimensions> sizes = BoardUtilities.getBoardSizes();
        return new Packet(Packet.COMMAND_SENDING_AVAILABLE_MAP_SIZES, sizes);
    }

    /**
     * Creates a packet containing the current turn index
     */
    static public Packet createTurnIndexPacket(IGame game, int playerId) {
        final Object[] data = new Object[3];
        data[0] = game.getTurnIndex();
        data[1] = playerId;
        return new Packet(Packet.COMMAND_TURN, data);
    }

    /**
     * Creates a packet containing the player ready status
     */
    static public Packet createPlayerDonePacket(IGame game, int playerId) {
        Object[] data = new Object[2];
        data[0] = playerId;
        data[1] = game.getPlayer(playerId).isDone();
        return new Packet(Packet.COMMAND_PLAYER_READY, data);
    }

    /**
     * Creates a packet containing the current turn vector
     */
    static public Packet createTurnVectorPacket(IGame game) {
        return new Packet(Packet.COMMAND_SENDING_TURNS, game.getTurnVector());
    }

    /**
     * Creates a packet informing that the player has connected
     */
    static public Packet createPlayerConnectPacket(IGame game, int playerId) {
        final Object[] data = new Object[2];
        data[0] = playerId;
        data[1] = game.getPlayer(playerId);
        return new Packet(Packet.COMMAND_PLAYER_ADD, data);
    }

    /**
     * Creates a packet containing the player info, for update
     */
    static public Packet createPlayerUpdatePacket(IGame game, int playerId) {
        final Object[] data = new Object[2];
        data[0] = playerId;
        data[1] = game.getPlayer(playerId);
        return new Packet(Packet.COMMAND_PLAYER_UPDATE, data);
    }

    /**
     * Creates a packet containing the map settings
     */
    static public Packet createMapSettingsPacket(MapSettings mapSettings) {
        return new Packet(Packet.COMMAND_SENDING_MAP_SETTINGS, mapSettings);
    }

    /**
     * Creates a packet containing a Vector of Reports
     */
    static public Packet createReportPacket(IPlayer p, IGame game, ReportManager reportManager) {
        // When the final report is created, MM sends a null player to create
        // the report. This will handle that issue.
        if ((p == null) || !game.doBlind()) {
            return new Packet(Packet.COMMAND_SENDING_REPORTS, reportManager.getvPhaseReport());
        }
        return new Packet(Packet.COMMAND_SENDING_REPORTS, reportManager.filterReportVector(game, reportManager.getvPhaseReport(), p));
    }

    /**
     * Creates a packet containing a Vector of special Reports which needs to be
     * sent during a phase that is not a report phase.
     */
    static public Packet createSpecialReportPacket(ReportManager reportManager) {
        return new Packet(Packet.COMMAND_SENDING_REPORTS_SPECIAL, reportManager.getvPhaseReport().clone());
    }

    /**
     * Creates a packet containing a Vector of Reports that represent a Tactical
     * Genius re-roll request which needs to update a current phase's report.
     */
    static public Packet createTacticalGeniusReportPacket(ReportManager reportManager) {
        return new Packet(Packet.COMMAND_SENDING_REPORTS_TACTICAL_GENIUS, reportManager.getvPhaseReport().clone());
    }

    /**
     * Creates a packet containing all the round reports
     */
    static public Packet createAllReportsPacket(IPlayer p, IGame game, ReportManager reportManager) {
        return new Packet(Packet.COMMAND_SENDING_REPORTS_ALL,
                reportManager.filterPastReports(game, game.getAllReports(), p));
    }

    /**
     * Creates a packet containing all entities, including wrecks, visible to
     * the player in a blind game
     */
    static public Packet createFilteredFullEntitiesPacket(IPlayer p, IGame game, GameManager gameManager) {
        final Object[] data = new Object[2];
        data[0] = filterEntities(p, game.getEntitiesVector(), null, game, gameManager);
        data[1] = game.getOutOfGameEntitiesVector();
        return new Packet(Packet.COMMAND_SENDING_ENTITIES, data);
    }

    /**
     * Creates a packet containing all entities visible to the player in a blind game
     */
    static public Packet createFilteredEntitiesPacket(IPlayer p, Map<EntityTargetPair, LosEffects> losCache, IGame game, GameManager gameManager) {
        return new Packet(Packet.COMMAND_SENDING_ENTITIES,
                filterEntities(p, game.getEntitiesVector(), losCache, game, gameManager));
    }

    /**
     * Creates a packet indicating end of game, including detailed unit status
     */
    static public Packet createEndOfGamePacket(IGame game, ReportManager reportManager) {
        Object[] array = new Object[3];
        array[0] = reportManager.getDetailedVictoryReport(game);
        array[1] = game.getVictoryPlayerId();
        array[2] = game.getVictoryTeam();
        return new Packet(Packet.COMMAND_END_OF_GAME, array);
    }

    /**
     * Filters an entity vector according to LOS
     */
    static private List<Entity> filterEntities(IPlayer pViewer,
                                        List<Entity> vEntities,
                                        Map<EntityTargetPair, LosEffects> losCache, IGame game, GameManager gameManager) {
        if (losCache == null) {
            losCache = new HashMap<>();
        }
        Vector<Entity> vCanSee = new Vector<>();
        Vector<Entity> vMyEntities = new Vector<>();
        boolean bTeamVision = game.getOptions().booleanOption(OptionsConstants.ADVANCED_TEAM_VISION);

        // If they can see all, return the input list
        if (pViewer.canSeeAll()) {
            return vEntities;
        }

        List<ECMInfo> allECMInfo = null;
        if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_SENSORS)) {
            allECMInfo = ComputeECM.computeAllEntitiesECMInfo(game.getEntitiesVector());
        }

        // If they're an observer, they can see anything seen by any enemy.
        if (pViewer.isObserver()) {
            vMyEntities.addAll(vEntities);
            for (Entity a : vMyEntities) {
                for (Entity b : vMyEntities) {
                    if (a.isEnemyOf(b) && Compute.canSee(game, b, a, true, null, allECMInfo)) {
                        gameManager.addVisibleEntity(vCanSee, a);
                        break;
                    }
                }
            }
            return vCanSee;
        }

        // If they aren't an observer and can't see all, create the list of
        // "friendly" units.
        for (Entity e : vEntities) {
            if ((e.getOwner() == pViewer) || (bTeamVision && !e.getOwner().isEnemyOf(pViewer))) {
                vMyEntities.addElement(e);
            }
        }

        // Then, break down the list by whether they're friendly,
        // or whether or not any friendly unit can see them.
        for (Entity e : vEntities) {
            // If it's their own unit, obviously, they can see it.
            if (vMyEntities.contains(e)) {
                gameManager.addVisibleEntity(vCanSee, e);
                continue;
            } else if (e.isHidden()) {
                // If it's NOT friendly and is hidden, they can't see it,
                // period.
                // LOS doesn't matter.
                continue;
            }
            for (Entity spotter : vMyEntities) {

                // If they're off-board, skip it; they can't see anything.
                if (spotter.isOffBoard()) {
                    continue;
                }

                // See if the LosEffects is cached, and if not cache it
                EntityTargetPair etp = new EntityTargetPair(spotter, e);
                LosEffects los = losCache.get(etp);
                if (los == null) {
                    los = LosEffects.calculateLos(game, spotter.getId(), e);
                    losCache.put(etp, los);
                }
                // Otherwise, if they can see the entity in question
                if (Compute.canSee(game, spotter, e, true, los, allECMInfo)) {
                    gameManager.addVisibleEntity(vCanSee, e);
                    break;
                }

                // If this unit has ECM, players with units affected by the ECM
                //  will need to know about this entity, even if they can't see
                //  it.  Otherwise, the client can't properly report things
                //  like to-hits.
                if ((e.getECMRange() > 0) && (e.getPosition() != null) &&
                        (spotter.getPosition() != null)) {
                    int ecmRange = e.getECMRange();
                    Coords pos = e.getPosition();
                    if (pos.distance(spotter.getPosition()) <= ecmRange) {
                        gameManager.addVisibleEntity(vCanSee, e);
                    }
                }
            }
        }

        return vCanSee;
    }
}
