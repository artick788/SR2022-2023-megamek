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



}
