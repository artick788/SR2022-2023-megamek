package megamek.server;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.annotation.JsonProperty;
import megamek.common.Report;

public class ReportSerializer {
    @JsonProperty("messageID")
    private int m_MessageID;
    @JsonProperty("player")
    private int m_Player;
    @JsonProperty("type")
    private int m_Type;

    public ReportSerializer(){
        m_MessageID = 0;
        m_Player = 0;
        m_Type = 0;
    }

    public ReportSerializer(Report r){
        m_MessageID = r.messageId;
        m_Player = r.player;
        m_Type = r.type;
    }

    public ReportSerializer(int messageID, int indentation, int player, int type){
        m_MessageID = messageID;
        m_Player = player;
        m_Type = type;
    }

    public int getMessageID(){
        return m_MessageID;
    }

    public int getPlayer(){
        return m_Player;
    }

    public int getType(){
        return m_Type;
    }

    public String toString(){
        return "ReportSerializer: " + m_MessageID + ", " + m_Player + ", " + m_Type;
    }

}
