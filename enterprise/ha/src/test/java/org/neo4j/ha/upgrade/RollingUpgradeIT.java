/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.ha.upgrade;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;

import org.neo4j.backup.OnlineBackup;
import org.neo4j.backup.OnlineBackupSettings;
import org.neo4j.consistency.ConsistencyCheckService;
import org.neo4j.consistency.checking.full.ConsistencyCheckIncompleteException;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.graphdb.factory.HighlyAvailableGraphDatabaseFactory;
import org.neo4j.helpers.Pair;
import org.neo4j.helpers.collection.MapUtil;
import org.neo4j.helpers.progress.ProgressMonitorFactory;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.ha.UpdatePuller;
import org.neo4j.io.fs.FileUtils;
import org.neo4j.kernel.impl.util.StringLogger;
import org.neo4j.test.TargetDirectory;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static org.neo4j.cluster.ClusterSettings.cluster_server;
import static org.neo4j.cluster.ClusterSettings.initial_hosts;
import static org.neo4j.cluster.ClusterSettings.server_id;
import static org.neo4j.ha.upgrade.Utils.assembleClassPathFromPackage;
import static org.neo4j.ha.upgrade.Utils.downloadAndUnpack;
import static org.neo4j.kernel.ha.HaSettings.ha_server;

//@Ignore( "Keep this test around as it's a very simple and 'close' test to quickly verify rolling upgrades" )
public class RollingUpgradeIT
{
    private static final String OLD_VERSION = "2.0.1";

    public static final RelationshipType type1 = DynamicRelationshipType.withName( "type1" );
    public static final RelationshipType type2 = DynamicRelationshipType.withName( "type2" );

    private final TargetDirectory DIR = TargetDirectory.forTest( getClass() );
    private final File DBS_DIR = DIR.cleanDirectory( "dbs" );

    private LegacyDatabase[] legacyDbs;
    private GraphDatabaseAPI[] newDbs;
    private long centralNode;

    @Test
    public void doRollingUpgradeFromPreviousVersionWithMasterLast() throws Throwable
    {
        /* High level scenario:
         * 1   Have a cluster of 3 instances running <old version>
         * 1.1 Download a <old version> package
         * 1.2 Unpack the <old version> package
         * 1.4 Assembly classpath and start 3 JVMs running <old version>
         * 1.5 Create some data in the cluster
         * 2   Go over each one restarting into <this version>
         * 2.1 Grab a JVM and kill it
         * 2.2 Start that db inside this test JVM, which will run <this version>
         * 2.3 Perform a write transaction to the current master and see that it picks it up
         * 2.4 Perform a write transaction to to this instance and see that master picks it up
         * 3   Make sure the cluster functions after each one has been restarted
         * 3.1 Do basic transactions on master/slaves.
         * 3.2 Do a master switch
         * 3.3 Restart one slave
         * 3.4 Take down the instances and do consistency check */

        /*
         *
         */

        try
        {
            startOldVersionCluster();
            rollOverToNewVersion();
            shutdownAndDoConsistencyChecks();
        }
        catch ( Throwable e )
        {
            e.printStackTrace();
            throw e;
        }
    }

    private void shutdownAndDoConsistencyChecks() throws ConsistencyCheckIncompleteException
    {
        Collection<String> storeDirs = new ArrayList<>( newDbs.length );
        for ( GraphDatabaseAPI item : newDbs )
        {
            storeDirs.add( item.getStoreDir() );
            item.shutdown();
        }

        ConsistencyCheckService service = new ConsistencyCheckService();
        for ( String storeDir : storeDirs )
        {
            service.runFullConsistencyCheck( storeDir, new Config(),
                    ProgressMonitorFactory.textual( System.out ), StringLogger.SYSTEM );
        }
    }

    private void debug( String message )
    {
        debug( message, true );
    }

    private void debug( String message, boolean enter )
    {
        String string = "RUT " + message;
        if ( enter )
        {
            System.out.println( string );
        }
        else
        {
            System.out.print( string );
        }
    }

    @After
    public void cleanUp() throws Exception
    {
        if ( legacyDbs != null )
        {
            for ( int i = 0; i < legacyDbs.length; i++ )
            {
                stop( i );
            }
        }
        if ( newDbs != null )
        {
            for ( GraphDatabaseService db : newDbs )
            {
                db.shutdown();
            }
        }
    }

    private void startOldVersionCluster() throws Exception
    {
        debug( "Downloading " + OLD_VERSION + " package" );
        File oldVersionPackage = downloadAndUnpack(
                "http://download.neo4j.org/artifact?edition=enterprise&version=" + OLD_VERSION + "&distribution=zip",
                DIR.cacheDirectory( "download" ), OLD_VERSION + "-enterprise" );
        String classpath = assembleClassPathFromPackage( oldVersionPackage );
        debug( "Starting " + OLD_VERSION + " cluster in separate jvms" );
        @SuppressWarnings( "rawtypes" )
        Future[] legacyDbFutures = new Future[3];
        for ( int i = 0; i < legacyDbFutures.length; i++ )
        {
            legacyDbFutures[i] = LegacyDatabaseImpl.start( classpath,
                    storeDir( i ), config( i ) );
            debug( "  Started " + i );
        }
        legacyDbs = new LegacyDatabase[legacyDbFutures.length];
        for ( int i = 0; i < legacyDbFutures.length; i++ )
        {
            legacyDbs[i] = (LegacyDatabase) legacyDbFutures[i].get();
        }

        for ( LegacyDatabase db : legacyDbs )
        {
            debug( "  Awaiting " + db.getStoreDir() + " to start" );
            db.awaitStarted( 10, TimeUnit.SECONDS );
            debug( "  " + db.getStoreDir() + " fully started" );
        }
        for ( int i = 0; i < legacyDbs.length; i++ )
        {
            long node = legacyDbs[i].createNode();
            for ( LegacyDatabase db : legacyDbs )
            {
                db.verifyNodeExists( node );
            }
        }
        debug( OLD_VERSION + " cluster fully operational" );

        centralNode = legacyDbs[0].initialize();
    }

    private File storeDir( int serverId )
    {
        return new File( DBS_DIR, "" + serverId );
    }

    private Map<String, String> config( int serverId ) throws UnknownHostException
    {
        String localhost = localhost();
        Map<String, String> result = MapUtil.stringMap(
                server_id.name(), "" + serverId,
                cluster_server.name(), localhost + ":" + (5000 + serverId),
                ha_server.name(), localhost + ":" + (6000 + serverId),
                GraphDatabaseSettings.allow_store_upgrade.name(), "true",
                OnlineBackupSettings.online_backup_server.name(), localhost + ":" + backupPort( serverId ),
                initial_hosts.name(), localhost + ":" + 5000 + "," + localhost + ":" + 5001 + "," + localhost + ":" + 5002 );
        return result;
    }

    private String localhost() throws UnknownHostException
    {
        return InetAddress.getLocalHost().getHostAddress();
    }

    private int backupPort( int serverId )
    {
        return (6362+serverId);
    }

    private void rollOverToNewVersion() throws Exception
    {
        debug( "Starting to roll over to current version" );
        Pair<LegacyDatabase, Integer> master = findOutWhoIsMaster();
        newDbs = new GraphDatabaseAPI[legacyDbs.length];
        int authorativeSlaveId = -1;
        for ( int i = 0; i < legacyDbs.length; i++ )
        {
            LegacyDatabase legacyDb = legacyDbs[i];
            if ( legacyDb == master.first() )
            {   // Roll over the master last
                System.out.println("master is " + master.first().getStoreDir());
                continue;
            }

            rollOver( legacyDb, i, master.other(), authorativeSlaveId );
            if ( authorativeSlaveId == -1 )
            {
                authorativeSlaveId = i;
            }
        }
        rollOver( master.first(), master.other(), master.other(), -2 );
    }

    private void rollOver( LegacyDatabase legacyDb, int i, int masterServerId, int authorativeSlaveId )
            throws Exception
    {
        String storeDir = legacyDb.getStoreDir();
        if ( i == 0)
        {
            storeDir += "new";
        }
        stop( i );
        Thread.sleep( 30000 );

        File storeDirFile = new File( storeDir );
        debug( "Starting " + i + " as current version" );
        switch ( authorativeSlaveId )
        {
        case -1:
            break;
        case -2:
            debug( "At last master starting, deleteing store so that it fetches from the new master" );
            FileUtils.deleteRecursively( storeDirFile );
            break;
        default:
            debug( "Consecutive slave starting, making it so that I will copy store from " + authorativeSlaveId );
            FileUtils.deleteRecursively( storeDirFile );
            storeDirFile.mkdirs();
            backup( authorativeSlaveId, storeDirFile );
            break;
        }

        // start that db up in this JVM
        newDbs[i] = (GraphDatabaseAPI) new HighlyAvailableGraphDatabaseFactory()
                .newHighlyAvailableDatabaseBuilder( storeDir )
                .setConfig( config( i ) )
                .newGraphDatabase();
        debug( "Started " + i + " as current version" );
        legacyDbs[i] = null;

        // issue transaction and see that it propagates
        if ( i != masterServerId )
        {
            // if the instance is not the old master, create on the old master
            legacyDbs[masterServerId].doComplexLoad( centralNode );
            debug( "Node created on " + i );
        }
        else
        {
            doComplexLoad( newDbs[1], centralNode );
        }
        for ( int j = 0; j < legacyDbs.length; j++ )
        {
            if ( legacyDbs[j] != null )
            {
                legacyDbs[j].verifyComplexLoad( centralNode );
                debug( "Verified on legacy db " + j );
            }
        }
        for ( int j = 0; j < newDbs.length; j++ )
        {
            if ( newDbs[j] != null )
            {
                verifyComplexLoad( newDbs[j], centralNode );
                debug( "Verified on new db " + j );
            }
        }
    }

    private void backup( int sourceServerId, File targetDir ) throws UnknownHostException
    {
        OnlineBackup backup = OnlineBackup.from( localhost(), backupPort( sourceServerId ) ).backup( targetDir.getPath() );
        assertTrue( "Something wrong with the backup", backup.isConsistent() );
    }

    public void doComplexLoad( GraphDatabaseAPI db, long center )
    {
        try( Transaction tx = db.beginTx() )
        {
            Node central = db.getNodeById( center );

            long type1RelCount = central.getDegree( type1 );
            long type2RelCount = central.getDegree( type2 );

            long[] type1RelId = new long[(int) type1RelCount];
            long[] type2RelId = new long[(int) type2RelCount];

            int index = 0;
            for ( Relationship relationship : central.getRelationships( type1 ) )
            {
                type1RelId[index++] = relationship.getId();
            }
            index = 0;
            for ( Relationship relationship : central.getRelationships( type2 ) )
            {
                type2RelId[index++] = relationship.getId();
            }

            // Delete the first half of each type
            Arrays.sort( type1RelId );
            Arrays.sort( type2RelId );

            for ( int i = 0; i < type1RelId.length / 2; i++ )
            {
                db.getRelationshipById( type1RelId[i] ).delete();
            }
            for ( int i = 0; i < type2RelId.length / 2; i++ )
            {
                db.getRelationshipById( type2RelId[i] ).delete();
            }

            // Go ahead and create relationships to make up for these deletes
            for ( int i = 0; i < type1RelId.length / 2; i++ )
            {
                central.createRelationshipTo( db.createNode(), type1 );
            }

            long largestCreated = 0;
            // The result is the id of the latest created relationship. We'll use that to set the properties
            for ( int i = 0; i < type2RelId.length / 2; i++ )
            {
                long current = central.createRelationshipTo( db.createNode(), type2 ).getId();
                if ( current > largestCreated )
                {
                    largestCreated = current;
                }
            }

            for ( Relationship relationship : central.getRelationships() )
            {
                relationship.setProperty( "relProp", "relProp" + relationship.getId() + "-" + largestCreated );
                Node end = relationship.getEndNode();
                end.setProperty( "nodeProp", "nodeProp" + end.getId() + "-" + largestCreated  );
            }

            tx.success();
        }
    }

    public void verifyComplexLoad( GraphDatabaseAPI db, long centralNode )
    {
        db.getDependencyResolver().resolveDependency( UpdatePuller.class ).pullUpdates();
        try( Transaction tx = db.beginTx() )
        {
            Node center = db.getNodeById( centralNode );
            long maxRelId = -1;
            for ( Relationship relationship : center.getRelationships() )
            {
                if (relationship.getId() > maxRelId )
                {
                    maxRelId = relationship.getId();
                }
            }

            int typeCount = 0;
            for ( Relationship relationship : center.getRelationships( type1 ) )
            {
                typeCount++;
                if ( !relationship.getProperty( "relProp" ).equals( "relProp" +relationship.getId()+"-"+maxRelId) )
                {
                    fail( "damn");
                }
                Node other = relationship.getEndNode();
                if ( !other.getProperty( "nodeProp" ).equals( "nodeProp"+other.getId()+"-"+maxRelId ) )
                {
                    fail("double damn");
                }
            }
            if ( typeCount != 100 )
            {
                fail("tripled damn");
            }

            typeCount = 0;
            for ( Relationship relationship : center.getRelationships( type2 ) )
            {
                typeCount++;
                if ( !relationship.getProperty( "relProp" ).equals( "relProp" +relationship.getId()+"-"+maxRelId) )
                {
                    fail( "damn");
                }
                Node other = relationship.getEndNode();
                if ( !other.getProperty( "nodeProp" ).equals( "nodeProp"+other.getId()+"-"+maxRelId ) )
                {
                    fail("double damn");
                }
            }
            if ( typeCount != 100 )
            {
                fail("tripled damn");
            }
            tx.success();
        }
    }

    private Pair<LegacyDatabase,Integer> findOutWhoIsMaster()
    {
        try
        {
            for ( int i = 0; i < legacyDbs.length; i++ )
            {
                LegacyDatabase db = legacyDbs[i];
                if ( db.isMaster() )
                {
                    return Pair.of( db, i );
                }
            }
        }
        catch ( RemoteException e )
        {
            throw new RuntimeException( e );
        }
        throw new IllegalStateException( "No master" );
    }

    private void stop( int i )
    {
        try
        {
            LegacyDatabase legacyDb = legacyDbs[i];
            if ( legacyDb != null )
            {
                legacyDb.stop();
                legacyDbs[i] = null;
            }
        }
        catch ( RemoteException e )
        {
            // OK
        }
    }
}
