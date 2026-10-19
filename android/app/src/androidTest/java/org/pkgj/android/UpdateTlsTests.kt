package org.pkgj.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.pkgj.android.updates.PatchSource
import org.pkgj.android.updates.SonyUpdateTls
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

@RunWith(AndroidJUnit4::class)
class UpdateTlsTests {
    private fun fetch(leaf: HeldCertificate, issuer: X509Certificate, trusted: X509Certificate = issuer) {
        val certificates = HandshakeCertificates.Builder().heldCertificate(leaf, issuer).build()
        MockWebServer().use { server ->
            server.useHttps(certificates.sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("<titlepatch/>"))
            val source = PatchSource(configureConnection = { connection ->
                (connection as HttpsURLConnection).sslSocketFactory = SonyUpdateTls.socketFactory(trusted)
                connection.hostnameVerifier = SonyUpdateTls.hostnameVerifier("localhost")
            }, manifestUrl = { server.url("/manifest").toString() })
            assertNull(source.fetch("PCSE90001"))
        }
    }
    @Test fun acceptsValidatedLegacySha1ChainAndCnHostname() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val root = assets.open("update-test-ca.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val leaf = HeldCertificate.decode(assets.open("update-test-localhost.pem").bufferedReader().use { it.readText() })
        assertEquals("1.2.840.113549.1.1.5", leaf.certificate.sigAlgOID)
        fetch(leaf, root)
    }
    @Test fun rejectsUntrustedChainEvenWhenCaNamesMatch() {
        val issuer = HeldCertificate.Builder().rsa2048().certificateAuthority(0).commonName("Fixture CA").build()
        val wrong = HeldCertificate.Builder().rsa2048().certificateAuthority(0).commonName("Fixture CA").build()
        val leaf = HeldCertificate.Builder().rsa2048().commonName("localhost").addSubjectAlternativeName("localhost").signedBy(issuer).build()
        assertThrows(SSLException::class.java) { fetch(leaf, issuer.certificate, wrong.certificate) }
    }
    @Test fun rejectsWrongHostnameAndDoesNotFallBackFromSanToCn() {
        val issuer = HeldCertificate.Builder().rsa2048().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().rsa2048().commonName("localhost").addSubjectAlternativeName("example.invalid").signedBy(issuer).build()
        assertThrows(SSLException::class.java) { fetch(leaf, issuer.certificate) }
    }
    @Test fun rejectsExpiredCertificate() {
        val issuer = HeldCertificate.Builder().rsa2048().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().rsa2048().commonName("localhost").addSubjectAlternativeName("localhost")
            .validityInterval(1_000, 2_000).signedBy(issuer).build()
        assertThrows(SSLException::class.java) { fetch(leaf, issuer.certificate) }
    }
    @Test fun legacyConfigurationIsLimitedToTheTwoExactSonyUpdateHosts() {
        val tls = SonyUpdateTls(InstrumentationRegistry.getInstrumentation().targetContext.resources.openRawResource(R.raw.sony_update_ca))
        listOf(SonyUpdateTls.HOST, SonyUpdateTls.PS3_HOST, SonyUpdateTls.PS3_HOST.uppercase()).forEach { host ->
            val connection = java.net.URL("https://$host/manifest").openConnection() as HttpsURLConnection
            val original = connection.sslSocketFactory
            tls.configure(connection)
            assertNotSame(original, connection.sslSocketFactory)
            assertFalse(connection.hostnameVerifier.verify("example.invalid", null))
        }
        listOf("b0.ww.np.dl.playstation.net", "sub." + SonyUpdateTls.PS3_HOST,
            SonyUpdateTls.PS3_HOST + ".example.invalid", "example.invalid").forEach { host ->
            val connection = java.net.URL("https://$host/manifest").openConnection() as HttpsURLConnection
            val factory = connection.sslSocketFactory
            val verifier = connection.hostnameVerifier
            tls.configure(connection)
            assertSame(factory, connection.sslSocketFactory)
            assertSame(verifier, connection.hostnameVerifier)
        }
    }
    @Test fun hostnameWildcardsOnlyMatchOneLabelAndExactSonyHostIsRequired() {
        assertTrue(SonyUpdateTls.matchesDns(SonyUpdateTls.HOST, "*.ww.np.dl.playstation.net"))
        assertFalse(SonyUpdateTls.matchesDns("nested." + SonyUpdateTls.HOST, "*.ww.np.dl.playstation.net"))
        assertFalse(SonyUpdateTls.matchesDns("evilplaystation.net", "*.playstation.net"))
        assertFalse(SonyUpdateTls.matchesDns("playstation.net", "*.playstation.net"))
        val other = java.net.URL("https://example.invalid/").openConnection() as HttpsURLConnection
        val originalFactory = other.sslSocketFactory
        val originalVerifier = other.hostnameVerifier
        val tls = SonyUpdateTls(InstrumentationRegistry.getInstrumentation().targetContext.resources.openRawResource(R.raw.sony_update_ca))
        tls.configure(other)
        assertSame(originalFactory, other.sslSocketFactory)
        assertSame(originalVerifier, other.hostnameVerifier)
        assertFalse(SonyUpdateTls.hostnameVerifier(SonyUpdateTls.HOST).verify("example.invalid", null))
    }
}
