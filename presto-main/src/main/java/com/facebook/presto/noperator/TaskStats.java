package com.facebook.presto.noperator;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import org.joda.time.DateTime;

import javax.annotation.Nullable;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public class TaskStats
{
    private final DateTime createTime;
    private final DateTime startTime;
    private final DateTime endTime;

    private final Duration elapsedTime;
    private final Duration queuedTime;

    private final int totalDrivers;
    private final int queuedDrivers;
    private final int startedDrivers;
    private final int runningDrivers;
    private final int completedDrivers;

    private final DataSize memoryReservation;

    private final Duration totalScheduledTime;
    private final Duration totalCpuTime;
    private final Duration totalUserTime;
    private final Duration totalBlockedTime;

    private final DataSize inputDataSize;
    private final long inputPositions;

    private final DataSize outputDataSize;
    private final long outputPositions;

    private final List<PipelineStats> pipelines;

    @JsonCreator
    public TaskStats(
            @JsonProperty("createTime") DateTime createTime,
            @JsonProperty("startTime") DateTime startTime,
            @JsonProperty("endTime") DateTime endTime,
            @JsonProperty("elapsedTime") Duration elapsedTime,
            @JsonProperty("queuedTime") Duration queuedTime,

            @JsonProperty("totalDrivers") int totalDrivers,
            @JsonProperty("queuedDrivers") int queuedDrivers,
            @JsonProperty("startedDrivers") int startedDrivers,
            @JsonProperty("runningDrivers") int runningDrivers,
            @JsonProperty("completedDrivers") int completedDrivers,

            @JsonProperty("memoryReservation") DataSize memoryReservation,

            @JsonProperty("totalScheduledTime") Duration totalScheduledTime,
            @JsonProperty("totalCpuTime") Duration totalCpuTime,
            @JsonProperty("totalUserTime") Duration totalUserTime,
            @JsonProperty("totalBlockedTime") Duration totalBlockedTime,

            @JsonProperty("inputDataSize") DataSize inputDataSize,
            @JsonProperty("inputPositions") long inputPositions,

            @JsonProperty("outputDataSize") DataSize outputDataSize,
            @JsonProperty("outputPositions") long outputPositions,

            @JsonProperty("pipelines") List<PipelineStats> pipelines)
    {
        this.createTime = checkNotNull(createTime, "createTime is null");
        this.startTime = startTime;
        this.endTime = endTime;
        this.elapsedTime = checkNotNull(elapsedTime, "elapsedTime is null");
        this.queuedTime = checkNotNull(queuedTime, "queuedTime is null");

        checkArgument(totalDrivers >= 0, "totalDrivers is negative");
        this.totalDrivers = totalDrivers;
        checkArgument(queuedDrivers >= 0, "queuedDrivers is negative");
        this.queuedDrivers = queuedDrivers;
        checkArgument(startedDrivers >= 0, "startedDrivers is negative");
        this.startedDrivers = startedDrivers;
        checkArgument(runningDrivers >= 0, "runningDrivers is negative");
        this.runningDrivers = runningDrivers;
        checkArgument(completedDrivers >= 0, "completedDrivers is negative");
        this.completedDrivers = completedDrivers;

        this.memoryReservation = checkNotNull(memoryReservation, "memoryReservation is null");

        this.totalScheduledTime = checkNotNull(totalScheduledTime, "totalScheduledTime is null");
        this.totalCpuTime = checkNotNull(totalCpuTime, "totalCpuTime is null");
        this.totalUserTime = checkNotNull(totalUserTime, "totalUserTime is null");
        this.totalBlockedTime = checkNotNull(totalBlockedTime, "totalBlockedTime is null");

        this.inputDataSize = checkNotNull(inputDataSize, "inputDataSize is null");
        checkArgument(inputPositions >= 0, "inputPositions is negative");
        this.inputPositions = inputPositions;

        this.outputDataSize = checkNotNull(outputDataSize, "outputDataSize is null");
        checkArgument(outputPositions >= 0, "outputPositions is negative");
        this.outputPositions = outputPositions;

        this.pipelines = ImmutableList.copyOf(checkNotNull(pipelines, "pipelines is null"));
    }

    @JsonProperty
    public DateTime getCreateTime()
    {
        return createTime;
    }

    @Nullable
    @JsonProperty
    public DateTime getStartTime()
    {
        return startTime;
    }

    @Nullable
    @JsonProperty
    public DateTime getEndTime()
    {
        return endTime;
    }

    @JsonProperty
    public Duration getElapsedTime()
    {
        return elapsedTime;
    }

    @JsonProperty
    public Duration getQueuedTime()
    {
        return queuedTime;
    }

    @JsonProperty
    public int getTotalDrivers()
    {
        return totalDrivers;
    }

    @JsonProperty
    public int getQueuedDrivers()
    {
        return queuedDrivers;
    }

    @JsonProperty
    public int getStartedDrivers()
    {
        return startedDrivers;
    }

    @JsonProperty
    public int getRunningDrivers()
    {
        return runningDrivers;
    }

    @JsonProperty
    public int getCompletedDrivers()
    {
        return completedDrivers;
    }

    @JsonProperty
    public DataSize getMemoryReservation()
    {
        return memoryReservation;
    }

    @JsonProperty
    public Duration getTotalScheduledTime()
    {
        return totalScheduledTime;
    }

    @JsonProperty
    public Duration getTotalCpuTime()
    {
        return totalCpuTime;
    }

    @JsonProperty
    public Duration getTotalUserTime()
    {
        return totalUserTime;
    }

    @JsonProperty
    public Duration getTotalBlockedTime()
    {
        return totalBlockedTime;
    }

    @JsonProperty
    public DataSize getInputDataSize()
    {
        return inputDataSize;
    }

    @JsonProperty
    public long getInputPositions()
    {
        return inputPositions;
    }

    @JsonProperty
    public DataSize getOutputDataSize()
    {
        return outputDataSize;
    }

    @JsonProperty
    public long getOutputPositions()
    {
        return outputPositions;
    }

    @JsonProperty
    public List<PipelineStats> getPipelines()
    {
        return pipelines;
    }
}
