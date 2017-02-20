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
package com.datatorrent.stram.plugin;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.yarn.conf.YarnConfiguration;

import com.google.common.collect.Lists;

import com.datatorrent.stram.StramAppContext;
import com.datatorrent.stram.StreamingContainerManager;
import com.datatorrent.stram.api.StramEvent;
import com.datatorrent.stram.api.StreamingContainerUmbilicalProtocol.ContainerHeartbeat;
import com.datatorrent.stram.api.plugin.ApexPlugin;
import com.datatorrent.stram.api.plugin.PluginContext;
import com.datatorrent.stram.api.plugin.PluginLocator;
import com.datatorrent.stram.api.plugin.PluginManager;
import com.datatorrent.stram.plan.logical.LogicalPlan;

import static com.datatorrent.stram.api.plugin.PluginManager.COMMIT_EVENT;
import static com.datatorrent.stram.api.plugin.PluginManager.HEARTBEAT;
import static com.datatorrent.stram.api.plugin.PluginManager.STRAM_EVENT;

/**
 * A top level ApexPluginManager which will handle multiple requests through
 * a thread pool.
 */
public abstract class AbstractApexPluginManager extends AbstractService implements ApexPluginManager
{
  private static final Logger LOG = LoggerFactory.getLogger(AbstractApexPluginManager.class);
  protected Collection<ApexPlugin> userServices = Lists.newArrayList();
  protected final StramAppContext appContext;
  protected final StreamingContainerManager dmgr;
  private final PluginLocator locator;
  protected transient Configuration launchConfig;
  protected FileContext fileContext;
  protected Map<ApexPlugin, PluginInfo> plugins = new HashMap<>();
  PluginContext pluginContext;
  private long lastCommittedWindowId = Long.MIN_VALUE;

  public AbstractApexPluginManager(PluginLocator locator, StramAppContext context, StreamingContainerManager dmgr)
  {
    super(AbstractApexPluginManager.class.getName());
    this.locator = locator;
    this.appContext = context;
    this.dmgr = dmgr;
    LOG.info("Creating appex service ");
  }

  private Configuration readLaunchConfiguration() throws IOException
  {
    LOG.info("Reading launch configuration file ");
    Path appPath = new Path(appContext.getApplicationPath());
    URI uri = appPath.toUri();
    Configuration config = new YarnConfiguration();
    fileContext = uri.getScheme() == null ? FileContext.getFileContext(config) : FileContext.getFileContext(uri, config);
    FSDataInputStream is = fileContext.open(new Path(appPath, LogicalPlan.LAUNCH_CONFIG_FILE_NAME));
    config.addResource(is);
    LOG.info("Read launch configuration");
    return config;
  }

  @Override
  protected void serviceInit(Configuration conf) throws Exception
  {
    super.serviceInit(conf);
    this.launchConfig = readLaunchConfiguration();
    pluginContext = new DefaultPluginContextImpl(dmgr, readLaunchConfiguration());
    if (locator != null) {
      Collection<ApexPlugin> plugins = locator.discoverPlugins();
      if (plugins != null) {
        userServices.addAll(plugins);
      }
    }

    super.serviceStart();
    for (ApexPlugin plugin : userServices) {
      plugin.init(new PluginManagerImpl(plugin));
    }
  }

  class PluginInfo
  {
    final ApexPlugin plugin;
    final Set<PluginManager.RegistrationType<?>> registrations = new HashSet<>();
    PluginManager.Handler<ContainerHeartbeat> heartbeatHandler;
    PluginManager.Handler<StramEvent> eventHandler;
    public PluginManager.Handler<Long> commitHandler;

    public PluginInfo(ApexPlugin plugin)
    {
      this.plugin = plugin;
    }
  }

  PluginInfo getPluginInfo(ApexPlugin plugin)
  {
    PluginInfo pInfo = plugins.get(plugin);
    if (pInfo == null) {
      pInfo = new PluginInfo(plugin);
      plugins.put(plugin, pInfo);
    }
    return pInfo;
  }

  public <T> void register(PluginManager.RegistrationType<T> type, PluginManager.Handler<T> handler, ApexPlugin owner)
  {
    PluginInfo pInfo = getPluginInfo(owner);
    boolean fresh = pInfo.registrations.add(type);
    if (!fresh) {
      LOG.warn("Handler already registered for the event type {} by plugin {}", type, owner);
      return;
    }
    if (type == HEARTBEAT) {
      LOG.info("setting hearbeat listenr for {} pInfo {}", pInfo.plugin, pInfo);
      pInfo.heartbeatHandler = (PluginManager.Handler<ContainerHeartbeat>)handler;
    } else if (type == STRAM_EVENT) {
      pInfo.eventHandler = (PluginManager.Handler<StramEvent>)handler;
    } else if (type == COMMIT_EVENT) {
      pInfo.commitHandler = (PluginManager.Handler<Long>)handler;
    }
  }

  public void addPlugin(ApexPlugin obj)
  {
    userServices.add(obj);
  }

  public abstract void submit(Runnable task);

  @Override
  public void setCommittedWindowId(long committedWindowId)
  {
    if (lastCommittedWindowId != committedWindowId) {
      dispatchCommittedWindowId(committedWindowId);
      lastCommittedWindowId = committedWindowId;
    }
  }

  public abstract void dispatchCommittedWindowId(long lastCommittedWindowId);

  class PluginManagerImpl implements PluginManager
  {
    private final ApexPlugin owner;

    PluginManagerImpl(ApexPlugin plugin)
    {
      this.owner = plugin;
    }

    @Override
    public <T> void register(RegistrationType<T> type, Handler<T> handler)
    {
      AbstractApexPluginManager.this.register(type, handler, owner);
    }

    @Override
    public void submit(Runnable task)
    {
      AbstractApexPluginManager.this.submit(task);
    }

    @Override
    public StramAppContext getApplicationContext()
    {
      return AbstractApexPluginManager.this.appContext;
    }

    @Override
    public PluginContext getPluginContext()
    {
      return AbstractApexPluginManager.this.pluginContext;
    }
  }
}
