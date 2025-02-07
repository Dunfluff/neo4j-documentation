/*
 * Licensed to Neo4j under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Neo4j licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.neo4j.doc.server.helpers;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import org.neo4j.collection.Dependencies;
import org.neo4j.common.DependencyResolver;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.SettingValueParsers;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.connectors.HttpConnector;
import org.neo4j.configuration.connectors.HttpsConnector;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.configuration.ssl.ClientAuth;
import org.neo4j.configuration.ssl.SslPolicyConfig;
import org.neo4j.configuration.ssl.SslPolicyScope;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.doc.server.WebContainerTestUtils;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.io.ByteUnit;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.server.configuration.ServerSettings;
import org.neo4j.test.ssl.SelfSignedCertificateFactory;

import static org.neo4j.configuration.SettingValueParsers.FALSE;
import static org.neo4j.doc.server.WebContainerTestUtils.addDefaultRelativeProperties;
import static org.neo4j.doc.server.WebContainerTestUtils.asOneLine;
import static org.neo4j.doc.server.WebContainerTestUtils.writeConfigToFile;
import static org.neo4j.internal.helpers.collection.MapUtil.stringMap;
import static org.neo4j.util.Preconditions.checkState;

public class CommunityWebContainerBuilder
{
    private static final SocketAddress ANY_ADDRESS = new SocketAddress( "localhost", 0 );

    private final LogProvider logProvider;
    private SocketAddress address = new SocketAddress( "localhost", HttpConnector.DEFAULT_PORT );
    private SocketAddress httpsAddress = new SocketAddress( "localhost", HttpsConnector.DEFAULT_PORT );
    private SocketAddress boltAddress = new SocketAddress( "localhost", BoltConnector.DEFAULT_PORT );
    private String maxThreads;
    private String dataDir;
    private String dbUri = "/db";
    private String restUri = "/db/data";
    private final HashMap<String, String> thirdPartyPackages = new HashMap<>();
    private final Properties arbitraryProperties = new Properties();

    static
    {
        System.setProperty( "sun.net.http.allowRestrictedHeaders", "true" );
    }

    private boolean persistent;
    private boolean httpEnabled = true;
    private boolean httpsEnabled;
    private DependencyResolver dependencies = new Dependencies();

    public static CommunityWebContainerBuilder builder( LogProvider logProvider )
    {
        return new CommunityWebContainerBuilder( logProvider );
    }

    public static CommunityWebContainerBuilder builder()
    {
        return new CommunityWebContainerBuilder( NullLogProvider.getInstance() );
    }

    public static CommunityWebContainerBuilder serverOnRandomPorts()
    {
        return builder().onRandomPorts();
    }

    public TestWebContainer build() throws IOException
    {
        checkState( dataDir != null || !persistent, "Must specify path" );
        final Path configFile = createConfigFiles();

        Log log = logProvider.getLog( getClass() );
        Config config = Config.newBuilder()
                .setDefaults( GraphDatabaseSettings.SERVER_DEFAULTS )
                .fromFile( configFile )
                .build();
        config.setLogger( log );
        return new TestWebContainer( build( config ) );
    }

    private DatabaseManagementService build( Config config )
    {
        var managementServiceBuilder = createManagementServiceBuilder();
        for ( Map.Entry<Setting<Object>,Object> entry : config.getValues().entrySet() )
        {
            managementServiceBuilder.setConfig( entry.getKey(), entry.getValue() );
        }
        return managementServiceBuilder
                .setUserLogProvider( logProvider )
                .setExternalDependencies( dependencies ).build();
    }

    protected DatabaseManagementServiceBuilder createManagementServiceBuilder()
    {
        return new DatabaseManagementServiceBuilder( Path.of( dataDir ) );
    }

    private Path createConfigFiles() throws IOException
    {
        Path testFolderPath;
        if ( persistent == true ) {
            testFolderPath = Path.of( dataDir );
        } else {
            testFolderPath = WebContainerTestUtils.createTempDir("neo4j-test-x"); //This folder will be removed.
        }
        Files.createDirectories( testFolderPath );

        Path temporaryConfigPath = testFolderPath.resolve( "test-x" + new Random().nextInt() + ".properties" );

        writeConfigToFile( createConfiguration( testFolderPath ), temporaryConfigPath );

        return temporaryConfigPath;
    }

    public Map<String, String> createConfiguration( Path temporaryFolder )
    {
        Map<String, String> properties = stringMap(
                ServerSettings.db_api_path.name(), dbUri,
                ServerSettings.rest_api_path.name(), restUri
        );

        addDefaultRelativeProperties( properties, temporaryFolder );

        if ( dataDir != null )
        {
            properties.put( GraphDatabaseSettings.data_directory.name(), dataDir );
        }

        if ( maxThreads != null )
        {
            properties.put( ServerSettings.webserver_max_threads.name(), maxThreads );
        }

        if ( thirdPartyPackages.keySet().size() > 0 )
        {
            properties.put( ServerSettings.third_party_packages.name(), asOneLine( thirdPartyPackages ) );
        }

        properties.put( HttpConnector.enabled.name(), String.valueOf( httpEnabled ) );
        properties.put( HttpConnector.listen_address.name(), address.toString() );

        properties.put( HttpsConnector.enabled.name(), String.valueOf( httpsEnabled ) );
        properties.put( HttpsConnector.listen_address.name(), httpsAddress.toString() );

        properties.put( BoltConnector.listen_address.name(), boltAddress.toString() );

        properties.put( GraphDatabaseSettings.neo4j_home.name(), temporaryFolder.toAbsolutePath().toString() );

        properties.put( GraphDatabaseSettings.auth_enabled.name(), FALSE );

        if ( httpsEnabled )
        {
            var certificates = temporaryFolder.resolve( "certificates" );
            SelfSignedCertificateFactory.create( certificates );
            SslPolicyConfig policy = SslPolicyConfig.forScope( SslPolicyScope.HTTPS );
            properties.put( policy.enabled.name(), Boolean.TRUE.toString() );
            properties.put( policy.base_directory.name(), certificates.toAbsolutePath().toString() );
            properties.put( policy.trust_all.name(), SettingValueParsers.TRUE );
            properties.put( policy.client_auth.name(), ClientAuth.NONE.name() );
        }

        properties.put( GraphDatabaseSettings.logs_directory.name(),
                temporaryFolder.resolve( "logs-x" ).toAbsolutePath().toString() );
        properties.put( GraphDatabaseSettings.transaction_logs_root_path.name(),
                temporaryFolder.resolve( "transaction-logs-x" ).toAbsolutePath().toString() );
        properties.put( GraphDatabaseSettings.pagecache_memory.name(), "8m" );
        properties.put( GraphDatabaseSettings.shutdown_transaction_end_timeout.name(), "0s" );

        for ( Object key : arbitraryProperties.keySet() )
        {
            properties.put( String.valueOf( key ), String.valueOf( arbitraryProperties.get( key ) ) );
        }
        return properties;
    }

    protected CommunityWebContainerBuilder( LogProvider logProvider )
    {
        this.logProvider = logProvider;
    }

    public CommunityWebContainerBuilder withDependencies( DependencyResolver dependencies )
    {
        this.dependencies = dependencies;
        return this;
    }

    public CommunityWebContainerBuilder persistent()
    {
        this.persistent = true;
        return this;
    }

    public CommunityWebContainerBuilder withMaxJettyThreads( int maxThreads )
    {
        this.maxThreads = String.valueOf( maxThreads );
        return this;
    }

    public CommunityWebContainerBuilder usingDataDir( String dataDir )
    {
        this.dataDir = dataDir;
        return this;
    }

    public CommunityWebContainerBuilder withRelativeDatabaseApiPath( String uri )
    {
        this.dbUri = getPath( uri );
        return this;
    }

    public CommunityWebContainerBuilder withRelativeRestApiPath( String uri )
    {
        this.restUri = getPath( uri );
        return this;
    }

    public CommunityWebContainerBuilder withDefaultDatabaseTuning()
    {
        return this;
    }

    public CommunityWebContainerBuilder withThirdPartyJaxRsPackage( String packageName, String mountPoint )
    {
        thirdPartyPackages.put( packageName, mountPoint );
        return this;
    }

    public CommunityWebContainerBuilder onRandomPorts()
    {
        this.onHttpsAddress( ANY_ADDRESS );
        this.onAddress( ANY_ADDRESS );
        this.onBoltAddress( ANY_ADDRESS );
        return this;
    }

    public CommunityWebContainerBuilder onAddress( SocketAddress address )
    {
        this.address = address;
        return this;
    }

    public CommunityWebContainerBuilder onHttpsAddress( SocketAddress address )
    {
        this.httpsAddress = address;
        return this;
    }

    public CommunityWebContainerBuilder onBoltAddress( SocketAddress address )
    {
        this.boltAddress = address;
        return this;
    }

    public CommunityWebContainerBuilder withHttpsEnabled()
    {
        httpsEnabled = true;
        return this;
    }

    public CommunityWebContainerBuilder withHttpDisabled()
    {
        httpEnabled = false;
        return this;
    }

    public CommunityWebContainerBuilder withProperty( String key, String value )
    {
        arbitraryProperties.put( key, value );
        return this;
    }

    private static String getPath( String uri )
    {
        URI theUri = URI.create( uri );
        if ( theUri.isAbsolute() )
        {
            return theUri.getPath();
        }
        else
        {
            return theUri.toString();
        }
    }
}
