package megamek.server;

import megamek.MegaMek;
import megamek.common.*;
import megamek.common.actions.*;
import megamek.common.net.Packet;
import megamek.common.options.OptionsConstants;

import java.util.*;

public class EntityManager {

    private IGame game;
    private GameManager gameManager;
    private ReportManager reportManager;

    // TODO: remove this later
    private Server server;

    public EntityManager(IGame game, GameManager gameManager, Server server) {
        this.game = game;
        this.gameManager = gameManager;
        this.server = server;
        this.reportManager = server.getReportmanager();
    }

    /**
     * Mark the unit as destroyed! Units transported in the destroyed unit will
     * get a chance to escape.
     *
     * @param entity - the <code>Entity</code> that has been destroyed.
     * @param reason - a <code>String</code> detailing why the entity was
     *               destroyed.
     * @return a <code>Vector</code> of <code>Report</code> objects that can be
     * sent to the output log.
     */
    public Vector<Report> destroyEntity(Entity entity, String reason) {
        return destroyEntity(entity, reason, true);
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
    public Vector<Report> destroyEntity(Entity entity, String reason, boolean survivable, boolean canSalvage) {
        // can't destroy an entity if it's already been destroyed
        if(entity.isDestroyed()) {
            return new Vector<>();
        }

        Vector<Report> vDesc = new Vector<>();
        Report r;

        //We'll need this later...
        Aero ship = null;
        if (entity.isLargeCraft()) {
            ship = (Aero) entity;
        }

        // regardless of what was passed in, units loaded onto aeros not on the
        // ground are destroyed
        if (entity.isAirborne()) {
            survivable = false;
        } else if (entity.isAero()) {
            survivable = true;
        }

        // The unit can suffer an ammo explosion after it has been destroyed.
        int condition = IEntityRemovalConditions.REMOVE_SALVAGEABLE;
        if (!canSalvage) {
            entity.setSalvage(false);
            condition = IEntityRemovalConditions.REMOVE_DEVASTATED;
        }

        // Destroy the entity, unless it's already destroyed.
        if (!entity.isDoomed() && !entity.isDestroyed()) {
            r = new Report(6365);
            r.subject = entity.getId();
            r.addDesc(entity);
            r.add(reason);
            vDesc.addElement(r);

            entity.setDoomed(true);

            // Kill any picked up MechWarriors
            Vector<Integer> mechWarriors = entity.getPickedUpMechWarriors();
            for(Integer mechWarriorId : mechWarriors) {
                Entity mw = game.getEntity(mechWarriorId);

                // in some situations, a "picked up" mechwarrior won't actually exist
                // probably this is brought about by picking up a mechwarrior in a previous MekHQ scenario
                // then having the same unit get blown up in a subsequent scenario
                // in that case, we simply move on
                if(mw == null) {
                    continue;
                }

                mw.setDestroyed(true);
                // We can safely remove these, as they can't be targeted
                game.removeEntity(mw.getId(), condition);
                entityUpdate(mw.getId());
                server.send(PacketFactory.createRemoveEntityPacket(mw.getId(), condition));
                r = new Report(6370);
                r.subject = mw.getId();
                r.addDesc(mw);
                vDesc.addElement(r);
            }

            // make any remaining telemissiles operated by this entity
            // out of contact
            for (int missileId : entity.getTMTracker().getMissiles()) {
                Entity tm = game.getEntity(missileId);
                if ((null != tm) && !tm.isDestroyed() && (tm instanceof TeleMissile)) {
                    ((TeleMissile) tm).setOutContact(true);
                    entityUpdate(tm.getId());
                }
            }

            // Mechanized BA that could die on a 3+
            ArrayList<Entity> externalUnits = entity.getExternalUnits();

            // Handle escape of transported units.
            if (entity.getLoadedUnits().size() > 0) {
                Coords curPos = entity.getPosition();
                int curFacing = entity.getFacing();
                for (Entity other : entity.getLoadedUnits()) {
                    //If the unit has been destroyed (as from a cargo hit), skip it
                    if (other.isDestroyed()) {
                        continue;
                    }
                    // Can the other unit survive?
                    boolean survived = false;
                    if (entity instanceof Tank) {
                        if ((entity.getMovementMode() == EntityMovementMode.NAVAL)
                                || (entity.getMovementMode() == EntityMovementMode.HYDROFOIL)) {
                            if (other.getMovementMode() == EntityMovementMode.INF_UMU) {
                                survived = Compute.d6() <= 3;
                            } else if (other.getMovementMode() == EntityMovementMode.INF_JUMP) {
                                survived = Compute.d6() == 1;
                            } else if (other.getMovementMode() == EntityMovementMode.VTOL) {
                                survived = Compute.d6() <= 2;
                            }
                        } else if (entity.getMovementMode() == EntityMovementMode.SUBMARINE) {
                            if (other.getMovementMode() == EntityMovementMode.INF_UMU) {
                                survived = Compute.d6() == 1;
                            }
                        } else {
                            survived = Compute.d6() <= 4;
                        }
                    } else if (entity instanceof Mech) {
                        // mechanized BA can escape on a roll of 1 or 2
                        if (externalUnits.contains(other)) {
                            survived = Compute.d6() < 3;
                        }
                    }
                    if (!survivable || (externalUnits.contains(other) && !survived)
                            //Don't unload from ejecting spacecraft. The crews aren't in their units...
                            || (ship != null && ship.isEjecting())) {
                        // Nope.
                        other.setDestroyed(true);
                        // We need to unload the unit, since it's ID goes away
                        entity.unload(other);
                        // Safe to remove, as they aren't targeted
                        game.moveToGraveyard(other.getId());
                        server.send(PacketFactory.createRemoveEntityPacket(other.getId(), condition));
                        r = new Report(6370);
                        r.subject = other.getId();
                        r.addDesc(other);
                        vDesc.addElement(r);
                    }
                    // Can we unload the unit to the current hex?
                    // TODO : unloading into stacking violation is not
                    // explicitly prohibited in the BMRr.
                    else if ((null != Compute.stackingViolation(game, other.getId(), curPos))
                            || other.isLocationProhibited(curPos)) {
                        // Nope.
                        other.setDestroyed(true);
                        // We need to unload the unit, since it's ID goes away
                        entity.unload(other);
                        // Safe to remove, as they aren't targeted
                        game.moveToGraveyard(other.getId());
                        server.send(PacketFactory.createRemoveEntityPacket(other.getId(), condition));
                        r = new Report(6375);
                        r.subject = other.getId();
                        r.addDesc(other);
                        vDesc.addElement(r);
                    } // End can-not-unload
                    else {
                        // The other unit survives.
                        server.unloadUnit(entity, other, curPos, curFacing,
                                entity.getElevation(), true, false);
                    }

                } // Handle the next transported unit.

            } // End has-transported-unit

            // Handle transporting unit.
            if (Entity.NONE != entity.getTransportId()) {
                final Entity transport = game.getEntity(entity.getTransportId());
                Coords curPos = transport.getPosition();
                int curFacing = transport.getFacing();
                if (!transport.isLargeCraft()) {
                    server.unloadUnit(transport, entity, curPos, curFacing, transport.getElevation());
                }
                entityUpdate(transport.getId());

                // if this is the last fighter in a fighter squadron then remove
                // the squadron
                if ((transport instanceof FighterSquadron)
                        && transport.getSubEntities().orElse(Collections.emptyList()).isEmpty()) {
                    transport.setDestroyed(true);
                    // Can't remove this here, otherwise later attacks will fail
                    //game.moveToGraveyard(transport.getId());
                    //entityUpdate(transport.getId());
                    //send(PacketFactory.createRemoveEntityPacket(transport.getId(), condition));
                    r = new Report(6365);
                    r.subject = transport.getId();
                    r.addDesc(transport);
                    r.add("fighter destruction");
                    vDesc.addElement(r);
                }

            } // End unit-is-transported

            // Is this unit towing some trailers?
            // If so, disconnect them
            if (!entity.getAllTowedUnits().isEmpty()) {
                //Find the first trailer in the list and drop it
                //this will disconnect all that follow too
                Entity leadTrailer = game.getEntity(entity.getAllTowedUnits().get(0));
                server.disconnectUnit(entity, leadTrailer, entity.getPosition());
            }

            // Is this unit a trailer being towed? If so, disconnect it from its tractor
            if (entity.getTractor() != Entity.NONE) {
                Entity tractor = game.getEntity(entity.getTractor());
                server.disconnectUnit(tractor, entity, tractor.getPosition());
            }

            // Is this unit being swarmed?
            final int swarmerId = entity.getSwarmAttackerId();
            if (Entity.NONE != swarmerId) {
                final Entity swarmer = game.getEntity(swarmerId);

                swarmer.setSwarmTargetId(Entity.NONE);
                // a unit that stopped swarming due to the swarmed unit dieing
                // should be able to move: setSwarmTargetId to Entity.None
                // changes done to true and unloaded to true, need to undo this
                swarmer.setUnloaded(false);
                swarmer.setDone(false);
                entity.setSwarmAttackerId(Entity.NONE);
                Report.addNewline(vDesc);
                r = new Report(6380);
                r.subject = swarmerId;
                r.addDesc(swarmer);
                vDesc.addElement(r);
                // Swarming infantry shouldn't take damage when their target dies
                // http://bg.battletech.com/forums/total-warfare/swarming-question
                entityUpdate(swarmerId);
            }

            // Is this unit swarming somebody?
            final int swarmedId = entity.getSwarmTargetId();
            if (Entity.NONE != swarmedId) {
                final Entity swarmed = game.getEntity(swarmedId);
                swarmed.setSwarmAttackerId(Entity.NONE);
                entity.setSwarmTargetId(Entity.NONE);
                r = new Report(6385);
                r.subject = swarmed.getId();
                r.addDesc(swarmed);
                vDesc.addElement(r);
                entityUpdate(swarmedId);
            }

            // If in a grapple, release both mechs
            if (entity.getGrappled() != Entity.NONE) {
                int grappler = entity.getGrappled();
                entity.setGrappled(Entity.NONE, false);
                Entity e = game.getEntity(grappler);
                if (e != null) {
                    e.setGrappled(Entity.NONE, false);
                }
                entityUpdate(grappler);
            }
        } // End entity-not-already-destroyed.

        // if using battlefield wreckage rules, then the destruction of this
        // unit
        // might convert the hex to rough
        Coords curPos = entity.getPosition();
        IHex entityHex = game.getBoard().getHex(curPos);
        if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_BATTLE_WRECK)
                && (entityHex != null) && game.getBoard().onGround()
                && !((entity instanceof Infantry) || (entity instanceof Protomech))) {
            // large support vees will create ultra rough, otherwise rough
            if (entity instanceof LargeSupportTank) {
                if (entityHex.terrainLevel(Terrains.ROUGH) < 2) {
                    entityHex.addTerrain(Terrains.getTerrainFactory()
                            .createTerrain(Terrains.ROUGH, 2));
                    gameManager.sendChangedHex(game, curPos);
                }
            } else if ((entity.getWeight() >= 40) && !entityHex.containsTerrain(Terrains.ROUGH)) {
                entityHex.addTerrain(Terrains.getTerrainFactory()
                        .createTerrain(Terrains.ROUGH, 1));
                gameManager.sendChangedHex(game, curPos);
            }
        }

        // update our entity, so clients have correct data needed for MekWars stuff
        entityUpdate(entity.getId());

        return vDesc;
    }

    /**
     * In a double-blind game, update only visible entities. Otherwise, update
     * everyone
     */
    public void entityUpdate(int nEntityID) {
        entityUpdate(nEntityID, new Vector<>(), true, null);
    }

    /**
     * In a double-blind game, update only visible entities. Otherwise, update
     * everyone
     *
     * @param updateVisibility Flag that determines if whoCanSee needs to be
     *                         called to update who can see the entity for
     *                         double-blind games.
     */
    public void entityUpdate(int nEntityID, Vector<UnitLocation> movePath, boolean updateVisibility,
                             Map<EntityTargetPair, LosEffects> losCache) {
        Entity eTarget = game.getEntity(nEntityID);
        if (eTarget == null) {
            if (game.getOutOfGameEntity(nEntityID) != null) {
                MegaMek.getLogger().error("S: attempted to send entity update for out of game entity, id was " + nEntityID);
            } else {
                MegaMek.getLogger().error("S: attempted to send entity update for null entity, id was " + nEntityID);
            }

            return; // do not send the update it will crash the client
        }

        // If we're doing double blind, be careful who can see it...
        if (game.doBlind()) {
            Vector<IPlayer> playersVector = game.getPlayersVector();
            Vector<IPlayer> vCanSee;
            if (updateVisibility) {
                vCanSee = server.whoCanSee(eTarget, true, losCache);
            } else {
                vCanSee = eTarget.getWhoCanSee();
            }

            // If this unit has ECM, players with units affected by the ECM will
            //  need to know about this entity, even if they can't see it.
            //  Otherwise, the client can't properly report things like to-hits.
            if ((eTarget.getECMRange() > 0) && (eTarget.getPosition() != null)) {
                int ecmRange = eTarget.getECMRange();
                Coords pos = eTarget.getPosition();
                for (Entity ent : game.getEntitiesVector()) {
                    if ((ent.getPosition() != null) && (pos.distance(ent.getPosition()) <= ecmRange)) {
                        if (!vCanSee.contains(ent.getOwner())) {
                            vCanSee.add(ent.getOwner());
                        }
                    }
                }
            }

            // send an entity update to everyone who can see
            Packet pack = PacketFactory.createEntityPacket(game, nEntityID, movePath);
            for (int x = 0; x < vCanSee.size(); x++) {
                IPlayer p = vCanSee.elementAt(x);
                server.send(p.getId(), pack);
            }
            // send an entity delete to everyone else
            pack = PacketFactory.createRemoveEntityPacket(nEntityID, eTarget.getRemovalCondition());
            for (int x = 0; x < playersVector.size(); x++) {
                if (!vCanSee.contains(playersVector.elementAt(x))) {
                    IPlayer p = playersVector.elementAt(x);
                    server.send(p.getId(), pack);
                }
            }

            entityUpdateLoadedUnits(eTarget, vCanSee, playersVector);
        } else {
            // But if we're not, then everyone can see.
            server.send(PacketFactory.createEntityPacket(game, nEntityID, movePath));
        }
    }

    /**
     * Whenever updating an Entity, we also need to update all of its loaded
     * Entity's, otherwise it could cause issues with Clients.
     *
     * @param loader        An Entity being updated that is transporting units that should
     *                      also send an update
     * @param vCanSee       The list of Players who can see the loader.
     * @param playersVector The list of all Players
     */
    private void entityUpdateLoadedUnits(Entity loader, Vector<IPlayer> vCanSee, Vector<IPlayer> playersVector) {
        Packet pack;

        // In double-blind, the client may not know about the loaded units,
        // so we need to send them.
        for (Entity eLoaded : loader.getLoadedUnits()) {
            // send an entity update to everyone who can see
            pack = PacketFactory.createEntityPacket(game, eLoaded.getId(), null);
            for (int x = 0; x < vCanSee.size(); x++) {
                IPlayer p = vCanSee.elementAt(x);
                server.send(p.getId(), pack);
            }
            // send an entity delete to everyone else
            pack = PacketFactory.createRemoveEntityPacket(eLoaded.getId(), eLoaded.getRemovalCondition());
            for (int x = 0; x < playersVector.size(); x++) {
                if (!vCanSee.contains(playersVector.elementAt(x))) {
                    IPlayer p = playersVector.elementAt(x);
                    server.send(p.getId(), pack);
                }
            }
            entityUpdateLoadedUnits(eLoaded, vCanSee, playersVector);
        }
    }

    /**
     * Send the complete list of entities to the players. If double_blind is in
     * effect, enforce it by filtering the entities
     */
    public void entityAllUpdate() {
        // If double-blind is in effect, filter each players' list individually,
        // and then quit out...
        if (game.doBlind()) {
            Vector<IPlayer> playersVector = game.getPlayersVector();
            for (int x = 0; x < playersVector.size(); x++) {
                IPlayer p = playersVector.elementAt(x);
                server.send(p.getId(), PacketFactory.createFilteredEntitiesPacket(p, null, game, gameManager));
            }
            return;
        }

        // Otherwise, send the full list.
        server.send(PacketFactory.createEntitiesPacket(game));
    }

    /**
     * Called at the beginning of each phase. Sets and resets any entity
     * parameters that need to be reset.
     */
    public void resetEntityPhase(IGame.Phase phase) {
        // first, mark doomed entities as destroyed and flag them
        Vector<Entity> toRemove = new Vector<>(0, 10);
        for (Entity entity : game.getEntitiesVector()) {
            entity.newPhase(phase);
            if (entity.isDoomed()) {
                entity.setDestroyed(true);

                // Is this unit swarming somebody? Better let go before
                // it's too late.
                final int swarmedId = entity.getSwarmTargetId();
                if (Entity.NONE != swarmedId) {
                    final Entity swarmed = game.getEntity(swarmedId);
                    swarmed.setSwarmAttackerId(Entity.NONE);
                    entity.setSwarmTargetId(Entity.NONE);
                    Report r = new Report(5165);
                    r.subject = swarmedId;
                    r.addDesc(swarmed);
                    reportManager.addReport(r);
                    entityUpdate(swarmedId);
                }
            }

            if (entity.isDestroyed()) {
                //Leaving destroyed entities in dropship bays alone
                if (!(game.getEntity(entity.getTransportId()) != null && game.getEntity(entity.getTransportId()).isLargeCraft())) {
                    toRemove.addElement(entity);
                }
            }
        }

        // actually remove all flagged entities
        for (Entity entity : toRemove) {
            int condition = IEntityRemovalConditions.REMOVE_SALVAGEABLE;
            if (!entity.isSalvage()) {
                condition = IEntityRemovalConditions.REMOVE_DEVASTATED;
            }

            entityUpdate(entity.getId());
            game.removeEntity(entity.getId(), condition);
            server.send(PacketFactory.createRemoveEntityPacket(entity.getId(), condition));
        }

        // do some housekeeping on all the remaining
        for (Entity entity : game.getEntitiesVector()) {
            entity.applyDamage();
            entity.reloadEmptyWeapons();

            // reset damage this phase telemissiles need a record of damage last phase
            entity.damageThisRound += entity.damageThisPhase;
            entity.damageThisPhase = 0;
            entity.engineHitsThisPhase = 0;
            entity.rolledForEngineExplosion = false;
            entity.dodging = false;
            entity.setShutDownThisPhase(false);
            entity.setStartupThisPhase(false);

            // reset done to false
            if (phase == IGame.Phase.PHASE_DEPLOYMENT) {
                entity.setDone(!entity.shouldDeploy(game.getRoundCount()));
            } else {
                entity.setDone(false);
            }

            // reset spotlights
            entity.setIlluminated(false);
            entity.setUsedSearchlight(false);
            entity.setCarefulStand(false);
            entity.setNetworkBAP(false);

            if (entity instanceof MechWarrior) {
                ((MechWarrior) entity).setLanded(true);
            }
        }
        game.clearIlluminatedPositions();
        server.send(new Packet(Packet.COMMAND_CLEAR_ILLUM_HEXES));
    }

    /**
     * receive and process an entity mode change packet
     *
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     */
    public void receiveEntityModeChange(Packet c, int connIndex) {
        int entityId = c.getIntValue(0);
        int equipId = c.getIntValue(1);
        int mode = c.getIntValue(2);
        Entity e = game.getEntity(entityId);
        if (e.getOwner() != game.getPlayer(connIndex)) {
            return;
        }
        Mounted m = e.getEquipment(equipId);

        if (m == null) {
            return;
        }

        try {
            // Check for BA dumping body mounted missile launchers
            if ((e instanceof BattleArmor) && (!m.isMissing())
                    && m.isBodyMounted()
                    && m.getType().hasFlag(WeaponType.F_MISSILE)
                    && (m.getLinked() != null)
                    && (m.getLinked().getUsableShotsLeft() > 0)
                    && (mode <= 0)) {
                m.setPendingDump(mode == -1);
                // a mode change for ammo means dumping or hot loading
            } else if ((m.getType() instanceof AmmoType) && !m.getType().hasInstantModeSwitch() && (mode < 0 || mode == 0 && m.isPendingDump())) {
                m.setPendingDump(mode == -1);
            } else if ((m.getType() instanceof WeaponType) && m.isDWPMounted() && (mode <= 0)) {
                m.setPendingDump(mode == -1);
            } else {
                if (!m.setMode(mode)) {
                    String message = e.getShortName() + ": " + m.getName() + ": " + e.getLocationName(m.getLocation())
                            + " trying to compensate";
                    MegaMek.getLogger().error(message);
                    server.sendServerChat(message);
                    e.setGameOptions();

                    if (!m.setMode(mode)) {
                        message = e.getShortName() + ": " + m.getName() + ": " + e.getLocationName(m.getLocation())
                                + " unable to compensate";
                        MegaMek.getLogger().error(message);
                        server.sendServerChat(message);
                    }
                }
            }
        } catch (Exception ex) {
            MegaMek.getLogger().error(ex);
        }
    }

    /**
     * Receive and process an Entity Sensor Change Packet
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     */
    public void receiveEntitySensorChange(Packet c, int connIndex) {
        int entityId = c.getIntValue(0);
        int sensorId = c.getIntValue(1);
        Entity e = game.getEntity(entityId);
        e.setNextSensor(e.getSensors().elementAt(sensorId));
    }

    /**
     * Receive and process an Entity Heat Sinks Change Packet
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     */
    public void receiveEntitySinksChange(Packet c, int connIndex) {
        int entityId = c.getIntValue(0);
        int numSinks = c.getIntValue(1);
        Entity e = game.getEntity(entityId);
        if ((e instanceof Mech) && (connIndex == e.getOwnerId())) {
            ((Mech)e).setActiveSinksNextRound(numSinks);
        }
    }

    /**
     *
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     */
    public void receiveEntityActivateHidden(Packet c, int connIndex) {
        int entityId = c.getIntValue(0);
        IGame.Phase phase = (IGame.Phase)c.getObject(1);
        Entity e = game.getEntity(entityId);
        if (connIndex != e.getOwnerId()) {
            MegaMek.getLogger().error("Player " + connIndex + " tried to activate a hidden unit owned by Player " + e.getOwnerId());
            return;
        }
        e.setHiddeActivationPhase(phase);
        entityUpdate(entityId);
    }

    /**
     * receive and process an entity nova network mode change packet
     *
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     */
    public void receiveEntityNovaNetworkModeChange(Packet c, int connIndex) {

        try {
            int entityId = c.getIntValue(0);
            String networkID = c.getObject(1).toString();
            Entity e = game.getEntity(entityId);
            if (e.getOwner() != game.getPlayer(connIndex)) {
                return;
            }
            // FIXME: Greg: This can result in setting the network to link to hostile units.
            // However, it should be caught by both the isMemberOfNetwork test from the c3 module as well as
            // by the clients possible input.
            e.setNewRoundNovaNetworkString(networkID);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * receive and process an entity mounted facing change packet
     *
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     */
    public void receiveEntityMountedFacingChange(Packet c, int connIndex) {
        int entityId = c.getIntValue(0);
        int equipId = c.getIntValue(1);
        int facing = c.getIntValue(2);
        Entity e = game.getEntity(entityId);
        if (e.getOwner() != game.getPlayer(connIndex)) {
            return;
        }
        Mounted m = e.getEquipment(equipId);

        if (m == null) {
            return;
        }
        m.setFacing(facing);
    }

    /**
     * receive and process an entity mode change packet
     *
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     */
    public void receiveEntityCalledShotChange(Packet c, int connIndex) {
        int entityId = c.getIntValue(0);
        int equipId = c.getIntValue(1);
        Entity e = game.getEntity(entityId);
        if (e.getOwner() != game.getPlayer(connIndex)) {
            return;
        }
        Mounted m = e.getEquipment(equipId);

        if (m == null) {
            return;
        }
        m.getCalledShot().switchCalledShot();
    }

    /**
     * receive and process an entity system mode change packet
     *
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     */
    public void receiveEntitySystemModeChange(Packet c, int connIndex) {
        int entityId = c.getIntValue(0);
        int equipId = c.getIntValue(1);
        int mode = c.getIntValue(2);
        Entity e = game.getEntity(entityId);
        if (e.getOwner() != game.getPlayer(connIndex)) {
            return;
        }
        if ((e instanceof Mech) && (equipId == Mech.SYSTEM_COCKPIT)) {
            ((Mech) e).setCockpitStatus(mode);
        }
    }

    /**
     * Receive a packet that contains an Entity ammo change
     *
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     */
    public void receiveEntityAmmoChange(Packet c, int connIndex) {
        int entityId = c.getIntValue(0);
        int weaponId = c.getIntValue(1);
        int ammoId = c.getIntValue(2);
        Entity e = game.getEntity(entityId);

        // Did we receive a request for a valid Entity?
        if (null == e) {
            MegaMek.getLogger().error("Could not find entity# " + entityId);
            return;
        }
        IPlayer player = game.getPlayer(connIndex);
        if ((null != player) && (e.getOwner() != player)) {
            MegaMek.getLogger().error("Player " + player.getName() + " does not own the entity " + e.getDisplayName());
            return;
        }

        // Make sure that the entity has the given equipment.
        Mounted mWeap = e.getEquipment(weaponId);
        Mounted mAmmo = e.getEquipment(ammoId);
        if (null == mAmmo) {
            MegaMek.getLogger().error("Entity " + e.getDisplayName() + " does not have ammo #" + ammoId);
            return;
        }
        if (!(mAmmo.getType() instanceof AmmoType)) {
            MegaMek.getLogger().error("Item #" + ammoId + " of entity " + e.getDisplayName()
                    + " is a " + mAmmo.getName() + " and not ammo.");
            return;
        }
        if (null == mWeap) {
            MegaMek.getLogger().error("Entity " + e.getDisplayName() + " does not have weapon #" + weaponId);
            return;
        }
        if (!(mWeap.getType() instanceof WeaponType)) {
            MegaMek.getLogger().error("Item #" + weaponId + " of entity " + e.getDisplayName()
                    + " is a " + mWeap.getName() + " and not a weapon.");
            return;
        }
        if (((WeaponType) mWeap.getType()).getAmmoType() == AmmoType.T_NA) {
            MegaMek.getLogger().error("Item #" + weaponId + " of entity " + e.getDisplayName()
                    + " is a " + mWeap.getName() + " and does not use ammo.");
            return;
        }
        if (mWeap.getType().hasFlag(WeaponType.F_ONESHOT)
                && !mWeap.getType().hasFlag(WeaponType.F_DOUBLE_ONESHOT)) {
            MegaMek.getLogger().error("Item #" + weaponId + " of entity " + e.getDisplayName()
                    + " is a " + mWeap.getName() + " and cannot use external ammo.");
            return;
        }

        // Load the weapon.
        e.loadWeapon(mWeap, mAmmo);
    }

    /**
     * Deletes an entity owned by a certain player from the list
     */
    public void receiveEntityDelete(Packet c, int connIndex) {
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) c.getObject(0);
        for (Integer entityId : ids) {
            final Entity entity = game.getEntity(entityId);

            // Only allow players to delete their *own* entities.
            if ((entity != null) && (entity.getOwner() == game.getPlayer(connIndex))) {

                // If we're deleting a ProtoMech, recalculate unit numbers.
                if (entity instanceof Protomech) {

                    // How many ProtoMechs does the player have (include this one)?
                    int numPlayerProtos = game.getSelectedEntityCount(new EntitySelector() {
                        private final int ownerId = entity.getOwnerId();

                        public boolean accept(Entity entity) {
                            return (entity instanceof Protomech) && (ownerId == entity.getOwnerId());
                        }
                    });

                    // According to page 54 of the BMRr, ProtoMechs must be
                    // deployed in full Points of five, unless "losses" have
                    // reduced the number to less than that.
                    final char oldMax = (char) (Math.ceil(numPlayerProtos / 5.0) - 1);
                    char newMax = (char) (Math.ceil((numPlayerProtos - 1) / 5.0) - 1);
                    short deletedUnitNum = entity.getUnitNumber();

                    // Do we have to update a ProtoMech from the last unit?
                    if ((oldMax != deletedUnitNum) && (oldMax != newMax)) {

                        // Yup. Find a ProtoMech from the last unit, and
                        // set it's unit number to the deleted entity.
                        Iterator<Entity> lastUnit = game.getSelectedEntities(new EntitySelector() {
                                    private final int ownerId = entity.getOwnerId();

                                    private final char lastUnitNum = oldMax;

                                    public boolean accept(Entity entity) {
                                        return (entity instanceof Protomech)
                                                && (ownerId == entity.getOwnerId())
                                                && (lastUnitNum == entity.getUnitNumber());
                                    }
                                });
                        Entity lastUnitMember = lastUnit.next();
                        lastUnitMember.setUnitNumber(deletedUnitNum);
                        entityUpdate(lastUnitMember.getId());
                    } // End update-unit-number
                } // End added-ProtoMech

                if (game.getPhase() != IGame.Phase.PHASE_DEPLOYMENT) {
                    // if a unit is removed during deployment just keep going
                    // without adjusting the turn vector.
                    game.removeTurnFor(entity);
                    game.removeEntity(entityId, IEntityRemovalConditions.REMOVE_NEVER_JOINED);
                }
            }
        }

        // during deployment this absolutely must be called before game.removeEntity(), otherwise the game hangs
        // when a unit is removed. Cause unknown.
        server.send(PacketFactory.createRemoveEntityPacket(ids, IEntityRemovalConditions.REMOVE_NEVER_JOINED));

        // Prevents deployment hanging. Only do this during deployment.
        if (game.getPhase() == IGame.Phase.PHASE_DEPLOYMENT) {
            for (Integer entityId : ids) {
                final Entity entity = game.getEntity(entityId);
                game.removeEntity(entityId, IEntityRemovalConditions.REMOVE_NEVER_JOINED);
                server.endCurrentTurn(entity);
            }
        }
    }

    /**
     * loop through entities in the exchange phase (i.e. after leaving
     * chat lounge) and do any actions that need to be done
     */
    public void checkEntityExchange() {
        for (Entity entity : game.getEntitiesVector()) {
            // apply bombs
            if (entity.isBomber()) {
                ((IBomber)entity).applyBombs();
            }

            if (entity.isAero()) {
                IAero a = (IAero) entity;
                if (a.isSpaceborne()) {
                    // altitude and elevation don't matter in space
                    a.liftOff(0);
                } else {
                    // check for grounding
                    if (game.getBoard().inAtmosphere() && !entity.isAirborne()) {
                        // you have to be airborne on the atmospheric map
                        a.liftOff(entity.getAltitude());
                    }
                }

                if (entity.isFighter()) {
                    a.updateWeaponGroups();
                    entity.loadAllWeapons();
                }
            }

            // if units were loaded in the chat lounge, I need to keep track of
            // it here because they can get dumped in the deployment phase
            if (entity.getLoadedUnits().size() > 0) {
                Vector<Integer> v = new Vector<>();
                for (Entity en : entity.getLoadedUnits()) {
                    v.add(en.getId());
                }
                entity.setLoadedKeepers(v);
            }

            if (game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_AERO_SANITY)
                    && (entity.isAero())) {
                Aero a = null;
                if (entity instanceof Aero) {
                    a = (Aero) entity;
                }
                if (entity.isCapitalScale()) {
                    if (a != null) {
                        int currentSI = a.getSI() * 20;
                        a.initializeSI(a.get0SI() * 20);
                        a.setSI(currentSI);
                    }
                    if (entity.isCapitalFighter()) {
                        ((IAero)entity).autoSetCapArmor();
                        ((IAero)entity).autoSetFatalThresh();
                    } else {
                        // all armor and SI is going to be at standard scale, so
                        // we need to adjust
                        for (int loc = 0; loc < entity.locations(); loc++) {
                            if (entity.getArmor(loc) > 0) {
                                int currentArmor = entity.getArmor(loc) * 10;
                                entity.initializeArmor(entity.getOArmor(loc) * 10, loc);
                                entity.setArmor(currentArmor, loc);

                            }
                        }
                    }
                } else if (a != null) {
                    int currentSI = a.getSI() * 2;
                    a.initializeSI(a.get0SI() * 2);
                    a.setSI(currentSI);
                }
            }
            // Give the unit a spotlight, if it has the spotlight quirk
            entity.setExternalSpotlight(entity.hasExternaSpotlight()
                    || entity.hasQuirk(OptionsConstants.QUIRK_POS_SEARCHLIGHT));
            entityUpdate(entity.getId());

            // Remove hot-loading some from LRMs for meks
            if (!game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_HOTLOAD_IN_GAME)) {
                for (Entity e : game.getEntitiesVector()) {
                    // Vehicles are allowed to hot load, just meks cannot
                    if (!(e instanceof Mech)) {
                        continue;
                    }
                    for (Mounted weapon : e.getWeaponList()) {
                        weapon.getType().removeMode("HotLoad");
                    }
                    for (Mounted ammo : e.getAmmo()) {
                        ammo.getType().removeMode("HotLoad");
                    }
                }
            }
        }
    }

    /**
     * Removes all entities owned by a player. Should only be called when it
     * won't cause trouble (the lounge, for instance, or between phases.)
     *
     * @param player whose entities are to be removed
     */
    public void removeAllEntitiesOwnedBy(IPlayer player) {
        Vector<Entity> toRemove = new Vector<>();

        for (Entity entity : game.getEntitiesVector()) {
            if (entity.getOwner().equals(player)) {
                toRemove.addElement(entity);
            }
        }

        for (Entity entity : toRemove) {
            int id = entity.getId();
            game.removeEntity(id, IEntityRemovalConditions.REMOVE_NEVER_JOINED);
            server.send(PacketFactory.createRemoveEntityPacket(id, IEntityRemovalConditions.REMOVE_NEVER_JOINED));
        }
    }

    /**
     * Check a list of entity Ids for doomed entities and destroy those.
     */
    public void destroyDoomedEntities(Vector<Integer> entityIds) {
        Vector<Entity> toRemove = new Vector<>(0, 10);
        for (Integer entityId : entityIds) {
            Entity entity = game.getEntity(entityId);
            if (entity.isDoomed()) {
                entity.setDestroyed(true);

                // Is this unit swarming somebody? Better let go before it's too late.
                final int swarmedId = entity.getSwarmTargetId();
                if (Entity.NONE != swarmedId) {
                    final Entity swarmed = game.getEntity(swarmedId);
                    swarmed.setSwarmAttackerId(Entity.NONE);
                    entity.setSwarmTargetId(Entity.NONE);
                    reportManager.addReport(ReportFactory.createReport(5165, 0, swarmed));
                    entityUpdate(swarmedId);
                }
            }

            if (entity.isDestroyed()) {
                toRemove.addElement(entity);
            }
        }

        // actually remove all flagged entities
        for (Entity entity : toRemove) {
            int condition = IEntityRemovalConditions.REMOVE_SALVAGEABLE;
            if (!entity.isSalvage()) {
                condition = IEntityRemovalConditions.REMOVE_DEVASTATED;
            }
            // If we removed a unit during the movement phase that hasn't moved,
            // remove its turn.
            if ((game.getPhase() == IGame.Phase.PHASE_MOVEMENT) && entity.isSelectableThisTurn()) {
                game.removeTurnFor(entity);
                server.send(PacketFactory.createTurnVectorPacket(game));
            }
            entityUpdate(entity.getId());
            game.removeEntity(entity.getId(), condition);
            server.send(PacketFactory.createRemoveEntityPacket(entity.getId(), condition));
        }
    }

    private int calculateMovementPointsUsed(MovePath md, Entity entity) {
        if (md.hasActiveMASC()) {
            return entity.getRunMP();
        } else {
            return entity.getRunMPwithoutMASC();
        }
    }

    /**
     * Steps through an entity movement packet, executing it.
     *
     * @param entity   The Entity that is moving
     * @param md       The MovePath that defines how the Entity moves
     * @param losCache A cache that stores Los between various Entities and
     *                 targets.  In double blind games, we may need to compute a
     *                 lot of LosEffects, so caching them can really speed
     *                 things up.
     */
    public void processMovement(Entity entity, MovePath md, Map<EntityTargetPair, LosEffects> losCache) {
        // Make sure the cache isn't null
        if (losCache == null) {
            losCache = new HashMap<>();
        }
        boolean sideslipped = false; // for VTOL side slipping
        PilotingRollData rollTarget;

        // check for fleeing
        if (md.contains(MovePath.MoveStepType.FLEE)) {
            reportManager.addReport(processLeaveMap(md, false, -1));
            return;
        }

        if (md.contains(MovePath.MoveStepType.EJECT)) {
            if (entity.isLargeCraft() && !entity.isCarcass()) {
                reportManager.addReport(ReportFactory.createReport(2026, entity));
                Aero ship = (Aero) entity;
                ship.setEjecting(true);
                entityUpdate(ship.getId());
                Coords legalPos = entity.getPosition();
                //Get the step so we can pass it in and get the abandon coords from it
                Vector<MoveStep> steps = md.getStepVector();
                for (MoveStep step : steps) {
                    if (step.getType() == MovePath.MoveStepType.EJECT) {
                        legalPos = step.getTargetPosition();
                    }
                }
                reportManager.addReport(ejectSpacecraft(ship, ship.isSpaceborne(), (ship.isAirborne() && !ship.isSpaceborne()),legalPos));
                //If we're grounded or destroyed by crew loss, end movement
                if (entity.isDoomed() || (!entity.isSpaceborne() && !entity.isAirborne())) {
                    return;
                }
            } else if ((entity instanceof Mech) || (entity instanceof Aero)) {
                reportManager.addReport(ReportFactory.createReport(2020, entity, entity.getCrew().getName()));
                reportManager.addReport(ejectEntity(entity, false));
                return;
            } else if ((entity instanceof Tank) && !entity.isCarcass()) {
                reportManager.addReport(ReportFactory.createReport(2025, entity));
                reportManager.addReport(ejectEntity(entity, false));
                return;
            }
        }

        if (md.contains(MovePath.MoveStepType.CAREFUL_STAND)) {
            entity.setCarefulStand(true);
        }
        if (md.contains(MovePath.MoveStepType.BACKWARDS)) {
            entity.setMovedBackwards(true);
            if (md.getMpUsed() > entity.getWalkMP()) {
                entity.setPowerReverse(true);
            }
        }

        if (entity.isAero()) {
            IAero a = (IAero) entity;
            if (md.contains(MovePath.MoveStepType.TAKEOFF)) {
                a.setCurrentVelocity(1);
                a.liftOff(1);
                if (entity instanceof Dropship) {
                    applyDropShipProximityDamage(md.getFinalCoords(), true, md.getFinalFacing(), entity);
                }
                checkForTakeoffDamage(a);
                entity.setPosition(entity.getPosition().translated(entity.getFacing(), a.getTakeOffLength()));
            }

            if (md.contains(MovePath.MoveStepType.VTAKEOFF)) {
                rollTarget = a.checkVerticalTakeOff();
                if (doVerticalTakeOffCheck(entity, rollTarget)) {
                    a.setCurrentVelocity(0);
                    a.liftOff(1);
                    if (entity instanceof Dropship) {
                        applyDropShipProximityDamage(md.getFinalCoords(), (Dropship) a);
                    }
                    checkForTakeoffDamage(a);
                }
            }

            if (md.contains(MovePath.MoveStepType.LAND)) {
                rollTarget = a.checkLanding(md.getLastStepMovementType(), md.getFinalVelocity(),
                        md.getFinalCoords(), md.getFinalFacing(), false);
                attemptLanding(entity, rollTarget);
                a.land();
                entity.setPosition(md.getFinalCoords().translated(md.getFinalFacing(), a.getLandingLength()));
            }

            if (md.contains(MovePath.MoveStepType.VLAND)) {
                rollTarget = a.checkLanding(md.getLastStepMovementType(), md.getFinalVelocity(),
                        md.getFinalCoords(), md.getFinalFacing(), true);
                attemptLanding(entity, rollTarget);
                if (entity instanceof Dropship) {
                    applyDropShipLandingDamage(md.getFinalCoords(), (Dropship) a);
                }
                a.land();
                entity.setPosition(md.getFinalCoords());
            }

            entity.setDone(true);
            entityUpdate(entity.getId());
            return;
        }

        // okay, proceed with movement calculations
        Coords lastPos = entity.getPosition();
        Coords curPos = entity.getPosition();
        int curFacing = entity.getFacing();
        int curVTOLElevation = entity.getElevation();
        int curElevation;
        int lastElevation = entity.getElevation();
        int curAltitude = entity.getAltitude();
        boolean curClimbMode = entity.climbMode();
        // if the entity already used some MPs,
        // it previously tried to get up and fell,
        // and then got another turn. set moveType
        // and overallMoveType accordingly
        // (these are all cleared by Entity.newRound)
        int distance = entity.delta_distance;
        int mpUsed = entity.mpUsed;
        EntityMovementType moveType = entity.moved;
        EntityMovementType overallMoveType;
        boolean wasProne = entity.isProne();
        boolean fellDuringMovement = false;
        boolean crashedDuringMovement = false;
        boolean dropshipStillUnloading = false;
        int prevFacing = curFacing;
        IHex prevHex = game.getBoard().getHex(curPos);
        final boolean isInfantry = entity instanceof Infantry;
        AttackAction charge = null;
        RamAttackAction ram = null;
        // cache this here, otherwise changing MP in the turn causes
        // erroneous gravity PSRs
        int cachedGravityLimit = -1;
        int thrustUsed = 0;
        int j = 0;
        boolean recovered = false;
        Entity loader = null;
        boolean continueTurnFromPBS = false;
        boolean continueTurnFromFishtail = false;
        boolean continueTurnFromLevelDrop = false;
        boolean continueTurnFromCliffAscent = false;

        // get a list of coordinates that the unit passed through this turn
        // so that I can later recover potential bombing targets
        // it may already have some values
        Vector<Coords> passedThrough = entity.getPassedThrough();
        passedThrough.add(curPos);
        List<Integer> passedThroughFacing = entity.getPassedThroughFacing();
        passedThroughFacing.add(curFacing);

        // Compile the move - don't clip
        // Clipping could affect hidden units; illegal steps aren't processed
        md.compile(game, entity, false);

        // if advanced movement is being used then set the new vectors based on
        // move path
        entity.setVectors(md.getFinalVectors());

        overallMoveType = md.getLastStepMovementType();

        // check for starting in liquid magma
        if ((game.getBoard().getHex(entity.getPosition()).terrainLevel(Terrains.MAGMA) == 2)
                && (entity.getElevation() == 0)) {
            server.doMagmaDamage(entity, false);
        }

        // set acceleration used to default
        if (entity.isAero()) {
            ((IAero)entity).setAccLast(false);
        }

        // check for dropping troops and drop them
        if (entity.isDropping() && !md.contains(MovePath.MoveStepType.HOVER)) {
            entity.setAltitude(entity.getAltitude() - game.getPlanetaryConditions().getDropRate());
            // they may have changed their facing
            if (md.length() > 0) {
                entity.setFacing(md.getFinalFacing());
            }
            passedThrough.add(entity.getPosition());
            entity.setPassedThrough(passedThrough);
            passedThroughFacing.add(entity.getFacing());
            entity.setPassedThroughFacing(passedThroughFacing);
            // We may still need to process any conversions for dropping LAMs
            if (entity instanceof LandAirMech && md.contains(MovePath.MoveStepType.CONVERT_MODE)) {
                entity.setMovementMode(md.getFinalConversionMode());
                entity.setConvertingNow(true);

                int reportID = 1210;
                if (entity.getMovementMode() == EntityMovementMode.WIGE) {
                    reportID = 2452;
                } else if (entity.getMovementMode() == EntityMovementMode.AERODYNE) {
                    reportID = 2453;
                } else {
                    reportID = 2450;
                }
                reportManager.addReport(ReportFactory.createReport(reportID, entity));
            }
            entity.setDone(true);
            entityUpdate(entity.getId());
            return;
        }

        // iterate through steps
        boolean firstStep = true;
        boolean turnOver = false;
        /* Bug 754610: Revert fix for bug 702735. */
        MoveStep prevStep = null;

        List<Entity> hiddenEnemies = new ArrayList<>();
        if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_HIDDEN_UNITS)) {
            for (Entity e : game.getEntitiesVector()) {
                if (e.isHidden() && e.isEnemyOf(entity) && (e.getPosition() != null)) {
                    hiddenEnemies.add(e);
                }
            }
        }

        Vector<UnitLocation> movePath = new Vector<>();
        EntityMovementType lastStepMoveType = md.getLastStepMovementType();
        for (final Enumeration<MoveStep> i = md.getSteps(); i.hasMoreElements();) {
            final MoveStep step = i.nextElement();
            EntityMovementType stepMoveType = step.getMovementType(md.isEndStep(step));
            wasProne = entity.isProne();
            boolean isPavementStep = step.isPavementStep();
            entity.inReverse = step.isThisStepBackwards();
            boolean entityFellWhileAttemptingToStand = false;
            boolean isOnGround = (!i.hasMoreElements() || stepMoveType != EntityMovementType.MOVE_JUMP)
                    && step.getElevation() < 1;

            // Check for hidden units point blank shots
            if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_HIDDEN_UNITS)) {
                for (Entity e : hiddenEnemies) {
                    int dist = e.getPosition().distance(step.getPosition());
                    // Checking for same hex and stacking violation
                    if ((dist == 0) && !continueTurnFromPBS && (Compute.stackingViolation(game, entity.getId(),
                            step.getPosition()) != null)) {
                        // Moving into hex of a hidden unit detects the unit
                        e.setHidden(false);
                        entityUpdate(e.getId());
                        reportManager.addReport(ReportFactory.createReport(9960, entity, e.getPosition().getBoardNum()));
                        // Report the block
                        if (game.doBlind()) {
                            reportManager.addReport(ReportFactory.createReport(9961, e, step.getPosition().getBoardNum()));
                        }
                        // Report halted movement
                        reportManager.addReport(ReportFactory.createReport(9962, entity, step.getPosition().getBoardNum()));
                        reportManager.addNewLines();
                        Report.addNewline(reportManager.getvPhaseReport());
                        // If we aren't at the end, send a special report
                        if ((game.getTurnIndex() + 1) < game.getTurnVector().size()) {
                            server.send(e.getOwner().getId(), PacketFactory.createSpecialReportPacket(reportManager));
                            server.send(entity.getOwner().getId(), PacketFactory.createSpecialReportPacket(reportManager));
                        }
                        entity.setDone(true);
                        entityUpdate(entity.getId(), movePath, true, losCache);
                        return;
                        // Potential point-blank shot
                    } else if ((dist == 1) && !e.madePointblankShot()) {
                        entity.setPosition(step.getPosition());
                        entity.setFacing(step.getFacing());
                        // If not set, BV icons could have wrong facing
                        entity.setSecondaryFacing(step.getFacing());
                        // Update entity position on client
                        server.send(e.getOwnerId(), PacketFactory.createEntityPacket(game, entity.getId(), null));
                        boolean tookPBS = server.processPointblankShotCFR(e, entity);
                        // Movement should be interrupted
                        if (tookPBS) {
                            // Attacking reveals hidden unit
                            e.setHidden(false);
                            entityUpdate(e.getId());
                            reportManager.addReport(ReportFactory.createReport(9960, entity, e.getPosition().getBoardNum()));
                            continueTurnFromPBS = true;

                            curFacing = entity.getFacing();
                            curPos = entity.getPosition();
                            mpUsed = step.getMpUsed();
                            break;
                        }
                    }
                }
            }

            // stop for illegal movement
            if (stepMoveType == EntityMovementType.MOVE_ILLEGAL
                    // stop if the entity already killed itself
                    || entity.isDestroyed() || entity.isDoomed()) {
                break;
            }

            if (entity.getMovementMode() == EntityMovementMode.WIGE) {
                if (step.getType() == MovePath.MoveStepType.UP && !entity.isAirborneVTOLorWIGE()) {
                    entity.setWigeLiftoffHover(true);
                } else if (step.getType() == MovePath.MoveStepType.HOVER) {
                    entity.setWigeLiftoffHover(true);
                    entity.setAssaultDropInProgress(false);
                } else if (step.getType() == MovePath.MoveStepType.DOWN && step.getClearance() == 0) {
                    // If this is the first step, use the Entity's starting elevation
                    int elevation = (prevStep == null) ? entity.getElevation() : prevStep.getElevation();
                    if (entity instanceof LandAirMech) {
                        reportManager.addReport(server.landAirMech((LandAirMech) entity, step.getPosition(), elevation,
                                distance));
                    } else if (entity instanceof Protomech) {
                        reportManager.addReport(server.landGliderPM((Protomech) entity, step.getPosition(), elevation,
                                distance));
                    }
                    // landing always ends movement whether successful or not
                }
            }

            // check for MASC failure on first step also check Tanks because
            // they can have superchargers that act like MASc
            if (firstStep && ((entity instanceof Mech) || (entity instanceof Tank))) {
                // Not necessarily a fall, but we need to give them a new turn to plot movement with
                // likely reduced MP.
                fellDuringMovement = checkMASCFailure(entity, md);
            }

            if (firstStep) {
                rollTarget = entity.checkGunningIt(overallMoveType);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    int mof = server.doSkillCheckWhileMoving(entity, lastElevation, lastPos, curPos, rollTarget, false);
                    if (mof > 0) {
                        // Since this is the first step, we don't have a previous step so we'll pass
                        // this one in case it's needed to process a skid.
                        if (processFailedVehicleManeuver(entity, curPos, 0, step,
                                step.isThisStepBackwards(), lastStepMoveType, distance, 2, mof)) {
                            mpUsed = calculateMovementPointsUsed(md, entity);

                            turnOver = true;
                            distance = entity.delta_distance;
                            curFacing = entity.getFacing();
                            entity.setSecondaryFacing(curFacing);
                            break;
                        } else if (entity.getFacing() != curFacing) {
                            // If the facing doesn't change we had a minor fishtail that doesn't require
                            // stopping movement.
                            continueTurnFromFishtail = true;
                            curFacing = entity.getFacing();
                            entity.setSecondaryFacing(curFacing);
                            break;
                        }
                    }
                }
            }

            // Check for failed maneuver for overdrive on first step. The rules for overdrive do not
            // state this explicitly, but since combining overdrive with gunning it requires two rolls
            // and gunning does state explicitly that the roll is made before movement, this
            // implies the same for overdrive.
            if (firstStep && (overallMoveType == EntityMovementType.MOVE_SPRINT || overallMoveType == EntityMovementType.MOVE_VTOL_SPRINT)) {
                rollTarget = entity.checkUsingOverdrive(EntityMovementType.MOVE_SPRINT);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    int mof = server.doSkillCheckWhileMoving(entity, lastElevation, lastPos, curPos, rollTarget, false);
                    if (mof > 0) {
                        if (processFailedVehicleManeuver(entity, curPos, 0, step,
                                step.isThisStepBackwards(), lastStepMoveType, distance, 2, mof)) {
                            mpUsed = calculateMovementPointsUsed(md, entity);

                            turnOver = true;
                            distance = entity.delta_distance;
                            curFacing = entity.getFacing();
                            entity.setSecondaryFacing(curFacing);
                            break;
                        } else if (entity.getFacing() != curFacing) {
                            // If the facing doesn't change we had a minor fishtail that doesn't require
                            // stopping movement.
                            continueTurnFromFishtail = true;
                            curFacing = entity.getFacing();
                            entity.setSecondaryFacing(curFacing);
                            break;
                        }
                    }
                }
            }

            if (step.getType() == MovePath.MoveStepType.CONVERT_MODE) {
                entity.setConvertingNow(true);

                // Non-omni QuadVees converting to vehicle mode dump any riding BA in the
                // starting hex if they fail to make an anti-mech check.
                // http://bg.battletech.com/forums/index.php?topic=55263.msg1271423#msg1271423
                if (entity instanceof QuadVee && entity.getConversionMode() == QuadVee.CONV_MODE_MECH
                        && !entity.isOmni()) {
                    for (Entity rider : entity.getExternalUnits()) {
                        reportManager.addReport(checkDropBAFromConverting(entity, rider, curPos, curFacing,
                                false, false, false));
                    }
                } else if ((entity.getEntityType() & Entity.ETYPE_LAND_AIR_MECH) != 0) {
                    //External units on LAMs, including swarmers, fall automatically and take damage,
                    //and the LAM itself may take one or more criticals.
                    for (Entity rider : entity.getExternalUnits()) {
                        reportManager.addReport(checkDropBAFromConverting(entity, rider, curPos, curFacing, true, true, true));
                    }
                    final int swarmerId = entity.getSwarmAttackerId();
                    if (Entity.NONE != swarmerId) {
                        reportManager.addReport(checkDropBAFromConverting(entity, game.getEntity(swarmerId),
                                curPos, curFacing, true, true, true));
                    }
                }
                continue;
            }

            // did the entity move?
            boolean didMove = step.getDistance() > distance;

            // check for aero stuff
            if (entity.isAirborne() && entity.isAero()) {
                IAero a = (IAero) entity;
                j++;

                // increment straight moves (can't do it at end, because not all
                // steps may be processed)
                a.setStraightMoves(step.getNStraight());

                // TODO : change the way this check is made
                if (!didMove && (md.length() != j)) {
                    thrustUsed += step.getMp();
                } else {
                    // if this was the last move and distance was zero, then add
                    // thrust
                    if (!didMove && (md.length() == j)) {
                        thrustUsed += step.getMp();
                    }
                    // then we moved to a new hex or the last step so check
                    // condition structural damage
                    rollTarget = a.checkThrustSI(thrustUsed, overallMoveType);
                    if ((rollTarget.getValue() != TargetRoll.CHECK_FALSE)
                            && !(entity instanceof FighterSquadron) && !game.useVectorMove()) {
                        if (!doSkillCheckInSpace(entity, rollTarget)) {
                            a.setSI(a.getSI() - 1);
                            if (entity instanceof LandAirMech) {
                                reportManager.addReport(server.criticalEntity(entity, Mech.LOC_CT, false, 0, 1));
                            }
                            // check for destruction
                            if (a.getSI() == 0) {
                                // Lets auto-eject if we can!
                                if (a instanceof LandAirMech) {
                                    // LAMs eject if the CT destroyed switch is on
                                    LandAirMech lam = (LandAirMech) a;
                                    if (lam.isAutoEject()
                                            && (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                            || (game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                            && lam.isCondEjectCTDest()))) {
                                        reportManager.addReport(ejectEntity(entity, true, false));
                                    }
                                } else {
                                    // Aeros eject if the SI Destroyed switch is on
                                    Aero aero = (Aero) a;
                                    if (aero.isAutoEject()
                                            && (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                            || (game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                            && aero.isCondEjectSIDest()))) {
                                        reportManager.addReport(ejectEntity(entity, true, false));
                                    }
                                }
                                reportManager.addReport(destroyEntity(entity, "Structural Integrity Collapse",
                                        false));
                            }
                        }
                    }

                    // check for pilot damage
                    int hits = entity.getCrew().getHits();
                    int health = 6 - hits;

                    if ((thrustUsed > (2 * health)) && !game.useVectorMove() && !(entity instanceof TeleMissile)) {
                        int targetRoll = 2 + (thrustUsed - (2 * health)) + (2 * hits);
                        resistGForce(entity, targetRoll);
                    }

                    thrustUsed = 0;
                }

                if (step.getType() == MovePath.MoveStepType.RETURN || step.getType() == MovePath.MoveStepType.OFF) {
                    a.setCurrentVelocity(md.getFinalVelocity());
                    entity.setAltitude(curAltitude);

                    if (step.getType() == MovePath.MoveStepType.RETURN) {
                        processLeaveMap(md, true, Compute.roundsUntilReturn(game, entity));
                    } else {
                        processLeaveMap(md, true, -1);
                    }
                    return;
                }

                rollTarget = a.checkRolls(step, overallMoveType);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    game.addControlRoll(new PilotingRollData(entity.getId(), 0, "excess roll"));
                }

                rollTarget = a.checkManeuver(step, overallMoveType);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    if (!doSkillCheckManeuver(entity, rollTarget)) {
                        a.setFailedManeuver(true);
                        int forward = Math.max(step.getVelocityLeft() / 2, 1);
                        if (forward < step.getVelocityLeft()) {
                            fellDuringMovement = true;
                        }
                        // multiply forward by 16 when on ground hexes
                        if (game.getBoard().onGround()) {
                            forward *= 16;
                        }
                        while (forward > 0) {
                            curPos = curPos.translated(step.getFacing());
                            forward--;
                            distance++;
                            a.setStraightMoves(a.getStraightMoves() + 1);
                            // make sure it didn't fly off the map
                            if (!game.getBoard().contains(curPos)) {
                                a.setCurrentVelocity(md.getFinalVelocity());
                                processLeaveMap(md, true, Compute.roundsUntilReturn(game, entity));
                                return;
                                // make sure it didn't crash
                            } else if (game.checkCrash(entity, curPos, step.getAltitude())) {
                                reportManager.addReport(server.processCrash(entity, step.getVelocity(), curPos));
                                forward = 0;
                                fellDuringMovement = false;
                                crashedDuringMovement = true;
                            }
                        }
                        break;
                    }
                }

                // if out of control, check for possible collision
                if (didMove && a.isOutControlTotal()) {
                    Iterator<Entity> targets = game.getEntities(step.getPosition());
                    if (targets.hasNext()) {
                        // Somebody here so check to see if there is a collision
                        int checkRoll = Compute.d6(2);
                        // TODO : change this to 11 for Large Craft
                        int targetRoll = 11;
                        if ((a instanceof Dropship) || (entity instanceof Jumpship)) {
                            targetRoll = 10;
                        }
                        if (checkRoll >= targetRoll) {
                            // this gets complicated, I need to check for each
                            // unit type by order of movement sub-phase
                            Vector<Integer> potentialSpaceStation = new Vector<>();
                            Vector<Integer> potentialWarShip = new Vector<>();
                            Vector<Integer> potentialJumpShip = new Vector<>();
                            Vector<Integer> potentialDropShip = new Vector<>();
                            Vector<Integer> potentialSmallCraft = new Vector<>();
                            Vector<Integer> potentialASF = new Vector<>();

                            while (targets.hasNext()) {
                                int id = targets.next().getId();
                                Entity ce = game.getEntity(id);
                                // if we are in atmosphere and not the same altitude then skip
                                if (!game.getBoard().inSpace() && (ce.getAltitude() != curAltitude)
                                        // you can't collide with yourself
                                        && ce.equals(a)) {
                                    continue;
                                }

                                if (ce instanceof SpaceStation) {
                                    potentialSpaceStation.addElement(id);
                                } else if (ce instanceof Warship) {
                                    potentialWarShip.addElement(id);
                                } else if (ce instanceof Jumpship) {
                                    potentialJumpShip.addElement(id);
                                } else if (ce instanceof Dropship) {
                                    potentialDropShip.addElement(id);
                                } else if (ce instanceof SmallCraft) {
                                    potentialSmallCraft.addElement(id);
                                } else {
                                    // ASF can actually include anything,
                                    // because we might
                                    // have combat dropping troops
                                    potentialASF.addElement(id);
                                }
                            }

                            // ok now go through and see if these have anybody in them
                            int chosen;
                            Entity target;
                            Coords destination;
                            List<Vector<Integer>> targetLists = new ArrayList<>();
                            targetLists.add(potentialSpaceStation);
                            targetLists.add(potentialWarShip);
                            targetLists.add(potentialJumpShip);
                            targetLists.add(potentialDropShip);
                            targetLists.add(potentialSmallCraft);
                            targetLists.add(potentialASF);
                            for (Vector<Integer> t : targetLists) {
                                if (!t.isEmpty()) {
                                    chosen = Compute.randomInt(t.size());
                                    target = game.getEntity(t.elementAt(chosen));
                                    destination = target.getPosition();
                                    if (processCollision(entity, target, lastPos)) {
                                        curPos = destination;
                                    }
                                    break; // Before this refactoring, everything was elseif statements so break when one is not empty
                                }
                            }
                        }
                    }
                }

                // if in the atmosphere, check for a potential crash
                if (game.checkCrash(entity, step.getPosition(), step.getAltitude())) {
                    reportManager.addReport(server.processCrash(entity, md.getFinalVelocity(), curPos));
                    crashedDuringMovement = true;
                    // don't do the rest
                    break;
                }

                // handle fighter launching
                if (step.getType() == MovePath.MoveStepType.LAUNCH) {
                    TreeMap<Integer, Vector<Integer>> launched = step.getLaunched();
                    Set<Integer> bays = launched.keySet();
                    for (int bayId : bays) {
                        Bay currentBay = entity.getFighterBays().elementAt(bayId);
                        Vector<Integer> launches = launched.get(bayId);
                        int nLaunched = launches.size();
                        // need to make some decisions about how to handle the distribution
                        // of fighters to doors beyond the launch rate. The most sensible thing
                        // is probably to distribute them evenly.
                        int doors = currentBay.getCurrentDoors();
                        int[] distribution = new int[doors];
                        for (int l = 0; l < nLaunched; l++) {
                            distribution[l % doors] = distribution[l % doors] + 1;
                        }
                        // ok, now lets launch them
                        Report r = ReportFactory.createReport(9380, entity, entity.getDisplayName());
                        r.add(nLaunched);
                        r.add("bay " + currentBay.getBayNumber() + " (" + doors + " doors)");
                        reportManager.addReport(r);
                        int currentDoor = 0;
                        int fighterCount = 0;
                        boolean doorDamage = false;
                        for (int fighterId : launches) {
                            // check to see if we are in the same door
                            fighterCount++;

                            // check for door damage
                            Report doorReport = null;
                            if (!doorDamage && (distribution[currentDoor] > 2) && (fighterCount > 2)) {
                                doorReport = new Report(9378);
                                doorReport.subject = entity.getId();
                                doorReport.indent(2);
                                int roll = Compute.d6(2);
                                doorReport.add(roll);
                                if (roll == 2) {
                                    doorDamage = true;
                                    doorReport.choose(true);
                                    currentBay.destroyDoorNext();
                                } else {
                                    doorReport.choose(false);
                                }
                                doorReport.newlines++;
                            }

                            if (fighterCount > distribution[currentDoor]) {
                                // move to a new door
                                currentDoor++;
                                fighterCount = 0;
                                doorDamage = false;
                            }
                            int bonus = Math.max(0, distribution[currentDoor] - 2);

                            Entity fighter = game.getEntity(fighterId);
                            if (!launchUnit(entity, fighter, curPos, curFacing, step, bonus)) {
                                MegaMek.getLogger().error("Server was told to unload "
                                        + fighter.getDisplayName() + " from " + entity.getDisplayName()
                                        + " into " + curPos.getBoardNum());
                            }
                            if (doorReport != null) {
                                reportManager.addReport(doorReport);
                            }
                        }
                    }
                    // now apply any damage to bay doors
                    entity.resetBayDoors();
                }

                // handle DropShip undocking
                if (step.getType() == MovePath.MoveStepType.UNDOCK) {
                    TreeMap<Integer, Vector<Integer>> launched = step.getLaunched();
                    Set<Integer> collars = launched.keySet();
                    for (int collarId : collars) {
                        Vector<Integer> launches = launched.get(collarId);
                        int nLaunched = launches.size();
                        // ok, now lets launch them
                        Report r = ReportFactory.createReport(9380, entity, entity.getDisplayName());
                        r.add(nLaunched);
                        r.add("collar " + collarId);
                        reportManager.addReport(r);
                        for (int dropShipId : launches) {
                            // check to see if we are in the same door
                            Entity ds = game.getEntity(dropShipId);
                            if (!launchUnit(entity, ds, curPos, curFacing, step, 0)) {
                                MegaMek.getLogger().error("Error! Server was told to unload "
                                        + ds.getDisplayName() + " from " + entity.getDisplayName()
                                        + " into " + curPos.getBoardNum());
                            }
                        }
                    }
                }

                // handle combat drops
                if (step.getType() == MovePath.MoveStepType.DROP) {
                    TreeMap<Integer, Vector<Integer>> dropped = step.getLaunched();
                    Set<Integer> bays = dropped.keySet();
                    for (int bayId : bays) {
                        Bay currentBay = entity.getTransportBays().elementAt(bayId);
                        currentBay = entity.getTransportBays().elementAt(bayId);
                        Vector<Integer> drops = dropped.get(bayId);
                        int nDropped = drops.size();
                        // ok, now lets drop them
                        Report r = ReportFactory.createReport(9386, entity, entity.getDisplayName());
                        r.add(nDropped);
                        reportManager.addReport(r);
                        for (int unitId : drops) {
                            if (Compute.d6(2) == 2) {
                                reportManager.addReport(ReportFactory.createReport(9390, 1, entity, currentBay.getType()));
                                currentBay.destroyDoorNext();
                            }
                            Entity drop = game.getEntity(unitId);
                            server.dropUnit(drop, entity, curPos, step.getAltitude());
                        }
                    }
                    // now apply any damage to bay doors
                    entity.resetBayDoors();
                }
            }

            // check piloting skill for getting up
            rollTarget = entity.checkGetUp(step, overallMoveType);

            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                // Unless we're an ICE- or fuel cell-powered IndustrialMech,
                // standing up builds heat.
                if ((entity instanceof Mech) && entity.hasEngine() && !(((Mech) entity).isIndustrial()
                        && ((entity.getEngine().getEngineType() == Engine.COMBUSTION_ENGINE)
                        || (entity.getEngine().getEngineType() == Engine.FUEL_CELL)))) {
                    entity.heatBuildup += 1;
                }
                entity.setProne(false);
                // entity.setHullDown(false);
                wasProne = false;
                game.resetPSRs(entity);
                entityFellWhileAttemptingToStand = !server.doSkillCheckInPlace(entity, rollTarget);
            }
            // did the entity just fall?
            if (entityFellWhileAttemptingToStand) {
                moveType = stepMoveType;
                curFacing = entity.getFacing();
                curPos = entity.getPosition();
                mpUsed = step.getMpUsed();
                fellDuringMovement = true;
                if (!entity.isCarefulStand()) {
                    break;
                }
            } else if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                entity.setHullDown(false);
            }

            if (step.getType() == MovePath.MoveStepType.UNJAM_RAC) {
                entity.setUnjammingRAC(true);
                game.addAction(new UnjamAction(entity.getId()));

                // for Aeros this will end movement prematurely
                // if we break
                if (!(entity.isAirborne())) {
                    break;
                }
            }

            if (step.getType() == MovePath.MoveStepType.LAY_MINE) {
                layMine(entity, step.getMineToLay(), step.getPosition());
                continue;
            }

            if (step.getType() == MovePath.MoveStepType.CLEAR_MINEFIELD) {
                ClearMinefieldAction cma = new ClearMinefieldAction(entity.getId(), step.getMinefield());
                entity.setClearingMinefield(true);
                game.addAction(cma);
                break;
            }

            if ((step.getType() == MovePath.MoveStepType.SEARCHLIGHT) && entity.hasSpotlight()) {
                final boolean SearchOn = !entity.isUsingSpotlight();
                entity.setSpotlightState(SearchOn);
                if (game.doBlind()) { // if double blind, we may need to filter the
                    // players that receive this message
                    Vector<IPlayer> playersVector = game.getPlayersVector();
                    Vector<IPlayer> vCanSee = server.whoCanSee(entity);
                    for (IPlayer p : playersVector) {
                        if (vCanSee.contains(p)) { // Player sees the unit
                            server.sendServerChat(p.getId(),
                                    entity.getDisplayName() + " switched searchlight " + (SearchOn ? "on" : "off") + '.');
                        } else {
                            server.sendServerChat(p.getId(),
                                    "An unseen unit" + " switched searchlight " + (SearchOn ? "on" : "off") + '.');
                        }
                    }
                } else { // No double blind, everyone can see this
                    server.sendServerChat(
                            entity.getDisplayName() + " switched searchlight " + (SearchOn ? "on" : "off") + '.');
                }
            }

            // set most step parameters
            moveType = stepMoveType;
            distance = step.getDistance();
            mpUsed = step.getMpUsed();

            if (cachedGravityLimit < 0) {
                cachedGravityLimit = EntityMovementType.MOVE_JUMP == moveType ? entity.getJumpMP(false)
                        : entity.getRunningGravityLimit();
            }
            // check for charge
            if (step.getType() == MovePath.MoveStepType.CHARGE) {
                if (entity.canCharge()) {
                    game.checkExtremeGravityMovement(entity, step, lastStepMoveType, curPos, cachedGravityLimit);
                    Targetable target = step.getTarget(game);
                    if (target != null) {
                        ChargeAttackAction caa = new ChargeAttackAction(entity.getId(), target.getTargetType(),
                                target.getTargetId(), target.getPosition());
                        entity.setDisplacementAttack(caa);
                        game.addCharge(caa);
                        charge = caa;
                    } else {
                        String message = "Illegal charge!! " + entity.getDisplayName() +
                                " is attempting to charge a null target!";
                        MegaMek.getLogger().info(message);
                        server.sendServerChat(message);
                        return;
                    }
                } else if (entity.isAirborneVTOLorWIGE() && entity.canRam()) {
                    game.checkExtremeGravityMovement(entity, step, lastStepMoveType,
                            curPos, cachedGravityLimit);
                    Targetable target = step.getTarget(game);
                    if (target != null) {
                        AirmechRamAttackAction raa = new AirmechRamAttackAction(
                                entity.getId(), target.getTargetType(),
                                target.getTargetId(), target.getPosition());
                        entity.setDisplacementAttack(raa);
                        entity.setRamming(true);
                        game.addCharge(raa);
                        charge = raa;
                    } else {
                        String message = "Illegal charge!! " + entity.getDisplayName() + " is attempting to charge a null target!";
                        MegaMek.getLogger().info(message);
                        server.sendServerChat(message);
                        return;
                    }
                } else {
                    server.sendServerChat("Illegal charge!! I don't think " + entity.getDisplayName()
                            + " should be allowed to charge, but the client of "
                            + entity.getOwner().getName() + " disagrees.");
                    server.sendServerChat("Please make sure " + entity.getOwner().getName()
                            + " is running MegaMek " + MegaMek.VERSION
                            + ", or if that is already the case, submit a bug report at https://github.com/MegaMek/megamek/issues");
                    return;
                }
                break;
            }

            // check for dfa
            if (step.getType() == MovePath.MoveStepType.DFA) {
                if (entity.canDFA()) {
                    game.checkExtremeGravityMovement(entity, step, lastStepMoveType, curPos, cachedGravityLimit);
                    Targetable target = step.getTarget(game);

                    int targetType;
                    int targetID;

                    // if it's a valid target, then simply pass along the type and ID
                    if (target != null) {
                        targetID = target.getTargetId();
                        targetType = target.getTargetType();
                        // if the target has become invalid somehow, or was incorrectly declared in the first place
                        // log the error, then put some defaults in for the DFA and proceed as if the target had been moved/destroyed
                    } else {
                        String errorMessage = "Illegal DFA by " + entity.getDisplayName() + " against non-existent entity at " + step.getTargetPosition();
                        server.sendServerChat(errorMessage);
                        MegaMek.getLogger().error(errorMessage);
                        targetID = Entity.NONE;
                        // doesn't really matter, DFA processing will cut out early if target resolves as null
                        targetType = Targetable.TYPE_ENTITY;
                    }

                    DfaAttackAction daa = new DfaAttackAction(entity.getId(),
                            targetType, targetID,
                            step.getPosition());
                    entity.setDisplacementAttack(daa);
                    entity.setElevation(step.getElevation());
                    game.addCharge(daa);
                    charge = daa;

                } else {
                    server.sendServerChat("Illegal DFA!! I don't think "
                            + entity.getDisplayName()
                            + " should be allowed to DFA,"
                            + " but the client of "
                            + entity.getOwner().getName() + " disagrees.");
                    server.sendServerChat("Please make sure "
                            + entity.getOwner().getName()
                            + " is running MegaMek " + MegaMek.VERSION
                            + ", or if that is already the case, submit a bug report at https://github.com/MegaMek/megamek/issues");
                    return;
                }
                break;
            }

            // check for ram
            if (step.getType() == MovePath.MoveStepType.RAM) {
                if (entity.canRam()) {
                    Targetable target = step.getTarget(game);
                    RamAttackAction raa = new RamAttackAction(entity.getId(),
                            target.getTargetType(), target.getTargetId(),
                            target.getPosition());
                    entity.setRamming(true);
                    game.addRam(raa);
                    ram = raa;
                } else {
                    server.sendServerChat("Illegal ram!! I don't think "
                            + entity.getDisplayName()
                            + " should be allowed to charge,"
                            + " but the client of "
                            + entity.getOwner().getName() + " disagrees.");
                    server.sendServerChat("Please make sure "
                            + entity.getOwner().getName()
                            + " is running MegaMek " + MegaMek.VERSION
                            + ", or if that is already the case, submit a bug report at https://github.com/MegaMek/megamek/issues");
                    return;
                }
                break;
            }

            if (step.isVTOLBombingStep()) {
                ((IBomber) entity).setVTOLBombTarget(step.getTarget(game));
            } else if (step.isStrafingStep() && (entity instanceof VTOL)) {
                ((VTOL) entity).getStrafingCoords().add(step.getPosition());
            }

            if ((step.getType() == MovePath.MoveStepType.ACC) || (step.getType() == MovePath.MoveStepType.ACCN)) {
                if (entity.isAero()) {
                    IAero a = (IAero) entity;
                    if (step.getType() == MovePath.MoveStepType.ACCN) {
                        a.setAccLast(true);
                    } else {
                        a.setAccDecNow(true);
                        a.setCurrentVelocity(a.getCurrentVelocity() + 1);
                    }
                    a.setNextVelocity(a.getNextVelocity() + 1);
                }
            }

            if ((step.getType() == MovePath.MoveStepType.DEC) || (step.getType() == MovePath.MoveStepType.DECN)) {
                if (entity.isAero()) {
                    IAero a = (IAero) entity;
                    if (step.getType() == MovePath.MoveStepType.DECN) {
                        a.setAccLast(true);
                    } else {
                        a.setAccDecNow(true);
                        a.setCurrentVelocity(a.getCurrentVelocity() - 1);
                    }
                    a.setNextVelocity(a.getNextVelocity() - 1);
                }
            }

            if (step.getType() == MovePath.MoveStepType.EVADE) {
                entity.setEvading(true);
            }

            if (step.getType() == MovePath.MoveStepType.SHUTDOWN) {
                entity.performManualShutdown();
                server.sendServerChat(entity.getDisplayName() + " has shutdown.");
            }

            if (step.getType() == MovePath.MoveStepType.STARTUP) {
                entity.performManualStartup();
                server.sendServerChat(entity.getDisplayName() + " has started up.");
            }

            if (step.getType() == MovePath.MoveStepType.SELF_DESTRUCT) {
                entity.setSelfDestructing(true);
            }

            if (step.getType() == MovePath.MoveStepType.ROLL) {
                if (entity.isAero()) {
                    IAero a = (IAero) entity;
                    a.setRolled(!a.isRolled());
                }
            }

            // check for dig in or fortify
            if (entity instanceof Infantry) {
                Infantry inf = (Infantry) entity;
                if (step.getType() == MovePath.MoveStepType.DIG_IN) {
                    inf.setDugIn(Infantry.DUG_IN_WORKING);
                    continue;
                } else if (step.getType() == MovePath.MoveStepType.FORTIFY) {
                    if (!entity.hasWorkingMisc(MiscType.F_TOOLS, MiscType.S_VIBROSHOVEL)) {
                        server.sendServerChat(entity.getDisplayName()
                                + " failed to fortify because it is missing suitable equipment");
                    }
                    inf.setDugIn(Infantry.DUG_IN_FORTIFYING1);
                    continue;
                } else if ((step.getType() != MovePath.MoveStepType.TURN_LEFT) && (step.getType() != MovePath.MoveStepType.TURN_RIGHT)) {
                    // other movement clears dug in status
                    inf.setDugIn(Infantry.DUG_IN_NONE);
                }

                if (step.getType() == MovePath.MoveStepType.TAKE_COVER) {
                    if (Infantry.hasValidCover(game, step.getPosition(),
                            step.getElevation())) {
                        inf.setTakingCover(true);
                    } else {
                        server.sendServerChat(entity.getDisplayName() + " failed to take cover: no valid unit found in "
                                + step.getPosition());
                    }
                }
            }

            // If we have turned, check whether we have fulfilled any turn mode requirements.
            if ((step.getType() == MovePath.MoveStepType.TURN_LEFT || step.getType() == MovePath.MoveStepType.TURN_RIGHT)
                    && entity.usesTurnMode()) {
                int straight = 0;
                if (prevStep != null) {
                    straight = prevStep.getNStraight();
                }
                rollTarget = entity.checkTurnModeFailure(overallMoveType, straight, md.getMpUsed(), step.getPosition());
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    int mof = server.doSkillCheckWhileMoving(entity, lastElevation, lastPos, curPos, rollTarget, false);
                    if (mof > 0) {
                        if (processFailedVehicleManeuver(entity, curPos, step.getFacing() - curFacing,
                                (null == prevStep)?step : prevStep, step.isThisStepBackwards(),
                                lastStepMoveType, distance, mof, mof)) {
                            mpUsed = calculateMovementPointsUsed(md, entity);

                            turnOver = true;
                            distance = entity.delta_distance;
                        } else {
                            continueTurnFromFishtail = true;
                        }
                        curFacing = entity.getFacing();
                        curPos = entity.getPosition();
                        entity.setSecondaryFacing(curFacing);
                        break;
                    }
                }
            }

            if (step.getType() == MovePath.MoveStepType.BOOTLEGGER) {
                rollTarget = entity.getBasePilotingRoll();
                entity.addPilotingModifierForTerrain(rollTarget);
                rollTarget.addModifier(0, "bootlegger maneuver");
                int mof = server.doSkillCheckWhileMoving(entity, lastElevation, curPos, curPos, rollTarget, false);
                if (mof > 0) {
                    // If the bootlegger maneuver fails, we treat it as a turn in a random direction.
                    processFailedVehicleManeuver(entity, curPos, Compute.d6() < 4 ? -1 : 1,
                            (null == prevStep)? step : prevStep,
                            step.isThisStepBackwards(), lastStepMoveType, distance, 2, mof);
                    curFacing = entity.getFacing();
                    curPos = entity.getPosition();
                    break;
                }
            }

            // set last step parameters
            curPos = step.getPosition();
            if (!((entity.getJumpType() == Mech.JUMP_BOOSTER) && step.isJumping())) {
                curFacing = step.getFacing();
            }
            // check if a building PSR will be needed later, before setting the
            // new elevation
            int buildingMove = entity.checkMovementInBuilding(step, prevStep, curPos, lastPos);
            curVTOLElevation = step.getElevation();
            curAltitude = step.getAltitude();
            curElevation = step.getElevation();
            curClimbMode = step.climbMode();
            // set elevation in case of collapses
            entity.setElevation(step.getElevation());
            // set climb mode in case of skid
            entity.setClimbMode(curClimbMode);

            IHex curHex = game.getBoard().getHex(curPos);

            // when first entering a building, we need to roll what type
            // of basement it has
            if (isOnGround && curHex.containsTerrain(Terrains.BUILDING)) {
                Building bldg = game.getBoard().getBuildingAt(curPos);
                if (bldg.rollBasement(curPos, game.getBoard(), reportManager.getvPhaseReport())) {
                    gameManager.sendChangedHex(game, curPos);
                    Vector<Building> buildings = new Vector<>();
                    buildings.add(bldg);
                    server.sendChangedBuildings(buildings);
                }
            }

            // check for automatic unstick
            if (entity.canUnstickByJumping() && entity.isStuck() && (moveType == EntityMovementType.MOVE_JUMP)) {
                entity.setStuck(false);
                entity.setCanUnstickByJumping(false);
            }

            // check for leap
            if (!lastPos.equals(curPos)
                    && (stepMoveType != EntityMovementType.MOVE_JUMP) && (entity instanceof Mech)
                    && !entity.isAirborne() && (step.getClearance() <= 0)  // Don't check airborne LAMs
                    && game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_TACOPS_LEAPING)) {
                int leapDistance = (lastElevation + game.getBoard().getHex(lastPos).getLevel())
                        - (curElevation + curHex.getLevel());
                if (leapDistance > 2) {
                    // skill check for leg damage
                    rollTarget = entity.getBasePilotingRoll(stepMoveType);
                    entity.addPilotingModifierForTerrain(rollTarget, curPos);
                    rollTarget.append(new PilotingRollData(entity.getId(),
                            2 * leapDistance, "leaping (leg damage)"));
                    if (0 < server.doSkillCheckWhileMoving(entity, lastElevation,
                            lastPos, curPos, rollTarget, false)) {
                        // do leg damage
                        reportManager.addReport(server.damageEntity(entity, new HitData(Mech.LOC_LLEG), leapDistance));
                        reportManager.addReport(server.damageEntity(entity, new HitData(Mech.LOC_RLEG), leapDistance));
                        reportManager.addNewLines();
                        reportManager.addReport(server.criticalEntity(entity, Mech.LOC_LLEG, false, 0, 0));
                        reportManager.addNewLines();
                        reportManager.addReport(server.criticalEntity(entity, Mech.LOC_RLEG, false, 0, 0));
                        if (entity instanceof QuadMech) {
                            reportManager.addReport(server.damageEntity(entity, new HitData(Mech.LOC_LARM), leapDistance));
                            reportManager.addReport(server.damageEntity(entity, new HitData(Mech.LOC_RARM), leapDistance));
                            reportManager.addNewLines();
                            reportManager.addReport(server.criticalEntity(entity, Mech.LOC_LARM, false, 0, 0));
                            reportManager.addNewLines();
                            reportManager.addReport(server.criticalEntity(entity, Mech.LOC_RARM, false, 0, 0));
                        }
                    }
                    // skill check for fall
                    rollTarget = entity.getBasePilotingRoll(stepMoveType);
                    entity.addPilotingModifierForTerrain(rollTarget, curPos);
                    rollTarget.append(new PilotingRollData(entity.getId(), leapDistance, "leaping (fall)"));
                    if (0 < server.doSkillCheckWhileMoving(entity, lastElevation, lastPos, curPos, rollTarget,
                            false)) {
                        entity.setElevation(lastElevation);
                        reportManager.addReport(server.doEntityFallsInto(entity, lastElevation, lastPos, curPos,
                                entity.getBasePilotingRoll(overallMoveType), false));
                    }
                }
            }

            // Check for skid.
            rollTarget = entity.checkSkid(moveType, prevHex, overallMoveType, prevStep, step, prevFacing, curFacing,
                    lastPos, curPos, isInfantry, distance - 1);
            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                // Have an entity-meaningful PSR message.
                boolean psrFailed;
                int startingFacing = entity.getFacing();
                if (entity instanceof Mech) {
                    // We need to ensure that falls will happen from the proper
                    // facing
                    entity.setFacing(curFacing);
                    psrFailed = (0 < server.doSkillCheckWhileMoving(entity,
                            lastElevation, lastPos, lastPos, rollTarget, true));
                } else {
                    psrFailed = (0 < server.doSkillCheckWhileMoving(entity,
                            lastElevation, lastPos, lastPos, rollTarget, false));
                }

                // Does the entity skid?
                if (psrFailed) {
                    if (entity instanceof Tank) {
                        reportManager.addReport(server.vehicleMotiveDamage((Tank)entity, 0));
                    }

                    curPos = lastPos;
                    int skidDistance = (int) Math.round((double) (distance - 1) / 2);
                    int skidDirection = prevFacing;

                    // All charge damage is based upon the pre-skid move distance.
                    entity.delta_distance = distance - 1;

                    // Attacks against a skidding target have additional +2.
                    moveType = EntityMovementType.MOVE_SKID;

                    // What is the first hex in the skid?
                    if (step.isThisStepBackwards()) {
                        skidDirection = (skidDirection + 3) % 6;
                    }

                    if (server.processSkid(entity, curPos, prevStep.getElevation(), skidDirection, skidDistance, prevStep,
                            lastStepMoveType)) {
                        return;
                    }

                    // set entity parameters
                    curFacing = entity.getFacing();
                    curPos = entity.getPosition();
                    entity.setSecondaryFacing(curFacing);

                    // skid consumes all movement
                    mpUsed = calculateMovementPointsUsed(md, entity);

                    entity.moved = moveType;
                    fellDuringMovement = true;
                    turnOver = true;
                    distance = entity.delta_distance;
                    break;

                } else { // End failed-skid-psr
                    // If the check succeeded, restore the facing we had before
                    // if it failed, the fall will have changed facing
                    entity.setFacing(startingFacing);
                }

            } // End need-skid-psr

            // check sideslip
            if ((entity instanceof VTOL)
                    || (entity.getMovementMode() == EntityMovementMode.HOVER)
                    || (entity.getMovementMode() == EntityMovementMode.WIGE && step.getClearance() > 0)) {
                rollTarget = entity.checkSideSlip(moveType, prevHex, overallMoveType, prevStep, prevFacing, curFacing,
                        lastPos, curPos, distance);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    int moF = server.doSkillCheckWhileMoving(entity, lastElevation, lastPos, curPos, rollTarget, false);
                    if (moF > 0) {
                        int elev;
                        int sideslipDistance;
                        int skidDirection;
                        Coords start;
                        if (step.getType() == MovePath.MoveStepType.LATERAL_LEFT
                                || step.getType() == MovePath.MoveStepType.LATERAL_RIGHT
                                || step.getType() == MovePath.MoveStepType.LATERAL_LEFT_BACKWARDS
                                || step.getType() == MovePath.MoveStepType.LATERAL_RIGHT_BACKWARDS) {
                            // A failed controlled sideslip always results in moving one additional hex
                            // in the direction of the intentional sideslip.
                            elev = step.getElevation();
                            sideslipDistance = 1;
                            skidDirection = lastPos.direction(curPos);
                            start = curPos;
                        } else {
                            elev = (null == prevStep)? curElevation : prevStep.getElevation();
                            // maximum distance is hexes moved / 2
                            sideslipDistance = Math.min(moF, distance / 2);
                            skidDirection = prevFacing;
                            start = lastPos;
                        }
                        if (sideslipDistance > 0) {
                            sideslipped = true;
                            reportManager.addReport(ReportFactory.createReport(2100, entity, sideslipDistance));

                            if (server.processSkid(entity, start, elev, skidDirection, sideslipDistance,
                                    (null == prevStep)? step : prevStep, lastStepMoveType)) {
                                return;
                            }

                            if (!entity.isDestroyed() && !entity.isDoomed() && (mpUsed < entity.getRunMP())) {
                                fellDuringMovement = true; // No, but it should
                                // work...
                            }

                            if ((entity.getElevation() == 0)
                                    && ((entity.getMovementMode() == EntityMovementMode.VTOL)
                                    || (entity.getMovementMode() == EntityMovementMode.WIGE))) {
                                turnOver = true;
                            }
                            // set entity parameters
                            curFacing = step.getFacing();
                            curPos = entity.getPosition();
                            entity.setSecondaryFacing(curFacing);
                            break;
                        }
                    }
                }
            }

            // check if we've moved into rubble
            boolean isLastStep = step.equals(md.getLastStep());
            rollTarget = entity.checkRubbleMove(step, overallMoveType, curHex,
                    lastPos, curPos, isLastStep, isPavementStep);
            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                server.doSkillCheckWhileMoving(entity, lastElevation, lastPos, curPos, rollTarget, true);
            }

            // check if we are using reckless movement
            rollTarget = entity.checkRecklessMove(step, overallMoveType, curHex, lastPos, curPos, prevHex);
            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                if (entity instanceof Mech) {
                    server.doSkillCheckWhileMoving(entity, lastElevation, lastPos, curPos, rollTarget, true);
                } else if (entity instanceof Tank) {
                    if (0 < server.doSkillCheckWhileMoving(entity, lastElevation, lastPos, curPos, rollTarget, false)) {
                        // assume VTOLs in flight are always in clear terrain
                        if ((0 == curHex.terrainsPresent())
                                || (step.getClearance() > 0)) {
                            int reportID = 2206;
                            if (entity instanceof VTOL) {
                                reportID = 2208;
                            }
                            reportManager.addReport(ReportFactory.createReport(reportID, entity));
                            mpUsed = step.getMpUsed() + 1;
                            fellDuringMovement = true;
                            break;
                        }
                        reportManager.addReport(ReportFactory.createReport(2207, entity));
                        // until we get a rules clarification assume that the
                        // entity is both giver and taker
                        // for charge damage
                        HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                        reportManager.addReport(server.damageEntity(entity, hit, ChargeAttackAction
                                .getDamageTakenBy(entity, entity)));
                        turnOver = true;
                        break;
                    }
                }
            }

            // check for breaking magma crust unless we are jumping over the hex
            if (stepMoveType != EntityMovementType.MOVE_JUMP) {
                ServerHelper.checkAndApplyMagmaCrust(curHex, step.getElevation(), entity, curPos, false, reportManager.getvPhaseReport(), server);
            }

            // check if we've moved into a swamp
            rollTarget = entity.checkBogDown(step, lastStepMoveType, curHex,
                    lastPos, curPos, lastElevation, isPavementStep);
            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                if (0 < server.doSkillCheckWhileMoving(entity, lastElevation, lastPos, curPos, rollTarget, false)) {
                    entity.setStuck(true);
                    entity.setCanUnstickByJumping(true);
                    reportManager.addReport(ReportFactory.createReport(2081, entity));
                    // check for quicksand
                    reportManager.addReport(server.checkQuickSand(curPos));
                    // check for accidental stacking violation
                    Entity violation = Compute.stackingViolation(game, entity.getId(), curPos);
                    if (violation != null) {
                        // target gets displaced, because of low elevation
                        int direction = lastPos.direction(curPos);
                        Coords targetDest = Compute.getValidDisplacement(game, entity.getId(), curPos, direction);
                        reportManager.addReport(server.doEntityDisplacement(violation, curPos, targetDest,
                                new PilotingRollData(violation.getId(), 0, "domino effect")));
                        // Update the violating entity's position on the client.
                        entityUpdate(violation.getId());
                    }
                    break;
                }
            }

            // check to see if we are a mech and we've moved OUT of fire
            IHex lastHex = game.getBoard().getHex(lastPos);
            if (entity instanceof Mech) {
                if (!lastPos.equals(curPos) && (prevStep != null)
                        && ((lastHex.containsTerrain(Terrains.FIRE)
                        && (prevStep.getElevation() <= 1))
                        || (lastHex.containsTerrain(Terrains.MAGMA)
                        && (prevStep.getElevation() == 0)))
                        && ((stepMoveType != EntityMovementType.MOVE_JUMP)
                        // Bug #828741 -- jumping bypasses fire, but not
                        // on the
                        // first step
                        // getMpUsed -- total MP used to this step
                        // getMp -- MP used in this step
                        // the difference will always be 0 on the "first
                        // step"
                        // of a jump,
                        // and >0 on a step in the midst of a jump
                        || (0 == (step.getMpUsed() - step.getMp())))) {
                    int heat = 0;
                    if (lastHex.containsTerrain(Terrains.FIRE)) {
                        heat += 2;
                    }
                    if (lastHex.terrainLevel(Terrains.MAGMA) == 1) {
                        heat += 2;
                    } else if (lastHex.terrainLevel(Terrains.MAGMA) == 2) {
                        heat += 5;
                    }
                    if (((Mech) entity).hasIntactHeatDissipatingArmor()) {
                        heat /= 2;
                    }
                    entity.heatFromExternal += heat;
                    reportManager.addReport(ReportFactory.createReport(2115, entity, heat));
                    if (((Mech) entity).hasIntactHeatDissipatingArmor()) {
                        reportManager.addReport(ReportFactory.createReport(5550));
                    }
                }
            }

            // check to see if we are not a mech and we've moved INTO fire
            if (!(entity instanceof Mech)) {
                boolean underwater = game.getBoard().getHex(curPos)
                        .containsTerrain(Terrains.WATER)
                        && (game.getBoard().getHex(curPos).depth() > 0)
                        && (step.getElevation() < game.getBoard().getHex(curPos).surface());
                if (game.getBoard().getHex(curPos).containsTerrain(
                        Terrains.FIRE) && !lastPos.equals(curPos)
                        && (stepMoveType != EntityMovementType.MOVE_JUMP)
                        && (step.getElevation() <= 1) && !underwater) {
                    server.doFlamingDamage(entity);
                }
            }
            // check for extreme gravity movement
            if (!i.hasMoreElements() && !firstStep) {
                game.checkExtremeGravityMovement(entity, step, lastStepMoveType, curPos, cachedGravityLimit);
            }
            // check for minefields. have to check both new hex and new
            // elevation
            // VTOLs may land and submarines may rise or lower into a minefield
            if (!lastPos.equals(curPos) || (lastElevation != curElevation)) {
                boolean boom = false;
                if (isOnGround) {
                    boom = server.checkVibrabombs(entity, curPos, false, lastPos, curPos, reportManager.getvPhaseReport());
                }
                if (game.containsMinefield(curPos)) {
                    // set the new position temporarily, because
                    // infantry otherwise would get double damage
                    // when moving from clear into mined woods
                    entity.setPosition(curPos);
                    if (server.enterMinefield(entity, curPos, step.getElevation(),
                            isOnGround, reportManager.getvPhaseReport())) {
                        // resolve any piloting rolls from damage unless unit
                        // was jumping
                        if (stepMoveType != EntityMovementType.MOVE_JUMP) {
                            reportManager.addReport(server.resolvePilotingRolls(entity));
                            game.resetPSRs(entity);
                        }
                        boom = true;
                    }
                    if (wasProne || !entity.isProne()) {
                        entity.setPosition(lastPos);
                    }
                }
                // did anything go boom?
                if (boom) {
                    // set fell during movement so that entity will get another
                    // chance to move with any motive damage
                    // taken account of (functions the same as MASC failure)
                    // only do this if they had more steps (and they were not
                    // jumping
                    if (i.hasMoreElements() && (stepMoveType != EntityMovementType.MOVE_JUMP)) {
                        md.clear();
                        fellDuringMovement = true;
                    }
                    // reset mines if anything detonated
                    gameManager.resetMines(game);
                }
            }

            // infantry discovers minefields if they end their move
            // in a minefield.
            if (!lastPos.equals(curPos) && !i.hasMoreElements() && isInfantry) {
                if (game.containsMinefield(curPos)) {
                    IPlayer owner = entity.getOwner();
                    for (Minefield mf : game.getMinefields(curPos)) {
                        if (!owner.containsMinefield(mf)) {
                            reportManager.addReport(ReportFactory.createReport(2120, entity));
                            gameManager.revealMinefield(game.getTeamForPlayer(owner), mf);
                        }
                    }
                }
            }

            // check if we've moved into water
            rollTarget = entity.checkWaterMove(step, lastStepMoveType, curHex, lastPos, curPos, isPavementStep);
            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                // Swarmers need special handling.
                final int swarmerId = entity.getSwarmAttackerId();
                boolean swarmerDone = true;
                Entity swarmer = null;
                if (Entity.NONE != swarmerId) {
                    swarmer = game.getEntity(swarmerId);
                    swarmerDone = swarmer.isDone();
                }

                // Now do the skill check.
                entity.setFacing(curFacing);
                server.doSkillCheckWhileMoving(entity, lastElevation, lastPos, curPos, rollTarget, true);

                // Swarming infantry platoons may drown.
                if (curHex.terrainLevel(Terrains.WATER) > 1) {
                    drownSwarmer(entity, curPos);
                }

                // Do we need to remove a game turn for the swarmer
                if (!swarmerDone && (swarmer != null) && (swarmer.isDoomed() || swarmer.isDestroyed())) {
                    // We have to diddle with the swarmer's
                    // status to get its turn removed.
                    swarmer.setDone(false);
                    swarmer.setUnloaded(false);

                    // Dead entities don't take turns.
                    game.removeTurnFor(swarmer);
                    server.send(PacketFactory.createTurnVectorPacket(game));

                    // Return the original status.
                    swarmer.setDone(true);
                    swarmer.setUnloaded(true);
                }

                // check for inferno wash-off
                checkForWashedInfernos(entity, curPos);
            }

            // In water, may or may not be a new hex, necessary to
            // check during movement, for breach damage, and always
            // set dry if appropriate
            // TODO : possibly make the locations local and set later
            reportManager.addReport(server.doSetLocationsExposure(entity, curHex,
                    stepMoveType == EntityMovementType.MOVE_JUMP, step.getElevation()));

            // check for breaking ice by breaking through from below
            if ((lastElevation < 0) && (step.getElevation() == 0)
                    && lastHex.containsTerrain(Terrains.ICE)
                    && lastHex.containsTerrain(Terrains.WATER)
                    && (stepMoveType != EntityMovementType.MOVE_JUMP)
                    && !lastPos.equals(curPos)) {
                // need to temporarily reset entity's position so it doesn't
                // fall in the ice
                entity.setPosition(curPos);
                reportManager.addReport(ReportFactory.createReport(2410, entity));
                reportManager.addReport(server.resolveIceBroken(lastPos));
                // ok now set back
                entity.setPosition(lastPos);
            }
            // check for breaking ice by stepping on it
            if (curHex.containsTerrain(Terrains.ICE)
                    && curHex.containsTerrain(Terrains.WATER)
                    && (stepMoveType != EntityMovementType.MOVE_JUMP)
                    && !lastPos.equals(curPos) && !(entity instanceof Infantry)
                    && !(isPavementStep && curHex.containsTerrain(Terrains.BRIDGE))) {
                if (step.getElevation() == 0) {
                    int roll = Compute.d6(1);
                    reportManager.addReport(ReportFactory.createReport(2118, entity, roll));
                    if (roll == 6) {
                        entity.setPosition(curPos);
                        reportManager.addReport(server.resolveIceBroken(curPos));
                        curPos = entity.getPosition();
                    }
                }
                // or intersecting it
                else if ((step.getElevation() + entity.height()) == 0) {
                    reportManager.addReport(ReportFactory.createReport(2410, entity));
                    reportManager.addReport(server.resolveIceBroken(curPos));
                }
            }

            // Handle loading units.
            if (step.getType() == MovePath.MoveStepType.LOAD) {

                // Find the unit being loaded.
                Entity loaded = null;
                Iterator<Entity> entities = game.getEntities(curPos);
                while (entities.hasNext()) {

                    // Is the other unit friendly and not the current entity?
                    loaded = entities.next();

                    // This should never ever happen, but just in case...
                    if (loaded.equals(null)) {
                        continue;
                    }

                    if (!entity.isEnemyOf(loaded) && !entity.equals(loaded)) {
                        // The moving unit should be able to load the other
                        // unit and the other should be able to have a turn.
                        if (!entity.canLoad(loaded) || !loaded.isLoadableThisTurn()) {
                            // Something is fishy in Denmark.
                            MegaMek.getLogger().error(entity.getShortName() + " can not load " + loaded.getShortName());
                            loaded = null;
                        } else {
                            // Have the deployed unit load the indicated unit.
                            server.loadUnit(entity, loaded, loaded.getTargetBay());

                            // Stop looking.
                            break;
                        }
                    } else {
                        // Nope. Discard it.
                        loaded = null;
                    }
                } // Handle the next entity in this hex.

                // We were supposed to find someone to load.
                if (loaded == null) {
                    MegaMek.getLogger().error("Could not find unit for " + entity.getShortName() + " to load in " + curPos);
                }

            } // End STEP_LOAD

            // Handle towing units.
            if (step.getType() == MovePath.MoveStepType.TOW) {

                // Find the unit being loaded.
                Entity loaded = game.getEntity(entity.getTowing());

                // This should never ever happen, but just in case...
                if (loaded == null) {
                    MegaMek.getLogger().error("Could not find unit for " + entity.getShortName() + " to tow.");
                    continue;
                }

                // The moving unit should be able to tow the other
                // unit and the other should be able to have a turn.
                //FIXME: I know this check duplicates functions already performed when enabling the Tow button.
                //This code made more sense as borrowed from "Load" where we actually rechecked the hex for the target unit.
                //Do we need it here for safety, client/server sync or can this be further streamlined?
                if (!entity.canTow(loaded.getId())) {
                    // Something is fishy in Denmark.
                    MegaMek.getLogger().error(entity.getShortName() + " can not tow " + loaded.getShortName());
                } else {
                    // Have the deployed unit load the indicated unit.
                    towUnit(entity, loaded);
                }
            } // End STEP_TOW

            // Handle mounting units to small craft/DropShip
            if (step.getType() == MovePath.MoveStepType.MOUNT) {
                Targetable mountee = step.getTarget(game);
                if (mountee instanceof Entity) {
                    Entity dropShip = (Entity) mountee;
                    if (!dropShip.canLoad(entity)) {
                        // Something is fishy in Denmark.
                        MegaMek.getLogger().error(dropShip.getShortName() + " can not load " + entity.getShortName());
                    } else {
                        // Have the indicated unit load this unit.
                        entity.setDone(true);
                        server.loadUnit(dropShip, entity, entity.getTargetBay());
                        Bay currentBay = dropShip.getBay(entity);
                        if ((null != currentBay) && (Compute.d6(2) == 2)) {
                            reportManager.addReport(ReportFactory.createReport(9390, 1, entity, currentBay.getType()));
                            currentBay.destroyDoorNext();
                        }
                        // Stop looking.
                        entityUpdate(dropShip.getId());
                        return;
                    }
                }
            } // End STEP_MOUNT

            // handle fighter recovery, and also DropShip docking with another large craft
            if (step.getType() == MovePath.MoveStepType.RECOVER) {

                loader = game.getEntity(step.getRecoveryUnit());
                boolean isDS = (entity instanceof Dropship);

                rollTarget = entity.getBasePilotingRoll(overallMoveType);
                if (loader.mpUsed > 0) {
                    rollTarget.addModifier(5, "carrier used thrust");
                }
                if (entity.getPartialRepairs().booleanOption("aero_collar_crit")) {
                    rollTarget.addModifier(2, "misrepaired docking collar");
                }
                if (isDS && (((Dropship)entity).getCollarType() == Dropship.COLLAR_PROTOTYPE)) {
                    rollTarget.addModifier(2, "prototype kf-boom");
                }
                int ctrlroll = Compute.d6(2);
                int reportID = 9381;
                if (isDS) {
                    reportID = 9388;
                }
                //TODO : This doesn't currently break out the modifiers and should...
                Report r = ReportFactory.createReport(reportID, 1, entity, entity.getDisplayName(), loader.getDisplayName());
                r.add(rollTarget.getValue(), ctrlroll);
                if (ctrlroll < rollTarget.getValue()) {
                    r.choose(false);
                    reportManager.addReport(r);
                    // damage unit
                    HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                    reportManager.addReport(server.damageEntity(entity, hit, 2 * (rollTarget.getValue() - ctrlroll)));
                } else {
                    r.choose(true);
                    reportManager.addReport(r);
                    recovered = true;
                }
                // check for door damage
                if (ctrlroll == 2) {
                    loader.damageDoorRecovery(entity);
                    reportManager.addReport(ReportFactory.createReport(9384, entity, loader.getDisplayName()));
                }
            }

            // handle fighter squadron joining
            if (step.getType() == MovePath.MoveStepType.JOIN) {
                loader = game.getEntity(step.getRecoveryUnit());
                recovered = true;
            }

            // Handle unloading units.
            if (step.getType() == MovePath.MoveStepType.UNLOAD) {
                Targetable unloaded = step.getTarget(game);
                Coords unloadPos = curPos;
                int unloadFacing = curFacing;
                if (null != step.getTargetPosition()) {
                    unloadPos = step.getTargetPosition();
                    unloadFacing = curPos.direction(unloadPos);
                }
                if (!server.unloadUnit(entity, unloaded, unloadPos, unloadFacing,
                        step.getElevation())) {
                    MegaMek.getLogger().error("Server was told to unload " + unloaded.getDisplayName() + " from "
                            + entity.getDisplayName() + " into " + curPos.getBoardNum());
                }
                // some additional stuff to take care of for small
                // craft/DropShip unloading
                if ((entity instanceof SmallCraft) && (unloaded instanceof Entity)) {
                    Bay currentBay = entity.getBay((Entity) unloaded);
                    if ((null != currentBay) && (Compute.d6(2) == 2)) {
                        reportManager.addReport(ReportFactory.createReport(9390, 1, entity, currentBay.getType()));
                        currentBay.destroyDoorNext();
                    }
                    // now apply any damage to bay doors
                    entity.resetBayDoors();
                    entityUpdate(entity.getId());
                    // ok now add another turn for the transport so it can
                    // continue to unload units
                    if (entity.getUnitsUnloadableFromBays().size() > 0) {
                        dropshipStillUnloading = true;
                        GameTurn newTurn = new GameTurn.SpecificEntityTurn(entity.getOwner().getId(), entity.getId());
                        // Need to set the new turn's multiTurn state
                        newTurn.setMultiTurn(true);
                        game.insertNextTurn(newTurn);
                    }
                    // ok add another turn for the unloaded entity so that it
                    // can move
                    if (!(unloaded instanceof Infantry)) {
                        GameTurn newTurn = new GameTurn.SpecificEntityTurn(
                                ((Entity) unloaded).getOwner().getId(),
                                ((Entity) unloaded).getId());
                        // Need to set the new turn's multiTurn state
                        newTurn.setMultiTurn(true);
                        game.insertNextTurn(newTurn);
                    }
                    // brief everybody on the turn update
                    server.send(PacketFactory.createTurnVectorPacket(game));
                }
            }

            // Handle disconnecting trailers.
            if (step.getType() == MovePath.MoveStepType.DISCONNECT) {
                Targetable unloaded = step.getTarget(game);
                Coords unloadPos = curPos;
                if (null != step.getTargetPosition()) {
                    unloadPos = step.getTargetPosition();
                }
                if (!server.disconnectUnit(entity, unloaded, unloadPos)) {
                    MegaMek.getLogger().error("Server was told to disconnect " + unloaded.getDisplayName() + " from "
                            + entity.getDisplayName() + " into " + curPos.getBoardNum());
                }
            }

            // moving backwards over elevation change
            if (((step.getType() == MovePath.MoveStepType.BACKWARDS)
                    || (step.getType() == MovePath.MoveStepType.LATERAL_LEFT_BACKWARDS)
                    || (step.getType() == MovePath.MoveStepType.LATERAL_RIGHT_BACKWARDS))
                    && !(md.isJumping() && (entity.getJumpType() == Mech.JUMP_BOOSTER))
                    && (lastHex.getLevel() + lastElevation != curHex.getLevel() + step.getElevation())
                    && !(entity instanceof VTOL)
                    && !(curClimbMode && curHex.containsTerrain(Terrains.BRIDGE)
                    && ((curHex.terrainLevel(Terrains.BRIDGE_ELEV) + curHex.getLevel()) == (prevHex.getLevel()
                    + (prevHex.containsTerrain(Terrains.BRIDGE) ? prevHex.terrainLevel(Terrains.BRIDGE_ELEV) : 0))))) {

                // per TacOps, if the mech is walking backwards over an elevation change and falls
                // it falls into the lower hex. The caveat is if it already fell from some other PSR in this 
                // invocation of processMovement, then it can't fall again. 
                if ((entity instanceof Mech) && (curHex.getLevel() < game.getBoard().getHex(lastPos).getLevel()) && !entity.hasFallen()) {
                    rollTarget = entity.getBasePilotingRoll(overallMoveType);
                    rollTarget.addModifier(0, "moving backwards over an elevation change");
                    server.doSkillCheckWhileMoving(entity, entity.getElevation(), curPos, curPos, rollTarget, true);
                } else if ((entity instanceof Mech) && !entity.hasFallen()) {
                    rollTarget = entity.getBasePilotingRoll(overallMoveType);
                    rollTarget.addModifier(0, "moving backwards over an elevation change");
                    server.doSkillCheckWhileMoving(entity, lastElevation, lastPos, lastPos, rollTarget, true);
                } else if (entity instanceof Tank) {
                    rollTarget = entity.getBasePilotingRoll(overallMoveType);
                    rollTarget.addModifier(0, "moving backwards over an elevation change");
                    if (server.doSkillCheckWhileMoving(entity, entity.getElevation(), curPos, lastPos, rollTarget, false) < 0) {
                        curPos = lastPos;
                    }
                }

            }

            // Handle non-infantry moving into a building.
            if (buildingMove > 0) {
                // Get the building being exited.
                Building bldgExited = null;
                if ((buildingMove & 1) == 1) {
                    bldgExited = game.getBoard().getBuildingAt(lastPos);
                }

                // Get the building being entered.
                Building bldgEntered = null;
                if ((buildingMove & 2) == 2) {
                    bldgEntered = game.getBoard().getBuildingAt(curPos);
                }

                // ProtoMechs changing levels within a building cause damage
                if (((buildingMove & 8) == 8) && (entity instanceof Protomech)) {
                    Building bldg = game.getBoard().getBuildingAt(curPos);
                    Vector<Report> vBuildingReport = server.damageBuilding(bldg, 1, curPos);
                    for (Report report : vBuildingReport) {
                        report.subject = entity.getId();
                    }
                    reportManager.addReport(vBuildingReport);
                }

                boolean collapsed = false;
                if ((bldgEntered != null)) {
                    // If we're not leaving a building, just handle the
                    // "entered".
                    String reason;
                    if (bldgExited == null) {
                        reason = "entering";
                    }
                    // If we're moving within the same building, just handle
                    // the "within".
                    else if (bldgExited.equals(bldgEntered)
                            && !(entity instanceof Protomech)
                            && !(entity instanceof Infantry)) {
                        reason = "moving in";
                    }
                    // If we have different buildings, roll for each.
                    else {
                        reason = "entering";
                    }
                    server.passBuildingWall(entity, bldgEntered, lastPos, curPos, distance, reason, step.isThisStepBackwards(),
                            lastStepMoveType, true);
                    server.addAffectedBldg(bldgEntered, collapsed);
                }

                // Clean up the entity if it has been destroyed.
                if (entity.isDoomed()) {
                    entity.setDestroyed(true);
                    game.moveToGraveyard(entity.getId());
                    server.send(PacketFactory.createRemoveEntityPacket(entity.getId()));

                    // The entity's movement is completed.
                    return;
                }

                // TODO : what if a building collapses into rubble?
            }

            if (stepMoveType != EntityMovementType.MOVE_JUMP
                    && (step.getClearance() == 0
                    || (entity.getMovementMode() == EntityMovementMode.WIGE && step.getClearance() == 1)
                    || curElevation == curHex.terrainLevel(Terrains.BLDG_ELEV)
                    || curElevation == curHex.terrainLevel(Terrains.BRIDGE_ELEV))) {
                Building bldg = game.getBoard().getBuildingAt(curPos);
                if ((bldg != null) && (entity.getElevation() >= 0)) {
                    boolean wigeFlyingOver = entity.getMovementMode() == EntityMovementMode.WIGE
                            && ((curHex.containsTerrain(Terrains.BLDG_ELEV)
                            && curElevation > curHex.terrainLevel(Terrains.BLDG_ELEV)) ||
                            (curHex.containsTerrain(Terrains.BRIDGE_ELEV)
                                    && curElevation > curHex.terrainLevel(Terrains.BRIDGE_ELEV)));
                    boolean collapse = server.checkBuildingCollapseWhileMoving(bldg, entity, curPos);
                    server.addAffectedBldg(bldg, collapse);
                    // If the building is collapsed by a WiGE flying over it, the WiGE drops one level of elevation.
                    // This could invalidate the remainder of the movement path, so we will send it back to the client.
                    if (collapse && wigeFlyingOver) {
                        curElevation--;
                        reportManager.addReport(ReportFactory.createReport(2378, entity));
                        continueTurnFromLevelDrop = true;
                        entity.setPosition(curPos);
                        entity.setFacing(curFacing);
                        entity.setSecondaryFacing(curFacing);
                        entity.setElevation(curElevation);
                        break;
                    }
                }
            }

            // Sheer Cliffs, TO p.39
            boolean vehicleAffectedByCliff = entity instanceof Tank && !entity.isAirborneVTOLorWIGE();
            boolean quadveeVehMode = entity instanceof QuadVee
                    && ((QuadVee)entity).getConversionMode() == QuadVee.CONV_MODE_VEHICLE;
            boolean mechAffectedByCliff = (entity instanceof Mech || entity instanceof Protomech)
                    && moveType != EntityMovementType.MOVE_JUMP
                    && !entity.isAero();
            // Cliffs should only exist towards 1 or 2 level drops, check just to make sure
            // Everything that does not have a 1 or 2 level drop shouldn't be handled as a cliff
            int stepHeight = curElevation + curHex.getLevel() - (lastElevation + prevHex.getLevel());
            boolean isUpCliff = !lastPos.equals(curPos)
                    && curHex.hasCliffTopTowards(prevHex)
                    && (stepHeight == 1 || stepHeight == 2);
            boolean isDownCliff = !lastPos.equals(curPos)
                    && prevHex.hasCliffTopTowards(curHex)
                    && (stepHeight == -1 || stepHeight == -2);

            // Vehicles (exc. WIGE/VTOL) moving down a cliff
            if (vehicleAffectedByCliff && isDownCliff && !isPavementStep) {
                rollTarget = entity.getBasePilotingRoll(stepMoveType);
                rollTarget.append(new PilotingRollData(entity.getId(), 0, "moving down a sheer cliff"));
                if (server.doSkillCheckWhileMoving(entity, lastElevation,
                        lastPos, curPos, rollTarget, false) > 0) {
                    reportManager.addReport(server.vehicleMotiveDamage((Tank)entity, 0));
                    reportManager.addNewLines();
                    turnOver = true;
                    break;
                }
            }

            // Mechs and Protomechs moving down a cliff
            // Quadvees in vee mode ignore PSRs to avoid falls, IO p.133
            if (mechAffectedByCliff && !quadveeVehMode && isDownCliff && !isPavementStep) {
                rollTarget = entity.getBasePilotingRoll(moveType);
                rollTarget.append(new PilotingRollData(entity.getId(), -stepHeight - 1, "moving down a sheer cliff"));
                if (server.doSkillCheckWhileMoving(entity, lastElevation, lastPos, curPos, rollTarget, true) > 0) {
                    reportManager.addNewLines();
                    turnOver = true;
                    break;
                }
            }

            // Mechs moving up a cliff
            if (mechAffectedByCliff && !quadveeVehMode && isUpCliff && !isPavementStep) {
                rollTarget = entity.getBasePilotingRoll(moveType);
                rollTarget.append(new PilotingRollData(entity.getId(), stepHeight, "moving up a sheer cliff"));
                if (server.doSkillCheckWhileMoving(entity, lastElevation, lastPos, lastPos, rollTarget, false) > 0) {
                    reportManager.addReport(ReportFactory.createReport(2209, entity));
                    reportManager.addNewLines();
                    curPos = entity.getPosition();
                    mpUsed = step.getMpUsed();
                    continueTurnFromCliffAscent = true;
                    break;
                }
            }

            // did the entity just fall?
            if (!wasProne && entity.isProne()) {
                curFacing = entity.getFacing();
                curPos = entity.getPosition();
                mpUsed = step.getMpUsed();
                fellDuringMovement = true;
                break;
            }

            // dropping prone intentionally?
            if (step.getType() == MovePath.MoveStepType.GO_PRONE) {
                mpUsed = step.getMpUsed();
                rollTarget = entity.checkDislodgeSwarmers(step, overallMoveType);
                if (rollTarget.getValue() == TargetRoll.CHECK_FALSE) {
                    // Not being swarmed
                    entity.setProne(true);
                    // check to see if we washed off infernos
                    checkForWashedInfernos(entity, curPos);
                } else {
                    // Being swarmed
                    entity.setPosition(curPos);
                    if (doDislodgeSwarmerSkillCheck(entity, rollTarget, curPos)) {
                        // Entity falls
                        curFacing = entity.getFacing();
                        curPos = entity.getPosition();
                        fellDuringMovement = true;
                        break;
                    }
                    // roll failed, go prone but don't dislodge swarmers
                    entity.setProne(true);
                    // check to see if we washed off infernos
                    checkForWashedInfernos(entity, curPos);
                    break;
                }
            }

            // going hull down
            if (step.getType() == MovePath.MoveStepType.HULL_DOWN) {
                mpUsed = step.getMpUsed();
                entity.setHullDown(true);
            }

            // Check for crushing buildings by Dropships/Mobile Structures
            for (Coords pos : step.getCrushedBuildingLocs()) {
                Building bldg = game.getBoard().getBuildingAt(pos);
                IHex hex = game.getBoard().getHex(pos);
                reportManager.addReport(ReportFactory.createReport(3443, entity, bldg.getName()));

                final int cf = bldg.getCurrentCF(pos);
                final int numFloors = Math.max(0, hex.terrainLevel(Terrains.BLDG_ELEV));
                reportManager.addReport(server.damageBuilding(bldg, 150, " is crushed for ", pos));
                int damage = (int) Math.round((cf / 10.0) * numFloors);
                HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                reportManager.addReport(server.damageEntity(entity, hit, damage));
            }

            // Track this step's location.
            movePath.addElement(new UnitLocation(entity.getId(), curPos,
                    curFacing, step.getElevation()));

            // if the lastpos is not the same as the current position
            // then add the current position to the list of places passed
            // through
            if (!curPos.equals(lastPos)) {
                passedThrough.add(curPos);
                passedThroughFacing.add(curFacing);
            }

            // update lastPos, prevStep, prevFacing & prevHex
            if (!curPos.equals(lastPos)) {
                prevFacing = curFacing;
            }
            lastPos = curPos;
            lastElevation = curElevation;
            prevStep = step;
            prevHex = curHex;

            firstStep = false;
        }

        // set entity parameters
        entity.setPosition(curPos);
        entity.setFacing(curFacing);
        entity.setSecondaryFacing(curFacing);
        entity.delta_distance = distance;
        entity.moved = moveType;
        entity.mpUsed = mpUsed;
        entity.setClimbMode(curClimbMode);
        if (!sideslipped && !fellDuringMovement && !crashedDuringMovement
                && (entity.getMovementMode() == EntityMovementMode.VTOL)) {
            entity.setElevation(curVTOLElevation);
        }
        entity.setAltitude(curAltitude);
        entity.setClimbMode(curClimbMode);

        // add a list of places passed through
        entity.setPassedThrough(passedThrough);
        entity.setPassedThroughFacing(passedThroughFacing);

        // if we ran with destroyed hip or gyro, we need a psr
        rollTarget = entity.checkRunningWithDamage(overallMoveType);
        if (rollTarget.getValue() != TargetRoll.CHECK_FALSE && entity.canFall()) {
            server.doSkillCheckInPlace(entity, rollTarget);
        }

        // if we sprinted with MASC or a supercharger, then we need a PSR
        rollTarget = entity.checkSprintingWithMASC(overallMoveType, entity.mpUsed);
        if (rollTarget.getValue() != TargetRoll.CHECK_FALSE && entity.canFall()) {
            server.doSkillCheckInPlace(entity, rollTarget);
        }

        // if we used ProtoMech myomer booster, roll 2d6
        // pilot damage on a 2
        if ((entity instanceof Protomech) && ((Protomech) entity).hasMyomerBooster()
                && (md.getMpUsed() > ((Protomech) entity)
                .getRunMPwithoutMyomerBooster(true, false, false))) {
            Report r = ReportFactory.createReport(2373, entity);
            int roll = Compute.d6(2);
            r.add(roll);
            if (roll > 2) {
                r.choose(true);
                reportManager.addReport(r);
            } else {
                r.choose(false);
                reportManager.addReport(r);
                reportManager.addReport(server.damageCrew(entity, 1));
            }
        }

        rollTarget = entity.checkSprintingWithSupercharger(overallMoveType, entity.mpUsed);
        if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
            server.doSkillCheckInPlace(entity, rollTarget);
        }
        if ((md.getLastStepMovementType() == EntityMovementType.MOVE_SPRINT)
                && md.hasActiveMASC() && entity.canFall()) {
            server.doSkillCheckInPlace(entity, entity.getBasePilotingRoll(EntityMovementType.MOVE_SPRINT));
        }

        if (entity.isAirborne() && entity.isAero()) {
            IAero a = (IAero) entity;
            int thrust = md.getMpUsed();

            // consume fuel
            if (((entity.isAero())
                    && game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_FUEL_CONSUMPTION))
                    || (entity instanceof TeleMissile)) {
                int fuelUsed = ((IAero) entity).getFuelUsed(thrust);
                a.useFuel(fuelUsed);
            }

            // JumpShips and space stations need to reduce accumulated thrust if
            // they spend some
            if (entity instanceof Jumpship) {
                Jumpship js = (Jumpship) entity;
                double penalty = 0.0;
                // JumpShips do not accumulate thrust when they make a turn or
                // change velocity
                if (md.contains(MovePath.MoveStepType.TURN_LEFT) || md.contains(MovePath.MoveStepType.TURN_RIGHT)) {
                    // I need to subtract the station keeping thrust from their
                    // accumulated thrust
                    // because they did not actually use it
                    penalty = js.getStationKeepingThrust();
                }
                if (thrust > 0) {
                    penalty = thrust;
                }
                if (penalty > 0.0) {
                    js.setAccumulatedThrust(Math.max(0, js.getAccumulatedThrust() - penalty));
                }
            }

            // check to see if thrust exceeded SI

            rollTarget = a.checkThrustSITotal(thrust, overallMoveType);
            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                game.addControlRoll(new PilotingRollData(entity.getId(), 0,
                        "Thrust spent during turn exceeds SI"));
            }

            if (!game.getBoard().inSpace()) {
                rollTarget = a.checkVelocityDouble(md.getFinalVelocity(), overallMoveType);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    game.addControlRoll(new PilotingRollData(entity.getId(), 0,
                            "Velocity greater than 2x safe thrust"));
                }

                rollTarget = a.checkDown(md.getFinalNDown(), overallMoveType);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    game.addControlRoll(
                            new PilotingRollData(entity.getId(), md.getFinalNDown(),
                                    "descended more than two altitudes"));
                }

                // check for hovering
                rollTarget = a.checkHover(md);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    game.addControlRoll(new PilotingRollData(entity.getId(), 0, "hovering"));
                }

                // check for aero stall
                rollTarget = a.checkStall(md);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    reportManager.addReport(ReportFactory.createReport(9391, entity));
                    game.addControlRoll(new PilotingRollData(entity.getId(), 0, "stalled out"));
                    entity.setAltitude(entity.getAltitude() - 1);
                    // check for crash
                    if (game.checkCrash(entity, entity.getPosition(), entity.getAltitude())) {
                        reportManager.addReport(server.processCrash(entity, 0, entity.getPosition()));
                    }
                }

                // check to see if spheroids should lose one altitude
                if (a.isSpheroid() && !a.isSpaceborne()
                        && a.isAirborne() && (md.getFinalNDown() == 0) && (md.getMpUsed() == 0)) {
                    reportManager.addReport(ReportFactory.createReport(9392, entity));
                    entity.setAltitude(entity.getAltitude() - 1);
                    // check for crash
                    if (game.checkCrash(entity, entity.getPosition(), entity.getAltitude())) {
                        reportManager.addReport(server.processCrash(entity, 0, entity.getPosition()));
                    }
                } else if (entity instanceof EscapePods && entity.isAirborne() && md.getFinalVelocity() < 2) {
                    //Atmospheric Escape Pods that drop below velocity 2 lose altitude as dropping units
                    entity.setAltitude(entity.getAltitude() - game.getPlanetaryConditions().getDropRate());
                    reportManager.addReport(ReportFactory.createReport(6676, entity, game.getPlanetaryConditions().getDropRate()));
                }
            }
        }

        // We need to check for the removal of hull-down for tanks.
        // Tanks can just drive out of hull-down: if the tank was hull-down
        // and doesn't end hull-down we can remove the hull-down status
        if (entity.isHullDown() && !md.getFinalHullDown() && (entity instanceof Tank
                || (entity instanceof QuadVee && entity.getConversionMode() == QuadVee.CONV_MODE_VEHICLE))) {
            entity.setHullDown(false);
        }

        // If the entity is being swarmed, erratic movement may dislodge the fleas.
        final int swarmerId = entity.getSwarmAttackerId();
        if ((Entity.NONE != swarmerId) && md.contains(MovePath.MoveStepType.SHAKE_OFF_SWARMERS)) {
            final Entity swarmer = game.getEntity(swarmerId);
            rollTarget = entity.getBasePilotingRoll(overallMoveType);

            entity.addPilotingModifierForTerrain(rollTarget);

            // Add a +4 modifier.
            if (md.getLastStepMovementType() == EntityMovementType.MOVE_VTOL_RUN) {
                rollTarget.addModifier(2, "dislodge swarming infantry with VTOL movement");
            } else {
                rollTarget.addModifier(4, "dislodge swarming infantry");
            }

            // If the swarmer has Assault claws, give a 1 modifier.
            // We can stop looking when we find our first match.
            for (Mounted mount : swarmer.getMisc()) {
                EquipmentType equip = mount.getType();
                if (equip.hasFlag(MiscType.F_MAGNET_CLAW)) {
                    rollTarget.addModifier(1, "swarmer has magnetic claws");
                    break;
                }
            }
            handleSwarmingResult(entity, swarmer, rollTarget, curPos);
        } // End try-to-dislodge-swarmers

        // but the danger isn't over yet! landing from a jump can be risky!
        if ((overallMoveType == EntityMovementType.MOVE_JUMP) && !entity.isMakingDfa()) {
            final IHex curHex = game.getBoard().getHex(curPos);
            // check for damaged criticals
            rollTarget = entity.checkLandingWithDamage(overallMoveType);
            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                server.doSkillCheckInPlace(entity, rollTarget);
            }
            // check for prototype JJs
            rollTarget = entity.checkLandingWithPrototypeJJ(overallMoveType);
            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                server.doSkillCheckInPlace(entity, rollTarget);
            }
            // check for jumping into heavy woods
            if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_PSR_JUMP_HEAVY_WOODS)) {
                rollTarget = entity.checkLandingInHeavyWoods(overallMoveType, curHex);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    server.doSkillCheckInPlace(entity, rollTarget);
                }
            }
            // Mechanical jump boosters fall damage
            if (md.shouldMechanicalJumpCauseFallDamage()) {
                reportManager.addReport(server.doEntityFallsInto(entity, entity.getElevation(), md.getJumpPathHighestPoint(),
                        curPos, entity.getBasePilotingRoll(overallMoveType), false, entity.getJumpMP()));
            }
            // jumped into water?
            int waterLevel = curHex.terrainLevel(Terrains.WATER);
            if (curHex.containsTerrain(Terrains.ICE) && (waterLevel > 0)) {
                if (!(entity instanceof Infantry)) {
                    // check for breaking ice
                    int roll = Compute.d6(1);
                    Report r = ReportFactory.createReport(2122, entity, entity.getDisplayName());
                    r.add(roll);
                    reportManager.addReport(r);
                    if (roll >= 4) {
                        // oops!
                        entity.setPosition(curPos);
                        reportManager.addReport(server.resolveIceBroken(curPos));
                        curPos = entity.getPosition();
                    } else {
                        // TacOps: immediate PSR with +4 for terrain. If you
                        // fall then may break the ice after all
                        rollTarget = entity.checkLandingOnIce(overallMoveType, curHex);
                        if (!server.doSkillCheckInPlace(entity, rollTarget)) {
                            // apply damage now, or it will show up as a
                            // possible breach, if ice is broken
                            entity.applyDamage();
                            roll = Compute.d6(1);
                            reportManager.addReport(ReportFactory.createReport(2118, entity, roll));
                            if (roll == 6) {
                                entity.setPosition(curPos);
                                reportManager.addReport(server.resolveIceBroken(curPos));
                                curPos = entity.getPosition();
                            }
                        }
                    }
                }
            } else if (!(prevStep.climbMode() && curHex.containsTerrain(Terrains.BRIDGE))
                    && !(entity.getMovementMode() == EntityMovementMode.HOVER)) {
                rollTarget = entity.checkWaterMove(waterLevel, overallMoveType);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    // For falling elevation, Entity must not on hex surface
                    int currElevation = entity.getElevation();
                    entity.setElevation(0);
                    boolean success = server.doSkillCheckInPlace(entity, rollTarget);
                    if (success) {
                        entity.setElevation(currElevation);
                    }
                }
                if (waterLevel > 1) {
                    // Any swarming infantry will be destroyed.
                    drownSwarmer(entity, curPos);
                }
            }

            // check for building collapse
            Building bldg = game.getBoard().getBuildingAt(curPos);
            if (bldg != null) {
                server.checkForCollapse(bldg, game.getPositionMap(), curPos, true,
                        reportManager.getvPhaseReport());
            }

            // Don't interact with terrain when jumping onto a building or a bridge
            if (entity.getElevation() == 0) {
                ServerHelper.checkAndApplyMagmaCrust(curHex, entity.getElevation(), entity, curPos, true, reportManager.getvPhaseReport(), server);

                // jumped into swamp? maybe stuck!
                if (curHex.getBogDownModifier(entity.getMovementMode(),
                        entity instanceof LargeSupportTank) != TargetRoll.AUTOMATIC_SUCCESS) {
                    if (entity instanceof Mech) {
                        entity.setStuck(true);
                        reportManager.addReport(ReportFactory.createReport(2121, entity, entity.getDisplayName()));
                        // check for quicksand
                        reportManager.addReport(server.checkQuickSand(curPos));
                    } else if (!entity.hasETypeFlag(Entity.ETYPE_INFANTRY)) {
                        rollTarget = new PilotingRollData(entity.getId(),
                                5, "entering boggy terrain");
                        rollTarget.append(new PilotingRollData(entity.getId(),
                                curHex.getBogDownModifier(entity.getMovementMode(), entity instanceof LargeSupportTank),
                                "avoid bogging down"));
                        if (0 < server.doSkillCheckWhileMoving(entity, entity.getElevation(), curPos, curPos,
                                rollTarget, false)) {
                            entity.setStuck(true);
                            reportManager.addReport(ReportFactory.createReport(2081, entity, entity.getDisplayName()));
                            // check for quicksand
                            reportManager.addReport(server.checkQuickSand(curPos));
                        }
                    }
                }
            }
            // If the entity is being swarmed, jumping may dislodge the fleas.
            if (Entity.NONE != swarmerId) {
                final Entity swarmer = game.getEntity(swarmerId);
                rollTarget = entity.getBasePilotingRoll(overallMoveType);

                entity.addPilotingModifierForTerrain(rollTarget);

                // Add a +4 modifier.
                rollTarget.addModifier(4, "dislodge swarming infantry");

                // If the swarmer has Assault claws, give a 1 modifier.
                // We can stop looking when we find our first match.
                if (swarmer.hasWorkingMisc(MiscType.F_MAGNET_CLAW, -1)) {
                    rollTarget.addModifier(1, "swarmer has magnetic claws");
                }
                handleSwarmingResult(entity, swarmer, rollTarget, curPos);
            } // End try-to-dislodge-swarmers

            // one more check for inferno wash-off
            checkForWashedInfernos(entity, curPos);

            // a jumping tank needs to roll for movement damage
            if (entity instanceof Tank) {
                int modifier = 0;
                if (curHex.containsTerrain(Terrains.ROUGH)
                        || curHex.containsTerrain(Terrains.WOODS)
                        || curHex.containsTerrain(Terrains.JUNGLE)) {
                    modifier = 1;
                }
                reportManager.addReport(ReportFactory.createReport(2126, entity));
                reportManager.addReport(server.vehicleMotiveDamage((Tank)entity, modifier,
                        false, -1, true));
                Report.addNewline(reportManager.getvPhaseReport());
            }
        } // End entity-is-jumping

        //If converting to another mode, set the final movement mode and report it
        if (entity.isConvertingNow()) {
            Report r = ReportFactory.createReport(1210, entity);
            if (entity instanceof QuadVee && entity.isProne()
                    && entity.getConversionMode() == QuadVee.CONV_MODE_MECH) {
                //Fall while converting to vehicle mode cancels conversion.
                entity.setConvertingNow(false);
                r.messageId = 2454;
            } else {
                // LAMs converting from fighter mode need to have the elevation set properly.
                if (entity.isAero()) {
                    if (md.getFinalConversionMode() == EntityMovementMode.WIGE
                            && entity.getAltitude() > 0 && entity.getAltitude() <= 3) {
                        entity.setElevation(entity.getAltitude() * 10);
                        entity.setAltitude(0);
                    } else {
                        IHex hex = game.getBoard().getHex(entity.getPosition());
                        if (hex.containsTerrain(Terrains.BLDG_ELEV)) {
                            entity.setElevation(hex.terrainLevel(Terrains.BLDG_ELEV));
                        } else {
                            entity.setElevation(0);
                        }
                    }
                }
                entity.setMovementMode(md.getFinalConversionMode());
                if (entity instanceof Mech && ((Mech)entity).hasTracks()) {
                    r.messageId = 2455;
                    r.choose(entity.getMovementMode() == EntityMovementMode.TRACKED);
                } else if (entity.getMovementMode() == EntityMovementMode.TRACKED
                        || entity.getMovementMode() == EntityMovementMode.WHEELED) {
                    r.messageId = 2451;
                } else if (entity.getMovementMode() == EntityMovementMode.WIGE) {
                    r.messageId = 2452;
                } else if (entity.getMovementMode() == EntityMovementMode.AERODYNE) {
                    r.messageId = 2453;
                } else {
                    r.messageId = 2450;
                }
                if (entity.isAero()) {
                    int altitude = entity.getAltitude();
                    if (altitude == 0 && md.getFinalElevation() >= 8) {
                        altitude = 1;
                    }
                    if (altitude == 0) {
                        ((IAero)entity).land();
                    } else {
                        ((IAero)entity).liftOff(altitude);
                    }
                }
            }
            reportManager.addReport(r);
        }

        // update entity's locations' exposure
        reportManager.addReport(server.doSetLocationsExposure(entity,
                game.getBoard().getHex(curPos), false, entity.getElevation()));

        // Check the falls_end_movement option to see if it should be able to
        // move on.
        // Need to check here if the 'Mech actually went from non-prone to prone
        // here because 'fellDuringMovement' is sometimes abused just to force
        // another turn and so doesn't reliably tell us.
        boolean continueTurnFromFall = !(game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_FALLS_END_MOVEMENT)
                && (entity instanceof Mech) && !wasProne && entity.isProne())
                && (fellDuringMovement && !entity.isCarefulStand()) // Careful standing takes up the whole turn
                && !turnOver && (entity.mpUsed < entity.getRunMP())
                && (overallMoveType != EntityMovementType.MOVE_JUMP);
        if ((continueTurnFromFall || continueTurnFromPBS || continueTurnFromFishtail || continueTurnFromLevelDrop || continueTurnFromCliffAscent)
                && entity.isSelectableThisTurn() && !entity.isDoomed()) {
            entity.applyDamage();
            entity.setDone(false);
            GameTurn newTurn = new GameTurn.SpecificEntityTurn(entity.getOwner().getId(), entity.getId());
            // Need to set the new turn's multiTurn state
            newTurn.setMultiTurn(true);
            game.insertNextTurn(newTurn);
            // brief everybody on the turn update
            server.send(PacketFactory.createTurnVectorPacket(game));
            // let everyone know about what just happened
            if (reportManager.getvPhaseReport().size() > 1) {
                server.send(entity.getOwner().getId(), PacketFactory.createSpecialReportPacket(reportManager));
            }
        } else {
            if (entity.getMovementMode() == EntityMovementMode.WIGE) {
                IHex hex = game.getBoard().getHex(curPos);
                if (md.automaticWiGELanding(false)) {
                    // try to land safely; LAMs require a psr when landing with gyro or leg actuator
                    // damage and ProtoMechs always require a roll
                    int elevation = (null == prevStep)? entity.getElevation() : prevStep.getElevation();
                    if (entity.hasETypeFlag(Entity.ETYPE_LAND_AIR_MECH)) {
                        reportManager.addReport(server.landAirMech((LandAirMech) entity, entity.getPosition(), elevation, entity.delta_distance));
                    } else if (entity.hasETypeFlag(Entity.ETYPE_PROTOMECH)) {
                        reportManager.addReport(server.landGliderPM((Protomech) entity, entity.getPosition(), elevation, entity.delta_distance));
                    } else {
                        reportManager.addReport(ReportFactory.createReport(2123, entity));
                    }

                    if (hex.containsTerrain(Terrains.BLDG_ELEV)) {
                        Building bldg = game.getBoard().getBuildingAt(entity.getPosition());
                        entity.setElevation(hex.terrainLevel(Terrains.BLDG_ELEV));
                        server.addAffectedBldg(bldg, server.checkBuildingCollapseWhileMoving(bldg, entity, entity.getPosition()));
                    } else if (entity.isLocationProhibited(entity.getPosition(), 0) && !hex.hasPavement()){
                        // crash
                        reportManager.addReport(ReportFactory.createReport(2124, entity));
                        reportManager.addReport(server.crashVTOLorWiGE((Tank) entity));
                    } else {
                        entity.setElevation(0);
                    }

                    // Check for stacking violations in the target hex
                    Entity violation = Compute.stackingViolation(game, entity.getId(), entity.getPosition());
                    if (violation != null) {
                        PilotingRollData prd = new PilotingRollData(violation.getId(), 2, "fallen on");
                        if (violation instanceof Dropship) {
                            violation = entity;
                            prd = null;
                        }
                        Coords targetDest = Compute.getValidDisplacement(game,
                                violation.getId(), entity.getPosition(), 0);
                        if (targetDest != null) {
                            reportManager.addReport(server.doEntityDisplacement(violation,
                                    entity.getPosition(), targetDest, prd));
                            // Update the violating entity's position on the
                            // client.
                            entityUpdate(violation.getId());
                        } else {
                            // ack! automatic death! Tanks
                            // suffer an ammo/power plant hit.
                            // TODO : a Mech suffers a Head Blown Off crit.
                            reportManager.addReport(destroyEntity(violation,
                                    "impossible displacement",
                                    violation instanceof Mech, violation instanceof Mech));
                        }
                    }
                } else if (!entity.hasETypeFlag(Entity.ETYPE_LAND_AIR_MECH)
                        && !entity.hasETypeFlag(Entity.ETYPE_PROTOMECH)) {

                    // we didn't land, so we go to elevation 1 above the terrain
                    // features
                    // it might have been higher than one due to the extra MPs
                    // it can spend to stay higher during movement, but should
                    // end up at one
                    entity.setElevation(Math.min(entity.getElevation(), 1 + hex.maxTerrainFeatureElevation(
                            game.getBoard().inAtmosphere())));
                }
            }

            // If we've somehow gotten here as an airborne LAM with a destroyed side torso
            // (such as conversion while dropping), crash now.
            if (entity instanceof LandAirMech
                    && (entity.isLocationBad(Mech.LOC_RT) || entity.isLocationBad(Mech.LOC_LT))) {
                if (entity.isAirborneVTOLorWIGE()) {
                    reportManager.addReport(ReportFactory.createReport(9710, entity));
                    server.crashAirMech(entity, new PilotingRollData(entity.getId(), TargetRoll.AUTOMATIC_FAIL,
                            "side torso destroyed"));
                } else if (entity.isAirborne() && entity.isAero()) {
                    reportManager.addReport(ReportFactory.createReport(9710, entity));
                    reportManager.addReport(server.processCrash(entity, ((IAero)entity).getCurrentVelocity(), entity.getPosition()));
                }
            }

            entity.setDone(true);
        }

        if (dropshipStillUnloading) {
            // turns should have already been inserted but we need to set the
            // entity as not done
            entity.setDone(false);
        }

        // If the entity is being swarmed, update the attacker's position.
        if (Entity.NONE != swarmerId) {
            final Entity swarmer = game.getEntity(swarmerId);
            swarmer.setPosition(curPos);
            // If the hex is on fire, and the swarming infantry is
            // *not* Battle Armor, it drops off.
            if (!(swarmer instanceof BattleArmor) && game.getBoard().getHex(curPos).containsTerrain(Terrains.FIRE)) {
                swarmer.setSwarmTargetId(Entity.NONE);
                entity.setSwarmAttackerId(Entity.NONE);
                reportManager.addReport(ReportFactory.createReport(2145, 1, entity, swarmer.getShortName()));
            }
            entityUpdate(swarmerId);
        }

        // Update the entity's position,
        // unless it is off the game map.
        if (!game.isOutOfGame(entity)) {
            entityUpdate(entity.getId(), movePath, true, losCache);
            if (entity.isDoomed()) {
                server.send(PacketFactory.createRemoveEntityPacket(entity.getId(), entity.getRemovalCondition()));
            }
        }

        //If the entity is towing trailers, update the position of those trailers
        if (!entity.getAllTowedUnits().isEmpty()) {
            List<Integer> reversedTrailers = new ArrayList<>(entity.getAllTowedUnits()); // initialize with a copy (no need to initialize to an empty list first)
            Collections.reverse(reversedTrailers); // reverse in-place
            List<Coords> trailerPath = game.initializeTrailerCoordinates(entity, reversedTrailers); // no need to initialize to an empty list first
            processTrailerMovement(entity, trailerPath);
        }

        // recovered units should now be recovered and dealt with
        if (entity.isAero() && recovered && (loader != null)) {
            if (loader.isCapitalFighter()) {
                if (!(loader instanceof FighterSquadron)) {
                    // this is a solo capital fighter so we need to add a new
                    // squadron and load both the loader and loadee
                    FighterSquadron fs = new FighterSquadron();
                    fs.setDeployed(true);
                    fs.setId(game.getNextEntityId());
                    fs.setCurrentVelocity(((Aero) loader).getCurrentVelocity());
                    fs.setNextVelocity(((Aero) loader).getNextVelocity());
                    fs.setVectors(loader.getVectors());
                    fs.setFacing(loader.getFacing());
                    fs.setOwner(entity.getOwner());
                    // set velocity and heading the same as parent entity
                    game.addEntity(fs);
                    server.send(PacketFactory.createAddEntityPacket(game, fs.getId()));
                    // make him not get a move this turn
                    fs.setDone(true);
                    // place on board
                    fs.setPosition(loader.getPosition());
                    server.loadUnit(fs, loader, -1);
                    loader = fs;
                    entityUpdate(fs.getId());
                }
                loader.load(entity);
            } else {
                loader.recover(entity);
                entity.setRecoveryTurn(5);
            }

            // The loaded unit is being carried by the loader.
            entity.setTransportId(loader.getId());

            // Remove the loaded unit from the screen.
            entity.setPosition(null);

            // Update the loaded unit.
            entityUpdate(entity.getId());
        }

        // even if load was unsuccessful, I may need to update the loader
        if (null != loader) {
            entityUpdate(loader.getId());
        }

        // if using double blind, update the player on new units he might see
        if (game.doBlind()) {
            server.send(entity.getOwner().getId(), PacketFactory.createFilteredEntitiesPacket(entity.getOwner(), losCache, game, gameManager));
        }

        // if we generated a charge attack, report it now
        if (charge != null) {
            server.send(PacketFactory.createAttackPacket(charge, 1));
        }

        // if we generated a ram attack, report it now
        if (ram != null) {
            server.send(PacketFactory.createAttackPacket(ram, 1));
        }
        if ((entity instanceof Mech) && entity.hasEngine() && ((Mech) entity).isIndustrial()
                && !entity.hasEnvironmentalSealing()
                && (entity.getEngine().getEngineType() == Engine.COMBUSTION_ENGINE)) {
            ((Mech) entity).setJustMovedIntoIndustrialKillingWater((!entity.isProne() && (game.getBoard().getHex(entity.getPosition()).terrainLevel(Terrains.WATER) >= 2)) || (entity.isProne()
                    && (game.getBoard().getHex(entity.getPosition()).terrainLevel(Terrains.WATER) == 1)));
        }
    }

    /**
     * processes a potential collision
     *
     * @param entity
     * @param target
     * @param src
     * @return
     */
    private boolean processCollision(Entity entity, Entity target, Coords src) {
        reportManager.addReport(ReportFactory.createReport(9035, entity, entity.getDisplayName(), target.getDisplayName()));
        boolean partial = (Compute.d6() == 6);
        // if aero chance to avoid
        if ((target.isAero())
                && (target.mpUsed < target.getRunMPwithoutMASC())
                && !((IAero) target).isOutControlTotal() && !target.isImmobile()) {
            // give them a control roll to avoid the collision
            // TODO : I should make this voluntary really
            IAero ta = (IAero) target;
            PilotingRollData psr = target.getBasePilotingRoll();
            psr.addModifier(0, "avoiding collision");
            int ctrlroll = Compute.d6(2);
            Report r = ReportFactory.createReport(9045, 2, target, target.getDisplayName());
            r.add(psr.getValue());
            r.add(ctrlroll);
            if (ctrlroll < psr.getValue()) {
                r.choose(false);
                reportManager.addReport(r);
            } else {
                // avoided collision
                r.choose(true);
                reportManager.addReport(r);
                // two possibilities:
                // 1) the target already moved, but had MP left - check for
                // control roll conditions
                // 2) the target had not yet moved, move them in straight line
                if (!target.isDone()) {
                    int vel = ta.getCurrentVelocity();
                    MovePath md = new MovePath(game, target);
                    while (vel > 0) {
                        md.addStep(MovePath.MoveStepType.FORWARDS);
                        vel--;
                    }
                    game.removeTurnFor(target);
                    server.send(PacketFactory.createTurnVectorPacket(game));
                    processMovement(target, md, null);
                    // for some reason it is not clearing out turn
                } else {
                    // what needs to get checked?
                    // this move puts them at over-thrust
                    target.moved = EntityMovementType.MOVE_OVER_THRUST;
                    // they may have exceeded SI, only add if they hadn't
                    // exceeded it before
                    if (target.mpUsed <= ta.getSI()) {
                        PilotingRollData rollTarget = ta.checkThrustSITotal(target.getRunMPwithoutMASC(), target.moved);
                        if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                            game.addControlRoll(new PilotingRollData(
                                    target.getId(), 0, "Thrust spent during turn exceeds SI"));
                        }
                    }
                    target.mpUsed = target.getRunMPwithoutMASC();
                }
                return false;
            }
        } else {
            // can't avoid collision - write report
            reportManager.addReport(ReportFactory.createReport(9040, 2, entity, entity.getDisplayName()));
        }

        // if we are still here, then collide
        ToHitData toHit = new ToHitData(TargetRoll.AUTOMATIC_SUCCESS, "Its a collision");
        toHit.setSideTable(target.sideTable(src));
        server.resolveRamDamage((IAero)entity, target, toHit, partial, false);

        // Has the target been destroyed?
        if (target.isDoomed()) {
            // Has the target taken a turn?
            if (!target.isDone()) {
                // Dead entities don't take turns.
                game.removeTurnFor(target);
                server.send(PacketFactory.createTurnVectorPacket(game));
            } // End target-still-to-move
            // Clean out the entity.
            target.setDestroyed(true);
            game.moveToGraveyard(target.getId());
            server.send(PacketFactory.createRemoveEntityPacket(target.getId()));
        }
        // Update the target's position,
        // unless it is off the game map.
        if (!game.isOutOfGame(target)) {
            entityUpdate(target.getId());
        }

        return true;
    }

    /**
     * Roll on the failed vehicle maneuver table.
     *
     * @param entity    The vehicle that failed the maneuver.
     * @param curPos    The coordinates of the hex in which the maneuver was attempted.
     * @param turnDirection The difference between the intended final facing and the starting facing
     *                      (-1 for left turn, 1 for right turn, 0 for not turning).
     * @param prevStep  The <code>MoveStep</code> immediately preceding the one being processed.
     *                  Cannot be null; if the check is made for the first step of the path,
     *                  use the current step.
     * @param lastStepMoveType  The <code>EntityMovementType</code> of the last step in the path.
     * @param distance  The distance moved so far during the phase; used to calculate any potential skid.
     * @param modifier  The modifier to the maneuver failure roll.
     * @return          true if the maneuver failure result ends the unit's turn.
     */
    private boolean processFailedVehicleManeuver(Entity entity, Coords curPos, int turnDirection,
                                                 MoveStep prevStep, boolean isBackwards, EntityMovementType lastStepMoveType, int distance,
                                                 int modifier, int marginOfFailure) {
        IHex curHex = game.getBoard().getHex(curPos);
        if (entity.getMovementMode() == EntityMovementMode.WHEELED && !curHex.containsTerrain(Terrains.PAVEMENT)) {
            modifier += 2;
        }
        if (entity.getMovementMode() == EntityMovementMode.VTOL) {
            modifier += 2;
        } else if (entity.getMovementMode() == EntityMovementMode.HOVER
                || (entity.getMovementMode() == EntityMovementMode.WIGE && entity instanceof Tank)
                || entity.getMovementMode() == EntityMovementMode.HYDROFOIL) {
            modifier += 4;
        }
        if (entity.getWeightClass() < EntityWeightClass.WEIGHT_MEDIUM
                || entity.getWeightClass() == EntityWeightClass.WEIGHT_SMALL_SUPPORT) {
            modifier++;
        } else if (entity.getWeightClass() == EntityWeightClass.WEIGHT_HEAVY
                || entity.getWeightClass() == EntityWeightClass.WEIGHT_LARGE_SUPPORT) {
            modifier--;
        } else if (entity.getWeightClass() == EntityWeightClass.WEIGHT_ASSAULT
                || entity.getWeightClass() == EntityWeightClass.WEIGHT_SUPER_HEAVY) {
            modifier -= 2;
        }
        boolean turnEnds = false;
        boolean motiveDamage = false;
        int motiveDamageMod = 0;
        boolean skid = false;
        boolean flip = false;
        boolean isGroundVehicle = ((entity instanceof Tank) && ((entity.getMovementMode() == EntityMovementMode.TRACKED)
                || (entity.getMovementMode() == EntityMovementMode.WHEELED)));

        int roll = Compute.d6(2);

        reportManager.addReport(ReportFactory.createReport(2505, 2, entity));
        reportManager.addReport(ReportFactory.createReport(6310, entity, roll));
        reportManager.addReport(ReportFactory.createReport(3340, entity, modifier));

        int reportID = 1210;
        roll += modifier;
        if (roll < 8) {
            reportID = 2506;
            // minor fishtail, fail to turn
            turnDirection = 0;
        } else if (roll < 10) {
            reportID = 2507;
            // moderate fishtail, turn an extra hexside and roll for motive damage at -1.
            if (turnDirection == 0) {
                turnDirection = Compute.d6() < 4? -1 : 1;
            } else {
                turnDirection *= 2;
            }
            motiveDamage = true;
            motiveDamageMod = -1;
        } else if (roll < 12) {
            reportID = 2508;
            // serious fishtail, turn an extra hexside and roll for motive damage. Turn ends.
            if (turnDirection == 0) {
                turnDirection = Compute.d6() < 4? -1 : 1;
            } else {
                turnDirection *= 2;
            }
            motiveDamage = true;
            turnEnds = true;
        } else {
            reportID = 2509;
            // Turn fails and vehicle skids
            // Wheeled and naval vehicles start to flip if the roll is high enough.
            if (roll > 13) {
                if (entity.getMovementMode() == EntityMovementMode.WHEELED) {
                    reportID = 2510;
                    flip = true;
                } else if (entity.getMovementMode() == EntityMovementMode.NAVAL
                        || entity.getMovementMode() == EntityMovementMode.HYDROFOIL) {
                    entity.setDoomed(true);
                    reportID = 2511;
                }
            }
            skid = true;
            turnEnds = true;
        }
        reportManager.addReport(ReportFactory.createReport(reportID, entity));
        entity.setFacing((entity.getFacing() + turnDirection + 6) % 6);
        entity.setSecondaryFacing(entity.getFacing());
        if (motiveDamage && isGroundVehicle) {
            reportManager.addReport(server.vehicleMotiveDamage((Tank)entity, motiveDamageMod));
        }
        if (skid && !entity.isDoomed()) {
            if (!flip && isGroundVehicle) {
                reportManager.addReport(server.vehicleMotiveDamage((Tank)entity, 0));
            }

            int skidDistance = (int)Math.round((double) (distance - 1) / 2);
            if (flip && entity.getMovementMode() == EntityMovementMode.WHEELED) {
                // Wheeled vehicles that start to flip reduce the skid distance by one hex.
                skidDistance--;
            } else if (entity.getMovementMode() == EntityMovementMode.HOVER
                    || entity.getMovementMode() == EntityMovementMode.VTOL
                    || entity.getMovementMode() == EntityMovementMode.WIGE) {
                skidDistance = Math.min(marginOfFailure, distance);
            }
            if (skidDistance > 0) {
                int skidDirection = prevStep.getFacing();
                if (isBackwards) {
                    skidDirection = (skidDirection + 3) % 6;
                }
                server.processSkid(entity, curPos, prevStep.getElevation(), skidDirection, skidDistance,
                        prevStep, lastStepMoveType, flip);
            }
        }
        return turnEnds;
    }

    /**
     * Have the loader tow the indicated unit. The unit being towed loses its
     * turn.
     *
     * @param loader - the <code>Entity</code> that is towing the unit.
     * @param unit   - the <code>Entity</code> being towed.
     */
    private void towUnit(Entity loader, Entity unit) {
        if ((game.getPhase() != IGame.Phase.PHASE_LOUNGE) && !unit.isDone()) {
            // Remove the *last* friendly turn (removing the *first* penalizes
            // the opponent too much, and re-calculating moves is too hard).
            game.removeTurnFor(unit);
            server.send(PacketFactory.createTurnVectorPacket(game));
        }

        loader.towUnit(unit.getId());

        // set deployment round of the loadee to equal that of the loader
        unit.setDeployRound(loader.getDeployRound());

        // Update the loader and towed units.
        entityUpdate(unit.getId());
        entityUpdate(loader.getId());
    }

    /**
     * Abandon a spacecraft (large or small).
     *
     * @param entity  The <code>Aero</code> to eject.
     * @param inSpace Is this ship spaceborne?
     * @param airborne Is this ship in atmospheric flight?
     * @param pos The coords of this ejection. Needed when abandoning a grounded ship
     * @return a <code>Vector</code> of report objects for the gamelog.
     */
    private Vector<Report> ejectSpacecraft(Aero entity, boolean inSpace, boolean airborne, Coords pos) {
        Vector<Report> vDesc = new Vector<>();
        Report r;

        // An entity can only eject it's crew once.
        if (entity.getCrew().isEjected()
                // If the crew are already dead, don't bother
                || entity.isCarcass()) {
            return vDesc;
        }

        // Try to launch some escape pods and lifeboats, if any are left
        if ((inSpace && (entity.getPodsLeft() > 0 || entity.getLifeBoatsLeft() > 0))
                || (airborne && entity.getPodsLeft() > 0)) {
            // Report the ejection
            PilotingRollData rollTarget = Server.getEjectModifiers(game, entity,
                    entity.getCrew().getCurrentPilotIndex(), false);
            r = new Report(2180);
            r.subject = entity.getId();
            r.addDesc(entity);
            r.add(rollTarget.getLastPlainDesc(), true);
            r.indent();
            vDesc.addElement(r);
            int roll = Compute.d6(2);
            int MOS = (roll - Math.max(2, rollTarget.getValue()));
            //Report the roll
            r = new Report(2190);
            r.subject = entity.getId();
            r.add(rollTarget.getValueAsString());
            r.add(rollTarget.getDesc());
            r.add(roll);
            r.indent();
            r.choose(roll >= rollTarget.getValue());
            vDesc.addElement(r);
            //Per SO p27, you get a certain number of escape pods away per turn per 100k tons of ship
            int escapeMultiplier = (int) (entity.getWeight() / 100000);
            //Set up the maximum number that CAN launch
            int toLaunch;
            if (roll < rollTarget.getValue()) {
                toLaunch = 1;
            } else {
                toLaunch = (1 + MOS) * Math.max(1, escapeMultiplier);
            }
            //And now modify it based on what the unit actually has TO launch
            int launchCounter = toLaunch;
            int totalLaunched = 0;
            boolean isPod = false;
            while (launchCounter > 0) {
                int launched;
                if (entity.getPodsLeft() > 0 && (airborne || entity.getPodsLeft() >= entity.getLifeBoatsLeft())) {
                    //Entity has more escape pods than lifeboats (or equal numbers)
                    launched = Math.min(launchCounter, entity.getPodsLeft());
                    entity.setLaunchedEscapePods(entity.getLaunchedEscapePods() + launched);
                    totalLaunched += launched;
                    launchCounter -= launched;
                    isPod = true;
                } else if (inSpace && entity.getLifeBoatsLeft() > 0 && (entity.getLifeBoatsLeft() > entity.getPodsLeft())) {
                    //Entity has more lifeboats left
                    launched = Math.min(launchCounter, entity.getLifeBoatsLeft());
                    entity.setLaunchedLifeBoats(entity.getLaunchedLifeBoats() + launched);
                    totalLaunched += launched;
                    launchCounter -= launched;
                } else {
                    //We've run out of both. End the loop
                    break;
                }
            }
            int nEscaped = Math.min((entity.getCrew().getCurrentSize() + entity.getNPassenger()), (totalLaunched * 6));
            //Report how many pods launched and how many escaped
            if (totalLaunched > 0) {
                vDesc.addElement(ReportFactory.createReport(6401, 1, entity, totalLaunched, nEscaped));
            }
            EscapePods pods = new EscapePods(entity,totalLaunched,isPod);
            entity.addEscapeCraft(pods.getExternalIdAsString());
            //Update the personnel numbers
            //If there are passengers aboard, get them out first
            if (entity.getNPassenger() > 0) {
                int change = Math.min(entity.getNPassenger(), nEscaped);
                entity.setNPassenger(Math.max(entity.getNPassenger() - nEscaped, 0));
                pods.addPassengers(entity.getExternalIdAsString(), change);
                nEscaped -= change;
            }
            //Now get the crew out with such space as is left
            if (nEscaped > 0) {
                entity.setNCrew(entity.getNCrew() - nEscaped);
                entity.getCrew().setCurrentSize(Math.max(0, entity.getCrew().getCurrentSize() - nEscaped));
                pods.addNOtherCrew(entity.getExternalIdAsString(), nEscaped);
                //*Damage* the host ship's crew to account for the people that left
                vDesc.addAll(server.damageCrew(entity,entity.getCrew().calculateHits()));
                if (entity.getCrew().getHits() >= Crew.DEATH) {
                    //Then we've finished ejecting
                    entity.getCrew().setEjected(true);
                }
            }
            // Need to set game manually; since game.addEntity not called yet
            // Don't want to do this yet, as Entity may not be added
            pods.setPosition(entity.getPosition());
            pods.setGame(game);
            pods.setDeployed(true);
            pods.setId(game.getNextEntityId());
            //Escape craft retain the heading and velocity of the unit they eject from
            pods.setVectors(entity.getVectors());
            pods.setFacing(entity.getFacing());
            pods.setCurrentVelocity(entity.getCurrentVelocity());
            //If the crew ejects, they should no longer be accelerating
            pods.setNextVelocity(entity.getVelocity());
            if (entity.isAirborne()) {
                pods.setAltitude(entity.getAltitude());
            }
            // Add Entity to game
            game.addEntity(pods);
            // No movement this turn
            pods.setDone(true);
            // Tell clients about new entity
            server.send(PacketFactory.createAddEntityPacket(game, pods.getId()));
            // Sent entity info to clients
            entityUpdate(pods.getId());
            if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_EJECTED_PILOTS_FLEE)) {
                game.removeEntity(pods.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
                server.send(PacketFactory.createRemoveEntityPacket(pods.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT));
            }
        } // End Escape Pod/Lifeboat Ejection
        else {
            if (airborne) {
                // Can't abandon in atmosphere with no escape pods
                vDesc.addElement(ReportFactory.createReport(6402, 1, entity));
                return vDesc;
            }

            // Eject up to 50 spacesuited crewmen out the nearest airlock!
            // This only works in space or on the ground
            int nEscaped = Math.min(entity.getNPassenger() + entity.getCrew().getCurrentSize(), 50);
            EjectedCrew crew = new EjectedCrew(entity, nEscaped);
            entity.addEscapeCraft(crew.getExternalIdAsString());

            //Report the escape
            vDesc.addElement(ReportFactory.createReport(6403, 1, entity, nEscaped));

            //If there are passengers aboard, get them out first
            if (entity.getNPassenger() > 0) {
                int change = Math.min(entity.getNPassenger(), nEscaped);
                entity.setNPassenger(Math.max(entity.getNPassenger() - nEscaped, 0));
                crew.addPassengers(entity.getExternalIdAsString(), change);
                nEscaped -= change;
            }
            //Now get the crew out with such airlock space as is left
            if (nEscaped > 0) {
                entity.setNCrew(entity.getNCrew() - nEscaped);
                entity.getCrew().setCurrentSize(Math.max(0, entity.getCrew().getCurrentSize() - nEscaped));
                crew.addNOtherCrew(entity.getExternalIdAsString(), nEscaped);
                //*Damage* the host ship's crew to account for the people that left
                vDesc.addAll(server.damageCrew(entity,entity.getCrew().calculateHits()));
                if (entity.getCrew().getHits() >= Crew.DEATH) {
                    //Then we've finished ejecting
                    entity.getCrew().setEjected(true);
                }
            }

            // Need to set game manually; since game.addEntity not called yet
            // Don't want to do this yet, as Entity may not be added
            crew.setGame(game);
            crew.setDeployed(true);
            crew.setId(game.getNextEntityId());
            if (inSpace) {
                //In space, ejected pilots retain the heading and velocity of the unit they eject from
                crew.setVectors(entity.getVectors());
                crew.setFacing(entity.getFacing());
                crew.setCurrentVelocity(entity.getVelocity());
                //If the crew ejects, they should no longer be accelerating
                crew.setNextVelocity(entity.getVelocity());
                // We're going to be nice and assume a ship has enough spacesuits for everyone aboard...
                crew.setSpaceSuit(true);
                crew.setPosition(entity.getPosition());
            } else {
                // On the ground, crew must abandon into a legal hex
                Coords legalPosition = null;
                //Small Craft can just abandon into the hex they occupy
                if (!entity.isLargeCraft() && !crew.isLocationProhibited(entity.getPosition())) {
                    legalPosition = entity.getPosition();
                } else {
                    //Use the passed in coords. We already calculated whether they're legal or not
                    legalPosition = pos;
                }
                // Cannot abandon if there is no legal hex.  This shoudln't have
                // been allowed
                if (legalPosition == null) {
                    MegaMek.getLogger().error("Spacecraft crews cannot abandon if there is no legal hex!");
                    return vDesc;
                }
                crew.setPosition(legalPosition);
            }
            // Add Entity to game
            game.addEntity(crew);
            // No movement this turn
            crew.setDone(true);
            // Tell clients about new entity
            server.send(PacketFactory.createAddEntityPacket(game, crew.getId()));
            // Sent entity info to clients
            entityUpdate(crew.getId());
            // Check if the crew lands in a minefield
            vDesc.addAll(server.doEntityDisplacementMinefieldCheck(crew, entity.getPosition(), entity.getElevation()));
            if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_EJECTED_PILOTS_FLEE)) {
                game.removeEntity(crew.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
                server.send(PacketFactory.createRemoveEntityPacket(crew.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT));
            }
        }
        // If we get here, end movement and return the report
        entity.setDone(true);
        entityUpdate(entity.getId());
        return vDesc;
    }

    /**
     * Process any flee movement actions, including flying off the map
     *
     * @param movePath   The move path which resulted in an entity leaving the map.
     * @param flewOff    whether this fleeing is a result of accidentally flying off the
     *                   map
     * @param returnable the number of rounds until the unit can return to the map (-1
     *                   if it can't return)
     * @return Vector of turn reports.
     */
    private Vector<Report> processLeaveMap(MovePath movePath, boolean flewOff, int returnable) {
        Entity entity = movePath.getEntity();
        Vector<Report> vReport = new Vector<>();
        // Unit has fled the battlefield.
        int reportID = 2005;
        if (flewOff) {
            reportID =9370;
        }
        reportManager.addReport(ReportFactory.createPublicReport(reportID, entity));
        OffBoardDirection fleeDirection;
        if (movePath.getFinalCoords().getY() <= 0) {
            fleeDirection = OffBoardDirection.NORTH;
        } else if (movePath.getFinalCoords().getY() >= (game.getBoard().getHeight() - 1)) {
            fleeDirection = OffBoardDirection.SOUTH;
        } else if (movePath.getFinalCoords().getX() <= 0) {
            fleeDirection = OffBoardDirection.WEST;
        } else {
            fleeDirection = OffBoardDirection.EAST;
        }

        if (returnable > -1) {
            entity.setDeployed(false);
            entity.setDeployRound(1 + game.getRoundCount() + returnable);
            entity.setPosition(null);
            entity.setDone(true);
            if (entity.isAero()) {
                // If we're flying off because we're OOC, when we come back we
                // should no longer be OOC
                // If we don't, this causes a major problem as aeros tend to
                // return, re-deploy then
                // fly off again instantly.
                ((IAero) entity).setOutControl(false);
            }
            switch (fleeDirection) {
                case WEST:
                    entity.setStartingPos(Board.START_W);
                    break;
                case NORTH:
                    entity.setStartingPos(Board.START_N);
                    break;
                case EAST:
                    entity.setStartingPos(Board.START_E);
                    break;
                case SOUTH:
                    entity.setStartingPos(Board.START_S);
                    break;
                default:
                    entity.setStartingPos(Board.START_EDGE);
            }
            entityUpdate(entity.getId());
            return vReport;
        }

        // Is the unit carrying passengers or trailers?
        final List<Entity> passengers = new ArrayList<>(entity.getLoadedUnits());
        for (int id : entity.getAllTowedUnits()) {
            Entity towed = game.getEntity(id);
            passengers.add(towed);
        }

        for (Entity passenger : passengers) {
            // Unit has fled the battlefield.
            reportManager.addReport(ReportFactory.createPublicReport(2010, 1, passenger));
            passenger.setRetreatedDirection(fleeDirection);
            game.removeEntity(passenger.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
            server.send(PacketFactory.createRemoveEntityPacket(passenger.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT));
        }

        // Handle any picked up MechWarriors
        for (Integer mechWarriorId : entity.getPickedUpMechWarriors()) {
            Entity mw = game.getEntity(mechWarriorId);

            if(mw == null) {
                continue;
            }

            // Is the MechWarrior an enemy?
            int condition = IEntityRemovalConditions.REMOVE_IN_RETREAT;
            reportID = 2010;
            if (mw.isCaptured()) {
                reportID = 2015;
                condition = IEntityRemovalConditions.REMOVE_CAPTURED;
            } else {
                mw.setRetreatedDirection(fleeDirection);
            }
            game.removeEntity(mw.getId(), condition);
            server.send(PacketFactory.createRemoveEntityPacket(mw.getId(), condition));
            reportManager.addReport(ReportFactory.createReport(reportID, 1, mw));
        }
        // Is the unit being swarmed?
        final int swarmerId = entity.getSwarmAttackerId();
        if (Entity.NONE != swarmerId) {
            final Entity swarmer = game.getEntity(swarmerId);

            // Has the swarmer taken a turn?
            if (!swarmer.isDone()) {
                // Dead entities don't take turns.
                game.removeTurnFor(swarmer);
                server.send(PacketFactory.createTurnVectorPacket(game));

            } // End swarmer-still-to-move

            // Unit has fled the battlefield.
            swarmer.setSwarmTargetId(Entity.NONE);
            entity.setSwarmAttackerId(Entity.NONE);
            reportManager.addReport(ReportFactory.createPublicReport(2015, 1, swarmer));
            game.removeEntity(swarmerId, IEntityRemovalConditions.REMOVE_CAPTURED);
            server.send(PacketFactory.createRemoveEntityPacket(swarmerId, IEntityRemovalConditions.REMOVE_CAPTURED));
        }
        entity.setRetreatedDirection(fleeDirection);
        game.removeEntity(entity.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
        server.send(PacketFactory.createRemoveEntityPacket(entity.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT));
        return vReport;
    }

    private void handleSwarmingResult(Entity entity, Entity swarmer, PilotingRollData rollTarget, Coords curPos) {
        // okay, print the info
        reportManager.addReport(ReportFactory.createReport(2125, entity));

        final int diceRoll = Compute.d6(2);
        Report r = ReportFactory.createReport(2130, entity, rollTarget.getValueAsString(), rollTarget.getDesc());
        r.add(diceRoll);
        if (diceRoll < rollTarget.getValue()) {
            r.choose(false);
            reportManager.addReport(r);
        } else {
            // Dislodged swarmers don't get turns.
            game.removeTurnFor(swarmer);
            server.send(PacketFactory.createTurnVectorPacket(game));

            // Update the report and the swarmer's status.
            r.choose(true);
            reportManager.addReport(r);
            entity.setSwarmAttackerId(Entity.NONE);
            swarmer.setSwarmTargetId(Entity.NONE);

            IHex curHex = game.getBoard().getHex(curPos);

            // Did the infantry fall into water?
            if (curHex.terrainLevel(Terrains.WATER) > 0) {
                // Swarming infantry die.
                swarmer.setPosition(curPos);
                reportManager.addReport(ReportFactory.createReport(2135, 1, swarmer));
                reportManager.addReport(destroyEntity(swarmer, "a watery grave", false));
            } else {
                // Swarming infantry take a 3d6 point hit.
                // ASSUMPTION : damage should not be doubled.
                r = new Report(2140);
                r.subject = entity.getId();
                r.indent();
                r.addDesc(swarmer);
                r.add("3d6");
                reportManager.addReport(r);
                reportManager.addReport(server.damageEntity(swarmer,
                        swarmer.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT), Compute.d6(3)));
                reportManager.addNewLines();
                swarmer.setPosition(curPos);
            }
            entityUpdate(entity.getSwarmAttackerId());
        } // End successful-PSR
    }

    /**
     * Updates the position of any towed trailers.
     *
     * @param tractor    The Entity that is moving
     * @param trainPath  The path all trailers are following?
     */
    private void processTrailerMovement(Entity tractor, List<Coords> trainPath) {
        for (int eId : tractor.getAllTowedUnits()) {
            Entity trailer = game.getEntity(eId);
            // if the Tractor didn't move anywhere, stay where we are
            if (tractor.delta_distance == 0) {
                trailer.delta_distance = tractor.delta_distance;
                trailer.moved = tractor.moved;
                trailer.setSecondaryFacing(trailer.getFacing());
                trailer.setDone(true);
                entityUpdate(eId);
                continue;
            }
            int stepNumber; // The Coords in trainPath that this trailer should move to
            Coords trailerPos;
            int trailerNumber = tractor.getAllTowedUnits().indexOf(eId);
            double trailerPositionOffset = (trailerNumber + 1); //Offset so we get the right position index
            // Unless the tractor is superheavy, put the first trailer in its hex.
            // Technically this would be true for a superheavy trailer too, but only a superheavy tractor can tow one.
            if (trailerNumber == 0 && !tractor.isSuperHeavy()) {
                trailer.setPosition(tractor.getPosition());
                trailer.setFacing(tractor.getFacing());
            } else {
                // If the trailer is superheavy, place it in a hex by itself
                if (trailer.isSuperHeavy()) {
                    trailerPositionOffset ++;
                } else if (tractor.isSuperHeavy()) {
                    // If the tractor is superheavy, we can put two trailers in each hex
                    // starting trailer 0 in the hex behind the tractor
                    trailerPositionOffset = (Math.ceil((trailerPositionOffset / 2.0)) + 1);
                } else {
                    // Otherwise, we can put two trailers in each hex
                    // starting trailer 1 in the hex behind the tractor
                    trailerPositionOffset ++;
                    trailerPositionOffset = Math.ceil((trailerPositionOffset / 2.0));

                }
                stepNumber = (trainPath.size() - (int) trailerPositionOffset);
                trailerPos = trainPath.get(stepNumber);
                trailer.setPosition(trailerPos);
                if ((tractor.getPassedThroughFacing().size() - trailerPositionOffset) >= 0) {
                    trailer.setFacing(tractor.getPassedThroughFacing().get(tractor.getPassedThroughFacing().size() - (int) trailerPositionOffset));
                }
            }
            // trailers are immobile by default. Match the tractor's movement here
            trailer.delta_distance = tractor.delta_distance;
            trailer.moved = tractor.moved;
            trailer.setSecondaryFacing(trailer.getFacing());
            trailer.setDone(true);
            entityUpdate(eId);
        }
    }

    /**
     * Checks whether the entity used MASC or a supercharger during movement, and if so checks for
     * and resolves any failures.
     *
     * @param entity  The unit using MASC/supercharger
     * @param md      The current <code>MovePath</code>
     * @return        Whether the unit failed the check
     */
    private boolean checkMASCFailure(Entity entity, MovePath md) {
        HashMap<Integer, List<CriticalSlot>> crits = new HashMap<>();
        Vector<Report> vReport = new Vector<>();
        if (entity.checkForMASCFailure(md, vReport, crits)) {
            boolean mascFailure = true;
            // Check to see if the pilot can reroll due to Edge
            if (entity.getCrew().hasEdgeRemaining()
                    && entity.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_MASC_FAILS)) {
                entity.getCrew().decreaseEdge();
                // Need to reset the MASCUsed flag
                entity.setMASCUsed(false);
                // Report to notify user that masc check was rerolled
                Report masc_report = new Report(6501);
                masc_report.subject = entity.getId();
                masc_report.indent(2);
                masc_report.addDesc(entity);
                vReport.add(masc_report);
                // Report to notify user how much edge pilot has left
                masc_report = new Report(6510);
                masc_report.subject = entity.getId();
                masc_report.indent(2);
                masc_report.addDesc(entity);
                masc_report.add(entity.getCrew().getOptions().intOption(OptionsConstants.EDGE));
                vReport.addElement(masc_report);
                // Recheck MASC failure
                if (!entity.checkForMASCFailure(md, vReport, crits)) {
                    // The reroll passed, don't process the failure
                    mascFailure = false;
                    reportManager.addReport(vReport);
                }
            }
            // Check for failure and process it
            if (mascFailure) {
                reportManager.addReport(vReport);
                // If this is supercharger failure we need to damage the supercharger as well as
                // the additional criticals. For mechs this requires the additional step of finding
                // the slot and marking it as hit so it can't absorb future damage.
                Mounted supercharger = entity.getSuperCharger();
                if ((null != supercharger) && supercharger.curMode().equals("Armed")) {
                    if (entity.hasETypeFlag(Entity.ETYPE_MECH)) {
                        final int loc = supercharger.getLocation();
                        for (int slot = 0; slot < entity.getNumberOfCriticals(loc); slot++) {
                            final CriticalSlot crit = entity.getCritical(loc, slot);
                            if ((null != crit) && (crit.getType() == CriticalSlot.TYPE_EQUIPMENT)
                                    && (crit.getMount().getType().equals(supercharger.getType()))) {
                                reportManager.addReport(server.applyCriticalHit(entity, loc, crit,
                                        true, 0, false));
                                break;
                            }
                        }
                    } else {
                        supercharger.setHit(true);
                    }
                    supercharger.setMode("Off");
                }
                for (Integer loc : crits.keySet()) {
                    List<CriticalSlot> lcs = crits.get(loc);
                    for (CriticalSlot cs : lcs) {
                        // HACK: if loc is -1, we need to deal motive damage to
                        // the tank, the severity of which is stored in the critslot index
                        if (loc == -1) {
                            reportManager.addReport(server.vehicleMotiveDamage((Tank) entity, 0, true, cs.getIndex()));
                        } else {
                            reportManager.addReport(server.applyCriticalHit(entity, loc, cs, true, 0, false));
                        }
                    }
                }
                // do any PSR immediately
                reportManager.addReport(server.resolvePilotingRolls(entity));
                game.resetPSRs(entity);
                // let the player replot their move as MP might be changed
                md.clear();
                return true;
            }
        } else {
            reportManager.addReport(vReport);
        }
        return false;
    }

    /**
     * LAMs or QuadVees converting from leg mode may force any carried infantry (including swarming)
     * to fall into the current hex. A LAM may suffer damage.
     *
     * @param carrier       The <code>Entity</code> making the conversion.
     * @param rider         The <code>Entity</code> possibly being forced off.
     * @param curPos        The coordinates of the hex where the conversion starts.
     * @param curFacing     The carrier's facing when conversion starts.
     * @param automatic     Whether the infantry falls automatically. If false, an anti-mech roll is made
     *                      to see whether it stays mounted.
     * @param infDamage     If true, the infantry takes falling damage, +1D6 for conventional.
     * @param carrierDamage If true, the carrier takes damage from converting while carrying infantry.
     */
    private Vector<Report> checkDropBAFromConverting(Entity carrier, Entity rider, Coords curPos, int curFacing,
                                                     boolean automatic, boolean infDamage, boolean carrierDamage) {
        Vector<Report> reports = new Vector<>();
        Report r;
        PilotingRollData prd = rider.getBasePilotingRoll(EntityMovementType.MOVE_NONE);
        boolean falls = automatic;
        if (automatic) {
            r = new Report(2465);
            r.subject = rider.getId();
            r.addDesc(rider);
            r.addDesc(carrier);
        } else {
            r = new Report(2460);
            r.subject = rider.getId();
            r.addDesc(rider);
            r.add(prd.getValueAsString());
            r.addDesc(carrier);
            final int diceRoll = carrier.getCrew().rollPilotingSkill();
            r.add(diceRoll);
            if (diceRoll < prd.getValue()) {
                r.choose(false);
                falls = true;
            } else {
                r.choose(true);
            }
        }
        reports.add(r);
        if (falls) {
            if (carrier.getSwarmAttackerId() == rider.getId()) {
                rider.setDone(true);
                carrier.setSwarmAttackerId(Entity.NONE);
                rider.setSwarmTargetId(Entity.NONE);
            } else if (!server.unloadUnit(carrier, rider, curPos, curFacing, 0)) {
                MegaMek.getLogger().error("Server was told to unload " + rider.getDisplayName() + " from "
                        + carrier.getDisplayName() + " into " + curPos.getBoardNum());
                return reports;
            }
            if (infDamage) {
                reports.addAll(server.doEntityFall(rider, curPos, 2, prd));
                if (rider.getEntityType() == Entity.ETYPE_INFANTRY) {
                    int extra = Compute.d6();
                    reports.addAll(server.damageEntity(rider, new HitData(Infantry.LOC_INFANTRY), extra));
                }
            }
            if (carrierDamage) {
                //Report the possibility of a critical hit.
                reports.addElement(ReportFactory.createReport(2470, carrier));
                int mod = 0;
                if (rider.getEntityType() == Entity.ETYPE_INFANTRY) {
                    mod = -2;
                }
                HitData hit = carrier.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                reports.addAll(server.criticalEntity(carrier, hit.getLocation(), false, mod, 0));
            }
        }
        return reports;
    }

    /**
     * If an aero unit takes off in the same turn that other units loaded, then
     * it risks damage to itself and those units
     *
     * @param a - The <code>Aero</code> taking off
     */
    private void checkForTakeoffDamage(IAero a) {
        boolean unsecured = false;
        for (Entity loaded : ((Entity)a).getLoadedUnits()) {
            if (loaded.wasLoadedThisTurn() && !(loaded instanceof Infantry)) {
                unsecured = true;
                // uh-oh, you forgot your seat belt
                reportManager.addReport(ReportFactory.createReport(6800, loaded));
                int damage = 25;
                ToHitData toHit = new ToHitData();
                while (damage > 0) {
                    HitData hit = loaded.rollHitLocation(toHit.getHitTable(), ToHitData.SIDE_FRONT);
                    reportManager.addReport(server.damageEntity(loaded, hit, 5, false,
                            Server.DamageType.NONE, false, true, false));
                    damage -= 5;
                }
            }
        }
        if (unsecured) {
            // roll hit location to get a new critical
            HitData hit = ((Entity)a).rollHitLocation(ToHitData.HIT_ABOVE, ToHitData.SIDE_FRONT);
            reportManager.addReport(server.applyCriticalHit((Entity)a, hit.getLocation(), new CriticalSlot(
                    0, ((Aero)a).getPotCrit()), true, 1, false));
        }
    }

    /**
     * drowns any units swarming the entity
     *
     * @param entity The <code>Entity</code> that is being swarmed
     * @param pos    The <code>Coords</code> the entity is at
     */
    private void drownSwarmer(Entity entity, Coords pos) {
        // Any swarming infantry will be destroyed.
        final int swarmerId = entity.getSwarmAttackerId();
        if (Entity.NONE != swarmerId) {
            final Entity swarmer = game.getEntity(swarmerId);
            // Only *platoons* drown while swarming.
            if (!(swarmer instanceof BattleArmor)) {
                swarmer.setSwarmTargetId(Entity.NONE);
                entity.setSwarmAttackerId(Entity.NONE);
                swarmer.setPosition(pos);
                reportManager.addReport(ReportFactory.createReport(2165, 1, entity, entity.getShortName()));
                reportManager.addReport(destroyEntity(swarmer, "a watery grave", false));
                entityUpdate(swarmerId);
            }
        }
    }

    /**
     * Checks to see if we may have just washed off infernos. Call after a step
     * which may have done this.
     *
     * @param entity The <code>Entity</code> that is being checked
     * @param coords The <code>Coords</code> the entity is at
     */
    private void checkForWashedInfernos(Entity entity, Coords coords) {
        IHex hex = game.getBoard().getHex(coords);
        int waterLevel = hex.terrainLevel(Terrains.WATER);
        // Mech on fire with infernos can wash them off.
        if (!(entity instanceof Mech) || !entity.infernos.isStillBurning()) {
            return;
        }
        // Check if entering depth 2 water or prone in depth 1.
        if ((waterLevel > 0) && (entity.relHeight() < 0)) {
            washInferno(entity, coords);
        }
    }

    /**
     * Washes off an inferno from a mech and adds it to the (water) hex.
     *
     * @param entity The <code>Entity</code> that is taking a bath
     * @param coords The <code>Coords</code> the entity is at
     */
    void washInferno(Entity entity, Coords coords) {
        game.getBoard().addInfernoTo(coords, InfernoTracker.STANDARD_ROUND, 1);
        entity.infernos.clear();

        // Start a fire in the hex?
        IHex hex = game.getBoard().getHex(coords);
        int reportID = 2170;
        if (!hex.containsTerrain(Terrains.FIRE)) {
            reportID = 2175;
            server.ignite(coords, Terrains.FIRE_LVL_INFERNO, null);
        }
        reportManager.addReport(ReportFactory.createReport(reportID, entity));
    }

    /**
     * Do a roll to avoid pilot damage from g-forces
     *
     * @param entity       The <code>Entity</code> that should make the PSR
     * @param targetNumber The <code>int</code> to be used for this PSR.
     */
    private void resistGForce(Entity entity, int targetNumber) {
        // okay, print the info
        reportManager.addReport(ReportFactory.createReport(9330, entity));

        // roll
        final int diceRoll = Compute.d6(2);
        Report r = ReportFactory.createReport(9335, entity, Integer.toString(targetNumber));
        r.add(diceRoll);
        if (diceRoll < targetNumber) {
            r.choose(false);
            reportManager.addReport(r);
            reportManager.addReport(server.damageCrew(entity, 1));
        } else {
            r.choose(true);
            reportManager.addReport(r);
        }
    }

    /**
     * Do a piloting skill check in space to avoid structural damage
     *
     * @param entity The <code>Entity</code> that should make the PSR
     * @param roll   The <code>PilotingRollData</code> to be used for this PSR.
     * @return true if check succeeds, false otherwise.
     */
    private boolean doSkillCheckInSpace(Entity entity, PilotingRollData roll) {
        if (roll.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            return true;
        }

        // okay, print the info
        reportManager.addReport(ReportFactory.createReport(9320, entity, roll.getLastPlainDesc()));

        // roll
        final int diceRoll = Compute.d6(2);
        Report r = ReportFactory.createReport(9325, entity, roll.getValueAsString(), roll.getDesc());
        r.add(diceRoll);
        boolean suc = diceRoll >= roll.getValue();
        r.choose(suc);
        reportManager.addReport(r);
        return suc;
    }

    /**
     * Do a piloting skill check to take off vertically
     *
     * @param entity The <code>Entity</code> that should make the PSR
     * @param roll   The <code>PilotingRollData</code> to be used for this PSR.
     * @return true if check succeeds, false otherwise.
     */
    private boolean doVerticalTakeOffCheck(Entity entity, PilotingRollData roll) {
        if (!entity.isAero()) {
            return false;
        }

        IAero a = (IAero) entity;

        if (roll.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            return true;
        }

        // okay, print the info
        reportManager.addReport(ReportFactory.createReport(9320, entity, roll.getLastPlainDesc()));

        // roll
        final int diceRoll = Compute.d6(2);
        Report r = ReportFactory.createReport(9321, entity, roll.getValueAsString(), roll.getDesc());
        r.add(diceRoll);
        r.newlines = 0;
        reportManager.addReport(r);
        boolean suc = false;
        if (diceRoll < roll.getValue()) {
            int mof = roll.getValue() - diceRoll;
            if (mof < 3) {
                reportManager.addReport(ReportFactory.createReport(6322, entity));
                suc = true;
            } else if (mof < 5) {
                PilotingRollData newRoll = entity.getBasePilotingRoll();
                if (Compute.d6(2) >= newRoll.getValue()) {
                    reportManager.addReport(ReportFactory.createReport(9322, entity));
                    suc = true;
                } else {
                    reportManager.addReport(ReportFactory.createReport(9323, entity));
                    int damage = 20;
                    while (damage > 0) {
                        reportManager.addReport(server.damageEntity(entity, entity.rollHitLocation(ToHitData.HIT_NORMAL,
                                ToHitData.SIDE_REAR), Math.min(5, damage)));
                        damage -= 5;
                    }
                }
            } else {
                reportManager.addReport(ReportFactory.createReport(9323, entity));
                int damage = 100;
                if (mof < 6) {
                    damage = 50;
                }
                while (damage > 0) {
                    reportManager.addReport(server.damageEntity(entity, entity.rollHitLocation(ToHitData.HIT_NORMAL,
                            ToHitData.SIDE_REAR), Math.min(5, damage)));
                    damage -= 5;
                }
            }
            a.setGearHit(true);
            reportManager.addReport(ReportFactory.createReport(9125, entity));
        } else {
            reportManager.addReport(ReportFactory.createReport(9322, entity));
            suc = true;
        }
        return suc;
    }

    /**
     * Do a piloting skill check in space to do a successful maneuver Failure
     * means moving forward half velocity
     *
     * @param entity The <code>Entity</code> that should make the PSR
     * @param roll   The <code>PilotingRollData</code> to be used for this PSR.
     * @return true if check succeeds, false otherwise.
     */
    private boolean doSkillCheckManeuver(Entity entity, PilotingRollData roll) {
        if (roll.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            return true;
        }

        // okay, print the info
        reportManager.addReport(ReportFactory.createReport(9600, entity, roll.getLastPlainDesc()));

        // roll
        final int diceRoll = Compute.d6(2);
        Report r = ReportFactory.createReport(9601, entity, roll.getValueAsString(), roll.getDesc());
        r.add(diceRoll);
        boolean suc = diceRoll >= roll.getValue();
        r.choose(suc);
        reportManager.addReport(r);

        return suc;
    }

    /**
     * Do a Piloting Skill check to dislodge swarming infantry.
     *
     * @param entity The <code>Entity</code> that is doing the dislodging.
     * @param roll   The <code>PilotingRollData</code> for this PSR.
     * @param curPos The <code>Coords</code> the entity is at.
     * @return <code>true</code> if the dislodging is successful.
     */
    private boolean doDislodgeSwarmerSkillCheck(Entity entity, PilotingRollData roll, Coords curPos) {
        // okay, print the info
        reportManager.addReport(ReportFactory.createReport(2180, entity, roll.getLastPlainDesc()));

        // roll
        final int diceRoll = Compute.d6(2);
        Report r = ReportFactory.createReport(2190, entity, roll.getValueAsString(), roll.getDesc());
        r.add(diceRoll);
        if (diceRoll < roll.getValue()) {
            r.choose(false);
            reportManager.addReport(r);
            // failed a PSR, possibly check for engine stalling
            entity.doCheckEngineStallRoll(reportManager.getvPhaseReport());
            return false;
        }
        // Dislodged swarmers don't get turns.
        int swarmerId = entity.getSwarmAttackerId();
        final Entity swarmer = game.getEntity(swarmerId);
        if (!swarmer.isDone()) {
            game.removeTurnFor(swarmer);
            swarmer.setDone(true);
            server.send(PacketFactory.createTurnVectorPacket(game));
        }

        // Update the report and cause a fall.
        r.choose(true);
        reportManager.addReport(r);
        entity.setPosition(curPos);
        reportManager.addReport(server.doEntityFallsInto(entity, curPos, roll, false));
        return true;
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
        return ejectEntity(entity, autoEject, false);
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
        Vector<Report> vDesc = new Vector<>();
        Report r;

        // An entity can only eject it's crew once.
        if (entity.getCrew().isEjected()
                // If the crew are already dead, don't bother
                || entity.isCarcass()) {
            return vDesc;
        }

        // Mek and fighter pilots may get hurt during ejection,
        // and run around the board afterwards.
        if (entity instanceof Mech || entity.isFighter()) {
            int facing = entity.getFacing();
            Coords targetCoords = (null != entity.getPosition()) ? entity.getPosition().translated((facing + 3) % 6) : null;
            if (entity.isSpaceborne() && entity.getPosition() != null) {
                //Pilots in space should eject into the fighter's hex, not behind it
                targetCoords = entity.getPosition();
            }

            if (autoEject) {
                r = new Report(6395);
                r.subject = entity.getId();
                r.addDesc(entity);
                r.indent(2);
                vDesc.addElement(r);
            }

            // okay, print the info
            PilotingRollData rollTarget = Server.getEjectModifiers(game, entity, entity.getCrew().getCurrentPilotIndex(), autoEject);
            r = new Report(2180);
            r.subject = entity.getId();
            r.addDesc(entity);
            r.add(rollTarget.getLastPlainDesc(), true);
            r.indent();
            vDesc.addElement(r);
            for (int crewPos = 0; crewPos < entity.getCrew().getSlotCount(); crewPos++) {
                if (entity.getCrew().isMissing(crewPos)) {
                    continue;
                }
                rollTarget = Server.getEjectModifiers(game, entity, crewPos, autoEject);
                // roll
                final int diceRoll = entity.getCrew().rollPilotingSkill();
                if (entity.getCrew().getSlotCount() > 1) {
                    r = new Report(2193);
                    r.add(entity.getCrew().getNameAndRole(crewPos));
                } else {
                    r = new Report(2190);
                }
                r.subject = entity.getId();
                r.add(rollTarget.getValueAsString());
                r.add(rollTarget.getDesc());
                r.add(diceRoll);
                r.indent();
                if (diceRoll < rollTarget.getValue()) {
                    r.choose(false);
                    vDesc.addElement(r);
                    Report.addNewline(vDesc);
                    if ((rollTarget.getValue() - diceRoll) > 1) {
                        // Pilots take damage based on ejection roll MoF
                        int damage = (rollTarget.getValue() - diceRoll);
                        if (entity instanceof Mech) {
                            // MechWarriors only take 1 damage per 2 points of MoF
                            damage /= 2;
                        }
                        if (entity.hasQuirk(OptionsConstants.QUIRK_NEG_DIFFICULT_EJECT)) {
                            damage++;
                        }
                        vDesc.addAll(server.damageCrew(entity, damage, crewPos));
                    }

                    // If this is a skin of the teeth ejection...
                    if (skin_of_the_teeth && (entity.getCrew().getHits(crewPos) < 6)) {
                        Report.addNewline(vDesc);
                        vDesc.addAll(server.damageCrew(entity, 6 - entity.getCrew().getHits(crewPos)));
                    }
                } else {
                    r.choose(true);
                    vDesc.addElement(r);
                }
            }
            // create the MechWarrior in any case, for campaign tracking
            MechWarrior pilot = new MechWarrior(entity);
            pilot.setDeployed(true);
            pilot.setId(game.getNextEntityId());
            pilot.setLanded(false);
            if (entity.isSpaceborne()) {
                //In space, ejected pilots retain the heading and velocity of the unit they eject from
                pilot.setVectors(entity.getVectors());
                pilot.setFacing(entity.getFacing());
                pilot.setCurrentVelocity(entity.getVelocity());
                //If the pilot ejects, he should no longer be accelerating
                pilot.setNextVelocity(entity.getVelocity());
            } else if (entity.isAirborne()) {
                pilot.setAltitude(entity.getAltitude());
            }
            //Pilot flight suits are vacuum-rated. MechWarriors wear shorts...
            pilot.setSpaceSuit(entity.isAero());
            game.addEntity(pilot);
            server.send(PacketFactory.createAddEntityPacket(game, pilot.getId()));
            // make him not get a move this turn
            pilot.setDone(true);
            int living = 0;
            for (int i = 0; i < entity.getCrew().getSlotCount(); i++) {
                if (!entity.getCrew().isDead(i) && entity.getCrew().getHits(i) < Crew.DEATH) {
                    living++;
                }
            }
            pilot.setInternal(living, MechWarrior.LOC_INFANTRY);
            if (entity.getCrew().isDead() || entity.getCrew().getHits() >= Crew.DEATH) {
                pilot.setDoomed(true);
            }

            if (entity.getCrew().isDoomed()) {
                vDesc.addAll(destroyEntity(pilot, "deadly ejection", false,
                        false));
            } else {
                // Add the pilot as an infantry unit on the battlefield.
                if (game.getBoard().contains(targetCoords)) {
                    pilot.setPosition(targetCoords);
                    // report safe ejection
                    r = new Report(6400);
                    r.subject = entity.getId();
                    r.indent(3);
                    vDesc.addElement(r);
                    // Update the entity
                    entityUpdate(pilot.getId());
                    // check if the pilot lands in a minefield
                    if (!entity.isAirborne()) {
                        vDesc.addAll(server.doEntityDisplacementMinefieldCheck(pilot, targetCoords, entity.getElevation()));
                    }
                } else {
                    // ejects safely
                    r = new Report(6410);
                    r.subject = entity.getId();
                    r.indent(3);
                    vDesc.addElement(r);
                    game.removeEntity(pilot.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
                    server.send(PacketFactory.createRemoveEntityPacket(pilot.getId(),
                            IEntityRemovalConditions.REMOVE_IN_RETREAT));
                }
                if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_EJECTED_PILOTS_FLEE)
                        // Don't create a pilot entity on low-atmospheric maps
                        || game.getBoard().inAtmosphere()) {
                    game.removeEntity(pilot.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
                    server.send(PacketFactory.createRemoveEntityPacket(pilot.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT));
                }

                // If this is a skin of the teeth ejection...
                if (skin_of_the_teeth && (pilot.getCrew().getHits() < 5)) {
                    Report.addNewline(vDesc);
                    vDesc.addAll(server.damageCrew(pilot, 5 - pilot.getCrew().getHits()));
                }
            } // Crew safely ejects.

            // ejection damages the cockpit
            // kind of irrelevant in stand-alone games, but important for MekHQ
            if (entity instanceof Mech) {
                Mech mech = (Mech) entity;
                // in case of mechs with 'full head ejection', the head is treated as blown off
                if (mech.hasFullHeadEject()) {
                    entity.destroyLocation(Mech.LOC_HEAD, true);
                } else {
                    for (CriticalSlot slot : (mech.getCockpit())) {
                        slot.setDestroyed(true);
                    }
                }
            }
        } // End entity-is-Mek or fighter
        else if (game.getBoard().contains(entity.getPosition()) && (entity instanceof Tank)) {
            EjectedCrew crew = new EjectedCrew(entity);
            // Need to set game manually; since game.addEntity not called yet
            // Don't want to do this yet, as Entity may not be added
            crew.setGame(game);
            crew.setDeployed(true);
            crew.setId(game.getNextEntityId());
            // Make them not get a move this turn
            crew.setDone(true);
            // Place on board
            // Vehicles don't have ejection systems, so crew must abandon into a legal hex
            Coords legalPosition = null;
            if (!crew.isLocationProhibited(entity.getPosition())) {
                legalPosition = entity.getPosition();
            } else {
                for (int dir = 0; (dir < 6) && (legalPosition == null); dir++) {
                    Coords adjCoords = entity.getPosition().translated(dir);
                    if (!crew.isLocationProhibited(adjCoords)) {
                        legalPosition = adjCoords;
                    }
                }
            }
            // Cannot abandon if there is no legal hex. This shouldn't have been allowed
            if (legalPosition == null) {
                MegaMek.getLogger().error("Vehicle crews cannot abandon if there is no legal hex!");
                return vDesc;
            }
            crew.setPosition(legalPosition);
            // Add Entity to game
            game.addEntity(crew);
            // Tell clients about new entity
            server.send(PacketFactory.createAddEntityPacket(game, crew.getId()));
            // Sent entity info to clients
            entityUpdate(crew.getId());
            // Check if the crew lands in a minefield
            vDesc.addAll(server.doEntityDisplacementMinefieldCheck(crew, entity.getPosition(), entity.getElevation()));
            if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_EJECTED_PILOTS_FLEE)) {
                game.removeEntity(crew.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
                server.send(PacketFactory.createRemoveEntityPacket(crew.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT));
            }
        } //End ground vehicles

        // Mark the entity's crew as "ejected".
        entity.getCrew().setEjected(true);
        if (entity instanceof VTOL) {
            vDesc.addAll(server.crashVTOLorWiGE((VTOL) entity));
        }
        vDesc.addAll(destroyEntity(entity, "ejection", true, true));

        // only remove the unit that ejected manually
        if (!autoEject) {
            game.removeEntity(entity.getId(), IEntityRemovalConditions.REMOVE_EJECTED);
            server.send(PacketFactory.createRemoveEntityPacket(entity.getId(), IEntityRemovalConditions.REMOVE_EJECTED));
        }
        return vDesc;
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
                    gameManager.deliverMinefield(game, coords, entity.getOwnerId(), 10, entity.getId(), Minefield.TYPE_CONVENTIONAL);
                    reportId = 3500;
                    break;
                case Mounted.MINE_VIBRABOMB:
                    gameManager.deliverMinefield(game, coords, entity.getOwnerId(), 10, entity.getId(), Minefield.TYPE_VIBRABOMB);
                    reportId = 3505;
                    break;
                case Mounted.MINE_ACTIVE:
                    gameManager.deliverMinefield(game, coords, entity.getOwnerId(), 10, entity.getId(), Minefield.TYPE_ACTIVE);
                    reportId = 3510;
                    break;
                case Mounted.MINE_INFERNO:
                    gameManager.deliverMinefield(game, coords, entity.getOwnerId(), 10, entity.getId(), Minefield.TYPE_INFERNO);
                    reportId = 3515;
                    break;
                // TODO : command-detonated mines
                // case 2:
            }
            mine.setShotsLeft(mine.getUsableShotsLeft() - 1);
            if (mine.getUsableShotsLeft() <= 0) {
                mine.setMissing(true);
            }
            reportManager.addReport(ReportFactory.createReport(reportId, entity, coords.getBoardNum()));
            entity.setLayingMines(true);
        }
    }

    private void applyDropShipLandingDamage(Coords centralPos, Entity killer) {
        // first cycle through hexes to figure out final elevation
        IHex centralHex = game.getBoard().getHex(centralPos);
        if (null == centralHex) {
            // shouldn't happen
            return;
        }
        int finalElev = centralHex.getLevel();
        if (!centralHex.containsTerrain(Terrains.PAVEMENT) && !centralHex.containsTerrain(Terrains.ROAD)) {
            finalElev--;
        }
        Vector<Coords> positions = new Vector<>();
        positions.add(centralPos);
        for (int i = 0; i < 6; i++) {
            Coords pos = centralPos.translated(i);
            IHex hex = game.getBoard().getHex(pos);
            if (null == hex) {
                continue;
            }
            if (hex.getLevel() < finalElev) {
                finalElev = hex.getLevel();
            }
            positions.add(pos);
        }
        // ok now cycle through hexes and make all changes
        for (Coords pos : positions) {
            IHex hex = game.getBoard().getHex(pos);
            hex.setLevel(finalElev);
            // get rid of woods and replace with rough
            if (hex.containsTerrain(Terrains.WOODS) || hex.containsTerrain(Terrains.JUNGLE)) {
                hex.removeTerrain(Terrains.WOODS);
                hex.removeTerrain(Terrains.JUNGLE);
                hex.removeTerrain(Terrains.FOLIAGE_ELEV);
                hex.addTerrain(Terrains.getTerrainFactory().createTerrain(Terrains.ROUGH, 1));
            }
            gameManager.sendChangedHex(game, pos);
        }
        applyDropShipProximityDamage(centralPos, killer);
    }

    private void applyDropShipProximityDamage(Coords centralPos, Entity killer) {
        applyDropShipProximityDamage(centralPos, false, 0, killer);
    }

    /**
     * apply damage to units and buildings within a certain radius of a landing
     * or lifting off DropShip
     *
     * @param centralPos - the Coords for the central position of the DropShip
     */
    private void applyDropShipProximityDamage(Coords centralPos, boolean rearArc, int facing, Entity killer) {

        Vector<Integer> alreadyHit = new Vector<>();

        // anything in the central hex or adjacent hexes is destroyed
        Hashtable<Coords, Vector<Entity>> positionMap = game.getPositionMap();
        for (Entity en : game.getEntitiesVector(centralPos)) {
            if (!en.isAirborne()) {
                reportManager.addReport(destroyEntity(en, "DropShip proximity damage", false, false));
                alreadyHit.add(en.getId());
            }
        }
        Building bldg = game.getBoard().getBuildingAt(centralPos);
        if (null != bldg) {
            server.collapseBuilding(bldg, positionMap, centralPos, reportManager.getvPhaseReport());
        }
        for (int i = 0; i < 6; i++) {
            Coords pos = centralPos.translated(i);
            for (Entity en : game.getEntitiesVector(pos)) {
                if (!en.isAirborne()) {
                    reportManager.addReport(destroyEntity(en, "DropShip proximity damage",
                            false, false));
                }
                alreadyHit.add(en.getId());
            }
            bldg = game.getBoard().getBuildingAt(pos);
            if (null != bldg) {
                server.collapseBuilding(bldg, positionMap, pos, reportManager.getvPhaseReport());
            }
        }

        // Report r;
        // ok now I need to look at the damage rings - start at 2 and go to 7
        for (int i = 2; i < 8; i++) {
            int damageDice = (8 - i) * 2;
            List<Coords> ring = centralPos.allAtDistance(i);
            for (Coords pos : ring) {
                if (rearArc && !Compute.isInArc(centralPos, facing, pos, Compute.ARC_AFT)) {
                    continue;
                }

                alreadyHit = server.artilleryDamageHex(pos, centralPos, damageDice, null, killer.getId(),
                        killer, null, false, 0, reportManager.getvPhaseReport(), false,
                        alreadyHit, true);
            }
        }
        destroyDoomedEntities(alreadyHit);
    }

    /**
     * Do a piloting skill check to attempt landing
     *
     * @param entity The <code>Entity</code> that is landing
     * @param roll   The <code>PilotingRollData</code> to be used for this landing.
     */
    private void attemptLanding(Entity entity, PilotingRollData roll) {
        if (roll.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            return;
        }

        // okay, print the info
        reportManager.addReport(ReportFactory.createReport(9605, entity, roll.getLastPlainDesc()));

        // roll
        final int diceRoll = Compute.d6(2);
        Report r = ReportFactory.createReport(9606, entity, roll.getValueAsString(), roll.getDesc());
        r.add(diceRoll);
        // boolean suc;
        if (diceRoll < roll.getValue()) {
            r.choose(false);
            reportManager.addReport(r);
            int mof = roll.getValue() - diceRoll;
            int damage = 10 * (mof);
            // Report damage taken
            reportManager.addReport(ReportFactory.createReport(9609, 1, entity, damage, mof));

            int side = ToHitData.SIDE_FRONT;
            if ((entity instanceof Aero) && ((Aero) entity).isSpheroid()) {
                side = ToHitData.SIDE_REAR;
            }
            while (damage > 0) {
                HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, side);
                reportManager.addReport(server.damageEntity(entity, hit, 10));
                damage -= 10;
            }
            // suc = false;
        } else {
            r.choose(true);
            reportManager.addReport(r);
            // suc = true;
        }
    }

    private boolean launchUnit(Entity unloader, Targetable unloaded, Coords pos, int facing, MoveStep step, int bonus) {
        int velocity = step.getVelocity();
        int altitude = step.getAltitude();
        int[] moveVec = step.getVectors();

        Entity unit;
        if (unloaded instanceof Entity && unloader instanceof Aero) {
            unit = (Entity) unloaded;
        } else {
            return false;
        }

        // must be an ASF, Small Craft, or DropShip
        if (!unit.isAero() || unit instanceof Jumpship) {
            return false;
        }
        IAero a = (IAero) unit;

        // Unload the unit.
        if (!unloader.unload(unit)) {
            return false;
        }

        // The unloaded unit is no longer being carried.
        unit.setTransportId(Entity.NONE);

        // pg. 86 of TW: launched fighters can move in fire in the turn they are
        // unloaded
        unit.setUnloaded(false);

        // Place the unloaded unit onto the screen.
        unit.setPosition(pos);

        // Units unloaded onto the screen are deployed.
        if (pos != null) {
            unit.setDeployed(true);
        }

        // Point the unloaded unit in the given direction.
        unit.setFacing(facing);
        unit.setSecondaryFacing(facing);

        // the velocity of the unloaded unit is the same as the loader
        a.setCurrentVelocity(velocity);
        a.setNextVelocity(velocity);

        // if using advanced movement then set vectors
        unit.setVectors(moveVec);

        unit.setAltitude(altitude);

        // it seems that the done button is still being set and I can't figure
        // out where
        unit.setDone(false);

        // if the bonus was greater than zero then too many fighters were
        // launched and they
        // must all make control rolls
        if (bonus > 0) {
            PilotingRollData psr = unit.getBasePilotingRoll();
            psr.addModifier(bonus, "safe launch rate exceeded");
            int ctrlroll = Compute.d6(2);
            Report r = ReportFactory.createReport(9375, 1, unit, unit.getDisplayName());
            r.add(psr.getValue());
            r.add(ctrlroll);
            if (ctrlroll < psr.getValue()) {
                r.choose(false);
                reportManager.addReport(r);
                // damage the unit
                int damage = 10 * (psr.getValue() - ctrlroll);
                HitData hit = unit.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                Vector<Report> rep = server.damageEntity(unit, hit, damage);
                Report.indentAll(rep, 1);
                rep.lastElement().newlines++;
                reportManager.addReport(rep);
                // did we destroy the unit?
                if (unit.isDoomed()) {
                    // Clean out the entity.
                    unit.setDestroyed(true);
                    game.moveToGraveyard(unit.getId());
                    server.send(PacketFactory.createRemoveEntityPacket(unit.getId()));
                }
            } else {
                // avoided damage
                r.choose(true);
                r.newlines++;
                reportManager.addReport(r);
            }
        } else {
            reportManager.addReport(ReportFactory.createReport(9374, 1, unit, unit.getDisplayName()));
        }

        // launching from an OOC vessel causes damage
        // same thing if faster than 2 velocity in atmosphere
        if ((((Aero) unloader).isOutControlTotal() && !unit.isDoomed())
                || ((((Aero)unloader).getCurrentVelocity() > 2) && !game.getBoard().inSpace())) {
            int damageRoll = Compute.d6(2);
            int damage = damageRoll * 10;
            Report r = ReportFactory.createReport(9385, unit, unit.getDisplayName());
            r.add(damage);
            reportManager.addReport(r);
            HitData hit = unit.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
            reportManager.addReport(server.damageEntity(unit, hit, damage));
            // did we destroy the unit?
            if (unit.isDoomed()) {
                // Clean out the entity.
                unit.setDestroyed(true);
                game.moveToGraveyard(unit.getId());
                server.send(PacketFactory.createRemoveEntityPacket(unit.getId()));
            }
        }

        // Update the unloaded unit.
        entityUpdate(unit.getId());

        // Set the turn mask. We need to be specific otherwise we run the risk
        // of having a unit of another class consume the turn and leave the
        // unloaded unit without a turn
        int turnMask;
        List<GameTurn> turnVector = game.getTurnVector();
        if (unit instanceof Dropship) {
            turnMask = GameTurn.CLASS_DROPSHIP;
        } else if (unit instanceof SmallCraft) {
            turnMask = GameTurn.CLASS_SMALL_CRAFT;
        } else {
            turnMask = GameTurn.CLASS_AERO;
        }
        // Add one, otherwise we consider the turn we're currently processing
        int turnInsertIdx = game.getTurnIndex() + 1;
        // We have to figure out where to insert this turn, to maintain proper
        // space turn order (JumpShips, Small Craft, DropShips, Aeros)
        for (; turnInsertIdx < turnVector.size(); turnInsertIdx++) {
            GameTurn turn = turnVector.get(turnInsertIdx);
            if (turn.isValidEntity(unit, game)) {
                break;
            }
        }

        // ok add another turn for the unloaded entity so that it can move
        GameTurn newTurn = new GameTurn.EntityClassTurn(unit.getOwner().getId(), turnMask);
        game.insertTurnAfter(newTurn, turnInsertIdx);
        // brief everybody on the turn update
        server.send(PacketFactory.createTurnVectorPacket(game));

        return true;
    }
}
