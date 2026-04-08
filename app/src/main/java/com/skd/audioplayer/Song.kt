package com.skd.audioplayer

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val path: String,
    val albumArtUri: String,
    var isPlaying: Boolean = false
)
