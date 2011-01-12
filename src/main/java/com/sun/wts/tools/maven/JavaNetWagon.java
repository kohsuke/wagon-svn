package com.sun.wts.tools.maven;

import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.repository.Repository;
import org.apache.maven.wagon.ConnectionException;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.SVNCancelException;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationProvider;
import org.tmatesoft.svn.core.auth.ISVNProxyManager;
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
    /**
     * Lazily loaded ~/.java.net file
     */
    private Properties props;

    @Override
    protected String getSubversionURL() {
        Repository r = getRepository();
        String url = r.getUrl();
        url = url.substring(9); // cut off "java-net:"

        return "https://svn.java.net/svn"+url;
    }

    @Override
    public void openConnection() throws ConnectionException, AuthenticationException {
        try {
            doOpenConnection();
        } catch (SVNCancelException e) {
            if(getDotJavaNetFile().exists())
                throw new ConnectionException("Unable to connect to "+getSubversionURL(),e);
            else
                throw new ConnectionException("Unable to connect to "+getSubversionURL()+" You need to create "+getDotJavaNetFile()+" See https://javanettasks.dev.java.net/nonav/maven/config.html",e);
        } catch (SVNException e) {
            throw new ConnectionException("Unable to connect to "+getSubversionURL(),e);
        }
    }

    @Override
    protected ISVNProxyManager getProxyManager(SVNURL url) {
        Properties properties = loadProperties();
        if(properties!=null) {
            final String proxyServer = properties.getProperty("proxyServer");
            final String proxyPort = properties.getProperty("proxyPort");
            if(proxyServer!=null) {
                StringBuilder debugMessage = new StringBuilder("proxyServer = " + proxyServer);
                debugMessage.append(proxyPort == null ? "" : ", proxyPort = " + proxyPort);
                fireTransferDebug(debugMessage.toString());
                return new ISVNProxyManager() {
                    public String getProxyHost() {
                        return proxyServer;
                    }

                    public int getProxyPort() {
                        if(proxyPort!=null)
                            return Integer.valueOf(proxyPort);
                        return 8080;
                    }

                    public String getProxyUserName() {
                        return null;
                    }

                    public String getProxyPassword() {
                        return null;
                    }

                    public void acknowledgeProxyContext(boolean accepted, SVNErrorMessage errorMessage) {
                    }
                };
            }
        }
        return super.getProxyManager(url);
    }

    @Override
    protected ISVNAuthenticationProvider createAuthenticationProvider() {
        return new ISVNAuthenticationProvider() {
            public SVNAuthentication requestClientAuthentication(String kind, SVNURL url, String realm, SVNErrorMessage errorMessage, SVNAuthentication previousAuth, boolean authMayBeStored) {
                if(previousAuth!=null) {
                    fireTransferDebug("Subversion authentication failed:"+url);
                    // svnkit will keep calling this method as long as we don't return null, so unless we do this
                    // this becomes infinite loop.
                    return null;
                }

                // if ~/.java.net, trust that the most
                Properties props = loadProperties();
                if(props!=null)
                    return new SVNPasswordAuthentication(
                            props.getProperty("userName"),
                            props.getProperty("password"),false);

                // fall back to ~/.m2/settings.xml
                AuthenticationInfo auth = getAuthenticationInfo();
                // maven always seems to give you non-null auth, even if nothing is configured in your settings.xml
                if(auth!=null && auth.getPassword()!=null)
                    return new SVNPasswordAuthentication(auth.getUserName(),auth.getPassword(),false);

                return null;
            }

            public int acceptServerAuthentication(SVNURL url, String realm, Object certificate, boolean resultMayBeStored) {
                return ACCEPTED_TEMPORARY;
            }
        };
    }

    protected Properties loadProperties() {
        if(props!=null)    return props;

        // load ~/.java.net
        File prop = getDotJavaNetFile();
        if(prop.exists()) {
            fireTransferDebug("Using "+prop);

            props = new Properties();
            try {
                props.load(new FileInputStream(prop));
                return props;
            } catch (IOException e) {
                fireTransferDebug("Failed to load "+prop);
                e.printStackTrace();
                return null;
            }
        } else {
            fireTransferDebug(prop+" didn't exist");
        }

        return null;
    }

    private File getDotJavaNetFile() {
        return new File(new File(System.getProperty("user.home")), ".java.net");
    }
}
