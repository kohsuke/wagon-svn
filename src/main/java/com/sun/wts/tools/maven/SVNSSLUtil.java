/*
The TMate License

This license applies to all portions of TMate SVNKit library, which
are not externally-maintained libraries (e.g. Ganymed SSH library).

All the source code and compiled classes in package org.tigris.subversion.javahl
except SvnClient class are covered by the license in JAVAHL-LICENSE file

Copyright (c) 2004-2007 TMate Software. All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

    * Redistributions of source code must retain the above copyright notice,
      this list of conditions and the following disclaimer.

    * Redistributions in binary form must reproduce the above copyright notice,
      this list of conditions and the following disclaimer in the documentation
      and/or other materials provided with the distribution.

    * Redistributions in any form must be accompanied by information on how to
      obtain complete source code for the software that uses SVNKit and any
      accompanying software that uses the software that uses SVNKit. The source
      code must either be included in the distribution or be available for no
      more than the cost of distribution plus a nominal fee, and must be freely
      redistributable under reasonable conditions. For an executable file, complete
      source code means the source code for all modules it contains. It does not
      include source code for modules or files that typically accompany the major
      components of the operating system on which the executable file runs.

    * Redistribution in any form without redistributing source code for software
      that uses SVNKit is possible only when such redistribution is explictly permitted
      by TMate Software. Please, contact TMate Software at support@svnkit.com to
      get such permission.

THIS SOFTWARE IS PROVIDED BY TMATE SOFTWARE ``AS IS'' AND ANY EXPRESS OR IMPLIED
WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, OR NON-INFRINGEMENT, ARE
DISCLAIMED.

IN NO EVENT SHALL TMATE SOFTWARE BE LIABLE FOR ANY DIRECT, INDIRECT,
INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package com.sun.wts.tools.maven;

import java.security.cert.X509Certificate;
import java.security.MessageDigest;
import java.util.Date;

/**
 * @version 1.0
 * @author  TMate Software Ltd.
 */
public class SVNSSLUtil {

    public static StringBuffer getServerCertificatePrompt(X509Certificate cert, String realm, String hostName) {
        int failures = getServerCertificateFailures(cert, hostName);
        StringBuffer prompt = new StringBuffer();
        prompt.append("Error validating server certificate for '");
        prompt.append(realm);
        prompt.append("':\n");
        if ((failures & 8) != 0) {
            prompt.append(" - The certificate is not issued by a trusted authority. Use the\n" +
                          "   fingerprint to validate the certificate manually!\n");
        }
        if ((failures & 4) != 0) {
            prompt.append(" - The certificate hostname does not match.\n");
        }
        if ((failures & 2) != 0) {
            prompt.append(" - The certificate has expired.\n");
        }
        if ((failures & 1) != 0) {
            prompt.append(" - The certificate is not yet valid.\n");
        }
        getServerCertificateInfo(cert, prompt);
        return prompt;

    }

    private static String getFingerprint(X509Certificate cert) {
        StringBuffer s = new StringBuffer();
        try  {
           MessageDigest md = MessageDigest.getInstance("SHA1");
           md.update(cert.getEncoded());
           byte[] digest = md.digest();
           for (int i= 0; i < digest.length; i++)  {
              if (i != 0) {
                  s.append(':');
              }
              int b = digest[i] & 0xFF;
              String hex = Integer.toHexString(b);
              if (hex.length() == 1) {
                  s.append('0');
              }
              s.append(hex.toLowerCase());
           }
        } catch (Exception e)  {
        }
        return s.toString();
     }

  private static void getServerCertificateInfo(X509Certificate cert, StringBuffer info) {
      info.append("Certificate information:");
      info.append('\n');
      info.append(" - Subject: ");
      info.append(cert.getSubjectDN().getName());
      info.append('\n');
      info.append(" - Valid: ");
      info.append("from " + cert.getNotBefore() + " until " + cert.getNotAfter());
      info.append('\n');
      info.append(" - Issuer: ");
      info.append(cert.getIssuerDN().getName());
      info.append('\n');
      info.append(" - Fingerprint: ");
      info.append(getFingerprint(cert));
  }

  private static int getServerCertificateFailures(X509Certificate cert, String realHostName) {
      int mask = 8;
      Date time = new Date(System.currentTimeMillis());
      if (time.before(cert.getNotBefore())) {
          mask |= 1;
      }
      if (time.after(cert.getNotAfter())) {
          mask |= 2;
      }
      String hostName = cert.getSubjectDN().getName();
      int index = hostName.indexOf("CN=") + 3;
      if (index >= 0) {
          hostName = hostName.substring(index);
          if (hostName.indexOf(' ') >= 0) {
              hostName = hostName.substring(0, hostName.indexOf(' '));
          }
          if (hostName.indexOf(',') >= 0) {
              hostName = hostName.substring(0, hostName.indexOf(','));
          }
      }
      if (realHostName != null && !realHostName.equals(hostName)) {
          mask |= 4;
      }
      return mask;
  }

}
