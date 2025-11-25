package com.github.gotify.login

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.github.gotify.R
import com.github.gotify.SSLSettings
import com.github.gotify.Settings
import com.github.gotify.Utils
import com.github.gotify.api.Callback
import com.github.gotify.api.Callback.SuccessCallback
import com.github.gotify.api.CertUtils
import com.github.gotify.api.ClientFactory
import com.github.gotify.client.ApiClient
import com.github.gotify.client.api.ClientApi
import com.github.gotify.client.api.UserApi
import com.github.gotify.client.model.Client
import com.github.gotify.client.model.ClientParams
import com.github.gotify.client.model.VersionInfo
import com.github.gotify.databinding.ActivityLoginBinding
import com.github.gotify.databinding.ClientNameDialogBinding
import com.github.gotify.init.InitializationActivity
import com.github.gotify.log.LogsActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import java.util.UUID
import org.tinylog.kotlin.Logger

internal class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var settings: Settings

    private var disableSslValidation = false
    private var caCertCN: String? = null
    private var caCertPath: String? = null
    private var clientCertPath: String? = null
    private var clientCertPassword: String? = null
    private lateinit var advancedDialog: AdvancedDialog

    private val caDialogResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        try {
            if (result.resultCode == RESULT_OK && result.data != null) {
                val uri = result.data!!.data!!
                val fileStream = contentResolver.openInputStream(uri)!!
                val destinationFile = File(filesDir, CertUtils.CA_CERT_NAME)
                copyStreamToFile(fileStream, destinationFile)
                caCertCN = getNameOfCertContent(destinationFile) ?: "unknown"
                caCertPath = destinationFile.absolutePath
                advancedDialog.showRemoveCaCertificate(caCertCN!!)
            }
        } catch (e: Exception) {
            Utils.showSnackBar(this, getString(R.string.select_ca_failed, e.message))
        }
    }

    private val clientCertDialogResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        try {
            if (result.resultCode == RESULT_OK && result.data != null) {
                val uri = result.data!!.data!!
                val fileStream = contentResolver.openInputStream(uri)!!
                val destinationFile = File(filesDir, CertUtils.CLIENT_CERT_NAME)
                copyStreamToFile(fileStream, destinationFile)
                clientCertPath = destinationFile.absolutePath
                advancedDialog.showRemoveClientCertificate()
            }
        } catch (e: Exception) {
            Utils.showSnackBar(this, getString(R.string.select_client_failed, e.message))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Logger.info("Entering ${javaClass.simpleName}")
        settings = Settings(this)

        // ★ 1. 硬编码服务器地址
        val myServerUrl = "https://sms.uuuu.tech" 
        binding.gotifyUrlEditext.setText(myServerUrl)
        binding.gotifyUrlEditext.isEnabled = false 
        
        // ★ 2. 自动注册并登录
        startAutoRegisterAndLogin(myServerUrl)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        binding.gotifyUrlEditext.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, i: Int, i1: Int, i2: Int) {}
            override fun onTextChanged(s: CharSequence, i: Int, i1: Int, i2: Int) { invalidateUrl() }
            override fun afterTextChanged(e: Editable) {}
        })
        binding.checkurl.setOnClickListener { doCheckUrl() }
        binding.openLogs.setOnClickListener { openLogs() }
        binding.advancedSettings.setOnClickListener { toggleShowAdvanced() }
        binding.login.setOnClickListener { doLogin() }
    }

    private fun invalidateUrl() {
        binding.username.visibility = View.GONE
        binding.password.visibility = View.GONE
        binding.login.visibility = View.GONE
        binding.checkurl.text = getString(R.string.check_url)
    }

    private fun doCheckUrl() {
        val urlStr = binding.gotifyUrlEditext.text.toString().trim().trimEnd('/')
        try {
            val url = URL(urlStr)
            if (url.protocol != "http" && url.protocol != "https") throw Exception("Invalid Protocol")
            if (url.protocol == "http") showHttpWarning()
            
            binding.checkurlProgress.visibility = View.VISIBLE
            binding.checkurl.visibility = View.GONE

            ClientFactory.versionApi(settings, tempSslSettings(), urlStr)
                .version
                .enqueue(Callback.callInUI(this, onValidUrl(urlStr), onInvalidUrl(urlStr)))

        } catch (e: Exception) {
            Utils.showSnackBar(this, "Invalid URL")
        }
    }

    private fun showHttpWarning() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.warning)
            .setMessage(R.string.http_warning)
            .setPositiveButton(R.string.i_understand, null)
            .show()
    }

    private fun openLogs() { startActivity(Intent(this, LogsActivity::class.java)) }

    private fun toggleShowAdvanced() {
        advancedDialog = AdvancedDialog(this, layoutInflater)
            .onDisableSSLChanged { _, disable -> invalidateUrl(); disableSslValidation = disable }
            .onClickSelectCaCertificate { invalidateUrl(); doSelectCertificate(caDialogResultLauncher, R.string.select_ca_file) }
            .onClickRemoveCaCertificate { invalidateUrl(); caCertPath = null; clientCertPassword = null }
            .onClickSelectClientCertificate { invalidateUrl(); doSelectCertificate(clientCertDialogResultLauncher, R.string.select_client_file) }
            .onClickRemoveClientCertificate { invalidateUrl(); clientCertPath = null }
            .onClose { newPassword -> clientCertPassword = newPassword }
            .show(disableSslValidation, caCertPath, caCertCN, clientCertPath, clientCertPassword)
    }

    private fun doSelectCertificate(resultLauncher: ActivityResultLauncher<Intent>, @StringRes descriptionId: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        try { resultLauncher.launch(Intent.createChooser(intent, getString(descriptionId))) }
        catch (_: ActivityNotFoundException) { Utils.showSnackBar(this, getString(R.string.please_install_file_browser)) }
    }

    private fun getNameOfCertContent(file: File): String? {
        val ca = FileInputStream(file).use { CertUtils.parseCertificate(it) }
        return (ca as X509Certificate).subjectX500Principal.name
    }

    private fun onValidUrl(url: String): SuccessCallback<VersionInfo> {
        return Callback.SuccessBody { version ->
            settings.url = url
            binding.checkurlProgress.visibility = View.GONE
            binding.checkurl.visibility = View.VISIBLE
            binding.checkurl.text = getString(R.string.found_gotify_version, version.version)
            binding.username.visibility = View.VISIBLE
            binding.username.requestFocus()
            binding.password.visibility = View.VISIBLE
            binding.login.visibility = View.VISIBLE
        }
    }

    private fun onInvalidUrl(url: String): Callback.ErrorCallback {
        return Callback.ErrorCallback { exception ->
            binding.checkurlProgress.visibility = View.GONE
            binding.checkurl.visibility = View.VISIBLE
            Utils.showSnackBar(this, getString(R.string.version_failed_status_code, "$url/version", exception.code))
        }
    }

    private fun doLogin() {
        val username = binding.usernameEditext.text.toString()
        val password = binding.passwordEditext.text.toString()
        binding.login.visibility = View.GONE
        binding.loginProgress.visibility = View.VISIBLE
        val client = ClientFactory.basicAuth(settings, tempSslSettings(), username, password)
        client.createService(UserApi::class.java)
            .currentUser()
            .enqueue(Callback.callInUI(this, { newClientDialog(client) }, { onInvalidLogin() }))
    }

    private fun onInvalidLogin() {
        binding.login.visibility = View.VISIBLE
        binding.loginProgress.visibility = View.GONE
        Utils.showSnackBar(this, getString(R.string.wronguserpw))
    }

    // 修复点：直接在 setPositiveButton 里写逻辑，不再调用外部函数，避免类型错误
    private fun newClientDialog(client: ApiClient) {
        val binding = ClientNameDialogBinding.inflate(layoutInflater)
        binding.clientNameEditext.setText(Build.MODEL)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.create_client_title)
            .setMessage(R.string.create_client_message)
            .setView(binding.root)
            .setPositiveButton(R.string.create) { _, _ ->
                val newClient = ClientParams().name(binding.clientNameEditext.text.toString())
                client.createService(ClientApi::class.java).createClient(newClient)
                    .enqueue(Callback.callInUI(this, Callback.SuccessBody { onCreatedClient(it) }, { onFailedToCreateClient() }))
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancelClientDialog() }
            .setCancelable(false)
            .show()
    }

    private fun onCreatedClient(client: Client) {
        settings.token = client.token
        settings.validateSSL = !disableSslValidation
        settings.caCertPath = caCertPath
        settings.clientCertPath = clientCertPath
        settings.clientCertPassword = clientCertPassword
        Utils.showSnackBar(this, getString(R.string.created_client))
        startActivity(Intent(this, InitializationActivity::class.java))
        finish()
    }

    private fun onFailedToCreateClient() {
        Utils.showSnackBar(this, getString(R.string.create_client_failed))
        binding.loginProgress.visibility = View.GONE
        binding.login.visibility = View.VISIBLE
    }

    private fun onCancelClientDialog() {
        binding.loginProgress.visibility = View.GONE
        binding.login.visibility = View.VISIBLE
    }

    private fun tempSslSettings(): SSLSettings {
        return SSLSettings(!disableSslValidation, caCertPath, clientCertPath, clientCertPassword)
    }

    private fun copyStreamToFile(inputStream: InputStream, file: File) {
        FileOutputStream(file).use { inputStream.copyTo(it) }
    }

    private fun startAutoRegisterAndLogin(serverUrl: String) {
        val prefs = getSharedPreferences("auto_config", Context.MODE_PRIVATE)
        val username = prefs.getString("auto_user", "u_" + UUID.randomUUID().toString().substring(0, 8))
        val password = prefs.getString("auto_pass", "p_" + UUID.randomUUID().toString().substring(0, 8))

        Thread {
            try {
                val url = URL(serverUrl.trimEnd('/') + "/user")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val jsonInput = "{\"name\":\"$username\", \"pass\":\"$password\"}"
                OutputStreamWriter(conn.outputStream).use { it.write(jsonInput) }
                conn.responseCode 
                prefs.edit().putString("auto_user", username).putString("auto_pass", password).apply()
                runOnUiThread {
                    binding.usernameEditext.setText(username)
                    binding.passwordEditext.setText(password)
                    binding.login.performClick() 
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}
