package megamek.server.DamageEntityTest;

import com.fasterxml.jackson.annotation.JsonProperty;
import megamek.server.Server;

import java.util.Vector;

public class TestSerializer {

    @JsonProperty("testName")
    private String m_TestName;

    @JsonProperty("damage")
    private int m_Damage;

    @JsonProperty("ammoExplosion")
    private boolean m_AmmoExplosion;

    @JsonProperty("damageType")
    private Server.DamageType m_DamageType;

    @JsonProperty("damageIs")
    private boolean m_DamageIs;

    @JsonProperty("areaSatEntry")
    private boolean m_AreaSatEntry;

    @JsonProperty("throughFront")
    private boolean m_ThroughFront;

    @JsonProperty("underWater")
    private boolean m_UnderWater;

    @JsonProperty("nukeS2S")
    private boolean m_NukeS2S;

    @JsonProperty("caseCount")
    private int m_CaseCount;

    @JsonProperty("cases")
    private Vector<CaseSerializer> m_Cases;

    public TestSerializer(){
        m_TestName = "DamageEntityTest";
        m_Damage = 20;
        m_AmmoExplosion = true;
        m_DamageType = Server.DamageType.FRAGMENTATION;
        m_DamageIs = true;
        m_AreaSatEntry = false;
        m_ThroughFront = true;
        m_UnderWater = false;
        m_NukeS2S = false;
        m_Cases = new Vector<CaseSerializer>();
        m_CaseCount = 0;
    }

    public TestSerializer(String testName, int damage, boolean ammoExplosion, Server.DamageType damageType,
                          boolean damageIs, boolean areaSatEntry, boolean throughFront, boolean underWater,
                          boolean nukeS2S, Vector<CaseSerializer> cases){
        m_TestName = testName;
        m_Damage = damage;
        m_AmmoExplosion = ammoExplosion;
        m_DamageType = damageType;
        m_DamageIs = damageIs;
        m_AreaSatEntry = areaSatEntry;
        m_ThroughFront = throughFront;
        m_UnderWater = underWater;
        m_NukeS2S = nukeS2S;
        m_Cases = cases;
        m_CaseCount = cases.size();
    }

    public String getTestName(){
        return m_TestName;
    }

    public int getDamage(){
        return m_Damage;
    }

    public boolean getAmmoExplosion(){
        return m_AmmoExplosion;
    }

    public Server.DamageType getDamageType(){
        return m_DamageType;
    }

    public boolean getDamageIs(){
        return m_DamageIs;
    }

    public boolean getAreaSatEntry(){
        return m_AreaSatEntry;
    }

    public boolean getThroughFront(){
        return m_ThroughFront;
    }

    public boolean getUnderWater(){
        return m_UnderWater;
    }

    public boolean getNukeS2S(){
        return m_NukeS2S;
    }

    public int getCaseCount(){
        return m_CaseCount;
    }

    public Vector<CaseSerializer> getCases(){
        return m_Cases;
    }

    public void addCase(CaseSerializer caseSerializer){
        m_Cases.add(caseSerializer);
        m_CaseCount++;
    }
}
