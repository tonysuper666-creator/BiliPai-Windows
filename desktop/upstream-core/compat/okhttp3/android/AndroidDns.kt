package okhttp3.android

import okhttp3.Dns
import java.net.InetAddress

/** Desktop has no Android resolver; this is the JVM system resolver (real lookups, not a stub). */
class AndroidDns : Dns { override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname) }
