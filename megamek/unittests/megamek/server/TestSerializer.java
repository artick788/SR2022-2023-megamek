package megamek.server;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Vector;

public class TestSerializer {

    @JsonProperty("TestName")
    private String m_TestName;

    @JsonProperty("Damage")
    private int m_Damage;

    @JsonProperty("AmmoExplosion")
    private boolean m_AmmoExplosion;

    @JsonProperty("DamageType")
    private Server.DamageType m_DamageType;

    @JsonProperty("DamageIs")
    private boolean m_DamageIs;

    @JsonProperty("AreaSatEntry")
    private boolean m_AreaSatEntry;

    @JsonProperty("ThroughFront")
    private boolean m_ThroughFront;

    @JsonProperty("UnderWater")
    private boolean m_UnderWater;

    @JsonProperty("NukeS2S")
    private boolean m_NukeS2S;

    @JsonProperty("Cases")
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

    public Vector<CaseSerializer> getCases(){
        return m_Cases;
    }

    public void setTestName(String testName){
        m_TestName = testName;
    }

    public void setDamage(int damage){
        m_Damage = damage;
    }

    public void setAmmoExplosion(boolean ammoExplosion){
        m_AmmoExplosion = ammoExplosion;
    }

    public void setDamageType(Server.DamageType damageType){
        m_DamageType = damageType;
    }

    public void setDamageIs(boolean damageIs){
        m_DamageIs = damageIs;
    }

    public void setAreaSatEntry(boolean areaSatEntry){
        m_AreaSatEntry = areaSatEntry;
    }

    public void setThroughFront(boolean throughFront){
        m_ThroughFront = throughFront;
    }

    public void setUnderWater(boolean underWater){
        m_UnderWater = underWater;
    }

    public void setNukeS2S(boolean nukeS2S){
        m_NukeS2S = nukeS2S;
    }

    public void setCases(Vector<CaseSerializer> cases){
        m_Cases = cases;
    }

    public void addCase(CaseSerializer caseSerializer){
        m_Cases.add(caseSerializer);
    }
}
