package xyz.mcxross.formation.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.ProviderException
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.mcxross.formation.crypto.toHex

// Only the certificate chain matters: each attestation makes a fresh key and deletes it at once.
class AndroidAttestation(private val context: Context) : DeviceAttestation {
  override val packageName: String = context.packageName

  override val signers: Set<String> by lazy {
    signingCertificates().map { MessageDigest.getInstance("SHA-256").digest(it).toHex() }.toSet()
  }

  override suspend fun attest(challenge: ByteArray): List<ByteArray> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) throw UnsupportedOperationException("This phone needs Android 12 or later to attest its model.")
    return withContext(Dispatchers.IO) { generate(challenge) }
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private fun generate(challenge: ByteArray): List<ByteArray> {
    val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
      .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
      .setDigests(KeyProperties.DIGEST_SHA256)
      .setAttestationChallenge(challenge)
      .setDevicePropertiesAttestationIncluded(true)
      .build()
    val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    return try {
      KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).run {
        initialize(spec)
        generateKeyPair()
      }
      store.getCertificateChain(ALIAS)?.map { it.encoded } ?: error("The keystore returned no attestation")
    } catch (e: ProviderException) {
      // The keystore's reason sits deep in the cause chain, after a dump of every key parameter.
      val reasons = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.toList()
      if (reasons.any { "CANNOT_ATTEST_IDS" in it }) error("This phone can't attest its model.")
      error("This phone's secure hardware couldn't attest it (${reasons.firstOrNull()?.lineSequence()?.first()?.take(100)}).")
    } finally {
      runCatching { store.deleteEntry(ALIAS) }
    }
  }

  @Suppress("DEPRECATION")
  private fun signingCertificates(): List<ByteArray> {
    val packages = context.packageManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val info = packages.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return emptyList()
      val certificates = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
      certificates.orEmpty().map { it.toByteArray() }
    } else {
      packages.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty().map { it.toByteArray() }
    }
  }

  private companion object {
    const val KEYSTORE = "AndroidKeyStore"
    const val ALIAS = "formation.seeker.presence"
  }
}
