package megamek.server;

import megamek.common.Entity;
import megamek.common.Report;

class ReportFactory {

    public static Report createReport(int ID){
        return createReport(ID, Report.HIDDEN);
    }

    public static Report createReport(int ID, int type){
        return new Report(ID, type);
    }

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
}
