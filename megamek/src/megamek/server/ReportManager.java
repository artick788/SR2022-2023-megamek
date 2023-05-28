package megamek.server;

import megamek.MegaMek;
import megamek.common.*;
import megamek.common.options.OptionsConstants;

import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;

public class ReportManager {
    private Vector<Report> vPhaseReport = new Vector<>();

    public Vector<Report> getvPhaseReport() {
        return vPhaseReport;
    }

    /**
     * Add a whole lotta Reports to the players report queues as well as the
     * Master report queue vPhaseReport.
     */
    public void addReport(Vector<Report> reports) {
        vPhaseReport.addAll(reports);
    }

    /**
     * Add a whole lotta Reports to the players report queues as well as the
     * Master report queue vPhaseReport, indenting each report by the passed
     * value.
     */
    public void addReport(Vector<Report> reports, int indents) {
        for (Report r : reports) {
            r.indent(indents);
            vPhaseReport.add(r);
        }
    }

    /**
     * Add a single report to the report queue of all players and the master
     * vPhaseReport queue
     */
    public void addReport(Report report) {
        vPhaseReport.addElement(report);
    }

    /**
     * New Round has started clear everyone's report queue
     */
    public void clearReports() {
        vPhaseReport.removeAllElements();
    }

    /**
     * make sure all the new lines that were added to the old vPhaseReport get
     * added to all of the players filters
     */
    public void addNewLines() {
        Report.addNewline(vPhaseReport);
    }

    /**
     * Generates a detailed report for campaign use
     */
    public String getDetailedVictoryReport(IGame game) {
        StringBuilder sb = new StringBuilder();

        Vector<Entity> vAllUnits = new Vector<>();
        for (Iterator<Entity> i = game.getEntities(); i.hasNext(); ) {
            vAllUnits.addElement(i.next());
        }

        for (Enumeration<Entity> i = game.getRetreatedEntities(); i.hasMoreElements(); ) {
            vAllUnits.addElement(i.nextElement());
        }

        for (Enumeration<Entity> i = game.getGraveyardEntities(); i.hasMoreElements(); ) {
            vAllUnits.addElement(i.nextElement());
        }

        for (Enumeration<IPlayer> i = game.getPlayers(); i.hasMoreElements(); ) {
            // Record the player.
            IPlayer p = i.nextElement();
            sb.append("++++++++++ ").append(p.getName()).append(" ++++++++++");
            sb.append(CommonConstants.NL);

            // Record the player's alive, retreated, or salvageable units.
            for (int x = 0; x < vAllUnits.size(); x++) {
                Entity e = vAllUnits.elementAt(x);
                if (e.getOwner() == p) {
                    sb.append(UnitStatusFormatter.format(e));
                }
            }

            // Record the player's devastated units.
            Enumeration<Entity> devastated = game.getDevastatedEntities();
            if (devastated.hasMoreElements()) {
                sb.append("=============================================================");
                sb.append(CommonConstants.NL);
                sb.append("The following utterly destroyed units are not available for salvage:");
                sb.append(CommonConstants.NL);
                while (devastated.hasMoreElements()) {
                    Entity e = devastated.nextElement();
                    if (e.getOwner() == p) {
                        sb.append(e.getShortName());
                        for (int pos = 0; pos < e.getCrew().getSlotCount(); pos++) {
                            sb.append(", ").append(e.getCrew().getNameAndRole(pos)).append(" (")
                                    .append(e.getCrew().getGunnery()).append('/')
                                    .append(e.getCrew().getPiloting()).append(')');
                            sb.append(CommonConstants.NL);
                        }
                    }
                } // Handle the next non-salvageable unit for the player
                sb.append("=============================================================");
                sb.append(CommonConstants.NL);
            }

        } // Handle the next player

        return sb.toString();
    }

    /**
     * Filter a single report so that the correct double-blind obscuration takes
     * place. To mark a message as "this should be visible to anyone seeing this
     * entity" set r.subject to the entity id to mark a message as "only visible
     * to the player" set r.player to that player's id and set r.type to
     * Report.PLAYER to mark a message as visible to all, set r.type to
     * Report.PUBLIC
     *
     * @param r         the Report to filter
     * @param p         the Player that we are going to send the filtered report to
     * @param omitCheck boolean indicating that this report happened in the past, so we
     *                  no longer have access to the Player
     * @return a new Report, which has possibly been obscured
     */
    public Report filterReport(IGame game, Report r, IPlayer p, boolean omitCheck) {
        if ((r.subject == Entity.NONE) && (r.type != Report.PLAYER) && (r.type != Report.PUBLIC)) {
            // Reports that don't have a subject should be public.
            MegaMek.getLogger().error("Attempting to filter a Report object that is not public yet "
                    + "but has no subject.\n\t\tmessageId: " + r.messageId);
            return r;
        }
        if ((r.type == Report.PUBLIC) || ((p == null) && !omitCheck)) {
            return r;
        }
        Entity entity = game.getEntity(r.subject);
        if (entity == null) {
            entity = game.getOutOfGameEntity(r.subject);
        }
        IPlayer owner = null;
        if (entity != null) {
            owner = entity.getOwner();
            // off board (Artillery) units get treated as public messages
            if (entity.isOffBoard()) {
                return r;
            }
        }

        if ((r.type != Report.PLAYER) && !omitCheck
                && ((entity == null) || (owner == null))) {
            MegaMek.getLogger().error("Attempting to filter a report object that is not public but has a subject ("
                    + entity + ") with owner (" + owner + ").\n\tmessageId: " + r.messageId);
            return r;
        }

        boolean shouldObscure = omitCheck
                || ((entity != null) && !entity.hasSeenEntity(p))
                || ((r.type == Report.PLAYER) && (p.getId() != r.player));
        // If suppressing double blind messages, don't send this report at all.
        if (game.getOptions()
                .booleanOption(OptionsConstants.ADVANCED_SUPRESS_ALL_DB_MESSAGES)
                && shouldObscure) {
            // Mark the original report to indicate it was filtered
            if (p != null) {
                r.addObscuredRecipient(p.getName());
            }
            return null;
        }
        Report copy = new Report(r);
        // Otherwise, obscure data in the report
        for (int j = 0; j < copy.dataCount(); j++) {
            if (shouldObscure) {
                // This report should be obscured
                if (r.isValueObscured(j)) {
                    copy.hideData(j);
                    // Mark the original report to indicate which players
                    // received an obscured version of it.
                    if (p != null) {
                        r.addObscuredRecipient(p.getName());
                    }
                }
            }
        }
        return copy;
    }

    /**
     * Filter a report vector for double blind.
     *
     * @param originalReportVector the original <code>Vector<Report></code>
     * @param p                    the <code>Player</code> who should see stuff only visible to
     *                             him
     * @return the <code>Vector<Report></code> with stuff only Player p can see
     */
    public Vector<Report> filterReportVector(IGame game, Vector<Report> originalReportVector, IPlayer p) {
        // If no double blind, no filtering to do
        if (!game.doBlind()) {
            return new Vector<>(originalReportVector);
        }
        // But if it is, then filter everything properly.
        Vector<Report> filteredReportVector = new Vector<>();
        for (Report r : originalReportVector) {
            Report filteredReport = filterReport(game, r, p, false);
            if (filteredReport != null) {
                filteredReportVector.addElement(filteredReport);
            }
        }
        return filteredReportVector;
    }

    /**
     * Convenience method that fills in a report showing that a crew member of a multicrew cockpit
     * has taken over for another incapacitated crew member.
     *
     * @param e         The <code>Entity</code> for the crew.
     * @param slot      The slot index of the crew member that was incapacitated.
     * @param wasPilot  Whether the crew member was the pilot before becoming incapacitated.
     * @param wasGunner Whether the crew member was the gunner before becoming incapacitated.
     * @return          A completed <code>Report</code> if the position was assumed by another
     *                  crew members, otherwise null.
     */
    public Report createCrewTakeoverReport(Entity e, int slot, boolean wasPilot, boolean wasGunner) {
        if (wasPilot && e.getCrew().getCurrentPilotIndex() != slot) {
            Report r = new Report(5560);
            r.subject = e.getId();
            r.indent(4);
            r.add(e.getCrew().getNameAndRole(e.getCrew().getCurrentPilotIndex()));
            r.add(e.getCrew().getCrewType().getRoleName(e.getCrew().getCrewType().getPilotPos()));
            r.addDesc(e);
            return r;
        }
        if (wasGunner && e.getCrew().getCurrentGunnerIndex() != slot) {
            Report r = new Report(5560);
            r.subject = e.getId();
            r.indent(4);
            r.add(e.getCrew().getNameAndRole(e.getCrew().getCurrentGunnerIndex()));
            r.add(e.getCrew().getCrewType().getRoleName(e.getCrew().getCrewType().getGunnerPos()));
            r.addDesc(e);
            return r;
        }
        return null;
    }

    /**
     *
     * @return a vector which has as its keys the round number and as its
     *         elements vectors that contain all the reports for the specified player
     *         that round. The reports returned this way are properly filtered for
     *         double blind.
     */
    public Vector<Vector<Report>> filterPastReports(IGame game,
            Vector<Vector<Report>> pastReports, IPlayer p) {
        // Only actually bother with the filtering if double-blind is in effect.
        if (!game.doBlind()) {
            return pastReports;
        }
        // Perform filtering
        Vector<Vector<Report>> filteredReports = new Vector<>();
        for (Vector<Report> roundReports : pastReports) {
            Vector<Report> filteredRoundReports = new Vector<>();
            for (Report r : roundReports) {
                if (r.isObscuredRecipient(p.getName())) {
                    r = filterReport(game, r, null, true);
                }
                if (r != null) {
                    filteredRoundReports.addElement(r);
                }
            }
            filteredReports.addElement(filteredRoundReports);
        }
        return filteredReports;
    }

    public void reportRoll(Roll roll) {
        Report r = new Report(1230);
        r.add(roll.getReport());
        addReport(r);
    }

    /**
     * Write the initiative results to the report
     */
    public void writeInitiativeReport(IGame game, boolean abbreviatedReport) {
        // write to report
        Report r;
        boolean deployment = false;
        if (!abbreviatedReport) {
            r = new Report(1210);
            r.type = Report.PUBLIC;
            if ((game.getLastPhase() == IGame.Phase.PHASE_DEPLOYMENT) || game.isDeploymentComplete()
                    || !game.shouldDeployThisRound()) {
                r.messageId = 1000;
                r.add(game.getRoundCount());
            } else {
                deployment = true;
                if (game.getRoundCount() == 0) {
                    r.messageId = 1005;
                } else {
                    r.messageId = 1010;
                    r.add(game.getRoundCount());
                }
            }
            addReport(r);
            // write separator
            addReport(new Report(1200, Report.PUBLIC));
        } else {
            addReport(new Report(1210, Report.PUBLIC));
        }

        if (game.getOptions().booleanOption(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE)) {
            r = new Report(1040, Report.PUBLIC);
            addReport(r);
            for (Enumeration<GameTurn> e = game.getTurns(); e.hasMoreElements(); ) {
                GameTurn t = e.nextElement();
                if (t instanceof GameTurn.SpecificEntityTurn) {
                    Entity entity = game.getEntity(((GameTurn.SpecificEntityTurn) t).getEntityNum());
                    r = new Report(1045);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    r.add(entity.getInitiative().toString());
                    addReport(r);
                } else {
                    IPlayer player = game.getPlayer(t.getPlayerNum());
                    if (null != player) {
                        r = new Report(1050, Report.PUBLIC);
                        r.add(player.getColorForPlayer());
                        addReport(r);
                    }
                }
            }
        } else {
            for (Enumeration<Team> i = game.getTeams(); i.hasMoreElements(); ) {
                final Team team = i.nextElement();

                // Teams with no active players can be ignored
                if (team.isObserverTeam()) {
                    continue;
                }

                // If there is only one non-observer player, list
                // them as the 'team', and use the team initiative
                if (team.getNonObserverSize() == 1) {
                    final IPlayer player = team.getNonObserverPlayers().nextElement();
                    r = new Report(1015, Report.PUBLIC);
                    r.add(player.getColorForPlayer());
                    r.add(team.getInitiative().toString());
                    addReport(r);
                } else {
                    // Multiple players. List the team, then break it down.
                    r = new Report(1015, Report.PUBLIC);
                    r.add(IPlayer.teamNames[team.getId()]);
                    r.add(team.getInitiative().toString());
                    addReport(r);
                    for (Enumeration<IPlayer> j = team.getNonObserverPlayers(); j.hasMoreElements(); ) {
                        final IPlayer player = j.nextElement();
                        r = new Report(1015, Report.PUBLIC);
                        r.indent();
                        r.add(player.getName());
                        r.add(player.getInitiative().toString());
                        addReport(r);
                    }
                }
            }

            if (!game.doBlind()) {

                // The turn order is different in movement phase
                // if a player has any "even" moving units.
                r = new Report(1020, Report.PUBLIC);

                boolean hasEven = false;
                for (Enumeration<GameTurn> i = game.getTurns(); i.hasMoreElements(); ) {
                    GameTurn turn = i.nextElement();
                    IPlayer player = game.getPlayer(turn.getPlayerNum());
                    if (null != player) {
                        r.add(player.getName());
                        if (player.getEvenTurns() > 0) {
                            hasEven = true;
                        }
                    }
                }
                r.newlines = 2;
                addReport(r);
                if (hasEven) {
                    r = new Report(1021, Report.PUBLIC);
                    r.choose((game.getOptions().booleanOption(OptionsConstants.INIT_INF_DEPLOY_EVEN)
                            || game.getOptions().booleanOption(OptionsConstants.INIT_PROTOS_MOVE_EVEN))
                            && !(game.getLastPhase() == IGame.Phase.PHASE_END_REPORT));
                    r.indent();
                    r.newlines = 2;
                    addReport(r);
                }
            }

        }
        if (!abbreviatedReport) {
            // we don't much care about wind direction and such in a hard vacuum
            if(!game.getBoard().inSpace()) {
                // Wind direction and strength
                Report rWindDir = new Report(1025, Report.PUBLIC);
                rWindDir.add(game.getPlanetaryConditions().getWindDirDisplayableName());
                rWindDir.newlines = 0;
                Report rWindStr = new Report(1030, Report.PUBLIC);
                rWindStr.add(game.getPlanetaryConditions().getWindDisplayableName());
                rWindStr.newlines = 0;
                Report rWeather = new Report(1031, Report.PUBLIC);
                rWeather.add(game.getPlanetaryConditions().getWeatherDisplayableName());
                rWeather.newlines = 0;
                Report rLight = new Report(1032, Report.PUBLIC);
                rLight.add(game.getPlanetaryConditions().getLightDisplayableName());
                Report rVis = new Report(1033, Report.PUBLIC);
                rVis.add(game.getPlanetaryConditions().getFogDisplayableName());
                addReport(rWindDir);
                addReport(rWindStr);
                addReport(rWeather);
                addReport(rLight);
                addReport(rVis);
            }

            if (deployment) {
                addNewLines();
            }
        }
    }

    public void addEntitiesToReport(Enumeration<Entity> entities, int reportId) {
        if (entities.hasMoreElements()) {
            addReport(new Report(reportId, Report.PUBLIC));
            while (entities.hasMoreElements()) {
                Entity entity = entities.nextElement();
                addReport(entity.victoryReport());
            }
        }
    }

    //////////////////////////
    // TODO (Sam): RESOLVES
    //////////////////////////


    /**
     * Make the rolls indicating whether any unconscious crews wake up
     */
    public void resolveCrewWakeUp(Iterator<Entity> entities) {
        while (entities.hasNext()) {
            final Entity e = entities.next();

            // only unconscious pilots of mechs and protos, ASF and Small Craft
            // and MechWarriors can roll to wake up
            if (e.isTargetable()
                    && ((e instanceof Mech) || (e instanceof Protomech)
                    || (e instanceof MechWarrior) || ((e instanceof Aero) && !(e instanceof Jumpship)))) {
                for (int pos = 0; pos < e.getCrew().getSlotCount(); pos++) {
                    if (e.getCrew().isMissing(pos)) {
                        continue;
                    }
                    if (e.getCrew().isUnconscious(pos) && !e.getCrew().isKoThisRound(pos)) {
                        int roll = Compute.d6(2);

                        if (e.hasAbility(OptionsConstants.MISC_PAIN_RESISTANCE)) {
                            roll = Math.min(12, roll + 1);
                        }

                        int rollTarget = Compute.getConsciousnessNumber(e.getCrew().getHits(pos));
                        Report r = new Report(6029);
                        r.subject = e.getId();
                        r.add(e.getCrew().getCrewType().getRoleName(pos));
                        r.addDesc(e);
                        r.add(e.getCrew().getName(pos));
                        r.add(rollTarget);
                        r.add(roll);
                        if (roll >= rollTarget) {
                            r.choose(true);
                            e.getCrew().setUnconscious(false, pos);
                        } else {
                            r.choose(false);
                        }
                        addReport(r);
                    }
                }
            }
        }
    }

    /**
     * Check whether any <code>Entity</code> with a cockpit command console has been scheduled to swap
     * roles between the two crew members.
     */
    public void resolveConsoleCrewSwaps(Iterator<Entity> entities) {
        while (entities.hasNext()) {
            final Entity e = entities.next();
            if (e.getCrew().doConsoleRoleSwap()) {
                final Crew crew = e.getCrew();
                final int current = crew.getCurrentPilotIndex();
                Report r = new Report(5560);
                r.subject = e.getId();
                r.add(crew.getNameAndRole(current));
                r.add(crew.getCrewType().getRoleName(0));
                r.addDesc(e);
                addReport(r);
            }
        }
    }

    /*
     * Resolve any outstanding self destructions...
     */
    public void resolveSelfDestruct(List<Entity> entities) {
        for (Entity e : entities) {
            if (e.getSelfDestructing()) {
                e.setSelfDestructing(false);
                e.setSelfDestructInitiated(true);
                Report r = new Report(5535, Report.PUBLIC);
                r.subject = e.getId();
                r.addDesc(e);
                addReport(r);
            }
        }
    }

    /**
     * resolves consciousness rolls for one entity
     *
     * @param e         The <code>Entity</code> that took damage
     * @param damage    The <code>int</code> damage taken by the pilot
     * @param crewPos   The <code>int</code> index of the crew member for multi crew cockpits, ignored by
     *                  basic <code>crew</code>
     */
    public Vector<Report> resolveCrewDamage(Entity e, int damage, int crewPos, boolean rpgOption) {
        Vector<Report> vDesc = new Vector<>();
        final int totalHits = e.getCrew().getHits(crewPos);
        if ((e instanceof MechWarrior) || !e.isTargetable() || !e.getCrew().isActive(crewPos) || (damage == 0)) {
            return vDesc;
        }

        // no consciousness roll for pain-shunted warriors
        if (e.hasAbility(OptionsConstants.MD_PAIN_SHUNT)
                // no consciousness roll for capital fighter pilots or large craft crews
                || e.isCapitalFighter() || e.isLargeCraft()) {
            return vDesc;
        }

        for (int hit = (totalHits - damage) + 1; hit <= totalHits; hit++) {
            int rollTarget = Compute.getConsciousnessNumber(hit);
            if (rpgOption) {
                rollTarget -= e.getCrew().getToughness(crewPos);
            }
            boolean edgeUsed = false;
            do {
                if (edgeUsed) {
                    e.getCrew().decreaseEdge();
                }
                int roll = Compute.d6(2);
                if (e.hasAbility(OptionsConstants.MISC_PAIN_RESISTANCE)) {
                    roll = Math.min(12, roll + 1);
                }
                Report r = new Report(6030);
                r.indent(2);
                r.subject = e.getId();
                r.add(e.getCrew().getCrewType().getRoleName(crewPos));
                r.addDesc(e);
                r.add(e.getCrew().getName(crewPos));
                r.add(rollTarget);
                r.add(roll);
                if (roll >= rollTarget) {
                    e.getCrew().setKoThisRound(false, crewPos);
                    r.choose(true);
                } else {
                    e.getCrew().setKoThisRound(true, crewPos);
                    r.choose(false);
                    if (e.getCrew().hasEdgeRemaining()
                            && (e.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_KO)
                            || e.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_AERO_KO))) {
                        edgeUsed = true;
                        vDesc.add(r);
                        r = new Report(6520);
                        r.subject = e.getId();
                        r.addDesc(e);
                        r.add(e.getCrew().getName(crewPos));
                        r.add(e.getCrew().getOptions().intOption(OptionsConstants.EDGE));
                    }
                }
                vDesc.add(r);
            } while (e.getCrew().hasEdgeRemaining()
                    && e.getCrew().isKoThisRound(crewPos)
                    && (e.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_KO)
                    || e.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_AERO_KO)));
            // end of do-while
            if (e.getCrew().isKoThisRound(crewPos)) {
                boolean wasPilot = e.getCrew().getCurrentPilotIndex() == crewPos;
                boolean wasGunner = e.getCrew().getCurrentGunnerIndex() == crewPos;
                e.getCrew().setUnconscious(true, crewPos);
                Report r = createCrewTakeoverReport(e, crewPos, wasPilot, wasGunner);
                if (null != r) {
                    vDesc.add(r);
                }
                return vDesc;
            }
        }
        return vDesc;
    }

    /*
     * Resolve HarJel II/III repairs for Mechs so equipped.
     */
    public void resolveHarJelRepairs(Iterator<Entity> entities) {
        Report r;
        while (entities.hasNext()) {
            Entity entity = entities.next();
            if (!(entity instanceof Mech)) {
                continue;
            }

            Mech me = (Mech) entity;
            for (int loc = 0; loc < me.locations(); ++loc) {
                boolean harJelII = me.hasHarJelIIIn(loc); // false implies HarJel III
                if ((harJelII || me.hasHarJelIIIIn(loc))
                        && me.isArmorDamagedThisTurn(loc)) {
                    if (me.hasRearArmor(loc)) {
                        // must have at least one remaining armor in location
                        if (!((me.getArmor(loc) > 0) || (me.getArmor(loc, true) > 0))) {
                            continue;
                        }

                        int toRepair = harJelII ? 2 : 4;
                        int frontRepair, rearRepair;
                        int desiredFrontRepair, desiredRearRepair;

                        Mounted harJel = null;
                        // find HarJel item
                        // don't need to check ready or worry about null,
                        // we already know there is one, it's ready,
                        // and there can be at most one in a given location
                        for (Mounted m: me.getMisc()) {
                            if ((m.getLocation() == loc)
                                    && (m.getType().hasFlag(MiscType.F_HARJEL_II)
                                    || m.getType().hasFlag(MiscType.F_HARJEL_III))) {
                                harJel = m;
                            }
                        }

                        if (harJelII) {
                            if (harJel.curMode().equals(MiscType.S_HARJEL_II_1F1R)) {
                                desiredFrontRepair = 1;
                            } else if (harJel.curMode().equals(MiscType.S_HARJEL_II_2F0R)) {
                                desiredFrontRepair = 2;
                            } else { // 0F2R
                                desiredFrontRepair = 0;
                            }
                        } else { // HarJel III
                            if (harJel.curMode().equals(MiscType.S_HARJEL_III_2F2R)) {
                                desiredFrontRepair = 2;
                            } else if (harJel.curMode().equals(MiscType.S_HARJEL_III_4F0R)) {
                                desiredFrontRepair = 4;
                            } else if (harJel.curMode().equals(MiscType.S_HARJEL_III_3F1R)) {
                                desiredFrontRepair = 3;
                            } else if (harJel.curMode().equals(MiscType.S_HARJEL_III_1F3R)) {
                                desiredFrontRepair = 1;
                            } else { // 0F4R
                                desiredFrontRepair = 0;
                            }
                        }
                        desiredRearRepair = toRepair - desiredFrontRepair;

                        int availableFrontRepair = me.getOArmor(loc) - me.getArmor(loc);
                        int availableRearRepair = me.getOArmor(loc, true) - me.getArmor(loc, true);
                        frontRepair = Math.min(availableFrontRepair, desiredFrontRepair);
                        rearRepair = Math.min(availableRearRepair, desiredRearRepair);
                        int surplus = desiredFrontRepair - frontRepair;
                        if (surplus > 0) { // we couldn't use all the points we wanted in front
                            rearRepair = Math.min(availableRearRepair, rearRepair + surplus);
                        } else {
                            surplus = desiredRearRepair - rearRepair;
                            // try to move any excess points from rear to front
                            frontRepair = Math.min(availableFrontRepair, frontRepair + surplus);
                        }

                        if (frontRepair > 0) {
                            me.setArmor(me.getArmor(loc) + frontRepair, loc);
                            r = new Report(harJelII ? 9850 : 9851);
                            r.subject = me.getId();
                            r.addDesc(entity);
                            r.add(frontRepair);
                            r.add(me.getLocationAbbr(loc));
                            addReport(r);
                        }
                        if (rearRepair > 0) {
                            me.setArmor(me.getArmor(loc, true) + rearRepair, loc, true);
                            r = new Report(harJelII ? 9850 : 9851);
                            r.subject = me.getId();
                            r.addDesc(entity);
                            r.add(rearRepair);
                            r.add(me.getLocationAbbr(loc) + " (R)");
                            addReport(r);
                        }
                    } else {
                        // must have at least one remaining armor in location
                        if (!(me.getArmor(loc) > 0)) {
                            continue;
                        }
                        int toRepair = harJelII ? 2 : 4;
                        toRepair = Math.min(toRepair, me.getOArmor(loc) - me.getArmor(loc));
                        me.setArmor(me.getArmor(loc) + toRepair, loc);
                        r = new Report(harJelII ? 9850 : 9851);
                        r.subject = me.getId();
                        r.addDesc(entity);
                        r.add(toRepair);
                        r.add(me.getLocationAbbr(loc));
                        addReport(r);
                    }
                }
            }
        }
    }

    /**
     * Report: - Any ammo dumps beginning the following round. - Any ammo dumps
     * that have ended with the end of this round.
     */
    public void resolveAmmoDumps(List<Entity> entities) {
        Report r;
        for (Entity entity : entities) {
            for (Mounted m : entity.getAmmo()) {
                if (m.isPendingDump()) {
                    // report dumping next round
                    r = new Report(5110);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    r.add(m.getName());
                    addReport(r);
                    // update status
                    m.setPendingDump(false);
                    m.setDumping(true);
                } else if (m.isDumping()) {
                    // report finished dumping
                    r = new Report(5115);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    r.add(m.getName());
                    addReport(r);
                    // update status
                    m.setDumping(false);
                    m.setShotsLeft(0);
                }
            }
            // also do DWP dumping
            if (entity instanceof BattleArmor) {
                for (Mounted m : entity.getWeaponList()) {
                    if (m.isDWPMounted() && m.isPendingDump()) {
                        m.setMissing(true);
                        r = new Report(5116);
                        r.subject = entity.getId();
                        r.addDesc(entity);
                        r.add(m.getName());
                        addReport(r);
                        m.setPendingDump(false);
                        // Also dump all of the ammo in the DWP
                        for (Mounted ammo : entity.getAmmo()) {
                            if (m.equals(ammo.getLinkedBy())) {
                                ammo.setMissing(true);
                            }
                        }
                        // Check for jettisoning missiles
                    } else if (m.isBodyMounted() && m.isPendingDump()
                            && m.getType().hasFlag(WeaponType.F_MISSILE)
                            && (m.getLinked() != null)
                            && (m.getLinked().getUsableShotsLeft() > 0)) {
                        m.setMissing(true);
                        r = new Report(5116);
                        r.subject = entity.getId();
                        r.addDesc(entity);
                        r.add(m.getName());
                        addReport(r);
                        m.setPendingDump(false);
                        // Dump all ammo related to this launcher BA burdened is based
                        // on whether the launcher has ammo left
                        while ((m.getLinked() != null) && (m.getLinked().getUsableShotsLeft() > 0)) {
                            m.getLinked().setMissing(true);
                            entity.loadWeapon(m);
                        }
                    }
                }
            }
            entity.reloadEmptyWeapons();
        }
    }

    /**
     * Resolve an Unjam Action object
     */
    public void resolveUnjam(Entity entity, boolean optionName) {
        Report r;
        final int TN = entity.getCrew().getGunnery() + 3;
        if (optionName) {
            r = new Report(3026);
        } else {
            r = new Report(3025);
        }
        r.subject = entity.getId();
        r.addDesc(entity);
        addReport(r);
        for (Mounted mounted : entity.getTotalWeaponList()) {
            if (mounted.isJammed() && !mounted.isDestroyed()) {
                WeaponType wtype = (WeaponType) mounted.getType();
                if (wtype.getAmmoType() == AmmoType.T_AC_ROTARY) {
                    reportWeaponJammingResult(entity, TN, mounted, wtype);
                }
                // Unofficial option to unjam UACs, ACs, and LACs like Rotary
                // Autocannons
                if (((wtype.getAmmoType() == AmmoType.T_AC_ULTRA)
                        || (wtype.getAmmoType() == AmmoType.T_AC_ULTRA_THB)
                        || (wtype.getAmmoType() == AmmoType.T_AC)
                        || (wtype.getAmmoType() == AmmoType.T_AC_IMP)
                        || (wtype.getAmmoType() == AmmoType.T_PAC)
                        || (wtype.getAmmoType() == AmmoType.T_LAC))
                        && optionName) {
                    reportWeaponJammingResult(entity, TN, mounted, wtype);
                }
            }
        }
    }

    private void reportWeaponJammingResult(Entity entity, int TN, Mounted mounted, WeaponType wtype) {
        int roll = Compute.d6(2);
        Report r = new Report(3030);
        r.indent();
        r.subject = entity.getId();
        r.add(wtype.getName());
        r.add(TN);
        r.add(roll);
        if (roll >= TN) {
            r.choose(true);
            mounted.setJammed(false);
        } else {
            r.choose(false);
        }
        addReport(r);
    }
}
