package com.vrplayer.app.smb

data class SmbHost(
    val address: String,
    val name: String,
    val source: String
) {
    val title: String get() = name.ifBlank { address }
    val subtitle: String get() = address
}
