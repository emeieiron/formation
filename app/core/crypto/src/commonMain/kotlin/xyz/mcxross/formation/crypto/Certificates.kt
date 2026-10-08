package xyz.mcxross.formation.crypto

class PublicKeyInfo(
  val algorithm: String,
  val curve: String?,
  // The subjectPublicKey bits: a PKCS #1 RSAPublicKey or an uncompressed elliptic-curve point.
  val key: ByteArray,
  val encoded: ByteArray,
)

class Certificate
private constructor(
  val encoded: ByteArray,
  internal val signed: ByteArray,
  val serial: String,
  val issuer: ByteArray,
  val subject: ByteArray,
  val subjectAttributes: List<Pair<String, String>>,
  val notBefore: Long,
  val notAfter: Long,
  val publicKey: PublicKeyInfo,
  val signatureAlgorithm: String,
  internal val signature: ByteArray,
  val extensions: Map<String, ByteArray>,
) {
  val selfIssued: Boolean
    get() = issuer.contentEquals(subject)

  fun attribute(oid: String): String? = subjectAttributes.firstOrNull { it.first == oid }?.second

  fun signedBy(key: PublicKeyInfo): Boolean =
    Signatures.verify(signatureAlgorithm, key, signed, signature)

  companion object {
    const val COMMON_NAME = "2.5.4.3"
    const val SERIAL_NUMBER = "2.5.4.5"
    const val ORGANIZATION = "2.5.4.10"

    fun parse(bytes: ByteArray): Certificate = malformedAs("certificate") { read(bytes) }

    private fun read(bytes: ByteArray): Certificate {
      val parts = Der.parse(bytes).sequence()
      if (parts.size != 3) throw DerException("A certificate has three parts")
      val (tbs, outerAlgorithm, signature) = parts
      val fields = tbs.sequence()
      var at = if (fields.firstOrNull()?.isContext(0) == true) 1 else 0
      fun field() = fields.getOrNull(at++) ?: throw DerException("Truncated certificate")
      val serial = field().integerBytes()
      val innerAlgorithm = algorithm(field())
      val issuer = field().also { it.sequence() }
      val validity = field().sequence()
      if (validity.size != 2) throw DerException("Malformed validity")
      val subject = field()
      val spki = field()
      val extensions = mutableMapOf<String, ByteArray>()
      while (at < fields.size) {
        val optional = field()
        if (!optional.isContext(3)) continue
        for (extension in optional.explicit().sequence()) {
          val items = extension.sequence()
          if (items.size !in 2..3) throw DerException("Malformed extension")
          val oid = items.first().oid()
          if (extensions.put(oid, items.last().octets()) != null)
            throw DerException("Repeated extension $oid")
        }
      }
      val algorithmName = algorithm(outerAlgorithm)
      if (algorithmName != innerAlgorithm) throw DerException("Signature algorithms disagree")
      return Certificate(
        encoded = bytes.copyOf(),
        signed = tbs.encoded,
        serial = serial.toHex().trimStart('0').ifEmpty { "0" },
        issuer = issuer.encoded,
        subject = subject.encoded,
        subjectAttributes =
          subject
            .sequence()
            .flatMap { it.set() }
            .map {
              val (type, value) = it.sequence().let { pair -> pair[0] to pair[1] }
              type.oid() to value.string()
            },
        notBefore = validity[0].time(),
        notAfter = validity[1].time(),
        publicKey = publicKey(spki),
        signatureAlgorithm = algorithmName,
        signature = signature.bitString(),
        extensions = extensions,
      )
    }

    // Accepts one or more concatenated PEM blocks, in order.
    fun parsePem(text: String): List<Certificate> =
      Regex("-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----")
        .findAll(text)
        .map {
          parse(Base64.decode(it.groupValues[1].filterNot(Char::isWhitespace)))
        }
        .toList()

    private fun algorithm(value: Der): String =
      (value.sequence().firstOrNull() ?: throw DerException("Empty algorithm")).oid()

    private fun publicKey(spki: Der): PublicKeyInfo {
      val parts = spki.sequence()
      if (parts.size != 2) throw DerException("Malformed key")
      val (algorithm, key) = parts
      val identifiers = algorithm.sequence()
      val curve = identifiers.getOrNull(1)?.takeIf { it.isUniversal(Der.OID) }?.oid()
      return PublicKeyInfo(identifiers.first().oid(), curve, key.bitString(), spki.encoded)
    }
  }
}
