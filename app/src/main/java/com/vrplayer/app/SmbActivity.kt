package com.vrplayer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vrplayer.app.databinding.ActivitySmbBinding
import com.vrplayer.app.smb.SmbAuth
import com.vrplayer.app.smb.SmbBrowser
import com.vrplayer.app.smb.SmbCredentialsStore
import com.vrplayer.app.smb.SmbDiscovery
import com.vrplayer.app.smb.SmbEntry
import com.vrplayer.app.smb.SmbException
import com.vrplayer.app.smb.SmbHost
import com.vrplayer.app.smb.SmbSavedAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmbActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySmbBinding
    private lateinit var store: SmbCredentialsStore
    private var current: SmbTarget? = null
    private var shares: List<String> = emptyList()
    private var pendingHost: SmbHost? = null
    private val hosts = mutableListOf<SmbHost>()
    private val adapter = EntryAdapter(
        onHost = { onHost(it) },
        onShare = { onShare(it) },
        onFile = { onEntry(it) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmbBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SmbCredentialsStore(this)
        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = adapter
        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnRefresh.setOnClickListener { scanHosts() }
        binding.btnManual.setOnClickListener { showManualHost() }
        binding.btnParent.setOnClickListener { goParent() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goParent()) finish()
            }
        })
        scanHosts()
    }

    private fun ensureScanPermission(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, needed) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        ActivityCompat.requestPermissions(this, arrayOf(needed), 41)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        scanHostsInternal()
    }

    private fun scanHosts() {
        if (!ensureScanPermission()) return
        scanHostsInternal()
    }

    private fun scanHostsInternal() {
        current = null
        shares = emptyList()
        pendingHost = null
        binding.listHeader.visibility = View.GONE
        binding.btnManual.visibility = View.VISIBLE
        binding.btnRefresh.visibility = View.VISIBLE
        binding.txtError.visibility = View.GONE
        binding.txtTitle.text = getString(R.string.smb_title)
        binding.txtStatus.text = getString(R.string.smb_scanning)
        binding.txtStatus.visibility = View.VISIBLE
        adapter.submitHosts(emptyList())
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SmbDiscovery.findHosts(this@SmbActivity) }
            }
            result.onSuccess { list ->
                hosts.clear()
                hosts.addAll(list)
                adapter.submitHosts(hosts)
                binding.txtStatus.text = if (list.isEmpty()) {
                    getString(R.string.smb_none)
                } else {
                    getString(R.string.smb_found, list.size)
                }
            }.onFailure {
                binding.txtStatus.text = getString(R.string.smb_none)
                showError(getString(R.string.error_smb_host))
            }
        }
    }

    private fun onHost(host: SmbHost) {
        val saved = store.load(host.address)
        if (saved != null && (saved.anonymous || saved.password.isNotEmpty() || saved.username.isNotEmpty())) {
            connectHost(host, saved.username, saved.password, saved.domain, remember = false)
            return
        }
        showLogin(host, saved)
    }

    private fun showLogin(host: SmbHost, saved: SmbSavedAccount?) {
        val view = layoutInflater.inflate(R.layout.dialog_smb_login, null)
        val txtHost = view.findViewById<TextView>(R.id.txtLoginHost)
        val inputUser = view.findViewById<EditText>(R.id.inputUser)
        val inputPass = view.findViewById<EditText>(R.id.inputPassword)
        val chkAnon = view.findViewById<CheckBox>(R.id.chkAnonymous)
        val chkRemember = view.findViewById<CheckBox>(R.id.chkRemember)
        txtHost.text = "${host.title}  ${host.address}"
        if (saved != null) {
            inputUser.setText(saved.username)
            inputPass.setText(saved.password)
            chkAnon.isChecked = saved.anonymous
        }
        fun applyAnon(on: Boolean) {
            inputUser.isEnabled = !on
            inputPass.isEnabled = !on
        }
        applyAnon(chkAnon.isChecked)
        chkAnon.setOnCheckedChangeListener { _, on -> applyAnon(on) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.smb_login_title, host.title))
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.smb_login) { _, _ ->
                val anon = chkAnon.isChecked
                val rawUser = if (anon) "" else inputUser.text.toString().trim()
                val pass = if (anon) "" else inputPass.text.toString()
                val (user, domain) = SmbAuth.splitUser(rawUser, "")
                connectHost(host, user, pass, domain, remember = chkRemember.isChecked)
            }
            .show()
    }

    private fun showManualHost() {
        val view = layoutInflater.inflate(R.layout.dialog_smb_manual, null)
        val input = view.findViewById<EditText>(R.id.inputHost)
        AlertDialog.Builder(this)
            .setTitle(R.string.smb_manual)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.smb_connect) { _, _ ->
                val host = input.text.toString().trim()
                if (host.isEmpty()) {
                    showError(getString(R.string.error_smb_host))
                    return@setPositiveButton
                }
                onHost(SmbHost(host, host, "手动"))
            }
            .show()
    }

    private fun connectHost(
        host: SmbHost,
        username: String,
        password: String,
        domain: String,
        remember: Boolean
    ) {
        binding.txtError.visibility = View.GONE
        binding.txtStatus.text = getString(R.string.connecting)
        binding.txtStatus.visibility = View.VISIBLE
        pendingHost = host
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    SmbBrowser.listShares(host.address, username, password, domain, host.name)
                }
            }
            result.onSuccess { names ->
                if (remember) {
                    store.save(
                        SmbSavedAccount(
                            host = host.address,
                            username = username,
                            password = password,
                            domain = domain,
                            anonymous = username.isBlank()
                        )
                    )
                }
                if (names.isEmpty()) {
                    binding.txtStatus.visibility = View.GONE
                    current = SmbTarget(host.address, "", "", username, password, domain)
                    showManualShare()
                    return@onSuccess
                }
                shares = names
                current = SmbTarget(host.address, "", "", username, password, domain)
                showShares()
            }.onFailure { e ->
                binding.txtStatus.visibility = View.GONE
                val code = (e as? SmbException)?.message
                val msg = when (code) {
                    "auth" -> getString(R.string.error_smb_auth)
                    "share" -> getString(R.string.error_smb_share)
                    else -> getString(R.string.error_smb_host)
                }
                showError(msg)
                if (code == "auth") {
                    showLogin(
                        host,
                        SmbSavedAccount(host.address, username, password, domain, username.isBlank())
                    )
                }
            }
        }
    }

    private fun showManualShare() {
        val input = EditText(this).apply {
            hint = getString(R.string.smb_share)
            setSingleLine()
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.smb_share_input)
            .setMessage(R.string.smb_no_share)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.smb_connect) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    showError(getString(R.string.error_smb_share))
                    return@setPositiveButton
                }
                shares = listOf(name)
                onShare(name)
            }
            .show()
    }

    private fun showShares() {
        val t = current ?: return
        binding.btnManual.visibility = View.GONE
        binding.btnRefresh.visibility = View.GONE
        binding.listHeader.visibility = View.VISIBLE
        binding.txtTitle.text = getString(R.string.smb_shares)
        binding.txtStatus.visibility = View.GONE
        binding.txtPath.text = "\\\\${t.host}"
        adapter.submitShares(shares)
    }

    private fun onShare(name: String) {
        val t = current ?: return
        browse(t.copy(share = name, path = ""))
    }

    private fun browse(target: SmbTarget) {
        binding.txtError.visibility = View.GONE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SmbBrowser.list(target) }
            }
            result.onSuccess { entries ->
                current = target
                binding.btnManual.visibility = View.GONE
                binding.btnRefresh.visibility = View.GONE
                binding.listHeader.visibility = View.VISIBLE
                binding.txtTitle.text = target.share
                binding.txtStatus.visibility = View.GONE
                binding.txtPath.text = "\\\\${target.host}\\${target.share}\\${target.path}"
                adapter.submitFiles(entries)
            }.onFailure { e ->
                val msg = when ((e as? SmbException)?.message) {
                    "auth" -> getString(R.string.error_smb_auth)
                    "share" -> getString(R.string.error_smb_share)
                    else -> getString(R.string.error_smb_host)
                }
                showError(msg)
            }
        }
    }

    private fun onEntry(entry: SmbEntry) {
        val t = current ?: return
        val next = t.child(entry.name)
        if (entry.directory) {
            browse(next)
        } else if (SmbBrowser.isVideo(entry.name)) {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                next.put(this)
            })
        } else {
            showError(getString(R.string.error_empty_file))
        }
    }

    private fun goParent(): Boolean {
        val t = current
        if (t == null) return false
        if (t.share.isNotEmpty() && t.path.isNotEmpty()) {
            browse(t.parent())
            return true
        }
        if (t.share.isNotEmpty()) {
            current = t.copy(share = "", path = "")
            showShares()
            return true
        }
        scanHosts()
        return true
    }

    private fun showError(msg: String) {
        binding.txtError.text = msg
        binding.txtError.visibility = View.VISIBLE
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private class EntryAdapter(
        private val onHost: (SmbHost) -> Unit,
        private val onShare: (String) -> Unit,
        private val onFile: (SmbEntry) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private enum class Kind { HOST, SHARE, FILE }
        private var kind = Kind.HOST
        private val hosts = mutableListOf<SmbHost>()
        private val shares = mutableListOf<String>()
        private val files = mutableListOf<SmbEntry>()

        fun submitHosts(list: List<SmbHost>) {
            kind = Kind.HOST
            hosts.clear()
            hosts.addAll(list)
            shares.clear()
            files.clear()
            notifyDataSetChanged()
        }

        fun submitShares(list: List<String>) {
            kind = Kind.SHARE
            shares.clear()
            shares.addAll(list)
            hosts.clear()
            files.clear()
            notifyDataSetChanged()
        }

        fun submitFiles(list: List<SmbEntry>) {
            kind = Kind.FILE
            files.clear()
            files.addAll(list)
            hosts.clear()
            shares.clear()
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = kind.ordinal

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == Kind.HOST.ordinal) {
                HostHolder(inf.inflate(R.layout.item_smb_host, parent, false))
            } else {
                FileHolder(inf.inflate(R.layout.item_smb_entry, parent, false))
            }
        }

        override fun getItemCount(): Int = when (kind) {
            Kind.HOST -> hosts.size
            Kind.SHARE -> shares.size
            Kind.FILE -> files.size
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is HostHolder -> {
                    val h = hosts[position]
                    holder.name.text = h.title
                    holder.addr.text = h.subtitle
                    holder.itemView.setOnClickListener { onHost(h) }
                }
                is FileHolder -> {
                    if (kind == Kind.SHARE) {
                        val name = shares[position]
                        holder.kind.text = "S"
                        holder.name.text = name
                        holder.itemView.setOnClickListener { onShare(name) }
                    } else {
                        val e = files[position]
                        holder.kind.text = when {
                            e.directory -> "D"
                            SmbBrowser.isVideo(e.name) -> "V"
                            else -> "F"
                        }
                        holder.name.text = e.name
                        holder.itemView.setOnClickListener { onFile(e) }
                    }
                }
            }
        }

        class HostHolder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.txtHostName)
            val addr: TextView = v.findViewById(R.id.txtHostAddr)
        }

        class FileHolder(v: View) : RecyclerView.ViewHolder(v) {
            val kind: TextView = v.findViewById(R.id.txtKind)
            val name: TextView = v.findViewById(R.id.txtName)
        }
    }
}
