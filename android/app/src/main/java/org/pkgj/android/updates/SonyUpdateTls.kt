package org.pkgj.android.updates

import android.net.http.SslCertificate
import java.io.InputStream
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.security.cert.*
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Locale
import javax.net.ssl.*

/** Sony's private PKI uses SHA-1, rejected by Android's generic TLS strength policy.
 * This exact-host client still validates the complete PKIX path against a pinned CA,
 * certificate validity, server usage, key strength and DNS identity. No system defaults change.
 */
class SonyUpdateTls(ca: InputStream) {
    private val factory: SSLSocketFactory
    init {
        val root = ca.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        val digest = MessageDigest.getInstance("SHA-256").digest(root.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(digest == CA_SHA256) { "Sony update CA fingerprint mismatch" }
        factory = socketFactory(root)
    }
    fun configure(connection: HttpURLConnection) {
        if (connection is HttpsURLConnection && connection.url.host.equals(HOST, true)) {
            connection.sslSocketFactory = factory
            connection.hostnameVerifier = hostnameVerifier(HOST)
        }
    }
    companion object {
        const val HOST = "gs-sec.ww.np.dl.playstation.net"
        const val CA_SHA256 = "51d5a7833f67f1d1c6212f75997e4b83dada0f7910ba4b5a3fa4e9b7b9bc827c"
        // Internal seams let local fixture tests exercise validation without trusting fixture CAs in production.
        internal fun socketFactory(root: X509Certificate): SSLSocketFactory {
            require(root.basicConstraints >= 0) { "Update trust anchor is not a CA" }
            root.verify(root.publicKey)
            val manager = object : X509TrustManager {
                override fun getAcceptedIssuers() = arrayOf(root)
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException("Client authentication is not supported") }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (chain.isEmpty() || chain.size > 8) throw CertificateException("Invalid update certificate chain")
                    root.checkValidity()
                    chain.forEach { cert ->
                        cert.checkValidity()
                        val strong = when (val key = cert.publicKey) {
                            is RSAPublicKey -> key.modulus.bitLength() >= 2048
                            is ECPublicKey -> key.params.curve.field.fieldSize >= 256
                            else -> false
                        }
                        if (!strong) throw CertificateException("Weak update certificate key")
                        // Legacy SHA-1 RSA is allowed for this private PKI; MD2/MD4/MD5 never are.
                        if (cert.sigAlgOID !in setOf("1.2.840.113549.1.1.5", "1.2.840.113549.1.1.10",
                                "1.2.840.113549.1.1.11", "1.2.840.113549.1.1.12", "1.2.840.113549.1.1.13",
                                "1.2.840.10045.4.3.2", "1.2.840.10045.4.3.3", "1.2.840.10045.4.3.4"))
                            throw CertificateException("Unsupported update certificate signature")
                    }
                    val leaf = chain[0]
                    if (leaf.basicConstraints >= 0) throw CertificateException("Update server certificate is a CA")
                    leaf.extendedKeyUsage?.let {
                        if ("1.3.6.1.5.5.7.3.1" !in it && "2.5.29.37.0" !in it) throw CertificateException("Certificate does not permit server authentication")
                    }
                    leaf.keyUsage?.let {
                        if (!it.getOrElse(0) { false } && !it.getOrElse(2) { false }) throw CertificateException("Certificate does not permit TLS")
                    }
                    val selector = X509CertSelector().apply { certificate = leaf }
                    val params = PKIXBuilderParameters(setOf(TrustAnchor(root, null)), selector).apply {
                        isRevocationEnabled = false // Sony's private CA has no public revocation service.
                        maxPathLength = 5
                        addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(chain.toList())))
                    }
                    try { CertPathBuilder.getInstance("PKIX").build(params) }
                    catch (e: Exception) { throw CertificateException("Update certificate path is not trusted", e) }
                }
            }
            return SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }.socketFactory
        }
        internal fun hostnameVerifier(expectedHost: String) = HostnameVerifier { host, session ->
            if (!host.equals(expectedHost, true)) false else try {
                val leaf = session.peerCertificates.first() as X509Certificate
                val dns = leaf.subjectAlternativeNames.orEmpty().filter { it[0] == 2 }.mapNotNull { it[1] as? String }
                // Sony's legacy server certificate has a CN wildcard and no DNS SAN.
                if (dns.isNotEmpty()) dns.any { matchesDns(host, it) }
                else matchesDns(host, SslCertificate(leaf).issuedTo.cName)
            } catch (_: Exception) { false }
        }
        internal fun matchesDns(host: String, pattern: String): Boolean {
            val name = host.lowercase(Locale.ROOT).removeSuffix(".")
            val certificateName = pattern.lowercase(Locale.ROOT).removeSuffix(".")
            if (!name.matches(Regex("[a-z0-9.-]+")) || !certificateName.matches(Regex("[a-z0-9.*-]+"))) return false
            if ('*' !in certificateName) return name == certificateName
            if (!certificateName.startsWith("*.") || certificateName.count { it == '*' } != 1) return false
            val suffix = certificateName.substring(1)
            if (!name.endsWith(suffix)) return false
            val prefix = name.removeSuffix(suffix)
            return prefix.isNotEmpty() && '.' !in prefix && certificateName.count { it == '.' } >= 2
        }
    }
}
