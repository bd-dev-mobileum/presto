/*
 * Copyright 2004-present Facebook. All Rights Reserved.
 */
package com.facebook.presto.execution;

import com.facebook.presto.split.Split;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.google.common.collect.Multimap;

import java.net.URI;
import java.util.Set;

public interface RemoteTask
{
    String getTaskId();

    TaskInfo getTaskInfo();

    void start(Iterable<? extends Split> initialSplits);

    void addSplit(Split split);

    void noMoreSplits();

    void addExchangeLocations(Multimap<PlanNodeId, URI> exchangeLocations, boolean noMore);

    void addOutputBuffers(Set<String> outputBuffers, boolean noMore);

    void cancel();

    void updateState();
}
