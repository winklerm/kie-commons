/*
 * Copyright 2012 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.commons.java.nio.fs.jgit;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.FilterOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.util.URIUtil;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PostReceiveHook;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.eclipse.jgit.util.FileUtils;
import org.kie.commons.cluster.ClusterService;
import org.kie.commons.data.Pair;
import org.kie.commons.java.nio.IOException;
import org.kie.commons.java.nio.base.BasicFileAttributesImpl;
import org.kie.commons.java.nio.base.ExtendedAttributeView;
import org.kie.commons.java.nio.base.SeekableByteChannelFileBasedImpl;
import org.kie.commons.java.nio.base.dotfiles.DotFileOption;
import org.kie.commons.java.nio.base.options.CommentedOption;
import org.kie.commons.java.nio.base.version.VersionAttributeView;
import org.kie.commons.java.nio.base.version.VersionAttributes;
import org.kie.commons.java.nio.channels.AsynchronousFileChannel;
import org.kie.commons.java.nio.channels.SeekableByteChannel;
import org.kie.commons.java.nio.file.AccessDeniedException;
import org.kie.commons.java.nio.file.AccessMode;
import org.kie.commons.java.nio.file.AtomicMoveNotSupportedException;
import org.kie.commons.java.nio.file.CopyOption;
import org.kie.commons.java.nio.file.DirectoryNotEmptyException;
import org.kie.commons.java.nio.file.DirectoryStream;
import org.kie.commons.java.nio.file.FileAlreadyExistsException;
import org.kie.commons.java.nio.file.FileStore;
import org.kie.commons.java.nio.file.FileSystem;
import org.kie.commons.java.nio.file.FileSystemAlreadyExistsException;
import org.kie.commons.java.nio.file.FileSystemNotFoundException;
import org.kie.commons.java.nio.file.LinkOption;
import org.kie.commons.java.nio.file.NoSuchFileException;
import org.kie.commons.java.nio.file.NotDirectoryException;
import org.kie.commons.java.nio.file.NotLinkException;
import org.kie.commons.java.nio.file.OpenOption;
import org.kie.commons.java.nio.file.Path;
import org.kie.commons.java.nio.file.StandardCopyOption;
import org.kie.commons.java.nio.file.StandardOpenOption;
import org.kie.commons.java.nio.file.StandardWatchEventKind;
import org.kie.commons.java.nio.file.WatchEvent;
import org.kie.commons.java.nio.file.attribute.BasicFileAttributeView;
import org.kie.commons.java.nio.file.attribute.BasicFileAttributes;
import org.kie.commons.java.nio.file.attribute.FileAttribute;
import org.kie.commons.java.nio.file.attribute.FileAttributeView;
import org.kie.commons.java.nio.file.spi.FileSystemProvider;
import org.kie.commons.java.nio.fs.jgit.util.Daemon;
import org.kie.commons.java.nio.fs.jgit.util.DaemonClient;
import org.kie.commons.java.nio.fs.jgit.util.JGitUtil;
import org.kie.commons.message.MessageType;

import static org.eclipse.jgit.api.ListBranchCommand.ListMode.*;
import static org.eclipse.jgit.lib.Constants.*;
import static org.kie.commons.java.nio.base.dotfiles.DotFileUtils.*;
import static org.kie.commons.java.nio.file.StandardOpenOption.*;
import static org.kie.commons.java.nio.fs.jgit.util.JGitUtil.*;
import static org.kie.commons.java.nio.fs.jgit.util.JGitUtil.PathType.*;
import static org.kie.commons.validation.Preconditions.*;

public class JGitFileSystemProvider implements FileSystemProvider {

    public static final String GIT_DEFAULT_REMOTE_NAME = DEFAULT_REMOTE_NAME;
    private static final String SCHEME = "git";

    public static final String REPOSITORIES_ROOT_DIR = ".niogit";
    public static final boolean DEAMON_DEFAULT_ENABLED = true;
    public static final int DEAMON_DEFAULT_PORT = 9418;
    public static final String DEAMON_DEFAULT_HOST = "localhost";
    public static final boolean DEAMON_DEFAULT_UPLOAD = true;
    private static final String GIT_ENV_PROP_DEST_PATH = "out-dir";

    public static File FILE_REPOSITORIES_ROOT;
    public static boolean DEAMON_ENABLED;
    public static int DEAMON_PORT;
    public static boolean DEAMON_UPLOAD;
    private static String DEAMON_HOST;

    public static final String USER_NAME = "username";
    public static final String PASSWORD = "password";
    public static final String INIT = "init";

    public static final int SCHEME_SIZE = ( SCHEME + "://" ).length();
    public static final int DEFAULT_SCHEME_SIZE = ( "default://" ).length();

    private Daemon deamonService = null;
    private final Map<String, JGitFileSystem> fileSystems = new ConcurrentHashMap<String, JGitFileSystem>();
    private final Set<JGitFileSystem> closedFileSystems = new HashSet<JGitFileSystem>();
    private final Map<Repository, JGitFileSystem> repoIndex = new ConcurrentHashMap<Repository, JGitFileSystem>();

    private final String fullHostName;

    private final Map<Repository, ClusterService> clusterMap = new ConcurrentHashMap<Repository, ClusterService>();

    private boolean isDefault;

    static {
        loadConfig();
        CredentialsProvider.setDefault( new UsernamePasswordCredentialsProvider( "guest", "" ) );
    }

    public static void loadConfig() {
        final String bareReposDir = System.getProperty( "org.kie.nio.git.dir" );
        final String enabled = System.getProperty( "org.kie.nio.git.deamon.enabled" );
        final String host = System.getProperty( "org.kie.nio.git.deamon.host" );
        final String port = System.getProperty( "org.kie.nio.git.deamon.port" );
        final String upload = System.getProperty( "org.kie.nio.git.deamon.upload" );
        if ( bareReposDir == null || bareReposDir.trim().isEmpty() ) {
            FILE_REPOSITORIES_ROOT = new File( REPOSITORIES_ROOT_DIR );
        } else {
            FILE_REPOSITORIES_ROOT = new File( bareReposDir.trim(), REPOSITORIES_ROOT_DIR );
        }

        if ( enabled == null || enabled.trim().isEmpty() ) {
            DEAMON_ENABLED = DEAMON_DEFAULT_ENABLED;
        } else {
            try {
                DEAMON_ENABLED = Boolean.valueOf( enabled );
            } catch ( Exception ex ) {
                DEAMON_ENABLED = DEAMON_DEFAULT_ENABLED;
            }
        }
        if ( DEAMON_ENABLED ) {
            if ( port == null || port.trim().isEmpty() ) {
                DEAMON_PORT = DEAMON_DEFAULT_PORT;
            } else {
                DEAMON_PORT = Integer.valueOf( port );
            }
            if ( host == null || host.trim().isEmpty() ) {
                DEAMON_HOST = DEAMON_DEFAULT_HOST;
            } else {
                DEAMON_HOST = host;
            }
            if ( upload == null || upload.trim().isEmpty() ) {
                DEAMON_UPLOAD = DEAMON_DEFAULT_UPLOAD;
            } else {
                try {
                    DEAMON_UPLOAD = Boolean.valueOf( upload );
                } catch ( Exception ex ) {
                    DEAMON_UPLOAD = DEAMON_DEFAULT_UPLOAD;
                }
            }
        }
    }

    public void onCloseFileSystem( final JGitFileSystem fileSystem ) {
        closedFileSystems.add( fileSystem );
        if ( deamonService != null && closedFileSystems.size() == fileSystems.size() ) {
            deamonService.stop();
        }
    }

    // for lazy init - basically for tests
    private static class DefaultProviderHolder {

        static final JGitFileSystemProvider provider = new JGitFileSystemProvider();

        private static JGitFileSystemProvider getDefaultProvider() {
            return provider;
        }
    }

    public static JGitFileSystemProvider getInstance() {
        return DefaultProviderHolder.getDefaultProvider();
    }

    private final class RepositoryResolverImpl
            implements RepositoryResolver<DaemonClient> {

        @Override
        public Repository open( final DaemonClient client,
                                final String name )
                throws RepositoryNotFoundException,
                ServiceNotAuthorizedException, ServiceNotEnabledException,
                ServiceMayNotContinueException {
            final JGitFileSystem fs = fileSystems.get( name );
            if ( fs == null ) {
                throw new RepositoryNotFoundException( name );
            }
            return fs.gitRepo().getRepository();
        }
    }

    public JGitFileSystemProvider() {
        fullHostName = DEAMON_ENABLED ? DEAMON_HOST + ":" + DEAMON_PORT : null;

        final String[] repos = FILE_REPOSITORIES_ROOT.list( new FilenameFilter() {
            @Override
            public boolean accept( final File dir,
                                   String name ) {
                return name.endsWith( DOT_GIT_EXT );
            }
        } );
        if ( repos != null ) {
            for ( final String repo : repos ) {
                final File repoDir = new File( FILE_REPOSITORIES_ROOT, repo );
                if ( repoDir.isDirectory() ) {
                    final String name = repoDir.getName().substring( 0, repoDir.getName().indexOf( DOT_GIT_EXT ) );
                    final JGitFileSystem fs = new JGitFileSystem( this, fullHostName, newRepository( repoDir, true ), name, ALL, buildCredential( null ) );
                    fileSystems.put( name, fs );
                    repoIndex.put( fs.gitRepo().getRepository(), fs );
                }
            }
        }
        if ( DEAMON_ENABLED ) {
            buildAndStartDeamon();
        } else {
            deamonService = null;
        }
    }

    private void buildAndStartDeamon() {
        deamonService = new Daemon( new InetSocketAddress( DEAMON_HOST, DEAMON_PORT ) );
        deamonService.getService( "git-receive-pack" ).setEnabled( DEAMON_UPLOAD );
        deamonService.setRepositoryResolver( new RepositoryResolverImpl() );
        deamonService.setReceivePackFactory( new ReceivePackFactory<DaemonClient>() {
            @Override
            public ReceivePack create( final DaemonClient req,
                                       final Repository db ) throws ServiceNotEnabledException, ServiceNotAuthorizedException {

                return new ReceivePack( db ) {{
                    final ClusterService clusterService = clusterMap.get( db );
                    final JGitFileSystem fs = repoIndex.get( db );
                    final String treeRef = "master";
                    final ObjectId oldHead = JGitUtil.getTreeRefObjectId( db, treeRef );

                    setPreReceiveHook( new PreReceiveHook() {
                        @Override
                        public void onPreReceive( final ReceivePack rp,
                                                  final Collection<ReceiveCommand> commands ) {
                            if ( clusterService != null ) {
                                clusterService.lock();
                            }
                        }
                    } );

                    setPostReceiveHook( new PostReceiveHook() {
                        @Override
                        public void onPostReceive( final ReceivePack rp,
                                                   final Collection<ReceiveCommand> commands ) {
                            final ObjectId newHead = JGitUtil.getTreeRefObjectId( db, treeRef );
                            notifyDiffs( fs, treeRef, oldHead, newHead );

                            if ( clusterService != null ) {
                                clusterService.broadcast( new MessageType() {

                                                              @Override
                                                              public String toString() {
                                                                  return "SYNC_FS";
                                                              }

                                                              @Override
                                                              public int hashCode() {
                                                                  return "SYNC_FS".hashCode();
                                                              }
                                                          },
                                                          new HashMap<String, String>() {{
                                                              put( "fs_scheme", "git" );
                                                              put( "fs_id", fs.id() );
                                                              put( "fs_uri", fs.toString() );
                                                          }}
                                                        );

                                clusterService.unlock();
                            }
                        }
                    } );
                }};
            }
        } );
        try {
            deamonService.start();
        } catch ( java.io.IOException e ) {
            throw new IOException( e );
        }

    }

    private synchronized void notifyDiffs( final JGitFileSystem fs,
                                           final String tree,
                                           final ObjectId oldHead,
                                           final ObjectId newHead ) {

        final String host = tree + "@" + fs.getName();
        final Path root = JGitPathImpl.createRoot( fs, "/", host, false );

        final List<DiffEntry> diff = JGitUtil.getDiff( fs.gitRepo().getRepository(), oldHead, newHead );
        final List<WatchEvent<?>> events = new ArrayList<WatchEvent<?>>( diff.size() );

        for ( final DiffEntry diffEntry : diff ) {
            final Path oldPath;
            if ( !diffEntry.getOldPath().equals( DiffEntry.DEV_NULL ) ) {
                oldPath = JGitPathImpl.create( fs, "/" + diffEntry.getOldPath(), host, null, false );
            } else {
                oldPath = null;
            }

            final Path newPath;
            if ( !diffEntry.getNewPath().equals( DiffEntry.DEV_NULL ) ) {
                JGitPathInfo pathInfo = resolvePath( fs.gitRepo(), tree, diffEntry.getNewPath() );
                newPath = JGitPathImpl.create( fs, "/" + pathInfo.getPath(), host, pathInfo.getObjectId(), false );
            } else {
                newPath = null;
            }

            events.add( new WatchEvent() {
                @Override
                public Kind kind() {
                    switch ( diffEntry.getChangeType() ) {
                        case ADD:
                        case COPY:
                            return StandardWatchEventKind.ENTRY_CREATE;
                        case DELETE:
                            return StandardWatchEventKind.ENTRY_DELETE;
                        case MODIFY:
                            return StandardWatchEventKind.ENTRY_MODIFY;
                        case RENAME:
                            return StandardWatchEventKind.ENTRY_RENAME;
                        default:
                            throw new RuntimeException();
                    }
                }

                @Override
                public int count() {
                    return 1;
                }

                @Override
                public Object context() {
                    switch ( diffEntry.getChangeType() ) {
                        case ADD:
                        case COPY:
                            return newPath;
                        case DELETE:
                            return oldPath;
                        case MODIFY:
                            return oldPath;
                        case RENAME:
                            return new Pair<Path, Path>( oldPath, newPath );
                        default:
                            throw new RuntimeException();
                    }
                }
            } );
            fs.publishEvents( root, events );
        }
    }

    @Override
    public synchronized void forceAsDefault() {
        this.isDefault = true;
    }

    @Override
    public boolean isDefault() {
        return isDefault;
    }

    @Override
    public String getScheme() {
        return SCHEME;
    }

    @Override
    public FileSystem newFileSystem( final Path path,
                                     final Map<String, ?> env )
            throws IllegalArgumentException, UnsupportedOperationException, IOException, SecurityException {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileSystem newFileSystem( final URI uri,
                                     final Map<String, ?> env )
            throws IllegalArgumentException, IOException, SecurityException, FileSystemAlreadyExistsException {
        checkNotNull( "uri", uri );
        checkCondition( "uri scheme not supported", uri.getScheme().equals( getScheme() ) || uri.getScheme().equals( "default" ) );
        checkURI( "uri", uri );
        checkNotNull( "env", env );

        final String name = extractRepoName( uri );

        if ( fileSystems.containsKey( name ) ) {
            throw new FileSystemAlreadyExistsException();
        }

        final Git git;
        final ListBranchCommand.ListMode listMode;
        final CredentialsProvider credential;

        boolean bare = true;
        final String outPath = (String) env.get( GIT_ENV_PROP_DEST_PATH );

        final File repoDest;
        if ( outPath != null ) {
            repoDest = new File( outPath );
        } else {
            repoDest = new File( FILE_REPOSITORIES_ROOT, name + DOT_GIT_EXT );
        }

        if ( env.containsKey( GIT_DEFAULT_REMOTE_NAME ) ) {
            final String originURI = env.get( GIT_DEFAULT_REMOTE_NAME ).toString();
            credential = buildCredential( env );
            git = cloneRepository( repoDest, originURI, bare, credential );
            listMode = ALL;
        } else {
            credential = buildCredential( null );
            git = newRepository( repoDest, bare );
            listMode = null;
        }

        final JGitFileSystem fs = new JGitFileSystem( this, fullHostName, git, name, listMode, credential );
        fileSystems.put( name, fs );
        repoIndex.put( fs.gitRepo().getRepository(), fs );

        boolean init = false;

        if ( env.containsKey( INIT ) && Boolean.valueOf( env.get( INIT ).toString() ) ) {
            init = true;
        }

        if ( !env.containsKey( GIT_DEFAULT_REMOTE_NAME ) && init ) {
            try {
                final URI initURI = URI.create( getScheme() + "://master@" + name + "/readme.md" );
                final CommentedOption op = setupOp( env );
                final OutputStream stream = newOutputStream( getPath( initURI ), op );
                final String _init = "Repository Init Content\n" +
                        "=======================\n" +
                        "\n" +
                        "Your project description here.";
                stream.write( _init.getBytes() );
                stream.close();
            } catch ( final Exception e ) {
            }
            if ( !bare ) {
                //todo: checkout
            }
        }

        final Object _clusterService = env.get( "clusterService" );
        if ( _clusterService != null && _clusterService instanceof ClusterService ) {
            clusterMap.put( git.getRepository(), (ClusterService) _clusterService );
        }

        if ( DEAMON_ENABLED && deamonService != null && !deamonService.isRunning() ) {
            buildAndStartDeamon();
        }

        return fs;
    }

    private CommentedOption setupOp( final Map<String, ?> env ) {
        return null;
    }

    @Override
    public FileSystem getFileSystem( final URI uri )
            throws IllegalArgumentException, FileSystemNotFoundException, SecurityException {
        checkNotNull( "uri", uri );
        checkCondition( "uri scheme not supported", uri.getScheme().equals( getScheme() ) || uri.getScheme().equals( "default" ) );
        checkURI( "uri", uri );

        final JGitFileSystem fileSystem = fileSystems.get( extractRepoName( uri ) );

        if ( fileSystem == null ) {
            throw new FileSystemNotFoundException( "No filesystem for uri (" + uri + ") found." );
        }

        if ( hasSyncFlag( uri ) ) {
            try {
                final String treeRef = "master";
                final ObjectId oldHead = JGitUtil.getTreeRefObjectId( fileSystem.gitRepo().getRepository(), treeRef );
                final Map<String, String> params = getQueryParams( uri );
                syncRepository( fileSystem.gitRepo(), fileSystem.getCredential(), params.get( "sync" ), hasForceFlag( uri ) );
                final ObjectId newHead = JGitUtil.getTreeRefObjectId( fileSystem.gitRepo().getRepository(), treeRef );
                notifyDiffs( fileSystem, treeRef, oldHead, newHead );
            } catch ( final Exception ex ) {
                throw new IOException( ex );
            }
        }

        return fileSystem;
    }

    @Override
    public Path getPath( final URI uri )
            throws IllegalArgumentException, FileSystemNotFoundException, SecurityException {
        checkNotNull( "uri", uri );
        checkCondition( "uri scheme not supported", uri.getScheme().equals( getScheme() ) || uri.getScheme().equals( "default" ) );
        checkURI( "uri", uri );

        final JGitFileSystem fileSystem = fileSystems.get( extractRepoName( uri ) );

        if ( fileSystem == null ) {
            throw new FileSystemNotFoundException();
        }

        try {
            return JGitPathImpl.create( fileSystem, URIUtil.decode( extractPath( uri ) ), extractHost( uri ), false );
        } catch ( final URIException e ) {
            return null;
        }
    }

    @Override
    public InputStream newInputStream( final Path path,
                                       final OpenOption... options )
            throws IllegalArgumentException, UnsupportedOperationException, NoSuchFileException, IOException, SecurityException {
        checkNotNull( "path", path );

        final JGitPathImpl gPath = toPathImpl( path );

        return resolveInputStream( gPath.getFileSystem().gitRepo(), gPath.getRefTree(), gPath.getPath() );
    }

    @Override
    public OutputStream newOutputStream( final Path path,
                                         final OpenOption... options )
            throws IllegalArgumentException, UnsupportedOperationException, IOException, SecurityException {
        checkNotNull( "path", path );

        final JGitPathImpl gPath = toPathImpl( path );

        final Pair<PathType, ObjectId> result = checkPath( gPath.getFileSystem().gitRepo(), gPath.getRefTree(), gPath.getPath() );

        if ( result.getK1().equals( PathType.DIRECTORY ) ) {
            throw new IOException();
        }

        try {
            final File file = File.createTempFile( "gitz", "woot" );
            return new FilterOutputStream( new FileOutputStream( file ) ) {
                public void close() throws java.io.IOException {
                    super.close();
                    String name = null;
                    String email = null;
                    String message = null;
                    TimeZone timeZone = null;
                    Date when = null;

                    if ( options != null && options.length > 0 ) {
                        for ( final OpenOption option : options ) {
                            if ( option instanceof CommentedOption ) {
                                final CommentedOption op = (CommentedOption) option;
                                name = op.getName();
                                email = op.getEmail();
                                message = op.getMessage();
                                timeZone = op.getTimeZone();
                                when = op.getWhen();
                                break;
                            }
                        }
                    }

                    commit( gPath.getFileSystem().gitRepo(), gPath.getRefTree(), name, email, message, timeZone, when, new HashMap<String, File>() {{
                        put( gPath.getPath(), file );
                    }} );
                }
            };
        } catch ( java.io.IOException e ) {
            throw new IOException( e );
        }
    }

    @Override
    public FileChannel newFileChannel( final Path path,
                                       Set<? extends OpenOption> options,
                                       final FileAttribute<?>... attrs )
            throws IllegalArgumentException, UnsupportedOperationException, IOException, SecurityException {
        throw new UnsupportedOperationException();
    }

    @Override
    public AsynchronousFileChannel newAsynchronousFileChannel( final Path path,
                                                               final Set<? extends OpenOption> options,
                                                               final ExecutorService executor,
                                                               FileAttribute<?>... attrs )
            throws IllegalArgumentException, UnsupportedOperationException, IOException, SecurityException {
        throw new UnsupportedOperationException();
    }

    @Override
    public SeekableByteChannel newByteChannel( final Path path,
                                               final Set<? extends OpenOption> options,
                                               final FileAttribute<?>... attrs )
            throws IllegalArgumentException, UnsupportedOperationException, FileAlreadyExistsException, IOException, SecurityException {
        final JGitPathImpl gPath = toPathImpl( path );

        if ( exists( path ) ) {
            if ( !( options != null && options.contains( TRUNCATE_EXISTING ) ) ) {
                throw new FileAlreadyExistsException( path.toString() );
            }
        }

        final Pair<PathType, ObjectId> result = checkPath( gPath.getFileSystem().gitRepo(), gPath.getRefTree(), gPath.getPath() );

        if ( result.getK1().equals( PathType.DIRECTORY ) ) {
            throw new IOException();
        }

        try {
            final File file = File.createTempFile( "gitz", "woot" );

            return new SeekableByteChannelFileBasedImpl( new RandomAccessFile( file, "rw" ).getChannel() ) {
                @Override
                public void close() throws java.io.IOException {
                    super.close();
                    String name = null;
                    String email = null;
                    String message = null;
                    TimeZone timeZone = null;
                    Date when = null;

                    if ( options != null && options.size() > 0 ) {
                        for ( final OpenOption option : options ) {
                            if ( option instanceof CommentedOption ) {
                                final CommentedOption op = (CommentedOption) option;
                                name = op.getName();
                                email = op.getEmail();
                                message = op.getMessage();
                                timeZone = op.getTimeZone();
                                when = op.getWhen();
                                break;
                            }
                        }
                    }

                    File tempDot = null;
                    if ( options != null && options.contains( new DotFileOption() ) ) {
                        deleteIfExists( dot( path ) );
                        tempDot = File.createTempFile( "meta", "dot" );
                        buildDotFile( path, new FileOutputStream( tempDot ), attrs );
                    }

                    final File dotfile = tempDot;

                    commit( gPath.getFileSystem().gitRepo(), gPath.getRefTree(), name, email, message, timeZone, when, new HashMap<String, File>() {{
                        put( gPath.getPath(), file );
                        if ( dotfile != null ) {
                            put( toPathImpl( dot( gPath ) ).getPath(), dotfile );
                        }
                    }} );
                }
            };
        } catch ( java.io.IOException e ) {
            throw new IOException( e );
        }
    }

    private boolean exists( final Path path ) {
        try {
            readAttributes( path, BasicFileAttributes.class );
            return true;
        } catch ( final Exception ex ) {
        }
        return false;
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream( final Path path,
                                                     final DirectoryStream.Filter<Path> pfilter )
            throws NotDirectoryException, IOException, SecurityException {
        checkNotNull( "path", path );
        final DirectoryStream.Filter<Path> filter;
        if ( pfilter == null ) {
            filter = new DirectoryStream.Filter<Path>() {
                @Override
                public boolean accept( final Path entry ) throws IOException {
                    return true;
                }
            };
        } else {
            filter = pfilter;
        }

        final JGitPathImpl gPath = toPathImpl( path );

        final Pair<PathType, ObjectId> result = checkPath( gPath.getFileSystem().gitRepo(), gPath.getRefTree(), gPath.getPath() );

        if ( !result.getK1().equals( PathType.DIRECTORY ) ) {
            throw new NotDirectoryException( path.toString() );
        }

        final List<JGitPathInfo> pathContent = listPathContent( gPath.getFileSystem().gitRepo(), gPath.getRefTree(), gPath.getPath() );

        return new DirectoryStream<Path>() {
            boolean isClosed = false;

            @Override
            public void close() throws IOException {
                if ( isClosed ) {
                    throw new IOException();
                }
                isClosed = true;
            }

            @Override
            public Iterator<Path> iterator() {
                if ( isClosed ) {
                    throw new IOException();
                }
                return new Iterator<Path>() {
                    private int i = -1;
                    private Path nextEntry = null;
                    public boolean atEof = false;

                    @Override
                    public boolean hasNext() {
                        if ( nextEntry == null && !atEof ) {
                            nextEntry = readNextEntry();
                        }
                        return nextEntry != null;
                    }

                    @Override
                    public Path next() {
                        final Path result;
                        if ( nextEntry == null && !atEof ) {
                            result = readNextEntry();
                        } else {
                            result = nextEntry;
                            nextEntry = null;
                        }
                        if ( result == null ) {
                            throw new NoSuchElementException();
                        }
                        return result;
                    }

                    private Path readNextEntry() {
                        if ( atEof ) {
                            return null;
                        }

                        Path result = null;
                        while ( true ) {
                            i++;
                            if ( i >= pathContent.size() ) {
                                atEof = true;
                                break;
                            }

                            final JGitPathInfo content = pathContent.get( i );
                            final Path path = JGitPathImpl.create( gPath.getFileSystem(), "/" + content.getPath(), gPath.getHost(), content.getObjectId(), gPath.isRealPath() );
                            if ( filter.accept( path ) ) {
                                result = path;
                                break;
                            }
                        }

                        return result;
                    }

                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }

    @Override
    public void createDirectory( final Path path,
                                 final FileAttribute<?>... attrs )
            throws UnsupportedOperationException, FileAlreadyExistsException, IOException, SecurityException {
        checkNotNull( "path", path );

        final JGitPathImpl gPath = toPathImpl( path );

        final Pair<PathType, ObjectId> result = checkPath( gPath.getFileSystem().gitRepo(), gPath.getRefTree(), gPath.getPath() );

        if ( !result.getK1().equals( NOT_FOUND ) ) {
            throw new FileAlreadyExistsException( path.toString() );
        }

        try {
            final OutputStream outputStream = newOutputStream( path.resolve( ".gitignore" ) );
            outputStream.write( "# empty\n".getBytes() );
            outputStream.close();
        } catch ( final Exception e ) {
            throw new IOException( e );
        }
    }

    @Override
    public void createSymbolicLink( final Path link,
                                    final Path target,
                                    final FileAttribute<?>... attrs )
            throws UnsupportedOperationException, FileAlreadyExistsException, IOException, SecurityException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void createLink( final Path link,
                            final Path existing )
            throws UnsupportedOperationException, FileAlreadyExistsException, IOException, SecurityException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void delete( final Path path )
            throws DirectoryNotEmptyException, NoSuchFileException, IOException, SecurityException {
        checkNotNull( "path", path );

        if ( path instanceof JGitFSPath ) {
            deleteRepo( path.getFileSystem() );
            return;
        }

        final JGitPathImpl gPath = toPathImpl( path );

        if ( isBranch( gPath ) ) {
            deleteBranch( gPath );
            return;
        }

        deleteAsset( gPath );
    }

    private boolean deleteRepo( final FileSystem fileSystem ) {
        final File gitDir = ( (JGitFileSystem) fileSystem ).gitRepo().getRepository().getDirectory();
        fileSystem.close();

        try {
            FileUtils.delete( gitDir, FileUtils.RECURSIVE );
            closedFileSystems.remove( fileSystem );
            fileSystems.remove( ( (JGitFileSystem) fileSystem ).id() );
            return true;
        } catch ( java.io.IOException e ) {
            throw new IOException( e );
        }
    }

    public void deleteAsset( final JGitPathImpl path ) {
        final Pair<PathType, ObjectId> result = checkPath( path.getFileSystem().gitRepo(), path.getRefTree(), path.getPath() );

        if ( result.getK1().equals( PathType.DIRECTORY ) ) {
            final List<JGitPathInfo> content = listPathContent( path.getFileSystem().gitRepo(), path.getRefTree(), path.getPath() );
            if ( content.size() == 1 && content.get( 0 ).getPath().equals( path.getPath().substring( 1 ) + "/.gitignore" ) ) {
                delete( path.resolve( ".gitignore" ) );
                JGitUtil.delete( path.getFileSystem().gitRepo(), path.getRefTree(), path.getPath(), null, null, "delete {" + path.getPath() + "}", null, null );
                return;
            }
            throw new DirectoryNotEmptyException( path.toString() );
        }

        if ( result.getK1().equals( NOT_FOUND ) ) {
            throw new NoSuchFileException( path.toString() );
        }

        JGitUtil.delete( path.getFileSystem().gitRepo(), path.getRefTree(), path.getPath(), null, null, "delete {" + path.getPath() + "}", null, null );
    }

    public void deleteBranch( final JGitPathImpl path ) {
        final Ref branch = getBranch( path.getFileSystem().gitRepo(), path.getRefTree() );

        if ( branch == null ) {
            throw new NoSuchFileException( path.toString() );
        }

        JGitUtil.deleteBranch( path.getFileSystem().gitRepo(), branch );
    }

    @Override
    public boolean deleteIfExists( final Path path )
            throws DirectoryNotEmptyException, IOException, SecurityException {
        checkNotNull( "path", path );

        if ( path instanceof JGitFSPath ) {
            return deleteRepo( path.getFileSystem() );
        }

        final JGitPathImpl gPath = toPathImpl( path );

        if ( isBranch( gPath ) ) {
            return deleteBranchIfExists( gPath );
        }

        return deleteAssetIfExists( gPath );
    }

    public boolean deleteBranchIfExists( final JGitPathImpl path ) {
        final Ref branch = getBranch( path.getFileSystem().gitRepo(), path.getRefTree() );

        if ( branch == null ) {
            return false;
        }

        JGitUtil.deleteBranch( path.getFileSystem().gitRepo(), branch );
        return true;
    }

    public boolean deleteAssetIfExists( final JGitPathImpl path ) {
        final Pair<PathType, ObjectId> result = checkPath( path.getFileSystem().gitRepo(), path.getRefTree(), path.getPath() );

        if ( result.getK1().equals( PathType.DIRECTORY ) ) {
            final List<JGitPathInfo> content = listPathContent( path.getFileSystem().gitRepo(), path.getRefTree(), path.getPath() );
            if ( content.size() == 1 && content.get( 0 ).getPath().equals( path.getPath().substring( 1 ) + "/.gitignore" ) ) {
                delete( path.resolve( ".gitignore" ) );
                return true;
            }
            throw new DirectoryNotEmptyException( path.toString() );
        }

        if ( result.getK1().equals( NOT_FOUND ) ) {
            return false;
        }

        JGitUtil.delete( path.getFileSystem().gitRepo(), path.getRefTree(), path.getPath(), null, null, "delete {" + path.getPath() + "}", null, null );
        return true;
    }

    @Override
    public Path readSymbolicLink( final Path link )
            throws UnsupportedOperationException, NotLinkException, IOException, SecurityException {
        checkNotNull( "link", link );
        throw new UnsupportedOperationException();
    }

    @Override
    public void copy( final Path source,
                      final Path target,
                      final CopyOption... options )
            throws UnsupportedOperationException, FileAlreadyExistsException, DirectoryNotEmptyException, IOException, SecurityException {
        checkNotNull( "source", source );
        checkNotNull( "target", target );

        final JGitPathImpl gSource = toPathImpl( source );
        final JGitPathImpl gTarget = toPathImpl( target );

        final boolean isSourceBranch = isBranch( gSource );
        final boolean isTargetBranch = isBranch( gTarget );

        if ( isSourceBranch && isTargetBranch ) {
            copyBranch( gSource, gTarget );
            return;
        }
        copyAsset( gSource, gTarget, options );
    }

    private void copyBranch( final JGitPathImpl source,
                             final JGitPathImpl target ) {
        checkCondition( "source and taget should have same setup", !hasSameFileSystem( source, target ) );
        if ( existsBranch( target ) ) {
            throw new FileAlreadyExistsException( target.toString() );
        }
        if ( !existsBranch( source ) ) {
            throw new NoSuchFileException( target.toString() );
        }
        createBranch( source, target );
    }

    private void copyAsset( final JGitPathImpl source,
                            final JGitPathImpl target,
                            final CopyOption... options ) {
        final Pair<PathType, ObjectId> sourceResult = checkPath( source.getFileSystem().gitRepo(), source.getRefTree(), source.getPath() );
        final Pair<PathType, ObjectId> targetResult = checkPath( target.getFileSystem().gitRepo(), target.getRefTree(), target.getPath() );

        if ( !isRoot( target ) && targetResult.getK1() != NOT_FOUND ) {
            if ( !contains( options, StandardCopyOption.REPLACE_EXISTING ) ) {
                throw new FileAlreadyExistsException( target.toString() );
            }
        }

        if ( sourceResult.getK1() == NOT_FOUND ) {
            throw new NoSuchFileException( target.toString() );
        }

        if ( sourceResult.getK1() == DIRECTORY ) {
            copyDirectory( source, target, options );
            return;
        }

        copyFile( source, target, options );
    }

    private boolean contains( final CopyOption[] options,
                              final CopyOption opt ) {
        for ( final CopyOption option : options ) {
            if ( option.equals( opt ) ) {
                return true;
            }
        }
        return false;
    }

    private void copyDirectory( final JGitPathImpl source,
                                final JGitPathImpl target,
                                final CopyOption... options ) {
        final List<JGitPathImpl> directories = new ArrayList<JGitPathImpl>();
        for ( final Path path : newDirectoryStream( source, null ) ) {
            final JGitPathImpl gPath = toPathImpl( path );
            final Pair<PathType, ObjectId> pathResult = checkPath( gPath.getFileSystem().gitRepo(), gPath.getRefTree(), gPath.getPath() );
            if ( pathResult.getK1() == DIRECTORY ) {
                directories.add( gPath );
                continue;
            }
            final JGitPathImpl gTarget = composePath( target, (JGitPathImpl) gPath.getFileName() );

            copyFile( gPath, gTarget );
        }
        for ( final JGitPathImpl directory : directories ) {
            createDirectory( composePath( target, (JGitPathImpl) directory.getFileName() ) );
        }
    }

    private JGitPathImpl composePath( final JGitPathImpl directory,
                                      final JGitPathImpl fileName,
                                      final CopyOption... options ) {
        if ( directory.getPath().endsWith( "/" ) ) {
            return toPathImpl( getPath( URI.create( directory.toUri().toString() + fileName.toString( false ) ) ) );
        }
        return toPathImpl( getPath( URI.create( directory.toUri().toString() + "/" + fileName.toString( false ) ) ) );
    }

    private void copyFile( final JGitPathImpl source,
                           final JGitPathImpl target,
                           final CopyOption... options ) {

        final InputStream in = newInputStream( source, convert( options ) );
        final SeekableByteChannel out = newByteChannel( target, new HashSet<OpenOption>() {{
            add( StandardOpenOption.TRUNCATE_EXISTING );
            for ( final CopyOption _option : options ) {
                if ( _option instanceof OpenOption ) {
                    add( (OpenOption) _option );
                }
            }
        }} );

        try {
            int count;
            byte[] buffer = new byte[ 8192 ];
            while ( ( count = in.read( buffer ) ) > 0 ) {
                out.write( ByteBuffer.wrap( buffer, 0, count ) );
            }
            out.close();
        } catch ( Exception e ) {
            throw new IOException( e );
        } finally {
            try {
                out.close();
            } catch ( java.io.IOException e ) {
                throw new IOException( e );
            } finally {
                try {
                    in.close();
                } catch ( java.io.IOException e ) {
                    throw new IOException( e );
                }
            }
        }
    }

    private OpenOption[] convert( CopyOption... options ) {
        if ( options == null || options.length == 0 ) {
            return new OpenOption[ 0 ];
        }
        final List<OpenOption> newOptions = new ArrayList<OpenOption>( options.length );
        for ( final CopyOption option : options ) {
            if ( option instanceof OpenOption ) {
                newOptions.add( (OpenOption) option );
            }
        }

        return newOptions.toArray( new OpenOption[ newOptions.size() ] );
    }

    private void createBranch( final JGitPathImpl source,
                               final JGitPathImpl target ) {
        JGitUtil.createBranch( source.getFileSystem().gitRepo(), source.getRefTree(), target.getRefTree() );
    }

    private boolean existsBranch( final JGitPathImpl path ) {
        return hasBranch( path.getFileSystem().gitRepo(), path.getRefTree() );
    }

    private boolean isBranch( final JGitPathImpl path ) {
        return path.getPath().length() == 1 && path.getPath().equals( "/" );
    }

    private boolean isRoot( final JGitPathImpl path ) {
        return path.getPath().length() == 1 && path.getPath().equals( "/" );
    }

    private boolean hasSameFileSystem( final JGitPathImpl source,
                                       final JGitPathImpl target ) {
        return source.getFileSystem().equals( target );
    }

    @Override
    public void move( final Path source,
                      final Path target,
                      final CopyOption... options )
            throws DirectoryNotEmptyException, AtomicMoveNotSupportedException, IOException, SecurityException {
        checkNotNull( "source", source );
        checkNotNull( "target", target );

        try {
            copy( source, target, options );
            delete( source );
        } catch ( final Exception ex ) {
            throw new IOException( ex );
        }
    }

    @Override
    public boolean isSameFile( final Path pathA,
                               final Path pathB )
            throws IOException, SecurityException {
        checkNotNull( "pathA", pathA );
        checkNotNull( "pathB", pathB );

        final JGitPathImpl gPathA = toPathImpl( pathA );
        final JGitPathImpl gPathB = toPathImpl( pathB );

        final Pair<PathType, ObjectId> resultA = checkPath( gPathA.getFileSystem().gitRepo(), gPathA.getRefTree(), gPathA.getPath() );
        final Pair<PathType, ObjectId> resultB = checkPath( gPathB.getFileSystem().gitRepo(), gPathB.getRefTree(), gPathB.getPath() );

        if ( resultA.getK1() == PathType.FILE && resultA.getK2().equals( resultB.getK2() ) ) {
            return true;
        }

        return pathA.equals( pathB );
    }

    @Override
    public boolean isHidden( final Path path )
            throws IllegalArgumentException, IOException, SecurityException {
        checkNotNull( "path", path );

        final JGitPathImpl gPath = toPathImpl( path );

        if ( gPath.getFileName() == null ) {
            return false;
        }

        return toPathImpl( path.getFileName() ).toString( false ).startsWith( "." );
    }

    @Override
    public FileStore getFileStore( final Path path )
            throws IOException, SecurityException {
        checkNotNull( "path", path );

        return new JGitFileStore( toPathImpl( path ).getFileSystem().gitRepo().getRepository() );
    }

    @Override
    public void checkAccess( final Path path,
                             final AccessMode... modes )
            throws UnsupportedOperationException, NoSuchFileException, AccessDeniedException, IOException, SecurityException {
        checkNotNull( "path", path );

        final JGitPathImpl gPath = toPathImpl( path );

        final Pair<PathType, ObjectId> result = checkPath( gPath.getFileSystem().gitRepo(), gPath.getRefTree(), gPath.getPath() );

        if ( result.getK1().equals( NOT_FOUND ) ) {
            throw new NoSuchFileException( path.toString() );
        }
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView( final Path path,
                                                                 final Class<V> type,
                                                                 final LinkOption... options )
            throws NoSuchFileException {
        checkNotNull( "path", path );
        checkNotNull( "type", type );

        final JGitPathImpl gPath = toPathImpl( path );

        final Pair<PathType, ObjectId> pathResult = checkPath( gPath.getFileSystem().gitRepo(), gPath.getRefTree(), gPath.getPath() );
        if ( pathResult.getK1().equals( NOT_FOUND ) ) {
            throw new NoSuchFileException( path.toString() );
        }

        final V resultView = gPath.getAttrView( type );

        if ( resultView == null && ( type == BasicFileAttributeView.class || type == VersionAttributeView.class || type == JGitVersionAttributeView.class ) ) {
            final V newView = (V) new JGitVersionAttributeView( gPath );
            gPath.addAttrView( newView );
            return newView;
        }

        return resultView;
    }

    private ExtendedAttributeView getFileAttributeView( final JGitPathImpl path,
                                                        final String name,
                                                        final LinkOption... options ) {
        final ExtendedAttributeView view = path.getAttrView( name );

        if ( view == null && ( name.equals( "basic" ) || name.equals( "version" ) ) ) {
            final JGitVersionAttributeView newView = new JGitVersionAttributeView( path );
            path.addAttrView( newView );
            return newView;
        }
        return view;
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes( final Path path,
                                                             final Class<A> type,
                                                             final LinkOption... options )
            throws NoSuchFileException, UnsupportedOperationException, IOException, SecurityException {
        checkNotNull( "path", path );
        checkNotNull( "type", type );

        final JGitPathImpl gPath = toPathImpl( path );

        final Pair<PathType, ObjectId> pathResult = checkPath( gPath.getFileSystem().gitRepo(), gPath.getRefTree(), gPath.getPath() );
        if ( pathResult.getK1().equals( NOT_FOUND ) ) {
            throw new NoSuchFileException( path.toString() );
        }

        if ( type == BasicFileAttributesImpl.class || type == BasicFileAttributes.class || type == VersionAttributes.class ) {
            final JGitVersionAttributeView view = getFileAttributeView( path, JGitVersionAttributeView.class, options );
            return (A) view.readAttributes();
        }

        return null;
    }

    @Override
    public Map<String, Object> readAttributes( final Path path,
                                               final String attributes,
                                               final LinkOption... options )
            throws UnsupportedOperationException, IllegalArgumentException, IOException, SecurityException {
        checkNotNull( "path", path );
        checkNotEmpty( "attributes", attributes );

        final String[] s = split( attributes );
        if ( s[ 0 ].length() == 0 ) {
            throw new IllegalArgumentException( attributes );
        }

        final ExtendedAttributeView view = getFileAttributeView( toPathImpl( path ), s[ 0 ], options );
        if ( view == null ) {
            throw new UnsupportedOperationException( "View '" + s[ 0 ] + "' not available" );
        }

        return view.readAttributes( s[ 1 ].split( "," ) );
    }

    @Override
    public void setAttribute( final Path path,
                              final String attribute,
                              final Object value,
                              final LinkOption... options )
            throws UnsupportedOperationException, IllegalArgumentException, ClassCastException, IOException, SecurityException {
        checkNotNull( "path", path );
        checkNotEmpty( "attributes", attribute );

        final String[] s = split( attribute );
        if ( s[ 0 ].length() == 0 ) {
            throw new IllegalArgumentException( attribute );
        }
        final ExtendedAttributeView view = getFileAttributeView( toPathImpl( path ), s[ 0 ], options );
        if ( view == null ) {
            throw new UnsupportedOperationException( "View '" + s[ 0 ] + "' not available" );
        }

        view.setAttribute( s[ 1 ], value );
    }

    private void checkURI( final String paramName,
                           final URI uri )
            throws IllegalArgumentException {
        checkNotNull( "uri", uri );

        if ( uri.getAuthority() == null || uri.getAuthority().isEmpty() ) {
            throw new IllegalArgumentException( "Parameter named '" + paramName + "' is invalid, missing host repository!" );
        }

        int atIndex = uri.getPath().indexOf( "@" );
        if ( atIndex != -1 && !uri.getAuthority().contains( "@" ) ) {
            if ( uri.getPath().indexOf( "/", atIndex ) == -1 ) {
                throw new IllegalArgumentException( "Parameter named '" + paramName + "' is invalid, missing host repository!" );
            }
        }

    }

    private String extractHost( final URI uri ) {
        checkNotNull( "uri", uri );

        int atIndex = uri.getPath().indexOf( "@" );
        if ( atIndex != -1 && !uri.getAuthority().contains( "@" ) ) {
            return uri.getAuthority() + uri.getPath().substring( 0, uri.getPath().indexOf( "/", atIndex ) );
        }

        return uri.getAuthority();
    }

    private String extractRepoName( final URI uri ) {
        checkNotNull( "uri", uri );

        final String host = extractHost( uri );

        int index = host.indexOf( '@' );
        if ( index != -1 ) {
            return host.substring( index + 1 );
        }

        return host;
    }

    private boolean hasSyncFlag( final URI uri ) {
        checkNotNull( "uri", uri );

        if ( uri.getQuery() != null ) {
            return uri.getQuery().contains( "sync" );
        }

        return false;
    }

    private boolean hasForceFlag( URI uri ) {
        checkNotNull( "uri", uri );

        if ( uri.getQuery() != null ) {
            return uri.getQuery().contains( "force" );
        }

        return false;
    }

    //by spec, it should be a list of pairs, but here we're just uisng a map.
    private static Map<String, String> getQueryParams( final URI uri ) {
        final String[] params = uri.getQuery().split( "&" );
        return new HashMap<String, String>( params.length ) {{
            for ( String param : params ) {
                final String[] kv = param.split( "=" );
                final String name = kv[ 0 ];
                final String value;
                if ( kv.length == 2 ) {
                    value = kv[ 1 ];
                } else {
                    value = "";
                }

                put( name, value );
            }
        }};
    }

    private String extractPath( final URI uri ) {
        checkNotNull( "uri", uri );

        final String host = extractHost( uri );

        final String path = uri.toString().substring( getSchemeSize( uri ) + host.length() );

        if ( path.startsWith( "/:" ) ) {
            return path.substring( 2 );
        }

        return path;
    }

    private CredentialsProvider buildCredential( final Map<String, ?> env ) {
        if ( env != null ) {
            if ( env.containsKey( USER_NAME ) ) {
                if ( env.containsKey( PASSWORD ) ) {
                    return new UsernamePasswordCredentialsProvider( env.get( USER_NAME ).toString(), env.get( PASSWORD ).toString() );
                }
                return new UsernamePasswordCredentialsProvider( env.get( USER_NAME ).toString(), "" );
            }
        }
        return CredentialsProvider.getDefault();
    }

    private JGitPathImpl toPathImpl( final Path path ) {
        if ( path instanceof JGitPathImpl ) {
            return (JGitPathImpl) path;
        }
        throw new IllegalArgumentException( "Path not supported by current provider." );
    }

    private String[] split( final String attribute ) {
        final String[] s = new String[ 2 ];
        final int pos = attribute.indexOf( ':' );
        if ( pos == -1 ) {
            s[ 0 ] = "basic";
            s[ 1 ] = attribute;
        } else {
            s[ 0 ] = attribute.substring( 0, pos );
            s[ 1 ] = ( pos == attribute.length() ) ? "" : attribute.substring( pos + 1 );
        }
        return s;
    }

    private int getSchemeSize( final URI uri ) {
        if ( uri.getScheme().equals( SCHEME ) ) {
            return SCHEME_SIZE;
        }
        return DEFAULT_SCHEME_SIZE;
    }
}
