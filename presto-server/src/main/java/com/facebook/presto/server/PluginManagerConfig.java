/*
 * Copyright 2004-present Facebook. All Rights Reserved.
 */
package com.facebook.presto.server;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import io.airlift.configuration.Config;
import io.tesla.aether.TeslaAether;

import javax.validation.constraints.NotNull;

import java.io.File;
import java.util.List;

public class PluginManagerConfig
{
    private File installedPluginsDir = new File("plugin");
    private List<String> plugins;
    private File pluginConfigurationDir = new File("etc/");
    private String mavenLocalRepository = TeslaAether.DEFAULT_LOCAL_REPOSITORY.getAbsolutePath();
    private List<String> mavenRemoteRepository = ImmutableList.of(TeslaAether.DEFAULT_REMOTE_REPOSITORY);

    public File getInstalledPluginsDir()
    {
        return installedPluginsDir;
    }

    @Config("plugin.dir")
    public PluginManagerConfig setInstalledPluginsDir(File installedPluginsDir)
    {
        this.installedPluginsDir = installedPluginsDir;
        return this;
    }

    public List<String> getPlugins()
    {
        return plugins;
    }

    public PluginManagerConfig setPlugins(List<String> plugins)
    {
        this.plugins = plugins;
        return this;
    }

    @Config("plugin.bundles")
    public PluginManagerConfig setPlugins(String plugins)
    {
        if (plugins == null) {
            this.plugins = null;
        } else {
            this.plugins = ImmutableList.copyOf(Splitter.on(',').omitEmptyStrings().trimResults().split(plugins));
        }
        return this;
    }

    @NotNull
    public File getPluginConfigurationDir()
    {
        return pluginConfigurationDir;
    }

    @Config("plugin.config-dir")
    public PluginManagerConfig setPluginConfigurationDir(File pluginConfigurationDir)
    {
        this.pluginConfigurationDir = pluginConfigurationDir;
        return this;
    }

    @NotNull
    public String getMavenLocalRepository()
    {
        return mavenLocalRepository;
    }

    @Config("maven.repo.local")
    public PluginManagerConfig setMavenLocalRepository(String mavenLocalRepository)
    {
        this.mavenLocalRepository = mavenLocalRepository;
        return this;
    }

    @NotNull
    public List<String> getMavenRemoteRepository()
    {
        return mavenRemoteRepository;
    }

    public PluginManagerConfig setMavenRemoteRepository(List<String> mavenRemoteRepository)
    {
        this.mavenRemoteRepository = mavenRemoteRepository;
        return this;
    }

    @Config("maven.repo.remote")
    public PluginManagerConfig setMavenRemoteRepository(String mavenRemoteRepository)
    {
        this.mavenRemoteRepository = ImmutableList.copyOf(Splitter.on(',').omitEmptyStrings().trimResults().split(mavenRemoteRepository));
        return this;
    }
}
