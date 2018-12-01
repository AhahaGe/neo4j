/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.database;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.IOLimiter;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.core.DatabasePanicEventGenerator;
import org.neo4j.kernel.impl.store.id.IdGeneratorFactory;
import org.neo4j.kernel.impl.store.id.configuration.CommunityIdTypeConfigurationProvider;
import org.neo4j.kernel.impl.util.Dependencies;
import org.neo4j.kernel.internal.DatabaseHealth;
import org.neo4j.kernel.lifecycle.LifecycleException;
import org.neo4j.logging.AssertableLogProvider;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.logging.internal.SimpleLogService;
import org.neo4j.test.rule.DatabaseRule;
import org.neo4j.test.rule.PageCacheRule;
import org.neo4j.test.rule.TestDirectory;
import org.neo4j.test.rule.fs.EphemeralFileSystemRule;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.neo4j.logging.AssertableLogProvider.inLog;

public class DatabaseTest
{
    @Rule
    public EphemeralFileSystemRule fs = new EphemeralFileSystemRule();
    @Rule
    public TestDirectory dir = TestDirectory.testDirectory( fs.get() );
    @Rule
    public DatabaseRule dsRule = new DatabaseRule();
    @Rule
    public PageCacheRule pageCacheRule = new PageCacheRule();

    @Test
    public void databaseHealthShouldBeHealedOnStart() throws Throwable
    {
        Database theDataSource = null;
        try
        {
            DatabaseHealth databaseHealth = new DatabaseHealth( mock( DatabasePanicEventGenerator.class ),
                    NullLogProvider.getInstance().getLog( DatabaseHealth.class ) );
            Dependencies dependencies = new Dependencies();
            dependencies.satisfyDependency( databaseHealth );

            theDataSource = dsRule.getDatabase( dir.databaseLayout(), fs.get(), pageCacheRule.getPageCache( fs.get() ),
                    dependencies );

            databaseHealth.panic( new Throwable() );

            theDataSource.start();

            databaseHealth.assertHealthy( Throwable.class );
        }
        finally
        {
            if ( theDataSource != null )
            {
                theDataSource.stop();
                theDataSource.shutdown();
            }
        }
    }

    @Test
    public void flushOfThePageCacheHappensOnlyOnceDuringShutdown() throws Throwable
    {
        PageCache pageCache = spy( pageCacheRule.getPageCache( fs.get() ) );
        Database ds = dsRule.getDatabase( dir.databaseLayout(), fs.get(), pageCache );

        ds.start();
        verify( pageCache, never() ).flushAndForce();
        verify( pageCache, never() ).flushAndForce( any( IOLimiter.class ) );

        ds.stop();
        ds.shutdown();
        verify( pageCache ).flushAndForce( IOLimiter.UNLIMITED );
    }

    @Test
    public void flushOfThePageCacheOnShutdownHappensIfTheDbIsHealthy() throws Throwable
    {
        PageCache pageCache = spy( pageCacheRule.getPageCache( fs.get() ) );

        Database ds = dsRule.getDatabase( dir.databaseLayout(), fs.get(), pageCache );

        ds.start();
        verify( pageCache, never() ).flushAndForce();

        ds.stop();
        ds.shutdown();
        verify( pageCache ).flushAndForce( IOLimiter.UNLIMITED );
    }

    @Test
    public void flushOfThePageCacheOnShutdownDoesNotHappenIfTheDbIsUnhealthy() throws Throwable
    {
        DatabaseHealth health = mock( DatabaseHealth.class );
        when( health.isHealthy() ).thenReturn( false );
        PageCache pageCache = spy( pageCacheRule.getPageCache( fs.get() ) );

        Dependencies dependencies = new Dependencies();
        dependencies.satisfyDependency( health );
        Database ds = dsRule.getDatabase( dir.databaseLayout(), fs.get(), pageCache, dependencies );

        ds.start();
        verify( pageCache, never() ).flushAndForce();

        ds.stop();
        ds.shutdown();
        verify( pageCache, never() ).flushAndForce( IOLimiter.UNLIMITED );
    }

    @Test
    public void logModuleSetUpError()
    {
        Config config = Config.defaults();
        IdGeneratorFactory idGeneratorFactory = mock( IdGeneratorFactory.class );
        Throwable openStoresError = new RuntimeException( "Can't set up modules" );
        doThrow( openStoresError ).when( idGeneratorFactory ).create( any( File.class ), anyLong(), anyBoolean() );

        CommunityIdTypeConfigurationProvider idTypeConfigurationProvider =
                new CommunityIdTypeConfigurationProvider();
        AssertableLogProvider logProvider = new AssertableLogProvider();
        SimpleLogService logService = new SimpleLogService( logProvider, logProvider );
        PageCache pageCache = pageCacheRule.getPageCache( fs.get() );
        Dependencies dependencies = new Dependencies();
        dependencies.satisfyDependencies( idGeneratorFactory, idTypeConfigurationProvider, config, logService );

        Database dataSource = dsRule.getDatabase( dir.databaseLayout(), fs.get(),
                pageCache, dependencies );

        try
        {
            dataSource.start();
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertEquals( openStoresError, e );
        }

        logProvider.assertAtLeastOnce( inLog( Database.class ).warn(
                equalTo( "Exception occurred while setting up store modules. Attempting to close things down." ),
                equalTo( openStoresError ) ) );
    }

    @Test
    public void shouldAlwaysShutdownLifeEvenWhenCheckPointingFails() throws Exception
    {
        // Given
        FileSystemAbstraction fs = this.fs.get();
        PageCache pageCache = pageCacheRule.getPageCache( fs );
        DatabaseHealth databaseHealth = mock( DatabaseHealth.class );
        when( databaseHealth.isHealthy() ).thenReturn( true );
        IOException ex = new IOException( "boom!" );
        doThrow( ex ).when( databaseHealth )
                .assertHealthy( IOException.class ); // <- this is a trick to simulate a failure during checkpointing
        Dependencies dependencies = new Dependencies();
        dependencies.satisfyDependencies( databaseHealth );
        Database dataSource = dsRule.getDatabase( dir.databaseLayout(), fs, pageCache, dependencies );
        dataSource.start();

        try
        {
            // When
            dataSource.stop();
            fail( "it should have thrown" );
        }
        catch ( LifecycleException e )
        {
            // Then
            assertEquals( ex, e.getCause() );
        }
    }
}