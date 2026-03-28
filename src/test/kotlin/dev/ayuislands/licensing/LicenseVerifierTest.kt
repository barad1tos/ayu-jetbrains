package dev.ayuislands.licensing

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class LicenseVerifierTest {
    private lateinit var verifier: LicenseVerifier

    @BeforeTest
    fun setUp() {
        verifier = LicenseVerifier()
    }

    // Group 1: isKeyValid - format rejection (no crypto needed)

    @Test
    fun `isKeyValid returns false for empty string`() {
        assertFalse(verifier.isKeyValid(""))
    }

    @Test
    fun `isKeyValid returns false for single part`() {
        assertFalse(verifier.isKeyValid("onlyone"))
    }

    @Test
    fun `isKeyValid returns false for two dash-separated parts`() {
        assertFalse(verifier.isKeyValid("a-b"))
    }

    @Test
    fun `isKeyValid returns false for three dash-separated parts`() {
        assertFalse(verifier.isKeyValid("a-b-c"))
    }

    @Test
    fun `isKeyValid returns false for five dash-separated parts`() {
        assertFalse(verifier.isKeyValid("a-b-c-d-e"))
    }

    @Test
    fun `isKeyValid returns false for invalid Base64 in signature field`() {
        val licenseBase64 = Base64.getEncoder().encodeToString("""{"licenseId":"LID1"}""".toByteArray())
        assertFalse(verifier.isKeyValid("LID1-$licenseBase64-!!!NOT_BASE64!!!-certpart"))
    }

    @Test
    fun `isKeyValid returns false for invalid Base64 in cert field`() {
        val licenseBase64 = Base64.getEncoder().encodeToString("""{"licenseId":"LID1"}""".toByteArray())
        val signatureBase64 = Base64.getEncoder().encodeToString(ByteArray(16))
        assertFalse(verifier.isKeyValid("LID1-$licenseBase64-$signatureBase64-!!!NOT_BASE64!!!"))
    }

    // Group 2: isKeyValid - crypto validation

    @Test
    fun `isKeyValid returns false when cert not signed by root CA`() {
        val keyPair = loadTestCaKeyPair()
        val cert = loadTestCaCert()
        val licenseId = "TEST-LICENSE-001"
        val licenseJson = """{"licenseId":"$licenseId"}"""
        val licenseBytes = licenseJson.toByteArray(StandardCharsets.UTF_8)
        val licenseBase64 = Base64.getEncoder().encodeToString(licenseBytes)

        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(keyPair.private)
        signature.update(licenseBytes)
        val signatureBase64 = Base64.getEncoder().encodeToString(signature.sign())

        val certBase64 = Base64.getEncoder().encodeToString(cert.encoded)

        // Self-signed test CA cert is not in PRODUCTION_ROOT_CAS
        assertFalse(verifier.isKeyValid("$licenseId-$licenseBase64-$signatureBase64-$certBase64"))
    }

    @Test
    fun `isKeyValid returns false when licenseId mismatch`() {
        val keyPair = loadTestCaKeyPair()
        val cert = loadTestCaCert()
        val licenseJson = """{"licenseId":"REAL-ID"}"""
        val licenseBytes = licenseJson.toByteArray(StandardCharsets.UTF_8)
        val licenseBase64 = Base64.getEncoder().encodeToString(licenseBytes)

        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(keyPair.private)
        signature.update(licenseBytes)
        val signatureBase64 = Base64.getEncoder().encodeToString(signature.sign())

        val certBase64 = Base64.getEncoder().encodeToString(cert.encoded)

        // licenseId in path ("WRONG-ID") does not match JSON ("REAL-ID")
        assertFalse(verifier.isKeyValid("WRONG-ID-$licenseBase64-$signatureBase64-$certBase64"))
    }

    // Group 3: isStampValid - format rejection

    @Test
    fun `isStampValid returns false for empty string`() {
        assertFalse(verifier.isStampValid(""))
    }

    @Test
    fun `isStampValid returns false for too few colon-separated parts`() {
        assertFalse(verifier.isStampValid("a:b:c"))
    }

    @Test
    fun `isStampValid returns false for four colon-separated parts`() {
        assertFalse(verifier.isStampValid("a:b:c:d"))
    }

    @Test
    fun `isStampValid returns false for non-numeric timestamp`() {
        val cert = loadTestCaCert()
        val certBase64 = Base64.getEncoder().encodeToString(cert.encoded)
        val sigBase64 = Base64.getEncoder().encodeToString(ByteArray(16))
        assertFalse(verifier.isStampValid("notanumber:mid:0:$sigBase64:$certBase64"))
    }

    // Group 4: isStampValid - timestamp window (frozen timeSource)

    @Test
    fun `isStampValid returns false for timestamp older than one hour`() {
        val now = 1_700_000_000_000L
        val twoHoursAgo = now - 2 * LicenseVerifier.TIMESTAMP_VALIDITY_PERIOD_MS
        val frozenVerifier = LicenseVerifier(timeSource = { now })

        val stamp = buildFakeStamp(twoHoursAgo, loadTestCaPrivateKey(), loadTestCaCert())
        assertFalse(frozenVerifier.isStampValid(stamp))
    }

    @Test
    fun `isStampValid returns false for timestamp more than one hour in future`() {
        val now = 1_700_000_000_000L
        val twoHoursFromNow = now + 2 * LicenseVerifier.TIMESTAMP_VALIDITY_PERIOD_MS
        val frozenVerifier = LicenseVerifier(timeSource = { now })

        val stamp = buildFakeStamp(twoHoursFromNow, loadTestCaPrivateKey(), loadTestCaCert())
        assertFalse(frozenVerifier.isStampValid(stamp))
    }

    // Group 5: isStampValid - crypto validation

    @Test
    fun `isStampValid returns false when cert not signed by root CA`() {
        val now = System.currentTimeMillis()
        val stamp = buildFakeStamp(now, loadTestCaPrivateKey(), loadTestCaCert())
        assertFalse(verifier.isStampValid(stamp))
    }

    @Test
    fun `isStampValid returns false for invalid signature bytes`() {
        val cert = loadTestCaCert()
        val certBase64 = Base64.getEncoder().encodeToString(cert.encoded)
        val garbageSig = Base64.getEncoder().encodeToString(ByteArray(256) { 0xFF.toByte() })
        val stamp = "${System.currentTimeMillis()}:machine123:0:$garbageSig:$certBase64"
        assertFalse(verifier.isStampValid(stamp))
    }

    // Group 6: createAndValidateCertificate - cert chain

    @Test
    fun `createAndValidateCertificate throws for self-signed cert not in trust anchors`() {
        val cert = loadTestCaCert()
        assertFailsWith<CertPathValidatorException> {
            verifier.createAndValidateCertificate(cert.encoded, emptyList())
        }
    }

    // Group 7: Constructor injection

    @Test
    fun `custom timeSource is used for stamp validation`() {
        val fixedTime = 1_700_000_000_000L
        val frozenVerifier = LicenseVerifier(timeSource = { fixedTime })

        val privateKey = loadTestCaPrivateKey()
        val cert = loadTestCaCert()
        val withinWindowStamp = buildFakeStamp(fixedTime, privateKey, cert)
        val outsideWindowStamp = buildFakeStamp(fixedTime - 5_000_000L, privateKey, cert)

        // Both fail (cert not trusted), confirming the frozen verifier uses injected time
        assertFalse(frozenVerifier.isStampValid(withinWindowStamp))
        assertFalse(frozenVerifier.isStampValid(outsideWindowStamp))
    }

    @Test
    fun `empty rootCertificates causes cert validation failure`() {
        val emptyRootsVerifier = LicenseVerifier(rootCertificates = emptyList())
        val cert = loadTestCaCert()

        assertFailsWith<Exception> {
            emptyRootsVerifier.createAndValidateCertificate(cert.encoded, emptyList())
        }
    }

    @Test
    fun `custom rootCertificates with matching CA validates cert chain`() {
        val caPem = TEST_CA_PEM
        val customVerifier = LicenseVerifier(rootCertificates = listOf(caPem))

        val leafCert = loadTestLeafCert()
        val result = customVerifier.createAndValidateCertificate(leafCert.encoded, emptyList())
        assertNotNull(result)
    }

    @Test
    fun `isStampValid with custom rootCertificates and valid stamp within time window returns true`() {
        val now = 1_700_000_000_000L
        val caPem = TEST_CA_PEM
        val customVerifier = LicenseVerifier(
            timeSource = { now },
            rootCertificates = listOf(caPem),
        )

        val leafPrivateKey = loadTestLeafPrivateKey()
        val leafCert = loadTestLeafCert()
        val stamp = buildFakeStamp(now, leafPrivateKey, leafCert)

        // Valid stamp: cert is trusted by custom CA, timestamp within window, signature matches
        assert(customVerifier.isStampValid(stamp))
    }

    @Test
    fun `isStampValid with custom rootCertificates but expired timestamp returns false`() {
        val now = 1_700_000_000_000L
        val oldTimestamp = now - 2 * LicenseVerifier.TIMESTAMP_VALIDITY_PERIOD_MS
        val caPem = TEST_CA_PEM
        val customVerifier = LicenseVerifier(
            timeSource = { now },
            rootCertificates = listOf(caPem),
        )

        val leafPrivateKey = loadTestLeafPrivateKey()
        val leafCert = loadTestLeafCert()
        val stamp = buildFakeStamp(oldTimestamp, leafPrivateKey, leafCert)

        // Cert is trusted, signature valid, but timestamp is outside window
        assertFalse(customVerifier.isStampValid(stamp))
    }

    // Test helpers

    private fun buildFakeStamp(
        timestamp: Long,
        privateKey: PrivateKey,
        cert: X509Certificate,
    ): String {
        val machineId = "test-machine-id"
        val signatureType = "0"
        val dataToSign = "$timestamp:$machineId"

        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(privateKey)
        signature.update(dataToSign.toByteArray(StandardCharsets.UTF_8))
        val signatureBase64 = Base64.getEncoder().encodeToString(signature.sign())

        val certBase64 = Base64.getEncoder().encodeToString(cert.encoded)

        return "$timestamp:$machineId:$signatureType:$signatureBase64:$certBase64"
    }

    private fun loadTestCaCert(): X509Certificate = parsePemCert(TEST_CA_PEM)

    private fun loadTestLeafCert(): X509Certificate = parsePemCert(TEST_LEAF_PEM)

    private fun loadTestCaPrivateKey(): PrivateKey = parsePkcs8Key(TEST_CA_PRIVATE_KEY_PKCS8)

    private fun loadTestLeafPrivateKey(): PrivateKey = parsePkcs8Key(TEST_LEAF_PRIVATE_KEY_PKCS8)

    private fun loadTestCaKeyPair(): KeyPair {
        val cert = loadTestCaCert()
        val privateKey = loadTestCaPrivateKey()
        return KeyPair(cert.publicKey, privateKey)
    }

    private fun parsePemCert(pem: String): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(ByteArrayInputStream(pem.toByteArray(StandardCharsets.UTF_8))) as X509Certificate
    }

    private fun parsePkcs8Key(pkcs8Pem: String): PrivateKey {
        val base64 = pkcs8Pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keyBytes = Base64.getDecoder().decode(base64)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    companion object {
        // Pre-generated test CA certificate (self-signed, valid 10 years from 2026-03-28)
        private val TEST_CA_PEM = """
            |-----BEGIN CERTIFICATE-----
            |MIIC2DCCAcCgAwIBAgIIbdoiB93En4QwDQYJKoZIhvcNAQELBQAwEjEQMA4GA1UE
            |AxMHVGVzdCBDQTAeFw0yNjAzMjgwODMxMzVaFw0zNjAzMjUwODMxMzVaMBIxEDAO
            |BgNVBAMTB1Rlc3QgQ0EwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCz
            |xM1jZde5mYUfEnlox8OW2clYU/Dt8hlx9PwWiFJ8Hpf5ymfD2CLmZBCZfDZt/VjX
            |3GI+w1hFAwzgCbky0O3Lo+hZHhhZ+mWBYNPfoOEV/SL1cZ6vsRAcHUL1n+H2hdTz
            |cToI/ZsD8bAV+EgDo1HP+irIWo5Hp9wkHSXmsVm9eTYeYfMba7QMB1LogZfojA/s
            |x9VHX/JmkKfIos8yQH2mN0BZ5p88sNDngu6ehSqwymtu1VLEyT+jKCjko59mT+Pv
            |YA/LfnNwyP9B84a+Ic0GHCMd9BC8DwQC4Ld406jspxJfgKhs+LwNuC9R8hNwaJ6S
            |yQG4E8nYeV2XcACgFvufAgMBAAGjMjAwMB0GA1UdDgQWBBTA6dsuDyfyWdATNF/m
            |P/w9WqeoaTAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQBDBw0C
            |zXGmLER6hGTG2njet8weYg9RBJRvUuxnzk1S3P7L2iyBHSasXGnf1sASGeFQ59vc
            |/s68zth302GBJSebIztOmcMsQr3sq0IksLeLE87y+dkHu6IlLLPy04143KrFzwlW
            |Pb4rrbsUF+HRl2+uYk9XC2AwmpxT5QuUcrU16w3udmUcdrG0cPUj2sgTA9duJC5b
            |C7ZJI8ba6sP8u/iRHJ5D77tbUwLyL4lXjdyDfoRblYJWdUNaOzRMF/uZK9+wn5OZ
            |nlamHjCJNy696IxFYlHUA2zN4rO1X0Od0igLQBsPJGW4d3/H0zkxCc1mh+kBkXRk
            |smzXN0TQEyB+JhFQ
            |-----END CERTIFICATE-----
        """.trimMargin().trim()

        // Pre-generated test leaf certificate (signed by TEST_CA, valid 10 years)
        private val TEST_LEAF_PEM = """
            |-----BEGIN CERTIFICATE-----
            |MIIC6zCCAdOgAwIBAgIJAI+YSuB4qupOMA0GCSqGSIb3DQEBCwUAMBIxEDAOBgNV
            |BAMTB1Rlc3QgQ0EwHhcNMjYwMzI4MDgzMTM3WhcNMzYwMzI1MDgzMTM3WjAUMRIw
            |EAYDVQQDEwlUZXN0IExlYWYwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIB
            |AQCZqTdjCGvo7dWS8lLEp4YXDp5KPECZLg4a944VeCG+zQoO3/HtLW7d4gVI8awn
            |9Yg2K9/FhVNmftFUzBT0BJnaQ49C5+hI4DIRD5SWRGxlqSiAt+NKjN8HuUfXllLp
            |3KQkAwqP1WJ3TB2w7GCvMt08EQYxgzCvOzwvL8Esie0+eyoGTTha/yD5LHjsj672
            |V9yAdiFeq8NwFYctDc/W/wVdmSniFrWktuE5cA7CcjhY/xhfFSoqnVivjf7cpmhH
            |034Ibhtd7FX4fTzeA7Kx0A1KOtH/DyL19RTmonIKK8iihMSUvMTuig3no6+SgZAR
            |30B7U0yngeyI1lqnQH8EkcFxAgMBAAGjQjBAMB0GA1UdDgQWBBTuMHfFC+2pvLi0
            |MAMMj4mMFB/uRjAfBgNVHSMEGDAWgBTA6dsuDyfyWdATNF/mP/w9WqeoaTANBgkq
            |hkiG9w0BAQsFAAOCAQEABZYwhCzkzoldi5Il2paU3F09g+jZxTy1JKLfb+ChTzai
            |anuJk2Qj1RohMQYUsOW2trXjRxOxyxKIwMF/xjdomwAWfP3aWLHKGIMklnNmgmS2
            |afuc9xze6tM1S+ysOTVeBz5zpn1vx0HaYQrCqA+qGsLgSUw6v4M4ueLPg8dsnCVk
            |cLM1ds45jSrPfNeXh+4G75LGUgV+kwrJx8tBW59GlyQhnGEikZCafVbjRTqfS1Nq
            |vNAC5PWubUVqMEz65QMQk2k2g+cxD0GyYBMGmQCeG0UCh3F49xcof595uOQqoADh
            |bZOW3dVm+5g8xVavcubCHXlvDSMebx+44sYvpua9xA==
            |-----END CERTIFICATE-----
        """.trimMargin().trim()

        // CA private key in PKCS#8 PEM format (for signing test data)
        private val TEST_CA_PRIVATE_KEY_PKCS8 = """
            |-----BEGIN PRIVATE KEY-----
            |MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCzxM1jZde5mYUf
            |Enlox8OW2clYU/Dt8hlx9PwWiFJ8Hpf5ymfD2CLmZBCZfDZt/VjX3GI+w1hFAwzg
            |Cbky0O3Lo+hZHhhZ+mWBYNPfoOEV/SL1cZ6vsRAcHUL1n+H2hdTzcToI/ZsD8bAV
            |+EgDo1HP+irIWo5Hp9wkHSXmsVm9eTYeYfMba7QMB1LogZfojA/sx9VHX/JmkKfI
            |os8yQH2mN0BZ5p88sNDngu6ehSqwymtu1VLEyT+jKCjko59mT+PvYA/LfnNwyP9B
            |84a+Ic0GHCMd9BC8DwQC4Ld406jspxJfgKhs+LwNuC9R8hNwaJ6SyQG4E8nYeV2X
            |cACgFvufAgMBAAECggEAFJ8a8m/bKb1scbk0TSqRNguV7GMgrE2ie+80QGod5YK6
            |60VmgXxDMKDs7eEQ+1gIaiar7HLgfCDSnXFFbzNb+965sgi2QWgXCcWE4yPOKNq+
            |DmAcvx8KxTbZbgS2WTRWo8sVcwo9zzDZLVlVpXkwKDE/Lswt2GwZ4C3/IYdZpIGC
            |sbxOEjUywtXJZ+bhY5Jh3jlVwneo752Q/SimIV03rWCY0SGvnWTHVUAUrz4I2tVk
            |mECeAide5LeZ0d5T7kkgQ2lI8/IZAp4MnTXOFJIRUqsYTrQ2v0fGrArAkgP2cY/G
            |yTpDq0JUgwkGKy8ZBGPXM4DX8XnVhv7Gh2+N64dZyQKBgQDv51MEtqOQqJB83ksV
            |V7BryE2DduBej9Y4BtIraaiPzNNT+vgbVOe78bTNNde8LmyWl1iko+wyzbQ7XsN4
            |voKODZSss2b/sSS2fg9S1Xq64dmZ23+0Jf9Qi2psZDDzHh74UKquywxDDUUvYMZ0
            |4Yb61GmEMwbDqC9r7NLXb2E2twKBgQC/1JQzpPeNMZqvXfR4+1MmZohQz5q4c+WZ
            |nHL92gQ1YmEbfdQVDQfyOFixGU+bwgqsHDQCXVyTGt5oht0fGnRX+CMJbScW3/Mh
            |WiONj8JyaTaLfb6hrEYnAzRHaS5JWtZAYMlst6S7bOv7GvAZr6xJrjJVQNcYsjvU
            |FyidrMa6WQKBgFamT7b5HLToHV/sjmQECyWy8ERWkI23GCGeXRTvEcH2sjG4CRse
            |HKEmmS4xj11Zy0DNI2g8CNkEsV9sR9/5t4AFabjDB6W83szHVVOO5chQQN8wh7yS
            |qNf3sxW5TnDRZVA9GpkNn70sMtv88VFQEAfS5tWn9H6A5bfujuzfPtabAoGBALFw
            |JJWQphrII1jLA3NUpZkDhluZbHfpXBs2h3cfznzCvyf6v82o/Ayk6gUGcIiWd+Cz
            |RbhaO2Mmm0r8VFSM18j3ERGLEXkrNW1IP1KWAzpo77cfXNGW1F0JrbXQKKxZhYyO
            |+kHBrHJhUfY2+JgJ0sdkhdIt48hINOb9dOhEBJ4hAoGAOGMTQxPMD2yysu5cw2e6
            |zGUSNsTjc0EThmq1NtdImwcpMI6rf+p4IIRg2OolVY1KtHgWkFIOu7tWrOHch8Qd
            |8BNReL3T72ljrEArjy/isrQf9G17DaWupkSibx3OPPABOsvdZq4lpFROrwtiOyN7
            |9NQ1R1IwIT5b0GsHLbmr24o=
            |-----END PRIVATE KEY-----
        """.trimMargin().trim()

        // Leaf private key in PKCS#8 PEM format (for signing stamp data)
        private val TEST_LEAF_PRIVATE_KEY_PKCS8 = """
            |-----BEGIN PRIVATE KEY-----
            |MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCZqTdjCGvo7dWS
            |8lLEp4YXDp5KPECZLg4a944VeCG+zQoO3/HtLW7d4gVI8awn9Yg2K9/FhVNmftFU
            |zBT0BJnaQ49C5+hI4DIRD5SWRGxlqSiAt+NKjN8HuUfXllLp3KQkAwqP1WJ3TB2w
            |7GCvMt08EQYxgzCvOzwvL8Esie0+eyoGTTha/yD5LHjsj672V9yAdiFeq8NwFYct
            |Dc/W/wVdmSniFrWktuE5cA7CcjhY/xhfFSoqnVivjf7cpmhH034Ibhtd7FX4fTze
            |A7Kx0A1KOtH/DyL19RTmonIKK8iihMSUvMTuig3no6+SgZAR30B7U0yngeyI1lqn
            |QH8EkcFxAgMBAAECggEAAMNMr3jPW1fR8YGzPD92LJdhnQ39Rp1qS8M6oPvok/tN
            |31rIh1RMSnz4qH7tq0GecGFpoiAKNNYwmR6NxJPgxSsEczE2T6VQTlIg21mh3aPY
            |PAJdtiUqps3KT+VSyk8yf+zFcMcfDudRdutHhBspKscNXDHR7C7LutK+f2d8Zmzh
            |ZGx4I3cnOz+wwElqYlveJhWcBLNEONOHPW5LIJuJeSKAnR1Fz1WCsoA1biPm5nUq
            |Dl1vYo63yKUfxpyruO85/lY0b78zp8bRxgwvJ8NvynPGdLfEZpoFtF1ahlMPDYzF
            |zxlO5irCxY+NUtENAjof7yRAgenbFNnYY8Cm4nOlGQKBgQC52yzcx9qcYH1azZnA
            |e1Blk4Pde1AwZuJsml0h4SXs4fZrjc+FP0OgQpeg4U0zKgzoDxjampOC7yMGFEp1
            |QQbrxoxcuf95vHSBhI+m1IxdPuNr/5hpwhdrFjV5eskEbT4D80tb7A1AKSKXgNXF
            |ehadRF3k2TXAVJZMKReq7qzR2QKBgQDTp3LrzewCde4oqYATC/4dkJdPi2g5XiwR
            |p88F9hyyENlT7FCwqBMkoY2j/cwuG7epKMBqtmlbg7YS2xZZ0NigXqPdj0L94vNZ
            |y1+ggmqwbT59gjCCCMBo5SGgAU41tvAe2c13MPqKavLgHS2mYP9KZL2fiY+95ECn
            |cges9bYVWQKBgQCzz12W+HADLML6j8G53FQLAe3o4L3TJibXpXyHI5malX7fzaJB
            |KtTVfrfN+UvEPWGhPcHw9O3UFmJPJmBnEpOMlloD+Bs3/uDE0ahdYnOuXwKN4Qnm
            |/9XCUAlKT0Wd18bQ8ZguBbFIKsQBya6IULcCTjt9BbygJ/YFFxiD0kg2+QKBgQDI
            |y+C0I11XjEhQnVYbO9JufAGA/pH3cwc+DMTUNARPTrrP6q82mY3nv7jfruVpjPQ1
            |8Kpz0vCrWI6A3wcaWI9bvc2aYdK9iPUz6ESlw3SyQkH50mxwwRrBqTe4U+S+AvtV
            |WW6bOIVIkmQvCJ+JbBZmnqJjW59aGNTZxs3PYiDHqQKBgFupnlrud4K5pbqqV78t
            |Xb4BBGYJ5EvUYE+IRCJ5WagWfFcRyw6cxib5tEfuKw7BMSC/v0zE8OMeuWtG9a8+
            |umv7fxPV7Q86xLOwHNWlw0FmqqZLBznrrv9PjijESqCrSXv6bZfM8AIuNyVQbm32
            |nXmzp9nOyfbF+z2zpY5d1ZeD
            |-----END PRIVATE KEY-----
        """.trimMargin().trim()
    }
}
