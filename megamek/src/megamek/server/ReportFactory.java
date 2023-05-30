package megamek.server;

import megamek.common.Entity;
import megamek.common.Report;

class ReportFactory {

    public static Report createReport(int ID){
        return new Report(ID);
    }

    public static Report createPublicReport(int ID){
        return new Report(ID, Report.PUBLIC);
    }

    public static Report createPublicReport(int ID, int indent){
        Report r = new Report(ID, Report.PUBLIC);
        r.indent(indent);
        return r;
    }

    // =====================with indent================================================
    public static Report createReport(int ID, int indent, Entity e){
        Report r = new Report(ID);
        r.subject = e.getId();
        r.indent(indent);
        r.addDesc(e);
        return r;
    }

    public static Report createReport(int ID, int indent, Entity e, String... str){
        Report r = createReport(ID, indent, e);
        for (String s : str) {
            r.add(s);
        }
        return r;
    }

    public static Report createReport(int ID, int indent, Entity e, int... ints){
        Report r = createReport(ID, indent, e);
        for (int i : ints) {
            r.add(i);
        }
        return r;
    }

    public static Report createPublicReport(int ID, int indent, Entity e){
        Report r = new Report(ID, Report.PUBLIC);
        r.subject = e.getId();
        r.indent(indent);
        r.addDesc(e);
        return r;
    }

    public static Report createPublicReport(int ID, int indent, Entity e, int... ints){
        Report r =createPublicReport(ID, indent, e);
        for (int i : ints) {
            r.add(i);
        }
        return r;
    }

    public static Report createPublicReport(int ID, int indent, String... str){
        Report r = new Report(ID, Report.PUBLIC);
        r.indent(indent);
        for (String s : str) {
            r.add(s);
        }
        return r;
    }

    // ====================without indent==============================================
    public static Report createReport(int ID, Entity e){
        Report r = new Report(ID);
        r.subject = e.getId();
        r.addDesc(e);
        return r;
    }

    public static Report createReport(int ID, Entity e, String... str){
        Report r = createReport(ID, e);
        for (String s : str) {
            r.add(s);
        }
        return r;
    }

    public static Report createReport(int ID, Entity e, int... ints){
        Report r = createReport(ID,  e);
        for (int i : ints) {
            r.add(i);
        }
        return r;
    }

    public static Report createPublicReport(int ID, Entity e){
        Report r = new Report(ID, Report.PUBLIC);
        r.subject = e.getId();
        r.addDesc(e);
        return r;
    }

    public static Report createPublicReport(int ID, String... str){
        Report r = new Report(ID, Report.PUBLIC);
        for (String s : str) {
            r.add(s);
        }
        return r;
    }

    // ===========================Player==============================================
    public static Report createPlayerReport(int ID, int playerID){
        Report r = new Report(ID);
        r.player = playerID;
        return r;
    }

    public static Report createPlayerReport(int ID, int playerID, int... ints){
        Report r = createPlayerReport(ID, playerID);
        for (int i : ints) {
            r.add(i);
        }
        return r;
    }

    public static Report createPlayerReport(int ID, int playerID, String... str) {
        Report r = createPlayerReport(ID, playerID);
        r.add(str);
        return r;
    }

    public static Report createAttackingEntityReport(int ID, Entity ae, Entity te){
        Report r = new Report(ID);
        r.subject = ae.getId();
        r.indent();
        r.addDesc(te);
        r.newlines = 0;
        return r;
    }

    public static Report createAttackingEntityReport(int ID, Entity ae, Entity te, String... str){
        Report r = createAttackingEntityReport(ID, ae, te);
        r.add(str);
        return r;
    }
}
