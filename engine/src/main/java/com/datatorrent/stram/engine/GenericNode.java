/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.datatorrent.stram.engine;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.apex.api.ControlAwareDefaultInputPort;
import org.apache.apex.api.operator.ControlTuple;
import org.apache.commons.lang.UnhandledException;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Maps;

import com.datatorrent.api.ControlTupleEnabledSink;
import com.datatorrent.api.Operator;
import com.datatorrent.api.Operator.IdleTimeHandler;
import com.datatorrent.api.Operator.InputPort;
import com.datatorrent.api.Operator.ProcessingMode;
import com.datatorrent.api.Operator.ShutdownException;
import com.datatorrent.api.Sink;
import com.datatorrent.api.annotation.Stateless;
import com.datatorrent.bufferserver.packet.MessageType;
import com.datatorrent.bufferserver.util.Codec;
import com.datatorrent.stram.api.StreamingContainerUmbilicalProtocol.ContainerStats;
import com.datatorrent.stram.debug.TappedReservoir;
import com.datatorrent.stram.plan.logical.LogicalPlan;
import com.datatorrent.stram.plan.logical.Operators;
import com.datatorrent.stram.tuple.CustomControlTuple;
import com.datatorrent.stram.tuple.Tuple;

/**
 * The base class for node implementation<p>
 * <br>
 * Implements the base interface {@link com.datatorrent.stram.engine.Node}<br>
 * <br>
 * This is the basic functional block of the DAG. It is responsible for the following<br>
 * It emits and consumes tuples<br>
 * Upon window boundary it does house cleaning, state sync up etc<br>
 * Interacts with Stram with a heartbeat protocol<br>
 * <br>
 *
 * @since 0.3.2
 */
public class GenericNode extends Node<Operator>
{
  protected final HashMap<String, SweepableReservoir> inputs = new HashMap<>();
  protected ArrayList<DeferredInputConnection> deferredInputConnections = new ArrayList<>();
  protected Map<SweepableReservoir,Sink> reservoirPortMap = Maps.newHashMap();

  @Override
  @SuppressWarnings("unchecked")
  public void addSinks(Map<String, Sink<Object>> sinks)
  {
    for (Entry<String, Sink<Object>> e : sinks.entrySet()) {
      SweepableReservoir original = inputs.get(e.getKey());
      if (original instanceof TappedReservoir) {
        TappedReservoir tr = (TappedReservoir)original;
        tr.add(e.getValue());
      } else if (original != null) {
        TappedReservoir tr = new TappedReservoir(original, e.getValue());
        inputs.put(e.getKey(), tr);
      }
    }

    super.addSinks(sinks);
  }

  @Override
  public void removeSinks(Map<String, Sink<Object>> sinks)
  {
    for (Entry<String, Sink<Object>> e : sinks.entrySet()) {
      SweepableReservoir reservoir = inputs.get(e.getKey());
      if (reservoir instanceof TappedReservoir) {
        TappedReservoir tr = (TappedReservoir)reservoir;
        tr.remove(e.getValue());
        if (tr.getSinks().length == 0) {
          tr.reservoir.setSink(tr.setSink(null));
          inputs.put(e.getKey(), tr.reservoir);
        }
      }
    }

    super.removeSinks(sinks);
  }

  public GenericNode(Operator operator, OperatorContext context)
  {
    super(operator, context);
  }

  @SuppressWarnings("unchecked")
  public InputPort<Object> getInputPort(String port)
  {
    return (InputPort<Object>)descriptor.inputPorts.get(port).component;
  }

  @Override
  public void connectInputPort(String port, final SweepableReservoir reservoir)
  {
    if (reservoir == null) {
      throw new IllegalArgumentException("Reservoir cannot be null for port '" + port + "' on operator '" + operator + "'");
    }

    InputPort<Object> inputPort = getInputPort(port);
    if (inputPort == null) {
      throw new IllegalArgumentException("Port '" + port + "' does not exist on operator '" + operator + "'");
    }

    if (inputs.containsKey(port)) {
      deferredInputConnections.add(new DeferredInputConnection(port, reservoir));
    } else {
      inputPort.setConnected(true);
      inputs.put(port, reservoir);
      reservoir.setSink(inputPort.getSink());
    }
  }

  /**
   * @param endWindowTuple the value of endWindowTuple
   */
  protected void processEndWindow(Tuple endWindowTuple)
  {
    logger.info("Calling process end window {} windowID {}", id, endWindowTuple.getWindowId());
    insideWindow = false;
    operator.endWindow();
    endWindowEmitTime = System.currentTimeMillis();

    if (endWindowTuple == null) {
      emitEndWindow();
    } else {
      forward(endWindowTuple);
    }

    ContainerStats.OperatorStats stats = new ContainerStats.OperatorStats();
    reportStats(stats, currentWindowId);
    if (!insideWindow) {
      stats.metrics = collectMetrics();
    }
    handleRequests(currentWindowId);
  }


  class TupleTracker
  {
    final Tuple tuple;
    SweepableReservoir[] ports;

    TupleTracker(Tuple base, int count)
    {
      tuple = base;
      ports = new SweepableReservoir[count];
    }
  }

  boolean insideWindow;
  boolean doCheckpoint;
  long lastCheckpointWindowId = Stateless.WINDOW_ID;

  @Override
  public void activate()
  {
    super.activate();
  }

  private boolean isInputPortConnectedToDelayOperator(String portName)
  {
    Operators.PortContextPair<InputPort<?>> pcPair = descriptor.inputPorts.get(portName);
    if (pcPair == null || pcPair.context == null) {
      return false;
    }
    return pcPair.context.getValue(LogicalPlan.IS_CONNECTED_TO_DELAY_OPERATOR);
  }

  enum Action
  {
    BREAK,
    CONTINUE,
    PORT_MAPPING_CHANGED
  }

  long expectingBeginWindows = 0;
  long receivedEndWindow = 0;
  long totalQueues = 0;
  boolean delay;
  ArrayList<Map.Entry<String, SweepableReservoir>> activeQueues = new ArrayList<>();
  Map<SweepableReservoir, LinkedHashSet<CustomControlTuple>> immediateDeliveryTuples = Maps.newHashMap();
  Map<SweepableReservoir,LinkedHashSet<CustomControlTuple>> endWindowDeliveryTuples = Maps.newHashMap();

  /**
   * Forward tuple on all out going sinks.
   * @param t
   */
  void forward(Tuple t)
  {
    for (int s = sinks.length; s-- > 0;) {
      sinks[s].put(t);
    }
  }

  Action handleBeginWindowTuple(String portName, SweepableReservoir port, Tuple t)
  {
    Preconditions.checkArgument(t.getType() == MessageType.BEGIN_WINDOW);
    totalQueues = inputs.size();
    logger.info("Begin window tuple id {} received {} expecting {} total {}", id, t.getWindowId(), expectingBeginWindows, totalQueues);
    if (expectingBeginWindows == totalQueues) {
      logger.info("Starting window {} in operator {}", t.getWindowId(), id);
      currentWindowId = t.getWindowId();
      port.remove();
      expectingBeginWindows--;
      forward(t);
      insideWindow = true;
      operator.beginWindow(currentWindowId);
    } else if (t.getWindowId() == currentWindowId) {
      port.remove();
      expectingBeginWindows--;
    } else {
      return handleOutOfOrderBeginWindow(portName, port, t);
    }
    return Action.CONTINUE;
  }

  Action handleOutOfOrderBeginWindow(String portName, SweepableReservoir port, Tuple t)
  {
    // there is a begin window tuple, but other port received begin window with different
    // windowId. This is allowed only in AT_MOST_ONCE processing, in other mode this is
    // a serious error.
    // This is special case, when operator is working in AT_MOST_ONCE processing mode.
    port.remove();
    if (PROCESSING_MODE == ProcessingMode.AT_MOST_ONCE) {
      if (t.getWindowId() < currentWindowId) {
        // skip messages from this port till we get current window id.
        Sink<Object> sink = port.setSink(Sink.BLACKHOLE);
        deferredInputConnections.add(0, new DeferredInputConnection(
            portName, port));
        WindowIdActivatedReservoir wiar = new WindowIdActivatedReservoir(portName, port, currentWindowId);
        wiar.setSink(sink);
        inputs.put(portName, wiar);
        activeQueues.add(new AbstractMap.SimpleEntry<>(portName, wiar));
        return Action.PORT_MAPPING_CHANGED;
      } else {
        expectingBeginWindows--;
        if (++receivedEndWindow == totalQueues) {
          processEndWindow(null);
          activeQueues.addAll(inputs.entrySet());
          expectingBeginWindows = activeQueues.size();
          return Action.PORT_MAPPING_CHANGED;
        }
      }
    } else {
      logger.error("Catastrophic Error: Out of sequence {} tuple {} on port {} while expecting {}",
          t.getType(), Codec.getStringWindowId(t.getWindowId()), port, Codec.getStringWindowId(currentWindowId));
      System.exit(2);
    }
    return null;
  }

  void deliverControlTuples()
  {
    for (Entry<SweepableReservoir, LinkedHashSet<CustomControlTuple>> portSet : endWindowDeliveryTuples.entrySet()) {
      Sink activeSink = reservoirPortMap.get(portSet.getKey());
      // activeSink may not be null
      if (activeSink instanceof ControlAwareDefaultInputPort) {
        ControlTupleEnabledSink sink = (ControlTupleEnabledSink)activeSink;
        for (CustomControlTuple cct : portSet.getValue()) {
          if (!sink.putControl((ControlTuple)cct.getUserObject())) {
            // operator cannot handle control tuple; forward to sinks
            forwardToSinks(delay, cct);
          }
        }
      } else {
        // Not a ControlAwarePort. Operator cannot handle a custom control tuple.
        for (CustomControlTuple cct : portSet.getValue()) {
          forwardToSinks(delay, cct);
        }
      }
    }

    immediateDeliveryTuples.clear();
    endWindowDeliveryTuples.clear();

  }

  Action handleEndWindowTuple(Map.Entry<String, SweepableReservoir> port, Tuple t)
  {
    Preconditions.checkArgument(t.getType() == MessageType.END_WINDOW);
    SweepableReservoir activePort = port.getValue();
    logger.info("handleEndWindowTuple id {} currentWid {} wid {} receivedEndWindows {} totalQueue {}",
        id, currentWindowId, t.getWindowId(),receivedEndWindow, totalQueues);
    if (t.getWindowId() == currentWindowId) {
      activePort.remove();
      endWindowDequeueTimes.put(activePort, System.currentTimeMillis());
      if (++receivedEndWindow == totalQueues) {
        assert (activeQueues.isEmpty());
        if (reservoirPortMap.isEmpty()) {
          populateReservoirInputPortMap();
        }
        deliverControlTuples();
        processEndWindow(t);
        activeQueues.addAll(inputs.entrySet());
        expectingBeginWindows = activeQueues.size();
        receivedEndWindow = 0;
        return  Action.PORT_MAPPING_CHANGED;
      }
    }
    return Action.CONTINUE;
  }

  Action handleCustomControlTuple(Map.Entry<String, SweepableReservoir> port, Tuple t)
  {
    Preconditions.checkArgument(t.getType() == MessageType.CUSTOM_CONTROL);
    SweepableReservoir activePort = port.getValue();
    activePort.remove();
    /* All custom control tuples are expected to be arriving in the current window only.*/
    /* Buffer control tuples until end of the window */
    CustomControlTuple cct = (CustomControlTuple)t;
    ControlTuple udct = (ControlTuple)cct.getUserObject();
    boolean forward = false;

    // Handle Immediate Delivery Control Tuples
    if (udct.getDeliveryType().equals(ControlTuple.DeliveryType.IMMEDIATE)) {
      if (!isDuplicate(immediateDeliveryTuples.get(activePort), cct)) {
        // Forward immediately
        if (reservoirPortMap.isEmpty()) {
          populateReservoirInputPortMap();
        }

        Sink activeSink = reservoirPortMap.get(activePort);
        // activeSink may not be null
        if (activeSink instanceof ControlAwareDefaultInputPort) {
          ControlTupleEnabledSink sink = (ControlTupleEnabledSink)activeSink;
          if (!sink.putControl((ControlTuple)cct.getUserObject())) {
            forward = true;
          }
        } else {
          forward = true;
        }

        if (forward) {
          forwardToSinks(delay, cct);
        }
        // Add to set
        if (!immediateDeliveryTuples.containsKey(activePort)) {
          immediateDeliveryTuples.put(activePort, new LinkedHashSet<CustomControlTuple>());
        }
        immediateDeliveryTuples.get(activePort).add(cct);
      }
    } else {
      // Buffer EndWindow Delivery Control Tuples
      if (!endWindowDeliveryTuples.containsKey(activePort)) {
        endWindowDeliveryTuples.put(activePort, new LinkedHashSet<CustomControlTuple>());
      }
      if (!isDuplicate(endWindowDeliveryTuples.get(activePort), cct)) {
        endWindowDeliveryTuples.get(activePort).add(cct);
      }
    }
    return Action.CONTINUE;
  }

  Action handleCheckpointTuple(Map.Entry<String, SweepableReservoir> port, Tuple t)
  {
    Preconditions.checkArgument(t.getType() == MessageType.CHECKPOINT);
    SweepableReservoir activePort = port.getValue();
    activePort.remove();
    long checkpointWindow = t.getWindowId();
    if (lastCheckpointWindowId < checkpointWindow) {
      checkpoint(checkpointWindow);
      lastCheckpointWindowId = checkpointWindow;
      forwardToSinks(delay, t);
    }
    return Action.CONTINUE;
  }

  /**
   * Originally this method was defined in an attempt to implement the interface Runnable.
   *
   * Note that activate does not return as long as there is useful workload for the node.
   */
  @Override
  @SuppressWarnings({"SleepWhileInLoop", "UseSpecificCatch", "BroadCatchBlock", "TooBroadCatch"})
  public final void run()
  {
    doCheckpoint = false;

    final long maxSpinMillis = context.getValue(OperatorContext.SPIN_MILLIS);
    long spinMillis = 0;
    final boolean handleIdleTime = operator instanceof IdleTimeHandler;
    int totalQueues = inputs.size();
    int regularQueues = totalQueues;
    // regularQueues is the number of queues that are not connected to a DelayOperator
    for (String portName : inputs.keySet()) {
      if (isInputPortConnectedToDelayOperator(portName)) {
        regularQueues--;
      }
    }

    activeQueues.addAll(inputs.entrySet());
    expectingBeginWindows = activeQueues.size();
    long firstWindowId = -1;

    TupleTracker tracker;
    LinkedList<TupleTracker> resetTupleTracker = new LinkedList<>();

    try {
      do {
        Iterator<Map.Entry<String, SweepableReservoir>> buffers = activeQueues.iterator();
      activequeue:
        while (buffers.hasNext()) {
          Map.Entry<String, SweepableReservoir> activePortEntry = buffers.next();
          SweepableReservoir activePort = activePortEntry.getValue();
          Tuple t = activePort.sweep();
          if (t != null) {
            spinMillis = 0;
            boolean delay = (operator instanceof Operator.DelayOperator);
            long windowAhead = 0;
            if (delay) {
              windowAhead = WindowGenerator.getAheadWindowId(t.getWindowId(), firstWindowMillis, windowWidthMillis, 1);
            }
            switch (t.getType()) {
              case BEGIN_WINDOW:
                Action ret = handleBeginWindowTuple(activePortEntry.getKey(), activePort, t);
                if (ret == Action.PORT_MAPPING_CHANGED) {
                  break activequeue;
                }
                break;

              case END_WINDOW:
                logger.info("end window received id {} window {}", id, t.getWindowId());
                buffers.remove();
                ret = handleEndWindowTuple(activePortEntry, t);
                if (ret == Action.PORT_MAPPING_CHANGED) {
                  break activequeue;
                }
                break;

              case CUSTOM_CONTROL:
                handleCustomControlTuple(activePortEntry, t);
                break;

              case CHECKPOINT:
                handleCheckpointTuple(activePortEntry, t);
                break;

              case END_STREAM:
                activePort.remove();
                buffers.remove();
                if (firstWindowId == -1) {
                  // this is for recovery from a checkpoint for DelayOperator
                  if (delay) {
                    // if it's a DelayOperator and this is the first RESET_WINDOW (start) or END_STREAM (recovery),
                    // fabricate the first window
                    fabricateFirstWindow((Operator.DelayOperator)operator, windowAhead);
                  }
                  firstWindowId = t.getWindowId();
                }
                for (Iterator<Entry<String, SweepableReservoir>> it = inputs.entrySet().iterator(); it.hasNext(); ) {
                  Entry<String, SweepableReservoir> e = it.next();
                  if (e.getValue() == activePort) {
                    if (!descriptor.inputPorts.isEmpty()) {
                      descriptor.inputPorts.get(e.getKey()).component.setConnected(false);
                    }
                    it.remove();

                    /* check the deferred connection list for any new port that should be connected here */
                    Iterator<DeferredInputConnection> dici = deferredInputConnections.iterator();
                    while (dici.hasNext()) {
                      DeferredInputConnection dic = dici.next();
                      if (e.getKey().equals(dic.portname)) {
                        connectInputPort(dic.portname, dic.reservoir);
                        dici.remove();
                        activeQueues.add(new AbstractMap.SimpleEntry<>(dic.portname, dic.reservoir));
                        break activequeue;
                      }
                    }

                    break;
                  }
                }

                /**
                 * We are not going to receive begin window on this ever!
                 */
                expectingBeginWindows--;

                /**
                 * Since one of the operators we care about it gone, we should relook at our ports.
                 * We need to make sure that the END_STREAM comes outside of the window.
                 */
                regularQueues--;
                totalQueues--;

                boolean break_activequeue = false;
                if (regularQueues == 0) {
                  alive = false;
                  break_activequeue = true;
                } else if (activeQueues.isEmpty()) {
                  assert (!inputs.isEmpty());
                  processEndWindow(null);
                  activeQueues.addAll(inputs.entrySet());
                  expectingBeginWindows = activeQueues.size();
                  break_activequeue = true;
                }

                if (break_activequeue) {
                  break activequeue;
                }
                break;

              default:
                throw new UnhandledException("Unrecognized Control Tuple", new IllegalArgumentException(t.toString()));
            }
          }
        }

        if (activeQueues.isEmpty() && alive) {
          logger.error("Catastrophic Error: Invalid State - the operator {} blocked forever!", id);
          System.exit(2);
        } else {
          boolean need2sleep = true;
          for (Map.Entry<String, SweepableReservoir> cb : activeQueues) {
            need2sleep = cb.getValue().isEmpty();
            if (!need2sleep) {
              spinMillis = 0;
              break;
            }
          }

          if (need2sleep) {
            if (handleIdleTime && insideWindow) {
              ((IdleTimeHandler)operator).handleIdleTime();
            } else {
              Thread.sleep(spinMillis);
              spinMillis = Math.min(maxSpinMillis, spinMillis + 1);
            }
          }
        }
      } while (alive);
    } catch (ShutdownException se) {
      logger.debug("Shutdown requested by the operator when alive = {}.", alive);
      alive = false;
    } catch (Throwable cause) {
      synchronized (this) {
        if (alive) {
          throw Throwables.propagate(cause);
        }
      }

      Throwable rootCause = cause;
      while (rootCause != null) {
        if (rootCause instanceof InterruptedException) {
          break;
        }
        rootCause = rootCause.getCause();
      }

      if (rootCause == null) {
        throw Throwables.propagate(cause);
      } else {
        logger.debug("Ignoring InterruptedException after shutdown", cause);
      }
    }

    /**
     * TODO: If shutdown and inside window provide alternate way of notifying the operator in such ways
     * TODO: as using a listener callback
     */
    if (insideWindow && !shutdown) {
      operator.endWindow();
      endWindowEmitTime = System.currentTimeMillis();
      if (++checkpointWindowCount == CHECKPOINT_WINDOW_COUNT) {
        checkpointWindowCount = 0;
        checkpoint(currentWindowId);
      }

      ContainerStats.OperatorStats stats = new ContainerStats.OperatorStats();
      fixEndWindowDequeueTimesBeforeDeactivate();
      reportStats(stats, currentWindowId);
      stats.metrics = collectMetrics();
      handleRequests(currentWindowId);
    }

  }

  protected void forwardToSinks(boolean delay, Object o)
  {
    if (!delay) {
      for (int s = sinks.length; s-- > 0; ) {
        sinks[s].put(o);
      }
      controlTupleCount++;
    }
  }

  /**
   * Populate {@link #reservoirPortMap} with information on which reservoirs are connected to which input ports
   */
  protected void populateReservoirInputPortMap()
  {
    for (Entry<String,Operators.PortContextPair<InputPort<?>>> entry : descriptor.inputPorts.entrySet()) {
      if (entry.getValue().component != null && entry.getValue().component instanceof InputPort) {
        if (inputs.containsKey(entry.getKey())) {
          reservoirPortMap.put(inputs.get(entry.getKey()), entry.getValue().component.getSink());
        }
      }
    }
  }

  protected boolean isDuplicate(LinkedHashSet<CustomControlTuple> set, CustomControlTuple t)
  {
    if (set == null || set.isEmpty()) {
      return false;
    }
    for (CustomControlTuple cct : set) {
      if (cct.getUid().equals(t.getUid())) {
        return true;
      }
    }
    return false;
  }

  private void fabricateFirstWindow(Operator.DelayOperator delayOperator, long windowAhead)
  {
    Tuple beginWindowTuple = new Tuple(MessageType.BEGIN_WINDOW, windowAhead);
    Tuple endWindowTuple = new Tuple(MessageType.END_WINDOW, windowAhead);
    for (Sink<Object> sink : outputs.values()) {
      sink.put(beginWindowTuple);
    }
    controlTupleCount++;
    delayOperator.firstWindow();
    for (Sink<Object> sink : outputs.values()) {
      sink.put(endWindowTuple);
    }
    controlTupleCount++;
  }

  /**
   * End window dequeue times may not have been saved for all the input ports during deactivate,
   * so save them for reporting. SPOI-1324.
   */
  private void fixEndWindowDequeueTimesBeforeDeactivate()
  {
    long endWindowDequeueTime = System.currentTimeMillis();
    for (SweepableReservoir sr : inputs.values()) {
      if (endWindowDequeueTimes.get(sr) == null) {
        endWindowDequeueTimes.put(sr, endWindowDequeueTime);
      }
    }
  }

  @Override
  protected void reportStats(ContainerStats.OperatorStats stats, long windowId)
  {
    ArrayList<ContainerStats.OperatorStats.PortStats> ipstats = new ArrayList<>();
    for (Entry<String, SweepableReservoir> e : inputs.entrySet()) {
      SweepableReservoir ar = e.getValue();
      ContainerStats.OperatorStats.PortStats portStats = new ContainerStats.OperatorStats.PortStats(e.getKey());
      portStats.queueSize = ar.size(DATA_TUPLE_AWARE);
      portStats.tupleCount = ar.getCount(true);
      portStats.endWindowTimestamp = endWindowDequeueTimes.get(e.getValue());
      ipstats.add(portStats);
    }
    stats.inputPorts = ipstats;
    super.reportStats(stats, windowId);
  }

  protected class DeferredInputConnection
  {
    String portname;
    SweepableReservoir reservoir;

    DeferredInputConnection(String portname, SweepableReservoir reservoir)
    {
      this.portname = portname;
      this.reservoir = reservoir;
    }
  }

  private static final Logger logger = LoggerFactory.getLogger(GenericNode.class);
}
