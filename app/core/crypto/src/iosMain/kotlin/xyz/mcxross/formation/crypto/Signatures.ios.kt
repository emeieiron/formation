package xyz.mcxross.formation.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyVerifySignature
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrKeyTypeRSA
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA384
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA512
import platform.Security.kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA256
import platform.Security.kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA384
import platform.Security.kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA512

@OptIn(ExperimentalForeignApi::class)
actual object Signatures {
  actual fun verify(
    algorithm: String,
    key: PublicKeyInfo,
    message: ByteArray,
    signature: ByteArray,
  ): Boolean {
    val scheme = signatureScheme(algorithm, key) ?: return false
    val secAlgorithm =
      when (scheme.key) {
        KeyType.RSA ->
          when (scheme.digest) {
            Digest.SHA256 -> kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA256
            Digest.SHA384 -> kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA384
            Digest.SHA512 -> kSecKeyAlgorithmRSASignatureMessagePKCS1v15SHA512
          }
        // X9.62 signatures are the DER-encoded (r, s) pairs that certificates carry.
        KeyType.EC ->
          when (scheme.digest) {
            Digest.SHA256 -> kSecKeyAlgorithmECDSASignatureMessageX962SHA256
            Digest.SHA384 -> kSecKeyAlgorithmECDSASignatureMessageX962SHA384
            Digest.SHA512 -> kSecKeyAlgorithmECDSASignatureMessageX962SHA512
          }
      }
    val attributes =
      CFDictionaryCreateMutable(
        null,
        2,
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
      )
    CFDictionaryAddValue(
      attributes,
      kSecAttrKeyType,
      if (scheme.key == KeyType.RSA) kSecAttrKeyTypeRSA else kSecAttrKeyTypeECSECPrimeRandom,
    )
    CFDictionaryAddValue(attributes, kSecAttrKeyClass, kSecAttrKeyClassPublic)
    val keyData = key.key.toCFData()
    val messageData = message.toCFData()
    val signatureData = signature.toCFData()
    try {
      val secKey = SecKeyCreateWithData(keyData, attributes, null) ?: return false
      try {
        return SecKeyVerifySignature(secKey, secAlgorithm, messageData, signatureData, null)
      } finally {
        CFRelease(secKey)
      }
    } finally {
      listOf(attributes, keyData, messageData, signatureData).forEach { it?.let(::CFRelease) }
    }
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toCFData(): CFDataRef? =
  if (isEmpty()) CFDataCreate(null, null, 0)
  else usePinned { CFDataCreate(null, it.addressOf(0).reinterpret<UByteVar>(), size.convert()) }
