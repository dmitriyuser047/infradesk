package ru.bitec.app.ops
package integration.remnawave

import java.net.InetAddress
import domain.provisioning.ServerProfileContent

/** Shared purpose-specific policy for DNS and packet candidates; it never infers a subnet. */
private[integration] object PanelSourceAddresses {
  def allowed(a: InetAddress,allowPrivate: Boolean): Boolean =
    !a.isAnyLocalAddress && !a.isLoopbackAddress && !a.isLinkLocalAddress && !a.isMulticastAddress &&
      !(a.getAddress.length==4 && ((a.getAddress()(0)&0xff)==0 || (a.getAddress()(0)&0xff)>=240)) &&
      !List("100.64.0.0/10","192.0.0.0/24","192.0.2.0/24","198.18.0.0/15","198.51.100.0/24","203.0.113.0/24","2001:db8::/32")
        .exists(ServerProfileContent.sourceCovers(_,a.getHostAddress)) &&
      (allowPrivate || (!a.isSiteLocalAddress && !(a.getAddress.length==16 && (a.getAddress()(0)&0xfe)==0xfc)))
}
