package com.rittme.theofficer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rittme.theofficer.network.ApiService
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "comfort_player_prefs"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_API_KEY = "api_key"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val serverUrlField = findViewById<EditText>(R.id.setup_server_url)
        val apiKeyField = findViewById<EditText>(R.id.setup_api_key)
        val connectButton = findViewById<Button>(R.id.setup_connect_button)
        val statusText = findViewById<TextView>(R.id.setup_status)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_SERVER_URL, "")?.takeIf { it.isNotEmpty() }
            ?.let { serverUrlField.setText(it) }
        prefs.getString(KEY_API_KEY, "")?.takeIf { it.isNotEmpty() }
            ?.let { apiKeyField.setText(it) }

        connectButton.setOnClickListener {
            val url = serverUrlField.text.toString().trim()
            val key = apiKeyField.text.toString().trim()

            if (url.isEmpty()) {
                statusText.text = getString(R.string.setup_error_url_empty)
                statusText.visibility = View.VISIBLE
                return@setOnClickListener
            }

            connectButton.isEnabled = false
            statusText.text = getString(R.string.setup_connecting)
            statusText.setTextColor(0xFFAAAAAA.toInt())
            statusText.visibility = View.VISIBLE

            lifecycleScope.launch {
                try {
                    val service = ApiService.create(url, key)
                    val response = service.getShowInfo()
                    if (response.isSuccessful) {
                        prefs.edit()
                            .putString(KEY_SERVER_URL, url)
                            .putString(KEY_API_KEY, key)
                            .apply()
                        startActivity(Intent(this@SetupActivity, PlayerActivity::class.java))
                        finish()
                    } else {
                        statusText.text = getString(R.string.setup_error_auth, response.code())
                        statusText.setTextColor(0xFFFF5555.toInt())
                        connectButton.isEnabled = true
                    }
                } catch (e: Exception) {
                    statusText.text = getString(R.string.setup_error_unreachable)
                    statusText.setTextColor(0xFFFF5555.toInt())
                    connectButton.isEnabled = true
                }
            }
        }
    }
}
