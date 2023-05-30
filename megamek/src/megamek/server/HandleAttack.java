package megamek.server;

import megamek.MegaMek;
import megamek.common.*;
import megamek.common.actions.*;
import megamek.common.options.OptionsConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

public class HandleAttack {

    private IGame game;
    private ReportManager reportmanager;
    private GameManager gamemanager;
    private Vector<PhysicalResult> physicalResults = new Vector<>();
    private Server server;
    private EntityManager entityManager;

    public HandleAttack(Server server, IGame game, ReportManager reportmanager, GameManager gamemanager) {
        this.server = server;
        this.game = game;
        this.reportmanager = reportmanager;
        this.gamemanager = gamemanager;
        this.entityManager = new EntityManager(game, gamemanager, server);
    }

    /**
     * Handle all physical attacks for the round
     */
    public void resolvePhysicalAttacks() {
        // Physical phase header
        reportmanager.addReport(new Report(4000, Report.PUBLIC));

        // add any pending charges
        for (AttackAction action : game.getChargesVector()) {
            game.addAction(action);
        }
        game.resetCharges();

        // add any pending rams
        for (AttackAction action : game.getRamsVector()) {
            game.addAction(action);
        }
        game.resetRams();

        // add any pending Tele Missile Attacks
        for (AttackAction action : game.getTeleMissileAttacksVector()) {
            game.addAction(action);
        }
        game.resetTeleMissileAttacks();

        // remove any duplicate attack declarations
        game.cleanupPhysicalAttacks();

        // loop through received attack actions
        for (EntityAction o : game.getActionsVector()) {
            // verify that the attacker is still active
            AttackAction aa = (AttackAction) o;
            if (!game.getEntity(aa.getEntityId()).isActive() && !(o instanceof DfaAttackAction)) {
                continue;
            }
            AbstractAttackAction aaa = (AbstractAttackAction) o;
            // do searchlights immediately
            if (aaa instanceof SearchlightAttackAction) {
                SearchlightAttackAction saa = (SearchlightAttackAction) aaa;
                reportmanager.addReport(saa.resolveAction(game));
            } else {
                physicalResults.addElement(game.preTreatPhysicalAttack(aaa));
            }
        }
        int cen = Entity.NONE;
        for (PhysicalResult pr : physicalResults) {
            resolvePhysicalAttack(pr, cen);
            cen = pr.aaa.getEntityId();
        }
        physicalResults.removeAllElements();
    }

    /**
     * Resolve a Physical Attack
     *
     * @param pr  The <code>PhysicalResult</code> of the physical attack
     * @param cen The <code>int</code> Entity Id of the entity whose physical
     *            attack was last resolved
     */
    private void resolvePhysicalAttack(PhysicalResult pr, int cen) {
        AbstractAttackAction aaa = pr.aaa;
        if (aaa instanceof PunchAttackAction) {
            PunchAttackAction paa = (PunchAttackAction) aaa;
            if (paa.getArm() == PunchAttackAction.BOTH) {
                paa.setArm(PunchAttackAction.LEFT);
                pr.aaa = paa;
                resolvePunchAttack(pr, cen);
                cen = paa.getEntityId();
                paa.setArm(PunchAttackAction.RIGHT);
                pr.aaa = paa;
                resolvePunchAttack(pr, cen);
            } else {
                resolvePunchAttack(pr, cen);
                cen = paa.getEntityId();
            }
        } else if (aaa instanceof KickAttackAction) {
            resolveKickAttack(pr, cen);
            cen = aaa.getEntityId();
        } else if (aaa instanceof BrushOffAttackAction) {
            BrushOffAttackAction baa = (BrushOffAttackAction) aaa;
            if (baa.getArm() == BrushOffAttackAction.BOTH) {
                baa.setArm(BrushOffAttackAction.LEFT);
                pr.aaa = baa;
                resolveBrushOffAttack(pr, cen);
                cen = baa.getEntityId();
                baa.setArm(BrushOffAttackAction.RIGHT);
                pr.aaa = baa;
                resolveBrushOffAttack(pr, cen);
            } else {
                resolveBrushOffAttack(pr, cen);
                cen = baa.getEntityId();
            }
        } else if (aaa instanceof ThrashAttackAction) {
            resolveThrashAttack(pr, cen);
            cen = aaa.getEntityId();
        } else if (aaa instanceof ProtomechPhysicalAttackAction) {
            resolveProtoAttack(pr, cen);
            cen = aaa.getEntityId();
        } else if (aaa instanceof ClubAttackAction) {
            resolveClubAttack(pr, cen);
            cen = aaa.getEntityId();
        } else if (aaa instanceof PushAttackAction) {
            resolvePushAttack(pr, cen);
            cen = aaa.getEntityId();
        } else if (aaa instanceof ChargeAttackAction) {
            resolveChargeAttack(pr, cen);
            cen = aaa.getEntityId();
        } else if (aaa instanceof AirmechRamAttackAction) {
            resolveAirmechRamAttack(pr, cen);
            cen = aaa.getEntityId();
        } else if (aaa instanceof DfaAttackAction) {
            resolveDfaAttack(pr, cen);
            cen = aaa.getEntityId();
        } else if (aaa instanceof LayExplosivesAttackAction) {
            resolveLayExplosivesAttack(pr);
            cen = aaa.getEntityId();
        } else if (aaa instanceof TripAttackAction) {
            resolveTripAttack(pr, cen);
            cen = aaa.getEntityId();
        } else if (aaa instanceof JumpJetAttackAction) {
            resolveJumpJetAttack(pr, cen);
            cen = aaa.getEntityId();
        } else if (aaa instanceof GrappleAttackAction) {
            resolveGrappleAttack(pr, cen);
            cen = aaa.getEntityId();
        } else if (aaa instanceof BreakGrappleAttackAction) {
            resolveBreakGrappleAttack(pr, cen);
            cen = aaa.getEntityId();
        } else if (aaa instanceof RamAttackAction) {
            resolveRamAttack(pr, cen);
            cen = aaa.getEntityId();
        } else if (aaa instanceof TeleMissileAttackAction) {
            resolveTeleMissileAttack(pr, cen);
            cen = aaa.getEntityId();
        } else if (aaa instanceof BAVibroClawAttackAction) {
            resolveBAVibroClawAttack(pr, cen);
            cen = aaa.getEntityId();
        } else {
            MegaMek.getLogger().error("Unknown attack action declared.");
        }
        // Not all targets are Entities.
        Targetable target = game.getTarget(aaa.getTargetType(), aaa.getTargetId());
        if (target instanceof Entity) {
            Entity targetEntity = (Entity) target;
            targetEntity.setStruck(true);
            targetEntity.addAttackedByThisTurn(target.getTargetId());
            game.creditKill(targetEntity, game.getEntity(cen));
        }
    }

    /**
     * Handle a punch attack
     */
    private void resolvePunchAttack(PhysicalResult pr, int lastEntityId) {
        final PunchAttackAction paa = (PunchAttackAction) pr.aaa;
        final Entity ae = game.getEntity(paa.getEntityId());
        final Targetable target = game.getTarget(paa.getTargetType(), paa.getTargetId());
        Entity te = null;
        if (target.getTargetType() == Targetable.TYPE_ENTITY) {
            te = (Entity) target;
        }
        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(game, ae.getPosition(), te);
        }
        final String armName = paa.getArm() == PunchAttackAction.LEFT ? "Left Arm" : "Right Arm";

        final int armLoc = paa.getArm() == PunchAttackAction.LEFT ? Mech.LOC_LARM : Mech.LOC_RARM;

        // get damage, ToHitData and roll from the PhysicalResult
        int damage = paa.getArm() == PunchAttackAction.LEFT ? pr.damage : pr.damageRight;
        // LAMs in airmech mode do half damage if airborne.
        if (ae.isAirborneVTOLorWIGE()) {
            damage = (int)Math.ceil(damage * 0.5);
        }
        final ToHitData toHit = paa.getArm() == PunchAttackAction.LEFT ? pr.toHit : pr.toHitRight;
        int roll = paa.getArm() == PunchAttackAction.LEFT ? pr.roll : pr.rollRight;


        final boolean targetInBuilding = Compute.isInBuilding(game, te);
        final boolean glancing = game.getOptions().booleanOption(
                OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS) && (roll == toHit.getValue());

        // Set Margin of Success/Failure.
        toHit.setMoS(roll - Math.max(2, toHit.getValue()));
        final boolean directBlow = game.getOptions().booleanOption(
                OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW) && ((toHit.getMoS() / 3) >= 1);

        // Which building takes the damage?
        Building bldg = game.getBoard().getBuildingAt(target.getPosition());
        reportWhoAttacks(lastEntityId, pr.aaa, ae);

        reportmanager.addReport(ReportFactory.createReport(4010, 1, ae, armName, target.getDisplayName()));

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            reportmanager.addReport(ReportFactory.createReport(4015, ae, toHit.getDesc()));
            if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed punch attack"));
            }
            return;
        } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            reportmanager.addReport(ReportFactory.createReport(4020, ae, toHit.getDesc()));
            roll = Integer.MAX_VALUE;
        } else {
            reportmanager.addReport(ReportFactory.createReport(4025, ae, toHit.getValue(), roll));
            checkGlancingBlow(glancing, directBlow, ae, toHit, roll);
        }

        // do we hit?
        if (isHit(roll, toHit, ae, target, bldg, damage, targetInBuilding)){
            if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed punch attack"));
            }
            if(paa.isZweihandering()) {
                ArrayList<Integer> criticalLocs = new ArrayList<>();
                criticalLocs.add(Mech.LOC_RARM);
                criticalLocs.add(Mech.LOC_LARM);
                applyZweihanderSelfDamage(ae, true, criticalLocs);
            }
            return;
        }

        // Targeting a building.
        if (isTargetBuilding(target, ae, bldg, damage)) {
            if(paa.isZweihandering()) {
                ArrayList<Integer> criticalLocs = new ArrayList<>();
                criticalLocs.add(Mech.LOC_RARM);
                criticalLocs.add(Mech.LOC_LARM);
                applyZweihanderSelfDamage(ae, false, criticalLocs);
            }

            // And we're done!
            return;
        }

        HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
        hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
        reportmanager.addReport(ReportFactory.createReport(4045, ae, toHit.getTableDesc(), te.getLocationAbbr(hit)));

        damage = shieldsUnitsBuilding(targetInBuilding, bldg, damage, target, ae);

        // A building may absorb the entire shot.
        if (damage == 0) {
            reportmanager.addReport(ReportFactory.createReport(4050, 1, ae, te.getShortName(), te.getOwner().getName()));
        } else {
            if (glancing) {
                damage = gamemanager.applyInfrantryDamageReduction(te, damage);
            }
            if (directBlow) {
                damage += toHit.getMoS() / 3;
                hit.makeDirectBlow(toHit.getMoS() / 3);
            }

            damage = server.checkForSpikes(te, hit.getLocation(), damage, ae,
                    (paa.getArm() == PunchAttackAction.LEFT) ?  Mech.LOC_LARM : Mech.LOC_RARM);
            Server.DamageType damageType = Server.DamageType.NONE;
            reportmanager.addReport(server.damageEntity(te, hit, damage, false, damageType, false,
                    false, throughFront));
            if (target instanceof VTOL) {
                // destroy rotor
                reportmanager.addReport(server.applyCriticalHit(te, VTOL.LOC_ROTOR,
                        new CriticalSlot(CriticalSlot.TYPE_SYSTEM, VTOL.CRIT_ROTOR_DESTROYED),
                        false, 0, false));
            }
            // check for extending retractable blades
            if (paa.isBladeExtended(paa.getArm())) {
                reportmanager.addNewLines();
                reportmanager.addReport(ReportFactory.createReport(4455, 2, ae));
                // conventional infantry don't take crits and battle armor need
                // to be handled differently
                if (!(target instanceof Infantry)) {
                    reportmanager.addNewLines();
                    reportmanager.addReport(server.criticalEntity(te, hit.getLocation(), hit.isRear(), 0,
                            true, false, damage));
                }
                if ((target instanceof BattleArmor) && (hit.getLocation() < te.locations())
                        && (te.getInternal(hit.getLocation()) > 0)) {
                    // TODO : we should really apply BA criticals through the critical
                    // TODO : hits methods. Right now they are applied in damageEntity
                    HitData baHit = new HitData(hit.getLocation(), false, HitData.EFFECT_CRITICAL);
                    reportmanager.addReport(server.damageEntity(te, baHit, 0));
                }
                // extend the blade
                // since retracting/extending is a freebie in the movement
                // phase, lets assume that the
                // blade retracts to its original mode
                // ae.extendBlade(paa.getArm());
                // check for breaking a nail
                if (Compute.d6(2) > 9) {
                    reportmanager.addNewLines();
                    reportmanager.addReport(ReportFactory.createReport(4456, 2, ae));
                    ae.destroyRetractableBlade(armLoc);
                }
            }
        }
        reportmanager.addNewLines();

        if(paa.isZweihandering()) {
            ArrayList<Integer> criticalLocs = new ArrayList<>();
            criticalLocs.add(Mech.LOC_RARM);
            criticalLocs.add(Mech.LOC_LARM);
            applyZweihanderSelfDamage(ae, false, criticalLocs);
        }
        reportmanager.addNewLines();

        // if the target is an industrial mech, it needs to check for crits
        // at the end of turn
        if ((target instanceof Mech) && ((Mech) target).isIndustrial()) {
            ((Mech) target).setCheckForCrit(true);
        }
    }

    private int shieldsUnitsBuilding(boolean targetInBuilding, Building bldg, int damage, Targetable target, Entity ae) {
        // The building shields all units from a certain amount of damage.
        // The amount is based upon the building's CF at the phase's start.
        if (targetInBuilding && (bldg != null)) {
            int bldgAbsorbs = bldg.getAbsorbtion(target.getPosition());
            int toBldg = Math.min(bldgAbsorbs, damage);
            damage -= toBldg;
            reportmanager.addNewLines();
            Vector<Report> buildingReport = server.damageBuilding(bldg, toBldg, target.getPosition());
            for (Report report : buildingReport) {
                report.subject = ae.getId();
            }
            reportmanager.addReport(buildingReport);

            // some buildings scale remaining damage that is not absorbed
            // TODO : this isn't quite right for castles brian
            return (int) Math.floor(bldg.getDamageToScale() * damage);
        }
        return damage;
    }

    private void resolveLayExplosivesAttack(PhysicalResult pr) {
        final LayExplosivesAttackAction laa = (LayExplosivesAttackAction) pr.aaa;
        final Entity ae = game.getEntity(laa.getEntityId());
        if (ae instanceof Infantry) {
            Infantry inf = (Infantry) ae;
            if (inf.turnsLayingExplosives < 0) {
                inf.turnsLayingExplosives = 0;
                reportmanager.addReport(ReportFactory.createReport(4270, inf));
            } else {
                Building building = game.getBoard().getBuildingAt(ae.getPosition());
                if (building != null) {
                    building.addDemolitionCharge(ae.getOwner().getId(), pr.damage, ae.getPosition());
                    reportmanager.addReport(ReportFactory.createReport(4275, inf, pr.damage));
                    // Update clients with this info
                    Vector<Building> updatedBuildings = new Vector<>();
                    updatedBuildings.add(building);
                    server.sendChangedBuildings(updatedBuildings);
                }
                inf.turnsLayingExplosives = -1;
            }
        }
    }

    /**
     * Handle a death from above attack
     */
    private void resolveDfaAttack(PhysicalResult pr, int lastEntityId) {
        final DfaAttackAction daa = (DfaAttackAction) pr.aaa;
        final Entity ae = game.getEntity(daa.getEntityId());

        // is the attacker dead? because that sure messes up the calculations
        if (ae == null) {
            return;
        }

        final IHex aeHex = game.getBoard().getHex(ae.getPosition());
        final IHex teHex = game.getBoard().getHex(daa.getTargetPos());
        final Targetable target = game.getTarget(daa.getTargetType(), daa.getTargetId());
        // get damage, ToHitData and roll from the PhysicalResult
        int damage = pr.damage;
        final ToHitData toHit = pr.toHit;
        int roll = pr.roll;
        Entity te = null;
        if ((target != null) && (target.getTargetType() == Targetable.TYPE_ENTITY)) {
            // Lets re-write around that horrible hack that was here before.
            // So instead of asking if a specific location is wet and praying
            // that it won't cause an NPE...
            // We'll check 1) if the hex has water, and 2) if it's deep enough
            // to cover the unit in question at its current elevation.
            // It's especially important to make sure it's done this way,
            // because some units (Sylph, submarines) can be at ANY elevation
            // underwater, and VTOLs can be well above the surface.
            te = (Entity) target;
            IHex hex = game.getBoard().getHex(te.getPosition());
            if (hex.containsTerrain(Terrains.WATER)) {
                if (te.relHeight() < 0) {
                    damage = (int) Math.ceil(damage * 0.5f);
                }
            }
        }
        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(game, ae.getPosition(), te);
        }
        final boolean glancing = game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS)
                && (roll == toHit.getValue());
        // Set Margin of Success/Failure.
        toHit.setMoS(roll - Math.max(2, toHit.getValue()));
        final boolean directBlow = game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW)
                && ((toHit.getMoS() / 3) >= 1);

        final int direction = ae.getFacing();
        reportWhoAttacks(lastEntityId, pr.aaa, ae);

        // should we even bother?
        if (!doBother(target, te, ae, 4245)){
            ae.setDisplacementAttack(null);
            if (ae.isProne()) {
                // attacker prone during weapons phase
                reportmanager.addReport(server.doEntityFall(ae, daa.getTargetPos(), 2, 3,
                        ae.getBasePilotingRoll(), false, false));

            } else {
                // same effect as successful DFA
                ae.setElevation(ae.calcElevation(aeHex, teHex, 0, false, false));
                reportmanager.addReport(server.doEntityDisplacement(ae, ae.getPosition(),
                        daa.getTargetPos(), new PilotingRollData(ae.getId(), 4, "executed death from above")));
            }
            return;
        }

        reportmanager.addReport(ReportFactory.createReport(4246, 1, ae, target.getDisplayName()));

        // target still in the same position?
        if (!target.getPosition().equals(daa.getTargetPos())) {
            reportmanager.addReport(ReportFactory.createReport(4215, ae));
            // entity isn't DFAing any more
            ae.setDisplacementAttack(null);
            reportmanager.addReport(server.doEntityFallsInto(ae, ae.getElevation(), ae.getPosition(), daa.getTargetPos(),
                    ae.getBasePilotingRoll(), true));
            return;
        }

        // hack: if the attacker's prone, or incapacitated, fudge the roll
        if (ae.isProne() || !ae.isActive()) {
            roll = -12;
            reportmanager.addReport(ReportFactory.createReport(4250, ae, toHit.getDesc()));
        } else if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            roll = -12;
            reportmanager.addReport(ReportFactory.createReport(4255, ae, toHit.getDesc()));
        } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            reportmanager.addReport(ReportFactory.createReport(4260, ae, toHit.getDesc()));
            roll = Integer.MAX_VALUE;
        } else {
            // report the roll
            checkGlancingBlow(glancing, directBlow, ae, toHit, roll);
        }

        // do we hit?
        if (roll < toHit.getValue()) {
            Coords dest = te.getPosition();
            Coords targetDest = Compute.getPreferredDisplacement(game, te.getId(), dest, direction);
            // miss
            reportmanager.addReport(ReportFactory.createReport(4035, ae));
            if (targetDest != null) {
                // move target to preferred hex
                reportmanager.addReport(server.doEntityDisplacement(te, dest, targetDest, null));
                // attacker falls into destination hex
                reportmanager.addReport(ReportFactory.createReport(4265, 1, ae, dest.getBoardNum()));
                // entity isn't DFAing any more
                ae.setDisplacementAttack(null);
                reportmanager.addReport(server.doEntityFall(ae, dest, 2, 3, ae.getBasePilotingRoll(),
                        false, false), 1);
                Entity violation = Compute.stackingViolation(game, ae.getId(), dest);
                if (violation != null) {
                    // target gets displaced
                    targetDest = Compute.getValidDisplacement(game, violation.getId(), dest, direction);
                    reportmanager.addReport(server.doEntityDisplacement(violation, dest, targetDest,
                            new PilotingRollData(violation.getId(), 0, "domino effect")));
                    // Update the violating entity's position on the client.
                    if (!game.getOutOfGameEntitiesVector().contains(violation)) {
                        entityManager.entityUpdate(violation.getId());
                    }
                }
            } else {
                // attacker destroyed
                // Tanks suffer an ammo/power plant hit.
                // TODO : a Mech suffers a Head Blown Off crit.
                reportmanager.addReport(entityManager.destroyEntity(ae, "impossible displacement",
                        ae instanceof Mech, ae instanceof Mech));
            }
            return;
        }

        // we hit...
        reportmanager.addReport(ReportFactory.createReport(4040, ae));

        Coords dest = target.getPosition();

        // Can't DFA a target inside of a building.
        int damageTaken = DfaAttackAction.getDamageTakenBy(ae);

        // Targeting a building.
        if ((target.getTargetType() == Targetable.TYPE_BUILDING) || (target.getTargetType() == Targetable.TYPE_FUEL_TANK)) {
            // Which building takes the damage?
            Building bldg = game.getBoard().getBuildingAt(daa.getTargetPos());

            // The building takes the full brunt of the attack.
            Vector<Report> buildingReport = server.damageBuilding(bldg, damage, target.getPosition());
            for (Report report : buildingReport) {
                report.subject = ae.getId();
            }
            reportmanager.addReport(buildingReport);

            // Damage any infantry in the hex.
            reportmanager.addReport(server.damageInfantryIn(bldg, damage, target.getPosition()));
        } else { // Target isn't building.
            if (glancing) {
                damage = gamemanager.applyInfrantryDamageReduction(te, damage);
            }
            if (directBlow) {
                damage += toHit.getMoS() / 3;
            }
            // damage target
            Report r = ReportFactory.createReport(4230, 2, ae, damage);
            r.add(toHit.getTableDesc());
            reportmanager.addReport(r);

            while (damage > 0) {
                int cluster = Math.min(5, damage);
                HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
                hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                if (directBlow) {
                    hit.makeDirectBlow(toHit.getMoS() / 3);
                }
                damage -= cluster;
                cluster = server.checkForSpikes(te, hit.getLocation(), cluster, ae, Mech.LOC_LLEG, Mech.LOC_RLEG);
                reportmanager.addReport(server.damageEntity(te, hit, cluster, false,
                        Server.DamageType.NONE, false, false, throughFront));
            }
            if (target instanceof VTOL) {
                // destroy rotor
                reportmanager.addReport(server.applyCriticalHit(te, VTOL.LOC_ROTOR,
                        new CriticalSlot(CriticalSlot.TYPE_SYSTEM,
                                VTOL.CRIT_ROTOR_DESTROYED), false, 0, false));
            }
            // Target entities are pushed away or destroyed.
            Coords targetDest = Compute.getValidDisplacement(game, te.getId(), dest, direction);
            if (targetDest != null) {
                reportmanager.addReport(server.doEntityDisplacement(te, dest, targetDest,
                        new PilotingRollData(te.getId(), 2, "hit by death from above")));
            } else {
                // ack! automatic death! Tanks
                // suffer an ammo/power plant hit.
                // TODO : a Mech suffers a Head Blown Off crit.
                reportmanager.addReport(entityManager.destroyEntity(te, "impossible displacement",
                        te instanceof Mech, te instanceof Mech));
            }
        }

        if (glancing || ae.hasQuirk(OptionsConstants.QUIRK_POS_REINFORCED_LEGS)) {
            // Glancing Blow rule doesn't state whether damage to attacker on charge
            // or DFA is halved as well, assume yes. TODO : Check with PM
            damageTaken = (int) Math.floor(damageTaken / 2.0);
        }

        // damage attacker
        reportmanager.addReport(ReportFactory.createReport(4240, 2, ae, damageTaken));
        while (damageTaken > 0) {
            int cluster = Math.min(5, damageTaken);
            HitData hit = ae.rollHitLocation(ToHitData.HIT_KICK, ToHitData.SIDE_FRONT);
            hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
            reportmanager.addReport(server.damageEntity(ae, hit, cluster));
            damageTaken -= cluster;
        }

        if (ae.hasQuirk(OptionsConstants.QUIRK_NEG_WEAK_LEGS)) {
            reportmanager.addNewLines();
            reportmanager.addReport(server.criticalEntity(ae, Mech.LOC_LLEG, false, 0, 0));
            reportmanager.addNewLines();
            reportmanager.addReport(server.criticalEntity(ae, Mech.LOC_RLEG, false, 0, 0));
            if (ae instanceof QuadMech) {
                reportmanager.addNewLines();
                reportmanager.addReport(server.criticalEntity(ae, Mech.LOC_LARM, false, 0, 0));
                reportmanager.addNewLines();
                reportmanager.addReport(server.criticalEntity(ae, Mech.LOC_RARM, false, 0, 0));
            }
        }

        reportmanager.addNewLines();

        // That's it for target buildings.
        if ((target.getTargetType() == Targetable.TYPE_BUILDING) || (target.getTargetType() == Targetable.TYPE_FUEL_TANK)) {
            return;
        }
        ae.setElevation(ae.calcElevation(aeHex, teHex, 0, false, false));
        // HACK: to avoid automatic falls, displace from dest to dest
        reportmanager.addReport(server.doEntityDisplacement(ae, dest, dest, new PilotingRollData(
                ae.getId(), 4, "executed death from above")));

        // entity isn't DFAing any more
        ae.setDisplacementAttack(null);

        // if the target is an industrial mech, it needs to check for crits
        // at the end of turn
        if ((target instanceof Mech) && ((Mech) target).isIndustrial()) {
            ((Mech) target).setCheckForCrit(true);
        }
    }

    /**
     * Handle a kick attack
     */
    private void resolveKickAttack(PhysicalResult pr, int lastEntityId) {
        KickAttackAction kaa = (KickAttackAction) pr.aaa;
        final Entity ae = game.getEntity(kaa.getEntityId());
        final Targetable target = game.getTarget(kaa.getTargetType(), kaa.getTargetId());
        Entity te = null;
        if ((target != null) && target.getTargetType() == Targetable.TYPE_ENTITY) {
            te = (Entity) target;
        }
        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(game, ae.getPosition(), te);
        }
        String legName = (kaa.getLeg() == KickAttackAction.LEFT) || (kaa.getLeg() == KickAttackAction.LEFTMULE) ? "Left " : "Right ";
        if ((kaa.getLeg() == KickAttackAction.LEFTMULE) || (kaa.getLeg() == KickAttackAction.RIGHTMULE)) {
            legName = legName.concat("rear ");
        } else if (ae instanceof QuadMech) {
            legName = legName.concat("front ");
        }
        legName = legName.concat("leg");

        // get damage, ToHitData and roll from the PhysicalResult
        int damage = pr.damage;
        // LAMs in airmech mode do half damage if airborne.
        if (ae.isAirborneVTOLorWIGE()) {
            damage = (int)Math.ceil(damage * 0.5);
        }
        final ToHitData toHit = pr.toHit;
        int roll = pr.roll;


        final boolean targetInBuilding = Compute.isInBuilding(game, te);
        final boolean glancing = game.getOptions().booleanOption(
                OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS) && (roll == toHit.getValue());

        // Set Margin of Success/Failure.
        toHit.setMoS(roll - Math.max(2, toHit.getValue()));
        final boolean directBlow = game.getOptions().booleanOption(
                OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW) && ((toHit.getMoS() / 3) >= 1);

        // Which building takes the damage?
        Building bldg = game.getBoard().getBuildingAt(target.getPosition());

        if (lastEntityId != ae.getId()) {
            // who is making the attacks
            reportmanager.addReport(ReportFactory.createReport(4005, ae));
        }

        reportmanager.addReport(ReportFactory.createReport(4055, 1, ae, legName, target.getDisplayName()));

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            reportmanager.addReport(ReportFactory.createReport(4060, ae, toHit.getDesc()));
            if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed a kick"));
            } else {
                game.addPSR(new PilotingRollData(ae.getId(), 0, "missed a kick"));
            }
            return;
        } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            reportmanager.addReport(ReportFactory.createReport(4065, ae, toHit.getDesc()));
            roll = Integer.MAX_VALUE;
        } else {
            reportmanager.addReport(ReportFactory.createReport(4025, ae, toHit.getValue(), roll));
            checkGlancingBlow(glancing, directBlow, ae, toHit, roll);
        }

        // do we hit?
        if (isHit(roll, toHit, ae, target, bldg, damage, targetInBuilding)){
            if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed a kick"));
            } else {
                game.addPSR(new PilotingRollData(ae.getId(), 0, "missed a kick"));
            }
            return;
        }
        // Targeting a building.
        if (isTargetBuilding(target, ae, bldg, damage)) {
            return;
        }

        HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
        hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
        reportmanager.addReport(ReportFactory.createReport(4045, ae, toHit.getTableDesc(), te.getLocationAbbr(hit)));

        damage = shieldsUnitsBuilding(targetInBuilding, bldg, damage, target, ae);

        // A building may absorb the entire shot.
        if (damage == 0) {
            reportmanager.addReport(ReportFactory.createReport(4050, ae, te.getShortName(), te.getOwner().getName()));
        } else {
            if (glancing) {
                damage = gamemanager.applyInfrantryDamageReduction(te, damage);
            }
            if (directBlow) {
                damage += toHit.getMoS() / 3;
                hit.makeDirectBlow(toHit.getMoS() / 3);
            }

            int leg;
            switch (kaa.getLeg()) {
                case KickAttackAction.LEFT:
                    leg = (ae instanceof QuadMech) ? Mech.LOC_LARM : Mech.LOC_LLEG;
                    break;
                case KickAttackAction.RIGHT:
                    leg = (ae instanceof QuadMech) ? Mech.LOC_RARM : Mech.LOC_RLEG;
                    break;
                case KickAttackAction.LEFTMULE:
                    leg = Mech.LOC_LLEG;
                    break;
                case KickAttackAction.RIGHTMULE:
                default:
                    leg = Mech.LOC_RLEG;
                    break;
            }
            damage = server.checkForSpikes(te, hit.getLocation(), damage, ae, leg);
            Server.DamageType damageType = Server.DamageType.NONE;
            reportmanager.addReport(server.damageEntity(te, hit, damage, false, damageType, false,
                    false, throughFront));
            if (target instanceof VTOL) {
                // destroy rotor
                reportmanager.addReport(server.applyCriticalHit(te, VTOL.LOC_ROTOR, new CriticalSlot(CriticalSlot.TYPE_SYSTEM,
                        VTOL.CRIT_ROTOR_DESTROYED), false, 0, false));
            }
            if (te.hasQuirk(OptionsConstants.QUIRK_NEG_WEAK_LEGS)) {
                reportmanager.addNewLines();
                reportmanager.addReport(server.criticalEntity(te, hit.getLocation(), hit.isRear(), 0, 0));
            }
        }

        if (te.canFall()) {
            PilotingRollData kickPRD = game.getKickPushPSR(te, ae, te, "was kicked");
            game.addPSR(kickPRD);
        }

        // if the target is an industrial mech, it needs to check for crits
        // at the end of turn
        if ((te instanceof Mech) && ((Mech) te).isIndustrial()) {
            ((Mech) te).setCheckForCrit(true);
        }

        reportmanager.addNewLines();
    }

    /**
     * Handle a kick attack
     */
    private void resolveJumpJetAttack(PhysicalResult pr, int lastEntityId) {
        JumpJetAttackAction kaa = (JumpJetAttackAction) pr.aaa;
        final Entity ae = game.getEntity(kaa.getEntityId());
        final Targetable target = game.getTarget(kaa.getTargetType(), kaa.getTargetId());
        Entity te = null;
        if ((target != null) && target.getTargetType() == Targetable.TYPE_ENTITY) {
            te = (Entity) target;
        }
        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(game, ae.getPosition(), te);
        }
        String legName;
        switch (kaa.getLeg()) {
            case JumpJetAttackAction.LEFT:
                legName = "Left leg";
                break;
            case JumpJetAttackAction.RIGHT:
                legName = "Right leg";
                break;
            default:
                legName = "Both legs";
                break;
        }
        // get damage, ToHitData and roll from the PhysicalResult
        int damage = pr.damage;
        final ToHitData toHit = pr.toHit;
        int roll = pr.roll;
        final boolean targetInBuilding = Compute.isInBuilding(game, te);
        final boolean glancing = game.getOptions().booleanOption(
                OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS) && (roll == toHit.getValue());

        // Set Margin of Success/Failure.
        toHit.setMoS(roll - Math.max(2, toHit.getValue()));
        final boolean directBlow = game.getOptions().booleanOption(
                OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW) && ((toHit.getMoS() / 3) >= 1);

        // Which building takes the damage?
        Building bldg = game.getBoard().getBuildingAt(target.getPosition());
        reportWhoAttacks(lastEntityId, pr.aaa, ae);

        reportmanager.addReport(ReportFactory.createReport(4290, 1, ae, legName, target.getDisplayName()));

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            reportmanager.addReport(ReportFactory.createReport(4075, ae, toHit.getDesc()));
            return;
        } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            reportmanager.addReport(ReportFactory.createReport(4080, ae, toHit.getDesc()));
            roll = Integer.MAX_VALUE;
        } else {
            checkGlancingBlow(glancing, directBlow, ae, toHit, roll);
        }

        // do we hit?
        if (isHit(roll, toHit, ae, target, bldg, damage, targetInBuilding)){
            return;
        }

        // Targeting a building.
        if ((target.getTargetType() == Targetable.TYPE_BUILDING) || (target.getTargetType() == Targetable.TYPE_FUEL_TANK)) {
            damage += pr.damageRight;
            targetBuilding(target, ae, bldg, damage);
            return;
        }

        reportmanager.addReport(ReportFactory.createReport(4040, ae));

        for (int leg = 0; leg < 2; leg++) {
            if (leg == 1) {
                damage = pr.damageRight;
                if (damage == 0) {
                    break;
                }
            }
            HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
            hit.setGeneralDamageType(HitData.DAMAGE_ENERGY);

            damage = shieldsUnitsBuilding(targetInBuilding, bldg, damage, target, ae);

            // A building may absorb the entire shot.
            if (damage == 0) {
                reportmanager.addReport(ReportFactory.createReport(4050, ae, te.getShortName(), te.getOwner().getName()));
            } else {
                if (glancing) {
                    damage = gamemanager.applyInfrantryDamageReduction(te, damage);
                }
                if (directBlow) {
                    damage += toHit.getMoS() / 3;
                    hit.makeDirectBlow(toHit.getMoS() / 3);
                }
                reportmanager.addReport(server.damageEntity(te, hit, damage, false, Server.DamageType.NONE,
                        false, false, throughFront));
            }
        }

        reportmanager.addNewLines();

        // if the target is an industrial mech, it needs to check for crits
        // at the end of turn
        if ((target instanceof Mech) && ((Mech) target).isIndustrial()) {
            ((Mech) target).setCheckForCrit(true);
        }
    }

    /**
     * Handle a ProtoMech physical attack
     */
    private void resolveProtoAttack(PhysicalResult pr, int lastEntityId) {
        final ProtomechPhysicalAttackAction ppaa = (ProtomechPhysicalAttackAction) pr.aaa;
        final Entity ae = game.getEntity(ppaa.getEntityId());
        // get damage, ToHitData and roll from the PhysicalResult
        int damage = pr.damage;
        final ToHitData toHit = pr.toHit;
        int roll = pr.roll;
        final Targetable target = game.getTarget(ppaa.getTargetType(), ppaa.getTargetId());
        Entity te = null;
        if ((target != null) && target.getTargetType() == Targetable.TYPE_ENTITY) {
            te = (Entity) target;
        }
        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(game, ae.getPosition(), te);
        }
        final boolean targetInBuilding = Compute.isInBuilding(game, te);
        final boolean glancing = game.getOptions().booleanOption(
                OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS) && (roll == toHit.getValue());
        // Set Margin of Success/Failure.
        toHit.setMoS(roll - Math.max(2, toHit.getValue()));
        final boolean directBlow = game.getOptions().booleanOption(
                OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW) && ((toHit.getMoS() / 3) >= 1);

        // Which building takes the damage?
        Building bldg = game.getBoard().getBuildingAt(target.getPosition());
        reportWhoAttacks(lastEntityId, pr.aaa, ae);

        reportmanager.addReport(ReportFactory.createReport(4070, 1, ae, target.getDisplayName()));

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            reportmanager.addReport(ReportFactory.createReport(4075, ae, toHit.getDesc()));
            return;
        } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            reportmanager.addReport(ReportFactory.createReport(4080, ae, toHit.getDesc()));
            roll = Integer.MAX_VALUE;
        } else {
            // report the roll
            checkGlancingBlow(glancing, directBlow, ae, toHit, roll);
        }

        // do we hit?
        if (isHit(roll, toHit, ae, target, bldg, damage, targetInBuilding)){
            return;
        }

        // Targeting a building.
        if (isTargetBuilding(target, ae, bldg, damage)) {
            return;
        }

        HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
        hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);

        reportmanager.addReport(ReportFactory.createReport(4045, ae, toHit.getTableDesc(), te.getLocationAbbr(hit)));

        damage = shieldsUnitsBuilding(targetInBuilding, bldg, damage, target, ae);

        // A building may absorb the entire shot.
        if (damage == 0) {
            reportmanager.addReport(ReportFactory.createReport(4050, ae, te.getShortName(), te.getOwner().getName()));
        } else {
            if (glancing) {
                damage = gamemanager.applyInfrantryDamageReduction(te, damage);
            }
            if (directBlow) {
                damage += toHit.getMoS() / 3;
                hit.makeDirectBlow(toHit.getMoS() / 3);
            }
            reportmanager.addReport(server.damageEntity(te, hit, damage, false, Server.DamageType.NONE,
                    false, false, throughFront));
            if (((Protomech) ae).isEDPCharged()) {
                Report r = ReportFactory.createReport(3701);
                int taserRoll = Compute.d6(2) - 2;
                r.add(taserRoll);
                r.newlines = 0;
                reportmanager.addReport(r);

                if (te instanceof BattleArmor) {
                    // shut down for rest of scenario, so we actually kill it
                    // TODO : fix for salvage purposes
                    HitData targetTrooper = te.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                    reportmanager.addReport(ReportFactory.createReport(3701, te, te.getLocationAbbr(targetTrooper)));
                    reportmanager.addReport(server.criticalEntity(ae, targetTrooper.getLocation(),
                            targetTrooper.isRear(), 0, false, false, 0));
                } else if (te instanceof Mech) {
                    if (((Mech) te).isIndustrial()) {
                        reportTaserRoll(te, taserRoll, 8, 4, true);
                    } else {
                        reportTaserRoll(te, taserRoll, 11, 3, true);
                    }
                } else if ((te instanceof Protomech) || (te instanceof Tank) || (te instanceof Aero)) {
                    reportTaserRoll(te, taserRoll, 8, 4, false);
                }
            }
        }

        reportmanager.addNewLines();

        // if the target is an industrial mech, it needs to check for crits
        // at the end of turn
        if ((target instanceof Mech) && ((Mech) target).isIndustrial()) {
            ((Mech) target).setCheckForCrit(true);
        }
    }

    /**
     * Handle a brush off attack
     */
    private void resolveBrushOffAttack(PhysicalResult pr, int lastEntityId) {
        final BrushOffAttackAction baa = (BrushOffAttackAction) pr.aaa;
        final Entity ae = game.getEntity(baa.getEntityId());
        // PLEASE NOTE: buildings are *never* the target
        // of a "brush off", but iNarc pods **are**.
        Targetable target = game.getTarget(baa.getTargetType(), baa.getTargetId());
        final String armName = baa.getArm() == BrushOffAttackAction.LEFT ? "Left Arm" : "Right Arm";

        Entity te = null;
        if ((target != null) && target.getTargetType() == Targetable.TYPE_ENTITY) {
            te = game.getEntity(baa.getTargetId());
        }

        // get damage, ToHitData and roll from the PhysicalResult
        // ASSUMPTION: buildings can't absorb *this* damage.
        int damage = baa.getArm() == BrushOffAttackAction.LEFT ? pr.damage : pr.damageRight;
        final ToHitData toHit = baa.getArm() == BrushOffAttackAction.LEFT ? pr.toHit : pr.toHitRight;
        int roll = baa.getArm() == BrushOffAttackAction.LEFT ? pr.roll : pr.rollRight;

        reportWhoAttacks(lastEntityId, pr.aaa, ae);

        reportmanager.addReport(ReportFactory.createReport(4085, 1, ae, target.getDisplayName(), armName));

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            reportmanager.addReport(ReportFactory.createReport(4090, ae, toHit.getDesc()));
            return;
        }

        // report the roll
        reportmanager.addReport(ReportFactory.createReport(4025, ae, toHit.getValue(), roll));

        // do we hit?
        if (roll < toHit.getValue()) {
            // miss
            reportmanager.addReport(ReportFactory.createReport(4035, ae));

            // Missed Brush Off attacks cause punch damage to the attacker.
            toHit.setHitTable(ToHitData.HIT_PUNCH);
            toHit.setSideTable(ToHitData.SIDE_FRONT);
            HitData hit = ae.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
            hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
            reportmanager.addReport(ReportFactory.createReport(4095, ae, ae.getLocationAbbr(hit)));
            reportmanager.addReport(server.damageEntity(ae, hit, damage));
            reportmanager.addNewLines();
            // if this is an industrial mech, it needs to check for crits
            // at the end of turn
            if ((ae instanceof Mech) && ((Mech) ae).isIndustrial()) {
                ((Mech) ae).setCheckForCrit(true);
            }
            return;
        }

        // Different target types get different handling.
        switch (target.getTargetType()) {
            case Targetable.TYPE_ENTITY:
                // Handle Entity targets.
                HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
                hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                reportmanager.addReport(ReportFactory.createReport(4045, ae, toHit.getTableDesc(), te.getLocationAbbr(hit)));
                reportmanager.addReport(server.damageEntity(te, hit, damage));
                reportmanager.addNewLines();

                // Dislodge the swarming infantry.
                ae.setSwarmAttackerId(Entity.NONE);
                te.setSwarmTargetId(Entity.NONE);
                reportmanager.addReport(ReportFactory.createReport(4100, ae, te.getDisplayName()));
                break;
            case Targetable.TYPE_INARC_POD:
                // Handle iNarc pod targets.
                // TODO : check the return code and handle false appropriately.
                ae.removeINarcPod((INarcPod) target);
                // TODO : confirm that we don't need to update the attacker.
                // killme
                // entityManager.entityUpdate( ae.getId() ); // killme
                reportmanager.addReport(ReportFactory.createReport(4105, ae, target.getDisplayName()));
                break;
            // TODO : add a default: case and handle it appropriately.
        }
    }

    /**
     * Handle a thrash attack
     */
    private void resolveThrashAttack(PhysicalResult pr, int lastEntityId) {
        final ThrashAttackAction taa = (ThrashAttackAction) pr.aaa;
        final Entity ae = game.getEntity(taa.getEntityId());

        // get damage, ToHitData and roll from the PhysicalResult
        int hits = pr.damage;
        final ToHitData toHit = pr.toHit;
        int roll = pr.roll;
        final boolean glancing = game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS)
                && (roll == toHit.getValue());

        // Set Margin of Success/Failure.
        toHit.setMoS(roll - Math.max(2, toHit.getValue()));
        final boolean directBlow = game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW)
                && ((toHit.getMoS() / 3) >= 1);

        // PLEASE NOTE: buildings are *never* the target of a "thrash".
        final Entity te = game.getEntity(taa.getTargetId());

        reportWhoAttacks(lastEntityId, pr.aaa, ae);
        reportmanager.addReport(ReportFactory.createAttackingEntityReport(4110, ae, te));

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            reportmanager.addReport(ReportFactory.createReport(4115, ae, toHit.getDesc()));
            return;
        }

        // Thrash attack may hit automatically
        int reportID = 4125;
        if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            reportID = 4120;
        } else {
            // report the roll
            reportmanager.addReport(ReportFactory.createReport(4025, ae, toHit.getValue(), roll));

            // do we hit?
            if (roll < toHit.getValue()) {
                // miss
                reportmanager.addReport(ReportFactory.createReport(4035, ae));
                return;
            }
        }
        reportmanager.addReport(ReportFactory.createReport(reportID, ae));

        // Standard damage loop in 5 point clusters.
        if (glancing) {
            hits = (int) Math.floor(hits / 2.0);
        }
        if (directBlow) {
            hits += toHit.getMoS() / 3;
        }

        reportmanager.addReport(ReportFactory.createReport(4130, ae, hits));
        if (glancing) {
            reportmanager.addReport(ReportFactory.createReport(3186, ae));
        }
        if (directBlow) {
            reportmanager.addReport(ReportFactory.createReport(3189, ae));
        }

        while (hits > 0) {
            int damage = Math.min(5, hits);
            hits -= damage;
            HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
            hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
            reportmanager.addReport(ReportFactory.createReport(4135, ae, te.getLocationAbbr(hit)));
            reportmanager.addReport(server.damageEntity(te, hit, damage));
        }

        reportmanager.addNewLines();

        // Thrash attacks cause PSRs. Failed PSRs cause falling damage.
        // This fall damage applies even though the Thrashing Mek is prone.
        PilotingRollData rollData = ae.getBasePilotingRoll();
        ae.addPilotingModifierForTerrain(rollData);
        rollData.addModifier(0, "thrashing at infantry");
        reportmanager.addReport(ReportFactory.createReport(4140, ae));
        final int diceRoll = Compute.d6(2);
        Report r = ReportFactory.createReport(2190, ae, rollData.getValueAsString(), rollData.getDesc());
        r.add(diceRoll);
        if (diceRoll < rollData.getValue()) {
            r.choose(false);
            reportmanager.addReport(r);
            reportmanager.addReport(server.doEntityFall(ae, rollData));
        } else {
            r.choose(true);
            reportmanager.addReport(r);
        }
    }

    /**
     * Handle a thrash attack
     */
    private void resolveBAVibroClawAttack(PhysicalResult pr, int lastEntityId) {
        final BAVibroClawAttackAction bvaa = (BAVibroClawAttackAction) pr.aaa;
        final Entity ae = game.getEntity(bvaa.getEntityId());

        // get damage, ToHitData and roll from the PhysicalResult
        int hits = pr.damage;
        final ToHitData toHit = pr.toHit;
        int roll = pr.roll;
        final boolean glancing = game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS)
                && (roll == toHit.getValue());

        // Set Margin of Success/Failure.
        toHit.setMoS(roll - Math.max(2, toHit.getValue()));
        final boolean directBlow = game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW)
                && ((toHit.getMoS() / 3) >= 1);

        // PLEASE NOTE: buildings are *never* the target of a BA vibroclaw
        // attack.
        final Entity te = game.getEntity(bvaa.getTargetId());

        if (lastEntityId != bvaa.getEntityId()) {
            // who is making the attacks
            reportmanager.addReport(ReportFactory.createReport(4005, ae));
        }
        reportmanager.addReport(ReportFactory.createAttackingEntityReport(4146, ae, te));

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            reportmanager.addReport(ReportFactory.createReport(4147, ae, toHit.getDesc()));
            return;
        }

        // we may hit automatically
        if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            reportmanager.addReport(ReportFactory.createReport(4120, ae));
        } else {
            // report the roll
            reportmanager.addReport(ReportFactory.createReport(4025, ae, toHit.getValue(), roll));

            // do we hit?
            if (roll < toHit.getValue()) {
                // miss
                reportmanager.addReport(ReportFactory.createReport(4035, ae));
                return;
            }
        }

        // Standard damage loop
        if (glancing) {
            hits = (int) Math.floor(hits / 2.0);
        }
        if (directBlow) {
            hits += toHit.getMoS() / 3;
        }
        if ((te instanceof Infantry) && !(te instanceof BattleArmor)) {
            reportmanager.addReport(ReportFactory.createReport(4149, ae, hits));
        } else {
            reportmanager.addReport(ReportFactory.createReport(4148, ae, hits, ae.getVibroClaws()));
        }
        if (glancing) {
            reportmanager.addReport(ReportFactory.createReport(3186, ae));
        }
        if (directBlow) {
            reportmanager.addReport(ReportFactory.createReport(3189, ae));
        }
        while (hits > 0) {
            // BA get hit separately by each attacking BA trooper
            int damage = Math.min(ae.getVibroClaws(), hits);
            // conventional infantry get hit in one lump
            if ((te instanceof Infantry) && !(te instanceof BattleArmor)) {
                damage = hits;
            }
            hits -= damage;
            HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
            hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
            reportmanager.addReport(ReportFactory.createReport(4135, ae, te.getLocationAbbr(hit)));
            reportmanager.addReport(server.damageEntity(te, hit, damage));
        }
        reportmanager.addNewLines();
    }

    /**
     * Handle a club attack
     */
    private void resolveClubAttack(PhysicalResult pr, int lastEntityId) {
        final ClubAttackAction caa = (ClubAttackAction) pr.aaa;
        final Entity ae = game.getEntity(caa.getEntityId());
        // get damage, ToHitData and roll from the PhysicalResult
        int damage = pr.damage;
        // LAMs in airmech mode do half damage if airborne.
        if (ae.isAirborneVTOLorWIGE()) {
            damage = (int)Math.ceil(damage * 0.5);
        }
        final ToHitData toHit = pr.toHit;
        int roll = pr.roll;
        final Targetable target = game.getTarget(caa.getTargetType(), caa.getTargetId());
        Entity te = null;
        if ((target != null) && target.getTargetType() == Targetable.TYPE_ENTITY) {
            te = (Entity) target;
        }
        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(game, ae.getPosition(), te);
        }
        final boolean targetInBuilding = Compute.isInBuilding(game, te);
        final boolean glancing = game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS)
                && (roll == toHit.getValue());

        // Set Margin of Success/Failure.
        // Make sure the MoS is zero for *automatic* hits in case direct blows
        // are in force.
        toHit.setMoS((roll == Integer.MAX_VALUE) ? 0 : roll - Math.max(2, toHit.getValue()));
        final boolean directBlow = game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW)
                && ((toHit.getMoS() / 3) >= 1);

        Report r;

        // Which building takes the damage?
        Building bldg = game.getBoard().getBuildingAt(target.getPosition());

        // restore club attack
        caa.getClub().restore();

        // Shield bash causes 1 point of damage to the shield
        if (((MiscType) caa.getClub().getType()).isShield()) {
            ((Mech) ae).shieldAbsorptionDamage(1, caa.getClub().getLocation(), false);
        }
        reportWhoAttacks(lastEntityId, pr.aaa, ae);

        reportmanager.addReport(ReportFactory.createReport(4145, 1, ae, caa.getClub().getName(), target.getDisplayName()));

        // Flail/Wrecking Ball auto misses on a 2 and hits themself.
        if ((caa.getClub().getType().hasSubType(MiscType.S_FLAIL)
                || caa.getClub().getType().hasSubType(MiscType.S_WRECKING_BALL))
                && (roll == 2)) {
            // miss
            reportmanager.addReport(ReportFactory.createReport(4035, ae));
            ToHitData newToHit = new ToHitData(TargetRoll.AUTOMATIC_SUCCESS, "hit with own flail/wrecking ball");
            pr.damage = ClubAttackAction.getDamageFor(ae, caa.getClub(), false, caa.isZweihandering());
            pr.damage = (pr.damage / 2) + (pr.damage % 2);
            newToHit.setHitTable(ToHitData.HIT_NORMAL);
            newToHit.setSideTable(ToHitData.SIDE_FRONT);
            pr.toHit = newToHit;
            pr.aaa.setTargetId(ae.getId());
            pr.aaa.setTargetType(Targetable.TYPE_ENTITY);
            pr.roll = Integer.MAX_VALUE;
            resolveClubAttack(pr, ae.getId());
            if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed a flail/wrecking ball attack"));
            } else {
                game.addPSR(new PilotingRollData(ae.getId(), 0, "missed a flail/wrecking ball attack"));
            }
            if(caa.isZweihandering()) {
                ArrayList<Integer> criticalLocs = new ArrayList<>();
                criticalLocs.add(caa.getClub().getLocation());
                applyZweihanderSelfDamage(ae, true, criticalLocs);
            }
            return;
        }

        // Need to compute 2d6 damage. and add +3 heat build up.
        if (caa.getClub().getType().hasSubType(MiscType.S_BUZZSAW)) {
            damage = Compute.d6(2);
            ae.heatBuildup += 3;

            // Buzzsaw's blade will shatter on a roll of 2.
            if (roll == 2) {
                Mounted club = caa.getClub();
                for (Mounted eq : ae.getWeaponList()) {
                    if ((eq.getLocation() == club.getLocation())
                            && (eq.getType() instanceof MiscType)
                            && eq.getType().hasFlag(MiscType.F_CLUB)
                            && eq.getType().hasSubType(MiscType.S_BUZZSAW)) {
                        eq.setHit(true);
                        break;
                    }
                }
                reportmanager.addReport(ReportFactory.createReport(4037, ae));
                if(caa.isZweihandering()) {
                    ArrayList<Integer> criticalLocs = new ArrayList<>();
                    criticalLocs.add(caa.getClub().getLocation());
                    applyZweihanderSelfDamage(ae, true, criticalLocs);
                }
                return;
            }
        }

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            reportmanager.addReport(ReportFactory.createReport(4075, ae, toHit.getDesc()));
            if (caa.getClub().getType().hasSubType(MiscType.S_MACE_THB) || caa.getClub().getType().hasSubType(MiscType.S_MACE)) {
                if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                    game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed a mace attack"));
                } else {
                    game.addPSR(new PilotingRollData(ae.getId(), 0, "missed a mace attack"));
                }
            }
            if(caa.isZweihandering()) {
                ArrayList<Integer> criticalLocs = new ArrayList<>();
                criticalLocs.add(caa.getClub().getLocation());
                applyZweihanderSelfDamage(ae, true, criticalLocs);
            }
            return;
        } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            reportmanager.addReport(ReportFactory.createReport(4080, ae, toHit.getDesc()));
            roll = Integer.MAX_VALUE;
        } else {
            // report the roll
            checkGlancingBlow(glancing, directBlow, ae, toHit, roll);
        }

        // do we hit?
        if (isHit(roll, toHit, ae, target, bldg, damage, targetInBuilding)){
            if (caa.getClub().getType().hasSubType(MiscType.S_MACE_THB)) {
                if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                    game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed a mace attack"));
                } else {
                    game.addPSR(new PilotingRollData(ae.getId(), 0, "missed a mace attack"));
                }
            }
            if (caa.getClub().getType().hasSubType(MiscType.S_MACE)) {
                if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                    game.addControlRoll(new PilotingRollData(ae.getId(), 2,
                            "missed a mace attack"));
                } else {
                    game.addPSR(new PilotingRollData(ae.getId(), 2,
                            "missed a mace attack"));
                }
            }

            if(caa.isZweihandering()) {
                ArrayList<Integer> criticalLocs = new ArrayList<>();
                criticalLocs.add(caa.getClub().getLocation());
                applyZweihanderSelfDamage(ae, true, criticalLocs);
            }
            return;
        }

        // Targeting a building.
        if (isTargetBuilding(target, ae, bldg, damage)){
            if(caa.isZweihandering()) {
                ArrayList<Integer> criticalLocs = new ArrayList<>();
                criticalLocs.add(caa.getClub().getLocation());
                applyZweihanderSelfDamage(ae, false, criticalLocs);
                if (caa.getClub().getType().hasSubType(MiscType.S_CLUB)) {
                    // the club breaks
                    reportmanager.addReport(ReportFactory.createReport(4150, ae, caa.getClub().getName()));
                    ae.removeMisc(caa.getClub().getName());
                }
            }
            // And we're done!
            return;
        }

        HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
        hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
        reportmanager.addReport(ReportFactory.createReport(4045, ae, toHit.getTableDesc(), te.getLocationAbbr(hit)));

        damage = shieldsUnitsBuilding(targetInBuilding, bldg, damage, target, ae);

        // A building may absorb the entire shot.
        if (damage == 0) {
            reportmanager.addReport(ReportFactory.createReport(4050, ae, te.getShortName(), te.getOwner().getName()));
        } else {
            if (glancing) {
                damage = gamemanager.applyInfrantryDamageReduction(te, damage);
            }
            if (directBlow) {
                damage += toHit.getMoS() / 3;
                hit.makeDirectBlow(toHit.getMoS() / 3);
            }

            damage = server.checkForSpikes(te, hit.getLocation(), damage, ae, Entity.LOC_NONE);

            Server.DamageType damageType = Server.DamageType.NONE;
            reportmanager.addReport(server.damageEntity(te, hit, damage, false, damageType, false,
                    false, throughFront));
            if (target instanceof VTOL) {
                // destroy rotor
                reportmanager.addReport(server.applyCriticalHit(te, VTOL.LOC_ROTOR, new CriticalSlot(CriticalSlot.TYPE_SYSTEM,
                        VTOL.CRIT_ROTOR_DESTROYED), false, 0, false));
            }
        }

        // On a roll of 10+ a lance hitting a mech/Vehicle can cause 1 point of
        // internal damage
        if (caa.getClub().getType().hasSubType(MiscType.S_LANCE)
                && (te.getArmor(hit) > 0)
                && (te.getArmorType(hit.getLocation()) != EquipmentType.T_ARMOR_HARDENED)
                && (te.getArmorType(hit.getLocation()) != EquipmentType.T_ARMOR_FERRO_LAMELLOR)) {
            roll = Compute.d6(2);
            // Pierce checking report
            r = ReportFactory.createReport(4021, 2, ae, te.getLocationAbbr(hit));
            r.add(roll);
            reportmanager.addReport(r);
            if (roll >= 10) {
                hit.makeGlancingBlow();
                reportmanager.addReport(server.damageEntity(te, hit, 1, false, Server.DamageType.NONE,
                        true, false, throughFront));
            }
        }

        // TODO : Verify this is correct according to latest rules
        if (caa.getClub().getType().hasSubType(MiscType.S_WRECKING_BALL) && (ae instanceof SupportTank) && (te instanceof Mech)) {
            // forces a PSR like a charge
            if (te instanceof LandAirMech && te.isAirborneVTOLorWIGE()) {
                game.addControlRoll(new PilotingRollData(te.getId(), 2, "was hit by wrecking ball"));
            } else {
                game.addPSR(new PilotingRollData(te.getId(), 2, "was hit by wrecking ball"));
            }
        }

        // Chain whips can entangle 'Mech and ProtoMech limbs. This
        // implementation assumes that in order to do so the limb must still
        // have some structure left, so if the whip hits and destroys a
        // location in the same attack no special effects take place.
        if (caa.getClub().getType().hasSubType(MiscType.S_CHAIN_WHIP)
                && ((te instanceof Mech) || (te instanceof Protomech))) {
            reportmanager.addNewLines();

            int loc = hit.getLocation();
            int toHitNumber = toHit.getValue();

            boolean checkte = !te.isLocationBad(loc)
                    && !te.isLocationDoomed(loc)
                    && !te.hasActiveShield(loc)
                    && !te.hasPassiveShield(loc);

            boolean mightTrip = (te instanceof Mech)
                    && te.locationIsLeg(loc)
                    && checkte;

            boolean mightGrapple = ((te instanceof Mech)
                    && ((loc == Mech.LOC_LARM) || (loc == Mech.LOC_RARM))
                    && checkte
                    && !te.hasNoDefenseShield(loc))
                    || ((te instanceof Protomech)
                    && ((loc == Protomech.LOC_LARM) || (loc == Protomech.LOC_RARM) || (loc == Protomech.LOC_LEG))
                    // Only check location status after confirming we did
                    // hit a limb -- Protos have no actual near-miss
                    // "location" and will throw an exception if it's
                    // referenced here.
                    && !te.isLocationBad(loc)
                    && !te.isLocationDoomed(loc));

            if (mightTrip) {
                roll = Compute.d6(2);

                if ((ae instanceof Mech) && (((Mech) ae).hasTSM() && (ae.heat >= 9))
                        && (!((Mech) te).hasTSM() || ((((Mech) te).hasTSM()) && (te.heat < 9)))) {
                    toHitNumber -= 2;
                }

                r = ReportFactory.createReport(4450, 2, ae, ae.getShortName(), te.getShortName());
                r.add(toHitNumber, roll);
                reportmanager.addReport(r);

                int reportID = 2357;
                if (roll >= toHit.getValue()) {
                    game.addPSR(new PilotingRollData(te.getId(), 3, "Snared by chain whip"));
                    reportID = 2270;
                }
                reportmanager.addReport(ReportFactory.createReport(reportID, ae));
            } else if (mightGrapple) {
                GrappleAttackAction gaa = new GrappleAttackAction(ae.getId(), te.getId());
                int grappleSide;
                if (caa.getClub().getLocation() == Mech.LOC_RARM) {
                    grappleSide = Entity.GRAPPLE_RIGHT;
                } else {
                    grappleSide = Entity.GRAPPLE_LEFT;
                }
                ToHitData grappleHit = GrappleAttackAction.toHit(game, ae.getId(), target,
                        grappleSide, true);
                PhysicalResult grappleResult = new PhysicalResult();
                grappleResult.aaa = gaa;
                grappleResult.toHit = grappleHit;
                grappleResult.roll = Compute.d6(2);
                resolveGrappleAttack(grappleResult, lastEntityId, grappleSide,
                        hit.getLocation() == Mech.LOC_RARM ? Entity.GRAPPLE_RIGHT : Entity.GRAPPLE_LEFT);
            }
        }

        reportmanager.addNewLines();

        if (caa.getClub().getType().hasSubType(MiscType.S_TREE_CLUB)) {
            // the club breaks
            reportmanager.addReport(ReportFactory.createReport(4150, ae, caa.getClub().getName()));
            ae.removeMisc(caa.getClub().getName());
        }

        if(caa.isZweihandering()) {
            ArrayList<Integer> criticalLocs = new ArrayList<>();
            criticalLocs.add(caa.getClub().getLocation());
            applyZweihanderSelfDamage(ae, false, criticalLocs);
            if (caa.getClub().getType().hasSubType(MiscType.S_CLUB)) {
                // the club breaks
                reportmanager.addReport(ReportFactory.createReport(4150, ae, caa.getClub().getName()));
                ae.removeMisc(caa.getClub().getName());
            }
        }

        reportmanager.addNewLines();

        // if the target is an industrial mech, it needs to check for crits
        // at the end of turn
        if ((target instanceof Mech) && ((Mech) target).isIndustrial()) {
            ((Mech) target).setCheckForCrit(true);
        }
    }

    /**
     * Handle a push attack
     */
    private void resolvePushAttack(PhysicalResult pr, int lastEntityId) {
        final PushAttackAction paa = (PushAttackAction) pr.aaa;
        final Entity ae = game.getEntity(paa.getEntityId());
        // PLEASE NOTE: buildings are *never* the target of a "push".
        final Entity te = game.getEntity(paa.getTargetId());
        // get roll and ToHitData from the PhysicalResult
        int roll = pr.roll;
        final ToHitData toHit = pr.toHit;

        // was this push resolved earlier?
        if (pr.pushBackResolved) {
            return;
        }
        // don't try this one again
        pr.pushBackResolved = true;
        reportWhoAttacks(lastEntityId, pr.aaa, ae);
        reportmanager.addReport(ReportFactory.createAttackingEntityReport(4155, ae, te));

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            reportmanager.addReport(ReportFactory.createReport(4160, ae, toHit.getDesc()));
            return;
        }

        // report the roll
        reportmanager.addReport(ReportFactory.createReport(4025, ae, toHit.getValue(), roll));

        // check if our target has a push against us, too, and get it
        PhysicalResult targetPushResult = null;
        for (PhysicalResult tpr : physicalResults) {
            if ((tpr.aaa.getEntityId() == te.getId()) && (tpr.aaa instanceof PushAttackAction)
                    && (tpr.aaa.getTargetId() == ae.getId())) {
                targetPushResult = tpr;
            }
        }
        // if our target has a push against us,
        // and we are hitting, we need to resolve both now
        if ((targetPushResult != null) && !targetPushResult.pushBackResolved && (roll >= toHit.getValue())) {
            targetPushResult.pushBackResolved = true;
            // do they hit?
            if (targetPushResult.roll >= targetPushResult.toHit.getValue()) {
                // TODO: are you guys sure that you have to add these descriptions multiple times?
                Report r = ReportFactory.createAttackingEntityReport(4165, ae, te);
                r.addDesc(te);
                r.addDesc(ae);
                r.add(targetPushResult.toHit.getValue(), targetPushResult.roll);
                r.addDesc(ae);
                reportmanager.addReport(r);
                if (ae.canFall()) {
                    PilotingRollData pushPRD = game.getKickPushPSR(ae, ae, te, "was pushed");
                    game.addPSR(pushPRD);
                } else if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                    game.addControlRoll(game.getKickPushPSR(ae, ae, te, "was pushed"));
                }
                if (te.canFall()) {
                    PilotingRollData targetPushPRD = game.getKickPushPSR(te, ae, te, "was pushed");
                    game.addPSR(targetPushPRD);
                } else if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                    game.addControlRoll(game.getKickPushPSR(te, ae, te, "was pushed"));
                }
                return;
            }
            // report the miss
            Report r = ReportFactory.createAttackingEntityReport(4166, ae, te);
            r.addDesc(ae);
            r.add(targetPushResult.toHit.getValue(), targetPushResult.roll);
            reportmanager.addReport(r);
        }

        // do we hit?
        if (roll < toHit.getValue()) {
            // miss
            reportmanager.addReport(ReportFactory.createReport(4035, ae));
            return;
        }

        // we hit...
        int direction = ae.getFacing();

        Coords src = te.getPosition();
        Coords dest = src.translated(direction);

        PilotingRollData pushPRD = game.getKickPushPSR(te, ae, te, "was pushed");

        if (Compute.isValidDisplacement(game, te.getId(), te.getPosition(), direction)) {
            reportmanager.addReport(ReportFactory.createReport(4170, ae));
            if (game.getBoard().contains(dest)) {
                reportmanager.addReport(ReportFactory.createReport(4175, ae, dest.getBoardNum()));
            } else {
                // uh-oh, pushed off board
                reportmanager.addReport(ReportFactory.createReport(4180, ae));
            }
            reportmanager.addReport(server.doEntityDisplacement(te, src, dest, pushPRD));

            // if push actually moved the target, attacker follows through
            if (!te.getPosition().equals(src)) {
                ae.setPosition(src);
            }
        } else {
            // target immovable
            reportmanager.addReport(ReportFactory.createReport(4185, ae));
            if (te.canFall()) {
                game.addPSR(pushPRD);
            }
        }

        // if the target is an industrial mech, it needs to check for crits
        // at the end of turn
        if ((te instanceof Mech) && ((Mech) te).isIndustrial()) {
            ((Mech) te).setCheckForCrit(true);
        }

        server.checkForSpikes(te, ae.rollHitLocation(ToHitData.HIT_PUNCH, Compute.targetSideTable(ae, te)).getLocation(),
                0, ae, Mech.LOC_LARM, Mech.LOC_RARM);

        reportmanager.addNewLines();
    }

    /**
     * Handle a trip attack
     */
    private void resolveTripAttack(PhysicalResult pr, int lastEntityId) {
        final TripAttackAction paa = (TripAttackAction) pr.aaa;
        final Entity ae = game.getEntity(paa.getEntityId());
        // PLEASE NOTE: buildings are *never* the target of a "trip".
        final Entity te = game.getEntity(paa.getTargetId());
        // get roll and ToHitData from the PhysicalResult
        int roll = pr.roll;
        final ToHitData toHit = pr.toHit;

        reportWhoAttacks(lastEntityId, pr.aaa, ae);
        reportmanager.addReport(ReportFactory.createAttackingEntityReport(4280, ae, te));

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            reportmanager.addReport(ReportFactory.createReport(4285, ae, toHit.getDesc()));
            return;
        }

        // report the roll
        reportmanager.addReport(ReportFactory.createReport(4025, ae, toHit.getValue(), roll));

        // do we hit?
        if (roll < toHit.getValue()) {
            // miss
            reportmanager.addReport(ReportFactory.createReport(4035, ae));
            return;
        }

        // we hit...
        if (te.canFall()) {
            PilotingRollData pushPRD = game.getKickPushPSR(te, ae, te, "was tripped");
            game.addPSR(pushPRD);
        }

        reportmanager.addReport(ReportFactory.createReport(4040, ae));
        reportmanager.addNewLines();
        // if the target is an industrial mech, it needs to check for crits
        // at the end of turn
        if ((te instanceof Mech) && ((Mech) te).isIndustrial()) {
            ((Mech) te).setCheckForCrit(true);
        }
    }

    /**
     * Handle a grapple attack
     */
    private void resolveGrappleAttack(PhysicalResult pr, int lastEntityId) {
        resolveGrappleAttack(pr, lastEntityId, Entity.GRAPPLE_BOTH, Entity.GRAPPLE_BOTH);
    }

    /**
     * Resolves a grapple attack.
     *
     * @param pr            the result of a physical attack - this one specifically being a grapple
     * @param lastEntityId  the entity making the attack
     * @param aeGrappleSide
     *            The side that the attacker is grappling with. For normal
     *            grapples this will be both, for chain whip grapples this will
     *            be the arm with the chain whip in it.
     * @param teGrappleSide
     *            The that the the target is grappling with. For normal grapples
     *            this will be both, for chain whip grapples this will be the
     *            arm that is being whipped.
     */
    private void resolveGrappleAttack(PhysicalResult pr, int lastEntityId, int aeGrappleSide, int teGrappleSide) {
        final GrappleAttackAction paa = (GrappleAttackAction) pr.aaa;
        final Entity ae = game.getEntity(paa.getEntityId());
        // PLEASE NOTE: buildings are *never* the target of a "push".
        final Entity te = game.getEntity(paa.getTargetId());
        // get roll and ToHitData from the PhysicalResult
        int roll = pr.roll;
        final ToHitData toHit = pr.toHit;
        // same method as push, for counterattacks
        if (pr.pushBackResolved) {
            return;
        }

        if ((te.getGrappled() != Entity.NONE) || (ae.getGrappled() != Entity.NONE)) {
            toHit.addModifier(TargetRoll.IMPOSSIBLE, "Already Grappled");
        }
        reportWhoAttacks(lastEntityId, pr.aaa, ae);
        reportmanager.addReport(ReportFactory.createAttackingEntityReport(4280, ae, te));

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            reportmanager.addReport(ReportFactory.createReport(4285, ae, toHit.getDesc()));
            return;
        }

        // report the roll
        reportmanager.addReport(ReportFactory.createReport(4025, ae, toHit.getValue(), roll));

        // do we hit?
        if (roll < toHit.getValue()) {
            // miss
            reportmanager.addReport(ReportFactory.createReport(4035, ae));
            return;
        }

        // we hit...
        ae.setGrappled(te.getId(), true);
        te.setGrappled(ae.getId(), false);
        ae.setGrappledThisRound(true);
        te.setGrappledThisRound(true);
        // For normal grapples, AE moves into targets hex.
        if (aeGrappleSide == Entity.GRAPPLE_BOTH) {
            Coords pos = te.getPosition();
            ae.setPosition(pos);
            ae.setElevation(te.getElevation());
            te.setFacing((ae.getFacing() + 3) % 6);
            reportmanager.addReport(server.doSetLocationsExposure(ae, game.getBoard().getHex(pos), false, ae.getElevation()));
        }

        ae.setGrappleSide(aeGrappleSide);
        te.setGrappleSide(teGrappleSide);

        reportmanager.addReport(ReportFactory.createReport(4040, ae));
        reportmanager.addNewLines();

        // if the target is an industrial mech, it needs to check for crits
        // at the end of turn
        if ((te instanceof Mech) && ((Mech) te).isIndustrial()) {
            ((Mech) te).setCheckForCrit(true);
        }
    }

    /**
     * Handle a break grapple attack
     */
    private void resolveBreakGrappleAttack(PhysicalResult pr, int lastEntityId) {
        final BreakGrappleAttackAction paa = (BreakGrappleAttackAction) pr.aaa;
        final Entity ae = game.getEntity(paa.getEntityId());
        // PLEASE NOTE: buildings are *never* the target of a "push".
        final Entity te = game.getEntity(paa.getTargetId());
        // get roll and ToHitData from the PhysicalResult
        int roll = pr.roll;
        final ToHitData toHit = pr.toHit;
        reportWhoAttacks(lastEntityId, pr.aaa, ae);
        reportmanager.addReport(ReportFactory.createAttackingEntityReport(4305, ae, te));

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            reportmanager.addReport(ReportFactory.createReport(4310, ae, toHit.getDesc()));
            if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed a physical attack"));
            }
            return;
        }

        if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            reportmanager.addReport(ReportFactory.createReport(4320, ae, toHit.getDesc()));
        } else {
            // report the roll
            reportmanager.addReport(ReportFactory.createReport(4025, ae, toHit.getValue(), roll));

            // do we hit?
            if (roll < toHit.getValue()) {
                // miss
                reportmanager.addReport(ReportFactory.createReport(4035, ae));
                if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                    game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed a physical attack"));
                }
                return;
            }

            // hit
            reportmanager.addReport(ReportFactory.createReport(4040, ae));
        }

        // is there a counterattack?
        PhysicalResult targetGrappleResult = null;
        for (PhysicalResult tpr : physicalResults) {
            if ((tpr.aaa.getEntityId() == te.getId())
                    && (tpr.aaa instanceof GrappleAttackAction)
                    && (tpr.aaa.getTargetId() == ae.getId())) {
                targetGrappleResult = tpr;
                break;
            }
        }

        if (targetGrappleResult != null) {
            targetGrappleResult.pushBackResolved = true;
            // counterattack
            reportmanager.addReport(ReportFactory.createReport(4315, te));

            // report the roll
            reportmanager.addReport(ReportFactory.createReport(4025, te, targetGrappleResult.toHit.getValue(), targetGrappleResult.roll));

            // do we hit?
            if (roll < toHit.getValue()) {
                // miss
                reportmanager.addReport(ReportFactory.createReport(4035, ae));
            } else {
                // hit
                reportmanager.addReport(ReportFactory.createReport(4040, ae));

                // exchange attacker and defender
                ae.setGrappled(te.getId(), false);
                te.setGrappled(ae.getId(), true);

                return;
            }
        }

        scoreAdjacentHexes(ae, te);

        // grapple is broken
        ae.setGrappled(Entity.NONE, false);
        te.setGrappled(Entity.NONE, false);

        reportmanager.addNewLines();
    }

    /**
     * Handle a charge attack
     */
    private void resolveChargeAttack(PhysicalResult pr, int lastEntityId) {
        final ChargeAttackAction caa = (ChargeAttackAction) pr.aaa;
        final Entity ae = game.getEntity(caa.getEntityId());
        final Targetable target = game.getTarget(caa.getTargetType(), caa.getTargetId());
        // get damage, ToHitData and roll from the PhysicalResult
        int damage = pr.damage;
        final ToHitData toHit = pr.toHit;
        int roll = pr.roll;

        Entity te = null;
        if ((target != null) && (target.getTargetType() == Targetable.TYPE_ENTITY)) {
            te = (Entity) target;
        }
        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(game, ae.getPosition(), te);
        }
        final boolean glancing = game.getOptions().booleanOption(
                OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS) && (roll == toHit.getValue());

        // Set Margin of Success/Failure.
        toHit.setMoS(roll - Math.max(2, toHit.getValue()));
        final boolean directBlow = game.getOptions().booleanOption(
                OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW) && ((toHit.getMoS() / 3) >= 1);

        // Which building takes the damage?
        Building bldg = game.getBoard().getBuildingAt(caa.getTargetPos());

        // is the attacker dead? because that sure messes up the calculations
        if (ae == null) {
            return;
        }

        final int direction = ae.getFacing();

        // entity isn't charging any more
        ae.setDisplacementAttack(null);
        reportWhoAttacks(lastEntityId, pr.aaa, ae);

        // should we even bother?
        if (!doBother(target, te, ae)){
            return;
        }

        // attacker fell down?
        if (ae.isProne()) {
            reportmanager.addReport(ReportFactory.createReport(4190, 1, ae));
            return;
        }

        // attacker immobile?
        if (ae.isImmobile()) {
            reportmanager.addReport(ReportFactory.createReport(4200, 1, ae));
            return;
        }

        // target fell down, only for attacking Mechs, though
        if ((te != null) && (te.isProne()) && (ae instanceof Mech)) {
            reportmanager.addReport(ReportFactory.createReport(4205, 1, ae));
            return;
        }
        reportmanager.addReport(ReportFactory.createReport(4210, 1, ae, target.getDisplayName()));

        // target still in the same position?
        if (!target.getPosition().equals(caa.getTargetPos())) {
            reportmanager.addReport(ReportFactory.createReport(4215, ae));
            reportmanager.addReport(server.doEntityDisplacement(ae, ae.getPosition(), caa.getTargetPos(), null));
            return;
        }

        // if the attacker's prone, fudge the roll
        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            roll = -12;
            reportmanager.addReport(ReportFactory.createReport(4220, ae, toHit.getDesc()));
        } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            reportmanager.addReport(ReportFactory.createReport(4225, ae, toHit.getDesc()));
        } else {
            // report the roll
            checkGlancingBlow(glancing, directBlow, ae, toHit, roll);
        }

        // do we hit?
        if (roll < toHit.getValue()) {
            Coords src = ae.getPosition();
            Coords dest = Compute.getMissedChargeDisplacement(game, ae.getId(), src, direction);

            // TODO : handle movement into/out of/through a building. Do it here?

            // miss
            reportmanager.addReport(ReportFactory.createReport(4035, ae));
            // move attacker to side hex
            reportmanager.addReport(server.doEntityDisplacement(ae, src, dest, null));
        } else if ((target.getTargetType() == Targetable.TYPE_BUILDING)
                || (target.getTargetType() == Targetable.TYPE_FUEL_TANK)) { // Targeting a building.
            targetBuilding(target, ae, bldg, damage);

            // Apply damage to the attacker.
            int toAttacker = ChargeAttackAction.getDamageTakenBy(ae, bldg, target.getPosition());
            HitData hit = ae.rollHitLocation(ToHitData.HIT_NORMAL, ae.sideTable(target.getPosition()));
            hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
            reportmanager.addReport(server.damageEntity(ae, hit, toAttacker, false, Server.DamageType.NONE,
                    false, false, throughFront));
            reportmanager.addNewLines();
            entityManager.entityUpdate(ae.getId());

            // TODO : Does the attacker enter the building?
            // TODO : What if the building collapses?
        } else {
            // Resolve the damage.
            server.resolveChargeDamage(ae, te, toHit, direction, glancing, throughFront, false);
        }
    }

    /**
     * Handle an Airmech ram attack
     */
    private void resolveAirmechRamAttack(PhysicalResult pr, int lastEntityId) {
        final AirmechRamAttackAction caa = (AirmechRamAttackAction) pr.aaa;
        final Entity ae = game.getEntity(caa.getEntityId());
        final Targetable target = game.getTarget(caa.getTargetType(), caa.getTargetId());
        // get damage, ToHitData and roll from the PhysicalResult
        int damage = pr.damage;
        final ToHitData toHit = pr.toHit;
        int roll = pr.roll;

        Entity te = null;
        if ((target != null) && (target.getTargetType() == Targetable.TYPE_ENTITY)) {
            te = (Entity) target;
        }
        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(game, ae.getPosition(), te);
        }
        final boolean glancing = game.getOptions().booleanOption(
                OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS) && (roll == toHit.getValue());

        // Set Margin of Success/Failure.
        toHit.setMoS(roll - Math.max(2, toHit.getValue()));
        final boolean directBlow = game.getOptions().booleanOption(
                OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW) && ((toHit.getMoS() / 3) >= 1);

        // Which building takes the damage?
        Building bldg = game.getBoard().getBuildingAt(caa.getTargetPos());

        // is the attacker dead? because that sure messes up the calculations
        if (ae == null) {
            return;
        }

        final int direction = ae.getFacing();

        // entity isn't charging any more
        ae.setDisplacementAttack(null);
        reportWhoAttacks(lastEntityId, pr.aaa, ae);

        // should we even bother?
        if (!doBother(target, te, ae, 4192)){
            game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed a ramming attack"));
            return;
        }

        // attacker landed?
        if (!ae.isAirborneVTOLorWIGE()) {
            reportmanager.addReport(ReportFactory.createReport(4197, 1, ae));
            return;
        }

        // attacker immobile?
        if (ae.isImmobile()) {
            reportmanager.addReport(ReportFactory.createReport(4202, 1, ae));
            return;
        }
        reportmanager.addReport(ReportFactory.createReport(4212, 1, ae, target.getDisplayName()));

        // if the attacker's prone, fudge the roll
        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            roll = -12;
            reportmanager.addReport(ReportFactory.createReport(4222, ae, toHit.getDesc()));
        } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            roll = Integer.MAX_VALUE;
            reportmanager.addReport(ReportFactory.createReport(4227, ae, toHit.getDesc()));
        } else {
            // report the roll
            checkGlancingBlow(glancing, directBlow, ae, toHit, roll);
        }

        // do we hit?
        if (roll < toHit.getValue()) {
            // miss
            reportmanager.addReport(ReportFactory.createReport(4035, ae));
            // attacker must make a control roll
            game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed ramming attack"));
        } else if ((target.getTargetType() == Targetable.TYPE_BUILDING)
                || (target.getTargetType() == Targetable.TYPE_FUEL_TANK)) { // Targeting a building.
            targetBuilding(target, ae, bldg, damage);

            // Apply damage to the attacker.
            int toAttacker = AirmechRamAttackAction.getDamageTakenBy(ae, target, ae.delta_distance);
            HitData hit = new HitData(Mech.LOC_CT);
            hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
            reportmanager.addReport(server.damageEntity(ae, hit, toAttacker, false, Server.DamageType.NONE,
                    false, false, throughFront));
            reportmanager.addNewLines();
            entityManager.entityUpdate(ae.getId());

            // TODO : Does the attacker enter the building?
            // TODO : What if the building collapses?
        } else {
            // Resolve the damage.
            server.resolveChargeDamage(ae, te, toHit, direction, glancing, throughFront, true);
        }
    }

    /**
     * Handle a telemissile attack
     */
    private void resolveTeleMissileAttack(PhysicalResult pr, int lastEntityId) {
        final TeleMissileAttackAction taa = (TeleMissileAttackAction) pr.aaa;
        final Entity ae = game.getEntity(taa.getEntityId());
        if (!(ae instanceof TeleMissile)) {
            return;
        }
        TeleMissile tm = (TeleMissile) ae;
        final Targetable target = game.getTarget(taa.getTargetType(), taa.getTargetId());
        final ToHitData toHit = pr.toHit;
        int roll = pr.roll;
        int amsDamage = taa.CounterAVInt;
        Entity te = null;
        if ((target != null) && (target.getTargetType() == Targetable.TYPE_ENTITY)) {
            te = (Entity) target;
        }

        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(game, ae.getPosition(), te);
        }

        Report r;
        reportWhoAttacks(lastEntityId, pr.aaa, ae);

        // should we even bother?
        if (!doBother(target, te, ae, 4191)) {
            return;
        }
        reportmanager.addReport(ReportFactory.createReport(9031, 1, ae, target.getDisplayName()));

        //If point defenses engaged the missile, handle that damage
        if (amsDamage > 0) {
            //Report the attack
            reportmanager.addReport(ReportFactory.createReport(3362, te));

            //If the target's point defenses overheated, report that
            if (taa.getPDOverheated()) {
                reportmanager.addReport(ReportFactory.createReport(3361, te));
            }

            //Damage the missile
            HitData hit = tm.rollHitLocation(ToHitData.HIT_NORMAL, tm.sideTable(te.getPosition(), true));
            reportmanager.addReport(server.damageEntity(ae, hit, amsDamage, false,
                    Server.DamageType.NONE, false, false, false));

            //If point defense fire destroys the missile, don't process a hit
            if (ae.isDoomed()) {
                return;
            }
        }

        // add some stuff to the to hit value
        // need to add damage done modifier
        int damageTaken = (ae.getOArmor(TeleMissile.LOC_BODY) - ae.getArmor(TeleMissile.LOC_BODY));
        if (damageTaken > 10) {
            toHit.addModifier((int) (Math.floor(damageTaken / 10.0)), "damage taken");
        }

        // add modifiers for the originating unit missing CIC, FCS, or sensors
        Entity ride = game.getEntity(tm.getOriginalRideId());
        if (ride instanceof Aero) {
            Aero aride = (Aero) ride;
            int cic = aride.getCICHits();
            if (cic > 0) {
                toHit.addModifier(cic * 2, "CIC damage");
            }

            // sensor hits
            int sensors = aride.getSensorHits();
            if ((sensors > 0) && (sensors < 3)) {
                toHit.addModifier(sensors, "sensor damage");
            }
            if (sensors > 2) {
                toHit.addModifier(+5, "sensors destroyed");
            }

            // FCS hits
            int fcs = aride.getFCSHits();
            if (fcs > 0) {
                toHit.addModifier(fcs * 2, "fcs damage");
            }
        }

        if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            roll = Integer.MAX_VALUE;
            r = ReportFactory.createReport(4226, ae, toHit.getDesc());
        } else {
            // report the roll
            r = ReportFactory.createReport(9033, ae, toHit.getValue());
            r.add(toHit.getValue());
            r.add(roll);
        }
        reportmanager.addReport(r);

        // do we hit?
        if (roll < toHit.getValue()) {
            // miss
            reportmanager.addReport(ReportFactory.createReport(4035, ae));
        } else {
            // Resolve the damage.
            HitData hit = te.rollHitLocation(ToHitData.HIT_NORMAL, te.sideTable(ae.getPosition(), true));
            hit.setCapital(true);
            hit.setCapMisCritMod(tm.getCritMod());
            reportmanager.addReport(server.damageEntity(te, hit,
                    TeleMissileAttackAction.getDamageFor(ae), false,
                    Server.DamageType.NONE, false, false, throughFront));
            entityManager.destroyEntity(ae, "successful attack");
        }
    }

    /**
     * Handle a ramming attack
     */
    private void resolveRamAttack(PhysicalResult pr, int lastEntityId) {
        final RamAttackAction raa = (RamAttackAction) pr.aaa;
        final Entity ae = game.getEntity(raa.getEntityId());
        final Targetable target = game.getTarget(raa.getTargetType(), raa.getTargetId());
        final ToHitData toHit = pr.toHit;
        int roll = pr.roll;
        Entity te = null;
        if ((target != null) && (target.getTargetType() == Targetable.TYPE_ENTITY)) {
            te = (Entity) target;
        }

        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(game, ae.getPosition(), te);
        }

        Report r;

        boolean glancing = Compute.d6(1) == 6;

        // entity isn't ramming anymore
        ae.setRamming(false);

        reportWhoAttacks(lastEntityId, pr.aaa, te);

        // should we even bother?
        if (!doBother(target, te, ae)){
            return;
        }

        // steel yourself for attack
        int steelRoll = Compute.d6(2);
        r = ReportFactory.createReport(9020, ae, steelRoll);
        r.choose(steelRoll >= 11);
        reportmanager.addReport(r);

        // attacker immobile?
        if (ae.isImmobile()) {
            reportmanager.addReport(ReportFactory.createReport(4200, 1, ae));
            return;
        }

        reportmanager.addReport(ReportFactory.createReport(9030, 1, ae, target.getDisplayName()));

        if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            roll = Integer.MAX_VALUE;
            reportmanager.addReport(ReportFactory.createReport(4225, ae, toHit.getDesc()));
        } else {
            // report the roll
            reportmanager.addReport(ReportFactory.createReport(4025, ae, toHit.getValue(), roll));
        }

        // do we hit?
        if (roll < toHit.getValue()) {
            // miss
            reportmanager.addReport(ReportFactory.createReport(4035, ae));
        } else {
            // Resolve the damage.
            server.resolveRamDamage((IAero) ae, te, toHit, glancing, throughFront);
        }
    }

    private void checkGlancingBlow(boolean glancing, boolean directBlow, Entity ae, ToHitData toHit, int roll) {
        reportmanager.addReport(ReportFactory.createReport(4025, ae, toHit.getValue(), roll));
        if (glancing) {
            reportmanager.addReport(ReportFactory.createReport(3186, ae));
        }
        if (directBlow) {
            reportmanager.addReport(ReportFactory.createReport(3189, ae));
        }
    }

    private void reportTaserRoll(Entity te, int taserRoll, int taserCap, int turns, boolean heat) {
        if (taserRoll >= taserCap) {
            reportmanager.addReport(ReportFactory.createReport(3705, te, turns));
            te.taserShutdown(turns, false);
        } else {
            reportmanager.addReport(ReportFactory.createReport(3710, te, 2, turns));
            te.setTaserInterference(2, turns, heat);
        }
    }

    /**
     * Apply damage to mech for zweihandering (melee attack with both hands) as per
     * pg. 82, CamOps
     *
     * @param ae           - the attacking entity
     * @param missed       - did the attack missed. If so PSR is necessary.
     * @param criticalLocs - the locations for possible criticals, should be one or
     *                     both arms depending on if it was an unarmed attack (both
     *                     arms) or a weapon attack (the arm with the weapon).
     */
    private void applyZweihanderSelfDamage(Entity ae, boolean missed, List<Integer> criticalLocs) {
        reportmanager.addReport(ReportFactory.createReport(4022, 1, ae));
        for (Integer loc : criticalLocs) {
            reportmanager.addReport(server.criticalEntity(ae, loc, false, 0, 1));
        }
        if(missed) {
            game.addPSR(new PilotingRollData(ae.getId(), 0, "Zweihander miss"));
        }
    }

    /**
     * Checks which entity is attacking and simply add a report to the reportmanager
     *
     * @param lastEntityID - the last entity that attacked
     * @param aaa - the physcial attack action
     * @param e - the entity that is attacking
     */
    private void reportWhoAttacks(int lastEntityID, AbstractAttackAction aaa, Entity e){
        if (lastEntityID != aaa.getEntityId()){
            reportmanager.addReport(ReportFactory.createReport(4005, e));
        }
    }

    /**
     * checks if the target is null or if the target is an entity and if the entity is destroyed, doomed or dead
     * if so, add a report to the reportmanager and return false
     * @param target - the target
     * @param te - the target entity
     * @param ae - the attacking entity
     * @return - true if the target is not null and if the target is an entity and if the entity is not destroyed, doomed or dead
     */
    private boolean doBother(Targetable target, Entity te, Entity ae){
        return doBother(target, te, ae, 4190);
    }

    /**
     * checks if the target is null or if the target is an entity and if the entity is destroyed, doomed or dead
     * if so, add a report to the reportmanager and return false
     * @param target - the target
     * @param te - the target entity
     * @param ae - the attacking entity
     * @param reportID - the reportID to add to the reportmanager
     * @return - true if the target is not null and if the target is an entity and if the entity is not destroyed, doomed or dead
     */
    private boolean doBother(Targetable target, Entity te, Entity ae, int reportID){
        if ((target == null)
                || ((target.getTargetType() == Targetable.TYPE_ENTITY) && (te.isDestroyed()
                || te.isDoomed() || te.getCrew().isDead()))) {
            reportmanager.addReport(ReportFactory.createReport(reportID, 1, ae));
            return false;
        }
        return true;
    }

    /**
     * If the target is a building, the building takes the full brunt of the attack.
     * we add the correct reports to the reportmanager
     *
     * @param target - the target
     * @param ae - the attacking entity
     * @param bldg - the building
     * @param damage - the damage
     */
    private void targetBuilding(Targetable target, Entity ae, Building bldg, int damage){
        // The building takes the full brunt of the attack.
        reportmanager.addReport(ReportFactory.createReport(4040, ae));
        Vector<Report> buildingReport = server.damageBuilding(bldg, damage, target.getPosition());
        for (Report report : buildingReport) {
            report.subject = ae.getId();
        }
        reportmanager.addReport(buildingReport);

        // Damage any infantry in the hex.
        reportmanager.addReport(server.damageInfantryIn(bldg, damage, target.getPosition()));
    }

    /**
     * checks if the target is a building, if so, the targetBuilding method is called to apply the damage to the building
     * @param target - the target
     * @param ae - the attacking entity
     * @param bldg - the building
     * @param damage - the damage
     * @return - true if the target is a building, false otherwise
     */
    private boolean isTargetBuilding(Targetable target, Entity ae, Building bldg, int damage){
        if ((target.getTargetType() == Targetable.TYPE_BUILDING)
                || (target.getTargetType() == Targetable.TYPE_FUEL_TANK)) {
            targetBuilding(target, ae, bldg, damage);
            return true;
        }
        return false;
    }

    /**
     * checks if the given roll is a hit or not
     * @param roll - the roll
     * @param toHit - the toHitData
     * @param ae - the attacking entity
     * @param target - the target
     * @param bldg - the building
     * @param damage - the damage
     * @param targetInBuilding - is the target in a building
     * @return - true if the roll is a hit, false otherwise
     */
    private boolean isHit(int roll, ToHitData toHit, Entity ae, Targetable target, Building bldg, int damage, boolean targetInBuilding){
        if (roll < toHit.getValue()) {
            // nope
            reportmanager.addReport(ReportFactory.createReport(4035, ae));

            // If the target is in a building, the building absorbs the damage.
            if (targetInBuilding && (bldg != null)) {

                // Only report if damage was done to the building.
                if (damage > 0) {
                    Vector<Report> buildingReport = server.damageBuilding(bldg, damage, target.getPosition());
                    for (Report report : buildingReport) {
                        report.subject = ae.getId();
                    }
                    reportmanager.addReport(buildingReport);
                }
            }
            return false;
        }
        return false;
    }

    /**
     * I'm going to be honest with you, I have no idea what this method does
     * @param ae - the attacking entity
     * @param te - the target entity
     */
    private void scoreAdjacentHexes(Entity ae, Entity te){
        // score the adjacent hexes
        Coords[] hexes = new Coords[6];
        int[] scores = new int[6];

        IHex curHex = game.getBoard().getHex(ae.getPosition());
        for (int i = 0; i < 6; i++) {
            hexes[i] = ae.getPosition().translated(i);
            scores[i] = 0;
            IHex hex = game.getBoard().getHex(hexes[i]);
            if (hex.containsTerrain(Terrains.MAGMA)) {
                scores[i] += 10;
            }
            if (hex.containsTerrain(Terrains.WATER)) {
                scores[i] += hex.terrainLevel(Terrains.WATER);
            }
            if ((curHex.surface() - hex.surface()) >= 2) {
                scores[i] += 2 * (curHex.surface() - hex.surface());
            }
        }

        int bestScore = 99999;
        int best = 0;
        int worstScore = -99999;
        int worst = 0;

        for (int i = 0; i < 6; i++) {
            if (bestScore > scores[i]) {
                best = i;
                bestScore = scores[i];
            }
            if (worstScore < scores[i]) {
                worst = i;
                worstScore = scores[i];
            }
        }

        // attacker doesn't fall, unless off a cliff
        if (ae.isGrappleAttacker()) {
            // move self to least dangerous hex
            PilotingRollData psr = ae.getBasePilotingRoll();
            psr.addModifier(TargetRoll.AUTOMATIC_SUCCESS, "break grapple");
            reportmanager.addReport(server.doEntityDisplacement(ae, ae.getPosition(), hexes[best], psr));
            ae.setFacing(hexes[best].direction(te.getPosition()));
        } else {
            // move enemy to most dangerous hex
            PilotingRollData psr = te.getBasePilotingRoll();
            psr.addModifier(TargetRoll.AUTOMATIC_SUCCESS, "break grapple");
            reportmanager.addReport(server.doEntityDisplacement(te, te.getPosition(), hexes[worst], psr));
            te.setFacing(hexes[worst].direction(ae.getPosition()));
        }
    }
}
