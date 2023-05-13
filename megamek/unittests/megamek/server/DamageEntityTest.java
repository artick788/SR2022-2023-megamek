package megamek.server;

import junit.framework.TestCase;
import megamek.common.*;

import megamek.common.loaders.EntityLoadingException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.Assert;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.ArrayList;
import java.io.File;
import java.util.Vector;

import java.util.Random;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

@RunWith(JUnit4.class)
public class DamageEntityTest {

    private static Server m_Server;
    private static IPlayer m_Player;

    private Vector<Report> damageEntityWrapper(Entity te, TestSerializer ts){
        HitData hitData = new HitData(0);
        int damage = ts.getDamage();
        boolean ammoExplosion = ts.getAmmoExplosion();
        Server.DamageType damageType = ts.getDamageType();
        boolean damageIs = ts.getDamageIs();
        boolean areaSatEntry = ts.getAreaSatEntry();
        boolean throughFront = ts.getThroughFront();
        boolean underWater = ts.getUnderWater();
        boolean nukeS2S = ts.getNukeS2S();

        return m_Server.damageEntity(te, hitData, damage, ammoExplosion, damageType, damageIs, areaSatEntry, throughFront, underWater, nukeS2S);
    }

    public DamageEntityTest() throws IOException {
        m_Server = new Server("Megamek", 1234);
        m_Player = m_Server.getGame().addNewPlayer(5, "NewPlayer1");
    }

    @Test
    public void testDamageEntity() throws EntityLoadingException, IOException {
        String jsonFile = "TestDamageEntity.json";
        runTestWithJsonData(jsonFile);
    }

    public void runTestWithJsonData(String jsonFile) throws IOException, EntityLoadingException {
        File f = new File(jsonFile);
        ObjectMapper mapper = new ObjectMapper();
        TestSerializer ts = mapper.readValue(f, TestSerializer.class);

        for (CaseSerializer cs: ts.getCases()){
            String mechFile = cs.getMechFile();
            File mech = new File(mechFile);
            MechFileParser mfp = new MechFileParser(mech);
            Entity e = mfp.getEntity();
            e.setOwner(m_Player);
            Vector<ReportSerializer> expected = cs.getReports();
            Vector<Report> actual = damageEntityWrapper(e, ts);

            Assert.assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++){
                Assert.assertEquals(expected.get(i).getMessageID(), actual.get(i).messageId);
                Assert.assertEquals(expected.get(i).getType(), actual.get(i).type);
                Assert.assertEquals(expected.get(i).getPlayer(), actual.get(i).player);
            }
        }
    }

    public void getJsonTestData(String jsonFile) throws EntityLoadingException {
        Vector<String> mechFiles = new Vector<>();
        mechFiles.add("data/mechfiles/mechs/3050U/Exterminator EXT-4A.mtf");
        mechFiles.add("data/mechfiles/mechs/3039u/Archer ARC-2K.mtf");
        mechFiles.add("data/mechfiles/mechs/3039u/Centurion CN9-A.mtf");
        mechFiles.add("data/mechfiles/mechs/3039u/Enforcer ENF-4R.mtf");
        mechFiles.add("data/mechfiles/mechs/3039u/Griffin GRF-1N.mtf");
        mechFiles.add("data/mechfiles/mechs/Arano Restoration/Atlas AS7-D-HT.mtf");
        mechFiles.add("data/mechfiles/mechs/3075/Balius A.mtf");

        File f;
        MechFileParser mfp;
        Entity e;

        TestSerializer ts = new TestSerializer();
        for (String mechFile : mechFiles) {
            CaseSerializer cs = new CaseSerializer(mechFile);

            f = new File(mechFile);
            mfp = new MechFileParser(f);
            e = mfp.getEntity();
            e.setOwner(m_Player);
            Vector<Report> expected = damageEntityWrapper(e, ts);

            for (Report r: expected){
                ReportSerializer rs = new ReportSerializer(r);
                cs.addReport(rs);
            }
            ts.addCase(cs);
        }

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        File json = new File(jsonFile);
        try {
            mapper.writeValue(json, ts);
        } catch (IOException e1) {
            e1.printStackTrace();
        }
    }
}
