package megamek.server;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.SerializationFeature;
import megamek.common.Report;

public class ReportSerizalizer {

    @JsonProperty("messageId")
    int messageId;

    public ReportSerizalizer(){
        this.messageId = 0;
    }

    public ReportSerizalizer(int messageId){
        this.messageId = messageId;
    }

    public int getMessageId() {
        return messageId;
    }

    public void setMessageId(int messageId) {
        this.messageId = messageId;
    }
}
