/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.memory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import static java.util.Objects.requireNonNull;

public class MemoryPoolInfo
{
    private final MemoryPoolId id;
    private final long maxBytes;
    private final long freeBytes;

    @JsonCreator
    public MemoryPoolInfo(@JsonProperty("id") MemoryPoolId id,
            @JsonProperty("maxBytes") long maxBytes,
            @JsonProperty("freeBytes") long freeBytes)
    {
        this.id = requireNonNull(id, "id is null");
        this.maxBytes = maxBytes;
        this.freeBytes = freeBytes;
    }

    @JsonProperty
    public MemoryPoolId getId()
    {
        return id;
    }

    @JsonProperty
    public long getMaxBytes()
    {
        return maxBytes;
    }

    @JsonProperty
    public long getFreeBytes()
    {
        return freeBytes;
    }

    @Override
    public String toString()
    {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("maxBytes", maxBytes)
                .add("freeBytes", freeBytes)
                .toString();
    }
}
