package megamek.server.DamageEntityTest;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Vector;

public class CaseSerializer {

    @JsonProperty("mechFile")
    private String m_MechFile;

    @JsonProperty("reportCount")
    private int m_ReportCount;

    @JsonProperty("reports")
    private Vector<ReportSerializer> m_Reports;

    public CaseSerializer(){
        m_MechFile = "";
        m_Reports = new Vector<ReportSerializer>();
        m_ReportCount = 0;
    }

    public CaseSerializer(String mechFile){
        m_MechFile = mechFile;
        m_Reports = new Vector<ReportSerializer>();
        m_ReportCount = 0;
    }

    public String getMechFile(){
        return m_MechFile;
    }

    public Vector<ReportSerializer> getReports(){
        return m_Reports;
    }

    public void addReport(ReportSerializer report){
        m_Reports.add(report);
        m_ReportCount++;
    }
}
