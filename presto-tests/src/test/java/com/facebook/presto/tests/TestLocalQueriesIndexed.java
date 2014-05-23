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
package com.facebook.presto.tests;

import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.testing.LocalQueryRunner;
import com.facebook.presto.testing.MaterializedResult;
import com.facebook.presto.tests.tpch.IndexedTpchConnectorFactory;
import com.facebook.presto.tpch.TpchMetadata;
import com.google.common.collect.ImmutableMap;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.AfterClass;

import java.util.Locale;

import static com.facebook.presto.spi.type.TimeZoneKey.UTC_KEY;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static java.util.concurrent.Executors.newCachedThreadPool;

public class TestLocalQueriesIndexed
        extends AbstractTestIndexedQueries
{
    public TestLocalQueriesIndexed()
    {
        super(createLocalQueryRunner());
    }

    @AfterClass(alwaysRun = true)
    public void destroy()
    {
        ((LocalQueryRunner) queryRunner).getExecutor().shutdownNow();
    }

    @Override
    protected MaterializedResult computeActual(@Language("SQL") String sql)
    {
        return queryRunner.execute(sql).toJdbcTypes();
    }

    private static LocalQueryRunner createLocalQueryRunner()
    {
        ConnectorSession defaultSession = new ConnectorSession("user", "test", "local", TpchMetadata.TINY_SCHEMA_NAME, UTC_KEY, Locale.ENGLISH, null, null);
        LocalQueryRunner localQueryRunner = new LocalQueryRunner(defaultSession, newCachedThreadPool(daemonThreadsNamed("test")));

        // add the tpch catalog
        // local queries run directly against the generator
        localQueryRunner.createCatalog(defaultSession.getCatalog(),
                new IndexedTpchConnectorFactory(localQueryRunner.getNodeManager(), INDEX_SPEC, 1), ImmutableMap.<String, String>of());

        return localQueryRunner;
    }
}
