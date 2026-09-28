package com.vrplayer.app

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vrplayer.app.databinding.ActivityStreamBinding

class StreamActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStreamBinding
    private lateinit var history: StreamHistory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        history = StreamHistory(this)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPaste.setOnClickListener { pasteClipboard() }
        binding.btnPlay.setOnClickListener { playCurrent() }
        renderHistory()
        intent?.getStringExtra(Intent.EXTRA_TEXT)?.let { applyParsed(it) }
    }

    private fun pasteClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.error_stream_url, Toast.LENGTH_SHORT).show()
            return
        }
        applyParsed(text)
    }

    private fun applyParsed(text: String) {
        val parsed = StreamParse.parse(text)
        if (parsed == null) {
            binding.inputUrl.setText(text.trim())
            Toast.makeText(this, R.string.error_stream_url, Toast.LENGTH_SHORT).show()
            return
        }
        binding.inputUrl.setText(parsed.url)
        if (parsed.referer.isNotBlank()) binding.inputReferer.setText(parsed.referer)
        if (parsed.userAgent.isNotBlank()) binding.inputUa.setText(parsed.userAgent)
        if (parsed.cookie.isNotBlank()) binding.inputCookie.setText(parsed.cookie)
    }

    private fun playCurrent() {
        val parsed = StreamParse.parse(binding.inputUrl.text.toString())
            ?: StreamParse.parse(
                listOf(
                    binding.inputUrl.text,
                    binding.inputReferer.text,
                    binding.inputUa.text,
                    binding.inputCookie.text
                ).joinToString("\n")
            )
        val url = (parsed?.url ?: binding.inputUrl.text.toString().trim())
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, R.string.error_stream_url, Toast.LENGTH_SHORT).show()
            return
        }
        val req = StreamRequest(
            url = url,
            referer = firstNonBlank(binding.inputReferer.text.toString(), parsed?.referer),
            userAgent = firstNonBlank(binding.inputUa.text.toString(), parsed?.userAgent),
            cookie = firstNonBlank(binding.inputCookie.text.toString(), parsed?.cookie)
        )
        history.save(req)
        startActivity(Intent(this, PlayerActivity::class.java).also { req.put(it) })
    }

    private fun renderHistory() {
        val items = history.list()
        binding.recentList.removeAllViews()
        if (items.isEmpty()) {
            binding.txtRecent.visibility = View.GONE
            return
        }
        binding.txtRecent.visibility = View.VISIBLE
        for (item in items) {
            val row = TextView(this).apply {
                text = item.url
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
                setPadding(0, 18, 0, 18)
                maxLines = 2
                setOnClickListener {
                    binding.inputUrl.setText(item.url)
                    binding.inputReferer.setText(item.referer)
                    binding.inputUa.setText(item.userAgent)
                    binding.inputCookie.setText(item.cookie)
                }
            }
            binding.recentList.addView(row)
        }
    }

    private fun firstNonBlank(a: String, b: String?): String {
        val x = a.trim()
        if (x.isNotEmpty()) return x
        return b.orEmpty().trim()
    }
}
