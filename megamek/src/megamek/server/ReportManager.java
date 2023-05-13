package megamek.server;

import megamek.MegaMek;
import megamek.common.*;
import megamek.common.options.OptionsConstants;

import java.util.Enumeration;
import java.util.Iterator;
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
                    if ((game.getOptions().booleanOption(OptionsConstants.INIT_INF_DEPLOY_EVEN)
                            || game.getOptions().booleanOption(OptionsConstants.INIT_PROTOS_MOVE_EVEN))
                            && !(game.getLastPhase() == IGame.Phase.PHASE_END_REPORT)) {
                        r.choose(true);
                    } else {
                        r.choose(false);
                    }
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


    public void create_report(int report_id, int subject, Entity to_adddesc, int to_add, int newlines) {
        Report r = new Report(report_id);
        r.subject = subject;



        r.addDesc(to_adddesc);
        addReport(r);
    }





}
