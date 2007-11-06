package com.sun.wts.tools.maven;

import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.repository.Repository;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationProvider;
import org.tmatesoft.svn.core.auth.SVNAuthentication;
import org.tmatesoft.svn.core.auth.SVNPasswordAuthentication;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Wagon implementation for connecting to java.net m2 repository.
 * @author Kohsuke Kawaguchi
 */
public class JavaNetWagon extends SubversionWagon {
    @Override
    protected String getSubversionURL() {
        Repository r = getRepository();
        String url = r.getUrl();
        url = url.substring(9); // cut off "java-net:"

        return "https://svn.dev.java.net/svn"+url;
    }

    protected ISVNAuthenticationProvider createAuthenticationProvider() {
        return new ISVNAuthenticationProvider() {
            public SVNAuthentication requestClientAuthentication(String kind, SVNURL url, String realm, SVNErrorMessage errorMessage, SVNAuthentication previousAuth, boolean authMayBeStored) {
                if(previousAuth!=null)
                    // svnkit will keep calling this method as long as we don't return null, so unless we do this
                    // this becomes infinite loop.
                    return null;

                AuthenticationInfo auth = getAuthenticationInfo();
                // maven always seems to give you non-null auth, even if nothing is configured in your settings.xml
                if(auth!=null && auth.getPassword()!=null)
                    return new SVNPasswordAuthentication(auth.getUserName(),auth.getPassword(),false);

                // load ~/.java.net
                File prop = new File(new File(System.getProperty("user.home")), ".java.net");
                if(prop.exists()) {
                    fireTransferDebug("Using "+prop);

                    Properties props = new Properties();
                    try {
                        props.load(new FileInputStream(prop));
                    } catch (IOException e) {
                        fireTransferDebug("Failed to load "+prop);
                        e.printStackTrace();
                        return null;
                    }

                    return new SVNPasswordAuthentication(
                            props.getProperty("userName"),
                            props.getProperty("password"),false);
                }

                return null;
            }

            public int acceptServerAuthentication(SVNURL url, String realm, Object certificate, boolean resultMayBeStored) {
                return ACCEPTED_TEMPORARY;
            }
        };
    }
}
