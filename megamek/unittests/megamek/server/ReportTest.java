package megamek.server;
import megamek.common.Report;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Hashtable;
import java.util.Vector;

@RunWith(JUnit4.class)
public class ReportTest {
    @Test
    public void testReportDeclaration() {
        int id = 9100;
        int subject = 999;
        int indent = 0;
        int newlines = 1;
        String reason1 = "Test";
        String reason2 = "Test2";


        Report r = new Report(id);
        r.subject = subject;
        r.add(reason1);
        r.indent(indent);
        r.add(reason2);
        r.newlines = newlines;

        Report s = new Report(id);
        s.subject = subject;
        s.add(reason1);
        s.indent(indent);
        s.add(reason2);
        s.newlines = newlines;

        Assert.assertEquals(r.getText(), s.getText());


    }

}
