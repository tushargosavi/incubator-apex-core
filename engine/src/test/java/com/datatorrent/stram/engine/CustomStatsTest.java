/**
 * Copyright (c) 2012-2013 DataTorrent, Inc.
 * All rights reserved.
 */
package com.datatorrent.stram.engine;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datatorrent.api.Context.OperatorContext;
import com.datatorrent.api.DAG.Locality;
import com.datatorrent.api.Operator;
import com.datatorrent.api.Partitioner;
import com.datatorrent.api.Stats.OperatorStats;
import com.datatorrent.api.StatsListener;

import com.datatorrent.stram.StramLocalCluster;
import com.datatorrent.stram.engine.CustomStatsTest.TestOperator.TestOperatorStats;
import com.datatorrent.stram.engine.CustomStatsTest.TestOperator.TestStatsListener;
import com.datatorrent.stram.plan.logical.LogicalPlan;
import com.datatorrent.stram.support.StramTestSupport;

public class CustomStatsTest
{
  private static final Logger LOG = LoggerFactory.getLogger(CustomStatsTest.class);

  public static class TestOperator extends TestGeneratorInputOperator implements Partitioner<TestOperator>, StatsListener
  {
    static class TestOperatorStats implements Serializable
    {
      private String message;
      private boolean attributeListenerCalled;
      private static final long serialVersionUID = -8096838101190642798L;
      private boolean currentPropVal;
    }

    public static class TestStatsListener implements StatsListener, Serializable
    {
      private static final long serialVersionUID = 1L;
      private boolean lastPropVal;

      @Override
      public Response processStats(BatchedOperatorStats stats)
      {
        for (OperatorStats os : stats.getLastWindowedStats()) {
          Assert.assertNotNull("custom stats", os.counters);
          ((TestOperatorStats)os.counters).attributeListenerCalled = true;
          lastPropVal = ((TestOperatorStats)os.counters).currentPropVal;
        }
        Response rsp = new Response();
        rsp.operatorCommands = Lists.newArrayList(new SetPropertyCommand());
        return rsp;
      }

      public static class SetPropertyCommand implements OperatorCommand, Serializable
      {
        private static final long serialVersionUID = 1L;
        @Override
        public void execute(Operator oper, int arg1, long arg2) throws IOException
        {
          if (oper instanceof TestOperator) {
            LOG.debug("Setting property");
            ((TestOperator)oper).propVal = true;
          }
        }
      }
    }

    private transient OperatorContext context;
    private static Object lastCustomStats = null;
    private static Thread processStatsThread = null;
    private static Thread definePartitionsThread = null;
    private transient boolean propVal;

    @Override
    public void partitioned(Map<Integer, Partition<TestOperator>> partitions)
    {
    }

    @Override
    public Collection<Partition<TestOperator>> definePartitions(Collection<Partition<TestOperator>> partitions, int incrementalCapacity)
    {
      List<Partition<TestOperator>> newPartitions = Lists.newArrayList();
      newPartitions.addAll(partitions);

      for (Partition<?> p : partitions) {
        BatchedOperatorStats stats = p.getStats();
        if (stats != null) {
          definePartitionsThread = Thread.currentThread();
          for (OperatorStats os : stats.getLastWindowedStats()) {
            if (os.counters != null) {
              //LOG.debug("Custom stats: {}", os.counters);
              lastCustomStats = os.counters;
            }
          }
        }
      }
      return newPartitions;
    }

    @Override
    public void setup(OperatorContext context)
    {
      super.setup(context);
      this.context = context;
    }

    @Override
    public void endWindow()
    {
      super.endWindow();
      TestOperatorStats counters = new TestOperatorStats();
      counters.message = "interesting";
      counters.currentPropVal = this.propVal;
      context.setCounters(counters);
      //LOG.debug("set custom stats");
    }

    @Override
    public Response processStats(BatchedOperatorStats stats)
    {
      processStatsThread = Thread.currentThread();
      for (OperatorStats os : stats.getLastWindowedStats()) {
        Assert.assertNotNull("custom stats in listener", os.counters);
      }
      Response rsp = new Response();
      rsp.repartitionRequired = true; // trigger definePartitions
      return rsp;
    }

  }

  /**
   * Verify custom stats generated by operator are propagated and trigger repartition.
   *
   * @throws Exception
   */
  @Test
  @SuppressWarnings("SleepWhileInLoop")
  public void testCustomStatsPropagation() throws Exception
  {
    LogicalPlan dag = new LogicalPlan();
    dag.getAttributes().put(LogicalPlan.STREAMING_WINDOW_SIZE_MILLIS, 300);
    dag.getAttributes().put(LogicalPlan.CONTAINERS_MAX_COUNT, 1);

    TestOperator testOper = dag.addOperator("TestOperator", TestOperator.class);
    TestStatsListener sl = new TestStatsListener();
    dag.setAttribute(testOper, OperatorContext.STATS_LISTENERS, Lists.newArrayList((StatsListener)sl));
    //dag.setAttribute(testOper, OperatorContext.INITIAL_PARTITION_COUNT, 1);

    GenericTestOperator collector = dag.addOperator("Collector", new GenericTestOperator());
    dag.addStream("TestTuples", testOper.outport, collector.inport1).setLocality(Locality.CONTAINER_LOCAL);

    StramLocalCluster lc = new StramLocalCluster(dag);
    lc.runAsync();

    long startTms = System.currentTimeMillis();
    while (TestOperator.lastCustomStats == null && StramTestSupport.DEFAULT_TIMEOUT_MILLIS > System.currentTimeMillis() - startTms) {
      Thread.sleep(300);
      LOG.debug("Waiting for stats");
    }

    while (StramTestSupport.DEFAULT_TIMEOUT_MILLIS > System.currentTimeMillis() - startTms) {
      if (sl.lastPropVal) {
        break;
      }
      Thread.sleep(100);
      LOG.debug("Waiting for property set");
    }


    lc.shutdown();

    Assert.assertNotNull("custom stats received", TestOperator.lastCustomStats);
    Assert.assertEquals("custom stats message", "interesting", ((TestOperatorStats)TestOperator.lastCustomStats).message);
    Assert.assertTrue("attribute defined stats listener called", ((TestOperatorStats)TestOperator.lastCustomStats).attributeListenerCalled);
    Assert.assertSame("single thread", TestOperator.definePartitionsThread, TestOperator.processStatsThread);
    Assert.assertTrue("property set", sl.lastPropVal);
  }

}
