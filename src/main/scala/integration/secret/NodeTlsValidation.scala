package ru.bitec.app.ops
package integration.secret

import domain.integration.{NodeTlsMaterial, RemnawaveProtocol}
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.{KeyFactory, MessageDigest, Signature}
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import javax.net.ssl.{TrustManagerFactory, X509TrustManager}
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Cryptographic validation emits fixed diagnoses; ASN.1/PEM parser messages must never escape. */
object NodeTlsValidation {
  final case class Metadata(fingerprint: String, expiresAt: Instant)
  def validate(domain: String, material: NodeTlsMaterial, now: Instant): Either[String, Metadata] = {
    if(!RemnawaveProtocol.domain(domain).contains(domain)) Left("REMNAWAVE_TLS_DOMAIN_INVALID")
    else if(material.certificatePem.length>32768 || material.privateKeyPem.length>16384)
      Left("REMNAWAVE_TLS_MATERIAL_INVALID")
    else Try {
      val factory=CertificateFactory.getInstance("X.509")
      val chain=factory.generateCertificates(new ByteArrayInputStream(material.certificatePem.getBytes(StandardCharsets.US_ASCII)))
        .asScala.toList.map(_.asInstanceOf[X509Certificate])
      require(chain.nonEmpty && chain.size<=8)
      chain
    }.toEither.left.map(_ => "REMNAWAVE_TLS_CERTIFICATE_INVALID").flatMap { chain =>
      val leaf=chain.head
      Try(Option(leaf.getSubjectAlternativeNames).toList.flatMap(_.asScala.toList).collect {
        case row if row.size()==2 && row.get(0)==Integer.valueOf(2) => row.get(1).toString.toLowerCase(java.util.Locale.ROOT)
      }).toEither.left.map(_ => "REMNAWAVE_TLS_CERTIFICATE_INVALID").flatMap { names =>
      val matches=names.exists(n => n==domain || n.startsWith("*.") &&
        domain.endsWith(n.drop(1)) && domain.split("\\.").length==n.split("\\.").length)
      if(!matches) Left("REMNAWAVE_TLS_SAN_MISMATCH")
      else if(chain.exists(c => now.isBefore(c.getNotBefore.toInstant) || !now.plusSeconds(7*86400).isBefore(c.getNotAfter.toInstant)))
        Left("REMNAWAVE_TLS_EXPIRY_TOO_CLOSE")
      else Try {
        val pem=material.privateKeyPem
        require(pem.startsWith("-----BEGIN PRIVATE KEY-----") && pem.trim.endsWith("-----END PRIVATE KEY-----"))
        val encoded=pem.stripPrefix("-----BEGIN PRIVATE KEY-----").trim.stripSuffix("-----END PRIVATE KEY-----").replaceAll("\\s", "")
        val key=KeyFactory.getInstance(leaf.getPublicKey.getAlgorithm).generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder.decode(encoded)))
        val algorithm=leaf.getPublicKey.getAlgorithm match {
          case "RSA" => "SHA256withRSA"
          case "EC" => "SHA256withECDSA"
          case "Ed25519" | "EdDSA" => "Ed25519"
          case _ => throw new IllegalArgumentException("Unsupported certificate key")
        }
        val challenge="infradesk-node-tls-key-match-v1".getBytes(StandardCharsets.US_ASCII)
        val signer=Signature.getInstance(algorithm); signer.initSign(key); signer.update(challenge)
        val proof=signer.sign()
        val verifier=Signature.getInstance(algorithm); verifier.initVerify(leaf.getPublicKey); verifier.update(challenge)
        require(verifier.verify(proof))
      }.toEither.left.map(_ => "REMNAWAVE_TLS_KEY_MISMATCH").flatMap { _ =>
        Try {
          val managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
          managers.init(null.asInstanceOf[java.security.KeyStore])
          val trust=managers.getTrustManagers.collectFirst { case m: X509TrustManager => m }.get
          trust.checkServerTrusted(chain.toArray,leaf.getPublicKey.getAlgorithm)
          val hash=MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded).map(b => f"${b & 0xff}%02x").mkString
          Metadata(hash,leaf.getNotAfter.toInstant)
        }.toEither.left.map(_ => "REMNAWAVE_TLS_CHAIN_UNTRUSTED")
      }
      }
    }
  }
}
