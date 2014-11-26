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
package com.facebook.presto.operator.window;

import com.facebook.presto.operator.PagesIndex;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.type.Type;
import com.google.common.primitives.Ints;

import java.util.List;

import static com.facebook.presto.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.facebook.presto.util.Failures.checkCondition;

public class LagFunction
        extends SimpleWindowFunction
{
    public static class BigintLagFunction
            extends LagFunction
    {
        public BigintLagFunction(List<Integer> argumentChannels)
        {
            super(BIGINT, argumentChannels);
        }
    }

    public static class BooleanLagFunction
            extends LagFunction
    {
        public BooleanLagFunction(List<Integer> argumentChannels)
        {
            super(BOOLEAN, argumentChannels);
        }
    }

    public static class DoubleLagFunction
            extends LagFunction
    {
        public DoubleLagFunction(List<Integer> argumentChannels)
        {
            super(DOUBLE, argumentChannels);
        }
    }

    public static class VarcharLagFunction
            extends LagFunction
    {
        public VarcharLagFunction(List<Integer> argumentChannels)
        {
            super(VARCHAR, argumentChannels);
        }
    }

    private final Type type;
    private final int valueChannel;
    private final int offsetChannel;
    private final int defaultChannel;

    private int partitionStartPosition;
    private int currentPosition;
    private PagesIndex pagesIndex;

    protected LagFunction(Type type, List<Integer> argumentChannels)
    {
        this.type = type;
        this.valueChannel = argumentChannels.get(0);
        this.offsetChannel = (argumentChannels.size() > 1) ? argumentChannels.get(1) : -1;
        this.defaultChannel = (argumentChannels.size() > 2) ? argumentChannels.get(2) : -1;
    }

    @Override
    public Type getType()
    {
        return type;
    }

    @Override
    public void reset(int partitionStartPosition, int partitionRowCount, PagesIndex pagesIndex)
    {
        this.partitionStartPosition = partitionStartPosition;
        this.currentPosition = partitionStartPosition;
        this.pagesIndex = pagesIndex;
    }

    @Override
    public void processRow(BlockBuilder output, boolean newPeerGroup, int peerGroupCount)
    {
        if ((offsetChannel >= 0) && pagesIndex.isNull(offsetChannel, currentPosition)) {
            output.appendNull();
        }
        else {
            long offset = (offsetChannel < 0) ? 1 : pagesIndex.getLong(offsetChannel, currentPosition);
            checkCondition(offset >= 0, INVALID_FUNCTION_ARGUMENT, "Offset must be at least 0");

            long valuePosition = currentPosition - offset;

            if ((valuePosition >= partitionStartPosition) && (valuePosition <= currentPosition)) {
                pagesIndex.appendTo(valueChannel, Ints.checkedCast(valuePosition), output);
            }
            else {
                appendDefault(output);
            }
        }

        currentPosition++;
    }

    private void appendDefault(BlockBuilder output)
    {
        if (defaultChannel < 0) {
            output.appendNull();
        }
        else {
            pagesIndex.appendTo(defaultChannel, currentPosition, output);
        }
    }
}
