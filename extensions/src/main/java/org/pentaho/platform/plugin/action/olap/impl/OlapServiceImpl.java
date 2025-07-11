/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2029-07-20
 ******************************************************************************/


package org.pentaho.platform.plugin.action.olap.impl;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import mondrian.olap.MondrianServer;
import mondrian.olap.Role;
import mondrian.olap.Util;
import mondrian.rolap.RolapConnection;
import mondrian.rolap.RolapConnectionProperties;
import mondrian.server.DynamicContentFinder;
import mondrian.server.MondrianServerRegistry;
import mondrian.spi.CatalogLocator;
import mondrian.util.LockBox.Entry;
import mondrian.xmla.XmlaHandler;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.VFS;
import org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.olap4j.OlapConnection;
import org.olap4j.OlapException;
import org.pentaho.platform.api.engine.ICacheManager;
import org.pentaho.platform.api.engine.IConnectionUserRoleMapper;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.api.engine.PentahoAccessControlException;
import org.pentaho.platform.api.repository2.unified.IUnifiedRepository;
import org.pentaho.platform.api.repository2.unified.RepositoryFilePermission;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.engine.security.SecurityHelper;
import org.pentaho.platform.plugin.action.messages.Messages;
import org.pentaho.platform.plugin.action.olap.IOlapConnectionFilter;
import org.pentaho.platform.plugin.action.olap.IOlapService;
import org.pentaho.platform.plugin.action.olap.IOlapServiceException;
import org.pentaho.platform.plugin.action.olap.PlatformXmlaExtra;
import org.pentaho.platform.plugin.services.connections.mondrian.MDXConnection;
import org.pentaho.platform.plugin.services.importexport.legacy.MondrianCatalogRepositoryHelper;
import org.pentaho.platform.plugin.services.importexport.legacy.MondrianCatalogRepositoryHelper.HostedCatalogInfo;
import org.pentaho.platform.plugin.services.importexport.legacy.MondrianCatalogRepositoryHelper.Olap4jServerInfo;
import org.pentaho.platform.repository.solution.filebased.MondrianVfs;
import org.pentaho.platform.util.messages.LocaleHelper;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Implementation of the IOlapService which uses the
 * {@link MondrianCatalogRepositoryHelper} as a backend to
 * store the connection informations and uses {@link DriverManager}
 * to create the connections.
 * <p/>
 * <p>It will also check for the presence of a {@link IConnectionUserRoleMapper}
 * and change the roles accordingly before creating a connection.
 * <p/>
 * <p>This implementation is thread safe. It will use a {@link ReadWriteLock}
 * to manage the access to its metadata.
 */
public class OlapServiceImpl implements IOlapService {

  public static final String CATALOG_CACHE_REGION = "iolapservice-catalog-cache"; //$NON-NLS-1$

  final ReadWriteLock cacheLock = new ReentrantReadWriteLock();

  /**
   * This is the default name of an XMLA data source on the server.
   * Mondrian XMLA servers only support a single data source.
   */
  static final String DATASOURCE_NAME = "Pentaho";

  private static final Log LOG = getLogger();

  /*
   * Do not access these two fields directly. They need to be accessed through
   * getRepository and getHelper because we can't init them before spring is
   * done initializing the sub modules.
   */
  private IUnifiedRepository repository;
  private MondrianCatalogRepositoryHelper helper;

  private MondrianServer server;
  private final List<IOlapConnectionFilter> filters;
  private Role role;

  private static Log getLogger() {
    return LogFactory.getLog( IOlapService.class );
  }

  /**
   * Empty constructor. Creating an instance from here will
   * use the {@link PentahoSystem} to fetch the {@link IUnifiedRepository}
   * at runtime.
   */
  public OlapServiceImpl() {
    this( null, null );
  }

  /**
   * Constructor for testing purposes. Takes a repository as a parameter.
   */
  public OlapServiceImpl( IUnifiedRepository repo, final MondrianServer server ) {
    this.repository = repo;
    this.filters = new CopyOnWriteArrayList<>();
    this.server = server;

    try {
      DefaultFileSystemManager dfsm = (DefaultFileSystemManager) VFS.getManager();
      if ( !dfsm.hasProvider( "mondrian" ) ) {
        dfsm.addProvider( "mondrian", new MondrianVfs() );
      }
    } catch ( FileSystemException e ) {
      throw new RuntimeException( e );
    }
  }

  private Boolean isSec = null;

  private boolean isSecurityEnabled() {

    if ( isSec != null ) {
      return isSec;
    }

    try {
      UserDetailsService uds = PentahoSystem.get( UserDetailsService.class );
      isSec = uds != null;
    } catch ( Exception e ) {
      // no op.
      isSec = false;
    }
    return isSec;
  }

  synchronized IUnifiedRepository getRepository() {
    if ( repository == null ) {
      repository = PentahoSystem.get( IUnifiedRepository.class );
    }
    return repository;
  }

  synchronized MondrianCatalogRepositoryHelper getHelper() {
    if ( helper == null ) {
      helper =
        new MondrianCatalogRepositoryHelper(
          getRepository() );
    }
    return helper;
  }

  public synchronized void setHelper( MondrianCatalogRepositoryHelper helper ) {
    this.helper = helper;
  }

  /**
   * Returns a list of catalogs for the current session.
   *
   * <p>The cache is stored in the platform's caches in the region
   * {@link #CATALOG_CACHE_REGION}. It is also segmented by
   * locale, but we only return the correct sub-region according to the
   * session passed as a parameter.
   */
  @SuppressWarnings( "unchecked" )
  protected synchronized List<IOlapService.Catalog> getCache( IPentahoSession session ) {
    // Create the cache region if necessary.
    final ICacheManager cacheMgr = PentahoSystem.getCacheManager( session );
    final Object cacheKey = makeCacheSubRegionKey( getLocale() );


    final Lock writeLock = cacheLock.writeLock();
    try {

      writeLock.lock();

      if ( !cacheMgr.cacheEnabled( CATALOG_CACHE_REGION ) ) {
        // Create the region. This requires write access.
        cacheMgr.addCacheRegion( CATALOG_CACHE_REGION );
      }

      if ( cacheMgr.getFromRegionCache( CATALOG_CACHE_REGION, cacheKey ) == null ) {
        // Create the sub-region. This requires write access.
        cacheMgr.putInRegionCache(
          CATALOG_CACHE_REGION,
          cacheKey,
          new ArrayList<IOlapService.Catalog>() );
      }

      return (List<IOlapService.Catalog>)
        cacheMgr.getFromRegionCache( CATALOG_CACHE_REGION, cacheKey );

    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Clears all caches for all locales.
   */
  protected void resetCache( IPentahoSession session ) {
    final Lock writeLock = cacheLock.writeLock();
    try {
      writeLock.lock();
      final ICacheManager cacheMgr = PentahoSystem.getCacheManager( session );
      cacheMgr.clearRegionCache( CATALOG_CACHE_REGION );
    } finally {
      writeLock.unlock();
    }
  }

  protected Object makeCacheSubRegionKey( Locale locale ) {
    return locale.toString();
  }

  /**
   * Initializes the cache. Only the cache specific to the sesison's locale
   * will be populated.
   */
  protected void initCache( IPentahoSession session ) {

    final List<Catalog> cache = getCache( session );

    final boolean needUpdate;
    final Lock readLock = cacheLock.readLock();

    try {
      readLock.lock();
      // Check if the cache is empty.
      needUpdate = cache.isEmpty();
    } finally {
      readLock.unlock();
    }

    if ( needUpdate ) {
      final Lock writeLock = cacheLock.writeLock();
      try {
        writeLock.lock();

        // First clear the cache
        cache.clear();

        final Callable<Void> call = new Callable<>() {
          public Void call() throws Exception {
            // Now build the cache. Use the system session in the holder.
            for ( String name : getHelper().getHostedCatalogs() ) {
              try {
                addCatalogToCache( PentahoSessionHolder.getSession(), name );
              } catch ( Throwable t ) {
                LOG.error(
                  "Failed to initialize the cache for OLAP connection "
                  + name,
                  t );
              }
            }
            for ( String name : getHelper().getOlap4jServers() ) {
              try {
                addCatalogToCache( PentahoSessionHolder.getSession(), name );
              } catch ( Throwable t ) {
                LOG.error(
                  "Failed to initialize the cache for OLAP connection "
                  + name,
                  t );
              }
            }
            return null;
          }
        };

        if ( isSecurityEnabled() ) {
          SecurityHelper.getInstance().runAsSystem( call );
        } else {
          call.call();
        }

        // Sort it all.
        Collections.sort(
          cache,
          new Comparator<IOlapService.Catalog>() {
            public int compare( Catalog o1, Catalog o2 ) {
              return o1.name.compareTo( o2.name );
            }
          } );

      } catch ( Throwable t ) {

        LOG.error(
          "Failed to initialize the connection cache",
          t );

        throw new IOlapServiceException( t );

      } finally {
        writeLock.unlock();
      }
    }
  }

  /**
   * Adds a catalog and its children to the cache.
   * Do not use directly. This must be called with a write lock
   * on the cache.
   *
   * @param catalogName The name of the catalog to load in cache.
   */
  private void addCatalogToCache( IPentahoSession session, String catalogName ) {

    final IOlapService.Catalog catalog =
      new Catalog( catalogName, new ArrayList<>() );

    OlapConnection connection = null;

    try {

      connection =
        getConnection( catalogName, session );

      for ( org.olap4j.metadata.Schema schema4j : connection.getOlapSchemas() ) {

        connection.setSchema( schema4j.getName() );

        final IOlapService.Schema schema =
          new Schema(
            schema4j.getName(),
            catalog,
            new ArrayList<>(),
            new ArrayList<>( connection.getAvailableRoleNames() ) );

        for ( org.olap4j.metadata.Cube cube4j : schema4j.getCubes() ) {
          schema.cubes.add(
            new IOlapService.Cube( cube4j.getName(), cube4j.getCaption(), schema ) );
        }

        catalog.schemas.add( schema );
      }

      // We're done.
      getCache( session ).add( catalog );

    } catch ( OlapException e ) {

      LOG.warn(
        "Failed to initialize the olap connection cache for catalog "
        + catalogName,
        e );

    } finally {
      try {
        if ( connection != null ) {
          connection.close();
        }
      } catch ( SQLException e ) {
        LOG.warn(
          "Failed to gracefully close an olap connection to catalog "
          + catalogName,
          e );
      }
    }
  }

  public void addHostedCatalog(
    String name,
    String dataSourceInfo,
    InputStream inputStream,
    boolean overwrite,
    IPentahoSession session ) {

    // Access
    if ( !hasAccess( name, EnumSet.of( RepositoryFilePermission.WRITE ), session ) ) {
      LOG.debug( "user does not have access; throwing exception" ); //$NON-NLS-1$
      throw new IOlapServiceException(
        Messages.getInstance().getErrorString(
          "OlapServiceImpl.ERROR_0003_INSUFFICIENT_PERMISSION" ), //$NON-NLS-1$
        IOlapServiceException.Reason.ACCESS_DENIED );
    }

    // check for existing vs. the overwrite flag.
    if ( getCatalogNames( session ).contains( name ) && !overwrite ) {
      throw new IOlapServiceException(
        Messages.getInstance().getErrorString(
          "OlapServiceImpl.ERROR_0004_ALREADY_EXISTS" ), //$NON-NLS-1$
        IOlapServiceException.Reason.ALREADY_EXISTS );
    }

    try {
      getHelper().addHostedCatalog( inputStream, name, dataSourceInfo );
    } catch ( Exception e ) {
      throw new IOlapServiceException(
        e,
        IOlapServiceException.Reason.convert( e ) );
    }
  }

  protected boolean hasAccess(
    final String catalogName,
    final EnumSet<RepositoryFilePermission> perms,
    IPentahoSession session ) {
    return getHelper().hasAccess( catalogName, perms, session );
  }

  public void addOlap4jCatalog(
    String name,
    String className,
    String URL,
    String user,
    String password,
    Properties props,
    boolean overwrite,
    IPentahoSession session ) {

    // Access
    if ( !hasAccess( name, EnumSet.of( RepositoryFilePermission.WRITE ), session ) ) {
      LOG.debug( "user does not have access; throwing exception" ); //$NON-NLS-1$
      throw new IOlapServiceException(
        Messages.getInstance().getErrorString(
          "OlapServiceImpl.ERROR_0003_INSUFFICIENT_PERMISSION" ), //$NON-NLS-1$
        IOlapServiceException.Reason.ACCESS_DENIED );
    }

    // check for existing vs. the overwrite flag.
    if ( getCatalogNames( session ).contains( name ) && !overwrite ) {
      throw new IOlapServiceException(
        Messages.getInstance().getErrorString(
          "OlapServiceImpl.ERROR_0004_ALREADY_EXISTS" ), //$NON-NLS-1$
        IOlapServiceException.Reason.ALREADY_EXISTS );
    }
    getHelper().addOlap4jServer( name, className, URL, user, password, props );
  }

  public void removeCatalog( String name, IPentahoSession session ) {

    // Check Access
    if ( !hasAccess( name, EnumSet.of( RepositoryFilePermission.DELETE ), session ) ) {
      LOG.debug( "user does not have access; throwing exception" ); //$NON-NLS-1$
      throw new IOlapServiceException(
        Messages.getInstance().getErrorString(
          "OlapServiceImpl.ERROR_0003_INSUFFICIENT_PERMISSION" ), //$NON-NLS-1$
        IOlapServiceException.Reason.ACCESS_DENIED );
    }

    if ( !getCatalogNames( session ).contains( name ) ) {
      throw new IOlapServiceException(
        Messages.getInstance().getErrorString(
          "MondrianCatalogHelper.ERROR_0015_CATALOG_NOT_FOUND",
          name ) );
    }

    // This could be a remote connection
    getHelper().deleteCatalog( name );
  }

  /**
   * Flushes a single schema from the cache.
   */
  public void flush( IPentahoSession session, String name ) {
    final Lock writeLock = cacheLock.writeLock();
    writeLock.lock();

    try ( OlapConnection connection = getConnection( name, session ) ) {
      final RolapConnection rc = connection.unwrap( RolapConnection.class );
      rc.getCacheControl( null ).flushSchema( rc.getSchema() );
    } catch ( Exception e ) {
      LOG.warn( Messages.getInstance().getErrorString( "MondrianCatalogHelper.ERROR_0019_FAILED_TO_FLUSH", name ), e );

      throw new IOlapServiceException(
        Messages.getInstance().getErrorString( "MondrianCatalogHelper.ERROR_0019_FAILED_TO_FLUSH", name ) );
    } finally {
      writeLock.unlock();
    }
  }

  public void flushAll( IPentahoSession session ) {
    final Lock writeLock = cacheLock.writeLock();
    try {
      writeLock.lock();

      // Start by flushing the local cache.
      resetCache( session );

      flushHostedCatalogs();
      flushRemoteCatalogs( session );
    } catch ( Exception e ) {
      throw new IOlapServiceException( e );
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Flushes all hosted catalogs.
   */
  private void flushHostedCatalogs() {
    // we don't want to create a new MondrianServer instance to clear the cache when server is null
    // in this case, just clear the cache of the static server
    if ( server != null ) {
      // clean cache for all mondrian schemas used by current mondrian server
      server.getAggregationManager().getCacheControl( null, null ).flushSchemaCache();
    }
    // clean cache for all mondrian schemas used by mondrian default "static" server
    MondrianServer.forId( null ).getAggregationManager().getCacheControl( null, null ).flushSchemaCache();
  }

  /**
   * Flushes all remote catalogs accessible to session.
   * Unlike Hosted Catalogs, remote catalogs need to be
   * flushed individually since they each may be running
   * in separate instances.
   */
  private void flushRemoteCatalogs( IPentahoSession session ) throws SQLException {
    for ( String name : getRemoteCatalogNames( session ) ) {
      OlapConnection connection = null;
      try {
        connection = getConnection( name, session );
        XmlaHandler.XmlaExtra xmlaExtra = getXmlaExtra( connection );
        if ( xmlaExtra != null ) {
          xmlaExtra.flushSchemaCache( connection );
        }
      } catch ( Exception e ) {
        LOG.warn(
            Messages.getInstance().getErrorString( "MondrianCatalogHelper.ERROR_0019_FAILED_TO_FLUSH", name ), e );
      } finally {
        if ( connection != null ) {
          connection.close();
        }
      }
    }
  }

  protected XmlaHandler.XmlaExtra getXmlaExtra( final OlapConnection connection ) throws SQLException {
    return PlatformXmlaExtra.unwrapXmlaExtra( connection );
  }

  public List<String> getCatalogNames(
      IPentahoSession pentahoSession )
    throws IOlapServiceException {
    // This is the quick implementation to obtain a list of catalogs
    // without having to open connections. IT can be used by UI tools
    // and tests.
    final List<String> names = new ArrayList<>();

    names.addAll( getHostedCatalogNames( pentahoSession ) );
    names.addAll( getRemoteCatalogNames( pentahoSession ) );

    // Sort it all.
    Collections.sort( names );

    return names;
  }

  private Collection<String> getHostedCatalogNames( final IPentahoSession pentahoSession ) {
    return Collections2.filter( getHelper().getHostedCatalogs(), new Predicate<>() {
      @Override public boolean apply( String name ) {
        return hasAccess( name, EnumSet.of( RepositoryFilePermission.READ ), pentahoSession );
      }
    } );
  }

  private Collection<String> getRemoteCatalogNames( final IPentahoSession pentahoSession ) {
    return Collections2.filter( getHelper().getOlap4jServers(), new Predicate<>() {
      @Override public boolean apply( String name ) {
        return hasAccess( name, EnumSet.of( RepositoryFilePermission.READ ), pentahoSession );
      }
    } );
  }


  public List<IOlapService.Catalog> getCatalogs( IPentahoSession session ) throws IOlapServiceException {

    // Make sure the cache is initialized.
    initCache( session );
    final List<Catalog> cache = getCache( session );

    final Lock readLock = cacheLock.readLock();
    try {
      readLock.lock();

      return cache.stream()
        .filter( catalog -> hasAccess( catalog.name, EnumSet.of( RepositoryFilePermission.READ ), session ) )
        .collect( Collectors.toList() );

    } finally {
      readLock.unlock();
    }
  }

  public List<IOlapService.Schema> getSchemas(
    String parentCatalog,
    IPentahoSession session ) {
    final List<IOlapService.Schema> schemas = new ArrayList<>();
    for ( IOlapService.Catalog catalog : getCatalogs( session ) ) {
      if ( parentCatalog == null
        || catalog.name.equals( parentCatalog ) ) {
        schemas.addAll( catalog.schemas );
      }
    }
    return schemas;
  }

  public List<Cube> getCubes(
    String parentCatalog,
    String parentSchema,
    IPentahoSession pentahoSession ) {
    final List<IOlapService.Cube> cubes = new ArrayList<>();
    for ( IOlapService.Schema schema : getSchemas( parentCatalog, pentahoSession ) ) {
      if ( parentSchema == null
        || schema.name.equals( parentSchema ) ) {
        cubes.addAll( schema.cubes );
      }
    }
    return cubes;
  }

  public OlapConnection getConnection(
    String catalogName,
    IPentahoSession session )
    throws IOlapServiceException {

    if ( catalogName == null ) {
      // This is normal. It happens on XMLA's DISCOVER_DATASOURCES
      try {
        return getServer().getConnection(
          DATASOURCE_NAME,
          null,
          null,
          new Properties() );
      } catch ( Exception e ) {
        throw new IOlapServiceException( e );
      }
    }

    // Check Access
    if ( !hasAccess( catalogName, EnumSet.of( RepositoryFilePermission.READ ), session ) ) {
      LOG.debug( "user does not have access; throwing exception" ); //$NON-NLS-1$
      throw new IOlapServiceException(
        Messages.getInstance().getErrorString(
          "OlapServiceImpl.ERROR_0003_INSUFFICIENT_PERMISSION" ), //$NON-NLS-1$
        IOlapServiceException.Reason.ACCESS_DENIED );
    }

    // Check its existence.
    if ( !getCatalogNames( session ).contains( catalogName ) ) {
      throw new IOlapServiceException(
        Messages.getInstance().getErrorString(
          "MondrianCatalogHelper.ERROR_0015_CATALOG_NOT_FOUND",
          catalogName ) );
    }

    // Check if it is a remote server
    if ( getHelper().getOlap4jServers().contains( catalogName ) ) {
      return makeOlap4jConnection( catalogName );
    }

    final StringBuilder roleName = new StringBuilder();
    Entry roleMonikor = null;
    if ( this.role != null ) {
      // We must use a custom role implementation.
      // Register the instance with the mondrian server.
      roleMonikor = getServer().getLockBox().register( this.role );
      roleName.append( roleMonikor.getMoniker() );
    } else {
      final IConnectionUserRoleMapper mapper =
          PentahoSystem.get(
              IConnectionUserRoleMapper.class,
              MDXConnection.MDX_CONNECTION_MAPPER_KEY,
              null ); // Don't use the user session here yet.

      String[] effectiveRoles = new String[0];

      /*
       * If Catalog/Schema are null (this happens with high level metadata requests,
       * like DISCOVER_DATASOURCES) we can't use the role mapper, even if it
       * is present and configured.
       */
      if ( session != null
        && mapper != null ) {
        // Use the role mapper.
        try {
          effectiveRoles =
              mapper
              .mapConnectionRoles(
                  session,
                  catalogName );
          if ( effectiveRoles == null ) {
            effectiveRoles = new String[0];
          }
        } catch ( PentahoAccessControlException e ) {
          throw new IOlapServiceException( e );
        }
      }

      // Now we tokenize that list.
      boolean addComma = false;
      for ( String role : effectiveRoles ) {
        if ( addComma ) {
          roleName.append( "," ); //$NON-NLS-1$
        }
        roleName.append( role );
        addComma = true;
      }
    }

    // Populate some properties, like locale.
    final Properties properties = new Properties();
    properties.put(
      RolapConnectionProperties.Locale.name(),
      getLocale().toString() );

    // Return a connection
    try {
      return getServer().getConnection(
        DATASOURCE_NAME,
        catalogName,
        Util.isEmpty( roleName.toString() )
          ? null
          : roleName.toString(),
        properties );
    } catch ( Exception e ) {
      throw new IOlapServiceException( e );
    } finally {
      // Cleanup our lockbox entry.
      if ( roleMonikor != null ) {
        getServer().getLockBox().deregister( roleMonikor );
      }
    }
  }

  private OlapConnection makeOlap4jConnection( String name ) {
    final Olap4jServerInfo olapServerInfo =
      getHelper().getOlap4jServerInfo( name );
    assert olapServerInfo != null;

    // Make sure the driver is present
    try {
      Class.forName( olapServerInfo.className );
    } catch ( ClassNotFoundException e ) {
      throw new IOlapServiceException( e );
    }

    // As per the JDBC specs, we can set the user/pass into
    // connection properties called 'user' and 'password'.
    final Properties newProps =
      new Properties( olapServerInfo.properties );

    // First, apply the filters.
    for ( IOlapConnectionFilter filter : this.filters ) {
      filter.filterProperties( newProps );
    }

    // Then override the user and password. We do this after the filters
    // so as not to expose this.
    if ( olapServerInfo.user != null ) {
      newProps.put(
        "user", olapServerInfo.user );
    }
    if ( olapServerInfo.password != null ) {
      newProps.put(
        "password", olapServerInfo.password );
    }

    try {
      final Connection conn =
        DriverManager.getConnection(
          olapServerInfo.URL, newProps );
      return conn.unwrap( OlapConnection.class );
    } catch ( SQLException e ) {
      throw new IOlapServiceException( e );
    }
  }

  private synchronized MondrianServer getServer() {
    if ( server == null ) {
      server =
        MondrianServerRegistry.INSTANCE.createWithRepository(
          new DynamicContentFinder( "http://not-needed.com" ) {
            @Override
            public String getContent() {
              // We dynamically generate the XML required by the
              // XMLA servlet. It must conform to Datasources.dtd,
              // as specified by olap4j-xmlaserver.
              return getDatasourcesXml();
            }
          },
          new CatalogLocator() {
            public String locate( String URL ) {
              return URL;
            }
          }
        );
    }
    return server;
  }

  private String getDatasourcesXml() {
    final Callable<String> call = new Callable<>() {
      public String call() throws Exception {
        return generateInMemoryDatasourcesXml();
      }
    };
    try {
      if ( isSecurityEnabled() ) {
        return
          SecurityHelper.getInstance().runAsSystem( call );
      } else {
        return call.call();
      }
    } catch ( Exception e ) {
      throw new IOlapServiceException( e );
    }
  }

  private String generateInMemoryDatasourcesXml() {
    StringBuffer datasourcesXML = new StringBuffer();
    datasourcesXML.append( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" ); //$NON-NLS-1$
    datasourcesXML.append( "<DataSources>\n" ); //$NON-NLS-1$

    datasourcesXML.append( "<DataSource>\n" ); //$NON-NLS-1$
    datasourcesXML.append( "<DataSourceName>" + DATASOURCE_NAME + "</DataSourceName>\n" ); //$NON-NLS-1$
    datasourcesXML.append( "<DataSourceDescription>Pentaho BI Platform Datasources</DataSourceDescription>\n" ); //$NON-NLS-1$
    datasourcesXML.append( "<URL>Xmla</URL>\n" ); //$NON-NLS-1$
    datasourcesXML.append( "<DataSourceInfo>Provider=mondrian</DataSourceInfo>\n" ); //$NON-NLS-1$
    datasourcesXML.append( "<ProviderName>PentahoXMLA</ProviderName>\n" ); //$NON-NLS-1$
    datasourcesXML.append( "<ProviderType>MDP</ProviderType>\n" ); //$NON-NLS-1$
    datasourcesXML.append( "<AuthenticationMode>Unauthenticated</AuthenticationMode>\n" ); //$NON-NLS-1$
    datasourcesXML.append( "<Catalogs>\n" ); //$NON-NLS-1$

    // Start with local catalogs.
    for ( String name : getHelper().getHostedCatalogs() ) {
      final HostedCatalogInfo hostedServerInfo =
        getHelper().getHostedCatalogInfo( name );
      addCatalogXml(
        datasourcesXML,
        hostedServerInfo.name,
        hostedServerInfo.dataSourceInfo,
        hostedServerInfo.definition );
    }

    // Don't add the olap4j catalogs. This doesn't work for now.

    datasourcesXML.append( "</Catalogs>\n" ); //$NON-NLS-1$
    datasourcesXML.append( "</DataSource>\n" ); //$NON-NLS-1$
    datasourcesXML.append( "</DataSources>\n" ); //$NON-NLS-1$
    return datasourcesXML.toString();
  }

  private void addCatalogXml( StringBuffer str, String catalogName, String dsInfo, String definition ) {
    assert definition != null;
    str.append( "<Catalog name=\"" + catalogName + "\">\n" ); //$NON-NLS-1$ //$NON-NLS-2$
    if ( dsInfo != null ) {
      str.append( "<DataSourceInfo>" + dsInfo + "</DataSourceInfo>\n" ); //$NON-NLS-1$ //$NON-NLS-2$
    }
    str.append( "<Definition>" + definition + "</Definition>\n" ); //$NON-NLS-1$ //$NON-NLS-2$
    str.append( "</Catalog>\n" ); //$NON-NLS-1$
  }

  public void setConnectionFilters( Collection<IOlapConnectionFilter> filters ) {
    this.filters.addAll( filters );
  }

  public void setMondrianRole( Role role ) {
    this.role = role;
  }

  private static Locale getLocale() {
    return LocaleHelper.getLocale();
  }
}
