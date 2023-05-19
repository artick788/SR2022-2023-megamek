package megamek.server;

import megamek.MegaMek;
import megamek.common.*;
import megamek.common.net.Packet;
import megamek.common.options.OptionsConstants;

import java.util.*;

public class EntityManager {

    private IGame game;
    private GameManager gameManager;

    // TODO: remove this later
    private Server server;

    public EntityManager(IGame game, GameManager gameManager, Server server) {
        this.game = game;
        this.gameManager = gameManager;
        this.server = server;
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
    public Vector<Report> destroyEntity(Entity entity, String reason, boolean survivable,
                                        boolean canSalvage) {
        // can't destroy an entity if it's already been destroyed
        if(entity.isDestroyed()) {
            return new Vector<Report>();
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
            Enumeration<Integer> iter = entity.getPickedUpMechWarriors().elements();
            while (iter.hasMoreElements()) {
                int mechWarriorId = iter.nextElement();
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
}
