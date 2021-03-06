/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package com.sun.wts.tools.maven;

import org.apache.maven.wagon.AbstractWagon;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.repository.Repository;
import org.apache.maven.wagon.resource.Resource;
import org.codehaus.plexus.util.FileUtils;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationProvider;
import org.tmatesoft.svn.core.auth.ISVNProxyManager;
import org.tmatesoft.svn.core.internal.io.dav.DAVRepositoryFactory;
import org.tmatesoft.svn.core.internal.io.fs.FSRepositoryFactory;
import org.tmatesoft.svn.core.internal.io.svn.SVNRepositoryFactoryImpl;
import org.tmatesoft.svn.core.internal.wc.DefaultSVNAuthenticationManager;
import org.tmatesoft.svn.core.io.ISVNEditor;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.io.diff.SVNDeltaGenerator;
import org.tmatesoft.svn.core.wc.SVNWCUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.EmptyStackException;

/**
 * {@link Wagon} implementation for Subversion repository.
 *
 * <h2>Implementation Note</h2>
 * <p>
 * We bundle all the upload into one commit so that the change can be easily rolled back, and etc.
 * If a commit is in progress, no other operation cannot be performed on that connection, so
 * this means we need to use two {@link SVNRepository}s.
 *
 * @author Kohsuke Kawaguchi
 */
public class SubversionWagon extends AbstractWagon {
    /**
     * {@link SVNRepository} for querying information
     */
    private SVNRepository queryRepo;

    /**
     * {@link SVNRepository} for committing changes
     */
    private SVNRepository commitRepo;

    private String rootPath;

    private ISVNEditor editor;

    /**
     * Paths added by the commit editor that {@link #queryRepo} can't see yet.
     */
    private final Set<String> pathAdded = new HashSet<String>();

    public void openConnection() throws ConnectionException, AuthenticationException {
        try {
            doOpenConnection();
        } catch (SVNException e) {
            throw new ConnectionException("Unable to connect to "+getSubversionURL(),e);
        }
    }

    protected void doOpenConnection() throws SVNException {
        String url = getSubversionURL();

        SVNURL repoUrl = SVNURL.parseURIDecoded(url);
        queryRepo = SVNRepositoryFactory.create(repoUrl);
        configureAuthenticationManager(queryRepo);

        // when URL is given like http://svn.dev.java.net/svn/abc/trunk/xyz, we need to compute
        // repositoryRoot=http://svn.dev.java.net/abc and rootPath=/trunk/xyz
        rootPath = repoUrl.getPath().substring(queryRepo.getRepositoryRoot(true).getPath().length());
        if(rootPath.startsWith("/"))    rootPath=rootPath.substring(1);

        // at least in case of file:// URL, the commit editor remembers the root path
        // portion and that interferes with the way we work, so re-open the repository
        // with the correct root.
        SVNURL repoRoot = queryRepo.getRepositoryRoot(true);
        queryRepo.setLocation(repoRoot,false);

        // open another one for commit
        commitRepo = SVNRepositoryFactory.create(repoRoot);
        configureAuthenticationManager(commitRepo);

        // prepare a commit
        ISVNEditor svnEditor = commitRepo.getCommitEditor("Upload by wagon-svn", new CommitMediator());
        svnEditor.openRoot(-1);
        // if openRoot fails, Maven calls closeConnection anyway, so don't let the incorrect
        // editor state show through.
        this.editor = svnEditor;
    }

    /**
     * Figures out the full subversion URL to connect to.
     */
    protected String getSubversionURL() {
        Repository r = getRepository();
        String url = r.getUrl();
        url = url.substring(4); // cut off "svn:"
        return url;
    }

    private void configureAuthenticationManager(SVNRepository repo) {
        ISVNAuthenticationManager manager =
            new DefaultSVNAuthenticationManager(SVNWCUtil.getDefaultConfigurationDirectory(), true, null, null, null, null) {

                @Override
                public ISVNProxyManager getProxyManager(SVNURL url) throws SVNException {
                    ISVNProxyManager pm = super.getProxyManager(url);
                    if(pm!=null)    return pm;
                    return SubversionWagon.this.getProxyManager(url);
                }
            };

        manager.setAuthenticationProvider(createAuthenticationProvider());
        repo.setAuthenticationManager(manager);
    }

    /**
     * Gives the derived class a chance to set a proxy.
     */
    protected ISVNProxyManager getProxyManager(SVNURL url) {
        return null;
    }

    /**
     * Creates an {@link ISVNAuthenticationProvider} to use.
     */
    protected ISVNAuthenticationProvider createAuthenticationProvider() {
        return new SVNConsoleAuthenticationProvider(
            isInteractive(), getAuthenticationInfo());
    }

    protected void closeConnection() throws ConnectionException {
        try {
            // beware that Maven often calls this method without first opening the connection

            // commit
            if(editor!=null) {
                try {
                    editor.closeDir();
                    editor.closeEdit();
                } catch (EmptyStackException e) {
                    e.printStackTrace(); // debug probe
                }
                editor = null;
            }
            
            if(queryRepo !=null)
                queryRepo.closeSession();
            queryRepo = null;
            if(commitRepo !=null)
                commitRepo.closeSession();
            commitRepo = null;
        } catch (SVNException e) {
            throw new ConnectionException("Failed to close svn connection",e);
        }
    }

    public void get(String resourceName, File destination) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        Resource res = new Resource( resourceName );
        try {
            fireGetInitiated( res, destination );
            fireGetStarted( res, destination );

            Map m = new HashMap();
            destination.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(destination);
            try {
                queryRepo.getFile(combine(rootPath,resourceName),-1/*head*/,m, fos);
            } finally {
                fos.close();
            }

            postProcessListeners( res, destination, TransferEvent.REQUEST_GET );
            fireGetCompleted( res, destination );
        } catch (SVNException e) {
            throw new ResourceDoesNotExistException("Unable to find "+resourceName+" in "+getRepository().getUrl(),e);
        } catch (IOException e) {
            throw new TransferFailedException("Unable to find "+resourceName+" in "+getRepository().getUrl(),e);
        }
    }

    public boolean getIfNewer(String resourceName, File destination, long timestamp) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        try {
            SVNDirEntry e = queryRepo.info(combine(rootPath,resourceName), -1/*head*/);
            if( e.getDate().getTime() < timestamp )
                return false;   // older

            get(resourceName,destination);
            return true;
        } catch (SVNException e) {
            throw new ResourceDoesNotExistException("Unable to find "+resourceName+" in "+getRepository().getUrl(),e);
        }
    }

    public void put(File source, String destination) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        destination = combine(rootPath,destination.replace('\\','/'));
        Resource res = new Resource(destination);

        firePutInitiated(res,source);
        firePutStarted(res,source);

        try {
            put(source,"/", editor,destination);

            postProcessListeners( res, source, TransferEvent.REQUEST_PUT );
            firePutCompleted(res,source);
        } catch (SVNException e) {
            throw new TransferFailedException("Failed to write to "+destination,e);
        } catch (IOException e) {
            throw new TransferFailedException("Failed to write to "+destination,e);
        }
    }

    /**
     * The way {@link ISVNEditor} works is to recursively open a sub-directory,
     * so this is implemented as a recursion.
     *
     * @param source
     *      The file to be uploaded.
     * @param path
     *      The current directory in SVN that we are in. String like "/foo/bar/zot"
     * @param editor
     *      The listener to receive what we are uploading.
     * @param destination
     *      Path relative to the 'path' parameter indicating where to upload. String like
     */
    private void put(File source, String path, ISVNEditor editor, String destination) throws SVNException, IOException {
        pathAdded.add(normalize(path));
        int idx = destination.indexOf('/');
        if(idx>0) {
            String head = destination.substring(0,idx);
            String tail = destination.substring(idx+1);

            String child = combine(path, head);

            if(exists(child) || pathAdded.contains(normalize(child))) {
                // directory exists
                try {
                    editor.openDir(child,-1);
                } catch (SVNException e) {
                    // in case it fails, try to fall back to add
                    editor.addDir(child,null,-1);
                }
            } else
                // directory doesn't exist
                editor.addDir(child,null,-1);

            put(source,child, editor,tail);
            editor.closeDir();
        } else {
            String filePath = combine(path, destination);

            // file
            if(exists(filePath) || pathAdded.contains(normalize(filePath)))
                // update file
                editor.openFile(filePath,-1);
            else
                // add file
                editor.addFile(filePath,null,-1);

            editor.applyTextDelta(filePath,null);

            SVNDeltaGenerator dg = new SVNDeltaGenerator();
            FileInputStream fin = new FileInputStream(source);
            String checksum = null;
            try {
                checksum = dg.sendDelta(filePath,fin,editor,true);
            } finally {
                fin.close();
            }
            editor.closeFile(filePath,checksum);
        }
    }

    private boolean exists(String child) throws SVNException {
        try {
            return queryRepo.info(child,-1)!=null;
        } catch (SVNException e) {
            // https:// protocol reports an error whereas it should return null
            return false;
        }
    }

    @Override
    public boolean supportsDirectoryCopy() {
        return true;
    }

    @Override
    public void putDirectory(File sourceDirectory, String destinationDirectory) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        try {
            List<String> files = FileUtils.getFileNames( sourceDirectory, "**/**", "", false );

            for (String file : files)
                put(new File(sourceDirectory,file),combine(destinationDirectory,file));
        } catch (IOException e) {
            throw new TransferFailedException("Failed to list up files in "+sourceDirectory,e);
        }
    }

    private String combine(String head, String tail) {
        if(head.length()==0)    return tail;
        if(head.endsWith("/"))  head=head.substring(0,head.length()-1);
        if(tail.startsWith("/"))  tail=tail.substring(1);
        return head+'/'+tail;
    }

    /**
     * Either svnkit or Maven messes up and often uses '//' where '/' is suffice,
     * so this method is to clean that up.
     */
    private String normalize(String str) {
        while(true) {
            int idx = str.indexOf("//");
            if(idx<0)   return str;
            str = str.substring(0,idx)+str.substring(idx+1);
        }
    }

    static {
        DAVRepositoryFactory.setup();   // http, https
        SVNRepositoryFactoryImpl.setup();   // svn, svn+xxx
        FSRepositoryFactory.setup();    // file
    }
}
