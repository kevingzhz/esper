/*
 * *************************************************************************************
 *  Copyright (C) 2006-2015 EsperTech, Inc. All rights reserved.                       *
 *  http://www.espertech.com/esper                                                     *
 *  http://www.espertech.com                                                           *
 *  ---------------------------------------------------------------------------------- *
 *  The software in this package is published under the terms of the GPL license       *
 *  a copy of which has been included with this distribution in the license.txt file.  *
 * *************************************************************************************
 */

package com.espertech.esper.regression.nwtable;

import com.espertech.esper.client.Configuration;
import com.espertech.esper.client.EPServiceProviderManager;
import com.espertech.esper.client.EPStatement;
import com.espertech.esper.client.EventBean;
import com.espertech.esper.client.scopetest.SupportUpdateListener;
import com.espertech.esper.client.util.*;
import com.espertech.esper.core.service.EPServiceProviderSPI;
import com.espertech.esper.metrics.instrumentation.InstrumentationHelper;
import com.espertech.esper.support.bean.SupportBean;
import com.espertech.esper.support.bean.SupportBean_S0;
import com.espertech.esper.support.bean.SupportBean_S1;
import com.espertech.esper.support.bean.SupportBean_S2;
import com.espertech.esper.support.client.SupportConfigFactory;
import com.espertech.esper.support.util.SupportMessageAssertUtil;
import junit.framework.TestCase;

import java.util.Arrays;

public class TestTableCountMinSketch extends TestCase
{
    private EPServiceProviderSPI epService;

    public void setUp()
    {
        Configuration config = SupportConfigFactory.getConfiguration();
        epService = (EPServiceProviderSPI) EPServiceProviderManager.getDefaultProvider(config);
        epService.initialize();
        if (InstrumentationHelper.ENABLED) { InstrumentationHelper.startTest(epService, this.getClass(), getName());}
        epService.getEPAdministrator().getConfiguration().addEventType(SupportBean.class);
        epService.getEPAdministrator().getConfiguration().addEventType(SupportBean_S0.class);
        epService.getEPAdministrator().getConfiguration().addEventType(SupportBean_S1.class);
        epService.getEPAdministrator().getConfiguration().addEventType(SupportBean_S2.class);
    }

    public void tearDown() {
        if (InstrumentationHelper.ENABLED) { InstrumentationHelper.endTest();}
    }

    public void testDocSamples() {
        epService.getEPAdministrator().createEPL("create schema WordEvent (word string)");
        epService.getEPAdministrator().createEPL("create schema EstimateWordCountEvent (word string)");

        epService.getEPAdministrator().createEPL("create table WordCountTable(wordcms countMinSketch())");
        epService.getEPAdministrator().createEPL("create table WordCountTable2(wordcms countMinSketch({\n" +
                "  epsOfTotalCount: 0.000002,\n" +
                "  confidence: 0.999,\n" +
                "  seed: 38576,\n" +
                "  topk: 20,\n" +
                "  agent: '" + CountMinSketchAgentStringUTF16.class.getName() + "'" +
                "}))");
        epService.getEPAdministrator().createEPL("into table WordCountTable select countMinSketchAdd(word) as wordcms from WordEvent");
        epService.getEPAdministrator().createEPL("select WordCountTable.wordcms.countMinSketchFrequency(word) from EstimateWordCountEvent");
        epService.getEPAdministrator().createEPL("select WordCountTable.wordcms.countMinSketchTopk() from pattern[every timer:interval(10 sec)]");
    }

    public void testNonStringType() {
        epService.getEPAdministrator().getConfiguration().addEventType(MyByteArrayEventRead.class);
        epService.getEPAdministrator().getConfiguration().addEventType(MyByteArrayEventCount.class);

        String eplTable = "create table MyApprox(bytefreq countMinSketch({" +
                "  epsOfTotalCount: 0.02," +
                "  confidence: 0.98," +
                "  topk: null," +
                "  agent: '" + MyBytesPassthruAgent.class.getName() + "'" +
                "}))";
        epService.getEPAdministrator().createEPL(eplTable);

        String eplInto = "into table MyApprox select countMinSketchAdd(data) as bytefreq from MyByteArrayEventCount";
        epService.getEPAdministrator().createEPL(eplInto);

        SupportUpdateListener listener = new SupportUpdateListener();
        String eplRead = "select MyApprox.bytefreq.countMinSketchFrequency(data) as freq from MyByteArrayEventRead";
        EPStatement stmtRead = epService.getEPAdministrator().createEPL(eplRead);
        stmtRead.addListener(listener);

        epService.getEPRuntime().sendEvent(new MyByteArrayEventCount(new byte[] {1, 2, 3}));
        epService.getEPRuntime().sendEvent(new MyByteArrayEventRead(new byte[] {0, 2, 3}));
        assertEquals(0L, listener.assertOneGetNewAndReset().get("freq"));

        epService.getEPRuntime().sendEvent(new MyByteArrayEventRead(new byte[] {1, 2, 3}));
        assertEquals(1L, listener.assertOneGetNewAndReset().get("freq"));
    }

    public void testFrequencyAndTopk() throws Exception {
        String epl =
                "create table MyApprox(wordapprox countMinSketch({topk:3}));\n" +
                "into table MyApprox select countMinSketchAdd(theString) as wordapprox from SupportBean;\n" +
                "@name('frequency') select MyApprox.wordapprox.countMinSketchFrequency(p00) as freq from SupportBean_S0;\n" +
                "@name('topk') select MyApprox.wordapprox.countMinSketchTopk() as topk from SupportBean_S1;\n";
        epService.getEPAdministrator().getDeploymentAdmin().parseDeploy(epl);

        SupportUpdateListener listenerFreq = new SupportUpdateListener();
        epService.getEPAdministrator().getStatement("frequency").addListener(listenerFreq);
        SupportUpdateListener listenerTopk = new SupportUpdateListener();
        epService.getEPAdministrator().getStatement("topk").addListener(listenerTopk);

        epService.getEPRuntime().sendEvent(new SupportBean("E1", 0));
        assertOutput(listenerFreq, "E1=1", listenerTopk, "E1=1");

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 0));
        assertOutput(listenerFreq, "E1=1,E2=1", listenerTopk, "E1=1,E2=1");

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 0));
        assertOutput(listenerFreq, "E1=1,E2=2", listenerTopk, "E1=1,E2=2");

        epService.getEPRuntime().sendEvent(new SupportBean("E3", 0));
        assertOutput(listenerFreq, "E1=1,E2=2,E3=1", listenerTopk, "E1=1,E2=2,E3=1");

        epService.getEPRuntime().sendEvent(new SupportBean("E4", 0));
        assertOutput(listenerFreq, "E1=1,E2=2,E3=1,E4=1", listenerTopk, "E1=1,E2=2,E3=1");

        epService.getEPRuntime().sendEvent(new SupportBean("E4", 0));
        assertOutput(listenerFreq, "E1=1,E2=2,E3=1,E4=2", listenerTopk, "E1=1,E2=2,E4=2");

        // test join
        String eplJoin = "select wordapprox.countMinSketchFrequency(s2.p20) as c0 from MyApprox, SupportBean_S2 s2 unidirectional";
        EPStatement stmtJoin = epService.getEPAdministrator().createEPL(eplJoin);
        stmtJoin.addListener(listenerFreq);
        epService.getEPRuntime().sendEvent(new SupportBean_S2(0, "E3"));
        assertEquals(1L, listenerFreq.assertOneGetNewAndReset().get("c0"));
        stmtJoin.destroy();

        // test subquery
        String eplSubquery = "select (select wordapprox.countMinSketchFrequency(s2.p20) from MyApprox) as c0 from SupportBean_S2 s2";
        EPStatement stmtSubquery = epService.getEPAdministrator().createEPL(eplSubquery);
        stmtSubquery.addListener(listenerFreq);
        epService.getEPRuntime().sendEvent(new SupportBean_S2(0, "E3"));
        assertEquals(1L, listenerFreq.assertOneGetNewAndReset().get("c0"));
        stmtSubquery.destroy();
    }

    public void testInvalid() {

        epService.getEPAdministrator().getConfiguration().addEventType(MyByteArrayEventCount.class);
        epService.getEPAdministrator().createEPL("create table MyCMS(wordcms countMinSketch())");

        // invalid "countMinSketch" declarations
        //
        tryInvalid("select countMinSketch() from SupportBean",
                "Error starting statement: Failed to validate select-clause expression 'countMinSketch()': Count-min-sketch aggregation function 'countMinSketch' can only be used in create-table statements [");
        tryInvalid("create table MyTable(cms countMinSketch(5))",
                "Error starting statement: Failed to validate table-column expression 'countMinSketch(5)': Count-min-sketch aggregation function 'countMinSketch'  expects either no parameter or a single json parameter object [");
        tryInvalid("create table MyTable(cms countMinSketch({xxx:3}))",
                "Error starting statement: Failed to validate table-column expression 'countMinSketch({xxx=3})': Unrecognized parameter 'xxx' [");
        tryInvalid("create table MyTable(cms countMinSketch({epsOfTotalCount:'a'}))",
                "Error starting statement: Failed to validate table-column expression 'countMinSketch({epsOfTotalCount=a})': Property 'epsOfTotalCount' expects an java.lang.Double but receives a value of type java.lang.String [");
        tryInvalid("create table MyTable(cms countMinSketch({agent:'a'}))",
                "Error starting statement: Failed to validate table-column expression 'countMinSketch({agent=a})': Failed to instantiate agent provider: Could not load class by name 'a', please check imports [");
        tryInvalid("create table MyTable(cms countMinSketch({agent:'java.lang.String'}))",
                "Error starting statement: Failed to validate table-column expression 'countMinSketch({agent=java.lang.String})': Failed to instantiate agent provider: Class 'java.lang.String' does not implement interface 'com.espertech.esper.client.util.CountMinSketchAgent' [");

        // invalid "countMinSketchAdd" declarations
        //
        tryInvalid("select countMinSketchAdd(theString) from SupportBean",
                "Error starting statement: Failed to validate select-clause expression 'countMinSketchAdd(theString)': Count-min-sketch aggregation function 'countMinSketchAdd' can only be used with into-table");
        tryInvalid("into table MyCMS select countMinSketchAdd() as wordcms from SupportBean",
                "Error starting statement: Failed to validate select-clause expression 'countMinSketchAdd()': Count-min-sketch aggregation function 'countMinSketchAdd' requires a single parameter expression");
        tryInvalid("into table MyCMS select countMinSketchAdd(data) as wordcms from MyByteArrayEventCount",
                "Error starting statement: Incompatible aggregation function for table 'MyCMS' column 'wordcms', expecting 'countMinSketch()' and received 'countMinSketchAdd(data)': Mismatching parameter return type, expected any of [class java.lang.String] but received byte(Array) [");
        tryInvalid("into table MyCMS select countMinSketchAdd(distinct 'abc') as wordcms from MyByteArrayEventCount",
                "Error starting statement: Failed to validate select-clause expression 'countMinSketchAdd(distinct \"abc\")': Count-min-sketch aggregation function 'countMinSketchAdd' is not supported with distinct [");

        // invalid "countMinSketchFrequency" declarations
        //
        tryInvalid("into table MyCMS select countMinSketchFrequency(theString) as wordcms from SupportBean",
                "Error starting statement: Failed to validate select-clause expression 'countMinSketchFrequency(theString)': Count-min-sketch aggregation function 'countMinSketchFrequency' requires the use of a table-access expression [");
        tryInvalid("select countMinSketchFrequency() from SupportBean",
                "Error starting statement: Failed to validate select-clause expression 'countMinSketchFrequency()': Count-min-sketch aggregation function 'countMinSketchFrequency' requires a single parameter expression");

        // invalid "countMinSketchTopk" declarations
        //
        tryInvalid("select countMinSketchTopk() from SupportBean",
                "Error starting statement: Failed to validate select-clause expression 'countMinSketchTopk()': Count-min-sketch aggregation function 'countMinSketchTopk' requires the use of a table-access expression");
        tryInvalid("select MyCMS.wordcms.countMinSketchTopk(theString) from SupportBean",
                "Error starting statement: Failed to validate select-clause expression 'MyCMS.wordcms.countMinSketchTopk(th...(43 chars)': Count-min-sketch aggregation function 'countMinSketchTopk' requires a no parameter expressions [");
    }

    private void tryInvalid(String epl, String expected) {
        SupportMessageAssertUtil.tryInvalid(epService, epl, expected);
    }

    private void assertOutput(SupportUpdateListener listenerFrequency, String frequencyList,
                              SupportUpdateListener listenerTopk, String topkList) {
        assertFrequencies(listenerFrequency, frequencyList);
        assertTopk(listenerTopk, topkList);
    }

    private void assertFrequencies(SupportUpdateListener listenerFrequency, String frequencyList) {
        String[] pairs = frequencyList.split(",");
        for (int i = 0; i < pairs.length; i++) {
            String[] split = pairs[i].split("=");
            epService.getEPRuntime().sendEvent(new SupportBean_S0(0, split[0].trim()));
            Object value = listenerFrequency.assertOneGetNewAndReset().get("freq");
            assertEquals("failed at index" + i, Long.parseLong(split[1]), value);
        }
    }

    private void assertTopk(SupportUpdateListener listenerTopk, String topkList) {

        epService.getEPRuntime().sendEvent(new SupportBean_S1(0));
        EventBean event = listenerTopk.assertOneGetNewAndReset();
        CountMinSketchTopK[] arr = (CountMinSketchTopK[]) event.get("topk");

        String[] pairs = topkList.split(",");
        assertEquals("received " + Arrays.asList(arr), pairs.length, arr.length);

        for (String pair : pairs) {
            String[] pairArr = pair.split("=");
            long expectedFrequency = Long.parseLong(pairArr[1]);
            String expectedValue = pairArr[0].trim();
            int foundIndex = find(expectedFrequency, expectedValue, arr);
            assertFalse("failed to find '" + expectedValue + "=" + expectedFrequency + "' among remaining " + Arrays.asList(arr), foundIndex == -1);
            arr[foundIndex] = null;
        }
    }

    private int find(long expectedFrequency, String expectedValue, CountMinSketchTopK[] arr) {
        for (int i = 0; i < arr.length; i++) {
            CountMinSketchTopK item = arr[i];
            if (item != null && item.getFrequency() == expectedFrequency && item.getValue().equals(expectedValue)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * An agent that expects byte[] values.
     */
    public static class MyBytesPassthruAgent implements CountMinSketchAgent {

        public Class[] getAcceptableValueTypes() {
            return new Class[] {byte[].class};
        }

        public void add(CountMinSketchAgentContextAdd ctx) {
            if (ctx.getValue() == null) {
                return;
            }
            byte[] value = (byte[]) ctx.getValue();
            ctx.getState().add(value, 1);
        }

        public Long estimate(CountMinSketchAgentContextEstimate ctx) {
            if (ctx.getValue() == null) {
                return null;
            }
            byte[] value = (byte[]) ctx.getValue();
            return ctx.getState().frequency(value);
        }

        public Object fromBytes(CountMinSketchAgentContextFromBytes ctx) {
            return ctx.getBytes();
        }
    }

    private abstract static class MyByteArrayEvent {
        private final byte[] data;

        private MyByteArrayEvent(byte[] data) {
            this.data = data;
        }

        public byte[] getData() {
            return data;
        }
    }

    private static class MyByteArrayEventRead extends MyByteArrayEvent {
        private MyByteArrayEventRead(byte[] data) {
            super(data);
        }
    }

    private static class MyByteArrayEventCount extends MyByteArrayEvent {
        private MyByteArrayEventCount(byte[] data) {
            super(data);
        }
    }
}