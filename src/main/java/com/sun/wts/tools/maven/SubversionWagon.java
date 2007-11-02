/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
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
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.repository.Repository;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.io.ISVNEditor;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.io.diff.SVNDeltaGenerator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author Kohsuke Kawaguchi
 */
public class SubversionWagon extends AbstractWagon {
    private SVNRepository svnrepo;

    public void openConnection() throws ConnectionException, AuthenticationException {
        Repository r = getRepository();
        String url = r.getUrl();
        url = url.substring(4); // cut off "svn:"

        try {
            svnrepo = SVNRepositoryFactory.create(SVNURL.parseURIDecoded(url));
        } catch (SVNException e) {
            throw new ConnectionException("Unable to connect to "+url,e);
        }
    }

    protected void closeConnection() throws ConnectionException {
        try {
            if(svnrepo!=null)
                svnrepo.closeSession();
            svnrepo = null;
        } catch (SVNException e) {
            throw new ConnectionException("Failed to close svn connection",e);
        }
    }

    public void get(String resourceName, File destination) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        try {
            Map m = new HashMap();
            FileOutputStream fos = new FileOutputStream(destination);
            try {
                svnrepo.getFile(resourceName,-1/*head*/,m, fos);
            } finally {
                fos.close();
            }
        } catch (SVNException e) {
            throw new ResourceDoesNotExistException("Unable to find "+resourceName+" in "+getRepository().getUrl(),e);
        } catch (IOException e) {
            throw new TransferFailedException("Unable to find "+resourceName+" in "+getRepository().getUrl(),e);
        }
    }

    public boolean getIfNewer(String resourceName, File destination, long timestamp) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        try {
            SVNDirEntry e = svnrepo.info(resourceName, -1/*head*/);
            if( e.getDate().getTime() < timestamp )
                return false;   // older

            get(resourceName,destination);
            return true;
        } catch (SVNException e) {
            throw new ResourceDoesNotExistException("Unable to find "+resourceName+" in "+getRepository().getUrl(),e);
        }
    }

    private List<SVNDirEntry> buildInfoList(String path) throws SVNException {
        List<SVNDirEntry> r = new LinkedList<SVNDirEntry>();
        while(true) {
            r.add(0,svnrepo.info(path,-1)); // push front

            int idx = path.lastIndexOf('/');
            if(idx<0)   return r;
            path = path.substring(0,idx);
        }
    }

    public void put(File source, String destination) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        try {
            ISVNEditor editor = svnrepo.getCommitEditor("Commiting from wagon-svn", new CommitMediator());
            editor.openRoot(-1);
            put(source,"",buildInfoList(destination).iterator(),editor,destination);
            editor.closeDir();
            editor.closeEdit();
        } catch (SVNException e) {
            throw new TransferFailedException("Failed to write to "+destination,e);
        } catch (IOException e) {
            throw new TransferFailedException("Failed to write to "+destination,e);
        }
    }

    private void put(File source, String path, Iterator<SVNDirEntry> infoList, ISVNEditor editor, String destination) throws SVNException, IOException {
        int idx = destination.indexOf('/');
        if(idx>0) {
            String head = destination.substring(0,idx);
            String tail = destination.substring(idx+1);

            String child = combine(path, head);

            if(infoList.next() !=null)
                // directory exists
                editor.openDir(child,-1);
            else
                // directory doesn't exist
                editor.addDir(child,null,-1);

            put(source,child,infoList,editor,tail);
            editor.closeDir();
        } else {
            String filePath = combine(path, destination);

            // file
            if(infoList.next() !=null)
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

    private String combine(String head, String tail) {
        if(head.length()==0)    return tail;
        return head+'/'+tail;
    }
}
