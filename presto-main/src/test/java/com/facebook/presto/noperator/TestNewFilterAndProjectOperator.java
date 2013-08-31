package com.facebook.presto.noperator;

import com.facebook.presto.execution.TaskId;
import com.facebook.presto.noperator.NewFilterAndProjectOperator.NewFilterAndProjectOperatorFactory;
import com.facebook.presto.operator.FilterFunction;
import com.facebook.presto.operator.Page;
import com.facebook.presto.operator.ProjectionFunctions;
import com.facebook.presto.spi.RecordCursor;
import com.facebook.presto.sql.analyzer.Session;
import com.facebook.presto.tuple.TupleInfo;
import com.facebook.presto.tuple.TupleReadable;
import com.facebook.presto.util.MaterializedResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static com.facebook.presto.noperator.NewOperatorAssertion.assertOperatorEquals;
import static com.facebook.presto.noperator.RowPagesBuilder.rowPagesBuilder;
import static com.facebook.presto.operator.ProjectionFunctions.singleColumn;
import static com.facebook.presto.tuple.TupleInfo.SINGLE_LONG;
import static com.facebook.presto.tuple.TupleInfo.SINGLE_VARBINARY;
import static com.facebook.presto.tuple.TupleInfo.Type.FIXED_INT_64;
import static com.facebook.presto.tuple.TupleInfo.Type.VARIABLE_BINARY;
import static com.facebook.presto.util.MaterializedResult.resultBuilder;
import static com.facebook.presto.util.Threads.daemonThreadsNamed;
import static java.util.concurrent.Executors.newCachedThreadPool;

public class TestNewFilterAndProjectOperator
{
    private ExecutorService executor;
    private DriverContext driverContext;

    @BeforeMethod
    public void setUp()
    {
        executor = newCachedThreadPool(daemonThreadsNamed("test"));
        Session session = new Session("user", "source", "catalog", "schema", "address", "agent");
        driverContext = new TaskContext(new TaskId("query", "stage", "task"), executor, session)
                .addPipelineContext()
                .addDriverContext();
    }

    @AfterMethod
    public void tearDown()
    {
        executor.shutdownNow();
    }

    @Test
    public void testAlignment()
            throws Exception
    {
        List<Page> input = rowPagesBuilder(SINGLE_VARBINARY, SINGLE_LONG)
                .addSequencePage(100, 0, 0)
                .build();

        NewOperatorFactory operatorFactory = new NewFilterAndProjectOperatorFactory(0, new FilterFunction()
        {
            @Override
            public boolean filter(TupleReadable... cursors)
            {
                long value = cursors[1].getLong(0);
                return 10 <= value && value < 20;
            }

            @Override
            public boolean filter(RecordCursor cursor)
            {
                long value = cursor.getLong(0);
                return 10 <= value && value < 20;
            }
        }, ProjectionFunctions.concat(singleColumn(VARIABLE_BINARY, 0, 0), singleColumn(FIXED_INT_64, 1, 0)));

        NewOperator operator = operatorFactory.createOperator(driverContext);

        MaterializedResult expected = resultBuilder(new TupleInfo(VARIABLE_BINARY, FIXED_INT_64))
                .row("10", 10)
                .row("11", 11)
                .row("12", 12)
                .row("13", 13)
                .row("14", 14)
                .row("15", 15)
                .row("16", 16)
                .row("17", 17)
                .row("18", 18)
                .row("19", 19)
                .build();

        assertOperatorEquals(operator, input, expected);
    }
}
