package megamek.server;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Vector;

public class CaseSerializer {

    @JsonProperty("MechFile")
    private String m_MechFile;

    @JsonProperty("Reports")
    private Vector<ReportSerializer> m_Reports;

    public CaseSerializer(){
        m_MechFile = "";
        m_Reports = new Vector<ReportSerializer>();
    }

    public CaseSerializer(String mechFile){
        m_MechFile = mechFile;
        m_Reports = new Vector<ReportSerializer>();
    }

    public String getMechFile(){
        return m_MechFile;
    }

    public Vector<ReportSerializer> getReports(){
        return m_Reports;
    }

    public void setMechFile(String mechFile){
        m_MechFile = mechFile;
    }

    public void setReports(Vector<ReportSerializer> reports){
        m_Reports = reports;
    }

    public void addReport(ReportSerializer report){
        m_Reports.add(report);
    }
}
