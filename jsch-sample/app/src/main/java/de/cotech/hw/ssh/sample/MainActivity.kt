package de.cotech.hw.ssh.sample

import android.os.Bundle
import android.os.SystemClock
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.View
import android.widget.AdapterView
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Logger
import de.cotech.hw.SecurityKeyAuthenticator
import de.cotech.hw.openpgp.OpenPgpSecurityKey
import de.cotech.hw.openpgp.OpenPgpSecurityKey.AlgorithmConfig
import de.cotech.hw.openpgp.OpenPgpSecurityKeyDialogFragment
import de.cotech.hw.piv.PivSecurityKey
import de.cotech.hw.piv.PivSecurityKeyDialogFragment
import de.cotech.hw.secrets.PinProvider
import de.cotech.hw.ssh.SecurityKeySshAuthenticator
import de.cotech.hw.ui.SecurityKeyDialogInterface
import de.cotech.hw.ui.SecurityKeyDialogOptions
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textDescription.movementMethod = LinkMovementMethod.getInstance()

        JSch.setLogger(object : Logger {
            override fun isEnabled(level: Int) = true
            override fun log(level: Int, message: String) {
                Log.d(MyCustomApplication.TAG, String.format("Jsch: $level: $message"))
            }
        })

        buttonConnect.setOnClickListener {
            showSecurityKeyDialog()
        }

        buttonSetup.setOnClickListener {
            showSecurityKeySetupDialog()
        }

        spinnerCardType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {

            }

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (spinnerCardType.getItemAtPosition(position)) {
                    "OpenPGP" -> {
                        spinnerAlgorithm.isEnabled = true
                        buttonSetup.isEnabled = true
                    }
                    "PIV" -> {
                        spinnerAlgorithm.isEnabled = false
                        buttonSetup.isEnabled = false
                    }
                }
            }
        }
    }

    private fun showSecurityKeyDialog() {
        when (spinnerCardType.selectedItem) {
            "OpenPGP" -> showOpenPgpSecurityKeyDialog()
            "PIV" -> showPivSecurityKeyDialog()
        }
    }

    private fun showPivSecurityKeyDialog() {
        val options = SecurityKeyDialogOptions.builder()
                //.setPinLength(4) // security keys with a fixed PIN and PUK length improve the UX
                //.setPukLength(8)
                .setShowReset(true) // show button to reset/unblock of PIN using the PUK
                .setFormFactor(SecurityKeyDialogOptions.FormFactor.SECURITY_KEY)
                .setAllowKeyboard(false)
                .setPreventScreenshots(!BuildConfig.DEBUG)
                .build()

        val securityKeyDialogFragment = PivSecurityKeyDialogFragment.newInstance(options)
        securityKeyDialogFragment.setSecurityKeyDialogCallback(SecurityKeyDialogInterface.SecurityKeyDialogCallback { dialogInterface, securityKey: PivSecurityKey, pinProvider ->
            connectToSshPiv(dialogInterface, securityKey, pinProvider)
        })
        securityKeyDialogFragment.show(supportFragmentManager)
    }

    private fun showOpenPgpSecurityKeyDialog() {
        val options = SecurityKeyDialogOptions.builder()
                //.setPinLength(4) // security keys with a fixed PIN and PUK length improve the UX
                //.setPukLength(8)
                .setShowReset(true) // show button to reset/unblock of PIN using the PUK
                .setFormFactor(SecurityKeyDialogOptions.FormFactor.SECURITY_KEY)
                .setAllowKeyboard(true)
                .setPreventScreenshots(!BuildConfig.DEBUG)
                .build()

        val securityKeyDialogFragment = OpenPgpSecurityKeyDialogFragment.newInstance(options)
        securityKeyDialogFragment.setSecurityKeyDialogCallback(SecurityKeyDialogInterface.SecurityKeyDialogCallback { dialogInterface, securityKey: OpenPgpSecurityKey, pinProvider ->
            connectToSshOpenPgp(dialogInterface, securityKey, pinProvider)
        })
        securityKeyDialogFragment.show(supportFragmentManager)
    }

    private fun showSecurityKeySetupDialog() {
        val algorithm: AlgorithmConfig = when (spinnerAlgorithm.selectedItem) {
            "RSA 2048" -> {
                AlgorithmConfig.RSA_2048_UPLOAD
            }
            "ECC P-256" -> {
                AlgorithmConfig.NIST_P256_GENERATE_ON_HARDWARE
            }
            "ECC P-384" -> {
                AlgorithmConfig.NIST_P384_GENERATE_ON_HARDWARE
            }
            "ECC P-521" -> {
                AlgorithmConfig.NIST_P521_GENERATE_ON_HARDWARE
            }
            else -> {
                AlgorithmConfig.RSA_2048_UPLOAD
            }
        }

        val options = SecurityKeyDialogOptions.builder()
                .setPinMode(SecurityKeyDialogOptions.PinMode.SETUP)
                .setFormFactor(SecurityKeyDialogOptions.FormFactor.SECURITY_KEY)
                .setPreventScreenshots(!BuildConfig.DEBUG)
                .build()

        val securityKeyDialogFragment = OpenPgpSecurityKeyDialogFragment.newInstance(options)
        securityKeyDialogFragment.setSecurityKeyDialogCallback(SecurityKeyDialogInterface.SecurityKeyDialogCallback { dialogInterface, securityKey: OpenPgpSecurityKey, pinProvider ->
            setupSecurityKey(dialogInterface, securityKey, pinProvider!!, algorithm)
        })
        securityKeyDialogFragment.show(supportFragmentManager)
    }

    @UiThread
    private fun setupSecurityKey(
            dialogInterface: SecurityKeyDialogInterface,
            securityKey: OpenPgpSecurityKey,
            pinProvider: PinProvider,
            algorithm: AlgorithmConfig
    ) = GlobalScope.launch(Dispatchers.Main) {
        val deferred = GlobalScope.async(Dispatchers.IO) {
            dialogInterface.postProgressMessage("Generating keys…")
            securityKey.setupPairedKey(pinProvider, algorithm)
            dialogInterface.successAndDismiss()
        }

        try {
            deferred.await()
        } catch (e: IOException) {
            dialogInterface.postError(e)
        } catch (e: Exception) {
            Log.e(MyCustomApplication.TAG, "Exception", e)
        }
    }

    private fun connectToSshPiv(
            dialogInterface: SecurityKeyDialogInterface,
            securityKey: PivSecurityKey,
            pinProvider: PinProvider?
    ) {
        val securityKeyAuthenticator = securityKey.createSecurityKeyAuthenticator(pinProvider)
        connectToSsh(dialogInterface, securityKeyAuthenticator)
    }

    private fun connectToSshOpenPgp(
            dialogInterface: SecurityKeyDialogInterface,
            securityKey: OpenPgpSecurityKey,
            pinProvider: PinProvider?
    ) {
        val securityKeyAuthenticator = securityKey.createSecurityKeyAuthenticator(pinProvider)
        connectToSsh(dialogInterface, securityKeyAuthenticator)
    }

    private fun connectToSsh(
            dialogInterface: SecurityKeyDialogInterface,
            securityKeyAuthenticator: SecurityKeyAuthenticator
    ) = GlobalScope.launch(Dispatchers.Main) {
        val loginName = textDataUser.text.toString()
        val loginHost = textDataHost.text.toString()
        textLog.text = ""

        dialogInterface.postProgressMessage("Retrieving public key/certificate from Security Key…")
        val deferred = GlobalScope.async(Dispatchers.IO) {
            val securityKeySshAuthenticator =
                    if (checkBoxUseCertificate.isChecked) SecurityKeySshAuthenticator.fromOpenSshCertificate(securityKeyAuthenticator)
                    else SecurityKeySshAuthenticator.fromPublicKey(securityKeyAuthenticator)

            appendToLog("SecurityKeySshAuthenticator is using SSH algorithm ${securityKeySshAuthenticator.sshPublicKeyAlgorithmName}")

            val securityKeyIdentity = SecurityKeyJschIdentity(dialogInterface, loginName, securityKeySshAuthenticator)
            jschConnection(dialogInterface, loginHost, securityKeyIdentity)
        }

        try {
            deferred.await()
        } catch (e: JSchException) {
            Log.e(MyCustomApplication.TAG, "JschException", e)
            // unwrap IOExceptions thrown in SshIdentity and handle them in SecurityKeyDialogFragment
            e.cause?.let { dialogInterface.postError(it as IOException?) }
        } catch (e: IOException) {
            dialogInterface.postError(e)
        } catch (e: Exception) {
            Log.e(MyCustomApplication.TAG, "Exception", e)
        }
    }

    @WorkerThread
    private fun jschConnection(
            dialogInterface: SecurityKeyDialogInterface,
            loginHost: String,
            securityKeyIdentity: SecurityKeyJschIdentity
    ) {
        dialogInterface.postProgressMessage("Connecting to SSH server…")

        val jsch = JSch()
        // disable strict host key checking for testing purposes
        JSch.setConfig("StrictHostKeyChecking", "no")
        jsch.addIdentity(securityKeyIdentity, null)
        val sshSession = jsch.getSession(securityKeyIdentity.loginName, loginHost)

        val baos = ByteArrayOutputStream()
        baos.write("Server Output: ".toByteArray(), 0, 15)

        sshSession.connect(TIMEOUT_MS_CONNECT)

        val channel = sshSession.openChannel("shell")
        channel.outputStream = baos
        channel.connect(TIMEOUT_MS_CHANNEL)

        val startTime = SystemClock.elapsedRealtime()
        while (channel.isConnected) {
            if (SystemClock.elapsedRealtime() - startTime > MAX_CONNECTION_TIME) {
                appendToLog("SSH client automatically disconnected after $MAX_CONNECTION_TIME ms.")
                channel.disconnect()
                break
            }
        }

        // close dialog after successful authentication
        dialogInterface.successAndDismiss()
        appendToLog("SSH connection successful!")
        appendToLog("")
        appendToLog(baos.toString())
    }

    @AnyThread
    private fun appendToLog(dataText: String) {
        Log.d(MyCustomApplication.TAG, String.format("text: %s", dataText))
        scrollLog.post {
            val appendedText = "${textLog.text}\n$dataText"
            textLog.text = appendedText
            scrollLog.fullScroll(View.FOCUS_DOWN)
            // scrollLog.smoothScrollTo(0, scrollLog.height)
        }
    }

    class SecurityKeyJschIdentity(
            val dialogInterface: SecurityKeyDialogInterface,
            val loginName: String,
            private val securityKeyAuthenticator: SecurityKeySshAuthenticator
    ) : Identity {
        override fun getName() = loginName
        override fun getAlgName() = securityKeyAuthenticator.sshPublicKeyAlgorithmName
        override fun getPublicKeyBlob() = securityKeyAuthenticator.sshPublicKeyBlob
        override fun getSignature(data: ByteArray?): ByteArray {
            dialogInterface.postProgressMessage("Authenticating with Security Key…")

            // wrap IOExceptions thrown by authenticateSshChallenge() into JschExceptions to handle them later in SecurityKeyDialogFragment
            try {
                return securityKeyAuthenticator.authenticateSshChallenge(data)
            } catch (e: IOException) {
                throw JSchException("IOException", e)
            }
        }

        override fun clear() {}
        override fun isEncrypted() = false
        override fun setPassphrase(passphrase: ByteArray?) = true
        override fun decrypt() = true
    }

    companion object {
        const val MAX_CONNECTION_TIME = 5000
        const val TIMEOUT_MS_CONNECT = 10000
        const val TIMEOUT_MS_CHANNEL = 10000
    }
}
